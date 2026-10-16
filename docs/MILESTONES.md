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
X

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

## 里程碑 12：修「个别章节预标注每轮必报 canonical units overlap」

对应反馈：「有 2 个章节标注始终报错，帮忙分析和解决下。」

### ① 现象与判据

第 250 章「第七十一章 心意」与第 251 章「第七十二章 朋友」后台预标注**每一轮**都停在
【角色记忆读取】之后，永远走不到 `【QUOTE_CLASSIFY】` / `【本地初筛】`：

```
canonical units overlap or are out of order: sequence=81 start=1961 previousEnd=1971   （第 250 章）
canonical units overlap or are out of order: sequence=88 start=1594 previousEnd=1595   （第 251 章）
```

5 轮重试全部复现同一断点 → 确定性算法缺陷，与模型 / 网络 / 角色记忆无关。

日志按章对照后否掉了两个直觉候选：`crossParagraph=1` 也出现在成功的第 246/248 章，
**不是区分因子**；`LONG_QUOTE blocks` 第 250 章是 53（完全正常），第 251 章 85（全场最高）
但也不是根因。全部章节 `unclosed=0 syntheticClose=0`。

### ② 定位：唯一抛错点

```
CanonicalChapterUnitStore.exactUnitRecords(String chapterText, List<ExactUnit>)   :2378
  .line 1208  getRawStart() → v2
              if-lt v2, v15, :cond_10     # v15 = previousEnd，初值 -1
  .line 1226  v15 = getRawEnd()           # 唯一的 previousEnd 更新点
```

同循环还要求 `text == chapterText.substring(rawStart, rawEnd)`。
报错由 `CanonicalChapterUnitStore$$ExternalSyntheticBUOutline0;->m` 拼串后抛 `IllegalArgumentException`。

### ③ 根因：enrich 的回滚分支丢了排序与区间减法

`enrichTimbreLinesWithQuoteDialogueBlocks$app`（`RoleTimbrePrelabel.smali:34044`）末尾有三个
自检旗标（`:35976-35982`）：`v2=invalidExact`、`v3=invalidCoverage`、`v4=overlapPair`。
任一成立即 `goto :goto_2b` 走**回滚分支**（`:36253-36446`）：

```
.line 1502  new QuoteDialogueEnrichment(p3, emptyMap(), 0)
            ^^^ p3 = enrich 的入参 base lines
```

正常路径（`:cond_33`）返回的是 `.line 1447` `sortedWith` 排序后、且做过
`subtractQuoteRawRanges` 引号区间减法的结果。**回滚路径返回未排序、未做减法的原始 base lines**
→ 旁白行的区间没有把内嵌对话区间挖掉 → 旁白行与其内部对白行区间重叠 → 穿透到
`exactUnitRecords` 的 `.line 1208`。

回滚只写 `Log.i` / `Log.w`（进 logcat），**不进预标注日志文件**——所以日志里 grep
`invalidExact` / `rollback` 全空，最初因此误判成「模型调用阶段的问题」。

### ④ 补丁落点：ExactUnit 构造循环

候选落点逐个排除：

| 候选 | 排除理由 |
|---|---|
| `exactUnitRecords` 本身 | 改它等于放宽校验，掩盖问题 |
| `normalize` | 不排序不去重；`ScriptBrain$Analysis` 只有 `chapterTextHash`、**没有 `chapterText` 字段**，拿不到正文无法重切片 |
| `enrich` 回滚分支 | 该分支只有 base lines，同样没有 `chapterText` |
| **`prepareTimbreV895PrelabelChapter$app` 的 ExactUnit 构造循环** | ✅ 最后一个同时握有 `chapterText`（`v7`，`:39164`）与最终偏移（`v26/v27/v28`）的地方 |

`materializeInitialIn` 对首单元有五连断言（必须是章节标题行：`rawStart=0` / `rawEnd=标题长` /
`text=标题` / `partIndex=0` / `entrySeq=0`），所以**不能删改首单元**，只能收敛区间。
收敛保持两个不变式：`rawStart >= previousEnd` 且 `text == chapterText.substring(rawStart, rawEnd)`。

