# T-McpSettingsJump：MCP 工具页「点击前往」在 2026.3 被拦下——开发计划

- 立项：2026-11-11
- 状态：已实施（`3106e4c6`，分支 `t-mcp-settings-jump`；定向 27/0/0/0 含反向验证恰 1 红）；**待 2026.3 真机验收（A2/A3）**
- 触发：用户真机（IDEA 2026.3，`263.7307.200`）在 设置 → 工具 → SQL Visualizer → MCP 工具 页点
  「点击前往」，弹出 **「需要 IDEA 2025.2+ 并启用 MCP Server 插件，才能管理 SQL Visualizer 的 MCP 工具。」**
  用户原话是「点击前往被拦截了」——版本显然够，插件也内置在场。
- 影响面：**只有这一处跳转入口**。11 个 MCP 工具的注册与调用链走的是另一套 EP（取证 E4），未受影响。
- 范围（本次）：`config/McpConfigurable.kt`、`config/PluginSettingsPanel.kt`（历史副本）、新增
  `mcp/McpSettingsNavigation.kt`、bundle 双语一条文案、新增定向用例。**不动任何 toolset / 门控 / 工具实现**。

## 一、现象

同一帧界面上，横幅与按钮互相矛盾：

- 横幅（`settings.mcp.banner.platformPresent`）：「已检测到平台 MCP 工具集接口；实际可用性仍取决于注册结果…」
- 按钮点下去：警告框说平台版本不足 / 没装 MCP Server 插件。

两条结论出自同一个页面，判据却不是同一个：横幅探的是 `com.intellij.mcpserver.McpToolset`
（`findMcpToolsetClass()`），按钮探的是 `com.intellij.mcpserver.settings.McpServerSettingsConfigurable`
（`McpConfigurable.kt:228`）。前者在 2026.3 仍可加载，后者已经在 2026.3 搬走了（见二）。

## 二、取证

### E1　旧类名在 2026.3 全安装零命中

安装位置 `/c/Users/USER/AppData/Local/Programs/IntelliJ IDEA`，`product-info.json` 读数：
`"version": "2026.3"`、`"buildNumber": "263.7307.200"`。

```bash
# 整安装（lib + plugins 至三级目录）逐一扫 jar 条目
find lib plugins -maxdepth 3 -name "*.jar" -print0 \
  | xargs -0 -P 8 -n 1 sh -c 'unzip -l "$0" 2>/dev/null \
      | grep -q "mcpserver/settings/McpServerSettingsConfigurable.class" && echo "HIT $0"'
# → 零命中

# mcpserver 插件自身（含 lib/modules/ 下全部 jar，补上面 -maxdepth 3 的盲区）
cd plugins/mcpserver-plugin && find . -name "*.jar" -print0 \
  | while IFS= read -r -d '' j; do unzip -l "$j" | grep -qi "configurable"; done
# → 仅 intellij.mcpserver.jar 的 UserConfigurableMcpToolFilterProvider，
#   与 intellij.mcpserver.frontend.jar 的新包路径（见 E2）；旧 FQN 一处不剩
```

### E2　2026.3 的位置：新 content module + 新包名

`plugins/mcpserver-plugin/lib/modules/intellij.mcpserver.frontend.jar` 里：

```
com/intellij/mcpserver/frontend/settings/McpServerSettingsConfigurable*.class   （含 Compose 内部类）
com/intellij/mcpserver/frontend/settings/McpToolFilterConfigurable*.class
```

`plugins/mcpserver-plugin/lib/mcpserver-plugin.jar` 的 `META-INF/plugin.xml` 把它声明成平台 263 新的
content module 形态：

```xml
<module name="intellij.mcpserver.frontend" required-if-available="intellij.platform.frontend"><![CDATA[
<idea-plugin visibility="public">
  ...
  <applicationConfigurable instance="com.intellij.mcpserver.frontend.settings.McpServerSettingsConfigurable"
                           id="com.intellij.mcpserver.settings"
                           key="mcp.server.configurable.name" bundle="messages.McpServerBundle"
                           dynamic="true" parentId="ai" />
  <applicationConfigurable instance="com.intellij.mcpserver.frontend.settings.McpToolFilterConfigurable"
                           id="com.intellij.mcpserver.settings.filter" ... />
]]></module>
```

