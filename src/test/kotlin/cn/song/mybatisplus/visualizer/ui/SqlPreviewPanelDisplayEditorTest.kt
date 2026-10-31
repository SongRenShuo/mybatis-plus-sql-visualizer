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
 * SqlPreviewPanel display-mode / editor-completion / auto-fold tests (split from SqlPreviewPanelTest).
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class SqlPreviewPanelDisplayEditorTest: BasePlatformTestCase() {

    private val previewPopupId = "cn.song.mybatisplus.visualizer.PreviewEditorPopupMenu"
    private val templateActionIds = listOf(
        "cn.song.mybatisplus.visualizer.ExplainSelectedTable",
        "cn.song.mybatisplus.visualizer.CountSelectedTable",
        "cn.song.mybatisplus.visualizer.SampleSelectedTable",
    )

    /** 从真实面板创建的编辑器取菜单，不另造注册组或 Editor 替身。 */
    private fun popupLeaves(editor: com.intellij.openapi.editor.ex.EditorEx): List<com.intellij.openapi.actionSystem.AnAction> {
        assertEquals(previewPopupId, editor.contextMenuGroupId)
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val root = manager.getAction(editor.contextMenuGroupId!!)
        assertTrue("预览专用菜单必须由 plugin.xml 注册", root is com.intellij.openapi.actionSystem.ActionGroup)
        fun leaves(action: com.intellij.openapi.actionSystem.AnAction): List<com.intellij.openapi.actionSystem.AnAction> =
            when (action) {
                is com.intellij.openapi.actionSystem.Separator -> emptyList()
                is com.intellij.openapi.actionSystem.ActionGroup -> action.getChildren(null).flatMap { leaves(it) }
                else -> listOf(action)
            }
        return leaves(root!!)
    }

    fun testPreviewPopupContainsOnlyOriginalEditActionsAndExistingTemplates() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(result(sql = "SELECT * FROM user", compact = "SELECT * FROM user"))
            val editor = panel.editorForTest() as com.intellij.openapi.editor.ex.EditorEx
            val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            val actions = popupLeaves(editor)
            val expected = listOf("\$Cut", "\$Copy", "\$Paste") + templateActionIds
            assertEquals("不能漏模板或灌入完整 EditorPopupMenu", expected, actions.map { manager.getId(it) })
            expected.zip(actions).forEach { (id, action) ->
                assertSame("必须复用原动作而非替换行为：$id", manager.getAction(id), action)
            }
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun testPreviewPopupTemplateEnablementUsesRealEditorSelection() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(result(sql = "SELECT * FROM user", compact = "SELECT * FROM user"))
            val editor = panel.editorForTest() as com.intellij.openapi.editor.ex.EditorEx
            val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            val templates = popupLeaves(editor).filter { manager.getId(it) in templateActionIds }
            assertEquals(3, templates.size)
            val context = com.intellij.testFramework.MapDataContext().apply {
                put(CommonDataKeys.EDITOR.name, editor)
                put(CommonDataKeys.PROJECT.name, project)
            }
            val start = editor.document.text.indexOf("user")
            assertTrue(start >= 0)
            editor.selectionModel.setSelection(start, start + 4)
            assertEquals("user", editor.selectionModel.selectedText)
            templates.forEach { action ->
                val event = AnActionEvent.createFromAnAction(action, null, "test", context)
                action.update(event)
                assertTrue("选 user 应启用：${manager.getId(action)}", event.presentation.isEnabled)
            }
            editor.selectionModel.setSelection(0, editor.document.textLength)
            templates.forEach { action ->
                val event = AnActionEvent.createFromAnAction(action, null, "test", context)
                action.update(event)
                assertFalse("整条 SELECT 不能当表名：${manager.getId(action)}", event.presentation.isEnabled)
            }
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun testPreviewPopupSurvivesEditorRecreationWithoutChangingMainEditor() {
        myFixture.configureByText("source.txt", "SELECT * FROM user")
        val source = myFixture.editor as com.intellij.openapi.editor.ex.EditorEx
        val originalPopup = source.contextMenuGroupId
        val panel = SqlPreviewPanel(project)
        try {
            repeat(2) {
                panel.showResult(result(sql = "SELECT * FROM user", compact = "SELECT * FROM user"))
                assertEquals(6, popupLeaves(panel.editorForTest() as com.intellij.openapi.editor.ex.EditorEx).size)
                assertEquals("不得修改主编辑器菜单", originalPopup, source.contextMenuGroupId)
                panel.clear()
            }
        } finally {
            panel.clear()
            panel.dispose()
        }
    }

    fun testReformatShortcutRegistered() {
        // T-M26.6：格式化快捷键注册——action 实例存在（跟随 IDE keymap 的 Reformat Code，
        // 兜底 Ctrl+Alt+L），触发走 [SqlPreviewPanel.formatSql]（与「格式化」按钮同逻辑）
        val panel = SqlPreviewPanel(project)
        assertNotNull("应注册格式化 action", panel.reformatActionForTest())
        panel.clear()
    }

    fun testReformatShortcutFormatsSql() {
        // T-M26.6：触发格式化 action 后 SQL 被格式化（关键字大写 + 子句换行）
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "select id from tbl", compact = "select id from tbl"))
        panel.reformatActionForTest().actionPerformed(
            AnActionEvent.createFromAnAction(panel.reformatActionForTest(), null, "test", DataContext.EMPTY_CONTEXT),
        )
        val text = panel.editorForTest()!!.document.text
        assertTrue("格式化后关键字应大写：" + text, text.contains("SELECT"))
        assertTrue("格式化后应含换行：" + text, text.contains("\n"))
        panel.clear()
    }

    fun testVerticalButtonPresentAndFormatsInsert() {
        // T-VStack：竖向排列按钮在位（文案取 panel.vertical），formatVertical 对 INSERT 竖排 + 配对标注
        val panel = SqlPreviewPanel(project)
        val expectedText = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.vertical")
        val button = com.intellij.util.ui.UIUtil.uiTraverser(panel)
            .preOrderDfsTraversal()
            .filterIsInstance<JButton>()
            .firstOrNull { it.text == expectedText }
        assertNotNull("工具栏应存在竖向排列按钮", button)
        panel.showResult(
            result(
                sql = "insert into `user` (username, password) values ('zhangsna', '123456')",
                compact = "insert into `user` (username, password) values ('zhangsna', '123456')",
            ),
        )
        panel.formatVertical()
        val text = panel.editorForTest()!!.document.text
        assertEquals(
            "INSERT 应竖排且值行尾标注列名：$text",
            """
            INSERT
            INTO `user`
            (
              username,
              password
            )
            VALUES
            (
              'zhangsna', -- username
              '123456'    -- password
            )
            """.trimIndent(),
            text,
        )
        panel.clear()
    }

    fun testVerticalNonInsertFallsBackToFormat() {
        // T-VStack·扩面 + InAll：非 INSERT 输入 = format + 横向组展开（多元素 IN 列表一律纵向化，
        // 与格式化按钮可区分；单元素形态保持同行）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(sql = "select id from tbl where id in (1, 2, 3, 4, 5)", compact = "select id from tbl"),
        )
        panel.formatVertical()
        val text = panel.editorForTest()!!.document.text
        assertTrue("SELECT 应按 format 全展开：$text", text.contains("SELECT\n") && text.contains("FROM tbl"))
        assertTrue("长 IN 列表应纵向化：$text", text.contains("IN\n"))
        panel.clear()

        val panel2 = SqlPreviewPanel(project)
        panel2.showResult(
            result(sql = "select id from tbl where id in (1, 2)", compact = "select id from tbl"),
        )
        panel2.formatVertical()
        val short = panel2.editorForTest()!!.document.text
        assertTrue("2 元素 IN 也应纵向化：$short", short.contains("IN\n"))
        panel2.clear()
    }

    fun testVerticalAnnotationFollowsSetting() {
        // T-VPairAnno：设置关闭 → 竖排保持但行尾无配对标注；恢复开启（默认）→ 标注回来
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val sql = "insert into `user` (username, password) values ('zhangsna', '123456')"
        try {
            settings.verticalPairAnnotation = false
            val panel = SqlPreviewPanel(project)
            panel.showResult(result(sql = sql, compact = sql))
            panel.formatVertical()
            val off = panel.editorForTest()!!.document.text
            assertTrue("竖排形态应保持：$off", off.contains("VALUES\n(") && off.contains("'zhangsna',"))
            assertFalse("行尾不应有配对标注：$off", off.contains("-- username"))
            panel.clear()

            settings.verticalPairAnnotation = true
            val panel2 = SqlPreviewPanel(project)
            panel2.showResult(result(sql = sql, compact = sql))
            panel2.formatVertical()
            val on = panel2.editorForTest()!!.document.text
            assertTrue("恢复开启后应重新标注：$on", on.contains("-- username") && on.contains("-- password"))
            panel2.clear()
        } finally {
            settings.verticalPairAnnotation = true
        }
    }

    // ------------------------------------------------------------------
    // T-PreviewDisplayMode：显示模式在重渲染后保持（用户实测：输入参数后竖排消失）
    // ------------------------------------------------------------------

    fun testVerticalModeSurvivesParamChange() {
        // 用户复现路径：点竖向排列 → 左侧填参数 → 形态必须保持（旧实现：文档被重建抹平）
        val panel = SqlPreviewPanel(project)
        val sql = "insert into `user` (username, password, age) values ('zhangsna', '123456', ?)"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("age")))
        panel.formatVertical()
        assertEquals(SqlPreviewPanel.PreviewDisplayMode.VERTICAL, panel.displayModeForTest())
        val vertical = panel.editorForTest()!!.document.text
        assertTrue("竖排应先生效：$vertical", vertical.contains("-- username") && vertical.contains("VALUES\n("))

        // 模拟用户在参数行输入（DocumentListener → onParamChanged → renderSql 全量重建）
        panel.paramFieldForTest(0)!!.text = "1"

        val after = panel.editorForTest()!!.document.text
        assertTrue("参数变更后应仍为竖排：$after", after.contains("-- username") && after.contains("VALUES\n("))
        assertTrue("参数应已回填：$after", after.contains("1"))
        panel.clear()
    }

    fun testKeywordWrapModeSurvivesParamChange() {
        // 关键字换行同缺陷同修复：参数变更后仍为「子句顶格 + 内容同行」形态（非 format 全展开）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("phone", "type")))
        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.KEYWORD_WRAP)
        assertEquals(
            "SELECT id\nFROM tbl\nWHERE phone = ?  AND type = ?",
            panel.editorForTest()!!.document.text,
        )

        panel.paramFieldForTest(0)!!.text = "abc"

        assertEquals(
            "参数变更后应仍为关键字换行形态",
            "SELECT id\nFROM tbl\nWHERE phone = 'abc'  AND type = ?",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    fun testFormatModeSurvivesParamChangeWhenSettingOff() {
        // 条件性丢失分支：设置「SQL 格式化输出」关闭时，点格式化后的形态此前会被参数变更抹平
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val backup = state.formatSql
        try {
            state.formatSql = false
            val panel = SqlPreviewPanel(project)
            val sql = "SELECT id FROM tbl WHERE phone = ?"
            panel.showResult(result(sql = sql, compact = sql, params = listOf("phone")))
            panel.formatSql()
            assertEquals(SqlPreviewPanel.PreviewDisplayMode.FORMAT, panel.displayModeForTest())
            val formatted = panel.editorForTest()!!.document.text
            assertTrue("FORMAT 模式应强制格式化：$formatted", formatted.contains("SELECT\n"))

            panel.paramFieldForTest(0)!!.text = "abc"

            val after = panel.editorForTest()!!.document.text
            assertTrue("设置关闭时格式化形态也应保持：$after", after.contains("SELECT\n"))
            assertTrue("参数应已回填：$after", after.contains("'abc'"))
            panel.clear()
        } finally {
            runCatching { state.formatSql = backup }
        }
    }

    fun testCompactTakesPrecedenceOverDisplayMode() {
        // 压缩优先级最高：压缩态下模式不套用（保持单行）；切回多行后既有模式恢复
        val panel = SqlPreviewPanel(project)
        val sql = "insert into `user` (username, age) values ('zhangsna', ?)"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("age")))
        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.VERTICAL)

        panel.toggleCompact()
        val compact = panel.editorForTest()!!.document.text
        assertFalse("压缩态应为单行：$compact", compact.contains("\n"))
        assertEquals("压缩不应改变模式状态", SqlPreviewPanel.PreviewDisplayMode.VERTICAL, panel.displayModeForTest())

        panel.toggleCompact()
        assertTrue("切回多行后竖排应恢复", panel.editorForTest()!!.document.text.contains("-- username"))
        panel.clear()
    }

    fun testSetDisplayModeExitsCompact() {
        // 压缩态点格式族按钮：一并退出压缩（否则模式被短路、点击无可见效果）
        val panel = SqlPreviewPanel(project)
        val sql = "insert into `user` (username, age) values ('zhangsna', ?)"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("age")))
        panel.toggleCompact()
        assertTrue("前置：应处于压缩态", panel.compactForTest())

        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.VERTICAL)

        assertFalse("格式族模式切换应退出压缩", panel.compactForTest())
        assertTrue("应显示竖排形态", panel.editorForTest()!!.document.text.contains("-- username"))
        panel.clear()
    }

    fun testRefreshResetsDisplayMode() {
        // 刷新 = 回到默认状态（M19.3）：显示模式一并复位为 NORMAL（设置派生形态）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(sql = "select id from tbl where id in (1, 2, 3, 4, 5)", compact = "select id from tbl"),
        )
        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.VERTICAL)
        assertTrue("前置：应已竖排", panel.editorForTest()!!.document.text.contains("IN\n"))

        panel.refreshView()

        assertEquals(SqlPreviewPanel.PreviewDisplayMode.NORMAL, panel.displayModeForTest())
        panel.clear()
    }

    fun testDisplayModeSurvivesConditionToggle() {
        // 另一重建入口：动态条件勾选（renderSql 直调）同样不应抹掉竖排。
        // 断言物用 ORDER BY 多字段——竖排把它拆成逐行（format 保持同行），可区分模式是否真在生效
        val panel = SqlPreviewPanel(project)
        val statement = SelectStatement(
            table = tableInfo(),
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                    toggle = ConditionToggle("c0", "true", true, "id = 1")),
            ),
            orderBy = listOf(
                OrderByItem(ColumnRef(columnName = "id"), asc = true),
                OrderByItem(ColumnRef(columnName = "name"), asc = false),
            ),
        )
        val model = SqlPreviewModel(
            statement, MySqlDialect, formatSql = true,
            conditionViews = listOf(ConditionView("c0", "id = 1", "true", true, null)),
        )
        panel.showResult(
            PreviewResult(
                sql = "SELECT id, name\nFROM car_device_info",
                compactSql = "SELECT id, name FROM car_device_info",
                unresolvedParams = emptyList(),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "car_device_info",
                columns = listOf("id", "name"),
                previewModel = model,
            ),
        )
        panel.setDisplayMode(SqlPreviewPanel.PreviewDisplayMode.VERTICAL)
        assertTrue("前置：ORDER BY 应已逐行", panel.editorForTest()!!.document.text.contains("ORDER BY\n"))

        panel.conditionCheckboxForTest(0)!!.doClick()

        val after = panel.editorForTest()!!.document.text
        assertEquals("勾选重建后模式不应丢失", SqlPreviewPanel.PreviewDisplayMode.VERTICAL, panel.displayModeForTest())
        assertTrue("勾选重建后 ORDER BY 应仍逐行：$after", after.contains("ORDER BY\n"))
        panel.clear()
    }

    fun testActionButtonGroupsSwitchByScene() {
        // T-M26.3：按钮组按场景构建——无内容无按钮、动态条件 → 条件组（标题行右侧）、
        // 批量 → 批量组、clear 复位（字段置 null）
        val panel = SqlPreviewPanel(project)
        // 无参数 → 无按钮
        panel.showResult(result())
        assertNull("无内容时应无全选按钮", panel.toggleSelectButtonForTest())
        assertNull("无内容时应无批量全选按钮", panel.batchToggleSelectButtonForTest())
        // 动态条件 → 条件组
        panel.showResult(
            dynamicResult(
                conditions = listOf(
                    Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"),
                        toggle = ConditionToggle("c0", "true", true, "id = 1")),
                ),
                views = listOf(ConditionView("c0", "id = 1", "true", true, null)),
            ),
        )
        assertNotNull("动态条件场景应有全选按钮", panel.toggleSelectButtonForTest())
        assertNull("动态条件场景不应有批量全选按钮", panel.batchToggleSelectButtonForTest())
        // 批量列 → 批量组
        panel.showResult(
            batchResult(
                BatchModel(
                    kind = BatchKind.INSERT,
                    tableName = "biz_store_coupon",
                    toggleColumns = listOf(
                        BatchColumn("name", "entity.name", quote = true, checked = true),
                    ),
                ),
            ),
        )
        assertNull("批量场景不应有全选按钮", panel.toggleSelectButtonForTest())
        assertNotNull("批量场景应有批量全选按钮", panel.batchToggleSelectButtonForTest())
        panel.clear()
        assertNull("clear 后应无全选按钮", panel.toggleSelectButtonForTest())
        assertNull("clear 后应无批量全选按钮", panel.batchToggleSelectButtonForTest())
    }

    // ------------------------------------------------------------------
    // T-M27.1：SQL 面板补全触发（用户实测：配置数据源后 XML 有补全、面板无补全——
    // 根因：编辑器组件链无 DataProvider（2025.3 平台编辑器改 UiCompatibleDataProvider，
    // 传统 getDataContext 沿父链只认 DataProvider）→ Ctrl+Space 的 action 拿不到 EDITOR
    // 静默失败。修复：挂载时包装 DataProvider 提供 EDITOR）
    // ------------------------------------------------------------------

    fun testPanelEditorWrapsDataProviderForEditor() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器", editor)
        // 组件树中应存在提供 EDITOR 的 DataProvider（Ctrl+Space action 的 DataContext 来源）
        val provider = findDataProvider(panel)
        assertNotNull("编辑器组件链应有 DataProvider 包装", provider)
        assertEquals("DataProvider 应提供当前编辑器", editor, provider!!.getData(CommonDataKeys.EDITOR.name))
        panel.clear()
    }

    fun testPanelEditorCompletionOffersKeywordsAndColumns() {
        if (Language.findLanguageByID("SQL") == null) return // 测试环境无 SQL 语言（DatabaseTools 未加载），真实 IDE 才执行补全断言
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()!!
        // 表名位：FROM 后（空格后空前缀 → 全量命中；测试环境 HeadlessDataManager 使 DataContext
        // 路径不可模拟，直接调平台补全处理器 = 编辑器内 Ctrl+Space 的最终路径）
        editor.caretModel.moveToOffset(editor.document.text.indexOf("FROM") + 5)
        val handler = CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
        handler.invokeCompletion(project, editor, 1, false)
        val lookup = LookupManager.getInstance(project).getActiveLookup()
        assertNotNull("Ctrl+Space 应弹出补全（实际为 null——补全链路失效）", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("补全应含关键字 SELECT：$items", items.contains("SELECT"))
        assertTrue("补全应含关键字 DISTINCT：$items", items.contains("DISTINCT"))
        assertTrue("FROM 后应含表名 tbl：$items", items.contains("tbl"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()

        // 列名位：WHERE 后（空格后空前缀）
        val panel2 = SqlPreviewPanel(project)
        panel2.showResult(result(sql = "SELECT id FROM tbl WHERE ", compact = "SELECT id FROM tbl"))
        val editor2 = panel2.editorForTest()!!
        editor2.caretModel.moveToOffset(editor2.document.text.indexOf("WHERE") + 6)
        CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true).invokeCompletion(project, editor2, 1, false)
        val lookup2 = LookupManager.getInstance(project).getActiveLookup()
        assertNotNull("WHERE 后应弹出补全", lookup2)
        val items2 = lookup2!!.items.map { it.lookupString }
        assertTrue("WHERE 后应含列名 id：$items2", items2.contains("id"))
        assertTrue("WHERE 后应含列名 name：$items2", items2.contains("name"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel2.clear()
    }

    /** 沿组件树找 DataProvider（编辑器挂载包装）。 */
    private fun findDataProvider(component: java.awt.Component): DataProvider? {
        if (component is DataProvider) return component
        if (component is java.awt.Container) {
            for (c in component.components) {
                findDataProvider(c)?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // T-M27.2：面板输入自动弹出补全（用户实测：输入字符无自动提示——平台 autopopup 对
    // 非文件编辑器显示空弹窗且不调度 contributor，面板自建：文档监听 + 手动模式补全处理器）
    // ------------------------------------------------------------------

    fun testTypingTriggersAutoPopup() {
        if (Language.findLanguageByID("SQL") == null) return // 测试环境无 SQL 语言（DatabaseTools 未加载），真实 IDE 才执行补全断言
        val panel = SqlPreviewPanel(project)
        // SQL 末尾空白处输入（新词起始，前缀 S 命中 SELECT）
        panel.showResult(result(sql = "SELECT id FROM ", compact = "SELECT id FROM tbl"))
        val editor = panel.editorForTest()!!
        // 格式化后 SQL 末尾无空格（format 的 trim）——先补空格再输入（模拟真实输入位置，避免连词）
        val insertOffset = editor.document.textLength
        editor.caretModel.moveToOffset(insertOffset)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(insertOffset, " S")
        }
        // 真实输入后 caret 自动在字符后（insertString 不移动 caret，测试手动模拟）
        editor.caretModel.moveToOffset(insertOffset + 2)
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        var round = 0
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            lookup = LookupManager.getInstance(project).getActiveLookup()
            round++
        }
        assertNotNull("输入字符后应自动弹出补全（实际为 null——自建 autopopup 失效）", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("补全应含关键字 SELECT：$items", items.contains("SELECT"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()
    }

    fun testTypingTriggersAutoPopupXmlScene() {
        if (Language.findLanguageByID("SQL") == null) return // 测试环境无 SQL 语言（DatabaseTools 未加载），真实 IDE 才执行补全断言
        // XML 预览场景：tableName/columns 为空（XmlPreviewService 不关联实体）——
        // 关键字补全应仍弹出（预览补全不依赖实体上下文）
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "SELECT id FROM ", compact = "SELECT id FROM tbl", tableName = null, columns = emptyList()))
        val editor = panel.editorForTest()!!
        // 格式化后 SQL 末尾无空格（format 的 trim）——先补空格再输入（模拟真实输入位置，避免连词）
        val insertOffset = editor.document.textLength
        editor.caretModel.moveToOffset(insertOffset)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(insertOffset, " S")
        }
        editor.caretModel.moveToOffset(insertOffset + 2)
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        var round = 0
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            lookup = LookupManager.getInstance(project).getActiveLookup()
            round++
        }
        assertNotNull("XML 场景（无实体上下文）输入字符后也应自动弹出补全", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("XML 场景应含关键字 SELECT：$items", items.contains("SELECT"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()
    }

    fun testTypingTriggersAutoPopupXmlSceneWithEntity() {
        if (Language.findLanguageByID("SQL") == null) return // 测试环境无 SQL 语言（DatabaseTools 未加载），真实 IDE 才执行补全断言
        // T-M27.3：XML 预览注入实体上下文后——输入应弹**列名**（不止关键字）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(
                sql = "SELECT i FROM ",
                compact = "SELECT i FROM tbl",
                columns = listOf("id", "name"),
                tableName = null,
            ),
        )
        val editor = panel.editorForTest()!!
        // 格式化后 SQL 末尾无空格（format 的 trim）——先补空格再输入（模拟真实输入位置，避免连词）
        val insertOffset = editor.document.textLength
        editor.caretModel.moveToOffset(insertOffset)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(insertOffset, " S")
        }
        editor.caretModel.moveToOffset(insertOffset + 2)
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        var round = 0
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            lookup = LookupManager.getInstance(project).getActiveLookup()
            round++
        }
        assertNotNull("XML 预览（注入实体）输入字符后应弹出补全", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("应含关键字 SELECT：$items", items.contains("SELECT"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()
    }

    fun testRenderAutoFormatsSql() {
        // T-M27.4：渲染自动格式化（设置开启）——关键字大写/主子句顶格/列清单换行/空行归一
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(
                sql = "select  id ,name  from t  where id=1",
                compact = "select id,name from t where id=1",
            ),
        )
        val doc = panel.editorForTest()!!.document.text
        // T-M29.2：列清单逗号保留（原实现顶层逗号丢失 → 非法 SQL）
        assertEquals(
            "SELECT\n    id,\n    name\nFROM t\nWHERE id = 1",
            doc,
        )
        panel.clear()
        panel.clear()
    }

    /**
     * T-PreviewConsoleFmt：控制台文档（= exec 文件文档）持有预览原文（裸单行）时，
     * 渲染链必须把**格式化文本**回填进该文档——真机日志实证：面板自建编辑器拿到 16 行格式化
     * 文本（len=274），但控制台挂载读的是文件内容（预热写的预览原文，1 行 len=231），挂载后
     * 无重渲染 → 用户看到裸单行 SQL。本用例钉死「渲染回填 nativeDoc」这一环（触发点见
     * prepareDefaultConsole 的 onConsole 回填，真机由 embedConsole 后 invokeLater 执行）。
     */
    fun testRenderRepublishesFormattedTextIntoNativeConsoleDoc() {
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(
                sql = "select  id ,name  from t  where id=1",
                compact = "select id,name from t where id=1",
            ),
        )
        // 模拟控制台就绪且已嵌入本面板（showSql 的 nativeDoc 分支要求 consoleAttachedToMe）
        panel.embedConsoleForTest(javax.swing.JPanel())
        assertTrue("前置：控制台应视为已挂到本面板", panel.consoleAttachedToMeForTest())
        // 控制台文档持有「预览原文」裸单行（挂载时 DBN 读的正是文件内容）
        val consoleDoc = com.intellij.openapi.editor.EditorFactory.getInstance()
            .createDocument("select  id ,name  from t  where id=1")
        panel.markNativeDocForTest(consoleDoc)

        panel.renderSqlForTest() // = 挂载后的回填渲染

        assertEquals(
            "控制台文档应被回填为格式化文本（否则控制台显示裸单行）",
            "SELECT\n    id,\n    name\nFROM t\nWHERE id = 1",
            consoleDoc.text,
        )
        panel.clear()
    }

    fun testTableNameCompletionInWhereClauseWithCandidates() {        if (Language.findLanguageByID("SQL") == null) return // 测试环境无 SQL 语言（DatabaseTools 未加载），真实 IDE 才执行补全断言
        // T-M27.4：表名全位置提示——WHERE 后输入表名前缀也提示（主表 + 联表候选），
        // 不再限定 FROM/JOIN 后（输入 me → merchant_entity 命中）
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            result(
                sql = "SELECT id FROM t WHERE ",
                compact = "SELECT id FROM t WHERE ",
                tableName = "t",
                columns = listOf("id"),
                candidateTables = listOf(TableInfo("MerchantEntity", "merchant_entity", emptyList())),
            ),
        )
        val editor = panel.editorForTest()!!
        val insertOffset = editor.document.textLength
        editor.caretModel.moveToOffset(insertOffset)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(insertOffset, "me")
        }
        editor.caretModel.moveToOffset(insertOffset + 2)
        var lookup = LookupManager.getInstance(project).getActiveLookup()
        var round = 0
        while (lookup == null && round < 30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
            lookup = LookupManager.getInstance(project).getActiveLookup()
            round++
        }
        assertNotNull("WHERE 后输入表名前缀应弹出补全", lookup)
        val items = lookup!!.items.map { it.lookupString }
        assertTrue("应含联表候选表名 merchant_entity：$items", items.contains("merchant_entity"))
        LookupManager.getInstance(project).hideActiveLookup()
        panel.clear()
    }

    // ---------- T-AutoFold F4-a：showSql 程序性折叠 ----------

    private fun withAutoFold(enabled: Boolean, block: () -> Unit) {
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val oldEnabled = state.autoFoldEnabled
        val oldThreshold = state.autoFoldFieldThreshold
        try {
            state.autoFoldEnabled = enabled
            state.autoFoldFieldThreshold = 3
            block()
        } finally {
            state.autoFoldEnabled = oldEnabled
            state.autoFoldFieldThreshold = oldThreshold
        }
    }

    private fun ownFoldRegions(editor: com.intellij.openapi.editor.Editor) =
        editor.foldingModel.allFoldRegions.filter { it.placeholderText.startsWith("…") }

    fun test开启自动折叠后showResult产生折叠区域() {
        withAutoFold(enabled = true) {
            val panel = SqlPreviewPanel(project)
            panel.showResult(
                result(
                    sql = "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t",
                    compact = "SELECT a1, a2, a3, a4 FROM t",
                ),
            )
            val editor = panel.editorForTest()!!
            val regions = ownFoldRegions(editor)
            assertEquals("showSql 后应有本方折叠区域", 1, regions.size)
            assertFalse("区域应收起", regions[0].isExpanded)
            panel.clear()
        }
    }

    fun test关闭自动折叠后showResult零区域() {
        withAutoFold(enabled = false) {
            val panel = SqlPreviewPanel(project)
            panel.showResult(
                result(
                    sql = "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t",
                    compact = "SELECT a1, a2, a3, a4 FROM t",
                ),
            )
            assertEquals("关闭态零折叠区域（逐字节不变）", 0, ownFoldRegions(panel.editorForTest()!!).size)
            panel.clear()
        }
    }

    // ---------- T-Perf-04：detectInClauseParams 单趟扫描（与旧 O(L×P) 实现语义一致） ----------

    fun testDetectInClauseParams_InParens多参数命中() {
        val panel = SqlPreviewPanel(project)
        try {
            // 语义与旧实现（对每个 ? 前缀做 endsWith("IN ("/"IN(")）完全一致：
            // 仅紧贴 "IN (" 的首个 ? 命中（后续 ? 的前缀以 "?," 结尾不命中）。
            val detected = panel.detectInClauseParams("SELECT * FROM t WHERE id IN ( ?, ?, ? ) AND name = ?")
            assertEquals(setOf(0), detected)
        } finally {
            panel.dispose()
        }
    }

    fun testDetectInClauseParams_紧凑形态与大小写() {
        val panel = SqlPreviewPanel(project)
        try {
            assertEquals(setOf(0), panel.detectInClauseParams("select * from t where id in(?)"))
        } finally {
            panel.dispose()
        }
    }

    fun testDetectInClauseParams_非In问号不命中() {
        val panel = SqlPreviewPanel(project)
        try {
            assertEquals(emptySet<Int>(), panel.detectInClauseParams("SELECT * FROM t WHERE a = ? AND b = ?"))
        } finally {
            panel.dispose()
        }
    }

    fun testDetectInClauseParams_IN与括号间任意空白命中() {
        // O-81 T-XmlListParamSplit（R2）：IN 与 ( 之间换行/制表/多空格均命中——
        // 升级前只容忍恰一个空格，mapper 常见排版 `IN\n(` 漏判 → 集合参数整串单值渲染
        val panel = SqlPreviewPanel(project)
        try {
            assertEquals(setOf(0), panel.detectInClauseParams("SELECT * FROM t WHERE id IN\n(?)"))
            assertEquals(setOf(0), panel.detectInClauseParams("SELECT * FROM t WHERE id IN\t(?)"))
            assertEquals(setOf(0), panel.detectInClauseParams("SELECT * FROM t WHERE id IN  (?)"))
            assertEquals(setOf(1), panel.detectInClauseParams("SELECT * FROM t WHERE a = ? AND b IN (\n?)"))
        } finally {
            panel.dispose()
        }
    }

    /** T-SqlCopyShortcuts：预览面板 SQL 编辑器（双击预览主入口）无选中 Ctrl+C 应复制整条 SQL。 */
    fun test编辑器CtrlC无选中复制整条SQL() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(result())
            val editor = panel.editorForTest()
            assertNotNull("编辑器应在场", editor)
            val comp = editor!!.component
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            val actionKey = comp.inputMap.get(KeyStroke.getKeyStroke("control C"))
            assertNotEquals("应覆盖 JTextComponent 默认 copy 绑定（共享件接线）", "copy", actionKey)
            comp.actionMap.get(actionKey)
                .actionPerformed(ActionEvent(comp, ActionEvent.ACTION_PERFORMED, actionKey.toString()))
            val clip: String? = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
            assertEquals("Ctrl+C 应复制整条预览 SQL", editor.document.text, clip)
        } finally {
            panel.clear()
        }
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
        // NEW-18 后续（2026-10-31）：本类验的是「选区 → 动作启用」语义，不是 Community 判定。
        // headless classpath 不含 database 插件标记类，真实探测恒 false 会让执行类动作一律置灰，
        // 故在此显式声明为可用；Community 置灰语义由 G5 券3 的专项用例覆盖。
        cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.availabilityOverrideForTest = true
    }

    override fun tearDown() {
        cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.availabilityOverrideForTest = null
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
}
