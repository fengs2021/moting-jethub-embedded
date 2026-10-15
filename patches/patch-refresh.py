#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 AiApiControlActivity 注入 onResume()，让「回到模型中心」必定重读模型数据。

用法
----
    python3 patches/patch-refresh.py <反编译树根目录>

其中 <反编译树根目录> 是 apktool 解包出来的目录（含 smali_classes*/ 与 AndroidManifest.xml）。
默认读环境变量 JETHUB_DECODED，未设置时用 ./full-decoded。

为什么需要这个补丁
------------------
墨听模型中心的加载协程 MotingAiModelCenterScreenKt$MotingAiModelCenterScreen$3$1
有一个 final synthetic int $backupRevision 字段，它的值来自 MotingAiModelCenterScreen(...)
的第 4 个参数（smali 里的 p3）。同一个值又被包成 Integer 当 LaunchedEffect 的 key：

    .line 459
    invoke-static {v21}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;   # v21 == p3
    ...
    invoke-static {v0, v3, v5, v1}, Landroidx/compose/runtime/EffectsKt;->LaunchedEffect(...)

协程体里：

    iget p1, ...->$backupRevision:I
    if-lez p1, :cond_1
        AiModelCenterStore.INSTANCE.load(context)  →  applyResult(...)

即 **revision 变一次，模型中心就重新读一次盘**。而 AiApiControlActivity 里这个
MutableIntState 只在「导入备份成功」时自增（AiApiControlActivity$readAiModelCenterBackup$1
里 setAiModelCenterBackupRevision(get()+1)）。

JetHub 写入模型中心走的是另一条路（ModelCenterRegistrar 反射调 restoreJson），
不经过那个导入流程，所以 revision 不变。用户从 JetHubActivity 返回模型中心时，
屏幕虽然会重组，但 LaunchedEffect 的 key 没变、协程不重启，页面上看不到新写入的模型。
用户的反馈正是：「写入 ai 模型中心后要刷新一下模型中心中的模型，要不然不会自动显示出来。」

本补丁给 AiApiControlActivity 加一个 onResume()（该类原本没有覆写任何 resume 方法，
注入点干净），每次回到本页就把 revision +1，于是任何返回路径都会触发重新 load()。
"""
import os
import sys

MARK = "# JETHUB-REFRESH-PATCH"
ANCHOR = ".method public onCreate(Landroid/os/Bundle;)V"

# 相对反编译树根目录的路径（apktool 的 dex 分包编号在不同墨听版本可能不同，
# 因此这里按 glob 兜底查找，见 resolve_smali()）。
REL = "io/legado/app/ui/book/read/config/AiApiControlActivity.smali"

PATCH = """# JETHUB-REFRESH-PATCH: 回到本页时自增 aiModelCenterBackupRevision。
# 模型中心的加载协程以这个值为 LaunchedEffect 的 key，只有它变化才会重新
# AiModelCenterStore.load()。原实现只在「导入备份成功」时自增，JetHub 的写入
# 走的是另一条路径（反射调 restoreJson），因此写完返回本页看不到新模型。
# 放在 onResume 里，任何返回路径（返回键、切换任务、从浏览器回来）都会刷新。
.method public onResume()V
    .locals 2

    invoke-super {p0}, Landroidx/appcompat/app/AppCompatActivity;->onResume()V

    invoke-direct {p0}, Lio/legado/app/ui/book/read/config/AiApiControlActivity;->getAiModelCenterBackupRevision()I

    move-result v0

    add-int/lit8 v0, v0, 0x1

    invoke-direct {p0, v0}, Lio/legado/app/ui/book/read/config/AiApiControlActivity;->setAiModelCenterBackupRevision(I)V

    return-void
.end method

"""


def resolve_smali(root):
    """在 root 下按 glob 找到 AiApiControlActivity.smali 的绝对路径。"""
    import glob
    hits = sorted(glob.glob(os.path.join(root, "smali_classes*", REL)))
    if not hits:
        hits = sorted(glob.glob(os.path.join(root, "smali", REL)))
    if not hits:
        sys.exit("在 %s 下找不到 %s（先跑 apktool d 解包）" % (root, REL))
    if len(hits) > 1:
        sys.exit("找到多份 %s，请人工确认：%s" % (REL, hits))
    return hits[0]


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("JETHUB_DECODED", "full-decoded")
    if not os.path.isdir(root):
        sys.exit("反编译树目录不存在：%s" % root)

    smali = resolve_smali(root)
    with open(smali, encoding="utf-8") as handle:
        text = handle.read()

    if MARK in text:
        print("已打过补丁，跳过：%s" % smali)
        return

    if ANCHOR not in text:
        sys.exit("找不到注入锚点：%s" % ANCHOR)

    if "onResume" in text:
        sys.exit("该类已有 onResume，注入会冲突，请人工确认")

    text = text.replace(ANCHOR, PATCH + ANCHOR, 1)
    with open(smali, "w", encoding="utf-8") as handle:
        handle.write(text)

    print("补丁已写入 %s" % smali)
    print("新增方法：public onResume()V（revision +1）")


if __name__ == "__main__":
    main()
