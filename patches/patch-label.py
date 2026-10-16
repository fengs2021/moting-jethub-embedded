#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""改应用显示名：桌面图标下显示的名称，从「墨听」换成「墨听JetHub」等。

用法
----
    python3 patches/patch-label.py <反编译树根目录> [新名称] [--all-locales]

默认只改 `res/values/strings.xml`（Android 的默认资源）。这与历史成品
`墨听-4.0.20-overlap修复版.apk` 的做法一致——那一版就是只改了默认资源，
所以英文/默认 locale 的设备显示「墨听JetHub」，而中文设备仍显示「墨听」。

加 `--all-locales` 会把 `values-zh-rCN` / `values-zh-rHK` / `values-zh-rTW`
一并改掉，让所有 locale 统一显示新名。

选择哪一个取决于目标：**要与历史成品逐字节对齐，就用默认（只改默认资源）**；
**要让中文手机上也真的看到改名，就加 `--all-locales`**——默认行为在中文设备上
是看不到效果的，因为 values-zh-rCN 会盖过默认资源。build-full.sh 默认走前者以
对齐历史成品，需要后者就设 `JETHUB_APP_LABEL_ALL_LOCALES=1`。

为什么需要这个补丁
-----------------
上游墨听的 `android:label="@string/app_name"`，即桌面名称由
`res/values*/strings.xml` 里的 `app_name` 决定。改造版与原版共存时，两者
图标下的名称一样，用户无法区分，所以把改造版的显示名加上后缀。

改哪些地方
---------
只改 `<string name="app_name">` 这一条。`app_name_a`（「阅读db·A」）是另一
个独立资源，不动；`AndroidManifest.xml` 里的 `@string/app_name` 引用也不动
——改资源值即可，无需触碰引用方。

幂等
----
目标文件里 app_name 已经是新名就跳过；全部跳过时打印提示并返回 0。
"""

import io
import os
import re
import sys

DEFAULT_LABEL = "墨听JetHub"

DEFAULT_LOCALE_FILE = "res/values/strings.xml"
ALL_LOCALE_FILES = (
    "res/values/strings.xml",
    "res/values-zh-rCN/strings.xml",
    "res/values-zh-rHK/strings.xml",
    "res/values-zh-rTW/strings.xml",
)

# 只匹配 app_name 本身；`name="app_name_a"` 因为后面紧跟的不是 `>` 而不会被命中
PAT = re.compile(r'(<string name="app_name">)([^<]*)(</string>)')


def main():
    raw = sys.argv[1:]
    flags = {a for a in raw if a.startswith("--")}
    args = [a for a in raw if not a.startswith("--")]

    root = os.path.abspath(args[0] if args else os.environ.get("JETHUB_DECODED", "./full-decoded"))
    label = args[1] if len(args) > 1 else os.environ.get("JETHUB_APP_LABEL", DEFAULT_LABEL)
    all_locales = "--all-locales" in flags

    if not os.path.isdir(root):
        sys.exit("反编译树不存在：%s" % root)
    if not label:
        sys.exit("新名称不能为空")

    targets = ALL_LOCALE_FILES if all_locales else (DEFAULT_LOCALE_FILE,)

    changed = 0
    already = 0
    for rel in targets:
        path = os.path.join(root, rel.replace("/", os.sep))
        if not os.path.isfile(path):
            print("  [跳过] %s 不存在" % rel)
            continue
        # newline="" 必须读写两侧都给：读时禁用 universal-newlines 折叠，
        # 否则 \r\n 会先在内存里变成 \n，写出时再按平台补回，行尾就被改写了。
        # apktool 在 Windows 上解包出的 XML 是 CRLF，在 Linux 上是 LF，本脚本
        # 只应改 app_name 的值，不该顺带改行尾。
        with io.open(path, encoding="utf-8", newline="") as f:
            src = f.read()

        m = PAT.search(src)
        if not m:
            print("  [跳过] %s 里找不到 app_name" % rel)
            continue

        old = m.group(2)
        if old == label:
            already += 1
            print("  [已是] %-40s %s" % (rel, label))
            continue

        out = PAT.sub(lambda mm: mm.group(1) + label + mm.group(3), src)
        # newline="" 是关键：Windows 上默认会把 \n 写成 \r\n，污染反编译树
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(out)
        print("  %-44s %s → %s" % (rel, old, label))
        changed += 1

    if changed == 0:
        if already:
            print("[label] 应用名已经是 %s，跳过" % label)
        else:
            print("[label] 没有可改的目标文件")
        return 0

    print("[label] 应用名已改为 %s（%d 个文件）" % (label, changed))
    if not all_locales:
        print("[label] 提示：本次只改了默认资源 res/values/strings.xml，"
              "中文设备仍显示 values-zh-rCN 里的旧名；")
        print("[label]       如需所有 locale 统一，请加 --all-locales 重跑。")
    return changed


if __name__ == "__main__":
    main()
