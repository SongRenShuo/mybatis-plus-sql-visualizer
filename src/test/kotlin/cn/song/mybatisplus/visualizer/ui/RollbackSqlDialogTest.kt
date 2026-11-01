package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.afterSubmitted
import cn.song.mybatisplus.visualizer.sql.SqlServerDialect
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotStore
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import javax.swing.text.AbstractDocument
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.KeyStroke

/**
 * 回滚视图组装测试（T-RollbackSql F3/P2-F5）：评级横幅分层、编辑器内容、底部动作
 * 启用态、粘贴快照生成回滚的委托链。不调用 show()（依赖真实屏幕），只构造与断言。
 */
class RollbackSqlDialogTest : BasePlatformTestCase() {

    private val panel = lazy { SqlPreviewPanel(project) }

    /** 必须是 public：生产反射按 getUrl 取目标方言，private 嵌套类会让 invoke 失败。 */
    class SnapshotTarget {
        @Suppress("unused")
        fun getUrl(): String = "jdbc:mysql://localhost:3306/demo"
    }

    override fun setUp() {
        super.setUp()
        // S2-4 券②：执行回滚/快照查询按钮在 Community 下预判置灰——本类验的是「Ultimate 态
        // 行为」（启用态跟随语句有无），headless 无 Database 标记类需覆写探测（DisplayEditorTest 先例）。
        cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.availabilityOverrideForTest = true
    }

    override fun tearDown() {
        cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.availabilityOverrideForTest = null
        if (panel.isInitialized()) panel.value.dispose()
        super.tearDown()
    }

    private fun newDialog(
        result: RollbackSqlGenerator.RollbackResult,
        kind: String,
        entry: RollbackSnapshotStore.SnapshotEntry? = null,
    ) = RollbackSqlDialog(project, panel.value, result, kind, entry)

    private fun completeInsertResult() = RollbackSqlGenerator.generate(
        "INSERT INTO user (id, name) VALUES (1, 'Tom')",
        primaryKeyColumns = listOf("id"),
    )

    private fun unableUpdateResult() = RollbackSqlGenerator.generate(
        "UPDATE user SET age = 3 WHERE id = 1",
    )

    // ======================== Complete ========================

