#!/usr/bin/env bash
# 成品包验收：以「能装能跑」为标准，不逐字节比对。
#
# 检查项：
#   1. dex 分包连续性 —— Android 运行时按 classes.dex, classes2.dex… 顺序扫描，
#      遇到第一个缺号就停止加载后续 dex。任一缺号 → 注入类加载不到 →
#      启动即 NoClassDefFoundError: Failed resolution of: Ljet/android/Boot; 闪退。
#      而回编、签名、产物结构检查全都正常，只有装到机器上才炸。故必查。
#   2. 关键类落位（Boot / JetHubActivity / 胶水类 / overlap 辅助类）
#   3. 入口三要素（Manifest 注册 JetHubActivity、assets/jethub/home.html、
#      App.onCreate 注入 Boot.init）——三者缺一则装出的包没有入口
#   4. 包身份（package / versionCode / versionName）与签名证书
#
# 用法：
#   bash tools/verify-apk.sh [APK 路径]
# 未给路径时取仓库根下最新的 墨听-*.apk。
#
# 依赖：unzip、baksmali.jar、apksigner（走 lib-deps.sh 的跨平台抽象层）、
#       以及 aapt2（优先 ANDROID_BUILD_TOOLS/aapt2[.exe]，否则 apktool 内置）。

set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

# shellcheck source=../scripts/lib-deps.sh
. "$ROOT/scripts/lib-deps.sh"
jethub_force_utf8

# jethub_resolve_build_tools 内部会读 $ANDROID_BUILD_TOOLS；本工具不跑
# ensure_deps，须自己先兜底并解析出真实目录，否则 set -u 下报 unbound variable，
# 且 jethub_apksigner/jethub_zipalign 拿不到可用的 build-tools 路径。
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-}"
ANDROID_BUILD_TOOLS="$(jethub_resolve_build_tools)"
export ANDROID_BUILD_TOOLS

BAKSMALI="$TOOLCHAIN/baksmali.jar"

APK="${1:-}"
if [ -z "$APK" ]; then
  APK="$(ls -t "$ROOT"/墨听-*.apk 2>/dev/null | head -1)"
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "找不到 APK。用法: bash tools/verify-apk.sh <APK 路径>" >&2
  exit 2
fi

W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT

fail=0
ok()   { echo "  ✓ $*"; }
bad()  { echo "  ✗ $*"; fail=1; }

echo "########## 0. 产物 ##########"
echo "  路径: $APK"
echo "  大小: $(stat -c %s "$APK") 字节"
if command -v sha256sum >/dev/null 2>&1; then
  echo "  sha256: $(sha256sum "$APK" | cut -d' ' -f1)"
fi

echo
echo "########## 1. dex 分包连续性（启动闪退根因）##########"
# sort -V 做版本序排序，否则 classes10 会排在 classes2 前面。
unzip -l "$APK" | grep -oE 'classes[0-9]*\.dex' | sort -u | sort -V > "$W/dexlist.txt"
if [ ! -s "$W/dexlist.txt" ]; then
  bad "包内没有 classes*.dex"
else
  sed 's/^/    /' "$W/dexlist.txt"
  prev=0
  gap=0
  while read -r d; do
    num="$(echo "$d" | sed 's/classes//; s/\.dex//')"
    [ -z "$num" ] && num=1
    if [ "$num" -ne $((prev + 1)) ]; then
      bad "编号跳跃：classes${prev}.dex → $d"
      gap=1
    fi
    prev="$num"
  done < "$W/dexlist.txt"
  total="$(wc -l < "$W/dexlist.txt")"
  [ "$gap" -eq 0 ] && ok "共 $total 个 dex，编号 1..$total 连续无缺号 → 运行时不会中途停止加载"
fi

echo
echo "########## 2. 反汇编验证关键类与注入 ##########"
if [ ! -f "$BAKSMALI" ]; then
  echo "  （跳过：缺 $BAKSMALI）"
