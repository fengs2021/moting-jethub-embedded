# 修复：第 250/251 章后台预标注必报 `canonical units overlap`

> 本文记录一次确定性算法缺陷的定位过程与修复方案。相关代码：`patches/patch-overlap.py`、
> `tools/verify_clamp.py`、`tests/art/`。构建时由 `scripts/build-full.sh` 第 [6/9] 步自动应用。

## 一、现象

墨听后台预标注第 250 章「第七十一章 心意」与第 251 章「第七十二章 朋友」时，**每一轮**都在
【角色记忆读取】之后中断，永远走不到 `【QUOTE_CLASSIFY】` / `【本地初筛】`。App 界面报：

```
canonical units overlap or are out of order: sequence=81 start=1961 previousEnd=1971   （第 250 章）
canonical units overlap or are out of order: sequence=88 start=1594 previousEnd=1595   （第 251 章）
```

判据：5 轮重试全部复现同一断点，属**确定性算法缺陷**，与模型、网络、角色记忆无关。

日志里的决定性对照（成功章 vs 失败章）：

| 章节 | canonical | LONG_QUOTE blocks | crossParagraph | 最后到达的阶段 |
|---|---|---|---|---|
| 246 第六十七章 | 3378 | 48 | 1 | 文件产物 ✅ |
| 247 第六十八章 | 3488 | 71 | 0 | 文件产物 ✅ |
| 248 第六十九章 | 3568 | 70 | 1 | 文件产物 ✅ |
| 249 第七十章 | 3499 | 63 | 0 | 文件产物 ✅ |
| 252 第七十三章 | 3439 | 62 | 0 | READER_PROVISIONAL ✅ |
| **250 第七十一章** | 3521 | 53 | 1 | **角色记忆读取（断链）** ❌ |
| **251 第七十二章** | 3408 | **85** | 1 | **角色记忆读取（断链）** ❌ |

`crossParagraph=1` 也出现在成功的 246/248，**不是区分因子**；`unclosed=0 syntheticClose=0` 全程成立。

## 二、唯一抛错点

```
smali_classes4/io/legado/app/help/audiobook/scriptbrain/CanonicalChapterUnitStore.smali
方法 exactUnitRecords(String chapterText, List<ExactUnit>)   定义 :2378
```

循环内：

```
.line 1208  getRawStart() → v2
            if-lt v2, v15, :cond_10     # v15 = previousEnd，初值 -1
                                        # rawStart < previousEnd 即抛错
.line 1226  v15 = getRawEnd()           # 唯一的 previousEnd 更新点
```

同一循环还要求 `text == chapterText.substring(rawStart, rawEnd)`（`.line 1220-1221`）。
报错文案由 `CanonicalChapterUnitStore$$ExternalSyntheticBUOutline0;->m` 拼串后抛 `IllegalArgumentException`。

## 三、失败链与根因

```
RoleTimbrePrelabel.prepareTimbreV895PrelabelChapter$app        (:37366)
  → ScriptBrain.analyze$app                                    (:39017)
  → RuleRuntime.withoutLocalSpeakerGuessesForScriptBookPrelabel$app (:39025)
  → enrichTimbreLinesWithQuoteDialogueBlocks$app                (:39045)   ← 关键：会回滚
  → PrelabelPlaybackUnitNormalizer.normalize                    (:39130)
  → 构造 ExactUnit                                              (:39216-39290)
  → materializeInitial$app (:39311) → materializeInitialIn$app  (:6242)
  → exactUnitRecords (:6336) → 在 .line 1208 抛错
```

**根因**：`enrich` 末尾有三个自检旗标（`:35976-35982`）——`v2=invalidExact`、
`v3=invalidCoverage`、`v4=overlapPair`。任一成立即 `goto :goto_2b` 走**回滚分支**
（`:36253-36446`）：

```
.line 1502  new QuoteDialogueEnrichment(p3, emptyMap(), 0)
            ^^^ p3 = enrich 的入参 base lines
```

