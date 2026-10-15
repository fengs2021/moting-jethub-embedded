#!/usr/bin/env bash
# JetHub 内置版墨听 — 轻量构建流水线（不跑 apktool 全量回编，直接替换 dex）
#
# ⚠️ 这条路线**出不了可用的成品**，不要用它发布。
#
# 它只做「换掉 classes4.dex + 追加 classes9.dex」，改不了 AndroidManifest、也加不了
# 新的 smali 包，因此**无法注册 JetHubActivity、无法往模型中心菜单插入口**。装出来的
# 包里网关在后台跑着，但用户没有任何入口能点进去。
#
# 它的真正用途：在已经用 scripts/build-full.sh 全量回编过、且已打好补丁的反编译树上
# 做增量调试 —— 改几行 Java 后只重编 dex，省掉几分钟的 apktool 回编时间。
# 要出成品请用 scripts/build-full.sh。
#
# 流程：
#   [1] javac 编译 src/jet/**
#   [2] d8 转 dex → classes9.dex
#   [3] 往 classes4 的 App.smali 注入 Boot.init（幂等）
#   [4] smali 回编 classes4 → classes4.new.dex
#   [5] 重建 APK：原包逐条复制，替换 classes4.dex，追加 classes9.dex，剔除原签名
#   [6] zipalign + apksigner 签名
#
# 环境变量：
#   JETHUB_UPSTREAM_APK  原始墨听 APK（必填；仓库不含，需自备）
#   JETHUB_SMALI_TREE    已反编译的 classes4 smali 树（默认 apk/smali4）
#   JETHUB_TOOLCHAIN     工具链目录（默认 ./toolchain）
#   JETHUB_SMALI_TOOL    smali.jar（默认 $JETHUB_TOOLCHAIN/smali.jar）
#   JETHUB_KEYSTORE      签名密钥库（不提供则产出未签名 APK）
#   JETHUB_KS_ALIAS / JETHUB_KS_PASS / JETHUB_KEY_PASS / JETHUB_OUT  同 build-full.sh
#   ANDROID_BUILD_TOOLS  build-tools 目录（默认自举到 ./sdk/build-tools/34.0.0）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SMALI_TREE="${JETHUB_SMALI_TREE:-$ROOT/apk/smali4}"
UPSTREAM="${JETHUB_UPSTREAM_APK:?请设置 JETHUB_UPSTREAM_APK 指向原始墨听 APK}"
OUT="${JETHUB_OUT:-墨听-4.0.20-jethub内置版.apk}"

. "$ROOT/scripts/lib-deps.sh"
ensure_deps "android-34.jar json.jar r8.jar smali.jar"
SMALI_JAR="${JETHUB_SMALI_TOOL:-$TOOLCHAIN/smali.jar}"

[ -f "$UPSTREAM" ] || { echo "原始 APK 不存在：$UPSTREAM" >&2; exit 1; }
[ -d "$SMALI_TREE" ] || {
  echo "smali 树不存在：$SMALI_TREE" >&2
  echo "  先用 scripts/prepare.sh 从上游 APK 里取 classes4 反编译出来。" >&2
  exit 1
}
mkdir -p build apk/dexout

echo "== 1/6 编译 Java =="
rm -rf build/classes && mkdir -p build/classes
javac $JETHUB_JAVAC_OPTS -encoding UTF-8 -source 11 -target 11 \
  -cp "$TOOLCHAIN/android-34.jar" -d build/classes \
  $(find src/jet -name '*.java')

echo "== 2/6 d8 转 dex =="
rm -f apk/dexout/classes9.dex
java $JETHUB_JAVA_OPTS -Xmx2g -cp "$TOOLCHAIN/r8.jar" com.android.tools.r8.D8 --release \
  --lib "$TOOLCHAIN/android-34.jar" --min-api 21 --output apk/dexout \
  $(find build/classes -name '*.class')
mv apk/dexout/classes.dex apk/dexout/classes9.dex

