package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PEV-01 text EXPLAIN 解析器单测（渲染判别测试）。
 *
 * 夹具基线：pev2 v1.25.0（开源 MIT 参考实现）`from-text` / `11-psql-frames` /
 * `12-line-wrapped-plans` / `04-actual-duration-parallel-from-text` / `27-hashagg-batches`
 * 中有代表性的形态，转写为本仓自有常量；断言全部按本仓口径书写（节点数与手数一致、
 * 结构化字段归位、诚实降级不猜不炸）。
 */
class ExplainTextPlanTreeTest {

    // ---------- 夹具常量（缩进即语义，禁 trimIndent） ----------

    /** from-text/01：仅估算档单节点。 */
    private val singleNodeEstimateOnly = """
Seq Scan on tenk1  (cost=0.00..333.00 rows=10000 width=148)
"""

    /** from-text/02：ANALYZE 档单节点（cost + actual 全形态）。 */
    private val singleNodeAnalyze = """
Seq Scan on test  (cost=0.00..34.00 rows=2400 width=4) (actual time=0.052..2.183 rows=1000 loops=1)
"""

    /** from-text/04：节点 + 属性行。 */
    private val indexScanWithCond = """
Index Scan using test_pkey on test  (cost=0.00..8.27 rows=1 width=4)
  Index Cond: (id = 4)
"""

    /** from-text/08：两级树（`->` 前缀）。 */
    private val twoLevel = """
 Limit  (cost=0.00..0.01 rows=1 width=4)
   ->  Seq Scan on test  (cost=0.00..14.00 rows=1000 width=4)
"""

    /** from-text/09：四节点树 + 查询回显行 + QUERY PLAN 表头 + 行数尾行。 */
    private val fourNodeTree = """
# explain select * from test t1 join test t2 on t1.id = t2.id + 1 limit 1;
                                     QUERY PLAN
-------------------------------------------------------------------------------------
 Limit  (cost=0.00..0.34 rows=1 width=8)
   ->  Nested Loop  (cost=0.00..335.20 rows=1000 width=8)
         ->  Seq Scan on test t2  (cost=0.00..14.00 rows=1000 width=4)
         ->  Index Scan using test_pkey on test t1  (cost=0.00..0.31 rows=1 width=4)
               Index Cond: (t1.id = (t2.id + 1))
(5 rows)
"""

    /** from-text/06：`#` 回显 + Total runtime 根级行 + 行数尾行。 */
    private val totalRuntimeTree = """
# explain analyze select * from test as t where id = 1;
                                                    QUERY PLAN
------------------------------------------------------------------------------------------------------------------
 Index Scan using test_pkey on test t  (cost=0.00..8.27 rows=1 width=4) (actual time=0.029..0.033 rows=1 loops=1)
   Index Cond: (id = 1)
 Total runtime: 0.083 ms
(3 rows)
"""

    /** 11-psql-frames text-0-aligned-ascii：psql 对齐输出（表头/分隔线/尾行，无框线字符）。 */
    private val psqlAlignedAscii = """
                                                 QUERY PLAN
------------------------------------------------------------------------------------------------------------
Nested Loop  (cost=0.00..2017.80 rows=15 width=133) (actual time=0.052..2.567 rows=15 loops=1)
  ->  Seq Scan on users u  (cost=0.00..41.40 rows=1 width=129) (actual time=0.020..0.092 rows=1 loops=1)
        Filter: (username = 'depesz'::text)
        Rows Removed by Filter: 1231
  ->  Seq Scan on part_0 p  (cost=0.00..1976.25 rows=15 width=17) (actual time=0.031..2.471 rows=15 loops=1)
        Filter: (added_by = 'depesz'::text)
        Rows Removed by Filter: 13205
Planning Time: 0.053 ms
Execution Time: 2.583 ms
(9 rows)
"""

    /** 11-psql-frames text-2-aligned-unicode：Unicode 双边框（`║` 框线清洗 + 根节点 1 空格缩进）。 */
    private val psqlUnicodeFrame = """
╔════════╗
║                                                  QUERY PLAN                                   ║
╠════════╣
║ Nested Loop  (cost=0.00..2017.80 rows=15 width=133) (actual time=0.035..2.219 rows=15 loops=1)               ║
║   ->  Seq Scan on users u  (cost=0.00..41.40 rows=1 width=129) (actual time=0.013..0.091 rows=1 loops=1)     ║
║         Filter: (username = 'depesz'::text)                                                                  ║
║         Rows Removed by Filter: 1231                                                                         ║
║   ->  Seq Scan on part_0 p  (cost=0.00..1976.25 rows=15 width=17) (actual time=0.021..2.125 rows=15 loops=1) ║
║         Filter: (added_by = 'depesz'::text)                                                                  ║
║         Rows Removed by Filter: 13205                                                                        ║
║ Planning Time: 0.040 ms                                                                                      ║
║ Execution Time: 2.228 ms                                                                                     ║
╚════════╝
(9 rows)
"""

