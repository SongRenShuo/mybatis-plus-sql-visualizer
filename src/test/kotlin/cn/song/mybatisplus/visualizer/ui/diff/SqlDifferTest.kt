package cn.song.mybatisplus.visualizer.ui.diff

import cn.song.mybatisplus.visualizer.ui.diff.SqlDiffer.DiffType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-4：SQL 行级 diff 算法测试（SqlDiffer.computeDiff）。
 *
 * 验证：完全相同、完全不同、部分变更、空 SQL、单行插入/删除。
 */
class SqlDifferTest {

    @Test
    fun `computeDiff_完全相同_无差异`() {
        val sql = "SELECT id\nFROM tbl\nWHERE id = 1"
        val diff = SqlDiffer.computeDiff(sql, sql)
        assertTrue("相同 SQL 应无差异", diff.isEmpty())
    }

    @Test
    fun `computeDiff_完全不同_全部变更`() {
        val old = "SELECT id\nFROM tbl"
        val new = "SELECT name\nFROM users"
        val diff = SqlDiffer.computeDiff(old, new)
        // 2 行删除 + 2 行新增 = 4 个差异条目
        assertEquals("完全不同应产生 4 个差异条目", 4, diff.size)
        val changed = diff.filter { it.type != DiffType.EQUAL }
        assertEquals(4, changed.size)
    }

    @Test
    fun `computeDiff_部分变更_中间行不同`() {
        val old = "SELECT id\nFROM tbl\nWHERE id = 1"
        val new = "SELECT id\nFROM users\nWHERE id = 1"
        val diff = SqlDiffer.computeDiff(old, new)
        // 中间行从 "FROM tbl" 改写为 "FROM users"：1 行删除 + 1 行新增（无 CHANGE 类型，见下方决策用例）
        val changed = diff.filter { it.type != DiffType.EQUAL }
        assertEquals("中间行改写应产生 2 个差异条目", 2, changed.size)
        val deleted = changed.filter { it.type == DiffType.DELETE }
        val inserted = changed.filter { it.type == DiffType.INSERT }
        assertEquals(1, deleted.size)
        assertEquals(1, inserted.size)
        assertEquals("FROM tbl", deleted.first().oldLine)
        assertEquals("FROM users", inserted.first().newLine)
    }

    @Test
    fun `computeDiff_新增行`() {
        val old = "SELECT id\nFROM tbl"
        val new = "SELECT id\nFROM tbl\nWHERE id = 1"
        val diff = SqlDiffer.computeDiff(old, new)
        val added = diff.filter { it.type == DiffType.INSERT }
        assertEquals("应检测到 1 行新增", 1, added.size)
        assertEquals("WHERE id = 1", added.first().newLine)
    }

    @Test
    fun `computeDiff_删除行`() {
        val old = "SELECT id\nFROM tbl\nWHERE id = 1"
        val new = "SELECT id\nFROM tbl"
        val diff = SqlDiffer.computeDiff(old, new)
        val deleted = diff.filter { it.type == DiffType.DELETE }
        assertEquals("应检测到 1 行删除", 1, deleted.size)
        assertEquals("WHERE id = 1", deleted.first().oldLine)
    }

    @Test
    fun `computeDiff_空旧SQL_全部新增`() {
        val old = ""
        val new = "SELECT id\nFROM tbl"
        val diff = SqlDiffer.computeDiff(old, new)
        val added = diff.filter { it.type == DiffType.INSERT }
        assertEquals("空旧 SQL 应全部新增", 2, added.size)
    }

    @Test
    fun `computeDiff_空新SQL_全部删除`() {
        val old = "SELECT id\nFROM tbl"
        val new = ""
        val diff = SqlDiffer.computeDiff(old, new)
        val deleted = diff.filter { it.type == DiffType.DELETE }
        assertEquals("空新 SQL 应全部删除", 2, deleted.size)
    }

    @Test
    fun `computeDiff_两者皆空_无差异`() {
        val diff = SqlDiffer.computeDiff("", "")
        assertTrue("两者皆空应无差异", diff.isEmpty())
    }

    @Test
    fun `computeDiff_行号正确`() {
        val old = "SELECT id\nFROM tbl\nWHERE id = 1"
        val new = "SELECT id\nFROM users\nWHERE id = 1"
        val diff = SqlDiffer.computeDiff(old, new)
        // 删除行的旧行号应为 2（FROM tbl 在旧 SQL 第 2 行）
        val deleted = diff.first { it.type == DiffType.DELETE }
        assertEquals(2, deleted.oldLineNumber)
        // 新增行的新行号应为 2（FROM users 在新 SQL 第 2 行）
        val inserted = diff.first { it.type == DiffType.INSERT }
        assertEquals(2, inserted.newLineNumber)
    }