### ⑤ 实现：新增静态辅助类 + 宽寄存器调用

`v1..v11` 是 `:39311 materializeInitial$app` 的实参，循环内**一个都不能碰**；而
`if-*`（22t）/ `invoke-*`（35c）/ `const/4`（11n）的寄存器字段只有 4 位，只能寻址 `v0..v15`。
于是把窄寄存器运算全部下沉到新增静态类 `JetHubCanonicalClamp`（自己的 `.locals` 独立），
调用方只用 `invoke-static/range {v41 .. v45}` 传参、`invoke-virtual/range {v47 .. v47}` 取回
（`v41..v47` 在循环区间 `:39196-39311` 内全部空闲）。

补丁后 `RoleTimbrePrelabel.smali` 45748 → 45780 行（+985 字节），辅助类 2659 字节。

### ⑥ 教训：语法合法 ≠ 语义正确

这个补丁的 `if-ge` / `if-gt` / `if-ltz` 方向在编写过程中被写反过**多次**，而
`smali.jar assemble` **只检查语法不检查语义**——每次都「编译通过」（exit 0，产出正常 dex）。
人眼审查在条件跳转极性上已被证明不可靠。因此建了两层机器验证：

- `tools/verify_clamp.py`：自写小型 Dalvik 解释器执行补丁的**真实 smali**，与独立写的
  参考实现对照（A 段 10 个边界用例 / B 段 5 个跨迭代不变式 / C 段 12 项调用协议）。
  这个验证器自己也栽过：最初把 `if-gez` / `if-lez` 的真值表写反（`gez` = **greater-or-equal**，
  不是「小于等于」），导致 10 个用例全 FAIL，还让我按错的表去「修」补丁、越修越偏。
- `tests/art/`：把测试壳 + 辅助类 + `PlaybackTextUnitContract$Part` 一起 assemble 成 dex，
  `adb push` 后用真实 Android ART（`dalvikvm`）执行。**测试壳里一条条件跳转都不写**——只打印
  `caseN start=.. end=.. len=.. text=..`，断言全放宿主端 Python。因为在壳里再写一遍同样的判据，
  等于用同一个易错点验证自己。

### ⑦ 验证结果

| 层 | 内容 | 结果 |
|---|---|---|
| 解释器 | `python3 tools/verify_clamp.py` | A 10/10 · B 5/5 · C 全绿 |
| 真机 ART | `bash tests/art/run-art-test.sh`（emulator-5554） | 8/8 通过（含两个真实失败形态：重叠 1 / 重叠 10） |
| 干净树 | 还原 → `--check` → 实打 → 幂等重跑 | exit 0，+985 字节，重复跑打印「跳过」 |
| 编译 | `smali.jar assemble -a 34` | 辅助类 904 字节 dex · 目标文件 175904 字节 dex |
| 全树回编 | `apktool b full-decoded` | exit 0，70272004 字节，dex 编号连续、包名不变 |

完整定位过程另见 [`OVERLAP-FIX.md`](OVERLAP-FIX.md)。

## 里程碑 13：移植 OpenCode (Zen) 平台

对应需求：「墨听的平台代理还要加上 opencode 平台，你对照下本机的 jethub 插件看看，
想办法移植进去。」

参照物是本机 jethub 插件的 `dsh-codearts-auth`（已安装版本 `0.2.0-rc.10`，
可比对源码在 PVE `/root/dsh-phone/vendor/dsh-codearts-auth/src/` 的 8 个 `opencode-*.ts`）。

### ① 与其余五家的本质差别：不需要协议转换

Zen 端点 `https://opencode.ai/zen/v1/chat/completions` 就是**标准 OpenAI chat 形状**，
响应就是标准 OpenAI SSE。故本项目**没有新建 SseConverter**，流式直接透传；
`aggregate` 只用于非流式聚合。

