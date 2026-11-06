# T-DevUpdateChannel：开发者模式下读取预览更新清单（2026-11-06 立项）

> 用户设想的形态：**多次点击设置页的「构建版本号」，出现「检测更新（开发版）」按钮，读的是
> `update/preview.update.json`**。
> 结论：**可行**，且几乎不用新造链路——检查/失败三态/下载安装三套逻辑都能复用，只需加「一个隐藏入口 + 一个 URL 分流 + 一段渲染保持」。
> 本册只做计划；实施另派。

## 一、现状锚点（都在 master 上读过，位置准确）

| 关注点 | 现状 | 位置 |
|---|---|---|
| 版本号标签 | `UpdateSection.versionLabel`（JBLabel）显示「当前版本: x」 | `config/UpdateSection.kt:74,296` |
| 构建号（用户说的「构建版本号」） | **内联创建的 JBLabel**，`text = PluginBuildInfo.displayText()`，旁边一个「复制」按钮 | `config/PluginSettingsPanelSections.kt:713-717` |
| 检查链 | `checkRunner: () -> CheckResult` seam，默认 `PluginUpdateRunner.check(PluginVersion.current())` | `config/UpdateSection.kt:60-61` |
| 检查实现 | `check(currentVersion, fetch)` 内部取 `UpdateSource.manifestUrl()`；失败分四类（NETWORK / CHANNEL_MISSING / MANIFEST_MALFORMED / VERSION_UNREADABLE） | `update/PluginUpdateRunner.kt:57-100` |
| 清单地址 | `MANIFEST_PATH = "update/update.json"`；`DEFAULT_MANIFEST_URL` = Gitee raw master；`-Dmpvs.update.manifest.url` 覆盖（进程内首读固化） | `update/UpdateSource.kt:26-56` |
| 预览清单 | `update/preview.update.json` 在仓库里（当前 `version=2026.11.6-beta.2`），**运行期无人读** | `update/preview.update.json` |
| 状态机 | Idle / Checking / UpToDate / Available / Downloading / Installed / InstalledLive / Failed，渲染集中在 `renderState()` | `config/UpdateSection.kt:45,270-291` |
| 既有测试 | `UpdateSectionTest`（20 个用例）、`PluginUpdateRunnerTest`、`SettingsRealityGuardTest`、`PluginSettingsPanelBindingTest` | `src/test/.../config|update` |
| 既有调用点 | `StartupUpdateCheck:49`、`UpdateSection:61`、测试 5 处（`PluginUpdateRunnerTest` 用 lambda 尾参） | 见 grep |

## 二、目标 / 非目标

**目标**

1. 设置页「构建版本号」在**限定时间内连点 N 次**后解锁开发模式；解锁后出现「检测更新（开发版）」按钮。
2. 该按钮读 **`update/preview.update.json`**，其余流程（解析 / 版本比较 / 失败三态 / 下载安装 / 重启或热替换）与正式通道完全一致。
3. 正式「检查更新」**行为一字不变**（仍读 `update/update.json`），且开发模式默认关闭、不污染普通用户。

**非目标**

- 不动 `update/update.json`（用户专属），也不改发布流程。
- 不做第二套下载/安装实现。
- 不做「强制显示更新」（版本相等就是「暂无可用更新」，语义与正式通道一致）。
- 不持久化开发模式（默认每次 IDE 会话重新解锁，见 D-2）。

## 三、方案（推荐形态）

### 3.1 解锁入口

- 点击目标：**构建号 `JBLabel`**（`PluginSettingsPanelSections.kt:713`，现在没有字段引用，需提为局部变量再挂监听）。
- 规则：**2 秒内连点 7 次**（`System.currentTimeMillis()` 滑动窗口；每次点击重置窗口起点策略二选一，见 D-1）→ `DevUpdateMode.unlock()`。
- 反馈：解锁时构建号 tooltip 改一行「开发模式已开启：预览通道」，并在 `UpdateSection` 里显示新按钮（无弹窗，避免打扰）。
- 自动化通道：`-Dmpvs.update.devMode=true` 直接视为已解锁（截图/测试/支持排查用）。

### 3.2 新按钮与状态

- `UpdateSection` 新增 `checkPreviewButton`（默认 `isVisible=false`），文本键 `settings.update.checkPreview`「检测更新（开发版）」。
- 点击走**同一条状态机**，只是把「本次检查用哪个 URL」记下来，状态行文案加通道后缀（`settings.update.channel.preview`「（开发版通道）」）。
- tooltip 显示实际读取的 URL（便于对账「到底读了哪个文件」）。

