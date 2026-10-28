# T-SqlDialectPrewarm：日志工具窗首帧 EDT 慢操作断言收口（SQL 方言映射延迟加载）

**创建时间**：2026-10-28 09:07
**触发源**：用户上报 `java.lang.Throwable: Slow operations are prohibited on EDT`，栈顶落点 `LogToSqlPanel.createResultEditor(LogToSqlPanel.kt:647)` → `SqlLogToolWindowFactory.createToolWindowContent(SqlLogToolWindowFactory.java:50)` → `ToolWindowSetInitializer`（即**项目打开、侧边栏工具窗首次装配**这一拍）
**基线**：master `aa9bb1ac`。根因分析读的是 `fb0a73fd` 的代码；期间并行会话前进一笔 `aa9bb1ac`（T-ConsoleBind，只动 `execute/DataSourceSelectorResults.kt` + 新增 `ExecFileBinding` 与其测试），与本册涉及文件无交集，故本册全部 `文件:行` 取证在新基线上仍然成立（dev 侧同文件与 master 无差异，已 `git diff master...dev` 核实）
**实施基线**：dev 分支（代码面提交 `a56c8e48`＝master `c584a89d` 整支并入后的 dev；其后 dev 上仅追加 docs 合并 `6972a5d1`，本册引用的代码文件在两者间无差异。合并后 `dev..master` = 0；本册在 dev 树上逐条复验行号仍成立，见 §九）
**占号**：本册不占 O/R/D/V 编号（近期并行会话高频占号且已出现 O-150 撞车；合流时统一分配）

## 一、结论先行

这不是「我们的代码慢」，而是**我们把平台一处「延迟加载」的 VFS 工作提前拉到了 EDT 上做**：

1. 结果编辑器为了拿「原生 SQL 高亮」，在 EDT 上调 `SyntaxHighlighterFactory.getSyntaxHighlighter(SQL, …)`；
2. SQL 高亮器要判方言 → `SqlDialectMappings.getMapping` → `PerFileMappingsBase.ensureStateLoaded`；
3. `ensureStateLoaded` 是**一次性延迟加载**：把持久化的「文件→方言」映射逐条 `VirtualFileManager.findFileByUrl` 解析成 `VirtualFile`；
4. 该 VFS 调用内部是 `PersistentFSImpl.findChildInfo → FSRecordsImpl.update`，平台把它归类为慢操作，在 EDT 上无许可即 `Logger.error`。

功能未断（生产模式 `Logger.error` 只记不抛），但每次 IDE 启动（每个项目会话第一条栈签名）都会留下一条**归因到本插件**的 SEVERE，属必须在质量面清掉的噪声；且测试基座里同形态是硬抛 `AssertionError`（平台抬版跑测试即红）。

修法两条腿：**S1 去掉这处本就不必要的平台高亮获取**（改用插件自研 SQL 高亮器，与预览面板同款）；**S2/S3 把剩下那条「绕不开」的原生 SQL 编辑器路径（SQL→Java 输入区）所需的方言映射预热挪到 BGT**，用有界等待保证装配顺序，不做 `allowSlowOperations` 逃生舱。

## 二、根因链（逐跳取证）

| # | 跳点 | 证据（来源＝源码 `文件:行`／243 发行版 jar 的 `javap -p -c`） |
|---|---|---|
| 1 | 项目打开时工具窗内容在 EDT 装配 | 上报栈：`ToolWindowSetInitializer.createAndLayoutToolWindows → ToolWindowManagerImpl.showToolWindowImpl → ToolWindowImpl.createContentIfNeeded → SqlLogToolWindowFactory.createToolWindowContent:50 → new SqlLogPanel:141 → new LogToSqlPanel:56` |
| 2 | 结果编辑器整备时显式取平台 SQL 高亮器 | `LogToSqlPanel.kt:643-650`：`FileTypeManager.getStdFileType("SQL")` → `SyntaxHighlighterFactory.getSyntaxHighlighter(sqlFileType, project, null)` → `LexerEditorHighlighter(...)` |
| 3 | 高亮器工厂按文件类型解析语言 → 方言 | 243 `lib/app-client.jar` `com.intellij.openapi.editor.highlighter.EditorHighlighterFactoryImpl`：按 `FileType` 走 `SyntaxHighlighterFactory.getSyntaxHighlighter(FileType, Project, VirtualFile)`；上报栈中对应 `LanguageFileTypeHighlighterProvider.create → SqlSyntaxHighlighterFactory.getSyntaxHighlighter → SqlLanguageSubstitutor.getSqlLanguageDialect:47` |
| 4 | 方言查询先触发「一次性延迟状态加载」 | 243 `lib/app-client.jar` `com.intellij.lang.PerFileMappingsBase` 字节码：`getMapping → getConfiguredMapping → getMappingInner → ensureStateLoaded`，**`getMappingInner` 第一句就是 `ensureStateLoaded`**，与 file 是否为 null 无关（行号 120/315 取上报栈 262 侧，243 侧以字节码调用序核实） |
| 5 | 延迟加载＝逐条 URL 解析成 VirtualFile | 同 jar `ensureStateLoaded`：消费 `myDeferredMappings`，对每条 `PerFileMappingState.url` 调 `VirtualFileManager.getInstance().findFileByUrl(url)`（`"PROJECT"` 条目跳过）；`loadState` 里**列表为空时把 `myDeferredMappings` 置 null**，即「项目没有任何 per-file 方言映射」时压根不会走 VFS |
| 6 | VFS 解析在 EDT 上被断言为慢操作 | 上报栈 `VirtualFileManagerImpl.findFileByUrl → VfsImplUtil.findFileByPath → VirtualDirectoryImpl.findChild → PersistentFSImpl.findChildInfo → FSRecordsImpl.update:711 → SlowOperations.assertSlowOperationsAreAllowed:114`；243 `lib/util-8.jar` `com.intellij.util.SlowOperations` 字节码：仅当 `EDT.isCurrentThreadEdt()` 且 `isSlowOperationAllowed()==false` 才 `Logger.error`（并带 `isAlreadyReported()` 去重——同栈签名一个会话只报一条） |

