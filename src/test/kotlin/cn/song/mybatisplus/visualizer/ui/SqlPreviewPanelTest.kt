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
 * SqlPreviewPanel 行为测试（M8/M9）：编辑器视图 + 单行多行切换 + 生命周期 + 参数回填。
 *
 * 需要 IDE fixture（EditorFactory/PSI 依赖应用环境），在 EDT 上运行。
 */
class SqlPreviewPanelTest : BasePlatformTestCase() {

    fun testShardingRouteAnnotationReadOnlyCollapsedAndCleared() {
        // T-MagpieGems W2：脱敏语义由 displayMaskMode 承载
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.displayMaskMode = "OFF"
        val panel = SqlPreviewPanel(project)
        com.intellij.openapi.util.Disposer.register(testRootDisposable, panel)
        val actual = "ds_0 ::: SELECT * FROM ui_order_0 WHERE id = ? ::: [100]\n" +
            "ds_1 ::: SELECT * FROM ui_order_1 WHERE id = ? ::: [100]"
        // 无参数早退也必须可见；控件与执行文本分离，切换条目不留旧路由。
        panel.showResult(result().copy(actualSql = actual))
        val fold = UIUtil.uiTraverser(panel).filter(com.intellij.ui.HideableTitledPanel::class.java)
            .toList().single { it.name == "shardingActualSqlFold" }
        assertFalse(fold.isExpanded())
        fold.setOn(true)
        val text = UIUtil.uiTraverser(panel).filter(javax.swing.JTextArea::class.java)
            .toList().single { it.name == "shardingActualSqlText" }
        assertEquals(actual, text.text)
        assertFalse(text.isEditable)
        assertNotNull(panel.lastRenderedSqlForTest())
        assertFalse(panel.lastRenderedSqlForTest()!!.contains("ds_0"))
        panel.showResult(result())
        assertTrue(UIUtil.uiTraverser(panel).filter(com.intellij.ui.HideableTitledPanel::class.java)
            .toList().none { it.name == "shardingActualSqlFold" })
        panel.clear()
    }

