package cn.song.mybatisplus.visualizer.util

import java.util.Comparator

/**
 * 结果表格列排序的单一比较器（F-13，DBX #317）。
 *
 * 仓内三处表结构预览表格——预览面板「列信息」标签页、EXPLAIN TABLE 结果网格、回滚快照表格——
 * 共用本对象这一套比较逻辑，禁止各面板再手写 Comparator：排序语义漂移会让同一份数据在
 * 三个面板里排出三种顺序。面板侧经 TableRowSorter 逐列安装 [compareCells]（列头点击排序），
 * 默认无 sortKeys 时视图序 = 模型序，零行为变更。
 *
 * 锚点用例见 ColumnOrderingsTest（空表 / 单列 / 多列 / 同值稳定序 / 方向 / 空值 / 数值串）。
 */
object ColumnOrderings {

    /** 排序方向。降序由升序结果取反实现（同值恒为 0），稳定排序下同值行不因方向翻转。 */
    enum class Direction { ASC, DESC }

    /** 空值/空白统一判定：null 与纯空白串都按「无值」处理，恒排在有值之后（与方向无关）。 */
    private fun isAbsent(v: Any?): Boolean = v == null || (v is String && v.isBlank())

    /**
     * 单元格比较（升序语义，三面板逐列安装的就是它）。
     *
     * 规则（依次判定）：
     *  1. 空值（null/空白串）恒排最后——两个都空视为相等；
     *  2. 双数值（Number 或可解析为数值的字符串）按数值比，数值相等再按字符串决胜（"01" 与 "1"
     *     数值同序时退回字典序，保证确定性总序）；
     *  3. 其余按大小写不敏感字符串比——折叠相等（如 "ABC" 与 "abc"）即返回 0，
     *     稳定排序下保持原始顺序。
     */
    @JvmStatic
    fun compareCells(a: Any?, b: Any?): Int {
        val aAbsent = isAbsent(a)
        val bAbsent = isAbsent(b)
        if (aAbsent && bAbsent) return 0
        if (aAbsent) return 1
        if (bAbsent) return -1
        numericPair(a, b)?.let { (na, nb) ->
            val byValue = java.lang.Double.compare(na, nb)
            if (byValue != 0) return byValue
        }
        return a.toString().compareTo(b.toString(), ignoreCase = true)
    }

    /** 双方可解析为数值时给出数值对（Number 直取；字符串非空白且能 parse 才算），否则 null。 */
    private fun numericPair(a: Any?, b: Any?): Pair<Double, Double>? {
        val na = asNumber(a) ?: return null
        val nb = asNumber(b) ?: return null
        return na to nb
    }

    private fun asNumber(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().takeIf { it.isNotEmpty() }?.let { s ->
            s.toDoubleOrNull() ?: hexOrBinaryLiteral(s)
        }
        else -> null
    }

    /**
     * 数据库结果里常见的 0x / 0b 前缀串 Double.parse 不认，这里单独认领（允许负号）；
     * 其余形态（含 "1,234"、"12%"）一律按字符串处理，不做猜格式。
     */
    private fun hexOrBinaryLiteral(raw: String): Double? {
        val s = raw.trim()
        val negative = s.startsWith("-")
        val body = (if (negative) s.substring(1) else s).lowercase()
        val radix = when {
            body.startsWith("0x") -> 16
            body.startsWith("0b") -> 2
            else -> return null
        }
        val digits = body.substring(2)
        if (digits.isEmpty() || !digits.all { it in "0123456789abcdef" }) return null
        val value = runCatching { digits.toLong(radix).toDouble() }.getOrNull() ?: return null
        return if (negative) -value else value
    }

    /**
     * 行比较器：按**列序号**定位关键列，行按该列值升降排。
     *
     * @param cell        行 → 单元格值的取值函数（入参 = 行对象与列序号）
     * @param columnCount 列总数（用于序号越界校验）；-1 = 列数未知（只挡负数序号）
     * @return 比较器；序号越界返回 null——调用方保持原序（不猜、不静默取首列）
     */
    @JvmStatic
    fun <T> byColumnIndex(
        columnIndex: Int,
        direction: Direction,
        columnCount: Int = -1,
        cell: (T, Int) -> Any?,
    ): Comparator<T>? {
        if (columnIndex < 0) return null
        if (columnCount >= 0 && columnIndex >= columnCount) return null
        return object : Comparator<T> {
            override fun compare(x: T, y: T): Int =
                directedCompare(cell(x, columnIndex), cell(y, columnIndex), direction)
        }
    }

    /**
     * 行比较器：按**列名**定位关键列（大小写不敏感），行按该列值升降排。
     *
     * @param columns 列名清单（下标即列序号）
     * @return 比较器；列名查不到返回 null——调用方保持原序（同上，不猜）
     */
    @JvmStatic
    fun <T> byColumnName(
        name: String,
        direction: Direction,
        columns: List<String>,
        cell: (T, Int) -> Any?,
    ): Comparator<T>? {
        val index = columns.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (index < 0) return null
        return byColumnIndex(index, direction, columns.size, cell)
    }

    /**
     * 带方向的单对比较：空值恒排最后（方向无关），其余按方向取反。
     * 取反只作用于非零结果——同值恒 0，稳定排序下两种方向的同值行都保持原始顺序。
     */
    private fun directedCompare(a: Any?, b: Any?, direction: Direction): Int {
        val aAbsent = isAbsent(a)
        val bAbsent = isAbsent(b)
        if (aAbsent && bAbsent) return 0
        if (aAbsent) return 1
        if (bAbsent) return -1
        val base = compareCells(a, b)
        return if (direction == Direction.ASC) base else -base
    }
}