补充取证（决定「哪些修法合法」）：`SlowOperations.isSlowOperationAllowed()` 在 243 字节码里为真（允许慢操作）的条件是——① `Registry ide.slow.operations.assertion` 关；② **EDT 处于写动作**且 `Registry ide.slow.operations.assertion.write.action` 关（默认关）；③ 显式 allow 段。这解释了为什么「包一层写动作」也能消警告，但见 §四「明确不采用」。

## 三、影响面：只改 `LogToSqlPanel` 一处不够

| 入口 | 触发方式 | 本次是否处理 | 依据 |
|---|---|---|---|
| `LogToSqlPanel.createResultEditor` | **显式**取 SQL 高亮器；项目打开时本次上报的直接原因 | 是（S1） | `LogToSqlPanel.kt:643-650` |
| `SqlInputEditor.createEditor`（= SQL→Java tab 输入区） | **隐式**：`EditorFactory.createEditor(doc, project, sqlFileType, false)` 内部按 FileType 建高亮器，走同一条 `SyntaxHighlighterFactory` 链 | 是（S2/S3 预热） | `SqlInputEditor.kt:53`（字段初始化即建编辑器）、`:65-80`；`SqlLogPanel.kt:143` 在 `LogToSqlPanel`(:141) 之后立刻构造 `SqlToJavaPanel` |
| 预览面板编辑器装配 | 隐式：先按 SQL FileType 建编辑器（`SqlPreviewPanelEditorLifecycle.kt:626-627`），随后才换成自研高亮器（`:649-650`）——换之前平台那次解析已经发生 | 是（S3 预热覆盖） | 同上 |
| 预览/历史的 Diff 窗口、回滚对话框 | `DiffContentFactory.createEditable(..., sqlFileType)` / `getStdFileType("SQL")`，均为**用户动作**触发 | 否（预热后天然覆盖） | `SqlPreviewPanelSqlView.kt:536-538`、`HistorySqlLogPanel.kt:979-983`、`RollbackSqlDialog.kt:1035` |
| `SqlToJavaPanel` 结果区 / `JavaCodePreviewDialog` | 用 `JavaSyntaxHighlighterFactory`，Java 无 per-file 映射状态 | 否（安全，且是守卫的**负例对照**） | `SqlToJavaPanel.kt:1743`、`JavaCodePreviewDialog.kt:54` |

关键推论：`SqlLogPanel.kt:141` 与 `:143` 是**同一构造链上的先后两跳**。S1 只消掉第一跳，第一次映射查询会落到 `SqlToJavaPanel` 的 `SqlInputEditor` 上——所以「只改一行」等于把同一条 SEVERE 挪个落点。这条推论必须按 §六 R2 的中间态实测证伪，不接受纸面结论。

代价面（顺带收益）：`plugin.xml:96` 把 `com.intellij.database` 声明为 **optional**。未装 Database 的 IDE 里 `getStdFileType("SQL")` 为 null，当前结果编辑器直接退化成纯文本、无高亮；S1 换自研高亮器后该配置下也有一致配色。

## 四、处置方案

### S1（必修，根因直指、单文件可回滚）：结果编辑器改用自研 SQL 高亮器

- 改点：`LogToSqlPanel.createResultEditor`（`LogToSqlPanel.kt:638-661`）。删除 `getStdFileType("SQL")` + `SyntaxHighlighterFactory.getSyntaxHighlighter(...)` 整段（`:643-651`），改为：

```kotlin
highlighter = LexerEditorHighlighter(
    cn.song.mybatisplus.visualizer.ui.highlight.SqlSyntaxHighlighter(),
    EditorColorsManager.getInstance().globalScheme,
)
```