    /** 11-psql-frames text-3-dbeaver：右框线 `|` 未对齐形态 + WAL/Planning 块（逐字留证夹具，分隔线以 `+` 收尾）。 */
    private val dbeaverPlan = """
QUERY PLAN                                                                                                                          |
------------------------------------------------------------------------------------------------------------------------------------+
Delete on public.toto123456789  (cost=0.00..78540.20 rows=0 width=0) (actual time=4024.307..4024.308 rows=0 loops=1)                |
  Buffers: shared hit=5000000 read=22124 dirtied=22124 written=7338                                                                 |
  WAL: records=5000000 fpi=7015 bytes=327276611                                                                                     |
  ->  Seq Scan on public.toto123456789  (cost=0.00..78540.20 rows=5641620 width=6) (actual time=0.019..673.892 rows=5000000 loops=1)|
        Output: ctid                                                                                                                |
        Buffers: shared read=22124 written=7338                                                                                     |
Planning:                                                                                                                           |
  Buffers: shared hit=4                                                                                                             |
Planning Time: 0.037 ms                                                                                                             |
Execution Time: 4024.335 ms                                                                                                         |
"""

    /**
     * 12-line-wrapped-plans/01：psql 折行计划（断点在 cost/actual 段中、`)` 收尾的续行在列 0）。
     * 折行点行尾的空格是真实夹具的原样字节（psql 折行保留行尾空格）——用 ${" "} 显式表达，
     * 避免编辑器剥尾随空白后续接出 "0.532rows=0" 的粘词形态（L37 同款判据）。
     */
    private val lineWrappedPlan = """
                                                                  QUERY PLAN
──────────────────────────────────────────────────────────────────────────────
 Sort  (cost=32.88..32.96 rows=32 width=224) (actual time=0.532..0.532${" "}
rows=0 loops=1)
   Sort Key: n.nspname, c.relname
   Sort Method: quicksort  Memory: 25kB
   ->  Hash Join  (cost=1.09..32.08 rows=32 width=224) (actual time=0.5
24..0.525 rows=0 loops=1)
         Hash Cond: (c.relnamespace = n.oid)
         ->  Seq Scan on pg_class c  (cost=0.00..29.84 rows=64 width=73
) (actual time=0.026..0.397 rows=128 loops=1)
               Filter: ((relkind = ANY ('{r,p,v,m,S,f,""}'::"char"[])) AND pg_table_is_visible(oid))
               Rows Removed by Filter: 261
         ->  Hash  (cost=1.07..1.07 rows=2 width=68) (actual time=0.062..0.062 rows=2 loops=1)
               Buckets: 1024  Batches: 1  Memory Usage: 9kB
               ->  Seq Scan on pg_namespace n  (cost=0.00..1.07 rows=2 width=68) (actual time=0.032..0.038 rows=2 loops=1)
                     Filter: ((nspname <> 'pg_catalog'::name) AND (nspname <> 'information_schema'::name))
                     Rows Removed by Filter: 3
 Planning Time: 0.636 ms
 Execution Time: 0.616 ms
(15 rows)
"""

    /** from-text/46 精选段：Buckets originally 括注 + 小数 actual rows + 根级 Planning/JIT 块。 */
    private val bucketsOriginallyPlan = """
 Aggregate  (cost=302291.30..302291.31 rows=1 width=8) (actual time=3656.686..3656.688 rows=1.00 loops=1)
   Buffers: shared hit=6734 read=41984 dirtied=13621, temp read=49855 written=49855
   ->  Hash Join  (cost=27826.00..300076.30 rows=886000 width=0) (actual time=146.635..3619.685 rows=1001000.00 loops=1)
         Hash Cond: (r.i = s1k.i)
         ->  Seq Scan on r  (cost=0.00..144294.76 rows=10000676 width=4) (actual time=0.201..1200.806 rows=10000000.00 loops=1)
         ->  Hash  (cost=13290.00..13290.00 rows=886000 width=4) (actual time=145.449..145.450 rows=1001000.00 loops=1)
               Buckets: 262144 (originally 262144)  Batches: 2048 (originally 8)  Memory Usage: 35157kB
               ->  Seq Scan on s1k  (cost=0.00..13290.00 rows=886000 width=4) (actual time=0.011..61.783 rows=1001000.00 loops=1)
 Planning:
   Buffers: shared hit=83 read=9
 Planning Time: 0.495 ms
 JIT:
   Functions: 10
   Options: Inlining false, Optimization false, Expressions true, Deforming true
   Timing: Generation 0.492 ms (Deform 0.134 ms), Inlining 0.000 ms, Optimization 0.266 ms, Emission 3.684 ms, Total 4.442 ms
 Execution Time: 3674.128 ms
(20 rows)
"""

