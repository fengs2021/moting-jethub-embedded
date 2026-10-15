#!/usr/bin/env bash
# 从原始墨听 APK 准备构建输入。
#
# scripts/build.sh（轻量路线）需要两样东西，都来自原始 APK：
#   1. classes4.dex 的反汇编树 → apk/smali4/（App.smali 就在里面，补丁注入它）
#   2. 原始 APK 本身 → 由 JETHUB_UPSTREAM_APK 指向，脚本不复制
#
# 环境变量：
#   JETHUB_UPSTREAM_APK  原始墨听 APK（必填）
#   JETHUB_TOOLCHAIN     工具链目录（默认 ./toolchain，缺失自动自举）
#   JETHUB_SMALI_TOOL    baksmali.jar（默认 $JETHUB_TOOLCHAIN/baksmali.jar）
#
# 用法：
#   JETHUB_UPSTREAM_APK=/path/to/墨听.apk bash scripts/prepare.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

UPSTREAM="${JETHUB_UPSTREAM_APK:?请设置 JETHUB_UPSTREAM_APK 指向原始墨听 APK}"

. "$ROOT/scripts/lib-deps.sh"
ensure_deps "baksmali.jar"
BAKSMALI="${JETHUB_SMALI_TOOL:-$TOOLCHAIN/baksmali.jar}"

[ -f "$UPSTREAM" ] || { echo "原始 APK 不存在：$UPSTREAM" >&2; exit 1; }

echo "== 1/2 列出 APK 内的 dex =="
python3 - "$UPSTREAM" <<'EOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for n in z.namelist():
    if n.startswith('classes') and n.endswith('.dex'):
        print("  %-16s %10d 字节" % (n, z.getinfo(n).file_size))
EOF

echo "== 2/2 反汇编 classes4.dex → apk/smali4/ =="
mkdir -p apk
python3 - "$UPSTREAM" <<'EOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
with z.open('classes4.dex') as f, open('apk/classes4.dex', 'wb') as out:
    out.write(f.read())
print("  已取出 apk/classes4.dex")
EOF

rm -rf apk/smali4
java $JETHUB_JAVA_OPTS -Xmx3g -jar "$BAKSMALI" d apk/classes4.dex -o apk/smali4

APP="apk/smali4/io/legado/app/App.smali"
[ -f "$APP" ] || { echo "反汇编结果里没有 $APP —— classes4 的编号可能不是 4，请调整本脚本" >&2; exit 1; }
echo "  已生成 apk/smali4/（$(find apk/smali4 -name '*.smali' | wc -l) 个 smali 文件）"

echo
echo "准备完成。接下来："
echo "  JETHUB_UPSTREAM_APK=$UPSTREAM bash scripts/build.sh"
echo
echo "注意：scripts/build.sh 是增量调试用的轻量路线，它改不了 AndroidManifest、"
echo "也插不进菜单入口，产出的包没有 JetHub 入口。要出成品请用 scripts/build-full.sh。"
