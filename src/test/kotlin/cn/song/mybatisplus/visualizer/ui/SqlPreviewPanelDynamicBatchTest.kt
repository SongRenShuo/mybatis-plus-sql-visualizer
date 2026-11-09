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
        val statement = SelectStatement(tableInfo(), emptyList(), null, conditions)
        val model = SqlPreviewModel(statement, MySqlDialect.INSTANCE, true, views)
        // k2j 适配：具名实参改 20 参位置形态（previewModel 落第 11 位）
        return PreviewResult(
            "SELECT id, station_id, name\nFROM car_device_info",
            "SELECT id, station_id, name FROM car_device_info",
            params,
            if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
            WrapperType.LAMBDA_QUERY,
            "car_device_info",
            null,
            listOf("id", "station_id", "name"),
            emptyMap(),
            null,
            model,
            emptyList(),
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

    fun testDynamicConditionCheckboxTogglesSql() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"),
                        ConditionToggle("c0", "true", true, "id = 1")),
                    Condition.Simple(ColumnRef("station_id"), "=", SqlValue.Literal("2"),
                        ConditionToggle("c1", "false", false, "station_id = 2")),
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testSelectAllNoneTogglesAllConditions() {
        // 全选/全不选（M13.1）：c0 默认勾选、c1 默认不勾选 → 全选两者都在、全不选两者都移除
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"),
                        ConditionToggle("c0", "true", true, "id = 1")),
                    Condition.Simple(ColumnRef("station_id"), "=", SqlValue.Literal("2"),
                        ConditionToggle("c1", "false", false, "station_id = 2")),
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testToggleSelectLabelFollowsSingleCheckboxClick() {
        // T-SelToggle：文案同步挂在 renderSql 单点收口——单个复选框点选即应翻转切换按钮文案
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"),
                        ConditionToggle("c0", "true", true, "id = 1")),
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testClearButtonClearsConditionValues() {
        // 清除按钮：填写条件值 → 点击清除 → 输入框清空、勾选态保留、SQL 恢复含 ?
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef("name"), "=", SqlValue.Placeholder("nameVar"),
                        ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", "nameVar")),
                    Condition.Simple(ColumnRef("station_id"), "=", SqlValue.Placeholder("stationIdVar"),
                        ConditionToggle("c1", "stationId != null", false, "station_id = ?", "stationIdVar")),
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testDynamicConditionValueBlankKeepsChecked() {
        // M13 复选框纯手动：值空不联动勾选态——默认勾选保留 `?`，填写拼接，清空仍勾选，手动取消才排除
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef("name"), "=", SqlValue.Placeholder("nameVar"),
                        ConditionToggle("c0", "StringUtils.isNotBlank(name)", true, "name = ?", "nameVar")),
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testDynamicConditionLikePlaceholderPercentBackfill() {
        // M13：LIKE 占位符回填自动拼百分号（name 为 String 列）→ name LIKE '%张三%'
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Like(ColumnRef("name"), false, SqlValue.Placeholder("likeVar"), LikePosition.ANYWHERE,
                        ConditionToggle("c0", "true", true, "name LIKE ?", "likeVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name LIKE ?", "true", true, "likeVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "张三"
        assertTrue(panel.editorForTest()!!.document.text.contains("name LIKE '%张三%'"))
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testDynamicConditionStringColumnNumericValueQuoted() {
        // M13：String 列（java.lang.String）输入数字 → 恒加引号（不再被识别成数值）
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef("name"), "=", SqlValue.Placeholder("nameVar"),
                        ConditionToggle("c0", "true", true, "name = ?", "nameVar")),
                ),
                views = listOf(
                    ConditionView("c0", "name = ?", "true", true, "nameVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "123"
        assertTrue(panel.editorForTest()!!.document.text.contains("name = '123'"))
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testDynamicConditionNumericColumnValueRaw() {
        // M13：数值列（java.lang.Long）输入数字 → 原样不带引号
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef("station_id"), "=", SqlValue.Placeholder("stationVar"),
                        ConditionToggle("c0", "true", true, "station_id = ?", "stationVar")),
                ),
                views = listOf(
                    ConditionView("c0", "station_id = ?", "true", true, "stationVar"),
                ),
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "2"
        assertTrue(panel.editorForTest()!!.document.text.contains("station_id = 2"))
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testDynamicConditionDateTimeColumnValueTemporalLiteral() {
        // T-M23-03：LocalDateTime 列回填不再是无引号；T-DateLiteralBackfill（178 轮随生产面同步）：
        // 进一步改为 ANSI 类型字面量（`'2024-01-01 00:00:00'` 在 Oracle 隐式转换恒败 ORA-01861）
        val statement = SelectStatement(
            // k2j 适配：TableInfo.copy 为 7 参位置形态
            tableInfo().copy(
                tableInfo().entityClassName, tableInfo().tableName,
                tableInfo().columns + ColumnInfo("useTime", "use_time", false, false, "java.time.LocalDateTime"),
                tableInfo().logicDeleteColumn, tableInfo().versionColumn, tableInfo().comment,
                tableInfo().associations,
            ),
            emptyList(),
            null,
            listOf(
                Condition.Simple(
                    ColumnRef("use_time"), "=", SqlValue.Placeholder("now"),
                    ConditionToggle("c0", "true", true, "use_time = ?", "now"),
                ),
            ),
        )
        val model = SqlPreviewModel(
            statement, MySqlDialect.INSTANCE, true,
            listOf(ConditionView("c0", "use_time = ?", "true", true, "now")),
        )
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            // k2j 适配：20 参位置构造（previewModel 第 11 位）
            PreviewResult(
                "SELECT id, station_id, name, use_time\nFROM car_device_info",
                "SELECT id, station_id, name, use_time FROM car_device_info",
                emptyList(),
                null,
                WrapperType.LAMBDA_QUERY,
                "car_device_info",
                null,
                listOf("id", "station_id", "name", "use_time"),
                emptyMap(),
                null,
                model,
                emptyList(),
                emptyList(),
                null,
                null,
                emptyMap(),
                null,
                null,
                null,
                null,
            ),
        )
        panel.conditionValueFieldForTest(0)!!.text = "2024-01-01 00:00:00"
        assertTrue(panel.editorForTest()!!.document.text.contains("use_time = TIMESTAMP '2024-01-01 00:00:00'"))
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    // ------------------------------------------------------------------
    // T-M23-04：批量参数列级勾选（BatchModel 复选框 + 勾选重建 SQL）
    // ------------------------------------------------------------------

    /** 构造带 BatchModel 的批量预览结果（面板走批量勾选重生成路径）。 */
    private fun batchResult(model: BatchModel): PreviewResult {
        val active = (model.toggleColumns.filter { it.checked }.map { it.columnName } + model.fixedColumns.map { it.columnName }).toSet()
        val sql = model.renderSql(active)
        // k2j 适配：20 参位置构造（batchModel 第 14 位）；JavaBean 访问器 getTableName()
        return PreviewResult(
            sql,
            sql,
            model.orderedSources(active),
            null,
            null,
            model.tableName,
            null,
            model.toggleColumns.map { it.columnName },
            emptyMap(),
            null,
            null,
            emptyList(),
            emptyList(),
            model,
            null,
            emptyMap(),
            null,
            null,
            null,
            null,
        )
    }

    fun testBatchModelAutoIdDefaultUncheckedRendersWithoutId() {
        // T-M23-04（用户建议）：save(entity) 自增主键默认不勾选 → 初始 SQL 无 id 列
        val model = BatchModel(
            BatchKind.INSERT,
            "biz_store_coupon",
            listOf(
                BatchColumn("id", "entity.id", false, false),
                BatchColumn("name", "entity.name", true, true),
                BatchColumn("dic_status", "entity.dic_status", false, true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, batchResult(model))
        val sql = panel.editorForTest()!!.document.text
        assertTrue("初始 SQL 应含勾选列", sql.contains("INSERT INTO biz_store_coupon (name, dic_status)"))
        assertFalse("初始 SQL 不应含 id 列", sql.contains("id"))
        assertFalse("id 复选框默认不勾选", panel.batchCheckboxForTest(0)!!.isSelected)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testBatchModelCheckboxToggleRebuildsSql() {
        // T-M23-04：勾选 id 复选框 → SQL 列清单恢复 id；取消 name → SET/列清单移除 name
        val model = BatchModel(
            BatchKind.INSERT,
            "biz_store_coupon",
            listOf(
                BatchColumn("id", "entity.id", false, false),
                BatchColumn("name", "entity.name", true, true),
                BatchColumn("dic_status", "entity.dic_status", false, true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, batchResult(model))
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testBatchModelFixedColumnHasNoCheckbox() {
        // T-M23-04：UPDATE 的 WHERE 主键为固定列——仅值输入框（无复选框），复选框数 = toggle 列数
        val model = BatchModel(
            BatchKind.UPDATE,
            "device_info",
            listOf(
                BatchColumn("name", "entity.name", true, true),
            ),
            listOf(BatchColumn("id", "entity.id", false, true)),
        )
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, batchResult(model))
        assertNotNull("toggle 列应有复选框", panel.batchCheckboxForTest(0))
        assertNull("fixed 列不应有复选框（复选框仅 toggle 列）", panel.batchCheckboxForTest(1))
        // 值输入框：toggle(name) + fixed(id) 各一个
        assertEquals("name 值输入框", "#entity.name", panel.paramFieldForTest(0)!!.toolTipText)
        assertEquals("id 值输入框", "#entity.id", panel.paramFieldForTest(1)!!.toolTipText)
        assertTrue(panel.editorForTest()!!.document.text.contains("SET name = ?"))
        assertTrue(panel.editorForTest()!!.document.text.contains("WHERE id = ?"))
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testBatchModelSelectAllNoneButtons() {
        // T-M23.2（用户要求：参数可选场景必须有【全选/全不选】按钮）：
        // 全不选 → 全部取消勾选、SQL 空列清单；全选 → 全部勾选、SQL 全列
        val model = BatchModel(
            BatchKind.INSERT,
            "biz_store_coupon",
            listOf(
                BatchColumn("id", "entity.id", false, false),
                BatchColumn("name", "entity.name", true, true),
                BatchColumn("dic_status", "entity.dic_status", false, true),
            ),
        )
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, batchResult(model))
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testInListMultiValueBackfill() {
        // M19.6（2026-08-13 用户实测）：IN 列表占位符回填按逗号分割多值、每个值独立引号——
        // 输入 `1,2` → IN ('1','2')（原实现拼成 IN ('1,2') 单值；name 为 String 列恒引号）
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.In(
                        ColumnRef("name"), false, listOf(SqlValue.Placeholder("names")),
                        ConditionToggle("c0", null, true, "name IN (?)", "names", listOf("names")),
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    // ------------------------------------------------------------------
    // M17：执行 SQL（M17.4 起由弹窗底部按钮区调用面板 internal executeSql()；
    // 测试沙盒无 Database 插件 → Community 降级路径）
    // ------------------------------------------------------------------

    fun testExecuteSqlWithoutDatabasePluginNoThrow() {
        // 测试沙盒无 Database 插件 → 走 Community 降级提示（headless 下被兜底吞掉），不抛异常
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = "SELECT id FROM tbl", compact = "SELECT id FROM tbl"))
        SqlPreviewPanelExecutionKt.executeSql(panel)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testExecuteSqlDisabledWithoutProject() {
        // 无项目面板（纯组件预览）：执行无动作（project null 直接返回），不抛异常
        val panel = SqlPreviewPanel(null)
        SqlPreviewPanelResultViewKt.showResult(panel, result())
        SqlPreviewPanelExecutionKt.executeSql(panel)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testExecuteSqlBlankSqlNoop() {
        // 空 SQL：不触发执行链路，不抛异常
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = "   ", compact = "   "))
        SqlPreviewPanelExecutionKt.executeSql(panel)
        SqlPreviewPanelSqlViewKt.clear(panel)
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
        SqlPreviewPanelSqlViewKt.clear(panel)
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
        SqlPreviewPanelResultViewKt.showResult(panel,
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef("station_id"), "IN", SqlValue.Placeholder("stationIds"),
                        ConditionToggle("c0", "true", true, "station_id IN (?)", "stationIds", listOf("stationIds"))),
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
        SqlPreviewPanelSqlViewKt.clear(panel)
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
        // k2j 适配：具名实参改 20 参位置形态（candidateTables 第 13 位）
        sql,
        compact,
        params,
        if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
        WrapperType.LAMBDA_QUERY,
        tableName,
        null,
        columns,
        emptyMap(),
        null,
        null,
        emptyList(),
        candidateTables,
        null,
        null,
        emptyMap(),
        null,
        null,
        null,
        null,
    )

    // ------------------------------------------------------------------
    // M12/M13：动态条件复选框（手动勾选）+ 回填提示（LIKE 百分号 / 字段类型引号）
    // ------------------------------------------------------------------

    private fun tableInfo() = TableInfo(
        "DeviceInfoEntity",
        "car_device_info",
        listOf(
            ColumnInfo("id", "id", true, false, "java.lang.Long"),
            ColumnInfo("stationId", "station_id", false, false, "java.lang.Long"),
            ColumnInfo("name", "name", false, false, "java.lang.String"),
        ),
    )

    /** headless 未显示组件 validate() 不布局（peer 为 null）——同步 doLayout 递归布局组件树。 */
    private fun layoutTree(container: java.awt.Container) {
        container.doLayout()
        container.components.filterIsInstance<java.awt.Container>().forEach { layoutTree(it) }
    }
}
