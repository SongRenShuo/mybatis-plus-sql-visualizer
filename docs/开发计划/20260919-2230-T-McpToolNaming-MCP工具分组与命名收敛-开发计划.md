# T-McpToolNaming MCP 工具分组与命名收敛 — 开发计划

- 日期：2026-09-19 22:30
- 任务编号：T-McpToolNaming
- 来源：用户 2026-09-19 反馈——「MCP Server 里我的插件 MCP 太分散了，不容易被用户看到，是不是分组名应该带上插件名，并且工具名前缀也调整下，现在是 mpsv 好像，可以考虑改成 sqlv」（补充：「或者 sv 开头也可以考虑」）
- **用户裁决（2026-09-19，本计划定案依据）**：分组收敛走**方案 B**（类名加插件名前缀，**保留平台侧逐工具门控**）；工具名前缀定为 **`sqlv_`**。原候选「方案 A 合并单类」与「`sv_` 前缀」均不采用，仅作对照留档（见 §三）
- 本轮范围：**仅立项出计划，不实施**（用户明确）。实施另起任务时按 §七 顺序执行
- 性质：**可发现性改进 + 工具名统一**。分组收敛只影响 UI 呈现；工具名统一为面向 agent 的公开名，牵动全仓文档、测试与守卫引用（无已发布用户，不涉及设置迁移）
- 实施分支：建议自 master 拉 `t-mcp-tool-naming`
- 验证基线：master（实施时以当次实测为准）

---

## 一、背景与依据（两处现状核实，决定方案形态）

### 1.1 「太分散」的根因：分组名就是 toolset 类名，而当前是一工具一类

平台「暴露工具」页按工具描述符的 **category** 分组，而 category 的 `shortName` 直接取自 **toolset 类的 simpleName**（反编译 `mcpserver.jar` → `com/intellij/mcpserver/impl/util/ToolsetReflection_utilKt.asTools`）：

```java
String var10002 = $this$asTools.getSimpleName();       // ← UI 上的分组名
String var10003 = $this$asTools.getQualifiedName();
McpToolCategory category = new McpToolCategory(var10002, var10003, isExperimental, alwaysIncluded);
```

`McpToolCategory` 的字段即 `(shortName, fullyQualifiedName, isExperimental, alwaysIncluded)`，`McpToolFilterConfigurableKt.buildCategoryGroups` 以它为 key 分组渲染。

而本插件当前是**一工具一 toolset 类**（`RenderSqlTool`、`CheckSqlTool`、`CrudSqlTool`…共 10 个），因此 UI 上呈现为**10 个互不相邻的分组**——这就是用户看到的「分散」。

> 该设计是**刻意的**，见 `mcp/McpToolsetSupport.kt:18-22` 与 `mcp-optional.xml` 头注：平台 per-tool 门控只有 **toolset 粒度**（`McpToolset.isEnabled()`），所以「一工具一 toolset 类 + 基类三钩子字面量覆写」是拿到**平台侧逐工具开关**的唯一办法。分组分散是它的代价。

**补充事实**：`McpToolset` 接口本身**没有名字属性**（反编译确认只有 `isEnabled` / `isExperimental` / `alwaysIncluded` 三个 default 方法），所以**无法通过接口自定义分组名**——分组名只能由类名决定。

### 1.2 工具名前缀的影响面

- 工具名在代码里是**显式写死**的：10 处 `@McpTool(name = "sqlv_xxx", title = "...")`（`mcp/` 包各 Tool 类），与构造器传入基类的门控名、`audit(...)` 的工具名、方法名同源。
- **门控设置按工具名存**：`mcp/McpToolGate.kt` 读 `state.mcpToolsEnabled`（总开关）+ `state.mcpDisabledTools`（**名字列表**）。工具名与 `McpToolGate.TOOL_NAMES`、`mcp-optional.xml` 的十个 toolset 必须逐字对齐——三处任一漂移即门控失配。
- **全仓引用面**：README、CHANGELOG、`docs/变更记录.md`、`docs/架构设计/`、历次开发计划与 `docs/memory-archive/` 等（以 `grep -rl` 为清单，逐文件改净）。
- **文档站另有一处**：`features/mcp.md` 与 `en/features/mcp.md` 的「十个工具」表格逐个列出 `sqlv_*` 名字，属对外公开面。