### 3.3 URL 分流（关键，别新造通道）

`UpdateSource` 增：

```kotlin
const val PREVIEW_MANIFEST_PATH = "update/preview.update.json"
const val DEFAULT_PREVIEW_MANIFEST_URL = "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/raw/master/$PREVIEW_MANIFEST_PATH"
const val PREVIEW_URL_OVERRIDE = "mpvs.update.previewManifestUrl"

fun previewManifestUrl(): String = System.getProperty(PREVIEW_URL_OVERRIDE)?.takeIf { it.isNotBlank() }
    ?: manifestUrl().replace(MANIFEST_PATH, PREVIEW_MANIFEST_PATH)   // 本地 server 只需多一条路由
```

`PluginUpdateRunner.check` 增加**带默认值的第三参**：

```kotlin
fun check(currentVersion: String?, fetch: (String) -> FetchOutcome = ..., manifestUrl: String = UpdateSource.manifestUrl())
```

既有两处调用（`StartupUpdateCheck`、`UpdateSection.checkRunner` 默认值）与测试里 `check(x) { … }` 的 lambda 尾参写法**都不用改**（第三参有默认值）。

### 3.5 下载 404 必须友好提示，不得抛错（用户 2026-11-06 明确要求）

预览清单的 `downloadUrl` 极可能指向**尚未发布的 release**（典型：`preview.update.json` 已写 `2026.11.6-beta.3`，但 Gitee 上还没有该 tag 的附件）。现状会很难看：

- `UpdateApi.download` 用 `.throwStatusCodeException(true)`，404 直接抛 `HttpRequests.HttpStatusException`；
- `PluginUpdateRunner.downloadAndInstall` 把它变成 `InstallResult.Failed(message)`，UI 渲染 `settings.update.installFailed=更新失败：{0}` —— 用户看到一串 HTTP 异常文本，像是插件坏了。

要求（两条通道都适用，不只开发版）：

1. **新增一种可辨识结局**：`InstallResult.NotPublished`（404/410 专用），与 `Failed` 分开；
2. **文案与出口**：状态行写「该更新包还没有发布（HTTP 404），请稍后重试，或到发布页手动下载」，并显示**已有的 Gitee 发布页链接**（`UpdateSource.GITEE_RELEASES_URL`，与 `CHANNEL_MISSING` 兜底同款出口）——不显示原始异常文本，不打日志 ERROR（`LOG.info` 一行带 URL 与状态码即可）；
3. 其余失败（网络不通/非法 zip/安装失败）仍走 `Failed`，**不要**把 404 的友好化扩散成「所有失败都说没发布」。

### 3.4 渲染保持（最容易漏的坑）

`updateTexts()` / 语言切换 rebuild 会重建按钮文本与 `renderState()`。新按钮的可见性必须**在渲染路径里按 `DevUpdateMode.enabled` 重新决定**，不能只在点击解锁那一刻 `isVisible=true` —— 否则切一次语言按钮就消失（现象会被误报成「开发模式失效」）。

## 四、决策点（推荐值，实施可推翻但要写理由）

| 编号 | 事项 | 推荐 | 理由 |
|---|---|---|---|
| D-1 | 连点阈值与窗口 | **7 次 / 2 秒**（每次点击都重开窗口） | Android「关于手机」惯例量级；7 次够防误触，2 秒够手速 |
| D-2 | 是否持久化解锁态 | **不持久化**（会话级） | 持久化会在用户机器上留一个隐藏状态，支持排查时无法区分「用户真开了」还是「残留」 |
| D-3 | 点击目标 | **只挂构建号**（不额外挂版本标签） | 用户原话就是构建号；双入口会让「怎么触发的」难以复述 |
| D-4 | 解锁提示 | 只改 tooltip + 出现按钮，**不弹窗** | 弹窗会把彩蛋变成打扰 |
| D-5 | 开发版按钮的失败态 | 完全复用四类失败分诊 | 预览清单的 `downloadUrl` 常指向未发布的 release，404 必须按 CHANNEL_MISSING 显示兜底链接，而不是「检查更新失败」一句 |
| D-6 | 下载 404 的呈现 | **新增 `InstallResult.NotPublished`**（不与 `Failed` 混）+ 发布页链接，文案「该更新包还没有发布」 | 用户明确要求「404 要友好提示不要报错」；`Failed` 现会渲染原始 `HttpStatusException` 文本，像插件坏了 |