- 同款先例（照抄，不要自创）：`SqlPreviewPanelEditorLifecycle.kt:649-650`（预览编辑器，KDoc 明写「不依赖 Database 插件」）、`RecentExecutionsDialog.kt:200`、`SqlSuppressManagerDialog.kt:273`。
- 同步改 `LogToSqlPanel` 类 KDoc：第 45 行「下：SQL 结果区（可编辑 + SQL 词法高亮）」与第 48 行「转换是 EDT 纯函数」相邻段需补一句「高亮走自研 SqlLexer/SqlSyntaxHighlighter，不查平台方言映射」；不要保留「原生 SQL 高亮器」这类已失效注释（`:642`）。
- 测试缝：新增 `internal fun resultEditorHighlighterForTest(): EditorHighlighter?`（`(resultEditor as? EditorEx)?.highlighter`），供定向测试断言类型。

### S2（必修，新增文件）：`SqlDialectMappingPrewarm` 预热助手

新文件 `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlDialectMappingPrewarm.kt`（消费方是 `log` 包的工厂；包内可见性收紧为 `internal` 亦可）。

契约（实现必须逐条满足）：

1. **单测模式直接跳过**：`ApplicationManager.getApplication().isUnitTestMode` → 返回 false（测试基座无 Database 插件、平台断言在测试里是硬抛，不允许预热流程把测试搞红）。
2. **能力探测**：`FileTypeManager.getInstance().getStdFileType("SQL") == null`（社区版/未装 Database）→ 返回 false，不派发线程。
3. **BGT + 读动作 + 进度上下文**：`executeOnPooledThread { ProgressManager.getInstance().runProcess({ ReadAction.compute<Unit, Throwable> { SyntaxHighlighterFactory.getSyntaxHighlighter(sqlFileType, project, null); Unit } }, EmptyProgressIndicator()) }`。理由：VFS 解析属平台阻塞面，裸池线程按仓内定例补 `EmptyProgressIndicator`；`ReadAction.compute` 在 262 标 deprecated，按 `T-M52` 先例加 `@Suppress("DEPRECATION")`。
4. **幂等且可重试**：按 project 记「已预热」，成功才置位；失败不置位（下次还能补）。**不得强引用 Project**（不要用普通 `HashMap<Project, …>`，用 `project.putUserData(Key, true)` 或弱键集合）。
5. **不抛**：整任务 `try/catch (Throwable)`，失败 `LOG.warn("sql dialect mapping prewarm failed: …")` 后正常结束——预热是优化，不允许阻断工具窗装配（JRebel/agent 场景尤其）。
6. **有界等待 API**：`prewarmAndAwait(project, timeoutMs = 300L): Boolean`——`Future.get(timeout)`；超时/中断/异常返回 false 并 WARN，调用方照原逻辑继续。
7. **日志**：成功/失败各一行带耗时（ms），供 P-3/P-6 取证；不打 project 路径以外的用户数据。

### S3（必修，接线下单）：两个确定性时序点

1. `SqlLogToolWindowFactory.createToolWindowContent`（`SqlLogToolWindowFactory.java:39-44`）：在 `new SqlLogPanel(...)` **之前**调用 `SqlDialectMappingPrewarm.prewarmAndAwait(project)`，注释写清「顺序是承重墙：`SqlLogPanel` 构造链上会创建 SQL 文件类型编辑器（`SqlInputEditor`），首查方言映射若落 EDT 即触发平台慢操作断言」。Java 调 Kotlin object 用 `INSTANCE`。
   - 不采纳「在 `SqlLogPanel` 字段声明序里插一行预热」：正确性挂在字段顺序上，后续有人在上方插字段即静默失效。
2. `SqlPreviewPanelEditorLifecycle.prewarmPreviewTempFile`（`:48` 起的 BGT 任务体首行）：调 `SqlDialectMappingPrewarm.prewarm(project)`（fire-and-forget 即可）。理由：日志工具窗未被布局加载的项目里，第一条 SQL 编辑器是预览面板；预览装配本身要 `await` 预热产物（`PrewarmAwait`），把映射预热排在同一任务的前面即可继承这个顺序，不必再加等待。

### S4（必修，守卫）：源码静检 + 自证语料 + 元守卫登记

新测试类 `src/test/kotlin/cn/song/mybatisplus/visualizer/convention/SqlDialectEdtSourceScanTest.kt`，继承 `BannedApiScanSupport`（复用剥注释与全量快照）：

