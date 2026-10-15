# 墨听 · JetHub 内置版

把 [JetHub](https://github.com/fengs2021) 的多家 AI 供应商适配器，以**原生 Java** 移植进
墨听（一款基于 Legado 的阅读 App）的 APK 内部，让墨听在不装第二个 App、不依赖任何外部
主机的前提下，直接使用 Cline / Trae / CodeBuddy / WorkBuddy / MiniMax 等平台的模型。

改造后的墨听里多出一个入口：**墨听设置 → AI 模型与接口中心 → ⋮ → 管理平台代理 API**。
进去就是一个本机网关的管理页（账号、签到、模型、接入四个标签），在里面登录供应商、
勾选模型，然后回到 AI 模型中心添加一个指向 `http://127.0.0.1:8391/v1` 的中转账号即可。

> 本仓库只包含**改造工程本身**（Java 源码、smali 胶水、补丁脚本、构建流水线、管理页）。
> 不含墨听原始 APK、不含任何私钥或真实凭据、不含构建产物。

---

## 它是怎么工作的

```
墨听 App 进程
├── App.onCreate()
│     └─ invoke-static {p0}, Ljet/android/Boot;->init(Context)V   ← smali 注入的一行
│           └─ GatewayServer 起一个 loopback HTTP 服务 127.0.0.1:8391
│                 ├── /            → 管理页（assets/jethub/home.html）
│                 ├── /v1/models   → 汇总各供应商模型目录，id 带供应商前缀
│                 ├── /v1/chat/completions → 按前缀路由到对应 Provider，转发 SSE
│                 └── /api/...     → 账号登录、签到、凭据状态
├── 凭据全部落在手机本地 App 私有目录，不出设备
└── JetHubActivity（不占桌面图标）→ 打开管理页的原生壳
```

- 墨听把网关当成一个普通的 OpenAI 兼容中转：**Base URL `http://127.0.0.1:8391/v1`**，
  API Key 是管理页展示的本地密钥（运行时随机生成，非硬编码）。
- 模型 id 形如 `cline/space-bunny-alpha`、`trae/...`、`buddy/...`、`workbuddy/...`、
  `minimax/...`，网关按前缀路由；同一个账号下可以同时开多家。
- 供应商的 token 过期由 `RefreshScheduler` 在后台续期，凭据存 `CredentialStore`
  （SharedPreferences + 本地加密），手机重启后依然有效。

## 当前进度

| 供应商 | 续期 | 模型目录 | 对话转发 | 手机自助登录 |
|---|---|---|---|---|
| Cline | ✅ | ✅ | ✅ | ✅ 设备码 |
| Trae | ✅ | ✅ | ✅ | ✅ 短信 / 扫码 |
| CodeBuddy (Buddy) | ✅ | ✅ | ✅ | ✅ 浏览器轮询 |
| WorkBuddy | ✅ | ✅ | ✅ | ✅ 浏览器轮询 |
| MiniMax | ✅ | ✅ | ✅ | ✅ 设备码 + PKCE |
| Qoder | — | — | — | 需 WASM 加密绕过 |
| DeepSeek / ZCode | — | — | — | 待移植 |

细节与踩坑记录见 [`docs/MILESTONES.md`](docs/MILESTONES.md)。

## 目录结构

```
src/jet/core/     供应商适配器与网关核心（HttpFetcher、CredentialStore、
                  ClineProvider、TraeProvider、BuddyProvider、MinimaxProvider、
                  GatewayServer、ModelRegistry、ModelCenterRegistrar、HomePage …）
src/jet/android/  App 侧壳：Boot（进程启动时拉起网关）、JetHubActivity（管理页容器）
src/test/         host JVM 上的端到端与回归测试（不依赖 Android 运行时）
smali-glue/       不能写成 Java 的 Compose 胶水（菜单文案 / 点击行为两个 smali 类）
patches/          对墨听反编译树的补丁脚本（菜单注入、桌面图标移除、模型中心刷新、改包名）
scripts/          构建流水线（prepare 取 dex；build 轻量替换 / build-full 全量 apktool 回编）
assets/jethub/    管理页前端（单文件 HTML，无外部依赖）
docs/             里程碑日志
```

## 构建

构建是**两段式**的：先把 Java 编译成 dex，再把它塞进墨听 APK。因为墨听的 APK 是加固过的，
apktool 全量回编不一定成功，所以仓库提供了两条路：

- `scripts/build.sh` —— **轻量路线（实际发布用这条）**：只替换 `classes4.dex`、追加
  `classes9.dex`，其余条目从原包原样复制。对加固/混淆的容忍度高。
- `scripts/build-full.sh` —— **全量路线**：用 apktool 解包整个 APK，改 manifest、加资源、
  加新的 smali 包，再整体回编。能做的事更多，但对原包更挑剔。

### 准备工具链

`toolchain/` 需要四个 jar（仓库不含，自行下载）：

| 文件 | 来源 |
|---|---|
| `android-34.jar` | Android SDK `platforms/android-34/android.jar` |
| `json.jar` | [org.json:json](https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar) |
| `r8.jar` | [R8](https://maven.google.com/com.android.tools/r8/)（含 D8） |
| `smali.jar` / `baksmali.jar` | [smali/baksmali](https://bitbucket.org/JesusFreke/smali/downloads/) |

另外需要：`javac`（JDK 11+）、`apktool`、`python3`，以及 Android SDK 的
`build-tools`（提供 `zipalign` / `apksigner`）。

### 准备构建输入

轻量路线只需要 `classes4.dex` 的反汇编树（因为 JetHub 的注入点 `App.smali` 在 classes4 里）：

```bash
export JETHUB_UPSTREAM_APK=/path/to/墨听.apk
bash scripts/prepare.sh        # 取出 classes4.dex → 反汇编成 apk/smali4/
```

全量路线要的是整个反编译树：

```bash
apktool d -f --use-aapt2 墨听.apk -o full-decoded
```

两个补丁脚本**不用手动跑** —— `build-full.sh` 的第 3d 步会调用它们，而且都是幂等的，
重复执行只打印「跳过」。想单独跑也可以：

```bash
JETHUB_DECODED=$PWD/full-decoded python3 patches/patch-ui.py       # 胶水 + manifest + 菜单 + Boot 注入
JETHUB_DECODED=$PWD/full-decoded python3 patches/patch-refresh.py  # 返回模型中心时自动刷新
```

`patches/patch-ui.py` 做四件事，都在脚本头部注释里写了原因：

1. **放胶水类** —— 把 `smali-glue/` 下的两个 smali 拷进 `smali_classes10/`。
2. **注册 JetHubActivity 并去掉 LAUNCHER** —— 上游原包里没有这个 Activity，所以要先注册；
   入口应该在墨听自己的《AI 模型与接口中心》里，桌面上再放一个图标是重复的。
3. **在《管理网页转 API》下方插入《管理平台代理 API》** —— 菜单由 Compose 构建，
   注入点是 `MotingAiModelCenterScreenKt` 里 `AppDropdownMenuItem` 的那次调用；
   文案与点击行为来自 `smali-glue/` 的两个类。
4. **往 `App.onCreate()` 注入 `Boot.init()`** —— 拉起内嵌网关。

`patches/patch-refresh.py` 解决的是用户实机反馈的问题：「写入 AI 模型中心后要刷新一下
模型中心中的模型，要不然不会自动显示出来」。原因是模型中心的加载协程以
`aiModelCenterBackupRevision` 作为 `LaunchedEffect` 的 key，只有「导入备份」那条路径会
自增它，而 JetHub 写入走的是反射调 `restoreJson`，不经过那条路。补丁给
`AiApiControlActivity` 加一个 `onResume()`，每次回到本页就把 revision +1。

### 改包名（可选）

上游墨听的 applicationId 是 `com.applecabal.njread`。改造版如果沿用同一个包名，
装到手机上会与原版墨听共用数据目录与签名身份——两个版本无法并存，签名不一致时
覆盖安装还会直接失败。要对外分发就换掉：

```bash
JETHUB_PACKAGE=com.example.moting python3 patches/patch-package.py full-decoded
```

`build-full.sh` 的第 3e 步会读 `JETHUB_PACKAGE` 自动调用它；不设置就沿用上游包名
（正式版用的是 `com.jethub.moting`）。

包名不只在 `AndroidManifest.xml` 的 `package` 属性里，还以字符串形式散落在 9 个
smali 中（`BuildConfig.APPLICATION_ID`、三个 provider authorities、取包名的工具类等）。
逐处枚举容易漏，所以脚本做的是全树字符串替换——`com.applecabal.njread` 这个字面量
在 apktool 解包出的树里只可能是包名本身，不会误伤类描述符。上游 4.0.20-r2 实测命中
16 处 / 9 个文件。

> 改名后是**另一个应用**：不会继承原版墨听的书架、阅读进度、AI 模型中心配置，
> 需要重新初始化。这是 applicationId 的固有语义。

### 编译与打包

```bash
export ANDROID_BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0
export JETHUB_UPSTREAM_APK=/path/to/墨听.apk
export JETHUB_KEYSTORE=/path/to/your.jks JETHUB_KS_PASS=***   # 不给则产出未签名 APK

bash scripts/prepare.sh        # 轻量路线需要：反汇编 classes4 → apk/smali4/
bash scripts/build.sh          # 轻量路线（实际发布用这条）

# 或者全量路线（需先 apktool 解包 + 跑两个补丁脚本）
export JETHUB_DECODED=$PWD/full-decoded
bash scripts/build-full.sh
```

所有路径与口令都从环境变量读取，脚本里没有硬编码。**签名必须用同一个密钥库**，
否则装不上（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）；换密钥意味着必须卸载重装、丢数据。

### 改完 Java 之后

`smali-glue/` 里的两个类不在 Java 源码里 —— 它们是 Compose 的 `Function2`/`Function0`
实现，写 Java 反而更绕。如果新增了需要被菜单/UI 直接引用的类，照这两个文件的样式手写
smali 放进对应 `smali_classesN/` 即可，`patch-ui.py` 会引用它们。

## 免责声明

本项目仅用于个人学习与自用，通过各家平台的**公开接口**访问**用户自己已付费/已授权的**
账号。请遵守各平台的服务条款；因使用本项目产生的账号风险由使用者自负。
仓库中不包含任何平台凭据、token 或私钥。

## License

MIT，见 [LICENSE](LICENSE)。