echo "== 3/6 注入 App.onCreate（如未注入）=="
# 锚点用正则：.registers N 与 .locals N 等价，具体写哪个随 apktool 参数而变。
# 注入指令只用 p0，不改变寄存器分配，所以两种写法都能直接插。
grep -q 'Ljet/android/Boot' "$SMALI_TREE/io/legado/app/App.smali" || python3 - "$SMALI_TREE" <<'EOF'
import re, sys
p = sys.argv[1] + '/io/legado/app/App.smali'
s = open(p, encoding='utf-8').read()
pat = re.compile(r'(\.method public onCreate\(\)V\n[ \t]+\.(?:registers|locals) \d+\n)')
found = pat.findall(s)
assert len(found) == 1, 'App.onCreate 锚点命中 %d 次（期望 1），请人工确认' % len(found)
s = pat.sub(lambda m: m.group(1) +
  "\n    # JETHUB: 启动内嵌供应商网关（loopback，失败静默）\n"
  "    invoke-static {p0}, Ljet/android/Boot;->init(Landroid/content/Context;)V\n", s, count=1)
open(p, 'w', encoding='utf-8').write(s)
print("injected")
EOF

echo "== 4/6 smali 回编 classes4 =="
java $JETHUB_JAVA_OPTS -Xmx3g -jar "$SMALI_JAR" a --api 29 -o apk/dexout/classes4.new.dex "$SMALI_TREE"

echo "== 5/6 重建 APK =="
python3 - "$UPSTREAM" <<'EOF'
import sys, zipfile
src = zipfile.ZipFile(sys.argv[1])
out = zipfile.ZipFile('apk/moting-patched-unsigned.apk', 'w')
for info in src.infolist():
    # 原签名与版本控制信息都要剔除，否则签名/校验会失败
    if info.filename.startswith('META-INF/') and info.filename.split('/')[-1].upper().endswith(('.RSA', '.SF', '.MF', '.DSA', '.EC')):
        continue
    if info.filename == 'META-INF/version-control-info.textproto':
        continue
    data = src.read(info.filename)
    if info.filename == 'classes4.dex':
        data = open('apk/dexout/classes4.new.dex', 'rb').read()
    zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    zi.compress_type = info.compress_type
    zi.external_attr = info.external_attr
    zi.create_system = info.create_system
    out.writestr(zi, data)
# 新增的 JetHub dex 追加到末尾（编号必须连续，这里假设原包已有 classes..classes8）
data = open('apk/dexout/classes9.dex', 'rb').read()
zi = zipfile.ZipInfo('classes9.dex', date_time=(2026, 10, 14, 0, 0, 0))
zi.compress_type = zipfile.ZIP_DEFLATED
zi.external_attr = 0o644 << 16
out.writestr(zi, data)
out.close(); src.close()
assert zipfile.ZipFile('apk/moting-patched-unsigned.apk').testzip() is None
print('rebuild ok')
EOF

echo "== 6/6 对齐 + 签名 =="
"$ANDROID_BUILD_TOOLS/zipalign" -p -f 4 apk/moting-patched-unsigned.apk apk/moting-patched-aligned.apk
if [ -n "${JETHUB_KEYSTORE:-}" ]; then
  : "${JETHUB_KS_PASS:?设置了 JETHUB_KEYSTORE 就必须给 JETHUB_KS_PASS}"
  "$ANDROID_BUILD_TOOLS/apksigner" sign \
    --ks "$JETHUB_KEYSTORE" --ks-key-alias "${JETHUB_KS_ALIAS:-jethub}" \
    --ks-pass "pass:$JETHUB_KS_PASS" --key-pass "pass:${JETHUB_KEY_PASS:-$JETHUB_KS_PASS}" \
    --out "$OUT" apk/moting-patched-aligned.apk
  "$ANDROID_BUILD_TOOLS/apksigner" verify "$OUT"
else
  cp apk/moting-patched-aligned.apk "$OUT"
  echo "  未设置 JETHUB_KEYSTORE，产出未签名 APK"
fi
echo "完成: $OUT"