else
  unzip -q -o "$APK" 'classes*.dex' -d "$W"
  for d in $(cat "$W/dexlist.txt"); do
    java -Xmx3g -jar "$BAKSMALI" d "$W/$d" -o "$W/smali_$d" >/dev/null 2>&1
  done

  echo "--- (a) App.onCreate 注入 Boot.init ---"
  boot_hit=0
  for d in $(cat "$W/dexlist.txt"); do
    hit="$(grep -rl 'Ljet/android/Boot;->init' "$W/smali_$d" 2>/dev/null | head -5)"
    if [ -n "$hit" ]; then
      ok "$d 中找到 Boot;->init 调用："
      echo "$hit" | sed "s|$W/smali_$d/|      |"
      boot_hit=1
    fi
  done
  [ "$boot_hit" -eq 0 ] && bad "未找到 Boot.init 注入 → 网关不会被拉起"

  echo "--- (b) 关键类落位 ---"
  for cls in \
    "jet/android/Boot.smali" \
    "jet/android/JetHubActivity.smali" \
    "jet/android/JetHubMenuAction.smali" \
    "jet/android/JetHubMenuLabel.smali" \
    "io/legado/app/help/audiobook/scriptbrain/JetHubCanonicalClamp.smali" ; do
    hit="$(find "$W" -path "*/smali_*/*$cls" 2>/dev/null | head -1)"
    if [ -n "$hit" ]; then
      ok "$cls  →  $(echo "$hit" | sed 's|.*/smali_||; s|/.*||')"
    else
      bad "$cls  →  缺失"
    fi
  done

  echo "--- (c) 补丁效果 ---"
  # 注意：补丁标记形如 `# JETHUB-REFRESH-PATCH` 是 smali 注释，dex 会丢弃，
  # 编译后不可能搜到。这里改为校验补丁的真实效果。
  # patch-refresh 的效果：AiApiControlActivity 多出一个 onResume()V 方法。
  # 必须按路径找类文件本身；grep -rl 找到的是「引用」该类的文件。
  c="$(find "$W" -path '*io/legado/app/ui/book/read/config/AiApiControlActivity.smali' 2>/dev/null | head -1)"
  if [ -n "$c" ]; then
    if grep -q '^\.method public onResume()V' "$c"; then
      ok "patch-refresh 生效（AiApiControlActivity 有 onResume）"
    else
      bad "patch-refresh 未生效（AiApiControlActivity 缺 onResume）"
    fi
  else
    bad "找不到 AiApiControlActivity.smali"
  fi
  # patch-overlap 的效果：辅助类存在 + 它被 RoleTimbrePrelabel 引用。
  clamp="$(find "$W" -path '*scriptbrain/JetHubCanonicalClamp.smali' 2>/dev/null | head -1)"
  if [ -n "$clamp" ]; then
    rtp="$(find "$W" -path '*scriptbrain/RoleTimbrePrelabel.smali' 2>/dev/null | head -1)"
    if [ -n "$rtp" ] && grep -q 'JetHubCanonicalClamp' "$rtp"; then
      ok "patch-overlap 生效（辅助类已注入且被 RoleTimbrePrelabel 调用）"
    else
      bad "patch-overlap 辅助类存在但未被调用"
    fi
  else
    bad "patch-overlap 未生效（缺 JetHubCanonicalClamp）"
  fi
  # patch-audiocomic 的效果：deliver() 里「图片未就绪」不再抛未捕获异常，改为走
  # p7 错误回调。判据有两条，缺一不可：
  #   1) 旧的 throw 助手类（Lokio/CipherSink$$ExternalSyntheticBUOutline0;->m(String,Object)V）
  #      在该文件里彻底消失——它只被这一处抛错路径使用；
  #   2) 新路径把异常构造出来并交给 p7。
  # 不做这一条检查的话，一张过小/写坏的图片会穿透到协程、取消整章配图流水线
  # （IllegalArgumentException: audio comic image is not stable）。
  acl="$(find "$W" -path '*help/audiobook/AudioComicImageDeliveryFastLane.smali' 2>/dev/null | head -1)"
  if [ -n "$acl" ]; then
    if grep -qF 'Lokio/CipherSink$$ExternalSyntheticBUOutline0;->m(Ljava/lang/String;Ljava/lang/Object;)V' "$acl"; then
      bad "patch-audiocomic 未生效（deliver 仍抛未捕获异常）"
    elif grep -qF 'invoke-interface {p7, p1}, Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;' "$acl" \
      && grep -qF 'new-instance p1, Ljava/lang/IllegalArgumentException;' "$acl"; then
      ok "patch-audiocomic 生效（图片未就绪改走 p7 错误回调，不再取消整章流水线）"
    else
      bad "patch-audiocomic 半生效（旧抛错已去除但新回调缺失）"
    fi
  else
    bad "找不到 AudioComicImageDeliveryFastLane.smali"
  fi
  # OpenCode 适配器：类落位 + 三个最易写错的实现点。
  #   - session id 形状（ses_ + 12hex + 14base62）：写成随机 26 位会产出 38 字符，
  #     匿名通道一律 403 FreeTierError，而付费通道正常 ⇒ 只测免登录才会暴露；
  #   - 形状门禁（tools 必须含 bash/read）：漏了就整条匿名通道不可用；
  #   - 错误分类（额度/余额在 auth 之前判）：写错会让用户看到「服务端故障」并无限重试。
  ocp="$(find "$W" -path '*jet/core/OpencodeProvider.smali' 2>/dev/null | head -1)"
  if [ -n "$ocp" ]; then
    oc_dex="$(echo "$ocp" | sed 's|.*/smali_||; s|/.*||')"
    ok "OpencodeProvider 已打入 $oc_dex"
    for m in deriveSessionId ensureFreeLaneShape classifyError parseRetryAfterMs buildPayload; do
      grep -q "^\.method.*$m" "$ocp" || bad "OpencodeProvider 缺方法：$m"
    done
    oc_ids="$(find "$W" -name 'AccountManager$OpencodeIdentity.smali' 2>/dev/null | head -1)"
    if [ -n "$oc_ids" ]; then
      ok "AccountManager 已登记 opencode 平台（含 OpencodeIdentity）"
    else
      bad "AccountManager 缺 OpencodeIdentity → 平台不会出现在管理页"
    fi
  else
    bad "找不到 OpencodeProvider.smali → opencode 平台未编进包"
  fi