- 判定函数 `syntaxHighlighterFactorySites(): List<String>`：在 `mainSources()`（已剥注释）里匹配**平台**工厂调用 `(?<![\w.])SyntaxHighlighterFactory\.getSyntaxHighlighter\(`（含 `com.intellij.openapi.fileTypes.` 全限定写法），期望 0 处。
- 自证语料（必须同文件内，元守卫按函数体引用判定）：正例（合成源码含裸调用与全限定调用，各须 1 命中）；**负例对照** `JavaSyntaxHighlighterFactory.getSyntaxHighlighter(` 与 `SqlMapperSyntaxHighlighterFactory`（本仓 EP 类声明）须 0 命中——没有这条负例，守卫会靠误伤换绿灯。
- 在 `GuardSelfTestCoverageMetaGuardTest.GUARD_CLASSES` 登记 `GuardEntry("convention/SqlDialectEdtSourceScanTest.kt", listOf(DecisionFn("syntaxHighlighterFactorySites", FnKind.VIOLATIONS)), note)`；不写 `exempt`。

### S5（必修，行为测试）：面板与预热助手

- `LogToSqlPanelUiTest` 增例：结果编辑器 `highlighter is LexerEditorHighlighter`；且 `SqlSyntaxHighlighter().getTokenHighlights(KEYWORD)` 非空（证明换的是能上色的自研器，不是空器）。
- 新 `SqlDialectMappingPrewarmTest`：单测模式短路（返回 false、不抛、链路总耗时 < 上限）；同 project 连续两次调用不重复派发；`getStdFileType("SQL")` 为 null 的分支不抛。
- 既有 `LogToSqlPanelTest`/`LogToSqlPanelExecuteTest`/`SqlToJavaPanel*` 必须保持全绿（S1 不动面板 API）。

### S1 的观感差异与「零观感变化」备选（D3）

用户明确关心「改完 UI 上有没有区别」，逐项列清（token 级，均已读码核实：`SqlLexer.KEYWORDS` 现为 66 词固定表；`SqlTokenTypes` 只有 KEYWORD/IDENTIFIER/TABLE/STRING/NUMBER/COMMENT/OPERATOR/PAREN 八类）：

| 维度 | 今天（原生 SQL 文件类型高亮） | 改后（自研 SqlSyntaxHighlighter） |
|---|---|---|
| 关键词集 | 按方言词法器，词集即该方言全集 | 固定 66 词；表外词降为标识符——`WITH` 不在表内，`INTERVAL`/`RETURNING`/`REGEXP`/`TRUNCATE`/`EXPLAIN`/`REPLACE` 等同样不覆盖 |
| 表名 / 表别名 | 词法层不区分，按标识符着色 | 独立成色（`TABLE` → `STATIC_FIELD` 语义槽），`FROM`/`JOIN`/`INTO`/`UPDATE`/`TABLE` 后按启发式命中，连别名一起 |
| 逗号 / 点 / 方括号 / 花括号 | 各自独立配色键 | 无独立键，落 `OPERATOR` |
| 配色来源 | Settings → Editor → Color Scheme → **SQL 段**的键（keyword/string/number/comment/parentheses…） | `MP_SQL.*` 键（当前无设置页入口）→ 回落 `DefaultLanguageHighlighterColors.*` 通用语义槽；**SQL 段的自定义不再作用于该区** |
| SQL 配色页的语义键（table/column/schema/alias/parameter） | 结果区是无 PSI 的裸文档，只有词法层键生效，这些语义键本来就不参与 | 同左（不新增损失） |
| 行号 / 折叠 / 软换行 / 可编辑 / 复制 / 占位提示 / 布局 | — | 完全不变 |

结论：改后与「预览面板、日志单击预览浮层」配色统一（同一套键），但**不是像素级零变化**：关键字覆盖面变窄、表名与别名变显眼、逗号点号并入运算符、SQL 配色段自定义失效。

决策口径：

- 默认按 S1 走（去掉不必要的平台调用，同时去掉 Database 插件依赖）；落地前必须在沙盒把结果区与预览区**并排截图**（P-5），由用户确认观感可接受。
- 若用户要求**零观感变化**，切备选 D3：撤回 S1，只做 S2/S3。断言同样消失（首查已挪到 BGT 预热），代价是结果区继续依赖 Database 插件（未装时仍无高亮）并保留那次不必要的平台高亮获取。D3 不改本册其余任何步骤。
- 可选 S1b（与 D3 互斥）：把常用缺失关键字补进 `SqlLexer.KEYWORDS`。注意 `SqlMapperKeywordCoverageGuardTest` 断言 `SqlLexer.KEYWORDS ⊆ SqlMapperLanguage.KEYWORDS`，补词必须同步 `SqlMapperLanguage.KEYWORDS`——这条红线要遵守，不允许为了过守卫改断言。

## 五、待验证前提（实施者先证伪再动手，证伪即回写本册再实施）

