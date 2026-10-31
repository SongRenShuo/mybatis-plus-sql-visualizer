package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-DialectAutoInfer：子协议映射 + 推断优先级合并单测（纯函数层）。
 */
class DialectInferenceTest {

    // ---------- UrlSubprotocolDialects ----------

    @Test
    fun subprotocolMapsToExactDialectNames() {
        // 值必须与 DialectRegistry 各方言 name 逐字一致（含中缀）——失配即静默回退 MySQL
        val expected = mapOf(
            "jdbc:mysql://localhost:3306/db" to "MySQL",
            "jdbc:mariadb://localhost:3306/db" to "MariaDB",
            "jdbc:postgresql://localhost:5432/db" to "PostgreSQL",
            "jdbc:oracle:thin:@localhost:1521:orcl" to "Oracle",
            "jdbc:sqlserver://localhost:1433;databaseName=db" to "SQL Server",
            "jdbc:h2:mem:test" to "H2",
            "jdbc:sqlite:test.db" to "SQLite",
            "jdbc:dm://localhost:5236" to "DM（达梦）",
            "jdbc:kingbase8://localhost:54321/db" to "KingBase（人大金仓）",
            "jdbc:kingbase://localhost:54321/db" to "KingBase（人大金仓）",
            // T-DialectXcFamily（O-63）：信创双备；T-GaussDbDialect（券 04）起 gaussdb 归独立方言
            "jdbc:oceanbase://localhost:2881/db" to "OceanBase",
            "jdbc:opengauss://localhost:5432/db" to "openGauss",
            "jdbc:gaussdb://localhost:8000/db" to "GaussDB",
            // T-XcDialects3（券 03）：瀚高 jdbc:highgo:（HgdbJdbc 驱动唯一前缀）；海量 jdbc:vastbase:
            // 与 jdbc:dws:iam:（cn.com.vastbase.Driver 的 parseURL 常量池，子协议分别为 vastbase/dws）
            "jdbc:highgo://localhost:5866/db" to "HighGo",
            "jdbc:vastbase://localhost:5432/db" to "Vastbase",
            "jdbc:dws:iam://localhost:5432/db" to "Vastbase",
        )
        expected.forEach { (url, name) -> assertEquals(name, UrlSubprotocolDialects.dialectName(url)) }
    }

    @Test
    fun xcDialects3SubprotocolsInferThroughPriorityChain() {
        // 推断优先级链上三前缀各自落位；HighGo/Vastbase 的 object 在 A 路（Dialect.kt）合并——
        // 此处只断言映射层的字符串名（A 路合并前 DialectRegistry.byName("HighGo") 仍回退 MySQL，
        // 注册表级断言由券 06 的 DialectXcFamily3Test 与 B1 守卫承接，不在本测试提前固化）
        assertEquals("HighGo", DialectInference.infer(null, "jdbc:highgo://h:5866/db").dialectName)
        assertEquals("Vastbase", DialectInference.infer("jdbc:vastbase://h:5432/db", null).dialectName)
        assertEquals("Vastbase", DialectInference.infer("jdbc:dws:iam://h:5432/db", "jdbc:mysql://h:3306/db").dialectName)
        // agent 端瀚高优先于数据源 MySQL
        assertEquals("HighGo", DialectInference.infer("jdbc:highgo://h/db", "jdbc:mysql://h/db").dialectName)
    }

    @Test
    fun dialectNamesAllResolvableByRegistry() {
        // 映射值必须能被 DialectRegistry.byName 命中（回退 MySQL 视为失配）
        setOf(
            "MySQL", "MariaDB", "PostgreSQL", "Oracle", "SQL Server", "H2", "SQLite",
            "DM（达梦）", "KingBase（人大金仓）", "OceanBase", "openGauss",
        ).forEach { name ->
            assertEquals(name, DialectRegistry.INSTANCE.byName(name).name)
        }
    }

    @Test
    fun subprotocolCaseInsensitive() {
        assertEquals("MySQL", UrlSubprotocolDialects.dialectName("jdbc:MySQL://h/db"))
        assertEquals("PostgreSQL", UrlSubprotocolDialects.dialectName("JDBC:PostgreSQL://h/db"))
    }

    @Test
    fun unknownOrMalformedUrlReturnsNull() {
        assertNull(UrlSubprotocolDialects.dialectName(null))
        assertNull(UrlSubprotocolDialects.dialectName(""))
        assertNull(UrlSubprotocolDialects.dialectName("   "))
        assertNull(UrlSubprotocolDialects.dialectName("jdbc:"))
        assertNull(UrlSubprotocolDialects.dialectName("jdbc:clickhouse://h/db")) // 无对应渲染方言
        assertNull(UrlSubprotocolDialects.dialectName("not-a-jdbc-url"))
    }

    // ---------- DialectInference 优先级 ----------

    @Test
    fun agentUrlWinsOverDataSource() {
        val r = DialectInference.infer("jdbc:dm://agent:5236", "jdbc:postgresql://ide:5432/db")
        assertEquals("DM（达梦）", r.dialectName)
        assertEquals("jdbc:dm://agent:5236", r.basis)
    }

    @Test
    fun fallsBackToDataSourceWhenAgentNull() {
        val r = DialectInference.infer(null, "jdbc:postgresql://ide:5432/db")
        assertEquals("PostgreSQL", r.dialectName)
    }

    @Test
    fun fallsBackToDataSourceWhenAgentUrlUnsupported() {
        // agent 连了不支持的库（如 clickhouse）→ 不猜，落数据源
        val r = DialectInference.infer("jdbc:clickhouse://h/db", "jdbc:mysql://ide:3306/db")
        assertEquals("MySQL", r.dialectName)
    }

    @Test
    fun noCandidatesReturnsNullResult() {
        val r = DialectInference.infer(null, null)
        assertNull(r.dialectName)
        assertNull(r.basis)
    }

    @Test
    fun unsupportedEverywhereReturnsNullResult() {
        val r = DialectInference.infer("garbage", "jdbc:db2://h:50000/db")
        assertNull(r.dialectName)
    }
}
