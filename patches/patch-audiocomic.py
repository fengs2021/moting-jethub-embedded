#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""让音频漫画「图片未就绪」不再抛未捕获异常，改为走错误回调。

用法
----
    python3 patches/patch-audiocomic.py <反编译树根目录> [--check]

其中 <反编译树根目录> 是 apktool 解包出来的目录（含 smali_classes*/）。
默认读环境变量 JETHUB_DECODED，未设置时用 ./full-decoded。
--check 只校验不改写。

为什么需要这个补丁
-----------------
崩溃栈（用户实机，Android 16 / 华为 SGT-AL10）：

    java.lang.IllegalArgumentException: audio comic image is not stable:
      /storage/emulated/0/Android/data/com.jethub.moting/files/audio-comic/
      images/剑卒过河/c0049_第48章 计划/raw/.../xxx.jpg
        at okio.CipherSink$$ExternalSyntheticBUOutline0.m(SourceFile:0)
        at ...AudioComicImageDeliveryFastLane.deliver(SourceFile:25)
        at ...AudioComicImageGenerationWorker.$r8$lambda$6I3qiUqS1sJepHkoGjm-M8wHbZg
        at ...generatePlanSnapshot$handleSceneOutcome(SourceFile:2653)
        at ...AudioComicImageLanePipeline$run$3.invokeSuspend(SourceFile:72)
    Suppressed: DiagnosticCoroutineContextException:
      [LazyStandaloneCoroutine{Cancelling}@6ab24, Dispatchers.IO]

deliver() 有两条出口：

  正常路径（smali 第 140-213 行）：sceneKey 非空、且 imageFile 是文件且长度 >= 1024
  时，依次回调 p3(file) / p4(sceneKey) / p5()，然后

      try { p6.invoke(file) } catch { p7.invoke(err) }

  即 p6 是下游交付回调、**p7 是错误回调**（$$ExternalSyntheticLambda35 →
  $r8$lambda$f9G9y1iCc4zR_spnKiN1k69tbKc，只往 debug 日志写一行
  `[AUDIO_COMIC_IMAGE] downstream_after_visible_failed scene=... reason=...`）。

  抛错路径（:cond_1）：文件不是文件或长度 < 1024 时，

      const-string "audio comic image is not stable: " + 绝对路径
      Lokio/CipherSink$$ExternalSyntheticBUOutline0;->m(String,Object)V   # 内部 new + throw

  直接抛未捕获异常。而唯一调用方 generatePlanSnapshot$handleSceneOutcome 的
  try/catch 只捕获 AudioComicImageGenerationWorker$ApiWorkerPlanWriteRejectedException，
  这个 IllegalArgumentException 会一路穿透到协程，把整条图片生成流水线取消
  （栈里的 LazyStandaloneCoroutine{Cancelling} 就是它）。

也就是说：**一张过小/写坏/模型返回错误体的小图片，会让整章的音频漫画配图流水线停摆**，
而不是只让这一格失败。这是上游的健壮性缺陷（该字符串在上游 r2 原包中同样存在，
不是本项目补丁引入的）。

本补丁把 :cond_1 的「抛未捕获异常」改成「构造同样的异常并交给 p7」，
于是失败被限定在单格、并且走 App 自己的错误日志路径，流水线继续跑完其余分镜。

为什么不做重试等待
-----------------
长度 < 1024 通常是模型侧返回了错误体（HTTP 200 但 body 是 JSON 错误串）或占位图，
不是「文件还在写」的竞态——写入侧 downloadImageToFile 用一次 writeBytes 落盘，
deliver 是在同一协程里紧随其后调用的。所以重试等待收益低、且要手写 smali 循环，
风险大于收益。这里只做「不崩」这一件事。
"""
import os
import re
import sys

MARK = "JETHUB-AUDIOCOMIC-PATCH"

# 抛错助手类（R8 outline）：内部就是 new IllegalArgumentException(前缀+对象) 后 throw。
THROW_HELPER = "Lokio/CipherSink$$ExternalSyntheticBUOutline0;->m(Ljava/lang/String;Ljava/lang/Object;)V"

REL = "io/legado/app/help/audiobook/AudioComicImageDeliveryFastLane.smali"

# 要被替换掉的整块（:cond_1 抛错分支）。用「不稳定的图片」这条唯一字符串锚定，
# 因为该文件里 :cond_1 标签出现 2 次（另一处在 deliver$default 里）。
OLD = """    .line 26
    :cond_1
    invoke-virtual {p2}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;

    move-result-object p0

    const-string p1, "audio comic image is not stable: "

    .line 25
    invoke-static {p1, p0}, %s

    return-void
