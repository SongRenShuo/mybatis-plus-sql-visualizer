package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects
import cn.song.mybatisplus.visualizer.mcp.McpDialectDetector
import cn.song.mybatisplus.visualizer.sql.DialectCapabilities.Capability
import cn.song.mybatisplus.visualizer.sql.verify.DialectVerifySupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GoldenDB 方言（T-GoldenDbDialect 券 A）定向测试：计划 §三 券 A 判据 1-5 + §5.1 验收表逐项。
 *
 * 证据级别：官方文档级（《GoldenDB 产品手册_开发支持 V6.1.03.10》p66/p73/p83/p274/p276/p320/
 * p351-355），无真机（P-1）——本类钉住的是**映射与渲染形态**，不宣称「方言可用」（§6.1 口径）。
 *
 * 反向验证（判据 6，读数见计划 §7.1）：摘掉 `DialectRegistry.dialects` 的 `GoldenDbDialect`
 * → [注册表_byName严格等价] 红；摘掉 `UrlSubprotocolDialects` 的 `goldendb` 键 →
 * [URL映射_两条子协议] 红。
 */
class DialectGoldenDbTest {

    // ---------- 判据 1：注册表 byName 严格等价 ----------

    @Test
    fun `注册表_byName严格等价`() {
        assertSame("byName 必须命中 GoldenDbDialect 本体（不是回落 MySQL）", GoldenDbDialect, DialectRegistry.byName("GoldenDB"))
        assertEquals("注册表 14→15", 15, DialectRegistry.dialects.size)
        // 未知名回退 MySQL 的既有口径不被新方言稀释
        assertSame(MySqlDialect, DialectRegistry.byName("Goldendb"))
        assertSame(MySqlDialect, DialectRegistry.byName("golden"))
        assertSame(MySqlDialect, DialectRegistry.byName(null))
    }

    // ---------- 判据 2：URL → 方言映射 ----------

    @Test
    fun `URL映射_两条子协议`() {
        assertEquals("GoldenDB", UrlSubprotocolDialects.dialectName("jdbc:goldendb://h:3306/db"))
        assertEquals("子协议取首个冒号前段，loadbalance 形态同命中", "GoldenDB", UrlSubprotocolDialects.dialectName("jdbc:goldendb:loadbalance://h1:3307,h2:3308/db"))
        assertEquals("GoldenDB", UrlSubprotocolDialects.dialectName("JDBC:GoldenDB://h:3306/db"))
        // 不误伤：MySQL 原生前缀仍映射 MySQL（驱动默认 useGoldendbDriver=true 下 jdbc:mysql://
        // 也走 GoldenDB 驱动，但 URL 不携带 GoldenDB 身份，不据此劫持）
        assertEquals("MySQL", UrlSubprotocolDialects.dialectName("jdbc:mysql://h:3306/db"))
        assertNull("未知子协议不猜", UrlSubprotocolDialects.dialectName("jdbc:no-such://h/db"))
    }

    // ---------- 判据 3：角标 displayName / badgeName ----------

    @Test
    fun `角标_displayName与badgeName`() {
        assertEquals("GoldenDB", JdbcUrlDialects.displayName("jdbc:goldendb://h:3306/db"))
        assertEquals("GoldenDB · wmm@10.0.0.7:3306", JdbcUrlDialects.badgeName("jdbc:goldendb://10.0.0.7:3306/wmm"))
        // loadbalance 是「带前缀的 authority 形态」（:// 前还有 loadbalance 段），R32 通用解析器
        // 不认（targetOf null）→ 角标如实只出方言名（既有边界，非本轮缺口；判据 3 的 db@h:3306
        // 由上面的通用 authority 形态覆盖）
        assertEquals("GoldenDB", JdbcUrlDialects.badgeName("jdbc:goldendb:loadbalance://h1:3307,h2:3308/test"))
        assertNull("未知子协议角标不显示（事实展示命名空间不猜）", JdbcUrlDialects.displayName("jdbc:nosuch://h/db"))
    }

    /** 券 A 第 6 项：角标名与注册名同为 GoldenDB，同名直接命中 knownDialect，**不加** BADGE_HEAD_ALIASES。 */
    @Test
    fun `预览解析_角标头段同名直接命中_无需别名`() {
        assertEquals("GoldenDB", PreviewDialectResolver.dialectFromBadge("GoldenDB · wmm@10.0.0.7:3306"))
        assertEquals("GoldenDB", PreviewDialectResolver.knownDialect("GoldenDB"))
    }

    // ---------- 判据 4：MCP guessFromUrl / normalize ----------

    @Test
    fun `MCP_URL推断与别名归一`() {
        assertEquals("GoldenDB", McpDialectDetector.guessFromUrl("jdbc:goldendb://h:3306/db"))
        assertEquals("jdbc:mysql 前缀不误命中（次序安全）", "MySQL", McpDialectDetector.guessFromUrl("jdbc:mysql://h:3306/db"))
        assertEquals("GoldenDB", McpDialectDetector.normalize("GoldenDB"))
        assertEquals("P-6 销案：p1 法律声明「金篆信科」确证中文名", "GoldenDB", McpDialectDetector.normalize("金篆"))
        assertNull("「中兴」PDF 全文 0 命中，无证据不收", McpDialectDetector.normalize("中兴"))
        assertTrue("金篆在 MCP 里可识别", McpDialectDetector.isRecognized("金篆"))
    }

