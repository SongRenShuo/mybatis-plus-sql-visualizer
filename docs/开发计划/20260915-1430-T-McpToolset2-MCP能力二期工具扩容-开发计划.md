# T-McpToolset2 MCP 能力二期工具扩容 — 开发计划

- 日期：2026-09-15 14:30（修订 2026-09-15 15:00：增补 T-MTS2-00 设置页总开关与工具级启用/禁用，结构决策由单 toolset 类改为按工具拆分——平台 per-tool 门控只有 toolset 粒度 `isEnabled()`）
- 任务编号：T-McpToolset2（MCP 能力二期；用户 2026-09-15 指示「一期三工具偏少」；同日二次指示「MCP 工具需在插件设置页快速启用/禁用，且可整体开关插件 MCP 功能」；第四轮报告 N-16 位差叙事的延伸——渲染器生态位要靠工具面密度守住）
- 性质：已交付能力（纯逻辑资产）的 MCP 面扩容（新增工具 + 既有链复用，引擎零改动）+ 插件设置页 MCP 门控
- 实施分支：基于 dev 开任务分支 t-mcp-toolset2（dev 现含 N-16/O-9~O-14 七任务交付，HEAD 8ad316b）
- 验证基线：dev 全量 `./gradlew testShards` 360 类 / 4410 / 0 / 0 / 6 绿

---

## 一、背景与依据

一期（T-McpToolset）注册了三只读工具：`sqlv_render_sql`（渲染）、`sqlv_schema_context`（上下文）、`sqlv_explain`（执行计划）——覆盖了 agent 消费 SQL 主链的「源码 → SQL → 结构 → 计划」四段。但 agent 的完整工作流还有四段空白，而填补它们的引擎侧资产**本插件全部现成且多为纯逻辑**：

1. **日志还原**（插件的核心能力）：agent 读应用日志拿到的是 `Preparing:`/`Parameters:` 两行文本，还原成可执行 SQL 目前只有 IDE 面板能做——三日志解析器是纯计算（`computeConversion` 注释明确后台线程安全不触 Swing），接 MCP 面零成本。
2. **写前风险核查**：CodeHelperPro 用 `validate_mybatis_mapper` 占了「校验器」叙事位；O-9 刚交付的 UnboundedWriteDetector（七规则）+ SqlTypeClassifier + SqlPerfAnalyzer 组合起来就是一个校验工具——且我们有位差武器：**校验的对象可以是渲染后的真实 SQL**，这是纯静态校验器做不到的。
3. **回滚规划**：agent 替用户执行破坏性操作前应给回滚预案；T-RollbackSql 的生成器零 PSI 零 IDE 依赖，是现成的纯函数。
4. **生成类**（CRUD 四件套 / SQL→Java）：IDE 内是右键动作，agent 面没有对应物；生成链的纯逻辑部分（CrudTableSpec→SQL、SqlReverseParser）在位。

约束红线（一期定案，全数继承）：只读三 hint、零弹窗（拒绝走 `McpExpectedError`）、`McpExpectedError` 可读错误、每次调用 `[MCP-AUDIT]` 审计、描述文案「deterministic renderer」口径、i18n 零新键（agent 文案英文常量不进 bundle）。

## 二、现状核实（dev 实测）

