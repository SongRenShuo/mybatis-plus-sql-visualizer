package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JComponent
import javax.swing.JList

/**
 * SQL 日志/历史列表「冻结根因」防回退守卫（T-TestGuard-06，T-LogListFreeze 家族收口）。
 *
 * EDT 冻结三因叠加（pitfall-sqllogpanel-edt-freeze-html-render / pitfall-swing-html-list-render /
 * project-ide-freeze-20260824）：DefaultListModel 逐条事件风暴 × JLabel.setText("<html>")
 * 逐行 BasicHTML 解析 × fixedCellHeight=-1 可变行高逐条测 preferred size。
 *
 * 红线（T-LogListFreeze-04 定稿语义）：
 *  1. 渲染器必须是本插件 SimpleColoredComponent 系片段通道（JLabel-HTML 通道不允许回潮）；
 *  2. SINGLE_LINE 模式下 fixedCellHeight 必须为正（O(1) 布局快路径）；
 *     KEYWORD_WRAP/FORMATTED 允许 -1 可变行高（条目行数不等），但渲染器须守住红线 1
 *     （多行片段有条目级缓存，无 HTML 解析）。
 *
 * 实现注记：JBList 会把面板设置的渲染器包一层 ExpandedItemListCellRendererWrapper
 * （ExpandableItemsHandler 委托机制），断言前须解包验委托；历史面板的列表挂树时机在
 * 首次进入 SQL 视图之后，行高断言走面板自带的 *ForTest 访问器而非组件树遍历。
 * 渲染器逐项行为已有 SqlLogPanelRendererUiTest / HistoryCellRendererTest 锁定。
 */
class SqlLogListFreezeGuardTest : BasePlatformTestCase() {

    /** 解开平台 ExpandableItems 包装，取真实委托渲染器（wrapper 公开静态 unwrap）。 */
    private fun effectiveRenderer(list: JList<*>): Any? =
        com.intellij.ui.ExpandedItemListCellRendererWrapper.unwrap(list.cellRenderer)

    /** 深度优先收集面板内全部 JList。 */
    private fun allLists(component: JComponent): List<JList<*>> {
        val found = mutableListOf<JList<*>>()
        fun walk(c: java.awt.Component) {
            if (c is JList<*>) found.add(c)
            if (c is JComponent) c.components.forEach { walk(it) }
        }
        walk(component)
        return found
    }

    /** 临时切 SINGLE_LINE 模式跑断言（结束后恢复），避免依赖用户默认模式。 */
    private fun withSingleLineMode(block: () -> Unit) {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logListSqlDisplayMode
        settings.state.logListSqlDisplayMode = SqlListDisplayMode.SINGLE_LINE.name
        try {
            block()
        } finally {
            settings.state.logListSqlDisplayMode = old
        }
    }