    /** 04-actual-duration-parallel-from-text：Workers 分段（Sort Method 形态 + actual 形态）。 */
    private val parallelWorkersPlan = """
 Gather Merge  (cost=735306.27..1707599.95 rows=8333364 width=17) (actual time=1560.468..3749.583 rows=10000000 loops=1)
   Output: c1, c2
   Workers Planned: 2
   Workers Launched: 2
   ->  Sort  (cost=734306.25..744722.95 rows=4166682 width=17) (actual time=1474.182..1967.788 rows=3333333 loops=3)
         Output: c1, c2
         Sort Key: t1.c1
         Sort Method: external merge  Disk: 125168kB
         Worker 0:  Sort Method: external merge  Disk: 73768kB
         Worker 1:  Sort Method: external merge  Disk: 74088kB
         Worker 0: actual time=1431.136..1883.370 rows=2700666 loops=1
         Worker 1: actual time=1431.175..1891.630 rows=2712505 loops=1
         ->  Parallel Seq Scan on public.t1  (cost=0.00..105264.82 rows=4166682 width=17) (actual time=0.214..386.014 rows=3333333 loops=3)
               Output: c1, c2
               Worker 0: actual time=0.027..382.325 rows=2700666 loops=1
               Worker 1: actual time=0.038..384.951 rows=2712505 loops=1
 Planning Time: 0.180 ms
 Execution Time: 4166.867 ms
(18 rows)
"""

    /** 05-parallel-jit 精选段：`(never executed)` 节点。 */
    private val neverExecutedPlan = """
Hash Left Join  (cost=1.07..413566.76 rows=4879205 width=19) (never executed)
  Inner Unique: true
  Hash Cond: (client_main.id_devise = devise_main.id_devise)
  ->  Parallel Seq Scan on aristote_mdl.clients client_main  (cost=0.00..382664.05 rows=4879205 width=16) (never executed)
        Output: client_main.id_client
  ->  Hash  (cost=1.03..1.03 rows=3 width=11) (never executed)
        ->  Seq Scan on aristote_mdl.devises devise_main  (cost=0.00..1.03 rows=3 width=11) (never executed)
              Output: devise_main.code_devise
"""

    /** 27-hashagg-batches：HashAggregate 独立 `Batches: N Memory Usage: ..kB Disk Usage: ..kB` 行。 */
    private val hashAggBatchesPlan = """
HashAggregate  (cost=170.00..270.00 rows=10000 width=4) (actual time=1.000..2.000 rows=10000 loops=1)
  Group Key: k
  Batches: 5  Memory Usage: 161kB  Disk Usage: 200kB
  ->  Seq Scan on synthetic_data  (cost=0.00..145.00 rows=10000 width=4) (actual time=0.010..0.500 rows=10000 loops=1)
Planning Time: 0.100 ms
Execution Time: 2.500 ms
"""

    /** 27-hashagg-batches 第二例：worker 级 Batches 不得混入节点结构化字段。 */
    private val workerBatchesPlan = """
Gather  (cost=1170.00..2270.00 rows=10000 width=4) (actual time=1.000..3.000 rows=10000 loops=1)
  Workers Planned: 1
  Workers Launched: 1
  ->  Partial HashAggregate  (cost=170.00..270.00 rows=10000 width=4) (actual time=1.000..2.000 rows=5000 loops=2)
        Group Key: k
        Batches: 1  Memory Usage: 481kB
        Worker 0:  Batches: 5  Memory Usage: 161kB  Disk Usage: 200kB
        ->  Parallel Seq Scan on synthetic_data  (cost=0.00..145.00 rows=10000 width=4) (actual time=0.010..0.500 rows=10000 loops=2)
Planning Time: 0.100 ms
Execution Time: 3.500 ms
"""

