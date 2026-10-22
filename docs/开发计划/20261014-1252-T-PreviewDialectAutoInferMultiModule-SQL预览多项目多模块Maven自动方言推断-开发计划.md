# T-PreviewDialectAutoInferMultiModule：SQL 预览多项目、多模块 Maven 真智能方言推断开发计划

- 占号：**O-145 / R95 / D155 / V178**（2026-10-14 13:44 再次全仓复核；O-144 已由 T-XmlGap 占用，本组仅本计划在用）
- 日期：2026-10-14
- 基线：`master@e15ee699`（`release: v2026.10.13 发行面纳入当前 HEAD 增量`）
- 状态：W0 三版本签名门扩至注入、模块和实际目标 API；W1/W2 注解与 mapper XML 的真实 SQL PSI/IDEA provider/scope/effective 方言纵切已并入 dev，W3 统一证据融合，W4 CRUD/JPA/XML 局部，W5 临时 PSI，W6 模块 UUID 被动学习与清除，W7 实际 session/active console 双闸及模板/日志/试算/adhoc 四旁路已做代码与定向验证。**不宣称整项完成**：其他入口的来源上下文、253/262 平台运行语义、真实 DatabaseTools 注入/真库与用户禁启的沙盒 GUI 仍未验；本批新代码的权威全量与 verifyGate 尚未重跑。
- 用户目标：插件应做到“真智能”，默认不要求用户主动配置 SQL Visualizer 方言；优先复用 IDEA 已有 SQL 方言、SQL 解析作用域与运行时上下文，只有证据缺失或冲突时才让用户介入
- 关联：`T-DialectAutoInfer` 已实现基础 URL 推断；`T-PreviewExecDsDialect`（O-136）解决预览执行 Runner 的方言过滤。二者都没有解决“按原始源文件/注入 SQL/模块上下文求有效方言”的问题

## 一、问题结论

当前“智能推断（跟随数据源）”只具备项目级最佳努力能力，不是真正的上下文推断：

1. `DialectRegistry.effective(project)` 只接收 `Project`，依次使用项目最近 agent URL、`DataSourceUrlProbe.firstUrl(project)`，没有原始文件、SQL PSI、模块、IDEA 解析作用域或执行目标上下文。
2. `DataSourceUrlProbe.firstUrl` 按 DatabaseTools 枚举顺序取第一条非空 URL。多模块、多数据源工程中，列表顺序不能证明某个数据源属于当前预览。
3. 全局设置页用 `ProjectManager.openProjects.firstOrNull()` 显示推断状态，多项目同开时可能展示另一个项目。
4. 预览和执行创建的插件私有 SQL 文件当前被 `DialectPreMapping` 强制映射为 MySQL；用户在 IDEA 配置的 PostgreSQL、Oracle、DM 等方言不会成为插件解析依据。
5. SQL 日志中的 `[PostgreSQL]` 来自该条运行时事件的 JDBC URL，但 `LogSqlEntry.toPreviewResult` 不传 URL/方言；历史角标保留时，项目级实时 URL 可能已经清空。
6. 多数预览入口只把 `文件名:行号` 文本交给面板，`PreviewResult` 没有原始 `VirtualFile`/SQL `PsiFile`。没有源上下文就无法正确查询 IDEA 的文件/目录方言和解析作用域。

错误方言不只影响标签：它会改变分页语法、标识符引用、UPSERT、CRUD 和复制/执行 SQL。执行连接仍由 DatabaseTools Runner 决定，不会因本缺陷直接串库；危险在于“按 A 方言生成 SQL，再由用户执行到 B 数据源”。

## 二、产品目标

### 2.1 默认体验

用户保持“自动识别”即可，无需在插件内重复选择 MySQL/PostgreSQL/Oracle：

1. 从 SQL 日志/历史打开时，优先使用该条目携带的精确 JDBC URL；它只属于这条预览，不被项目级状态或恢复出的当前文件配置覆盖。
2. 其他源代码预览读取当前实际 SQL PSI/原始源文件在 IDEA 中已经生效的 SQL Resolution Scope 和 SQL Dialect。
3. 上述证据没有唯一结论时，再使用模块级被动学习和项目内方言共识。
4. 只有所有自动证据均缺失或互相冲突时，才显示可行动提示；模块绑定是修复冲突的工具，不是默认必配项。执行选择的数据源/活控制台走独立 `ExecutionDialectCheck`，不参加已有渲染结果的投票。

### 2.2 安全目标

- 不按打开项目顺序、数据源列表顺序或显示名相似度猜测。
- 不从方言名反推唯一数据源：同一种方言可有多个数据库/schema。
- 多个候选数据源若方言相同，可确定渲染方言，但保留数据源集合；若方言不同，返回 `Ambiguous`。
- 自动结论必须携带来源、置信度和候选集合；最终 MySQL 兼容回退不得伪装成“已推断 MySQL”。
- 执行/EXPLAIN 前比较“生成 SQL 的方言”与“目标数据源方言”；已知冲突时先重渲染或阻止提交。

## 三、JetBrains 官方调研

### 3.1 SQL Dialects

官方文档：<https://www.jetbrains.com/help/idea/settings-languages-sql-dialects.html>

IDEA 2026.2 文档明确：

- SQL Dialect 用于指定不同作用域中的 DBMS 方言；未设置时使用 Generic SQL。
- 文件/目录可以覆盖 Project，Project 覆盖 Global；`<None>` / `<Clear>` 表示继承更高层。
- 有 attached data source/schema 的编辑上下文通常由该数据源定义方言。

因此插件应读取平台计算后的**有效映射**，不能自行解析 `sqldialects.xml`，也不能只看全局设置。

### 3.2 SQL Resolution Scopes

官方文档：<https://www.jetbrains.com/help/idea/settings-languages-sql-resolution-scopes.html>

IDEA 2026.2 文档明确：

- 文件或目录可以关联一个数据源，也可以关联一组数据源/database/schema。
- Project mapping 是所有 SQL 文件解析未限定对象名时的默认集合。
- 路径没有映射时使用 Project mapping。
- 给父目录添加映射时，IDEA 会询问子项继承父 scope 还是保留自己的 scope。

因此插件不能手写“最近目录即唯一数据源”规则，也不能取 scope 返回集合的第一项。

### 3.3 Language Injections

官方文档：<https://www.jetbrains.com/help/idea/using-language-injections.html>

- Java/Kotlin 字符串、XML tag/attribute 可以形成 injected language fragment。
- SQL Resolution Scope 页面明确要求：项目代码中要获得 SQL coding assistance，须启用 language injections。
- 持久注入可来自 `// language=SQL`、`@Language("SQL")`、内置规则、框架插件或项目 `.idea/IntelliLang.xml`。

插件应优先把实际 SQL `PsiFile`（含 injected SQL PSI）交给 DatabaseTools；`Java/XML 文件路径` 与 `已存在 SQL injection` 不是同一事实。

## 四、本机反编译与版本证据

### 4.1 调研环境

- 日常 IDEA：Ultimate 2026.2.3，build 262.10968.63，安装目录 `C:/Users/USER/AppData/Local/Programs/IntelliJ IDEA`
- 精确 262.2.1 SDK：`F:/gradle-home/caches/9.7.0/transforms/af228bcb143270d170c2bd6debbba1c3/transformed/idea-2026.2.1-win`
- 253.3.5 SDK：Gradle transforms 中 `idea-2025.3.5-win`
- 243.3.7.1 SDK：Gradle transforms 中 `ideaIU-2024.3.7.1-win`

