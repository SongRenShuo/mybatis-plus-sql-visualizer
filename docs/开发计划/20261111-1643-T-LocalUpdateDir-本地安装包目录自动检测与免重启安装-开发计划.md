# T-LocalUpdateDir：关于页「本地安装包目录」自动检测与免重启安装 — 开发计划

- 日期：2026-11-11 16:43
- 任务编号：T-LocalUpdateDir
- 来源：用户 2026-11-11 新需求——「为了让内网开发的伙伴更新更顺利，现在需要在插件的关于页面版本信息附近新增一个『本地安装包目录』，用来指定存放新安装包的路径，达到自动检测最新版、无需重启安装的目的；需要留意文件路径确保多个平台都支持，并且最好能校验下目录是否合法」。
- 性质：功能（新增第三条更新来源通道 + 一个设置项 + 一个启动检测触点）。不改现有远端通道语义，不改平台动态安装链。
- 前置：无硬前置。复用 M28/T-DynUpdate 的更新状态机（`UpdateSection`）、安装编排（`PluginUpdateRunner`）、动态热替换（`DynamicInstallSupport`）、包校验（`UpdateZipGuard` / `validateArtifact`）。
- 红线约束：新增「插件主动」通知触点（启动时本地检测）须过《设计规范-弹窗克制红线》准入五问并登记触点清单；不做任何静默自动安装（见 D-1）。
- 决策：**D-1~D-8 全部按推荐拍板**（用户 2026-11-11 确认「拍板可以按照推荐」）。实施时不再回头讨论这些选项。
- 状态：**计划（未实施，生产代码零改动）**

---

## 一、需求解读（把话说死）

| 诉求原话 | 落点 | 可验收判据 |
|---|---|---|
| 在「关于页面」的「版本信息」附近 | 设置 → 关于（`AboutConfigurable`）「更新与诊断」组，版本信息行**下方**新增一行 | 打开设置关于页，版本信息行下面能看到「本地安装包目录」一行 |
| 新增「本地安装包目录」指定存放新安装包的路径 | `PluginSettings.State` 新增持久化字段（绝对路径字符串；空 = 功能关闭） | 填路径 → 应用 → 重启 IDE 后路径仍在 |
| 自动检测最新版 | 扫描目录内 zip → 读包内 `plugin.xml` 的 `<version>` → 与当前版本比较（`VersionComparator`，与 Marketplace 同规则） | 目录里放一个更高版本的包 → 页面状态行/启动通知给出「发现 X」，放同版或旧版则「已是最新」 |
| 无需重启安装 | 复用 `DynamicInstallSupport.install(path)`：平台允许则动态卸载+装载（`InstalledDynamic`），不允许则回退重启脚本（`InstalledRestartRequired`）并如实提示 | 263/262 上安装后提示「已安装并立即生效—无需重启」；243 上提示「重启 IDE 生效」 |
| 文件路径多平台支持 | 一律用 `java.nio.file.Path`，不做字符串拼接；覆盖 Windows 盘符/UNC/反斜杠、macOS/Linux 正斜杠、`~`、中文与空格、大小写差异 | 三平台各走一遍 A6/A7（见 §九），Windows 中文+空格路径端到端可用 |
| 校验目录是否合法 | 结构化校验状态机（空/非法字符/相对路径/不存在/无权限/不是目录/不可读/指向插件安装目录），页面逐条给原因 | A2~A5 四种错误态文案各自可复现，且都不抛异常、不弹窗 |
| **不能借这个通道装成别的插件**（用户 2026-11-11 追加） | 三层归属闸门：扫描期按 `<id>` 过滤 → 安装前复核 `<id>` → 包布局校验（禁止夹带其他插件目录，重启脚本路径会把 zip 直解到插件根） | 目录里放一个别的插件包/夹带包/A 冒充包，一律**不提示、不可安装**，跳过计数进 tooltip；反向验证摘掉闸门必红 |

**边界澄清**（避免实施时跑偏）：

1. 这是「本地文件系统目录」通道，不是「内网 HTTP 源」。后者已由 `-Dmpvs.update.manifest.url=` 支持（README「内网更新源」），但要求改 JVM 参数并重启 IDE——本任务要解决的正是「不想改参数、不想重启」。
2. 「无需重启」是平台能力，不是本插件的承诺：243 上本插件扩展的 `com.intellij.sql.dialect` 非动态，平台必然拒绝卸载（`PlatformEpDynamism` 真机取证），只能用既有的「重启生效」分支。
3. 检测到新版本后**默认不自动安装**，与远端通道一致：通知/页面给「立即安装」入口，用户点头才动。理由见 D-1。

---

## 二、现状（证据先行）