""" % THROW_HELPER

NEW = """    .line 26
    :cond_1
    # %s: 图片未就绪/过小（不是文件或 < 1024 字节）时不再抛未捕获异常。
    # 上游在这里直接 throw，而唯一调用方 handleSceneOutcome 只捕获
    # ApiWorkerPlanWriteRejectedException，于是异常穿透到协程、把整章图片
    # 流水线取消（LazyStandaloneCoroutine{Cancelling}）。改为走 p7 错误回调，
    # 失败被限定在单格，流水线继续跑完其余分镜。
    invoke-virtual {p2}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;

    move-result-object p0

    new-instance p1, Ljava/lang/StringBuilder;

    invoke-direct {p1}, Ljava/lang/StringBuilder;-><init>()V

    const-string v0, "audio comic image is not stable: "

    invoke-virtual {p1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    new-instance p1, Ljava/lang/IllegalArgumentException;

    invoke-direct {p1, p0}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    invoke-interface {p7, p1}, Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;

    return-void
""" % MARK

# 补丁后该块里必须出现的指令（写后自检）
REQUIRED = [
    "new-instance p1, Ljava/lang/IllegalArgumentException;",
    "invoke-direct {p1, p0}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V",
    "invoke-interface {p7, p1}, Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;",
]

# deliver 的寄存器预算：.locals 4 → v0..v3 局部，p0..p7 映射到 v4..v11。
# 本补丁只用 v0 与 p 寄存器，不需要改 .locals。
EXPECT_LOCALS = ".locals 4"
LOCALS_ANCHOR = ".method public final deliver(Ljava/lang/String;Ljava/io/File;"


def resolve_smali(root):
    import glob
    hits = sorted(glob.glob(os.path.join(root, "smali_classes*", REL)))
    if not hits:
        hits = sorted(glob.glob(os.path.join(root, "smali", REL)))
    if not hits:
        sys.exit("在 %s 下找不到 %s（先跑 apktool d 解包）" % (root, REL))
    if len(hits) > 1:
        sys.exit("找到多份 %s，请人工确认：%s" % (REL, hits))
    return hits[0]


def detect_eol(text):
    """返回该文件的行尾：'\\r\\n' 或 '\\n'。

    这棵树的行尾取决于 apktool 在哪个平台上解包——Windows 上解出来全是 CRLF
    （实测目标文件 235 行全 CRLF、无裸 LF），Linux 上是 LF。下面的锚点块统一用
    '\\n' 书写，落盘前再按实际行尾展开，这样两种树都能匹配，且不会把文件的行尾
    改掉（改行尾会让整个 smali 的字节数变化，也会污染 git diff）。
    """
    crlf = text.count("\r\n")
    lf = text.count("\n") - crlf
    if crlf and lf:
        sys.exit("目标文件行尾混杂（CRLF=%d 裸LF=%d），请人工确认" % (crlf, lf))
    return "\r\n" if crlf else "\n"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check_only = "--check" in sys.argv
    root = args[0] if args else os.environ.get("JETHUB_DECODED", "full-decoded")
    if not os.path.isdir(root):
        sys.exit("反编译树目录不存在：%s" % root)

    smali = resolve_smali(root)
    # 读写都禁用 universal-newlines / 平台换行翻译，避免把 smali 的行尾改掉。
    with open(smali, encoding="utf-8", newline="") as handle:
        text = handle.read()

    if MARK in text:
        print("已打过补丁，跳过：%s" % smali)
        return

    # 锚点块按该文件的实际行尾展开（Windows 解包是 CRLF、Linux 是 LF）
    eol = detect_eol(text)
    old = OLD.replace("\n", eol) if eol != "\n" else OLD
    new = NEW.replace("\n", eol) if eol != "\n" else NEW

    # 锚点唯一性自检：抛错助手类在这条分支上只能出现 1 次
    if text.count(THROW_HELPER) != 1:
        sys.exit("抛错助手类出现 %d 次（期望 1 次），锚点不唯一，请人工确认" % text.count(THROW_HELPER))
    if "audio comic image is not stable" not in text:
        sys.exit("找不到锚点字符串 'audio comic image is not stable'，上游版本可能已变")
    if text.count(old) != 1:
        sys.exit("目标块匹配 %d 次（期望 1 次，行尾 %r），上游 smali 形态可能已变"
                 % (text.count(old), eol))

    # deliver 的 .locals 必须仍是 4：本补丁假设 v0..v3 可用、p0..p7 映射到 v4..v11
    head = text.find(LOCALS_ANCHOR)
    if head < 0:
        sys.exit("找不到 deliver 方法定义，无法核对寄存器预算")
    window = text[head:head + 400]
    if EXPECT_LOCALS not in window:
        sys.exit("deliver 的寄存器预算不是 %r，请人工确认（窗口内未见）" % EXPECT_LOCALS)

    new_text = text.replace(old, new, 1)

    # 行尾必须没被改动
    if detect_eol(new_text) != eol:
        sys.exit("写后自检失败：行尾被改写")

    # 写后自检：新块里必须含全部必需指令，且抛错助手类只剩 0 次
    for need in REQUIRED:
        if need not in new_text:
            sys.exit("写后自检失败，缺少：%s" % need)
    if THROW_HELPER in new_text:
        sys.exit("写后自检失败：抛错助手类仍然存在")
    if "audio comic image is not stable" not in new_text:
        sys.exit("写后自检失败：提示字符串被误删")

    delta = len(new_text) - len(text)
    if check_only:
        print("--check 通过（未写入）：%s" % smali)
        print("预计文件长度变化：%+d 字节" % delta)
        return

    with open(smali, "w", encoding="utf-8", newline="") as handle:
        handle.write(new_text)

    print("补丁已写入 %s" % smali)
    print("改动：:cond_1 抛未捕获异常 → invoke p7 错误回调")
    print("文件长度变化：%+d 字节" % delta)


if __name__ == "__main__":
    main()
