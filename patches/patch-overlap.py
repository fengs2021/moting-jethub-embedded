#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch-overlap.py —— 修复「第 250/251 章后台预标注必报 canonical units overlap」的 smali 补丁。

================================================================================
一、现象
================================================================================
墨听（com.jethub.moting）后台预标注第 250 章「第七十一章 心意」与第 251 章
「第七十二章 朋友」时，每一轮都在【角色记忆读取】之后中断，永远走不到
【QUOTE_CLASSIFY】/【本地初筛】。App 界面报：

    canonical units overlap or are out of order: sequence=81 start=1961 previousEnd=1971   （第 250 章）
    canonical units overlap or are out of order: sequence=88 start=1594 previousEnd=1595   （第 251 章）

5 轮重试全部复现，属确定性算法缺陷，与模型、网络、角色记忆无关。

================================================================================
二、唯一抛错点
================================================================================
smali_classes4/io/legado/app/help/audiobook/scriptbrain/CanonicalChapterUnitStore.smali
方法 exactUnitRecords(String chapterText, List<ExactUnit>)（定义 :2378），循环内：

    .line 1208  getRawStart() → v2
                if-lt v2, v15, :cond_10      # v15 = previousEnd，初值 -1
                                            # rawStart < previousEnd 即抛错
    .line 1226  v15 = getRawEnd()           # 唯一的 previousEnd 更新点

同一循环还要求 text == chapterText.substring(rawStart, rawEnd)（.line 1220-1221）。
报错文案由 CanonicalChapterUnitStore$$ExternalSyntheticBUOutline0;->m 拼串后抛
IllegalArgumentException。

================================================================================
三、失败链
================================================================================
RoleTimbrePrelabel.prepareTimbreV895PrelabelChapter$app（:37366）
  → ScriptBrain.analyze$app（:39017）
  → RuleRuntime.withoutLocalSpeakerGuessesForScriptBookPrelabel$app（:39025）
  → enrichTimbreLinesWithQuoteDialogueBlocks$app（:39045）  ← 关键：会回滚
  → PrelabelPlaybackUnitNormalizer.normalize（:39130）
  → 构造 ExactUnit（:39216-:39290）
  → materializeInitial$app（:39311） → materializeInitialIn$app（:6242）
  → exactUnitRecords（:6336）→ 在 .line 1208 抛错

enrich 末尾有三个自检旗标（:35976-:35982）：v2=invalidExact、v3=invalidCoverage、
v4=overlapPair。任一成立即 goto :goto_2b 走**回滚分支**（:36253-:36446）：

    .line 1502  new QuoteDialogueEnrichment(p3, emptyMap(), 0)
                ^^^ p3 = enrich 的入参 base lines

而正常路径（:cond_33）返回的是 .line 1447 sortedWith 排序后、且做过
subtractQuoteRawRanges 引号区间减法的结果。**回滚路径返回的是未排序、未做
引号区间减法的原始 base lines**——旁白行的区间没有把内嵌对话区间挖掉，于是
旁白行与其内部对白行的区间重叠，一路穿透到 exactUnitRecords 的 .line 1208。
（回滚只写 Log.i/Log.w，进 logcat 不进预标注日志文件，所以日志里 grep
invalidExact / rollback 全空。）

================================================================================
四、为什么改在这里
================================================================================
- ExactUnit 完全不可变（无 setter），无法就地改偏移。
- normalize 不排序、不去重，且 ScriptBrain$Analysis 不带 chapterText 字段
  （只有 chapterTextHash），故 normalize 内拿不到正文、无法重切片。
- prepareTimbreV895PrelabelChapter$app 的 ExactUnit 构造循环是**最后一个同时握有
  chapterText（v7 = ChapterPayload.getChapterText()，:39164）与最终偏移
  （v26/v27/v28）的地方**，也是 materializeInitialIn 五连断言（首单元必须是
  章节标题行：rawStart=0 / rawEnd=标题长 / text=标题 / partIndex=0 / entrySeq=0）
  之前最后可介入的点。
- 收敛策略保持两个不变式：rawStart >= previousEnd，且
  text == chapterText.substring(rawStart, rawEnd)。做法是**裁剪**（start 前推到
  previousEnd、end 不变），只丢弃被上一单元重复覆盖的字符，不丢正文内容，也
  不侵占下一单元的正文（与 App 自身 subtractQuoteRawRanges 的裁剪语义一致）。

================================================================================
五、寄存器可行性（这是本补丁最易踩的坑）
================================================================================
1) v1..v11 是 :39311 materializeInitial$app 的实参，循环内**一个都不能碰**。
2) v7=chapterText、v8=generation 串、v9=当前行、v11=输出表、v14/v15/v18/v19/v22
   跨循环存活，也不能碰。
