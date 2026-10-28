package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import javax.swing.DefaultListModel

/**
 * 日志列表 Ctrl+C 复制纯 SQL（T-M42）：JBList 内置复制链路调 `itemToText(index, element)`
 * （默认返回 cellRenderer 渲染文本 = HTML 源码，用户实测缺陷）——[SqlEntryList] 覆写后
 * 复制输出 `restoredSql + ";"` 纯文本（多选逐条换行拼接、CopyPasteManager 写入由平台
 * 既有链路承担，此处按计划 §4.4 以单条断言 + 拼接语义覆盖）。
 */
class SqlLogListCopyTest : BasePlatformTestCase() {

    /** itemToText 为 protected 钩子——测试经子类公开访问（生产覆写路径不变）。 */
    private class ExposedSqlEntryList : SqlEntryList(DefaultListModel<LogSqlEntry>()) {
        fun textFor(index: Int, element: LogSqlEntry): String = itemToText(index, element)
    }

    private fun entry(restoredSql: String, rawSql: String = restoredSql) =
        LogSqlEntry(rawSql = rawSql, restoredSql = restoredSql, params = emptyList())

    fun testItemToTextReturnsPureSqlWithTerminator() {
        val list = ExposedSqlEntryList()
        val e = entry(restoredSql = "SELECT * FROM t WHERE username = 'admin'")
        val text = list.textFor(0, e)
        assertEquals("应等于还原 SQL + 分号", "SELECT * FROM t WHERE username = 'admin';", text)
        assertFalse("不应含 HTML 片段", text.contains("<html"))
        assertFalse("不应含 span 标签", text.contains("<span"))
        assertFalse("不应含时间戳前缀", text.contains("["))
    }

    fun testItemToTextDoesNotDuplicateExistingSemicolon() {
        val list = ExposedSqlEntryList()
        val e = entry(restoredSql = "SELECT 1;")
        assertEquals("已有分号不重复追加", "SELECT 1;", list.textFor(0, e))
    }

    fun testItemToTextEmptySqlNoTerminator() {
        val list = ExposedSqlEntryList()
        val e = entry(restoredSql = "")
        assertEquals("空 SQL 不追加分号", "", list.textFor(0, e))
    }

    fun testItemToTextMultiSelectionJoinSemantics() {
        // 平台 doCopyToClipboardAction 对选中项逐条调 itemToText 后 StringUtil.join("\n")——
        // 断言各条输出与拼接形态（多选换行拼接链路为平台既有行为）
        val list = ExposedSqlEntryList()
        val first = list.textFor(0, entry(restoredSql = "SELECT * FROM t WHERE id = 1"))
        val second = list.textFor(1, entry(restoredSql = "UPDATE t SET name = 'a' WHERE id = 2"))
        val joined = listOf(first, second).joinToString("\n")
        assertEquals("SELECT * FROM t WHERE id = 1;\nUPDATE t SET name = 'a' WHERE id = 2;", joined)
        assertTrue("拼接后仍为纯文本", !joined.contains("<html"))
    }

    fun testItemToTextUsesRestoredSqlNotRawSql() {
        // 复制内容应为值内联的还原 SQL（与右键「复制」一致），而非含 `?` 的 Preparing 原文
        val list = ExposedSqlEntryList()
        val e = entry(
            restoredSql = "SELECT * FROM t WHERE id = 10",
            rawSql = "SELECT * FROM t WHERE id = ?",
        )
        assertEquals("SELECT * FROM t WHERE id = 10;", list.textFor(0, e))
    }

    // ---------- T-MagpieGems W2：Ctrl+C 复制是离开 IDE 面——mode != OFF 从严打码 ----------

