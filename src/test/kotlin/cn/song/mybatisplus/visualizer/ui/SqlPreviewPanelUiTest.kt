package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

/**
 * SQL 预览面板「渲染 / 布局 / 大结果」UI 测试（F-44-6 续 · 用户要求「整个插件 UI 用例」）：
 *
 * 此前 SqlPreviewPanelTest 覆盖 showResult 行为逻辑，但「弹窗渲染 / 大结果性能」未作为 UI 回归锁定——
 * 用户点名「SqlPreviewService 弹窗渲染 / JCEF / 大结果性能未测」。本测试在 headless 下（JCEF 不可用，
 * 仅验证非 JCEF 的编辑器渲染与主面板布局稳定）：
 *  - 面板构建 + showResult 将 SQL 渲染进编辑器（current 状态保持）；
 *  - 大结果（超长 SQL）渲染不崩溃、编辑器仍持有完整文本（大结果性能冒烟）；
 *  - 布局：非空标签 / 按钮有正首选尺寸（编辑器 headless 下 preferredSize 不可靠，跳过）。
 */
class SqlPreviewPanelUiTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun allComponents(c: Component): List<Component> {
        val list = mutableListOf(c)
        if (c is Container) for (child in c.components) list += allComponents(child)
        return list
    }

    private fun makeResult(sql: String, compact: String = sql, params: List<String> = emptyList()): PreviewResult =
        PreviewResult(
            sql = sql,
            compactSql = compact,
            unresolvedParams = params,
            paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
            wrapperType = WrapperType.LAMBDA_QUERY,
            tableName = "tbl",
            columns = listOf("id", "name"),
        )

    fun test面板构建与结果渲染_编辑器含SQL() {
        val panel = SqlPreviewPanel(project)
        try {
            val result = makeResult("SELECT id\nFROM tbl")
            panel.showResult(result)
            waitForEdt()
            val stored = requireNotNull(panel.currentForTest())
            // T-DialectInferFix：面板在结果缺省方言决策时补记一条（含 FALLBACK 披露），故按「除决策外逐字一致」比对。
            // 并入 t-sql-insight 后披露决策落面板变量（模型保持原实例身份，供投递失效机制判新旧；
            // 生产链全部读 currentDialectDecision），断言随实现语义迁移。
            assertEquals("showResult 应保存当前结果", result, stored.copy(dialectDecision = null))
            assertNotNull("showResult 应记录方言决策（披露走面板变量）", panel.currentDialectDecision)
            val editor = panel.editorForTest()
            assertNotNull("应挂载编辑器视图", editor)
            val text = editor!!.document.text
            assertTrue("编辑器应渲染出 SELECT", text.contains("SELECT"))
            assertTrue("编辑器应渲染出 FROM tbl", text.contains("FROM tbl"))
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun test大结果渲染_超长SQL不崩溃且完整持有() {
        val panel = SqlPreviewPanel(project)
        try {
            // 构造超长 SQL（数百列）——大结果性能冒烟
            val cols = (1..400).joinToString(", ") { "col_$it" }
            val bigSql = "SELECT $cols\nFROM big_table WHERE id = 1"
            val result = makeResult(bigSql)
            panel.showResult(result)
            waitForEdt()
            val editorText = panel.editorForTest()?.document?.text ?: ""
            assertTrue("大结果应完整渲染进编辑器（含列 col_400）", editorText.contains("col_400"))
            assertTrue("大结果应含 FROM big_table", editorText.contains("FROM big_table"))
            val stored = requireNotNull(panel.currentForTest())
            assertEquals("current 应保存大结果", result, stored.copy(dialectDecision = null))
            assertNotNull("current 应记录方言决策（披露走面板变量）", panel.currentDialectDecision)
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun test布局_非空标签与按钮有正首选尺寸() {
        val panel = SqlPreviewPanel(project)
        try {
            waitForEdt()
            val targets = allComponents(panel).filter {
                (it is JBLabel && it.text.isNotBlank()) || it is JButton
            }
            assertTrue("应存在标签/按钮控件", targets.isNotEmpty())
            val zero = targets.filter { it.preferredSize.width <= 0 || it.preferredSize.height <= 0 }
            assertEquals("非空标签与按钮应有正首选尺寸（无渲染塌缩）: ${zero.map { it::class.simpleName }}", 0, zero.size)
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    // ------------------------------------------------------------------
    // T-ErrorLocateUsability T-ELU-01（O-49）：错误定位按钮「先解析后显隐」
    // ------------------------------------------------------------------

    private fun locateButton(panel: SqlPreviewPanel): JButton? =
        allComponents(panel.resultPanelForTest()).filterIsInstance<JButton>()
            .firstOrNull { it.text == MpSqlVisualizerBundle.message("execute.error.locate") }

    private fun errorInfo() = cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo(message = "boom")

    /**
     * **本任务的核心判据（改前必红）**：本会话尚未登记预览源锚点 → 定位动作返回 null
     * → 面板里的定位按钮**不出现**。改前 `showErrorRichWithLocate` 恒传非 null lambda，
     * 按钮可见但点击静默 return（`project ?: return` / `anchor ?: return`）。
     */
    fun test错误定位_无锚点时不给按钮() {
        val panel = SqlPreviewPanel(project)
        try {
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor = null
            waitForEdt()
            assertNull("锚点缺失时定位动作应为 null", panel.errorLocateActionForTest())
            panel.showErrorRichWithLocateForTest(errorInfo())
            waitForEdt()
            val btn = locateButton(panel)
            assertNotNull("定位按钮组件应存在（仅隐藏）", btn)
            assertFalse("锚点不可解析时定位按钮不得可见", btn!!.isVisible)
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    /** 锚点可解析（预览入口已登记）→ 定位动作非 null 且按钮可见（回归确认，改前改后皆绿）。 */
    fun test错误定位_有锚点时给按钮() {
        val panel = SqlPreviewPanel(project)
        try {
            val vf = myFixture.configureByText("Anchor.java", "class Anchor {}").virtualFile
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor =
                SqlPreviewService.SourceAnchor(vf, 0)
            waitForEdt()
            assertNotNull("锚点可解析时定位动作应非 null", panel.errorLocateActionForTest())
            panel.showErrorRichWithLocateForTest(errorInfo())
            waitForEdt()
            assertTrue("锚点可解析时定位按钮应可见", locateButton(panel)!!.isVisible)
        } finally {
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor = null
            panel.clear()
            panel.dispose()
        }
    }

    /** 源文件已失效（跳不动）同样不给按钮——「跳不动的按钮不给」比「给了点不动」诚实。 */
    fun test错误定位_源文件失效时不给按钮() {
        val panel = SqlPreviewPanel(project)
        try {
            val vf = myFixture.addFileToProject("Gone.java", "class Gone {}").virtualFile
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor =
                SqlPreviewService.SourceAnchor(vf, 0)
            assertNotNull("删除前锚点可解析", panel.errorLocateActionForTest())
            // 删除须在写动作内（VirtualFile.delete 在 262 平台要求写权限）
            @Suppress("DEPRECATION")
            com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> { vf.delete(this) }
            waitForEdt()
            assertNull("源文件失效时定位动作应为 null", panel.errorLocateActionForTest())
        } finally {
            project.getService(SqlPreviewService::class.java).lastPreviewSourceAnchor = null
            panel.clear()
            panel.dispose()
        }
    }
}