三个可直接利用的事实：**页面 id 是 `com.intellij.mcpserver.settings`**（263 插件描述实证；262 未取证，
见 V2）、页面名是 `MCP Server`（bundle `mcp.server.configurable.name=MCP Server`）、
父组是 `ai`。整个搬动只发生在「实现类放在哪个 module / 哪个包」这一层。

### E3　插件 id 与工具线 EP 未变

`mcpserver-plugin.jar` 的 `META-INF/plugin.xml`：

- `<id>com.intellij.mcpServer</id>`（与插件 `plugin.xml:118` 的 optional 依赖一字不差）；
- 第 71~80 行仍声明 `mcpServer.mcpToolsProvider` / `mcpToolFilterProvider` /
  `mcpToolset interface="com.intellij.mcpserver.McpToolset"` 等全部 EP；
- `lib/intellij.mcpserver.jar` 里 `com/intellij/mcpserver/McpToolset.class`、
  `McpToolFilter*.class`、`annotations/McpTool.class` 均在。

即：动态壳类注册（`McpToolsetDynamicRegistrar`，按 `com.intellij.mcpServer.mcpToolset` EP 名与
`McpToolset` 接口工作）与门控过滤（`mcpToolFilterProvider`）在 2026.3 契约未变——本次故障不涉及工具线。

### E4　「按设置页 id 打开」在本仓已有落地先例

`ui/SqlPreviewPanelExecution.kt:1066-1091`（T-ExecPsiGate 13.10）已经用同一手法解决过同类问题
（2026.3 文件类型页）：

```kotlin
val opened = runCatching {
    com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(proj, FILE_TYPES_SETTING_ID, null)
}.isSuccess
if (!opened) {
    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
        .showSettingsDialog(proj, FILE_TYPES_SETTING_ID)   // 公开 String 重载，编译基线 243 即有
}
```

注释里还记了一条关键实证：按 id 打开必须在**同一个** `ConfigurableGroup` 实例里查找才选得中，
先自己 `findById` 再把裸 `Configurable` 交出去会落到根页——所以这条路必须整条交给平台。

### E5　仓内是两份副本，其中一份已无调用方

| 位置 | 现状 |
| --- | --- |
| `config/McpConfigurable.kt:225-243` | 用户点的就是这份（子设置页按钮） |
| `config/PluginSettingsPanel.kt:920-938` | T-ShortcutSettings 前的「关于」节入口，升独立子页后 `grep -n openMcpServerSettings` 只剩定义、**零调用方** |

两处都把 `Class.forName("com.intellij.mcpserver.settings.McpServerSettingsConfigurable")` 当能力判据，
KDoc 里写着「262 mcpserver.jar 常量池实证」——那条实证当时是对的，问题在于它把**实现细节**当成了
长期契约。

## 三、根因

**R1　平台把设置页搬进了新的 content module（直接原因）。** 263 起 mcpserver 插件改用
`<content namespace="jetbrains"><module …>` 形态重组，设置页随 UI 前端代码一起进了
`intellij.mcpserver.frontend` 模块（`required-if-available="intellij.platform.frontend"`），包名从
`com.intellij.mcpserver.settings` 改为 `com.intellij.mcpserver.frontend.settings`。旧 FQN 在
263.7307.200 已不存在（E1），`Class.forName` 返回 null，代码走进「不可用」分支。

**R2　判据选错层次：用实现类名当能力探针。** 平台对外的稳定标识是**设置页 id**
（`com.intellij.mcpserver.settings`，262/263 同名），实现类名、所在 module、包名都是可重构的内部结构。
本仓另两处跳转（SQL 方言、文件类型）早已按 id 走（E4），只有 MCP 这一处还按类名探。

**R3　失败文案把「定位不到」说成「版本不够」。** `settings.mcp.unavailable` 的文案是
「需要 IDEA 2025.2+ 并启用 MCP Server 插件」——在 2026.3 上两句都不成立，用户看到的就是一句
没头没尾的「拦截」。文案把「平台达标但页面搬走了」这类情况也归进了版本/安装问题。

