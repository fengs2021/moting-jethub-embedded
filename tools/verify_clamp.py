#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_clamp.py —— 用小型 Dalvik 解释器执行 patch-overlap.py 注入的**真实 smali**，
与独立写的参考实现逐用例对照。

为什么需要它：
  smali.jar assemble 只检查**语法**，不检查**语义**。本补丁编写过程中多次把条件跳转
  方向写反（if-ge / if-gt / if-gez / if-gtz），每次都「编译通过」但逻辑是错的。
  所以必须真的把指令跑一遍。

验证内容：
  A. clamp() 的 smali 语义 vs 参考实现（10 个边界用例）+ 三条不变式
  B. 跨迭代不变式：模拟 ExactUnit 构造循环，确认相邻单元单调不重叠
  C. BODY_BLOCK 的调用协议（参数顺序、回写寄存器）

解释器注意：**不能**用 list.index(ins) 定位下一条指令（重复指令会取到错误的行）。
必须显式维护 pc 下标。
"""

import importlib.util
import os
import re
import sys

# 从本文件定位 patches/patch-overlap.py，使验证器可在任意工作目录下运行。
_HERE = os.path.dirname(os.path.abspath(__file__))
PO = os.path.join(os.path.dirname(_HERE), "patches", "patch-overlap.py")
spec = importlib.util.spec_from_file_location("po", PO)
po = importlib.util.module_from_spec(spec)
spec.loader.exec_module(po)

SKIP_PREFIX = (".locals", ".method", ".end method", ".annotation", ".end annotation",
               ".source", ".super", ".class", ".line", ".param", ".prologue",
               ".registers", "value =")


def extract_method(src, name):
    out, inside = [], False
    for ln in src.splitlines():
        if ln.startswith(".method") and name in ln:
            inside = True
        if inside:
            out.append(ln)
        if inside and ln.strip() == ".end method":
            return "\n".join(out)
    raise SystemExit("找不到方法 " + name)


CLAMP_SRC = extract_method(po.HELPER_SRC, "clamp")


class Part:
    def __init__(self, raw_start, raw_end, text):
        self.raw_start = raw_start
        self.raw_end = raw_end
        self.text = text

    def tuple(self):
        return (self.raw_start, self.raw_end, self.text)

    def __repr__(self):
        return "Part(%d,%d,%r)" % (self.raw_start, self.raw_end, self.text)


class Interp:
    """只覆盖本片段用到的指令子集；显式 pc 下标。"""

    def __init__(self, src, locals_count):
        self.pbase = locals_count
        self.prog = []          # [(op, rest)]
        self.labels = {}        # label -> pc
        for raw in src.splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if any(line.startswith(p) for p in SKIP_PREFIX):
                continue
            if line.startswith(":"):
                self.labels[line] = len(self.prog)
                continue
            m = re.match(r"^([a-z0-9/\-]+)\s*(.*)$", line)
            if not m:
                raise SystemExit("无法解析：" + line)
            self.prog.append((m.group(1), m.group(2).strip()))

    # ---- 寄存器
    def rid(self, tok):
        tok = tok.strip()
        m = re.fullmatch(r"p(\d+)", tok)
        if m:
            return self.pbase + int(m.group(1))
        m = re.fullmatch(r"v(\d+)", tok)
        if m:
            return int(m.group(1))
        raise ValueError("非法寄存器 " + tok)

    def run(self, args):
        self.regs = {}
        self.last_result = None
        for i, a in enumerate(args):
            self.regs[self.pbase + i] = a

        pc = 0
        steps = 0
        while pc < len(self.prog):
            steps += 1
            if steps > 100000:
                raise SystemExit("执行步数超限，疑似死循环")
            op, rest = self.prog[pc]
            finished, nxt = self.step(op, rest, pc)
            if finished:
                return self.ret
            pc = nxt
        raise SystemExit("执行到末尾却没有 return")

    def g(self, tok):
        return self.regs.get(self.rid(tok), 0)

    def s(self, tok, val):
        self.regs[self.rid(tok)] = val

    def step(self, op, rest, pc):
        nxt = pc + 1

        def jmp(label):
            return self.labels[label]

        if op in ("move", "move-object", "move/from16", "move-object/from16", "move/16"):
            a, b = [x.strip() for x in rest.split(",")]
            self.s(a, self.g(b))

        elif op in ("move-result", "move-result-object", "move-result-wide"):
            self.s(rest, self.last_result)

        elif op in ("const/4", "const/16", "const"):
            a, b = [x.strip() for x in rest.split(",", 1)]
            self.s(a, int(b, 0))

        elif op in ("const-string", "const-string/jumbo"):
            a, b = rest.split(",", 1)
            self.s(a.strip(), b.strip().strip('"'))

        elif op in ("add-int", "sub-int", "mul-int"):
            a, b, c = [x.strip() for x in rest.split(",")]
            x, y = self.g(b), self.g(c)
            self.s(a, {"add-int": x + y, "sub-int": x - y, "mul-int": x * y}[op])

        elif op in ("add-int/lit8", "sub-int/lit8"):
            a, b, c = [x.strip() for x in rest.split(",")]
            x, y = self.g(b), int(c, 0)
            self.s(a, x + y if op == "add-int/lit8" else x - y)

        elif op in ("if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez"):
            a, lbl = [x.strip() for x in rest.split(",")]
            v = self.g(a)
            if {"if-eqz": v == 0, "if-nez": v != 0, "if-ltz": v < 0,
                "if-gez": v >= 0, "if-gtz": v > 0, "if-lez": v <= 0}[op]:
                nxt = jmp(lbl)

        elif op in ("if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le"):
            a, b, lbl = [x.strip() for x in rest.split(",")]
            x, y = self.g(a), self.g(b)
            if {"if-eq": x == y, "if-ne": x != y, "if-lt": x < y,
                "if-ge": x >= y, "if-gt": x > y, "if-le": x <= y}[op]:
                nxt = jmp(lbl)

        elif op in ("goto", "goto/16", "goto/32"):
            nxt = jmp(rest.strip())

        elif op.startswith("invoke-"):
            inner = re.search(r"\{([^}]*)\}", rest).group(1)
            if ".." in inner:
                a, b = [t.strip() for t in inner.split("..")]
                toks = ["v%d" % i for i in range(self.rid(a), self.rid(b) + 1)]
            else:
                toks = [t.strip() for t in inner.split(",") if t.strip()]
            vals = [self.g(t) for t in toks]
            sig = rest.split("},", 1)[1].strip() if "}," in rest else rest.split("}", 1)[1].strip()
            self.last_result = self.do_invoke(vals, sig)

        elif op == "new-instance":
            a, typ = [x.strip() for x in rest.split(",", 1)]
            self.s(a, {"__new__": typ})

        elif op in ("return-object", "return"):
            self.ret = self.g(rest)
            return True, None

        elif op == "return-void":
            self.ret = None
            return True, None

        else:
            raise SystemExit("解释器不支持的指令：%s %s" % (op, rest))

        return False, nxt

    def do_invoke(self, vals, sig):
        if "String;->length()I" in sig:
            return len(vals[0])
        if "String;->substring(II)" in sig:
            return vals[0][vals[1]:vals[2]]
        if "Part;-><init>(IILjava/lang/String;)V" in sig:
            vals[0]["__init__"] = Part(vals[1], vals[2], vals[3])
            return None
        raise SystemExit("解释器不支持的调用：" + sig)


# ------------------------------------------------------------------ 参考实现
def reference(chapter_text, start, end, prev_end, text):
    """独立于 smali 写的语义（见 patch-overlap.py 文档字符串第四节）。

    收敛策略 = **裁剪**：start 前推到 previousEnd、end 保持不变，只丢弃被上一单元
    重复覆盖的前缀（与 App 自身 subtractQuoteRawRanges 的裁剪语义一致）。
    被完全吞掉时取 [previousEnd, previousEnd+1) 一字符占位，保证区间非空。

    退化情形（previousEnd 已到正文末尾，放不下任何非空区间）→ 原样返回，
    不伪造区间；此时不变式无法满足，属输入本身不可修。
    """
    n = len(chapter_text)
    if prev_end < 0:                 # 首单元
        return start, end, text
    if start >= prev_end:            # 无重叠
        return start, end, text
    if n <= 0:                       # 正文为空，无法重切片
        return start, end, text
    s = prev_end                     # 裁剪：start 前推到 previousEnd
    e = end                          # end 不变
    if e <= s:                       # 被上一单元完全吞掉 → 1 字符占位
        e = s + 1
    if e > n:                        # 上限截到正文长度
        e = n
    if e <= s:                       # 退化：无合法位置 → 原样返回
        return start, end, text
    return s, e, chapter_text[s:e]


def run_clamp(chapter_text, start, end, prev_end, text):
    it = Interp(CLAMP_SRC, 6)
    it.run([chapter_text, start, end, prev_end, text])
    out = it.ret
    if not isinstance(out, dict) or "__init__" not in out:
        raise SystemExit("clamp 未返回 Part，实际 %r" % (out,))
    return out["__init__"].tuple()


def chapter_text_of(n):
    return "".join(chr(0x4E00 + (i % 2000)) for i in range(n))


CASES = [
    # name, start, end, prev_end, text_len, note, allow_degenerate
    ("首单元 prev_end=-1", 0, 7, -1, 100, "章节标题行，必须原样通过", False),
    ("无重叠 start>=prev_end", 50, 60, 50, 100, "正常递增", False),
    ("重叠 1 字符（第251章形态）", 1594, 1600, 1595, 3408, "start 前推到 1595", False),
    ("重叠 10 字符（第250章形态）", 1961, 1980, 1971, 3521, "start 前推到 1971", False),
    ("被上一单元完全吞掉", 100, 105, 200, 400, "start 前推到 200", False),
    ("重叠且超正文长度", 90, 100, 95, 98, "上限截到 98", False),
    ("重叠后压空", 90, 100, 100, 100, "退化：prevEnd 已到末尾，原样返回", True),
    ("空区间（end==start）", 50, 50, 60, 100, "长度兜底为 1", False),
    ("正文长度为 1", 0, 5, 1, 1, "退化：正文太短，原样返回", True),
    ("prev_end 恰等于 start", 60, 70, 60, 100, "边界：不算重叠", False),
]

fails = 0
print("=" * 78)
print("A. clamp() 真实 smali 语义 vs 参考实现")
print("=" * 78)
for name, start, end, prev_end, tlen, note, degenerate in CASES:
    ct = chapter_text_of(tlen)
    text = ct[start:end] if 0 <= start <= end <= tlen else "ORIG"
    got = run_clamp(ct, start, end, prev_end, text)
    exp = reference(ct, start, end, prev_end, text)
    ok = got == exp
    if not ok:
        fails += 1
    print("%s %-24s start=%-5d end=%-5d prevEnd=%-5d -> (%d,%d,%r)"
          % ("[ok]  " if ok else "[FAIL]", name, start, end, prev_end, got[0], got[1], got[2]))
    if not ok:
        print("        期望 = (%d,%d,%r)" % exp)
    s, e, t = got
    # 退化用例：clamp 诚实放弃修复，此时不变式无法满足，不算失败
    if degenerate:
        if got != (start, end, text):
            print("        !! 退化用例应原样返回，实际被改动")
            fails += 1
        else:
            print("        (退化用例：诚实原样返回，不变式由原校验如实报错)")
        continue
    if prev_end >= 0 and s < prev_end:
        print("        !! 不变式违反：rawStart(%d) < previousEnd(%d)" % (s, prev_end))
        fails += 1
    if e <= s:
        print("        !! 不变式违反：rawEnd(%d) <= rawStart(%d)" % (e, s))
        fails += 1
    if 0 <= s and e <= len(ct) and t != ct[s:e]:
        print("        !! 不变式违反：text != chapterText.substring(start,end)")
        fails += 1

print()
print("=" * 78)
print("B. 跨迭代不变式：模拟 ExactUnit 构造循环")
print("=" * 78)


def simulate_loop(raw_units, chapter_text):
    prev = -1
    out = []
    for (s, e) in raw_units:
        t = chapter_text[s:e] if 0 <= s <= e <= len(chapter_text) else "ORIG"
        ns, ne, nt = run_clamp(chapter_text, s, e, prev, t)
        out.append((ns, ne, nt))
        prev = ne
    return out


LOOP_CASES = {
    "第251章形态（重叠1）": [(0, 7), (1594, 1600), (1600, 1610)],
    "第250章形态（重叠10）": [(0, 7), (1961, 1980), (1980, 2000)],
    "多重连续重叠": [(0, 7), (100, 120), (110, 130), (115, 140)],
    "嵌套吞掉": [(0, 7), (50, 200), (60, 70), (80, 90)],
    "标题行后紧跟重叠": [(0, 7), (3, 20)],
}
for name, raw in LOOP_CASES.items():
    ct = chapter_text_of(3521)
    res = simulate_loop(raw, ct)
    prev = -1
    bad = []
    for i, (s, e, t) in enumerate(res):
        if i > 0 and s < prev:
            bad.append("unit%d rawStart=%d < prevEnd=%d" % (i, s, prev))
        if e <= s:
            bad.append("unit%d 空区间 (%d,%d)" % (i, s, e))
        if t != ct[s:e]:
            bad.append("unit%d text 与切片不符" % i)
        prev = e
    if bad:
        fails += 1
        print("[FAIL] %s" % name)
        for b in bad:
            print("        " + b)
    else:
        print("[ok]   %-22s %d 个单元全部单调不重叠、切片一致" % (name, len(res)))

print()
print("=" * 78)
print("C. BODY_BLOCK 调用协议")
print("=" * 78)
checks = [
    ("move-object/from16 v41, v7", "p0 = chapterText"),
    ("move/from16 v42, v26", "p1 = rawStart"),
    ("move/from16 v43, v27", "p2 = rawEnd"),
    ("move/from16 v44, v46", "p3 = previousEnd"),
    ("move-object/from16 v45, v28", "p4 = text"),
    ("invoke-static/range {v41 .. v45}", "调用 clamp，5 个连续宽寄存器"),
    ("move-result-object v47", "取回 Part"),
    ("move-result v26", "回写 rawStart"),
    ("move-result v27", "回写 rawEnd"),
    ("move-result-object v28", "回写 text"),
    ("move/from16 v46, v27", "更新 previousEnd"),
]
for needle, why in checks:
    if needle in po.BODY_BLOCK:
        print("[ok]   %-38s %s" % (needle, why))
    else:
        fails += 1
        print("[FAIL] 缺少 %-32s %s" % (needle, why))

sig = "(Ljava/lang/String;IIILjava/lang/String;)"
if sig in po.HELPER_SRC:
    print("[ok]   clamp 签名 %s 与 v41..v45 顺序一致" % sig)
else:
    fails += 1
    print("[FAIL] clamp 签名与调用协议不符")

# v41..v47 必须都在宽寄存器区且互不重叠
used = set()
for m in re.finditer(r"\bv(4[1-7])\b", po.BODY_BLOCK):
    used.add(m.group(1))
print("[ok]   注入块使用宽寄存器：%s" % ", ".join(sorted(used)))
injected = po.BODY_BLOCK.split("# JetHub")[1]

# 注入块不得**写**任何窄寄存器（v0..v15）：v1..v11 是 materializeInitial 的实参。
# 只读窄寄存器（如 v7=chapterText、v26/v27/v28）是允许的。
clobbered = set()
for raw in injected.splitlines():
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    m = re.match(r"^(move[a-z/\-]*|const[a-z0-9/\-]*)\s+v(\d+)\s*,", line)
    if m and int(m.group(2)) < 16:
        clobbered.add(int(m.group(2)))
    m = re.match(r"^move-result(-object|-wide)?\s+v(\d+)\s*$", line)
    if m and int(m.group(2)) < 16:
        clobbered.add(int(m.group(2)))
if clobbered:
    print("[FAIL] 注入块写入了窄寄存器 v%s（会破坏 materializeInitial 的 v1..v11 实参）"
          % ", v".join(str(n) for n in sorted(clobbered)))
    fails += 1
else:
    print("[ok]   注入块未写入任何窄寄存器（v0..v15），v1..v11 实参安全")

print()
print("=" * 78)
print("结论：%s（失败 %d 项）" % ("全部通过" if fails == 0 else "存在失败", fails))
print("=" * 78)
sys.exit(1 if fails else 0)
