package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExecutionDialectGateTest {
    private fun request(
        mode: ExecutionDialectGate.RenderMode,
        dialect: String? = "MySQL",
        intent: ExecutionDialectGate.Intent = ExecutionDialectGate.Intent.QUERY,
        rerender: Boolean = false,
        untouched: Boolean = false,
    ) = ExecutionDialectGate.Request(mode, dialect, intent, rerender, untouched)

    @Test
    fun `same target continues`() {
        assertEquals(ExecutionDialectGate.Status.MATCH,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO), "mysql").status)
    }

    @Test
    fun `manual mismatch always blocks`() {
        val verdict = ExecutionDialectGate.evaluate(
            request(ExecutionDialectGate.RenderMode.MANUAL, "Oracle"), "MySQL")
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT, verdict.status)
    }

    @Test
    fun `auto mismatch rerenders only when lossless and untouched`() {
        assertEquals(ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL",
                rerender = true, untouched = true), "PostgreSQL").status)
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL",
                rerender = true, untouched = false), "PostgreSQL").status)
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, "MySQL"), "PostgreSQL").status)
    }

    @Test
    fun `generated template and DML impact query block known mismatch without a pinned rerender target`() {
        val template = request(
            ExecutionDialectGate.RenderMode.AUTO,
            "MySQL",
            ExecutionDialectGate.Intent.TEMPLATE,
            rerender = false,
            untouched = true,
        )
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(template, "Oracle").status)
        val impactCount = template.copy(intent = ExecutionDialectGate.Intent.QUERY)
        assertEquals(ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
            ExecutionDialectGate.evaluate(impactCount, "Oracle").status)
    }

    @Test
    fun `unknown target query asks confirmation but DML and EXPLAIN block`() {
        assertEquals(ExecutionDialectGate.Status.TARGET_UNKNOWN_QUERY_CONFIRM,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO), null).status)
        for (intent in listOf(ExecutionDialectGate.Intent.DML, ExecutionDialectGate.Intent.EXPLAIN,
            ExecutionDialectGate.Intent.TEMPLATE)) {
            assertEquals(intent.name, ExecutionDialectGate.Status.TARGET_UNKNOWN_BLOCK,
                ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, intent = intent), null).status)
        }
    }

    @Test
    fun `raw query always asks confirmation while raw DML and EXPLAIN block`() {
        val query = ExecutionDialectGate.rawSqlRequest("SELECT * FROM t")
        assertEquals(ExecutionDialectGate.Intent.QUERY, query.intent)
        assertEquals(ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM,
            ExecutionDialectGate.evaluate(query, "PostgreSQL").status)
        assertEquals(ExecutionDialectGate.Status.RENDER_UNKNOWN_QUERY_CONFIRM,
            ExecutionDialectGate.evaluate(query, null).status)

        val dml = ExecutionDialectGate.rawSqlRequest("UPDATE t SET a = 1 WHERE id = 2")
        assertEquals(ExecutionDialectGate.Intent.DML, dml.intent)
        assertEquals(ExecutionDialectGate.Status.RENDER_UNKNOWN_BLOCK,
            ExecutionDialectGate.evaluate(dml, "MySQL").status)

        val explain = ExecutionDialectGate.rawSqlRequest("EXPLAIN SELECT * FROM t")
        assertEquals(ExecutionDialectGate.Intent.EXPLAIN, explain.intent)
        assertEquals(ExecutionDialectGate.Status.RENDER_UNKNOWN_BLOCK,
            ExecutionDialectGate.evaluate(explain, "MySQL").status)
    }

    @Test
    fun `unknown rendered dialect can request auto rerender but never manual overwrite`() {
        assertEquals(ExecutionDialectGate.Status.AUTO_RERENDER_REQUIRED,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.AUTO, null,
                rerender = true, untouched = true), "Oracle").status)
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT,
            ExecutionDialectGate.evaluate(request(ExecutionDialectGate.RenderMode.MANUAL, null), "Oracle").status)
    }

    @Test
    fun `exact JDBC vendor wins over broader platform language family`() {
        assertEquals("OceanBase", ExecutionDialectGate.targetDialectName(
            "jdbc:oceanbase://localhost/app", "MySQL"))
        assertEquals("PostgreSQL", ExecutionDialectGate.targetDialectName(null, "PostgreSQL"))
    }

    @Test
    fun `session seam resolves exact selected datasource without runner display name`() {
        class Ds(private val url: String) { @Suppress("unused") fun getUrl(): String = url }
        val session = Any()
        val ds = Ds("jdbc:postgresql://localhost/app")
        try {
            ExecutionDialectGate.sessionDataSourceForTest = { if (it === session) ds else null }
            val target = ExecutionDialectGate.fromSession(session)
            assertTrue(target?.dataSource === ds)
            assertEquals("PostgreSQL", target?.dialectName)
            assertNull(target?.stableId)
        } finally {
            ExecutionDialectGate.sessionDataSourceForTest = null
        }
    }

    @Test
    fun `console seam and unknown URL keep target dialect unknown`() {
        class Ds { @Suppress("unused") fun getUrl(): String = "jdbc:vendor://localhost/app" }
        val console = Any()
        try {
            ExecutionDialectGate.consoleDataSourceForTest = { if (it === console) Ds() else null }
            val target = ExecutionDialectGate.fromConsole(console)
            assertNull(target?.dialectName)
        } finally {
            ExecutionDialectGate.consoleDataSourceForTest = null
        }
    }

    @Test
    fun `central handler keeps callers without request unchanged and lets known conflicts through with a warning`() {
        // 用户定案（2026-10-25）：「方言不能影响最终的执行。哪怕方言是错的，用户手动选了数据源，
        // 可以正常提示方言不一致，但还是要能成功执行」。所以 MANUAL_CONFLICT 从「阻断」改为
        // 「提示 + 放行」——文案照旧要报出双方方言，让用户知道为什么可能报语法错。
        val project = projectStub()
        val target = ExecutionDialectGate.Target(Any(), "ds-1", "prod", "PostgreSQL", "PostgreSQL")
        assertTrue(DataSourceSelector.allowDialectGate(project, null, target, null, null))

        var callbackStatus: ExecutionDialectGate.Status? = null
        var error: String? = null
        val allowed = DataSourceSelector.allowDialectGate(
            project,
            request(ExecutionDialectGate.RenderMode.MANUAL, "MySQL", ExecutionDialectGate.Intent.DML),
            target,
            { callbackStatus = it.status },
            { error = it },
        )
        assertTrue("已知方言冲突应放行（方言不拦执行）", allowed)
        assertEquals(ExecutionDialectGate.Status.MANUAL_CONFLICT, callbackStatus)
        assertTrue("仍要提示渲染方言", error.orEmpty().contains("MySQL"))
        assertTrue("仍要提示目标方言", error.orEmpty().contains("PostgreSQL"))
    }

    @Test
    fun `unknown dialect on either side still blocks writes`() {
        // 与上一条的边界：**有一边方言未知**时不能放行——写操作可能把 PG 语法打到 MySQL 库上，
        // 属于「无从判断」而非「判断出冲突」。
        val project = projectStub()
        val unknownRender = DataSourceSelector.allowDialectGate(
            project,
            request(ExecutionDialectGate.RenderMode.UNKNOWN, null, ExecutionDialectGate.Intent.DML),
            ExecutionDialectGate.Target(Any(), "ds-1", "prod", "PostgreSQL", "PostgreSQL"),
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
        )
        assertFalse("目标方言未知的写操作仍阻断", unknownTarget)
    }

    @Test
    fun `central handler unknown target blocks explain but query needs explicit confirmation`() {
        val project = projectStub()
        assertFalse(
            DataSourceSelector.allowDialectGate(
                project,
                request(ExecutionDialectGate.RenderMode.AUTO, intent = ExecutionDialectGate.Intent.EXPLAIN),
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
                ),
            )
        } finally {
            DataSourceSelector.unknownTargetQueryConfirmationForTest = null
        }
    }

    @Test
    fun `central handler confirms raw query and blocks raw writes even for known target`() {
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
            ))
            assertEquals(1, confirmations)
            assertFalse(DataSourceSelector.allowDialectGate(
                project,
                ExecutionDialectGate.rawSqlRequest("DELETE FROM t WHERE id = 1"),
                target,
                null,
                null,
            ))
            assertEquals("raw DML must block before any confirmation", 1, confirmations)
        } finally {
            DataSourceSelector.unknownTargetQueryConfirmationForTest = null
        }
    }

    @Test
    fun `source wiring gates selected session before attach and active console before submit mutation`() {
        val root = repoRoot()
        val results = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt").readText()
        val console = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt").readText()
        val sessionGate = results.indexOf("ExecutionDialectGate.fromSession(session)")
        val attach = results.indexOf("prepareInConsole(", sessionGate)
        assertTrue("session gate must precede prepareInConsole/attach", sessionGate >= 0 && attach > sessionGate)
        val consoleGate = console.indexOf("ExecutionDialectGate.fromConsole(console)")
        val firstMutation = console.indexOf("stripGSuffixForSubmit", consoleGate)
        assertTrue("console gate must precede any submit document mutation", consoleGate >= 0 && firstMutation > consoleGate)
    }

    @Test
    fun `submission receipt is threaded through cold reuse queued and native fallback paths`() {
        val root = repoRoot()
        val results = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt").readText()
        val console = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt").readText()
        val panel = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.kt").readText()
        assertTrue(results.contains("dialectGateRequest, onDialectGate, onSubmission,"))
        assertTrue(results.contains("onSubmission = onSubmission"))
        assertTrue(console.contains("onSubmission = selectPrepareSubmission(queuedIntent, onSubmission)"))
        assertEquals("native 与 fallback 均从既有 afterSubmitted 转发回执", 2,
            console.lineSequence().count { it.contains("afterSubmitted(project") && it.contains("onSubmission = onSubmission") })
        val entry = sourceSlice(panel, "private fun executeSql(sql: String)", "private fun showSubmission(")
        assertFalse("发起执行不能预报成功", entry.contains("logToSql.execute.submitted"))
        assertTrue(entry.contains("logToSql.execute.pending"))
        assertTrue(entry.contains("onSubmission = ::showSubmission"))
        val runner = sourceSlice(results, "internal fun DataSourceSelector.runRunner(", "internal fun DataSourceSelector.execSilentProbe(")
        assertFalse("runner 返回不能发成功回执", runner.contains("ExecutionSubmission.SUBMITTED"))
    }

    @Test
    fun `all explicit execution entry points carry requests and uncovered list is empty`() {
        val root = repoRoot()
        // D202（2026-10-31）：EXPLAIN 双通道链已拆到同包 SqlPreviewPanelExecutionExplain.kt，
        // 扫描面按家族铺开（只扫主文件会让 EXPLAIN 意图声明从守卫视野消失）。
        val preview = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt").readText() +
            File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecutionExplain.kt").readText()
        val templates = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/SelectedTableSqlTemplateActions.kt").readText()
        val logs = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/log/LogToSqlPanel.kt").readText()

        assertTrue(preview.contains("ExecutionDialectGate.Intent.DML"))
        assertTrue(preview.contains("ExecutionDialectGate.Intent.EXPLAIN"))
        val structuredGate = preview.indexOf("DataSourceSelector.allowDialectGate")
        val structuredSubmit = preview.indexOf("ExplainPanelChannel.executeStructured")
        assertTrue("structured EXPLAIN must gate before JDBC submit", structuredGate >= 0 && structuredSubmit > structuredGate)

        assertTrue("selected-table EXPLAIN must use the captured dialect renderer",
            templates.contains("renderExplainStatement"))
        assertTrue("selected-table sample must use the captured dialect renderer",
            templates.contains("renderLimit"))
        assertTrue("selected-table rendering must capture the action source/project dialect",
            templates.contains("DialectRegistry.effective(project, sourceFile)"))
        val templateExecuteBody = sourceSlice(
            templates,
            "protected open fun executeTemplate(",
            "/** 目标文本入口",
        )
        assertEquals(1, templateExecuteBody.countOccurrences("dialectGateRequest = request"))
        val logBody = sourceSlice(logs, "private fun executeSql(sql: String)", "/** T-Perf-14")
        assertEquals(1, logBody.countOccurrences("ExecutionDialectGate.rawSqlRequest"))
        assertEquals(1, logBody.countOccurrences("dialectGateRequest = dialectGateRequest"))
        val impactBody = sourceSlice(
            preview,
            "internal fun SqlPreviewPanel.estimateDmlImpact(",
            "/** R42：影响面预演的轻通知",
        )
        assertEquals(0, impactBody.countOccurrences("SqlExecutor.execute("))
        assertTrue(
            "影响面预演必须走只读旁路查询，不能进共享控制台（会替换预览编辑器）",
            impactBody.contains("PreviewSideQuery.run("),
        )
        assertTrue("影响面预演仍须携带方言闸请求", impactBody.contains("dialectGateRequest"))

        val adhocBody = sourceSlice(
            preview,
            "internal fun SqlPreviewPanel.executeAdhocSql(",
            "T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目",
        )
        assertEquals(2, adhocBody.countOccurrences("SqlExecutor.execute("))
        assertEquals(1, adhocBody.countOccurrences("SqlExecutor.executePrepared("))
        assertEquals(3, adhocBody.countOccurrences("dialectGateRequest = dialectGateRequest"))
        assertTrue(ExecutionDialectGate.uncoveredEntryPoints.isEmpty())
    }

    @Test
    fun `UI020 rollback receipt is wired through both full paths and prepared facade`() {
        val root = repoRoot()
        val preview = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt").readText()
        val adhoc = sourceSlice(preview, "internal fun SqlPreviewPanel.executeAdhocSql(",
            "T-RollbackSql P2-F5/F6：UPDATE/DELETE 执行前登记快照条目")
        assertEquals("两个全链落点和一个prepared落点必须均接回执", 3, adhoc.countOccurrences("onSubmission = receipt"))
        val executor = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/SqlExecutor.kt").readText()
        val prepared = sourceSlice(executor, "fun executePrepared(", "/** 控制台是否已就绪")
        assertTrue(prepared.contains("onSubmission = if (onSubmission == null) null else receipt"))
        assertTrue("prepared错误和富错误均须结算未提交", prepared.countOccurrences("receipt(ExecutionSubmission.BLOCKED)") == 2)
        val selector = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt").readText()
        val selectorPrepared = sourceSlice(selector, "fun executePrepared(", "fun selectAndExecute(")
        assertTrue(selectorPrepared.contains("onSubmission = onSubmission"))
        assertFalse("Boolean true不得制造提交回执", selectorPrepared.contains("ExecutionSubmission.SUBMITTED"))
        val dialog = File(root, "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/RollbackSqlDialog.kt").readText()
        val entry = sourceSlice(dialog, "internal fun executeRollback()", "internal fun rollbackSubmissionReceipt()")
        assertTrue(entry.contains("onSubmission = rollbackSubmissionReceipt()"))
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
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }
}
