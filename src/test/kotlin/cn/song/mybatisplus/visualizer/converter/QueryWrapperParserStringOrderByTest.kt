package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 N：字符串版多列 orderByAsc/orderByDesc 回归。
 *
 * MP 字符串版 `orderByAsc("col_a", "col_b")` 的实参全为 ValueArg（字符串列）——
 * 别名分支曾无「第二参是 ColumnArg」判据：首参被误判为表别名且别名分支只收
 * ColumnArg，字符串列全被 mapNotNull 过滤，ORDER BY 整体静默消失（单列形态
 * size==1 恰好走 fallback 掩盖缺陷）。修复后别名分支要求「别名后跟 lambda 列」。
 */
class QueryWrapperParserStringOrderByTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
        ),
    )

    private val parser = QueryWrapperParser(table)

    private fun chain(vararg calls: WrapperCall) = WrapperChain(WrapperType.LAMBDA_QUERY, "DeviceInfoEntity", calls.toList())

    @Test
    fun `字符串版多列orderByAsc_两列都保留`() {
        val statement = parser.parse(
            chain(
                WrapperCall(
                    "orderByAsc",
                    listOf(
                        WrapperArg.ValueArg(SqlValue.Literal("create_time")),
                        WrapperArg.ValueArg(SqlValue.Literal("id")),
                    ),
                ),
            ),
        )
        assertEquals("两列都应保留（曾整体丢失）", 2, statement.orderBy.size)
        assertEquals("create_time", (statement.orderBy[0] as OrderByItem).column.columnName)
        assertEquals("id", (statement.orderBy[1] as OrderByItem).column.columnName)
    }

    @Test
    fun `字符串版单列orderByDesc_回归不变`() {
        val statement = parser.parse(
            chain(
                WrapperCall("orderByDesc", listOf(WrapperArg.ValueArg(SqlValue.Literal("sort")))),
            ),
        )
        assertEquals(1, statement.orderBy.size)
        assertEquals("sort", (statement.orderBy[0] as OrderByItem).column.columnName)
    }

    @Test
    fun `lambda多列orderByAsc_回归不变`() {
        val statement = parser.parse(
            chain(
                WrapperCall(
                    "orderByAsc",
                    listOf(
                        WrapperArg.ColumnArg(ColumnRef(columnName = "station_id")),
                        WrapperArg.ColumnArg(ColumnRef(columnName = "share_scheme_id")),
                    ),
                ),
            ),
        )
        assertEquals(2, statement.orderBy.size)
        assertEquals("station_id", (statement.orderBy[0] as OrderByItem).column.columnName)
        assertEquals("share_scheme_id", (statement.orderBy[1] as OrderByItem).column.columnName)
    }
}
