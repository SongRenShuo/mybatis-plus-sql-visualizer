package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.DATABASE_UNKNOWN_SORT_KEY
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.FILTERABLE_TYPES
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.filterByFocus
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.filterBySearch
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.filterBySlowOnly
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.filterBySuppress
import cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.filterByType
import cn.song.mybatisplus.visualizer.log.LogEntryRailKt.maxDurationOf

/**
 * SQL 列表**刷新管线**压测（搜索 / 筛选 / 排序）。
 *
 * 与 [LogEntryRailPerfTest] 的分工：那边压「渲染器热路径」（每格成本，与总条数无关）；
 * 这边压「管线」（每次刷新对全部条目跑一遍，O(N) 或 O(N log N)）——**几万条时真正的瓶颈在这里**。
 *
 * 为什么必须单独压：用户会把 `logMaxCount` 调到几千乃至几万。渲染成本不随总数增长，但管线是
 * 全量的；流式灌入下每秒可能触发多次刷新（每条新条目一次 `refreshList`），若单次管线是
 * 几十毫秒，就会表现为「面板一直卡、输入搜索词延迟数秒」。
 *
 * 判据同样分两档：
 *  - 绝对预算：只拦数量级退化（阈值取实测数倍余量）；
 *  - 相对判据：同一管线在 2 万条 vs 2 千条的成本比必须接近 10 倍（线性），
 *    若退化成平方级会立刻暴露。
 *
 * k2j 适配：条目构造经 20 参位置构造器（Java record 无具名默认参），工厂封装同
 * FocusTabFilterTest 先例；filter 族为 SqlLogPanelKt/LogEntryRailKt 的 Java 静态门面，
 * 显式 import 后裸调；耗时组件访问器走 record 无前缀形态（executionTimeMs() 等）。
 */
class LogPipelinePerfTest : BasePlatformTestCase() {

    companion object {
        /** 大数据集规模（用户可能把缓冲调到这个量级）。 */
        private const val LARGE = 20_000

        /** 小数据集规模（用于线性度对比）。 */
        private const val SMALL = 2_000
    }

    /** 构造 n 条多样化的条目：不同表名/库名/类型/耗时，保证搜索与排序都真的做工作。 */
    private fun entries(n: Int): List<LogSqlEntry> = (0 until n).map { i ->
        val type = when (i % 4) {
            0 -> "SELECT"
            1 -> "UPDATE"
            2 -> "INSERT"
            else -> "DELETE"
        }
        val table = "tbl_${i % 97}"
        LogSqlEntry(
            "$type * FROM $table WHERE id = $i",
            "$type * FROM $table WHERE id = $i AND name = 'user_${i % 500}'",
            emptyList(),
            1_700_000_000_000L + i * 37L,
            1_700_000_000_000L + i * 37L,
            null,
            SqlSource.UNKNOWN,
            (i % 900).toLong(),
            "src/main/java/svc/Service${i % 40}.java",
            i,
            emptyList(),
            emptyList(),
            null,
            i.toLong() + 1,
            null,
            null,
            null,
            null,
            "MySQL · db${i % 5}@localhost:3306",
        )
    }

    /** 计时助手：预热若干轮后测 [rounds] 轮，返回单轮毫秒。 */
    private inline fun measure(rounds: Int, warmup: Int = 5, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val t0 = System.nanoTime()
        repeat(rounds) { block() }
        return (System.nanoTime() - t0) / 1_000_000.0 / rounds
    }

    // ---------- 搜索 ----------

    /**
     * 子串搜索（最常用路径）。
     *
     * 每条要做 `restoredSql.contains` + `summary.contains` + 时间片段拼接后 contains。
     * 两万条下若超过几十毫秒，用户输入搜索词就会感到卡顿（且每个字符触发一次重过滤）。
     */
    fun test搜索_子串_两万条在预算内() {
        val data = entries(LARGE)
        val ms = measure(10) { filterBySearch(data, "tbl_42") }
        // 预算 80ms：本机实测 20~45ms（随机器负载波动明显），设 80ms 留一倍余量。
        // 判据仍是「不得退化为平方级」——真退化会在 500ms 以上。
        // 另注：搜索输入有 200ms 防抖，单次 40ms 不会造成逐键卡顿。
        assertTrue("两万条子串搜索 ${"%.2f".format(ms)}ms 超预算 80ms（输入搜索词会明显卡顿）", ms < 80.0)
    }

    /** 无命中查询（最坏情况：每条都要比完全部维度才判定不命中）。 */
    fun test搜索_无命中_两万条在预算内() {
        val data = entries(LARGE)
        val ms = measure(10) { filterBySearch(data, "zzz_no_such_token_zzz") }
        assertTrue("两万条无命中搜索 ${"%.2f".format(ms)}ms 超预算 80ms", ms < 80.0)
    }

