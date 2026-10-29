package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.GaussDbDialect
import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.KingBaseDialect
import cn.song.mybatisplus.visualizer.sql.MariaDbDialect
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OceanBaseDialect
import cn.song.mybatisplus.visualizer.sql.OpenGaussDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.SqlServerDialect
import cn.song.mybatisplus.visualizer.sql.SqliteDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R36 回放脚本导出（[ReplayScriptExport] + [cn.song.mybatisplus.visualizer.sql.Dialect.transactionBegin]）
 * 纯逻辑测试——零平台依赖。
 *
 * 覆盖四条验证纪律要面：脚本渲染（含参数已填回）/ 事务包裹开与关 / 方言无显式事务语句时的降级 /
 * 脱敏开关生效；另加事务语句边界黄金表（V46 证伪①的清单固化）与空输入。
 */
class ReplayScriptExportTest {

    private fun script(sql: String, dialect: Dialect, wrap: Boolean, mask: Boolean = false): String {
        val plan = ReplayScriptExport.plan(sql, dialect, wrap)
            ?: error("期望有可执行语句（空输入的断言另有专测）")
        return ReplayScriptExport.render(plan, mask)
    }

    // ------------------------------------------------------------------
    // 脚本渲染（含参数已填回）
    // ------------------------------------------------------------------

    @Test
    fun `渲染_参数已填回_字面量与占位符同形保留_每条补尾分号`() {
        val out = script(
            "SELECT id FROM user WHERE phone = '13812345678';\nUPDATE user SET name = 'tom' WHERE id = 42;",
            MySqlDialect,
            wrap = false,
        )
        assertTrue("填回的值应逐字在脚本里（脚本不做参数替换）", out.contains("phone = '13812345678'"))
        assertTrue("第二条语句应规范化结尾分号", out.contains("UPDATE user SET name = 'tom' WHERE id = 42;"))
        assertTrue("注释头 statements 应等于语句条数", out.contains("-- statements: 2\n"))
        assertTrue("-- dialect: MySQL 行在场", out.contains("-- dialect: MySQL\n"))
        assertTrue("首行应是标识", out.startsWith("-- ${ReplayScriptExport.HEADER_TAG}\n"))
        assertFalse("未勾选包裹时不得出现事务语句", out.contains("START TRANSACTION") || out.contains("COMMIT;"))
    }

    @Test
    fun `渲染_字符串内分号不切分`() {
        val out = script("DELETE FROM t WHERE order_no = '00390360;081111'", PostgreSQLDialect, wrap = false)
        assertTrue(out.contains("DELETE FROM t WHERE order_no = '00390360;081111';"))
        assertTrue(out.contains("-- statements: 1\n"))
    }

    @Test
    fun `空输入_不产脚本`() {
        assertNull("空白输入应返回 null（调用方不发导出）", ReplayScriptExport.plan("   \n  ", MySqlDialect, true))
    }

    // ------------------------------------------------------------------
    // 事务包裹：开 / 关
    // ------------------------------------------------------------------

    @Test
    fun `事务包裹_开_首尾为方言事务语句`() {
        val out = script("SELECT 1;\nSELECT 2;", PostgreSQLDialect, wrap = true)
        val lines = out.trim().lines()
        assertEquals("包裹开时应以方言事务开始语句起头（紧接注释头）", "BEGIN;", lines[4])
        assertEquals("包裹开时应以 COMMIT 收尾", ReplayScriptExport.COMMIT_STATEMENT, lines.last())
        assertTrue("注释头应标注生效的包裹形态", out.contains("-- transaction: BEGIN ... COMMIT"))
    }

    @Test
    fun `事务包裹_关_无事务语句`() {
        val out = script("SELECT 1;", MySqlDialect, wrap = false)
        assertTrue("未勾选时应标 none", out.contains("-- transaction: none\n"))
        assertFalse(out.contains("START TRANSACTION"))
        assertFalse(out.contains("COMMIT"))
    }

    // ------------------------------------------------------------------
    // 方言无显式事务语句：降级（V46 证伪①）
    // ------------------------------------------------------------------