### 4.2 SQL 方言映射

262 中 `com.intellij.sql.dialects.SqlDialectMappings` 位于 `plugins/DatabaseTools/lib/modules/intellij.database.sql.core.impl.jar`，继承 `LanguagePerFileMappings<SqlLanguageDialect>`；243 为 `database-plugin.jar`，253 仍保留同一核心签名。

已核实入口：

```text
SqlDialectMappings.getInstance(Project)
SqlDialectMappings.getMapping(Project, VirtualFile)
SqlDialectMappings.getMapping(VirtualFile)
SqlDialectMappings.getConfiguredMapping(VirtualFile)
SqlDialectMappings.getImmediateMapping(VirtualFile)
SqlDialectMappings.getAvailableValues()
```

262 反编译确认 `getMapping` 的有效查找顺序：

1. injected `VirtualFileWindow` 解包 delegate；
2. 取 original file；
3. 文件自身映射；
4. 逐级父目录映射；
5. Project 映射（内部 null key）；
6. Global/default SQL dialect。

`getConfiguredMapping` 不含最终默认方言，但仍含目录继承和 Project 映射；`getImmediateMapping` 才是精确键。插件正常推断应使用 `getMapping`，诊断“用户是否显式配置”时才读取 configured/immediate 层。

映射值序列化使用 `Language.id`。生产适配应枚举 `getAvailableValues()` 并按 ID 对齐，不依赖具体方言实现类。

### 4.3 SQL 解析作用域

262 中设置页由 `SqlResolveConfigurable` / `SqlResolveEditor` 实现，项目服务为：

```text
com.intellij.sql.dialects.SqlResolveMappings
    extends PerFileMappingsBase<TreePattern>
```

位于 `intellij.database.sql.core.impl.jar`，持久化到项目 `.idea/sqldialects.xml`。已核实入口：

```text
SqlResolveMappings.getInstance(Project)
SqlResolveMappings.getMapping(VirtualFile)
SqlResolveMappings.getConfiguredMapping(VirtualFile)
SqlResolveMappings.getImmediateMapping(VirtualFile)
SqlResolveMappings.getMappings()
```

有效层级同样是文件 → 最近父目录 → Project；未配置时 `getDefaultMapping()` 返回 `ALL_DATA_SOURCES`，所以“有结果”不等于“用户把当前文件绑定到了唯一数据源”。值是可包含多个数据源/database/schema 分支的 `TreePattern`，不是简单的 `LocalDataSource` ID。

243 中还核实了更适合生产读取的入口：

```text
com.intellij.sql.dialects.SqlDataSourceMappings.getInstance(Project)
SqlDataSourceMappings.getDataSources(PsiFile)
SqlDataSourceMappings.getProvidedDataSources(PsiFile)
```

其字节码会先处理 `file.getOriginalFile()` 和 `SqlImplUtil.getTopLevelSqlFile(...)`，然后按真实分流求值：`DataSourceProvider` 已提供非空集合时优先采用；provider 为空才回落 Resolution Scope；forced datasource 再参与后续收口。`getProvidedDataSources` 只用于诊断 provider 证据，禁止与 `getDataSources` 的最终结果再次合并投票。正确输入是实际 SQL `PsiFile`，而不是插件私有临时文件或人工猜测的 Java/XML 路径。

`SqlResolveMappings` / `SqlDataSourceMappings` 属 DatabaseTools 实现层，没有可假定长期稳定的公开 OpenAPI；生产必须走反射桥、签名探测和降级链。

### 4.4 事件与线程

- 两套 mappings 均没有面向第三方插件的稳定 change topic。
- `setMapping` 会 push file properties 并 reparse PSI；现有代码已实证写操作必须派 EDT。
- 本功能正常读取不应修改用户源文件映射。插件只允许给自己拥有的 preview/exec 临时文件写映射。
- 缓存采用短 TTL + 源 PSI/VFS/项目生命周期失效；不得常驻轮询。

## 五、方案定案：统一上下文与证据融合

### 5.1 第 0 基础件：PreviewSourceContext

新增不持久化的上下文，贯通所有预览入口：

```text
PreviewSourceContext
  project
  hostPsiFile / hostVirtualFile
  sqlPsiFile                  // 普通 SQL 或 injected SQL；可空
  module / canonicalModuleKey
  origin                      // PSI、XML、LOG、HISTORY、MANUAL、MCP
  capturedJdbcUrl             // 仅本次确有 URL 的新事件；旧历史只存库标，不得反推；可空
  selectedDataSourceId        // 本次明确选择/绑定；可空
```

- Java/Kotlin/Mapper/XML gutter 与快捷键入口直接携带原始 PSI，不再只传 `文件名:行号`。
- 日志/历史通过 `sourceFile` 尝试恢复宿主文件，但同路径多模块命中时不得任取第一个模块。现有条目和旧历史仅持久化脱敏数据库角标，**没有精确 JDBC URL**；仅新事件确实携带且安全保留 URL 时才可赋 `capturedJdbcUrl`，凭据/持久化策略须另行定案，旧历史一律按缺失证据降级。
- `PreviewResult` 仍保持纯结果模型；上下文由对话框/面板瞬态持有，避免污染历史序列化和复制语义。

### 5.2 DialectDecision

新增统一决策结果：

```text
DialectDecision
  status: RESOLVED | AMBIGUOUS | CONFLICT | UNAVAILABLE | MANUAL
  dialectName
  platformLanguageId
  dataSourceIds
  evidence[]
  confidence: EXACT | STRONG | CONSENSUS | FALLBACK
  explanationKey
  compatibilityFallbackName

ExecutionDialectCheck
  renderDecision
  targetDataSourceId
  targetDialectName
  status: MATCH | AUTO_RERENDER_REQUIRED | MANUAL_CONFLICT | TARGET_UNKNOWN
```

所有 SQL 生成、分页、引号、UPSERT、临时 PSI 方言映射与补全消费同一个 `DialectDecision`；执行/EXPLAIN 额外消费 `ExecutionDialectCheck`。渲染推断和执行目标校验是两个阶段，禁止用目标数据源静默覆盖用户手动方言，也禁止调用点自行再读 `firstUrl` 或硬编码 MySQL。

### 5.3 自动证据层级

#### A. 渲染决策：显式与精确事实层

1. 用户在 SQL Visualizer 明确选择具体方言时保持最高优先级，视为 `MANUAL`；默认仍是自动识别。
2. 从 SQL 日志/历史打开的条目若携带 JDBC URL，该 URL 只服务该条预览，属于运行时精确事实 `EXACT`；不得被项目级 last-agent URL、恢复出的当前源文件 scope 或另一文件的 IDEA 设置覆盖。

#### B. 渲染决策：原始 SQL 上下文层

3. 先用 `SqlResolveMappings.getConfiguredMapping(originalFile)` 区分“文件/目录/Project 明确或继承的 scope”与默认 `ALL_DATA_SOURCES`；再把实际 SQL `PsiFile` 交给 `SqlDataSourceMappings.getDataSources` 取得平台最终候选。`getProvidedDataSources` 仅记录 provider 是否命中，不与最终集合二次合并：
   - configured scope/provider 最终得到唯一数据源：按其 URL/DBMS 定方言，`STRONG`；
   - 最终得到多数据源且方言一致：方言为 `CONSENSUS`，数据源集合保留；
   - 最终得到多数据源方言冲突：`AMBIGUOUS`，不取第一条；
   - 无 configured scope/provider 时，平台默认的 `ALL_DATA_SOURCES` 只提供项目候选目录，不得标为用户明确绑定，也不得给 `EXACT`。
