package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 P：renderLike 重包 LIKE 字面量补单引号转义回归。
 *
 * unquoted 是未转义原文——曾直接拼进引号重包，LIKE 常量含单引号（O'Hara 等）
 * 产出 `'%O'Hara%'` 三引号断串，且该文本进复制可执行 SQL/执行链。
 */
class SqlGeneratorLikeQuoteEscapeTest {

    private val parser = cn.song.mybatisplus.visualizer.converter.QueryWrapperParser(
        cn.song.mybatisplus.visualizer.model.TableInfo(
            entityClassName = "DeviceInfoEntity",
            tableName = "car_device_info",
            columns = listOf(cn.song.mybatisplus.visualizer.model.ColumnInfo("name", "name")),
        ),
    )

    @Test
    fun `LIKE常量含单引号_产物转义完整`() {
        val statement = parser.parse(
            WrapperChain(
                WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
                listOf(
                    WrapperCall(
                        "like",
                        listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                            // psi 层产物：raw=已转义带引号，unquoted=原文
                            WrapperArg.ValueArg(SqlValue.Literal(raw = "'O''Hara%'", unquoted = "O'Hara%")),
                        ),
                    ),
                ),
            ),
        )
        val sql = SqlGenerator.generate(statement)
        assertTrue("产物应含正确转义的 LIKE 常量：$sql", sql.contains("%O''Hara%%"))  // 值自带尾 %，ANYWHERE 两侧包 %
        assertTrue("不得出现断串形态", !sql.contains("'%O'Hara%'"))
    }

    @Test
    fun `LIKE右缀与无缀形态转义一致`() {
        val statement = parser.parse(
            WrapperChain(
                WrapperType.LAMBDA_QUERY, "DeviceInfoEntity",
                listOf(
                    WrapperCall(
                        "likeRight",
                        listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                            WrapperArg.ValueArg(SqlValue.Literal(raw = "'O''Brien'", unquoted = "O'Brien")),
                        ),
                    ),
                ),
            ),
        )
        val sql = SqlGenerator.generate(statement)
        assertTrue("右缀形态：$sql", sql.contains("'O''Brien%'"))
    }
}
