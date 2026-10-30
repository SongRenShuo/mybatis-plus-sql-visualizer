package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方言 EXPLAIN 语句渲染单元测试（M37.2 / P-立项-06 N-1）。
 *
 * 支持矩阵（DBX 真机实证 2026-09-02；T-ExplainFirstNative 对齐 IDEA 原生形态）：
 * MySQL 5.7/8.4、MariaDB（EXPLAIN 表格行——原生 MysqlBaseRawPlanData 同款，FORMAT=JSON
 * 为自创形态无渲染器消费）、PostgreSQL 16（FORMAT JSON, VERBOSE）、KingbaseES V8
 * （FORMAT JSON）、DM8（原生 EXPLAIN）、H2（PLAN FOR）、SQLite（QUERY PLAN）。
 * R23（2026-09-30）接入：Oracle（EXPLAIN PLAN FOR——真机实证 PLAN_TABLE 单语句登记，
 * 计划另查 DBMS_XPLAN.DISPLAY）、SQL Server（SET STATISTICS XML 批内前缀——官方文档级，
 * 真机未验证留账）。
 *
 * T-ExplainChainHardening T-ECH-02（O-48）：形态字面量断言保留（渲染逐字节不变的回归网），
 * 另加「支持表 vs 逐方言实现一致」「表覆盖注册表全量」「格式取值自洽」三组守卫——
 * 支持面与输出格式已收敛为 [ExplainSupport] 显式表。
 * 首关键字边界判定（T-ECH-01）用例见 [ExplainGateTest]。
 */
class DialectExplainTest {

    private val sample = "SELECT * FROM users WHERE id > 1"

    @Test
    fun `MySQL 形态`() {
        assertEquals("EXPLAIN $sample", MySqlDialect.renderExplainStatement(sample))
    }

    @Test
    fun `MariaDB 同 MySQL 形态`() {
        assertEquals("EXPLAIN $sample", MariaDbDialect.renderExplainStatement(sample))
    }

    @Test
    fun `PostgreSQL 形态`() {
        assertEquals("EXPLAIN (FORMAT JSON, VERBOSE) $sample", PostgreSQLDialect.renderExplainStatement(sample))
    }

    @Test
    fun `H2 形态`() {
        assertEquals("EXPLAIN PLAN FOR $sample", H2Dialect.renderExplainStatement(sample))
    }

    @Test
    fun `SQLite 形态`() {
        assertEquals("EXPLAIN QUERY PLAN $sample", SqliteDialect.renderExplainStatement(sample))
    }

    @Test
    fun `金仓 PG 兼容形态（V8 真机实证）`() {
        assertEquals("EXPLAIN (FORMAT JSON) $sample", KingBaseDialect.renderExplainStatement(sample))
    }

    @Test
    fun `达梦原生形态（DM8 真机实证）`() {
        assertEquals("EXPLAIN $sample", DmDialect.renderExplainStatement(sample))
    }

    /**
     * T-DialectXcFamily 接入；T-OBExplainFormat 纠档：模板同 MySQL（`EXPLAIN <sql>`），但输出**不是**
     * 表格行——真机实证（社区镜像 oceanbase/oceanbase-ce:4.3.5-lts，OB CE 4.3.5.6，MySQL 模式）
     * `EXPLAIN SELECT 1` 返回单列 `Query Plan` ASCII 计划树（`TABLE RANGE SCAN` + `Outputs & filters`），
     * 故档位为 TEXT（误登 TABLE 会让索引建议按 MySQL 列名解析而永不触发）。
     */
    @Test
    fun `OceanBase 同 MySQL 模板但档位为 TEXT（真机实证）`() {
        assertEquals("EXPLAIN $sample", OceanBaseDialect.renderExplainStatement(sample))
        assertEquals(ExplainOutputFormat.TEXT, ExplainSupport.formatFor("OceanBase"))
    }

