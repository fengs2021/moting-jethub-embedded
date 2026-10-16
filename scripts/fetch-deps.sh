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
# 平台差异（Windows）：
#   Google 只为 build-tools 提供整包 zip，而官方仓库里 34.0.0 只有 linux 版
#   （build-tools_r34-linux.zip，里面是 ELF 二进制）。Windows 上即使下下来也跑不了，
#   所以本脚本在 Windows 上**跳过 build-tools 这一节**，改为要求复用本机已装的
#   Android SDK —— 由 lib-deps.sh 的 jethub_resolve_build_tools 从
#   ANDROID_SDK_ROOT / ANDROID_HOME 里挑版本最高的一个。
#   其余四步（工具链 jar、android-34.jar、上游 APK）与平台无关，照常执行。
#
# GitHub 在国内是间歇性可达的（同一个 URL 这次通、下次超时），所以 GitHub 的
# 资源按「直连 → 一组加速镜像」的顺序依次尝试，谁先成功用谁。所有来源都过
# sha256 校验，镜像即使被投毒也进不来。
#
# 环境变量：
#   JETHUB_TOOLCHAIN   工具链目录（默认 ./toolchain）
#   JETHUB_SDK         SDK 目录（默认 ./sdk）
#   JETHUB_GH_MIRRORS  GitHub 加速前缀列表（空格分隔，默认见下）
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
GH_MIRRORS="${JETHUB_GH_MIRRORS:-https://gh-proxy.com/ https://ghproxy.net/ https://ghfast.top/}"
CURL_OPTS="${JETHUB_CURL_OPTS:---retry 3 --retry-delay 2 --connect-timeout 20}"
# 「慢到没有希望」才放弃，而不是「超过固定时长」就放弃。
# 原来用的是 --max-time 600，那是个陷阱：上游 APK 有 68 MB，链路只有 42 KB/s 时
# 需要 27 分钟，600 秒一到 curl 就被杀，而且没有断点续传，下一轮从 0 重来 ——
# 在这种链路上永远下不完。
# 现在两层保护：
#   1. --speed-limit/--speed-time：连续 60 秒低于 1 KB/s 才判定该源死了；
#   2. 每个源只给 SRC_BUDGET 秒，超时就换下一个候选，靠 -C - 接着下。
# 第 2 条是为了让慢源及时让位给快源：实测同一条链路上直连 42 KB/s、
# gh-proxy 155 KB/s，只按速率判定的话慢源永远「没死」，会一直霸着不放。
SLOW_OPTS="${JETHUB_SLOW_OPTS:---speed-limit 1024 --speed-time 60}"
SRC_BUDGET="${JETHUB_SRC_BUDGET:-300}"
# 所有候选轮完算一轮；每轮都能累积进度，所以允许重来几轮。
# 真正的终止条件是「一整轮下来一个字节都没长」，所以这里给宽一点没关系：
# 假设所有源都只有 40 KB/s，68 MB 的上游包需要 4~5 轮才够。
MAX_PASSES="${JETHUB_MAX_PASSES:-10}"
BT_DIR="$SDK/build-tools/34.0.0"

# 判定当前是不是 Windows（Git Bash / MSYS / Cygwin）。
# uname 在 Git Bash 下是 MINGW64_NT-10.0-…，在 Cygwin 下是 CYGWIN_NT-…。
jethub_is_windows() {
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}

command -v curl >/dev/null || { echo "需要 curl" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "需要 sha256sum" >&2; exit 1; }
command -v unzip >/dev/null || { echo "需要 unzip" >&2; exit 1; }

mkdir -p "$TOOLCHAIN" "$SDK/build-tools" "$SDK/platforms"

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

# 各源的中文镜像改写规则。已实测这些镜像返回的文件与官方 sha256 逐字节一致。
mirror_variants() {
  case "$1" in
    https://dl.google.com/dl/android/maven2/*)
      printf '%s\n' "${1/dl.google.com\/dl\/android\/maven2/maven.aliyun.com\/repository\/google}"
      ;;
    https://dl.google.com/android/repository/*)
      printf '%s\n' "${1/dl.google.com\/android\/repository/mirrors.cloud.tencent.com\/AndroidSDK}"
      ;;
    https://repo1.maven.org/maven2/*)
      printf '%s\n' "${1/repo1.maven.org\/maven2/maven.aliyun.com\/repository\/public}"
      ;;
  esac
}

# 为一个资源生成候选 URL 列表：直连 → 各镜像。谁先下完且哈希对就用谁。
urls_for() {
  local m
  printf '%s\n' "$1"
  mirror_variants "$1"
  case "$1" in
    https://github.com/*)
      for m in $GH_MIRRORS; do printf '%s%s\n' "$m" "$1"; done
      ;;
  esac
}