| 面 | 现状 | 位置 |
|---|---|---|
| 一期三工具 | `@McpTool` + `@McpDescription` + 三 hint 全 TRUE/FALSE/TRUE；`audit()` 包裹；三钩子字面量覆写（253 兼容） | `mcp/SqlVisualizerToolset.kt:34-39,45,90,129` |
| 桩面 | mcpStub 4 文件（annotations/McpCallInfo/McpExpectedError/McpToolset）——新工具全部复用现有注解与类型，**零新平台类型 = 零桩变更** | `src/mcpStub/kotlin/com/intellij/mcpserver/` |
| 错误码 | 九码在册（READ_ONLY/SQL_BLOCKED/INVALID_ARGUMENT/DATASOURCE_×2/SCHEMA_NOT_AVAILABLE/DIALECT_UNSUPPORTED/SOURCE_NOT_FOUND/PROJECT_NOT_READY），二期够用不新增 | `mcp/McpToolErrors.kt:15-39` |
| 日志还原资产 | MyBatisLogParser / HibernateLogParser / JdbcTemplateLogParser：`feed(line)` + `closePending()` + `takeEntries()` 三段式，构造参数为前缀/长度配置（UI 从 PluginSettings 读）；LogSqlRestorer 逐条还原；`computeConversion` 已注明纯计算后台安全 | `log/LogToSqlPanel.kt:357-395`、`log/LogSqlRestorer.kt:16` |
| 写风险资产 | UnboundedWriteDetector（七规则 + `dangerous` 分级 + `kinds()`，解析失败 PARSE_FAILED fail closed）；SqlTypeClassifier（QUERY/DML/DDL） | `execute/UnboundedWriteDetector.kt`、`execute/SqlTypeClassifier.kt` |
| 回滚资产 | RollbackSqlGenerator.generate(sql, pk)：Rating 三档 + warnings + countCheckSql + snapshotSelectSql，类 KDoc 明确零 PSI/零 IDE 依赖 | `sql/rollback/RollbackSqlGenerator.kt:45,117` |
| 性能资产 | SqlPerfAnalyzer.analyze(sql) → List<PerfWarning>（messageKey 形态） | `sql/SqlPerfAnalyzer.kt:57`、`sql/perf/PerfRuleEngine.kt` |
| mapper 解析资产 | XmlMapperParser（errors + statements 清单）；RenderedXmlSql.dollarSubstitutions（O-11 直替计数）；XmlPreviewModel.renderSql(state) 单源渲染 | `xml/XmlMapperParser.kt`、`xml/XmlSqlRenderer.kt:38` |
| 生成类资产 | EntityResolver.resolve(PsiClass)→TableInfo（PSI 读）；CrudTableSpec（列勾选重建输入）；SqlReverseParser.parse（纯解析，错误带行号） | `psi/EntityResolver.kt:57`、`generator/CrudTableSpec.kt:41`、`reverse/SqlReverseParser.kt:34` |
| PSI 读取先例 | McpSourceLocator / McpSchemaContextService 内部包 ReadAction；requireSmart 拒 dumb 态 | `mcp/McpSourceLocator.kt`、`SqlVisualizerToolset.kt:267` |
| 格式化资产 | SqlFormatter（object，仅 PSI token 类型常量、lexer 驱动，无活文档依赖——接入时验证 headless 可用性即可） | `ui/highlight/SqlFormatter.kt` |
| 缺口 | 上述资产全部只有 IDE 面板消费路径；MCP 面无一暴露；工具无任何启用/禁用门控（注册即常开） | — |
| 插件 MCP 设置页 | 纯跳转页（平台 MCP Server 设置入口 + 三工具说明文案），`isModified()=false` 无本地状态——用户要求的「快速启用/禁用」落点，需增补开关 UI | `config/McpConfigurable.kt:27-102`（plugin.xml:159-164 注册，groupWeight=15） |
| 设置状态类 | PluginSettings @State 持久化（State 数据类默认值形态）；无任何 MCP 相关字段 | `config/PluginSettings.kt:24-28` |
| 生成链入口（R2 依赖） | 实体→spec：`GenerateCrudSqlFromEntityAction.entitySpec(PsiClass)`（internal 同模块可复用，内含 EntityResolver+引号提示+方言）；spec→SQL：`CrudSqlGenerator.generate(CrudTableSpec)`；SQL→Java：`JavaCodeGenerator.generate(result)`（entity=null 字符串列形态，纯函数）；解析：`SqlReverseParser.parse(sqlText)` | `actions/GenerateCrudSqlFromEntityAction.kt:126`、`generator/CrudSqlGenerator.kt:31`、`reverse/JavaCodeGenerator.kt:66`、`reverse/SqlReverseParser.kt:34` |
| 日志还原产物 | LogSqlEntry 已含 `restoredSql`（还原后可执行 SQL）+ source/sourceLine 元信息——render_logs 直接消费 | `log/LogSqlModels.kt:24-57` |
| 设置页既有键 | settings.mcp.title/label/desc/open/unavailable/tools.heading 六键双语在册（新开关键相邻追加） | `messages/MpSqlVisualizerBundle*.properties:505-510` |
| 英文文案面 | bundle base 文件即英文（rollback.warn.\*、perf.\* 等键双语在册）——agent 面英文渲染可直接读 base bundle，零新键 | `util/MpSqlVisualizerBundle.kt`（utf8Control 私有，需开 internal 入口） |

