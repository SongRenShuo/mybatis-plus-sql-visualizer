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
8. 测试：`UpdateSectionTest` 补 4 例 + `DevUpdateModeTest`（纯逻辑）+ `UpdateSourceTest` 补 URL 分流

**测试要求（反向验证必须有）**

- 默认（未解锁）：`checkPreviewButton.isVisible == false`；
- 7 次点击（注入时钟或直接调 `DevUpdateMode.unlock()`）→ 可见；
- 点开发版按钮 → 注入的 `checkRunner` 记录到的 URL 是 `preview.update.json`（**分流断言**）；
- 点正式按钮 → 记录到的仍是 `update.json`（**回归断言**）；
- 语言切换（`updateTexts()` 再调一次）后按钮仍可见（**P-3 的钉子**）；
- 反向 1：把 `previewManifestUrl()` 改成返回正式 URL → 分流断言必红；
- 反向 2：删掉 `updateTexts()` 里的可见性恢复 → 「切换后仍可见」必红。

## 七、风险

- **彩蛋变支持负担**：用户开了开发模式读到的预览清单常指向未发布版本，可能报 404。文案必须写清「开发版通道」，且失败态复用 CHANNEL_MISSING 的兜底链接（别只说失败）。
- **`-D` 直通**：`mpvs.update.devMode` 只在启动时读（同现有 `mpvs.update.manifest.url` 口径），文档要写清需重启。
- **与「抢先体验」开关的关系**：那个复选框管的是**启动时静默检查**，与本次的「手动检查开发版」互不影响；不要合并语义。

## 八、实施记录

<!-- 待回填 -->
