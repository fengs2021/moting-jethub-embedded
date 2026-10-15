#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""改包名：把上游墨听的 applicationId 换成自己的，避免与原版墨听互相覆盖。

用法
----
    python3 patches/patch-package.py <反编译树根目录> <新包名>

其中 <反编译树根目录> 是 apktool 解包出来的目录，<新包名> 形如 com.example.moting。
默认读环境变量 JETHUB_DECODED / JETHUB_PACKAGE，都缺省时分别用 ./full-decoded
与 com.jethub.moting。脚本幂等：已经是新包名就跳过。

为什么需要这个补丁
------------------
上游墨听的 applicationId 是 com.applecabal.njread。改造版如果沿用同一个包名，
装到手机上会与原版墨听共用数据目录与签名身份——想两个版本并存做不到，
而且一旦签名不一致，覆盖安装会直接失败（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
所以对外分发前把包名换掉。

改哪些地方
----------
包名不是只写在 AndroidManifest.xml 的 package 属性里，它还会以字符串形式散落在
若干 smali 中（BuildConfig.APPLICATION_ID、provider authorities 的拼装、
ContextExtensionsKt 里取包名的逻辑等）。逐处枚举容易漏，所以这里做的是
**全树字符串替换**——apktool 解包出的树里，`com.applecabal.njread` 这个字面量
只可能是包名本身，不会误伤类描述符（类描述符是 `Lio/legado/app/...`，与本包名无关）。

实测上游 4.0.20-r2 的命中位置（共 16 处 / 9 个文件）：

    AndroidManifest.xml                                    6   package 属性、自定义权限、三个 provider authorities
    smali_classes6/io/legado/app/BuildConfig.smali          1   APPLICATION_ID
    smali_classes6/io/legado/app/utils/ContextExtensionsKt.smali  3
    smali_classes4/io/legado/app/constant/AppConst.smali    1
    smali_classes4/io/legado/app/help/audiobook/JReadListenBackupManager.smali  1
    smali_classes5/io/legado/app/model/localBook/epubcore/facade/EpubCoreFacade.smali  1
    smali_classes5/io/legado/app/ui/file/FileManageActivity$FileAdapter.smali  1
    smali_classes6/io/legado/app/ui/main/externallibrary/cloud/AliyunDriveCloudAdapter.smali  1
    smali_classes7/io/legado/app/ui/book/read/epub/EpubDirectWebLayer.smali  1

注意：改名后是**另一个应用**，装到已有原版墨听的手机上不会继承书架、阅读进度、
AI 模型中心配置等任何数据，需要重新走一遍初始化。这是 applicationId 的固有语义，
不是本脚本的缺陷。
"""

import io
import os
import re
import sys

OLD_DEFAULT = "com.applecabal.njread"
NEW_DEFAULT = "com.jethub.moting"

# 只扫这些后缀，避免遍历 lib/ 下的二进制
EXTS = (".xml", ".smali", ".yml", ".json", ".txt")


def collect(root, old):
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        # apktool 回编时会把 dex 解到 build/ 下，那是产物不是输入
        dirnames[:] = [d for d in dirnames if d not in ("build", ".git")]
        for fn in filenames:
            if not fn.endswith(EXTS):
                continue
            path = os.path.join(dirpath, fn)
            try:
                with io.open(path, encoding="utf-8") as f:
                    src = f.read()
            except (UnicodeDecodeError, OSError):
                continue
            n = src.count(old)
            if n:
                hits.append((path, n))
    return hits


def main():
    if len(sys.argv) >= 3:
        root, new = sys.argv[1], sys.argv[2]
    else:
        root = sys.argv[1] if len(sys.argv) == 2 else os.environ.get("JETHUB_DECODED", "./full-decoded")
        new = os.environ.get("JETHUB_PACKAGE", NEW_DEFAULT)
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        sys.exit("反编译树不存在：%s" % root)

    manifest = os.path.join(root, "AndroidManifest.xml")
    if not os.path.isfile(manifest):
        sys.exit("在 %s 下找不到 AndroidManifest.xml —— 这不像 apktool 解包出来的目录" % root)

    with io.open(manifest, encoding="utf-8") as f:
        head = f.read(4096)
    m = re.search(r'package="([^"]+)"', head)
    if not m:
        sys.exit("AndroidManifest.xml 里没有 package 属性")
    old = m.group(1)

    if not re.match(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$", new):
        sys.exit("新包名不合法：%s" % new)
    if old == new:
        print("[package] 已经是 %s，跳过" % new)
        return False
    if old != OLD_DEFAULT:
        print("[package] 注意：当前包名是 %s（不是已知的 %s），仍按字符串替换处理"
              % (old, OLD_DEFAULT))

    hits = collect(root, old)
    if not hits:
        sys.exit("[package] 全树找不到 %s —— 树可能已被改过或损坏" % old)

    total = 0
    for path, n in hits:
        with io.open(path, encoding="utf-8") as f:
            src = f.read()
        with io.open(path, "w", encoding="utf-8") as f:
            f.write(src.replace(old, new))
        total += n
        print("  %-4d %s" % (n, os.path.relpath(path, root)))

    print("[package] %s → %s（%d 处 / %d 个文件）" % (old, new, total, len(hits)))
    print("[package] 提醒：改包名后是另一个应用，不会继承原版墨听的数据")
    return True


if __name__ == "__main__":
    main()