    /** 05-parallel-jit 精选段：worker 深层续行（Buffers/JIT）归 worker 叶。 */
    private val workerJitPlan = """
Parallel Hash  (cost=209817.72..209817.72 rows=1 width=48) (actual time=1599.389..1599.395 rows=0 loops=3)
  Buckets: 1024  Batches: 1  Memory Usage: 0kB
  Worker 0:  actual time=1583.596..1583.600 rows=0 loops=1
    Buffers: shared hit=9
    JIT:
      Functions: 49
      Options: Inlining true, Optimization true, Expressions true, Deforming true
  Worker 1:  actual time=1591.903..1591.911 rows=0 loops=1
    Buffers: shared hit=9
  ->  Hash Join  (cost=27.55..209817.72 rows=1 width=48) (actual time=1435.827..1435.833 rows=0 loops=3)
        Hash Cond: (a.id = b.id)
"""

    /** from-text/12：SubPlan 分段。 */
    private val subPlanTree = """
Seq Scan on pg_class c  (cost=0.00..1885.60 rows=227 width=200)
  SubPlan
    ->  Index Scan using pg_class_relname_nsp_index on pg_class c2  (cost=0.00..8.27 rows=1 width=4)
          Index Cond: (relname = ${'$'}0)
(4 rows)
"""

    /** CTE 分段（自造夹具：CTE 定义 + CTE Scan 兄弟复位）。 */
    private val ctePlan = """
Sort  (cost=144.30..144.55 rows=100 width=316)
  CTE t
    ->  Seq Scan on t  (cost=0.00..12.90 rows=100 width=316)
  ->  CTE Scan on t  (cost=12.90..14.15 rows=100 width=316)
"""

    /** from-text/29：`(actual rows=.. loops=..)` 无 time 形态 + 法语行数尾 `(3 lignes)`。 */
    private val actualNoTimePlan = """
explain (analyze, timing off) select * from pg_class ;
                                     QUERY PLAN
-----------------------------------------------------------------------------------
 Seq Scan on pg_class  (cost=0.00..32.98 rows=698 width=776) (actual rows=698 loops=1)
 Planning Time: 0.051 ms
 Execution Time: 0.105 ms
(3 lignes)
"""

    /** psql 命令回显链（夹具 46 形态）：SET/CREATE TABLE/INSERT 回显容忍跳过，不炸树。 */
    private val psqlEchoJunkPlan = """
SET work_mem = '4MB';
CREATE TABLE r AS SELECT generate_series(1, 10000000)::int i;
INSERT 0 1000
                              QUERY PLAN
------------------------------------------------------------------------
 Seq Scan on t  (cost=0.00..12.90 rows=100 width=316)
(1 row)
"""

    // ---------- 基础形态 ----------

    @Test
    fun `单节点_仅估算档`() {
        val s = ExplainTextPlanTree.parse(singleNodeEstimateOnly) as ExplainTextPlanTree.Result.Success
        assertEquals(1, s.roots.size)
        assertEquals("Seq Scan on tenk1 (rows=10000)", s.roots[0].label)
        assertEquals(1, s.planNodes.size)
        assertEquals(10000L, s.planNodes[0].planRows)
        assertEquals(333.00, s.planNodes[0].totalCost!!, 0.0001)
        assertEquals(148L, s.planNodes[0].planWidth)
        assertNull(s.planNodes[0].actualRows)
        assertFalse(s.isAnalyze)
    }

    @Test
    fun `单节点_ANALYZE档_actual解析`() {
        val s = ExplainTextPlanTree.parse(singleNodeAnalyze) as ExplainTextPlanTree.Result.Success
        // rows 展示 Plan Rows 优先（与 ExplainJsonPlanTree.planLabel 同口径），actual 在结构化字段里不丢
        assertEquals("Seq Scan on test (rows=2400)", s.roots[0].label)
        val n = s.planNodes[0]
        assertEquals(2400L, n.planRows)
        assertEquals(1000.0, n.actualRows!!, 0.0001)
        assertEquals(1L, n.actualLoops)
        assertEquals(0.052, n.actualStartupTime!!, 0.000001)
        assertEquals(2.183, n.actualTotalTime!!, 0.000001)
        assertTrue(s.isAnalyze)
    }

    @Test
    fun `节点属性行归节点_渲染为叶`() {
        val s = ExplainTextPlanTree.parse(indexScanWithCond) as ExplainTextPlanTree.Result.Success
        assertEquals("Index Scan using test_pkey on test (rows=1)", s.roots[0].label)
        assertEquals(1, s.roots[0].children.size)
        assertEquals("Index Cond: (id = 4)", s.roots[0].children[0].label)
        assertEquals("(id = 4)", s.planNodes[0].attributes["Index Cond"])
    }