| 面 | 现状 | 证据 |
|---|---|---|
| 更新来源 | 只有两条**远端**清单通道：正式 `update/update.json`、开发版 `update/preview.update.json`；均可被系统属性换源 | `update/UpdateSource.kt:27-48`、`:61-79` |
| 检查编排 | `PluginUpdateRunner.check(currentVersion, manifestUrl, fetch)` —— 只认 HTTP 清单文本，签名里没有「来源」概念 | `update/PluginUpdateRunner.kt:77-106` |
| 下载安装 | `downloadAndInstall(manifest, indicator, download, install)` 恒走下载，且 `finally` 里删除临时文件——**不能直接喂本地文件**（会删掉用户的包） | `update/PluginUpdateRunner.kt:117-162`（删除在 `:159-161`） |
| 动态安装 | `DynamicInstallSupport.install(zip: Path)` 已是**路径入口**：前置校验 → 开发态守卫 → 平台能力探测 → 卸载预检 → EDT 卸载+装载；任何失败回退重启脚本 | `update/DynamicInstallSupport.kt:64-117` |
| 包内版本读取 | `validateArtifact(zip)` 已能从 zip（含 IPGP 的 `lib/*.jar` 布局）读出 `META-INF/plugin.xml` 的 `<id>`/`<version>`，并校验 id 必须等于本插件 id | `update/DynamicInstallSupport.kt:161-215`；id 常量 `UpdateSource.kt:24` |
| 包安全守卫 | `UpdateZipGuard.check(path)` 三重上限（条目 64 / 单条 64MB / 累计 128MB）+ 头谎报截断检测 | `update/PluginUpdateRunner.kt:135-143`；`update/UpdateZipGuard.kt` |
| 热替换平台分界 | 243 ❌ 只能重启生效；253/262/263 ✅ 可热卸载 | `update/PlatformEpDynamism.kt` 事实表（2026-11-06 真机取证） |
| 插件 since-build | `243` | `src/main/resources/META-INF/plugin.xml:11` |
| 页面状态机 | `UpdateSection` 已有 8 态（Idle/Checking/UpToDate/Available/Downloading/Installed/InstalledLive/Failed）与双通道枚举 `Channel{STABLE,PREVIEW}`，UI 编排与网络/安装分离（seam 注入） | `config/UpdateSection.kt:48-58`、`:74-82`、`:282-330`、`:391-430` |
| 版本信息行 | 关于页「更新与诊断」组第 2 行，容器就是 `updateSection` | `config/PluginSettingsPanelSections.kt:699-716`（版本信息在 `:715`） |
| 启动检查 | `StartupUpdateCheck`：`autoCheckUpdate && !alreadyChecked` 才跑远端检查；仅 Available 且未被忽略才弹通知，动作＝立即安装/忽略此版本/稍后/查看发布页 | `update/StartupUpdateCheck.kt:31-39`、`:42-103`、`:186-187` |
| 忽略版本 | `ignoredUpdateVersions`（`;` 分隔），只抑制**自动**通知 | `config/PluginSettings.kt:261-268`；`update/VersionIgnore.kt` |
| 设置字段接线范式 | 路径类文本框有现成四段接线：字段声明 → `isModified` → `apply` → `reset`，加浏览按钮 | `config/PluginSettingsPanel.kt:609`、`:1274`、`:1421`、`:1625`、`:1655-1665` |
| 设置页搜索索引 | 关于节 3 条硬编码；新控件必须同批登记，否则守卫变红 | `config/PluginSettingsFolderConfigurable.kt:641-645`；`SettingsPanelSearchableGuardTest` 断言一 |
| 设置导入导出 | `exportToJson` 是**硬编码 23 项**，新字段天然不进导出；`importFromJson` 以现值为底只覆盖出现的键 | `config/SettingsIO.kt:31-56`、`:85-120` |

**差距**：全仓没有任何「本地目录」概念；`UpdateSection` 只有两个远端通道；`downloadAndInstall` 与本地文件不兼容（临时文件会被删）；设置页也没有路径校验概念（现有路径项都是「自动创建目录」，不判合法性）。

---

## 三、设计

### 3.1 数据模型

`PluginSettings.State` 新增一个字段（默认空＝功能关闭）：

```kotlin
/**
 * 本地安装包目录（T-LocalUpdateDir）：绝对路径；空字符串 = 关闭。
 * 已做 `~` 展开与 `Path.toAbsolutePath().normalize()` 归一，跨平台只用
 * `java.nio.file.Path` 解析，不做字符串分隔符假设。机器相关，不进设置导出/导入。
 */
var localUpdateDir: String = "",
```

- 不加第二个「自动安装」开关（D-1）；也不新增独立「启动检测」开关（D-2）。
- 不加 `localUpdateAutoCheck` 之类字段：**配置目录即视为用户同意检测**，检测只在「发现更高版本」时发一条通知，每会话一次。
- 持久化沿用 `PluginSettings` 的 XML 序列化，Windows 反斜杠由平台转义（P-7 实测确认）。

### 3.2 目录解析与合法性校验（纯逻辑，可单测）

新文件 `update/LocalUpdateDir.kt`，只做路径层：

```kotlin
object LocalUpdateDir {
    sealed interface DirCheck {
        data object Disabled : DirCheck                              // 空 → 功能关闭
        data class InvalidPath(val reason: String) : DirCheck        // InvalidPathException / 含 NUL
        data object Relative : DirCheck                              // 相对路径（无项目上下文，语义不确定）
        data object NotFound : DirCheck                              // exists=false 且 notExists=true
        data object Inaccessible : DirCheck                          // exists/notExists 都为 false（权限或网络盘不可达）
        data object NotDirectory : DirCheck                          // 存在但是文件
        data object NotReadable : DirCheck                           // Files.isReadable=false
        data object InsidePluginsDir : DirCheck                      // 指向 IDE 插件目录或其子目录
        data class Ok(val dir: Path) : DirCheck
    }
    fun parse(raw: String): DirCheck   // 展开 ~ → Paths.get → toAbsolutePath().normalize() → 文件系统判定
}
```

关键实现口径（每条都对应一个跨平台坑）：

