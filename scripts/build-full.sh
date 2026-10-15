#!/usr/bin/env bash
# JetHub 内置版墨听 — 完整构建流水线（apktool 全量回编）
#
# 这是**唯一能产出可用成品**的路线：它会往 AndroidManifest 注册 JetHubActivity、
# 往模型中心菜单里插入入口、往 App.onCreate 注入网关启动——三者缺一，装出来的包
# 就没有入口（网关在跑，但用户点不进去）。
#
# 流程：
#   [1] javac 编译 src/jet/**（Java 11 目标）
#   [2] host 侧单测（模型注册表合并逻辑，不依赖 Android 运行时）
#   [3] 解包上游墨听 APK → $DECODED（已存在则跳过；JETHUB_REDECODE=1 强制重解）
#   [4] 同步管理页 assets/jethub/home.html 到反编译树
#   [5] d8 把 jet/** 打成 dex，baksmali 成 smali_classes9
#   [6] 打补丁（幂等）：胶水类 / manifest 注册 / 菜单注入 / Boot 注入 / 刷新补丁
#   [7] 改包名（可选，JETHUB_PACKAGE）
#   [8] apktool b 全量回编
#   [9] zipalign + apksigner
#
# 依赖（toolchain/ 与 sdk/）由 scripts/fetch-deps.sh 自动拉取并校验哈希。
#
# 环境变量（括号内为默认值）：
#   JETHUB_UPSTREAM_APK  原始墨听 APK（默认自动下载到 ./upstream/；想用别的版本就指过来）
#   ANDROID_BUILD_TOOLS  build-tools 目录（默认 ./sdk/build-tools/34.0.0）
#   JETHUB_TOOLCHAIN     toolchain 目录（默认 ./toolchain）
#   JETHUB_DECODED       apktool 解包出来的反编译树（默认 ./full-decoded）
#   JETHUB_SMALI_TOOL    smali/baksmali 的 jar（默认 $JETHUB_TOOLCHAIN/baksmali.jar）
#   JETHUB_KEYSTORE      签名密钥库（不提供则只产出未签名 APK，仅能本地验证）
#   JETHUB_KS_ALIAS      密钥别名（默认 jethub）
#   JETHUB_KS_PASS       密钥库口令（提供 keystore 时必填）
#   JETHUB_KEY_PASS      私钥口令（默认与 JETHUB_KS_PASS 相同）
#   JETHUB_PACKAGE       改成这个包名（默认不改，沿用上游包名）
#   JETHUB_OUT           产物文件名（默认 墨听-4.0.20-jethub内置版.apk）
#   JETHUB_SKIP_DEPS=1   跳过自动拉依赖（依赖已就绪时省时间）
#
# 用法：
#   bash scripts/build-full.sh
# 默认会自己把工具链与原始墨听 APK 都拉下来，所以裸跑就行。
# 想指定上游版本：JETHUB_UPSTREAM_APK=/path/to/墨听.apk bash scripts/build-full.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TOOLCHAIN="${JETHUB_TOOLCHAIN:-$ROOT/toolchain}"
SDK="${JETHUB_SDK:-$ROOT/sdk}"
DECODED="${JETHUB_DECODED:-$ROOT/full-decoded}"
BAKSMALI="${JETHUB_SMALI_TOOL:-$TOOLCHAIN/baksmali.jar}"
OUT="${JETHUB_OUT:-墨听-4.0.20-jethub内置版.apk}"

# ---------------------------------------------------------------- 依赖自举
. "$ROOT/scripts/lib-deps.sh"
# 第二个参数 = 1：把「原始墨听 APK」也算进依赖，缺了就自动下载。
# 但已经有反编译树（下面 [3/9] 会跳过解包）时就不需要它了，别白下一遍。
if [ -d "$DECODED" ] && [ -z "${JETHUB_REDECODE:-}" ]; then
  ensure_deps "android-34.jar json.jar r8.jar baksmali.jar apktool.jar"
else
  ensure_deps "android-34.jar json.jar r8.jar baksmali.jar apktool.jar" 1
fi
UPSTREAM="${JETHUB_UPSTREAM_APK:-}"

mkdir -p build apk

echo "[1/9] 编译 jet.* 源码"
rm -rf build/classes && mkdir -p build/classes
javac $JETHUB_JAVAC_OPTS -encoding UTF-8 -source 11 -target 11 \
  -cp "$TOOLCHAIN/android-34.jar" -d build/classes \
  $(find src/jet -name '*.java')

echo "[2/9] host 侧单测（模型注册表合并逻辑）"
REGTEST="${JETHUB_REGTEST:-src/test/e2e/RegTest.java}"
if [ -f "$REGTEST" ]; then
  mkdir -p build/test
  javac $JETHUB_JAVAC_OPTS -encoding UTF-8 \
    -cp "$TOOLCHAIN/android-34.jar:$TOOLCHAIN/json.jar:build/classes" \
    -d build/test "$REGTEST"
  java $JETHUB_JAVA_OPTS -cp "$TOOLCHAIN/json.jar:$TOOLCHAIN/android-34.jar:build/classes:build/test" RegTest | tail -1