而正常路径（`:cond_33`）返回的是 `.line 1447` `sortedWith` 排序后、且做过
`subtractQuoteRawRanges` 引号区间减法的结果。**回滚路径返回的是未排序、未做引号区间减法的
原始 base lines**——旁白行的区间没有把内嵌对话区间挖掉，于是旁白行与其内部对白行的区间重叠，
一路穿透到 `exactUnitRecords` 的 `.line 1208`。

回滚只写 `Log.i` / `Log.w`（进 logcat），**不进预标注日志文件**，所以日志里 grep
`invalidExact` / `rollback` 全空——这也是最初误判「失败发生在模型调用阶段」的原因。

## 四、为什么补丁落在这里

| 候选落点 | 为什么不行 |
|---|---|
| `exactUnitRecords` 本身 | 改它等于放宽校验，问题被掩盖而不是解决 |
| `PrelabelPlaybackUnitNormalizer.normalize` | 不排序、不去重，且 `ScriptBrain$Analysis` 不带 `chapterText` 字段（只有 `chapterTextHash`），拿不到正文无法重切片 |
| `enrich` 回滚分支 | 该分支只拿到 base lines，没有 `chapterText` |
| **`prepareTimbreV895PrelabelChapter$app` 的 ExactUnit 构造循环** | ✅ 这是**最后一个同时握有 `chapterText`（`v7 = ChapterPayload.getChapterText()`，`:39164`）与最终偏移（`v26`/`v27`/`v28`）的地方**，也在 `materializeInitialIn` 五连断言之前 |

`materializeInitialIn` 对首单元有五连断言（必须是章节标题行：`rawStart=0` / `rawEnd=标题长度` /
`text=标题` / `partIndex=0` / `entrySeq=0`），因此补丁**不能删除或重排首单元**，只能收敛区间。

收敛策略保持两个不变式：

1. `rawStart >= previousEnd`
2. `text == chapterText.substring(rawStart, rawEnd)`

做法是**裁剪**：`start` 前推到 `previousEnd`、`end` 不变，只丢弃被上一单元重复覆盖的字符，
不丢正文内容，也不侵占下一单元的正文——与 App 自身 `subtractQuoteRawRanges` 的裁剪语义一致。

退化输入（`previousEnd` 已到正文末尾、放不下任何非空区间）**诚实原样返回，不伪造区间**：
这种输入本身不可修，交由原有校验如实报错。

## 五、实现要点

新增一个静态辅助类 `JetHubCanonicalClamp`（自己的 `.locals` 独立），由补丁脚本生成到
`smali_classes4/io/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp.smali`：

```java
static PlaybackTextUnitContract.Part clamp(String chapterText, int rawStart, int rawEnd,
                                           int previousEnd, String text)
```

调用方在 ExactUnit 构造循环内插入：

- 循环前 `const/16 v46, -0x1`（`previousEnd` 初值）
- 循环内取完 `v28=text` 后，用 `invoke-static/range {v41 .. v45}` 调 `clamp`，
  用 `invoke-virtual/range {v47 .. v47}` 取回 `getRawStart()` / `getRawEnd()` / `getText()`
  写回 `v26` / `v27` / `v28`，末尾 `move/from16 v46, v27`

**为什么必须下沉到辅助类**（本补丁最易踩的坑）：

1. `v1..v11` 是 `:39311 materializeInitial$app` 的实参，循环内**一个都不能碰**。
2. `v7=chapterText`、`v8=generation`、`v9=当前行`、`v11=输出表`、`v14/v15/v18/v19/v22`
   跨循环存活，也不能碰。
3. Dalvik 寄存器宽度硬约束：`if-*`（22t）、`invoke-*`（35c）、`const/4`（11n）的寄存器字段
   只有 4 位 → **只能寻址 v0..v15**；而 `const/16`（21s）、`move/from16`（22x）、
   `invoke-*/range`（3rc，16 位起始寄存器）可以直接用宽寄存器。
4. 结论：窄寄存器运算全部下沉到辅助类，调用方只用 `/range` 形式寻址 `v41..v47`
   （这些在循环区间 `:39196-39311` 内全部空闲）。

## 六、验证

