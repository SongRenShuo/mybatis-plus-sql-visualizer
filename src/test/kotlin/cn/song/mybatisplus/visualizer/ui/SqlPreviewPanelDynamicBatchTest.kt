package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.ui.highlight.SqlCompletionContext
import cn.song.mybatisplus.visualizer.ui.SqlInputEditor
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.testFramework.EditorTestUtil
import com.intellij.util.ui.UIUtil
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import javax.swing.JButton
import javax.swing.SwingUtilities
import cn.song.mybatisplus.visualizer.testutil.FakeClipboardManager
import cn.song.mybatisplus.visualizer.testutil.installFakeClipboard
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.KeyStroke

/**
 * SqlPreviewPanel dynamic-condition / batch-model / execute-sql tests (split from SqlPreviewPanelTest).
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class SqlPreviewPanelDynamicBatchTest: BasePlatformTestCase() {

    /** 构造带动态条件模型的预览结果（面板走模型级重生成路径）。 */
    private fun dynamicResult(
        conditions: List<Condition>,
        views: List<ConditionView>,
        params: List<String> = emptyList(),
    ): PreviewResult {
        val statement = SelectStatement(table = tableInfo(), conditions = conditions)
        val model = SqlPreviewModel(statement, MySqlDialect, formatSql = true, conditionViews = views)
        return PreviewResult(
            sql = "SELECT id, station_id, name\nFROM car_device_info",
            compactSql = "SELECT id, station_id, name FROM car_device_info",
            unresolvedParams = params,
            paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
            wrapperType = WrapperType.LAMBDA_QUERY,
            tableName = "car_device_info",
            columns = listOf("id", "station_id", "name"),
            previewModel = model,
        )
    }

    fun testDynamicConditionCheckboxTogglesSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", "true", true, "id = 1")),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("2"),
                        toggle = ConditionToggle("c1", "false", false, "station_id = 2")),
                ),
                views = listOf(
                    ConditionView("c0", "id = 1", "true", true, null),
                    ConditionView("c1", "station_id = 2", "false", false, null),
                ),
            ),
        )
        // 默认：eq(true) 保留、eq(false) 排除
        assertTrue(panel.editorForTest()!!.document.text.contains("WHERE id = 1"))
        assertFalse(panel.editorForTest()!!.document.text.contains("station_id = 2"))

        // 勾选第二个（eq(false)）→ 拼接 station_id = 2
        panel.conditionCheckboxForTest(1)!!.doClick()
        assertTrue(panel.editorForTest()!!.document.text.contains("AND station_id = 2"))

        // 取消第一个（eq(true)）→ 移除 id = 1，station_id 保留
        panel.conditionCheckboxForTest(0)!!.doClick()
        val sql = panel.editorForTest()!!.document.text
        assertFalse(sql.contains("id = 1"))
        assertTrue(sql.contains("station_id = 2"))
        panel.clear()
    }

    /**
     * R49：Wrapper 布尔条件探镜——条件行尾求值结果标注（恒真/恒假/恒真·缺省；flagSource=null 不标注）。
     * 纯只读探针：不参与 SQL 渲染（行尾标注不影响正文），只追加淡色标签。
     */
    fun testR49_Wrapper布尔条件行尾标注() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", "true", true, "id = 1")),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("2"),
                        toggle = ConditionToggle("c1", "false", false, "station_id = 2")),
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("3"),
                        toggle = ConditionToggle("c2", "StringUtils.isNotBlank(name)", true, "name = 3")),
                    Condition.Simple(ColumnRef(columnName = "type"), "=", SqlValue.Literal("4"),
                        toggle = ConditionToggle("c3", null, true, "type = 4")),
                ),
                views = listOf(
                    ConditionView("c0", "id = 1", "true", true, null),
                    ConditionView("c1", "station_id = 2", "false", false, null),
                    ConditionView("c2", "name = 3", "StringUtils.isNotBlank(name)", true, null),
                    ConditionView("c3", "type = 4", null, true, null),
                ),
            ),
        )
        assertEquals("字面量 true → 恒真", "恒真", panel.conditionOutcomeLabelForTest(0)!!.text)
        assertEquals("字面量 false → 恒假", "恒假", panel.conditionOutcomeLabelForTest(1)!!.text)
        assertEquals("运行时占位符（缺省 true）→ 恒真·缺省", "恒真·缺省", panel.conditionOutcomeLabelForTest(2)!!.text)
        assertTrue(
            "恒真·缺省 tooltip 应带出标志原文",
            panel.conditionOutcomeLabelForTest(2)!!.toolTipText.contains("StringUtils.isNotBlank(name)"),
        )
        assertNull("flagSource=null（非布尔重载）不得标注", panel.conditionOutcomeLabelForTest(3))
        panel.clear()
    }

    /**
     * R49 边界：**无标志条件在前**时下标不得错位——标注表按条件行下标对齐，
     * 不可标注的行补 null 占位（初版跳着追加，前置无标志行会把后面标注整段前移：
     * 该形态下 index 1 会读到 c2 的「恒真·缺省」）。
     */
    fun testR49_前置无标志条件_标注下标不错位() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", null, true, "id = 1")),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("2"),
                        toggle = ConditionToggle("c1", "false", false, "station_id = 2")),
                ),
                views = listOf(
                    ConditionView("c0", "id = 1", null, true, null),
                    ConditionView("c1", "station_id = 2", "false", false, null),
                ),
            ),
        )
        assertNull("第 0 行无标志 → 下标 0 必须是 null（不得被第 1 行的标注前移占位）", panel.conditionOutcomeLabelForTest(0))
        assertEquals(
            "第 1 行的恒假标注必须仍在下标 1（与 conditionCheckboxForTest 同序）",
            "恒假",
            panel.conditionOutcomeLabelForTest(1)!!.text,
        )
        assertEquals(
            "下标 1 同时是第 1 行的复选框（同序前提）",
            "station_id = 2",
            panel.conditionCheckboxForTest(1)!!.text,
        )
        panel.clear()
    }

    fun testSelectAllNoneTogglesAllConditions() {
        // 全选/全不选（M13.1）：c0 默认勾选、c1 默认不勾选 → 全选两者都在、全不选两者都移除
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", "true", true, "id = 1")),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("2"),
                        toggle = ConditionToggle("c1", "false", false, "station_id = 2")),
                ),
                views = listOf(
                    ConditionView("c0", "id = 1", "true", true, null),
                    ConditionView("c1", "station_id = 2", "false", false, null),
                ),
            ),
        )
        // 初始：仅 c0
        assertTrue(panel.editorForTest()!!.document.text.contains("WHERE id = 1"))
        assertFalse(panel.editorForTest()!!.document.text.contains("station_id = 2"))

        // T-SelToggle：单个切换按钮——初始非全勾（仅 c0）→ 文案「全选」→ 点击全勾、文案翻「全不选」
        val toggle = panel.toggleSelectButtonForTest()!!
        assertEquals("初始非全勾应为全选文案", MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.text)
        toggle.doClick()
        assertTrue(panel.editorForTest()!!.document.text.contains("AND station_id = 2"))
        assertTrue(panel.conditionCheckboxForTest(0)!!.isSelected)
        assertTrue(panel.conditionCheckboxForTest(1)!!.isSelected)
        assertEquals("全勾后应翻转为全不选文案", MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.text)

        // 再点一次 → 动态条件全部移除（无 WHERE），复选框状态同步、文案回「全选」
        toggle.doClick()
        assertFalse(panel.editorForTest()!!.document.text.contains("WHERE"))
        assertFalse(panel.conditionCheckboxForTest(0)!!.isSelected)
        assertFalse(panel.conditionCheckboxForTest(1)!!.isSelected)
        assertTrue(panel.enabledIdsForTest().isEmpty())
        assertEquals("全不选后应回到全选文案", MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.text)

        // 全选后再取消单个勾选 → 另一个保留，文案随部分勾选态翻回「全选」
        toggle.doClick()
        panel.conditionCheckboxForTest(1)!!.doClick()
        val sql = panel.editorForTest()!!.document.text
        assertTrue(sql.contains("id = 1"))
        assertFalse(sql.contains("station_id = 2"))
        assertEquals("部分勾选应为全选文案", MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.text)
        panel.clear()
    }

    fun testToggleSelectLabelFollowsSingleCheckboxClick() {
        // T-SelToggle：文案同步挂在 renderSql 单点收口——单个复选框点选即应翻转切换按钮文案
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", "true", true, "id = 1")),
                ),
                views = listOf(ConditionView("c0", "id = 1", "true", true, null)),
            ),
        )
        val toggle = panel.toggleSelectButtonForTest()!!
        // 初始：唯一条件已勾选 → 全勾 → 「全不选」
        assertEquals(MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.text)
        panel.conditionCheckboxForTest(0)!!.doClick() // 取消唯一勾选 → 空集 → 「全选」
        assertEquals(MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.text)
        panel.conditionCheckboxForTest(0)!!.doClick() // 勾回 → 全勾 → 「全不选」
        assertEquals(MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.text)
        panel.clear()
    }

    /** 构造 iBatis 动态条件语句的预览结果（真实服务链：解析 → 无参基线 + 重渲染回调）。 */
    private fun ibatisDynamicResult(): PreviewResult {
        val xml =
            """<sqlMap><select id="selectByCondition">SELECT id, username, status, age FROM t_user""" +
                """<dynamic prepend="WHERE">""" +
                """<isNotNull property="id">id = #id#</isNotNull>""" +
                """<isNotEmpty property="username">AND username = #username#</isNotEmpty>""" +
                """<isEqual property="status" compareValue="1">AND status = #status#</isEqual>""" +
                """<isGreaterThan property="age" compareValue="18">AND age > #age#</isGreaterThan>""" +
                """</dynamic></select></sqlMap>"""
        return IbatisXmlPreviewService.preview(xml, "selectByCondition")
    }

    fun testIbatisSelectAllRerendersSql() {
        // T-IbatisSelectAll（20261111 录制现场实测）：iBatis 链点「全选」四个条件复选框全勾上、
        // SQL 预览却不重渲染——setAllConditions 只枚举 Wrapper/XML 两份 conditionViews，
        // iBatis 条件 id（`ibatis-cond-N`）在 ibatisParamModel 里，两份皆空 ⇒ enabledIds 一个没动，
        // rerender 收到的启用集与基线相同，SQL 逐字节不变。单框切换（listener 直写 enabledIds）不受影响。
        val panel = SqlPreviewPanel(project)
        panel.showResult(ibatisDynamicResult())
        assertTrue("前置：四个 iBatis 条件行必须建成复选框", panel.conditionCheckboxForTest(3) != null)
        // 无参基线：条件整段跳过（无 WHERE）
        val baselineSql = panel.editorForTest()!!.document.text
        assertFalse("无参基线不应有 WHERE 段", baselineSql.contains("WHERE"))

        // 点「全选」：复选框全勾 + enabledIds 落齐四条 + WHERE 段实时出现（修复点）
        val toggle = panel.toggleSelectButtonForTest()!!
        toggle.doClick()
        assertEquals("全选后四条 iBatis 条件 id 必须进启用集", 4, panel.enabledIdsForTest().size)
        assertTrue(panel.conditionCheckboxForTest(0)!!.isSelected)
        assertTrue(panel.conditionCheckboxForTest(3)!!.isSelected)
        assertTrue("全选后 SQL 必须实时重渲染出 WHERE 段", panel.editorForTest()!!.document.text.contains("WHERE"))
        assertTrue(panel.editorForTest()!!.document.text.contains("id = ?"))
        assertEquals("全勾后应翻转为全不选文案", MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.text)

        // 再点一次 → 全不选 → 条件整段移除
        toggle.doClick()
        assertTrue(panel.enabledIdsForTest().isEmpty())
        assertFalse(panel.editorForTest()!!.document.text.contains("WHERE"))

        // 单框切换路径回归钉：全不选后勾单个条件，WHERE 段同样实时出现（既有行为，不得被本次修复波及）
        panel.conditionCheckboxForTest(0)!!.doClick()
        assertTrue(panel.editorForTest()!!.document.text.contains("WHERE"))
        panel.clear()
    }

    fun testIbatisClearButtonClearsConditionValues() {
        // 清除按钮（iBatis 链）：填写条件值 → 点击清除 → 输入框清空、勾选态保留、SQL 恢复 ?。
        // 此前只枚举 Wrapper 条件值来源与 unresolvedParams（iBatis 基线两者皆空），点清除毫无反应。
        val panel = SqlPreviewPanel(project)
        panel.showResult(ibatisDynamicResult())
        assertNotNull("清除按钮应存在", panel.clearButtonForTest())
        // 勾选 isNotNull(id) + 填值 42（程序化 setText 经监听链同步 paramValues 并同步重渲染）
        panel.conditionCheckboxForTest(0)!!.doClick()
        panel.syncParamFieldText("id", "42")
        assertTrue("填值后 SQL 应回填 42", panel.editorForTest()!!.document.text.contains("id = 42"))

        // 点击清除：值清空、勾选态保留、SQL 回 ? 形态
        panel.clearButtonForTest()!!.doClick()
        assertEquals("", panel.paramValuesForTest()["id"])
        assertTrue("清除不影响勾选态", panel.conditionCheckboxForTest(0)!!.isSelected)
        assertTrue("清除后 SQL 应恢复 ?", panel.editorForTest()!!.document.text.contains("id = ?"))
        assertFalse(panel.editorForTest()!!.document.text.contains("id = 42"))
        panel.clear()
    }

    fun testClearButtonClearsConditionValues() {
        // 清除按钮：填写条件值 → 点击清除 → 输入框清空、勾选态保留、SQL 恢复含 ?
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Placeholder("nameVar"),
                        toggle = ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", "nameVar")),
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Placeholder("stationIdVar"),
                        toggle = ConditionToggle("c1", "stationId != null", false, "station_id = ?", "stationIdVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "StringUtils.isNotBlank(name)", true, "nameVar"),
                    ConditionView("c1", "station_id = ?", "stationId != null", false, "stationIdVar"),
                ),
            ),
        )
        // 清除按钮存在
        assertNotNull("清除按钮应存在", panel.clearButtonForTest())
        // 填写条件值
        panel.conditionValueFieldForTest(0)!!.text = "zhang"
        panel.conditionValueFieldForTest(1)!!.text = "100"
        // 值回填到 SQL
        assertTrue(panel.editorForTest()!!.document.text.contains("name = 'zhang'"))
        // 点击清除
        panel.clearButtonForTest()!!.doClick()
        // 输入框清空
        assertEquals("清除后输入框应为空", "", panel.conditionValueFieldForTest(0)!!.text)
        assertEquals("清除后输入框应为空", "", panel.conditionValueFieldForTest(1)!!.text)
        // paramValues 清空
        assertEquals("paramValues 应为空字符串", "", panel.paramValuesForTest()["nameVar"])
        assertEquals("paramValues 应为空字符串", "", panel.paramValuesForTest()["stationIdVar"])
        // 勾选态保留（c0 默认勾选，c1 默认不勾选）
        assertTrue("清除不影响勾选态", panel.conditionCheckboxForTest(0)!!.isSelected)
        assertFalse("清除不影响勾选态", panel.conditionCheckboxForTest(1)!!.isSelected)
        // SQL 恢复含 ? 形态
        assertTrue("清除后 SQL 应恢复 ?", panel.editorForTest()!!.document.text.contains("name = ?"))
        panel.clear()
    }

    fun testDynamicConditionValueBlankKeepsChecked() {
        // M13 复选框纯手动：值空不联动勾选态——默认勾选保留 `?`，填写拼接，清空仍勾选，手动取消才排除
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Placeholder("nameVar"),
                        toggle = ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", "nameVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "StringUtils.isNotBlank(name)", true, "nameVar"),
                ),
            ),
        )
        // 初始：值空但默认勾选 → 保留 ?（不再按判空语义自动取消勾选）
        assertTrue(panel.enabledIdsForTest().contains("c0"))
        assertTrue(panel.editorForTest()!!.document.text.contains("name = ?"))

        // 填写值 → 拼接实际值
        panel.conditionValueFieldForTest(0)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("name = '张三'"))

        // 清空 → 勾选态不变、保留 ?
        panel.conditionValueFieldForTest(0)!!.text = ""
        assertTrue(panel.enabledIdsForTest().contains("c0"))
        assertTrue(panel.editorForTest()!!.document.text.contains("name = ?"))

        // 手动取消勾选 → 排除
        panel.conditionCheckboxForTest(0)!!.doClick()
        assertFalse(panel.editorForTest()!!.document.text.contains("WHERE"))
        panel.clear()
    }

    fun testDynamicConditionLikePlaceholderPercentBackfill() {
        // M13：LIKE 占位符回填自动拼百分号（name 为 String 列）→ name LIKE '%张三%'
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Like(ColumnRef(columnName = "name"), negated = false,
                        pattern = SqlValue.Placeholder("likeVar"), position = LikePosition.ANYWHERE,
                        toggle = ConditionToggle("c0", "true", true, "name LIKE ?", "likeVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name LIKE ?", "true", true, "likeVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("name LIKE '%张三%'"))
        panel.clear()
    }

    fun testDynamicConditionStringColumnNumericValueQuoted() {
        // M13：String 列（java.lang.String）输入数字 → 恒加引号（不再被识别成数值）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Placeholder("nameVar"),
                        toggle = ConditionToggle("c0", "true", true, "name = ?", "nameVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "true", true, "nameVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "123"
        assertTrue(panel.editorForTest()!!.document.text.contains("name = '123'"))
        panel.clear()
    }

    fun testDynamicConditionNumericColumnValueRaw() {
        // M13：数值列（java.lang.Long）输入数字 → 原样不带引号
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Placeholder("stationVar"),
                        toggle = ConditionToggle("c0", "true", true, "station_id = ?", "stationVar")),
                ),
                views = listOf(
                    ConditionView("c0", "station_id = ?", "true", true, "stationVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "2"
        assertTrue(panel.editorForTest()!!.document.text.contains("station_id = 2"))
        panel.clear()
    }

    fun testDynamicConditionDateTimeColumnValueTemporalLiteral() {
        // T-M23-03：LocalDateTime 列回填不再是无引号；T-DateLiteralBackfill：进一步改为 ANSI 类型字面量
        // （`'2024-01-01 00:00:00'` 在 Oracle 隐式转换恒败 ORA-01861）
        val statement = SelectStatement(
            table = tableInfo().copy(
                columns = tableInfo().columns + ColumnInfo("useTime", "use_time", fieldType = "java.time.LocalDateTime"),
            ),
            conditions = listOf(
                Condition.Simple(
                    ColumnRef(columnName = "use_time"), "=", SqlValue.Placeholder("now"),
                    toggle = ConditionToggle("c0", "true", true, "use_time = ?", "now"),
                ),
            ),
        )
        val model = SqlPreviewModel(
            statement, MySqlDialect, formatSql = true,
            conditionViews = listOf(ConditionView("c0", "use_time = ?", "true", true, "now")),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            PreviewResult(
                sql = "SELECT id, station_id, name, use_time\nFROM car_device_info",
                compactSql = "SELECT id, station_id, name, use_time FROM car_device_info",
                unresolvedParams = emptyList(),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "car_device_info",
                columns = listOf("id", "station_id", "name", "use_time"),
                previewModel = model,
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "2024-01-01 00:00:00"
        assertTrue(panel.editorForTest()!!.document.text.contains("use_time = TIMESTAMP '2024-01-01 00:00:00'"))
        panel.clear()
    }

    // ------------------------------------------------------------------
    // T-M23-04：批量参数列级勾选（BatchModel 复选框 + 勾选重建 SQL）
    // ------------------------------------------------------------------

    /** 构造带 BatchModel 的批量预览结果（面板走批量勾选重生成路径）。 */
    private fun batchResult(model: BatchModel): PreviewResult {
        val active = (model.toggleColumns.filter { it.checked }.map { it.columnName } + model.fixedColumns.map { it.columnName }).toSet()
        val sql = model.renderSql(active)
        return PreviewResult(
            sql = sql,
            compactSql = sql,
            unresolvedParams = model.orderedSources(active),
            paramTable = null,
            wrapperType = null,
            tableName = model.tableName,
            columns = model.toggleColumns.map { it.columnName },
            batchModel = model,
        )
    }

    fun testBatchModelAutoIdDefaultUncheckedRendersWithoutId() {
        // T-M23-04（用户建议）：save(entity) 自增主键默认不勾选 → 初始 SQL 无 id 列
        val model = BatchModel(
            kind = BatchKind.INSERT,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = false),
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(batchResult(model))
        val sql = panel.editorForTest()!!.document.text
        assertTrue("初始 SQL 应含勾选列", sql.contains("INSERT INTO biz_store_coupon (name, dic_status)"))
        assertFalse("初始 SQL 不应含 id 列", sql.contains("id"))
        assertFalse("id 复选框默认不勾选", panel.batchCheckboxForTest(0)!!.isSelected)
        panel.clear()
    }

    fun testBatchModelCheckboxToggleRebuildsSql() {
        // T-M23-04：勾选 id 复选框 → SQL 列清单恢复 id；取消 name → SET/列清单移除 name
        val model = BatchModel(
            kind = BatchKind.INSERT,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = false),
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(batchResult(model))
        // 勾选 id → INSERT 恢复 id 列
        panel.batchCheckboxForTest(0)!!.doClick()
        assertTrue(panel.batchCheckboxForTest(0)!!.isSelected)
        assertTrue(
            "勾选后 SQL 应含 id",
            panel.editorForTest()!!.document.text.contains("INSERT INTO biz_store_coupon (id, name, dic_status)"),
        )
        // 取消 name → 列清单移除 name
        panel.batchCheckboxForTest(1)!!.doClick()
        assertTrue(
            "取消后 SQL 不应含 name",
            panel.editorForTest()!!.document.text.contains("INSERT INTO biz_store_coupon (id, dic_status)"),
        )
        // 回填值不丢：勾选态变更后重渲染仍按 source 绑定；未勾选列的输入值不进 SQL
        panel.paramFieldForTest(0)!!.text = "1"
        panel.paramFieldForTest(1)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("VALUES (1, ?)"))
        assertFalse("未勾选列（name）的输入值不应进入 SQL", panel.editorForTest()!!.document.text.contains("张三"))
        // 重新勾选 name → 值保留、按 String 列类型加引号回填
        panel.batchCheckboxForTest(1)!!.doClick()
        assertTrue(panel.editorForTest()!!.document.text.contains("VALUES (1, '张三', ?)"))
        panel.clear()
    }

    fun testBatchModelFixedColumnHasNoCheckbox() {
        // T-M23-04：UPDATE 的 WHERE 主键为固定列——仅值输入框（无复选框），复选框数 = toggle 列数
        val model = BatchModel(
            kind = BatchKind.UPDATE,
            tableName = "device_info",
            toggleColumns = listOf(
                BatchColumn("name", "entity.name", quote = true, checked = true),
            ),
            fixedColumns = listOf(BatchColumn("id", "entity.id", quote = false, checked = true)),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(batchResult(model))
        assertNotNull("toggle 列应有复选框", panel.batchCheckboxForTest(0))
        assertNull("fixed 列不应有复选框（复选框仅 toggle 列）", panel.batchCheckboxForTest(1))
        // 值输入框：toggle(name) + fixed(id) 各一个
        assertEquals("name 值输入框", "#entity.name", panel.paramFieldForTest(0)!!.toolTipText)
        assertEquals("id 值输入框", "#entity.id", panel.paramFieldForTest(1)!!.toolTipText)
        assertTrue(panel.editorForTest()!!.document.text.contains("SET name = ?"))
        assertTrue(panel.editorForTest()!!.document.text.contains("WHERE id = ?"))
        panel.clear()
    }

    fun testBatchModelSelectAllNoneButtons() {
        // T-M23.2（用户要求：参数可选场景必须有【全选/全不选】按钮）：
        // 全不选 → 全部取消勾选、SQL 空列清单；全选 → 全部勾选、SQL 全列
        val model = BatchModel(
            kind = BatchKind.INSERT,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = false),
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(batchResult(model))
        val toggle = panel.batchToggleSelectButtonForTest()
        assertNotNull("批量区应有全选/全不选切换按钮", toggle)
        // T-SelToggle：初始部分勾选（id 未勾）→ 文案「全选」；点击 → 全列恢复
        assertEquals(MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle!!.text)
        toggle.doClick()
        assertTrue(panel.batchCheckboxForTest(0)!!.isSelected)
        assertTrue(panel.batchCheckboxForTest(1)!!.isSelected)
        assertTrue(panel.batchCheckboxForTest(2)!!.isSelected)
        assertEquals(MpSqlVisualizerBundle.message("panel.condition.selectNone"), toggle.text)
        assertTrue(
            panel.editorForTest()!!.document.text.contains("INSERT INTO biz_store_coupon (id, name, dic_status)"),
        )
        // 再点 → 全不选 → 空列清单（文档化边界），文案回「全选」
        toggle.doClick()
        assertFalse(panel.batchCheckboxForTest(0)!!.isSelected)
        assertFalse(panel.batchCheckboxForTest(1)!!.isSelected)
        assertFalse(panel.batchCheckboxForTest(2)!!.isSelected)
        assertEquals(MpSqlVisualizerBundle.message("panel.condition.selectAll"), toggle.text)
        assertTrue(panel.editorForTest()!!.document.text.contains("INSERT INTO biz_store_coupon ()"))
        panel.clear()
    }

    fun testInListMultiValueBackfill() {
        // M19.6（2026-08-13 用户实测）：IN 列表占位符回填按逗号分割多值、每个值独立引号——
        // 输入 `1,2` → IN ('1','2')（原实现拼成 IN ('1,2') 单值；name 为 String 列恒引号）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.In(
                        ColumnRef(columnName = "name"),
                        negated = false,
                        values = listOf(SqlValue.Placeholder("names")),
                        toggle = ConditionToggle("c0", null, true, "name IN (?)", "names", listOf("names")),
                    ),
                ),
                views = listOf(
                    ConditionView("c0", "name IN (?)", null, true, "names", listOf("names")),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "张三,李四"
        assertTrue(panel.editorForTest()!!.document.text.contains("name IN ('张三', '李四')"))
        // 单值输入不受影响（String 列恒引号）
        panel.conditionValueFieldForTest(0)!!.text = "王五"
        assertTrue(panel.editorForTest()!!.document.text.contains("name IN ('王五')"))
        panel.clear()
    }

    // ------------------------------------------------------------------
    // M17：执行 SQL（M17.4 起由弹窗底部按钮区调用面板 internal executeSql()；
    // 测试沙盒无 Database 插件 → Community 降级路径）
    // ------------------------------------------------------------------

    fun testExecuteSqlWithoutDatabasePluginNoThrow() {
        // 测试沙盒无 Database 插件 → 走 Community 降级提示（headless 下被兜底吞掉），不抛异常
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "SELECT id FROM tbl", compact = "SELECT id FROM tbl"))
        panel.executeSql()
        panel.clear()
    }

    fun testExecuteSqlDisabledWithoutProject() {
        // 无项目面板（纯组件预览）：执行无动作（project null 直接返回），不抛异常
        val panel = SqlPreviewPanel(null)
        panel.showResult(result())
        panel.executeSql()
        panel.clear()
    }

    fun testExecuteSqlBlankSqlNoop() {
        // 空 SQL：不触发执行链路，不抛异常
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "   ", compact = "   "))
        panel.executeSql()
        panel.clear()
    }

    fun testSplitIsNativeOnePixelSplitter() {
        // T-M26.1→T-UiNative P0：左右分栏换平台 OnePixelSplitter——分隔条由平台绘制（原生主题
        // 融合 + 比例持久化键 mpvs.preview.splitter），替代旧 ThemedSplitPane 覆写 paint 粉刷。
        // 断言：分栏是平台 Splitter（持久化键就位）且 proportion 契约可用
        val panel = SqlPreviewPanel(project)
        val split = panel.splitPaneForTest()
        assertNotNull("应存在左右分栏", split)
        assertEquals("mpvs.preview.splitter", split!!.splitterProportionKey)
        split.setSize(300, 100)
        split.doLayout()
        split.proportion = 0.4f
        assertEquals(0.4f, split.proportion, 1e-6f)
        panel.clear()
    }

    fun testSelectAllButtonsOnConditionSectionHeader() {
        // T-M26.3（方案 A）：全选/全不选回挂「动态条件」分区标题行右侧（IDEA 组操作惯例，
        // T-M26.2 的固定右上角已拆除）。长 test 表达式标签按视口宽度截断 → 标题行按钮不被推出
        // 视口：断言按钮在参数面板内、布局后仍可见于视口
        val long = "stationIds != null and stationIds.size() > 0"
        val panel = SqlPreviewPanel(project)
        val split = panel.splitPaneForTest()!!
        // 先布局再 showResult：截断上限按视口宽度计算（真实 IDE 中 rebuild 发生在面板布局后）
        split.setSize(500, 120)
        split.doLayout()
        split.proportion = 0.5f
        split.doLayout()
        (split.firstComponent as JBScrollPane).doLayout() // 布局 viewport bounds（否则宽度为 0，截断上限回落默认值）
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "station_id"), "IN", SqlValue.Placeholder("stationIds"),
                        toggle = ConditionToggle("c0", "true", true, "station_id IN (?)", "stationIds", listOf("stationIds"))),
                ),
                views = listOf(ConditionView("c0", long, "true", true, "stationIds", listOf("stationIds"))),
            ),
        )
        val scroll = split.firstComponent as JBScrollPane
        val viewport = scroll.viewport
        val button = panel.toggleSelectButtonForTest()!!
        // 按钮在参数面板内（回挂分区标题行，随列表滚动——T-M26.2 曾固定视口外）
        assertTrue("按钮应在参数面板内", SwingUtilities.isDescendingFrom(button, viewport.view))
        // 布局（doLayout 不递归，逐层布局组件树）后按钮仍可见于视口（长标签按视口截断 → 行宽受限）
        split.doLayout()
        layoutTree(scroll)
        val btnLeft = SwingUtilities.convertPoint(button, 0, 0, scroll).x
        assertTrue("按钮应位于视口内", btnLeft < viewport.width)
        panel.clear()
    }

    /** T-ClipboardIso：内存剪贴板（测试全程不碰系统剪贴板——原快照/还原写法在用户复制或
     *  并行会话占用剪贴板时会在 setUp 抛 cannot open system clipboard，且还原失败会污染用户剪贴板）。 */
    private lateinit var clipboard: FakeClipboardManager

    // T-M27.4：渲染文本断言类测试关闭自动格式化（默认开启的格式化行为由
    // SqlFormatterTest + testRenderAutoFormatsSql 专项覆盖；此处模拟"关闭格式化"场景）
    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = false }
        clipboard = installFakeClipboard(testRootDisposable)
    }

    override fun tearDown() {
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true }
        super.tearDown()
    }

    private fun result(
        sql: String = "SELECT id\nFROM tbl",
        compact: String = "SELECT id FROM tbl",
        params: List<String> = emptyList(),
        columns: List<String> = listOf("id", "name"),
        tableName: String? = "tbl",
        candidateTables: List<TableInfo> = emptyList(),
    ) = PreviewResult(
        sql = sql,
        compactSql = compact,
        unresolvedParams = params,
        paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = tableName,
        columns = columns,
        candidateTables = candidateTables,
    )

    // ------------------------------------------------------------------
    // M12/M13：动态条件复选框（手动勾选）+ 回填提示（LIKE 百分号 / 字段类型引号）
    // ------------------------------------------------------------------

    private fun tableInfo() = TableInfo(
        entityClassName = "DeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true, fieldType = "java.lang.Long"),
            ColumnInfo("stationId", "station_id", fieldType = "java.lang.Long"),
            ColumnInfo("name", "name", fieldType = "java.lang.String"),
        ),
    )

    /** headless 未显示组件 validate() 不布局（peer 为 null）——同步 doLayout 递归布局组件树。 */
    private fun layoutTree(container: java.awt.Container) {
        container.doLayout()
        container.components.filterIsInstance<java.awt.Container>().forEach { layoutTree(it) }
    }
}
