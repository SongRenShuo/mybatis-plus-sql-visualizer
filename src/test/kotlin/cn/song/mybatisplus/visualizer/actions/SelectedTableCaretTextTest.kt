package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull

/**
 * T-SqlTemplateCaret：快捷模板「光标处表名」三层测试。
 *
 *  1. [identifierAtCaret]（纯函数）：光标处标识符的词边界扫描——含引号包裹、限定名跨点、
 *     空白/标点/单引号字符串边界、文件首尾、越界钳制；
 *  2. [resolveTemplateText]（纯函数）：非空选区优先、空白选区/无选区退化到光标处；
 *  3. [editorTemplateText]（真编辑器，`myFixture`）：把前两层接到 `Editor` 上，
 *     验「有选区取选区、无选区取光标处、光标在空白处禁用」。
 *
 * 三层都不依赖真机/沙盒：第 1、2 层是纯逻辑，第 3 层用测试框架的内存编辑器。
 */
class SelectedTableCaretTextTest : BasePlatformTestCase() {

    /** 用 `|` 标记光标：返回该光标处取到的标识符。 */
    private fun id(textWithCaret: String): String? {
        val caret = textWithCaret.indexOf('|')
        require(caret >= 0) { "用例文本缺少光标标记 |" }
        return identifierAtCaret(textWithCaret.removeRange(caret, caret + 1), caret)
    }

    // ---------- 第 1 层：identifierAtCaret 词边界扫描 ----------

    fun `test 光标在标识符中间`() {
        assertEquals("car_device_info", id("FROM car_dev|ice_info"))
    }

    fun `test 光标在标识符首`() {
        assertEquals("car_device_info", id("FROM |car_device_info"))
    }

    fun `test 光标在标识符尾`() {
        assertEquals("car_device_info", id("FROM car_device_info|"))
    }

    fun `test 光标压空白禁用`() {
        assertNull(id("FROM |  t_order"))
    }

    fun `test 光标压标点禁用`() {
        assertNull(id("SELECT *,| FROM t"))
    }

    fun `test 反引号标识符连引号一并取`() {
        assertEquals("`car_device_info`", id("FROM `car_dev|ice_info`"))
    }

    fun `test 双引号标识符连引号一并取`() {
        assertEquals("\"car_device_info\"", id("FROM \"car_dev|ice_info\""))
    }

    fun `test 方括号标识符连引号一并取`() {
        assertEquals("[car_device_info]", id("FROM [car_dev|ice_info]"))
    }

    fun `test 光标压在开引号上`() {
        assertEquals("`car_device_info`", id("FROM |`car_device_info`"))
    }

    fun `test 光标压在闭引号之后`() {
        assertEquals("`car_device_info`", id("FROM `car_device_info`| "))
    }

    fun `test 单引号字符串不连引号`() {
        assertEquals("car_device_info", id("= 'car_dev|ice_info'"))
    }

    fun `test 注释内标识符仍可取`() {
        assertEquals("car_device_info", id("-- car_dev|ice_info"))
    }

    fun `test 注释符处禁用`() {
        assertNull(id("--|"))
    }

    fun `test 限定名跨点取全`() {
        assertEquals("mpj_demo.t_order", id("FROM mpj_demo.t_|order"))
    }

    fun `test 限定名带引号跨点取全`() {
        assertEquals("`db`.`t_order`", id("FROM `db`.`t_|order`"))
    }

    fun `test 星号不并入限定名`() {
        assertEquals("t_order", id("FROM t_order|.*"))
    }

    fun `test 文件首标识符`() {
        assertEquals("SELECT", id("|SELECT * FROM t"))
    }

    fun `test 文件尾标识符`() {
        assertEquals("t_order", id("SELECT * FROM t_order|"))
    }

    fun `test 空文档返回null`() {
        assertNull(identifierAtCaret("", 0))
    }

    fun `test 光标越界钳制`() {
        assertEquals("abc", identifierAtCaret("abc", 99))
        assertEquals("abc", identifierAtCaret("abc", -5))
    }

    // ---------- 第 2 层：resolveTemplateText 优先级 ----------

    fun `test 非空选区优先于光标处`() {
        assertEquals("t_order", resolveTemplateText("t_order", "car_device_info"))
    }

    fun `test 空白选区退化到光标处`() {
        // 实证场景：预览弹窗无选区时报 14 个空格选区，不得据此判成禁用
        assertEquals("car_device_info", resolveTemplateText("              ", "car_device_info"))
    }

    fun `test 无选区取光标处`() {
        assertEquals("car_device_info", resolveTemplateText(null, "car_device_info"))
    }

    fun `test 两路皆空返回null`() {
        assertNull(resolveTemplateText(null, null))
        assertNull(resolveTemplateText("   ", null))
        assertNull(resolveTemplateText("   ", "  "))
    }

    private fun eventForSelection(text: String): AnActionEvent {
        val editor = myFixture.configureByText("T.txt", text).let { myFixture.editor }
        editor.selectionModel.setSelection(0, text.length)
        val context = MapDataContext().apply {
            put(CommonDataKeys.EDITOR.name, editor)
            put(CommonDataKeys.PROJECT.name, project)
        }
        return AnActionEvent(null, context, ActionPlaces.UNKNOWN, Presentation(), ActionManager.getInstance(), 0)
    }

