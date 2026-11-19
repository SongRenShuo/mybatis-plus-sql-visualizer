---
name: pitfall-sql-dialect-mapping-lazy-load-on-edt
description: 插件首帧在 EDT 取 SQL 文件类型高亮器 → 平台 SQL 方言映射「一次性延迟加载」的 VFS 解析被
  SlowOperations 断言（20261028 用户上报）；含四条决定性平台事实、只修首个调用点会挪落点的证据链、正确修法与三条被否决的假修
metadata:
  node_type: memory
  type: project
  originSessionId: session_06e836f98ca891c0b668caad6a51e8fc
originSessionId: sess_current_20261028
---

# SQL 方言映射延迟加载在 EDT 上触发 SlowOperations 断言（20261028）

## 现象

用户真机（2026.2.x Ultimate）项目打开时 `idea.log` 出现
`java.lang.Throwable: Slow operations are prohibited on EDT`，栈顶落点
`cn.song.mybatisplus.visualizer.log.LogToSqlPanel.createResultEditor(LogToSqlPanel.kt:647)`
→ `SqlLogToolWindowFactory.createToolWindowContent:50` → `ToolWindowSetInitializer`。
生产模式只记 SEVERE（功能不断），但归因到本插件；测试基座里同形态是真抛 `AssertionError`。

## 根因链（243 发行版字节码取证，路径见 [[reference-platform-artifact-keymap-lookup]]）

1. 工具窗内容在项目打开时于 EDT 装配；`LogToSqlPanel` 求「原生 SQL 高亮」→
   `SyntaxHighlighterFactory.getSyntaxHighlighter(SQL, project, null)`。
2. `EditorHighlighterFactoryImpl` 按 FileType 建编辑器高亮器时也走同一个静态工厂
   ⇒ **任何** `EditorFactory.createEditor(doc, project, sqlFileType, …)` 都在这条链上（不只是显式调用）。
3. SQL 高亮器要判方言 → `SqlDialectMappings.getMapping` → `PerFileMappingsBase.getMappingInner`，
   **第一句就是 `ensureStateLoaded()`，与 file 是否为 null 无关**。
4. `ensureStateLoaded` 是**一次性延迟加载**：消费 `myDeferredMappings`，逐条
   `VirtualFileManager.findFileByUrl(url)` → `PersistentFSImpl.findChildInfo` → `FSRecordsImpl.update`。
5. 平台把它归为慢操作：`SlowOperations.assertSlowOperationsAreAllowed` 在 EDT 上 `Logger.error`。

## 四条决定性平台事实（`lib/util-8.jar` / `lib/app-client.jar` javap 实证）

- **只在 EDT 断言**：`EDT.isCurrentThreadEdt()` 为假直接放行 ⇒ BGT 上做同样的事零告警，这是唯一清白的处置方向。
- **写动作内放行**：`isSlowOperationAllowed()` 在 `Application.isWriteAccessAllowed()` 且 Registry
  `ide.slow.operations.assertion.write.action`（默认关）时返回 true。**但不要把调用包进写动作**——
  等于用平台豁免盖住问题，还把一次 VFS 状态解析塞进全局写锁。
- **空映射根本不触发**：`loadState` 只在解析出的列表**非空**时保留 deferred，为空则置 null。
  ⇒ 「拿一个没配 per-file 方言映射的项目验证修复」是**空绿**；先红读数必须先建
  `.idea/sqldialects.xml` 里的 `file`/目录条目。
- **同栈签名一个会话只报一条**：`isAlreadyReported()` 按 intern 过的栈去重
  ⇒ 先红与后绿都必须**各起一个新 IDE 会话**，同一会话跑两次不构成对照。

## 只修第一个调用点没用（承重推论）

`SqlLogPanel` 构造链上 `:141 LogToSqlPanel` 之后紧跟 `:143 SqlToJavaPanel`，后者字段初始化即
`SqlInputEditor` → `EditorFactory.createEditor(doc, project, sqlFileType, false)` → 同一条高亮器工厂链。
把第一处改掉，第一次映射查询就落到第二处，SEVERE 只是换个落点。而 SQL FileType 又不能丢：
`plugin.xml` 的 `completion.contributor language="SQL"` 依赖它，丢了补全整条失效。

## 正确修法（计划 T-SqlDialectPrewarm）

1. 能不用平台 SQL 高亮器的地方改**插件自研** `SqlSyntaxHighlighter`（`LexerEditorHighlighter` + 自研 `SqlLexer`，
   预览面板/RecentExecutionsDialog/SqlSuppressManagerDialog 同款先例，仓内注释明写「不依赖 Database 插件」）。
   顺带修掉「未装 Database 插件时结果区退化为纯文本」。
2. 绕不开的原生 SQL 编辑器路径：新增 `SqlDialectMappingPrewarm`，BGT 上
   `ProgressManager.runProcess { ReadAction.compute { SyntaxHighlighterFactory.getSyntaxHighlighter(sqlFileType, project, null) } }`
   + `EmptyProgressIndicator`；单测模式短路、`getStdFileType("SQL")==null` 短路、幂等可重试、失败只 WARN。