# fetch <url> <目标路径> <期望 sha256>
# 逐个候选尝试；只要有一个能下完且哈希对就算成功。
#
# 断点续传的关键：$dest.part 只增不减。
# 天真写法是让每个候选都直接写 $dest.part 并带 -C -，但那是错的：如果某个源不认
# Range（回 200 而不是 206），curl 会从偏移 0 重写，把前面候选辛苦下的 13MB 直接截成
# 几百 KB —— 实测踩过，进度全丢，在弱网下永远下不完。
# 所以每个候选写自己的临时文件，下完只有「哈希对」或「比现有 $dest.part 大」才采纳，
# 保证 $dest.part 单调增长；换源续传时先把现有进度拷进临时文件，从那儿接着下。
fetch() {
  local url="$1" dest="$2" want="$3" got cand ok=0
  if [ -f "$dest" ] && [ "$(sha256_of "$dest")" = "$want" ]; then
    echo "  ✓ $(basename "$dest")  已就绪"
    return 0
  fi
  echo "  ↓ $(basename "$dest")"

  # best 必须从「已有的进度」起算，不能从 0 起算：上一轮构建中断会留下一个
  # 十几 MB 的 .part，若从 0 起算，本轮一个不认 Range 的源回吐 300 KB 也会被
  # 当成「更长」而采纳，把上次的进度截掉 —— 等于续传白做。实测复现过。
  local pass=0 base=0 tmp grew idx
  local best=0
  if [ -s "$dest.part" ]; then best="$(stat -c %s "$dest.part")"; fi
  while [ "$pass" -lt "$MAX_PASSES" ]; do
    pass=$((pass + 1))
    grew=0
    idx=0
    while IFS= read -r cand; do
      idx=$((idx + 1))
      tmp="$dest.part.try$idx"
      base=0
      # 接着现有进度下（拷贝而不是改名：$dest.part 在本次尝试失败前不能被破坏）
      if [ -s "$dest.part" ]; then
        cp -f "$dest.part" "$tmp"
        base="$(stat -c %s "$tmp")"
      else
        rm -f "$tmp"
      fi

      # shellcheck disable=SC2086
      if curl -fL $CURL_OPTS $SLOW_OPTS --max-time "$SRC_BUDGET" \
              -C - -o "$tmp" "$cand" 2>/dev/null; then
        got="$(sha256_of "$tmp")"
        if [ "$got" = "$want" ]; then
          mv -f "$tmp" "$dest.part"
          ok=1
          break
        fi
        echo "      候选哈希不符，换下一个来源：$(echo "$cand" | cut -d/ -f3)" >&2
        rm -f "$tmp"
        continue
      fi

      # 采纳「更长」的那份。源不认 Range 时 $tmp 可能比 $base 还短，那种就丢掉。
      local size
      size="$(stat -c %s "$tmp" 2>/dev/null || echo 0)"
      if [ "$size" -gt "$best" ]; then
        mv -f "$tmp" "$dest.part"
        best="$size"
      else
        rm -f "$tmp"
      fi
      if [ "$size" -gt "$base" ]; then
        grew=1
        echo "      该源已超时，进度 $((size / 1024 / 1024))MB，换下一个来源续传" >&2
      fi
    done < <(urls_for "$url")
    if [ "$ok" = 1 ]; then break; fi
    # 一整轮下来一点没长，说明所有源都不通，再轮也没用。
    if [ "$grew" = 0 ]; then break; fi
  done
  rm -f "$dest.part".try*

  if [ "$ok" != 1 ]; then
    rm -f "$dest.part"
    echo "  ✗ 下载失败：$url" >&2
    echo "      所有来源都不可用。可设 JETHUB_GH_MIRRORS 指定镜像，或手动放置该文件后重跑。" >&2
    return 1
  fi
  mv "$dest.part" "$dest"
  echo "  ✓ $(basename "$dest")  ${got:0:16}…"
}

echo "== 1/4 工具链 jar → $TOOLCHAIN =="
# 每行：文件名|URL|sha256|必需性。版本刻意钉死，换版本要同步更新这里的哈希。
# smali.jar 标 optional：只有轻量路线 scripts/build.sh 用它，全量路线不需要，
# 所以它下不下来都不该挡住出包。
fail=0
while IFS='|' read -r name url sha need; do
  [ -n "$name" ] || continue
  if ! fetch "$url" "$TOOLCHAIN/$name" "$sha"; then
    if [ "$need" = "optional" ]; then
      echo "      （$name 可选，跳过）" >&2
    else
      fail=1
    fi
  fi