4. 没有可用数据源上下文时，对原始或 injected `VirtualFile` 调用 `SqlDialectMappings.getMapping`，使用 IDEA 已完成继承计算的有效方言，`STRONG`。262 已证实 `VirtualFileWindow`/original-file 归一化；243/253 的 injected 继承仍列入 V178，不提前宣称全版本成立。
5. Resolution Scope 与 SQL Dialect 同时存在但方言冲突时返回 `CONFLICT`。状态行展示两条证据，不静默让任一方覆盖。

#### C. 渲染决策：被动学习层

6. 用户在某模块预览中选择并执行数据源后，被动记忆 `(Project, moduleKey) → datasource UUID`。这是自动学习，不要求用户进设置配置；数据源重命名后 UUID 仍可解析，删除后标记失效。

#### D. 渲染决策：保守兜底层

7. 无 configured scope/provider 时，若当前项目只有一个受支持数据源，或全部候选数据源都属于同一插件方言，可确定方言；同方言多库只给 `CONSENSUS`，不假装知道唯一 schema。
8. 项目级 `AgentUrlRegistry.lastConnectionUrl` 仅在项目不存在多模块/多方言冲突，或能证明会话属于当前模块时采用；否则只作诊断证据。
9. 所有证据缺失时为 `UNAVAILABLE`。渲染边界可暂用 MySQL 保持兼容，但 UI 必须显示“未验证，兼容回退 MySQL”，执行 DML/EXPLAIN 前要求目标方言验证。

#### E. 执行目标校验（不参与既有渲染投票）

10. 本次执行选择的数据源/已绑定活控制台是**目标事实**，由 `ExecutionDialectCheck` 与当前 `DialectDecision` 比较。校验落点必须位于 `DataSourceSelector` 确定实际 Runner/活控制台后、提交前；现有 `SqlExecutor.execute(dataSource=…)` 并未使用该参数，面板侧提前比较上次使用数据源不能充当闸门：
    - 自动模式且目标方言不同：以目标数据源重新求一次自动决策，重渲染、更新临时文件映射、重建 PSI/Runner，用户确认新 SQL 后再提交；
    - 手动模式且目标方言不同：返回 `MANUAL_CONFLICT`，不得静默覆盖用户手动方言，也不得直接执行；提示改方言或换数据源；
    - 目标方言未知：SELECT 可提示后继续，DML/EXPLAIN 按安全策略要求显式确认或阻断。

### 5.4 模块绑定的角色调整

模块 → 数据源 UUID 绑定仍保留，但从默认工作流降为冲突修复工具：

- IDEA Resolution Scope 已给出唯一/一致结论时，不要求再配插件绑定。
- 用户首次在某模块执行时可自动学习绑定；设置页只提供查看、修正和清除。
- 无 SQL injection、无 IDEA scope、项目多方言且从未执行过时，才提示用户选择一次。
- 不按模块名、数据源名、host 或数据库名模糊匹配。

### 5.5 临时预览/执行 SQL 文件

废除“非 MySQL 一律覆盖为 MySQL”的行为：

1. 根据 `DialectDecision.platformLanguageId` 在 `SqlDialectMappings.getAvailableValues()` 中找平台 dialect。
2. 将结果映射到插件自有的 preview/exec 文件或目录，绝不修改业务源文件映射。
3. 若决策直接来自源文件 IDEA dialect，优先复用同一 `SqlLanguageDialect` 实例/ID。
4. 平台没有对应 dialect 时使用 Generic SQL，并保留插件自己的渲染方言；状态行说明“渲染方言已确定，IDE PSI 方言不可用”。
5. 执行目标发生变化时，更新映射、重建 PSI/Runner，再提交 SQL，避免 A 方言文本挂在 B 方言 console model 上。

### 5.6 UI

- 文案从“智能推断（跟随数据源）”改为“自动识别（IDEA 上下文与运行时）”。
- 状态行展示：结果、来源、作用域、置信度和冲突原因，例如：
  - `PostgreSQL · IDEA 解析作用域 · order-service`；
  - `MySQL · 日志 JDBC URL`；
  - `PostgreSQL · 2 个数据源方言一致`；
  - `冲突：IDEA 方言=PostgreSQL，解析作用域数据源=MySQL`。
- 多项目全局设置页不再读取第一个打开项目；只说明全局默认模式。项目/预览面板展示当前上下文实况。
- 提供“查看证据”折叠区和“重新检测”；模块绑定入口只在歧义/冲突时突出。

## 六、跨版本适配与门禁

新增 `IdeaSqlContextBridge`，所有 DatabaseTools 内部入口集中反射：

```text
resolveDataSources(project, sqlPsiFile)
resolveDialect(project, virtualFile)
findPlatformDialect(project, languageId)
mapOwnedFile(project, ownedVirtualFile, languageId)
```

实施顺序必须是“签名门禁先落，生产接线后落”：

1. 扩展 `PlatformSignatureChecker`，对本机 243/253/262 核验：
   - `SqlDialectMappings.getInstance/getMapping/getAvailableValues`，以及继承自 `PerFileMappingsBase` 的 public `getConfiguredMapping/getImmediateMapping/setMapping`（检查器须用 `getMethod` 覆盖继承方法，不能只扫 declared methods）；
   - `com.intellij.sql.dialects.SqlDataSourceMappings.getInstance/getDataSources/getProvidedDataSources`；
   - `SqlResolveMappings.getInstance` 及继承的 `getMapping/getConfiguredMapping`；
   - `LocalDataSource.getUniqueId/getConnectionConfig`，并覆盖 253 `isGlobal()` 与 262 `getStorageScope()==DSSS_APPLICATION` 的 ABI 分流。
2. 262 已证实 `SqlResolveMappings`，但生产优先调用返回数据源集合的 `SqlDataSourceMappings`；仅在该入口不可用时，才把 `SqlResolveMappings` 作为诊断/降级桥。
3. 任一版本入口缺失：返回 `UNAVAILABLE` 并继续下一证据层，不能类加载失败导致整个插件不可用。
4. Community/DatabaseTools 禁用时同样降级，手动模式与纯 PSI 预览仍可用。

## 七、剩余待验证前提 V178

| 编号 | 待验证项 | 成功判据 | 失败时降级 |
|---|---|---|---|
| V178-1 | 253/262 的 `SqlDataSourceMappings` 精确签名与返回元素类型 | 跨版本检查器三目标均解析成功，真实 SQL PSI 返回与 IDEA 设置页一致的候选集合 | 该版本只读 `SqlDialectMappings`，解析作用域证据记不可用 |
| V178-2 | Java/Kotlin 字符串和 Mapper XML 的 injected SQL PSI 覆盖率，以及 243/253 的 `SqlDialectMappings` 对 `VirtualFileWindow`/original-file 继承；同路径在多个模块存在时的来源歧义 | 仅在真实注入在场时取得 SQL PsiFile；三版本 scope/dialect 与编辑器一致；同相对路径命中多个模块不取首个 | 不伪造 injection；宿主文件和模块均无法唯一确定时不借当前跳转路径猜方言，改走明确绑定或证据不足 |
| V178-3 | 数据源对象到稳定 UUID、URL、平台 DBMS/dialect 的 243/253/262 取值 | 重命名 UUID 不变、改 URL 可感知、删除后绑定失效 | UUID 不可用时不持久学习，仅保留本次会话精确证据 |
| V178-4 | 执行目标选择后能在提交前安全重渲染/重建 PSI 与 Runner | 方言变化后预览文本、IDE mapping、Runner、console model 四者一致 | 已知冲突时阻止执行并要求手动方言/换数据源 |
| V178-5 | mapping/scope 变更后的低成本刷新 | 短 TTL、PSI/VFS 变化、设置页重开和显式刷新均能更新结果 | 不引入常驻轮询；保留“重新检测”动作 |