    /** T-DialectXcFamily：openGauss 同金仓形态（openGauss 5.0.0 真机实证 FORMAT JSON 单列 JSON 文本）。 */
    @Test
    fun `openGauss 同金仓 JSON 形态（真机实证）`() {
        assertEquals("EXPLAIN (FORMAT JSON) $sample", OpenGaussDialect.renderExplainStatement(sample))
    }

    /**
     * R23：Oracle 接入——`EXPLAIN PLAN FOR <sql>` 单语句登记进 PLAN_TABLE。
     * 真机实证（DBX 通道 2026-09-30，Oracle Free 23 + XE 11g）：单语句执行成功（0 行）；
     * 计划文本须经二段式 `SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)` 另查（会话级，跨连接不可见）。
     */
    @Test
    fun `Oracle 形态（真机实证 PLAN_TABLE 登记）`() {
        assertEquals("EXPLAIN PLAN FOR $sample", OracleDialect.renderExplainStatement(sample))
    }

    /**
     * R23：SQL Server 接入——`SET STATISTICS XML ON; <sql>; SET STATISTICS XML OFF` 批内前缀。
     * 官方文法（Microsoft Learn SET STATISTICS XML）：「need not be the only statement in a batch」，
     * 计划 XML 为语句执行后的附加结果集；本插件无 SQL Server 真机（dbx 在册无实例），真机未验证留账。
     */
    @Test
    fun `SQL Server 形态（文档实证 STATISTICS XML 批内前缀）`() {
        assertEquals(
            "SET STATISTICS XML ON; $sample; SET STATISTICS XML OFF",
            SqlServerDialect.renderExplainStatement(sample),
        )
    }

    /**
     * R23：二段式/多结果集方言的通道元数据——postPlanQuery（计划另查语句）与 channelNote
     * （MCP 单语句通道的诚实降级说明）。其余 9 家必须两者皆空：channelNote 为 null 是
     * 「MCP 输出逐字节与现状一致」防回归门禁的判据。
     */
    @Test
    fun `Oracle SQLServer 二段式通道说明 其余九家无追加`() {
        val oracle = ExplainSupport.entryFor("Oracle")!!
        assertEquals("SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY)", oracle.postPlanQuery)
        assertTrue("Oracle 必须有通道说明（诚实降级）", !oracle.channelNote.isNullOrEmpty())
        val sqlServer = ExplainSupport.entryFor("SQL Server")!!
        assertNull("SQL Server 无另查语句（计划 XML 为附加结果集，非另查）", sqlServer.postPlanQuery)
        assertTrue("SQL Server 必须有通道说明（查询行非计划行的澄清）", !sqlServer.channelNote.isNullOrEmpty())
        ExplainSupport.entries
            .filter { it.dialectName != "Oracle" && it.dialectName != "SQL Server" }
            .forEach {
                assertNull("${it.dialectName} 不应有另查语句", it.postPlanQuery)
                assertNull("${it.dialectName} 不应有通道说明（输出零追加会被打破）", it.channelNote)
            }
    }

    @Test
    fun `未知方言降级 null（R23 后全量 11 家已接入，仅存未知键路径）`() {
        assertNull(ExplainSupport.render("no-such-dialect", sample))
        assertNull(ExplainSupport.render(null, sample))
    }

    @Test
    fun `全部方言均可调用不抛异常`() {
        DialectRegistry.dialects.forEach { d ->
            @Suppress("UNUSED_EXPRESSION")
            d.renderExplainStatement(sample)
        }
    }

    // ---------- T-ExplainDbl：幂等包装（重复点击执行计划不二次加前缀） ----------

    @Test
    fun `幂等-已包装文本原样返回`() {
        val wrapped = "EXPLAIN FORMAT=JSON $sample"
        assertEquals(wrapped, MySqlDialect.renderExplainStatementIdempotent(wrapped))
        // 首次点击后该文本写回控制台文档，二次点击读取到的即此文本——不再产生双前缀
        assertEquals(wrapped, MySqlDialect.renderExplainStatementIdempotent(wrapped))
    }

