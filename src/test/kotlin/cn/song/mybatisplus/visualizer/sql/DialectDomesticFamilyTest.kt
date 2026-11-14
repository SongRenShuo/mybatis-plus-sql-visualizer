package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.execute.ExecDialectRouting
import cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects
import cn.song.mybatisplus.visualizer.mcp.McpDialectDetector
import cn.song.mybatisplus.visualizer.model.LimitClause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-DomesticDialects（2026-11-09）第三批 10 方言定向测试：调研报告/开发计划 §四验收判据逐条。
 *
 * 证据级别：官方文档级 + 族推定（无真机）——本类钉住的是**映射与渲染形态**，不宣称
 * 「方言可用」（照 [DialectGoldenDbTest] §6.1 口径）。
 *
 * 反向验证（S2）内建：每个 `assertSame(X 方言本体, DialectRegistry.byName(...))` 都是
 * 反向锚点——摘掉该方言的注册表项，byName 回落 MySQL 即红；`ExplainSupport` 的
 * `supported` 断言在摘掉 EXPLAIN Entry 时变红（entryFor 返回 null）。
 */
class DialectDomesticFamilyTest {

    /** 第三批 10 方言（注册名 → 平台语言 id；id 无中文后缀，两命名空间独立维护）。 */
    private val batch: List<Pair<String, String>> = listOf(
        TiDbDialect.name to "TiDB",
        PolarDbXDialect.name to "PolarDB-X",
        PolarDbDialect.name to "PolarDB",
        GBase8aDialect.name to "GBase 8a",
        GBase8cDialect.name to "GBase 8c",
        OscarDialect.name to "Oscar",
        UxDbDialect.name to "UXDB",
        AntDbDialect.name to "AntDB",
        XuGuDialect.name to "XuGu",
        YashanDbDialect.name to "YashanDB",
    )

    // ---------- 判据 1：注册表 byName 严格等价（反向锚点：摘注册即红） ----------

    @Test
    fun `注册表_byName严格等价_25方言`() {
        for ((name, _) in batch) {
            val dialect = DialectRegistry.dialects.first { it.name == name }
            assertSame("byName 必须命中 $name 本体（不是回落 MySQL）", dialect, DialectRegistry.byName(name))
        }
        assertEquals("注册表 15→25", 25, DialectRegistry.dialects.size)
        assertSame(MySqlDialect, DialectRegistry.byName("tidb"))
        assertSame(MySqlDialect, DialectRegistry.byName("golden"))
        assertSame(MySqlDialect, DialectRegistry.byName(null))
    }

    // ---------- 判据 2：URL 子协议 → 方言映射 ----------

    @Test
    fun `URL映射_新子协议逐条命中`() {
        assertEquals("PolarDB-X", UrlSubprotocolDialects.dialectName("jdbc:polardbx://h:3306/db"))
        assertEquals("PolarDB", UrlSubprotocolDialects.dialectName("jdbc:polardb://h:3306/db"))
        assertEquals("PolarDB", UrlSubprotocolDialects.dialectName("jdbc:polardb2://h:3306/db"))
        assertEquals("GBase 8a", UrlSubprotocolDialects.dialectName("jdbc:gbase://h:5258/db"))
        assertEquals("GBase 8c", UrlSubprotocolDialects.dialectName("jdbc:gbase8c://h:5432/db"))
        assertEquals("Oscar（神通）", UrlSubprotocolDialects.dialectName("jdbc:oscar://h:2003/osrdb"))
        assertEquals("UXDB（优炫）", UrlSubprotocolDialects.dialectName("jdbc:uxdb://h:5432/db"))
        assertEquals("AntDB", UrlSubprotocolDialects.dialectName("jdbc:antdb://h:5432/db"))
        assertEquals("XuGu（虚谷）", UrlSubprotocolDialects.dialectName("jdbc:xugu://h:5138/SYSTEM"))
        assertEquals("YashanDB（崖山）", UrlSubprotocolDialects.dialectName("jdbc:yasdb://h:1630/db"))
        // 大小写不敏感（子协议提取统一 lowercase）
        assertEquals("Oscar（神通）", UrlSubprotocolDialects.dialectName("JDBC:OSCAR://h:2003/db"))
        // 不误伤：MySQL / PostgreSQL / DM 原生前缀不被新键劫持
        assertEquals("MySQL", UrlSubprotocolDialects.dialectName("jdbc:mysql://h:4000/db"))
        assertEquals("PostgreSQL", UrlSubprotocolDialects.dialectName("jdbc:postgresql://h:5432/db"))
        assertEquals("DM（达梦）", UrlSubprotocolDialects.dialectName("jdbc:dm://h:5236/db"))
        assertNull("未知子协议不猜", UrlSubprotocolDialects.dialectName("jdbc:no-such://h/db"))
    }

