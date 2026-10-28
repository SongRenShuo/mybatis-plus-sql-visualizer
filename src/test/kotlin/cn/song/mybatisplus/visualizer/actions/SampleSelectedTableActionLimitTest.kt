package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * R5：抽样 LIMIT 配置化测试（模板函数消费 PluginSettings.state.sampleLimit；
 * BasePlatformTestCase 提供 Application 供 getInstance）。
 *
 * 修复前 LIMIT 硬编码 10（配置点 grep 为零，第四轮报告 R5 缺口）；本用例先红
 * （配置 25 时模板仍产出 LIMIT 10）、修复后绿（LIMIT 25）。钳制 1..10000。
 *
 * T-SyncK2JMaster136 C波 差距5 随产对齐 master 终态：templateOf 不再反射 template
 * 函数字段（该形态已随「捕获方言渲染器」上收删除），直调
 * SelectedTableSqlTemplateAction.renderSelectedTableTemplate（Java 静态；方言为
 * MySqlDialect.INSTANCE——Kotlin object 在 Java 侧的承载形态）；补 master 同文件
 * 两条用例（只读契约 + Oracle 抽样/EXPLAIN 按捕获方言渲染）。
 */
class SampleSelectedTableActionLimitTest : BasePlatformTestCase() {

    private fun templateOf(selected: String): String =
        SelectedTableSqlTemplateAction.renderSelectedTableTemplate(
            SelectedTableTemplateKind.SAMPLE,
            selected,
            MySqlDialect.INSTANCE,
            PluginSettings.getInstance().state.sampleLimit,
        )!!

    private fun withLimit(limit: Int, block: () -> Unit) {
        val old = PluginSettings.getInstance().state.sampleLimit
        try {
            PluginSettings.getInstance().state.sampleLimit = limit
            block()
        } finally {
            PluginSettings.getInstance().state.sampleLimit = old
        }
    }

    fun `test R5_默认LIMIT10`() {
        withLimit(10) { assertEquals("SELECT * FROM t_user LIMIT 10", templateOf("t_user")) }
    }

    fun `test R5_配置生效_默认档改25即产出LIMIT25`() {
        withLimit(25) { assertEquals("SELECT * FROM t_user LIMIT 25", templateOf("t_user")) }
    }

    fun `test R5_范围钳制_0负数与超界均收敛`() {
        withLimit(0) { assertEquals("SELECT * FROM t LIMIT 1", templateOf("t")) }
        withLimit(-5) { assertEquals("SELECT * FROM t LIMIT 1", templateOf("t")) }
        withLimit(99999) { assertEquals("SELECT * FROM t LIMIT 10000", templateOf("t")) }
    }

    fun testPreviewMenuReusesReadOnlyTemplateSqlContract() {
        val expected = mapOf(
            SelectedTableTemplateKind.EXPLAIN to "EXPLAIN SELECT * FROM user",
            SelectedTableTemplateKind.COUNT to "SELECT COUNT(*) FROM user",
            SelectedTableTemplateKind.SAMPLE to "SELECT * FROM user LIMIT 10",
        )
        for ((kind, sql) in expected) {
            val actual = SelectedTableSqlTemplateAction.renderSelectedTableTemplate(
                kind, "user", MySqlDialect.INSTANCE, 10)
            assertEquals(sql, actual)
            org.junit.Assert.assertTrue("模板必须保持只读：$kind",
                cn.song.mybatisplus.visualizer.execute.SqlTypeClassifier.isQuery(actual!!))
        }
    }

    fun `test 抽样与EXPLAIN按捕获方言渲染`() {
        assertEquals(
            "SELECT * FROM t FETCH FIRST 25 ROWS ONLY",
            SelectedTableSqlTemplateAction.renderSelectedTableTemplate(
                SelectedTableTemplateKind.SAMPLE, "t", OracleDialect.INSTANCE, 25),
        )
        assertEquals(
            MySqlDialect.INSTANCE.renderExplainStatement("SELECT * FROM t"),
            SelectedTableSqlTemplateAction.renderSelectedTableTemplate(
                SelectedTableTemplateKind.EXPLAIN, "t", MySqlDialect.INSTANCE, 10),
        )
    }
}