3. 时序点＝工具窗工厂在构造面板**之前**做有界等待（≤300ms，超时照常装配）+ 预览面板既有 BGT 预热链首行接线
   （预览装配本来 await 预热产物，顺序天然继承）。

明确否决三条假修：`allowSlowOperations` 逃生舱（243+262 双证伪）、包一层写动作、关 Registry
`ide.slow.operations.assertion`。

## 观感代价（S1 换自研高亮器的真实差异，20261028 逐项核过）

固定 66 词关键词表（`WITH`/`INTERVAL`/`RETURNING`/`REGEXP` 等降为标识符）；`TABLE` token 用
`STATIC_FIELD` 语义槽 ⇒ 表名与别名比原生更显眼；逗号/点/方括号并入 OPERATOR；
`MP_SQL.*` 键当前无设置页入口，回落到 `DefaultLanguageHighlighterColors.*` 通用槽
⇒ **Settings → Color Scheme → SQL 段的自定义不再作用于该区**。行号/折叠/可编辑/布局零变化。
用户要求「零观感变化」时的备选 D3＝只做后台预热、保留原生高亮（断言同样消失，代价是继续依赖 Database 插件）。
补 `SqlLexer.KEYWORDS` 必须同步 `SqlMapperLanguage.KEYWORDS`——`SqlMapperKeywordCoverageGuardTest`
断言前者是后者子集，红线不许改断言绕过。

## 用户可见面（用户追问「是不是只有这一页有区别」时的口径，20261028 逐文件核过）

- **只有「日志转 SQL」tab 的下半区（结果编辑器）配色变**。输入区是纯文本文档、不受影响。
- 「SQL → Java」不受影响：它的输入区虽用 SQL FileType 建编辑器，但高亮取自平台 Java 高亮器，不查方言映射。
- 「SQL 日志」tab 的列表与单击/停靠预览**本来就用**同一套 `MP_SQL.*` 键（`SqlListSyntaxHighlighter`），
  这次是让结果区向它们看齐，不是改它们。
- 预热（S2/S3）无配色/布局变化；项目打开那次的延迟加载**本来就在 EDT 同步做**，只是挪到 BGT，
  净耗时≈0（最坏 300ms 有界等待，正常几毫秒）。这不是「页面差异」，但属可感知项，要主动交代。
- 未装 Database 插件的环境里结果区今天是无高亮纯文本，改后才有色——仍是同一页面，从「无」到「有」。

## 让用户自己验观感的配方（他不让代做时照此给步骤）

- **零成本近似**：日志列表的停靠/单击预览与目标形态共用同一 token→配色映射，拿同一段 SQL 进列表
  对比结果区即可看风格差异（注意预览默认无行号、开软换行，只看颜色）。
- **逐字精确**：只落 S1 一处（`createResultEditor` 里把平台高亮器那段换成
  `LexerEditorHighlighter(SqlSyntaxHighlighter(), globalScheme)`），`./gradlew runIde` 后同文本截图。
- 样本要一次踩满四类差异：`WITH`/`INTERVAL`/`ON DUPLICATE KEY UPDATE`（词表外）、表名+别名、
  逗号点号括号、`?` 占位符。
- 评测前先确认用户是否改过 `Settings → Color Scheme → SQL` 段——改过的话 S1 的落差会被放大，应直接走 D3。

## 自研词法器的两处结构性盲区（补词表解决不了）

`?` 占位符落 `OPERATOR`（原生有独立 parameter 键）；MySQL `#` 行注释与 PostgreSQL `$tag$…$tag$`
美元引用都不识别，会按运算符/标识符拆开。这两类只能接受或走 D3，别想着扩 `KEYWORDS` 就行。

**Why:** 平台这类「一次性延迟加载」不写在任何文档里，只在首次查询时付 VFS 代价；插件首帧装配恰好是
第一个查询方，于是别人的懒加载变成自己的慢操作告警。只按自己的调用点找根因，修完会立刻在下一个入口复现。
**How to apply:** 见到 `Slow operations are prohibited on EDT` 先读整条栈里**平台那一跳**（这里是
`PerFileMappingsBase.ensureStateLoaded`），判断它是否「每会话一次」；然后核三件事——是否只在 EDT 断言、
空数据会不会不触发（空绿）、同链还有几个调用点；最后按「能在 BGT 预热的预热、能不用平台能力的自研」收口。

## 落地与读数（20261028，dev 树 → 已 ff 并入 master）

- 三笔代码/测试：`e3ab9335`（S1 换自研高亮器）／`4d2abc64`（S2 预热助手 + S3 两处接线）／`f062e833`
  （守卫 `SqlDialectEdtSourceScanTest` + 元守卫登记 + 结果区 token 断言 + 预热短路单测）；
  文档两笔 `f96935e5`／`b0175202`。master 以 `--ff-only` 前移（dev 是 master 的祖先，零合并提交）。