    @Test
    fun `computeDiff_性能验证_1000行100ms内`() {
        val oldLines = (1..1000).map { "line_$it" }
        val newLines = (1..1000).map { if (it == 500) "line_changed" else "line_$it" }
        val old = oldLines.joinToString("\n")
        val new = newLines.joinToString("\n")
        val start = System.currentTimeMillis()
        val diff = SqlDiffer.computeDiff(old, new)
        val elapsed = System.currentTimeMillis() - start
        // T-CiRelease（20261024）：预算从 100ms 放宽到 2000ms——本机 warm 实测个位数~几十 ms，守卫
        // 意图是抓复杂度量级回归（O(n³)/病态实现会到秒级以上）而非跑分；GitHub runner 上 5 个分片
        // JVM 抢 4 核 + 冷 JIT，100ms 预算必炸（CI 实测假失败）。20 倍余量仍在回归形态的判别域内。
        assertTrue("1000 行 diff 应在 2000ms 内完成（实际 ${elapsed}ms）", elapsed < 2000)
        val changed = diff.filter { it.type != DiffType.EQUAL }
        // 1 行改写 = 1 删除 + 1 新增 = 2 个差异条目
        assertEquals("仅 1 行改写（2 个差异条目）", 2, changed.size)
    }

    @Test
    fun `computeDiff_行改写_表达为删除加新增对而非CHANGE`() {
        // 决策留痕：DiffType 不应再声明 CHANGE——差异类型的唯一消费形态是按类型分派的渲染，
        // 而该渲染器（O-4 DiffHighlighter）已整体移除、对比入口改走 IDEA 原生 Diff，
        // 产出 CHANGE 只会得到无人读取的不可达常量。故「修改一行」固定为 DELETE + INSERT 成对表达。
        val diff = SqlDiffer.computeDiff("SELECT id\nFROM tbl", "SELECT id\nFROM users")
        assertEquals(listOf(DiffType.DELETE, DiffType.INSERT), diff.map { it.type })
        assertEquals(
            "DiffType 不应再声明无产出点的 CHANGE",
            setOf("INSERT", "DELETE", "EQUAL"),
            DiffType.values().map { it.name }.toSet(),
        )
    }

    // ---------- F61（T-PerfSweep18）：LCS 行数上限护栏——超限降级逐行并排 ----------

    /**
     * 阈值边界双面：同形态输入（前置插入 1 行）在阈值内走 LCS 全局对齐（2 条目）、
     * 超限走逐行并排（整体移位致每行错位都出对）。
     * 反向验证锚：摘掉 computeDiff 的降级分支后，「超限降级」用例会以 LCS 对齐产出
     * 2 条目（而非 2*行数）→ 断言 2002 红。
     */
    @Test
    fun `F61_阈值边界_恰1000行走LCS全局对齐`() {
        val old = (1..1000).joinToString("\n") { "line_$it" }
        val new = ("line_prepended\n$old").split("\n").take(1000).joinToString("\n")
        assertEquals(1000, new.split("\n").size)
        val diff = SqlDiffer.computeDiff(old, new)
        // LCS：前置 1 行插入 + 尾行删除 = 2 条目
        assertEquals("阈值内须全局对齐（2 条目）", 2, diff.size)
        assertEquals(DiffType.INSERT, diff.first().type)
        assertEquals("line_prepended", diff.first().newLine)
        assertEquals(DiffType.DELETE, diff.last().type)
        assertEquals("line_1000", diff.last().oldLine)
    }

    @Test
    fun `F61_超限降级_逐行并排不再付平方DP`() {
        val old = (1..1001).joinToString("\n") { "line_$it" }
        val new = "line_prepended\n" + (1..1000).joinToString("\n") { "line_$it" }
        assertEquals(1001, new.split("\n").size)
        val start = System.currentTimeMillis()
        val diff = SqlDiffer.computeDiff(old, new)
        val elapsed = System.currentTimeMillis() - start
        // 逐行并排：前插致整体移位、1001 行全错位 → 1001 DELETE + 1001 INSERT = 2002 条目
        assertEquals("超限须逐行并排（2002 条目）", 2002, diff.size)
        assertEquals(1001, diff.count { it.type == DiffType.DELETE })
        assertEquals(1001, diff.count { it.type == DiffType.INSERT })
        assertTrue("降级路径应远快于 1001² DP（实际 ${elapsed}ms）", elapsed < 100)
        // 行号形态仍符合契约：首对 DELETE 旧行号 1、INSERT 新行号 1
        assertEquals(1, diff.first { it.type == DiffType.DELETE }.oldLineNumber)
        assertEquals(1, diff.first { it.type == DiffType.INSERT }.newLineNumber)
    }

    /** 降级路径同位相等行不产出条目（与主路径「仅返回非 EQUAL」口径一致）。 */
    @Test
    fun `F61_降级路径_同位相等行跳过_条目形态契约不变`() {
        val old = (1..1001).joinToString("\n") { "line_$it" }
        val new = (1..1000).joinToString("\n") { "line_$it" } + "\nline_tail_new"
        val diff = SqlDiffer.computeDiff(old, new)
        // 同位 line_1..line_1000 全等不产出；仅尾行改写 → 1 DELETE + 1 INSERT
        assertEquals(2, diff.size)
        assertEquals("line_1001", diff.first { it.type == DiffType.DELETE }.oldLine)
        assertEquals("line_tail_new", diff.first { it.type == DiffType.INSERT }.newLine)
        assertEquals(1001, diff.first { it.type == DiffType.DELETE }.oldLineNumber)
        assertEquals(1001, diff.first { it.type == DiffType.INSERT }.newLineNumber)
    }
}
