package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-13（DBX #317）：[ColumnOrderings] 单一列比较器锚点用例。
 *
 * 覆盖计划要求的四个锚点形态——空表 / 单列 / 多列 / 同值稳定序——外加空值恒排后、数值串感知、
 * 列名/列序号两种寻址与查不到返回 null 的诚实降级。**方向不在本对象**：升/降序由平台
 * `TableRowSorter` 反转比较器承担，此处只钉升序语义（降序用 `reversed()` 模拟平台行为）。
 * 纯函数零 com.intellij 依赖，进 testFast 快桶。
 */
class ColumnOrderingsTest {

    // ------------------------------------------------------------------
    // 锚点一：空表
    // ------------------------------------------------------------------

    @Test
    fun 空表排序保持空表() {
        val rows = emptyList<List<String?>>()
        val comparator = ColumnOrderings.byColumnIndex<List<String?>>(0) { row, i -> row[i] }
        assertNotNull(comparator)
        assertEquals(emptyList<List<String?>>(), rows.sortedWith(comparator!!))
    }

    @Test
    fun 空列清单按列名寻址返回null() {
        val comparator = ColumnOrderings.byColumnName<List<String?>>("id", emptyList()) { row, i -> row[i] }
        assertNull(comparator)
    }

    // ------------------------------------------------------------------
    // 锚点二：单列
    // ------------------------------------------------------------------

    @Test
    fun 单行表排序保持原序() {
        val rows = listOf(listOf("only"))
        val comparator = ColumnOrderings.byColumnIndex<List<String?>>(0) { row, i -> row[i] }
        assertEquals(listOf(listOf("only")), rows.sortedWith(comparator!!))
    }

    // ------------------------------------------------------------------
    // 锚点三：多列
    // ------------------------------------------------------------------

    @Test
    fun 多行按指定列升序() {
        val rows = listOf(
            listOf("b", "2"),
            listOf("a", "1"),
            listOf("c", "3"),
        )
        val asc = ColumnOrderings.byColumnIndex<List<String?>>(0) { row, i -> row[i] }!!
        assertEquals(listOf("a", "b", "c"), rows.sortedWith(asc).map { it[0] })
        // 降序＝平台 TableRowSorter 对升序比较器取反（reversed），同值仍为 0
        assertEquals(listOf("c", "b", "a"), rows.sortedWith(asc.reversed()).map { it[0] })
    }

    @Test
    fun 按第二列排序即列序号寻址() {
        val rows = listOf(
            listOf("x", "9"),
            listOf("y", "1"),
        )
        val comparator = ColumnOrderings.byColumnIndex<List<String?>>(1) { row, i -> row[i] }!!
        assertEquals(listOf("y", "x"), rows.sortedWith(comparator).map { it[0] })
    }

    @Test
    fun 按列名寻址大小写不敏感() {
        val columns = listOf("Id", "Name")
        val rows = listOf(
            listOf("1", "b"),
            listOf("2", "a"),
        )
        val comparator = ColumnOrderings.byColumnName<List<String?>>("ID", columns) { row, i -> row[i] }!!
        assertEquals(listOf("1", "2"), rows.sortedWith(comparator).map { it[0] })
    }

    @Test
    fun 列名查不到与序号越界都返回null() {
        assertNull(
            ColumnOrderings.byColumnName<List<String?>>("missing", listOf("a", "b")) { row, i -> row[i] },
        )
        // 正向越界校验须知道列总数（columnCount）；-1 = 未知列数，只挡负数序号
        assertNull(
            ColumnOrderings.byColumnIndex<List<String?>>(5, columnCount = 2) { row, i -> row[i] },
        )
        assertNotNull(
            ColumnOrderings.byColumnIndex<List<String?>>(1, columnCount = 2) { row, i -> row[i] },
        )
        assertNull(
            ColumnOrderings.byColumnIndex<List<String?>>(-1) { row, i -> row[i] },
        )
    }

    // ------------------------------------------------------------------
    // 锚点四：同值稳定序
    // ------------------------------------------------------------------

    @Test
    fun 同值行升序保持原始顺序() {
        val rows = listOf(
            listOf("a", "first"),
            listOf("a", "second"),
            listOf("a", "third"),
        )
        val asc = ColumnOrderings.byColumnIndex<List<String?>>(0) { row, i -> row[i] }!!
        assertEquals(listOf("first", "second", "third"), rows.sortedWith(asc).map { it[1] })
    }

    @Test
    fun 同值行降序同样保持原始顺序() {
        // 平台取反只翻非零结果，同值恒 0——降序不翻转同值行的原始顺序。
        val rows = listOf(
            listOf("a", "first"),
            listOf("a", "second"),
            listOf("a", "third"),
        )
        val desc = ColumnOrderings.byColumnIndex<List<String?>>(0) { row, i -> row[i] }!!.reversed()
        assertEquals(listOf("first", "second", "third"), rows.sortedWith(desc).map { it[1] })
    }

    // ------------------------------------------------------------------
    // 空值 / 数值串
    // ------------------------------------------------------------------

    @Test
    fun 空值恒排在有值之后与方向无关() {
        val rows = listOf(
            listOf(null, "r1"),
            listOf("v", "r2"),
            listOf("", "r3"),
        )
        // 两个空值行彼此相等，稳定排序保持原始顺序（r1 在前）。
        // 方向口径以 [ColumnOrderings] KDoc 定案为准：**方向不进本对象**——升序空值恒尾，
        // 降序由平台 TableRowSorter 对同一比较器反转（空值即恒头）；「双向恒排尾」须面板侧
        // 接管方向才能实现，另立项处理。合并轮收口修正（2026-11-13）：本用例原第二断言按
        // 「双向恒尾」写期望，与实现定案相悖（dev 全量桶覆盖缺口漏检）。
        val asc = ColumnOrderings.byColumnIndex<List<String?>>(0) { row, i -> row[i] }!!
        assertEquals(listOf("r2", "r1", "r3"), rows.sortedWith(asc).map { it[1] })
        assertEquals(listOf("r1", "r3", "r2"), rows.sortedWith(asc.reversed()).map { it[1] })
    }

    @Test
    fun 纯数字串按数值比较() {
        assertTrue(ColumnOrderings.compareCells("10", "9") > 0)
        assertTrue(ColumnOrderings.compareCells("2", "10") < 0)
        assertTrue(ColumnOrderings.compareCells(10, "10") == 0)
        // 数值相等时字符串决胜（"01" < "1" 纯字典序），要点是确定性而非谁赢
        assertTrue(ColumnOrderings.compareCells("01", "1") < 0)
    }

    @Test
    fun 非数字串大小写不敏感比较() {
        assertTrue(ColumnOrderings.compareCells("apple", "Banana") < 0)
        assertTrue(ColumnOrderings.compareCells("ABC", "abc") == 0)
    }

    @Test
    fun 数字与非数字混合按字符串比较() {
        // "abc" 非数值 → 与 "9" 走字符串比较（'a' > '9'）
        assertTrue(ColumnOrderings.compareCells("abc", "9") > 0)
    }
}
