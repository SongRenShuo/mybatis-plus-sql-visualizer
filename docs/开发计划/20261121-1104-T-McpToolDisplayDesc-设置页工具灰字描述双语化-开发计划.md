# T-McpToolDisplayDesc：MCP「公开的工具」页灰字描述双语化 · 开发计划

> 立项：2026-11-21 11:04，用户在 IDEA 2026.2.1 设置页（AI → MCP 服务器 → 公开的工具）目视发现。
> 状态：计划完成即从 master 开工作树与分支 `t-mcp-tool-display-desc` 实施**本计划全部内容**（用户令：禁缩范围）。

## §一 背景与现象

- 用户报告：平台设置页「公开的工具」列表里，本插件 11 个 `sqlv_*` 工具的工具行下方灰色小字**只重复工具名**（如 `sqlv_check_sql` 下面还是 `sqlv_check_sql`），而其它插件的工具灰字是**一句人类可读描述**。
- 用户诉求：灰字应该有描述，且**跟随插件语言**（插件设中文 → 灰字中文；设英文 → 灰字英文）。

## §二 根因（字节码级证据链，2026-11-21 本机 2026.2.1 实证）

1. 设置页灰字读 `McpToolDescriptorKt.getPresentableDescription()` ＝ **`displayDescription ?: description`**（javap 实证）——`displayDescription` 非空时不回落到 `@McpDescription` 长文。
2. 平台构建描述符时，`displayDescription` 来自调用 toolset 的 **`displayDescription(fallback)` 钩子，fallback＝工具名**（`ToolsetReflection_utilKt` 字节码：`toolset.displayDescription(toolName)`）。
3. 本插件 `McpToolsetSupport.displayDescription(fallback)` 现实现为**透传 fallback**（「不自造文案」）→ 灰字＝工具名。
4. `@McpDescription` 长文本身完好（外部 MCP 客户端 tools/list 里是完整描述）——缺陷只在「设置页灰字」这条展示面。

## §三 方案

1. **bundle 加 11 个键**（段位贴既有 `settings.mcp.*`）：`settings.mcp.tool.desc.<工具名>`，英文 base ＋ 中文 `_zh` 各一行（60~80 字内，灰字单行空间）。
2. **基类两钩子改读 bundle**：
   - `displayDescription(fallback: String)` ＝ `bundle("settings.mcp.tool.desc.$toolName") ?: fallback`；
   - `displayDescription()`（263 无参版）＝ 同键，缺键给空串（维持「不参与渲染」语义）。
   - **红线**：`McpToolsetSupport` 必须保持零 `com.intellij.mcpserver.*` 静态类型引用（243/251 verifier，T-McpDynamicToolset 存在理由）——bundle 是插件自有类，不触线。
3. **文案草案**（实施照抄进两份 properties；refine 空间仅限措辞不改语义）：

| 工具 | zh | en |
|---|---|---|
| sqlv_check_sql | 静态核查 SQL 文本（不执行不连库）：语句数、每条类型与缺 WHERE 等无界写风险 | Statically check SQL text (no execution): statement count, types, unbounded-write risks |
| sqlv_crud_sql | 按 MyBatis-Plus 实体类生成 INSERT/UPDATE/SELECT/DELETE 模板语句（只产文本不执行） | Generate INSERT/UPDATE/SELECT/DELETE statements from a MyBatis-Plus entity class |
| sqlv_mapper_check | 核查 MyBatis mapper XML：解析错误、语句清单与 ${} 注入面，可模拟渲染做写风险分析 | Check a MyBatis mapper XML: parse errors, statement inventory, ${} injection surface, write-risk simulation |
| sqlv_history_search | 搜索已保存的 SQL 历史（按关键词/正则/时间段过滤，只读不执行） | Search persisted SQL history by keyword, regex, time range and source (read-only) |
| sqlv_render_logs | 从应用日志还原可执行 SQL：MyBatis/Hibernate/JdbcTemplate/ShardingSphere 形态，不执行 | Restore executable SQL from application log text (MyBatis/Hibernate/JdbcTemplate/ShardingSphere) |
| sqlv_perf_advice | SQL 静态性能评审：SELECT *、缺索引提示、前导通配 LIKE 等 21 条规则，不执行 | Static performance review of one SQL statement: 21 rules, no execution, no database access |
| sqlv_render_sql | 还原 MyBatis-Plus Wrapper/mapper XML/JPA 等链路的真实可执行 SQL（参数已填值） | Render the executable SQL behind Wrapper chains, mapper XML and JPA queries with real values |
| sqlv_explain | 对单条只读 SELECT 跑 EXPLAIN 拿真实执行计划（支持 MySQL/PG/Oracle 等方言） | Run EXPLAIN for one read-only SELECT against a configured data source (dialect-aware) |
| sqlv_rollback_plan | 为 INSERT/UPDATE/DELETE 生成回滚计划与快照模板（只产计划不执行） | Generate a rollback plan for INSERT/UPDATE/DELETE without executing anything |
| sqlv_schema_context | 取数据源的表/列紧凑上下文（名称、类型、注释），写 SQL 前对齐真实结构 | Compact table/column context (names, types, comments) resolved from the real data source |
| sqlv_reverse_sql | 把单条 SQL 反向生成 MyBatis-Plus QueryWrapper Java 代码（只产代码不执行） | Convert one SQL statement into MyBatis-Plus QueryWrapper Java code |