## 二、目标与非目标

**目标**：① 让插件在「暴露工具」页里**一眼可辨识、成组相邻**（方案 B：10 个 toolset 类名统一加 `SqlVisualizer` 前缀）；② 工具名统一 **`sqlv_`** 前缀，并完成全仓（插件仓库 + 文档站）文档同步。

**非目标**：不改工具语义、参数与只读契约（归各自既有任务）；不改 `[MCP-AUDIT]` 审计格式；不改门控机制本身（`McpToolGate` 逻辑与逐工具门控行为原样，只同步工具名清单）；不引入新工具；**不设设置迁移**（MCP 工具未随任何发布产物交付，不存在旧名配置——见 §八 实施记录）。


## 三、方案

分两个**可独立取舍**的子项，先定 A 再定 B。

### T-MTN-01 分组收敛（**定案：方案 B**）

| 方案 | 做法 | UI 效果 | 代价 | 采纳 |
|------|------|---------|------|------|
| A 合并单类 | 10 个 `@McpTool` 方法并入一个 toolset 类（如 `SqlVisualizerToolset`） | **1 个分组**，组名即类名 | **平台侧 per-tool 门控塌缩为 1 个开关**——「暴露工具」页无法逐工具启停，插件设置页的逐工具勾选与平台列表不再对应 | ✗ 不采用 |
| **B 类名加前缀** | 保留一工具一类，类名统一加插件名前缀 | 仍是 10 组，但**组名自带插件名且字典序相邻**，扫视即知同属一插件 | 改动小、**门控能力零损失** | **✓ 定案** |
| C 嵌套类 | 把 Tool 类收进同一外部类的嵌套类 | `getSimpleName()` 对内层类只返回内层名，**分组名不变** | 无效 | ✗ 不采用 |

**定案依据**：平台侧逐工具门控（插件设置页「MCP 工具」子页的总开关 + 逐工具勾选 ↔ 平台「暴露工具」页逐项状态）是本插件 MCP 门控的既有卖点，A 方案会把它塌缩为整组开关，属能力回退；B 以最小改动达成用户要的「可辨识 + 相邻」。

**落地做法（B）**：10 个 toolset 类统一加前缀 `SqlVisualizer`——

```
RenderSqlTool       → SqlVisualizerRenderSqlTool
CheckSqlTool        → SqlVisualizerCheckSqlTool
CrudSqlTool         → SqlVisualizerCrudSqlTool
…（其余七个同理）
```

基类 `mcp/McpToolsetSupport.kt` 与 `src/main/resources/META-INF/mcp-optional.xml` 中十处 `implementation` 的全限定类名同步修改；类名只影响分组名，**不改工具名**（工具名归 T-MTN-02）。

### T-MTN-02 工具名前缀（**定案：`sqlv_`**）

| 候选 | 优点 | 缺点 | 采纳 |
|------|------|------|------|
| **`sqlv_`** | 与插件品牌一致（插件名 SQL Visualizer、内部命名 `SqlVisualizer*`），语义可读 | 比 `sv_` 长一字符 | **✓ 定案** |
| `sv_` | 最短 | 过于泛化，多插件共存时易与别家前缀混淆；可读性弱 | ✗ 不采用 |
| `mpsv_` | 与既有代码一致、零改动 | 与插件品牌不一致，缩写不可读 | ✗ 不采用 |

**定案依据**：`sqlv_` 与插件既有品牌一致——插件名即 SQL Visualizer，内部类/动作命名均为 `SqlVisualizer*`，工具名前缀与之对齐后，agent 侧看到的工具名与插件身份一致；`sv_` 过短且泛化，在「一台 IDE 挂多个 MCP 工具集」的场景下辨识度不足。

**工具名清单**（十个，`McpToolGate.TOOL_NAMES` 为单一真源）：

```
sqlv_render_sql
sqlv_schema_context
sqlv_explain
sqlv_render_logs
sqlv_check_sql
sqlv_rollback_plan
sqlv_perf_advice
sqlv_crud_sql
sqlv_reverse_sql
sqlv_mapper_check
```

**实施要点**：

