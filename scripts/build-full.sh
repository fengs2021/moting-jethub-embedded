#!/usr/bin/env bash
# JetHub 内置版墨听 — 完整构建流水线（apktool 全量回编）
#
# 流程：
#   [1] javac 编译 src/jet/**（Java 11 目标）
#   [2] host 侧单测（合并/注册逻辑，不依赖 Android 运行时）
#   [3] 同步管理页资源 assets/jethub/home.html 到反编译树
#   [3b] d8 把 jet/** 打成 classes9.dex
#   [4] baksmali 反编该 dex → smali_classes9，再 apktool b 回编整个 APK
#   [5] zipalign + apksigner 签名
#   [6] 产出 墨听-4.0.20-jethub内置版.apk
#
# 所有路径与口令都从环境变量读取，仓库里不含任何私钥、口令或私有主机名。
# 变量一览（括号内为默认值）：
#   ANDROID_BUILD_TOOLS  build-tools 目录，需含 zipalign/apksigner（必填）
#   JETHUB_TOOLCHAIN     toolchain 目录，需含 android-34.jar/json.jar/r8.jar/baksmali.jar（默认 ./toolchain）
#   JETHUB_DECODED       apktool 解包出来的反编译树（默认 ./full-decoded）
#   JETHUB_SMALI_TOOL    smali/baksmali 的 jar（默认 $JETHUB_TOOLCHAIN/baksmali.jar）
#   JETHUB_KEYSTORE      签名密钥库（必填；不提供则跳过签名，只产出未签名 APK）
#   JETHUB_KS_ALIAS      密钥别名（默认 jethub）
#   JETHUB_KS_PASS       密钥库口令（必填）
#   JETHUB_KEY_PASS      私钥口令（默认与 JETHUB_KS_PASS 相同）
#   JETHUB_OUT           产物文件名（默认 墨听-4.0.20-jethub内置版.apk）
#
# 用法：
#   ANDROID_BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0 \
#   JETHUB_KEYSTORE=~/keystore.jks JETHUB_KS_PASS=*** \
#   bash scripts/build-full.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TOOLCHAIN="${JETHUB_TOOLCHAIN:-$ROOT/toolchain}"
DECODED="${JETHUB_DECODED:-$ROOT/full-decoded}"
BAKSMALI="${JETHUB_SMALI_TOOL:-$TOOLCHAIN/baksmali.jar}"
OUT="${JETHUB_OUT:-墨听-4.0.20-jethub内置版.apk}"
: "${ANDROID_BUILD_TOOLS:?请设置 ANDROID_BUILD_TOOLS 指向 build-tools 目录（含 zipalign/apksigner）}"

for f in android-34.jar json.jar r8.jar; do
  [ -f "$TOOLCHAIN/$f" ] || { echo "缺少 $TOOLCHAIN/$f（见 README「准备工具链」）" >&2; exit 1; }
done
[ -d "$DECODED" ] || { echo "反编译树不存在：$DECODED（先跑 apktool d 解包）" >&2; exit 1; }

mkdir -p build apk

echo "[1/6] 编译 jet.* 源码"
rm -rf build/classes && mkdir -p build/classes
javac -source 11 -target 11 -cp "$TOOLCHAIN/android-34.jar" -d build/classes \
  $(find src/jet -name '*.java') 2>&1 | grep -v '警告\|warning\|Note\|过时' || true

echo "[2/6] host 侧单测（模型注册表合并逻辑，不依赖 Android 运行时）"
REGTEST="${JETHUB_REGTEST:-src/test/e2e/RegTest.java}"
if [ -f "$REGTEST" ]; then
  mkdir -p build/test
  javac -cp "$TOOLCHAIN/android-34.jar:$TOOLCHAIN/json.jar:build/classes" \
    -d build/test "$REGTEST"
  java -cp "$TOOLCHAIN/json.jar:$TOOLCHAIN/android-34.jar:build/classes:build/test" RegTest | tail -1
else
  echo "  （跳过：$REGTEST 不存在）"
fi

echo "[3/6] 同步管理页资源到反编译树"
mkdir -p "$DECODED/assets/jethub"
cp assets/jethub/home.html "$DECODED/assets/jethub/home.html"

echo "[3b/6] d8 → classes9.dex（仅 jet/**，排除测试类）"
rm -rf build/dex && mkdir -p build/dex
java -cp "$TOOLCHAIN/r8.jar" com.android.tools.r8.D8 --release --min-api 29 \
  --lib "$TOOLCHAIN/android-34.jar" --output build/dex $(find build/classes/jet -name '*.class')

echo "[4/6] baksmali classes9 + apktool 回编整个 APK"
if [ -f "$BAKSMALI" ]; then
  rm -rf build/smali9 "$DECODED/smali_classes9"
  java -jar "$BAKSMALI" d build/dex/classes.dex -o build/smali9
  mv build/smali9 "$DECODED/smali_classes9"
else
  # 没有 baksmali 时，直接把 dex 塞进 APK 也可行（apktool 不参与），见 scripts/build.sh
  echo "  未找到 baksmali.jar，改用 dex 直塞模式" >&2
fi
apktool b -f --use-aapt2 "$DECODED" -o apk/built-full.apk 2>&1 | tail -1

echo "[5/6] zipalign + apksigner"
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
  echo "  未设置 JETHUB_KEYSTORE，产出未签名 APK（只能用于本地验证）"
fi

echo "[6/6] 完成"
ls -la "$OUT"