    @Test
    fun `方言无显式事务语句_请求包裹_诚实省略并标注原因`() {
        val out = script("SELECT 1;", OracleDialect, wrap = true)
        assertFalse("不得产出语法错误的伪包裹", out.contains("BEGIN;"))
        assertFalse(out.contains("COMMIT"))
        assertTrue(
            "注释头应说明降级原因",
            out.contains("-- transaction: none (Oracle has no explicit transaction-start statement)\n"),
        )
        val plan = ReplayScriptExport.plan("SELECT 1;", OracleDialect, wrapTransaction = true)!!
        assertFalse("生效包裹应为 false", plan.wrapped)
        assertTrue("应标记请求过但不支持", plan.wrapUnsupported)
    }

    @Test
    fun `事务包裹_请求但方言不支持_副作用与未请求区分`() {
        val notRequested = ReplayScriptExport.plan("SELECT 1;", OracleDialect, wrapTransaction = false)!!
        assertFalse(notRequested.wrapped)
        assertFalse("未请求不算降级", notRequested.wrapUnsupported)
        val out = ReplayScriptExport.render(notRequested)
        assertTrue(out.contains("-- transaction: none\n"))
    }

    /**
     * V46 证伪①的边界清单固化（黄金表）：全部注册方言的显式事务开始语句取值。
     * 判无（null）恰 Oracle 与 DM——改动本表即为一次口径变更，须先更新接口 KDoc 的证据档。
     */
    @Test
    fun `事务语句边界_全方言黄金表`() {
        val expected = mapOf(
            MySqlDialect.name to "START TRANSACTION",
            MariaDbDialect.name to "START TRANSACTION",
            OceanBaseDialect.name to "START TRANSACTION",
            PostgreSQLDialect.name to "BEGIN",
            KingBaseDialect.name to "BEGIN",
            OpenGaussDialect.name to "BEGIN",
            GaussDbDialect.name to "BEGIN",
            H2Dialect.name to "BEGIN",
            SqliteDialect.name to "BEGIN",
            SqlServerDialect.name to "BEGIN TRANSACTION",
            OracleDialect.name to null,
            DmDialect.name to null,
        )
        val actual = DialectRegistry.dialects.associate { it.name to it.transactionBegin }
        assertEquals("注册方言集合应与黄金表逐名对齐", expected.keys, actual.keys)
        assertEquals("逐方言显式事务开始语句取值", expected, actual)
        assertEquals(
            "判无（无显式事务开始语句）的方言应恰为 Oracle 与 DM",
            setOf(OracleDialect.name, DmDialect.name),
            actual.filterValues { it == null }.keys,
        )
    }

    // ------------------------------------------------------------------
    // 脱敏开关生效（R60 口径）
    // ------------------------------------------------------------------

    @Test
    fun `脱敏_开启_敏感字面量打码且头部标注`() {
        val sql = "SELECT * FROM user WHERE phone = '13812345678' AND name = 'tom'"
        val out = script(sql, MySqlDialect, wrap = false, mask = true)
        assertTrue("敏感列字面量应打码", out.contains("phone = '***'"))
        assertTrue("非敏感字面量逐字保留", out.contains("name = 'tom'"))
        assertTrue("头部应标注 masked", out.contains("-- masked: literal values replaced with ***"))
    }

    @Test
    fun `脱敏_关闭_原文逐字保留`() {
        val sql = "SELECT * FROM user WHERE phone = '13812345678'"
        val out = script(sql, MySqlDialect, wrap = false, mask = false)
        assertTrue("关闭时逐字可执行", out.contains("phone = '13812345678'"))
        assertFalse(out.contains("***"))
        assertFalse("未脱敏不标 masked", out.contains("-- masked:"))
    }

    @Test
    fun `脱敏_开启_事务包裹与打码可叠加`() {
        val out = script("UPDATE user SET pwd = 'secret1' WHERE id = 1;", MySqlDialect, wrap = true, mask = true)
        assertTrue(out.contains("START TRANSACTION;"))
        assertTrue(out.contains("pwd = '***'"))
        assertTrue(out.contains(ReplayScriptExport.COMMIT_STATEMENT))
    }
}
