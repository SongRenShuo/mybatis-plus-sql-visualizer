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

// k2j 互操作：master 侧面板扩展函数（showResult/clear/toggleCompact/copySql/refreshView）在
// k2j 落为同包 Java 门面静态（T-M53-II 拆分），调用点改门面限定形态（receiver 首参）；
// 同包静态直调无需 import。

/**
 * SqlPreviewPanel 行为测试（M8/M9）：编辑器视图 + 单行多行切换 + 生命周期 + 参数回填。
 *
 * 需要 IDE fixture（EditorFactory/PSI 依赖应用环境），在 EDT 上运行。
 */
class SqlPreviewPanelTest : BasePlatformTestCase() {

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
        // k2j 适配：PreviewResult 为 Java 类（20 参构造），具名实参→位置实参，缺省槽按 Kotlin 数据类默认值补齐
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

    fun testShowResultCreatesEditorView() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result())
        val editor = panel.editorForTest()
        assertNotNull("应挂载编辑器视图", editor)
        assertEquals("SELECT id\nFROM tbl", editor!!.document.text)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testShowResultReplacesEditor() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = "SELECT 1 FROM t1", compact = "SELECT 1 FROM t1"))
        val first = panel.editorForTest()
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = "SELECT 2 FROM t2", compact = "SELECT 2 FROM t2"))
        val second = panel.editorForTest()
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("SELECT 2 FROM t2", second!!.document.text)
        SqlPreviewPanelSqlViewKt.clear(panel)
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
            // k2j 适配：16 参全量位置构造（table, selectColumns, tableAlias, joins, distinct,
            // conditions, groupBy, having, orderBy, limit, rawFirst, rawAround, comment, unions,
            // fromSubQuery, fillClauses）
            mainTable,
            selectColumns,
            "u",
            listOf(
                JoinClause(
                    JoinType.LEFT,
                    "user_address",
                    "a",
                    listOf(
                        Condition.ColumnComparison(
                            ColumnRef("u", "id"),
                            ColumnRef("a", "user_id"),
                            null,
                        ),
                    ),
                ),
            ),
            false,
            emptyList(),
            emptyList(),
            null,
            emptyList(),
            null,
            null,
            null,
            null,
            emptyList(),
            null,
            null,
        )
        val sql = "SELECT u.id, u.name FROM car_device_info u " +
            "LEFT JOIN user_address a ON u.id = a.user_id"
        // k2j 适配：20 参位置构造（previewModel 第 11 位）
        return PreviewResult(
            sql,
            sql,
            emptyList(),
            null,
            WrapperType.LAMBDA_QUERY,
            "car_device_info",
            null,
            listOf("id", "name"),
            emptyMap(),
            null,
            SqlPreviewModel(statement, MySqlDialect.INSTANCE, false, emptyList()),
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
        SqlPreviewPanelResultViewKt.showResult(panel, joinResult())
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
        SqlPreviewPanelSqlViewKt.clear(panel)
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
        SqlPreviewPanelResultViewKt.showResult(panel, joinResult())
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
        SqlPreviewPanelSqlViewKt.clear(panel)
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
        SqlPreviewPanelResultViewKt.showResult(panel, joinResult())
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
        SqlPreviewPanelSqlViewKt.clear(panel)
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
        SqlPreviewPanelResultViewKt.showResult(panel, joinResult())
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
        SqlPreviewPanelSqlViewKt.clear(panel)
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
        SqlPreviewPanelResultViewKt.showResult(panel,
            joinResult(
                // k2j 适配：TableInfo.copy 为 7 参位置形态；ColumnRef 2 参位置
                tableInfo().copy(
                    tableInfo().entityClassName, tableInfo().tableName,
                    sizeExplodingColumns(tableInfo().columns),
                    tableInfo().logicDeleteColumn, tableInfo().versionColumn, tableInfo().comment,
                    tableInfo().associations,
                ),
                listOf(ColumnRef("u", "id")),
            ),
        )
        // 无 project → 同步分支：showResult 返回时该跑的都跑完了，无需排空 EDT 队列
        assertFalse(
            "无 project 同步分支：buildDiagramModel 抛在 showJoinDiagram 之前、finally 不会执行——" +
                "catch 不摘圈则转圈永留宿主层",
            panel.diagramPanelForTest().isLoadingForTest(),
        )
        assertFalse("宿主层同样不得卡转圈", panel.diagramLoadingHostForTest().isLoading)
        SqlPreviewPanelSqlViewKt.clear(panel)
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
        SqlPreviewPanelResultViewKt.showResult(panel, result())

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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testToggleCompactSwitchesSql() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result())
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)

        assertTrue("toggleCompact 应返回单行模式", SqlPreviewPanelSqlViewKt.toggleCompact(panel))
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

        SqlPreviewPanelSqlViewKt.toggleCompact(panel)
        assertEquals("SELECT id\nFROM tbl", panel.editorForTest()!!.document.text)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testRefreshResetsModeToSettingsDefault() {
        // M19.3（用户实测：点刷新后 SQL 仍停留在单行模式）：
        // 刷新 = 模式复位为与设置「SQL 格式化输出」对齐（默认多行）——手动切到单行后刷新应回多行
        // （setUp 关闭格式化以保持渲染断言——本测试需默认开启语义，显式恢复）
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result())
        SqlPreviewPanelSqlViewKt.toggleCompact(panel)   // 切到单行
        assertTrue(panel.compactForTest())
        assertEquals("SELECT id FROM tbl", panel.editorForTest()!!.document.text)

        SqlPreviewPanelSqlViewKt.refreshView(panel)
        assertFalse("刷新后应恢复默认多行模式", panel.compactForTest())
        // formatSql=true 时刷新渲染自动格式化
        assertEquals("SELECT\n    id\nFROM tbl", panel.editorForTest()!!.document.text)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testCopySqlWithContent() {
        // 无 project 时不弹成功提示（测试环境 Messages 对话框会抛异常）
        val panel = SqlPreviewPanel(null)
        SqlPreviewPanelResultViewKt.showResult(panel, result())
        assertTrue("有内容时应复制成功", SqlPreviewPanelSqlViewKt.copySql(panel))
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testCopySqlClosePanelCallbackInjectable() {
        // T-M23.6：onClosePanelRequested 回调默认 null、可注入（预览弹窗赋值 dispose）；
        // project null → 弹框跳过（回调不触发），注入的回调可被面板外部链路触发
        val panel = SqlPreviewPanel(null)
        assertNull("默认无关闭面板回调", panel.onClosePanelRequested)
        var closed = false
        panel.onClosePanelRequested = { closed = true }
        SqlPreviewPanelResultViewKt.showResult(panel, result())
        assertTrue("有内容时应复制成功", SqlPreviewPanelSqlViewKt.copySql(panel))
        assertFalse("无 project 弹框跳过，回调不应触发", closed)
        // 链路：点「关闭 SQL 面板」→ onClosePanelRequested?.invoke()
        panel.onClosePanelRequested?.run()
        assertTrue("注入的回调应可触发", closed)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testClearReleasesEditor() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result())
        assertNotNull(panel.editorForTest())
        SqlPreviewPanelSqlViewKt.clear(panel)
        assertNull("clear 后应释放编辑器", panel.editorForTest())
        assertNull(panel.currentForTest())
    }

    fun testShowResultBuildsParamFields() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result(params = listOf("dto.getPhone()", "id")))
        assertEquals(2, panel.paramFieldForTest(1)?.let { 2 } ?: 0)
        assertNotNull("应生成参数输入框", panel.paramFieldForTest(0))
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testParamRowLabelWidthAdaptsAndTooltipForLongNames() {
        // T-M19.4-01（用户实测：`#productInfo.getDicProtocolType()` 这类长参数名被固定 140px
        // 标签截断显示不全）+ T-M26.3（标签列宽统一：按最长可见标签自适应，超宽中段省略 + tooltip 全名）
        // T-ParamNameTruncation：上限改为「视口 55% / 硬上限 480」，且全名 tooltip **无条件**设置
        // （旧实现只在「构建时判定超宽」的分支里设 → 列宽事后一变就漏 tooltip）
        val panel = SqlPreviewPanel(project)
        val short = "id"
        val medium = "productInfo.getDicProtocolType()"   // 用户实测场景：约 200px
        val long = "productInfo.getDicProtocolType().getDeviceName().getStatusName().getDetailInfo()"
        SqlPreviewPanelResultViewKt.showResult(panel, result(params = listOf(short, medium, long)))
        val shortLabel = panel.paramRowLabelForTest(0)!!
        val mediumLabel = panel.paramRowLabelForTest(1)!!
        val longLabel = panel.paramRowLabelForTest(2)!!
        // T-ParamNameTruncation：同一场景所有参数行共享标签列宽（夹在 80~480 之间）
        val columnWidth = shortLabel.preferredSize.width
        assertTrue("列宽应不小于下限 ${SqlPreviewPanelParamPanelKt.LABEL_COLUMN_MIN_PX}", columnWidth >= SqlPreviewPanelParamPanelKt.LABEL_COLUMN_MIN_PX)
        assertTrue("列宽应不超过上限 ${SqlPreviewPanelParamPanelKt.LABEL_COLUMN_MAX_PX}", columnWidth <= SqlPreviewPanelParamPanelKt.LABEL_COLUMN_MAX_PX)
        assertEquals("标签列宽应统一", columnWidth, mediumLabel.preferredSize.width)
        assertEquals("标签列宽应统一", columnWidth, longLabel.preferredSize.width)
        // 超长名：截断显示（文本 != 全名）+ tooltip 全名（超过折行阈值走 HTML 折行，见下一条用例）
        assertEquals("超长标签 tooltip 应为全名", SqlPreviewPanelParamPanelKt.wrappedLabelTooltip("#$long"), longLabel.toolTipText)
        assertNotEquals("超长标签应截断显示", "#$long", longLabel.text)
        assertEquals("短参数 tooltip 恒为全名", "#$short", shortLabel.toolTipText)
        assertEquals("中等长度参数 tooltip 恒为全名", "#$medium", mediumLabel.toolTipText)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testParamLabelColumnFollowsViewportWidth() {
        // T-ParamNameTruncation（用户截图场景）：左栏明明很宽，标签却按构建那一刻的窄列宽截断。
        // 列宽改为跟随视口后，宽视口要能完整显示、窄视口回到中段省略且 tooltip 仍是全名。
        val panel = SqlPreviewPanel(project)
        val name = "productInfo.getDicProtocolType().getDeviceName()"
        SqlPreviewPanelResultViewKt.showResult(panel, result(params = listOf(name)))
        val label = panel.paramRowLabelForTest(0)!!
        val scroll = panel.paramScrollForTest()
        scroll.viewport.setSize(java.awt.Dimension(1000, 300))
        SqlPreviewPanelParamPanelKt.relabelParamLabels(panel)
        val wide = label.preferredSize.width
        assertEquals("宽视口列宽应容下全名，标签完整显示", "#$name", label.text)
        scroll.viewport.setSize(java.awt.Dimension(220, 300))
        SqlPreviewPanelParamPanelKt.relabelParamLabels(panel)
        val narrow = label.preferredSize.width
        assertTrue("窄视口列宽应收缩（$wide -> $narrow）", narrow < wide)
        assertNotEquals("窄视口应回到中段省略", "#$name", label.text)
        assertEquals("截断后 tooltip 仍为全名", "#$name", label.toolTipText)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testWrappedLabelTooltipWrapsLongName() {
        // T-ParamNameTruncation：超长名 tooltip 走 HTML 软折行——Swing 对纯文本 tooltip 不折行，
        // 单行超屏宽会被裁掉（「悬停也看不全」的成因面之一）
        val short = "#id"
        assertEquals("短名 tooltip 原样返回", short, SqlPreviewPanelParamPanelKt.wrappedLabelTooltip(short))
        val long = "productInfo.getDicProtocolType().getDeviceName().getStatusName().getDetailInfo().getRemark()"
        val wrapped = SqlPreviewPanelParamPanelKt.wrappedLabelTooltip(long)
        assertTrue("超长名应转 HTML 折行：$wrapped", wrapped.startsWith("<html>"))
        val plain = wrapped.removePrefix("<html>").removeSuffix("</html>").replace("<br>", "")
        assertEquals("折行后必须保留全部字符", long, plain)
    }

    fun testParamLabelColumnWidthBudget() {
        // T-ParamNameTruncation：列宽预算纯函数——内容优先、视口比例封顶、未布局按兜底视口估算
        // K2J 适配：paramLabelColumnWidth 的 insets 默认参在生产侧未落重载，显式传空表对齐 master 默认值
        assertEquals(SqlPreviewPanelParamPanelKt.LABEL_COLUMN_MIN_PX, SqlPreviewPanelParamPanelKt.paramLabelColumnWidth(emptyList(), 1000, emptyList()))
        assertEquals("内容窄时取内容宽度", 200, SqlPreviewPanelParamPanelKt.paramLabelColumnWidth(listOf(200), 1000, emptyList()))
        assertEquals("视口 55% 封顶", 264, SqlPreviewPanelParamPanelKt.paramLabelColumnWidth(listOf(2000), 480, emptyList()))
        assertEquals("低于下限抬到下限", SqlPreviewPanelParamPanelKt.LABEL_COLUMN_MIN_PX, SqlPreviewPanelParamPanelKt.paramLabelColumnWidth(listOf(10), 400, emptyList()))
        assertEquals(
            "未布局（视口 0）按兜底视口估算",
            (SqlPreviewPanelParamPanelKt.LABEL_COLUMN_FALLBACK_VIEWPORT_PX * SqlPreviewPanelParamPanelKt.LABEL_COLUMN_VIEWPORT_RATIO).toInt(),
            SqlPreviewPanelParamPanelKt.paramLabelColumnWidth(listOf(9999), 0, emptyList()),
        )
        assertEquals("视口极宽时受硬上限约束", SqlPreviewPanelParamPanelKt.LABEL_COLUMN_MAX_PX, SqlPreviewPanelParamPanelKt.paramLabelColumnWidth(listOf(9999), 4000, emptyList()))
    }

    // ------------------------------------------------------------------
    // M9：参数回填 / 可编辑 / 补全上下文
    // ------------------------------------------------------------------

    fun testParamBackfillUpdatesSql() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?"
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = sql, compact = sql, params = listOf("dto.getPhone()", "type")))
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testParamBackfillEmptyKeepsPlaceholder() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ?"
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = sql, compact = sql, params = listOf("phone")))
        // 留空 → 保留 ?
        assertEquals(sql, panel.editorForTest()!!.document.text)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    // T-PSG（N-33）：面板回填串感知——引号/注释内的 `?` 不是占位符，不参与回填
    fun testParamBackfillIgnoresQuestionMarkInStringLiteral() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE note = 'a?b' AND phone = ?"
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = sql, compact = sql, params = listOf("phone")))
        panel.paramFieldForTest(0)!!.text = "abc"
        assertEquals(
            "引号内 `?` 应保持原样、真占位符按 phone 回填",
            "SELECT id FROM tbl WHERE note = 'a?b' AND phone = 'abc'",
            panel.editorForTest()!!.document.text,
        )
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testParamBackfillIgnoresQuestionMarkInComments() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl /* 占?位 */ WHERE phone = ?"
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = sql, compact = sql, params = listOf("phone")))
        panel.paramFieldForTest(0)!!.text = "abc"
        assertEquals(
            "块注释内 `?` 应保持原样",
            "SELECT id FROM tbl /* 占?位 */ WHERE phone = 'abc'",
            panel.editorForTest()!!.document.text,
        )
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testParamBackfillNormalPathByteIdentical() {
        // T-PSG 验收 1：不含引号/注释内 `?` 的正常 SQL，回填结果与改前逐字节一致（硬编码钉住）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE note = 'hello' AND phone = ? AND age > ?"
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = sql, compact = sql, params = listOf("phone", "age")))
        panel.paramFieldForTest(0)!!.text = "abc"
        panel.paramFieldForTest(1)!!.text = "1"
        assertEquals(
            "SELECT id FROM tbl WHERE note = 'hello' AND phone = 'abc' AND age > 1",
            panel.editorForTest()!!.document.text,
        )
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    // T-PSG-04：面板回填链的基数对账——位点数与参数条数不等时如实记录（不阻断）
    fun testPlaceholderReconciliationMismatchRecorded() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE a = ? AND b = ?"
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = sql, compact = sql, params = listOf("a")))
        assertEquals(
            "位点数(2) != 参数条数(1) → 应如实记录对账标注",
            "-- placeholder/param count mismatch (2 placeholders vs 1 params)",
            panel.placeholderReconciliationForTest(),
        )
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testPlaceholderReconciliationEqualNoMarker() {
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ?"
        SqlPreviewPanelResultViewKt.showResult(panel, result(sql = sql, compact = sql, params = listOf("phone")))
        assertNull("位点数与参数条数相等 → 无标注", panel.placeholderReconciliationForTest())
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    // ------------------------------------------------------------------
    // T-M42：日志双击预览参数识别——initialParamValues 预填（参数行初始值 + SQL 初始渲染）
    // ------------------------------------------------------------------

    fun testInitialParamValuesPrefillFieldAndRender() {
        // 日志识别路径：param1 预填 "admin"（String → 引号提示）——初始显示与现状（值内联 'admin'）一致
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE username = ?"
        // k2j 适配：20 参位置构造（paramHints 第 12 位、initialParamValues 第 16 位）
        SqlPreviewPanelResultViewKt.showResult(panel,
            PreviewResult(
                sql,
                sql,
                listOf("param1"),
                null,
                WrapperType.LAMBDA_QUERY,
                "tbl",
                null,
                emptyList(),
                emptyMap(),
                null,
                null,
                listOf(ParamHint(true)),
                emptyList(),
                null,
                null,
                mapOf("param1" to "admin"),
                null,
                null,
                null,
                null,
            ),
        )
        assertEquals("参数行应预填日志参数值", "admin", panel.paramFieldForTest(0)!!.text)
        assertTrue(
            "SQL 初始渲染应含值内联 'admin'：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("username = 'admin'"),
        )
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testInitialParamValuesEditableAfterPrefill() {
        // 预填值可编辑：改参数行值 → SQL 实时回填（T-M42 验收：改 admin → 新值同步变化）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE username = ?"
        // k2j 适配：20 参位置构造（paramHints 第 12 位、initialParamValues 第 16 位）
        SqlPreviewPanelResultViewKt.showResult(panel,
            PreviewResult(
                sql,
                sql,
                listOf("param1"),
                null,
                WrapperType.LAMBDA_QUERY,
                "tbl",
                null,
                emptyList(),
                emptyMap(),
                null,
                null,
                listOf(ParamHint(true)),
                emptyList(),
                null,
                null,
                mapOf("param1" to "admin"),
                null,
                null,
                null,
                null,
            ),
        )
        panel.paramFieldForTest(0)!!.text = "zhang"
        assertTrue(
            "修改参数行值后 SQL 应同步回填：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("username = 'zhang'"),
        )
        assertFalse("原值不应残留", panel.editorForTest()!!.document.text.contains("admin"))
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testInitialParamValuesMissingSourceFallsBackToEmpty() {
        // 预填表缺失的来源回落空串（参数行初始为空 → 渲染保留 `?`）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE phone = ? AND type = ?"
        // k2j 适配：20 参位置构造（paramHints 第 12 位、initialParamValues 第 16 位）
        SqlPreviewPanelResultViewKt.showResult(panel,
            PreviewResult(
                sql,
                sql,
                listOf("param1", "param2"),
                null,
                WrapperType.LAMBDA_QUERY,
                "tbl",
                null,
                emptyList(),
                emptyMap(),
                null,
                null,
                listOf(ParamHint(true), ParamHint(false)),
                emptyList(),
                null,
                null,
                mapOf("param1" to "admin"),
                null,
                null,
                null,
                null,
            ),
        )
        assertEquals("param1 应预填", "admin", panel.paramFieldForTest(0)!!.text)
        assertEquals("param2 无预填值应回落空串", "", panel.paramFieldForTest(1)!!.text)
        assertTrue(
            "param2 空值渲染保留 ?：" + panel.editorForTest()!!.document.text,
            panel.editorForTest()!!.document.text.contains("type = ?"),
        )
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testEditorIsEditable() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result())
        val editor = panel.editorForTest()!!
        assertFalse("应为可编辑编辑器（非只读 viewer）", editor.isViewer)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    fun testCompletionContextAttached() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result(columns = listOf("id", "phone")))
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
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

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