    fun `test 快捷模板拒绝非单个表标识符选区`() {
        val executionAvailable = cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()
        val unavailableHint = cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.unavailableHint()
        for (action in listOf(ExplainSelectedTableAction(), CountSelectedTableAction(), SampleSelectedTableAction())) {
            for (text in listOf("t; SELECT 1", "t -- comment", "t/*comment*/", "SELECT", "t JOIN u", "t.", ".t", "t..x", "[unclosed", "t\nSELECT 1", "t\u0000")) {
                val event = eventForSelection(text)
                action.update(event)
                assertFalse("无效表名不应进入执行入口：$text", event.presentation.isEnabled)
            }
            for (text in listOf("t_order", "schema.t_order", "`db`.`table`", "\"my table\"", "[db].[table]", "\"we\"\"ird\"", "[tab]]le]", "`we``ird`")) {
                val event = eventForSelection(text)
                action.update(event)
                // UX Community 预判置灰：入口可用性 = 表名合法 && 执行能力可用（测试沙盒无
                // Database 插件 → 恒灰）。原「有效表名恒可用」的写法在 Community 语义下已不成立。
                assertEquals(
                    "有效限定表名入口可用性应与执行可用性一致（Community 下预判置灰）：$text",
                    executionAvailable,
                    event.presentation.isEnabled,
                )
                if (!executionAvailable) {
                    assertEquals("置灰必须带说明 tooltip：$text", unavailableHint, event.presentation.description)
                }
            }
        }
    }

    /**
     * UX Community 执行入口预判置灰（新功能小券批 券3）：有效表名 + 无 Database 插件时，
     * 三个模板动作必须**一眼就知道不可用**（灰置 + tooltip），而不是点了才弹「需要 Ultimate」。
     */
    fun `test Community 下执行入口预判置灰并带 tooltip`() {
        assertFalse(
            "本用例前提=测试沙盒无 Database 插件（Community 形态）",
            cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable(),
        )
        val hint = cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.unavailableHint()
        assertNotNull("不可用必须有 tooltip 文案", hint)
        for (action in listOf(ExplainSelectedTableAction(), CountSelectedTableAction(), SampleSelectedTableAction())) {
            val event = eventForSelection("t_order")
            action.update(event)
            assertFalse("Community 下有效表名也必须预判置灰", event.presentation.isEnabled)
            assertEquals("tooltip 应说明原因与替代路径", hint, event.presentation.description)
        }
    }

    fun `test 点击前重验选区且非法输入不能到达执行器`() {
        val submitted = mutableListOf<String>()
        val action = object : SelectedTableSqlTemplateAction(
            "action.cn.song.mybatisplus.visualizer.SampleSelectedTable.text",
            SelectedTableTemplateKind.COUNT,
        ) {
            override fun executeTemplate(
                project: com.intellij.openapi.project.Project,
                sql: String,
                locate: (() -> Unit)?,
                request: cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.Request,
            ) {
                submitted += sql
            }
        }
        action.actionPerformed(eventForSelection("t; SELECT 1"))
        assertTrue("直接调用动作也不得提交第二条语句", submitted.isEmpty())
        action.actionPerformed(eventForSelection("db.tbl"))
        assertEquals(listOf("SELECT COUNT(*) FROM db.tbl"), submitted)

        val stale = eventForSelection("safe t; SELECT 1")
        val editor = stale.getData(CommonDataKeys.EDITOR)!!
        editor.selectionModel.setSelection(0, 4)
        action.update(stale)
        // 选区重验只看表名本身；入口是否可点还叠加执行能力（Community 下预判置灰）
        assertEquals(
            "合法表名时入口可用性应与执行能力一致",
            cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable(),
            stale.presentation.isEnabled,
        )
        editor.selectionModel.setSelection(0, editor.document.textLength)
        action.actionPerformed(stale)
        assertEquals("选区在 update 后变坏也不得提交", listOf("SELECT COUNT(*) FROM db.tbl"), submitted)
    }

    // ---------- 第 3 层：editorTemplateText 接到真编辑器 ----------

    private fun editorAt(text: String, offset: Int) =
        myFixture.configureByText("T.txt", text).let {
            myFixture.editor.caretModel.moveToOffset(offset)
            myFixture.editor
        }

    fun `test 编辑器有选区取选区文本`() {
        val text = "SELECT * FROM car_device_info"
        val editor = myFixture.configureByText("T.txt", text).let { myFixture.editor }
        val start = text.indexOf("car_device_info")
        editor.selectionModel.setSelection(start, start + "car_device_info".length)
        assertEquals("car_device_info", editorTemplateText(editor))
    }

    fun `test 编辑器无选区取光标处标识符`() {
        val text = "SELECT * FROM car_device_info"
        val editor = editorAt(text, text.indexOf("device"))
        assertEquals("car_device_info", editorTemplateText(editor))
    }

    fun `test 编辑器光标在空白处禁用`() {
        val text = "SELECT * FROM car_device_info"
        // 光标压在第 8 位空格上（两侧均为非标识符字符：'*' 与 ' '）
        val editor = editorAt(text, text.indexOf(" FROM"))
        assertNull(editorTemplateText(editor))
    }

    fun `test 编辑器空白选区退化取光标处`() {
        val text = "SELECT * FROM   car_device_info"
        val editor = myFixture.configureByText("T.txt", text).let { myFixture.editor }
        val spaces = text.indexOf("   ")
        editor.selectionModel.setSelection(spaces, spaces + 3)
        // 保留空白选区的同时把光标移到标识符上（collapseSelection=false）
        editor.caretModel.moveToOffset(text.indexOf("car_device_info"), false)
        assertEquals("car_device_info", editorTemplateText(editor))
    }
}
