package cn.song.mybatisplus.visualizer.xml.ibatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-IbatisFix3 缺陷三：`#x:jdbcType#` → 参数类别 → 回填引号提示（纯 JUnit / 零 IDE fixture）。
 *
 * 缺陷现场（用户 2026.3 真机）：`#email:VARCHAR:0#` 填 `11212` 预览出 `WHERE EMAIL = 11212`，
 * **没有引号**——jdbcType 明明写着 VARCHAR，但服务层从未构造 `paramHints`，面板按
 * 「纯数字 → 裸渲染」的启发式走。
 */
class IbatisParamTypeHintsTest {

    @Test
    fun `字符串族 jdbcType 必须要求引号`() {
        for (type in listOf("VARCHAR", "CHAR", "TEXT", "CLOB", "NVARCHAR", "LONGVARCHAR", "varchar")) {
            val hint = IbatisParamTypeHints.hintsFor(listOf(type)).single()
            assertEquals("$type 应恒引号", true, hint.quote)
            assertFalse("$type 不是时间族", hint.temporal)
        }
    }

    @Test
    fun `数值族 jdbcType 不得引号`() {
        for (type in listOf("INTEGER", "NUMERIC", "DECIMAL", "BIGINT", "DOUBLE", "FLOAT", "BIT", "SMALLINT")) {
            val hint = IbatisParamTypeHints.hintsFor(listOf(type)).single()
            assertEquals("$type 应裸渲染", false, hint.quote)
        }
    }

    @Test
    fun `时间族 jdbcType 走 ANSI 类型字面量优先`() {
        for (type in listOf("DATE", "TIME", "TIMESTAMP", "DATETIME")) {
            val hint = IbatisParamTypeHints.hintsFor(listOf(type)).single()
            assertTrue("$type 应标 temporal", hint.temporal)
            assertEquals("$type 回退口径仍是引号", true, hint.quote)
        }
    }

    @Test
    fun `未知或缺失 jdbcType 时不产出提示（面板维持既有启发式）`() {
        val hints = IbatisParamTypeHints.hintsFor(listOf(null, "", "SOMETHING_ELSE"))
        assertEquals(3, hints.size)
        hints.forEach { assertNull("未知类型不得猜", it.quote) }
    }

    @Test
    fun `jdbcType 序列与位点一一对应`() {
        val hints = IbatisParamTypeHints.hintsFor(listOf("VARCHAR", "INTEGER", "DATE"))
        assertEquals(3, hints.size)
        assertEquals(true, hints[0].quote)
        assertEquals(false, hints[1].quote)
        assertTrue(hints[2].temporal)
    }
}