- **定向 162/0/0/0**（LogToSqlPanel* 45、SqlToJavaPanel* 83、SqlClickPreview 20、Prewarm 2、新守卫 4、元守卫 8）。
- **守卫反向验证要拆文件取读数**：命中清单是「按文件」出的，正负例写进同一个文件只能证明「文件被判红」，
  证不了「负例那一行没误伤」——正例一个文件、只含 `JavaSyntaxHighlighterFactory` 的负例另一个文件，
  看清单是否只多出正例那个。本次读数：清单 = `[预热助手, 正例探针]`，负例文件不在内；删探针后绿。
- 结果区 token 断言用 `highlighter.createIterator(0).tokenType == "MP_SQL_KEYWORD"` 判别（`SqlClickPreviewTest`
  先例）；「类型是 `LexerEditorHighlighter`」两边都成立，不具判别力。
- 架构文档 `docs/架构设计/01` 里「预览编辑器用平台原生 SQL 语言高亮」是**既存漂移**（源码本来就是自研
  `SqlSyntaxHighlighter`），本次一并订正为「结果区走自研、输入区按 SQL FileType 且由预热覆盖首查」。
- 两处 Kotlin 推断陷阱：①`executeOnPooledThread` 有 `Runnable`/`Callable<T>` 两个重载，lambda 返回值参与
  推断时会歧义并把整段表达式退化成 `Any!` ⇒ 显式写 `Callable { }` / `Runnable { }`；②
  `FileTypeManager.getStdFileType` 在 Kotlin 侧是**非空平台类型**，`val x: FileType = … ?: run{}` 会报
  「elvis 恒取左值」⇒ 不声明类型、让推断从 elvis 走。
- 接线坑：预览预热链的接收者是 `Project?`，`prewarmNow(project)` 要 `project?.let{}`——**不要**写成
  `?: return@executeOnPooledThread`（那会让 `await` 永不完成，降级路径反而变慢）。

**未闭环（引用前先实测）**：①262 沙盒 R1/R2 运行期读数未取——「断言已消失」目前只有代码 + 243 字节码证据；
②观测感拍板（S1 vs D3）；③全量 `testShards` 那次因系统内存耗尽无效（见
[[pitfall-testshards-shards-run-serially]]）。

## T-DialectInferFix 追加：`SqlDialectMappings` 继承与清除的实现级事实（2026-10-29，fernflower 反编译实测）

工具配方：IDE 自带反编译器 `plugins/java-decompiler/lib/java-decompiler.jar`，用 **JBR 21** 跑
`org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler -dgs=1 <in-dir> <out-dir>`（输入可只放挑出来的 .class，
比整包快得多；Java 8 跑不动 2026 平台 class）。

- **`getConfiguredMapping` 不含「全局」**：`PerFileMappingsBase.getMappingInner` 的顺序是
  ① `pusherKey.getPersistentValue(file)`（VFS 上上次 push 的值，文件级残留！）→
  ② `getMappingForHierarchy(file)`（file → 父目录逐个 `getImmediateMapping`）→
  ③ `VirtualFileWindow` 的原文件同样走 ② → ④ `getNotInHierarchy`：**仅当文件在内容根内**才返回映射表 `null` 键（项目级），
  不在内容根/非物理 FS 一律 null。全局方言是另一条链：`SqlDialectMappings.getMapping` = configured ?: `getDefaultMapping`，
  而 `getDefaultMapping` = `SqlDialectImplUtilCore.getDefaultSqlDialect()` = `getConfiguredDefaultSqlDialect() ?: GenericSQL`
  （`getConfiguredDefaultSqlDialect` 读 `PropertiesComponent "DBE.Default.Dialect"`，即设置页「全局 SQL 方言」）。
  ⇒ 要复刻「平台会怎么解析」，必须写「项目级 `getConfiguredMapping` → 全局 `getConfiguredDefaultSqlDialect` → GenericSQL」，
  只读前者会漏全局；只读后者会漏项目级。
- **`setMapping(file, null)` 真的能清除并回退**：`myMappings.remove(file)` → `handleMappingChange([file],[file],false)`：
  清 `SQL_DIALECT_KEY` 持久值 → `PushedFilePropertiesUpdater.pushAll(SqlDialectPusher)` → `reparseFiles([file], includeOpenFiles=false)`。
  随后 `getMapping` 自然回落到项目级/全局。插件清自己目录的旧映射用这条路是安全的（EDT 两段式仍必须，`VcsVFSListener` 断言未变）。
- 用途：[[project-pending-plans]] 的 T-DialectInferFix 的 P-4/P-5 已按此定案；`DialectPreMapping.inheritedLanguageId` / `clearMapping`
  就是这两个结论的直接实现。

相关：[[project-pending-plans]]（计划状态）、[[reference-platform-artifact-keymap-lookup]]（jar 定位与 javap 取法）、
[[pitfall-blocking-call-needs-progress-context]]（裸池线程补进度上下文）、
[[pitfall-guard-expectation-drift-latent-red]]（合并带进别人的既有红）。