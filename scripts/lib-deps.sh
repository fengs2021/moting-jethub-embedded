#!/usr/bin/env bash
# 公共依赖自举 —— 被 build-full.sh / build.sh / prepare.sh source。
#
# 三个脚本都需要「工具链 jar + Android SDK 片段」，而且必须判定一致：一旦某处的
# 清单或路径解析漂移，就会出现「A 脚本说依赖齐了、B 脚本说缺」这种最难查的问题。
# 所以这段逻辑只写一份。
#
# 不直接执行，只提供函数：
#   ensure_deps <jar 名单> [want_upstream]
#   jethub_next_dex_slot <反编译树根>
#   jethub_upstream_apk
#   jethub_resolve_build_tools      定位 build-tools 目录
#   jethub_check_build_tools <目录> 校验 d8/apksigner/zipalign 是否齐
#   jethub_d8 / jethub_apksigner / jethub_zipalign
#                                   跨平台调用三个 build-tools 组件
#
# 调用约定：调用者已 cd 到仓库根、且已 set -euo pipefail。

JETHUB_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${JETHUB_TOOLCHAIN:-$JETHUB_ROOT/toolchain}"
SDK="${JETHUB_SDK:-$JETHUB_ROOT/sdk}"
# 首选版本；找不到时由 jethub_resolve_build_tools 回退到系统 SDK 里的其它版本。
BT_VERSION="34.0.0"

# ---------------------------------------------------------------- 字符集兜底
# 源码与产物里全是中文（注释、菜单文案、日志）。javac 在没有 locale 的机器上
# 默认按 US-ASCII 解码源文件，中文注释会炸出成千上万条
# "unmappable character for encoding US-ASCII"，整个构建第一步就死。
# 这种机器很常见：容器、CI、`ssh host cmd` 这种非交互 shell（LANG 为空、
# LC_CTYPE=POSIX）。所以不指望环境正确，自己兜住。
#
# 两件事：
#   1. 把 LANG/LC_ALL 顶到一个真实存在的 UTF-8 locale —— 影响 shell 自身与
#      子进程（python3 的 stdout 编码就跟着它）。
#   2. 给 java/javac 加 JVM 级的 file.encoding —— JVM 不认 LANG 时会退化到
#      ASCII。注意 javac 与 java 的写法不同：javac 要把 JVM 参数用 -J 传进去，
#      直接写 -Dfile.encoding 会被当成 javac 自己的选项而报 invalid flag。
#      （javac 读源文件的真正开关是 -encoding UTF-8，各脚本里已显式带上。）
jethub_force_utf8() {
  local loc
  if [ -z "${LC_ALL:-}" ] || ! locale charmap 2>/dev/null | grep -qi 'utf-\?8'; then
    for loc in C.UTF-8 en_US.UTF-8 zh_CN.UTF-8 en_US.utf8; do
      if LC_ALL="$loc" locale charmap 2>/dev/null | grep -qi 'utf-\?8'; then
        export LANG="$loc" LC_ALL="$loc"
        break
      fi
    done
  fi
  export JETHUB_JAVA_OPTS="${JETHUB_JAVA_OPTS:--Dfile.encoding=UTF-8}"
  export JETHUB_JAVAC_OPTS="${JETHUB_JAVAC_OPTS:--J-Dfile.encoding=UTF-8}"
}
jethub_force_utf8

# jethub_next_dex_slot <反编译树根>
# 返回下一个可用的 dex 分包编号（smali → 1，smali_classesN → N）。
#
# 必须取「现有最大编号 +1」，不能写死。
# Android 运行时按 classes.dex、classes2.dex、classes3.dex… 的顺序扫描，**遇到第一个
# 缺失的编号就停下**，后面的 dex 一律不加载。上游墨听 4.0.20 有 8 个 dex，我们写进
# classes9/classes10 刚好连续，所以从没暴露过问题；但 4.0.19-r1 只有 7 个 dex，
# classes8 是空的，我们注入的类全在 classes9/10 里 —— 结果 Boot/JetHubActivity/
# 胶水类/注册器一个都加载不到，应用一启动就
#   NoClassDefFoundError: Failed resolution of: Ljet/android/Boot;
# 直接闪退。而且 apktool 回编、签名、产物结构检查全都正常，只有装到机器上才炸。
jethub_next_dex_slot() {
  local root="$1" max=1 d n
  for d in "$root"/smali "$root"/smali_classes*; do
    [ -d "$d" ] || continue
    n="$(basename "$d")"
    if [ "$n" = "smali" ]; then
      n=1
    else
      n="${n#smali_classes}"
    fi
    case "$n" in ''|*[!0-9]*) continue ;; esac
    [ "$n" -gt "$max" ] && max="$n"
  done
  echo $((max + 1))
}