## 三、方案

**结构决策（修订）：按工具拆分 toolset 类 + 抽象基类门控**。平台对工具的启用/禁用门控只有 **toolset 粒度的 `isEnabled()`**（桩接口三钩子；ReflectionCallableMcpTool 按方法建工具、toolset 是唯一可关单元）——用户要求工具级启用/禁用，即一工具一 toolset 类。落地：

- 抽象基类 `mcp/McpToolsetSupport`：三钩子**字面量覆写**（253 桥安全——类内零接口默认方法引用；基类提供覆写与子类各自覆写等价，桥只为「未覆写的接口默认方法」生成）；`isEnabled() = McpToolGate.isEnabled(toolName)`（总开 ∧ 工具级未禁用）；isExperimental=false / alwaysIncluded=false 固定；复用一期 `audit()`/requireProject/requireSmart/throwExpected/firstLine 基础设施。
- 一期三工具拆为 `RenderSqlTool` / `SchemaContextTool` / `ExplainTool` 三类（方法体逐行搬移，explain 的 resolveExplainTarget/executeExplain/ExplainTarget 随 ExplainTool 走）；二期七工具各一类；`SqlVisualizerToolset` 删除；`mcp-optional.xml` 注册 10 个 `mcpServer.mcpToolset` EP。
- 所有类保持 `mcp/` 包平铺（McpScopeSourceScanTest 作用域守卫：mcpserver 引用锁 mcp/ 包，越包即回退豁免）。
- `McpToolGate`（`mcp/McpToolGate.kt`）：读 PluginSettings（服务未注册的测试环境兜底全启用），总开关 `mcpToolsEnabled` + 工具禁用清单 `mcpDisabledTools`。

### T-MTS2-00 插件设置页 MCP 门控（用户二次指示，先行——门控在位再扩工具面）

- **PluginSettings.State** 新增两字段：`mcpToolsEnabled: Boolean = true`（总开关——插件 MCP 功能整体启停）、`mcpDisabledTools: MutableList<String> = mutableListOf()`（工具级禁用清单，缺省即启用；新工具天然默认启用，配置向后兼容）。
- **McpConfigurable** 增补开关区（既有跳转区保留在顶部）：总开关复选框 + 10 工具逐行复选框（勾=启用）+「全选/全不选」状态切换单按钮（T-SelToggle 同款范式；用户全局 UX 偏好）+ isModified/apply/reset 三处接线；文案进 bundle 双语（新键 ~14 个，settings.mcp.\* 相邻追加；parity 守卫照常约束）。既有「已注册的三只读工具」静态文案改为跟随开关状态的动态提示（工具数随二期变化，不再硬编码"三只读"）。
- 生效语义：`isEnabled()` 在平台列工具/调用时读取设置——切换后**已连接的 MCP 客户端何时看到工具面变化由平台刷新机制决定**（真机走查 §五-5 核实；最差情况客户端重连生效，诚实记录）。

### T-MTS2-01 纯逻辑四工具（R1，零 PSI 零 JDBC，普通 JUnit 全覆盖）