4. **MCP 协议层不动**：`@McpDescription` 长文保持英文（受众＝外部 AI agent），两个受众分离。

## §四 实施步骤

1. 从 master 开工作树（`git worktree add`，目录与既有工作树同级）＋分支 `t-mcp-tool-display-desc`。
2. `McpToolsetSupport`：两钩子改读 bundle。bundle 取值口径先核对 `MpSqlVisualizerBundle` 是否有「缺键返回 null」的 API（`messageOrNull` 或 `containsKey` 前置判断），没有则补一个（对齐现有 `message()` 的 UTF-8 control 与语言选择链）。
3. 两份 properties 各加 11 键（§三.3 草案），中英键集对称。
4. 新测试 `McpToolsetDisplayDescriptionTest`（纯 JUnit，落 `test/kotlin/cn/song/mybatisplus/visualizer/mcp/`）：
   - 中文态 11 工具灰字非空且 ≠ 工具名（语言缺省兜底中文，bundle 头注实证）；
   - 英文态（按既有测试控制 PluginSettings/locale 的先例）返回英文键值；
   - 缺键回落：构造不在 bundle 的 toolName 测试子类 → `displayDescription(fallback)` 恰返回 fallback；
   - 无参版缺键返回空串；
   - bundle 对称自查（en/zh 键数一致、11 键两份齐全）。
5. 守卫核对：跑 `McpToolsetShellFactoryTest` / `TinyClassFileWriterD239Test`（壳生成不受钩子实现影响）＋ grep 既有 bundle/设置自检类（`PluginDistributionSelfCheckTest` 等）确认键对称与设置面板断言不连坐；撞面即同步修。
6. 定向测试（只跑本任务相关类，禁全量禁构建校验禁 clean）；反向验证见 §五。
7. CHANGELOG `[Unreleased]` 回填（src/main 用户可感改动，随提交）；`docs/MEMORY.md` 文档索引不加（无新文档）。

## §五 验收清单

- [ ] 中文态设置页：11 个工具灰字＝中文一行描述（真机目视，用户点验）
- [ ] 英文态设置页：灰字切英文（真机目视；或以单测 en 键值＋真机抽查为准）
- [ ] 定向测试全绿：`McpToolsetDisplayDescriptionTest` ＋ 壳层两守卫类
- [ ] 反向验证恰红：把基类钩子改回透传 → 灰字回落工具名的用例变红（恰好 1~2 红，不漂移）
- [ ] bundle 中英键数一致（自检断言在场）
- [ ] CHANGELOG `[Unreleased]` 已回填

## §六 待验证前提（实施者先证伪再动手）

