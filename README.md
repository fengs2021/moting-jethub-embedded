# 墨听 · JetHub 内置版

把 [JetHub](https://github.com/fengs2021) 的多家 AI 供应商适配器，以**原生 Java** 移植进
墨听（一款基于 Legado 的阅读 App）的 APK 内部，让墨听在不装第二个 App、不依赖任何外部
主机的前提下，直接使用 Cline / Trae / CodeBuddy / WorkBuddy / MiniMax 等平台的模型。

改造后的墨听里多出一个入口：**墨听设置 → AI 模型与接口中心 → ⋮ → 管理平台代理 API**。
进去就是一个本机网关的管理页（账号、签到、模型、接入四个标签），在里面登录供应商、
勾选模型，然后回到 AI 模型中心添加一个指向 `http://127.0.0.1:8391/v1` 的中转账号即可。

> 本仓库包含**完整的改造工程**：Java 源码、smali 胶水、补丁脚本、构建流水线、管理页前端。
> 工具链与 Android SDK 片段由脚本从公开源自动下载并校验 sha256，无需手工准备。
> 仓库不含墨听原始 APK、不含任何私钥或真实凭据、不含构建产物。

---

## 快速开始

```bash
git clone https://github.com/fengs2021/moting-jethub-embedded
cd moting-jethub-embedded

# 一条命令：自动拉工具链 + Android SDK 片段 + 原始墨听 APK，然后出成品
JETHUB_KEYSTORE=/path/to/your.jks JETHUB_KS_PASS='***' \
  bash scripts/build-full.sh
```

产物是 `墨听-4.0.20-jethub内置版.apk`。

**不需要自备任何东西。** 工具链、Android SDK 片段、原始墨听 APK 全部由 `fetch-deps.sh`
自动下载并逐个校验 sha256（原始 APK 与上游发布仓库自带的 `.sha256` 对照）。不提供
`JETHUB_KEYSTORE` 也能跑，但产出的是未签名包，只能本地验证、不能安装分发。

前置条件只有三样：**JDK 11+**（`javac`）、**python3**、**curl/unzip**。apktool、d8、
baksmali、zipalign、apksigner 全部自动备好，不需要预装 Android SDK（**Windows 例外**，
见下节）。

### Windows 上构建

脚本在 Git Bash（MSYS2）下可直接跑，但有两处与 Linux 不同，都已在脚本内处理：

- **build-tools 必须复用系统 SDK。** `fetch-deps.sh` 拉的是 `build-tools_r34-linux.zip`
  （ELF 二进制），Windows 上跑不了，所以它会跳过这一步；脚本会自动去
  `ANDROID_SDK_ROOT` / `ANDROID_HOME` 里找版本最高的 build-tools。也可以显式指定：

  ```bash
  ANDROID_BUILD_TOOLS=/d/Android/android-sdk/build-tools/35.0.0 bash scripts/build-full.sh
  ```

- **`d8` 与 `apksigner` 在 Windows 上只有 `.bat`，没有无扩展名的可执行文件**，直接
  `"$BT/d8"` 会报 `No such file or directory`（退出码 127）。脚本改走 java 直调
  `lib/d8.jar` 与 `lib/apksigner.jar` 的主类，与平台无关；`zipalign` 有原生 `zipalign.exe`，
  照常直接调用。这三处统一收敛在 `scripts/lib-deps.sh` 的 `jethub_d8` /
  `jethub_apksigner` / `jethub_zipalign` 三个函数里。

仓库自带 `.gitattributes`（`* text=auto eol=lf`），保证脚本以 LF 检出。若你的仓库是在
加这个文件**之前**克隆的，`core.autocrlf=true` 会让工作区的 `.sh` 全是 CRLF，bash 会报
`$'\r': command not found`。修法是重新检出：

```bash
git rm --cached -r . && git reset --hard
```

想用别的墨听版本（比如你手上更新的 4.0.20-r2）就把它指过来，自动下载那步会跳过：

```bash
JETHUB_UPSTREAM_APK=/path/to/墨听.apk bash scripts/build-full.sh
```

---

## 上游墨听 APK 从哪来

改造对象是墨听 Android 客户端。它没有公开源码，所以只能拿发布包做反编译输入。
默认从作者的公开发布仓库下载：

- 仓库：<https://github.com/jwoo1982217-eng/MoTing-Releases>
- 默认版本：`v4.0.19-r1`（`MoTing-4.0.19-r1-40020-9b60b6c0e-arm64-v8a-R8-release.apk`）
- sha256：`1f2b7e9b47db0bba52f4d6588905cfe451dd37ccad7e76b6a8d01828ea4530af`

换版本用 `JETHUB_UPSTREAM_TAG` / `JETHUB_UPSTREAM_ASSET` / `JETHUB_UPSTREAM_SHA` 三个变量，
或者干脆用 `JETHUB_UPSTREAM_APK` 指向本地文件。

各版本差异（都已实测能出包）：

| 版本 | versionCode | dex 数 | 说明 |
|------|-------------|--------|------|
| 4.0.19-r1 | 40020 | 7 | 公开发布仓库最新版，默认 |
| 4.0.20-r2 | 40023 | 8 | 开发时用的版本，需自备 |

补丁脚本对 dex 分包编号是自适应的（按 `smali_classes*` glob 找目标文件），注入用的分包编号
也**不写死** —— 取「现有最大编号 +1」（见下），菜单注入锚点同样是版本无关的：靠菜单文案反查
Compose 生成的 lambda id，而不是靠行号。

> **为什么分包编号必须动态取**：Android 运行时按 `classes.dex`、`classes2.dex`、`classes3.dex`…
> 的顺序扫描，**遇到第一个缺失的编号就停下**，后面的 dex 一律不加载。上游 4.0.20 有 8 个 dex，
> 把新代码写进 `classes9/classes10` 刚好连续；但 4.0.19-r1 只有 7 个 dex，`classes8` 是空的，
> 此时写进 `classes9/classes10` 就等于把注入的类藏进了加载器不看的地方 —— 结果
> `Boot` / `JetHubActivity` / 胶水类 / 注册器一个都加载不到，应用一启动就
> `NoClassDefFoundError: Failed resolution of: Ljet/android/Boot;` 闪退。
> 而 apktool 回编、签名、产物结构检查全都正常，**只有装到机器上才炸**。

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
- 供应商的 token 过期由后台续期，凭据存 `CredentialStore`（App 私有目录），手机重启后依然有效。

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

### 已修复的上游缺陷

| 问题 | 现象 | 记录 |
|---|---|---|
| 个别章节预标注每轮必失败 | 第 250/251 章报 `canonical units overlap or are out of order`，永远标注不出来 | [`docs/OVERLAP-FIX.md`](docs/OVERLAP-FIX.md) |

## 目录结构

```
src/jet/core/     供应商适配器与网关核心（HttpFetcher、CredentialStore、
                  ClineProvider、TraeProvider、BuddyProvider、MinimaxProvider、
                  GatewayServer、ModelRegistry、ModelCenterRegistrar、HomePage …）
src/jet/android/  App 侧壳：Boot（进程启动时拉起网关）、JetHubActivity（管理页容器）
src/test/         host JVM 上的端到端与回归测试（不依赖 Android 运行时）
src/test/e2e/     真实凭据下的端到端验证脚本（RegTest / UiE2E / ModelMgmtTest …）
smali-glue/       不能写成 Java 的 Compose 胶水（菜单文案 / 点击行为两个 smali 类）
patches/          对墨听反编译树的补丁脚本（菜单注入、桌面图标移除、模型中心刷新、
                  预标注区间收敛、音频漫画配图健壮性、改包名、改应用显示名）
tools/            验证工具（verify_clamp.py：自写 Dalvik 解释器 + 参考实现对照；
                  verify-apk.sh：成品包验收，dex 分包连续性 / 关键类落位 / 入口三要素 / 签名）
tests/art/        真机 ART 验证（把补丁的 smali 丢给真实 ART 执行，替代人眼审查）
scripts/          fetch-deps 拉依赖；build-full 全量出包；build 轻量路线；prepare 备输入
assets/jethub/    管理页前端（单文件 HTML，无外部依赖）
docs/             里程碑日志 + 单点修复的详细记录（如 OVERLAP-FIX.md）
```

## 构建详解

### 依赖自举

`scripts/fetch-deps.sh` 把下面这些从公开源拉到 `toolchain/` 与 `sdk/`，每个都校验 sha256
（哈希写在脚本里，版本刻意钉死）：

| 构件 | 来源 | 用途 |
|---|---|---|
| `apktool.jar` | [Apktool v2.9.3](https://github.com/iBotPeaches/Apktool/releases/tag/v2.9.3) | 解包 / 回编 APK（内置 linux aapt2，不依赖 SDK） |
| `baksmali.jar` / `smali.jar` | [smali v2.5.2 fat jar](https://github.com/baksmali/smali/releases/tag/v2.5.2) | dex ↔ smali |
| `r8.jar` | [Google Maven 8.2.33](https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.33/r8-8.2.33.jar) | d8：class → dex |
| `json.jar` | [org.json 20240303](https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar) | host 侧单测 |
| `build-tools/34.0.0` | [build-tools_r34-linux.zip](https://dl.google.com/android/repository/build-tools_r34-linux.zip) | d8 / zipalign / apksigner / aapt2（**仅 Linux/macOS**；Windows 见上） |
| `android-34.jar` | [platform-34-ext7_r03.zip](https://dl.google.com/android/repository/platform-34-ext7_r03.zip) | javac 编译用的平台库 |

GitHub 在国内是**间歇性可达**的：同一个 URL 这次能下、下次超时。所以 `fetch-deps.sh` 对
每个资源都按「直连 → 镜像」顺序逐个尝试，任何一个下完且哈希对即止：

```
GitHub 资源：  直连 → https://gh-proxy.com/ → https://ghproxy.net/ → https://ghfast.top/
dl.google.com：直连 → https://maven.aliyun.com/repository/google/（r8）
                     → https://mirrors.cloud.tencent.com/AndroidSDK/（build-tools、platform）
Maven Central：直连 → https://maven.aliyun.com/repository/public/（json）
```

想换一组 GitHub 镜像就设 `JETHUB_GH_MIRRORS="https://a/ https://b/"`（空格分隔）。
所有来源都过 sha256 校验，镜像即使被投毒也进不来；上面这些镜像的返回内容已逐个实测与
官方哈希一致。

### 慢链路与断点续传

上游 APK 有 68 MB，工具链加起来也有 40 MB。弱网下真正的问题不是「下不动」而是「下不完」：

- 每个源最多占用 `JETHUB_SRC_BUDGET`（默认 300）秒，超时就换下一个候选，**已下的部分
  通过 `curl -C -` 续传**，不从零重来。实测同一条链路上直连 42 KB/s、`gh-proxy.com`
  155 KB/s，只按「死没死」判断的话慢源永远不算死，会一直霸着不放，所以要有这个让位机制。
- 一个源如果连上却一个字节都不长（多半不支持 Range），它的残留文件会挡住后面的源，脚本会丢掉它。
- 进度文件 `$dest.part` 只增不减：每个候选写自己的临时文件，只有「哈希对」或「比现有进度长」
  才被采纳。因为有的源不认 `Range`（回 200 而不是 206），curl 会从偏移 0 重写 —— 若共用一个
  进度文件，换源时就会把前面下的十几 MB 截成几百 KB，在弱网下永远下不完。
- 所有候选轮完算一轮，最多 `JETHUB_MAX_PASSES`（默认 10）轮；**一整轮下来一个字节都没长
  就提前结束**，所以轮数给宽不会浪费时间。
- `--speed-limit 1024 --speed-time 60` 兜底：连续 60 秒低于 1 KB/s 才判定该源已死。

（历史教训：早先这里用的是 `curl --max-time 600`，68 MB 的包在 42 KB/s 链路上需要 27 分钟，
600 秒一到 curl 被杀且没有续传，下一轮从 0 重来——在这种链路上永远下不完。）

若本机的 `HTTP_PROXY` 指向一个不通的代理，curl 会连不上，先
`unset HTTP_PROXY HTTPS_PROXY ALL_PROXY`。

`smali.jar` 标记为可选：只有轻量路线 `build.sh` 需要它，全量路线用不上，所以它下不下来
都不会挡住出包。

### 字符集

源码、菜单文案、日志里都是中文，而 **javac 在没有 locale 的机器上默认按 US-ASCII 读源文件**，
中文注释会炸出几万条 `unmappable character for encoding US-ASCII`，第一步编译就死。这种机器
很常见：容器、CI、`ssh host cmd` 这类非交互 shell（`LANG` 为空、`LC_CTYPE=POSIX`）。

所以脚本不指望环境正确，自己兜住：`scripts/lib-deps.sh` 在 source 时把 `LANG`/`LC_ALL` 顶到
一个真实存在的 UTF-8 locale，并导出两个 JVM 参数变量——`JETHUB_JAVA_OPTS`
（`-Dfile.encoding=UTF-8`，给 `java`）与 `JETHUB_JAVAC_OPTS`（`-J-Dfile.encoding=UTF-8`，给
`javac`；javac 必须用 `-J` 前缀转发 JVM 参数，直接写 `-D` 会被当成 javac 自己的选项而报
`invalid flag`）。各脚本的 `javac` 另带显式 `-encoding UTF-8`。想覆盖就自行设这两个变量。

### 一条命令出包

```bash
bash scripts/build-full.sh
```

`build-full.sh` 的九步：

| 步骤 | 做什么 |
|---|---|
| 1 | `javac` 编译 `src/jet/**`（Java 11 目标） |
| 2 | host 侧单测（模型注册表合并逻辑，不需要 Android 运行时） |
| 3 | `apktool d` 解包上游 APK → `full-decoded/`（已存在则跳过，`JETHUB_REDECODE=1` 强制重解） |
| 4 | 同步管理页 `assets/jethub/home.html` 进反编译树 |
| 5 | `d8` 把 `jet/**` 打成 dex，再 `baksmali` 成下一个空闲分包（现有最大编号 +1，见上） |
| 6 | 打补丁：胶水类 / manifest 注册 / 菜单注入 / Boot 注入 / 模型中心刷新 / 预标注区间收敛 / 音频漫画配图健壮性（全部幂等） |
| 7 | 改包名（可选，设了 `JETHUB_PACKAGE` 才做）+ 改应用显示名（默认「墨听JetHub」） |
| 8 | `apktool b` 全量回编 |
| 9 | `zipalign` + `apksigner` |

为什么必须是**全量回编**这条路线：成品要在 `AndroidManifest.xml` 里注册
`JetHubActivity`、在模型中心菜单里插一项、在 `App.onCreate()` 里注入一行启动网关。
三者缺一，装出来的包就没有入口 —— 网关在后台跑着，但用户点不进去。

`scripts/build.sh` 是另一条**轻量路线**（只替换 `classes4.dex`、追加 `classes9.dex`，
其余条目从原包原样复制）。它改不了 `AndroidManifest.xml`、也加不了新的 smali 包，所以
**它产出的包没有 JetHub 入口** —— 网关在后台跑着，但用户点不进去。

它的用途只有一个：在已经用 `build-full.sh` 全量回编过、且已打好补丁的反编译树上做增量
调试，改几行 Java 后只重编 dex，省掉几分钟的 apktool 回编。**要出成品一律用
`build-full.sh`。**

### 补丁脚本

`build-full.sh` 第 6 步会自动调用，且都幂等（重复跑只打印「跳过」）。单独跑也可以：

```bash
JETHUB_DECODED=$PWD/full-decoded python3 patches/patch-ui.py       # 胶水 + manifest + 菜单 + Boot
JETHUB_DECODED=$PWD/full-decoded python3 patches/patch-refresh.py  # 返回模型中心时自动刷新
python3 patches/patch-overlap.py full-decoded                      # 预标注区间收敛
python3 patches/patch-audiocomic.py full-decoded                   # 音频漫画图片未就绪不再崩
python3 patches/patch-label.py full-decoded                        # 应用显示名 墨听 → 墨听JetHub
```

每个脚本都接受反编译树路径作为第一个参数，缺省读环境变量 `JETHUB_DECODED`，再缺省
`./full-decoded`。目标 smali 用 glob 在 `smali_classes*/` 下查找 —— 因为 apktool 的
dex 分包编号随墨听版本变化，不能硬编码。`patch-overlap.py` 与 `patch-audiocomic.py`
另支持 `--check` 只校验不写。

`patches/patch-ui.py` 做四件事，都在脚本头部注释里写了原因：

1. **放胶水类** —— 把 `smali-glue/` 下的两个 smali 拷进下一个空闲分包（`build-full.sh` 用的编号 +1）。
2. **注册 JetHubActivity 并去掉 LAUNCHER** —— 上游原包里没有这个 Activity，所以要先注册；
   入口应该在墨听自己的《AI 模型与接口中心》里，桌面上再放一个图标是重复的。
3. **在《管理网页转 API》下方插入《管理平台代理 API》** —— 菜单由 Compose 构建，
   注入点是 `MotingAiModelCenterScreenKt` 里 `AppDropdownMenuItem` 的那次调用；
   文案与点击行为来自 `smali-glue/` 的两个类。
   定位方式**不是按行号**：先拿菜单文案 `"\u7ba1\u7406\u7f51\u9875\u8f6c API"` 去
   `ComposableSingletons$MotingAiModelCenterScreenKt.smali` 里反查它所属的 lambda
   trace id（`lambda$<id>.<anonymous>`），再用这个 id 对应的
   `getLambda$<id>$app()` 定位调用点。行号随上游版本漂移（4.0.20 是 `.line 529`、
   4.0.19-r1 是 `.line 525`），按行号做锚点换个版本就命中 0 次；lambda id 由内容派生，
   两个版本上完全一致（`2085309646`）。
   顺带修掉一个老缺陷：早期模板会**把锚点那一行原样重发一遍**再追加新项，每注入一次就多出
   一个重复的《管理网页转 API》（已发布的 4.0.20 成品包里就有这个重复项）。现在不再复刻原行。
   所有失败路径一律 `sys.exit` 非零 —— 之前是「打印警告后照常退出 0」，而 `build-full.sh`
   的 `set -e` 抓不住，会静默产出**没有菜单入口**的 APK。
4. **往 `App.onCreate()` 注入 `Boot.init()`** —— 拉起内嵌网关。锚点用正则匹配
   `.method public onCreate()V` 后跟 `.registers N` 或 `.locals N`，因为两种写法等价、
   随 apktool 参数而变；注入指令只用 `p0`，不改变寄存器分配。

`patches/patch-refresh.py` 解决的是实机反馈的问题：「写入 AI 模型中心后要刷新一下模型中心
中的模型，要不然不会自动显示出来」。原因是模型中心的加载协程以
`aiModelCenterBackupRevision` 作为 `LaunchedEffect` 的 key，只有「导入备份」那条路径会
自增它，而 JetHub 写入走的是反射调 `restoreJson`，不经过那条路。补丁给
`AiApiControlActivity` 加一个 `onResume()`，每次回到本页就把 revision +1。

`patches/patch-overlap.py` 修的是「个别章节后台预标注**每轮必报**
`canonical units overlap or are out of order`、永远标注不出来」。根因在
`enrich` 的重叠自检失败后走**回滚分支**，而回滚返回的是未排序、未做引号区间减法的原始
行表，于是旁白行与其内嵌对白行的区间重叠，穿透到 `CanonicalChapterUnitStore.exactUnitRecords`
的 `rawStart < previousEnd` 判据。补丁在构造 `ExactUnit` 前对相邻单元做**区间单调收敛**
（裁剪，保持 `text == chapterText.substring(rawStart, rawEnd)` 不变式）。

完整定位过程、失败链、寄存器约束与三层验证记录见
[`docs/OVERLAP-FIX.md`](docs/OVERLAP-FIX.md)。验证方式：

```bash
python3 tools/verify_clamp.py            # 自写 Dalvik 解释器 + 参考实现对照（10+5+12 项）
bash tests/art/run-art-test.sh           # 真实 Android ART 执行补丁的 smali（8 个用例）
bash tools/verify-apk.sh                 # 成品包验收（见下）
```

`patches/patch-audiocomic.py` 修的是实机崩溃「音频漫画配图到某一格时整章停摆」：

```
java.lang.IllegalArgumentException: audio comic image is not stable:
  /storage/emulated/0/Android/data/com.jethub.moting/files/audio-comic/images/…/xxx.jpg
    at okio.CipherSink$$ExternalSyntheticBUOutline0.m(SourceFile:0)
    at …AudioComicImageDeliveryFastLane.deliver(SourceFile:25)
    at …AudioComicImageGenerationWorker.generatePlanSnapshot$handleSceneOutcome(SourceFile:2653)
    at …AudioComicImageLanePipeline$run$3.invokeSuspend(SourceFile:72)
  Suppressed: DiagnosticCoroutineContextException: [LazyStandaloneCoroutine{Cancelling}]
```

`deliver()` 对「图片不是文件、或长度 < 1024 字节」这种情况**直接抛异常**，而唯一调用方
`generatePlanSnapshot$handleSceneOutcome` 的 `try/catch` 只捕获
`ApiWorkerPlanWriteRejectedException` —— 于是异常穿透到协程，把**整章**的配图流水线取消，
而不是只让这一格失败。补丁把该分支改成「构造同样的异常并交给 `p7` 错误回调」，失败被限定
在单格、走 App 自己的错误日志路径，其余分镜照常出图。

> 这是上游的健壮性缺陷，不是本项目引入的：`audio comic image is not stable` 这个字符串在
> 上游 r2 原包里同样存在（`grep` 可验）。补丁只做「不崩」，**不做重试等待**——长度 < 1024
> 通常是模型侧返回了错误体（HTTP 200 但 body 是 JSON 错误串），不是「文件还在写」的竞态：
> 写入侧 `downloadImageToFile` 用一次 `writeBytes` 落盘，`deliver` 是在同一协程里紧随其后调用的。

### 成品包验收

出包之后、装机之前，跑一次：

```bash
bash tools/verify-apk.sh                          # 自动取最新的 墨听-*.apk
bash tools/verify-apk.sh apk/built-full.apk       # 或指定路径
```

它检查四类东西，其中第一类是**唯一必须查**的：

1. **dex 分包连续性**。Android 运行时按 `classes.dex`、`classes2.dex`… 顺序扫描，
   遇到第一个缺失编号就停下，后面的 dex 一律不加载。上游 4.0.20-r2 有 8 个 dex，
   注入类写进 `classes9`/`classes10` 刚好连续；但若上游只有 7 个 dex，`classes8` 空缺，
   写进 `classes9` 就会让 `Boot`/`JetHubActivity`/胶水类全部加载不到，启动即
   `NoClassDefFoundError: Failed resolution of: Ljet/android/Boot;` 闪退 ——
   而回编、签名、产物结构检查全都正常，**只有装到机器上才炸**。所以 `build-full.sh`
   取的是「现有最大编号 +1」（`jethub_next_dex_slot`），验收工具再复核一遍。
2. **关键类落位**（`Boot` / `JetHubActivity` / 两个胶水类 / `JetHubCanonicalClamp`）
   与**各补丁的真实效果**（`onResume` 已注入、区间收敛类已被调用、`deliver` 不再抛未捕获异常）。
3. **入口三要素**：Manifest 注册了 `JetHubActivity`、`assets/jethub/home.html` 已打入、
   `App.onCreate()` 注入了 `Boot.init()`。三者缺一则装出的包没有入口（网关在跑但点不进去）。
4. **包身份与签名证书**。

> 验收用 `baksmali` 反汇编而不是 `grep` APK：`AndroidManifest.xml` 与 `res/values/strings.xml`
> 在包里都是**编译后的二进制 XML**，文本 grep 必然找不到，会误报「未注册」「未改名」。
> 另外 `dexdump` 的方法引用格式是 `Ljet/android/Boot;.init`（点号），不是 smali 的 `->init`，
> 用错格式也会误报「未注入」。

> 为什么值得单独写个解释器和真机测试：这个补丁的 `if-ge`/`if-gt` 方向在编写过程中被写反过
> 多次，而 `smali.jar assemble` **只检查语法不检查语义**——每次都「编译通过」。人眼审查在
> 条件跳转极性上已被证明不可靠。

### 改包名（可选）

上游墨听的 applicationId 是 `com.applecabal.njread`。改造版如果沿用同一个包名，
装到手机上会与原版墨听共用数据目录与签名身份——两个版本无法并存，签名不一致时
覆盖安装还会直接失败。要对外分发就换掉：

```bash
JETHUB_PACKAGE=com.example.moting python3 patches/patch-package.py full-decoded
```

`build-full.sh` 第 7 步会读 `JETHUB_PACKAGE` 自动调用它；不设置就沿用上游包名。

包名不只在 `AndroidManifest.xml` 的 `package` 属性里，还以字符串形式散落在 9 个
smali 中（`BuildConfig.APPLICATION_ID`、三个 provider authorities、取包名的工具类等）。
逐处枚举容易漏，所以脚本做的是全树字符串替换——`com.applecabal.njread` 这个字面量
在 apktool 解包出的树里只可能是包名本身，不会误伤类描述符。上游 4.0.20-r2 实测命中
16 处 / 9 个文件。

> 改名后是**另一个应用**：不会继承原版墨听的书架、阅读进度、AI 模型中心配置，
> 需要重新初始化。这是 applicationId 的固有语义。

### 改应用显示名（可选）

`patch-label.py` 把桌面图标下的名字从「墨听」改成「墨听JetHub」，用来区分原版与内置版：

```bash
python3 patches/patch-label.py full-decoded                      # 只改默认资源
python3 patches/patch-label.py full-decoded --all-locales        # 四个 locale 一起改
python3 patches/patch-label.py full-decoded 墨听JetHub测试版      # 自定义名称
```

`app_name` 分布在四个文件里：`res/values/strings.xml`（默认）与 `values-zh-rCN` /
`values-zh-rHK` / `values-zh-rTW`。默认只改前者——中文设备会走 `values-zh-rCN`，
所以**想在中英文设备上都看到改名，得加 `--all-locales`**。

> 注意别误伤 `app_name_a`（值「阅读db·A」）。脚本的正则锚定 `<string name="app_name">`，
> 后面紧跟的不是 `>`，所以 `app_name_a` 不会被命中。

### 环境变量一览

| 变量 | 默认 | 说明 |
|---|---|---|
| `JETHUB_UPSTREAM_APK` | 自动下载到 `./upstream/` | 墨听原始 APK；指定了就跳过下载 |
| `JETHUB_UPSTREAM_TAG` | `v4.0.19-r1` | 上游 release tag |
| `JETHUB_UPSTREAM_ASSET` | `MoTing-4.0.19-r1-…-R8-release.apk` | 上游资产名 |
| `JETHUB_UPSTREAM_SHA` | `1f2b7e9b…4530af` | 上游资产 sha256 |
| `ANDROID_BUILD_TOOLS` | 先找 `./sdk/build-tools/34.0.0`，再回退系统 SDK 里最高版本 | 含 zipalign/apksigner/d8 |
| `JETHUB_TOOLCHAIN` | `./toolchain` | jar 目录 |
| `JETHUB_SDK` | `./sdk` | SDK 片段目录 |
| `JETHUB_DECODED` | `./full-decoded` | 反编译树 |
| `JETHUB_KEYSTORE` / `JETHUB_KS_ALIAS` / `JETHUB_KS_PASS` / `JETHUB_KEY_PASS` | — / `jethub` / — / 同前 | 签名 |
| `JETHUB_PACKAGE` | 空 | 改包名，空则沿用上游 |
| `JETHUB_APP_LABEL` | `墨听JetHub` | 改应用显示名；传空串则跳过 |
| `JETHUB_APP_LABEL_ALL_LOCALES` | 空 | 非空则四个 locale 的 `app_name` 一起改 |
| `JETHUB_OUT` | `墨听-4.0.20-jethub内置版.apk` | 产物名 |
| `JETHUB_REDECODE` | 空 | 非空则强制重新解包 |
| `JETHUB_SKIP_DEPS` | 空 | 非空则跳过依赖自举 |
| `JETHUB_GH_MIRRORS` | 见上 | GitHub 加速镜像列表（空格分隔） |
| `JETHUB_SRC_BUDGET` | `300` | 单个下载源最多占用多少秒，超时换下一个候选（续传接着下） |
| `JETHUB_MAX_PASSES` | `10` | 候选轮完算一轮；最多几轮。一整轮一个字节都没长就提前停 |
| `JETHUB_SLOW_OPTS` | `--speed-limit 1024 --speed-time 60` | 连续 60 秒低于 1 KB/s 判定该源已死 |
| `JETHUB_JAVA_OPTS` | `-Dfile.encoding=UTF-8` | 传给 `java` 的 JVM 参数 |
| `JETHUB_JAVAC_OPTS` | `-J-Dfile.encoding=UTF-8` | 传给 `javac` 的 JVM 参数 |

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

墨听（Legado 衍生版）的著作权归其原作者所有。本仓库只分发改造工程，不重新分发原 APK ——
`fetch-deps.sh` 只是从上游作者自己的发布仓库下载，且校验其官方 `.sha256`。改造产物仅供
个人学习与自用，请勿用于商业分发。

## License

MIT，见 [LICENSE](LICENSE)。
