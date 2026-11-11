package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SqlCommentStripper 单元测试。
 */
class SqlCommentStripperTest {

    @Test
    fun testStripBlockComment() {
        val sql = "SELECT u FROM user_entity u /* 推断的ON条件，请验证 */ WHERE d.name = ?"
        val result = SqlCommentStripper.strip(sql)
        assertFalse("不应含块注释", result.contains("/*"))
        assertFalse("不应含注释内容", result.contains("推断"))
        assertTrue("应含 SELECT", result.contains("SELECT"))
        assertTrue("应含 WHERE", result.contains("WHERE"))
    }

    @Test
    fun testStripLineComment() {
        val sql = "SELECT u FROM user_entity u -- 这是行注释\nWHERE d.name = ?"
        val result = SqlCommentStripper.strip(sql)
        assertFalse("不应含行注释", result.contains("--"))
        assertFalse("不应含注释内容", result.contains("这是行注释"))
        assertTrue("应含 SELECT", result.contains("SELECT"))
        assertTrue("应含 WHERE", result.contains("WHERE"))
    }

    @Test
    fun testPreserveStringLiteral() {
        // 字符串内的 -- 或 /* 不应被视为注释
        val sql = "SELECT * FROM user WHERE name = '/* not a comment */'"
        val result = SqlCommentStripper.strip(sql)
        assertTrue("应保留字符串内容", result.contains("/* not a comment */"))
    }

    @Test
    fun testStripMultipleComments() {
        val sql = "/* 块注释 */ SELECT u FROM user_entity u -- 行注释\nWHERE /* 块 */ d.name = ?"
        val result = SqlCommentStripper.strip(sql)
        assertFalse("不应含块注释", result.contains("/*"))
        assertFalse("不应含行注释", result.contains("--"))
        assertTrue("应含 SELECT", result.contains("SELECT"))
        assertTrue("应含 WHERE", result.contains("WHERE"))
        assertTrue("应含 ?", result.contains("?"))
    }

    @Test
    fun testNoCommentsUnchanged() {
        val sql = "SELECT u FROM user_entity u WHERE d.name = ?"
        val result = SqlCommentStripper.strip(sql)
        assertEquals("无注释应原样返回", sql, result)
    }

    @Test
    fun testJoinFetchCommentStripped() {
        // 用户反馈的实际场景：JOIN FETCH 后的注释应被剥离
        val sql = "SELECT u FROM user_entity u LEFT JOIN department d ON d.id = u.department_id /* 推断的ON条件，请验证 */ WHERE d.name = ?"
        val result = SqlCommentStripper.strip(sql)
        assertFalse("不应含块注释", result.contains("/*"))
        assertFalse("不应含推断提示", result.contains("推断"))
        assertTrue("应含 LEFT JOIN", result.contains("LEFT JOIN"))
        assertTrue("应含 ON", result.contains(" ON "))
    }
}
