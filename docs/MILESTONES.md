# JetHub 内置版墨听 — 里程碑日志

> 这是开发过程的原始记录，按时间顺序追加。文中的绝对路径（`/opt/android-sdk/...`、
> `apk/`、`build.sh` 等）是当时机器上的实际布局，与现在仓库的目录结构（`scripts/`、
> `patches/`、`toolchain/`）不完全一致，读的时候以 [README](../README.md) 为准。

把 jethub 的供应商适配器（认证 / token 刷新 / 模型发现 / 对话转发）以原生 Java 移植进墨听 APK，
凭据全部存手机本地 App 私有目录（`/data/data/<包名>/files/jethub/`），不依赖任何外部主机。

## 当前状态（里程碑 2：Cline + Trae 双供应商）

- ✅ Cline / Trae / Buddy(CodeBuddy) / WorkBuddy / MiniMax 五家：续期、模型目录、对话转发全通
- ✅ 手机自助登录 4 家：Cline（设备码）、MiniMax（设备码+PKCE）、CodeBuddy/WorkBuddy（浏览器轮询）、Trae（短信/扫码回调）
- ✅ 内嵌网关 `127.0.0.1:8391`：模型 id 带 `cline/` `trae/` `buddy/` `workbuddy/` `minimax/` 前缀，chat 按前缀路由
- ✅ host JVM 真实凭据端到端全通（models 535、非流式、流式 chunk 转换）
- ✅ 产出签名 APK：`墨听-4.0.20-jethub内置版.apk`
- ⏳ 真机验证；剩余供应商（Qoder 需 WASM 加密绕过、DeepSeek/ZCode 等）待移植

## 机制