    @Test
    fun `两级树_箭头子节点挂父`() {
        val s = ExplainTextPlanTree.parse(twoLevel) as ExplainTextPlanTree.Result.Success
        assertEquals(1, s.roots.size)
        assertEquals("Limit (rows=1)", s.roots[0].label)
        assertEquals(1, s.roots[0].children.size)
        assertEquals("Seq Scan on test (rows=1000)", s.roots[0].children[0].label)
        assertEquals(2, s.planNodes.size)
    }

    @Test
    fun `四节点树_节点数与手数一致_头尾清洗`() {
        val s = ExplainTextPlanTree.parse(fourNodeTree) as ExplainTextPlanTree.Result.Success
        assertEquals(1, s.roots.size)
        assertEquals("Limit (rows=1)", s.roots[0].label)
        assertEquals(1, s.roots[0].children.size)
        val nested = s.roots[0].children[0]
        assertEquals("Nested Loop (rows=1000)", nested.label)
        assertEquals(2, nested.children.size)
        assertEquals("Seq Scan on test t2 (rows=1000)", nested.children[0].label)
        assertEquals("Index Scan using test_pkey on test t1 (rows=1)", nested.children[1].label)
        assertEquals("Index Cond: (t1.id = (t2.id + 1))", nested.children[1].children[0].label)
        assertEquals(4, s.planNodes.size)
        assertFalse(s.isAnalyze)
    }

    @Test
    fun `根级计时行出为附属叶_回显行不并入节点`() {
        val s = ExplainTextPlanTree.parse(totalRuntimeTree) as ExplainTextPlanTree.Result.Success
        assertEquals(2, s.roots.size)
        assertEquals("Index Scan using test_pkey on test t (rows=1)", s.roots[0].label)
        assertEquals("Total runtime: 0.083 ms", s.roots[1].label)
        assertEquals(1, s.planNodes.size)
        assertTrue(s.isAnalyze)
    }

    // ---------- psql 表格形态清洗 ----------

    @Test
    fun `psql对齐输出_表头分隔线尾行清洗`() {
        val s = ExplainTextPlanTree.parse(psqlAlignedAscii) as ExplainTextPlanTree.Result.Success
        assertEquals(3, s.roots.size)
        assertEquals("Nested Loop (rows=15)", s.roots[0].label)
        assertEquals(2, s.roots[0].children.size)
        assertEquals("Seq Scan on users u (rows=1)", s.roots[0].children[0].label)
        assertEquals("Seq Scan on part_0 p (rows=15)", s.roots[0].children[1].label)
        assertEquals(1231L, s.planNodes[1].rowsRemovedByFilter)
        assertEquals(13205L, s.planNodes[2].rowsRemovedByFilter)
        assertEquals("(username = 'depesz'::text)", s.planNodes[1].attributes["Filter"])
        assertTrue(s.isAnalyze)
        assertEquals("Planning Time: 0.053 ms", s.roots[1].label)
        assertEquals("Execution Time: 2.583 ms", s.roots[2].label)
    }

    @Test
    fun `psqlUnicode双边框清洗_根缩进深度语义不破`() {
        val s = ExplainTextPlanTree.parse(psqlUnicodeFrame) as ExplainTextPlanTree.Result.Success
        assertEquals(3, s.roots.size)
        assertEquals("Nested Loop (rows=15)", s.roots[0].label)
        assertEquals(2, s.roots[0].children.size)
        assertEquals(3, s.planNodes.size)
        assertEquals(1231L, s.planNodes[1].rowsRemovedByFilter)
        assertEquals(13205L, s.planNodes[2].rowsRemovedByFilter)
    }