    /** `gbase` 子协议歧义（8a/8s 共用）：按 8a 处理是**登记的口径**（GBase8aDialect KDoc），钉住防漂移。 */
    @Test
    fun `gbase子协议歧义按8a处理_口径钉住`() {
        assertEquals("GBase 8a", UrlSubprotocolDialects.dialectName("jdbc:gbase://h:9088/db:GBASEDBTSERVER=gbase01"))
    }

    // ---------- 判据 3：采集角标 / MCP 识别（独立命名空间，映射不串线） ----------

    @Test
    fun `角标_displayName_新子协议逐条`() {
        assertEquals("PolarDB", JdbcUrlDialects.displayName("jdbc:polardb://h:3306/db"))
        assertEquals("PolarDB", JdbcUrlDialects.displayName("jdbc:polardb2://h:3306/db"))
        assertEquals("PolarDB-X", JdbcUrlDialects.displayName("jdbc:polardbx://h:3306/db"))
        assertEquals("GBase 8c", JdbcUrlDialects.displayName("jdbc:gbase8c://h:5432/db"))
        assertEquals("Oscar", JdbcUrlDialects.displayName("jdbc:oscar://h:2003/db"))
        assertEquals("UXDB", JdbcUrlDialects.displayName("jdbc:uxdb://h:5432/db"))
        assertEquals("AntDB", JdbcUrlDialects.displayName("jdbc:antdb://h:5432/db"))
        assertEquals("XuGu", JdbcUrlDialects.displayName("jdbc:xugu://h:5138/db"))
        assertEquals("YashanDB", JdbcUrlDialects.displayName("jdbc:yasdb://h:1630/db"))
        // 角标与渲染推断是独立映射：gbase 角标保持 GBASE 家族名（8a/8s 共用），渲染按 8a
        assertEquals("GBASE", JdbcUrlDialects.displayName("jdbc:gbase://h:5258/db"))
        // TiDB 无自有子协议 → 角标如实显示 MySQL（GoldenDB KDoc 同款先例：URL 不携带
        // 产品身份就不据实推断），渲染按 MySQL
        assertEquals("TiDB 走 jdbc:mysql: 角标按 MySQL", "MySQL", JdbcUrlDialects.displayName("jdbc:mysql://h:4000/db"))
    }

    @Test
    fun `MCP识别_URL前缀与别名_命中注册名`() {
        // URL 前缀位：polardbx 必须先于 polardb 命中（前缀包含关系，先匹配先赢）
        assertEquals("PolarDB-X", McpDialectDetector.guessFromUrl("jdbc:polardbx://h:3306/db"))
        assertEquals("PolarDB", McpDialectDetector.guessFromUrl("jdbc:polardb://h:3306/db"))
        assertEquals("PolarDB", McpDialectDetector.guessFromUrl("jdbc:polardb2://h:3306/db"))
        assertEquals("GBase 8c", McpDialectDetector.guessFromUrl("jdbc:gbase8c://h:5432/db"))
        assertEquals("GBase 8a", McpDialectDetector.guessFromUrl("jdbc:gbase://h:5258/db"))
        assertEquals("Oscar（神通）", McpDialectDetector.guessFromUrl("jdbc:oscar://h:2003/db"))
        assertEquals("UXDB（优炫）", McpDialectDetector.guessFromUrl("jdbc:uxdb://h:5432/db"))
        assertEquals("AntDB", McpDialectDetector.guessFromUrl("jdbc:antdb://h:5432/db"))
        assertEquals("XuGu（虚谷）", McpDialectDetector.guessFromUrl("jdbc:xugu://h:5138/db"))
        assertEquals("YashanDB（崖山）", McpDialectDetector.guessFromUrl("jdbc:yasdb://h:1630/db"))
        // 别名位（agent 显式方言名归一化）
        assertEquals("TiDB", McpDialectDetector.normalize("TiDB"))
        assertEquals("PolarDB-X", McpDialectDetector.normalize("polardbx"))
        assertEquals("Oscar（神通）", McpDialectDetector.normalize("oscar"))
        assertEquals("Oscar（神通）", McpDialectDetector.normalize("神通"))
        assertEquals("UXDB（优炫）", McpDialectDetector.normalize("优炫"))
        assertEquals("XuGu（虚谷）", McpDialectDetector.normalize("虚谷"))
        assertEquals("YashanDB（崖山）", McpDialectDetector.normalize("崖山"))
        assertEquals("YashanDB（崖山）", McpDialectDetector.normalize("yashandb"))
    }

    // ---------- 判据 4：EXPLAIN 表登记与渲染形态（族底档位） ----------

