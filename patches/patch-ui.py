#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""墨听改造补丁：① 去掉 JetHub 桌面图标 ② 在《AI模型与接口中心》菜单里，
《管理网页转 API》下方插入《管理平台代理 API》入口。

用法
----
    python3 patches/patch-ui.py <反编译树根目录>

其中 <反编译树根目录> 是 apktool 解包出来的目录（含 smali_classes*/ 与 AndroidManifest.xml）。
默认读环境变量 JETHUB_DECODED，未设置时用 ./full-decoded。
两个补丁都是幂等的，重复执行会打印「跳过」。

为什么需要这两个补丁
--------------------
① 入口不再放桌面
   JetHubActivity 最初带 MAIN/LAUNCHER intent-filter，桌面上多出一个「JetHub 模型中心」图标。
   但它的入口应当在墨听自己的《AI 模型与接口中心》里（用户实机反馈：「上面外围的不要了」），
   桌面图标纯属重复。改成 android:excludeFromRecents="true"，同时不占最近任务列表。

② 菜单项注入
   模型中心右上角下拉菜单的构建代码在
   smali_classes*/io/legado/app/ui/book/read/config/MotingAiModelCenterScreenKt.smali，
   注入点是 MotingAiModelCenterScreen$lambda$120$2$1$2 内、line 529 的
   AppDropdownMenuItem（即《管理网页转 API》）之后。文案与点击行为由两个 smali 胶水类提供
   （见 smali-glue/，不进 Java 源码）：
     - jet/android/JetHubMenuLabel  —— Compose 文案
     - jet/android/JetHubMenuAction —— 点击后 startActivity(JetHubActivity)
"""
import io
import os
import re
import sys

MARK = "# JETHUB-MENU-INJECTED"
SCREEN_REL = "io/legado/app/ui/book/read/config/MotingAiModelCenterScreenKt.smali"

NEW_ITEM = """    .line 529
    invoke-static/range {v4 .. v15}, Lio/legado/app/ui/widget/components/menuItem/AppDropdownMenuKt;->AppDropdownMenuItem(Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function0;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function2;ZLandroidx/compose/material3/MenuItemColors;Landroidx/compose/foundation/layout/PaddingValues;Landroidx/compose/foundation/interaction/MutableInteractionSource;Landroidx/compose/runtime/Composer;II)V

    %s
    new-instance v4, Ljet/android/JetHubMenuLabel;

    invoke-direct {v4}, Ljet/android/JetHubMenuLabel;-><init>()V

    new-instance v5, Ljet/android/JetHubMenuAction;

    invoke-direct {v5}, Ljet/android/JetHubMenuAction;-><init>()V

    const/4 v6, 0x0

    const/4 v7, 0x0

    const/4 v8, 0x0

    const/4 v9, 0x0

    const/4 v10, 0x0

    const/4 v11, 0x0

    const/4 v12, 0x0

    const/4 v14, 0x6

    const/16 v15, 0x1fc

    invoke-static/range {v4 .. v15}, Lio/legado/app/ui/widget/components/menuItem/AppDropdownMenuKt;->AppDropdownMenuItem(Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function0;Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function2;ZLandroidx/compose/material3/MenuItemColors;Landroidx/compose/foundation/layout/PaddingValues;Landroidx/compose/foundation/interaction/MutableInteractionSource;Landroidx/compose/runtime/Composer;II)V