1. **263 无参钩子的平台调用面未实证**：262 已证走带参版（字节码）；263 平台是否也调无参版（组描述？）未查——两版都改即覆盖两态，风险低，但 263 真机灰字表现留待用户点验时顺带确认。
2. **灰字长文案的截断行为未证**：描述超长时设置页是否截断/换行未知——文案草案按 60~80 字控制，真机目视若溢出再收敛措辞（不改结构）。
3. **测试环境语言控制先例未核对**：`MpSqlVisualizerBundle` 按 `PluginSettings.state.language` 选语言、测试环境兜底中文（头注）——英文态单测怎么控语言，实施时先找既有先例（grep 测试里 language/locale 注入口），照抄；若无先例，给 bundle 加包内可见测试注入口（先例同 JarOriginProbe 形态）。
4. **bundle 缺键 API 缺位**：`messageOrNull` 可能不存在——补 API 是计划内动作（步骤 2）。
5. **「仅路由器」勾选与灰字无关**：截图里组级/工具级「仅路由器」是 router-only 标记（本插件默认形态），本任务不触碰该语义。

## §七 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-21 11:04 | 立项：根因字节码实证（§二）、方案与文案草案（§三）、步骤/验收/前提（§四~六）。计划提交 master 后开 `t-mcp-tool-display-desc` 工作树实施。 |

## §八 实施记录（2026-11-21）

- **实施修正一条（如实登记）**：§三.3 草拟的「新增 11 键 + 双语文案」**不需要发生**——实施时发现 bundle 早已存在同一用途的键 `settings.mcp.tool.desc.<尾段>`（T-McpToolset2 时代随门控区落盘，`McpConfigurable.checkBoxText` 在用，11 键中英双语对称、`_zh` 1224~1234 / base 1254~1264）。本任务交付＝**把既有键接入新消费面**（平台设置页灰字，原只门控区一处在用），键派生规则＝`removePrefix("sqlv_").replace("_", "")`（全局去下划线）。计划意图（灰字有双语描述）已由既有键完整达成，无缺交付物；文案采既有短文案而非本册草案（更精炼、已被设置页 UI 验证）。
- **改动面**（分支 `t-mcp-tool-display-desc`，基于 master `9f4937fb8`）：
  - `McpToolsetSupport`：两钩子改读 bundle（`displayDescription(fallback)` 缺键回落 fallback、无参版缺键空串）；companion 提取 `toolDescKey` 单一派生源；
  - `McpConfigurable.checkBoxText` 改引 `toolDescKey`（防两处内联漂移）；
  - `MpSqlVisualizerBundle` 补 `messageOrNull`（缺键 null，与 `message` 的 key 兜底区分）；
  - `McpToolsetShellFactoryTest` 三钩子守卫期望随行为更新（空串/透传 → bundle 简述，KDoc 登记）；
  - 新增 `McpToolsetDisplayDescriptionTest` 6 例。
- **定向读数（XML 核对）**：`McpToolsetDisplayDescriptionTest` 6/0 + `McpToolsetShellFactoryTest` 11/0 + `TinyClassFileWriterD239Test` 5/0 + `McpConfigurableGateTest` 13/0 ＝ **35/0**。
- **反向验证恰红**：把 `displayDescription(fallback)` 改回透传 → 新测试恰 **2 红**（`十一工具_中文态_灰字非空且不等于工具名` / `中文态_灰字是描述不是工具名`——恰是「灰字＝工具名」断言面，其余 4 例不红不漂移）；`git checkout` 还原后 17/0 复绿。反向前先提交正向（`e6e5acee7`），探针未连坐未提交改动。
- **待用户真机点验**（验收清单 §五 前两项）：设置页灰字中文态/英文态目视（改插件语言 → 重开「公开的工具」页）。
- **合流与门禁（用户令「合流 清理 推送 监听」，2026-11-21）**：分支合入 master（merge `d88413eb0`，零冲突，合并树与分支 tip 一致、定向读数按复用前提沿用）；工作树与分支已删（`-d` 确认已合）；双仓 master 推送成功（push-all.ps1 令牌通路）。**CI gate run 44863040432 @ `d88413eb0` conclusion=success（16 job 全绿：build/agenttest/test 五片+fast/verify 243·253·263/marketplace 243·253·263/verifyplugin/gate；publish 在 gate 模式 skipped 属正常形态）**——本轮任务收官，剩真机灰字中文/英文两态目视由用户随手点验。
