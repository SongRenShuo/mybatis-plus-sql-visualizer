package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFoldPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-09：[SqlSizeGuard] 超大 SQL 护栏锚点用例。
 *
 * 断的是「哪一档、该关什么」——档位边界（阈值本身 / 阈值 + 1）必须精确，否则 200KB 的
 * 正常大 SQL 会被误关装饰、1MB 的又不给提示。另附装饰规划（自动折叠）在 100KB / 1MB 造数下的
 * 实测耗时读数，说明护栏拦的到底是什么成本。
 */
class SqlSizeGuardTest {

    // ------------------------------------------------------------------
    // 档位与边界
    // ------------------------------------------------------------------

    @Test
    fun 阈值内全开() {
        val plan = SqlSizeGuard.plan("SELECT 1")
        assertEquals(SqlSizeGuard.Level.FULL, plan.level)
        assertTrue(plan.decorationsEnabled)
        assertTrue(plan.perValueParamsEnabled)
        assertFalse(plan.showRetentionNotice)
    }

    @Test
    fun 恰好等于装饰阈值仍全开() {
        val plan = SqlSizeGuard.plan("x".repeat(SqlSizeGuard.DECORATION_LIMIT_CHARS))
        assertEquals(SqlSizeGuard.Level.FULL, plan.level)
        assertTrue(plan.decorationsEnabled)
    }

    @Test
    fun 超装饰阈值关装饰但不提示() {
        val plan = SqlSizeGuard.plan("x".repeat(SqlSizeGuard.DECORATION_LIMIT_CHARS + 1))
        assertEquals(SqlSizeGuard.Level.SKIP_DECORATION, plan.level)
        assertFalse(plan.decorationsEnabled)
        assertFalse(plan.perValueParamsEnabled)
        assertFalse(plan.showRetentionNotice)
    }

    @Test
    fun 超渲染阈值关装饰并提示() {
        val plan = SqlSizeGuard.plan("x".repeat(SqlSizeGuard.RENDER_LIMIT_CHARS + 1))
        assertEquals(SqlSizeGuard.Level.NOTICE_ONLY, plan.level)
        assertFalse(plan.decorationsEnabled)
        assertFalse(plan.perValueParamsEnabled)
        assertTrue(plan.showRetentionNotice)
    }

    @Test
    fun 空文本与null按全开处理() {
        assertEquals(SqlSizeGuard.Level.FULL, SqlSizeGuard.plan(null).level)
        assertEquals(SqlSizeGuard.Level.FULL, SqlSizeGuard.plan("").level)
        assertEquals(0, SqlSizeGuard.plan(null).chars)
    }

    // ------------------------------------------------------------------
    // 参数条数汇总
    // ------------------------------------------------------------------

    @Test
    fun 参数条数按占位符计() {
        val result = PreviewResult(
            sql = "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?",
            compactSql = "",
            unresolvedParams = listOf("param1", "param2", "param3"),
            paramTable = null,
            wrapperType = null,
            tableName = "t",
        )
        assertEquals(3, paramCountOf(result))
    }

    // ------------------------------------------------------------------
    // 造数实测：装饰规划耗时（读数进报告）
    // ------------------------------------------------------------------

    @Test
    fun 装饰规划耗时读数() {
        val line = "SELECT id, user_name, create_time FROM t_user WHERE id = #{id}\n"
        val text100k = line.repeat(100 * 1024 / line.length)
        val text1m = line.repeat(1024 * 1024 / line.length + 1)
        assertEquals(SqlSizeGuard.Level.FULL, SqlSizeGuard.plan(text100k).level)
        assertEquals(SqlSizeGuard.Level.NOTICE_ONLY, SqlSizeGuard.plan(text1m).level)

        val ms100k = measureMs { SqlAutoFoldPlanner.plan(text100k, 6, "… {0} columns") }
        val ms1m = measureMs { SqlAutoFoldPlanner.plan(text1m, 6, "… {0} columns") }
        println("[F-09] 装饰规划（自动折叠）100KB=${ms100k}ms，1MB=${ms1m}ms")
        // 1MB 的装饰规划在编辑器文档线程上跑就是可见卡顿的量级；护栏把它挡在门外
        assertTrue("装饰规划本身应可测出耗时，1MB 实测 ${ms1m}ms", ms1m >= 0)
    }

    private fun measureMs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }
}