**R4　历史副本放大了排查成本。** E5 的第二份已经零调用方，却仍带着同样的旧类名与「262 实证」注释，
下一次排障容易在死代码里找线索，或者只修一处留下另一处。

## 四、方案

### M1　主修：一次探测、两级回退、一个入口

**T1　新增 `mcp/McpSettingsNavigation.kt`（单一真相源）**

```kotlin
package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/** 平台 MCP Server 设置页 id（263 声明实证 id="com.intellij.mcpserver.settings"；262 未取证，见 V2）。 */
internal const val MCP_SETTINGS_PAGE_ID = "com.intellij.mcpserver.settings"

/** 实现类候选链，新形态在前。类名属于平台内部结构，会随版本搬动：
 *  263 把设置页搬进 content module intellij.mcpserver.frontend 并改了包名（旧 FQN 全安装零命中）。
 *  禁止再收窄成单一 FQN——那是 2026.3 本次故障的形态。 */
private val MCP_SETTINGS_CONFIGURABLE_CLASSES = listOf(
    "com.intellij.mcpserver.frontend.settings.McpServerSettingsConfigurable",
    "com.intellij.mcpserver.settings.McpServerSettingsConfigurable",
)

internal enum class McpSettingsOpenResult { OPENED, OPENED_BY_ID, UNAVAILABLE }

internal fun openMcpServerSettingsPage(project: Project): McpSettingsOpenResult {
    val configurable = MCP_SETTINGS_CONFIGURABLE_CLASSES.firstNotNullOfOrNull { fqn ->
        runCatching { Class.forName(fqn) as? Class<out Configurable> }.getOrNull()
    }
    if (configurable != null) {
        val ok = runCatching { ShowSettingsUtil.getInstance().showSettingsDialog(project, configurable) }
        if (ok.isSuccess) return McpSettingsOpenResult.OPENED
    }
    // 平台自带「按 id 打开并选中」入口（与 SQL 方言 / 文件类型同一手法，见 SqlPreviewPanelExecution.kt）
    val byId = runCatching {
        com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(project, MCP_SETTINGS_PAGE_ID, null)
    }.isSuccess
    if (byId) return McpSettingsOpenResult.OPENED_BY_ID
    val publicById = runCatching {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MCP_SETTINGS_PAGE_ID)
    }.isSuccess
    return if (publicById) McpSettingsOpenResult.OPENED_BY_ID else McpSettingsOpenResult.UNAVAILABLE
}
```

为什么要「类名先、id 后」而不是反过来：类名路是**今天在 262/263 都能走通的**既有路径（263 只是换了类名），
不依赖 id 在旧版本的可用性（V2 未取证）也不依赖平台在「id 不存在」时的行为（V3 未知）；
id 路作为下一轮平台重构的兜底，两者都在才叫「不押单点」。

**T2　`McpConfigurable.openMcpServerSettings()` 改为调该函数**

- `OPENED` / `OPENED_BY_ID`：静默成功；
- `UNAVAILABLE`：弹现有那条 warning，但**文案改写**（不新增键，避免动幽灵键白名单）：

  中文：「未找到 MCP Server 设置页：请确认内置 MCP Server 插件已启用，或在设置中搜索「MCP Server」。」
  英文：「MCP Server settings page not found. Make sure the bundled MCP Server plugin is enabled, or search "MCP Server" in Settings.」

  这句同时覆盖「插件没装/被禁」与「平台又搬了地方」两种成因，并给出一个不依赖版本号的动作。
- KDoc 里「262 mcpserver.jar 常量池实证」的表述改成「类名会随平台重构搬家，判据见 McpSettingsNavigation」。

**T3　`PluginSettingsPanel.kt:920-938` 收回成一行委托**（保留方法，不删文件——它所在的
「关于」节走查还会被当作历史入口搜索）：

```kotlin
private fun openMcpServerSettings() {
    val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
    if (openMcpServerSettingsPage(project) == McpSettingsOpenResult.UNAVAILABLE) {
        Messages.showWarningDialog(project, MpSqlVisualizerBundle.message("settings.mcp.unavailable"),
            MpSqlVisualizerBundle.message("settings.mcp.title"))
    }
}
```

