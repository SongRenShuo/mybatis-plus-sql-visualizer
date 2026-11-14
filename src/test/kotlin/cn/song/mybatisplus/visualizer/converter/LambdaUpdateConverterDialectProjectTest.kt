package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 Y6（not run 补测）：setIncrBy/setDecrBy 方言读取传 project。
 *
 * 曾调无参 currentDialect() 落全局手动值/MySQL 兜底——T-FS-04「多项目同开且项目级
 * 表配置不同时不受首个打开项目影响」被绕过。本用例以**单项目覆盖项目级方言**的
 * 形态验证透传链：项目级 SQL Server + 全局 MySQL → 保留字列按项目方言方括号引用
 * （若仍走全局/兜底则会产出反引号）。双项目同时同开的完整真机场景仍 not run。
 */
class LambdaUpdateConverterDialectProjectTest : BasePlatformTestCase() {

    private val table = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user_entity",
        columns = listOf(ColumnInfo("order", "order")),
    )

    fun testSetIncrBy按项目级方言引用列名() {
        val ps = ProjectPluginSettings.getInstance(project).state
        ps.useProjectSettings = true
        ps.dialectAuto = false
        ps.dialect = "SQL Server"
        try {
            val chain = WrapperChain(
                WrapperType.LAMBDA_UPDATE, "UserEntity",
                listOf(
                    WrapperCall(
                        "setIncrBy",
                        listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "order")),
                            WrapperArg.ValueArg(SqlValue.Literal(raw = "1")),
                        ),
                    ),
                ),
            )
            val statement = LambdaUpdateConverter.convert(chain, table, project) as UpdateStatement
            val raw = statement.sets[0].rawSql ?: error("SET 子句应有 rawSql")
            assertTrue("项目级 SQL Server 方言应方括号引用保留字列：$raw", raw.contains("[order]"))
            assertTrue("不得回落全局 MySQL 反引号口径：$raw", !raw.contains("`order`"))
        } finally {
            ps.useProjectSettings = false
            ps.dialect = ""
            ps.dialectAuto = null
        }
    }
}