done <<'MANIFEST'
apktool.jar|https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar|7956eb04194300ce0d0a84ad18771eebc94b89fb8d1ddcce8ea4c056818646f4|required
baksmali.jar|https://github.com/baksmali/smali/releases/download/v2.5.2/baksmali-2.5.2.jar|d3116248cce4f82ec5a31eb7f95ee75daff42ddf6eed0ab573973dc53fbad2e5|required
smali.jar|https://github.com/baksmali/smali/releases/download/v2.5.2/smali-2.5.2.jar|9544299578b16f771d8aa8eaefe0d3718ca03478c16f3c356f2fcf1366bfb116|optional
json.jar|https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar|3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed|required
r8.jar|https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.33/r8-8.2.33.jar|80c39a28bb66b64efc232d68ae554d59fe3838808738b4fe584884691cb1e5a3|required
MANIFEST
[ "$fail" = 0 ] || { echo "必需依赖缺失，无法继续。" >&2; exit 1; }

echo
echo "== 2/4 Android build-tools 34.0.0 → $BT_DIR =="
if jethub_is_windows; then
  # Windows 上没有可用的官方 build-tools 整包（Google 只提供 linux/mac 版），
  # 硬下下来是 ELF，跑不了。这里明确跳过，并把「必须复用系统 SDK」讲清楚，
  # 免得用户以为依赖装好了、到构建末尾才发现 d8 不存在。
  echo "  - 当前是 Windows（$(uname -s)），跳过 build-tools 下载。"
  echo "    Google 只提供 build-tools 的 linux/macOS 整包，Windows 上无法使用。"
  if [ -d "${ANDROID_SDK_ROOT:-}/build-tools" ] || [ -d "${ANDROID_HOME:-}/build-tools" ]; then
    echo "    已检测到系统 Android SDK，构建脚本会自动选用其 build-tools。"
  else
    echo "    ✗ 未检测到系统 Android SDK。请先安装，或设 ANDROID_SDK_ROOT/ANDROID_HOME。"
  fi
else
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
fi

echo
echo "== 3/4 Android 34 平台库 → $TOOLCHAIN/android-34.jar =="
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
echo "== 4/4 墨听原始 APK（改造对象）=="
# 上游墨听没有源码公开，只能拿它的发布包做反编译输入。这里从作者的公开
# 发布仓库下载，哈希与仓库自带的 .sha256 对照校验。
# 想用别的版本（比如更新的 4.0.20-r2），把 JETHUB_UPSTREAM_APK 指过去即可，
# 这一步就会跳过。注意菜单注入的锚点是跨版本稳定的（靠文案反查 lambda），
# 但不同版本的 dex 分包编号不同，脚本会自动适配。
UPSTREAM_DIR="$ROOT/upstream"
UPSTREAM_APK="${JETHUB_UPSTREAM_APK:-}"
UPSTREAM_TAG="${JETHUB_UPSTREAM_TAG:-v4.0.19-r1}"
UPSTREAM_ASSET="${JETHUB_UPSTREAM_ASSET:-MoTing-4.0.19-r1-40020-9b60b6c0e-arm64-v8a-R8-release.apk}"
UPSTREAM_SHA="${JETHUB_UPSTREAM_SHA:-1f2b7e9b47db0bba52f4d6588905cfe451dd37ccad7e76b6a8d01828ea4530af}"

if [ -n "$UPSTREAM_APK" ]; then
  echo "  ✓ 已指定 JETHUB_UPSTREAM_APK=$UPSTREAM_APK，跳过下载"
  if [ ! -f "$UPSTREAM_APK" ]; then
    echo "  ✗ 该文件不存在" >&2; exit 1
  fi
else
  mkdir -p "$UPSTREAM_DIR"
  dest="$UPSTREAM_DIR/$UPSTREAM_ASSET"
  if [ -f "$dest" ] && [ "$(sha256_of "$dest")" = "$UPSTREAM_SHA" ]; then
    echo "  ✓ 上游 APK 已就绪"
  else
    fetch "https://github.com/jwoo1982217-eng/MoTing-Releases/releases/download/$UPSTREAM_TAG/$UPSTREAM_ASSET" \
          "$dest" "$UPSTREAM_SHA" || {
      cat >&2 <<EOF
  ✗ 上游 APK 下载失败。
    也可以自己从 https://github.com/jwoo1982217-eng/MoTing-Releases/releases
    下载墨听安装包，然后：
        JETHUB_UPSTREAM_APK=/path/to/墨听.apk bash scripts/build-full.sh
EOF
      exit 1
    }
  fi
  UPSTREAM_APK="$dest"
fi
export JETHUB_UPSTREAM_APK="$UPSTREAM_APK"

echo
echo "依赖就绪。"
if jethub_is_windows; then
  echo "  ANDROID_BUILD_TOOLS=（由 lib-deps.sh 自动解析系统 SDK 的 build-tools）"
else
  echo "  ANDROID_BUILD_TOOLS=$BT_DIR"
fi
echo "  JETHUB_TOOLCHAIN=$TOOLCHAIN"
echo "  JETHUB_UPSTREAM_APK=$UPSTREAM_APK"