    @Test
    fun `dbeaver右框_WAL与Buffers属性_Planning块`() {
        val s = ExplainTextPlanTree.parse(dbeaverPlan) as ExplainTextPlanTree.Result.Success
        assertEquals(4, s.roots.size)
        val delete = s.roots[0]
        assertEquals("Delete on public.toto123456789 (rows=0)", delete.label)
        // Delete 节点渲染 children = Buffers 叶 + WAL 叶 + Seq Scan 子节点
        assertEquals(3, delete.children.size)
        assertEquals("Seq Scan on public.toto123456789 (rows=5641620)", delete.children[2].label)
        assertEquals("shared hit=5000000 read=22124 dirtied=22124 written=7338", s.planNodes[0].attributes["Buffers"])
        assertEquals("records=5000000 fpi=7015 bytes=327276611", s.planNodes[0].attributes["WAL"])
        assertEquals(2, s.planNodes.size)
        assertEquals("shared read=22124 written=7338", s.planNodes[1].attributes["Buffers"])
        // 根级 Planning 块：合成节点 + 深层行挂块内
        assertEquals("Planning", s.roots[1].label)
        assertEquals(1, s.roots[1].children.size)
        assertEquals("Buffers: shared hit=4", s.roots[1].children[0].label)
        assertEquals("Planning Time: 0.037 ms", s.roots[2].label)
        assertEquals("Execution Time: 4024.335 ms", s.roots[3].label)
    }

    // ---------- 折行与杂散行容忍 ----------

    @Test
    fun `折行计划续接_节点数与属性不丢`() {
        val s = ExplainTextPlanTree.parse(lineWrappedPlan) as ExplainTextPlanTree.Result.Success
        assertEquals(3, s.roots.size)
        assertEquals(5, s.planNodes.size)
        val sort = s.planNodes[0]
        assertEquals("Sort", sort.label)
        // 折行断点恰在 actual 段中——续接后 rows=0 loops=1 归位（不是丢成 null）
        assertEquals(0.0, sort.actualRows!!, 0.0001)
        assertEquals(1L, sort.actualLoops)
        assertEquals("n.nspname, c.relname", sort.attributes["Sort Key"])
        assertEquals("quicksort  Memory: 25kB", sort.attributes["Sort Method"])
        assertEquals("Hash Join", s.planNodes[1].label)
        assertEquals("(c.relnamespace = n.oid)", s.planNodes[1].attributes["Hash Cond"])
        assertEquals("Seq Scan on pg_class c", s.planNodes[2].label)
        assertEquals(128.0, s.planNodes[2].actualRows!!, 0.0001)
        assertEquals("Hash", s.planNodes[3].label)
        assertEquals(1L, s.planNodes[3].hashBatches)
        // 行未带 originally → originalHashBatches 按 pev2 口径回退当前批次数
        assertEquals(1L, s.planNodes[3].originalHashBatches)
        assertEquals("Seq Scan on pg_namespace n", s.planNodes[4].label)
        assertEquals(261L, s.planNodes[2].rowsRemovedByFilter)
        assertEquals(3L, s.planNodes[4].rowsRemovedByFilter)
    }

    @Test
    fun `psql命令回显链_容忍跳过不炸树`() {
        val s = ExplainTextPlanTree.parse(psqlEchoJunkPlan) as ExplainTextPlanTree.Result.Success
        assertEquals(1, s.roots.size)
        assertEquals("Seq Scan on t (rows=100)", s.roots[0].label)
        assertEquals(1, s.planNodes.size)
    }

    // ---------- 数值形态 ----------

    @Test
    fun `Buckets_originally括注_小数actual行数`() {
        val s = ExplainTextPlanTree.parse(bucketsOriginallyPlan) as ExplainTextPlanTree.Result.Success
        assertEquals(5, s.planNodes.size)
        val hash = s.planNodes[3]
        assertEquals("Hash", hash.label)
        assertEquals(2048L, hash.hashBatches)
        assertEquals(8L, hash.originalHashBatches)
        assertEquals(1001000.0, s.planNodes[1].actualRows!!, 0.0001)
        assertEquals(1.00, s.planNodes[0].actualRows!!, 0.0001)
        // 根级 Planning 与 JIT 块合成节点，深层行挂块内
        assertEquals(5, s.roots.size)
        assertEquals("Planning", s.roots[1].label)
        assertEquals("Buffers: shared hit=83 read=9", s.roots[1].children[0].label)
        assertEquals("JIT", s.roots[3].label)
        assertEquals(3, s.roots[3].children.size)
        assertEquals("Execution Time: 3674.128 ms", s.roots[4].label)
    }

    @Test
    fun `HashAggregate独立Batches行_结构化提取`() {
        val s = ExplainTextPlanTree.parse(hashAggBatchesPlan) as ExplainTextPlanTree.Result.Success
        val agg = s.planNodes[0]
        assertEquals(5L, agg.hashaggBatches)
        assertEquals(200L, agg.hashaggDiskUsageKb)
        assertNull(agg.hashBatches)
        assertEquals("5  Memory Usage: 161kB  Disk Usage: 200kB", agg.attributes["Batches"])
    }