    @Test
    fun `幂等-大小写与首尾空白结尾分号`() {
        assertEquals("explain $sample", MySqlDialect.renderExplainStatementIdempotent("  explain $sample;  "))
        assertEquals("EXPLAIN $sample", DmDialect.renderExplainStatementIdempotent("EXPLAIN $sample"))
    }

    @Test
    fun `幂等-词边界不误判标识符`() {
        // EXPLAINING_... 是标识符不是关键字 → 正常包装
        val ident = "EXPLAINING_TABLE"
        assertEquals("EXPLAIN $ident", MySqlDialect.renderExplainStatementIdempotent(ident))
    }

    @Test
    fun `幂等-未包装文本正常加前缀`() {
        assertEquals("EXPLAIN $sample", MySqlDialect.renderExplainStatementIdempotent(sample))
        assertEquals(
            "EXPLAIN (FORMAT JSON, VERBOSE) $sample",
            PostgreSQLDialect.renderExplainStatementIdempotent(sample),
        )
    }

    /** R23 后 Oracle 已接入：未包装文本正常包装，已包装文本（EXPLAIN PLAN FOR 开头）原样返回。 */
    @Test
    fun `幂等-Oracle 未包装正常包装 已包装原样`() {
        assertEquals("EXPLAIN PLAN FOR $sample", OracleDialect.renderExplainStatementIdempotent(sample))
        assertEquals(
            "EXPLAIN PLAN FOR $sample",
            OracleDialect.renderExplainStatementIdempotent("EXPLAIN PLAN FOR $sample"),
        )
    }

    /**
     * 幂等短路旁的**多语句旁路**（T-ECH-01 订正，独立复核发现，2026-09-22）。
     *
     * 改前缺陷：幂等短路**无条件**原样返回已包装文本 → `EXPLAIN SELECT 1; DROP TABLE t` 整段
     * 绕过 [ExplainGate] 被交回执行链（IDE 链只有 confirmDollarRawSegments/confirmUnknownParams
     * 两闸，没有 executeSql 的 DML 确认闸），第二条 DDL 会真实执行；而首条包装成功后控制台文档
     * 恰是 EXPLAIN 文本，用户追加一句即可命中。修法：已包装文本只在**单条语句**时原样返回。
     */
    @Test
    fun `幂等订正-已包装文本夹带第二条语句即拒`() {
        assertNull("已包装 + 夹带 DDL 必须拒", MySqlDialect.renderExplainStatementIdempotent("EXPLAIN SELECT 1; DROP TABLE t"))
        assertNull("已包装 + 夹带 DML 必须拒", MySqlDialect.renderExplainStatementIdempotent("EXPLAIN SELECT 1; UPDATE t SET a = 1"))
        assertNull("全查询形态的多语句同样拒（fail closed）", MySqlDialect.renderExplainStatementIdempotent("EXPLAIN SELECT 1; SELECT 2"))
    }

    /** 正对照：带包装选项的**单条**已包装文本仍原样返回——不得把幂等短路一起修掉。 */
    @Test
    fun `幂等订正-单条已包装文本仍原样返回_正对照`() {
        assertEquals(
            "EXPLAIN (FORMAT JSON, VERBOSE) SELECT 1",
            PostgreSQLDialect.renderExplainStatementIdempotent("EXPLAIN (FORMAT JSON, VERBOSE) SELECT 1"),
        )
        assertEquals(
            "EXPLAIN QUERY PLAN SELECT 1",
            SqliteDialect.renderExplainStatementIdempotent("EXPLAIN QUERY PLAN SELECT 1;"),
        )
    }

    // ---------- T-ExplainChainHardening T-ECH-02：支持面/格式表守卫 ----------