1. 墨听 `App.onCreate()` 注入一行 `invoke-static {p0}, Ljet/android/Boot;->init(...)`，启动 loopback 网关（8391）。
2. Cline：手机浏览器打开 `http://127.0.0.1:8391/` → 点「登录 Cline」完成设备码授权。
3. 在墨听 AI 模型中心添加自定义中转账号：Base URL `http://127.0.0.1:8391/v1`，API Key 为管理页展示的本地密钥。
4. 点「获取模型」→ 目录含 cline/* 与 trae/*；对话由网关按前缀转发对应供应商并自动续期 token。

## 目录结构

- `src/jet/core/` — 纯 Java 内核（host JVM 可直接测试）：HttpFetcher / CredentialStore / ClineProvider / GatewayServer
- `src/jet/android/Boot.java` — Android 壳（smali 注入入口）
- `src/test/` — Smoke / DeviceLoginTest / E2E / Chat2 / StreamTest / GwE2E（host 测试，classpath 加 `toolchain/json.jar`）
- `apk/` — moting.apk、smali4（反汇编的 classes4，App.smali 已注入）、构建中间产物、签名 APK
- `toolchain/` — r8.jar（d8）、android-34.jar、json.jar（仅 host 测试用）
- `build.sh` — 一键构建流水线

## 测试（host JVM）

```bash
javac -source 11 -target 11 -cp toolchain/android-34.jar -d build/classes $(find src -name '*.java' ! -path '*/test/*')
javac -cp build/classes:toolchain/json.jar -d build/classes src/test/*.java
java -cp build/classes:toolchain/json.jar Smoke   # 网关鉴权/路由/坏输入
java -cp build/classes:toolchain/json.jar GwE2E   # 真实凭据端到端（需先导入凭据到 /tmp/jethub-smoke-credentials.json）
```

## 工具链

| 工具 | 位置 |
|---|---|
| d8/r8 | `toolchain/r8.jar`（阿里云镜像 com.android.tools:r8:8.3.37） |
| android.jar | `toolchain/android-34.jar`（Sable GitHub） |
| smali/baksmali | `/tmp/smalitool/{smali,baksmali}.jar`（baksmali 注意：`d moting.apk` 只反汇编 classes.dex，classes4 需先单独解出再 `d`） |
| zipalign/apksigner | `/opt/android-sdk/build-tools/34.0.0/` |
| adb | `/opt/android-sdk/platform-tools/adb` |

## 下一步（移植其余供应商）

按 ClineProvider 模式每家一个 Provider 类 + GatewayServer 注册路由。认证普查结论：12 家全部 refresh_token 静默续期（纯 HTTP 可移植）；登录三分类：设备码+PKCE（Minimax/Accio）、浏览器回调（Gemini/Cline）、短信（Loomy）；Trae 用 ExchangeToken 轮换；Qoder 续期载荷 `{refresh_token, machine_id}`。
- 管理页（127.0.0.1:8391/）新增「登录 MiniMax Code」按钮 + /jethub/mm 状态端点：手机浏览器可直接完成设备码+PKCE 授权（实测申请/轮询通过，授权完成即落盘凭据）

## 里程碑 7：应用内页面 + 一键写入模型中心（仿「网页转API」）

- 新增 `jet.android.JetHubActivity`：内嵌 WebView 加载本机网关管理页（`http://127.0.0.1:8391/`），桌面图标「JetHub 模型中心」直达；页内完成 Cline 设备码 / MiniMax 设备码 / CodeBuddy / WorkBuddy / Trae 五种登录（Trae 回调直接落回本机网关，无需外部浏览器）。
- 新增 `jet.core.ModelCenterRegistrar`：「一键写入 AI 模型中心」按钮 —— 经墨听自己的 `AiModelCenterBackup.exportJson/restoreJson` 读-合并-校验写回：
  - 写入 1 个中转账号（baseUrl=`http://127.0.0.1:8391/v1`，apiKey=网关本地密钥，canonical id）；
  - 写入网关当前全部模型与线路（id 与墨听「获取模型」算法一致，点「获取模型」原地刷新不重复）；
  - 仅在 `GLOBAL_TEXT_DEFAULT` / `ASSISTANT_CHAT` 完全缺失时补默认绑定，绝不覆盖用户已有配置；
  - 幂等：重复点击不产生重复条目。
- 构建切换到 `build-full.sh`：apktool 全量回编（PVE 承担解包/回编，host 承担编译/签名），manifest 注册 Activity，9 个 dex。
- 单测 `RegTest`：首次合并/幂等/用户配置保留三场景全 PASS。

## 里程碑 6：Trae 应用内登录（手机自助）

- TraeProvider 新增 buildLoginUrl / handleLoginCallback / deviceIdentity（machine_id 32hex + device_id 16 位数字持久化，避免换指纹触发风控）
- console 授权页（https://www.trae.cn/authorization?...）支持短信/扫码；授权后 console 302 回调到
  `http://127.0.0.1:8391/jethub/trae/callback`（手机浏览器与网关同机 loopback 可达，无需外部服务器），
  网关从 query 提取 refreshToken/userInfo → ExchangeToken 换正式凭据落盘
- 管理页新增「登录 Trae（短信/扫码）」按钮 + /jethub/trae/login/start、/jethub/trae/callback 端点
- 实测：device_id 生成正确、login URL 全参数正确（含 auth_callback_url 编码）、假 token 回调被上游 401 正确拒绝、网关 POST start 200

## 里程碑 5：手机自助登录扩展（CodeBuddy / WorkBuddy）

- BuddyProvider 新增 startLogin/pollLogin：POST /v2/plugin/auth/state?platform=… 取 state+authUrl → 用户浏览器授权 → GET /v2/plugin/auth/token?state 轮询（11217=未就绪继续），成功后尽力补 login/account（enterpriseId/nickname）
- WorkBuddy 授权链接自动附加 version=5.5.2 + loginSessionId（appendSessionParams）；expiresAt 绝对/相对秒/JWT exp 三级兜底解析
- 网关新增 /jethub/buddy、/jethub/workbuddy 状态端点与 login/start（后台 1s 自动轮询）；管理页新增两个登录按钮
- 实测：两产品 auth/state 真实申请成功（授权链接正确、WorkBuddy 附加参数齐全）、未授权轮询 PENDING、网关冒烟通过

## 里程碑 4：MiniMax Code（设备码+PKCE，可手机自助登录）

- MinimaxProvider.java：accountHost=account.minimax.cn（OAuth）、apiHost=agent.minimax.cn；clientId=mcode-public、audience=agent-backend、scope=agent.default
- 续期：POST /oauth2/token（form：grant_type=refresh_token），access_token 为 mmoat_ 非 JWT，过期以 expires_in 为准；凭据兼容 expires_at（毫秒字符串）
- 模型：GET /mavis/api/v1/models → providers[providerId=minimax].config.models（对象表，key=长 id），按 model_order 排序
- 对话：POST /mavis/api/v1/llm/v1/messages —— Anthropic messages 格式、强制 stream:true；MinimaxSseConverter 做 SSE 双向转换（text_delta/thinking_delta → content/reasoning_content，错误双信封兼容）
- 实测（真实账号）：续期轮换 200、目录 4 模型、对话 200「收到」；OpenAI 请求自动抽 system、补 max_tokens
- ⚠️ 已知坑：refresh_token 单次有效且会话互斥 —— 同一账号不能同时在主机 jet-hub 与手机登录（各设备应独立登录自己的账号）
- 网关联测路由 minimax/ 前缀正确（上游 401 透传验证）；合并目录在 minimax 段有效时为 596

## 里程碑 3：Buddy（CodeBuddy 腾讯）+ WorkBuddy（国际版）

- BuddyProvider.java：一家适配器参数化两个产品（copilot.tencent.com/codebuddy 与 www.workbuddy.ai/workbuddy）
- 刷新：POST /v2/plugin/auth/token/refresh（X-Refresh-Token 头，{code,message,data} 信封，401/403/expired/invalid 判终态）
- 模型：/console/enterprises/personal/models 优先 + /v3/config 合并（isChatModel 三重过滤）；WorkBuddy 需 UA "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2"（v3/config 校验 UA）
- 对话：/v2/chat/completions 标准 OpenAI SSE；上游强制 stream:true（网关对非流式请求自动聚合）；WorkBuddy 硬性要求 messages[0]=system（网关自动注入兜底 system）
- 网关 handleModels 改为全供应商容错合并（任何一家未登录/失败不阻塞目录）
- 网关联测：592 模型合并（cline 466 + trae 69 + buddy 31 + workbuddy 26 + 兜底），buddy/workbuddy 流式与非流式对话全通


## 里程碑 8：入口重构（用户实机反馈三项）

用户实机测试反馈三条，本轮全部落地：

### ① 修 8391 打不开（真实 bug）

`GatewayServer.start()` 原用 `InetAddress.getLoopbackAddress()` 绑端口 —— 部分 Android 上它返回 IPv6 `::1`，
此时只监听 IPv6，浏览器访问 `http://127.0.0.1:8391`（IPv4）必然连不上。

现改为显式 `InetAddress.getByName("127.0.0.1")`，只绑 IPv4 回环。

同时把 `Boot.init` 的静默失败改为可观测：
- 记录 `lastError`（异常类名+消息）与完整堆栈 `trace`；
- 落盘 `/data/data/com.applecabal.njread/files/jethub/boot.log`（started/port/error/time + 堆栈）；
- `JetHubActivity` 顶部状态栏直接显示「运行中」或「未启动 + 原因 + 日志路径」。

用户侧「打不开又没有任何提示」的问题从此可自查。

### ② 取消桌面图标

`AndroidManifest.xml` 里 `JetHubActivity` 移除 `MAIN`/`LAUNCHER` intent-filter，
改为 `android:excludeFromRecents="true"`（不占最近任务列表）。

### ③ 入口移入《AI 模型与接口中心》

在模型中心右上角下拉菜单里，《管理网页转 API》下方插入《管理平台代理 API》：

- 菜单项文案与点击行为由两个注入类提供（`smali_classes10`，不进 Java 源码）：
  - `jet/android/JetHubMenuLabel.smali` —— Compose 文案，调用 `AppTextKt.AppText-Nvy7gAk("管理平台代理 API", …)`
  - `jet/android/JetHubMenuAction.smali` —— 点击后 `startActivity(JetHubActivity)`（FLAG_ACTIVITY_NEW_TASK）
- 注入点：`MotingAiModelCenterScreenKt.smali` 的 `MotingAiModelCenterScreen$lambda$120$2$1$2` 方法内，
  紧跟在 line 529 的 `AppDropdownMenuItem` 之后（即《管理网页转 API》之后、《添加模型与接口》之前）。
- 复现脚本：`patch-ui.py`（幂等，带 `# JETHUB-MENU-INJECTED` 标记）。

菜单顺序实测：管理已接入服务 → 管理网页转 API → **管理平台代理 API** → 添加模型与接口 → …

### 产物核验（对 APK 二进制直接验，非源码自证）

| 项 | 核验方式 | 结果 |
|---|---|---|
| 桌面图标已去掉 | `aapt2 dump xmltree` 看 JetHubActivity 节点 | 无 intent-filter，`excludeFromRecents=true` |
| 菜单入口已注入 | `strings classes7.dex \| grep JetHubMenu` | 命中 `JetHubMenuLabel`/`JetHubMenuAction` |
| 胶水类已打包 | `classes10.dex` | 两个类都在 |
| IPv4 绑定已修 | `strings classes9.dex \| grep -c getLoopbackAddress` | 0（旧包为 1） |
| 诊断 API 已打包 | `JetHubActivity.smali` 反查 | 调用 `Boot.isStarted`/`lastError`/`ensureStarted` |

旧包（11:35 上传的 `moting-jethub-4.0.20.apk`）三项缺陷全在，已由新包覆盖。

---

## 里程碑 9：账号管理 · 积分签到 · 管理页美化

针对反馈「相比 jethub 还少帐号管理和积分签到功能区，UI 也太粗糙」，本轮补齐三块。

### ① 多账号（一家供应商可登多个账号）

- 新增 `AccountStore.java`：每个供应商一个「账号桶」，同身份重复登录只覆盖不新增；
  旧的单账号存储会自动迁移成桶内第一个账号。
- 新增 `AccountManager.java`，核心是 **AccountView 模式**：为每个账号造一个
  `CredentialStore` 视图 —— 读该账号的 payload、写时按身份 upsert 回桶，
  设备指纹与本地密钥等全局 ref 透传。要按某账号干活就用它的视图新建一个 Provider，
  **Provider 代码一行没改**，且天然并发安全（不会出现两个线程互相踩凭据）。
- 管理页可对每个账号执行：设为当前 / 测试连通（返回模型数）/ 刷新令牌 / 移除。

### ② 积分与签到

- 新增 `CreditsService.java`，五家能力矩阵（与 jethub 一致）：

| 平台 | 余额 | 签到 |
|---|---|---|
| Cline | ✅（USD） | ❌ 无此活动 |
| Trae | ✅ | ✅ |
| CodeBuddy | ✅ | ✅ |
| WorkBuddy | ✅ | ❌ 国际版无签到接口 |
| MiniMax | ✅ | ✅（按上报时区结算） |

- 实测余额：Cline $5.00、Trae 4598.69、CodeBuddy 6667.86、WorkBuddy 350。
- 签到支持单账号与「一键全部签到」，结果四分类：已领取 / 今日已领 / 活动未开启 / 失败。
- 幂等判据一律以**响应体业务码**为准，不看 HTTP 状态码
  （CodeBuddy 重复签到返回 HTTP 400 + code 10001，只看状态码会把「已签」误报成「失败」）。
- Trae 的设备标识（X-Device-Id / X-Market-User-Id / Vscode-Sessionid）**由 user_id 确定性派生**，
  保证同一账号永远同一套设备身份 —— 否则会被服务端判成新设备，触发「每设备每天一次」配额。
- 部分查询失败不显示成 0：查不到时界面显示失败原因（「0 积分」和「查不到」是两回事）。

### ③ 管理页美化

- 新的单文件管理页（`assets/jethub/home.html`，82KB，纯原生 JS、无外部依赖），
  深色墨纸配色、移动端优先，三个标签页：账号 / 签到 / 接入。
- 首页「一键写入 AI 模型中心」按钮通过 `addJavascriptInterface` 桥接
  （`JetHubNative.register()`）调原生 `ModelCenterRegistrar`，把网关账号与模型
  合并进墨听模型中心；在电脑浏览器打开时该按钮会提示需在 App 内使用。

### 构建

`build-full.sh` 新增一步：把 `assets/jethub/home.html` 同步到 PVE 解包树，
随 apktool 回编进 APK。

### 产物核验（对 APK 二进制直接验）

| 项 | 核验方式 | 结果 |
|---|---|---|
| 管理页已打包 | `unzip -l` 看 `assets/jethub/home.html` | 82807 字节 |
| 桥接对象已打包 | `strings classes9.dex \| grep JetHubNative` | 命中 |
| 菜单入口仍在 | `strings classes7.dex \| grep -c JetHubMenuLabel` | 1 |
| 桌面图标仍未回退 | `aapt2 dump xmltree` | 无 intent-filter，`excludeFromRecents=true` |
| 签名 | `apksigner verify --print-certs` | CN=JetHub Embedded |

回归：`RegTest` 合并逻辑 PASS、`UiE2E` 11/11、`BalCkTest` 五家余额+签到实测通过、
`GwFull` 网关 591 模型且 trae/buddy/workbuddy 真实对话 200。

## 里程碑 10：去掉重复外壳 · 授权链接可返回 · 修 MiniMax 登录不落库

用户实机反馈（原话）：「上面外围的不要了，直接加载里面的，功能重复了。另外里面点进
供应商链接登陆后没有返回按钮，minimax强制返回登录信息没有保存」。

### ① 去掉原生外壳，整屏就是管理页

`JetHubActivity` 原先在 WebView 上方又摆了一条状态栏（「内嵌网关运行中：127.0.0.1:8391」）
和「一键写入 AI 模型中心」「重试」两个按钮 —— 而这三件事网页里本来就有
（页头状态徽标、`#btn-register`、`#btn-retry`）。两套并存的结果是用户分不清哪个是真的，
还白白吃掉手机屏幕顶部一大条。

现在整屏只有一个 WebView。原生的那三件事只保留**网页打不开时**的兜底错误页
（网关未启动 → 显示原因 + `boot.log` 路径 + 重试）。

### ② 供应商授权链接：改走系统浏览器 + 兜底返回按钮

两个独立成因，都修了：

- 页面渲染授权链接用的是 `<a target="_blank">`。Android WebView 默认
  `setSupportMultipleWindows(false)` 会把它当**同窗导航**，管理页被顶掉，
  用户授权完回不到原页面 —— 这就是「没有返回按钮」的现场。
  现在 `JetHubActivity` 在捕获阶段拦下所有站外 `<a>` 点击，交给
  `JetHubNative.openUrl()` 用系统浏览器打开；管理页原地不动，它自己的轮询
  继续跑，授权完成即自动出现账号。`shouldOverrideUrlLoading` 同时兜住
  非 `<a>` 形式的跳转。
- WebView 真的产生历史时（例如 Trae 的 loopback 回调页），右下角浮出「返回」，
  不指望用户知道系统返回键等于 WebView 后退。
- `onResume` 里补派发一次 `visibilitychange`：Android WebView 不保证该事件在
  Activity 恢复时触发，少了它用户从浏览器切回来会看到「还在等待授权」，
  以为登录没保存（其实凭据已经落库）。

### ③ MiniMax 登录不落库（真实 bug，一行括号写错）

`MinimaxProvider.startDeviceLogin()` 里：

```java
// 错：5 秒变成 1000 秒
a.intervalMs = Math.max(1000, o.optLong("interval", 5)) * 1000L;
// 对：先换算成毫秒，再取下限
a.intervalMs = Math.max(1000L, o.optLong("interval", 5) * 1000L);
```

`interval` 字段单位是**秒**。写成 `Math.max(1000, interval) * 1000` 后，
服务端给的 5 变成 1000（秒），而后台轮询线程第一步就是 `Thread.sleep(interval)` ——
设备码 600 秒就过期，轮询永远来不及触发一次，用户授权完凭据自然不落库。
（`Math.max` 本身没问题，`1000` 这个下限是毫秒量级、和秒级的 `interval` 混用了。）

顺带修掉一个同源缺陷：`MinimaxIdentity` 原先把 `access_token` 前 24 字符当账号身份，
而 MiniMax 的 token 每次续期都换新值 —— 每续期一次就多出一个账号。
现在优先用 `nickname`，拿不到就退化成稳定常量。

### 产物核验（对 APK 二进制直接验）

| 项 | 核验方式 | 结果 |
|---|---|---|
| 旧外壳已移除 | `grep -ac "内嵌网关运行中" classes9.dex` | 0 |
| 新错误页已打包 | `grep -ac "内嵌网关未启动" classes9.dex` | 1 |
| 兜底返回按钮 | `grep -ac "返回" classes9.dex` | 2 |
| 外链桥接 | `strings classes9.dex \| grep -c openUrl` | 2 |
| 注入脚本已打包 | `strings classes9.dex \| grep -c __jhExt` | 命中 |
| interval 修复 | baksmali 反汇编：`optLong("interval",5)` → `mul-long 1000` → `Math.max(1000, ·)` | 确认 |
| 管理页已打包 | `unzip -l` 看 `assets/jethub/home.html` | 82807 字节 |
| 菜单入口仍在 | `aapt2 dump xmltree` | `jet.android.JetHubActivity` 在册 |

回归：`RegTest` PASS、`UiE2E` **11/11**。


---

## 里程碑 11：模型管理 · 倍率显示 · 写入后自动刷新 · 余额签到点击查询

对应反馈：「1.还缺每个供应商的模型管理功能。2.写入 ai 模型中心后模型的倍率没有显示。3.写入 ai 模型中心后要刷新一下模型中心中的模型。余额和签到为什么一直是未查询？增加个点击查询的功能。」

### ① 每个供应商的模型管理（黑名单制）

jethub 的 `model.list` / `model.setDisabled` 是**黑名单制**：表里没有 = 可用，只有显式关闭才算关闭。
本工程照此实现，键用网关目录的完整 id（`buddy/hy3`、`cline/…`）。

新增 `src/jet/core/ModelRegistry.java`：存 `{provider:[id,...]}`，与凭据共用 `CredentialStore`。
- `setDisabledMany` 一次读一次写 —— 前端循环调 `set` 会发 N 次请求、写 N 次。
- 解析失败**视为空表**：宁可全部可用，也不要因一个坏文件把模型全藏了。

网关新增端点（`GatewayServer.handleAdmin`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/jethub/models` | 按供应商分组，**不套黑名单**（设置页要能重新打开已关闭的模型） |
| POST | `/jethub/models/set` | 单个开关 |
| POST | `/jethub/models/set-many` | 批量 |
| POST | `/jethub/models/reset` | 该供应商全部恢复 |

目录分两层：`catalogAll()`（全量，管理页用）→ `catalogData()`（套黑名单，`/v1/models` 与写入模型中心用）。
管理页新增第四个 tab「模型」：按供应商折叠卡片，每张卡带全部开启 / 全部关闭 / 重置，
单条按 `MODEL_PAGE=60` 分页渲染，避免一次插入上千节点。

### ② 倍率显示

墨听的模型条目**没有倍率字段**（`AiModelEntry` 只有 id/displayName/capability/…），
所以倍率只能写进 `displayName` —— 这也正是 jethub 的既有约定（`accio-adapter.ts`：
「倍率写进 description 等于没写」，因为切换菜单只渲染 `name`）。

新增 `src/jet/core/ModelRate.java`（纯函数，host 可测）：

| 供应商 | 字段 | 解析要点 |
|---|---|---|
| Buddy / WorkBuddy | `data.models[].credits` | 实测形态 `"x0.00 credits"`；先试 `^x数字` 前缀，再试 `数字x` 后缀 |
| Trae | `display_contact_config` | **该字段是字符串，内容又是一段 JSON**，必须二次 parse；`enable:false` 视为无倍率；**rate 0 合法**（免费），不能用 `>0` 过滤 |
| Cline | id 形态 | `:free` 结尾 / `cline-free/` 前缀 / `isFree:true` → 拼「· 免费」 |
| MiniMax | 无 | 照抄远端 name（与 jethub 一致） |

三条纪律（都来自 jethub 被用户报障纠正过的结论）：
- **解析不出返回 null，不回退成 x1** —— 编造倍率比不显示更糟。
- **倍率 0 保留**（免费是合法倍率，与「没有倍率信息」语义不同）。
- **幂等**：裸名已含 ` · ` 就不再拼，防上游已拼过变成 `Hy3 · x0.29 · x0.29`。

实测贯通：591 个模型中 **135 个**带倍率/免费标注（trae 69 个里 66 个、buddy 31 里 29、workbuddy 26 里 24、cline 465 里 16）。
样例：`Seed-Code · x0.06`、`Doubao-Seed-2.0-Code · x0.39`、`MiniMax-M2.7 · x0.27`。

### ③ 写入后自动刷新模型中心

`AiApiControlActivity` 的 `aiModelCenterBackupRevision` 是模型中心屏幕加载协程的 key ——
smali 里 `v21` 既作 `LaunchedEffect` 的 key，又作加载协程的 `$backupRevision` 字段。
该 Activity **原本只有 `onCreate` 和 `onWindowFocusChanged`，没有 `onResume`** ⇒ 注入点干净。

补丁（`patch-refresh.py`，幂等，标记 `# JETHUB-REFRESH-PATCH`）：

```smali
.method public onResume()V
    .locals 2
    invoke-super {p0}, Landroidx/appcompat/app/AppCompatActivity;->onResume()V
    invoke-direct {p0}, ...AiApiControlActivity;->getAiModelCenterBackupRevision()I
    move-result v0
    add-int/lit8 v0, v0, 0x1
    invoke-direct {p0, v0}, ...AiApiControlActivity;->setAiModelCenterBackupRevision(I)V
    return-void
.end method
```

从 JetHub 页返回模型中心即触发重组 + `AiModelCenterStore.load()`，新模型立刻出现，不用手动重进。

### ④ 余额 / 签到「一直是未查询」

**根因**：页面 `api.accounts()` 从不带 `credits=1`，而后端只在 `credits=1` 时才补查余额/签到 ⇒ 永远走不查分支。

修法（三处，缺一不可）：
1. `api.accounts(withCredits)` 支持 `?credits=1`。
2. `boot()` 与**登录成功后**各自动补查一次；**不进 30 秒心跳**（每 30 秒打上游太重）。
3. **`mergeCredits(fresh, prev)`** —— 心跳那条不带 credits，响应里没有 balance/checkin 字段，
   直接覆盖会把已查到的余额抹回「暂未查询」。缺字段时沿用旧值。

手动查询：余额/签到单元格变可点（`data-act="credits"` + 刷新图标 + `aria-label`），
点击走 `POST /jethub/accounts/credits`（服务端先 `invalidate` 清缓存再查）。

### 回归

| 测试 | 结果 |
|---|---|
| `RegTest` | REG PASS（幂等合并、用户数据保留） |
| `UiE2E` | **11 / 11** |
| `ModelMgmtTest` | **52 / 52**（倍率边界、黑名单语义、端点契约） |
| `BalCkTest` | Cline $5.00 · Trae 4598.68 · Buddy 6727.86 · WorkBuddy 350 |
| `RateE2E` | 591 模型 / 135 带倍率 |
| `DnTest` | 写入模型中心的 displayName 带倍率 **135** 个 |
| `HttpCredits` | 四个端点走真实 HTTP 全通过 |

### 二进制核验（对最终 APK）

| 项 | 方式 | 结果 |
|---|---|---|
| 新类已打包 | `grep -ac ModelRegistry classes9.dex` | 2 |
| 倍率解析已打包 | `grep -ac ModelRate classes9.dex` | 2 |
| 模型端点已打包 | `grep -ac /jethub/models classes9.dex` | 1 |
| 批量端点已打包 | `grep -ac set-many classes9.dex` | 1 |
| 管理页已打包 | `unzip -l` | 109349 字节 |
| 页面含模型 tab | `grep -ac 'data-tab="models"'` | 1 |
| onResume 补丁 | baksmali 反汇编 classes6 | 方法体在，`getAiModelCenterBackupRevision` +1 |
| 包名 / 签名 | aapt2 / apksigner | `com.jethub.moting` · v2+v3 通过 |

### 最终产物指纹（里程碑 11 交付）

| 项 | 值 |
|---|---|
| 文件 | `墨听-4.0.20-jethub内置版.apk` |
| 大小 | 70,488,726 字节 |
| sha256 | `c7e39bbca94948d3deb585baeb128b3b8a662cd99928304e77f591ab8703aabc` |
| 管理页 | `assets/jethub/home.html` 110,155 字节 / 2466 行 |
| 包名 | `com.jethub.moting`（用户要求改包名后） |
| 签名 | v2 + v3 通过，CN=JetHub Embedded |

> 注意：该版页面由前端子代理在我首次打包**之后**才定稿（22:12 写入，我 22:03 打的包），
> 已重新打包并核验：包内页面 110155 字节、`data-tab="models"`/`panel-models`/`mergeCredits`/
> `supportsCheckin`/`MODEL_PAGE` 全部命中，classes9 的 `ModelRegistry`/`ModelRate`/`/jethub/models`/
> `set-many` 全部在位，classes6 的 `onResume` 补丁方法体在位。

### 无外部依赖核验

```
grep -rn "111\.229\.221\.200|jetgw|8326|8327" src/ assets/jethub/home.html
→ 空
```

所有上游请求由内嵌内核直连各供应商官方域名；墨听侧只连 `127.0.0.1:<port>`。
