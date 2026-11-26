package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.converter.QueryWrapperParser
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * setEntity 场景的参数面板行为（D255 T-SetEntityParamKeyCollision）。
 *
 * 修复前：15 个条件行各有一个输入框，但全部绑定同一个 `"?"` 键——改一个框右侧全部条件跟着变，
 * 清除只写回第一个框。这里用 5 列表直接构造 PreviewResult（跳过 PSI），走面板真实的
 * showResult → conditionValueFields → renderSql 链路钉住三件事：键唯一、单框只影响本条件、
 * 清除立即收敛所有可见框。
 *
 * k2j 移植注（互操作三形态）：
 *  - `PreviewResult` 具名实参 → 21 参位置形态（previewModel 在第 11 位、paramHints 尾参）；
 *  - `TableInfo` 3 参重载 + `ColumnInfo(fieldName, columnName[, isId])` 位置实参；
 *  - `WrapperChain(type, entity, calls)` 3 参重载、`MySqlDialect.INSTANCE`；
 *  - `panel.showResult` / `paramValuesForTest` / `clearConditionValues` / `syncParamFieldText` 走门面。
 */
class SqlPreviewPanelSetEntityParamKeyTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        ParamHistoryCache.clearPersistedHistory()
    }

    override fun tearDown() {
        try {
            ParamHistoryCache.clearPersistedHistory()
        } finally {
            super.tearDown()
        }
    }

    private val table = TableInfo(
        "User",
        "app_user",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("password", "password"),
            ColumnInfo("age", "age"),
            ColumnInfo("email", "email"),
            ColumnInfo("username", "username"),
        ),
    )

    private val expectedSources = listOf(
        "query.id", "query.password", "query.age", "query.email", "query.username",
    )

    private fun previewResult(): PreviewResult {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY,
            "User",
            listOf(
                WrapperCall("setEntity", listOf(WrapperArg.ValueArg(SqlValue.Placeholder("query")))),
            ),
        )
        val statement = QueryWrapperParser(table).parse(chain)
        val generated = SqlGenerator.generateWithParams(statement)
        return PreviewResult(
            generated.getSql(),
            SqlGenerator.generate(statement, true),
            generated.getUnresolvedParams(),
            null,
            WrapperType.LAMBDA_QUERY,
            table.getTableName(),
            null,
            emptyList(),
            emptyMap(),
            null,
            SqlPreviewModel(
                statement,
                MySqlDialect.INSTANCE,
                true,
                statement.getConditions().mapNotNull { cond ->
                    val toggle = cond.getToggle() ?: return@mapNotNull null
                    ConditionView(
                        toggle.getId(),
                        toggle.getLabel(),
                        toggle.getFlagSource(),
                        toggle.getDefaultEnabled(),
                        toggle.getValueSource(),
                        toggle.getValueSources(),
                    )
                },
            ),
            generated.getParamHints(),
            emptyList(),
            null,
            null,
            emptyMap(),
            null,
            null,
            null,
            null,
        )
    }

    private fun panel(): SqlPreviewPanel = SqlPreviewPanel(project).also {
        Disposer.register(testRootDisposable, it)
        SqlPreviewPanelResultViewKt.showResult(it, previewResult())
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun sourceOf(field: JBTextField): String? =
        field.getClientProperty(SqlPreviewPanel.PROP_FIELD_SOURCE) as? String

    fun testConditionFieldsCarryDistinctSources() {
        val p = panel()
        val sources = p.conditionValueFields.map { sourceOf(it) }
        assertEquals(expectedSources, sources)
        assertEquals("每个条件行必须绑定独立参数键", sources.size, sources.toSet().size)
    }

    fun testEditingOneFieldOnlyReplacesItsCondition() {
        val p = panel()
        val idField = p.conditionValueFields.first { sourceOf(it) == "query.id" }
        idField.text = "2"
        UIUtil.dispatchAllInvocationEvents()

        assertEquals("2", p.paramValuesForTest()["query.id"])
        assertEquals("", p.paramValuesForTest()["query.password"])
        assertEquals("", p.paramValuesForTest()["query.age"])

        val sql = p.lastRenderedSqlForTest().orEmpty()
        assertTrue("id 应被替换为 2：$sql", sql.contains("id = 2"))
        assertTrue("password 不得串值：$sql", sql.contains("password = ?"))
        assertTrue("age 不得串值：$sql", sql.contains("age = ?"))
        assertFalse("其余条件不得被同一个值填满：$sql", sql.contains("AND password = 2"))
    }

    fun testClearEmptiesEveryConditionFieldImmediately() {
        val p = panel()
        p.conditionValueFields.forEach { it.text = "1" }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("填值后应生效：${p.lastRenderedSqlForTest()}", p.lastRenderedSqlForTest().orEmpty().contains("id = 1"))

        SqlPreviewPanelParamPanelKt.clearConditionValues(p)
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(
            "清除后所有可见条件框必须立即为空：${p.conditionValueFields.map { it.text }}",
            p.conditionValueFields.all { it.text.isEmpty() },
        )
        assertTrue(
            "paramValues 不得残留：${p.paramValuesForTest()}",
            p.paramValuesForTest().filterKeys { it.startsWith("query.") }.values.all { it.isEmpty() },
        )
        val sql = p.lastRenderedSqlForTest().orEmpty()
        assertFalse("清除后不得再有字面值：$sql", sql.contains("= 1"))
        assertTrue("清除后应回到占位符：$sql", sql.contains("id = ?"))
    }

    fun testSyncParamFieldTextWritesEveryMatchingField() {
        // D255：清除/召回/导入共用这条写回函数。同 source 多框时只写首框会让可见值与
        // paramValues 分叉（用户看到的「点清除没清干净、重开预览才正常」）。
        val p = panel()
        val first = JBTextField().apply { putClientProperty(SqlPreviewPanel.PROP_FIELD_SOURCE, "dup.source") }
        val second = JBTextField().apply { putClientProperty(SqlPreviewPanel.PROP_FIELD_SOURCE, "dup.source") }
        p.paramFields.add(first)
        p.paramFields.add(second)

        SqlPreviewPanelParamPanelKt.syncParamFieldText(p, "dup.source", "x")

        assertEquals("x", first.text)
        assertEquals("x", second.text)
    }
}
