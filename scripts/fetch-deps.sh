#!/usr/bin/env bash
# 拉取构建所需的外部依赖：工具链 jar + Android SDK 片段。
#
# 仓库只含本项目自己的代码（src/、smali-glue/、patches/、assets/）。
# 编译器、反编译器、打包器、签名工具、android.jar 一律从公开源下载，
# 并按 sha256 逐字节校验——避免拿到被替换过的二进制。
#
# 落位：
#   toolchain/apktool.jar      反编译 / 回编 APK（内置 linux 版 aapt2，不依赖 SDK）
#   toolchain/baksmali.jar     dex → smali
#   toolchain/smali.jar        smali → dex
#   toolchain/json.jar         host 侧单测用的 org.json
#   toolchain/r8.jar           d8：把 .class 打成 dex
#   toolchain/android-34.jar   javac 编译用的 Android 34 平台库
#   sdk/build-tools/34.0.0/    d8 / zipalign / apksigner / aapt2
#   sdk/platforms/android-34/  平台库（android.jar 的原始出处）
#
# 环境变量：
#   JETHUB_TOOLCHAIN   工具链目录（默认 ./toolchain）
#   JETHUB_SDK         SDK 目录（默认 ./sdk）
#   JETHUB_GH_MIRROR   GitHub 加速前缀，如 https://ghfast.top/（默认空=直连）
#   JETHUB_CURL_OPTS   追加给 curl 的参数（默认 --retry 3）
#
# 用法：
#   bash scripts/fetch-deps.sh
#
# 若本机 HTTP_PROXY/HTTPS_PROXY 指向一个不通的代理，curl 会连不上，先 unset 掉。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TOOLCHAIN="${JETHUB_TOOLCHAIN:-$ROOT/toolchain}"
SDK="${JETHUB_SDK:-$ROOT/sdk}"
GH_MIRROR="${JETHUB_GH_MIRROR:-}"
CURL_OPTS="${JETHUB_CURL_OPTS:---retry 3 --connect-timeout 20}"
BT_DIR="$SDK/build-tools/34.0.0"

command -v curl >/dev/null || { echo "需要 curl" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "需要 sha256sum" >&2; exit 1; }
command -v unzip >/dev/null || { echo "需要 unzip" >&2; exit 1; }

mkdir -p "$TOOLCHAIN" "$SDK/build-tools" "$SDK/platforms"

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

# GitHub 走可选镜像；其余源直连。
url_for() {
  case "$1" in
    https://github.com/*) printf '%s%s' "$GH_MIRROR" "$1" ;;
    *)                    printf '%s' "$1" ;;
  esac
}

# fetch <url> <目标路径> <期望 sha256>
fetch() {
  local url="$1" dest="$2" want="$3" got
  if [ -f "$dest" ] && [ "$(sha256_of "$dest")" = "$want" ]; then
    echo "  ✓ $(basename "$dest")  已就绪"
    return 0
  fi
  echo "  ↓ $(basename "$dest")"
  # shellcheck disable=SC2086
  curl -fL $CURL_OPTS --max-time 1800 -o "$dest.part" "$(url_for "$url")" \
    || { rm -f "$dest.part"; echo "  ✗ 下载失败：$url" >&2; return 1; }
  got="$(sha256_of "$dest.part")"
  if [ "$got" != "$want" ]; then
    rm -f "$dest.part"
    echo "  ✗ $(basename "$dest") 校验失败" >&2
    echo "      期望 $want" >&2
    echo "      实际 $got" >&2
    return 1
  fi
  mv "$dest.part" "$dest"
  echo "  ✓ $(basename "$dest")  ${got:0:16}…"
}

echo "== 1/3 工具链 jar → $TOOLCHAIN =="
# 每行：文件名|URL|sha256。版本刻意钉死，换版本要同步更新这里的哈希。
while IFS='|' read -r name url sha; do
  [ -n "$name" ] || continue
  fetch "$url" "$TOOLCHAIN/$name" "$sha"