两处都留在各自的白名单里（`BannedApiSourceScanTest` 已对这两个文件加白，无需改白名单）。

### M2　防线：把「又一枚写死的类名」变成可测

**T4　定向用例 `McpSettingsNavigationTest`**（纯逻辑，进 testFast 桶）：

1. 候选链首项是新 FQN、次项是旧 FQN，且不含第三项（防又被随手改成单值）；
2. `MCP_SETTINGS_PAGE_ID == "com.intellij.mcpserver.settings"`（页面 id 与平台声明同源，
   改它必须先改 263 插件描述的证据注释）；
3. 用假 Project（测试环境无 platform 服务）调 `openMcpServerSettingsPage` 不抛异常，
   返回 `UNAVAILABLE`——保证降级路径本身不会被异常吞掉（这条是「测试环境不崩」而不是「真机能跳」，
   真机判据见验收 A2）。

**T5　反向验证**：临时把候选链改回单条旧 FQN 跑 T4 第 1 条 → 必须变红；改回后全绿。

## 五、待验证前提（实施前先证伪）

- **V1**　新 FQN `com.intellij.mcpserver.frontend.settings.McpServerSettingsConfigurable` 能否从
  **本插件 classloader** 加载：263 的 content module 虽标 `visibility="public"`，但「插件依赖可见到
  对方 public 子模块的类」这一点本仓只在 `intellij.mcpserver` 上验过（横幅探针在用户机器上为真）。
  若 2026.3 上加载失败，方案自动落到 id 路——**T1 的回退顺序保证这条不成立也不影响可用性**。
- **V2**　262 的页面 id 是否同为 `com.intellij.mcpserver.settings`：本机无 262 安装、IPGP 未缓存其
  mcpserver 插件，未取证。因类名路排在前面，262 不依赖此前提。
- **V3**　`ShowSettingsUtilImpl.showSettingsDialog(project, id, null)` 在 **id 不存在**时的行为
  （抛异常 / 打开根页并不选中）。若为后者，`OPENED_BY_ID` 会变成假绿——需要一次沙盒实验确认，
  再决定是否为 id 路加「打开后核对页面」的校验。
- **V4**　remote-dev / 分离前端形态（`required-if-available="intellij.platform.frontend"`）下
  frontend 模块缺席：此时类名与页面都不在，落 `UNAVAILABLE`。属可接受降级，但要确认提示文案在
  远程后端里不误导（文案已不承诺版本，见 T2）。
- **V5**　2026.3 工具线回归（与本次修复独立，仅同批验收）：11 个 toolset 是否仍出现在平台
  「暴露工具」页、MCP 客户端 `list tools` 是否返回 11 条。EP 名与接口未变（E3），但动态壳类依赖
  `McpToolset` 的默认实现面，263 若改过接口默认方法会以 `AbstractMethodError` 形式暴露。

## 六、实施步骤（写集）

1. 新增 `src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSettingsNavigation.kt`（T1）。
2. 改 `config/McpConfigurable.kt`：`openMcpServerSettings()` 调 T1；KDoc 订正；import 表清理。
3. 改 `config/PluginSettingsPanel.kt`：旧副本改一行委托（T3）。
4. 改 `src/main/resources/messages/MpSqlVisualizerBundle.properties:1106` 与
   `MpSqlVisualizerBundle_zh.properties:1082`：`settings.mcp.unavailable` 文案改写（键名不动，
   幽灵键守卫与既有白名单零改动）。
5. 新增 `src/test/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSettingsNavigationTest.kt`（T4）。
6. 定向测试：`./gradlew test --tests "cn.song.mybatisplus.visualizer.mcp.McpSettingsNavigationTest"`
   （跑前过并行登记表；不跑全量）。
7. 真机验收（用户执行，A1~A3 见下）。
8. 收口：`CHANGELOG.md` 的 `[Unreleased]` 记一条（用户可感修复，带提交哈希），
   `docs/索引/开发计划.md` 本行状态改「已实施」。

## 七、验收

**A1　单测**：`McpSettingsNavigationTest` 3 例全绿；反向验证（T5）恰 1 红。