else
  echo "  （跳过：$REGTEST 不存在）"
fi

echo "[3/9] 解包上游 APK → $DECODED"
if [ -d "$DECODED" ] && [ -z "${JETHUB_REDECODE:-}" ]; then
  echo "  已存在，跳过（要重解就设 JETHUB_REDECODE=1）"
else
  [ -n "$UPSTREAM" ] || { echo "反编译树不存在，且未设 JETHUB_UPSTREAM_APK" >&2; exit 1; }
  [ -f "$UPSTREAM" ] || { echo "原始 APK 不存在：$UPSTREAM" >&2; exit 1; }
  rm -rf "$DECODED"
  java $JETHUB_JAVA_OPTS -jar "$TOOLCHAIN/apktool.jar" d -f --use-aapt2 -o "$DECODED" "$UPSTREAM" 2>&1 | tail -2
fi
[ -f "$DECODED/AndroidManifest.xml" ] || { echo "$DECODED 不是有效的反编译树" >&2; exit 1; }

echo "[4/9] 同步管理页资源到反编译树"
mkdir -p "$DECODED/assets/jethub"
cp assets/jethub/home.html "$DECODED/assets/jethub/home.html"

echo "[5/9] d8 → dex，baksmali → smali（分包编号按现有最大编号 +1）"
# 编号不能写死：Android 运行时按 classes.dex、classes2.dex… 顺序扫描，遇到第一个
# 缺失的编号就停下，后面的 dex 全不加载。上游 4.0.20 有 8 个 dex（写进 9/10 刚好
# 连续），4.0.19-r1 只有 7 个 dex（写进 9/10 就跳过 classes8，注入的类全部加载不到，
# 应用启动即 NoClassDefFoundError 闪退）。
JET_SLOT="$(jethub_next_dex_slot "$DECODED")"
if [ "$JET_SLOT" = 1 ]; then
  JET_DIR="$DECODED/smali"
else
  JET_DIR="$DECODED/smali_classes$JET_SLOT"
fi
echo "  现有最大分包后 → $(basename "$JET_DIR")"
rm -rf build/dex build/smali9 && mkdir -p build/dex
"$ANDROID_BUILD_TOOLS/d8" --release --min-api 29 \
  --lib "$TOOLCHAIN/android-34.jar" --output build/dex $(find build/classes/jet -name '*.class')
rm -rf "$JET_DIR"
java $JETHUB_JAVA_OPTS -Xmx3g -jar "$BAKSMALI" d build/dex/classes.dex -o "$JET_DIR"

echo "[6/9] 打补丁（幂等：已打过则跳过）"
# 顺序有意义：install_glue 先把胶水类放进下一个空分包，
# patch_menu 才能在 MotingAiModelCenterScreenKt 里引用到它们。
python3 patches/patch-ui.py "$DECODED"
python3 patches/patch-refresh.py "$DECODED"

echo "[7/9] 改包名"
# 默认不改：改名后是另一个应用，不继承原版墨听的数据，属于分发决策而非构建细节。
if [ -n "${JETHUB_PACKAGE:-}" ]; then
  python3 patches/patch-package.py "$DECODED" "$JETHUB_PACKAGE"
else
  echo "  （跳过：未设 JETHUB_PACKAGE，产出包名沿用上游）"
fi

echo "[8/9] apktool 回编整个 APK"
java $JETHUB_JAVA_OPTS -jar "$TOOLCHAIN/apktool.jar" b -f --use-aapt2 "$DECODED" -o apk/built-full.apk 2>&1 | tail -1

echo "[9/9] zipalign + apksigner"
"$ANDROID_BUILD_TOOLS/zipalign" -p -f 4 apk/built-full.apk apk/aligned-full.apk
if [ -n "${JETHUB_KEYSTORE:-}" ]; then
  : "${JETHUB_KS_PASS:?设置了 JETHUB_KEYSTORE 就必须给 JETHUB_KS_PASS}"
  "$ANDROID_BUILD_TOOLS/apksigner" sign \
    --ks "$JETHUB_KEYSTORE" --ks-key-alias "${JETHUB_KS_ALIAS:-jethub}" \
    --ks-pass "pass:$JETHUB_KS_PASS" --key-pass "pass:${JETHUB_KEY_PASS:-$JETHUB_KS_PASS}" \
    --out "$OUT" apk/aligned-full.apk
  "$ANDROID_BUILD_TOOLS/apksigner" verify "$OUT"
else
  cp apk/aligned-full.apk "$OUT"
  echo "  未设置 JETHUB_KEYSTORE，产出未签名 APK（只能本地验证，不能安装分发）"
fi

echo
echo "完成：$OUT"
ls -la "$OUT"