## 八、任务波次

| 波次 | 任务 | 内容 | 验证 |
|---|---|---|---|
| W0 | 跨版本签名门 | 扩展 `PlatformSignatureChecker`，先锁定 243/253/262 方言/解析作用域/数据源身份入口 | 三目标签名绿；临时改错方法名必须红 |
| W1 | 源上下文贯通 | 新增瞬态 `PreviewSourceContext`；Java/XML 等入口传原始 PSI、可得的 SQL PSI 与模块。Kotlin Wrapper 在表/列/参数解析未完成前不冒认；旧历史不含精确 URL，按证据缺失处理，新事件携带 URL 需先过凭据/持久化授权 | 各入口缺失/歧义矩阵；摘掉 SQL PSI 传递，IDE scope 用例必红 |
| W2 | IDEA 上下文桥 | 实现 `IdeaSqlContextBridge`：平台求有效 dialect、datasource scope、ID/URL；DatabaseTools 缺失降级 | 243/253/262 定向测试 + Community 缺类用例 |
| W3 | 统一决策器 | `DialectDecision`、证据融合、冲突/歧义/共识模型；移除 auto 分支的 `firstUrl` 单值决策 | 纯函数表驱动；候选换序结果不变 |
| W4 | 生成链收口 | `DialectRegistry`、`SqlPreviewService`、XML/JPA/批量/CRUD/PSI 方言入口统一消费决策 | 现有方言语法测试 + 新上下文端到端测试 |
| W5 | 临时 PSI 同步 | `DialectPreMapping` 泛化为按决策映射；删除硬编码 MySQL；目标变化时重建 PSI/Runner | PG/Oracle/DM/MySQL 文件语言与渲染一致 |
| W6 | 被动学习与修复绑定 | 执行选择后按模块保存 datasource UUID；设置页仅展示/修正/清除，不要求首用配置 | 模块 A/B 学习隔离、重命名稳定、删除失效 |
| W7 | 状态与安全闸 | 新状态文案、证据面板、多项目全局页纠正、执行/EXPLAIN 目标方言一致性检查 | UI 状态测试 + DML 已知冲突阻断 |
| W8 | 文档与收口 | CHANGELOG、设置说明、已知降级；定向、反向、权威全量与三平台真机走查 | 按三档测试纪律收口 |

## 九、验收矩阵

### 9.1 无插件主动配置

1. 单项目单 PostgreSQL 数据源：默认自动识别 PostgreSQL，无需打开 SQL Visualizer 设置。
2. 多项目同时打开：每个项目读取自己的 IDEA scope/dialect，互不串扰。
3. Maven reactor 中模块 A=MySQL、模块 B=PostgreSQL：若 IDEA 已设置 Resolution Scope，两个模块分别正确生成分页、引用符和 UPSERT。
4. 用户只设置 IDEA SQL Dialect、未设置数据源 scope：按文件/目录/Project/Global 继承结果生成 SQL。
5. 用户只设置 Resolution Scope：唯一数据源或同方言集合可直接确定方言。
6. 用户既未设置 IDEA scope/dialect，但项目只有一个受支持数据源：自动采用唯一方言。
7. 用户首次在歧义模块执行时选择数据源；第二次自动复用模块 UUID，不要求进入设置页。

### 9.2 注入与来源

- Java/Kotlin `// language=SQL`、`@Language("SQL")`、框架自动注入；
- Mapper XML tag/attribute injected SQL；
- 无 injection 的 Wrapper/JPA 链；
- 仅确实保存精确 URL 的新日志；旧历史仅存脱敏角标，精确 URL 缺席须返回 `UNAVAILABLE`/低置信度；
- 来源文件已删除、路径无法恢复、手工 SQL。

每类都要断言证据来源，不只断言最终方言名。

### 9.3 冲突与歧义

- IDEA dialect=PostgreSQL、Resolution Scope 唯一 MySQL：返回 `CONFLICT`，不静默选边。
- Scope 含 MySQL+PostgreSQL：`AMBIGUOUS`，顺序反转结果不变。
- Scope 含两个 PostgreSQL 但 schema 不同：方言确定、数据源仍为集合。
- 日志精确 URL 与项目最近 agent URL 不同：该条日志以精确 URL 为准。
- 执行目标与渲染方言不同：提交前重渲染；无法重渲染则阻断。

### 9.4 兼容与降级

- 手动具体方言行为逐字不变，且不探测 DatabaseTools。
- DatabaseTools 缺失、反射签名漂移、Generic SQL、未知 JDBC 子协议均不崩溃。
- 旧配置 `dialectAuto=true` 自动迁移到新决策器；不自动写入模块绑定。
- 现有 MySQL 单库项目输出保持不变。

## 十、反向验证

1. 临时恢复 `firstUrl`：多模块异方言、候选顺序反转测试必须红。
2. 临时把 generated preview/exec 文件强制映射 MySQL：PostgreSQL/Oracle/DM PSI 一致性测试必须红。
3. 临时绕过 original/injected SQL PSI，改查插件临时文件：IDE scope/dialect 用例必须红。
4. 临时把多方言 scope 取第一项：`AMBIGUOUS` 测试必须红。
5. 临时移除 project identity：双项目隔离测试必须红。
6. 临时改错 243/253/262 任一反射签名：跨版本签名门必须红。
7. 临时让目标方言冲突继续执行：DML 安全闸测试必须红。

## 十一、明确不做

- 第一阶段不扫描 Spring/Quarkus/Micronaut 配置来猜数据源；仓内尚无通用配置探测器，IDEA 原生上下文和运行时事实优先。后续可作为低置信扩展，但必须匹配现有 IDEA 数据源 UUID/URL 后才采用。
- 不从 dialect 名唯一选择 datasource。
- 不修改用户业务源文件的 IDEA dialect/scope 配置。
- 不把 Resolution Scope 的 `ALL_DATA_SOURCES` 默认值误称为用户明确绑定。
- 不要求用户为 `.idea/sql-visualizer/preview` 或 `exec` 私有目录单独配置作用域。
- 不合并/搬运与本功能无关的 dev 大批次；O-136 的执行路由只作为接口协同依赖处理。

## 十二、风险与实施纪律

- `SqlResolveMappings` / `SqlDataSourceMappings` 属 DatabaseTools 实现层，必须反射隔离并纳入跨版本签名门。
- `setMapping` 会触发属性推送和 PSI 重解析，写操作继续只在 EDT，读取放后台/ReadAction；禁止在设置 `apply` 阻塞扫描。
- 收口期间主控不得在权威全量测试后继续修改 `src/main`；若修复触达生产代码必须重跑。
- 子任务只跑定向测试；全部生产改动冻结后统一跑一次 `testShards --no-configuration-cache`。新增平台反射入口属于大改动，最终还须跑 `verifyGate`/`verifyPlugin` 三目标。本轮用户明确禁止启动沙盒真机验收，243/253/262 的 runIde 走查单列「本轮未验证」，不得用 headless 测试冒充完成。