登录形态也不同：**没有设备码 / 扫码流程**，凭据就是一条 `sk-…` API Key 直接填。
`isExpired` 恒 `false`（长期凭据，无续期循环）。

### ② 两处「漏掉就整条通道不可用」的协议细节

1. **请求体形状门禁**：`stream` 必须 `true`，且 `tools` 里必须同时存在名为 `bash`
   与 `read` 的 function 工具（描述、参数、请求头都不检查）。不满足回 403 `FreeTierError`。
   网关自动补这两个桩工具，**只有在请求原本一个工具都没有时**才顺带补
   `tool_choice: "none"` —— 已有真实工具时覆盖它，会让模型无法调用任何工具。

2. **五个指纹头 + session id 形状**：
   `x-opencode-project` / `x-opencode-session` / `x-opencode-request` /
   `x-opencode-client: cli` / `user-agent`；**绝不发** `x-session-affinity` /
   `X-Session-Id`（那是同函数里「非 opencode provider」分支的头）。

   session id 必须是 `ses_` + 12 位小写 hex + **14** 位 base62（尾段共 26 字符）。
   官方 `id/index.ts` 的 `const LENGTH = 26` 是**整段尾部长度**
   （`randomBase62(LENGTH - 12)`），随机段是 14。误读成「随机 26 位」会产出
   `ses_` + 38 字符 —— **此错只在匿名通道下暴露**（付费通道不校验形状），
   是移植期最难发现的一处。

   project id 用 `sha1("git-remote:opencode/" + sha256Hex(identity + " " + generation))`：
   同身份稳定、换代次即变，满足「防关联但不参与配额计算」。

### ③ 错误分类顺序即优先级

额度语义必须**先于**鉴权判定，因为额度错误常带 401/403/429：

```
free_usage_limit → go_usage_limit → free_tier（不重试不换槽）
→ rate_limit(429) → transport(status 0 或 fetch failed/terminated/econnreset/…)
→ quota（402 或含 insufficient account funds / funds / balance）
→ auth(401/403) → server(>=500) → 兜底 auth
```

`retry-after: 0` 是合法值（表示立即解除），必须用 `!== undefined` 与
「没给这个头」区分开 —— 混同会让用户白等一轮。

**402 必须单独归类成 quota**：付费 key 余额耗尽时上游回 402，
若归成「服务端故障请重试」，用户会反复重试一个永远不会成功的请求。

### ④ 模型目录只暴露实测可达的条目

上游 `/zen/v1/models` 返回 **88 条**且不含协议信息，含 `claude-*` / `ling-*` 等走
`/v1/messages`（Anthropic 协议，本项目未实现）的模型 —— 直接透传会让用户点到
必然 404/401/500 的条目。故维护显式表（**12 条：5 免费 + 7 付费**），表外一律返回
`isFree=false` / `isReachable=false`。

### ⑤ 关键发现：匿名（免登录）通道已被服务端封死

初次实现照搬插件逻辑，提供「免登录添加（免费模型）」入口（用字面量 `public` 作 key）。
**真实端点探针（用本项目的 `OpencodeProvider` 原样打线上，非重写假设）实测：**

| 用例 | 结果 |
|---|---|
| 匿名槽 + 完整门禁形状（stream + bash/read + tool_choice=none） | **403 `FreeTierError`** |
| 匿名槽 + 去掉门禁形状 | 403 `FreeTierError`（证明门禁真实存在，且本实现的形状正是放行条件） |
| 匿名槽 + 付费模型 | 401 `AuthError: Missing API key`（分类正确） |
| 请求头逐项消融（含 `accept: text/event-stream`） | **全部 403，无一放行** |

完全消融实验证明与请求头无关。进一步拉上游最新版 `dsh-codearts-auth@1.3.261015`
比对：**服务端事实与本地移植完全一致**，其新增的 `opencode-auth.js` / `opencode-rpc.js`
也把匿名通道当普通条目处理，**不再自动播种匿名账号**。