    private fun withMaskMode(mode: String, allowList: String = "", block: () -> Unit) {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.displayMaskMode = mode
            settings.state.displayMaskAllowList = allowList
            block()
        } finally {
            settings.loadState(snapshot)
        }
    }

    /** UI-001：复制链白名单豁免（textFor → displayMaskAllowList 贯穿）。 */
    fun test复制链白名单列豁免() {
        withMaskMode("MASK", allowList = "tel\nphone") {
            val list = ExposedSqlEntryList()
            val e = entry(restoredSql = "SELECT * FROM t WHERE phone = '13812345678'")
            val text = list.textFor(0, e)
            assertTrue("白名单列复制应豁免原文：$text", text.contains("13812345678"))
            assertFalse("豁免后不得残留打码：$text", text.contains("'***'"))
        }
        withMaskMode("MASK") {
            val list = ExposedSqlEntryList()
            val e = entry(restoredSql = "SELECT * FROM t WHERE phone = '13812345678'")
            val text = list.textFor(0, e)
            assertTrue("空白名单仍打码（状态隔离）：$text", text.contains("'***'"))
        }
    }

    fun test复制链MASK模式敏感字面量打码() {
        withMaskMode("MASK") {
            val list = ExposedSqlEntryList()
            val e = entry(restoredSql = "SELECT * FROM t WHERE phone = '13812345678'")
            val text = list.textFor(0, e)
            assertTrue("MASK 复制应打码：$text", text.contains("'***'"))
            assertFalse("原文手机号不得出产品：$text", text.contains("13812345678"))
        }
    }

    fun test复制链BLOCK模式同样打码不吐占位外的原文() {
        withMaskMode("BLOCK") {
            val list = ExposedSqlEntryList()
            val e = entry(restoredSql = "SELECT * FROM t WHERE phone = '13812345678'")
            val text = list.textFor(0, e)
            assertFalse("原文手机号不得出产品：$text", text.contains("13812345678"))
        }
    }

    fun test复制链WARN模式从严按MASK打码() {
        withMaskMode("WARN") {
            val list = ExposedSqlEntryList()
            val e = entry(restoredSql = "SELECT * FROM t WHERE phone = '13812345678'")
            val text = list.textFor(0, e)
            assertTrue("WARN 复制应从严打码（WARN 只给屏显原文）：$text", text.contains("'***'"))
            assertFalse(text.contains("13812345678"))
        }
    }

    fun test复制链OFF模式原文原样() {
        withMaskMode("OFF") {
            val list = ExposedSqlEntryList()
            val e = entry(restoredSql = "SELECT * FROM t WHERE phone = '13812345678'")
            assertEquals("OFF 复制原文（既有默认观感）", "SELECT * FROM t WHERE phone = '13812345678';", list.textFor(0, e))
        }
    }

    // ---------- T-ListPreviewCopy：右键批量复制 + 右键选中收敛 ----------

    fun testBatchCopySql多选拼接与内置CtrlC同构() {
        val entries = listOf(
            entry(restoredSql = "SELECT * FROM t WHERE id = 1"),
            entry(restoredSql = "UPDATE t SET name = 'a' WHERE id = 2"),
        )
        val joined = batchCopySql(entries)
        assertEquals("多条换行拼接、逐条分号收尾", "SELECT * FROM t WHERE id = 1;\nUPDATE t SET name = 'a' WHERE id = 2;", joined)
        assertTrue("拼接后仍为纯文本", !joined.contains("<html"))
    }

    fun testBatchCopySql单条与分号不重复() {
        assertEquals("SELECT 1;", batchCopySql(listOf(entry(restoredSql = "SELECT 1"))))
        assertEquals("已有分号不重复追加", "SELECT 1;", batchCopySql(listOf(entry(restoredSql = "SELECT 1;"))))
        assertEquals("空列表空串", "", batchCopySql(emptyList()))
    }

    fun testResolveContextMenuSelection命中行在多选内保留多选() {
        val kept = resolveContextMenuSelection(intArrayOf(0, 2, 5), hitIndex = 2)
        assertTrue("命中行在集合内应保留原多选（复制项=批量复制）", kept.contentEquals(intArrayOf(0, 2, 5)))
    }

    fun testResolveContextMenuSelection命中行不在集合内收敛为该行() {
        val collapsed = resolveContextMenuSelection(intArrayOf(0, 2, 5), hitIndex = 4)
        assertTrue("命中行不在集合内应收敛为该行（右键=切换操作目标）", collapsed.contentEquals(intArrayOf(4)))
        val single = resolveContextMenuSelection(intArrayOf(1), hitIndex = 1)
        assertTrue("单选右键自身保持单选", single.contentEquals(intArrayOf(1)))
    }
}
