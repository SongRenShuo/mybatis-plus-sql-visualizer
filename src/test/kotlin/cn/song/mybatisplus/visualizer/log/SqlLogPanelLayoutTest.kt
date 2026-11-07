package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

/**
 * SQL 日志面板布局 / 兼容性 UI 测试（F-44-5 全插件 UI 测试）：
 *
 * 用户反馈「布局错乱 / 不兼容」——工具栏是主战场：
 *  - 关键组件（SQL类型按钮、去重勾选、帮助动作组；捕捉开关 T-CaptureBack 起在面板工具栏首位）构建即存在；
 *  - 默认可见性正确（排序选择器恒可见、搜索条默认隐藏、无慢查询不显示徽章）；
 *  - **T-UiCoord F2（2026-09-21 用户定案「不要两行」）：工具栏恒为单行**——控制组实现高度恒等于
 *    其单行理想高度（FlowLayout 折行时实现高度会翻倍，这就是判据），且任意宽度下无越界。
 *    旧用例 `test窄侧边栏_WrapLayout_类型筛选按钮不被裁切` 已退役：它自建 holder 手工装配组件、
 *    且只对 holder 调 doLayout（Container.doLayout 不递归 → 读到的子件 bounds 是未布局的陈旧值），
 *    实际断言恒真——守的是假形态（本仓「守卫看不见它要防的站点」同族）。
 */
class SqlLogPanelLayoutTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    private fun waitForEdt() = com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

    fun test全部动作直达工具栏_无自建更多弹层() {
        val panel = SqlLogPanel(project, manager)
        try {
            val main = panel.toolbarGroup.getChildren(null).toList()
            // 用户 2026-10-14：「不要这个更多，之前是侧边栏不够宽时自动折叠、悬停能看全部」。
            // 故组内不得出现自建 popup 分组；窄宽折叠交给平台 ActionToolbar 自带的溢出。
            assertFalse("工具栏不应再有自建「更多」弹层", main.any { it is DefaultActionGroup })
            assertTrue("捕捉应直达", main.contains(panel.captureToggleAction))
            assertTrue("增强捕获暂停/恢复应直达", main.contains(panel.agentPauseAction))
            assertEquals("暂停动作应紧随捕捉动作", 1, main.indexOf(panel.agentPauseAction))
            assertTrue("滚动到底部应直达", main.contains(panel.scrollToEndAction))
            assertTrue("排序应直达（并入左侧动作组）", main.contains(panel.sortActionForTest))
            assertTrue("清空应直达", main.any { it is SqlLogPanel.ClearAction })
            assertTrue("搜索应直达", main.any { it is SqlLogPanel.ShowSearchAction })
            assertTrue("预览应归入左侧动作组", main.contains(panel.previewToggleAction))
            assertSame("预览动作必须带平台图标，不能渲染占位圆圈",
                MpvsIcons.DockedPreview, panel.previewToggleAction.templatePresentation.icon)
            assertEquals(MpSqlVisualizerBundle.message("log.dockedPreview.toggle.tip"),
                panel.previewToggleAction.templatePresentation.description)
            assertTrue("设置应直达", main.any { it is SqlLogPanel.SettingsAction })
            assertTrue("屏蔽管理应直达", main.any { it is SqlLogPanel.ManageSuppressAction })
            assertTrue("帮助应直达", main.any { it is SqlLogPanel.HelpAction })
            val community = main.filterIsInstance<SqlLogPanel.CommunityAction>()
            assertEquals("交流群入口只能出现一次", 1, community.size)
            assertEquals(MpSqlVisualizerBundle.message("log.community.action"), community.single().templatePresentation.text)
            assertNotNull("交流群动作必须显示自有图标", community.single().templatePresentation.icon)
            assertEquals(
                "十一个动作应全部在组内（捕捉/增强暂停/清空/设置/屏蔽/搜索/预览/滚底/排序/交流群/帮助）",
                11,
                main.size,
            )
            // 用户 2026-10-14 选定「方案 A」：操作在前、工具在后、帮助收尾（溢出从右端折叠，帮助最先让位）。
            assertEquals("帮助应为组内末项（方案 A）", main.last(), main.first { it is SqlLogPanel.HelpAction })
            val sortIndex = main.indexOfFirst { it === panel.sortActionForTest }
            assertEquals("交流群按钮应紧挨排序右侧、帮助左侧", sortIndex + 1, main.indexOf(community.single()))
            assertEquals("帮助应紧随交流群按钮", main.lastIndex, main.indexOf(community.single()) + 1)
        } finally {
            panel.dispose()
        }
    }

    fun test工具栏关键组件存在_默认可见性正确() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            // 关键组件均存在（构建即无 NPE / 缺 i18n）
            assertNotNull("SQL类型按钮应存在", panel.typeFilterButtonForTest)
            assertNotNull("去重勾选框应存在", panel.dedupeCheckBoxForTest)
            assertNotNull("搜索条应存在", panel.searchFieldForTest)
            // T-CaptureBack：捕捉开关回归面板工具栏首位（曾迁标题栏致入口不可见），
            // 工具栏组含 5 个动作；捕捉动作存在于组内且为捕捉态
            assertTrue(
                "工具栏动作组应含 ≥5 个动作（捕捉/清空/设置/搜索/帮助）",
                panel.toolbarGroup.childActionsOrStubs.size >= 5,
            )
            assertTrue(
                "捕捉开关动作应在工具栏组内（回迁面板工具栏）",
                panel.toolbarGroup.childActionsOrStubs.contains(panel.captureToggleAction),
            )

            // 默认可见性：排序动作恒在工具栏组内（并入左侧动作组，随组折叠）；搜索条默认隐藏；慢查询徽章隐藏
            assertTrue(
                "排序动作应恒在工具栏动作组内（并入左侧按钮集，窄宽随组折叠）",
                panel.toolbarGroup.getChildren(null).contains(panel.sortActionForTest),
            )
            assertEquals("搜索条默认隐藏", false, panel.searchFieldVisibleForTest)
            assertEquals("默认无慢查询，计数徽章应隐藏/为空", false, panel.slowQueryLabelForTest?.isVisible ?: false)
        } finally {
            panel.dispose()
        }
    }

    /**
     * R53：双通道状态栏 + 暂停/恢复入口在场——工具窗状态可见化交付的结构守卫。
     * 状态栏两段标签构建即存在；捕捉中刷新一拍后两段均有文案（取数只读内存态，headless 可跑）。
     * 非捕捉态整条隐藏：只清两段文案会留下分隔符「｜」与一条空白高度。
     */
    fun testR53_双通道状态栏与暂停动作在场() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            assertTrue(
                "工具栏组应含增强暂停/恢复动作（R53 暂停入口）",
                panel.toolbarGroup.childActionsOrStubs.contains(panel.agentPauseAction),
            )
            val (console, agent) = panel.statusBarLabelsForTest()
            assertNotNull("状态栏控制台通道段应存在", console)
            assertNotNull("状态栏增强通道段应存在", agent)
            assertNotNull("状态栏宿主面板应存在", panel.statusBarForTest())
            // 非捕捉态：整条隐藏（构建期即非捕捉，默认就应隐藏）
            assertFalse("非捕捉态状态栏应整条隐藏", panel.statusBarForTest()!!.isVisible)

            manager.setCapturingForTest(true)
            manager.entries.paused = false
            panel.syncStatusBarForTest()
            assertTrue("捕捉中状态栏应显示", panel.statusBarForTest()!!.isVisible)
            assertTrue("捕捉中控制台通道段应有文案：${console?.text}", !console!!.text.isNullOrEmpty())
            assertTrue("捕捉中增强通道段应有文案：${agent?.text}", !agent!!.text.isNullOrEmpty())

            // 停捕捉后回到隐藏态（不留空带）
            manager.setCapturingForTest(false)
            panel.syncStatusBarForTest()
            assertFalse("停捕捉后状态栏应重新整条隐藏", panel.statusBarForTest()!!.isVisible)
        } finally {
            panel.dispose()
        }
    }

    /**
     * T-SqlListCatchup 券 D：追赶读数接线守卫——buffer 节流窗积压（pendingNotifyCount>0）经
     * [SqlLogPanel.syncCaptureStatusBar] 驱动「追赶中：落后 N 条」上控制台段；追平（clear 复位
     * pending=0）持有一拍后消失。不新开轮询：驱动拍与状态栏既有刷新拍同源（R53 的 2s 诊断定时器
     * + refreshList→syncDiagnostics 链）。
     */
    fun testTSqlListCatchupD_追赶读数出现与追平消失() {
        manager.setCapturingForTest(true)
        manager.entries.paused = false
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val (console, _) = panel.statusBarLabelsForTest()
            // 基线：无积压不出现
            panel.syncStatusBarForTest()
            assertFalse("无积压不得出现追赶读数：${console!!.text}", console.text.contains("追赶中"))

            // 快速灌 3 条：首条即时通知清零，后两条落节流窗（200ms 窗内）→ pendingNotifyCount>0
            repeat(3) { i ->
                manager.entries.addEntry(
                    LogSqlEntry(
                        rawSql = "SELECT $i FROM catchup_t",
                        restoredSql = "SELECT $i FROM catchup_t",
                        params = emptyList(),
                        timestamp = i.toLong() + 1,
                    ),
                )
            }
            waitForEdt()
            panel.syncStatusBarForTest()
            assertTrue(
                "节流积压期追赶读数应出现：${console.text}",
                console.text.contains("追赶中"),
            )

            // 追平：clear 复位 pending=0（buffer 侧悬挂余量作废）→ 持有拍后消失
            manager.entries.clear()
            waitForEdt()
            panel.syncStatusBarForTest()
            panel.syncStatusBarForTest()
            assertFalse("追平后追赶读数应消失：${console.text}", console.text.contains("追赶中"))
        } finally {
            panel.dispose()
        }
    }

    /**
     * T-SqlListCatchup 券B2：诊断拍过期冲洗接线守卫——悬挂节流余量（末批不足批量阈值、时间推过
     * 200ms 窗且无后续触发源）在 [SqlLogPanel.syncCaptureStatusBar] 诊断拍被冲洗：pending 归零、
     * 悬挂条目经 notifyUi 合拍链上屏。反向验证：摘掉 syncCaptureStatusBar 里的
     * hasStalePendingNotify/flushPendingNotify 分支，本用例必红（pending 滞留 + 列表缺悬挂条目）。
     */
    fun testTSqlListCatchupB2_诊断拍冲洗悬挂节流余量() {
        manager.setCapturingForTest(true)
        manager.entries.paused = false
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val baseNanos = 1_000_000_000L
            // 首条即时通知；随后 2 条窗内（+50ms）抑制 → 悬挂候选 pending=2（<批量阈值 20）
            manager.entries.nowNanosForTest = baseNanos
            manager.entries.addEntry(LogSqlEntry("SELECT 1 FROM stale_t", "SELECT 1 FROM stale_t", emptyList(), 1L))
            manager.entries.nowNanosForTest = baseNanos + 50_000_000L
            repeat(2) { i ->
                manager.entries.addEntry(
                    LogSqlEntry(
                        "SELECT ${i + 2} FROM stale_t",
                        "SELECT ${i + 2} FROM stale_t",
                        emptyList(),
                        (i + 2).toLong(),
                    ),
                )
            }
            assertEquals(2, manager.entries.pendingNotifyCount())
            // 时间推过窗口 → 悬挂态成立，此后无新条目（无触发源）
            manager.entries.nowNanosForTest = baseNanos + 250_000_000L
            assertTrue(manager.entries.hasStalePendingNotify())

            // 诊断拍（syncStatusBarForTest 直驱 syncCaptureStatusBar）：冲洗悬挂余量
            panel.syncStatusBarForTest()
            assertEquals("诊断拍冲洗悬挂节流余量", 0, manager.entries.pendingNotifyCount())
            waitForEdt()
            assertEquals("悬挂条目经冲洗通知上屏", 3, panel.visibleEntryCount)
        } finally {
            manager.entries.nowNanosForTest = null
            panel.dispose()
        }
    }

    /**
     * T-SqlListCatchup 券 D：refreshList 全拍探针接线守卫——每次刷新拍必落一枚样本到
     * `LogPanel.refreshList` 分布（计划 §七.1「探针读数非空」）。反向验证：摘掉 refreshList
     * 的 LogPerf.measure 包裹，本用例必须红。
     */
    fun testTSqlListCatchupD_refreshList全拍探针读数非空() {
        LogPerfProbe.resetSamples()
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            manager.setCapturingForTest(true)
            manager.entries.paused = false
            manager.entries.addEntry(
                LogSqlEntry(
                    rawSql = "SELECT 1 FROM probe_t",
                    restoredSql = "SELECT 1 FROM probe_t",
                    params = emptyList(),
                    timestamp = 1L,
                ),
            )
            waitForEdt()
            val stats = LogPerfProbe.sampleStats("LogPanel.refreshList")
            assertNotNull("刷新拍后全拍探针应有读数（key=LogPanel.refreshList）", stats)
            assertTrue("读数 count 应 ≥1：$stats", stats!!.count >= 1)
        } finally {
            LogPerfProbe.resetSamples()
            panel.dispose()
        }
    }

    fun test注入耗时信息_排序方式选择器保持可见() {
        val panel = SqlLogPanel(project, manager)
        try {
            // p6spy 格式带耗时
            manager.entries.addEntry(
                LogSqlEntry(
                    rawSql = "1723456789012|1500|statement|SELECT * FROM t",
                    restoredSql = "SELECT * FROM t",
                    params = emptyList(),
                    timestamp = 1L,
                    executionTimeMs = 1500L,
                ),
            )
            waitForEdt()
            assertEquals(
                "有耗时信息时排序动作仍在工具栏组内",
                true,
                panel.toolbarGroup.getChildren(null).contains(panel.sortActionForTest),
            )
            assertEquals("默认排序方式为时间升序", SortMode.TIME_ASC, panel.currentSortMode)
        } finally {
            panel.dispose()
        }
    }

    // ------------------------------------------------------------------
    // T-UiCoord F2：工具栏单行契约
    // ------------------------------------------------------------------

    /** 把生产工具栏行放进定宽 holder 按 NORTH 布局（NORTH 用理想高 → 行高即单行高），逐层 doLayout。 */
    private fun layoutRowAt(panel: SqlLogPanel, width: Int): JPanel {
        val row = panel.toolbarRowForTest!!
        val holder = JPanel(BorderLayout()).apply { size = Dimension(width, 400) }
        holder.add(row, BorderLayout.NORTH)
        layoutRecursively(holder)
        waitForEdt()
        return row
    }

    /** Container.doLayout 不递归——必须逐层布局，否则子件 bounds 停在未布局状态（旧用例的假绿根因）。 */
    private fun layoutRecursively(c: java.awt.Container) {
        c.doLayout()
        c.components.filterIsInstance<java.awt.Container>().forEach { layoutRecursively(it) }
    }

    /** 控制组的可见子件垂直中心极差（同父坐标系直接可比）：单行应 ≤ 6px，折行会是 20px 以上。 */
    private fun verticalBand(controls: JPanel): Int {
        val centers = controls.components
            .filter { it.isVisible && it.width > 0 && it.height > 0 }
            .map { it.y + it.height / 2 }
        if (centers.isEmpty()) return 0
        return centers.max() - centers.min()
    }

    /**
     * 控制组内可见子件分出的**行数**（按垂直中心聚类）。
     *
     * 两条必须写明的边界（2026-09-21 反向验证实测）：
     *  1. **不能拿「实现高度 == preferredSize 高度」当折行判据**——`WrapLayout.preferredLayoutSize`
     *     本就按折行后的多行高度上报（它存在的意义），拿它与实现高度对比对 WrapLayout 恒等，是空绿；
     *  2. **单测环境复现不出「旧结构折行」**：headless 下 `ActionToolbar` 不建按钮，
     *     `preferredSize` 为 0×0（真机量测约 133×26）——图标组不吃宽，旧的流式行在 460px 下反而放得下。
     *     故「一行放得下」由 [test控制组最坏宽在预算内] 的宽度预算锁守，「折行」的真机确认走 runIde 走查。
     * 零尺寸子件（headless 的工具栏占位）不计入行带，否则其中心 0 会造成假红。
     */
    private fun distinctRowCount(controls: JPanel): Int {
        val centers = controls.components
            .filter { it.isVisible && it.width > 0 && it.height > 0 }
            .map { it.y + it.height / 2 }
            .sorted()
        if (centers.isEmpty()) return 0
        var rows = 1
        var prev = centers.first()
        for (c in centers.drop(1)) {
            if (c - prev > 10) rows++
            prev = c
        }
        return rows
    }

    /** 控制组内最高控件的理想高（单行行的上限基准；两行必然接近它的两倍）。 */
    private fun tallestControlHeight(controls: JPanel): Int =
        controls.components.filter { it.isVisible && it.height > 0 }
            .maxOfOrNull { it.preferredSize.height } ?: 0

    fun test工具栏恒为单行_用户宽度下() {
        val panel = SqlLogPanel(project, manager)
        try {
            // 多选类型 → 按钮文案变长（旧流式行正是在这个状态下把「排序」挤到第二行）
            panel.applyTypeFilterForTest(setOf(StatementType.SELECT, StatementType.UPDATE))
            waitForEdt()

            val row = layoutRowAt(panel, USER_SIDEBAR_WIDTH)
            assertTrue("预览图标应参与左侧工具栏溢出布局",
                panel.toolbarGroup.getChildren(null).contains(panel.previewToggleAction))
            val controls = panel.toolbarControlsForTest!!
            assertEquals(
                "控制组应单行（实测 ${distinctRowCount(controls)} 行）",
                1,
                distinctRowCount(controls),
            )
            assertTrue("控制组内控件应共轴（垂直中心极差 ${verticalBand(controls)}）", verticalBand(controls) <= 6)
            val tallest = tallestControlHeight(controls)
            assertTrue(
                "工具栏行高 ${row.height} 应明显小于两行高（单行基准 $tallest×2-8）",
                row.height < tallest * 2 - 8,
            )
            // 控制组右缘不越界（勾选在最右，不得被推出面板）
            val controlsRight = controls.x + controls.width
            assertTrue("控制组右缘 $controlsRight 不应超出行宽 ${row.width}", controlsRight <= row.width)
        } finally {
            panel.dispose()
        }
    }

    /**
     * 控制组最坏宽锁：类型多选（显示文本被预算封顶）即控制组的最坏形态。
     *
     * 上限 340 = 用户侧边栏 460 − 图标组保守下限 120（真机量测约 133，取更保守的 120）。
     * 本断言防的是「又往控制组加控件 / 又改长文案」——一旦超限，单行就会挤掉图标组，
     * 而 headless 复现不出挤掉过程（见 [distinctRowCount] 边界 2），故必须用预算锁前置拦住。
     */
    fun test控制组最坏宽在预算内() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.applyTypeFilterForTest(
                setOf(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE),
            )
            waitForEdt()
            val controls = panel.toolbarControlsForTest!!
            val worst = controls.preferredSize.width
            assertTrue(
                "控制组最坏理想宽 $worst 应 ≤ $CONTROLS_WIDTH_BUDGET" +
                    "（= 用户侧边栏 $USER_SIDEBAR_WIDTH − 图标组保守下限 $ICON_GROUP_WIDTH_MIN）",
                worst <= CONTROLS_WIDTH_BUDGET,
            )
        } finally {
            panel.dispose()
        }
    }

    /**
     * 控制组结构守卫：单行定构下「列表控制件在 EAST 控制组、动作图标组在 CENTER」。
     * 反向验证的落点——把旧流式行结构放回去时，本用例必红（控制组里会出现图标组与两个标签）。
     *
     * 图标组挂载断言是**实测漏点**：还原反向验证探针时曾把 `row.add(toolbar.component, CENTER)`
     * 一并删掉，而 headless 下工具栏理想宽 0×0、其余用例全绿——图标组整个没进行也无人报错。
     */
    fun test控制组结构_含列表控制件且不含图标组() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val row = panel.toolbarRowForTest!!
            val toolbarComponent = panel.toolbarComponentForTest
            assertNotNull("动作图标组组件应存在", toolbarComponent)
            // T-SqlListSortRight 二轮（用户校正）：排序**并入左侧动作组**，图标组回到行宿主 CENTER；
            // 故断言「行 → 图标组」直连（曾因还原反向验证探针误删挂载而全绿，此处保留该守卫）。
            assertTrue(
                "动作图标组必须直接挂在行宿主上（曾漏挂且全绿）",
                row.components.any { it === toolbarComponent },
            )
            val controls = panel.toolbarControlsForTest!!
            val children = controls.components.filter { it.isVisible }.toList()
            assertTrue("控制组应含 SQL类型按钮", children.contains(panel.typeFilterButtonForTest))
            // 排序归位到动作组内：既不在整行最左（原 WEST 槽），也不进右侧控制组（原位置）。
            val mainActions = panel.toolbarGroup.getChildren(null).toList()
            val sortIndex = mainActions.indexOfFirst { it === panel.sortActionForTest }
            assertTrue("排序动作应在工具栏动作组内", sortIndex >= 0)
            assertTrue(
                "排序应排在滚动到底部之后、帮助之前（方案 A 中排序为操作组末位，idx=$sortIndex / size=${mainActions.size}）",
                sortIndex > 0 && sortIndex < mainActions.size - 1,
            )
            assertTrue(
                "滚动到底部动作应排在排序之前",
                mainActions.indexOfFirst { it === panel.scrollToEndAction } < sortIndex,
            )
            assertTrue("控制组应含 去重勾选（仍在最右）", children.contains(panel.dedupeCheckBoxForTest))
            assertEquals(
                "去重勾选应在控制组最右",
                panel.dedupeCheckBoxForTest,
                children.last(),
            )
            assertFalse("动作图标组不应混进控制组", children.any { it === toolbarComponent })

            // 两侧不再各取理想宽、结构上不可能互相压盖：图标组挂 CENTER、控制组挂 EAST，
            // 行宿主 WEST 必须为空（它曾是压盖来源：左簇与控制组同时取理想宽）。
            val layout = row.layout as BorderLayout
            assertSame("动作图标组必须挂 CENTER（窄宽由 ActionToolbar 自身溢出折叠）",
                toolbarComponent, layout.getLayoutComponent(BorderLayout.CENTER))
            assertNull("行宿主 WEST 不应挂任何组件（排序已并入动作组，WEST 是压盖来源）",
                layout.getLayoutComponent(BorderLayout.WEST))
            assertNotNull("控制组应挂在行宿主 EAST", layout.getLayoutComponent(BorderLayout.EAST))
        } finally {
            panel.dispose()
        }
    }

    fun test排序并入动作组后控制组仍保留图标组预算() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.applyTypeFilterForTest(setOf(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE))
            waitForEdt()
            // T-SqlListSortRight 二轮：排序已是组内动作，左侧宽度由 ActionToolbar 自行折叠，
            // 控制组最坏宽 + 图标组保守下限仍须落在用户侧栏宽内（判据不放宽）。
            val right = panel.toolbarControlsForTest!!.preferredSize.width
            assertTrue("控制组 $right + 图标组 $ICON_GROUP_WIDTH_MIN 不能超出 $USER_SIDEBAR_WIDTH",
                right + ICON_GROUP_WIDTH_MIN <= USER_SIDEBAR_WIDTH)
        } finally {
            panel.dispose()
        }
    }

    fun test工具栏宽度扫描_恒单行不折行() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.applyTypeFilterForTest(setOf(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE))
            waitForEdt()
            for (width in listOf(400, USER_SIDEBAR_WIDTH, 600, 800)) {
                val row = layoutRowAt(panel, width)
                val controls = panel.toolbarControlsForTest!!
                assertEquals(
                    "宽度 ${width}px 下控制组应仍为单行（实测 ${distinctRowCount(controls)} 行）",
                    1,
                    distinctRowCount(controls),
                )
                assertTrue(
                    "宽度 ${width}px 下控制组不应越界（右缘 ${controls.x + controls.width} > ${row.width}）",
                    controls.x + controls.width <= row.width,
                )
            }
        } finally {
            panel.dispose()
        }
    }

    fun test慢查询徽章出现时仍为单行() {
        val saved = PluginSettings.getInstance().state.slowQueryThresholdMs
        PluginSettings.getInstance().state.slowQueryThresholdMs = 1000
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(
                LogSqlEntry(
                    rawSql = "SELECT slow FROM big_table",
                    restoredSql = "SELECT slow FROM big_table",
                    params = emptyList(),
                    timestamp = 1L,
                    executionTimeMs = 2000L,
                ),
            )
            waitForEdt()
            val label = panel.slowQueryLabelForTest
            assertNotNull("慢查询徽章应存在", label)
            assertEquals("存在慢查询时徽章可见", true, label?.isVisible)
            // T-UiCoord F2：全文「慢查询: N」改短徽章（复用 log.panel.slowQueryBadge），全文进 tooltip
            assertEquals(
                "徽章应为短形态",
                MpSqlVisualizerBundle.message("log.panel.slowQueryBadge") + " 1",
                label?.text,
            )
            assertEquals(
                "tooltip 应为全文（信息不丢）",
                MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", "1"),
                label?.toolTipText,
            )
            val row = layoutRowAt(panel, USER_SIDEBAR_WIDTH)
            val controls = panel.toolbarControlsForTest!!
            assertEquals(
                "徽章在场时控制组仍为单行",
                1,
                distinctRowCount(controls),
            )
            assertTrue("徽章在场时控制组不越界", controls.x + controls.width <= row.width)
        } finally {
            PluginSettings.getInstance().state.slowQueryThresholdMs = saved
            panel.dispose()
        }
    }

    fun test类型按钮文本封顶_多选后不撑宽() {
        val panel = SqlLogPanel(project, manager)
        try {
            val btn = panel.typeFilterButtonForTest
            // 三选（非全选——全选时按钮显示「全部」，不构成超预算文案）
            panel.applyTypeFilterForTest(
                setOf(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE),
            )
            waitForEdt()

            val tip = btn.toolTipText ?: ""
            assertTrue(
                "tooltip 应以「SQL类型：」标签键为前缀（标签控件退役后语义由它承载）",
                tip.startsWith(MpSqlVisualizerBundle.message("log.filter.type.label")),
            )
            for (key in listOf("log.filter.select", "log.filter.update", "log.filter.delete")) {
                assertTrue("tooltip 应含 $key 全文", tip.contains(MpSqlVisualizerBundle.message(key)))
            }
            assertTrue("多选组合应被截尾省略（显示文本「${btn.text}」）", btn.text.endsWith("\u2026"))
            val width = btn.getFontMetrics(btn.font).stringWidth(btn.text)
            assertTrue(
                "显示文本宽度 $width 应 ≤ 预算 ${panel.dropdownTextBudgetForTest}",
                width <= panel.dropdownTextBudgetForTest,
            )
            assertTrue("显示文本应短于 tooltip 全文（省略只发生在显示层）", btn.text.length < tip.length)
        } finally {
            panel.dispose()
        }
    }

    fun test去标签后tooltip携带完整状态() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            assertEquals(
                "类型按钮 tooltip 应为「SQL类型：」+ 当前状态",
                MpSqlVisualizerBundle.message("log.filter.type.label") + MpSqlVisualizerBundle.message("log.filter.all"),
                panel.typeFilterButtonForTest.toolTipText,
            )
            val sortAction = panel.sortActionForTest
            val sortEvent = AnActionEvent.createFromAnAction(
                sortAction, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT,
            )
            sortAction.update(sortEvent)
            assertEquals(
                "排序动作 tooltip 应为「排序：」+ 当前方式",
                MpSqlVisualizerBundle.message("log.sort.label") + MpSqlVisualizerBundle.message("log.sort.time.asc"),
                sortEvent.presentation.description,
            )
            // 当前方式显示名（工具窗工具栏只渲染图标，显示名进 tooltip 与菜单选中态）
            assertEquals("时间升序", panel.sortButtonTextForTest)
        } finally {
            panel.dispose()
        }
    }

    /** 清理默认历史目录（本类各用例共享同一临时项目目录，方法间残留不清——JUnit3 执行序非声明序）。 */
    private fun cleanHistoryDir() {
        historyDir().deleteRecursively()
    }

    private fun historyDir() = java.io.File(
        project.basePath + java.io.File.separator + ".idea" +
            java.io.File.separator + SqlLogAutoSaver.DATA_DIR_NAME +
            java.io.File.separator + "sql-history",
    )

    /**
     * T-HistoryTabAlways：历史 tab 常驻——未开自动保存、`.idea` 里也没有历史文件的干净项目里
     * 同样存在（此前由 `logAutoSaveEnabled || hasHistoryFiles()` 决定，此态无入口）。
     *
     * 判据取**等值**（历史 tab 在场 + tab 总数恰 4）：旧用例的 `tabCount >= 3` 对门禁无判别力
     * （关态 3、开态 4，两态皆过）——反向验证时把门禁改回去，本用例必须变红。
     */
    fun test历史Tab常驻_未开自动保存且无历史文件也存在() {
        cleanHistoryDir()
        val settings = PluginSettings.getInstance()
        val prevAutoSave = settings.state.logAutoSaveEnabled
        settings.state.logAutoSaveEnabled = false
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val tabs = panel.tabsForTest
            assertEquals("四个 tab：SQL 日志 / 日志转 SQL / SQL→Java / SQL 历史", 4, tabs.getTabCount())
            assertTrue(
                "历史 tab 应恒定存在（未开自动保存、无历史文件）",
                tabs.getTabs().any { it.text == MpSqlVisualizerBundle.message("log.tab.history") },
            )
        } finally {
            panel.dispose()
            settings.state.logAutoSaveEnabled = prevAutoSave
        }
    }

    /** V182：只检查真实页签容器的结构与旗位；headless 不把溢出菜单当作视觉验收。 */
    fun testV182_窄侧栏页签结构与可用时的单行几何() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val tabs = panel.tabsForTest
            val infos = tabs.tabs
            assertEquals("主容器恰有四个页签", 4, tabs.tabCount)
            assertEquals(
                "页签顺序不得漂移",
                listOf("log.tab.logs", "log.tab.logToSql", "log.tab.sqlToJava", "log.tab.history")
                    .map { MpSqlVisualizerBundle.message(it) },
                infos.map { it.text },
            )
            assertSame("初始选中 SQL 日志", infos.first(), tabs.selectedInfo)
            val implementation = tabs as JBTabsImpl
            assertTrue("生产容器须开启单行模式", implementation.isSingleRow)
            assertTrue("生产容器须开启压缩", implementation.supportCompression())

            // 不挂显示窗口也可以布局；只有真实 TabLabel 的四个 raster bounds 均为正才断言行高。
            // 仅有宿主 preferredSize 或零尺寸占位时，不构成一行/MorePopup 的视觉证据。
            val holder = JPanel(BorderLayout()).apply { size = Dimension(240, 360) }
            holder.add(tabs.component, BorderLayout.CENTER)
            layoutRecursively(holder)
            waitForEdt()
            val bounds = infos.map { implementation.getTabLabel(it)?.bounds ?: java.awt.Rectangle() }
            if (bounds.all { it.width > 0 && it.height > 0 }) {
                assertEquals("窄栏中四个实际标签的纵向起点应相同：$bounds", 1, bounds.map { it.y }.distinct().size)
            } else {
                println("V182 GEOMETRY ENVIRONMENT-LIMITED: headless TabLabel raster bounds=$bounds; only structure/flags/selection verified")
            }
            tabs.select(infos[2], true)
            waitForEdt()
            assertSame("选择 SQL→Java 后应保留 TabInfo 身份", infos[2], tabs.selectedInfo)
        } finally {
            panel.dispose()
        }
    }

    /** 历史 tab 正常装配：切到该 tab 不抛异常，且挂的是历史面板（兼容性回归）。 */
    fun test切换历史Tab_历史面板正常装配() {
        cleanHistoryDir()
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val tabs = panel.tabsForTest
            val historyInfo = tabs.getTabs().firstOrNull { it.text == MpSqlVisualizerBundle.message("log.tab.history") }
            assertTrue("历史 tab 应在场", historyInfo != null)
            tabs.select(historyInfo!!, true)
            waitForEdt()
            val history = tabs.selectedInfo?.component as? HistorySqlLogPanel
            assertNotNull("切到历史 tab 后应选中历史面板", history)
            // UI-004：只允许真实 JBTabs.select 触发生产监听，不能用模拟 showing 补刷掩盖接线错误。
            assertEquals("进入历史页的真实选中回调应恰好发起一次刷新",
                1, history!!.refreshInvocationCountForTest)
        } finally {
            panel.dispose()
        }
    }

    /**
     * UI-004：设置在隐藏页扫描空目录后，真实页签选择仍须刷新；离开不刷，再进更新。
     * 不创建窗口，不调用 refresh/refreshForTest/模拟 showing；任务派发和列表应用均走生产路径。
     */
    fun testUI004_设置空扫后真实切入刷新_离开不刷_再进入更新() {
        val settings = PluginSettings.getInstance()
        val previousState = settings.state.copy()
        val root = java.nio.file.Files.createTempDirectory("ui004-history-").toFile()
        val oldDir = java.io.File(root, "old").apply { mkdirs() }
        val emptyDir = java.io.File(root, "empty").apply { mkdirs() }
        fun entry(id: Int) = "-- source: Ui004.java:$id | time: 1ms | 2026-10-16 23:43:31.000 --\nSELECT $id;\n\n"
        java.io.File(oldDir, "previous.sql").writeText(entry(0))
        settings.state.logAutoSaveEnabled = false
        settings.state.logAutoSavePath = oldDir.absolutePath
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val tabs = panel.tabsForTest
            val logs = tabs.selectedInfo!!
            val historyInfo = tabs.tabs.single { it.component is HistorySqlLogPanel }
            val history = historyInfo.component as HistorySqlLogPanel
            assertFalse("不挂真实窗口；本测试不能被 showing 首扫兜底救绿", history.isShowing)
            assertEquals("构造不扫描", 0, history.refreshInvocationCountForTest)
            fun publishSettings() {
                com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
                    .syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
                waitForEdt()
            }
            fun awaitFiles(message: String, condition: () -> Boolean) {
                com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching(message, { condition() }, 10)
            }
            // 先建立非空旧快照：后续变空必须由生产扫描真正应用，不能把初始空模型当作完成。
            publishSettings()
            awaitFiles("旧目录扫描应落入模型") { history.fileListEntries.singleOrNull()?.displayName == "previous.sql" }
            settings.state.logAutoSaveEnabled = true
            settings.state.logAutoSavePath = emptyDir.absolutePath
            publishSettings()
            awaitFiles("新空目录扫描必须替换旧快照") { history.fileListEntries.isEmpty() }
            assertSame("设置空扫期间仍停留日志页", logs, tabs.selectedInfo)
            val afterEmptyScan = history.refreshInvocationCountForTest
            assertEquals("两次设置广播各扫描一次", 2, afterEmptyScan)

            val file = java.io.File(emptyDir, "sql-history-2026-10-16.sql")
            file.writeText((1..7).joinToString("") { entry(it) })
            tabs.select(historyInfo, false)
            waitForEdt()
            assertSame(historyInfo, tabs.selectedInfo)
            assertEquals("进入时必须刷新；错误 old/new 顺序会停在空扫计数", afterEmptyScan + 1, history.refreshInvocationCountForTest)
            awaitFiles("首次切入应读到后来产生的7条，不允许手动刷新") {
                history.fileListEntries.singleOrNull()?.let { it.displayName == file.name && it.sqlCount == 7 } == true
            }

            tabs.select(logs, false)
            waitForEdt()
            assertEquals("离开历史页不得刷新", afterEmptyScan + 1, history.refreshInvocationCountForTest)
            file.appendText(entry(8))
            tabs.select(historyInfo, false)
            waitForEdt()
            assertEquals("再次进入必须重新扫描", afterEmptyScan + 2, history.refreshInvocationCountForTest)
            awaitFiles("再次切入应更新为8条") { history.fileListEntries.singleOrNull()?.sqlCount == 8 }
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            settings.loadState(previousState)
            root.deleteRecursively()
        }
    }

    /**
     * T-HistoryTabAlways：设置变更（把自动保存关掉）不再摘除历史 tab——旧实现的运行期门禁会移除它。
     *
     * 构造期先**开着**自动保存（旧实现在此态也会挂载 tab），再关掉并广播：这样红/绿差异只可能来自
     * 「设置变更后摘不摘 tab」这一条分支，而不是构造期门禁（两条门禁的判别点各自独立）。
     */
    fun test设置变更_关闭自动保存后历史Tab不消失() {
        cleanHistoryDir()
        val settings = PluginSettings.getInstance()
        val prevAutoSave = settings.state.logAutoSaveEnabled
        settings.state.logAutoSaveEnabled = true
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val tabs = panel.tabsForTest
            val historyInfo = tabs.getTabs().firstOrNull { it.text == MpSqlVisualizerBundle.message("log.tab.history") }
            assertTrue("前提：自动保存开启时历史 tab 在场", historyInfo != null)
            settings.state.logAutoSaveEnabled = false
            // 等价设置页 apply 后的广播（监听器经 invokeLater 处理）
            project.messageBus.syncPublisher(PluginSettings.SETTINGS_CHANGE_TOPIC).settingsChanged()
            waitForEdt()
            assertTrue(
                "关闭自动保存后历史 tab 不得被移除（旧实现此处会摘掉它）",
                tabs.getTabs().any { it === historyInfo },
            )
            assertEquals("tab 总数不应变化", 4, tabs.getTabCount())
        } finally {
            panel.dispose()
            settings.state.logAutoSaveEnabled = prevAutoSave
        }
    }

    /** R19：历史 tab 常驻——未开启自动保存且无历史文件时 tab 仍在（原 F-44 条件挂载废除），
     *  空态由文件列表引导文案承接（开启自动保存的指引）。 */
    fun testR19_历史tab常驻_未开启自动保存也无历史文件时仍在() {
        runCatching { PluginSettings.getInstance().state.logAutoSaveEnabled = false }
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            val tabs = panel.tabsForTest
            val found = tabs.getTabs().any {
                it.text.contains("历史") || it.text.contains("History")
            }
            assertTrue("未开启自动保存时历史 tab 也应常驻（R19）", found)
        } finally {
            panel.dispose()
        }
    }

    /**
     * T-AgentPauseVisibility（2026-10-19，方案 B）：增强暂停/恢复动作临时全隐藏。
     * 动作对象仍在工具栏组内（能力代码保留），但 update 恒置不可见/不可用；
     * 恢复方案 A 时改回条件可见并更新本用例。
     */
    fun test增强暂停动作在方案B下始终不可见() {
        val panel = SqlLogPanel(project, manager)
        val state = PluginSettings.getInstance().state
        val oldEnabled = state.enhancedCaptureEnabled
        val am = cn.song.mybatisplus.visualizer.log.agent.AgentManager.getInstance(project)
        val oldPaused = am.userPaused
        try {
            for (enabled in listOf(true, false)) {
                for (paused in listOf(true, false)) {
                    state.enhancedCaptureEnabled = enabled
                    am.setUserPaused(paused)
                    val event = AnActionEvent.createFromAnAction(
                        panel.agentPauseAction, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT,
                    )
                    panel.agentPauseAction.update(event)
                    assertFalse(
                        "方案 B：enabled=$enabled paused=$paused 时都不得显示暂停按钮",
                        event.presentation.isVisible,
                    )
                    assertFalse(
                        "方案 B：enabled=$enabled paused=$paused 时都不得可用",
                        event.presentation.isEnabled,
                    )
                }
            }
            assertTrue(
                "动作对象仍应留在工具栏组内（能力代码保留，恢复方案 A 时复用）",
                panel.toolbarGroup.childActionsOrStubs.contains(panel.agentPauseAction),
            )
        } finally {
            state.enhancedCaptureEnabled = oldEnabled
            am.setUserPaused(oldPaused)
            panel.dispose()
        }
    }
}

/** 用户日常侧边栏宽度（2026-09-21 真机截图量测：内容区约 460px）——单行契约的主验宽度。 */
private const val USER_SIDEBAR_WIDTH = 460

/** 图标组宽度的**保守下限**（真机量测约 133：5 枚图标 + 工具栏内边距）。 */
private const val ICON_GROUP_WIDTH_MIN = 120

/** 控制组理想宽上限 = [USER_SIDEBAR_WIDTH] − [ICON_GROUP_WIDTH_MIN]（超了单行就会挤掉图标组）。 */
private const val CONTROLS_WIDTH_BUDGET = USER_SIDEBAR_WIDTH - ICON_GROUP_WIDTH_MIN