done <<'MANIFEST'
apktool.jar|https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar|7956eb04194300ce0d0a84ad18771eebc94b89fb8d1ddcce8ea4c056818646f4
baksmali.jar|https://github.com/baksmali/smali/releases/download/v2.5.2/baksmali-2.5.2.jar|d3116248cce4f82ec5a31eb7f95ee75daff42ddf6eed0ab573973dc53fbad2e5
smali.jar|https://github.com/baksmali/smali/releases/download/v2.5.2/smali-2.5.2.jar|9544299578b16f771d8aa8eaefe0d3718ca03478c16f3c356f2fcf1366bfb116
json.jar|https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar|3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
r8.jar|https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.33/r8-8.2.33.jar|80c39a28bb66b64efc232d68ae554d59fe3838808738b4fe584884691cb1e5a3
MANIFEST

echo
echo "== 2/3 Android build-tools 34.0.0 → $BT_DIR =="
BT_ZIP_URL="https://dl.google.com/android/repository/build-tools_r34-linux.zip"
BT_ZIP_SHA="e858c4b60069d0431051b225d384413b1643e1289b00a4825aed347f25bd510f"
if [ -x "$BT_DIR/zipalign" ] && [ -x "$BT_DIR/apksigner" ] && [ -x "$BT_DIR/d8" ]; then
  echo "  ✓ build-tools 已就绪"
else
  fetch "$BT_ZIP_URL" "$SDK/build-tools.zip" "$BT_ZIP_SHA"
  rm -rf "$SDK/.bt.tmp" && mkdir -p "$SDK/.bt.tmp"
  unzip -q "$SDK/build-tools.zip" -d "$SDK/.bt.tmp"
  # zip 里的顶层目录名是 AOSP 内部代号（android-14），统一改成版本号
  src="$(dirname "$(find "$SDK/.bt.tmp" -maxdepth 2 -name zipalign -type f | head -1)")"
  [ -n "$src" ] && [ -d "$src" ] || { echo "  ✗ build-tools zip 结构异常" >&2; exit 1; }
  rm -rf "$BT_DIR" && mv "$src" "$BT_DIR"
  rm -rf "$SDK/.bt.tmp"
  chmod +x "$BT_DIR"/d8 "$BT_DIR"/zipalign "$BT_DIR"/apksigner "$BT_DIR"/aapt2 2>/dev/null || true
  echo "  ✓ build-tools 就绪：$(ls "$BT_DIR" | tr '\n' ' ')"
fi

echo
echo "== 3/3 Android 34 平台库 → $TOOLCHAIN/android-34.jar =="
PLATFORM_URL="https://dl.google.com/android/repository/platform-34-ext7_r03.zip"
PLATFORM_SHA="16fdb74c55e59ae3ef52def135aec713508467bd56d7dabcd8c9be31fa8b20f3"
ANDROID_JAR_SHA="6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad"
if [ -f "$TOOLCHAIN/android-34.jar" ] && [ "$(sha256_of "$TOOLCHAIN/android-34.jar")" = "$ANDROID_JAR_SHA" ]; then
  echo "  ✓ android-34.jar 已就绪"
else
  fetch "$PLATFORM_URL" "$SDK/platforms/platform-34.zip" "$PLATFORM_SHA"
  rm -rf "$SDK/.pf.tmp" && mkdir -p "$SDK/.pf.tmp"
  unzip -q "$SDK/platforms/platform-34.zip" -d "$SDK/.pf.tmp"
  jar="$(find "$SDK/.pf.tmp" -name android.jar -type f | head -1)"
  [ -n "$jar" ] || { echo "  ✗ platform zip 里没有 android.jar" >&2; exit 1; }
  rm -rf "$SDK/platforms/android-34" && mv "$(dirname "$jar")" "$SDK/platforms/android-34"
  rm -rf "$SDK/.pf.tmp"
  cp "$SDK/platforms/android-34/android.jar" "$TOOLCHAIN/android-34.jar"
  echo "  ✓ android-34.jar  $(sha256_of "$TOOLCHAIN/android-34.jar" | cut -c1-16)…"
fi

echo
echo "依赖就绪。"
echo "  ANDROID_BUILD_TOOLS=$BT_DIR"
echo "  JETHUB_TOOLCHAIN=$TOOLCHAIN"