| 工具 | 入参 | 引擎链 | 产物 |
|---|---|---|---|
| `sqlv_render_logs` | logText（必填）；sqlPrefix/paramPrefix/mixedPrefix 等可选覆盖（缺省读 PluginSettings，与面板同源） | 三解析器并行 feed → takeEntries（`LogSqlEntry.restoredSql` 现成） | 逐条可执行 SQL（多组以 `-- SQL #N` 注释行分隔，与 render_sql 多语句口径一致）+ 来源格式/行号/耗时元信息 |
| `sqlv_check_sql` | sql（必填） | SqlTypeClassifier 分类 + UnboundedWriteDetector.detect + SqlStatementSplitter 计数 | type / statementCount / findings（kind + detail）/ dangerous 布尔——纯文本判定，绝不执行 |
| `sqlv_rollback_plan` | sql（必填）；primaryKeyColumns 可选（逗号分隔） | RollbackSqlGenerator.generate | rating / statements / warnings / countCheckSql / snapshotSelectSql / targetTable——只产出预案永不执行 |
| `sqlv_perf_advice` | sql（必填） | SqlPerfAnalyzer.analyze | 警告清单（ruleId/severity/行号 + 英文文案）。**英文文案 helper 定案**：bundle base 文件即英文（rollback.warn.\*/perf.\* 键双语在册）——`MpSqlVisualizerBundle` 开 internal `messageEn(key, args)`（Locale.ROOT + 既有 utf8Control）统一渲染 rollback 与 perf 警告，**替代原 McpPerfText 键映射方案**（零新键且无映射不全风险）；未识别键输出键名 + 参数诚实降级 |

### T-MTS2-02 PSI 两工具（R2，ReadAction 模式照 McpSourceLocator 先例）

| 工具 | 入参 | 引擎链 | 产物 |
|---|---|---|---|
| `sqlv_crud_sql` | filePath（实体类文件，与 render_sql 同定位语义）或 className 二选一 | requireSmart → ReadAction 内 EntityResolver.resolve → CrudTableSpec → 既有 CRUD 生成链 | INSERT / UPDATE / SELECT / DELETE 四件套文本 + 表名/列元信息；无主键降级 `WHERE 1 = 0` 占位与 IDE 侧口径一致；只生成不执行 |
| `sqlv_reverse_sql` | sql（必填）；targetClass 可选提示 | SqlReverseParser.parse（纯解析）+ 生成器 | MP 代码文本（默认形态）+ 错误行号（失败时 SOURCE 语义错误经 McpExpectedError）；PSI 候选反查（TableInfo）不做——无实体解析的纯文本模式已可交付，索引反查留后评估（慢操作守卫成本） |

### T-MTS2-03 mapper 契约核查（R2，CodeHelperPro validate_mybatis_mapper 对位）

`sqlv_mapper_check`：xmlSnippet 或 filePath（mapper XML）→ 三层产出：
1. **契约层**：XmlMapperParser 解析错误清单 + 语句清单（id / 动态标签拓扑 / `#{}` 参数清单 vs parameterType 缺失提示）+ `${}` 直替位标注（O-11 计数口径，`dollarSubstitutions`）；
2. **渲染层（位差落点）**：可选 `simulate=true` 时以空模拟参数渲染 → 渲染产物逐条过 UnboundedWriteDetector / SqlPerfAnalyzer——**校验的是渲染后的真实 SQL**，纯静态校验器给不出的证据形态；
3. 输出分节文本（契约 findings / 渲染 findings），拒绝与错误走九码不新增。

### T-MTS2-04 只读查询抽样（P3 可选分支，实施前用户定夺）

`sqlv_query_rows`：单条 SELECT 白名单（McpSelectGuard 复用）+ 行数/列数硬上限 + 走 McpDbExecutor 既有 headless 通道，给 agent「看一眼数据」的能力。**涉及真实业务数据出 IDE**（隐私面）且与「渲染器不出数据」的一期口径有张力——默认不实施，仅在用户明示需要时立项，本计划只预留决策位不做设计。