3) Dalvik 寄存器宽度硬约束：if-*（22t）、invoke-*（35c）、const/4（11n）的寄存器
   字段只有 4 位 → 只能寻址 v0..v15。而 const/16（21s）、move/from16（22x）、
   invoke-*/range（3rc，16 位起始寄存器）可以直接用宽寄存器。
4) 结论：**把全部窄寄存器运算下沉到一个新增的静态辅助类**
   （JetHubCanonicalClamp，自己的 .locals 完全独立，不受调用方实参约束），
   调用方只用 invoke-static/range 传 v41..v45、用 invoke-virtual/range 取回结果。
   v41..v47 在循环区间（:39196-:39311）内全部空闲。

================================================================================
六、条件跳转语义（本补丁曾反复写反，务必对照）
================================================================================
    if-ltz  vA   → vA <  0 时跳
    if-gez  vA   → vA >= 0 时跳
    if-gtz  vA   → vA >  0 时跳
    if-lez  vA   → vA <= 0 时跳
    if-eqz  vA   → vA == 0 时跳
    if-nez  vA   → vA != 0 时跳
    if-lt/ge/gt/le/eq/ne vA, vB → 按 vA 与 vB 的关系跳

    注意：**if-gez / if-lez 极容易写反**（gez = greater-or-equal，不是 "小于等于"）。
    本补丁编写时曾在解释器与文档里同时写错这两个的真值表，导致「修」错方向两次。
    真值表以 verify_clamp.py 的 Interp.step 为唯一权威，并用 10 个边界用例回归。

================================================================================
七、用法
================================================================================
    python3 patches/patch-overlap.py full-decoded            # 打补丁
    python3 patches/patch-overlap.py full-decoded --check     # 只校验不写

    不传目录时默认 ./full-decoded（即 build-full.sh 解包出来的反编译树）。

失败一律 sys.exit（非 0），不做「打不上还静默通过」的事。
语义正确性由 verify_clamp.py 用解释器跑真实 smali 验证，不靠人眼。
"""

import os
import re
import sys

MARKER = "JetHubCanonicalClamp"

HELPER_REL = ("smali_classes4/io/legado/app/help/audiobook/scriptbrain/"
              "JetHubCanonicalClamp.smali")
TARGET_REL = ("smali_classes4/io/legado/app/help/audiobook/scriptbrain/"
              "RoleTimbrePrelabel.smali")

# ---------------------------------------------------------------- 辅助类源码
HELPER_SRC = '''\
.class public final Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;
.super Ljava/lang/Object;
.source "JetHubCanonicalClamp.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

# JetHub: canonical unit range monotonic clamp
# clamp(chapterText, rawStart, rawEnd, previousEnd, text) -> Part
#   previousEnd < 0            -> 首个单元，原样返回
#   rawStart >= previousEnd    -> 无重叠，原样返回
#   正文为空                    -> 原样返回（无法重切片）
#   否则**裁剪**：rawStart 前推到 previousEnd，rawEnd 不变。
#   只丢弃被上一单元重复覆盖的前缀，绝不侵占下一单元的正文
#   （与 App 自身 subtractQuoteRawRanges 的裁剪语义一致）。
#   若本单元被上一单元完全吞掉（rawEnd <= previousEnd）-> 取
#   [previousEnd, previousEnd+1) 一个字符占位，保证区间非空。
#   rawEnd 上限截到正文长度；若仍然放不下任何非空区间（previousEnd 已到
#   正文末尾），则**原样返回**，不伪造区间：这种输入本身不可修，
#   交由原有校验如实报错。
.method public static final clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;
    .locals 6

    move v0, p1

    move v1, p2

    move-object v2, p4

    # previousEnd < 0 -> 首个单元，原样
    if-ltz p3, :jethub_done

    # rawStart >= previousEnd -> 无重叠，原样
    if-ge p1, p3, :jethub_done

    invoke-virtual {p0}, Ljava/lang/String;->length()I

    move-result v4

    # 正文为空 -> 无法重切片
    if-lez v4, :jethub_done

    # 裁剪：start 前推到 previousEnd
    move v0, p3

    # rawEnd > previousEnd -> 部分重叠，end 保持不变
    if-gt p2, v0, :jethub_cap

    # rawEnd <= previousEnd -> 被完全吞掉，取 1 个字符占位
    add-int/lit8 v1, v0, 0x1

    :jethub_cap
    # end 上限截到正文长度
    if-le v1, v4, :jethub_room

    move v1, v4

    :jethub_room
    # 仍然放不下非空区间 -> 诚实回退原值
    if-gt v1, v0, :jethub_ok

    move v0, p1

    move v1, p2

    move-object v2, p4

    goto :jethub_done

    :jethub_ok
    invoke-virtual {p0, v0, v1}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v2

    :jethub_done
    new-instance v5, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    invoke-direct {v5, v0, v1, v2}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;-><init>(IILjava/lang/String;)V

    return-object v5
.end method
'''

# ---------------------------------------------------------------- 注入锚点
ANCHOR_INIT = """\
    .line 1661
    invoke-interface {v9}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;

    move-result-object v15

    :goto_27