| # | 前提 | 证伪方法 |
|---|---|---|
| P-1 | 对 `virtualFile == null` 预热即可覆盖全部入口（`getMappingInner` 无论 file 是否为空都先 `ensureStateLoaded`，且空 file 不再额外做 per-file 查找） | 243 字节码已核；262 侧在沙盒按 §六 R1 实测（预热后所有入口 0 条）。若 262 出现「预热后仍报一次」，需按真实 VirtualFile 形态补预热 |
| P-2 | 仅修 S1 会把断言**挪到** `SqlInputEditor`（SQL→Java 输入区）而非消失 | 做一个「只落 S1」的中间态提交，按 §六 R2 在同一项目新会话复现；若断言直接消失，说明 `SqlInputEditor` 并未走该链，S3 的工厂接线可降级为纯防御（记录依据后仍保留） |
| P-3 | BGT 侧 `getSyntaxHighlighter(SQL, project, null)` 不需要 EDT 写锁，有界等待不会常态化超时（否则 300ms 上限退化为「每次都超时」） | 沙盒打点：冷/暖各 5 次读 `prewarm` 耗时与 `prewarmAndAwait` 返回值；有超时即改备选 D2（见 §七） |
| P-4 | 262（上报环境）与 243（编译基线）的 `ensureStateLoaded` 语义同构：一次加载、逐条 `findFileByUrl`、映射为空则根本不加载 | 本机仅缓存 243 发行版（`F:/gradle-home/.../ideaIU-2024.3.7.1-win`），**262 无字节码可核**——只能以「262 沙盒修复后 0 条」作为行为证据，证据边界须写进实施记录，不许宣称「两版字节码一致」 |
| P-5 | S1 的观感落差可接受：结果区与预览区并排看一致，且「关键字覆盖面收窄（`WITH`/`INTERVAL` 等降为标识符）、表名别名变显眼、逗号点号并入运算符」三项用户不视为退化 | 沙盒并排截图（结果区 vs 预览区）+ 用含 `WITH` CTE、MySQL `INTERVAL`、`ON DUPLICATE KEY UPDATE` 的 SQL 各贴一张；不接受则走 D3（撤回 S1，仅留 S2/S3，断言同样消失） |
| P-8 | 用户当前主题是否改过 Color Scheme → SQL 段（不可知，不能默认「没改」） | 若能拿到用户设置截图/真机 `colorsScheme` 一并看；改过则 S1 的观感落差会被放大，此时默认改走 D3，不做 S1 |
| P-6 | `LogToSqlPanel` 输入编辑器仍是纯文本文档，不受本改动影响（避免误改输入区） | 读 `createInputEditor`（`LogToSqlPanel.kt:622-635`）：无 fileType 参数；S1 只动 `createResultEditor` |
| P-7 | 守卫正则不误伤既有合法调用 | 落地后全量扫 `src/main`：0 命中；同时 `SqlToJavaPanel.kt:1743`、`JavaCodePreviewDialog.kt:54` 两处 `JavaSyntaxHighlighterFactory` 仍在（负例对照绿） |

## 六、验收红线与测试口径

### 6.0 复现前置（不做这步，后面全是空绿）

- 复现项目**必须**至少有一条 per-file SQL 方言映射：`PerFileMappingsBase.loadState` 在列表为空时把 deferred 置 null，压根不会走 VFS（§二-5 字节码）。落盘形态为项目 `.idea/sqldialects.xml`，含 `<file url="file://$PROJECT_DIR$/…" dialect="…"/>` 这类条目；实施记录里**贴该文件内容**作为前置证据。
- `SlowOperations` 带同栈签名去重（`isAlreadyReported`），所以**先红与后绿都必须各起一个新 IDE 会话**，不能在同一个会话里「跑两次看有没有第二条」。

### 6.1 R1 后绿（修复版）

- 262 沙盒（`./gradlew runIde -PgifRecord=true` 或用户真机）打开上述项目，工具窗自动装配；
- `<sandbox>/log/idea.log` 里 `grep -c "Slow operations are prohibited on EDT"` = 0，且不再出现 `Plugin to blame: SQL Visualizer` 关联该栈；贴 grep 命令与读数；
- 功能面：日志转 SQL 转换/格式化/复制、SQL→Java tab 打开与转换、预览面板打开各走一遍，0 异常。

### 6.2 R2 先红 + 中间态（证明 P-2）

1. master 未修版本 + 同一项目 + 新会话 → ≥1 条该断言，栈含 `LogToSqlPanel.createResultEditor`（贴栈）；
2. 「只落 S1」中间态 + 新会话 → 断言若仍出现，栈应改指 `SqlInputEditor.createEditor` / `SqlToJavaPanel`（贴栈，作为 P-2 的实证）；
3. 全量修复 + 新会话 → 0 条（R1）。

### 6.3 R3 守卫双向读数

- 反向：临时在 `src/main` 加探针文件调用 `SyntaxHighlighterFactory.getSyntaxHighlighter(...)`（**只用于验证，验证后删除**）→ `SqlDialectEdtSourceScanTest` 必红，贴报错原文；
- 判别力对照：同探针再写一行 `JavaSyntaxHighlighterFactory.getSyntaxHighlighter(...)` → 不得因这行变红；
- 正向：删探针 → 绿，贴读数。两次读数都要在实施记录里，不许只写「已反向验证」。

### 6.4 测试分层（照 AGENTS.md 三档）