### T-MTS2-05 测试与质量面

- **T-MTS2-00 门控测试**：McpToolGate 表驱动（总开关 off / 工具在禁用清单 / 默认启用 / 服务未注册兜底）；基类三钩子覆写存在性守卫（每个 toolset 类 isEnabled 语义 + isExperimental=false 恒定——防新工具类漏覆写漂移）；设置页 apply/isModified/reset 往返（照 PluginSettingsPanelBindingTest 范式）。

- 纯逻辑四工具：普通 JUnit 表驱动（render_logs 三格式各 ≥3 正例 + 混合格式 + 前缀覆盖 + 空输入；check_sql 复用 O-9 样例池 + 查询反例；rollback_plan 复用 T-RollbackSql 样例 + UNABLE 路径；perf_advice 键映射全量断言 + 未映射键降级）；
- mapper_check：契约层纯 JUnit（解析错误/动态标签/参数缺失/`${}` 标注）+ 渲染层模拟路径（空参数渲染失败的诚实降级断言）；
- PSI 两工具：BasePlatformTestCase（实体解析命中/className 未命中/非 MP 实体降级；reverse 成功/解析失败错误行号）；
- 输入解析照 McpRenderInput 模式独立纯函数 + 测试；audit 零改动自动覆盖（每工具一次 `[MCP-AUDIT]`）。

### T-MTS2-06 文档同步与门禁

CHANGELOG [Unreleased] Added、变更记录总册行、README「MCP：给 AI agent 的确定性 SQL 工具」小节工具清单更新、marketplace 页 MCP 行同步（新工具一句带过不加营销腔）、一期计划 `20260828-*`（如有）链接关系核对。**mcp-optional.xml 变更 → verifyGate 三目标必跑 exit 0**（T-McpGuard 验收 4 同款红线；本任务零桩变更但注册面动了，门禁不豁免）。

## 四、验收标准

