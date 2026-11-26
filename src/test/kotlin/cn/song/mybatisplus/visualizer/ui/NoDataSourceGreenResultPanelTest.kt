package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.UIManager

/**
 * D253 T-NoDataSourceGreenResultPanel：无数据源执行失败结果区绿块。
 *
 * 根因（headless 探针实证）：失败收口后 editorHolder.componentCount=0、editor=null；原
 * editorHolder.isOpaque=false 透出父 split 用 UIUtil.getPanelBackground() 涂的底色，主题把该键
 * 解析成亮绿时暴露整块纯绿空白。修复 M1＝editorHolder 自带不透明主题底色（消除透明穿透）；
 * M2＝失败收口在无 console 且 editor 空时重建预览编辑器（恢复格式化 SQL，覆盖空白区）。
 *
 * k2j 移植注：`resolved.result` → `resolved.result()`（Java record 访问器）；`p.releaseEditor()` →
 * `SqlPreviewPanelEditorLifecycleKt.releaseEditor(p)`；`p.showResult(...)` / `p.showTempFileError(...)`
 * 走对应门面；`restoreResultAreaAfterExecFailure(forceSync = true)` → 同门面 2 参形态。
 */
class NoDataSourceGreenResultPanelTest : BasePlatformTestCase() {
    private var panel: SqlPreviewPanel? = null

    override fun tearDown() {
        // 释放测试期间创建/重建的编辑器，避免平台 checkEditorsReleased 断言（幂等）
        panel?.let { runCatching { SqlPreviewPanelEditorLifecycleKt.releaseEditor(it) } }
        panel = null
        super.tearDown()
    }

    private fun newPanel(): SqlPreviewPanel = SqlPreviewPanel(project).also { panel = it }

    private fun plainResult(): PreviewResult {
        val resolved = StandaloneSqlSourceResolver.resolve("SELECT * FROM t_biz WHERE id = \${id}")
        checkNotNull(resolved)
        return resolved.result()
    }

    /** 制造「执行失败确认框释放编辑器后」的空置结果区（探针实测：editor=null、holder 空、无 console）。 */
    private fun simulateEditorReleased(p: SqlPreviewPanel) {
        p.embeddedConsole = null
        SqlPreviewPanelEditorLifecycleKt.releaseEditor(p) // 正确释放（含 EditorFactory 注销），而非裸置 null 造成泄漏
        p.editorHolderForTest().let { holder ->
            while (holder.componentCount > 0) holder.remove(0)
        }
    }

    fun testM1_失败后结果区自绘不透出父容器() {
        val p = newPanel()
        val oldBg = UIManager.getColor("Panel.background")
        UIManager.put("Panel.background", Color.GREEN)
        try {
            assertEquals("sentinel 前置：UIUtil 应解析成绿", Color.GREEN, UIUtil.getPanelBackground())
            SqlPreviewPanelResultViewKt.showResult(p, plainResult())
            simulateEditorReleased(p)
            p.current = null // M1 只测底色不测重建；置 null 让 M2 重建缝跳过，避免异步 editor 泄漏
            SqlPreviewPanelExecutionKt.showTempFileError(p, "未找到可用数据源。")

            val holder = p.editorHolderForTest()
            assertTrue("失败路径 editorHolder 必须自绘，不透出父容器背景", holder.isOpaque)
            assertEquals("结果区背景与主题面板色一致（不硬编码、不残留异常色）",
                UIUtil.getPanelBackground(), holder.background)
            assertEquals("错误态仍在", "ERROR", p.resultPanelForTest().stateForTest())
            assertTrue("错误文案在场", p.resultPanelForTest().currentErrorForTest()?.contains("未找到可用数据源") == true)
        } finally {
            UIManager.put("Panel.background", oldBg)
        }
    }

    fun testM2_失败后空置结果区触发重建() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, plainResult())
        simulateEditorReleased(p)
        check(p.editor == null && p.editorHolderForTest().componentCount == 0) { "前置：结果区应为空置" }

        // 失败收口的重建缝：forceSync 同步执行（测试跑 EDT，invokeLater 回调泵不到）
        val restored = SqlPreviewPanelExecutionKt.restoreResultAreaAfterExecFailure(p, true)

        assertTrue("空置 + 有最近结果 → 触发重建", restored)
        assertTrue("重建展示的 SQL 含最近预览内容（display mode 可能再格式化）：${p.lastRenderedSqlForTest()}",
            p.lastRenderedSqlForTest()?.contains("t_biz") == true)
    }

    fun testM2_有编辑器在场时不重建() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, plainResult())
        // 有 editor 在场（未模拟释放）时不得重建——守卫避免误覆盖既有展示
        val restored = SqlPreviewPanelExecutionKt.restoreResultAreaAfterExecFailure(p, true)
        assertFalse("有编辑器在场不触发重建", restored)
    }

    fun testM2_无最近结果时不重建() {
        val p = newPanel()
        simulateEditorReleased(p)
        p.current = null
        val restored = SqlPreviewPanelExecutionKt.restoreResultAreaAfterExecFailure(p, true)
        assertFalse("无最近结果不重建（不无中生有）", restored)
    }
}