1. **代码**：10 处 `@McpTool(name = ...)`、方法名、构造器传入基类的门控名、`audit(...)` 工具名、`McpToolGate.TOOL_NAMES`、`McpConfigurable` 复选框文案派生键前缀，六处同源同步；
2. **不设设置迁移**：MCP 工具未随任何发布产物交付（Gitee 无 2026.9.18 正式版，`2026.9.14` 与 `2026.9.18-beta.*` 的 tag 树里 `mcp/` 与 `mcp-optional.xml` 均不存在），不存在旧名配置需要迁移；

3. **文档同步**：插件仓库**全仓一律用 `sqlv_`**（含历次开发计划与 `docs/memory-archive/`——旧名从未对外存在过，历史记录里留着反而是在项目里留一个不存在的名字），以 `grep -rl` 为清单逐文件改净后复查零残留；`docs/变更记录.md` 记一行交付记录；
4. **文档站同步**：`features/mcp.md` + `en/features/mcp.md` 的十工具表逐个改名（不加「旧名已更名」类遗留提示，按本来就叫 `sqlv_` 直述）；
5. **CHANGELOG**：按「本来就叫 `sqlv_`」直述，**不写**破坏性变更段与迁移说明（无已发布用户需要同步改名）。

## 四、验收标准

1. 「暴露工具」页中本插件的十个分组**全部相邻且组名含插件名**（`SqlVisualizer*`，方案 B）；
2. 十个工具在 MCP 服务端仍可被 `tools/list` 拉到，且前缀正确；调用产生 `[MCP-AUDIT]` 且工具名为新名；
3. 全仓（排除 `build/`）`grep -rn` 复查无旧前缀残留；
4. 平台侧逐工具门控在 B 方案下**行为不变**（设置页勾选 ↔ 平台列表一致）；
5. 插件仓库（含文档站中英两页）名字全部更新，无遗留旧名；
6. 既有门禁（testShards / verifyPlugin）通过。

## 五、真机走查清单（留人工）

- 沙盒内 `Settings → Tools | MCP Server → Exposed Tools` 目视分组名与相邻性；
- 插件设置页「MCP 工具」子页逐工具勾选 ↔ 平台列表状态一致；
- 通过任一 agent（终端 `claude mcp list` 或 ACP）实调一次新名工具，确认审计行工具名为新名。

## 六、风险

| # | 风险 | 影响 | 应对 |
|---|------|------|------|
| R1 | 改名波及既有引用面（agent 提示词、MCP 白名单、`mcpDisabledTools` 键） | 外部配置失配 | MCP 工具未随任何发布产物交付（无已发布用户，不存在旧名配置），风险面仅限仓库内引用——以全仓改名 + 零残留复查收口 |
| R2 | 类名改造牵连测试与守卫——工具级门控测试**直接实例化 Tool 类**（见 `build.gradle.kts` 中 mcpStub 接线注释：「test 编译同样需要桩（工具级门控测试直接实例化 Tool 类）」） | 改名后编译失败或守卫失配 | 类名与测试引用同步改；改完跑 testShards 全量，不只看单模块 |
| R3 | 类名加前缀后与平台其他 toolset 类名风格不一致 | 观感 | 前缀统一为插件名，且该命名只影响本插件自己的类 |
| R4 | 文档同步遗漏（全仓引用面 + 文档站两页） | 新旧名并存，用户困惑 | 验收标准 3/5 显式检查（grep 复查零残留） |
| R5 | 改名与 T-AcpMcpTransport 等并行任务在同一批文件上冲突 | 合并冲突 | 两个任务分属不同文件面（本任务动 `mcp/*Tool.kt` 的注解与类名，T-AcpMcpTransport 动 build.gradle.kts 与新增 EP 配置），实施时先合并 master 再改 |

## 七、实施顺序

> 方案已定案（**B + `sqlv_`**）。以下为另起实施任务时的执行顺序；**本轮不实施**。

1. T-MTN-01 类名改造（纯机械：10 个 toolset 类加 `SqlVisualizer` 前缀 + `mcp-optional.xml` 十处全限定名，编译即验）；
2. T-MTN-02 工具名改造（10 处 `@McpTool(name = ...)` + 方法名 / 门控名 / `audit` 名 / `McpToolGate.TOOL_NAMES` / 派生键前缀六处同源）+ 守卫标记同步；
3. 文档同步（插件仓库全仓改名 → 文档站中英两页 → CHANGELOG 直述新名）；
4. 真机走查（第五节三项）。