1. **定向**（实施者自己跑）：
   `./gradlew test --tests "cn.song.mybatisplus.visualizer.log.LogToSqlPanel*" --tests "cn.song.mybatisplus.visualizer.log.SqlToJavaPanel*" --tests "cn.song.mybatisplus.visualizer.log.SqlDialectMappingPrewarmTest" --tests "cn.song.mybatisplus.visualizer.convention.*"`
   不跑全量、不跑构建校验、不 `clean`、不 `--stop`。
2. **全量门禁**：全部改完单派只读收口代理跑 `testShards`（5 片，单路），主控同时写文档。
3. **构建校验**：本册不动 `build.gradle.kts`/依赖版本、不加 EP/since-build、不引新平台 API 面（`ReadAction`/`ProgressManager`/`EditorFactory` 均为既有用法）→ **不触发** `verifyGate/verifyPlugin`；若实施中越界则补跑并在记录里说明。

## 七、风险、回滚与备选

| 项 | 说明 | 处置 |
|---|---|---|
| 自研高亮观感与原生 SQL 有差 | 关键字/标识符/表名/字符串/数字/注释/运算符八类键与预览面板一致，但方言专属词不额外上色 | P-5 沙盒并排看；不接受则回滚 S1，仅留 S2/S3（断言同样消失） |
| EDT 有界等待（≤300ms）拖慢项目打开 | 现状本来就在 EDT 上同步做同一份工作，理论上不劣化；极端冷启动仍可能超时 | P-3 打点；超时率高则改备选 D2 |
| `ReadAction` 在 BGT 被 EDT 写动作阻塞 → 超时 | 已用有界等待兜住，最坏退化为「本次未预热」，装配照常 | 记 WARN，不允许抛到调用方 |
| 改动越界到员工窗口初始化时序 | 只加一个前置调用 + 一处 BGT 首行调用，不动 Panel 生命周期 | 单笔提交，可整体 revert |
| 平台后续自行修复 | 若 Database 插件把 `ensureStateLoaded` 挪进 BGT/进度上下文，本预热变成无害 no-op（状态已加载，耗时≈0） | 无需跟进 |

**备选 D2（仅在 P-3 证伪时启用）**：`createToolWindowContent` 不在 EDT 阻塞，改为「BGT 预热 → 回到 EDT 装配 → `addContent`」，并加 `toolWindow.isDisposed()` 与一次性守卫。代价是工具窗内容区可能闪一帧空白；启用前必须沙盒目视确认。

**明确不采用**：
- `SlowOperations.allowSlowOperations{…}`（仓内 243+262 双证伪的逃生舱，禁）；
- 把调用包进写动作（243 字节码确有 `isWriteAccessAllowed` 放行分支，但那会把一次 VFS 状态解析塞进全局写锁，且是在用平台豁免盖住问题本身）；
- 关 `Registry ide.slow.operations.assertion`（改用户环境不是修缺陷）；
- 让 `SqlInputEditor` 丢掉 SQL FileType 来规避（`plugin.xml:304` 的 `completion.contributor language="SQL"` 依赖它，补全会整条失效）。

## 八、文档同步与提交纪律

- 代码与测试：S1 一笔（可独立回滚）→ S2+S3 一笔（结构性）→ S4 守卫一笔 → S5 测试随所在笔。**每完成一件可提交的就立即提交**，提交信息中文、写文件走 `-F`。
- CHANGELOG：`[Unreleased]` → `### Fixed` 一行，口径「项目打开时工具窗初始化在 EDT 读取 SQL 方言映射，触发平台慢操作断言（仅日志噪声，功能未受影响），现改为自研高亮器 + 后台预热」。
- `docs/索引/开发计划.md`：本册入库一行（时间倒序首行），状态写「仅计划」。
- 经验总结（交付时写）：`docs/经验总结/20261028-*-SQL方言映射延迟加载在EDT上的慢操作断言-经验总结.md`，要点两条——① 平台「一次性延迟加载」最容易在插件首帧装配处被拉到 EDT 上，取证要看 `ensureStateLoaded` 这类函数而不是只看自己的调用；② 只修首个调用点会把错误挪到同链的下一个入口，必须先证明「下一个入口是否同链」再决定范围。
- 架构文档：不动（无分层/流程变化）；`docs/架构设计/01-02` 里若有「结果区原生 SQL 高亮」表述，实施时 `grep` 核对后顺手改。

## 九、合并与基线迁移记录（2026-10-28）

