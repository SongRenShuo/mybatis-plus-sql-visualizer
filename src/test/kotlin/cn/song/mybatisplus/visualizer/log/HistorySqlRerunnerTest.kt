package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-12（DBX #316）历史一键重跑测试：重跑组装的请求与原条目逐字段一致、三态（成功/失败/旧格式）
 * 均可重跑、未绑定占位符判别（引号感知）正确。纯函数测试，无平台依赖。
 */
class HistorySqlRerunnerTest {

    private fun entry(
        sql: String = "SELECT * FROM t WHERE id = 1",
        rawSql: String = "SELECT * FROM t WHERE id = ?",
        params: List<LogSqlParam> = listOf(LogSqlParam("1", "Integer")),
        dialectName: String? = "MySQL",
        sourceFile: String? = "UserMapper.java",
        sourceLine: Int = 10,
        errorText: String? = null,
    ) = LogSqlEntry(
        rawSql = rawSql,
        restoredSql = sql,
        params = params,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
        dialectName = dialectName,
        errorText = errorText,
    )

    // ---------- 请求组装逐字段一致 ----------

    @Test
    fun `重跑请求与原条目逐字段一致`() {
        val e = entry()
        val r = HistorySqlRerunner.buildRequest(e)
        assertNotNull(r)
        // SQL 原样：逐字取 restoredSql，绝不二次改写
        assertEquals(e.restoredSql, r!!.sql)
        // 方言证据原样
        assertEquals("MySQL", r.dialectName)
        // 来源同源
        assertEquals("UserMapper.java:10", r.sourceLabel)
        // 参数已还原，无未绑定占位符
        assertEquals(0, r.unboundPlaceholders)
    }

    @Test
    fun `三态条目均可组装重跑请求`() {
        // 成功态
        assertEquals("SELECT 1", HistorySqlRerunner.buildRequest(entry(sql = "SELECT 1"))!!.sql)
        // 失败态（errorText 非空）——重跑正是排查失败原因的入口
        val failed = HistorySqlRerunner.buildRequest(entry(sql = "DELETE FROM t WHERE id = 9", errorText = "SQLException: deadlock"))
        assertNotNull(failed)
        assertEquals("DELETE FROM t WHERE id = 9", failed!!.sql)
        assertNull(failed.unboundPlaceholders.let { if (it > 0) it else null }) // 0 = 可执行
        // 旧格式条目（字段全缺省：无方言/无来源/无参数）——仍可按剩余事实重跑
        val legacy = LogSqlEntry(rawSql = "COMMIT", restoredSql = "COMMIT", params = emptyList())
        val r = HistorySqlRerunner.buildRequest(legacy)
        assertNotNull(r)
        assertEquals("COMMIT", r!!.sql)
        assertNull(r.dialectName)
        assertEquals("", r.sourceLabel)
    }

    @Test
    fun `空条目无重跑意义返回null`() {
        assertNull(HistorySqlRerunner.buildRequest(LogSqlEntry(rawSql = "", restoredSql = "", params = emptyList())))
    }

    // ---------- 来源描述 ----------

    @Test
    fun `来源描述三形态`() {
        assertEquals("", HistorySqlRerunner.sourceLabelOf(entry(sourceFile = null)))
        assertEquals("A.java", HistorySqlRerunner.sourceLabelOf(entry(sourceFile = "A.java", sourceLine = 0)))
        assertEquals("A.java:3", HistorySqlRerunner.sourceLabelOf(entry(sourceFile = "A.java", sourceLine = 3)))
    }

    // ---------- 未绑定占位符判别（引号感知） ----------

    @Test
    fun `占位符计数_普通与多占位`() {
        assertEquals(0, HistorySqlRerunner.countUnboundPlaceholders("SELECT 1"))
        assertEquals(1, HistorySqlRerunner.countUnboundPlaceholders("SELECT * FROM t WHERE a = ?"))
        assertEquals(2, HistorySqlRerunner.countUnboundPlaceholders("INSERT INTO t(a,b) VALUES (?, ?)"))
    }

    @Test
    fun `占位符计数_字符串字面量内不误报`() {
        assertEquals(0, HistorySqlRerunner.countUnboundPlaceholders("SELECT * FROM t WHERE a = '?'"))
        assertEquals(0, HistorySqlRerunner.countUnboundPlaceholders("SELECT 'a''b?c' FROM t"))
        // 字面量外的仍计入
        assertEquals(1, HistorySqlRerunner.countUnboundPlaceholders("SELECT 'x?' FROM t WHERE b = ?"))
    }

    @Test
    fun `占位符计数_PG运算符与标识符内不误报`() {
        // PostgreSQL jsonb ?| / ?& 运算符
        assertEquals(0, HistorySqlRerunner.countUnboundPlaceholders("SELECT doc FROM t WHERE attrs ?| array['a','b']"))
        assertEquals(0, HistorySqlRerunner.countUnboundPlaceholders("SELECT doc FROM t WHERE attrs ?& array['a','b']"))
        // 标识符内（双引号/反引号）
        assertEquals(0, HistorySqlRerunner.countUnboundPlaceholders("SELECT \"col?x\" FROM `tab?y`"))
    }

    @Test
    fun `未闭合引号防御不抛且保守计入`() {
        // 未闭合引号 = 断句 SQL：其中的 ? 保守计入未绑定（宁拦截给提示，不送残句进执行链）
        assertEquals(1, HistorySqlRerunner.countUnboundPlaceholders("SELECT 'unclosed FROM t WHERE a = ?"))
        assertEquals(1, HistorySqlRerunner.countUnboundPlaceholders("SELECT \"unclosed FROM t WHERE a = ?"))
    }
}