## 十三、实施记录

- 2026-10-16 T-M19/V178 243连接/自然注入补验：真实H2 2.3.232内存库经DatabaseConnectionManager/RemoteStatement执行SELECT42与EXPLAIN，读取真实连接UUID及同源session/console目标H2；Java/Kotlin/XML语言注释均自然得到真实SqlFileImpl/GenericSQL，Java桥AVAILABLE，IntelliLang=true。四项 **4/0/0/0**（UTC20:14:18.425Z）；没有测试injector、ForTest替身、业务配置或凭据。明确日志sessionConnected=false和providers=[]：不冒称console自有连接或非空provider。探针保存既有dev-support目录且不进入默认测试发现，正式验收文档含重放步骤、关键输出与锁定XML。最后相同跨版API适配回归243八例 **8/0/0/0**（UTC20:16:33.950Z）。临时构建配置已还原，仅测试与文档提交，生产冻结未碰。

- 2026-10-16 T-M19/V178 跨版实际fixture续验（scope安全代码，生产零改动）：253.33514.17/JBR21.0.10/精确同版framework同组八例 **8/0/0/0**，UTC19:55:38.633Z；先前混装243framework的Java11Shim初始化失败与253驱动classpath API漂移已按测试前提修正，新旧真实API适配提交 `39ea87e4`。262.10968.63/JBR25.0.4/精确framework原始八例全部startup失败，原因IPGP平铺classloader把product-backend.jar中同名混淆类Z.Z.Z.Z.Z盖过ultimate-plugin.jar扩展实现；一次前置真实ultimate jar对照后 **8/0/0/0**，UTC20:08:09.797Z，XML记录实际版本和class来源。结论严格为显式重排测试classpath下262 fixture通过，非标准IDE装配或GUI通过；原8/8红、两jar/class哈希、方法表、单变量命令保留在既有验收文档跨版节。临时build/buildSrc不提交，未跑全量/verify，不影响主控冻结门禁。

- 2026-10-16 T-M19/V178 scope 安全续验：上条 scope 语义待核项已按非 UI 实测关闭误判，不能转嫁为 UI 待验。真实 PG+MySQL、真实 PG SqlFile 配置 EMPTY/限定 MySQL UUID/显式 ALL 三项均复现错误 RESOLVED/STRONG/IDEA_CONFIGURED_SCOPE，**3/3/0/0**。现有 API 未证明候选按配置 scope 缩窄，故无真实 provider 的明确配置降为 UNAVAILABLE，阻止 effective/learned/project 回升冒充强证据，专用双语状态明确建议手动方言或明确执行目标；默认未配置 ALL、手动/captured 和原 provider 优先不变。撤修三项再次 **3/3/0/0**，恢复真实平台 **8/0/0/0**，四类定向回归合计 **18/0/0/0**；无跳过。新增三版 scope mapping 类型及 provider 精确签名均 RESULT OK。实际243 fixture的 sql provider EP清单为空，未伪造非空provider；该非空运行分支只保留既有优先级回归，不冒称实测。此修复是诚实关闭不可靠证据层，仍不宣称显式 scope 候选解析已支持；证据路径见既有正式验收文档续验节。

- 2026-10-16 T-M19/V178 非 UI 补验（独立 `t-m19-v178-nonui`，基线 `a434dc2c`）：不启动 runIde，不读 demo 私有数据源配置，使用既有 testDbDriverPipeline 的真实 DatabaseTools 无连接 fixture。实际发现并修复 `dbms=DM` 无平台枚举、sql.dialect InstanceBean 缺 `public static INSTANCE` 两项产品初始化错误；保留 DM 语言 ID，UNKNOWN 仅 Generic/SQL92 兼容，不能称原生达梦支持。真实非空候选另击中桥的 RawConnectionConfig 错包名（三版实际 owner 均 `com.intellij.database.model`），一行最小修复；撤修单项 **1/1/0/0**，diagnostic=ClassNotFoundException，已恢复。第六轮 fresh XML 为平台 **5/0/0/0**、模块 **5/0/0/0**；同命令 testFast 受既有隐式任务依赖校验未入场，不报整个命令绿。目录映射/覆盖/撤销后 SQL PSI 重解析、真实 UUID 重命名/改 URL/删除、断开态 session/console API、模块销毁已有 243 平台证据。243 的 getDataSourcesForResolve 实测不按 SqlResolveMappings.EMPTY 清候选，不能将 configuredScopePresent 当作 scope 已缩窄；该产品语义另列待核，不擅改决策顺序。三版精确候选 API 检查 243/253/262 均进程0，非三版服务运行通过。最终恢复复测及证据限制见 `docs/验收文档/20261016-T-M19-V178-真实DatabaseTools非UI补验-验收文档.md`。本条只回填本工作树，无全量/verify/UI/真库 Runner 结论。