    @Test
    fun `EXPLAIN登记_8接入2诚实不接入`() {
        val connected = listOf("TiDB", "PolarDB-X", "PolarDB", "GBase 8a", "GBase 8c", "UXDB（优炫）", "AntDB", "XuGu（虚谷）")
        for (name in connected) {
            val entry = ExplainSupport.entryFor(name)
            assertNotNull("$name 应有 EXPLAIN Entry", entry)
            assertTrue("$name 应接入（template 非 null）", entry!!.supported)
        }
        // P-9 诚实不接入（无输出形态证据）：Entry 在册、supported=false
        for (name in listOf("Oscar（神通）", "YashanDB（崖山）")) {
            val entry = ExplainSupport.entryFor(name)
            assertNotNull("$name 应有无接入 Entry（P-9 留痕）", entry)
            assertFalse("$name 不得在无证据时接入", entry!!.supported)
        }
        // 模板逐字（防占位符漂移）
        assertEquals("EXPLAIN ${ExplainSupport.SQL_PLACEHOLDER}", ExplainSupport.entryFor("TiDB")!!.template)
        assertEquals("EXPLAIN ${ExplainSupport.SQL_PLACEHOLDER}", ExplainSupport.entryFor("GBase 8a")!!.template)
    }

    @Test
    fun `渲染形态_按族底档位`() {
        val limit = LimitClause(offset = 5, count = 10)
        val noOffset = LimitClause(count = 10)
        // MySQL 族：LIMIT 5, 10 / 反引号 / MySQL 形态 upsert（biz_name 非保留字，裸写）
        for (d in listOf(TiDbDialect, PolarDbXDialect, GBase8aDialect)) {
            assertEquals("${d.name} 分页", "LIMIT 5, 10", d.renderLimit(limit))
            assertEquals("${d.name} 无 offset", "LIMIT 10", d.renderLimit(noOffset))
            assertEquals("${d.name} 引用", "`id`", d.quoteIdentifier("id"))
            assertEquals("${d.name} upsert", "ON DUPLICATE KEY UPDATE biz_name = VALUES(biz_name)", d.renderUpsert("id", listOf("biz_name")))
        }
        assertEquals("TiDB DELETE LIMIT", true, TiDbDialect.supportsDeleteLimit)
        assertEquals("GBase 8a DELETE LIMIT 保守判无", false, GBase8aDialect.supportsDeleteLimit)
        // GBase 8c：PG 族分页/双引号/LOWER + openGauss 同源 MySQL 形态 upsert
        assertEquals("LIMIT 10 OFFSET 5", GBase8cDialect.renderLimit(limit))
        assertEquals("\"id\"", GBase8cDialect.quoteIdentifier("id"))
        assertEquals("ON DUPLICATE KEY UPDATE biz_name = VALUES(biz_name)", GBase8cDialect.renderUpsert("id", listOf("biz_name")))
        // UXDB / AntDB：PG 族全家桶（ON CONFLICT 形态）
        for (d in listOf(UxDbDialect, AntDbDialect)) {
            assertEquals("${d.name} 分页", "LIMIT 10 OFFSET 5", d.renderLimit(limit))
            assertEquals("${d.name} 引用", "\"id\"", d.quoteIdentifier("id"))
            assertEquals("${d.name} upsert", "ON CONFLICT (id) DO UPDATE SET biz_name = EXCLUDED.biz_name", d.renderUpsert("id", listOf("biz_name")))
        }
        // Oracle 族：OFFSET/FETCH / 双引号 / 无原生 upsert（降级纯 INSERT）
        for (d in listOf(PolarDbDialect, OscarDialect, YashanDbDialect)) {
            assertEquals("${d.name} 分页", "OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY", d.renderLimit(limit))
            assertEquals("${d.name} 无 offset", "FETCH FIRST 10 ROWS ONLY", d.renderLimit(noOffset))
            assertEquals("${d.name} 引用", "\"id\"", d.quoteIdentifier("id"))
            assertNull("${d.name} 无原生 upsert（降级）", d.renderUpsert("id", listOf("biz_name")))
            assertNull("${d.name} 无整语句 upsert", d.renderUpsertStatement("t", listOf("id", "biz_name"), "id"))
        }
        // XuGu：MySQL 风格分页 + 双引号 + 全保守判无
        assertEquals("LIMIT 5, 10", XuGuDialect.renderLimit(limit))
        assertEquals("\"id\"", XuGuDialect.quoteIdentifier("id"))
        assertNull(XuGuDialect.renderUpsert("id", listOf("biz_name")))
        assertEquals(false, XuGuDialect.supportsDeleteLimit)
        assertNull(XuGuDialect.transactionBegin)
    }

    // ---------- 判据 5：执行链候选链首项 = 平台语言 id（自注册成对登记） ----------

    @Test
    fun `执行链候选链_首项即平台语言id`() {
        for ((name, platformId) in batch) {
            val candidates = ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS[name]
            assertNotNull("方言 $name 应有执行链候选链", candidates)
            assertEquals("方言 $name 候选链首项应为平台语言 id $platformId", platformId, candidates!!.first())
        }
    }
}
