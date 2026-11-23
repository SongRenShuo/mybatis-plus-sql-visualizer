package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * setEntity 展开的参数键（D255 T-SetEntityParamKeyCollision）。
 *
 * 缺陷形态：15 列全部 `col = ?` 共用匿名键 `"?"`，预览参数面板 15 个输入框读写同一个
 * `paramValues["?"]`——改一个框右侧全变，点清除只写回第一个框。修复后每列一个独立键
 * （`query.id`、`query.password`…），SQL 文本必须保持逐字节不变（键只影响参数元数据）。
 */
class QueryWrapperParserSetEntityParamKeyTest {

    private val table = TableInfo(
        entityClassName = "User",
        tableName = "app_user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("password", "password"),
            ColumnInfo("age", "age"),
            ColumnInfo("email", "email"),
            ColumnInfo("username", "username"),
        ),
    )

    private val parser = QueryWrapperParser(table)

    private fun setEntityChain(vararg entityArgs: String): WrapperChain = WrapperChain(
        WrapperType.LAMBDA_QUERY,
        "User",
        entityArgs.map { arg ->
            WrapperCall("setEntity", listOf(WrapperArg.ValueArg(SqlValue.Placeholder(arg))))
        },
    )

    private fun sourcesOf(chain: WrapperChain): List<String> {
        val statement = parser.parse(chain)
        return statement.conditions.mapNotNull { it.toggle?.valueSources?.singleOrNull() }
    }

    @Test
    fun `setEntity 每列生成独立参数键`() {
        assertEquals(
            listOf("query.id", "query.password", "query.age", "query.email", "query.username"),
            sourcesOf(setEntityChain("query")),
        )
    }

    @Test
    fun `SQL 文本保持与原占位符形态一致`() {
        val sql = SqlGenerator.generate(parser.parse(setEntityChain("query")))
        assertEquals(
            """
            SELECT id, password, age, email, username
            FROM app_user
            WHERE id = ?
                AND password = ?
                AND age = ?
                AND email = ?
                AND username = ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `回填来源与参数键一一对应`() {
        val generated = SqlGenerator.generateWithParams(parser.parse(setEntityChain("query")))
        assertEquals(
            listOf("query.id", "query.password", "query.age", "query.email", "query.username"),
            generated.unresolvedParams,
        )
        assertEquals("键不得重复", generated.unresolvedParams.toSet().size, generated.unresolvedParams.size)
    }

    @Test
    fun `不可命名的实参回退 entity 前缀`() {
        assertEquals(
            listOf("entity.id", "entity.password", "entity.age", "entity.email", "entity.username"),
            sourcesOf(setEntityChain("new User()")),
        )
    }

    @Test
    fun `多次 setEntity 的参数键互不覆盖`() {
        val sources = sourcesOf(setEntityChain("query", "query"))
        assertEquals(10, sources.size)
        assertEquals("两份展开必须各自唯一", 10, sources.toSet().size)
        assertEquals("query.id", sources[0])
        assertEquals("query#2.id", sources[5])
    }
}