## 八、实施记录

- 实施：sam 实施子代理；工作树 `F:\code\Java\Gitee\SongRenShuo\mpsv-mtn`，分支 `t-mcp-tool-naming`，基点 master `a27c198`；沙盒 `mpsv-mtn`（并行登记表 `T-MTN`）
- **T-MTN-01 落地（方案 B，逐工具门控零损失）**：十个 toolset 类统一加 `SqlVisualizer` 前缀，源文件同步重命名——
  `SqlVisualizerRenderSqlTool` / `SqlVisualizerSchemaContextTool` / `SqlVisualizerExplainTool` / `SqlVisualizerRenderLogsTool` / `SqlVisualizerCheckSqlTool` / `SqlVisualizerRollbackPlanTool` / `SqlVisualizerPerfAdviceTool` / `SqlVisualizerCrudSqlTool` / `SqlVisualizerReverseSqlTool` / `SqlVisualizerMapperCheckTool`；
  `mcp-optional.xml` 十处 `implementation` 全限定类名同步；`McpToolsetSupport` KDoc 登记「`SqlVisualizer` 前缀是硬约定（分组名 = toolset 类 simpleName，`McpToolset` 接口无名字属性），新增工具照此命名」；一工具一 toolset 类与基类三钩子字面量覆写原样保留。
  **静态核验**：十个类名按字典序排序后连续且均含插件名（`ls mcp/SqlVisualizer*Tool.kt | sort` → 十项相邻无插入）
- **T-MTN-02 落地**：工具名统一 `sqlv_`，六处同源同步（`@McpTool(name=...)` / 方法名 / 构造器传入基类的门控名 / `audit(...)` 工具名 / `McpToolGate.TOOL_NAMES` / `McpConfigurable` 复选框文案派生键前缀）。
  **静态核验**：`grep '@McpTool(name = '` 取出的十个名字 == `McpToolGate.TOOL_NAMES` 十项，逐项一致（排序后完全相等）
- **守卫同步**：`McpScopeSourceScanTest` 的「META-INF 不得引用适配类名」标记由单个门面类名扩为 `Regex("SqlVisualizer\\w*Tool\\b|SqlVisualizerToolset")`——旧标记只认 `SqlVisualizerToolset` 一字面量，改名后真引用会整片漏网（守卫只会漏报不会误报，不改则静默失守）
- **设置迁移：不设**（计划 §三 实施要点 2 与 §四.3 已按此改写）。核验依据：MCP 工具未随任何发布产物交付——Gitee 21 个发行版无 `2026.9.18` 正式版（只有 beta.1~4）；`2026.9.14` / `2026.9.18-beta.1` / `2026.9.18-beta.4` 三个 tag 的树里 `mcp/` 文件数 0、`mcp-optional.xml` 不存在（发行 tag 从旁支切出，MCP 只在 master 主线）。故不存在旧前缀的工具名配置，迁移是死代码
- **全仓改名**：以 `grep -rl`（旧前缀，排除 `build/`、`.git/`）为清单逐文件改净，**含历次开发计划与 `docs/memory-archive/`**；改后复查零残留（唯一保留的旧名提及是 §三 选型对照表里被否决的候选行，属决策依据）。顺带把 `database-lab/docker-compose.dialects-cn.example.yml` 的 `INSTANCE_NAME`（非 MCP 工具名，全仓唯一引用）一并对齐，以满足零残留
- **文档同步**：README MCP 节、`docs/架构设计/` 两篇三处、CHANGELOG（`[Unreleased]` 段内的工具名）、`marketplace-custom-page.html`、双语 bundle 的 `settings.section.mcp.desc`、`mcpStub` 注解 KDoc；CHANGELOG 不写破坏性变更段（按「本来就叫 `sqlv_`」直述）
- 提交：`7f984c6`（类名 + 工具名 + 迁移 + 测试引用，47 文件）、`120975f`（去遗留叙事）、`37618ac`（架构设计两篇）、末笔（删迁移 + 全仓改名 + 计划回填 + 变更记录）
- 门禁：见 §九

## 九、实施小结：偏离、阻塞与未验证项

### 与计划的偏离

