package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-06②：[suggestKeywordFix] 关键字拼写提示锚点用例。
 *
 * 正例是常见手误（漏字母、多字母、相邻换位、大小写混杂）；反例是「不该提示」的四类：
 * 拼对的关键字、标识符（下划线/点/数字）、差距过大的词、并列候选。纯函数零 IDE 依赖。
 */
class SqlKeywordSuggestTest {

    // ------------------------------------------------------------------
    // 正例
    // ------------------------------------------------------------------

    @Test
    fun 漏字母给建议() {
        assertEquals("SELECT", suggestKeywordFix("selet"))
        assertEquals("UPDATE", suggestKeywordFix("updat"))
        assertEquals("LIMIT", suggestKeywordFix("limt"))
    }

    @Test
    fun 多字母给建议() {
        assertEquals("JOIN", suggestKeywordFix("joinn"))
        assertEquals("WHERE", suggestKeywordFix("wherex"))
    }

    @Test
    fun 相邻换位给建议() {
        assertEquals("FROM", suggestKeywordFix("frmo"))
        assertEquals("GROUP", suggestKeywordFix("gropu"))
    }

    @Test
    fun 大小写不敏感且结果统一大写() {
        assertEquals("SELECT", suggestKeywordFix("SELCT"))
        assertEquals("DELETE", suggestKeywordFix("Deletee"))
        assertEquals("ORDER", suggestKeywordFix("Orderr"))
    }

    @Test
    fun 最近一档唯一即命中_次近档不构成竞争() {
        // selet：select 距离 1，set 距离 2——按最近一档取 select
        assertEquals("SELECT", suggestKeywordFix("selet"))
    }

    // ------------------------------------------------------------------
    // 反例
    // ------------------------------------------------------------------

    @Test
    fun 拼对的关键字不提示() {
        assertNull(suggestKeywordFix("select"))
        assertNull(suggestKeywordFix("WHERE"))
    }

    @Test
    fun 标识符与表达式不提示() {
        assertNull(suggestKeywordFix("user_name"))
        assertNull(suggestKeywordFix("t.id"))
        assertNull(suggestKeywordFix("param1"))
        assertNull(suggestKeywordFix("`select`"))
        assertNull(suggestKeywordFix("count(*)"))
    }

    @Test
    fun 空输入不提示() {
        assertNull(suggestKeywordFix(null))
        assertNull(suggestKeywordFix(""))
        assertNull(suggestKeywordFix("   "))
    }

    @Test
    fun 差距过大不提示() {
        assertNull(suggestKeywordFix("xyzzy"))
        assertNull(suggestKeywordFix("abcdefghijklmnop"))
    }

    @Test
    fun 并列候选不提示() {
        // an → and/as/any/in 距离都是 1，判不出用户想要哪个
        assertNull(suggestKeywordFix("an"))
    }

    @Test
    fun 超长token不提示() {
        assertNull(suggestKeywordFix("a".repeat(40)))
    }

    // ------------------------------------------------------------------
    // 编辑距离内核
    // ------------------------------------------------------------------

    @Test
    fun 编辑距离基本形态() {
        assertEquals(0, boundedEditDistance("select", "select", 2))
        assertEquals(1, boundedEditDistance("selet", "select", 2))
        assertEquals(2, boundedEditDistance("form", "from", 2))
        assertEquals(3, boundedEditDistance("abc", "xyz", 2)) // 超界统一返回 limit + 1
    }
}