    /** 正则搜索（每轮可能重编译，靠 memo 复用）。 */
    fun test搜索_正则_两万条在预算内() {
        val data = entries(LARGE)
        val memo = HistoryRegexMemo()
        val ms = measure(10) { filterBySearch(data, "id = \\d{4}$", false, true, memo) }
        assertTrue("两万条正则搜索 ${"%.2f".format(ms)}ms 超预算 120ms（正则本身比子串慢，预算放宽）", ms < 120.0)
    }

    /** 空查询必须零成本透传（常态路径：不搜索时不该有任何开销）。 */
    fun test搜索_空查询零成本() {
        val data = entries(LARGE)
        val ms = measure(50) { filterBySearch(data, "   ") }
        assertTrue("空查询应直接透传（实测 ${"%.4f".format(ms)}ms）", ms < 0.05)
    }

    // ---------- 筛选 ----------

    /** 类型筛选：只勾一类时对每条做 statementType 判断（条目级缓存属性，不重跑分类）。 */
    fun test筛选_类型_两万条在预算内() {
        val data = entries(LARGE)
        val ms = measure(10) { filterByType(data, setOf(StatementType.SELECT)) }
        assertTrue("两万条类型筛选 ${"%.2f".format(ms)}ms 超预算 20ms", ms < 20.0)
    }

    /** 类型全选必须零成本透传（默认态：什么都没勾掉时不该有开销）。 */
    fun test筛选_类型全选零成本() {
        val data = entries(LARGE)
        val ms = measure(50) { filterByType(data, FILTERABLE_TYPES.toSet()) }
        assertTrue("类型全选应直接透传（实测 ${"%.4f".format(ms)}ms）", ms < 0.05)
    }

    /** 「只看慢查询」筛选：对每条解析耗时（可能回退日志文本解析）。 */
    fun test筛选_只看慢查询_两万条在预算内() {
        val data = entries(LARGE)
        val ms = measure(10) {
            filterBySlowOnly(data, true, 500L) { it.executionTimeMs() }
        }
        assertTrue("两万条慢查询筛选 ${"%.2f".format(ms)}ms 超预算 20ms", ms < 20.0)
    }

    /** 慢查询筛选关闭时零成本。 */
    fun test筛选_慢查询关闭零成本() {
        val data = entries(LARGE)
        val ms = measure(50) {
            filterBySlowOnly(data, false, 500L) { it.executionTimeMs() }
        }
        assertTrue("慢查询筛选关闭应直接透传（实测 ${"%.4f".format(ms)}ms）", ms < 0.05)
    }

    /** 屏蔽规则筛选：无规则时零成本（常态），有规则时每条查两个集合。 */
    fun test筛选_屏蔽规则_两万条在预算内() {
        val data = entries(LARGE)
        val noRules = measure(50) { filterBySuppress(data, emptySet()) }
        assertTrue("无屏蔽规则应直接透传（实测 ${"%.4f".format(noRules)}ms）", noRules < 0.05)
        val keys = data.take(500).map { it.suppressKey }.toSet()
        val withRules = measure(10) { filterBySuppress(data, keys) }
        assertTrue("两万条屏蔽筛选 ${"%.2f".format(withRules)}ms 超预算 30ms", withRules < 30.0)
    }

    // ---------- 排序 ----------

    /**
     * 七种排序模式逐一压测。
     *
     * 排序是 O(N log N) 且比较器里可能做字段读取/字符串比较；`STRUCTURE_CLUSTER` 与
     * `DATABASE_GROUP` 的比较器还涉及 lazy 指纹与字符串比较，是七种里最重的两种。
     */
    fun test排序_七种模式_两万条均在预算内() {
        val data = entries(LARGE)
        // 先让 structureKey 惰性指纹各算一次（模拟真实场景：首次聚类后已缓存）
        data.forEach { it.structureKey }
        val budgets = mapOf(
            "TIME_ASC" to 60.0,
            "TIME_DESC" to 60.0,
            "DURATION_ASC" to 60.0,
            "DURATION_DESC" to 60.0,
            "SEQUENCE" to 60.0,
            "STRUCTURE_CLUSTER" to 120.0,
            "DATABASE_GROUP" to 120.0,
        )
        val displayTime: (LogSqlEntry) -> Long = { it.logTimestamp() ?: it.timestamp() }
        val results = mutableListOf<String>()
        for ((name, budget) in budgets) {
            val ms = measure(5) {
                when (name) {
                    "TIME_ASC" -> data.sortedBy(displayTime)
                    "TIME_DESC" -> data.sortedByDescending(displayTime)
                    "DURATION_ASC" -> data.sortedBy { it.executionTimeMs() ?: Long.MAX_VALUE }
                    "DURATION_DESC" -> data.sortedByDescending { it.executionTimeMs() ?: Long.MIN_VALUE }
                    "SEQUENCE" -> data.sortedBy { it.sequenceNumber() }
                    "STRUCTURE_CLUSTER" -> data.sortedWith(compareBy({ it.structureKey }, displayTime))
                    "DATABASE_GROUP" -> data.sortedWith(
                        compareBy({ it.databaseName() ?: DATABASE_UNKNOWN_SORT_KEY }, displayTime),
                    )
                    else -> error("unreachable")
                }
            }
            results += "$name=${"%.1f".format(ms)}ms"
            assertTrue("[$name] 两万条排序 ${"%.2f".format(ms)}ms 超预算 ${budget}ms", ms < budget)
        }
        println("PIPELINE 排序读数：${results.joinToString("  ")}")
    }

