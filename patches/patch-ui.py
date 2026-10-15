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


def patch_manifest(root):
    path = os.path.join(root, "AndroidManifest.xml")
    with io.open(path, encoding="utf-8") as f:
        xml = f.read()
    pat = re.compile(
        r'<activity android:exported="true" android:name="jet\.android\.JetHubActivity"'
        r' android:label="[^"]*">.*?</activity>',
        re.S)
    m = pat.search(xml)
    if not m:
        print("[manifest] 未找到含 intent-filter 的 JetHubActivity 节点（可能已打过补丁）")
        return False
    if "LAUNCHER" not in m.group(0):
        print("[manifest] JetHubActivity 已无 LAUNCHER 项，跳过")
        return False
    rep = ('<activity android:exported="true" android:name="jet.android.JetHubActivity"'
           ' android:label="JetHub 模型中心" android:excludeFromRecents="true"/>')
    xml2 = pat.sub(rep, xml, count=1)
    assert "android.intent.category.LAUNCHER" not in xml2.split("JetHubActivity")[1][:400]
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(xml2)
    print("[manifest] 已移除 LAUNCHER intent-filter（桌面不再出现图标）")
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


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("JETHUB_DECODED", "full-decoded")
    if not os.path.isdir(root):
        sys.exit("反编译树目录不存在：%s" % root)
    a = patch_manifest(root)
    b = patch_menu(root)
    print("完成：manifest=%s menu=%s" % (a, b))