⇒ **结论：匿名通道 403 是服务端现状，不是本移植的缺陷。**
2026-10-02 起服务端从「按形状识别客户端」改为「只认 OpenCode 官方客户端本体」。

**据此做的修正（第二次提交）：**

| 位置 | 修正 |
|---|---|
| `OpencodeProvider.FALLBACK_MODELS` | 移除远端已下架两条：`mimo-v2.5-free`、`nemotron-3-ultra-free`（远端 88 条里查无此 id） |
| `AccountManager` | 删除 `ensureDefaultOpencodeAccount()` 播种调用及方法；`Boot.java` 同步删除调用 |
| `GatewayServer` 管理端点 | 空 key 由「即免登录」改为 **400 错误**，文案说明「免费档位模型同样需要 Key」 |
| `GatewayServer` 模型目录 | 注释与逻辑改为「有 key 才列出」，不再声称零配置可用 |
| `home.html` | **删除「免登录添加（免费模型）」按钮**；`onOpencodeAdd(node)` 去掉 `anonymous` 形参，留空直接 toast 提示；占位符改「sk-…（必填）」；`PROVIDERS` 的 note 改「API Key」 |
| `AccountManager` 标签 | 匿名槽标签显示「匿名通道（已失效）」——否则用户看到正常标签却条条失败，会误判成自己网络问题 |
| `README.md` | 「免登录」宣传全部撤下，改为显式警告块 + 实测证据 |
| `RegTest` | 新增下架条目断言（`isReachable` 必须为 false） |

**保留的部分**（有意为之）：形状门禁与指纹代码**不动**。一旦上游放宽，
无需改动即可恢复；对带 key 的认证通道也无害。

### ⑥ 离线断言验证不了假设本身 —— 这是本里程碑最大的教训

`RegTest` 的 10 组 OpenCode 断言**全部通过**，但它们只证明「我实现的假设自洽」，
**证明不了假设与线上一致**。匿名通道的 FreeTier 门禁属**未公开行为**，
只能靠真实端点证伪。探针的价值正在于此：它不复现实现，而是直接编译并调用
**真实的** `jet.core.OpencodeProvider`（`headers` / `deriveSessionId` / `buildPayload` /
`ensureFreeLaneShape` / `classifyError` / `aggregate`），因此测的是线上事实而非我的推理。

> 若当初只跑离线断言就交付，用户拿到的会是一个「有免登录入口、点进去条条 403」的包。

### ⑦ 验证结果

| 层 | 内容 | 结果 |
|---|---|---|
| 编译 | `javac src/jet`（android-34.jar + json.jar） | 67 个 class，exit 0 |
| 回归 | `java RegTest` | **REG PASS**（含 10 组 OpenCode 断言） |
| 前端 | 抽取内联 JS → `node --check` | `JS_SYNTAX_OK`（67543 字节） |
| 真实端点 | `.oc-probe/` 探针（复用真实 Provider 打线上） | 5 项用例，匿名通道 403 定案 |
| 全树回编 | `bash scripts/build-full.sh` | exit 0，70603414 字节 |
| 二进制核验 | `bash tools/verify-apk.sh` | 全绿（见下） |

### ⑧ 交付产物指纹

| 项 | 值 |
|---|---|
| 文件 | `墨听-4.0.20-r2-jethub内置版.apk` |
| 大小 | 70603414 字节 |
| sha256 | `05adeb3bf67f7a50224d427e5f9802b0c4e1b0187efb5ce3696e8ca18f6071fc` |
| dex 分包 | `classes.dex` … `classes10.dex`，编号 1..10 连续 |
| 关键类落位 | `jet/core/OpencodeProvider.smali`（31 个方法）、`AccountManager$OpencodeIdentity.smali` → `classes9.dex` |
| 包身份 | `com.jethub.moting` / versionCode 40023 / versionName 4.0.20-r2 / targetSdk 34 |
| 应用名 | 墨听JetHub |
| 签名 | CN=JetHub Embedded, OU=Dev, O=JetHub, C=CN，SHA-256 `3f0e1b4d…5e15f73` |