    // ---------- 线性度（防退化成平方级） ----------

    /**
     * 搜索的线性度：两万条 vs 两千条的成本比应接近 10。
     *
     * 判据放宽到 25 倍：容许常数开销与缓存效应；但若谁引入了嵌套遍历（每条再扫全表），
     * 比值会跳到 100 倍量级，立刻暴露。
     */
    fun test搜索_成本随条数线性增长() {
        val small = entries(SMALL)
        val large = entries(LARGE)
        val smallMs = measure(20) { filterBySearch(small, "tbl_42") }
        val largeMs = measure(10) { filterBySearch(large, "tbl_42") }
        val ratio = largeMs / smallMs.coerceAtLeast(0.0001)
        assertTrue(
            "搜索成本比 ${"%.1f".format(ratio)}（2 万条 ${"%.2f".format(largeMs)}ms / 2 千条 ${"%.2f".format(smallMs)}ms）" +
                "明显超出线性——疑似退化为平方级",
            ratio < 25.0,
        )
    }

    /**
     * 排序的线性度：两万条 vs 两千条的成本比。
     *
     * 判据取「10 倍规模 → 成本比 < 40」而非严格 10：n log n 本身带来 log 因子，
     * 且小数据集单轮仅 0.1ms、受计时精度与 JIT 影响大。真退化（嵌套遍历）会让比值跳到
     * 100 倍以上，仍能被这条拦住。绝对预算另由 [test排序_七种模式_两万条均在预算内] 兜底。
     */
    fun test排序_成本随条数近似线性() {
        val small = entries(SMALL)
        val large = entries(LARGE)
        small.forEach { it.structureKey }
        large.forEach { it.structureKey }
        val displayTime: (LogSqlEntry) -> Long = { it.logTimestamp() ?: it.timestamp() }
        // 小数据集多跑几轮累计，降低单轮计时噪声（单轮 0.1ms 在 nanoTime 分辨率附近）
        val smallMs = measure(200) { small.sortedBy(displayTime) }
        val largeMs = measure(20) { large.sortedBy(displayTime) }
        val ratio = largeMs / smallMs.coerceAtLeast(0.0001)
        assertTrue(
            "排序成本比 ${"%.1f".format(ratio)}（2 万条 ${"%.3f".format(largeMs)}ms / 2 千条 ${"%.3f".format(smallMs)}ms）" +
                "明显超出 n log n 预期——疑似退化为平方级",
            ratio < 40.0,
        )
    }

    // ---------- 完整管线（模拟一次真实刷新） ----------

    /**
     * 一次完整刷新管线的端到端成本：屏蔽 → 类型 → 慢查询 → 聚焦 → 搜索 → 排序 → 最大耗时扫描。
     *
     * 这是用户「打一条日志 → 面板刷新一次」的实际代价。两万条下若超过 200ms，
     * 流式灌入（每秒数条）就会持续抢占 EDT。
     */
    fun test完整管线_两万条在预算内() {
        val data = entries(LARGE)
        data.forEach { it.structureKey }
        val displayTime: (LogSqlEntry) -> Long = { it.logTimestamp() ?: it.timestamp() }
        val ms = measure(5) {
            val a = filterBySuppress(data, emptySet())
            val b = filterByType(a, setOf(StatementType.SELECT, StatementType.UPDATE))
            val c = filterBySlowOnly(b, true, 300L) { it.executionTimeMs() }
            val d = filterByFocus(c, null)
            val e = filterBySearch(d, "tbl_4")
            val f = e.sortedBy(displayTime)
            maxDurationOf(f)
        }
        assertTrue(
            "两万条完整刷新管线 ${"%.1f".format(ms)}ms 超预算 200ms——流式灌入下会持续抢占 EDT",
            ms < 200.0,
        )
        println("PIPELINE 完整管线（两万条）= ${"%.1f".format(ms)}ms")
    }

    /** 空态管线（无搜索无筛选，只排序 + 扫描）——流式灌入的常态路径。 */
    fun test完整管线_空态两万条在预算内() {
        val data = entries(LARGE)
        val displayTime: (LogSqlEntry) -> Long = { it.logTimestamp() ?: it.timestamp() }
        val ms = measure(10) {
            val a = filterBySuppress(data, emptySet())
            val b = filterByType(a, FILTERABLE_TYPES.toSet())
            val c = filterBySlowOnly(b, false, 1000L) { it.executionTimeMs() }
            val d = filterByFocus(c, null)
            val e = filterBySearch(d, "")
            val f = e.sortedBy(displayTime)
            maxDurationOf(f)
        }
        assertTrue("两万条空态管线 ${"%.1f".format(ms)}ms 超预算 120ms", ms < 120.0)
    }
}
