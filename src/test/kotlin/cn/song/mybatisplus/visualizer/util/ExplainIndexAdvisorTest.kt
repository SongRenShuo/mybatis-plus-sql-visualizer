package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import cn.song.mybatisplus.visualizer.sql.ExplainSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ExplainIndex（N-23）T-EI-02：EXPLAIN 计划索引缺失建议识别表驱动。
 * MySQL 表格式（ALL 全表扫 / key=NULL / filesort / temporary）、PG 文本树（Seq Scan）、
 * 阈值边界、无命中零输出、不支持方言静默降级、空清单渲染零差异。
 */
class ExplainIndexAdvisorTest {

    private val mysqlHeaders = listOf("id", "select_type", "table", "type", "key", "rows", "Extra")

    // ---------- MySQL 表格式 ----------

    @Test
    fun testMySQL全表扫超阈提示() {
        val rows = listOf(listOf("1", "SIMPLE", "orders", "ALL", "NULL", "500000", ""))
        val hints = ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows)
        assertEquals(1, hints.size)
        assertEquals("FULL_SCAN", hints[0].rule)
        assertEquals("orders", hints[0].target)
        assertTrue(hints[0].advice.contains("500000"))
    }

    @Test
    fun testMySQL小表全扫不提示() {
        // rows 低于阈值：小表全扫常是更优计划，不噪音
        val rows = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", "500", ""))
        assertTrue(ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows).isEmpty())
    }

    @Test
    fun test阈值边界() {
        val at = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", ExplainIndexAdvisor.ROWS_THRESHOLD.toString(), ""))
        assertEquals(1, ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, at).size)
        val below = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", "9999", ""))
        assertTrue(ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, below).isEmpty())
    }

    @Test
    fun testMySQL非ALL但keyNULL弱信号() {
        val rows = listOf(listOf("1", "SIMPLE", "t", "index", "NULL", "50", ""))
        val hints = ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows)
        assertEquals(1, hints.size)
        assertEquals("NO_INDEX_USED", hints[0].rule)
    }

    @Test
    fun testMySQLfilesort与temporary() {
        val rows = listOf(
            listOf("1", "SIMPLE", "t", "ref", "idx_a", "100", "Using where; Using filesort; Using temporary"),
        )
        val rules = ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows).map { it.rule }
        assertTrue("FILESORT" in rules)
        assertTrue("TEMPORARY_TABLE" in rules)
    }

    // ---------- R45 EXPLAIN 体检：rows 估算独立规则 ----------

    /**
     * R45：rows 估算维度此前只作 FULL_SCAN 的阈值闸门（非 ALL 时无从触发）——「命中索引但
     * 估算行数仍超阈」的形态（索引选择性差 / 大范围扫描）无规则覆盖。体检规则清单补一条
     * HIGH_ROWS_ESTIMATE：rows ≥ 阈值且 type 非 ALL（与 FULL_SCAN 互补不重复）时提示。
     */
    @Test
    fun testR45_命中索引但rows超阈_估算异常提示() {
        // type=range + key=idx_created 命中索引，但估算 500000 行 → 选择性差
        val rows = listOf(listOf("1", "SIMPLE", "orders", "range", "idx_created", "500000", "Using index condition"))
        val hints = ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows)
        assertTrue("命中索引但 rows 超阈应产出 HIGH_ROWS_ESTIMATE", hints.any { it.rule == "HIGH_ROWS_ESTIMATE" })
        assertTrue("建议目标应是表名", hints.first { it.rule == "HIGH_ROWS_ESTIMATE" }.target == "orders")
    }

    @Test
    fun testR45_rows超阈且typeALL_只报FULL_SCAN不重复报估算() {
        // ALL 已由 FULL_SCAN 覆盖；估算规则须与 FULL_SCAN 互补不重复（防同一形态双提示噪音）
        val rows = listOf(listOf("1", "SIMPLE", "orders", "ALL", "NULL", "500000", ""))
        val rules = ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows).map { it.rule }
        assertTrue("FULL_SCAN" in rules)
        assertTrue("ALL 形态不应重复报 HIGH_ROWS_ESTIMATE", "HIGH_ROWS_ESTIMATE" !in rules)
    }

    @Test
    fun testR45_rows未超阈_不报估算() {
        val rows = listOf(listOf("1", "SIMPLE", "t", "ref", "idx_a", "100", ""))
        assertTrue(ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows).none { it.rule == "HIGH_ROWS_ESTIMATE" })
    }

    @Test
    fun testR45_规则清单_含全表扫与估算与文件排序() {
        // 体检规则清单（R45 呈现层数据）：覆盖任务点名的维度——type 全表扫/key 缺失/rows 估算/extra 文件排序
        val inventory = ExplainIndexAdvisor.ruleInventory()
        val rules = inventory.map { it.rule }
        assertTrue("清单应含全表扫 FULL_SCAN", "FULL_SCAN" in rules)
        assertTrue("清单应含 key 缺失 NO_INDEX_USED", "NO_INDEX_USED" in rules)
        assertTrue("清单应含 rows 估算 HIGH_ROWS_ESTIMATE", "HIGH_ROWS_ESTIMATE" in rules)
        assertTrue("清单应含文件排序 FILESORT", "FILESORT" in rules)
        assertTrue("清单应含临时表 TEMPORARY_TABLE", "TEMPORARY_TABLE" in rules)
    }

    // ---------- PG 文本树 ----------

    @Test
    fun testPGSeqScan超阈提示() {
        val rows = listOf(
            listOf("Seq Scan on big_table  (cost=0.00..35000.00 rows=300000 width=64)"),
        )
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), rows)
        assertEquals(1, hints.size)
        assertEquals("SEQ_SCAN", hints[0].rule)
        assertEquals("big_table", hints[0].target)
    }

    @Test
    fun testPG小表SeqScan不提示() {
        val rows = listOf(listOf("Seq Scan on tiny  (cost=0.00..12.00 rows=5 width=8)"))
        assertTrue(ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), rows).isEmpty())
    }

    // ---------- PG JSON（T-ExplainChainHardening T-ECH-03：真缺陷修复） ----------

    /**
     * 真库实测形态钉住（PG 16.14，DBX 通道 2026-09-22）：
     * `EXPLAIN (FORMAT JSON, VERBOSE) SELECT * FROM bench_items` 返回**单列**（列名 `QUERY PLAN`）
     * 的 JSON 文本，关系名键为 `"Relation Name"`、行数键为 `"Plan Rows"`。
     *
     * 改前此形态必红：旧 PG 分支按 `Seq Scan on (\w+)` 文本树正则解析，JSON 里没有该文本形态
     * → PG 索引建议**永不触发**（真缺陷）。
     */
    private val pgJsonSeqScan = """[{"Plan":{"Node Type":"Seq Scan","Parallel Aware":false,""" +
        """"Async Capable":false,"Relation Name":"bench_items","Schema":"public",""" +
        """"Alias":"bench_items","Startup Cost":0.00,"Total Cost":256.00,"Plan Rows":10000,""" +
        """"Plan Width":87,"Output":["id","numeric_value","timestamp_value","text_value"]}}]"""

    /** 真库实测形态钉住：`SELECT 1` 的 JSON（节点无关系名，`Node Type` = `Result`）。 */
    private val pgJsonResult = """[{"Plan":{"Node Type":"Result","Parallel Aware":false,""" +
        """"Async Capable":false,"Startup Cost":0.00,"Total Cost":0.01,"Plan Rows":1,"Plan Width":4,""" +
        """"Output":["1"]}}]"""

    @Test
    fun testPG_JSON形态_SeqScan超阈提示() {
        val rows = listOf(listOf(pgJsonSeqScan))
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), rows)
        assertEquals(1, hints.size)
        assertEquals("SEQ_SCAN", hints[0].rule)
        assertEquals("bench_items", hints[0].target)
        assertTrue("建议文案应含实测行数", hints[0].advice.contains("10000"))
    }

    @Test
    fun testPG_JSON形态_小表SeqScan不提示() {
        val small = pgJsonSeqScan.replace(""""Plan Rows":10000""", """"Plan Rows":5""")
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(small))).isEmpty(),
        )
    }

    @Test
    fun testPG_JSON形态_非SeqScan节点不提示() {
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(pgJsonResult))).isEmpty(),
        )
    }

    @Test
    fun testPG_JSON形态_嵌套子计划可识别() {
        // PG 把子计划放 "Plans" 数组；顶层 Hash Join + 子节点 Seq Scan
        val nested = """[{"Plan":{"Node Type":"Hash Join","Plan Rows":10,"Plans":[""" +
            """{"Node Type":"Seq Scan","Relation Name":"child_tbl","Plan Rows":500000,""" +
            """"Plans":[]}]}}]"""
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(nested)))
        assertEquals(1, hints.size)
        assertEquals("child_tbl", hints[0].target)
    }

    @Test
    fun testPG_JSON被截断_分片扫描仍能识别顶层节点() {
        // 实测约束：McpExplainFormatter.MAX_CELL_CHARS=2000 会把长计划 JSON 截断（尾部丢 "…"）。
        // 截断只影响尾部，顶层节点对象仍完整 → 分片扫描路径必须仍产出建议（否则大计划永不提示）
        val truncated = pgJsonSeqScan.take(150) + "…"
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(truncated)))
        assertEquals(1, hints.size)
        assertEquals("bench_items", hints[0].target)
    }

    @Test
    fun testKingBase_JSON形态同PG() {
        val hints = ExplainIndexAdvisor.advise("KingBase（人大金仓）", listOf("QUERY PLAN"), listOf(listOf(pgJsonSeqScan)))
        assertEquals(1, hints.size)
        assertEquals("bench_items", hints[0].target)
    }

    @Test
    fun testPG文本树形态_仍支持_容错回落() {
        // JSON 档的容错回落：非 JSON 的文本计划（如驱动把计划渲染成文本树）仍走文本树规则
        val rows = listOf(listOf("Seq Scan on big_table  (cost=0.00..35000.00 rows=300000 width=64)"))
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), rows)
        assertEquals(1, hints.size)
        assertEquals("big_table", hints[0].target)
    }

    // ---------- 降级与零差异 ----------

    @Test
    fun test不支持方言静默降级() {
        val rows = listOf(listOf("1", "SIMPLE", "t", "ALL", "NULL", "999999", ""))
        // 独立复核 ⑳：分派键必须是**表键**（= Dialect.name = "DM（达梦）"）。此前本用例传品牌短名
        // "DM"——`ExplainSupport` 里查不到 → 落到 else 静默降级分支，而下面的 `.isEmpty()` 对
        // 「档位零命中」与「未登记降级」同形，肉眼区分不出 ⇒ 绿灯来自错误分支。
        // 故先把**路由**钉住（键写错时本行先红），再断言输出。
        // T-Fr45DmGuard S2：DM 真机 `EXPLAIN FOR` 返回 19 列计划表 → 档位由 TEXT 改 TABLE。
        val dmDialectName = "DM（达梦）"
        assertEquals(
            "DM 必须以表键（Dialect.name）路由到 TABLE 档，品牌短名 \"DM\" 不是表键",
            ExplainOutputFormat.TABLE,
            ExplainSupport.formatFor(dmDialectName),
        )
        // DM 的真机列集没有 MySQL 的 type/key/Extra 列 → adviseMysql 列名不符即提前返回，
        // 零命中（不噪音、不猜）；不能拿 MySQL 列头喂它冒充「同档即可套规则」。
        val dmHeaders = listOf("PLAN_ID", "OPERATION", "TAB_NAME", "SCAN_TYPE", "ROW_NUMS", "COST")
        val dmRows = listOf(listOf("1", "DELETE", "ARTICLE", "FULL SCAN", "999999", "0"))
        assertTrue(ExplainIndexAdvisor.advise(dmDialectName, dmHeaders, dmRows).isEmpty())
        // R23 后 Oracle 已接入（TEXT 档）：MySQL 表格式行塞给它同样零命中——
        // 本插件通道对 Oracle 不产出计划行（EXPLAIN PLAN FOR 只登记不返回），勿硬解析
        assertTrue(ExplainIndexAdvisor.advise("Oracle", mysqlHeaders, rows).isEmpty())
        assertTrue("未知方言静默降级", ExplainIndexAdvisor.advise("no-such-dialect", mysqlHeaders, rows).isEmpty())
    }

    // ---------- R23：Oracle/SQL Server 接入后的分派边界 ----------

    /**
     * R23：Oracle TEXT 档，本插件通道不产出行（EXPLAIN PLAN FOR 只登记）→ 空行零命中不噪音。
     */
    @Test
    fun testOracle_TEXT档空行零命中() {
        assertEquals(
            "Oracle 必须以表键路由到 TEXT 档",
            ExplainOutputFormat.TEXT,
            ExplainSupport.formatFor("Oracle"),
        )
        assertTrue(ExplainIndexAdvisor.advise("Oracle", emptyList(), emptyList()).isEmpty())
    }

    /**
     * R23：SQL Server XML 档**不硬解析**（勿硬解析红线）——即使把 PG JSON 形态的行喂进来，
     * 也不得按 JSON/文本树规则产出建议；SQL Server 的计划 XML 是附加结果集，本通道不采集。
     */
    @Test
    fun testSQLServer_XML档不硬解析() {
        assertEquals(
            "SQL Server 必须以表键路由到 XML 档",
            ExplainOutputFormat.XML,
            ExplainSupport.formatFor("SQL Server"),
        )
        val pgJsonRow = listOf(listOf("""[{"Plan":{"Node Type":"Seq Scan","Relation Name":"big_table","Plan Rows":999999}}]"""))
        assertTrue(ExplainIndexAdvisor.advise("SQL Server", listOf("XML Showplan"), pgJsonRow).isEmpty())
        // 文本树形态同样不猜
        assertTrue(ExplainIndexAdvisor.advise("SQL Server", listOf("StmtText"), listOf(listOf("Seq Scan on big_table (rows=999999)"))).isEmpty())
    }

    /** 分派改读格式后，MySQL 家族仍走表格式、PG 家族仍走 JSON——方言名大小写不敏感。 */
    @Test
    fun test方言名大小写不敏感() {
        val rows = listOf(listOf("1", "SIMPLE", "orders", "ALL", "NULL", "500000", ""))
        assertEquals(1, ExplainIndexAdvisor.advise("mysql", mysqlHeaders, rows).size)
        assertEquals(1, ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, rows).size)
        assertEquals(
            1,
            ExplainIndexAdvisor.advise("postgresql", listOf("QUERY PLAN"), listOf(listOf(pgJsonSeqScan))).size,
        )
    }

    @Test
    fun test无命中渲染零差异() {
        assertEquals("", ExplainIndexAdvisor.formatHints(emptyList()))
    }

    @Test
    fun test列名形态不符不猜() {
        // FORMAT=TREE 变体等非预期列名——不猜（空输出）
        assertTrue(ExplainIndexAdvisor.advise("MySQL", listOf("EXPLAIN"), rows = listOf(listOf("foo"))).isEmpty())
    }

    // ---------- F59（T-PerfSweep18）：文本树 Regex 提 object 单例 ----------

    /**
     * 同一 Pattern 实例断言：object 属性只在初始化时求值一次，两次读引用同一实例；
     * pattern 文本与原函数体内现场字面量逐字一致（提常量零语义漂移）。
     * 红绿判别由 convention.InFunctionRegexSourceScanTest 承担（回退即函数体 Regex 复现）。
     */
    @Test
    fun `F59_Pattern单例_object属性同实例且pattern文本不变`() {
        val first = ExplainIndexAdvisor.SEQ_SCAN_ON
        assertTrue("object 属性两次读必须同一实例", first === ExplainIndexAdvisor.SEQ_SCAN_ON)
        assertTrue("object 属性两次读必须同一实例", ExplainIndexAdvisor.PLAN_TEXT_ROWS === ExplainIndexAdvisor.PLAN_TEXT_ROWS)
        assertEquals("Seq Scan on (\\w+)", first.pattern)
        assertEquals("rows=(\\d+)", ExplainIndexAdvisor.PLAN_TEXT_ROWS.pattern)
        // 行为锚：文本树解析仍命中（Seq Scan + 估算行数超阈）
        val pgRows = listOf(listOf("Seq Scan on big_table  (cost=0.00..1.01 rows=99999 width=4)"))
        val hints = ExplainIndexAdvisor.advise("postgresql", listOf("QUERY PLAN"), pgRows)
        assertEquals(1, hints.size)
        assertEquals("SEQ_SCAN", hints[0].rule)
        assertEquals("big_table", hints[0].target)
    }
}