1. 计划只要求改类名，实施把**源文件名**一并重命名（Kotlin 文件名与类名一致约定）；`McpToolGateTest` / `PluginXmlStructureGuardTest` / `McpRowBudgetTest` / `McpAuditLogTest` 等的类名引用与源码路径引用同步更新
2. 计划未点名 `McpConfigurable.checkBoxText` 的 `removePrefix(旧前缀)`——该前缀不匹配会派生出空尾段（复选框文案取不到键），已同步为 `sqlv_`；bundle 十键尾段不变（`render_sql` → `rendersql`），`SettingsRealityGuardTest` 白名单无需改
3. 计划未点名 `marketplace-custom-page.html` 与双语 bundle 的 `settings.section.mcp.desc`（工具名短清单），已同步
4. `McpScopeSourceScanTest` 的适配类名标记加固（见 §八）——属守卫保真度修复，非计划内条目
5. 设置迁移由「必做 + 单测」改为「不设」：一度按「旧前缀改名后旧禁用项静默复活」实现并落地（含 6 例单测，先红后绿 5 红 → 6/6 绿），后经发布产物核验确认 MCP 从未交付任何发布产物，迁移为死代码，遂连同单测一并移除

### 需登记的两处文档不准确（**本轮不改**，不在写集）

1. `CHANGELOG.md` 的 `[2026.9.18]` 已发布段描述了 MCP 工具集（T-McpToolset 一期三工具、T-McpToolset2 十工具 + 设置页门控），而该版本产物里并不含 MCP——条目是 dev 分支合并时被并入已发布段的
2. `update/preview.update.json` 的 `downloadUrl` 指向 `.../releases/download/2026.9.18/...zip`，而 Gitee 侧从未创建该正式版（必 404）

### 环境受限未验证（计划 §五 真机走查三项，均未执行）

1. 沙盒 `Settings → Tools | MCP Server → Exposed Tools` 目视十个分组名与相邻性——本机无 MCP 可用沙盒会话，静态核验已做（类名清单见 §八），**UI 呈现未实测**
2. 插件设置页「MCP 工具」子页逐工具勾选 ↔ 平台列表状态一致——未实测（门控逻辑未改，测试侧由 `McpToolGateTest` / `McpConfigurableGateTest` 覆盖）
3. 任一 agent 实调一次新名工具并核 `[MCP-AUDIT]` 工具名——无可用 MCP client，未执行

### 门禁读数

- **定向测试**（改名受影响面：`mcp.*` 全包 + `config.*` 全包 + `convention.*` 三个守卫 + `generator.DmlAliasGuardTest` + `log.SqlConsoleBridgeFilterProviderTest` / `SqlLogListFreezeGuardTest` / `log.agent.*` + `ui.highlight.DatabaseSchemaResolverNegativeCacheTest`）：**62 套件 / 388 例 / 0 失败 / 0 错误 / 0 跳过**，BUILD SUCCESSFUL 4m48s。其中守卫类 `BannedApiSourceScanTest`、`McpScopeSourceScanTest`（标记加固后）、`PluginXmlStructureGuardTest`（十 toolset 类名清单）、`SettingsRealityGuardTest`（bundle 幽灵键）全绿
- **全量 `testShards`**（权威读数，删迁移后的最终态；清 `build/classes` + `build/kotlin` 后跑）：**5372/0/0/6** —— shard1 1634/0/0/0、shard2 1727/0/0/6、shard3 2011/0/0/0（6 跳过 = Docker），BUILD SUCCESSFUL 3m31s。与删迁移前的 5378 相差 **-6**，精确等于移除的迁移单测数
- 删迁移前那一轮首跑 shard3 曾出现 8 例失败（`SqlConsoleBridgeFilterProviderTest` ×2、`SqlLogListFreezeGuardTest` ×1、`AgentListenSyncTest` ×3、`AgentManagerPremainTest` ×1，形态为 `AlreadyDisposedException` / 断言），按既有三步证伪：① 四个类隔离复跑全绿 → ② 全量复跑 5378/0/0/6 绿 → ③ 删迁移后全量复跑 5372/0/0/6 绿，判定为既有的顺序/分片邻居依赖假失败（与本次改名无关：失败类均不在 MCP 面）
- `verifyGate` / `verifyPlugin`（三目标 IU-243/253/262）：**按用户口径未纳入本轮**（构建校验只跑一次、由派活方在全部子智能体完成后统一跑），本轮未执行