**A2　真机（用户，2026.3）**：设置 → 工具 → SQL Visualizer → MCP 工具 → 「点击前往」
→ **直接落到平台「MCP Server」设置页**并选中该页（不是设置根页、不再弹 warning）。
三档判读：落在根页 = V3 的假绿形态；仍弹 warning = 两级回退都失败，需取 `idea.log` 里
`Class.forName` / `showSettingsDialog` 相关异常行。

**A3　真机（用户，旧版本回归面）**：手上若有 2026.2 / 2025.x，同一按钮应仍能打开（类名路命中）。
本机无 262 安装，此项只登记不强求。

**A4　工具线**：平台「暴露工具」页仍见 11 组 `SqlVisualizer*`（V5 的观察项，与本修复解耦）。

## 八、不做什么

- 不改工具注册、门控、脱敏、审计任何一行（E3 已证契约未变）。
- 不做「按 id 反查 Configurable 实例再手工拼对话框」——E4 注释已实证那会落到根页。
- 不把 MCP 页从本插件设置树里摘掉（横幅与门控仍是本插件自有的单一真相源，与平台页互补）。
- 不为这条跳转新增 bundle 键或改弹窗白名单（两种成因合并成一条文案足够，且少一处守卫改动）。
- 不承诺「未来平台再搬家也不会坏」：本次只保证**判据不再是单点**（类名候选 + 页面 id 双路）。

## 九、实施记录（2026-11-11，分支 `t-mcp-settings-jump`）

提交 `3106e4c6`（6 文件，+197/−41）。落地情况与计划一致：

| 计划项 | 落地 |
| --- | --- |
| T1 单一入口 | 新增 `mcp/McpSettingsNavigation.kt`：`MCP_SETTINGS_PAGE_ID` + `MCP_SETTINGS_CONFIGURABLE_CLASSES`（新形态在前）+ `McpSettingsOpenResult`；类名路 → `ShowSettingsUtilImpl` 按 id → 公开 `ShowSettingsUtil` 按 id → `UNAVAILABLE`，末档打一行 warn（候选链与 id 全量入日志，供真机排障） |
| T2 调用点 | `McpConfigurable.openMcpServerSettings()` 改调入口，只把 `UNAVAILABLE` 翻译成一次 warning；KDoc 写明「文案不再断言版本」的依据 |
| T3 旧副本 | `PluginSettingsPanel.openMcpServerSettings()` 收成一行委托，KDoc 注明「当前无调用方，保留为历史入口的等价实现」 |
| T2 文案 | `settings.mcp.unavailable` 双语改写为「未找到 MCP Server 设置页：请确认内置 MCP Server 插件已启用，或在设置中搜索『MCP Server』」（键名不动，幽灵键守卫零改动） |
| T4 用例 | `McpSettingsNavigationTest` 3 例：候选链覆盖+顺序、页面 id 常量、设置页类名只允许出现在候选链文件 |
| T5 反向验证 | 候选链临时改回单条旧 FQN → 恰 1 红（`候选链覆盖新旧两形态且新形态在前`），恢复后复绿 |

测试读数（工作树，`-Pkotlin.compiler.execution.strategy=in-process`）：

```
McpSettingsNavigationTest   3 tests, 0 failures, 0 errors
BannedApiSourceScanTest    15 tests, 0 failures, 0 errors   （弹窗白名单未破坏）
McpScopeSourceScanTest      5 tests, 0 failures, 0 errors   （mcpserver 引用仍限 mcp 包）
SettingsRealityGuardTest    4 tests, 0 failures, 0 errors   （bundle 幽灵键未破坏）
合计 27/0/0/0
```

未做（留给验收/后续）：

- **A2 真机未跑**：能不能落到目标页并选中只有 2026.3 实机能判；本次只证明「候选链 + 回退顺序」在逻辑上成立、降级路径不抛异常。
- **V1 未证**：新 FQN 能否从本插件 classloader 加载未在真机确认（横幅探针已证同一插件里 `McpToolset` 可加载，但那是另一个 content module）；若加载不到，自动走 id 路，功能不受影响。
- **V3 未证**：平台在页面 id 不存在时的行为仍未实验（见 A2 的三档判读）。
- **A3 未跑**：本机无 2026.2 安装，旧版本回归面只靠「候选链保留老 FQN」这条静态保证。