    @Test
    fun `actual无time形态_法语行数尾`() {
        val s = ExplainTextPlanTree.parse(actualNoTimePlan) as ExplainTextPlanTree.Result.Success
        assertEquals(1, s.planNodes.size)
        val n = s.planNodes[0]
        assertEquals(698.0, n.actualRows!!, 0.0001)
        assertEquals(1L, n.actualLoops)
        assertNull(n.actualStartupTime)
        assertNull(n.actualTotalTime)
        assertEquals(3, s.roots.size)
    }

    // ---------- Workers 分段 ----------

    @Test
    fun `Workers分段归worker叶_不混入节点属性`() {
        val s = ExplainTextPlanTree.parse(parallelWorkersPlan) as ExplainTextPlanTree.Result.Success
        assertEquals(3, s.roots.size)
        assertEquals(3, s.planNodes.size)
        val gather = s.planNodes[0]
        assertEquals("Gather Merge", gather.label)
        assertEquals("2", gather.attributes["Workers Planned"])
        assertEquals("2", gather.attributes["Workers Launched"])
        val sort = s.planNodes[1]
        assertEquals("Sort", sort.label)
        assertEquals(3L, sort.actualLoops)
        // Sort 渲染 children = Output/Sort Key/Sort Method 三叶 + 4 个 Worker 叶 + Parallel Seq Scan 子节点
        val sortRender = s.roots[0].children[3]
        assertEquals(8, sortRender.children.size)
        assertEquals(4, sortRender.children.filter { it.label.startsWith("Worker ") }.size)
        assertTrue("worker 行不得混入节点属性", sort.attributes.keys.none { it.startsWith("Worker") })
        val pss = s.planNodes[2]
        assertEquals("Parallel Seq Scan on public.t1", pss.label)
        assertEquals(3L, s.planNodes[2].actualLoops)
    }

    @Test
    fun `neverExecuted_标记与零值`() {
        val s = ExplainTextPlanTree.parse(neverExecutedPlan) as ExplainTextPlanTree.Result.Success
        assertEquals(4, s.planNodes.size)
        val root = s.planNodes[0]
        assertTrue(root.neverExecuted)
        assertEquals(0.0, root.actualRows!!, 0.0001)
        assertEquals(0L, root.actualLoops)
        assertEquals("Hash Left Join (rows=4879205) (never executed)", s.roots[0].label)
        assertTrue(s.isAnalyze)
    }

    @Test
    fun `worker级Batches不入节点结构化字段`() {
        val s = ExplainTextPlanTree.parse(workerBatchesPlan) as ExplainTextPlanTree.Result.Success
        assertEquals(3, s.planNodes.size)
        val agg = s.planNodes[1]
        assertEquals("Partial HashAggregate", agg.label)
        assertEquals("Partial", agg.partialMode)
        assertEquals(1L, agg.hashaggBatches)
        assertNull("worker 级 Batches: 5 不得记到节点头上", agg.hashBatches)
        assertEquals(2L, agg.actualLoops)
    }

    @Test
    fun `worker续行归worker叶_节点属性不受污染`() {
        val s = ExplainTextPlanTree.parse(workerJitPlan) as ExplainTextPlanTree.Result.Success
        assertEquals(2, s.planNodes.size)
        val hash = s.planNodes[0]
        assertEquals(1L, hash.hashBatches)
        assertEquals(1, hash.attributes.size)
        assertEquals("1024  Batches: 1  Memory Usage: 0kB", hash.attributes["Buckets"])
        val hashRender = s.roots[0]
        // children = Buckets 叶 + Worker 0 叶 + Worker 1 叶 + Hash Join 子节点
        assertEquals(4, hashRender.children.size)
        val w0 = hashRender.children[1]
        assertEquals("Worker 0:  actual time=1583.596..1583.600 rows=0 loops=1", w0.label)
        assertEquals(4, w0.children.size)
        assertEquals("Buffers: shared hit=9", w0.children[0].label)
        assertEquals("JIT:", w0.children[1].label)
        assertEquals("Functions: 49", w0.children[2].label)
        val w1 = hashRender.children[2]
        assertEquals(1, w1.children.size)
        assertEquals("Hash Join (rows=1)", hashRender.children[3].label)
        assertEquals("(a.id = b.id)", s.planNodes[1].attributes["Hash Cond"])
    }

    // ---------- SubPlan / CTE 分段 ----------

