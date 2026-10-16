#!/usr/bin/env bash
# 在真实 Android ART 上执行 tests/art/ 里的 clamp 测试壳，替代人眼审查 smali。
#
# 为什么需要它：smali.jar assemble 只检查语法，不检查语义。本补丁在编写过程中
# 把条件跳转方向（if-ge/if-gt/if-ltz…）写反过多次，且每次都能「编译通过」。
# 只有把字节码真正丢给 ART 执行，才能确认行为正确。
#
# 流程：
#   [1] smali assemble 三个 smali（测试壳 + 辅助类 + Part 数据类）成一个 dex
#   [2] adb push 到设备
#   [3] dalvikvm 执行，打印每个用例的 (start, end, len, text)
#   [4] 宿主端 Python 与参考实现逐项比对，并检查不变式
#
# 依赖：adb 连着一台设备（真机或模拟器，x86_64/arm64 均可）、smali.jar、python3。
#
# 环境变量（括号内为默认值）：
#   JETHUB_TOOLCHAIN   toolchain 目录（默认 <仓库根>/toolchain）
#   JETHUB_ADB         adb 可执行文件（默认 adb）
#   JETHUB_DALVIKVM    设备上的 dalvikvm 路径（默认 /apex/com.android.art/bin/dalvikvm）
#   JETHUB_DEVICE      指定 adb 设备序列号（多设备时用）
#
# 用法：
#   bash tests/art/run-art-test.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TOOLCHAIN="${JETHUB_TOOLCHAIN:-$ROOT/toolchain}"
ADB="${JETHUB_ADB:-adb}"
DALVIKVM="${JETHUB_DALVIKVM:-/apex/com.android.art/bin/dalvikvm}"
DEV="${JETHUB_DEVICE:-}"
ADB_DEV=()
[ -n "$DEV" ] && ADB_DEV=(-s "$DEV")

SMALI="$TOOLCHAIN/smali.jar"
[ -f "$SMALI" ] || { echo "缺少 smali.jar：$SMALI（先跑 scripts/fetch-deps.sh）" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "[1/4] 编译测试壳"
# 辅助类不在这里维护副本，而是直接从 patch-overlap.py 的 HELPER_SRC 生成——
# 否则补丁改了、测试用的还是旧副本，会「测试通过但真机跑的是另一份代码」。
mkdir -p "$WORK/src"
cp "$HERE/TestClamp.smali" "$HERE/PlaybackTextUnitContract\$Part.smali" "$WORK/src/"
python3 - "$ROOT/patches/patch-overlap.py" "$WORK/src/JetHubCanonicalClamp.smali" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("po", sys.argv[1])
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
open(sys.argv[2], "w", encoding="utf-8").write(m.HELPER_SRC)
PY
java -jar "$SMALI" assemble -a 34 "$WORK/src" -o "$WORK/TestClamp.dex"

echo "[2/4] 推送到设备"
"$ADB" "${ADB_DEV[@]}" push "$WORK/TestClamp.dex" /data/local/tmp/TestClamp.dex >/dev/null

echo "[3/4] 在 ART 上执行"
"$ADB" "${ADB_DEV[@]}" shell \
  "cd /data/local/tmp && $DALVIKVM -cp TestClamp.dex TestClamp" > "$WORK/art_out.txt"
cat "$WORK/art_out.txt"

echo "[4/4] 宿主端比对（参考实现 + 不变式）"
python3 - "$WORK/art_out.txt" "$HERE/TestClamp.smali" <<'PY'
import re, sys

out = open(sys.argv[1], encoding="utf-8").read()

# 正文常量必须与测试壳里的字面量逐字一致，否则 text != CT[start:end] 会全错。
# 直接从 TestClamp.smali 里读出来，避免两处各写一份而漂移。
src = open(sys.argv[2], encoding="utf-8").read()
m = re.search(r'const-string v0, "([0-9A-Za-z]+)"', src)
if not m:
    print("[FAIL] 无法从 TestClamp.smali 解析正文常量"); sys.exit(1)
CT = m.group(1)

# 与 TestClamp.smali 里的 8 个用例保持一致：name -> (start, end, prev_end)
CASES = {
    "case1": (0, 7, -1),      # 首单元：previousEnd = -1，必须原样通过
    "case2": (50, 60, 50),    # 无重叠（start >= previousEnd）
    "case3": (94, 100, 95),   # 重叠 1 字符（第 251 章形态）
    "case4": (61, 80, 71),    # 重叠 10 字符（第 250 章形态）
    "case5": (100, 105, 150), # 被上一单元完全吞掉
    "case6": (190, 200, 195), # 重叠且超出正文长度
    "case7": (90, 100, 202),  # 退化：previousEnd 已到正文末尾
    "case8": (50, 50, 60),    # 空区间（end == start）
}
DEGENERATE = {"case7"}  # 补丁诚实放弃修复，豁免单调性检查


def ref(start, end, prev_end):
    """与 patch-overlap.py 第四节一致的裁剪语义。"""
    n = len(CT)
    if prev_end < 0 or start >= prev_end or n <= 0:
        return start, end
    s, e = prev_end, end
    if e <= s:
        e = s + 1
    if e > n:
        e = n
    if e <= s:
        return start, end
    return s, e


pat = re.compile(r"^(case\d) start=(-?\d+) end=(-?\d+) len=(\d+) text=(.*)$")
seen, fails = set(), 0
for line in out.splitlines():
    m = pat.match(line.strip())
    if not m:
        continue
    name, gs, ge, ln, text = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), m.group(5)
    start, end, prev = CASES[name]
    seen.add(name)
    exp = ref(start, end, prev)
    problems = []
    if (gs, ge) != exp:
        problems.append("与参考实现不符，期望 %r" % (exp,))
    if name not in DEGENERATE:
        if prev >= 0 and gs < prev:
            problems.append("rawStart(%d) < previousEnd(%d)" % (gs, prev))
        if ge <= gs:
            problems.append("区间为空")
        if gs < 0:
            problems.append("rawStart < 0")
        if ge > len(CT):
            problems.append("rawEnd > 正文长度")
    if text != CT[gs:ge]:
        problems.append("text != chapterText.substring(start,end)")
    if problems:
        fails += 1
        print("[FAIL] %s in=(%d,%d,prev=%d) art=(%d,%d,len=%d) — %s"
              % (name, start, end, prev, gs, ge, ln, "；".join(problems)))
    else:
        note = "（退化：原样返回）" if name in DEGENERATE else ""
        print("[ok]   %s in=(%d,%d,prev=%d) -> (%d,%d,len=%d)%s"
              % (name, start, end, prev, gs, ge, ln, note))

missing = set(CASES) - seen
if missing:
    fails += 1
    print("[FAIL] 设备未输出这些用例：%s" % ", ".join(sorted(missing)))

print()
print("结论：%s（失败 %d 项）" % ("全部通过" if fails == 0 else "存在失败", fails))
sys.exit(1 if fails else 0)
PY