- master 侧本册入库提交 `c584a89d`（计划 + `docs/索引/开发计划.md` 一行）；同刻 master 另有并行会话一笔 `aa9bb1ac`（T-ConsoleBind）。
- `git merge master` 进 dev：合并点 **`a56c8e48`**，无冲突（两侧同名改动交集只有 `sub-projects/sql-visualizer-agent/src/test/java/.../AttachPoolWarmGateTest.java` 一个文件且未成 hunk 冲突）；合并后 `dev..master` = 0、`master..dev` = 28，dev 工作树干净。
- 合并后逐条复验本册引用的行号仍成立（dev 树上 grep 实测）：`LogToSqlPanel.kt:644/646`（`getStdFileType("SQL")` / `SyntaxHighlighterFactory`）、`SqlLogPanel.kt:141`（`LogToSqlPanel`）与 `:143`（`SqlToJavaPanel`）、`SqlInputEditor.kt:79`（`createEditor(doc, project, sqlFileType, false)`）、`SqlPreviewPanelEditorLifecycle.kt:627`（同形创建）与 `:650`（`SqlSyntaxHighlighter()`）、`SqlLogToolWindowFactory.java:39/50`。
- 结论：本册不需要按合并结果改修法或改范围；仅补了「实施基线＝dev `a56c8e48`」与本节的迁移记录，以及 §四新增的「S1 观感差异与 D3 备选」。

## 实施记录（2026-10-28，dev 工作树，提交 `e3ab9335` / `4d2abc64` / `f062e833`）

### 落地清单

| 步骤 | 落点 | 提交 |
|---|---|---|
| S1 | `LogToSqlPanel.createResultEditor` 改挂自研 `SqlSyntaxHighlighter`；类 KDoc 与方法 KDoc 同步（删掉「原生 SQL 高亮器」这句已失效表述） | `e3ab9335` |
| S2 | 新增 `src/main/kotlin/.../ui/highlight/SqlDialectMappingPrewarm.kt`：`prewarmNow`（BGT 同步首查）/ `prewarm`（fire-and-forget）/ `prewarmAndAwait`（EDT 有界等待，`@JvmOverloads` 供 Java 调）+ `resetForTest` / `isWarmedForTest` 两个测试缝 | `4d2abc64` |
| S3 | `SqlLogToolWindowFactory.createToolWindowContent` 在 `new SqlLogPanel(...)` 之前调 `prewarmAndAwait(project)`；`SqlPreviewPanelEditorLifecycle.prewarmPreviewTempFile` 的 BGT 任务首行调 `prewarmNow(project)` | `4d2abc64` |
| S4 | 新增 `convention/SqlDialectEdtSourceScanTest`（等值允许清单 + 三条自证语料）+ `GuardSelfTestCoverageMetaGuardTest.GUARD_CLASSES` 登记 | `f062e833` |
| S5 | `LogToSqlPanelUiTest` 增「首个 token 必须是 `MP_SQL_KEYWORD`」判别断言；新增 `SqlDialectMappingPrewarmTest` 钉短路契约；`LogToSqlPanel` 增 `setResultForTest` 缝 | `f062e833` |

### 前提证伪结论

| # | 结论 | 依据 |
|---|---|---|
| P-1 | **成立**（243 字节码）：`getMappingInner` 第一句即 `ensureStateLoaded`，与 file 是否为 null 无关 → 对 null file 预热即可覆盖全部入口 | 243 `lib/app-client.jar` 的 `javap -p -c PerFileMappingsBase` |
| P-2 | **未按「只落 S1 中间态」做运行期实测**（需独立 IDE 会话 + 含 per-file 方言映射的项目）。代码面同链已确认：`SqlInputEditor.kt:79` 按 SQL FileType 建编辑器；243 `EditorHighlighterFactoryImpl` 字节码按 FileType 走 `SyntaxHighlighterFactory.getSyntaxHighlighter` → 同一首查。S3 按「假定成立」落地——即便不成立，预热也只是空转，无副作用 | 上报栈 + 243 字节码 + 源码 |
| P-3 | **未实测**（需沙盒打点）。已用有界等待兜底；若沙盒显示超时成常态，按 §七 D2 改异步装配 | — |
| P-4 | **未实测**：262 无字节码可核，只能以沙盒行为取证（见「未闭环」第 1 条）。本册不宣称两版语义一致 | 本机仅缓存 243 发行版 |
| P-5 / P-8 | **未实测**（观感由用户并排截图拍板）。差异已在本册 §四 逐项写明；若要零观感变化，单独 revert `e3ab9335` 走 D3，其余两笔保留 | — |
| P-6 | **成立**：`createInputEditor`（`LogToSqlPanel.kt:622-635`）无 FileType 参数，本次未动输入区 | 源码 |
| P-7 | **成立**：守卫全量扫描命中清单恰为预热助手一处；`JavaSyntaxHighlighterFactory` 两处既有调用（`SqlToJavaPanel.kt:1743`、`JavaCodePreviewDialog.kt:54`）未被判红 | 守卫读数（见下） |

### 读数