## 五、待验证前提（实施者先证伪再动手）

| 编号 | 前提 | 不成立时 |
|---|---|---|
| P-1 | `check()` 加**带默认值**的第三参不破坏既有调用点（含测试里的 lambda 尾参 `check(x) { … }`） | 保留原签名另加 `checkPreview(...)`，但要写明重复代价；不要改现有调用点 |
| P-2 | Gitee raw 对 `update/preview.update.json` 返回 200 且内容是该文件（同仓同分支） | 换 `previewManifestUrl` 默认值为 GitHub raw 镜像；需实测两次（浏览器 UA / 无 UA） |
| P-3 | `updateTexts()` / rebuild 路径可控（新按钮可见性可在此恢复） | 若 rebuild 会重建整个 `UpdateSection` 实例，则把解锁态放 object（本来就这么设计）即可；但要确认 `updateTexts` 确实被 rebuild 调用 |
| P-4 | 设置页没有「按钮/复选框计数」类守卫会把新按钮算进去 | 有则同步计数（`SettingsRealityGuardTest` / `PluginSettingsPanelBindingTest` 先跑一遍再动手） |
| P-5 | 新 i18n 键中英占位符数量一致（`BundlePlaceholderArityGuardTest`） | 无占位符的新键天然通过；若有占位符，中英必须同个数 |

## 六、写集与实施步骤

**写集（预计 6~8 文件，全在 master 上顺序做，不分券）**

1. `update/UpdateSource.kt`：预览路径/URL/覆盖属性
2. `update/PluginUpdateRunner.kt`：`check` 第三参（默认 `manifestUrl()`）
3. `update/DevUpdateMode.kt`（新）：`@Volatile enabled` + `unlock()` + `-D` 直通
4. `config/UpdateSection.kt`：`checkPreviewButton`、通道后缀、`updateTexts()` 里保持可见性、checkRunner 分流
5. `config/PluginSettingsPanelSections.kt`：构建号 label 提为变量 + 连点计数挂监听 + 解锁后刷新按钮
6. `messages/MpSqlVisualizerBundle{,_zh}.properties`：`settings.update.checkPreview`、`settings.update.channel.preview`、`settings.update.devMode.tip`
7. `CHANGELOG.md` `[Unreleased]`（用户可感：开发者模式入口）
8. `update/PluginUpdateRunner.kt`：新增 `InstallResult.NotPublished`；`downloadAndInstall` 把 404/410 分流出来（其余异常仍 `Failed`）
9. `config/UpdateSection.kt`：`NotPublished` 的渲染分支（友好文案 + 发布页链接），复用 `CHANNEL_MISSING` 的兜底出口
10. 测试：`UpdateSectionTest` 补 4 例 + `DevUpdateModeTest`（纯逻辑）+ `UpdateSourceTest` 补 URL 分流 + 404 两条

**测试要求（反向验证必须有）**

- 默认（未解锁）：`checkPreviewButton.isVisible == false`；
- 7 次点击（注入时钟或直接调 `DevUpdateMode.unlock()`）→ 可见；
- 点开发版按钮 → 注入的 `checkRunner` 记录到的 URL 是 `preview.update.json`（**分流断言**）；
- 点正式按钮 → 记录到的仍是 `update.json`（**回归断言**）；
- 语言切换（`updateTexts()` 再调一次）后按钮仍可见（**P-3 的钉子**）；
- 反向 1：把 `previewManifestUrl()` 改成返回正式 URL → 分流断言必红；
- 反向 2：删掉 `updateTexts()` 里的可见性恢复 → 「切换后仍可见」必红。
- **404 友好化（两条，必须各有用例）**：①注入一个抛 404 的 download seam → 结果必须是 `NotPublished` 且 UI 文案**不含**异常原文，含发布页链接；②注入抛 500/IOException 的 seam → 必须仍是 `Failed`（防止友好化扩散）。
- 反向 3：把 404 分支去掉（统一走 `Failed`）→ ①那条用例必红。

## 七、风险

- **彩蛋变支持负担**：用户开了开发模式读到的预览清单常指向未发布版本，可能报 404。文案必须写清「开发版通道」，且失败态复用 CHANNEL_MISSING 的兜底链接（别只说失败）。
- **`-D` 直通**：`mpvs.update.devMode` 只在启动时读（同现有 `mpvs.update.manifest.url` 口径），文档要写清需重启。
- **与「抢先体验」开关的关系**：那个复选框管的是**启动时静默检查**，与本次的「手动检查开发版」互不影响；不要合并语义。