- 2026-10-14 12:52：初版立项，确认当前第一项目/第一数据源推断不适用于多项目多模块。
- 2026-10-14 13:44：按用户“真智能、无需主动配置插件方言”的目标完成第二轮调研并重写方案。证据来源为 JetBrains IDEA 2026.2 官方文档、本机 IDEA 2026.2.3、缓存中的 2026.2.1/2025.3.5/2024.3.7.1 DatabaseTools 二进制反编译及当前仓库调用链。定案为“手动显式值/当前条目的精确事实 → 实际 SQL PSI 的 configured Resolution Scope + IDEA 有效 Dialect → 模块被动学习 → 项目候选共识”的渲染证据融合模式，并把执行目标单列为提交前一致性校验；默认 `ALL_DATA_SOURCES` 明确降为候选目录，不冒充用户绑定；模块绑定降为冲突修复工具；本轮未修改生产代码、未运行构建测试、未提交。
- 2026-10-15 实施前提勘误：现有 `AgentEventAdapter` 将 URL 归约为脱敏库标，`LogSqlEntry`/自动保存/历史读回未保留完整 URL，旧历史不可能给 `EXACT`；同一路径命中多个模块时 `SourcePathResolver` 取首个也不能作为模块证据。新 URL 的凭据过滤与持久化格式属于另行授权事项，本轮先按缺失证据降级，不伪造精确来源。执行目标需在实际 Runner/活控制台确定后校验，`SqlExecutor.execute` 的 dataSource 参数当前未参与选择。
- 2026-10-15 O-145 日志/历史来源证据补缝（隔离分支 `t-m19-log-source`）：本轮先锁定三处真实预览入口的危险升级形态，不把库标当精确 JDBC URL、不拿项目最近 agent URL 充当本条连接、不把运行时源码当注入 SQL PSI，也不将路径首命中冒充唯一来源。反查入口既有 `sourceLocation=entry.sourceFile` 仅作日志记录的源码位置展示，保留以免新 agent 事件失去定位；它不是当前源码配置，更不是历史执行时的 IDE scope。历史读回没有精确 URL，实时条目也只存脱敏库标；`SourcePathResolver.resolveVirtualFiles` 和 `ModuleIdentityResolver.resolve` 已有多候选状态，但当前三入口尚未安全接入瞬态来源上下文。暂不注入 `moduleKey`，避免当前源文件的模块学习绑定反向污染旧执行；未来若接入，必须先验证唯一文件/唯一模块，区分当前文件状态与历史执行事实，并对 SQL PSI 另取真实注入对象。本项只做测试/守卫和边界登记，不宣称日志预览已获得精确 URL 或自动模块方言。真实 `LogEntryPreviewOpener.open` 临时加入 `capturedJdbcUrl = entry.databaseName`，新守卫 1 红；移除后 O-145 守卫 2/0/0，既有 SourcePathResolver/ModuleIdentityResolver 歧义类 8/0/0，合计定向 10/0/0；细化首命中路径样本后守卫再验 2/0/0。首次反向跑整类时还见独立的既有模态弹窗白名单 1 红（ProjectPluginSettingsConfigurable/DataSourceSelector），不属于本写集，不将整类报绿。首次 Gradle 启动遗漏机器级 gate，后两轮由 `gradle-gate.ps1` 获取 slot=0 后执行；独立 sandbox 使用完已撤销。未跑全量/IDE/真库，也未上传 SQL。
- 2026-10-15 W0 阶段性落地：`PlatformSignatureChecker` 新增 DatabaseTools SQL dialect/scope/dataSource 三组反射签名，补上 262 独立类加载器缺失的 `libraries-misc-plugin` XStream jar。`testExternal --tests PlatformSignatureCrossVersionTest --no-configuration-cache` **1/0/0**，打印覆盖 **243/253/262**；临时将 `getProvidedDataSources` 改成不存在的方法名，三目标均报 `NoSuchMethodException` 且测试 1 红，恢复后复绿。仍未验证 `DbDataSource` 到稳定 URL/UUID 的桥、243/253 注入 SQL PSI 的继承语义以及实际 Runner 拦截点；本条不是 W1~W8 完成声明。
- 2026-10-15 W3 决策核先行：新增纯 `DialectDecisionEngine`，手动值、实时事件精确事实、显式 IDEA scope/dialect、模块已学绑定、项目候选共识按层决策；多个异方言候选恒歧义，同方言保留全部数据源 ID，Generic/旧历史无精确证据时只标兼容 MySQL 回退；目标方言比较单列纯 `TargetCheck`，决策结果不保存 JDBC URL。`testFast --tests DialectDecisionEngineTest` **11/0/0**；临时把多候选取首项后恰 2 例红，恢复后复绿。**在本条记录时，此核尚未接 `DialectRegistry.effective`、原始 SQL PSI、临时文件映射和实际 Runner；当时生产推断仍取首个 URL。后续项目候选接线见下条，不把本条纯函数测试冒充生产链已闭环。**
- 2026-10-15 W4 两条已知断链修复：`SqlPreviewService.previewDerivedQuery` 和 `previewCriteriaQuery` 已求得服务所属项目方言，但调用 `SqlGenerator.generate(statement)` 又回落默认 MySQL；现把同一 `dialect` 显式传到最终生成点。手动 Oracle + Limit/Pageable 两条端到端 PSI 定向测试改前 **25 例中 2 红**（产物 `LIMIT`），改后 **25/0/0**（`FETCH FIRST` / `OFFSET ... FETCH NEXT`）。其余批量、JPA CRUD、原始 SQL PSI 与跨模块自动推断仍待接线，本条只证明两条 JPA 管道不再隐性回退。
- 2026-10-15 W4 补 JPA CRUD：Repository 声明/调用、EntityManager、HibernateTemplate 四条当前方言读取改用实际 `PsiElement.project`，`existsById` 的首行截断从硬编码 `LIMIT 1` 改走 `Dialect.renderLimit`；原生 MySQL 输出保持原样，仅语义说明注释改为方言中性。项目级 Oracle 覆盖全局 auto 的端到端 PSI 用例改前 23 例中 1 红（错误 `LIMIT 1`），改后 **23/0/0**，本条不代表其他批量与 Wrapper 路径已对齐。
- 2026-10-15 W4 补实体类右键生成：`GenerateCrudSqlFromEntityAction.entitySpec` 已持有实体 `PsiClass.project`，原 `toCrudSpec()` 无参方言在项目级 Oracle、全局 auto=true 时回退 MySQL；现显式传实体项目。headless 真 PSI 定向改前 5 例中 1 红（期望 Oracle 实得 MySQL），改后 **5/0/0**。仅此实体入口，批量模板等其余无项目调用仍在册。
- 2026-10-15 W4 批量模板补位：`BatchOperationConverter` 以实际 `PsiMethodCallExpression.project` 统一模型构建、CRUD 主键/列引用、Map 等值条件与 upsert 的方言读取，去掉 `renderIdCondition` 的无项目默认值。项目级 PostgreSQL + 全局 auto 的真实 PSI `saveOrUpdate` 定向改前 **13 例中 1 红**（错误 `ON DUPLICATE KEY UPDATE`），改后 **13/0/0**（`ON CONFLICT`）；仍未证明基于 IDEA 原始 SQL PSI 的多模块自动判定。
- 2026-10-15 W3 项目候选入口：`DataSourceUrlProbe.candidateDialects` 按项目枚举候选方言，5 秒弱项目缓存只存会话内候选，不存 URL 或凭据；`DialectRegistry.decision(project)` 使用纯决策核，显式手动模式不触探测、异方言集合返回 `AMBIGUOUS`、同方言多库保留全体身份，仅能证明单模块且无候选时参考最近 agent URL。`DialectProbeCacheTest` **8/0/0**＋纯决策核 **11/0/0**；临时恢复「取首个候选」后 8 例中恰 2 红，还原复绿。三代 `ModuleManager.getInstance/getModules` 入签名门并在 243/253/262 检查 **1/0/0**。**候选 ID 目前只是会话内位置键，不可持久化为数据源 UUID；渲染 `effective()` 在未知/冲突态仍用兼容 MySQL。本条提交时 UI 尚未显式标来源，也无执行目标闸；后续全局设置页仅作局部解释，不得宣称真智能闭环。**
- 2026-10-15 W3 冲突反例（`75597d66`）：单模块最近 agent 方言为 PostgreSQL、项目唯一候选为 MySQL 时，纯决策核不再静默选任一方，返回 `CONFLICT` 并保留两份证据。改前 **12 例中 1 红**，改后 **12/0/0**。这是决策状态修正，实际执行仍无目标闸。
- 2026-10-15 W1/W2/W4 文件配置小切片（`cb967158`）：派生查询取得原始 Repository 方法所在的 VirtualFile，在自动模式下读取该文件 `SqlDialectMappings.getConfiguredMapping` 的明确方言 ID；已知 ID 映射为插件方言，Generic/缺失不冒充证据，手动模式不触探测。Oracle 来源传到最终生成器，`LIMIT 3` 改为 `FETCH FIRST 3 ROWS ONLY`。真实 PSI 方法夹具用可注入方言 ID 替代缺席的 DatabaseTools 运行期文件映射：改前 **16 例中 1 红**、接线后 **18/0/0**、暂摘接线再红并恢复。**此测试不证明真实 IDEA 文件映射或注入 SQL PSI 的 Resolution Scope 可用**，W2 平台行为与 W1 源上下文剩余链仍待验。
- 2026-10-15 W4 预览方言载体（`27ce7016`、`c1e4f51e`）：无动态条件的 JPA 预览原 `previewModel=null`，虽然 SQL 已按 Oracle 生成，面板、EXPLAIN、DML 试算及导出仍把它当 MySQL；现为非持久化 `PreviewResult.renderedDialect` 保留渲染实际方言，面板读 `effectiveDialect`，同时在有项目上下文的 XML 预览保留方言且分页复用同一实例。JPA/面板两类合计 **36/0/0**，暂摘结果赋值及面板读取时两例各红，恢复后复绿；XML 项目/既有/XML 分页三类 **31/0/0**，摘结果赋值 1 红，恢复后复绿。目标数据源仍须在最终 Runner 确定后检查，本条只修「已经生成的方言在后续动作丢失」。
- 2026-10-15 W7 设置状态局部纠偏（`1df2ee8a`）：全局设置页不再拿 `openProjects.firstOrNull` 与首条 URL 冒充所有项目；只在唯一项目时取 `DialectRegistry.decision`，对多项目、无项目、歧义、冲突、无证据分别显示诚实状态与兼容回退，保留自动/手动模式差别。源码守卫改前 **7 例中 1 红**，状态纯逻辑+守卫+双语 bundle **15/0/0**，设置面板绑定夹具另 **15/0/0**；暂去多项目分支后纯逻辑 3 例中 1 红，恢复复绿。**这里只是全局设置状态行，不是每条预览的来源/置信度证据面板，也未拦截执行；无沙盒 GUI 视觉读数。**
- 2026-10-15 W7 旧键收口（`9fe1fdd6`）：首次统一门禁在 `:testExternal` 找到不再使用的 `settings.dialect.inferred.none`，五片尚未启动，故不是全量绿；连带核出旧父键 `settings.dialect.inferred` 被新子键的字符串前缀掩盖，原幽灵键守卫对此会假绿。双语删掉两键，加精确键测试；暂放回父键后 `SettingsTruthTest` 9 例中 1 红，还原后 **9/0/0**，原设置幽灵键守卫 **4/0/0**、bundle 奇偶守卫 **5/0/0**。新冻结点完整测试仍要另跑，详见第十九轮总计划 §七。
- 2026-10-15 W7 agent URL 守卫迁移（`1e2e9a8a`）：二轮全量在 shard3 命中旧 `AgentUrlDecoupleGuardTest` 的“设置页必须直接出现 `AgentUrlRegistry.lastConnectionUrl`”前提；真实设置页已改走统一 `DialectRegistry.decision`，轻量 URL 仍由决策器读取，`AgentManager` 没有回潮。守卫改为核「设置页决策→同一结果状态文案」和「决策体仍直读轻量持有者」，定向 **3/0/0**；暂摘决策体读点后 **3 例恰 1 红**，还原复绿。此处只修读点守卫与实际调用图一致，不替代最终 Runner 方言冲突验证。
- 2026-10-15 门禁第四轮：可选 DatabaseTools 的 `com.intellij.sql.dialect` 在 243/253/262 本机制品均有 EP 声明，测试 classpath 缺席导致第三轮守卫假阴性，已按精确名豁免（`e99ecb91`）并做暂摘变红。当前冻结代码的五片、`testExternal` 与 agent 统一收口 **7583/0/0/10**，675 个计划测试类均入场；十项跳过含 DatabaseTools 测试缺席、真实 Docker 库默认关、外部 JDBC URL、Windows 符号链接及 ACP 探针。三平台 `verifyGate` 的 IU 243/253/262 落盘结论均为 **Compatible**；但离线模式下 `ml.llm` 三目标与 `mcpServer` 在 243 的可选依赖缺席，不覆盖这些链。本轮没有启动沙盒真机，以上门禁仍不能证明实际 SQL PSI/Resolution Scope、数据库连接、最终 Runner 冲突拦截或真实 IDE 行为。
- 2026-10-15 W5 预览临时 PSI（`05c2462b`）：预览目录不再永久硬编码 MySQL；`PreviewResult.effectiveDialect` 先写入面板目标方言，预热线程在 PSI 创建前按插件方言解析平台 SQL Language，结果晚到时同目录重映射，并以 `VirtualFile + language id` 双条件判幂等，避免早到 MySQL 阻断 Oracle/PG。结果恰在首次映射与 PSI 创建之间到达时，发布预热产物前再做一次幂等同步；平台无对应 Language 时先映射 `GenericSQL` 而非冒充 MySQL（`c903f600`）。源码守卫改前 **21 例中 1 红**，改后 `ExecDialectRoutingTest` **21/0/0**，真实面板夹具 `InjectionGateTest` **18/0/0**。本条只证明映射编排与面板消费，DatabaseTools 缺测试 classpath，真实 PSI 重解析效果仍待非本轮真机验收。
- 2026-10-15 W4 方言载体补齐（`62bf243a`）：普通 CRUD 结果显式保存 `spec.dialect`，批量结果的 `effectiveDialect` 纳入 `BatchModel.dialect`，避免最终 Runner 闸和 W5 临时 PSI 再把 Oracle/PG CRUD 当成未知/MySQL。`CrudSqlGeneratorTest` 改前 **29 例中 1 红**（SELECT_ALL 方言为 null），改后 **29/0/0**；JPA 回归另 **18/0/0**。
- 2026-10-15 W2 只读平台桥（`cc7d2941`）：新增反射隔离 `IdeaSqlContextBridge`，区分 provider、configured resolution、默认 ALL 与不可用；最终候选按稳定 UUID 去重，只在瞬态 raw facts 中读取 JDBC URL，快照不保留 URL；同时分离 effective/configured/immediate dialect，`IdeaSourceDialectProbe` 改消费有效方言。桥与 JPA 源文件路径 **24/0/0**；暂把 configured scope 错置于 provider 之前时桥测试 **6 例恰 1 红**，恢复后桥/路由/CRUD **56/0/0**。实际 SQL PSI 获取与入口上下文接线仍由 W1 基础件继续完成。
- 2026-10-15 W7 目标闸纯模型（`035e76dc`）：新增 `ExecutionDialectGate` 和 session/`JdbcConsole` 精确目标解析，不按 Runner 显示名猜目标；策略钉住同方言放行、手动冲突阻断、自动模式仅在无损且未编辑时请求重渲染、未知 QUERY 需确认、未知 DML/EXPLAIN/TEMPLATE 阻断。定向 **7/0/0**；临时把手动冲突改为 MATCH 后 **7 例中 2 红**，恢复复绿。该提交是中央判定与目标解析基础件，尚不等于所有执行入口已接闸。
- 2026-10-15 W1/W2 XML mapper 纵切（隔离分支 `o145-w1w2-xml-context-tm19`，dev 基线 `91e73576`，提交 `4f4926ff`）：由 Action/gutter 的原始 XmlFile、选定 XmlTag 和有效光标构造 `PreviewSourceContext`；只认真实 SQL PSI，模块身份不走首模块便利 API，owner 内多注入/越界光标不给首个候选，自研 MpSqlMapper 明确拒绝；`DialectRegistry.effective(context)` 给 XML 结果方言与分页共同消费，UI 只保留原有模块键，不驻留来源 PSI。新夹具先因缺 API 编译红；接线后五类定向 **30/0/0**；临时撤来源方言分支 **5 例中 1 红**；恢复并增分页断言后 **31/0/0**。headless 243 用实际 XML PSI 和测试注入、provider 缝分别区分 PG/Oracle；不等于真实 DatabaseTools SqlFile/映射或 runIde GUI 验证。本笔在隔离分支未跑全量或合并；之后代码及文档已由 `458e941d` / `91f6437e` 并入 dev，未推送。
- 2026-10-15 W0/W1 来源基础件（`7c30548b`、`7c930913`、`72d8431c`）：`ActualSqlPsiResolver` 光标精确优先、owner 有界扫描，状态区分唯一/多注入/未提交/DatabaseTools 缺席；`ModuleIdentityResolver` 枚举所有命中模块并用 external-system linked ID/path 构稳定键，日志路径另开多候选口、不改旧导航首命中。三类 headless 定向 **16/0/0**；243/253/262 签名门 **1/0/0**，故意改错 `getLinkedProjectId` 时 1 红，恢复复绿。SQL PSI 实类在普通 243 headless fixture 缺席，算法用注入夹具+类型测试缝，不能当三平台实际行为通过。
- 2026-10-15 W2/W3/W4 决策接线（`89d85e13`、`a4684f2c`、`1cfcd72e`）：把 provider 与 configured scope 分开，default ALL 归项目候选，未知方言不宣称同方言共识，IDE effective dialect 与 scope 异方言返回冲突；注解预览从真实 owner 取瞬态上下文，经对话框传到面板，面板仅留模块稳定键。决策/登记/能力矩阵四类 **29/0/0**，把 default ALL 误置显式 scope 时 6 例中 1 红；注解/SQL PSI/模块/登记四类 **16/0/0**，暂摘实际 resolver 接线后注解 2 例中 1 红，恢复复绿。项目级以外的其余生成入口仍待逐项复核。
- 2026-10-15 W6 模块被动学习（`83ed4106`、`91e73576`）：最终 console 执行闸放行后才写 `(project,moduleKey)→dataSource UUID`，项目设置页显示数量与单独确认清理；已打开面板只留模块键，不留 PsiFile/URL。模块存储/项目设置/注解/执行闸/双语共 **43/0/0**；原模块学习守卫只查类名是假绿，改查 `.remember(moduleKey,dataSourceId)` 实际调用后暂摘可恰 1 红，恢复后复绿。尚需真实数据源重命名/删除的平台夹具验证与其他入口的模块归属。
- 2026-10-15 W7 执行目标双闸与来源可视化（`f81df5c4`、`177bbdb3`、`be14c230`、`9f4de638`、`971f28f8`）：session 已选数据源但未 attach 时判一次，active JdbcConsole 任何提交前再判一次；自动模式只对未手改的模型/批量载体重渲染、停本次提交并让用户复核后二击，手动冲突和未知写/EXPLAIN 阻断；模板、日志转 SQL、DML 试算、adhoc 四旁路分别接闸。注解/XML 结果携不含 URL 的来源/置信/候选 UUID 快照，面板可见行标回退，悬停显示完整证据并修复原能力矩阵 tooltip 空求值。中央闸+CRUD 定向 **16/0/0**、其他四入口独立定向与反向详见提交；证据 UI 四类 **16/0/0**，暂摘可见状态/能力 tooltip 各引 7 例中 1 红；XML 证据三类 **14/0/0**，摘快照 6 例中 1 红。以上仍非用户禁启的真实 IDE/真库验收；本批新增生产代码后须重跑当前 dev 权威全量与三目标 verifier，旧 `e99ecb91` 的 7583/0/0/10 不覆盖。
- 2026-10-15 W6 模块绑定不是写后空转（`7bca9e64`）：多方言项目先返回歧义；同模块学到稳定 UUID 后方言由该数据源 PG 决定，显示名/URL 改变仍按 UUID 重新读取，清绑定或数据源删除后退回歧义并清失效键。`ModuleDataSourceBindingStoreTest` **3/0/0**、登记 **2/0/0**、目标闸 **15/0/0**，合计 **20/0/0**；暂摘 `DialectRegistry` 的 learnedModule 消费后 3 例中 1 红，恢复复绿。headless 以 UUID→对象缝替代 DatabaseTools 存储，不拿它冒充真库。
- 2026-10-15 W2 缺身份证据 fail-closed（`1d2c36c7`）：平台 provider 返回候选却无 `getUniqueId` 时，旧 `mapNotNull` 丢候选使 provider 误降 DEFAULT_ALL，并把其余 PG 虚判唯一；现仍记 provider 来源，无 UUID 项只用本次决策临时键参与投票，失效数据源不再贡献陈旧 URL 方言。tooltip 文案明确候选标识可能是临时键（不能用于模块持久学习）。桥/决策/登记/状态/双语五类 **23/0/0**；缺 UUID 与失效源在修前各 7 例中 1 红，分别暂摘匿名候选与有效性分支亦各 1 红，恢复复绿。真实 DbDataSource 仍需接上无连接对象夹具做三版本运行时取证。
- 2026-10-15 W1/W4 Wrapper 来源纵切（`e5b95883`、`e3a17db5`）：Wrapper 按调用源码文件/唯一模块求一次决策；有真实 SQL PsiFile 才消费 injection scope，无注入时仍按宿主有效 IDEA 方言生成主 SQL、分页 COUNT 与历史指纹，结果同步决策快照并把瞬态上下文交弹窗。独立定向 **59/0/0**；摘掉宿主文件参数时 PostgreSQL 对 MySQL 项目候选的测试恰 1 红，还原复绿。Kotlin 实体表不可求时不冒产 SQL；真实 DatabaseTools 注入与多模块 IDE 行为未验。
- 2026-10-15 W1/W4 JPA/HQL 来源续接（`ad574f3e`、`bdfd9a23`）：Java `@Query`、普通/命名 HQL、Criteria、native、Specification、QBE、Procedure 按定义或调用处真实宿主求不含 URL 的来源决策，不把 JPQL/HQL 字符串当 SqlFile；Criteria 生成与结果快照共用方言，独立七例 **7/0/0**，暂改回项目方言时 Oracle 用例 1 红、恢复再绿。其他几个转换器生成方言中性 SQL，只验证产物内容及载体一致，不能声称方言专有语法已测。跨文件 Java 命名查询 Light 索引为 0，测试用两份真实 PSI 候选钉同名歧义收口 **36/0/0**、首取反向 1 红；平台真实索引回查仍环境受限。
- 2026-10-15 hbm 命名查询双层歧义修复（`29093ef3`，在 `bdfd9a23` XML 定义 owner 接线之后）：`createNamedQuery` 回查过去按文件顺序首取并缓存，同 kind/name 的异文第二份加入后会继续拿旧 SQL 和旧 owner。现对第二定义整体拒绝 SQL，不从查询名字面回落为 HQL；定义 owner 也拒绝多处命中，去掉会陈旧的命名查询正/负缓存。两份 XML 的唯一→新增重复反例覆盖上游返回、owner 和调用点，修前各阶段单红、恢复 HBM 类 **8/0/0**。同名 HQL 与原生 SQL 跨 kind 的命名空间是否共用仍待真引擎证明；无运行证据时不宣称执行等价。
- 2026-10-15 日志与旧历史来源安全界（`c3a084dc`）：三个预览入口不将脱敏库标或项目最近 agent URL 升级为本条精确事实，也不将运行时源码当实际 SQL PSI；保留已有 `sourceLocation` 展示。危险推断注入真实入口后守卫恰 1 红，恢复守卫 **2/0/0**、路径/模块歧义既有类 **8/0/0**。当前旧历史没有精确 URL、实时条目只存脱敏角标，不能为追求覆盖率伪造来源；安全事件级方言事实链仍未实施。