补丁的正确性靠**三层机器验证**，不靠人眼——因为本补丁在编写过程中把条件跳转方向
（`if-ge`/`if-gt`/`if-ltz`）写反过多次，而 `smali.jar assemble` **只检查语法不检查语义**，
每次都能「编译通过」。

### 第 1 层：`tools/verify_clamp.py` —— 自写 Dalvik 解释器 + 参考实现对照

```
python3 tools/verify_clamp.py
```

三段：

- **A 段**：解释器执行 `clamp` 的真实 smali 语义 vs 独立写的 `reference()`，10 个边界用例
  （首单元 / 无重叠 / 重叠 1 字符 / 重叠 10 字符 / 被完全吞掉 / 重叠且超正文长度 /
  退化 / 空区间 / 多重连续重叠）
- **B 段**：跨迭代不变式模拟 ExactUnit 构造循环，5 个用例
- **C 段**：注入块的调用协议校验，12 项（含「注入块未写入任何窄寄存器 v0..v15」）

> 教训：本验证器最初把 `if-gez` / `if-lez` 的真值表写反了（`gez` 是 **greater-or-equal**，
> 不是「小于等于」），导致 10 个用例全部 FAIL，并让我按错的表去「修」补丁、越修越偏。
> 真值表以 `verify_clamp.py` 的 `Interp.step` 为唯一权威。

### 第 2 层：`tests/art/` —— 真实 Android ART 执行

```
bash tests/art/run-art-test.sh
```

把测试壳 `TestClamp.smali` + 辅助类 + `PlaybackTextUnitContract$Part.smali` 一起
`smali assemble` 成一个 dex，`adb push` 到设备，用 `dalvikvm` 真实执行。

**关键设计：测试壳里一条条件跳转都不写**——只打印 `caseN start=.. end=.. len=.. text=..`，
所有断言放回宿主端 Python（与参考实现逐项比对，并检查 5 条不变式）。原因：在测试壳里再写一遍
同样的判据，等于用同一个易错点去验证自己。

实测输出（emulator-5554 / redroid12_x86_64_only）：

```
case1 start=0   end=7   len=7  text=0123456      ← 首单元 prevEnd=-1
case2 start=50  end=60  len=10 text=opqrstuvwx   ← 无重叠
case3 start=95  end=100 len=5  text=XYZab        ← 重叠 1 字符（第 251 章形态）
case4 start=71  end=80  len=9  text=9ABCDEFGH    ← 重叠 10 字符（第 250 章形态）
case5 start=150 end=151 len=1  text=Q            ← 被完全吞掉
case6 start=195 end=200 len=5  text=9ABCD        ← 重叠且接近正文末尾
case7 start=90  end=100 len=10 text=STUVWXYZab   ← 退化：原样返回
case8 start=60  end=61  len=1  text=y            ← 空区间
结论：全部通过（失败 0 项）
```

### 第 3 层：编译级与全树回编

```
java -jar toolchain/smali.jar assemble -a 34 <补丁后的 RoleTimbrePrelabel.smali> -o /tmp/RTP.dex
apktool b full-decoded -o /tmp/moting-overlapfix.apk
```

补丁后 `RoleTimbrePrelabel.smali` 45748 → 45780 行（+985 字节），辅助类 2659 字节；
`smali.jar assemble` exit 0；`apktool b` exit 0，产物 70272004 字节，dex 编号连续、包名不变。

## 七、用法

```bash
python3 patches/patch-overlap.py full-decoded            # 打补丁
python3 patches/patch-overlap.py full-decoded --check     # 只校验不写
python3 tools/verify_clamp.py                             # 解释器级验证
bash tests/art/run-art-test.sh                            # 真机 ART 验证
```

补丁是**幂等**的（目标文件含 `MARKER` 则跳过），并且：

- 两个锚点各须命中**恰好 1 次**，否则 `sys.exit` 拒绝改文件（防止改错位置）
- 寄存器宽度自检（22t / 21t / 35c / 11n 不得寻址 v16+）
- 写后按**注入块内**计数自检（不是全文件计数——4.5 万行的目标文件里 `move-result-object v47`
  别处也有），并校验文件长度增量等于预期
- 全部校验通过后才落盘（失败时磁盘上不留半个补丁）