""" % MARK


def resolve_screen(root):
    """按 glob 找到 MotingAiModelCenterScreenKt.smali（dex 分包编号随版本变化）。"""
    import glob
    hits = sorted(glob.glob(os.path.join(root, "smali_classes*", SCREEN_REL)))
    if not hits:
        hits = sorted(glob.glob(os.path.join(root, "smali", SCREEN_REL)))
    if not hits:
        sys.exit("在 %s 下找不到 %s" % (root, SCREEN_REL))
    if len(hits) > 1:
        sys.exit("找到多份 %s，请人工确认：%s" % (SCREEN_REL, hits))
    return hits[0]


ACTIVITY_TAG = (
    '<activity android:exported="true" android:name="jet.android.JetHubActivity"'
    ' android:label="JetHub 模型中心" android:excludeFromRecents="true"/>')


def patch_manifest(root):
    """注册 JetHubActivity，并确保它不带 LAUNCHER（桌面不出现图标）。

    四种输入形态都要处理：
      1. 自闭合且已带 excludeFromRecents  → 已完成，跳过（幂等）
      2. 自闭合但没带 excludeFromRecents  → 补上
      3. 成对标签（多半带 intent-filter）→ 换成自闭合形式，去掉 LAUNCHER
      4. 完全没有这个节点（上游原包）    → 插到 </application> 之前
    """
    path = os.path.join(root, "AndroidManifest.xml")
    with io.open(path, encoding="utf-8") as f:
        xml = f.read()

    # 形态 1/2：自闭合
    self_close = re.compile(
        r'<activity\b[^>]*android:name="jet\.android\.JetHubActivity"[^>]*/>')
    m = self_close.search(xml)
    if m:
        if "excludeFromRecents" in m.group(0):
            print("[manifest] JetHubActivity 已注册且无 LAUNCHER，跳过")
            return False
        xml2 = self_close.sub(ACTIVITY_TAG, xml, count=1)
        with io.open(path, "w", encoding="utf-8") as f:
            f.write(xml2)
        print("[manifest] 已给 JetHubActivity 补上 excludeFromRecents")
        return True

    # 形态 3：成对标签（可能带 intent-filter）
    paired = re.compile(
        r'<activity\b[^>]*android:name="jet\.android\.JetHubActivity"[^>]*>.*?</activity>',
        re.S)
    m = paired.search(xml)
    if m:
        xml2 = paired.sub(ACTIVITY_TAG, xml, count=1)
        with io.open(path, "w", encoding="utf-8") as f:
            f.write(xml2)
        print("[manifest] 已把 JetHubActivity 换成自闭合形式（移除 intent-filter）")
        return True

    # 形态 4：还没注册 —— 插到 </application> 之前，缩进取同级子元素（不是 </application> 自己的）
    m = re.search(r'([ \t]*)</application>', xml)
    if not m:
        sys.exit("AndroidManifest.xml 里没有 </application>，无法注册 Activity")
    # <application> 的子元素比 </application> 多一级缩进；参照已有子元素最稳
    sib = re.search(r'\n([ \t]+)<(activity|service|receiver|provider|meta-data)\b',
                    xml[:m.start()])
    indent = sib.group(1) if sib else m.group(1) + "    "
    xml2 = xml.replace(m.group(0),
                       indent + ACTIVITY_TAG + "\n" + m.group(0), 1)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(xml2)
    print("[manifest] 已注册 JetHubActivity（无 LAUNCHER，不占最近任务）")
    return True


def install_glue(root):
    """把 smali-glue/ 下的胶水类复制进反编译树。

    这两个类是 Compose 的 Function2/Function0 实现，写成 Java 反而更绕，所以直接
    以 smali 形式提供。它们必须落在 MotingAiModelCenterScreenKt 所在的同一个 dex
    分包里（否则跨 dex 引用没问题，但 patch-ui.py 的注入点按包名找不到）。
    这里统一放进 smali_classes10/ —— 正式发布包就是这么放的。
    """
    import glob
    import shutil
    glue_src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "smali-glue")
    glue_src = os.path.normpath(glue_src)
    if not os.path.isdir(glue_src):
        print("[glue] 跳过：%s 不存在" % glue_src)
        return False
    # 胶水类要放进一个「已经存在」的 smali 分包，优先 classes10，否则新建
    target = os.path.join(root, "smali_classes10")
    if not os.path.isdir(target):
        os.makedirs(os.path.join(target, "jet", "android"))
    installed = []
    for src in glob.glob(os.path.join(glue_src, "smali_classes*", "**", "*.smali"),
                         recursive=True):
        rel = os.path.relpath(src, os.path.join(glue_src, "smali_classes10"))
        dst = os.path.join(target, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        installed.append(rel)
    print("[glue] 已放入 smali_classes10/：%s" % ", ".join(installed))
    return True


def patch_menu(root):
    screen = resolve_screen(root)
    with io.open(screen, encoding="utf-8") as f:
        src = f.read()
    if MARK in src:
        print("[menu] 已打过补丁，跳过")
        return False
    anchor = "    .line 529\n    invoke-static/range {v4 .. v15}, Lio/legado/app/ui/widget/components/menuItem/AppDropdownMenuKt;->AppDropdownMenuItem("
    n = src.count(anchor)
    if n != 1:
        print("[menu] !! 锚点命中 %d 次（期望 1），中止" % n)
        return False
    idx = src.index(anchor)
    # 找到该 invoke 行结束（行尾）
    end = src.index("\n", src.index("(Lkotlin/jvm/functions/Function2;", idx))
    out = src[:end + 1] + "\n" + NEW_ITEM + src[end + 1:]
    with io.open(screen, "w", encoding="utf-8") as f:
        f.write(out)
    print("[menu] 已在《管理网页转 API》后插入《管理平台代理 API》")
    return True


def patch_boot(root):
    """往 App.onCreate() 注入一行 Boot.init(Context)，启动内嵌网关。

    幂等：已经含 Ljet/android/Boot 就跳过。

    锚点用正则而不是硬编码字符串：同一个墨听版本经不同 apktool 参数解包，
    onCreate 头部可能写成 `.registers 14`，也可能写成 `.locals 13`
    （两者等价：.registers = .locals + 参数个数）。注入的指令只用 p0，
    不改变寄存器分配，所以两种写法都能直接插。
    """
    import glob
    hits = sorted(glob.glob(os.path.join(root, "smali_classes*", "io/legado/app/App.smali")))
    if not hits:
        hits = sorted(glob.glob(os.path.join(root, "smali", "io/legado/app/App.smali")))
    if not hits:
        sys.exit("在 %s 下找不到 io/legado/app/App.smali" % root)
    path = hits[0]
    with io.open(path, encoding="utf-8") as f:
        src = f.read()
    if "Ljet/android/Boot" in src:
        print("[boot] 已注入，跳过")
        return False

    pat = re.compile(r'(\.method public onCreate\(\)V\n[ \t]+\.(?:registers|locals) \d+\n)')
    found = pat.findall(src)
    if len(found) != 1:
        sys.exit("[boot] onCreate 锚点命中 %d 次（期望 1），请人工确认 App.smali" % len(found))
    src = pat.sub(lambda m: m.group(1) +
                  "\n    # JETHUB: 启动内嵌供应商网关（loopback，失败静默）\n"
                  "    invoke-static {p0}, Ljet/android/Boot;->init(Landroid/content/Context;)V\n",
                  src, count=1)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(src)
    print("[boot] 已在 App.onCreate() 注入 Boot.init()")
    return True


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("JETHUB_DECODED", "full-decoded")
    if not os.path.isdir(root):
        sys.exit("反编译树目录不存在：%s" % root)
    g = install_glue(root)
    m = patch_manifest(root)
    b = patch_menu(root)
    k = patch_boot(root)
    print("完成：glue=%s manifest=%s menu=%s boot=%s" % (g, m, b, k))