    /**
     * 守卫：支持表覆盖注册表**全量**方言。
     *
     * 表是支持面的单一事实源，而「没覆写」与「故意不接」在代码上同形——新增方言时最容易漏的
     * 就是漏登记（`Dialect.renderExplainStatement` 默认读表，漏登记即静默变成「不支持」，
     * 与 Oracle/SQL Server 的有意不接无法区分）。本用例把漏项变成红灯。
     */
    @Test
    fun `守卫-支持表覆盖注册表全量方言`() {
        DialectRegistry.dialects.forEach { d ->
            assertNotNull("方言 ${d.name} 未登记进 ExplainSupport 表", ExplainSupport.entryFor(d.name))
        }
        assertEquals(
            "支持表条目数应与注册方言数一致（表里不应有注册表外的孤儿条目）",
            DialectRegistry.dialects.size,
            ExplainSupport.entries.size,
        )
    }

    /**
     * 守卫：表与逐方言实现**逐字节一致**（渲染产物不变的硬门禁）。
     *
     * 逐方言覆写已改为读表，本用例断言 `Dialect.renderExplainStatement` 与 `ExplainSupport.render`
     * 同结果——将来任何人加回覆写并抄错模板，这里立刻红。
     */
    @Test
    fun `守卫-表与逐方言实现逐字节一致`() {
        DialectRegistry.dialects.forEach { d ->
            assertEquals(
                "方言 ${d.name} 的实现与支持表不一致",
                ExplainSupport.render(d.name, sample),
                d.renderExplainStatement(sample),
            )
        }
    }

    /** 守卫：格式取值与模板形态自洽（JSON 档的模板必须真的带 `FORMAT JSON`）。 */
    @Test
    fun `守卫-格式取值与模板形态自洽`() {
        ExplainSupport.entries.forEach { e ->
            if (!e.supported) {
                assertNull("不支持的方言不应有格式取值: ${e.dialectName}", e.outputFormat)
                return@forEach
            }
            assertNotNull("支持的方言必须有格式取值: ${e.dialectName}", e.outputFormat)
            if (e.outputFormat == ExplainOutputFormat.JSON) {
                assertTrue(
                    "JSON 档模板应含 FORMAT JSON: ${e.dialectName} → ${e.template}",
                    e.template!!.contains("FORMAT JSON"),
                )
            }
            if (e.outputFormat == ExplainOutputFormat.XML) {
                assertTrue(
                    "XML 档模板应含 STATISTICS XML: ${e.dialectName} → ${e.template}",
                    e.template!!.contains("STATISTICS XML"),
                )
            }
        }
    }

    /** 守卫：模板占位符替换是字面量替换——SQL 里的 `$`/`\`/`{sql}` 不得被当成模板语法。 */
    @Test
    fun `守卫-模板替换为字面量替换`() {
        val tricky = "SELECT '{sql}' AS a, '$' AS b, '\\' AS c FROM t"
        assertEquals("EXPLAIN $tricky", MySqlDialect.renderExplainStatement(tricky))
        assertEquals(
            "EXPLAIN (FORMAT JSON, VERBOSE) $tricky",
            PostgreSQLDialect.renderExplainStatement(tricky),
        )
    }

    /** 守卫：查找大小写不敏感（MCP/外部调用方可能传小写方言名）。 */
    @Test
    fun `守卫-方言名查找大小写不敏感`() {
        assertEquals(ExplainOutputFormat.TABLE, ExplainSupport.formatFor("mysql"))
        assertEquals(ExplainOutputFormat.JSON, ExplainSupport.formatFor("postgresql"))
        assertEquals(ExplainOutputFormat.JSON, ExplainSupport.formatFor("PostgreSQL"))
        assertNull("未知方言无格式", ExplainSupport.formatFor("no-such-dialect"))
        assertNull("null 容错", ExplainSupport.formatFor(null))
    }
}
