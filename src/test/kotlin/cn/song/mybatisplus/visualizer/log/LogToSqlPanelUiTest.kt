package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container

/**
 * 日志转 SQL 面板「渲染 / 布局 / 交互」UI 测试（F-44-6 续 · 用户要求「整个插件 UI 用例」）：
 *
 * 此前仅覆盖转换逻辑（LogToSqlPanelTest），编辑器 L&F 与格式切换的真实渲染未测——用户点名
 * 「编辑器 L&F / 格式切换未测」。本测试模拟真实面板打开：
 *  - 面板构建即出输入/结果编辑器（不崩），空态提示接线既有文案键（T-UN-14 迁编辑器原生 placeholder）；
 *  - 真实转换：MyBatis 日志（Preparing/Parameters）经解析 → 参数被替换进 SQL、默认格式化输出含换行；
 *  - 格式切换：关闭格式化 → 同一条 SQL 输出为原样单行（无换行），证明格式开关真实生效；
 *  - 布局：非空标签有正首选尺寸（编辑器在 headless 下 preferredSize 不可靠，跳过，仅断言标签层）。
 */
class LogToSqlPanelUiTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun allComponents(c: Component): List<Component> {
        val list = mutableListOf(c)
        if (c is Container) for (child in c.components) list += allComponents(child)
        return list
    }

    fun test面板构建_编辑器与空态提示可见() {
        val panel = LogToSqlPanel(project)
        try {
            waitForEdt()
            // T-UiNative T-UN-14：空态提示已迁编辑器原生 placeholder（空文档时由编辑器原生绘制），
            // 自摆 hintLabel 删除——断言两区 placeholder 均接线既有文案键
            assertEquals(
                "输入区 placeholder 应接线文案键",
                MpSqlVisualizerBundle.message("log.panel.hint.input"),
                panel.inputPlaceholderForTest(),
            )
            assertEquals(
                "结果区 placeholder 应接线文案键",
                MpSqlVisualizerBundle.message("log.panel.hint.result"),
                panel.resultPlaceholderForTest(),
            )
        } finally {
            panel.dispose()
        }
    }

    fun test转换_格式化输出含换行与参数替换() {
        val panel = LogToSqlPanel(project)
        try {
            val input = "Preparing: SELECT * FROM user WHERE id = ?\nParameters: 1(Integer)"
            panel.setInputForTest(input)
            panel.convertForTest()
            val result = panel.resultTextForTest()

            assertTrue("结果应含 SELECT", result.contains("SELECT"))
            assertTrue("参数应被替换进 SQL（? → 1）", result.contains("1"))
            assertTrue("默认格式化输出应含换行（多行缩进）", result.contains("\n"))
            assertFalse("参数应已被全部替换，结果不应残留占位符 ?", result.contains("?"))
        } finally {
            panel.dispose()
        }
    }

    fun test格式切换_原样输出无换行() {
        val panel = LogToSqlPanel(project)
        try {
            val input = "Preparing: SELECT * FROM user WHERE id = ?\nParameters: 1(Integer)"
            panel.setInputForTest(input)
            panel.convertForTest() // 默认 formatToggled = true
            val formatted = panel.resultTextForTest()
            assertTrue("格式化输出应含换行", formatted.contains("\n"))

            panel.toggleFormatForTest() // formatToggled = false
            panel.convertForTest() // 重新转换以应用新开关
            val raw = panel.resultTextForTest()
            assertTrue("原样输出仍应含 SQL 且参数已替换", raw.contains("SELECT") && raw.contains("1"))
            assertFalse("原样（不格式化）输出不应含换行", raw.contains("\n"))
        } finally {
            panel.dispose()
        }
    }

    fun test布局_非空标签有正首选尺寸() {
        val panel = LogToSqlPanel(project)
        try {
            waitForEdt()
            // T-UiNative T-UN-14：空态灰字提示迁编辑器 placeholder 后，面板常态只有底部状态行
            // 是文字标签——先真实转换一次让状态行有文案，再断言无非空标签渲染塌缩
            panel.setInputForTest("Preparing: SELECT * FROM user WHERE id = ?\nParameters: 1(Integer)")
            panel.convertForTest()
            waitForEdt()
            val nonEmptyLabels = allComponents(panel).filterIsInstance<JBLabel>().filter { it.text.isNotBlank() }
            assertTrue("面板应含非空标签（状态行）", nonEmptyLabels.isNotEmpty())
            val zero = nonEmptyLabels.filter { it.preferredSize.width <= 0 || it.preferredSize.height <= 0 }
            assertEquals("非空标签应有正首选尺寸（无渲染塌缩）: ${zero.map { it.text }}", 0, zero.size)
        } finally {
            panel.dispose()
        }
    }

    /**
     * T-SqlDialectPrewarm：结果区必须挂**插件自研**词法器。
     *
     * 判别力放在 token 类型上：平台 SQL 词法器给出的关键字 token 不是 `MP_SQL_KEYWORD`，把高亮器改回
     * 平台工厂（`SyntaxHighlighterFactory.getSyntaxHighlighter(SQL, …)`）本断言即红——「类型是
     * LexerEditorHighlighter」这种弱断言两边都成立，不能当覆盖。
     */
    fun test结果区挂自研SQL词法器() {
        val panel = LogToSqlPanel(project)
        try {
            panel.setResultForTest("SELECT id FROM user_info")
            waitForEdt()
            val highlighter =
                (panel.resultEditorForTest() as com.intellij.openapi.editor.ex.EditorEx).highlighter
            assertTrue(
                "结果区 highlighter 应为 LexerEditorHighlighter",
                highlighter is com.intellij.openapi.editor.ex.util.LexerEditorHighlighter,
            )
            val iterator = highlighter.createIterator(0)
            assertFalse("token 迭代不应为空", iterator.atEnd())
            assertEquals(
                "首个 token 应为自研词法器的关键字类型（改回平台 SQL 高亮器会变成平台 token）",
                "MP_SQL_KEYWORD",
                iterator.tokenType?.toString(),
            )
        } finally {
            panel.dispose()
        }
    }
}