fi

echo
echo "########## 3. 入口可达性 ##########"
AAPT2=""
BT="$(jethub_resolve_build_tools)"
if [ -n "$BT" ] && [ -x "$BT/aapt2" ]; then
  AAPT2="$BT/aapt2"
elif [ -n "$BT" ] && [ -x "$BT/aapt2.exe" ]; then
  AAPT2="$BT/aapt2.exe"
fi

if [ -n "$AAPT2" ]; then
  if "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null | grep -qi 'jet\.android\.JetHubActivity'; then
    ok "AndroidManifest 已注册 jet.android.JetHubActivity"
  else
    bad "AndroidManifest 未注册 JetHubActivity → 网关在跑但点不进去"
  fi
else
  echo "  （跳过 Manifest 检查：找不到 aapt2）"
fi

if unzip -l "$APK" | grep -q 'assets/jethub/home.html'; then
  ok "assets/jethub/home.html 已打入（$(unzip -l "$APK" | grep 'assets/jethub/home.html' | awk '{print $1}') 字节）"
  # home.html 是明文资源（不走 aapt2 编译），可直接 grep。
  # 管理页少了 opencode 的卡片/表单，平台即使编进包里用户也点不到。
  hm="$(unzip -p "$APK" assets/jethub/home.html 2>/dev/null)"
  if echo "$hm" | grep -q 'data-oc-add' && echo "$hm" | grep -q 'k-opencode'; then
    ok "管理页含 OpenCode 卡片（API Key 表单 + 配色）"
  else
    bad "管理页缺 OpenCode 卡片 → 平台在网关里但没有入口"
  fi
else
  bad "assets/jethub/home.html 缺失 → 管理页打不开"
fi

echo
echo "########## 4. 包身份与签名 ##########"
if [ -n "$AAPT2" ]; then
  "$AAPT2" dump badging "$APK" 2>/dev/null | grep -E '^(package|targetSdkVersion)' | sed 's/^/    /'
  lbl="$("$AAPT2" dump badging "$APK" 2>/dev/null | grep -m1 '^application-label:' | cut -d: -f2-)"
  [ -n "$lbl" ] && echo "    应用名: $lbl"
fi

sigout="$(jethub_apksigner verify --print-certs "$APK" 2>&1)"
if echo "$sigout" | grep -q 'Signer #1 certificate DN'; then
  echo "$sigout" | grep -E 'Signer #1 certificate (DN|SHA-256)' | sed 's/^/    /'
else
  bad "签名校验失败："
  echo "$sigout" | sed 's/^/      /'
fi

echo
echo "########## 结论 ##########"
if [ "$fail" -eq 0 ]; then
  echo "  ✓ 全部检查通过：分包连续、入口齐备、签名有效"
else
  echo "  ✗ 存在未通过项（见上方 ✗）"
fi
exit "$fail"