    fun testCompleteResultShowsExecutableView() {
        val result = completeInsertResult()
        val dialog = newDialog(result, "INSERT")
        try {
            assertEquals(RollbackSqlGenerator.Rating.COMPLETE, dialog.currentResult.rating)
            assertEquals(
                MpSqlVisualizerBundle.message("rollback.banner.complete"),
                dialog.bannerTextForTest(),
            )
            assertEquals("编辑器应载入回滚语句", "DELETE FROM user WHERE id = 1;", dialog.sqlTextForTest())
            assertTrue("Complete 应启用执行动作", dialog.executeActionForTest().isEnabled)
            assertTrue(dialog.copyActionForTest().isEnabled)
            assertTrue("INSERT 无快照工作流", dialog.snapshotQueryLabelForTest().text.isEmpty())
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== Unable UPDATE + 快照工作流 ========================

    fun testUnableUpdateShowsSnapshotWorkflowAndDisablesExecute() {
        val result = unableUpdateResult()
        val dialog = newDialog(result, "UPDATE")
        try {
            assertEquals(RollbackSqlGenerator.Rating.UNABLE, dialog.currentResult.rating)
            assertEquals(MpSqlVisualizerBundle.message("rollback.banner.unable"), dialog.bannerTextForTest())
            assertTrue(
                "UPDATE 应展示快照工作流",
                dialog.snapshotQueryLabelForTest().text.contains("SELECT * FROM user WHERE id = 1"),
            )
            assertFalse("无语句不得启用执行动作", dialog.executeActionForTest().isEnabled)
            assertTrue(dialog.sqlTextForTest().isEmpty())
        } finally {
            dialog.doDispose()
        }
    }

    fun testGenerateFromPasteProducesExecutableRollback() {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        try {
            // 模拟用户粘贴快照查询结果（DBN 网格 TSV：首行列名）
            dialog.pasteAreaForTest().text = "id\tage\n1\t20"
            dialog.pkFieldForTest().text = "id"
            dialog.generateFromPaste()

            assertEquals(RollbackSqlGenerator.Rating.COMPLETE, dialog.currentResult.rating)
            assertEquals("UPDATE user SET age = 20 WHERE id = 1 AND age = 3;", dialog.sqlTextForTest())
            assertTrue("手动粘贴成功后应切到表格视图", dialog.snapshotTableShownForTest())
            assertTrue("生成成功后应启用执行动作", dialog.executeActionForTest().isEnabled)
            assertTrue(
                "横幅应转为可执行",
                dialog.bannerTextForTest() == MpSqlVisualizerBundle.message("rollback.banner.complete"),
            )
        } finally {
            dialog.doDispose()
        }
    }

    // T-RollbackSnapshotAutoFill：执行快照查询走只读旁路，自动填充并生成回滚。

    fun testRunSnapshotQueryUsesSideQueryAndAutoFillsRollback() {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.snapshotQueryTargetForTest = { SnapshotTarget() }
        val raw = cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
            headers = listOf("id", "age"),
            rows = listOf(listOf("1", "20")),
        )
        val oldRunner = PreviewSideQuery.runnerForTest
        PreviewSideQuery.runnerForTest = { _, _, _, scalar, cb ->
            assertFalse("快照查询必须是整表旁路（非标量）", scalar)
            cb(PreviewSideQuery.Result.Table(raw))
        }
        try {
            dialog.runSnapshotQuery()
            assertTrue(
                "快照框应自动填充 TSV：${dialog.pasteAreaForTest().text}",
                dialog.pasteAreaForTest().text.contains("id\tage"),
            )
            assertTrue(
                "回滚 SQL 应自动生成：${dialog.sqlTextForTest()}",
                dialog.sqlTextForTest().contains("UPDATE user SET age = 20"),
            )
            assertTrue("生成成功后应启用执行动作", dialog.executeActionForTest().isEnabled)
            // 未填主键列时整行等值定位存在误匹配风险，告警是设计内披露（文案中性，不再写死 DELETE）。
            assertTrue(
                "整行定位风险应披露：${dialog.warningsTextForTest()}",
                dialog.warningsTextForTest().contains("整行等值谓词"),
            )
        } finally {
            PreviewSideQuery.runnerForTest = oldRunner
            dialog.doDispose()
        }
    }

    fun testSnapshotTableViewShowsColumnsAndRowsAndToggles() {
        // T-RollbackSnapshotView：自动查询结果默认表格展示（列值对应），可切回文本再切回表格。
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.snapshotQueryTargetForTest = { SnapshotTarget() }
        val raw = cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
            headers = listOf("id", "age"),
            rows = listOf(listOf("1", "20")),
        )
        val oldRunner = PreviewSideQuery.runnerForTest
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb ->
            cb(PreviewSideQuery.Result.Table(raw))
        }
        try {
            dialog.runSnapshotQuery()
            assertTrue("自动查询后应切到表格视图", dialog.snapshotTableShownForTest())
            assertEquals("表格行数", 1, dialog.snapshotTableForTest().rowCount)
            assertEquals("表格列数", 2, dialog.snapshotTableForTest().columnCount)
            assertEquals(
                MpSqlVisualizerBundle.message("rollback.snapshot.summary", 1, 2),
                dialog.snapshotSummaryTextForTest(),
            )
            dialog.snapshotToggleForTest().doClick()
            assertFalse("应切回文本视图", dialog.snapshotTableShownForTest())
            dialog.snapshotToggleForTest().doClick()
            assertTrue("应再切回表格视图（按文本重解析）", dialog.snapshotTableShownForTest())
            assertEquals(1, dialog.snapshotTableForTest().rowCount)
        } finally {
            PreviewSideQuery.runnerForTest = oldRunner
            dialog.doDispose()
        }
    }

    fun testRunSnapshotQueryKeepsPreviewEditorUntouched() {
        // 只读旁路：不调用 executeAdhocSql → 预览编辑器文档不变、不换入控制台。
        val p = panel.value
        val sql = "UPDATE user SET age = 3 WHERE id = 1"
        p.showResult(
            cn.song.mybatisplus.visualizer.model.PreviewResult(
                sql, sql, emptyList(), null, null, "user",
                renderedDialect = cn.song.mybatisplus.visualizer.sql.MySqlDialect,
            ),
        )
        p.nativeEditorDoc = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(sql)
        val beforeDoc = p.nativeEditorDoc?.text
        val beforeConsole = p.embeddedConsole
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.snapshotQueryTargetForTest = { SnapshotTarget() }
        val oldRunner = PreviewSideQuery.runnerForTest
        var sideQueryCalled = false
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb ->
            sideQueryCalled = true
            cb(
                PreviewSideQuery.Result.Table(
                    cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
                        listOf("id", "age"), listOf(listOf("1", "20")),
                    ),
                ),
            )
        }
        try {
            dialog.runSnapshotQuery()
            assertTrue("必须走只读旁路查询（旧 executeAdhocSql 路径不会调用 runner）", sideQueryCalled)
            assertEquals("只读快照查询不得替换预览编辑器", beforeDoc, p.nativeEditorDoc?.text)
            assertEquals(sql, p.currentEditorSql())
            assertSame("只读快照查询不得换入控制台", beforeConsole, p.embeddedConsole)
        } finally {
            PreviewSideQuery.runnerForTest = oldRunner
            dialog.doDispose()
            p.clear()
        }
    }

    fun testRunSnapshotQueryWithoutDataSourcePromptsSelectorAndRetries() {
        // 用户真机反馈：无数据源时应直接弹选择器并自动重试，而不是只报错。
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        var target: Any? = null
        var prompted = false
        dialog.snapshotQueryTargetForTest = { target }
        dialog.snapshotPromptForTest = { _, _, _ -> prompted = true }
        dialog.snapshotAwaitBindForTest = { _, _, onBound, _ ->
            target = SnapshotTarget()
            onBound()
        }
        val oldRunner = PreviewSideQuery.runnerForTest
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb ->
            cb(
                PreviewSideQuery.Result.Table(
                    cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
                        listOf("id", "age"), listOf(listOf("1", "20")),
                    ),
                ),
            )
        }
        try {
            dialog.runSnapshotQuery()
            assertTrue("无数据源应先弹数据源选择器", prompted)
            assertTrue("绑定后应自动重试并填充表格", dialog.snapshotTableShownForTest())
            assertTrue(
                "重试后不应残留无数据源告警：${dialog.warningsTextForTest()}",
                !dialog.warningsTextForTest().contains(
                    MpSqlVisualizerBundle.message("rollback.snapshot.noDataSource"),
                ),
            )
        } finally {
            PreviewSideQuery.runnerForTest = oldRunner
            dialog.snapshotPromptForTest = null
            dialog.snapshotAwaitBindForTest = null
            dialog.doDispose()
        }
    }

    fun testRunSnapshotQueryWithoutDataSourceGiveUpShowsHint() {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        try {
            dialog.snapshotQueryTargetForTest = { null }
            dialog.snapshotPromptForTest = { _, _, _ -> }
            dialog.snapshotAwaitBindForTest = { _, _, _, onGiveUp -> onGiveUp() }
            dialog.runSnapshotQuery()
            assertTrue(
                "取消选择后应提示：${dialog.warningsTextForTest()}",
                dialog.warningsTextForTest().contains(
                    MpSqlVisualizerBundle.message("rollback.snapshot.noDataSource"),
                ),
            )
            assertTrue("快照框保持为空", dialog.pasteAreaForTest().text.isEmpty())
        } finally {
            dialog.snapshotPromptForTest = null
            dialog.snapshotAwaitBindForTest = null
            dialog.doDispose()
        }
    }

    // ======================== T-DialectGateExit（UI-040） ========================

    private fun gateRequest(
        rendered: String? = "PostgreSQL",
        mode: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode =
            cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.AUTO,
        canRerender: Boolean = true,
    ) = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request(
        renderMode = mode,
        renderedDialectName = rendered,
        intent = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Intent.QUERY,
        canLosslesslyRerender = canRerender,
        userTextUntouched = true,
    )

    private fun tableResult() = PreviewSideQuery.Result.Table(
        cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
            listOf("id", "age"), listOf(listOf("1", "20")),
        ),
    )

    fun testDialectConflictShowsExitRowAndRerenderRetries() {
        // 方言冲突（PostgreSQL 渲染 vs MySQL 目标）→ 露出两个出口；点重渲染 → 重渲染并重试一次。
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.snapshotQueryTargetForTest = { SnapshotTarget() } // MySQL
        dialog.snapshotGateRequestForTest = { gateRequest() }
        var rerenderedTo: String? = null
        dialog.snapshotRerenderForTest = { name ->
            rerenderedTo = name
            true
        }
        var runnerCalls = 0
        val oldRunner = PreviewSideQuery.runnerForTest
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb ->
            runnerCalls++
            cb(tableResult())
        }
        try {
            dialog.runSnapshotQuery()
            assertTrue("方言冲突应露出出口行", dialog.gateExitRowVisibleForTest())
            assertTrue("重渲染按钮应可见", dialog.rerenderButtonForTest().isVisible)
            assertTrue("切换数据源按钮应可见", dialog.switchSourceButtonForTest().isVisible)
            assertEquals("阻断时不得提交查询", 0, runnerCalls)

            // 重渲染成功后，重试用的请求已是目标方言 → 匹配并填表
            dialog.snapshotGateRequestForTest = { gateRequest(rendered = "MySQL") }
            dialog.rerenderButtonForTest().doClick()

            assertEquals("应按目标数据源方言重渲染", "MySQL", rerenderedTo)
            assertEquals("重渲染后应重试一次", 1, runnerCalls)
            assertTrue(
                "重试成功应填充快照：${dialog.pasteAreaForTest().text}",
                dialog.pasteAreaForTest().text.contains("id\tage"),
            )
            assertFalse("成功后应收起出口行", dialog.gateExitRowVisibleForTest())
        } finally {
            PreviewSideQuery.runnerForTest = oldRunner
            dialog.snapshotGateRequestForTest = null
            dialog.snapshotRerenderForTest = null
            dialog.doDispose()
        }
    }

    fun testManualDialectConflictHidesRerenderButton() {
        // 用户手动选了方言 → 闸门语义是「插件不覆盖手动选择」→ 不给重渲染，只给切换数据源。
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.snapshotQueryTargetForTest = { SnapshotTarget() }
        dialog.snapshotGateRequestForTest = {
            gateRequest(mode = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.MANUAL)
        }
        try {
            dialog.runSnapshotQuery()
            assertTrue("手动方言冲突也应露出出口行", dialog.gateExitRowVisibleForTest())
            assertFalse("手动方言不给重渲染按钮", dialog.rerenderButtonForTest().isVisible)
            assertTrue("切换数据源仍可用", dialog.switchSourceButtonForTest().isVisible)
        } finally {
            dialog.snapshotGateRequestForTest = null
            dialog.doDispose()
        }
    }

    fun testNonDialectBlockHidesExitRow() {
        // 渲染方言未知（非冲突）→ 出口行不出现（重渲染/换目标都解决不了这类阻断）。
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.snapshotQueryTargetForTest = { SnapshotTarget() }
        dialog.snapshotGateRequestForTest = {
            gateRequest(
                rendered = null,
                mode = cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.RenderMode.UNKNOWN,
            )
        }
        try {
            dialog.runSnapshotQuery()
            assertFalse("非方言冲突不露出出口行", dialog.gateExitRowVisibleForTest())
            assertTrue("仍应给出阻断原因：${dialog.warningsTextForTest()}", dialog.warningsTextForTest().isNotEmpty())
        } finally {
            dialog.snapshotGateRequestForTest = null
            dialog.doDispose()
        }
    }

    fun testSwitchDataSourceButtonInvokesPicker() {
        // 「切换数据源」必须真的走选择器链路（而不是静默复用上次目标）。
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.snapshotQueryTargetForTest = { SnapshotTarget() }
        dialog.snapshotGateRequestForTest = { gateRequest() }
        var prompted = false
        dialog.snapshotPromptForTest = { _, _, _ -> prompted = true }
        dialog.snapshotAwaitBindForTest = { _, _, _, onGiveUp -> onGiveUp() }
        try {
            dialog.runSnapshotQuery()
            assertTrue("前置：方言冲突露出出口行", dialog.gateExitRowVisibleForTest())
            dialog.switchSourceButtonForTest().doClick()
            assertTrue("切换数据源应触发选择器", prompted)
        } finally {
            dialog.snapshotGateRequestForTest = null
            dialog.snapshotPromptForTest = null
            dialog.snapshotAwaitBindForTest = null
            dialog.doDispose()
        }
    }

    fun testRunSnapshotQueryEmptyResultShowsHint() {        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.snapshotQueryTargetForTest = { SnapshotTarget() }
        val oldRunner = PreviewSideQuery.runnerForTest
        PreviewSideQuery.runnerForTest = { _, _, _, _, cb ->
            cb(
                PreviewSideQuery.Result.Table(
                    cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult(
                        listOf("id", "age"), emptyList(),
                    ),
                ),
            )
        }
        try {
            dialog.runSnapshotQuery()
            assertTrue(
                "空结果应提示：${dialog.warningsTextForTest()}",
                dialog.warningsTextForTest().contains(
                    MpSqlVisualizerBundle.message("rollback.snapshot.emptyResult"),
                ),
            )
            assertEquals("空结果不生成回滚", RollbackSqlGenerator.Rating.UNABLE, dialog.currentResult.rating)
        } finally {
            PreviewSideQuery.runnerForTest = oldRunner
            dialog.doDispose()
        }
    }

    fun testGenerateFromPasteRejectsNonTsv() {        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        try {
            dialog.pasteAreaForTest().text = "只有一行无制表符"
            dialog.generateFromPaste()
            assertEquals("解析失败不更新结果", RollbackSqlGenerator.Rating.UNABLE, dialog.currentResult.rating)
            assertFalse(dialog.executeActionForTest().isEnabled)
            assertTrue(dialog.warningsTextForTest().contains(MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed")))
        } finally {
            dialog.doDispose()
        }
    }

    fun testGenerateFromPasteDeleteBackfillInsert() {
        val deleteResult = RollbackSqlGenerator.generate("DELETE FROM user WHERE id = 1")
        val dialog = newDialog(deleteResult, "DELETE")
        try {
            dialog.pasteAreaForTest().text = "id\tname\n1\tTom"
            dialog.generateFromPaste()
            assertEquals(RollbackSqlGenerator.Rating.PARTIAL, dialog.currentResult.rating)
            assertEquals("INSERT INTO user (id, name) VALUES (1, 'Tom');", dialog.sqlTextForTest())
            assertTrue("快照回填执行动作应启用", dialog.executeActionForTest().isEnabled)
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== UI018：快照派生产物失效 ========================

    private fun assertSnapshotUnavailable(dialog: RollbackSqlDialog) {
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, dialog.currentResult.rating)
        assertTrue("执行入口共用的内存模型必须清空，不是只灰按钮", dialog.currentResult.statements.isEmpty())
        assertEquals("编辑器及键盘复制源不得保留旧 SQL", "", dialog.sqlTextForTest())
        assertEquals(MpSqlVisualizerBundle.message("rollback.banner.unable"), dialog.bannerTextForTest())
        assertFalse(dialog.executeActionForTest().isEnabled)
        assertFalse(dialog.copyActionForTest().isEnabled)
        var confirmations = 0
        TestDialogManager.setTestDialog(TestDialog {
            confirmations++
            Messages.NO // 即使摘修也绝不放行数据库执行。
        }, testRootDisposable)
        dialog.executeRollback() // 绕过禁用按钮，直接验证生产执行入口。
        assertEquals("失效模型不得进入 DML 确认链", 0, confirmations)
        assertFalse("直接调用复制入口也必须拒绝", dialog.copyRollback())
    }

    fun testUI018DeleteSnapshotEditInvalidatesBeforeGenerateAndRequiresFreshSuccess() {
        val dialog = newDialog(RollbackSqlGenerator.generate("DELETE FROM user WHERE id = 71"), "DELETE")
        try {
            dialog.pasteAreaForTest().text = "id\n71"
            dialog.pkFieldForTest().text = "id"
            dialog.generateFromPaste()
            assertEquals("INSERT INTO user (id) VALUES (71);", dialog.sqlTextForTest())
            assertTrue(dialog.executeActionForTest().isEnabled)

            dialog.pasteAreaForTest().text = "invalid_snapshot"
            assertSnapshotUnavailable(dialog)
            dialog.generateFromPaste()
            assertSnapshotUnavailable(dialog)
            assertTrue(dialog.warningsTextForTest().contains(MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed")))

            // 改回原输入也不复活缓存，必须再次成功生成。
            dialog.pasteAreaForTest().text = "id\n71"
            assertSnapshotUnavailable(dialog)
            dialog.generateFromPaste()
            assertEquals("INSERT INTO user (id) VALUES (71);", dialog.sqlTextForTest())
            assertTrue(dialog.executeActionForTest().isEnabled)
            assertTrue(dialog.copyActionForTest().isEnabled)
            assertEquals("SELECT * FROM user WHERE id = 71;", dialog.currentResult.snapshotSelectSql)
        } finally {
            dialog.doDispose()
        }
    }

    fun testUI018PrimaryKeyEditInvalidatesAndUpdateCanRegenerateWithOriginalMetadata() {
        val original = unableUpdateResult()
        val dialog = newDialog(original, "UPDATE")
        try {
            dialog.pasteAreaForTest().text = "id\tage\n1\t20"
            dialog.pkFieldForTest().text = "id"
            dialog.generateFromPaste()
            assertEquals("UPDATE user SET age = 20 WHERE id = 1 AND age = 3;", dialog.sqlTextForTest())
            dialog.pkFieldForTest().text = ""
            assertSnapshotUnavailable(dialog)
            dialog.pkFieldForTest().text = "id"
            assertSnapshotUnavailable(dialog)
            dialog.generateFromPaste()
            assertEquals("UPDATE user SET age = 20 WHERE id = 1 AND age = 3;", dialog.sqlTextForTest())
            dialog.pasteAreaForTest().text = "id\tage\n1\t19"
            assertSnapshotUnavailable(dialog)
            dialog.generateFromPaste()
            assertEquals("UPDATE user SET age = 19 WHERE id = 1 AND age = 3;", dialog.sqlTextForTest())
            assertEquals(original.setColumns, dialog.currentResult.setColumns)
            assertEquals(original.setValues, dialog.currentResult.setValues)
            assertEquals(original.snapshotSelectSql, dialog.currentResult.snapshotSelectSql)
            assertTrue(dialog.executeActionForTest().isEnabled)
        } finally {
            dialog.doDispose()
        }
    }

    /** 隔离输入监听，单独钉住“生成失败先撤旧模型”的防线，避免监听让失败分支测试空绿。 */
    fun testUI018FailedGenerationInvalidatesEvenWithoutInputNotification() {
        assertFailedGenerationInvalidates("DELETE", "invalid_snapshot")
    }

    fun testUI018MissingColumnGenerationInvalidatesEvenWithoutInputNotification() {
        assertFailedGenerationInvalidates("UPDATE", "id\n71")
    }

    private fun assertFailedGenerationInvalidates(kind: String, pasted: String) {
        run {
            val original = RollbackSqlGenerator.generate(
                if (kind == "DELETE") "DELETE FROM user WHERE id = 71" else "UPDATE user SET age = 3 WHERE id = 71",
            )
            val dialog = newDialog(original, kind)
            try {
                dialog.pasteAreaForTest().text = "id\tage\n71\t20"
                dialog.pkFieldForTest().text = "id"
                dialog.generateFromPaste()
                assertTrue(dialog.currentResult.statements.isNotEmpty())
                val document = dialog.pasteAreaForTest().document as AbstractDocument
                val listeners = document.documentListeners
                listeners.forEach { document.removeDocumentListener(it) }
                try {
                    dialog.pasteAreaForTest().text = pasted
                } finally {
                    listeners.forEach { document.addDocumentListener(it) }
                }
                assertTrue("故意绕开监听，旧模型必须仍在，才有失败分支判别力", dialog.currentResult.statements.isNotEmpty())
                dialog.generateFromPaste()
                assertSnapshotUnavailable(dialog)
                val expectedWarning = if (kind == "DELETE") {
                    MpSqlVisualizerBundle.message("rollback.snapshot.parseFailed")
                } else {
                    MpSqlVisualizerBundle.message("rollback.snapshot.columnMismatch", "age")
                }
                assertTrue(dialog.warningsTextForTest().contains(expectedWarning))
            } finally {
                dialog.doDispose()
            }
        }
    }

    fun testUI018KeyboardCopyCannotExposeInvalidatedSql() {
        val dialog = newDialog(RollbackSqlGenerator.generate("DELETE FROM user WHERE id = 71"), "DELETE")
        try {
            dialog.pasteAreaForTest().text = "id\n71"
            dialog.generateFromPaste()
            val editor = dialog.editorForTest()!!
            editor.selectionModel.setSelection(0, editor.document.textLength)
            dialog.pasteAreaForTest().text = "invalid_snapshot"
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            val component = editor.component
            val key = component.inputMap.get(KeyStroke.getKeyStroke("control C"))
            component.actionMap.get(key).actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, key.toString()))
            assertEquals("键盘复制源已清空，不能复制上一次选区", "", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        } finally {
            dialog.doDispose()
        }
    }

    fun testUI018NonSnapshotCompleteAndPartialRollbackStillReachConfirmation() {
        for (result in listOf(completeInsertResult(), RollbackSqlGenerator.generate("INSERT INTO user (id) VALUES (71)"))) {
            val dialog = newDialog(result, "INSERT")
            try {
                // 无快照工作流的原始回滚不应因隐藏输入或误调生成入口失效。
                dialog.pasteAreaForTest().text = "invalid_snapshot"
                dialog.pkFieldForTest().text = "id"
                dialog.generateFromPaste()
                assertEquals(result, dialog.currentResult)
                assertEquals(result.statements.joinToString("\n"), dialog.sqlTextForTest())
                assertTrue(dialog.executeActionForTest().isEnabled)
                assertTrue(dialog.copyActionForTest().isEnabled)
                val confirmations = mutableListOf<String>()
                TestDialogManager.setTestDialog(TestDialog {
                    confirmations += it
                    Messages.NO // 正对照只走到确认入口，不写库。
                }, testRootDisposable)
                dialog.executeRollback()
                assertEquals("确认拦截器正对照，证明失效用例的零次并非环境不可达", 1, confirmations.size)
            } finally {
                dialog.doDispose()
            }
        }
    }

    // ======================== UI020：真实提交回执，不以调用返回冒充成功 ========================

    private fun registeredDelete(): RollbackSnapshotStore.SnapshotEntry =
        project.getService(RollbackSnapshotStore::class.java).register(
            "DELETE FROM user WHERE id = 71", "user", "SELECT * FROM user WHERE id = 71;",
        )

    private fun snapshotDialog(entry: RollbackSnapshotStore.SnapshotEntry) =
        newDialog(RollbackSqlGenerator.generate(entry.sql), "DELETE", entry).also {
            it.pasteAreaForTest().text = "id\n71"
            it.generateFromPaste()
        }

    fun testUI020DmlNoLeavesRegisteredEntryAndBannerUnchanged() {
        val store = project.getService(RollbackSnapshotStore::class.java)
        val entry = registeredDelete()
        val dialog = snapshotDialog(entry)
        try {
            val before = dialog.bannerTextForTest()
            var confirmations = 0
            TestDialogManager.setTestDialog(TestDialog { confirmations++; Messages.NO }, testRootDisposable)
            dialog.executeRollback()
            assertEquals(1, confirmations)
            assertEquals(before, dialog.bannerTextForTest())
            assertSame("No 不得复制或修改原快照条目", entry, store.findBySql(entry.sql))
            assertEquals(RollbackSnapshotStore.Status.REGISTERED, store.findBySql(entry.sql)!!.status)
        } finally {
            dialog.doDispose()
            store.clearForTest()
        }
    }

    fun testUI020DmlNoWithoutSnapshotEntryDoesNotClaimSubmitted() {
        val dialog = newDialog(completeInsertResult(), "INSERT")
        try {
            val before = dialog.bannerTextForTest()
            TestDialogManager.setTestDialog(TestDialog { Messages.NO }, testRootDisposable)
            dialog.executeRollback()
            assertEquals(before, dialog.bannerTextForTest())
        } finally { dialog.doDispose() }
    }

    fun testUI020NonPreparedUnavailableBackendDoesNotMarkSubmitted() {
        // 本例验「真实无 Database 插件」的阻断链：本类 setUp 为验 Ultimate 态启用态而覆写了探测，
        // 这里必须复位成真实探测（沙盒恒 false），否则用例变成伪验证。
        cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.availabilityOverrideForTest = null
        try {
            assertFalse("本例验证无Database插件的真实阻断，不伪称验证数据库提交", cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable())
            val store = project.getService(RollbackSnapshotStore::class.java)
            val entry = registeredDelete()
            val dialog = snapshotDialog(entry)
            try {
                val before = dialog.bannerTextForTest()
                TestDialogManager.setTestDialog(TestDialog { Messages.YES }, testRootDisposable)
                dialog.executeRollback()
                assertEquals(before, dialog.bannerTextForTest())
                assertSame(entry, store.findBySql(entry.sql))
            } finally {
                dialog.doDispose()
                store.clearForTest()
            }
        } finally {
            // 还原类级 Ultimate 态覆写（tearDown 亦复位，此处保证同类后续用例不受影响）
            cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.availabilityOverrideForTest = true
        }
    }

    fun testUI020CancelledOrBlockedReceiptCannotBeOverwrittenByLateSubmitted() {
        val store = project.getService(RollbackSnapshotStore::class.java)
        for (outcome in listOf(
            cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.CANCELLED,
            cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.BLOCKED,
        )) {
            val entry = registeredDelete()
            val dialog = snapshotDialog(entry)
            try {
                val before = dialog.bannerTextForTest()
                val receipt = dialog.rollbackSubmissionReceipt()
                receipt(outcome)
                receipt(cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.SUBMITTED)
                assertEquals(before, dialog.bannerTextForTest())
                assertSame(entry, store.findBySql(entry.sql))
            } finally {
                dialog.doDispose()
                store.clearForTest()
            }
        }
    }

    private fun submitThroughProductionReceipt(receipt: (cn.song.mybatisplus.visualizer.execute.ExecutionSubmission) -> Unit) {
        val selector = cn.song.mybatisplus.visualizer.execute.DataSourceSelector
        val before = selector.swapGeneration
        try {
            // 调真实提交收口；不直接调用成功状态。无原生console/ui，不执行SQL、不启轮询。
            selector.afterSubmitted(project, null, Any(), null, null,
                com.intellij.openapi.diagnostic.Logger.getInstance(javaClass), onSubmission = receipt)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        } finally { selector.swapGeneration = before }
    }

    fun testUI020OnlyActualSubmissionMarksOnceAndLateErrorsDoNotUndoSubmission() {
        val store = project.getService(RollbackSnapshotStore::class.java)
        val entry = registeredDelete()
        val dialog = snapshotDialog(entry)
        try {
            val before = dialog.bannerTextForTest()
            val receipt = dialog.rollbackSubmissionReceipt()
            assertSame("创建请求/等待无超时，也不是已提交", entry, store.findBySql(entry.sql))
            assertEquals(before, dialog.bannerTextForTest())
            submitThroughProductionReceipt(receipt)
            val submitted = store.findBySql(entry.sql)!!
            assertEquals(RollbackSnapshotStore.Status.ROLLBACK_EXECUTED, submitted.status)
            assertEquals(MpSqlVisualizerBundle.message("rollback.banner.executed"), dialog.bannerTextForTest())
            submitThroughProductionReceipt(receipt)
            receipt(cn.song.mybatisplus.visualizer.execute.ExecutionSubmission.BLOCKED)
            assertSame("重复回执不得再次调用mark生成副本；提交后错误不抹掉真实提交事实", submitted, store.findBySql(entry.sql))
        } finally {
            dialog.doDispose()
            store.clearForTest()
        }
    }

    fun testUI020DisposedDialogStillRecordsLateSubmissionExactlyOnceWithoutUpdatingUi() {
        val store = project.getService(RollbackSnapshotStore::class.java)
        val entry = registeredDelete()
        val dialog = snapshotDialog(entry)
        val before = dialog.bannerTextForTest()
        val receipt = dialog.rollbackSubmissionReceipt()
        dialog.doDispose()
        try {
            submitThroughProductionReceipt(receipt)
            val submitted = store.findBySql(entry.sql)!!
            assertEquals(RollbackSnapshotStore.Status.ROLLBACK_EXECUTED, submitted.status)
            assertEquals("关闭后只留业务事实，不更新UI", before, dialog.bannerTextForTest())
            submitThroughProductionReceipt(receipt)
            assertSame(submitted, store.findBySql(entry.sql))
        } finally { store.clearForTest() }
    }

    fun testUI020EditedSnapshotDoesNotShowOldRequestSubmissionBanner() {
        val store = project.getService(RollbackSnapshotStore::class.java)
        val entry = registeredDelete()
        val dialog = snapshotDialog(entry)
        try {
            val receipt = dialog.rollbackSubmissionReceipt()
            dialog.pasteAreaForTest().text = "invalid_snapshot"
            submitThroughProductionReceipt(receipt)
            assertEquals("提交是旧请求的事实，仍应留痕", RollbackSnapshotStore.Status.ROLLBACK_EXECUTED, store.findBySql(entry.sql)!!.status)
            assertEquals(MpSqlVisualizerBundle.message("rollback.banner.unable"), dialog.bannerTextForTest())
            assertFalse(dialog.executeActionForTest().isEnabled)
        } finally {
            dialog.doDispose()
            store.clearForTest()
        }
    }

    // ======================== O-59 T-IIR-02：回滚侧 identity 包装 ========================

    /**
     * O-59 T-IIR-02 **fail-safe 半项**：表结构取不到时**不得包装**，产物与改前逐字节相同。
     *
     * 本环境拿不到表结构（单测无数据源，且 `DatabaseSchemaResolver` 的 project 级入口还要求
     * Database 插件可用——实测 `columnMeta`/`columnNamesCached` 在本测试里恒为空，而同一替身下
     * `resolveFromSources` 直调能返回 `autoGenerated=true`），故这里钉的是降级侧：
     * `autoGeneratedColumnsOf` 返回空 → `wrapIdentity` 假 → 无包装。与 O-52 D1=A 的
     * 「未知不臆造」契约一致（宁可少包装，也不误包装）。
     *
     * **另一侧（解析到自增列 → 成对包装）在本环境验不了**，证据链由两处拼成：
     * [cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolverAutoGeneratedTest] 在解析器层
     * 钉住「自增列会被收录且带 autoGenerated=true」，[RollbackSqlPanelTest] 钉住「登记点带方言」，
     * 端到端只剩真机（O-59 计划 §五-1 的 SQL Server 项）。
     */
    fun testO59TIIR02_表结构取不到时不包装_产物与改前逐字节相同() {
        val deleteResult = RollbackSqlGenerator.generate("DELETE FROM user WHERE id = 1")
        val entry = RollbackSnapshotStore.SnapshotEntry(
            executionId = 1L,
            sql = "DELETE FROM user WHERE id = 1",
            targetTable = "user",
            snapshotSelectSql = "SELECT * FROM user WHERE id = 1;",
            // 方言取 SQL Server（要包装的方言）却仍不包装——差异只能来自 identity 列解析不到
            dialect = SqlServerDialect,
            identityColumns = emptyList(),
        )
        val dialog = newDialog(deleteResult, "DELETE", entry)
        try {
            dialog.pasteAreaForTest().text = "id\tname\n1\tTom"
            dialog.generateFromPaste()
            assertEquals(
                "表结构取不到 → 不包装（SQL Server 方言亦然），产物与改前逐字节相同",
                "INSERT INTO user (id, name) VALUES (1, 'Tom');",
                dialog.sqlTextForTest(),
            )
            assertFalse(
                "不得出现任何包装痕迹: " + dialog.sqlTextForTest(),
                dialog.sqlTextForTest().contains("SET IDENTITY_INSERT"),
            )
        } finally {
            dialog.doDispose()
        }
    }

    // ======================== F6 留痕：已执行横幅 ========================

    fun testExecutedEntryShowsExecutedBanner() {
        val store = project.getService(RollbackSnapshotStore::class.java)
        store.register(
            "UPDATE user SET age = 3 WHERE id = 1", "user",
            "SELECT * FROM user WHERE id = 1;", listOf("age"),
        )
        store.markRollbackExecuted(store.findBySql("UPDATE user SET age = 3 WHERE id = 1")!!)
        // data class 不可变——markRollbackExecuted 后须经 store 重取新副本（status 已置终态）
        val entry = store.findBySql("UPDATE user SET age = 3 WHERE id = 1")
        val dialog = newDialog(unableUpdateResult(), "UPDATE", entry)
        try {
            assertEquals(
                MpSqlVisualizerBundle.message("rollback.banner.executed"),
                dialog.bannerTextForTest(),
            )
        } finally {
            dialog.doDispose()
            store.clearForTest()
        }
    }

    // ======================== Partial ========================

    fun testPartialResultShowsCountCheck() {
        val result = RollbackSqlGenerator.generate("INSERT INTO user (id, name) VALUES (1, 'Tom')")
        val dialog = newDialog(result, "INSERT")
        try {
            assertEquals(RollbackSqlGenerator.Rating.PARTIAL, dialog.currentResult.rating)
            assertEquals(MpSqlVisualizerBundle.message("rollback.banner.partial"), dialog.bannerTextForTest())
            assertTrue(
                "Partial 应带 COUNT 自查提示",
                dialog.warningsTextForTest().contains(MpSqlVisualizerBundle.message("rollback.warn.fullRowPredicate", "user")),
            )
        } finally {
            dialog.doDispose()
        }
    }

    // ---------- T-SqlCopyShortcuts：键盘复制接线（只读编辑器同样要能 Ctrl+C） ----------

    fun test编辑器CtrlC无选中复制回滚SQL() {
        val dialog = newDialog(completeInsertResult(), "INSERT")
        try {
            val editor = dialog.editorForTest()
            assertNotNull("编辑器应在场", editor)
            val comp = editor!!.component
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            val actionKey = comp.inputMap.get(KeyStroke.getKeyStroke("control C"))
            assertNotEquals("应覆盖 JTextComponent 默认 copy 绑定（共享件接线）", "copy", actionKey)
            comp.actionMap.get(actionKey)
                .actionPerformed(ActionEvent(comp, ActionEvent.ACTION_PERFORMED, actionKey.toString()))
            val clip: String? = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
            assertEquals("只读编辑器无选中 Ctrl+C 应复制整条回滚 SQL", dialog.sqlTextForTest(), clip)
        } finally {
            dialog.doDispose()
        }
    }

    // ---------- T-RollbackSnapshotCsvView：表格增强 / CSV 打开 / 布局修复 ----------

    private fun dialogWithSnapshot(tsv: String): RollbackSqlDialog {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        dialog.pasteAreaForTest().text = tsv
        // 文本 → 表格（按文本重解析），复用既有切换链。
        dialog.snapshotToggleForTest().doClick()
        return dialog
    }

    fun testSnapshotTableSortsAndKeepsModelOrder() {
        val dialog = dialogWithSnapshot("id\tname\n2\tB\n1\tA")
        try {
            val table = dialog.snapshotTableForTest()
            assertEquals("装载后保持原始顺序", "2", table.getValueAt(0, 0))
            table.rowSorter.toggleSortOrder(0)
            assertEquals("列头排序后视图升序", "1", table.getValueAt(0, 0))
            assertEquals("2", table.getValueAt(1, 0))
            assertEquals("排序不得改模型顺序", "2", table.model.getValueAt(0, 0))
            assertEquals("行号按视图顺序重排", "1",
                dialog.snapshotRowHeaderForTest().model.getElementAt(0))
        } finally {
            dialog.doDispose()
        }
    }

    fun testSnapshotCopySelectionCopiesTsv() {
        val clipboard = cn.song.mybatisplus.visualizer.testutil.installFakeClipboard(testRootDisposable)
        val dialog = dialogWithSnapshot("id\tname\n1\tA\n2\tB")
        try {
            val table = dialog.snapshotTableForTest()
            table.setRowSelectionInterval(0, 0)
            table.setColumnSelectionInterval(0, 1)
            assertTrue("复制选择应成功", dialog.copySnapshotSelectionForTest())
            val copied = clipboard.getContents()
                ?.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            assertEquals("选中单元格应复制为 TSV", "1\tA", copied)
        } finally {
            dialog.doDispose()
        }
    }

    fun testSnapshotAutoFitWidensLongColumns() {
        val long = "x".repeat(60)
        val dialog = dialogWithSnapshot("id\tname\n1\t$long")
        try {
            val table = dialog.snapshotTableForTest()
            dialog.autoFitSnapshotColumnsForTest()
            assertTrue("长内容列应被加宽：${table.columnModel.getColumn(1).preferredWidth}",
                table.columnModel.getColumn(1).preferredWidth > 120)
            assertTrue("短内容列应有下限", table.columnModel.getColumn(0).preferredWidth >= 48)
        } finally {
            dialog.doDispose()
        }
    }

    fun testOpenSnapshotInCsvEditorWritesTsvAndCleansUp() {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        val latch = java.util.concurrent.CountDownLatch(1)
        var opened: java.io.File? = null
        var content: String? = null
        dialog.snapshotOpenInEditorForTest = { _, file ->
            opened = file
            content = runCatching { file.readText() }.getOrNull()
            latch.countDown()
        }
        try {
            val tsv = "id\tname\n1\tA"
            dialog.pasteAreaForTest().text = tsv
            dialog.openSnapshotInCsvEditorForTest()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && latch.count > 0) {
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(20)
            }
            assertTrue(
                "临时文件应被写出并打开；warnings=" + dialog.warningsTextForTest() +
                    " tempFiles=" + dialog.snapshotTempFilesForTest().map { it.path },
                latch.count == 0L,
            )
            assertEquals("临时文件内容应为快照 TSV", tsv, content)
            val file = opened!!
            assertTrue(file.exists())
            dialog.doDispose()
            assertTrue("dispose 后未打开的临时文件应清理", !file.exists())
        } finally {
            dialog.snapshotOpenInEditorForTest = null
        }
    }

    fun testSnapshotToggleVisibleAtNarrowWidth() {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        try {
            val center = dialog.centerPanelForTest()
            center.setSize(640, 460)
            layoutTree(center)
            val toggle = dialog.snapshotToggleForTest()
            assertTrue("切换按钮应可见", toggle.isVisible)
            val bounds = javax.swing.SwingUtilities.convertRectangle(toggle.parent, toggle.bounds, center)
            assertTrue("切换按钮宽度应大于 0：$bounds", bounds.width > 0)
            assertTrue("切换按钮应在面板内：$bounds", bounds.x + bounds.width <= center.width + 2)
        } finally {
            dialog.doDispose()
        }
    }

    fun testSnapshotCsvViewButtonsPresent() {
        val dialog = newDialog(unableUpdateResult(), "UPDATE")
        try {
            assertEquals(
                MpSqlVisualizerBundle.message("rollback.snapshot.openCsv"),
                dialog.snapshotOpenCsvForTest().text,
            )
            assertEquals(
                MpSqlVisualizerBundle.message("rollback.snapshot.autofit"),
                dialog.snapshotAutofitForTest().text,
            )
        } finally {
            dialog.doDispose()
        }
    }

    private fun layoutTree(component: java.awt.Component) {
        if (component is java.awt.Container) {
            component.doLayout()
            component.components.forEach { layoutTree(it) }
        }
    }

}