    fun testShardingRouteDisplayMaskOnOffAndLiveSettingsChange() {
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val original = settings.displayMaskMode
        val actual = "private_ds ::: SELECT * FROM physical_order WHERE code = ? ::: [Secret001, BOOK,100]"
        val preview = result().copy(actualSql = actual)
        try {
            for (initialMask in listOf("MASK", "OFF")) {
                settings.displayMaskMode = initialMask
                val panel = SqlPreviewPanel(project)
                com.intellij.openapi.util.Disposer.register(testRootDisposable, panel)
                panel.showResult(preview)
                val fold = UIUtil.uiTraverser(panel).filter(com.intellij.ui.HideableTitledPanel::class.java)
                    .toList().single { it.name == "shardingActualSqlFold" }
                fold.setOn(true)
                val text = UIUtil.uiTraverser(panel).filter(javax.swing.JTextArea::class.java)
                    .toList().single { it.name == "shardingActualSqlText" }
                fun assertProjection(mask: String) {
                    val expected = if (mask == "MASK") MpSqlVisualizerBundle.message("log.preview.shardingRoutesMasked") else actual
                    assertEquals(expected, text.text)
                    assertFalse(text.isEditable)
                    text.selectAll()
                    assertEquals(expected, text.selectedText)
                    if (mask == "MASK") {
                        for (secret in listOf("Secret001", "BOOK,100", "private_ds", "physical_order")) {
                            assertFalse(text.text.contains(secret))
                            assertFalse(text.selectedText.contains(secret))
                            assertFalse(text.toolTipText.orEmpty().contains(secret))
                        }
                    }
                    assertEquals("展示投影不得改原始预览数据", actual, preview.actualSql)
                }
                assertProjection(initialMask)
                // 同一个已展开面板/同一个文本组件：模式变更即时清掉旧选区及可复制原文。
                for (mask in listOf(if (initialMask == "MASK") "OFF" else "MASK", initialMask, "MASK")) {
                    settings.displayMaskMode = mask
                    com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
                        .syncPublisher(cn.song.mybatisplus.visualizer.config.PluginSettings.SETTINGS_CHANGE_TOPIC)
                        .settingsChanged()
                    assertNull(text.selectedText)
                    assertProjection(mask)
                    assertTrue(fold.isExpanded())
                }
                panel.clear()
            }
        } finally {
            settings.displayMaskMode = original
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

    fun testShowResultCreatesEditorView() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器视图", editor)
        assertEquals("SELECT id\nFROM tbl", editor!!.document.text)
        panel.clear()
    }

    fun testShowResultReplacesEditor() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = "SELECT 1 FROM t1", compact = "SELECT 1 FROM t1"))
        val first = panel.editorForTest()
        panel.showResult(result(sql = "SELECT 2 FROM t2", compact = "SELECT 2 FROM t2"))
        val second = panel.editorForTest()
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("SELECT 2 FROM t2", second!!.document.text)
        panel.clear()
    }

    /**
     * T-F8：含 JOIN 的预览结果（驱动 updateDiagramTab → 后台 schema 富化 → 回 EDT 渲染）。
     *
     * @param mainTable     主表元数据；T-TS-FixA 用它注入「列清单取 size 即抛」的替身，触发
     *                      [cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator.buildDiagramModel]
     *                      的异常路径
     * @param selectColumns 显式 SELECT 列（默认空 = `SELECT *` 语义）
     */
    private fun joinResult(
        mainTable: TableInfo = tableInfo(),
        selectColumns: List<ColumnRef> = emptyList(),
    ): PreviewResult {
        val statement = SelectStatement(
            table = mainTable,
            selectColumns = selectColumns,
            tableAlias = "u",
            joins = listOf(
                JoinClause(
                    type = JoinType.LEFT,
                    tableName = "user_address",
                    alias = "a",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            left = ColumnRef(tableAlias = "u", columnName = "id"),
                            right = ColumnRef(tableAlias = "a", columnName = "user_id"),
                        ),
                    ),
                ),
            ),
        )
        val sql = "SELECT u.id, u.name FROM car_device_info u " +
            "LEFT JOIN user_address a ON u.id = a.user_id"
        return PreviewResult(
            sql = sql,
            compactSql = sql,
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = WrapperType.LAMBDA_QUERY,
            tableName = "car_device_info",
            columns = listOf("id", "name"),
            previewModel = SqlPreviewModel(
                statement, MySqlDialect, formatSql = false, conditionViews = emptyList(),
            ),
        )
    }

    /**
     * T-F8 接线守卫：JOIN 图加载态必须包住「进后台富化 → 回 EDT 渲染 → 收尾」整段。
     *
     * 审计项 F8 的形态是 `DiagramPanel.showLoading/hideLoading` 全仓零调用点——T-UN-14 接的
     * JBLoadingPanel 转圈永远不会出现。本用例钉两件事：
     *  ① 探针序列为「进入 → 退出」（修复前为空序列，即红）；
     *  ② 退出时图**已换入**（停转圈若早于渲染，用户在唯一的耗时段里看不到任何反馈）。
     *     「已换入」按两条渲染路径取或：原生图路径（Diagrams 插件在测试沙盒里已加载 →
     *     [SqlPreviewPanel.hasNativeDiagramForTest]，与真机默认配置同路径）或自绘路径
     *     （[DiagramPanel.hasModelForTest]）。
     *
     * 富化在 `executeOnPooledThread` 上、结果经 `invokeLater` 回 EDT，故需排空 EDT 队列收口；
     * 有界等待避免测试环境异常时挂死。
     */
    fun testDiagramLoadingWrapsBackgroundEnrichment() {
        val panel = SqlPreviewPanel(project)
        val events = mutableListOf<Boolean>()
        var renderedWhenStopped = false
        panel.diagramLoadingProbeForTest { loading ->
            events += loading
            if (!loading) {
                renderedWhenStopped = panel.hasNativeDiagramForTest() ||
                    panel.diagramPanelForTest().hasModelForTest()
            }
        }
        panel.showResult(joinResult())
        val deadline = System.currentTimeMillis() + 10_000
        while (events.size < 2 && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        assertEquals("加载态序列应为 进入 → 退出", listOf(true, false), events)
        assertTrue(
            "停转圈必须发生在图已换入之后（提前摘则富化窗口内零反馈；" +
                "native=${panel.hasNativeDiagramForTest()}, rendered=${panel.diagramPanelForTest().hasModelForTest()}）",
            renderedWhenStopped,
        )
        panel.clear()
        assertFalse("整图卸载后不得卡转圈", panel.diagramPanelForTest().isLoadingForTest())
    }

    /**
     * T-TS-05 第二层失效守卫（一）：JOIN 图标签页的内容**必须**是宿主加载面板。
     *
     * 为什么单独立项：`testDiagramLoadingWrapsBackgroundEnrichment` 断的「进/出序列 + 退出时图已换入」
     * 在「把 addTab 目标改回 `diagramTabHost`」之后**仍然全绿**——探针挂在 [DiagramPanel] 的显式 API 上，
     * 与转圈画在哪一层无关。而真机默认原生图路径下 `showJoinDiagram` 的 `diagramTabHost.removeAll()`
     * 会把图面板摘出层级，加载面不在标签页内容里的那一刻，转圈就再也画不出来（F8 的原始形态）。
     */
    fun testDiagramTabContentIsHostLoadingPanel() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(joinResult())
        val host = panel.diagramLoadingHostForTest()
        val tabPane = panel.contentTabPaneForTest()
        assertNotNull("JOIN 预览应切到标签页模式（否则没有加载面可挂）", tabPane)
        val tabContents = (0 until tabPane!!.tabCount).map { tabPane.getComponentAt(it) }
        assertTrue(
            "JOIN 图标签页内容必须是宿主加载面板（实际标签页内容=$tabContents）；" +
                "改回 diagramTabHost 则原生图路径下转圈被 removeAll 摘出层级、永不出现",
            tabContents.any { it === host },
        )
        assertTrue("加载窗口内宿主层应在转（宿主即真机默认路径下唯一可见的加载面）", host.isLoading)
        panel.clear()
    }

    /**
     * T-TS-05 第二层失效守卫（二）：图面板的出圈层**必须**是宿主加载面板。
     *
     * 与用例（一）互补：`isLoadingForTest` 读的是委托结果，删掉 [DiagramPanel.bindHostLoadingPanel]
     * 注入后它照样自洽地反映面板内部 `contentLayer`——单看它为真断不出「转圈画在了哪一层」。
     * 故这里按对象身份断「出圈层 === 宿主」，让注入被删这件事必红。
     */
    fun testDiagramPanelLoadingTargetIsHostLoadingPanel() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(joinResult())
        val host = panel.diagramLoadingHostForTest()
        assertSame(
            "图面板出圈层必须是宿主加载面板（删掉 bindHostLoadingPanel 注入即回落到面板内部层——" +
                "原生图路径下该层被 removeAll 摘出层级，转圈不可见）",
            host,
            panel.diagramPanelForTest().loadingTargetForTest(),
        )
        assertTrue(
            "面板加载态 API 必须反映宿主层（isLoadingForTest 断的是委托结果，宿主侧同步为真）",
            host.isLoading && panel.diagramPanelForTest().isLoadingForTest(),
        )
        panel.clear()
    }

    /**
     * T-TS-05 异常兜底：建图抛异常时转圈必须被摘下。
     *
     * 原实现是「顺序执行 + 末尾摘转圈」：`JoinDiagramGenerator.buildDiagramModel` 与原生框架建图
     * 都在 try 之外/无 finally 保护，一旦抛异常（原生图反射链、文件编辑器创建、自绘测量），
     * 摘转圈那行直接被跳过——用户看到的是转圈永远留在宿主层；后台路径上异常还会让 pooled 线程
     * 终止、`invokeLater` 永不投递，收尾方（`showJoinDiagram`）根本不会被调用。
     * 修复 = `try { … } finally { hideLoading() }` + buildDiagramModel 移入 try + 调用方带日志兜底。
     */
    fun testDiagramRenderFailureClearsLoading() {
        val panel = SqlPreviewPanel(project)
        panel.diagramRenderForTest = { throw IllegalStateException("diagram boom") }
        panel.showResult(joinResult())
        val deadline = System.currentTimeMillis() + 10_000
        while (panel.diagramPanelForTest().isLoadingForTest() && System.currentTimeMillis() < deadline) {
            // 修复前建图异常会从 EDT 拍逃逸到事件泵（转圈卡住的同一根因）；本用例要断的是转圈状态，
            // 故吞掉逃逸异常继续排空队列——修复后（finally + 调用方带日志兜底）不会有异常逃逸。
            runCatching { UIUtil.dispatchAllInvocationEvents() }
            Thread.sleep(20)
        }
        assertFalse(
            "建图抛异常后必须摘转圈（原实现异常逃逸会跳过摘转圈那行，转圈永留宿主层）",
            panel.diagramPanelForTest().isLoadingForTest(),
        )
        assertFalse("宿主层同样不得卡转圈", panel.diagramLoadingHostForTest().isLoading)
        panel.clear()
    }

    /**
     * T-TS-FixA：列清单替身——只有 `size` 抛异常，迭代/按索引取值全部委托真实列表。
     *
     * 为什么只让 `size` 抛：[JoinDiagramGenerator.buildDiagramModel] 的首个可能抛点是
     * `pickColumns(tableInfo)` 的 `tableInfo.columns.size`；渲染链上唯一同样读 `size` 的是
     * `SELECT *` 的全列展开（`SqlGenerator` 读 `table.allColumnNames`），故用例显式给出
     * selectColumns 绕开它——其余列清单访问（按列名查字段类型等）走迭代/按名查找，不受影响。
     */
    private fun sizeExplodingColumns(delegate: List<ColumnInfo>): List<ColumnInfo> =
        object : AbstractList<ColumnInfo>() {
            override val size: Int get() = throw IllegalStateException("diagram model boom")
            override fun get(index: Int): ColumnInfo = delegate[index]
            override fun iterator(): Iterator<ColumnInfo> = delegate.iterator()
        }

    /**
     * T-TS-FixA：无 project 的**同步分支**上建图模型抛异常，转圈同样必须被摘下。
     *
     * 与 [testDiagramRenderFailureClearsLoading] 互补——后者走后台富化分支、在 [showJoinDiagram]
     * 内部注入；本用例走 `proj == null` 的同步分支，那里 `buildDiagramModel(stmt)` 是
     * `showJoinDiagram` 的**实参**：实参一抛，方法根本没进入，其 `finally`（唯一收口点）不会执行。
     * 原实现的 catch 只 warn 不摘圈，于是 `showLoading()` 打开的转圈永留宿主层（直到下一次预览
     * 或整图卸载）——两条分支对同一异常的处理不对称，本用例钉住补齐后的行为。
     *
     * 触发走**输入**而非注入缝：给主表列清单塞一个「取 size 即抛」的替身。显式给 selectColumns 是
     * 必需的——渲染拍的列展开（`SqlGenerator` 仅在 `selectColumns` 为空时读 `table.allColumnNames`）
     * 与建图拍读的是**同一个** `size` 访问器，不显式给列清单异常会先落在 renderSql，红的原因就跑偏了。
     */
    fun testDiagramModelBuildFailureWithoutProjectClearsLoading() {
        val panel = SqlPreviewPanel(null)
        panel.showResult(
            joinResult(
                mainTable = tableInfo().copy(columns = sizeExplodingColumns(tableInfo().columns)),
                selectColumns = listOf(ColumnRef(tableAlias = "u", columnName = "id")),
            ),
        )
        // 无 project → 同步分支：showResult 返回时该跑的都跑完了，无需排空 EDT 队列
        assertFalse(
            "无 project 同步分支：buildDiagramModel 抛在 showJoinDiagram 之前、finally 不会执行——" +
                "catch 不摘圈则转圈永留宿主层",
            panel.diagramPanelForTest().isLoadingForTest(),
        )
        assertFalse("宿主层同样不得卡转圈", panel.diagramLoadingHostForTest().isLoading)
        panel.clear()
    }

    /**
     * T-PreviewBgFit（问题一守卫）：左侧参数滚动区必须自带面板底色——该区整条链是透明的
     * （paramTopAlign/viewport 非不透明），底色过去由祖先容器提供；T-UiNative P0a 把自绘分栏换成
     * `OnePixelSplitter` 时丢了 `background = UIUtil.getPanelBackground()`，深色主题下参数行下方
     * 直接透出分栏近黑背景（用户实测）。本用例钉死两点：滚动区不透明 + 其背景==平台面板背景；
     * 同时分栏容器也须显式带面板背景（不再依赖子组件自绘）。
     */
    fun testParamsAreaCarriesPanelBackground() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())

        val scroll = panel.paramScrollForTest()
        assertTrue("参数滚动区应不透明（否则空区透出祖先容器底色）", scroll.isOpaque)
        assertEquals(
            "参数滚动区背景应为平台面板背景（主题命名色）",
            com.intellij.util.ui.UIUtil.getPanelBackground(),
            scroll.background,
        )
        val split = panel.splitPaneForTest()
        assertNotNull("应能取到左右分栏容器", split)
        assertEquals(
            "分栏容器应恢复 T-UiNative P0a 丢失的面板背景",
            com.intellij.util.ui.UIUtil.getPanelBackground(),
            split!!.background,
        )
        panel.clear()
    }

    fun testToggleCompactSwitchesSql() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)

        assertTrue("toggleCompact 应返回单行模式", panel.toggleCompact())
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

        panel.toggleCompact()
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)
        panel.clear()
    }

    fun testRefreshResetsModeToSettingsDefault() {
        // M19.3（用户实测：点刷新后 SQL 仍停留在单行模式）：
        // 刷新 = 模式复位为与设置「SQL 格式化输出」对齐（默认多行）——手动切到单行后刷新应回多行
        // （setUp 关闭格式化以保持渲染断言——本测试需默认开启语义，显式恢复）
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        panel.toggleCompact()   // 切到单行
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

        panel.refreshView()
        assertFalse("刷新后应恢复默认多行模式", panel.compactForTest())
        // formatSql=true 时刷新渲染自动格式化
        assertEquals("SELECT\n    id\nFROM tbl", panel.editorForTest()!!.document.text)
        panel.clear()
    }

    fun testCopySqlWithContent() {
        // 无 project 时不弹成功提示（测试环境 Messages 对话框会抛异常）
        val panel = SqlPreviewPanel(null)
        panel.showResult(result())
        assertTrue("有内容时应复制成功", panel.copySql())
        panel.clear()
    }

    fun testCopySqlNotificationOffersClosePanelActionByDefault() {
        // UI-044：单纯「复制 SQL」时通知要挂「关闭 SQL 面板」——用户还在面板里，这是顺手出口。
        val panel = SqlPreviewPanel(null)
        panel.showResult(result())
        val actions = mutableListOf<String>()
        panel.copyNotificationActionsForTest = actions
        try {
            assertTrue(panel.copySql())
            val closeLabel = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("dialog.closeSqlPanel")
            assertTrue("单纯复制时通知应带「关闭 SQL 面板」，实际=$actions", actions.contains(closeLabel))
        } finally {
            panel.copyNotificationActionsForTest = null
            panel.clear()
        }
    }

    fun testCopySqlCanSuppressClosePanelAction() {
        // UI-044：调用方自己就要关面板时（「复制 SQL 并关闭」），通知不得再挂「关闭 SQL 面板」——
        // 弹窗已关，这个链接既冗余又指不到目标。用户真机截图即此形态。
        val panel = SqlPreviewPanel(null)
        panel.showResult(result())
        val actions = mutableListOf<String>()
        panel.copyNotificationActionsForTest = actions
        try {
            assertTrue(panel.copySql(offerClosePanelAction = false))
            val closeLabel = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("dialog.closeSqlPanel")
            assertFalse("复制并关闭时通知不应带「关闭 SQL 面板」，实际=$actions", actions.contains(closeLabel))
        } finally {
            panel.copyNotificationActionsForTest = null
            panel.clear()
        }
    }

    fun testCopySqlClosePanelCallbackInjectable() {
        // T-M23.6：onClosePanelRequested 回调默认 null、可注入（预览弹窗赋值 dispose）；
        // project null → 弹框跳过（回调不触发），注入的回调可被面板外部链路触发
        val panel = SqlPreviewPanel(null)
        assertNull("默认无关闭面板回调", panel.onClosePanelRequested)
        var closed = false
        panel.onClosePanelRequested = { closed = true }
        panel.showResult(result())
        assertTrue("有内容时应复制成功", panel.copySql())
        assertFalse("无 project 弹框跳过，回调不应触发", closed)
        // 链路：点「关闭 SQL 面板」→ onClosePanelRequested?.invoke()
        panel.onClosePanelRequested?.invoke()
        assertTrue("注入的回调应可触发", closed)
        panel.clear()
    }

    fun testClearReleasesEditor() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        assertNotNull(panel.editorForTest())
        panel.clear()
        assertNull("clear 后应释放编辑器", panel.editorForTest())
        assertNull(panel.currentForTest())
    }

    fun testShowResultBuildsParamFields() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(params = listOf("dto.getPhone()", "id")))
        assertEquals(2, panel.paramFieldForTest(1)?.let { 2 } ?: 0)
        assertNotNull("应生成参数输入框", panel.paramFieldForTest(0))
        panel.clear()
    }

    fun testParamRowLabelWidthAdaptsAndTooltipForLongNames() {
        // T-M19.4-01（用户实测：`#productInfo.getDicProtocolType()` 这类长参数名被固定 140px
        // 标签截断显示不全）+ T-M26.3（标签列宽统一：按最长可见标签自适应 140~260，
        // 超宽中段省略 + tooltip 全名）
        val panel = SqlPreviewPanel(project)
        val short = "id"
        val medium = "productInfo.getDicProtocolType()"   // 用户实测场景：约 200px
        val long = "productInfo.getDicProtocolType().getDeviceName().getStatusName().getDetailInfo()"
        panel.showResult(result(params = listOf(short, medium, long)))
        val shortLabel = panel.paramRowLabelForTest(0)!!
        val mediumLabel = panel.paramRowLabelForTest(1)!!
        val longLabel = panel.paramRowLabelForTest(2)!!
        // T-M26.3：同一场景所有参数行共享标签列宽（最长可见标签夹取 140~260）
        val columnWidth = shortLabel.preferredSize.width
        assertTrue("列宽应不小于下限 140", columnWidth >= 140)
        assertTrue("列宽应不超过上限 260", columnWidth <= 260)
        assertEquals("标签列宽应统一", columnWidth, mediumLabel.preferredSize.width)
        assertEquals("标签列宽应统一", columnWidth, longLabel.preferredSize.width)
        // 超长名：截断显示（文本 != 全名）+ tooltip 全名；短/中名不截断、无 tooltip
        assertEquals("超长标签 tooltip 应为全名", "#$long", longLabel.toolTipText)
        assertNotEquals("超长标签应截断显示", "#$long", longLabel.text)
        assertNull("短参数不设 tooltip", shortLabel.toolTipText)
        assertNull("中等长度参数不设 tooltip", mediumLabel.toolTipText)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // M9：参数回填 / 可编辑 / 补全上下文
    // ------------------------------------------------------------------

    fun testParamBackfillUpdatesSql() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("dto.getPhone()", "type")))
        // 输入第一个参数值（字符串）→ SQL 中第一个 ? 加单引号回填
        val field = panel.paramFieldForTest(0)!!
        field.text = "abc"
        assertEquals(
            "SELECT id FROM tbl WHERE phone = 'abc' AND type = ?",
            panel.editorForTest()!!.document.text,
        )
        // 输入第二个（数字）参数值 → 原样
        panel.paramFieldForTest(1)!!.text = "1"
        assertEquals(
            "SELECT id FROM tbl WHERE phone = 'abc' AND type = 1",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    fun testParamBackfillEmptyKeepsPlaceholder() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("phone")))
        // 留空 → 保留 ?
        assertEquals(sql, panel.editorForTest()!!.document.text)
        panel.clear()
    }

    // T-PSG（N-33）：面板回填串感知——引号/注释内的 `?` 不是占位符，不参与回填
    fun testParamBackfillIgnoresQuestionMarkInStringLiteral() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE note = 'a?b' AND phone = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("phone")))
        panel.paramFieldForTest(0)!!.text = "abc"
        assertEquals(
            "引号内 `?` 应保持原样、真占位符按 phone 回填",
            "SELECT id FROM tbl WHERE note = 'a?b' AND phone = 'abc'",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    fun testParamBackfillIgnoresQuestionMarkInComments() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl /* 占?位 */ WHERE phone = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("phone")))
        panel.paramFieldForTest(0)!!.text = "abc"
        assertEquals(
            "块注释内 `?` 应保持原样",
            "SELECT id FROM tbl /* 占?位 */ WHERE phone = 'abc'",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    fun testParamBackfillNormalPathByteIdentical() {
        // T-PSG 验收 1：不含引号/注释内 `?` 的正常 SQL，回填结果与改前逐字节一致（硬编码钉住）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE note = 'hello' AND phone = ? AND age > ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("phone", "age")))
        panel.paramFieldForTest(0)!!.text = "abc"
        panel.paramFieldForTest(1)!!.text = "1"
        assertEquals(
            "SELECT id FROM tbl WHERE note = 'hello' AND phone = 'abc' AND age > 1",
            panel.editorForTest()!!.document.text,
        )
        panel.clear()
    }

    // T-PSG-04：面板回填链的基数对账——位点数与参数条数不等时如实记录（不阻断）
    fun testPlaceholderReconciliationMismatchRecorded() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE a = ? AND b = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("a")))
        assertEquals(
            "位点数(2) != 参数条数(1) → 应如实记录对账标注",
            "-- placeholder/param count mismatch (2 placeholders vs 1 params)",
            panel.placeholderReconciliationForTest(),
        )
        panel.clear()
    }

    fun testPlaceholderReconciliationEqualNoMarker() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("phone")))
        assertNull("位点数与参数条数相等 → 无标注", panel.placeholderReconciliationForTest())
        panel.clear()
    }

    // ------------------------------------------------------------------
    // T-M42：日志双击预览参数识别——initialParamValues 预填（参数行初始值 + SQL 初始渲染）
    // ------------------------------------------------------------------

    fun testInitialParamValuesPrefillFieldAndRender() {
        // 日志识别路径：param1 预填 "admin"（String → 引号提示）——初始显示与现状（值内联 'admin'）一致
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE username = ?"
        panel.showResult(
            PreviewResult(
                sql = sql,
                compactSql = sql,
                unresolvedParams = listOf("param1"),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "tbl",
                paramHints = listOf(ParamHint(quote = true)),
                initialParamValues = mapOf("param1" to "admin"),
            ),
        )
        assertEquals("参数行应预填日志参数值", "admin", panel.paramFieldForTest(0)!!.text)
        assertTrue(
            "SQL 初始渲染应含值内联 'admin'：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("username = 'admin'"),
        )
        panel.clear()
    }

    fun testInitialParamValuesEditableAfterPrefill() {
        // 预填值可编辑：改参数行值 → SQL 实时回填（T-M42 验收：改 admin → 新值同步变化）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE username = ?"
        panel.showResult(
            PreviewResult(
                sql = sql,
                compactSql = sql,
                unresolvedParams = listOf("param1"),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "tbl",
                paramHints = listOf(ParamHint(quote = true)),
                initialParamValues = mapOf("param1" to "admin"),
            ),
        )
        panel.paramFieldForTest(0)!!.text = "zhang"
        assertTrue(
            "修改参数行值后 SQL 应同步回填：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("username = 'zhang'"),
        )
        assertFalse("原值不应残留", panel.editorForTest()!!.document.text.contains("admin"))
        panel.clear()
    }

    fun testInitialParamValuesMissingSourceFallsBackToEmpty() {
        // 预填表缺失的来源回落空串（参数行初始为空 → 渲染保留 `?`）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?"
        panel.showResult(
            PreviewResult(
                sql = sql,
                compactSql = sql,
                unresolvedParams = listOf("param1", "param2"),
                paramTable = null,
                wrapperType = WrapperType.LAMBDA_QUERY,
                tableName = "tbl",
                paramHints = listOf(ParamHint(quote = true), ParamHint(quote = false)),
                initialParamValues = mapOf("param1" to "admin"),
            ),
        )
        assertEquals("param1 应预填", "admin", panel.paramFieldForTest(0)!!.text)
        assertEquals("param2 无预填值应回落空串", "", panel.paramFieldForTest(1)!!.text)
        assertTrue(
            "param2 空值渲染保留 ?：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("type = ?"),
        )
        panel.clear()
    }

    fun testEditorIsEditable() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val editor = panel.editorForTest()!!
        assertFalse("应为可编辑编辑器（非只读 viewer）", editor.isViewer)
        panel.clear()
    }

    fun testCompletionContextAttached() {
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(columns = listOf("id", "phone")))
        val psi = panel.psiFileForTest()
        assertNotNull("应创建预览 PSI 文件", psi)
        val ctx = psi!!.getUserData(SqlCompletionContext.KEY)
        assertNotNull("应挂补全上下文", ctx)
        assertEquals("tbl", ctx!!.tableName)
        assertEquals(listOf("id", "phone"), ctx.columns)

        // M16.2 双载体：Editor 上同样挂载补全上下文（编辑后 PSI 重建丢失时兜底）
        val editor = panel.editorForTest()
        assertNotNull("应创建编辑器", editor)
        val editorCtx = editor!!.getUserData(SqlCompletionContext.KEY)
        assertNotNull("Editor 上应挂补全上下文（M16.2）", editorCtx)
        assertEquals("tbl", editorCtx!!.tableName)
        assertEquals(listOf("id", "phone"), editorCtx.columns)
        panel.clear()
    }

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