    // ---------- 判据 5：保留字词表 ----------

    @Test
    fun `词表_规模与判别词`() {
        assertTrue("矩阵下限 50 词（实际 384）", GOLDENDB_RESERVED.size >= 50)
        // GoldenDB 自有词 + Oracle 兼容词（照抄 MYSQL_RESERVED 会漏的判别位）
        listOf("storagedb", "chgdiskey", "gdb_nolock", "rownum", "varchar2", "autonomous_transaction", "samedb").forEach {
            assertTrue("GoldenDB 自有/Oracle 兼容词 $it 必须在词表", it in GOLDENDB_RESERVED)
        }
        // 与 MySQL 共有词（判「词表真的接上了」的反向判别位）
        listOf("limit", "order", "table", "select").forEach {
            assertTrue("MySQL 共有词 $it 必须在词表", it in GOLDENDB_RESERVED)
        }
        // 与 MYSQL_RESERVED 差集非空（防止有人把实现退化成「= MYSQL_RESERVED」而不破其它断言）
        assertNotEquals("与 MYSQL_RESERVED 差集必须非空（漏包即真库语法错）", 0, (GOLDENDB_RESERVED - MYSQL_RESERVED).size)
        assertTrue("差集应含 GoldenDB 自有词", "storagedb" in (GOLDENDB_RESERVED - MYSQL_RESERVED))
    }

    @Test
    fun `词表_按需引用大小写不敏感`() {
        assertEquals("`storagedb`", GoldenDbDialect.quoteIfKeyword("storagedb"))
        assertEquals("`STORAGEDB`", GoldenDbDialect.quoteIfKeyword("STORAGEDB"))
        assertEquals("非保留字裸写", "biz_name", GoldenDbDialect.quoteIfKeyword("biz_name"))
    }

    // ---------- 渲染形态（§5.1：逐字断言） ----------

    @Test
    fun `渲染形态_分页引用upsert事务`() {
        assertEquals("p73 LIMIT [offset,]count（0 起算）", "LIMIT 2, 10", GoldenDbDialect.renderLimit(LimitClause(offset = 2, count = 10)))
        assertEquals("LIMIT 5", GoldenDbDialect.renderLimit(LimitClause(count = 5)))
        assertEquals("p320 反引号", "`order`", GoldenDbDialect.quoteIdentifier("order"))
        assertEquals("p66 ON DUPLICATE KEY UPDATE", "ON DUPLICATE KEY UPDATE c = VALUES(c)", GoldenDbDialect.renderUpsert("id", listOf("c")))
        assertTrue("p274/p276 DELETE LIMIT 文档级支持", GoldenDbDialect.supportsDeleteLimit)
        assertEquals("p83 START TRANSACTION", "START TRANSACTION", GoldenDbDialect.transactionBegin)
        assertEquals("identifierQuotes 反引号", setOf('`'), GoldenDbDialect.identifierQuotes)
        assertEquals("未实测折叠方向，默认 NONE（真机核对后增量）", IdentifierFolding.NONE, GoldenDbDialect.identifierFolding)
    }

    // ---------- P-9：EXPLAIN 诚实不接入 ----------

    @Test
    fun `EXPLAIN_未定档不接入`() {
        assertNull("P-9：原文无输出形态示例，不猜档（OceanBase 教训）", GoldenDbDialect.renderExplainStatement("SELECT 1"))
        val entry = ExplainSupport.entryFor("GoldenDB")
        assertNotNull("守卫「全量覆盖」要求显式条目在场（template=null=不接入）", entry)
        assertFalse("supported 必须 false（能力矩阵 EXPLAIN 行如实显示 false）", entry!!.supported)
        assertNull(ExplainSupport.formatFor("GoldenDB"))
        assertNull(ExplainSupport.render("GoldenDB", "SELECT 1"))
    }

    // ---------- 能力矩阵（§5.1） ----------

    @Test
    fun `能力矩阵_四行产出与真值`() {
        val rows = DialectCapabilities.matrix(GoldenDbDialect).associateBy { it.capability }
        assertEquals(false, rows.getValue(Capability.EXPLAIN).supported)
        assertTrue("DELETE_LIMIT=true（p274/p276）", rows.getValue(Capability.DELETE_LIMIT).supported)
        assertTrue("UPSERT=true（p66 原生后缀形态）", rows.getValue(Capability.UPSERT).supported)
        assertEquals(false, rows.getValue(Capability.IDENTITY_WRAP).supported)
        assertEquals("verify 套件 upsert 模式推导 REAL", DialectVerifySupport.UpsertMode.REAL, DialectVerifySupport.upsertModeOf(GoldenDbDialect))
    }

    // ---------- 执行路由候选链 ----------

    @Test
    fun `执行路由_候选链首项自注册方言`() {
        assertEquals(listOf("GoldenDB", "MySQL"), cn.song.mybatisplus.visualizer.execute.ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GoldenDB"])
    }
}
