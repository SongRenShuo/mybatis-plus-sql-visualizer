package cn.song.mybatisplus.visualizer.psource

import cn.song.mybatisplus.visualizer.sql.ParamHint
import org.junit.Test
import org.junit.Assert.*

/**
 * [NamedParamParser] 纯函数单测（零 PSI / 零 IDE 依赖）。
 *
 * 覆盖：常规命名参数收集去重保序、双冒号守卫、数值命名参数、引号提示、边界。
 */
class NamedParamParserTest {

    // ------------------------------------------------------------------
    // collectNamedParams
    // ------------------------------------------------------------------

    @Test
    fun `collectNamedParams - basic single param`() {
        val sql = "SELECT * FROM t WHERE id = :id"
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(1, params.size)
        assertEquals("id", params[0].name)
    }

    @Test
    fun `collectNamedParams - multiple distinct params in order`() {
        val sql = "SELECT * FROM t WHERE a = :CUR_USER AND b = :flowKey AND c = :msgId"
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(3, params.size)
        assertEquals("CUR_USER", params[0].name)
        assertEquals("flowKey", params[1].name)
        assertEquals("msgId", params[2].name)
    }

    @Test
    fun `collectNamedParams - duplicate names deduplicated`() {
        // 示例一：:CUR_USER 在 SQL 中出现 3 次
        val sql = "WHERE WT.OWNER = :CUR_USER OR WT.ASSIGNEE = :CUR_USER OR AUR.USER_ID = :CUR_USER"
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(1, params.size)
        assertEquals("CUR_USER", params[0].name)
    }

    @Test
    fun `collectNamedParams - double colon guard`() {
        // PostgreSQL 类型转换语法不应被识别为命名参数
        val sql = "WHERE id::integer = :id AND name::varchar = :name"
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(2, params.size)
        assertEquals("id", params[0].name)
        assertEquals("name", params[1].name)
    }

    @Test
    fun `collectNamedParams - numeric param`() {
        val sql = "LIMIT :1 OFFSET :2"
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(2, params.size)
        assertEquals("1", params[0].name)
        assertEquals("2", params[1].name)
        assertFalse(params[0].quotedLike)
    }

    @Test
    fun `collectNamedParams - no params`() {
        val sql = "SELECT 1"
        val params = NamedParamParser.collectNamedParams(sql)
        assertTrue(params.isEmpty())
    }

    @Test
    fun `collectNamedParams - empty sql`() {
        assertTrue(NamedParamParser.collectNamedParams("").isEmpty())
    }

    @Test
    fun `collectNamedParams - param at start of line`() {
        val sql = ":CUR_USER AS user"
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(1, params.size)
        assertEquals("CUR_USER", params[0].name)
    }

    @Test
    fun `collectNamedParams - param name with underscores`() {
        val sql = "WHERE a = :my_param AND b = :Another_Param123"
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(2, params.size)
        assertEquals("my_param", params[0].name)
        assertEquals("Another_Param123", params[1].name)
    }

    @Test
    fun `collectNamedParams - mixed letter and numeric params`() {
        val sql = "WHERE a = :name AND b = :1 AND c = :age"
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(3, params.size)
        assertEquals("name", params[0].name)
        assertEquals("1", params[1].name)
        assertEquals("age", params[2].name)
    }

    // ------------------------------------------------------------------
    // quotedLike 引号提示
    // ------------------------------------------------------------------

    @Test
    fun `quotedLike - string-like names are quoted`() {
        val sql = "WHERE a = :CUR_USER AND b = :flowKey AND c = :msgId"
        val params = NamedParamParser.collectNamedParams(sql)
        assertTrue(params[0].quotedLike)  // CUR_USER
        assertTrue(params[1].quotedLike)  // flowKey
        assertTrue(params[2].quotedLike)  // msgId
    }

    @Test
    fun `quotedLike - numeric-like names are bare`() {
        val sql = "LIMIT :pageSize OFFSET :pageNumber"
        val params = NamedParamParser.collectNamedParams(sql)
        assertFalse(params[0].quotedLike)  // pageSize
        assertFalse(params[1].quotedLike)  // pageNumber
    }

    @Test
    fun `quotedLike - boolean-like names are bare`() {
        val sql = "WHERE a = :isActive AND b = :hasPermission"
        val params = NamedParamParser.collectNamedParams(sql)
        assertFalse(params[0].quotedLike)  // isActive
        assertFalse(params[1].quotedLike)  // hasPermission
    }

    @Test
    fun `quotedLike - numeric params are bare`() {
        val sql = "LIMIT :1 OFFSET :2"
        val params = NamedParamParser.collectNamedParams(sql)
        assertFalse(params[0].quotedLike)
        assertFalse(params[1].quotedLike)
    }

    // ------------------------------------------------------------------
    // quoteHint
    // ------------------------------------------------------------------

    @Test
    fun `quoteHint - string param returns quote true`() {
        val param = NamedParam("CUR_USER", quotedLike = true)
        val hint = NamedParamParser.quoteHint(param)
        assertTrue(hint.quote == true)
    }

    @Test
    fun `quoteHint - numeric param returns quote false`() {
        val param = NamedParam("pageSize", quotedLike = false)
        val hint = NamedParamParser.quoteHint(param)
        assertTrue(hint.quote == false)
    }

    // ------------------------------------------------------------------
    // 示例一真实片段
    // ------------------------------------------------------------------

    @Test
    fun `collectNamedParams - real world union query with repeated params`() {
        val sql = ("SELECT WT.OWNER = :CUR_USER OR WT.ASSIGNEE = :CUR_USER OR " +
            "(WT.ASSIGNEE IS NULL AND AUR.USER_ID = :CUR_USER))")
        val params = NamedParamParser.collectNamedParams(sql)
        assertEquals(1, params.size)
        assertEquals("CUR_USER", params[0].name)
    }
    @Test
    fun `字面量与注释内的命名参数不收_券Y11`() {
        // T-HiddenBugPerfScan 券 Y11：曾裸跑正则——'提交:审核'、'a:b'、-- 见 :doc
        // 产生幻影参数行；真参数照常收集
        val params = NamedParamParser.collectNamedParams(
            "WHERE remark = '提交:审核' AND a = :real1 -- 见 :docId\n" +
                "AND b = :real2 /* :ghost */ AND c = 1::integer"
        )
        val names = params.map { it.name }
        org.junit.Assert.assertEquals(listOf("real1", "real2"), names)
    }

}