## 八、实施记录

> 实施：2026-11-06，分支 `t-dev-update-channel`（工作树 `.worktrees/dev-update`）。
> 实现提交 `cda8f3e5`（12 文件，+626/-31）；本文档与 CHANGELOG 回填另行提交。
> 决策全部按「推荐」落地（D-1 7 次/2 秒且每次点击重开窗口、D-2 内存态 + `-D` 直通、
> D-3 只挂构建号、D-4 只改 tooltip 不弹窗、D-5 复用失败分诊、D-6 新增 `NotPublished`）。

### 8.1 落点与实现要点

| 文件 | 改动 |
|---|---|
| `update/DevUpdateMode.kt`（新） | `REQUIRED_CLICKS=7` / `CLICK_WINDOW_MS=2000` / `ENABLED_PROPERTY=mpvs.update.devMode`；`@Volatile` 内存态 + `unlock()` + `registerClick(now)`（相邻点击间隔 ≤ 窗口才累加，超窗从 1 重开）+ `parseEnabledProperty`；`resetForTest()` 供用例隔离 |
| `update/UpdateSource.kt` | `PREVIEW_MANIFEST_PATH` / `DEFAULT_PREVIEW_MANIFEST_URL` / `PREVIEW_MANIFEST_URL_OVERRIDE` / `previewManifestUrl()`（专用覆盖优先，否则 `manifestUrl()` 同源改路径；换源值不含 `update/update.json` 时回落默认预览地址，不静默复用正式 URL）；`logOverrideIfAny` 认预览默认地址，避免把预览通道误报成「已换源」 |
| `update/PluginUpdateRunner.kt` | `check(currentVersion, manifestUrl = UpdateSource.manifestUrl(), fetch = …)`；`InstallResult.NotPublished(status)`；`downloadAndInstall` 用 `httpStatusOf()` 挖异常链（含被 IOException 包一层），404/410 → `LOG.info` 一行（URL + 状态码）并返回 `NotPublished`，其余仍 `Failed` |
| `config/UpdateSection.kt` | `Channel{STABLE,PREVIEW}`、`checkRunner: (String) -> CheckResult`、`checkPreviewButton`（构造隐藏）、`applyDevVisibility()` 唯一可见性赋值点、`updateTexts()` 里重算可见性、`refreshDevModeVisibility()`、状态行通道后缀、`NotPublished` 渲染 + 兜底链接可见 |
| `config/PluginSettingsPanelSections.kt` | 构建号 `JBLabel` 提为局部变量 + `MouseAdapter.mouseClicked` 喂 `DevUpdateMode.registerClick()`，解锁后改 tooltip 并调 `updateSection.refreshDevModeVisibility()` |
| `update/StartupUpdateCheck.kt` | **写集外补一处**：`when (result)` 是 exhaustive，新增 sealed 分支后不加分支编译不过；按「启动通道也不许甩异常原文」补 `NotPublished` 分支（复用 WARNING 通知，正文用友好文案并去掉设置页链接行的尾冒号） |
| `messages/MpSqlVisualizerBundle{,_zh}.properties` | `settings.update.checkPreview` / `settings.update.channel.preview` / `settings.update.notPublished` / `settings.update.devMode.tip`（中英对齐，均无占位符） |

### 8.2 待验证前提结论（P-1~P-5）