"""

INIT_BLOCK = """\
    .line 1661
    invoke-interface {v9}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;

    move-result-object v15

    # JetHub: canonical unit range monotonic clamp (previousEnd init)
    const/16 v46, -0x1

    :goto_27
"""

ANCHOR_BODY = """\
    .line 403
    invoke-virtual {v9}, Lio/legado/app/help/audiobook/ScriptBrain$ScriptLine;->getText()Ljava/lang/String;

    move-result-object v28

    .line 404
"""

BODY_BLOCK = """\
    .line 403
    invoke-virtual {v9}, Lio/legado/app/help/audiobook/ScriptBrain$ScriptLine;->getText()Ljava/lang/String;

    move-result-object v28

    # JetHub: canonical unit range monotonic clamp
    move-object/from16 v41, v7

    move/from16 v42, v26

    move/from16 v43, v27

    move/from16 v44, v46

    move-object/from16 v45, v28

    invoke-static/range {v41 .. v45}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v47

    invoke-virtual/range {v47 .. v47}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->getRawStart()I

    move-result v26

    invoke-virtual/range {v47 .. v47}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->getRawEnd()I

    move-result v27

    invoke-virtual/range {v47 .. v47}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->getText()Ljava/lang/String;

    move-result-object v28

    move/from16 v46, v27

    .line 404