    fun testSQL日志列表_单行模式固定行高加片段通道渲染器() {
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            // 只认「片段通道渲染器所归属的条目列表」：面板组件树里可能内嵌其他子面板的列表
            // （如历史文件列表用 HistoryFileCellRenderer），不属于本守卫对象。
            // 渲染器若回潮成 JLabel-HTML 通道，本过滤即落空 → 守卫红。
            val entryLists = allLists(panel).filter { effectiveRenderer(it) is LogEntryListCellRenderer }
            assertTrue(
                "面板内应有片段通道（LogEntryListCellRenderer）渲染的 SQL 条目 JList——" +
                    "渲染器回潮即在此暴露；实际: " +
                    allLists(panel).map { effectiveRenderer(it)?.javaClass?.name },
                entryLists.isNotEmpty(),
            )
            // 红线 2：SINGLE_LINE 模式固定行高（O(1) 布局快路径）
            withSingleLineMode {
                panel.applyListCellHeight()
                for (list in entryLists) {
                    assertTrue(
                        "SINGLE_LINE 模式 SQL 日志列表 fixedCellHeight 必须为正" +
                            "（-1 = O(N²) 行高测量冻结回潮），实际 ${list.fixedCellHeight}",
                        list.fixedCellHeight > 0,
                    )
                }
            }
        } finally {
            panel.dispose()
            manager.dispose()
        }
    }

    fun test历史列表_单行模式固定行高() {
        val panel = HistorySqlLogPanel(project)
        try {
            withSingleLineMode {
                panel.applySqlListCellHeight()
                assertTrue(
                    "SINGLE_LINE 模式历史列表 fixedCellHeight 必须为正，实际 ${panel.sqlListFixedCellHeightForTest}",
                    panel.sqlListFixedCellHeightForTest > 0,
                )
            }
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun test日志与历史SQL列表跟随视口宽度且不横向滚动() {
        val manager = SqlLogManager(project)
        val logPanel = SqlLogPanel(project, manager)
        val historyPanel = HistorySqlLogPanel(project)
        try {
            for ((panel, sqlList) in listOf(logPanel to logPanel.list, historyPanel to historyPanel.sqlListForTest)) {
                assertTrue("SQL 条目列表应跟随视口宽度", sqlList.scrollableTracksViewportWidth)
                val scroll = findScrollPane(panel, sqlList)
                assertTrue("SQL 列表滚动宿主应存在", scroll != null)
                assertTrue(
                    "SQL 条目不能靠水平滚动阅读",
                    scroll?.horizontalScrollBarPolicy == javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
                )
            }
        } finally {
            historyPanel.dispose()
            logPanel.dispose()
            manager.dispose()
        }
    }

    private fun findScrollPane(root: JComponent, list: JList<*>): javax.swing.JScrollPane? {
        fun walk(component: java.awt.Component): javax.swing.JScrollPane? {
            if (component is javax.swing.JScrollPane && component.viewport.view === list) return component
            if (component is JComponent) component.components.forEach { child -> walk(child)?.let { return it } }
            return null
        }
        return walk(root)
    }

    fun testSpeedSearch匹配高亮锚点_共用渲染基类() {
        // T-UiNative f65d9ae：speed search 的匹配片段高亮是渲染器职责
        //（SpeedSearchUtil.applySpeedSearchHighlighting 施加在自定义渲染的各文本组件上；
        // pitfall-speedsearch-highlight-renderer：setDynamicSearchMatchHighlighting 并不存在）。
        // 面板新增渲染路径时若漏施高亮，搜索命中无标记且难察觉——以「施加点数量」为结构锚点锁住。
        //
        // T-LogListRail（2026-10-26）：日志页与历史页渲染器曾各持一份同构实现（每文件 2 处施加点），
        // 改版时收进共用基类。锚点随之从「两个渲染器文件各 ≥2 处」改为「基类 ≥3 处」，且
        // **子类不得自带施加点**——自带即说明出现了绕过基类的新渲染路径，那正是本守卫要防的漏施加情形。
        // k2j 移植注：master 的渲染器是同文件顶层类（LogEntryRendering.kt / HistorySqlLogPanel.kt 内），
        // Java 侧基类与两个子类各自独立成文件——子类锚点用渲染器文件本体（语义不变）。
        val basePath = "src/main/java/cn/song/mybatisplus/visualizer/log/RailCellRendererBase.java"
        val baseFile = java.io.File(basePath)
        assertTrue("找不到基类源文件 $basePath（工作目录=${java.io.File(".").absolutePath}）", baseFile.isFile)
        val baseCount = Regex("SpeedSearchUtil\\.applySpeedSearchHighlighting").findAll(baseFile.readText()).count()
        assertTrue(
            "共用渲染基类的 speed search 高亮施加点不足（$baseCount < 3）——轨道行/注脚行/错误行/SQL 行" +
                "均须施 SpeedSearchUtil.applySpeedSearchHighlighting，否则搜索命中无高亮（f65d9ae 教训）",
            baseCount >= 3,
        )

        val subclassPaths = mapOf(
            "src/main/java/cn/song/mybatisplus/visualizer/log/LogEntryListCellRenderer.java" to "SQL 日志主列表渲染器",
            "src/main/java/cn/song/mybatisplus/visualizer/log/HistoryCellRenderer.java" to "历史二级 SQL 列表渲染器",
        )
        for ((path, label) in subclassPaths) {
            val f = java.io.File(path)
            assertTrue("找不到源文件 $path", f.isFile)
            val count = Regex("SpeedSearchUtil\\.applySpeedSearchHighlighting").findAll(f.readText()).count()
            assertEquals(
                "$label 不应自带 speed search 施加点（现 $count 处）——渲染路径须统一走共用基类，" +
                    "自带即意味着新增渲染路径可能漏施高亮",
                0,
                count,
            )
        }
    }
}
