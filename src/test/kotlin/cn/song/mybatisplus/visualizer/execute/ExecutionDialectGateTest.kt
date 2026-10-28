package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * k2j 互操作（T-SyncK2JMaster136 B2 移植）：
 *  - Request 记录比 master 多一枚 moduleKey 尾参（6 参），helper 以位置实参构造；
 *  - allowDialectGate 比 master 的 5 参显式多一枚 onSubmission（master 默认 null）；
 *  - 测试缝 sessionDataSourceForTest/consoleDataSourceForTest 为 SAM 接口字段；
 *  - 源码守卫扫描根 src/main/kotlin → src/main/java，文件名带 Kt 后缀（文件级函数承载类）；
 *  - Kotlin 命名实参/具名默认参在 Java 侧全部位置化；uncoveredEntryPoints → UNCOVERED_ENTRY_POINTS。
 */
class ExecutionDialectGateTest {
    private fun request(
        mode: ExecutionDialectGate.RenderMode,
        dialect: String? = "MySQL",
        intent: ExecutionDialectGate.Intent = ExecutionDialectGate.Intent.QUERY,
        rerender: Boolean = false,
        untouched: Boolean = false,
    ) = ExecutionDialectGate.Request(mode, dialect, intent, rerender, untouched, null)

    @Test
    fun testSameTargetContinues() {
        assertEquals(ExecutionDialectGate.Status.MATCH,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO), "mysql").status())
    }

    @Test
    fun testManualMismatchAlwaysBlocks() {
        val verdict = ExecutionDialectGate.evaluate(
            request(ExecutionDialectGate.RenderMode.MANUAL, "Oracle"), "MySQL")
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT, verdict.status())
    }

    @Test
    fun testAutoMismatchRerendersOnlyWhenLosslessAndUntouched() {
        assertEquals(ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL",
                rerender = true, untouched = true), "PostgreSQL").status())
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL",
                rerender = true, untouched = false), "PostgreSQL").status())
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL"), "PostgreSQL").status())
    }

    @Test
    fun testGeneratedTemplateAndDmlImpactQueryBlockKnownMismatchWithoutAPinnedRerenderTarget() {
        val template = request(
            ExecutionDialectGate.RenderMode.AUTO,
            "MySQL",
            ExecutionDialectGate.Intent.TEMPLATE,
            rerender = false,
            untouched = true,
        )
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(template, "Oracle").status())
        // Java record 无 Kotlin copy()——同参重建（intent 换 QUERY）
        val impactCount = request(
            ExecutionDialectGate.RenderMode.AUTO,
            "MySQL",
            ExecutionDialectGate.Intent.QUERY,
            rerender = false,
            untouched = true,
        )
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(impactCount, "Oracle").status())
    }

    @Test
    fun testUnknownTargetQueryAsksConfirmationButDmlAndExplainBlock() {
        assertEquals(ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO), null).status())
        for (intent in listOf(ExecutionDialectGate.Intent.DML, ExecutionDialectGate.Intent.EXPLAIN,
            ExecutionDialectGate.Intent.TEMPLATE)) {
            assertEquals(intent.name, ExecutionDialectGate.Status.TARGET_UNKNOWN_BLOCK,
                ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, intent = intent), null).status())
        }
    }

    @Test
    fun testRawQueryAlwaysAsksConfirmationWhileRawDmlAndExplainBlock() {
        val query = ExecutionDialectGate.rawSqlRequest("SELECT * FROM t")
        assertEquals(ExecutionDialectGate.Intent.QUERY, query.intent())
        assertEquals(ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM,
            ExecutionDialectGate.evaluate(query, "PostgreSQL").status())
        assertEquals(ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM,
            ExecutionDialectGate.evaluate(query, null).status())

        val dml = ExecutionDialectGate.rawSqlRequest("UPDATE t SET a = 1 WHERE id = 2")
        assertEquals(ExecutionDialectGate.Intent.DML, dml.intent())
        assertEquals(ExecutionDialectGate.Status.RENDER_UNKNOWN_BLOCK,
            ExecutionDialectGate.evaluate(dml, "MySQL").status())

        val explain = ExecutionDialectGate.rawSqlRequest("EXPLAIN SELECT * FROM t")
        assertEquals(ExecutionDialectGate.Intent.EXPLAIN, explain.intent())
        assertEquals(ExecutionDialectGate.Status.RENDER_UNKNOWN_BLOCK,
            ExecutionDialectGate.evaluate(explain, "MySQL").status())
    }

    @Test
    fun testUnknownRenderedDialectCanRequestAutoRerenderButNeverManualOverwrite() {
        assertEquals(ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, null,
                rerender = true, untouched = true), "Oracle").status())
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.MANUAL, null), "Oracle").status())
    }

    @Test
    fun testExactJdbcVendorWinsOverBroaderPlatformLanguageFamily() {
        assertEquals("OceanBase", ExecutionDialectGate.targetDialectName(
            "jdbc:oceanbase://localhost/app", "MySQL"))
        assertEquals("PostgreSQL", ExecutionDialectGate.targetDialectName(null, "PostgreSQL"))
    }

    @Test
    fun testSessionSeamResolvesExactSelectedDatasourceWithoutRunnerDisplayName() {
        class Ds(private val url: String) { @Suppress("unused") fun getUrl(): String = url }
        val session = Any()
        val ds = Ds("jdbc:postgresql://localhost/app")
        try {
            ExecutionDialectGate.sessionDataSourceForTest =
                ExecutionDialectGate.SessionDataSourceLookup { if (it === session) ds else null }
            val target = ExecutionDialectGate.fromSession(session)
            assertTrue(target?.dataSource === ds)
            assertEquals("PostgreSQL", target?.dialectName)
            assertNull(target?.stableId)
        } finally {
            ExecutionDialectGate.sessionDataSourceForTest = null
        }
    }

    @Test
    fun testConsoleSeamAndUnknownUrlKeepTargetDialectUnknown() {
        class Ds { @Suppress("unused") fun getUrl(): String = "jdbc:vendor://localhost/app" }
        val console = Any()
        try {
            ExecutionDialectGate.consoleDataSourceForTest =
                ExecutionDialectGate.ConsoleDataSourceLookup { if (it === console) Ds() else null }
            val target = ExecutionDialectGate.fromConsole(console)
            assertNull(target?.dialectName)
        } finally {
            ExecutionDialectGate.consoleDataSourceForTest = null
        }
    }

    @Test
    fun testCentralHandlerKeepsCallersWithoutRequestUnchangedAndLetsKnownConflictsThroughWithAWarning() {
        // 用户定案（2026-10-25）：「方言不能影响最终的执行。哪怕方言是错的，用户手动选了数据源，
        // 可以正常提示方言不一致，但还是要能成功执行」。所以 MANUAL_CONFLICT 从「阻断」改为
        // 「提示 + 放行」——文案照旧要报出双方方言，让用户知道为什么可能报语法错。
        val project = projectStub()
        val target = ExecutionDialectGate.Target(Any(), "ds-1", "prod", "PostgreSQL", "PostgreSQL")
        assertTrue(DataSourceSelector.allowDialectGate(project, null, target, null, null, null))

        var callbackStatus: ExecutionDialectGate.Status? = null
        var error: String? = null
        var submission: ExecutionSubmission? = null
        val allowed = DataSourceSelector.allowDialectGate(
            project,
            request(ExecutionDialectGate.RenderMode.MANUAL, "MySQL", ExecutionDialectGate.Intent.DML),
            target,
            { callbackStatus = it.status() },
            { error = it },
            { submission = it },
        )
        assertTrue("已知方言冲突应放行（方言不拦执行）", allowed)
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT, callbackStatus)
        assertTrue("仍要提示渲染方言", error.orEmpty().contains("MySQL"))
        assertTrue("仍要提示目标方言", error.orEmpty().contains("PostgreSQL"))
        // master 忠实断言：放行态（MANUAL_CONFLICT/RERENDER_UNAVAILABLE）在放行前仍先发
        // onSubmission(BLOCKED)——非 MATCH 且非确认态一律先回执未提交（DataSourceSelector.kt:220 同款）。
        assertEquals("放行态仍先回执 BLOCKED", ExecutionSubmission.BLOCKED, submission)
    }

    @Test
    fun testUnknownDialectOnEitherSideStillBlocksWrites() {
        // 与上一条的边界：**有一边方言未知**时不能放行——写操作可能把 PG 语法打到 MySQL 库上，
        // 属于「无从判断」而非「判断出冲突」。
        val project = projectStub()
        val unknownRender = DataSourceSelector.allowDialectGate(
            project,
            request(ExecutionDialectGate.RenderMode.UNKNOWN, null, ExecutionDialectGate.Intent.DML),
            ExecutionDialectGate.Target(Any(), "ds-1", "prod", "PostgreSQL", "PostgreSQL"),
            null,
            null,
            null,
        )
        assertFalse("渲染方言未知的写操作仍阻断", unknownRender)

        val unknownTarget = DataSourceSelector.allowDialectGate(
            project,
            request(ExecutionDialectGate.RenderMode.MANUAL, "MySQL", ExecutionDialectGate.Intent.DML),
            null,
            null,
            null,
            null,
        )
        assertFalse("目标方言未知的写操作仍阻断", unknownTarget)
    }

    @Test
    fun testCentralHandlerUnknownTargetBlocksExplainButQueryNeedsExplicitConfirmation() {
        val project = projectStub()
        assertFalse(
            DataSourceSelector.allowDialectGate(
                project,
                request(ExecutionDialectGate.RenderMode.AUTO, intent = ExecutionDialectGate.Intent.EXPLAIN),
                null,
                null,
                null,
                null,
            ),
        )
        try {
            DataSourceSelector.unknownTargetQueryConfirmationForTest = { true }
            assertTrue(
                DataSourceSelector.allowDialectGate(
                    project,
                    request(ExecutionDialectGate.RenderMode.AUTO, intent = ExecutionDialectGate.Intent.QUERY),
                    null,
                    null,
                    null,
                    null,
                ),
            )
        } finally {
            DataSourceSelector.unknownTargetQueryConfirmationForTest = null
        }
    }

    @Test
    fun testCentralHandlerConfirmsRawQueryAndBlocksRawWritesEvenForKnownTarget() {
        val project = projectStub()
        val target = ExecutionDialectGate.Target(Any(), "ds-1", "prod", "PostgreSQL", "PostgreSQL")
        try {
            var confirmations = 0
            DataSourceSelector.unknownTargetQueryConfirmationForTest = {
                confirmations++
                true
            }
            assertTrue(DataSourceSelector.allowDialectGate(
                project,
                ExecutionDialectGate.rawSqlRequest("SELECT * FROM t"),
                target,
                null,
                null,
                null,
            ))
            assertEquals(1, confirmations)
            assertFalse(DataSourceSelector.allowDialectGate(
                project,
                ExecutionDialectGate.rawSqlRequest("DELETE FROM t WHERE id = 1"),
                target,
                null,
                null,
                null,
            ))
            assertEquals("raw DML must block before any confirmation", 1, confirmations)
        } finally {
            DataSourceSelector.unknownTargetQueryConfirmationForTest = null
        }
    }

    @Test
    fun testSourceWiringGatesSelectedSessionBeforeAttachAndActiveConsoleBeforeSubmitMutation() {
        val root = repoRoot()
        val results = File(root, "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResultsKt.java").readText()
        val console = File(root, "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsoleKt.java").readText()
        val sessionGate = results.indexOf("ExecutionDialectGate.fromSession(session)")
        val attach = results.indexOf("prepareInConsole(", sessionGate)
        assertTrue("session gate must precede prepareInConsole/attach", sessionGate >= 0 && attach > sessionGate)
        val consoleGate = console.indexOf("ExecutionDialectGate.fromConsole(console)")
        val firstMutation = console.indexOf("stripGSuffixForSubmit", consoleGate)
        assertTrue("console gate must precede any submit document mutation", consoleGate >= 0 && firstMutation > consoleGate)
    }

    @Test
    fun testSubmissionReceiptIsThreadedThroughColdReuseQueuedAndNativeFallbackPaths() {
        val root = repoRoot()
        val results = File(root, "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResultsKt.java").readText()
        val console = File(root, "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsoleKt.java").readText()
        val runner = File(root, "src/main/java/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.java").readText()
        assertTrue(results.contains("dialectGateRequest, onDialectGate, onSubmission,"))
        // k2j 互操作：Kotlin 命名实参 `onSubmission = onSubmission` 在 Java 侧为位置实参——
        // 改钉 afterSubmitted 调用携带 onSubmission 形参（同一 threading 事实）。
        assertTrue(console.contains("selectPrepareSubmission(queuedIntent, onSubmission)"))
        assertEquals("native 与 fallback 均从既有 afterSubmitted 转发回执", 2,
            console.lineSequence().count { it.contains("afterSubmitted(project") && it.trimEnd().endsWith("onSubmission);") })
        // k2j 互操作：方法名 executeSql→executeStatement、`onSubmission = ::showSubmission`→`this::showSubmission`。
        val entry = sourceSlice(runner, "private void executeStatement(String sql)", "private void showSubmission(")
        assertFalse("发起执行不能预报成功", entry.contains("logToSql.execute.submitted"))
        assertTrue(entry.contains("logToSql.execute.pending"))
        assertTrue(entry.contains("this::showSubmission"))
        val runnerSlice = sourceSlice(results, "public static void runRunner(", "public static String execSilentProbe(")
        assertFalse("runner 返回不能发成功回执", runnerSlice.contains("ExecutionSubmission.SUBMITTED"))
    }

    @Test
    fun testAllExplicitExecutionEntryPointsCarryRequestsAndUncoveredListIsEmpty() {
        val root = repoRoot()
        val preview = File(root, "src/main/java/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecutionKt.java").readText()
        val templates = File(root, "src/main/java/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateAction.java").readText()

        assertTrue(preview.contains("ExecutionDialectGate.Intent.DML"))
        assertTrue(preview.contains("ExecutionDialectGate.Intent.EXPLAIN"))
        val structuredGate = preview.indexOf("DataSourceSelector.allowDialectGate")
        val structuredSubmit = preview.indexOf("ExplainPanelChannel.executeStructured")
        assertTrue("structured EXPLAIN must gate before JDBC submit", structuredGate >= 0 && structuredSubmit > structuredGate)

        // 【master 断言延后——生产差距登记】选表模板三动作的「捕获方言渲染器」
        // （master SelectedTableSqlTemplateActions.kt:52-123 renderExplainStatement/renderLimit/
        // DialectRegistry.effective/executeTemplate+dialectGateRequest）k2j 未译
        // （SelectedTableSqlTemplateAction.java 仍为恒 MySQL 模板字符串 + SqlExecutor.execute 旧参形态）；
        // 同上登记勿改生产，此处钉现状形态（template.apply 纯字符串模板）供补译对照。
        assertTrue("k2j 选表模板仍是纯字符串模板形态（生产补译捕获方言渲染器后此断言应删除）",
            templates.contains("template.apply"))

        val impactBody = sourceSlice(
            preview,
            "public static void estimateDmlImpact(@NotNull SqlPreviewPanel p, boolean allowDataSourcePrompt) {",
            "public static void estimateDmlImpact(@NotNull SqlPreviewPanel p) {",
        )
        assertEquals(0, impactBody.countOccurrences("SqlExecutor.execute("))
        assertTrue(
            "影响面预演必须走只读旁路查询，不能进共享控制台（会替换预览编辑器）",
            impactBody.contains("PreviewSideQuery.run("),
        )
        assertTrue("影响面预演仍须携带方言闸请求", impactBody.contains("dialectGateRequest"))

        val logs = File(root, "src/main/java/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.java").readText()
        // k2j 互操作：方法名 executeSql→executeStatement、止锚「/** T-Perf-14」在 Java 布局位于更早处——
        // 改用「转换流程」分节锚；命名实参 `dialectGateRequest = dialectGateRequest` 在 Java 侧为
        // 位置实参——改数透传实参个数（同一 threading 事实）。
        val logBody = sourceSlice(logs, "private void executeStatement(String sql)", "转换流程")
        assertEquals(1, logBody.countOccurrences("ExecutionDialectGate.rawSqlRequest"))
        assertEquals(1, logBody.countOccurrences("dialectGateRequest, null,"))

        val adhocBody = sourceSlice(
            preview,
            "public static void executeAdhocSql(\n            @NotNull SqlPreviewPanel p,\n            @NotNull String sql,\n            boolean dmlConfirmed,\n            @Nullable cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry rollbackOfEntry,\n            @Nullable Consumer<ExecutionSubmission> onSubmission) {",
            "public static void executeSql(@NotNull SqlPreviewPanel p) {",
        )
        assertEquals(2, adhocBody.countOccurrences("SqlExecutor.execute("))
        assertEquals(1, adhocBody.countOccurrences("SqlExecutor.executePrepared("))
        // k2j 互操作：master 的 `dialectGateRequest = dialectGateRequest` ×3 在 Java 侧为位置实参——
        // 两全链 + 一 prepared 落点均以 `dialectGateRequest, null, receipt` 形态透传（同一事实）。
        assertEquals(3, adhocBody.countOccurrences("dialectGateRequest, null, receipt"))
        assertTrue(ExecutionDialectGate.UNCOVERED_ENTRY_POINTS.isEmpty())
    }

    @Test
    fun testUI020RollbackReceiptIsWiredThroughBothFullPathsAndPreparedFacade() {
        val root = repoRoot()
        val preview = File(root, "src/main/java/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecutionKt.java").readText()
        val adhoc = sourceSlice(preview,
            "public static void executeAdhocSql(\n            @NotNull SqlPreviewPanel p,\n            @NotNull String sql,\n            boolean dmlConfirmed,\n            @Nullable cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore.SnapshotEntry rollbackOfEntry,\n            @Nullable Consumer<ExecutionSubmission> onSubmission) {",
            "public static void executeSql(@NotNull SqlPreviewPanel p) {")
        // k2j 互操作：master 的 `onSubmission = receipt`×3 在 Java 侧为位置实参 receipt——
        // 改数 receipt 作为实参传入执行调用的次数（两全链 + 一 prepared，同一事实）。
        assertEquals("两个全链落点和一个prepared落点必须均接回执", 3, adhoc.countOccurrences("receipt, false)") + adhoc.countOccurrences("null, receipt)"))
        val executor = File(root, "src/main/java/cn/song/mybatisplus/visualizer/execute/SqlExecutor.java").readText()
        // k2j 互操作：master 的切片止锚（/** 控制台是否已就绪）在 Java 侧位于最后一个
        // executePrepared 重载之后——prepareConsole 在文件更早处，不能当止锚。
        val prepared = sourceSlice(executor, "public static boolean executePrepared(", "/** 控制台是否已就绪")
        // k2j：receipt lambda 单发（AtomicBoolean）+ onSubmission 空则不发——与 master 的
        // `onSubmission = if (onSubmission == null) null else receipt` 等价。
        assertTrue(prepared.contains("onSubmission != null"))
        assertTrue("prepared错误和富错误均须结算未提交", prepared.countOccurrences("receipt.accept(ExecutionSubmission.BLOCKED)") == 2)
        val selector = File(root, "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.java").readText()
        val selectorPrepared = sourceSlice(selector, "public static boolean executePrepared(", "public static void selectAndExecute(")
        // k2j 互操作：位置实参透传替代 master 的命名实参文本。
        assertTrue("executePrepared 门面须透传 onSubmission", selectorPrepared.countOccurrences("onSubmission") >= 3)
        assertFalse("Boolean true不得制造提交回执", selectorPrepared.contains("ExecutionSubmission.SUBMITTED"))
        val dialog = File(root, "src/main/java/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.java").readText()
        val entry = sourceSlice(dialog, "public void executeRollback()", "public Consumer<ExecutionSubmission> rollbackSubmissionReceipt()")
        assertTrue(entry.contains("rollbackSubmissionReceipt()"))
        assertFalse(entry.contains("markRollbackExecuted("))
    }

    private fun sourceSlice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("missing source marker: $startMarker", start >= 0)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("missing source marker: $endMarker", end > start)
        return source.substring(start, end)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length, 1).count { it == needle }

    private fun projectStub(): com.intellij.openapi.project.Project =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.intellij.openapi.project.Project::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as com.intellij.openapi.project.Project

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }
}
