.class public LTestClamp;
.super Ljava/lang/Object;

# 在真实 Android ART 上执行 JetHubCanonicalClamp.clamp 的测试壳。
#
# 设计约束（重要）：
#   本文件里**一条条件跳转都不写**。断言全部放回宿主端 Python（见 run-art-test.sh）。
#   原因：本补丁在编写过程中把 if-ge/if-gt/if-ltz 的方向写反过多次，而测试壳里
#   再写一遍同样的判据，等于用同一个易错点去验证自己。壳只负责「如实打印」。
#
# 打印格式必须与 run-art-test.sh 的正则严格一致：
#   caseN start=<int> end=<int> len=<int> text=<str>

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

# show(Part, caseName) —— 只打印，不做任何判断
.method public static show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V
    .locals 5

    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v1, " start="

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->getRawStart()I

    move-result v1

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v1, " end="

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->getRawEnd()I

    move-result v1

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v1, " len="

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->getText()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v2}, Ljava/lang/String;->length()I

    move-result v3

    invoke-virtual {v0, v3}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v1, " text="

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {v1, v0}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V

    return-void
.end method

# main：8 个用例，正文是 202 字符的可打印 ASCII 串
#   BASE62 = "0123456789ABC...xyz"（62 字符）
#   CT     = BASE62 * 3 + "0123456789ABCDEF"（62*3 + 16 = 202）
# 宿主端用同一个 CT 做切片一致性检查。
.method public static main([Ljava/lang/String;)V
    .locals 8

    const-string v0, "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ABCDEF"

    # case1 首单元 prevEnd=-1：(0,7) 原样通过
    const/4 v1, 0x0

    const/16 v2, 0x7

    const/4 v3, -0x1

    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v4

    invoke-static {v0, v1, v2, v3, v4}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v5

    const-string v6, "case1"

    invoke-static {v5, v6}, LTestClamp;->show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V

    # case2 无重叠 start=50 >= prevEnd=50：(50,60) 原样
    const/16 v1, 0x32

    const/16 v2, 0x3c

    const/16 v3, 0x32

    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v4

    invoke-static {v0, v1, v2, v3, v4}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v5

    const-string v6, "case2"

    invoke-static {v5, v6}, LTestClamp;->show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V

    # case3 重叠 1 字符（第 251 章形态）start=94 end=100 prevEnd=95：(95,100)
    const/16 v1, 0x5e

    const/16 v2, 0x64

    const/16 v3, 0x5f

    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v4

    invoke-static {v0, v1, v2, v3, v4}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v5

    const-string v6, "case3"

    invoke-static {v5, v6}, LTestClamp;->show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V

    # case4 重叠 10 字符（第 250 章形态）start=61 end=80 prevEnd=71：(71,80)
    const/16 v1, 0x3d

    const/16 v2, 0x50

    const/16 v3, 0x47

    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v4

    invoke-static {v0, v1, v2, v3, v4}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v5

    const-string v6, "case4"

    invoke-static {v5, v6}, LTestClamp;->show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V

    # case5 被上一单元完全吞掉 start=100 end=105 prevEnd=150：(150,151) 一字符占位
    const/16 v1, 0x64

    const/16 v2, 0x69

    const/16 v3, 0x96

    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v4

    invoke-static {v0, v1, v2, v3, v4}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v5

    const-string v6, "case5"

    invoke-static {v5, v6}, LTestClamp;->show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V

    # case6 重叠且接近正文末尾 start=190 end=200 prevEnd=195：(195,200)
    const/16 v1, 0xbe

    const/16 v2, 0xc8

    const/16 v3, 0xc3

    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v4

    invoke-static {v0, v1, v2, v3, v4}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v5

    const-string v6, "case6"

    invoke-static {v5, v6}, LTestClamp;->show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V

    # case7 退化：prevEnd 已到正文末尾（=202）→ 原样返回 (90,100)
    const/16 v1, 0x5a

    const/16 v2, 0x64

    invoke-virtual {v0}, Ljava/lang/String;->length()I

    move-result v3

    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v4

    invoke-static {v0, v1, v2, v3, v4}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v5

    const-string v6, "case7"

    invoke-static {v5, v6}, LTestClamp;->show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V

    # case8 空区间 start=50 end=50 prevEnd=60：(60,61) 长度兜底为 1
    const/16 v1, 0x32

    const/16 v2, 0x32

    const/16 v3, 0x3c

    const-string v4, ""

    invoke-static {v0, v1, v2, v3, v4}, Lio/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp;->clamp(Ljava/lang/String;IIILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object v5

    const-string v6, "case8"

    invoke-static {v5, v6}, LTestClamp;->show(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;Ljava/lang/String;)V

    return-void
.end method