| 处理 | 规则 | 为什么 |
|---|---|---|
| 前后空白 | `trim()` | 从聊天工具/文档里复制路径常带尾随空格 |
| `~` 展开 | 仅 `~`、`~/…`、`~\…` 前缀 → `System.getProperty("user.home")` 拼接 | Java 的 `Path` 不展开 `~`；macOS/Linux 用户习惯直接粘 `~/Downloads/插件包` |
| 环境变量 | **不支持** `%USERPROFILE%` / `$HOME`，命中 `%…%`/`$…` 时给明确文案 | 半吊子展开比不展开更坑；文案写清「请填绝对路径」 |
| 非法字符 | 捕获 `InvalidPathException`（Windows 下 `:` 位置错误、`*`、`?` 等） | 不能让异常冒到 EDT |
| 绝对性 | 归一后 `isAbsolute` 必须为真 | 设置项没有「当前目录」概念，相对路径无法定义基准 |
| 归一化 | 只用 `toAbsolutePath().normalize()`，**不用** `toRealPath()` | `toRealPath` 在网络盘/权限不足/符号链接断裂时会抛，把「能用的目录」误判成非法 |
| 尾分隔符 | `normalize()` 自然去掉；回填 state 用归一后的字符串 | 避免 `C:\dir\` 与 `C:\dir` 存两套 |
| 不存在 vs 无权限 | `Files.exists` 为 false 时再看 `Files.notExists`：仍为 false ⇒ 不可判定（权限/网络盘） | 这是 `Files` 文档定义的「无法确定」态，直接报「不存在」会误导内网用户 |
| 指向插件目录 | 与 `PathManager.getPluginsDir()` 比较：`dir.startsWith(pluginsDir)`（`WindowsPath.startsWith` 本身大小写不敏感，无需 os.name 分支） | 指向插件安装目录会自我覆盖/文件锁，直接拒装 |
| 符号链接 | `Files.isDirectory` / `isReadable` 默认跟随链接；不做额外解析 | 内网用户可能把目录做成共享盘链接，跟随才是期望行为 |

> 所有文件系统调用（`exists`/`isDirectory`/`list`）**不得在 EDT 执行**：死掉的 UNC 路径上 `Files.exists` 可阻塞数秒，会触发平台慢操作断言。页面/启动链一律「EDT 拿快照 → BGT 判定 → `invokeLater` 回渲染」。

### 3.3 包发现与「最新版」判定

同一文件（或 `update/LocalPackageScanner.kt`）加扫描层：

```kotlin
data class LocalPackage(
    val version: String,          // 以包内 plugin.xml 为准（权威）
    val path: Path,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

sealed interface ScanResult {
    data object Disabled : ScanResult
    data class DirInvalid(val check: LocalUpdateDir.DirCheck) : ScanResult
    data class Empty(val dir: Path) : ScanResult                    // 可读，但没有 .zip
    data class NoValidPackage(val dir: Path, val skipped: Int) : ScanResult  // 有 zip，但都不是本插件/损坏
    data class UpToDate(val latest: LocalPackage, val total: Int, val skipped: Int) : ScanResult
    data class Available(val latest: LocalPackage, val total: Int, val skipped: Int) : ScanResult
    data class Failed(val reason: String) : ScanResult              // 列举目录 IO 异常
}
```

扫描规则：

1. **非递归**列目录（D-3），扩展名 `.zip` 大小写不敏感（Windows 常见 `.ZIP`）。
2. 候选上限 **100** 个；超出按 mtime 降序截断并在状态 tooltip 里写明「另有 N 个未扫描」，不静默吞。
3. 每个候选都过 **G1** `DynamicInstallSupport.validateArtifact(path)` 与 **G2** `verifyOwnership(path)`（见 §3.3.1）：
   - `Ok(id, version)` 且 `id == UpdateSource.PLUGIN_ID`、布局为 `Ours` → 合法；
   - `Invalid` / `NotOurs` / `Tainted` / 抛异常 → 计入 `skipped`（不改文案噪音，只在 tooltip 明细 + 日志一行）。
4. 「最新」＝合法候选里 `VersionComparator.compare` 的最大值；同版本取 `modifiedMs` 更大者（确定性）。
5. **只用包内版本，不用文件名版本**：用户会把 `xxx-2026.11.12.zip` 改名为 `最新.zip` 或复制成 `xxx (1).zip`。文件名只在日志/展示里用。
6. **只升不降**：`latest.version <= current` ⇒ `UpToDate`，不提示、不提供安装入口。目录里只剩旧版本时不能把用户降级。
7. 性能：一次会话内按 `(path, size, modifiedMs)` 缓存校验结论（`ConcurrentHashMap`，容量 ≤200），重复扫描（页面防抖/重开设置）不重复开包；首次全扫走 BGT + 进度条。网络盘首次扫描成本见 P-6，超时策略见 §十。

### 3.3.1 归属校验：这个通道只能装本插件（用户 2026-11-11 追加的硬要求）

**威胁面**不是「扫描器认错版本」，而是**安装链会把不属于本插件的包装进去**。两条安装路径的实际行为不同：

- 动态路径：`installAndLoadDynamicPlugin(zip)` 按 zip **顶层目录名**解包；不是本插件的包会装成另一个插件，甚至覆盖同名目录（`PlatformPluginInstaller.kt:51-60` 的真实教训：解包目标/顶层目录名决定最终落点）。
- 重启脚本路径：`StartupActionScriptManager.UnzipCommand(zip, <plugins 根>)` **纯解压到 IDE 插件根目录**——zip 里若夹带第二个插件目录，重启后那个插件也会一起被装上，且与本插件更新毫无关系。

所以归属校验必须**三道闸门**，任何一道不过就跳过（扫描期）或拒绝（安装期）：

| 闸门 | 时机 | 判据 | 不过的后果 |
|---|---|---|---|
| G1 描述符归属 | 扫描每个候选 + 安装前复核 | `DynamicInstallSupport.validateArtifact(path)` 返回 `Ok(id, version)` 且 `id == UpdateSource.PLUGIN_ID`（`cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer`） | 扫描期计入 `skipped`（不提示、不进候选）；安装期 `Failed`，文案「不是本插件的安装包」 |
| G2 布局归属（新增） | 扫描期 + 安装前 | zip 顶层**只能有一个目录**且名 == `UpdateSource.INSTALL_DIR_NAME`（`mybatis-plus-sql-visualizer`），目录内部不限（`META-INF/`、`lib/*.jar` 等）；唯一例外是完全平坦的 `META-INF/plugin.xml` 布局。**任何第二个顶层目录、任何插件目录以外的顶层条目**即拒绝 | 扫描期计入 `skipped`；安装期 `Failed`，文案「安装包内含其他内容，已拒绝安装」 |
| G3 有界守卫 | 安装前 | `UpdateZipGuard.check(path)`（条目数/单条/累计三重上限 + 头谎报） | `Failed`（既有行为不变） |

G2 的实现口径（新文件 `update/PluginPackageOwnership.kt` 或并入 `UpdateZipGuard`）：

```kotlin
internal sealed interface Ownership {
    data class Ours(val version: String, val topLevelDir: String) : Ownership
    data class NotOurs(val reason: String) : Ownership   // id 不符 / 无描述符 / 无本插件顶层目录
    data class Tainted(val reason: String) : Ownership   // 夹带其他顶层目录或文件
}

/**
 * 归属 + 布局校验：顶层必须只有一个目录且名 == UpdateSource.INSTALL_DIR_NAME。
 * 只读中央目录（ZipFile.entries），不解压——与 UpdateZipGuard 同层、同代价。
 */
internal fun verifyOwnership(zip: Path): Ownership
```

细节：

- 顶层判定＝条目名去掉首段后是否还有内容；`mybatis-plus-sql-visualizer/lib/x.jar` 的顶层段是 `mybatis-plus-sql-visualizer`。
- 允许的例外：zip 内允许出现 `META-INF/` 直属描述符（IPGP 解包态布局，`DynamicInstallSupport.kt:163-166` 明确支持这种形态），但此时**不得再出现**任何与 `INSTALL_DIR_NAME` 同级的其他目录——即「要么整包是 `<插件目录>/…`，要么是 `META-INF/plugin.xml` + 直属文件」。
- 目录名大小写：zip 条目名大小写敏感（Linux/macOS 上解包后是两个目录），比较按 `equals(ignoreCase = true)` 但**记录实际名**进日志；Windows 上 `Foo` 与 `foo` 解包会互相覆盖，这一条正好也拦住。
- 冒名形态：`id` 对但顶层目录名是 `evil-plugin/` → **Tainted**（重启路径会装成另一个插件）；`id` 不符但目录名正确 → **NotOurs**。
- 扫描期也跑 G2（代价是读一次中央目录，很便宜），这样「夹带包」在页面上直接表现为 `skipped`，不会先弹出「发现新版本」再安装失败。
- D-5 的兼容性判据在同一次描述符解析里顺带取出：`Ours` 增带 `sinceBuild: Int?`（`plugin.xml` 的 `<idea-version since-build="…"/>`），与 `ApplicationInfo.build.baselineVersion` 比较；**不二次开包**，也不改 `DynamicInstallSupport.ArtifactCheck` 的现有签名（在该文件里另起内部解析函数）。

**不做过度拦截**：不校验签名/哈希/发布者（内网自建包没有签名，加了只会让通道不可用）；不做「文件名必须匹配版本号」（用户会改名，版本以包内为准）。

> 反向验证的铁律：G1/G2 两道闸门各自要有独立变红读数——只摘 G1 时「冒名 id」用例红；只摘 G2 时「夹带目录」用例红。两个用例不能共用一个夹具，否则摘掉一道闸门另一道仍会拦住，读数会假绿。

### 3.4 免重启安装（本地包入口）

`PluginUpdateRunner` 新增一个与下载链**平级**的入口，不改现有方法签名：

```kotlin
/**
 * 安装本地包：源 zip → 临时副本 → 有界校验 → 动态安装 / 重启脚本；finally 删**临时副本**，
 * 用户目录里的源包一律不动。
 */
fun installFromLocal(
    pkg: LocalPackage,
    indicator: ProgressIndicator? = null,
    install: (Path) -> InstallResult = { DynamicInstallSupport.install(it) },
): InstallResult
```

步骤：

1. **归属、布局与兼容性复核（G1 + G2 + D-5，安装前必跑）**：`validateArtifact` 的 `id` 必须等于 `UpdateSource.PLUGIN_ID`；`verifyOwnership` 必须是 `Ours`（`NotOurs`/`Tainted` 拒绝）；包内 `since-build` 高于当前 IDE baseline 时拒绝并给可执行建议——**不复用扫描期缓存结论**（扫描到点击安装之间，共享盘上的文件可能已被替换；指纹 `(size, mtime)` 变化即重验）。
2. `FileUtil.createTempFile("mpvs-local-", ".zip", false)`；
3. `Files.copy(pkg.path, temp, REPLACE_EXISTING)` —— 复制失败（共享盘掉线/被杀软锁）返回 `Failed`，文案带文件名；
4. `isValidZip(temp)` → `UpdateZipGuard.check(temp)` → 不过则 `Failed("本地安装包未通过校验: …")`；
5. `install(temp)` → 三态原样透传（`InstalledDynamic` / `InstalledRestartRequired` / `Failed`）；
6. `finally` 删临时副本。

> 复核顺序刻意放在复制**之前**：先否掉「根本不该装」的包，不去碰用户目录里的可疑文件，也避免坏包先落进临时目录再被清理的噪音。复制后再用临时副本跑一遍 zip 守卫（防复制期间被替换的极小窗口）。

为什么必须复制而不是直接装源文件：①与远端链共用同一套「临时文件生命周期」，不碰用户目录；②网络盘抖动/被占用时安装过程读的是本地稳定副本；③`DynamicInstallSupport` 的失败回退链（`PlatformPluginInstaller.persistZip`）本来就会再复制一份，直连源文件只会多两处不一致。

**「无需重启」如何如实呈现**：`InstallResult` 三分支已有 UI 分叉（`UpdateSection.renderInstall` → `InstalledLive` / `Installed`），本地通道不新增文案分支。243 上必然走 `InstalledRestartRequired`，这是平台事实，不包装成「失败」。

### 3.5 页面 UI（关于页，版本信息行下方）

落点 `config/PluginSettingsPanelSections.kt` 的关于组，紧跟 `:715` 版本信息行之后新增一行；控件放在 `PluginSettingsPanel`（与其他路径项同处，便于接线/测试）：

```
本地安装包目录   [ JBTextField(24) ................ ] [浏览…] [检测本地更新] [打开目录]
                 状态行：已找到 3 个安装包，最新 2026.11.12（就绪）        ← 普通小字
```

- 标签文案键：`settings.update.localDir`（标题）/`.tip`（说明：把内网构建的 zip 放这里，插件自动识别最高版本）。
- **浏览**：照抄 `browseAutoSavePath`（`PluginSettingsPanel.kt:1655-1665`）——`FileChooserDescriptor(false, true, false, false, false, false)` 只管选目录，`chooseFile` 起始目录 = `user.home`。三平台都由平台对话框处理，不自绘。
- **打开目录**：可选便利项，复用 `captureDirectoryOpener` 那类 seam（`PluginSettingsPanel.kt:1057-1076`），目录不存在时给蓝字提示而不是创建空目录。
- **状态行**：`JBLabel`，`renderLocalDirStatus()` 单一渲染点。文案分级：
  - `Disabled`：显示「未设置（此项关闭）」；
  - `Ok` + `Available`：`已找到 N 个安装包，最新 X`；
  - `UpToDate`：`已找到 N 个安装包，最新 X（已是最新）`；
  - `Empty` / `NoValidPackage`：`目录可读，但没有可用的插件安装包（已跳过 M 个）`；
  - 其余 `DirCheck` 错误：逐条原因（非法字符/相对路径/不存在/无权限/不是目录/不可读/指向插件目录）。
- **校验时机**：`DocumentListener` → `javax.swing.Timer(400ms)` 单次防抖（EDT）→ 派 BGT → 回 EDT 渲染；另在浏览后、`reset()`、点「检测本地更新」时立即跑一次。定时器在 `disposeResources()` 里 `stop()`（面板销毁防回写）。
- **「检测本地更新」**：直接用输入框**当前文本**（不要求先点应用），走 `UpdateSection` 新增通道 `Channel.LOCAL`：

```
runCheck(Channel.LOCAL)
  ├─ localDirField.text → LocalUpdateDir.parse → Scanner.scan（BGT）
  └─ 结果映射到既有状态机：
       Available  → State.Available；notesArea 显示「本地安装包：<文件名>（<大小>）」
       UpToDate   → State.UpToDate
       DirInvalid / Empty / NoValidPackage / Failed → State.Failed（failureReason 用结构化原因）
```

- **安装按钮复用现有那个**（`UpdateSection.installButton`）：`runInstall()` 里按 `channel` 分流——`LOCAL` 走 `installFromLocal(pendingLocalPackage)`，`STABLE/PREVIEW` 原样走 `downloadAndInstall`。
- **通道后缀**：`renderState()` 末尾追加 `settings.update.channel.local`（「（本地目录）」），与现有 `PREVIEW` 后缀同构（`UpdateSection.kt:322-325`）。
- 变更日志区对本地通道没有 `changeNotes`：标题沿用 `settings.update.changelog` 不合适，本地态改为 `settings.update.localPackage` 一行（文件名 + 大小 + 修改时间）。

### 3.6 启动自动检测（插件主动触点）

扩 `StartupUpdateCheck`，与远端分支**并行独立**：

- 门禁：`localUpdateDir` 非空；`alreadyCheckedLocal` 会话级单次；`unitTestMode` 跳过（与现有纪律一致）。
- 链：BGT 扫描 → 仅 `Available` 且版本不在 `ignoredUpdateVersions` → EDT 弹一条同组通知。
- 通知内容：第一行 `版本 X（本地目录）`，第二行 `安装包：<文件名>`；**不放**「查看 Gitee 发布页」动作（与目录无关）。
- 动作：`立即安装`（BGT → `installFromLocal` → 三态收尾，复用 `showInstalledLive` / `showInstalled` / `showInstallFailed`）/ `忽略此版本`（写 `ignoredUpdateVersions`，与远端共用）/ `稍后`。
- 静默失败：目录不存在/无权限/列举异常一律不打扰（页面里能看到原因）。
- 弹窗红线：登记为第 3 处「插件主动」触点，准入五问自检见 §十一。

### 3.7 i18n、搜索索引、守卫

- 新增键中英双语（`BundleParityGuardTest` 会查齐）：
  - `settings.update.localDir` / `.tip` / `.browse` / `.check` / `.open`
  - `settings.update.channel.local`
  - `settings.update.local.package`（文件名/大小/时间行）
  - 状态文案：`settings.update.local.disabled` / `.ready` / `.upToDate` / `.empty` / `.noValid` / `.invalidPath` / `.relative` / `.notFound` / `.inaccessible` / `.notDirectory` / `.notReadable` / `.insidePluginsDir` / `.scanned`（含计数占位）
  - 通知：`update.notification.local.versionLine`（或复用 `update.notification.versionLine` + 本地行）
- 关于页搜索索引补 2 条（标题 + tip），否则 `SettingsPanelSearchableGuardTest` 断言一变红：`PluginSettingsFolderConfigurable.kt` 关于节。
- 不新增 Action / EP / 通知组（复用 `cn.song.mybatisplus.visualizer.updates`）。

---

## 四、改动面清单（文件级）

| 文件 | 改动 | 类型 |
|---|---|---|
| `config/PluginSettings.kt` | 新增 `localUpdateDir` 字段（约 `:261` 附近，紧跟更新相关字段） | 改 |
| `update/LocalUpdateDir.kt`（新） | 路径解析 + 合法性校验（纯函数） | 新增 |
| `update/LocalPackageScanner.kt`（新，或并入上者） | 目录扫描、包校验、选最新、会话缓存 | 新增 |
| `update/PluginPackageOwnership.kt`（新） | 归属 + 布局校验（G1 复核 + G2 顶层唯一目录），只读中央目录 | 新增 |
| `update/PluginUpdateRunner.kt` | 新增 `installFromLocal`（归属复核 → 临时副本 → zip 守卫 → 动态安装） | 改 |
| `update/StartupUpdateCheck.kt` | 本地分支、本地通知、`alreadyCheckedLocal` | 改 |
| `config/UpdateSection.kt` | `Channel.LOCAL`、`localCheckRunner` / `localInstallRunner` seam、本地结果渲染、`runInstall` 分流、文本刷新 | 改 |
| `config/PluginSettingsPanelSections.kt` | 关于组新增一行（`:715` 之后） | 改 |
| `config/PluginSettingsPanel.kt` | 字段 + 浏览/打开 + 防抖校验 + `isModified`/`apply`/`reset`/`disposeResources` 五处接线 | 改 |
| `config/PluginSettingsFolderConfigurable.kt` | 关于节搜索索引补登记 | 改 |
| `resources/messages/MpSqlVisualizerBundle.properties`（+ `_zh`） | 新增双语文案 | 改 |
| `docs/架构设计/02-核心流程详解.md` §8.2 | 更新来源三通道与本地通道判据 | 改 |
| `README.md`「内网、离线与数据边界 → 内网更新源」 | 补「设置页本地安装包目录」一节（截图/步骤/判据） | 改 |
| `docs/规范/设计规范-弹窗克制红线.md` | 触点清单登记 | 改 |
| `CHANGELOG.md` `[Unreleased]` | 一句一条，随提交回填 | 改 |
| `docs/索引/开发计划.md` | 本计划索引行 | 改 |
| `src/test/.../update/`、`config/` | 新增/扩展用例（§八） | 新增/改 |

---

## 五、决策点（2026-11-11 用户拍板：全部按推荐）

> 结论列已经是定案，实施阶段不再讨论。「理由」保留，用于日后被质疑时能直接回答为什么这么做。

| 编号 | 问题 | 选项 | 结论 | 理由 |
|---|---|---|---|---|
| D-1 | 检测到新版本后自动安装吗？ | A 自动装 / B 仅提示 + 一键装 | **B** | 自替换是高影响动作；用户可能正开着设置页或改到一半。远端通道也一直是「点才装」。V2 若确有诉求再加开关 |
| D-2 | 启动检测要不要独立开关？ | A 新增开关 / B 配置目录即开启 | **B** | 用户显式填目录＝强 opt-in；多一个默认开的开关只会增加「为什么没检测」的困惑。通知仍有「忽略此版本/稍后」 |
| D-3 | 是否递归扫描子目录？ | A 递归 / B 只扫一层 | **B** | 递归会把「归档旧版本」目录里的包也算进最新，判据变模糊；只扫一层＝「放进这个目录就是要发的版本」 |
| D-4 | 支持解包目录形态（`plugins/<name>/lib/*.jar`）吗？ | A 支持 / B 只认 zip | **B** | 内网分发天然是 `buildPlugin` 的 zip；解包形态会引入「目录里又有一套旧 jar」的判定歧义，文案里明说只认 zip |
| D-5 | 包内 `<idea-version since-build>` 高于当前 IDE 时？ | A 只警告（同远端）/ B 阻止安装 | **B** | 远端包的 sinceBuild 由发布方对同一发行线负责；共享目录里的包可能是同事给 2026.3 打的，装到 2024.3 上会直接把插件弄坏。阻止并给「与你的 IDE 不兼容，请让构建方提供 since-build ≤ … 的包」 |
| D-6 | 目录指向 IDE 插件目录（或其子目录）时？ | A 仅警告 / B 拒绝安装 | **B** | 自我覆盖 + 文件锁，失败形态不可诊断；状态行直接给错误原因 |
| D-7 | 本地通道是否共用 `ignoredUpdateVersions`？ | A 共用 / B 独立 | **A** | 用户「忽略 2026.11.12」的意图与来源无关；独立键会让同一版本在两条通道反复弹 |
| D-8 | 包内夹带第二个插件目录时？ | A 拒绝整个包 / B 只装本插件那部分 | **A** | 重启脚本路径是整包解压到插件根，B 做不到「只解一部分」而不改平台安装链；且夹带本身就是可疑信号，用户要的正是「别装成别的插件」 |

---

## 六、待验证前提（实施者先证伪再动手）

| 编号 | 前提 | 证伪方式 | 若不成立的替代 |
|---|---|---|---|
| P-1 | 243 上本地包同样回退「重启生效」（同走 `DynamicInstallSupport.install`） | 243 沙盒放一个真实包跑安装链，读 `idea.log` 的 `dynamic unload rejected`/`falling back to restart script` | 结论只需如实写进验收；无替代方案（平台判据） |
| P-2 | `FileChooser` 返回的 `VirtualFile.path` 在 Windows/macOS/Linux 都是可直接 `Paths.get` 的本地绝对路径（macOS 不是 `/Volumes` 别名怪形态） | 三平台沙盒各选一次目录并打印 `vf.path` → `Paths.get.path` | 回填前统一 `LocalUpdateDir.parse(vf.path)` 兜底归一（本就是设计） |
| P-3 | 死掉的 UNC/SMB 路径上 `Files.exists` 的错误形态是「exists=false && notExists=false」而不是抛 IOException | Windows 沙盒挂一个不存在的 `\\host\share\dir` 实测 | 捕获异常也归 `Inaccessible`，文案带异常类型 |
| P-4 | Windows 长路径（>260 字符）下 `ZipFile`/`Files.copy` 行为 | Windows 沙盒造长路径目录 | 失败时给明确文案（「路径过长，请改短」），不做 `\\?\` 自适配 |
| P-5 | 中文/空格路径端到端（`Files.copy` → `installAndLoadDynamicPlugin(Path)`）不被平台内部字符串处理破坏 | Windows 沙盒 `C:\Users\张三\Desktop\插件包 目录\` 实测 | 若平台链有坑，退化为「先复制到 `PathManager.getSystemPath()` 下临时目录再装」（复制步骤本来就能承接） |
| P-6 | 网络盘上 100 个 zip 全量扫描的耗时与可接受阈值 | 沙盒连测试服务器 SMB 共享打点 | 超阈值（初步定 >5s）改两阶段：文件名快筛 → 只校验 top-3 + 失败回退（见 §十） |
| P-7 | 设置 XML 持久化 Windows 反斜杠路径后读回逐字一致 | 单测 + 沙盒改设置重启 | 若被转义破坏，改存 `Path.toString()` 的 UTF-8 原样并补 `SettingsPersistence` 用例 |
| P-8 | `DynamicInstallSupport.validateArtifact` 对「IPGP buildPlugin 真实产物」与「测试假包」判定一致（本任务扫描全靠它） | 拿 `build/distributions` 真包跑一次 | 若不认，补 `lib/*.jar` 扫描分支（已有，但需真包回归） |
| P-9 | 真实 `buildPlugin` 产物的顶层条目形态（是否只有 `mybatis-plus-sql-visualizer/`；是否存在直属 `META-INF/` 或直属文件） | 对 `build/distributions` 真包 `unzip -l`（或 `ZipFile.entries` 打印顶层段） | G2 白名单按实测形态调整；若存在合法直属条目，加入允许集并补一条真包回归用例 |

---

## 七、实施拆解

| 里程碑 | 内容 | 完成判据 |
|---|---|---|
| **M1** 路径层 | `LocalUpdateDir.parse`：`~` 展开、非法字符、相对路径、存在性三态、目录/可读、插件目录守卫 | 单测覆盖 §八 T1 全部分支；纯函数零平台依赖 |
| **M2** 扫描层 | `LocalPackageScanner` + `PluginPackageOwnership`：非递归列举、上限、`validateArtifact` 复核、G2 布局校验、选最新、只升不降、缓存 | 单测 T2/T2b（多包/改名/损坏/冒名/夹带/id 不符/同版本 mtime 决胜/超 100 上限/大小写变体） |
| **M3** 安装层 | `PluginUpdateRunner.installFromLocal`：安装前归属复核（G1+G2，不用扫描缓存）→ 复制临时副本 → zip 守卫 → 动态安装；源包不动 | 单测 T3（成功/复制失败/坏 zip/守卫拒绝/冒名包/夹带包/三态透传/源包仍在） |
| **M4** 页面 | 关于页新行、状态渲染、防抖校验、`Channel.LOCAL`、`runInstall` 分流、五处接线（`isModified`/`apply`/`reset`/`disposeResources`/`updateTexts`） | 单测 T4 + `UpdateSectionTest` 扩展；面板在无目录时行为与今日一致（零回归） |
| **M5** 启动检测 | `StartupUpdateCheck` 本地分支 + 通知 + 忽略复用 | 单测 T5（门禁/忽略/静默失败/三动作）；通知内容快照断言 |
| **M6** 收尾 | i18n 双语、搜索索引、文档四处、`CHANGELOG`、弹窗触点登记 | 守卫全绿（`SettingsPanelSearchableGuardTest` / `BundleParityGuardTest` / `SettingsRealityGuardTest`） |

每个里程碑完成即提交（本仓并行开发纪律），提交信息中文、不带反引号管道符。

---

## 八、测试计划（定向）

新增/扩展用例（只跑这些类，不跑全量；全量由收口代理单独派）：

| 编号 | 类 | 覆盖 |
|---|---|---|
| T1 | `update/LocalUpdateDirTest` | 空/空白→Disabled；`~`、`~/x`、`~/`、`~` 后带反斜杠；相对路径；`InvalidPathException` 分支；临时目录存在/是文件/不存在；不可读（`Files.setPosixFilePermissions`，Windows 上跳过该例）；指向 `PathManager.getPluginsDir()` 子目录（构造 fake 根来测前缀比较）；中文与空格路径 |
| T2 | `update/LocalPackageScannerTest` | 用 `ZipOutputStream` 造**真实布局**假包（照抄 `DynamicInstallSupportTest.kt:34-40` 的 `lib/*.jar` 内嵌写法）；多版本选最大；`-beta.N` 与正式版同日（正式版胜出）；文件名与包内版本不一致（以包内为准）；非本插件 id 被跳过；坏 zip 被跳过；同版本取 mtime 晚者；空目录；只有旧版本→UpToDate；超上限截断计数 |
| T2b | `update/PluginPackageOwnershipTest` | G2 布局闸门独立夹具：①标准布局（`<目录名>/lib/x.jar`）→ `Ours`；②直属 `META-INF/plugin.xml` 形态 → `Ours`；③夹带 `other-plugin/lib/x.jar` → `Tainted`；④`id` 对但顶层目录名不是 `INSTALL_DIR_NAME` → `Tainted`；⑤`id` 不符 → `NotOurs`；⑥顶层目录名大小写变体 → `Tainted`（Windows 会覆盖）；⑦**一个包同时对 G1/G2 不合格时两者都能报出来**（防止两道闸门互相遮蔽，反证读数才有意义） |
| T3 | `update/PluginUpdateRunnerLocalTest` | `installFromLocal` 注入 fake `install` seam：三态透传；**冒名 id / 夹带目录 / since-build 过高 → Failed 且 install seam 零调用**（证明拦在安装之前）；`Files.copy` 失败（源文件在调用前被删）→ Failed；坏 zip → Failed；zip 守卫拒绝 → Failed；**断言源包仍在原处且内容未变**；临时副本被清理（枚举 temp 目录前后差集） |
| T4 | `config/UpdateSectionTest`（扩展） | `Channel.LOCAL` 渲染：Available 显示文件名行；UpToDate；DirInvalid/Empty/NoValid 四类失败文案；安装按钮分流调用 `localInstallRunner` 而非 `installRunner`；`updateTexts` 后本地状态不被重建抹掉 |
| T5 | `update/StartupUpdateCheckTest`（扩展） | 本地门禁（无目录/有目录）、已忽略版本静默、目录非法静默、Available 弹通知、`alreadyCheckedLocal` 单次语义（纯函数化 `shouldStartupCheckLocal`） |
| T6 | `config/PluginSettingsPanelBindingTest`（扩展） | 文本框↔state 的 `isModified`/`apply`/`reset` 三向一致；浏览按钮回填；防抖校验不写 state |

**反向验证（必做，成对留读数）**：

1. **G1**：摘掉 `installFromLocal` 里的 `id` 复核（扫描期过滤保留）→ T3「安装期拦冒名包」必红；只摘扫描期过滤 → T2「非本插件 id 被跳过」必红。两道各自单独验证，不合并成一次「反正都红」。
2. **G2**：摘掉 `verifyOwnership` 的顶层唯一性判断（其余闸门保留）→ T2b③/④/⑥ 必红，T2b①/② 仍绿；恢复后全绿。夹具必须是「`id` 正确 + 夹带目录」，否则 G1 会替 G2 拦下，读数假绿。
3. 摘掉「只升不降」判断（允许相等/更低版本进 Available）→ T2「只有旧版本→UpToDate」必红；恢复后绿。
4. 摘掉 `installFromLocal` 的临时副本（改成直接 `install(pkg.path)`）→ T3「源包仍在」不红，但**临时副本清理**用例会变成「删源包」形态——该用例要能区分两者（断言源包存在 + 临时目录无新增），否则等于空绿；若区分不了，改用「源包被改名/移动时安装仍成功」这条行为用例。

**not run（本任务定向范围外，须在报告里明写）**：真机动态热替换（需 243/253/262/263 四个沙盒）、macOS/Linux GUI 走查、网络盘真实环境。

---

## 九、验收（交给用户，GUI）

| 编号 | 步骤 | 预期 |
|---|---|---|
| A1 | 设置 → 关于 → 找到「本地安装包目录」行 | 位于版本信息行下方；未设置时状态行提示「未设置（此项关闭）」 |
| A2 | 填一个不存在的绝对路径（如 `C:\no\such\dir`）→ 停 0.4s | 状态行「目录不存在」；不弹窗、不抛异常 |
| A3 | 填一个文件路径（如 `C:\Windows\notepad.exe`） | 状态行「不是目录」 |
| A4 | 填相对路径 `plugins` | 状态行「请填绝对路径」 |
| A5 | 填含非法字符的路径（Windows：`C:\a:b\c`） | 状态行「路径不合法」+ 原因 |
| A6 | 浏览按钮 → 选一个含 3 个安装包的目录 | 回填路径 + 状态行「已找到 3 个安装包，最新 X」 |
| A7 | Windows 中文+空格目录：`C:\Users\<user>\Desktop\插件包 目录\`（放一个更高版本 zip） | 检测到「发现 X」；点安装 → 263/262 上提示「已安装并立即生效」，243 上提示「重启 IDE 生效」 |
| A8 | 目录里只放当前版本/更旧版本 | 状态行「已是最新」；无安装按钮 |
| A9 | 目录里混入 1 个非本插件 zip + 1 个截断坏包 | 状态行仍给正确最新版本；tooltip「已跳过 2 个」 |
| A10 | 应用后重启 IDE | 路径仍在；若目录里已有更新包，启动收到一条通知（仅一次）；点「忽略此版本」后同会话不再提示、重启后也不再提示该版本 |
| A11 | macOS/Linux 各选一次目录 | 与 A6/A7 一致 |
| A12 | 目录里**只**放另一个 JetBrains 插件包（例如从别处拿的任意插件 zip） | 状态行「没有可用的插件安装包（已跳过 1 个）」，**无任何安装入口**；点「检测本地更新」也只得到这个结论 |
| A13 | 放一个 `id` 是本插件但夹带第二个插件目录的包（可用 `zip` 手工拼一个） | 该包不参与「最新版本」判定（进跳过计数）；即便强行让它成为候选，点安装也报「安装包内含其他内容，已拒绝安装」，插件列表无变化 |
| A14 | 放一个 `since-build` 高于当前 IDE 的本插件包（例如给 2026.3 打的包放到 2024.3） | 检测阶段可见版本；点安装被拒绝，文案说明「该包要求 IDE 构建 ≥ X，当前为 Y」，插件不被替换 |

---

## 十、风险与边界

| 风险 | 处置 |
|---|---|
| 平台在 243 拒绝热卸载 → 用户觉得「说好的免重启」没实现 | 文案沿用既有两态（`installedLive` vs `installed`），验收 A7 明确分版本预期；README 写清平台分界 |
| 网络盘抖动/掉线让扫描或复制卡住 | 所有 FS 操作在 BGT；`installFromLocal` 失败即 `Failed`（不阻塞 UI）；P-6 若超阈值改「文件名快筛 + top-3 校验」 |
| 同事拿别人打的包（since-build 更高）装坏 IDE | D-5：读 `<idea-version since-build>` 高于当前 IDE → **阻止安装**并给可执行建议 |
| 目录里包太多（>100）或全是历史包 | 上限 + tooltip 写明未扫描数量；只升不降避免降级 |
| 用户把目录指向插件安装目录 → 自我覆盖 | D-6：`InsidePluginsDir` 直接拒绝安装 |
| 本地包来源不可信（共享盘被别人写） | 三道闸门：G1 `id` 必须等于本插件 id（扫描期 + 安装期各一次）、G2 顶层布局唯一目录（防夹带别的插件被一起装上）、G3 `UpdateZipGuard` 三重上限；不新增签名校验（发布包本身未签名，加了只会让内网自建包全面失效） |

**不做**：静默自动安装（D-1）、目录监听守护线程（`WatchService`/VFS 常驻监听）、多目录、差分包、递归扫描、解包目录形态、降级安装、HTTP 内网源改造、Marketplace 通道改动。

---

## 十一、弹窗红线准入五问（启动本地检测触点）

1. 用户是否刚做了对应动作？—— 用户**显式配置了目录**，并放入新包；检测只在发现更高版本时出声。
2. 是否打断当前操作？—— 非模态 balloon，可忽略、可稍后，不抢焦点。
3. 是否可永久关闭？—— 「忽略此版本」按版本抑制；清空目录设置即彻底关闭。
4. 是否会重复打扰？—— 每会话一次 + 版本级忽略 + 设置页可随时关。
5. 是否登记触点清单？—— 是，本任务同批登记为第 3 处「插件主动」触点。

---

## 十二、文档同步

| 文档 | 内容 |
|---|---|
| `docs/架构设计/02-核心流程详解.md` §8.2 | 更新来源三通道（正式/预览/本地目录）；本地通道的判定链（路径校验 → 扫描 → 版本比较 → 动态安装）与平台分界 |
| `README.md`「内网更新源」 | 新增「设置页 · 本地安装包目录」小节：适用场景（内网/共享盘）、操作步骤、目录约定（放 `buildPlugin` 产出的 zip）、平台差异（243 需重启） |
| `docs/规范/设计规范-弹窗克制红线.md` | 触点清单增一行（启动本地检测） |
| `CHANGELOG.md` `[Unreleased]` | Added：关于页本地安装包目录自动检测与免重启安装（提交哈希随提交回填） |
| `docs/索引/开发计划.md` | 本计划索引行 |

---

## 十三、实施记录

（实施后回写：提交哈希、定向读数、反向验证两次读数、not run 清单、真机验收结论。）