    @Test
    fun `SubPlan分段头_合成中间层`() {
        val s = ExplainTextPlanTree.parse(subPlanTree) as ExplainTextPlanTree.Result.Success
        assertEquals(2, s.planNodes.size)
        assertEquals("Seq Scan on pg_class c (rows=227)", s.roots[0].label)
        assertEquals(1, s.roots[0].children.size)
        val sub = s.roots[0].children[0]
        assertEquals("SubPlan", sub.label)
        assertEquals(1, sub.children.size)
        assertEquals("Index Scan using pg_class_relname_nsp_index on pg_class c2 (rows=1)", sub.children[0].label)
        assertEquals("Index Cond: (relname = ${'$'}0)", sub.children[0].children[0].label)
    }

    @Test
    fun `CTE分段头_合成中间层与兄弟复位`() {
        val s = ExplainTextPlanTree.parse(ctePlan) as ExplainTextPlanTree.Result.Success
        assertEquals(3, s.planNodes.size)
        val root = s.roots[0]
        assertEquals("Sort (rows=100)", root.label)
        assertEquals(2, root.children.size)
        assertEquals("CTE t", root.children[0].label)
        assertEquals("Seq Scan on t (rows=100)", root.children[0].children[0].label)
        assertEquals("CTE Scan on t (rows=100)", root.children[1].label)
    }

    // ---------- 诚实降级（失败 = 结构化原因 + 原文全量保留，不猜不炸） ----------

    @Test
    fun `JSON输入_结构化拒收_原文保留`() {
        val json = """[{"Plan": {"Node Type": "Seq Scan", "Relation Name": "t", "Plan Rows": 10}}]"""
        val f = ExplainTextPlanTree.parse(json) as ExplainTextPlanTree.Result.Failure
        assertEquals(ExplainTextPlanTree.Reason.JSON_SHAPED, f.reason)
        assertEquals(json, f.originalText)
    }

    @Test
    fun `非计划文本_NO_PLAN_NODES_原文保留`() {
        val garbage = "hello world, this is not a plan"
        val f = ExplainTextPlanTree.parse(garbage) as ExplainTextPlanTree.Result.Failure
        assertEquals(ExplainTextPlanTree.Reason.NO_PLAN_NODES, f.reason)
        assertEquals(garbage, f.originalText)
    }

    @Test
    fun `截断节点头_宁失败不猜_诚实降级`() {
        // 粘贴被截断在节点头中段（有 (cost= 节点头标记但尾巴不完整）——不猜格式，失败 + 原文；
        // 判 UNRECOGNIZED_NODE_LINE 而非 NO_PLAN_NODES（看得见节点头，只是形态读不完）
        val truncated = "Seq Scan on big_table  (cost=0.00..12.90 rows=100 wid"
        val f = ExplainTextPlanTree.parse(truncated) as ExplainTextPlanTree.Result.Failure
        assertEquals(ExplainTextPlanTree.Reason.UNRECOGNIZED_NODE_LINE, f.reason)
        assertEquals(truncated, f.originalText)
    }

    @Test
    fun `箭头行不识别_宁失败不猜`() {
        val broken = """
Seq Scan on t  (cost=0.00..1.00 rows=1 width=4)
  ->  Weird Node cost=broken rows=1 width=2
"""
        val f = ExplainTextPlanTree.parse(broken) as ExplainTextPlanTree.Result.Failure
        assertEquals(ExplainTextPlanTree.Reason.UNRECOGNIZED_NODE_LINE, f.reason)
        assertEquals(broken, f.originalText)
    }

    @Test
    fun `空输入_EMPTY_INPUT`() {
        assertEquals(
            ExplainTextPlanTree.Reason.EMPTY_INPUT,
            (ExplainTextPlanTree.parse("   ") as ExplainTextPlanTree.Result.Failure).reason,
        )
        assertEquals(
            ExplainTextPlanTree.Reason.EMPTY_INPUT,
            (ExplainTextPlanTree.parse("") as ExplainTextPlanTree.Result.Failure).reason,
        )
    }

    @Test
    fun `非PG族文本形态_诚实失败不硬猜`() {
        // DM8 #NSET2 文本树（无 cost 节点头）——本解析器不接，结构化失败
        val dm = """
1|#NSET2:SELECT STATEMENT
2|#PRJT2
3|#CSCN2:SYS.T1
"""
        assertEquals(
            ExplainTextPlanTree.Reason.NO_PLAN_NODES,
            (ExplainTextPlanTree.parse(dm) as ExplainTextPlanTree.Result.Failure).reason,
        )
    }
}
