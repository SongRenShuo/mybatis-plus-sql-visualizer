// 自 master src/test/kotlin/.../ui/PreviewReadonlySnapshotTest.kt 移植
// （k2j 适配：previewSqlForReadonly/currentEditorSql 为 SqlPreviewPanelExecutionKt 静态承载；
//   showResult/clear 走 k2j 的 ResultViewKt/SqlViewKt 门面；PreviewResult 20 参位置构造）
package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
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
 * 漂移到试算/EXPLAIN 语句」）。[SqlPreviewPanelExecutionKt.previewSqlForReadonly] 用
 * `lastRenderedSql` 兜住这一点。
 */
class PreviewReadonlySnapshotTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    /** 普通无模型语料（20 参位置构造，k2j 同 BindStateTest 口径）。 */
    private fun buildResult(previewSql: String): PreviewResult = PreviewResult(
        previewSql,
        previewSql,
        emptyList(),
        null,
        null,
        null,
        null,
        emptyList(),
        emptyMap(),
        null,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        null,
        null,
        null,
        null,
    )

    private fun panel(previewSql: String): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        SqlPreviewPanelResultViewKt.showResult(p, buildResult(previewSql))
        // 模拟「预览已渲染过」——生产由 showSql 三处赋值，这里直接落值等价。
        p.lastRenderedSql = previewSql
        p.nativeEditorDoc = EditorFactory.getInstance().createDocument(previewSql)
        UIUtil.dispatchAllInvocationEvents()
        return p
    }

    /** 进入执行视图（生产由 embedConsole(EXECUTE) 挂头部实现）。 */
    private fun enterExecuteView(p: SqlPreviewPanel) {
        p.executeViewHeader = JBPanel<JBPanel<*>>(BorderLayout())
    }

    /** 模拟控制台文档被执行链改写（生产由 DataSourceSelector 写 exec 文件触发）。 */
    private fun rewriteEditorDocument(p: SqlPreviewPanel, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { p.nativeEditorDoc!!.setText(text) }
    }

    override fun tearDown() {
        runCatching { panel?.let { SqlPreviewPanelSqlViewKt.clear(it) } }
        panel = null
        super.tearDown()
    }

    fun testExecuteViewReturnsPreviewSnapshotNotExecDocument() {
        val preview = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(preview)
        // 执行视图：编辑器文档已被 EXPLAIN 顶掉（这正是形态 A 的真实状态）
        rewriteEditorDocument(p, "EXPLAIN $preview")
        enterExecuteView(p)
        assertEquals(
            "执行视图下只读动作必须拿到预览 SQL，而不是控制台里的 EXPLAIN 语句",
            preview,
            SqlPreviewPanelExecutionKt.previewSqlForReadonly(p),
        )
        // 对照：裸 currentEditorSql 确实已漂移——证明本用例测的是真问题
        assertEquals(
            "对照：currentEditorSql 应已漂移成 EXPLAIN",
            "EXPLAIN $preview",
            SqlPreviewPanelExecutionKt.currentEditorSql(p),
        )
    }

    fun testBackToPreviewFallsBackToEditorText() {
        val preview = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(preview)
        rewriteEditorDocument(p, "EXPLAIN $preview")
        assertEquals(
            "非执行视图一律读真实编辑器（用户手改必须被看见）",
            "EXPLAIN $preview",
            SqlPreviewPanelExecutionKt.previewSqlForReadonly(p),
        )
    }

    fun testUserEditedTextWinsWhenNoExecuteView() {
        val preview = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(preview)
        val edited = "SELECT id FROM t_order WHERE id = 2"
        rewriteEditorDocument(p, edited)
        assertEquals(
            "无执行视图时用户手改优先于渲染快照",
            edited,
            SqlPreviewPanelExecutionKt.previewSqlForReadonly(p),
        )
    }

    fun testExecuteViewWithoutSnapshotFallsBackToEditor() {
        val preview = "SELECT * FROM t_order WHERE id = 1"
        val p = panel(preview)
        p.lastRenderedSql = null
        enterExecuteView(p)
        assertEquals(
            "快照缺失时兜底读编辑器（不返回 null 阻断动作）",
            preview,
            SqlPreviewPanelExecutionKt.previewSqlForReadonly(p),
        )
    }
}