| 编号 | 结论 | 证据 |
|---|---|---|
| P-1 | **原方案会翻车，已改参数顺序** | Kotlin 尾随 lambda 绑定**最后一个**形参。若写成 `check(version, fetch = …, manifestUrl = …)`，既有 `check(x) { … }` 调用（`PluginUpdateRunnerTest` / `UpdateCheckDiagnosisTest` / `UpdateFlowHeavyTest` 等 14 处）会把 lambda 当 `String` → 编译失败。实施取 `check(version, manifestUrl = …, fetch = …)`，尾随 lambda 仍落 `fetch`，既有调用点零改动（KDoc 已写明原因）。`checkDefaultManifestUrlIsStableChannel` 钉住「不传第三参仍读正式清单」 |
| P-2 | **成立** | `curl .../raw/master/update/preview.update.json` 无 UA 返回 302→CDN 签名 URL，`-L` 跟随后 200 / 1164 bytes，内容正是预览清单（`version=2026.11.6-beta.2`）；带浏览器 UA 同为 302→200。`HttpRequests` 默认跟随 redirect（`UpdateApi` 既有注释实证），与正式清单同一条通路 |
| P-3 | **成立且必须显式恢复** | `updateTexts()` 确实由 rebuild 调用（`PluginSettingsPanelSections.kt` 的 `updateSection.apply { updateTexts() }`；`UpdateSectionTest.testLanguageSwitchRefreshesTexts` 既有用例）。可见性统一走 `applyDevVisibility()`，反向验证 2 红在 `testPreviewButtonVisibilitySurvivesUpdateTexts` |
| P-4 | **无计数类守卫，4 个面板守卫全绿** | `SettingsRealityGuardTest`（4 例）/ `SettingsPanelSearchableGuardTest`（4 例）/ `PluginSettingsPanelBuildInfoTest`（3 例）/ `PluginSettingsPanelBindingTest`（16 例）全 0 failures。`SettingsRealityGuardTest` 只断言「控件字段数 ≥ 60」，新按钮声明在 `UpdateSection.kt` 不在其扫描面；`SettingsPanelSearchableGuardTest` 只采 `JToggleButton`/`JLabel`，`JButton` 不在采集面 |
| P-5 | **成立** | 4 个新键均无 `{n}` 占位符，`BundlePlaceholderArityTest` 无新增风险 |

### 8.3 定向测试读数（命令 + XML 关键字段）

命令（四条类一起）：

```
./gradlew test --tests "cn.song.mybatisplus.visualizer.update.DevUpdateModeTest" \
  --tests "cn.song.mybatisplus.visualizer.update.UpdateSourceTest" \
  --tests "cn.song.mybatisplus.visualizer.update.PluginUpdateRunnerTest" \
  --tests "cn.song.mybatisplus.visualizer.config.UpdateSectionTest" \
  -Pkotlin.compiler.execution.strategy=in-process
```

恢复后绿（`build/test-results/test/*.xml`，2026-11-06 20:33）：

| 类 | tests | failures | errors |
|---|---|---|---|
| `update.DevUpdateModeTest` | 7 | 0 | 0 |
| `update.UpdateSourceTest` | 6 | 0 | 0 |
| `update.PluginUpdateRunnerTest` | 21 | 0 | 0 |
| `config.UpdateSectionTest` | 24 | 0 | 0 |

合计 58 例全绿；`BUILD SUCCESSFUL`。

### 8.4 反向验证（摘掉前红 / 恢复后绿）

| # | 探针 | 摘掉后（红） | 恢复后（绿） |
|---|---|---|---|
| 1 | `previewManifestUrl()` 改成 `return manifestUrl()` | `UpdateSectionTest tests=24 failures=1`，`testPreviewButtonReadsPreviewManifestUrl FAILED`，message=`开发版按钮必须读预览清单，实际=https://gitee.com/.../raw/master/update/update.json` | 24 / 0 / 0 |
| 2 | 删掉 `updateTexts()` 里的 `applyDevVisibility()` | `UpdateSectionTest tests=24 failures=2`：`testPreviewButtonVisibilitySurvivesUpdateTexts FAILED` + `testPreviewButtonVisibleAfterUnlock FAILED`（message=`解锁后应出现开发版按钮`） | 24 / 0 / 0 |
| 3 | 去掉 `downloadAndInstall` 的 404/410 分支（统一 `Failed`） | `PluginUpdateRunnerTest tests=21 failures=3`：`downloadAndInstall404IsNotPublished` / `downloadAndInstall410IsNotPublished` / `downloadAndInstallWrapped404IsStillNotPublished` 全红；同轮 `UpdateSectionTest` 仍 24/0/0（渲染层不受影响，证明分诊点在编排层） | 21 / 0 / 0 |

### 8.5 未做 / 风险

- **未跑**：全量 `testShards`、`verifyGate` / `verifyPlugin` / `buildPlugin`、runIde 沙盒真机走查（连点 7 次、切语言、真 404 下载）。按券令只做定向。
- `-Dmpvs.update.devMode=true` 只在 object 首次触达时读（同 `mpvs.update.manifest.url` 口径），运行中改属性需重启——已写进 `DevUpdateMode` KDoc，未动 README（写集外）。
- 预览清单的 `downloadUrl` 目前指向 `2026.11.6-beta.2`；该 Release 是否存在未实测（本次只验证清单可读）。若不存在，用户点「下载并安装」就会看到新增的「该更新包还没有发布」友好态——正是本次要求的形态。

