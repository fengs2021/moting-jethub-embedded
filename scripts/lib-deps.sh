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

# ensure_deps "android-34.jar r8.jar ..."
# 缺失就调 fetch-deps.sh 自举；自举后仍缺则报错退出。
ensure_deps() {
  local want="$1" need=0 f

  for f in $want; do
    [ -f "$TOOLCHAIN/$f" ] || need=1
  done
  # build-tools 只认「调用者显式指定的」或「已自举好的」。
  # 注意不能在这里把结果定死：fetch-deps.sh 跑完之前 SDK 可能还不存在，
  # 自举之后必须重新解析一次，否则会拿着空值走到后面报错。
  if [ -z "${ANDROID_BUILD_TOOLS:-}" ] && [ ! -x "$SDK/build-tools/$BT_VERSION/zipalign" ]; then
    need=1
  fi

  if [ "$need" = 1 ] && [ -z "${JETHUB_SKIP_DEPS:-}" ]; then
    echo "[0] 拉取并校验外部依赖（工具链 + Android SDK 片段）"
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
}
