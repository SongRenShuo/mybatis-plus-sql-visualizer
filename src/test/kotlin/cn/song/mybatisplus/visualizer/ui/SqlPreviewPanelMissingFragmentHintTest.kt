package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 结果区「缺少片段」补齐入口测试（T-PreviewActionRow D-5）。
 *
 * ## 为什么是面板级而不是工作台级
 *
 * 补齐链原先只有工作台一份（提示行是工作台私有控件），于是「在弹框中打开」后看到
 * `TODO: missing` 占位的用户无处可补（2026-11-20 用户报障）。下沉到
 * `SqlPreviewPanel` 后两个面自动一致，代价是**必须默认隐藏**：面板被 22 个文件 / 53 处
 * `showResult` 调用点共用，任何"总是可见"的写法都会让 gutter / 日志弹窗多出一行死链接。
 *
 * 三档判据即本类三条用例：
 *  - 有缺失但**没有补齐回调** → 隐藏（无原文可重解析的入口）；
 *  - 有缺失**且有回调** → 可见，文案含 refid，点击回调拿到首个 refid；
 *  - 无缺失（或已清空）→ 隐藏。
 *
 * 桶位：引 `SqlPreviewPanel` 与 `BasePlatformTestCase`，属**平台桶**（不进 testFast 快桶）。
 *
 * k2j 移植注：`PreviewResult` 具名实参 → 21 参位置形态 + `withMissingFragmentIds` 派生；
 * `missingFragmentFillHandler = { … }` → `setMissingFragmentFillHandler(Consumer<String> { … })`
 * （显式泛型防空推断歧义）。
 */
class SqlPreviewPanelMissingFragmentHintTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun tearDown() {
        runCatching { panel?.let { SqlPreviewPanelSqlViewKt.clear(it) } }
        panel = null
        super.tearDown()
    }

    private fun newPanel(): SqlPreviewPanel = SqlPreviewPanel(project).also { panel = it }

    private fun result(missing: List<String> = emptyList()) = PreviewResult(
        "SELECT\n  /* TODO: missing <sql id=\"Base_Column_List\"> */\nFROM t",
        "SELECT * FROM t",
        emptyList(),
        null,
        WrapperType.LAMBDA_QUERY,
        "t",
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
    ).withMissingFragmentIds(missing)

    private fun click(label: javax.swing.JLabel) {
        label.dispatchEvent(
            java.awt.event.MouseEvent(
                label,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false,
                java.awt.event.MouseEvent.BUTTON1,
            ),
        )
    }

    fun test有缺失但无补齐回调时隐藏提示行() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, result(listOf("Base_Column_List")))
        // 真实文件来源（gutter / 日志 / 历史）拿不到原文 → 不注入 handler → 不该出现不可点的链接
        assertFalse("没有补齐回调时不应显示提示行", p.missingFragmentVisibleForTest())
    }

    fun test有回调且有缺失时显示提示行并带refid() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, result(listOf("Base_Column_List")))
        // 先 showResult 再注入 handler——工作台/弹窗的真实顺序（面板 setter 会立刻重算可见性）
        p.setMissingFragmentFillHandler(java.util.function.Consumer<String> { })
        assertTrue("注入回调后应显示提示行", p.missingFragmentVisibleForTest())
        val text = p.missingFragmentTextForTest()
        assertTrue("文案应含 refid，实际：$text", text.contains("Base_Column_List"))
        assertEquals(
            "文案应按面板级键渲染（工作台与弹窗同源）",
            MpSqlVisualizerBundle.message("panel.missingFragment.label", "Base_Column_List"),
            text,
        )
    }

    fun test点击提示行回调首个refid() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, result(listOf("Base_Column_List", "Other_Cols")))
        val asked = mutableListOf<String>()
        p.setMissingFragmentFillHandler(java.util.function.Consumer<String> { asked.add(it) })
        click(p.missingFragmentLabelForTest())
        assertEquals("一次只补一个：应回调首个 refid", listOf("Base_Column_List"), asked)
    }

    fun test载荷无缺失时即使有回调也不显示() {
        val p = newPanel()
        p.setMissingFragmentFillHandler(java.util.function.Consumer<String> { fail("无缺失时不应有补齐请求") })
        SqlPreviewPanelResultViewKt.showResult(p, result(emptyList()))
        assertFalse("无缺失应隐藏", p.missingFragmentVisibleForTest())
    }

    fun test清空后提示行隐藏() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, result(listOf("Base_Column_List")))
        p.setMissingFragmentFillHandler(java.util.function.Consumer<String> { })
        assertTrue(p.missingFragmentVisibleForTest())
        SqlPreviewPanelSqlViewKt.clear(p)
        assertFalse("清空后应收起提示行", p.missingFragmentVisibleForTest())
    }
}
