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
#   JETHUB_UPSTREAM_APK  原始墨听 APK（必填；仓库不含，需自备）
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
#   JETHUB_UPSTREAM_APK=/path/to/墨听.apk bash scripts/build-full.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TOOLCHAIN="${JETHUB_TOOLCHAIN:-$ROOT/toolchain}"
SDK="${JETHUB_SDK:-$ROOT/sdk}"
DECODED="${JETHUB_DECODED:-$ROOT/full-decoded}"
BAKSMALI="${JETHUB_SMALI_TOOL:-$TOOLCHAIN/baksmali.jar}"
OUT="${JETHUB_OUT:-墨听-4.0.20-jethub内置版.apk}"
UPSTREAM="${JETHUB_UPSTREAM_APK:-}"

# ---------------------------------------------------------------- 依赖自举
need_deps=0
for f in android-34.jar json.jar r8.jar baksmali.jar apktool.jar; do
  [ -f "$TOOLCHAIN/$f" ] || need_deps=1
done
if [ -z "${ANDROID_BUILD_TOOLS:-}" ]; then
  if [ -x "$SDK/build-tools/34.0.0/zipalign" ]; then
    ANDROID_BUILD_TOOLS="$SDK/build-tools/34.0.0"
  else
    need_deps=1
  fi
fi
export ANDROID_BUILD_TOOLS

if [ "$need_deps" = 1 ] && [ -z "${JETHUB_SKIP_DEPS:-}" ]; then
  echo "[0/9] 拉取并校验外部依赖（工具链 + Android SDK 片段）"
  bash scripts/fetch-deps.sh
  need_deps=0
fi
if [ "$need_deps" = 1 ]; then
  echo "缺少依赖且已设 JETHUB_SKIP_DEPS=1，请先跑 scripts/fetch-deps.sh" >&2
  exit 1
fi
: "${ANDROID_BUILD_TOOLS:?未能确定 build-tools 目录，请设置 ANDROID_BUILD_TOOLS}"
for f in android-34.jar json.jar r8.jar baksmali.jar apktool.jar; do
  [ -f "$TOOLCHAIN/$f" ] || { echo "缺少 $TOOLCHAIN/$f（跑 scripts/fetch-deps.sh）" >&2; exit 1; }
done

mkdir -p build apk

echo "[1/9] 编译 jet.* 源码"
rm -rf build/classes && mkdir -p build/classes
javac -source 11 -target 11 -cp "$TOOLCHAIN/android-34.jar" -d build/classes \
  $(find src/jet -name '*.java')

echo "[2/9] host 侧单测（模型注册表合并逻辑）"
REGTEST="${JETHUB_REGTEST:-src/test/e2e/RegTest.java}"
if [ -f "$REGTEST" ]; then
  mkdir -p build/test
  javac -cp "$TOOLCHAIN/android-34.jar:$TOOLCHAIN/json.jar:build/classes" \
    -d build/test "$REGTEST"
  java -cp "$TOOLCHAIN/json.jar:$TOOLCHAIN/android-34.jar:build/classes:build/test" RegTest | tail -1
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
  java -jar "$TOOLCHAIN/apktool.jar" d -f --use-aapt2 -o "$DECODED" "$UPSTREAM" 2>&1 | tail -2
fi
[ -f "$DECODED/AndroidManifest.xml" ] || { echo "$DECODED 不是有效的反编译树" >&2; exit 1; }

echo "[4/9] 同步管理页资源到反编译树"
mkdir -p "$DECODED/assets/jethub"
cp assets/jethub/home.html "$DECODED/assets/jethub/home.html"

echo "[5/9] d8 → dex，baksmali → smali_classes9"
rm -rf build/dex build/smali9 && mkdir -p build/dex
"$ANDROID_BUILD_TOOLS/d8" --release --min-api 29 \
  --lib "$TOOLCHAIN/android-34.jar" --output build/dex $(find build/classes/jet -name '*.class')
rm -rf "$DECODED/smali_classes9"
java -Xmx3g -jar "$BAKSMALI" d build/dex/classes.dex -o "$DECODED/smali_classes9"

echo "[6/9] 打补丁（幂等：已打过则跳过）"
# 顺序有意义：install_glue 先把胶水类放进 smali_classes10，
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
java -jar "$TOOLCHAIN/apktool.jar" b -f --use-aapt2 "$DECODED" -o apk/built-full.apk 2>&1 | tail -1

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