"""

# 注入块内必须出现的指令及其**块内**期望次数
# （不能按全文件计数：RoleTimbrePrelabel.smali 有 4.5 万行，v47 等寄存器在别处也被用过）
REQUIRED_IN_BODY = (
    ("move-object/from16 v41, v7", 1),
    ("move/from16 v42, v26", 1),
    ("move/from16 v43, v27", 1),
    ("move/from16 v44, v46", 1),
    ("move-object/from16 v45, v28", 1),
    ("invoke-static/range {v41 .. v45}", 1),
    ("move-result-object v47", 1),
    ("invoke-virtual/range {v47 .. v47}", 3),
    ("move-result v26", 1),
    ("move-result v27", 1),
    ("move-result-object v28", 1),
    ("move/from16 v46, v27", 1),
)

BLOCK_BEGIN = "    # JetHub: canonical unit range monotonic clamp\n"

# ------------------------------------------------- 寄存器宽度自检（4 位寻址）
NARROW_LIMIT = 16          # v0..v15
KNOWN_OPS = {
    "move", "move-object", "move/from16", "move-object/from16", "move/16",
    "move-result", "move-result-object", "move-result-wide",
    "const", "const/4", "const/16", "const/high16", "const-wide/16",
    "add-int", "sub-int", "mul-int", "div-int", "rem-int",
    "add-int/lit8", "sub-int/lit8", "add-int/lit16",
    "if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez",
    "if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le",
    "goto", "goto/16", "goto/32",
    "invoke-virtual", "invoke-super", "invoke-direct", "invoke-static",
    "invoke-interface",
    "invoke-virtual/range", "invoke-super/range", "invoke-direct/range",
    "invoke-static/range", "invoke-interface/range",
    "new-instance", "new-array", "check-cast", "instance-of",
    "sget-object", "iget-object", "iput-object", "iput",
    "return-void", "return-object", "return", "return-wide",
    "monitor-enter", "monitor-exit", "throw", "nop", "fill-array-data",
    "int-to-long", "long-to-int", "neg-int", "not-int",
}


def _regs(text):
    return [int(m) for m in re.findall(r"\bv(\d+)\b", text)]


def _width_check(src, label):
    """粗检：4 位寻址指令（22t/35c/11n）不得出现 > v15 的寄存器。"""
    bad = []
    for lineno, raw in enumerate(src.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("."):
            continue
        m = re.match(r"^([a-z0-9/\-]+)\s+(.*)$", line)
        if not m:
            continue
        op, rest = m.group(1), m.group(2)
        if op not in KNOWN_OPS:
            bad.append((lineno, line, "未知操作码"))
            continue
        if op.startswith("invoke-") and not op.endswith("/range"):
            for r in _regs(rest.split("}")[0] + "}"):
                if r >= NARROW_LIMIT:
                    bad.append((lineno, line, "35c 越界 v%d" % r))
        elif op in ("if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le"):
            for r in _regs(",".join(rest.split(",")[:2])):
                if r >= NARROW_LIMIT:
                    bad.append((lineno, line, "22t 越界 v%d" % r))
        elif op in ("if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez"):
            for r in _regs(rest.split(",")[0]):
                if r >= NARROW_LIMIT:
                    bad.append((lineno, line, "21t 越界 v%d" % r))
        elif op == "const/4":
            for r in _regs(rest.split(",")[0]):
                if r >= NARROW_LIMIT:
                    bad.append((lineno, line, "11n 越界 v%d" % r))
    if bad:
        print("!! 寄存器宽度自检失败（%s）：" % label)
        for lineno, line, why in bad:
            print("   %s:%d  %s   <- %s" % (label, lineno, line, why))
        sys.exit(1)
    print("   [ok] 寄存器宽度自检通过（%s）" % label)


def patch(root, check_only=False):
    helper_path = os.path.join(root, HELPER_REL)
    target_path = os.path.join(root, TARGET_REL)

    if not os.path.isfile(target_path):
        print("!! 找不到目标文件：%s" % target_path)
        sys.exit(1)

    with open(target_path, "r", encoding="utf-8") as fh:
        src = fh.read()

    # ---- 幂等
    if MARKER in src:
        print("== 已打过补丁（目标文件含 MARKER），跳过。")
        if not os.path.isfile(helper_path):
            print("!! 但辅助类缺失：%s" % helper_path)
            sys.exit(1)
        return

    # ---- 锚点唯一性
    n_init = src.count(ANCHOR_INIT)
    n_body = src.count(ANCHOR_BODY)
    print("   锚点命中：ANCHOR_INIT=%d  ANCHOR_BODY=%d" % (n_init, n_body))
    if n_init != 1:
        print("!! ANCHOR_INIT 命中 %d 次（应为 1），拒绝改文件。" % n_init)
        sys.exit(1)
    if n_body != 1:
        print("!! ANCHOR_BODY 命中 %d 次（应为 1），拒绝改文件。" % n_body)
        sys.exit(1)

    # ---- 注入块自检
    for need, _want in REQUIRED_IN_BODY:
        if need not in BODY_BLOCK:
            print("!! BODY_BLOCK 缺少必需指令：%s" % need)
            sys.exit(1)
    _width_check(HELPER_SRC, "JetHubCanonicalClamp.smali")
    _width_check(BODY_BLOCK, "RoleTimbrePrelabel.smali(injected)")

    if check_only:
        print("== --check：锚点与自检全部通过，未写入。")
        return

    # ---- 先在内存里算出结果并全部校验通过，再落盘（避免校验失败却留下半个补丁）
    out = src.replace(ANCHOR_INIT, INIT_BLOCK, 1)
    out = out.replace(ANCHOR_BODY, BODY_BLOCK, 1)

    # ---- 写后自检（**按注入块内**计数，不能按全文件：4.5 万行里 v41..v47 别处也在用）
    if out.count("const/16 v46, -0x1") != 1:
        print("!! 写后自检失败：const/16 v46, -0x1 不唯一")
        sys.exit(1)
    i = out.find(BLOCK_BEGIN)
    if i < 0:
        print("!! 写后自检失败：注入块起始标记缺失")
        sys.exit(1)
    j = out.find("    .line 404", i)
    if j < 0:
        print("!! 写后自检失败：注入块结束标记（.line 404）缺失")
        sys.exit(1)
    injected = out[i:j]
    for need, want in REQUIRED_IN_BODY:
        got = injected.count(need)
        if got != want:
            print("!! 写后自检失败：注入块内 %s 出现 %d 次（应为 %d）" % (need, got, want))
            sys.exit(1)
    delta = len(out) - len(src)
    expect = (len(INIT_BLOCK) - len(ANCHOR_INIT)) + (len(BODY_BLOCK) - len(ANCHOR_BODY))
    if delta != expect:
        print("!! 写后自检失败：文件长度变化 %d，期望 %d" % (delta, expect))
        sys.exit(1)

    # ---- 校验全通过，落盘
    os.makedirs(os.path.dirname(helper_path), exist_ok=True)
    with open(helper_path, "w", encoding="utf-8") as fh:
        fh.write(HELPER_SRC)
    print("   [ok] 写入辅助类：%s" % HELPER_REL)

    with open(target_path, "w", encoding="utf-8") as fh:
        fh.write(out)
    print("   [ok] 注入 %s（+%d 字节）" % (TARGET_REL, delta))
    print("== 补丁完成。")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    # 默认用仓库根下的反编译树（build-full.sh 的 $JETHUB_DECODED 默认值）。
    default_root = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "full-decoded")
    root = args[0] if args else default_root
    check_only = "--check" in sys.argv
    print("patch-overlap: root=%s check_only=%s" % (root, check_only))
    patch(root, check_only)


if __name__ == "__main__":
    main()
