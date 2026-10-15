#!/usr/bin/env bash
# 公共依赖自举 —— 被 build-full.sh / build.sh / prepare.sh source。
#
# 三个脚本都需要「工具链 jar + Android SDK 片段」，而且必须判定一致：一旦某处的
# 清单或路径解析漂移，就会出现「A 脚本说依赖齐了、B 脚本说缺」这种最难查的问题。
# 所以这段逻辑只写一份。
#
# 不直接执行，只提供函数：
#   ensure_deps <jar 名单>
#
# 调用约定：调用者已 cd 到仓库根、且已 set -euo pipefail。

JETHUB_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${JETHUB_TOOLCHAIN:-$JETHUB_ROOT/toolchain}"
SDK="${JETHUB_SDK:-$JETHUB_ROOT/sdk}"
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

# ensure_deps "<jar 名单>" [want_upstream]
# 缺失就调 fetch-deps.sh 自举；自举后仍缺则报错退出。
# want_upstream=1 时把「原始墨听 APK」也算进依赖。
ensure_deps() {
  local want="$1" want_upstream="${2:-}" need=0 f

  for f in $want; do
    [ -f "$TOOLCHAIN/$f" ] || need=1
  done
  # build-tools 只认「调用者显式指定的」或「已自举好的」。
  # 注意不能在这里把结果定死：fetch-deps.sh 跑完之前 SDK 可能还不存在，
  # 自举之后必须重新解析一次，否则会拿着空值走到后面报错。
  if [ -z "${ANDROID_BUILD_TOOLS:-}" ] && [ ! -x "$SDK/build-tools/$BT_VERSION/zipalign" ]; then
    need=1
  fi
  if [ -n "$want_upstream" ] && [ -z "$(jethub_upstream_apk)" ]; then
    need=1
  fi

  if [ "$need" = 1 ] && [ -z "${JETHUB_SKIP_DEPS:-}" ]; then
    echo "[0] 拉取并校验外部依赖（工具链 + Android SDK 片段 + 原始墨听 APK）"
    bash "$JETHUB_ROOT/scripts/fetch-deps.sh"
  fi

  # 自举之后才解析 build-tools（此时 SDK 一定已就位）
  if [ -z "${ANDROID_BUILD_TOOLS:-}" ]; then
    ANDROID_BUILD_TOOLS="$SDK/build-tools/$BT_VERSION"
  fi
  export ANDROID_BUILD_TOOLS
  [ -x "$ANDROID_BUILD_TOOLS/zipalign" ] || {
    echo "build-tools 不可用：$ANDROID_BUILD_TOOLS" >&2
    echo "  跑 bash scripts/fetch-deps.sh，或用 ANDROID_BUILD_TOOLS 指定已有目录。" >&2
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
