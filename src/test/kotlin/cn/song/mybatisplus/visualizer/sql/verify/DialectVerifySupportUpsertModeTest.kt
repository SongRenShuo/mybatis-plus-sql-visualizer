package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.SqlServerDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * T-SuiteIdempotent（2026-10-30）：`DialectVerifySupport.upsertModeOf` 的能力推导守卫。
 *
 * 背景：外部实例入口原先写死 `UpsertMode.REAL`，对**无原生 upsert** 的方言（Oracle / SQL Server /
 * 达梦）必然去撞主键冲突，异常又把后面的保留字扫描整段带崩——读数变成「方言不支持」的假结论。
 * 本用例把「哪些族该走降级路径」钉死，防止再退回硬编码。
 *
 * 判定口径：与渲染侧同源——[Dialect.renderUpsert] 与 [Dialect.renderUpsertStatement] 都返回 null
 * 才算「无原生 upsert」。所以这里同时断言两个渲染入口的形态，避免只改一侧时推导与渲染分叉。
 */
class DialectVerifySupportUpsertModeTest {

    @Test
    fun `无原生upsert的方言必须推导为降级路径`() {
        val degrade = listOf(OracleDialect, SqlServerDialect, DmDialect)
        for (dialect in degrade) {
            assertEquals(
                "${dialect.name} 的 upsert 渲染入口应全为 null（无原生 upsert）",
                null,
                dialect.renderUpsert("id", listOf("biz_name")),
            )
            assertEquals(
                "${dialect.name} 的整语句 upsert 渲染入口应全为 null",
                null,
                dialect.renderUpsertStatement("dev_info", listOf("id", "biz_name"), "id"),
            )
            assertEquals(
                "${dialect.name} 应推导为 DEGRADE_ONLY",
                DialectVerifySupport.UpsertMode.DEGRADE_ONLY,
                DialectVerifySupport.upsertModeOf(dialect),
            )
        }
    }

    @Test
    fun `有原生upsert的方言必须推导为实际执行`() {
        // 覆盖注册表里所有**不是**上面三族的方言：任一族的渲染入口被误删都会在这里变红
        val degradeNames = setOf(OracleDialect.name, SqlServerDialect.name, DmDialect.name)
        val natives = DialectRegistry.dialects.filter { it.name !in degradeNames }
        assertEquals("注册表应含 11 族原生 upsert 方言", 11, natives.size)
        for (dialect in natives) {
            assertEquals(
                "${dialect.name} 应推导为 REAL",
                DialectVerifySupport.UpsertMode.REAL,
                DialectVerifySupport.upsertModeOf(dialect),
            )
        }
    }

    @Test
    fun `H2与MySQL族的upsert入口形态各不相同但都算原生`() {
        // H2 走整语句 MERGE..KEY，MySQL 族走 ON DUPLICATE KEY UPDATE 后缀——推导必须都认作原生
        assertNotNull("H2 的整语句 upsert 入口不应为 null", H2Dialect.renderUpsertStatement(
            "dev_info", listOf("id", "biz_name"), "id",
        ))
        assertNotNull("MySQL 的后缀 upsert 入口不应为 null", MySqlDialect.renderUpsert("id", listOf("biz_name")))
        assertNotNull("PostgreSQL 的后缀 upsert 入口不应为 null", PostgreSQLDialect.renderUpsert("id", listOf("biz_name")))
    }
}