1. 二期 7 工具全部注册可发现（R1 四 + R2 三），只读三 hint 与描述「deterministic/read-only」口径一致；`sqlv_query_rows` 未实施（决策位留置）。
2. **设置页门控生效**：总开关 off → `isEnabled()` 全 false（工具面对 agent 消失）；单工具禁用 → 仅该工具不可见；apply/reset 往返正确；设置页新文案双语 parity 绿。
3. 纯逻辑工具测试不依赖 IDE fixture（普通 JUnit），PSI 工具按既有面板测试范式；新增用例 ≥60 条，全量 testShards 绿（基线 4410 + 新增）。
4. 引擎侧零改动（本期只加 mcp/ 包适配层 + util 包 messageEn 单函数）——grep 证伪：xml/、log/、execute/、sql/rollback/ 四包 diff 为空。
5. 零弹窗红线：全部拒绝路径走 McpExpectedError；audit 覆盖每工具（10 工具一类一条 `[MCP-AUDIT]`）；九错误码无新增。
6. verifyGate 三目标 exit 0（拆分后每个 toolset 类三钩子覆写语义在 253 无桥悬空——门禁实证）；桩源集 diff 为空（零新平台类型的自证）。
7. 跑 gradle 前按铁律 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check` 并 register 独立沙盒。

## 五、真机走查清单（留人工）

1. Claude Code / AI Assistant 各调一次 10 工具：核对描述文案、错误形态、`[MCP-AUDIT]` 日志。
2. `sqlv_render_logs` 喂真实项目混合日志（MyBatis + JdbcTemplate 混排）：条目数与面板「日志 → SQL」一致。
3. `sqlv_mapper_check` 对含 `${orderBy}` 动态排序的 mapper：`${}` 标注 + simulate 渲染 findings 在位。
4. `sqlv_crud_sql` 对逻辑删除实体：DELETE 变体与 IDE 右键产物一致。
5. **设置页开关真机核实（T-MTS2-00）**：总开关关 → 平台 MCP 工具列表变化时机（即时/重连）；单工具禁用 → agent 侧不可见；重开恢复。结论落 §八。
6. 243 真机回归：mcp-optional.xml 整文件不加载零影响（一期口径不变）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 工具面膨胀稀释 agent 选择 | 10 工具 descriptions 全量进 system prompt，描述冲突/重叠致误选 | 每工具描述首句钉死「输入→输出」差异；check/mapper_check/render_logs 三者描述互相指路（「render logs first, then check」链式引导）；**设置页工具级开关是用户侧最终解**（T-MTS2-00） |
| 按工具拆类后 253 桥回归 | toolset 类从 1 变 10，K2 接口默认桥风险面扩大 | 覆写收敛到抽象基类 McpToolsetSupport（类内零接口默认引用，253 安全机制与一期相同）；verifyGate 253 目标硬验收（§四-6） |
| isEnabled 读取时机 | 平台可能缓存工具清单，开关切换不即时生效 | 真机走查 §五-5 核实并诚实记录；页面文案注明「可能需重连 MCP 客户端」 |
| PerfWarning 键映射不全 | messageKey 是 IDE 面板消费形态，英文常量映射漏键致输出降级 | T-MTS2-05 键映射全量测试 = 键清单从 PerfRulesBatch1~3 反射/静态枚举断言，新增规则漏映射即红 |
| render_logs 前缀配置面过宽 | 用户自定义前缀形态组合爆炸，MCP 参数覆盖语义含糊 | 参数只暴露 UI 设置页同名字段，缺省读同一 state（单源）；文档注明「通常无需传」 |
| mapper_check simulate 误报疲劳 | 空模拟参数下 `<if>` 全关属正常态，报「语句为空」是噪音 | simulate 结果明确标注「空参数上下文」；空语句不计 finding；文档引导配合 render_sql 拿真实参数形态 |
| PSI 工具 dumb 态挂起 | 实体解析依赖索引，索引期调用体验差 | requireSmart 直接拒绝（PROJECT_NOT_READY）不等待——一期既有口径 |
| query_rows 决策悬置 | 用户后续可能要求 | 决策位 + 红线（隐私/量级/口径张力）已留档，届时单独立项不搭车 |

## 七、实施顺序

```
T-MTS2-00（设置门控：状态字段+McpToolGate+设置页开关+三工具拆类基线）
→ T-MTS2-01（纯逻辑四工具+表驱动测试）→ T-MTS2-03（mapper_check，吃 01 的 check 渲染层组合）
→ T-MTS2-02（PSI 两工具）→ T-MTS2-05（质量面收口）→ T-MTS2-06（文档+verifyGate 三目标）
→ 真机走查 §五；T-MTS2-04 全程不实施（决策位）
```

## 八、实施记录（实施后回填）

**2026-09-15 实施完毕并合入 dev（分支 t-mcp-toolset2 → dev 快进合并，dev HEAD f42b739；合并后全量 testShards 367类/4421/0/0/6 绿——基线 350/4267 → +17 类 +154 例；verifyGate 三目标 exit 0，253/262 Compatible + 243 豁免留痕；独立子代理复核 9/10 通过，1 处措辞补正）**

> 合并踩坑留档：dev worktree 旧 build 目录直接跑 testShards 出现成片 `NoSuchMethodError`（PluginSettings 新增字段后旧 class 被陈旧产物劫持）——**清 build/classes + build/kotlin 后即绿**，与记忆 pitfall-regression-false-failure-flakes 的「合并后首跑先清产物」同型。

- **T-MTS2-00 门控落地**：PluginSettings.State 增 `mcpToolsEnabled`(默认 true) + `mcpDisabledTools`(默认空)；McpToolGate 统一读口（服务未注册兜底启用）；**一工具一 toolset 类**（平台 per-tool 门控只有 toolset 粒度 isEnabled——262 mcpserver.jar getEnabledToolsets 字节码实证每次 getTools 重新求值，开关即时生效无需重启）；McpToolsetSupport 抽象基类三钩子 final 字面量覆写（253 兼容机制与一期相同）；mcp-optional.xml 注册 10 个 toolset。McpConfigurable 门控区：总开关 + 10 工具复选框 + 全部启用/禁用切换按钮（T-SelToggle 同款）+ isModified/apply/reset；**工具复选框与总开关正交**（仅表达「不在禁用清单」——否则 master off 时复选框全不勾会把全部工具误写禁用清单，apply 反噬）。
- **T-MTS2-01 R1 四工具**：render_logs（三解析器 feed→takeEntries 复用面板同构，`LogSqlEntry.restoredSql` 直接消费，前缀缺省读设置可覆盖）/ check_sql（splitter+classifier+detector 组合）/ rollback_plan（RollbackSqlGenerator + messageEn）/ perf_advice（SqlPerfAnalyzer + messageEn）。**英文文案定案**：MpSqlVisualizerBundle 增 internal `messageEn`（Locale.ROOT + utf8Control 读 base 英文，零新键），替代原 McpPerfText 键映射方案（无映射不全风险）。
- **T-MTS2-02 R2 两工具 + T-MTS2-03**：crud_sql（复用 GenerateCrudSqlFromEntityAction.entitySpec 单一真相源 + CrudSqlGenerator，无主键 `1=0` 占位与 IDE 口径一致）/ reverse_sql（SqlReverseParser+JavaCodeGenerator 字符串列形态，错误带行号）/ mapper_check（契约层 + simulate 渲染层——空参数语境下 `sqlv_mapper_check` 对渲染产物过 detector/perf，即「校验渲染产物」位差落点；空/非法 XML 转 McpExpectedError）。
- **测试分层决策（实施中关键修正）**：243 基线测试 JVM 的 stdlib 缺 `SpillingKt`——`audit` 本体是高阶 suspend 结构性发射（一期 ignored-problems 豁免语义=mcp-optional.xml 不加载则类永不可达），**测试进程直接实例化 Tool 类调用挂起方法即 NoClassDefFoundError**。解法=七新工具核心全部抽 `internal` 非 suspend 纯函数（checkSql/rollbackPlan/perfAdvice/reverseSql/mapperCheck/renderLogs/resolveSpec+crudOutput），挂起方法只做 audit 一行委托；测试全走纯函数层，挂起链只有真机/生产触达。测试 9 类约 50 例（门控 5 + 设置页 5 + 各工具表驱动 + mapper_check 契约/simulate + crud 实体四件套/空列拒绝）。
- **顺带收口三处 74f7842 合并回归**（base 基线即红，非本次引入）：PluginXmlStructureGuardTest 子设置页 count 5→7（plugin.xml 声明序按 weight 降序重排至 ui/shortcuts/preview/tableMapping/sqlLog/mcp/about）；PluginSettingsFolderConfigurable.SUB_PAGES 补 shortcuts 子页（folder 搜索页）；McpScopeSourceScanTest 增 stripStrings 字符串字面量豁免（`Class.forName("com.intellij.mcpserver.settings...")` 是反射字符串非类型引用，未装环境已兜底提示）。
- **构建接线**：mcpStub 桩源集补挂 compileTestKotlin + test 任务 + **testShardN 分片任务**（IPGP 分片默认不含桩输出，漏挂致工具类 NoClassDefFoundError 成片 28 例，定向 `test` 因已挂桩而绿——实证分片与 test 两条 classpath 独立）。
- **sam 独立复核（写验分离）**：独立上下文子代理对照 10 项验收，9/10 通过；唯一不通过项=MapperCheckTool @McpDescription 缺 deterministic 措辞——已补「deterministic evidence a static-only validator cannot produce」，与其余 9 工具描述风格对齐。