- **定向测试**：162 / 0 / 0 / 0。命令：
  `./gradlew test --tests "cn.song.mybatisplus.visualizer.log.LogToSqlPanel*" --tests "cn.song.mybatisplus.visualizer.log.SqlToJavaPanel*" --tests "cn.song.mybatisplus.visualizer.log.SqlClickPreviewTest" --tests "cn.song.mybatisplus.visualizer.ui.highlight.SqlDialectMappingPrewarmTest" --tests "cn.song.mybatisplus.visualizer.convention.SqlDialectEdtSourceScanTest" --tests "cn.song.mybatisplus.visualizer.convention.GuardSelfTestCoverageMetaGuardTest"`
  分类：LogToSqlPanel 45（Test 22 / Execute 18 / Ui 5）、SqlToJavaPanel 83（38/23/18/4）、SqlClickPreview 20、Prewarm 2、SqlDialectEdtSourceScan 4、元守卫 8。
- **守卫反向验证（双向）**：
  - 探针 `TmpSqlDialectGuardProbe.kt`（裸调用）+ 负例 `TmpGuardNegativeControl.kt`（只含 `JavaSyntaxHighlighterFactory` 跨行调用）→ 红，命中清单 `[SqlDialectMappingPrewarm.kt, TmpSqlDialectGuardProbe.kt]`，**负例文件不在清单内**（文件粒度判别力；只把两个探针写在同一文件里做不到这一点，故拆成两个文件重取一次读数）。
  - 删除两个探针 → 绿（`BUILD SUCCESSFUL in 27s`）。探针未提交，`git status` 复核无残留。
- **编译**：`compileKotlin` / `compileJava` / `compileTestKotlin` 通过。首轮编译两处类型错误已修：预览链 `project` 是 `Project?`（改 `project?.let`，**不提前 return**，否则预热产物 await 永不完成、降级路径变慢）；`executeOnPooledThread` 的 `Runnable`/`Callable` 两重载推断歧义（显式指定）。
- **全量门禁（`testShards` 5 片）本次无效，且不是用例失败**：五片并行时系统内存耗尽——JVM 直接 `os::commit_memory` / native `malloc` 失败（产出 5 个 `hs_err_pid*.log` + 3 个 `replay_pid*.log`，已删除未提交），shard2/3/4 启动期即崩、无 `tests completed` 读数；`testExternal` 的两条失败也都不归本册：`AcpSignatureCrossVersionTest` 的断言消息里直接夹着 `os::commit_memory` 告警（同一内存诱因），`BannedApiSourceScanTest.方言setMapping…` 见下节第 2 条。当时机器上另有多个 0.8~1.5GB 级 java 进程（并行会话负载）。**建议机器空闲时单路重跑一次取有效读数**；在此之前不得引用「全量绿」。
- **构建面**：未动 `build.gradle.kts`、依赖版本、plugin.xml、EP/since-build，按 §六 6.4 不触发 `verifyGate`/`verifyPlugin`/`buildPlugin`。

### 未闭环（合并/发版前须处理）

1. **运行期读数全缺**：R1（262 沙盒 + 含 per-file 方言映射的项目 + 新会话 → `Slow operations` 计数 0）与 R2（先红栈 + 「只落 S1」中间态栈改指 `SqlInputEditor`）都需要带 Database 插件的 262 沙盒，本工作树只跑了单测与编译。**证据边界：本册关于「断言已消失」的所有结论目前都没有运行期读数支撑**，不得当作已验证。
2. **既有红（非本任务，merge 带进 dev）**：`BannedApiSourceScanTest.方言setMapping反射只允许DialectPreMapping单入口` 命中 `execute/ExecFileBinding.kt`。该文件由 master `aa9bb1ac`（T-ConsoleBind）新增；其反射目标是 `com.intellij.database.dataSource.DataSourcePerFileMappingsImpl`（**不是** `SqlDialectMappings`），而守卫用裸 `"setMapping"` 子串判，粒度太粗。证据：`git show aa9bb1ac:...ExecFileBinding.kt | grep -c '"setMapping"'` = 1，守卫文件最后改动是 `d39a8e57`（早于该提交）→ master 上本就是红的。归 T-ConsoleBind 处置，两条候选：①守卫收窄到「同一站点同时引用 SqlDialectMappings」；②给该处具名豁免并注明目标类不同。本册未动手，避免与在途任务改同一批文件。
3. **观感拍板**：P-5/P-8 未验，结果区配色变化是否接受由用户并排截图决定（步骤见对话记录）。

### 与计划的偏离（两处，均有理由）

- **预热助手放 `ui/highlight/` 而不是计划写的 `log` 包**：消费方是 `SqlLogToolWindowFactory`（log 包，Java）与预览链（ui 包），放高亮层让两边都只依赖「编辑器高亮」这一概念；且仓库本就有 ui→log 的 import，这个位置不新增方向依赖。
- **`prewarmNow` 先查「是否有 SQL FileType」再派发**：`FileTypeManager.getStdFileType("SQL")` 在 Kotlin 里是**非空平台类型**（写成 `val x: FileType = … ?: run{}` 会被编译器警告「elvis 恒取左值」），故不声明类型、让推断走；未装 Database 的环境记「无可预热」以免每次启动重复探测。
