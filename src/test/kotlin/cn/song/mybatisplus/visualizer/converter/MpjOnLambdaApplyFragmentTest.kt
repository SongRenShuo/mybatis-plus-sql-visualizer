package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.SqlFragment
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 Y4：MPJ ON-lambda 的 apply 走片段通道回归。
 *
 * 曾只认纯字面量（asValue as? SqlValue.Literal）——运行期拼接段
 * `on.eq(...).apply("t1.v = " + x)` 的 Placeholder fragment 被**静默丢弃**（条件消失）；
 * 纯字面量自带 `?` 时 Condition.Raw 不带 params，该 ? 无来源致后续回填整体错位
 * （T-UpdateSetSqlConcat 同族）。修复后与 MpjJoinSupport.parseOnLambda 统一
 * asSqlFragment 通道（字面量 ? 登记 UNKNOWN_PARAM 中性来源、Placeholder 带来源）。
 */
class MpjOnLambdaApplyFragmentTest {

    private val table = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(ColumnInfo("id", "id", isId = true), ColumnInfo("stationId", "station_id")),
    )

    private val stationTable = TableInfo(
        entityClassName = "StationInfoEntity",
        tableName = "station_info",
        columns = listOf(ColumnInfo("id", "id", isId = true), ColumnInfo("merchantId", "merchant_id")),
    )

    private fun onChain(vararg calls: WrapperCall) =
        WrapperChain(WrapperType.NESTED, "StationInfoEntity", calls.toList())

    private fun joinSql(onChain: WrapperChain): String =
        SqlGenerator.generate(
            MpjLambdaConverter.convert(
                WrapperChain(
                    WrapperType.MPJ_LAMBDA, "DeviceInfoEntity",
                    listOf(
                        WrapperCall(
                            "leftJoin",
                            listOf(WrapperArg.EntityRefArg(stationTable), WrapperArg.NestedArg(onChain)),
                        ),
                    ),
                ),
                table,
            ),
        )

    @Test
    fun apply运行期拼接段_进ON条件不静默丢弃() {
        val sql = joinSql(
            onChain(
                WrapperCall(
                    "apply",
                    listOf(
                        WrapperArg.ValueArg(
                            SqlValue.Placeholder(
                                source = "merchantId 拼接",
                                fragment = SqlFragment("st.merchant_id = ?", listOf("merchantId")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue("apply 片段应进 ON 条件：$sql", sql.contains("st.merchant_id = ?"))
    }

    @Test
    fun apply纯字面量自带问号_进ON条件且来源已登记() {
        // 字面量 ? 经 asSqlFragment 登记 UNKNOWN_PARAM 中性来源（修复前 Condition.Raw 不带 params）
        val sql = joinSql(
            onChain(
                WrapperCall(
                    "apply",
                    listOf(WrapperArg.ValueArg(SqlValue.Literal(raw = "'st.v = ?'", unquoted = "st.v = ?"))),
                ),
            ),
        )
        assertTrue("字面量片段应进 ON 条件：$sql", sql.contains("st.v = ?"))
    }
}
