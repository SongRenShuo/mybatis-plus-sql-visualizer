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
        // 「TEXT 档零命中」与「未登记降级」同形，肉眼区分不出 ⇒ 绿灯来自错误分支。
        // 故先把**路由**钉住（键写错时本行先红），再断言输出。
        val dmDialectName = "DM（达梦）"
        assertEquals(
            "DM 必须以表键（Dialect.name）路由到 TEXT 档，品牌短名 \"DM\" 不是表键",
            ExplainOutputFormat.TEXT,
            ExplainSupport.formatFor(dmDialectName),
        )
        // DM（达梦）在册且接入（TEXT 档），但表格式行不含文本树模式 → 零命中（不噪音、不猜）
        assertTrue(ExplainIndexAdvisor.advise(dmDialectName, mysqlHeaders, rows).isEmpty())
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

    // ---------- R45 ③：OceanBase ASCII 计划树 ----------

    /**
     * OB 计划文本形态（真机实证：提交 58b8b5f3 正文逐字留证，社区镜像 oceanbase/oceanbase-ce:4.3.5-lts
     * / OB CE 4.3.5.6，`EXPLAIN SELECT 1` 返回单列 `Query Plan`）：
     * 表头 `|ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|` 与 EXPRESSION 行为真机原文；
     * 表扫行按表头声明的列序填充（真机留证覆盖到算子名 `TABLE RANGE SCAN`/`TABLE FULL SCAN`，
     * 未逐字落盘整行，故此处不伪造列宽——解析按列名取值，与列宽无关）。
     */
    private fun obPlan(operator: String, name: String, estRows: String): String =
        """
        ==========================================
        |ID|OPERATOR  |NAME|EST.ROWS|EST.TIME(us)|
        ------------------------------------------
        |0 |$operator|$name|$estRows|1           |
        ==========================================
        Outputs & filters:
          0 - output([*]), filter(nil), access([*])
        """.trimIndent()

    /** 路由先钉住：OceanBase 必须落在 TEXT 档（T-OBExplainFormat 真机实证），否则本组用例测的是别的分支。 */
    @Test
    fun testR45_OB路由为TEXT档() {
        assertEquals(ExplainOutputFormat.TEXT, ExplainSupport.formatFor("OceanBase"))
    }

    @Test
    fun testR45_OB全表扫超阈提示() {
        val rows = listOf(listOf(obPlan("TABLE FULL SCAN", "bench_items", "500000")))
        val hints = ExplainIndexAdvisor.advise("OceanBase", listOf("Query Plan"), rows)
        assertEquals(1, hints.size)
        assertEquals("TABLE_FULL_SCAN", hints[0].rule)
        assertEquals("bench_items", hints[0].target)
        assertTrue("建议文案应含估算行数", hints[0].advice.contains("500000"))
    }

    @Test
    fun testR45_OB全表扫小表不提示() {
        val rows = listOf(listOf(obPlan("TABLE SCAN", "tiny", "100")))
        assertTrue(ExplainIndexAdvisor.advise("OceanBase", listOf("Query Plan"), rows).isEmpty())
    }

    @Test
    fun testR45_OB范围扫超阈_报估算不报缺索引() {
        // TABLE RANGE SCAN 已走索引：不报 TABLE_FULL_SCAN，只在估算行数超阈时报 HIGH_ROWS_ESTIMATE
        val rows = listOf(listOf(obPlan("TABLE RANGE SCAN", "orders", "500000")))
        val hints = ExplainIndexAdvisor.advise("OceanBase", listOf("Query Plan"), rows)
        assertEquals(1, hints.size)
        assertEquals("HIGH_ROWS_ESTIMATE", hints[0].rule)
        assertEquals("orders", hints[0].target)
    }

    @Test
    fun testR45_OB点查与常量计划不提示() {
        assertTrue(
            ExplainIndexAdvisor.advise("OceanBase", listOf("Query Plan"), listOf(listOf(obPlan("TABLE GET", "t", "1")))).isEmpty(),
        )
        assertTrue(
            ExplainIndexAdvisor.advise("OceanBase", listOf("Query Plan"), listOf(listOf(obPlan("EXPRESSION", "", "1")))).isEmpty(),
        )
    }

    /**
     * 反例：OB 规则不得外溢。
     *  - MySQL（TABLE 档）拿到 OB 文本 → 列名不符，零命中；
     *  - H2 文本里出现 `TABLE SCAN` 字样但无 OB 表头 → 零命中（门是表头，不是算子词）；
     *  - PG 文本树规则不受影响（同一次调用仍识别 Seq Scan）。
     */
    @Test
    fun testR45_OB规则不误伤其它方言() {
        val obText = obPlan("TABLE FULL SCAN", "bench_items", "500000")
        assertTrue(
            "MySQL 是 TABLE 档，文本计划不得被 MySQL 列名分支或 OB 分支命中",
            ExplainIndexAdvisor.advise("MySQL", mysqlHeaders, listOf(listOf(obText))).isEmpty(),
        )
        assertTrue(
            "无 OB 表头的 TABLE SCAN 字样（H2 等）不得触发 OB 规则",
            ExplainIndexAdvisor.advise("H2", listOf("PLAN"), listOf(listOf("TABLE SCAN big_table"))).isEmpty(),
        )
        val pg = listOf(listOf("Seq Scan on big_table  (cost=0.00..35000.00 rows=300000 width=64)"))
        val pgHints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), pg)
        assertEquals(1, pgHints.size)
        assertEquals("SEQ_SCAN", pgHints[0].rule)
    }

    @Test
    fun testR45_规则清单_含OB全表扫() {
        assertTrue(ExplainIndexAdvisor.ruleInventory().any { it.rule == "TABLE_FULL_SCAN" })
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

    // ---------- PEV-04：计划警示徽章族（Heap Fetches / Hash Batches / Filter 高过滤比） ----------

    /**
     * PEV-04 JSON 夹具：Bitmap Heap Scan 带高 Heap Fetches（pev2 heapFetchesClass 语义转写，
     * 形态基线 = PG EXPLAIN ANALYZE 真机输出的键名）。
     */
    private val pgJsonBitmapHeapScan = """[{"Plan":{"Node Type":"Bitmap Heap Scan","Relation Name":"big_table",""" +
        """"Plan Rows":50000,"Actual Rows":43210,"Actual Loops":1,"Heap Fetches":42000,""" +
        """"Rows Removed by Filter":1000,"Recheck Cond":"(v > 100)","Plans":[]}}]"""

    @Test
    fun `testPEV04_HeapFetches超阈提示`() {
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(pgJsonBitmapHeapScan)))
        val hf = hints.filter { it.rule == "HEAP_FETCHES" }
        assertEquals("Bitmap Heap Scan 高 Heap Fetches 应产出一条建议", 1, hf.size)
        assertTrue("建议文案应含 fetches 数", hf[0].advice.contains("42000"))
        assertEquals("Bitmap Heap Scan on big_table", hf[0].target)
    }

    @Test
    fun `testPEV04_HeapFetches低占比不提示`() {
        // fetches 占比 ≈ 4%（42000/1_000_000）——远低于阈值，不噪音
        val low = pgJsonBitmapHeapScan
            .replace(""""Actual Rows":43210""", """"Actual Rows":1000000""")
            .replace(""""Rows Removed by Filter":1000""", """"Rows Removed by Filter":0""")
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(low))).none { it.rule == "HEAP_FETCHES" },
        )
    }

    @Test
    fun `testPEV04_HeapFetches阈值边界`() {
        // 占比恰在阈值（40/100 = 40%）→ 提示；差一丝（39/100 = 39%）→ 不提示
        val at = """[{"Plan":{"Node Type":"Bitmap Heap Scan","Relation Name":"t","Plan Rows":100,""" +
            """"Actual Rows":100,"Actual Loops":1,"Heap Fetches":40,"Plans":[]}}]"""
        val below = at.replace(""""Heap Fetches":40""", """"Heap Fetches":39""")
        val atHints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(at)))
        val belowHints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(below)))
        assertEquals(1, atHints.filter { it.rule == "HEAP_FETCHES" }.size)
        assertTrue(belowHints.none { it.rule == "HEAP_FETCHES" })
    }

    @Test
    fun `testPEV04_HeapFetches无actualRows不判`() {
        // 无 Actual Rows（估算档）→ 无分母，诚实不判（不猜）
        val noActual = pgJsonBitmapHeapScan.replace(""""Actual Rows":43210,"Actual Loops":1,""", "")
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(noActual))).none { it.rule == "HEAP_FETCHES" },
        )
    }

    @Test
    fun `testPEV04_HashBatches溢盘提示`() {
        // Hash 节点 Batches: 16（pev2 45-buckets 夹具形态：1048576 桶 16 批）
        val hash = """[{"Plan":{"Node Type":"Hash","Plan Rows":9999056,"Actual Rows":9999900,"Actual Loops":1,""" +
            """"Hash Buckets":1048576,"Hash Batches":16,"Original Hash Batches":1,"Peak Memory Usage":62533,""" +
            """"Plans":[{"Node Type":"Seq Scan","Relation Name":"entetes","Plan Rows":9999056}]}}]"""
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(hash)))
        val hb = hints.filter { it.rule == "HASH_BATCHES" }
        assertEquals(1, hb.size)
        assertTrue("建议文案应含批次数", hb[0].advice.contains("16"))
        // Seq Scan 子节点无 Batches → 不误报
        assertTrue(hints.none { it.target.startsWith("Seq Scan") && it.rule == "HASH_BATCHES" })
    }

    @Test
    fun `testPEV04_HashBatches单批不提示`() {
        val ok = """[{"Plan":{"Node Type":"Hash","Plan Rows":100,"Hash Batches":1,"Hash Buckets":1024,"Plans":[]}}]"""
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(ok))).none { it.rule == "HASH_BATCHES" },
        )
    }

    @Test
    fun `testPEV04_HashAgg独立Batches行溢盘提示`() {
        val agg = """[{"Plan":{"Node Type":"HashAggregate","Plan Rows":10000,"Actual Rows":10000,"Actual Loops":1,""" +
            """"HashAgg Batches":5,"Peak Memory Usage":161,"Disk Usage":200,"Plans":[]}}]"""
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(agg)))
        val hb = hints.filter { it.rule == "HASH_BATCHES" }
        assertEquals("HashAgg 独立 Batches 行应同样触发", 1, hb.size)
        assertTrue(hb[0].advice.contains("5"))
    }

    @Test
    fun `testPEV04_Filter高过滤比提示`() {
        // Seq Scan：actual 15 + removed 13205 → 13205/13220 ≈ 99.9%（pev2 text-0 psql 夹具形态）
        val filtered = """[{"Plan":{"Node Type":"Seq Scan","Relation Name":"part_0","Plan Rows":15,""" +
            """"Actual Rows":15,"Actual Loops":1,"Filter":"(added_by = 'depesz'::text)",""" +
            """"Rows Removed by Filter":13205,"Plans":[]}}]"""
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(filtered)))
        val fr = hints.filter { it.rule == "HIGH_FILTER_RATIO" }
        assertEquals(1, fr.size)
        assertEquals("Seq Scan on part_0", fr[0].target)
    }

    @Test
    fun `testPEV04_Filter低过滤比不提示`() {
        // removed 101 / actual 9999900 ≈ 0%（pev2 45-buckets entetes 形态）——不噪音
        val filtered = """[{"Plan":{"Node Type":"Seq Scan","Relation Name":"entetes","Plan Rows":9999056,""" +
            """"Actual Rows":9999900,"Actual Loops":1,"Filter":"(a > 100)","Rows Removed by Filter":101,"Plans":[]}}]"""
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(filtered))).none { it.rule == "HIGH_FILTER_RATIO" },
        )
    }

    @Test
    fun `testPEV04_Filter无RowsRemoved不判`() {
        // 有 Filter 条件但没有 Rows Removed by Filter（估算档）——无从计算过滤比，不判
        val estimateOnly = """[{"Plan":{"Node Type":"Seq Scan","Relation Name":"t","Plan Rows":100,""" +
            """"Filter":"(a > 1)","Plans":[]}}]"""
        assertTrue(
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(estimateOnly))).none { it.rule == "HIGH_FILTER_RATIO" },
        )
    }

    /** PEV-04 text 档夹具：Bitmap Heap Scan 高 Heap Fetches（解析器产出结构化节点后套同一规则）。 */
    private val pgTextBitmapHeapScan = """
Bitmap Heap Scan on big_table  (cost=100.00..2000.00 rows=50000 width=8) (actual time=1.000..9.000 rows=43210 loops=1)
  Recheck Cond: (v > 100)
  Heap Fetches: 42000
  ->  Bitmap Index Scan on idx_v  (cost=0.00..100.00 rows=50000 width=0) (actual time=0.900..0.900 rows=43210 loops=1)
        Index Cond: (v > 100)
"""

    @Test
    fun `testPEV04_文本档BitmapHeapScan经解析器出HeapFetches提示`() {
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(pgTextBitmapHeapScan)))
        assertTrue(
            "text 档应经解析器产出 HEAP_FETCHES",
            hints.any { it.rule == "HEAP_FETCHES" && it.advice.contains("42000") },
        )
    }

    @Test
    fun `testPEV04_文本档Hash节点Batches溢盘提示且Filter不误报`() {
        val text = """
Hash Join  (cost=471037.90..1304640.12 rows=14999862 width=114) (actual time=1480.644..9895.695 rows=15001138 loops=1)
  Hash Cond: (details.eid = entetes.eid)
  ->  Seq Scan on details  (cost=0.00..335215.62 rows=15001362 width=65) (actual time=0.014..1114.539 rows=15001289 loops=1)
  ->  Hash  (cost=238637.70..238637.70 rows=9999056 width=57) (actual time=1474.384..1474.385 rows=9999900 loops=1)
        Buckets: 1048576  Batches: 16  Memory Usage: 62533kB
        ->  Seq Scan on entetes  (cost=0.00..238637.70 rows=9999056 width=57) (actual time=0.016..567.370 rows=9999900 loops=1)
              Filter: (a > 100)
              Rows Removed by Filter: 101
"""
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(text)))
        assertTrue("Batches: 16 应触发溢盘提示", hints.any { it.rule == "HASH_BATCHES" && it.advice.contains("16") })
        // entetes 过滤比 101/10000001 ≈ 0%——不误报
        assertTrue("低过滤比不得误报", hints.none { it.rule == "HIGH_FILTER_RATIO" })
    }

    @Test
    fun `testPEV04_文本档Filter高过滤比提示`() {
        val text = """
Nested Loop  (cost=0.00..2017.80 rows=15 width=133) (actual time=0.052..2.567 rows=15 loops=1)
  ->  Seq Scan on users u  (cost=0.00..41.40 rows=1 width=129) (actual time=0.020..0.092 rows=1 loops=1)
        Filter: (username = 'depesz'::text)
        Rows Removed by Filter: 1231
  ->  Seq Scan on part_0 p  (cost=0.00..1976.25 rows=15 width=17) (actual time=0.031..2.471 rows=15 loops=1)
        Filter: (added_by = 'depesz'::text)
        Rows Removed by Filter: 13205
"""
        val hints = ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(text)))
        val fr = hints.filter { it.rule == "HIGH_FILTER_RATIO" }
        assertEquals("两个高过滤比 Seq Scan 各一条", 2, fr.size)
    }

    @Test
    fun `testPEV04_worker级Batches不触发节点溢盘提示`() {
        // pev2 27-hashagg 第二例：Batches: 5 在 Worker 行上——节点自己的 Batches 是 1，不得误报
        val text = """
Gather  (cost=1170.00..2270.00 rows=10000 width=4) (actual time=1.000..3.000 rows=10000 loops=1)
  Workers Planned: 1
  Workers Launched: 1
  ->  Partial HashAggregate  (cost=170.00..270.00 rows=10000 width=4) (actual time=1.000..2.000 rows=5000 loops=2)
        Group Key: k
        Batches: 1  Memory Usage: 481kB
        Worker 0:  Batches: 5  Memory Usage: 161kB  Disk Usage: 200kB
        ->  Parallel Seq Scan on synthetic_data  (cost=0.00..145.00 rows=10000 width=4) (actual time=0.010..0.500 rows=10000 loops=2)
"""
        assertTrue(
            "worker 级 Batches 不得触发节点 HASH_BATCHES",
            ExplainIndexAdvisor.advise("PostgreSQL", listOf("QUERY PLAN"), listOf(listOf(text))).none { it.rule == "HASH_BATCHES" },
        )
    }

    @Test
    fun `testPEV04_规则清单含三新规则`() {
        val rules = ExplainIndexAdvisor.ruleInventory().map { it.rule }
        assertTrue("清单应含 HEAP_FETCHES", "HEAP_FETCHES" in rules)
        assertTrue("清单应含 HASH_BATCHES", "HASH_BATCHES" in rules)
        assertTrue("清单应含 HIGH_FILTER_RATIO", "HIGH_FILTER_RATIO" in rules)
    }

    @Test
    fun `testPEV04_非PG族文本档天然零命中`() {
        // DM8/H2 文本（无 cost 节点头）→ 解析器诚实失败 → 新规则零产出（不噪音）
        val dmText = "1|#NSET2:SELECT STATEMENT\n2|#PRJT2\n3|#CSCN2:SYS.T1"
        assertTrue(
            ExplainIndexAdvisor.advise("DM（达梦）", listOf("QUERY PLAN"), listOf(listOf(dmText))).isEmpty(),
        )
    }
}
