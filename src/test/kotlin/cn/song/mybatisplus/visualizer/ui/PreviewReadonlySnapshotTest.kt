package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout

/**
 * T-ExplainConsoleFirst W2（2026-10-24）：只读动作取词在**执行视图**下必须回到预览快照。
 *
 * 背景：执行计划走内嵌原生控制台后，EXPLAIN 语句会被写进 `mpsql-exec.sql` 的 document，
 * 而 `nativeEditorDoc` 指向它 ⇒ `currentEditorSql()` 从「预览 SQL」漂移成「EXPLAIN …」。
 * `1a1cf724` 正是因此把默认通道换成旁路查询（commit message 原文「会让 currentEditorSql()
 * 漂移到试算/EXPLAIN 语句」）。[previewSqlForReadonly] 用 `lastRenderedSql` 兜住这一点。
 */
class PreviewReadonlySnapshotTest : BasePlatformTestCase() {

    private fun panel(previewSql: String): SqlPreviewPanel = SqlPreviewPanel(project).also {
        Disposer.register(testRootDisposable, it)
        it.showResult(PreviewResult(previewSql, previewSql, emptyList(), null, null, null, renderedDialect = MySqlDialect))
        // 模拟「预览已渲染过」——生产由 showSql 三处赋值，这里直接落值等价。
        it.lastRenderedSql = previewSql
        it.nativeEditorDoc = EditorFactory.getInstance().createDocument(previewSql)
        UIUtil.dispatchAllInvocationEvents()
    }

    /** 进入执行视图（生产由 embedConsole(EXECUTE) 挂头部实现）。 */
    private fun SqlPreviewPanel.enterExecuteView() {
        executeViewHeader = JBPanel<JBPanel<*>>(BorderLayout())
    }

    /** 模拟控制台文档被执行链改写（生产由 DataSourceSelector 写 exec 文件触发）。 */
    private fun SqlPreviewPanel.rewriteEditorDocument(text: String) {
        WriteCommandAction.runWriteCommandAction(project) { nativeEditorDoc!!.setText(text) }
    }

    fun testExecuteViewReturnsPreviewSnapshotNotExecDocument() {
        val preview = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(preview)
        // 执行视图：编辑器文档已被 EXPLAIN 顶掉（这正是形态 A 的真实状态）
        p.rewriteEditorDocument("EXPLAIN $preview")
        p.enterExecuteView()
        try {
            assertEquals(
                "执行视图下只读动作必须拿到预览 SQL，而不是控制台里的 EXPLAIN 语句",
                preview,
                p.previewSqlForReadonly(),
            )
            // 对照：裸 currentEditorSql 确实已漂移——证明本用例测的是真问题
            assertEquals("对照：currentEditorSql 应已漂移成 EXPLAIN", "EXPLAIN $preview", p.currentEditorSql())
        } finally {
            p.clear()
        }
    }

    fun testBackToPreviewFallsBackToEditorText() {
        val preview = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(preview)
        p.rewriteEditorDocument("EXPLAIN $preview")
        try {
            assertEquals(
                "非执行视图一律读真实编辑器（用户手改必须被看见）",
                "EXPLAIN $preview",
                p.previewSqlForReadonly(),
            )
        } finally {
            p.clear()
        }
    }

    fun testUserEditedTextWinsWhenNoExecuteView() {
        val preview = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(preview)
        val edited = "SELECT id FROM t_order WHERE id = 2"
        p.rewriteEditorDocument(edited)
        try {
            assertEquals("无执行视图时用户手改优先于渲染快照", edited, p.previewSqlForReadonly())
        } finally {
            p.clear()
        }
    }

    fun testExecuteViewWithoutSnapshotFallsBackToEditor() {
        val preview = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(preview)
        p.lastRenderedSql = null
        p.enterExecuteView()
        try {
            assertEquals(
                "快照缺失时兜底读编辑器（不返回 null 阻断动作）",
                preview,
                p.previewSqlForReadonly(),
            )
        } finally {
            p.clear()
        }
    }
}