# jethub_upstream_apk
# 解析「要改造的原始墨听 APK」的路径，按优先级：
#   1. 调用者显式给的 JETHUB_UPSTREAM_APK
#   2. 之前自举下载到 upstream/ 的那份
# 找不到就输出空串。
jethub_upstream_apk() {
  local p
  if [ -n "${JETHUB_UPSTREAM_APK:-}" ]; then
    printf '%s\n' "$JETHUB_UPSTREAM_APK"
    return 0
  fi
  for p in "$JETHUB_ROOT"/upstream/*.apk; do
    [ -f "$p" ] && { printf '%s\n' "$p"; return 0; }
  done
  return 0
}

# ------------------------------------------------------- 跨平台 build-tools 抽象
# build-tools 在不同平台上形态不同，脚本里不能直接拼 "$ANDROID_BUILD_TOOLS/d8"：
#
#   Linux/macOS : d8、apksigner、zipalign 都是可执行文件，直接调用。
#   Windows     : 只有 d8.bat 与 apksigner.bat —— **没有无扩展名的 d8 / apksigner**。
#                 Git Bash 里 `[ -x "$BT/d8" ]` 为假，直接调用报
#                   No such file or directory   （退出码 127）
#                 zipalign 反而有原生 zipalign.exe，可以直接调用。
#
# 绕行办法：build-tools 把这两个工具的实现也以 jar 形式放在 lib/ 下
# （lib/d8.jar、lib/apksigner.jar），用 java 直调主类即可，与平台无关：
#     java -cp <BT>/lib/d8.jar       com.android.tools.r8.D8
#     java -cp <BT>/lib/apksigner.jar com.android.apksigner.ApkSignerTool
# 仓库里 build.sh 早就用同样手法直调过 r8.jar 的 D8，这里只是把同一手法收敛成
# 一处，免得每个脚本各写一遍、各错一遍。
#
# 另外：Windows 上 java 的 path.separator 是 ';'，但 Git Bash(MSYS) 会把传进去的
# ':' 分隔类路径自动转成 ';'，所以调用方继续用 ':' 拼接即可，不要改成分号
# （分号在 MSYS 下不会被转换，反而会失效）。

# jethub_check_build_tools <build-tools 目录>
# d8 / apksigner / zipalign 三个组件都可用才返回 0。
jethub_check_build_tools() {
  local bt="$1"
  [ -d "$bt" ] || return 1
  [ -x "$bt/d8" ] || [ -f "$bt/lib/d8.jar" ] || return 1
  [ -x "$bt/apksigner" ] || [ -f "$bt/lib/apksigner.jar" ] || return 1
  [ -x "$bt/zipalign" ] || [ -x "$bt/zipalign.exe" ] || return 1
  return 0
}

# jethub_resolve_build_tools
# 解析 build-tools 目录，按优先级：
#   1. 调用者显式给的 ANDROID_BUILD_TOOLS
#   2. 仓库自举的 $SDK/build-tools/$BT_VERSION
#   3. 系统 Android SDK（ANDROID_SDK_ROOT / ANDROID_HOME）里版本最高的一个
#
# 第 3 条在 Windows 上是必须的：fetch-deps.sh 拉的是 Linux 版 build-tools
# （build-tools_r34-linux.zip，ELF 二进制），Windows 上根本跑不了；而本机通常已经
# 装好 Windows 版 Android SDK，直接用现成的即可，不必也无法自举。
# 找不到就输出空串。
jethub_resolve_build_tools() {
  local cand bt sys_sdk
  if [ -n "${ANDROID_BUILD_TOOLS:-}" ]; then
    printf '%s\n' "$ANDROID_BUILD_TOOLS"
    return 0
  fi
  cand="$SDK/build-tools/$BT_VERSION"
  if jethub_check_build_tools "$cand"; then
    printf '%s\n' "$cand"
    return 0
  fi
  for sys_sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -n "$sys_sdk" ] && [ -d "$sys_sdk/build-tools" ] || continue
    for bt in "$sys_sdk"/build-tools/*; do
      [ -d "$bt" ] || continue
      jethub_check_build_tools "$bt" || continue
      printf '%s\n' "$bt"
    done
  done | sort -V | tail -1
}

# jethub_d8 <d8 参数…>
jethub_d8() {
  if [ -x "$ANDROID_BUILD_TOOLS/d8" ]; then
    "$ANDROID_BUILD_TOOLS/d8" "$@"
  else
    java $JETHUB_JAVA_OPTS -cp "$ANDROID_BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 "$@"
  fi
}

# jethub_apksigner <apksigner 参数…>
jethub_apksigner() {
  if [ -x "$ANDROID_BUILD_TOOLS/apksigner" ]; then
    "$ANDROID_BUILD_TOOLS/apksigner" "$@"
  else
    java $JETHUB_JAVA_OPTS -cp "$ANDROID_BUILD_TOOLS/lib/apksigner.jar" \
      com.android.apksigner.ApkSignerTool "$@"
  fi
}

# jethub_zipalign <zipalign 参数…>
jethub_zipalign() {
  if [ -x "$ANDROID_BUILD_TOOLS/zipalign" ]; then
    "$ANDROID_BUILD_TOOLS/zipalign" "$@"
  elif [ -x "$ANDROID_BUILD_TOOLS/zipalign.exe" ]; then
    "$ANDROID_BUILD_TOOLS/zipalign.exe" "$@"
  else
    echo "找不到 zipalign：$ANDROID_BUILD_TOOLS" >&2
    return 1
  fi
}

# ensure_deps "<jar 名单>" [want_upstream]
# 缺失就调 fetch-deps.sh 自举；自举后仍缺则报错退出。
# want_upstream=1 时把「原始墨听 APK」也算进依赖。
ensure_deps() {
  local want="$1" want_upstream="${2:-}" need=0 f

  for f in $want; do
    [ -f "$TOOLCHAIN/$f" ] || need=1
  done
  # build-tools 只认「调用者显式指定的」或「能解析到的」。
  # 判定必须走 jethub_resolve_build_tools 而不是只看 $SDK/build-tools/$BT_VERSION：
  # Windows 上系统 SDK 里的 build-tools 是可用的，若这里只认自举目录，就会误判为
  # 缺依赖而去跑 fetch-deps.sh —— 而它拉的是 build-tools_r34-linux.zip（ELF），
  # Windows 上下载完照样不能用，白白浪费时间。
  if [ -z "$(jethub_resolve_build_tools)" ]; then
    need=1
  fi
  if [ -n "$want_upstream" ] && [ -z "$(jethub_upstream_apk)" ]; then
    need=1
  fi

  if [ "$need" = 1 ] && [ -z "${JETHUB_SKIP_DEPS:-}" ]; then
    echo "[0] 拉取并校验外部依赖（工具链 + Android SDK 片段 + 原始墨听 APK）"
    bash "$JETHUB_ROOT/scripts/fetch-deps.sh"
  fi

  # 自举之后才解析 build-tools：可能是自举下来的，也可能直接复用系统 SDK。
  if [ -z "${ANDROID_BUILD_TOOLS:-}" ]; then
    ANDROID_BUILD_TOOLS="$(jethub_resolve_build_tools)"
  fi
  export ANDROID_BUILD_TOOLS
  jethub_check_build_tools "$ANDROID_BUILD_TOOLS" || {
    echo "build-tools 不可用：${ANDROID_BUILD_TOOLS:-<未找到>}" >&2
    echo "  Linux/macOS：跑 bash scripts/fetch-deps.sh 自举。" >&2
    echo "  Windows：fetch-deps.sh 拉的是 Linux 版 build-tools，用不了；" >&2
    echo "           请装 Android SDK 的 build-tools，或直接指定目录：" >&2
    echo "           ANDROID_BUILD_TOOLS=/d/Android/android-sdk/build-tools/35.0.0 bash scripts/build-full.sh" >&2
    exit 1
  }

  local missing=""
  for f in $want; do
    [ -f "$TOOLCHAIN/$f" ] || missing="$missing $f"
  done
  if [ -n "$missing" ]; then
    echo "缺少工具链构件：$missing" >&2
    echo "  跑 bash scripts/fetch-deps.sh（GitHub 不通时设 JETHUB_GH_MIRRORS）。" >&2
    exit 1
  fi

  if [ -n "$want_upstream" ]; then
    local up
    up="$(jethub_upstream_apk)"
    if [ -z "$up" ]; then
      cat >&2 <<'EOF'
缺少原始墨听 APK。
  跑 bash scripts/fetch-deps.sh 会自动下载，或者自己从
  https://github.com/jwoo1982217-eng/MoTing-Releases/releases 下载后：
      JETHUB_UPSTREAM_APK=/path/to/墨听.apk bash scripts/build-full.sh
EOF
      exit 1
    fi
    JETHUB_UPSTREAM_APK="$up"
    export JETHUB_UPSTREAM_APK
  fi
}
