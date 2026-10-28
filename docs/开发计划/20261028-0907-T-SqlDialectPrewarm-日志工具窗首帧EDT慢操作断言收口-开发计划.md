# T-SqlDialectPrewarm：日志工具窗首帧 EDT 慢操作断言收口（SQL 方言映射延迟加载）

**创建时间**：2026-10-28 09:07
**触发源**：用户上报 `java.lang.Throwable: Slow operations are prohibited on EDT`，栈顶落点 `LogToSqlPanel.createResultEditor(LogToSqlPanel.kt:647)` → `SqlLogToolWindowFactory.createToolWindowContent(SqlLogToolWindowFactory.java:50)` → `ToolWindowSetInitializer`（即**项目打开、侧边栏工具窗首次装配**这一拍）
**基线**：master `aa9bb1ac`。根因分析读的是 `fb0a73fd` 的代码；期间并行会话前进一笔 `aa9bb1ac`（T-ConsoleBind，只动 `execute/DataSourceSelectorResults.kt` + 新增 `ExecFileBinding` 与其测试），与本册涉及文件无交集，故本册全部 `文件:行` 取证在新基线上仍然成立（dev 侧同文件与 master 无差异，已 `git diff master...dev` 核实）
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

## 五、待验证前提（实施者先证伪再动手，证伪即回写本册再实施）

| # | 前提 | 证伪方法 |
|---|---|---|
| P-1 | 对 `virtualFile == null` 预热即可覆盖全部入口（`getMappingInner` 无论 file 是否为空都先 `ensureStateLoaded`，且空 file 不再额外做 per-file 查找） | 243 字节码已核；262 侧在沙盒按 §六 R1 实测（预热后所有入口 0 条）。若 262 出现「预热后仍报一次」，需按真实 VirtualFile 形态补预热 |
| P-2 | 仅修 S1 会把断言**挪到** `SqlInputEditor`（SQL→Java 输入区）而非消失 | 做一个「只落 S1」的中间态提交，按 §六 R2 在同一项目新会话复现；若断言直接消失，说明 `SqlInputEditor` 并未走该链，S3 的工厂接线可降级为纯防御（记录依据后仍保留） |
| P-3 | BGT 侧 `getSyntaxHighlighter(SQL, project, null)` 不需要 EDT 写锁，有界等待不会常态化超时（否则 300ms 上限退化为「每次都超时」） | 沙盒打点：冷/暖各 5 次读 `prewarm` 耗时与 `prewarmAndAwait` 返回值；有超时即改备选 D2（见 §七） |
| P-4 | 262（上报环境）与 243（编译基线）的 `ensureStateLoaded` 语义同构：一次加载、逐条 `findFileByUrl`、映射为空则根本不加载 | 本机仅缓存 243 发行版（`F:/gradle-home/.../ideaIU-2024.3.7.1-win`），**262 无字节码可核**——只能以「262 沙盒修复后 0 条」作为行为证据，证据边界须写进实施记录，不许宣称「两版字节码一致」 |
| P-5 | 自研高亮器的配色与预览面板一致，且不含「方言专属关键字」导致的观感断裂可接受 | 沙盒并排截图（结果区 vs 预览区）；可接受即闭环，不接受则回滚 S1 并只保留 S2/S3（此时断言仍消失，只是继续依赖 Database 插件） |
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

## 实施记录（待填）

（实施者按 §六 逐条回填：R1 后绿读数与 grep 命令、R2 三态栈、R3 双向读数、P-1~P-7 证伪结论、定向测试读数、全量门禁读数与 HEAD sha、未闭环项与证据边界。）
