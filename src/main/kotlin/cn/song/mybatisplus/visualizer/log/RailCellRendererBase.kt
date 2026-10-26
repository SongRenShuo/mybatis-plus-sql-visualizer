package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.speedSearch.SpeedSearchUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.border.Border

/**
 * SQL 列表条目单元格渲染器基类（T-LogListRail）。
 *
 * 存在理由：日志页 `LogEntryListCellRenderer` 与历史页 `HistoryCellRenderer` 曾有**三组方法
 * 逐行同构**（`getListCellRendererComponent` / `ensureSqlLineCount` / `sqlLineFragmentsFor`），
 * 每次改版都得人工同步两遍、且容易漏一处。本次改版把共用部分收进本基类，
 * 子类只声明「计数快照从哪取」与「耗时是否显示」两个差异点。
 *
 * 版面（自上而下、自左向右）：
 * ```
 * [色条] [轨道 88px][竖线] [SQL 行…]
 *                          [错误行（有 errorText 才挂）]
 * ```
 * 轨道语义见 [LogEntryRail]。SQL 区宽度必须扣除轨道（见 [sqlAreaWidth]），
 * 否则 SQL 会按偏大的宽度测量、末尾字符被静默吞掉。
 */
internal abstract class RailCellRendererBase : JPanel(BorderLayout()), ListCellRenderer<Any?> {

    /** 左侧信息轨。 */
    internal val rail = LogEntryRail()

    /**
     * 首行兼容别名（存量测试断言序号/时间用）。**只读**——写入请走 [rail]。
     */
    internal val metaLine: SimpleColoredComponent get() = rail.timeLine

    /** SQL 行组件池（T-ListMode：单行模式恒 1 行；多行模式按变换后行数伸缩）。 */
    private val sqlLinePool = mutableListOf(SimpleColoredComponent())

    /** SQL 首行（SINGLE_LINE 恒为唯一 SQL 行；测试断言兼容单行语义）。 */
    internal val sqlLine: SimpleColoredComponent get() = sqlLinePool[0]

    /** 全部 SQL 行（internal 供 UI 测试按行断言多行模式渲染）。 */
    internal val sqlLines: List<SimpleColoredComponent> get() = sqlLinePool

    private val linesPanel = JPanel(GridLayout(0, 1)).apply { isOpaque = false }

    /**
     * T-CrudColor：左侧类型色条（[SqlListTypeColor.STYLE_BAR] 下着色可见；
     * 其他样式 `isOpaque=false` 但仍**占位**——占位是刻意的：切换样式时文本左缘不位移）。
     * T-LogListRail：类型只靠本色条表达，不再加「查/增/改/删」字样。
     */
    internal val colorBar = JPanel().apply {
        preferredSize = Dimension(SqlListTypeColor.BAR_WIDTH, 1)
        isOpaque = false
    }

    /** 未选中态 Border（分隔线 + 内边距；T-UN-09 描边退役后选中态同样只用它，选中视觉归平台绘制）。 */
    internal val normalBorder: Border = BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
        javax.swing.BorderFactory.createEmptyBorder(5, 5, 5, 8), // 左 5 + 色条 3 = 原 8
    )

    /** 轨道与 SQL 区之间的 1px 竖线。 */
    private val railSeparator = JPanel().apply {
        preferredSize = Dimension(RAIL_SEPARATOR_WIDTH, 1)
        isOpaque = false
    }

    /** 轨道容器：轨道（固定宽）+ 竖线，整体挂内容区 WEST。 */
    private val railHost = JPanel(BorderLayout()).apply { isOpaque = false }

    /** SQL 区容器：SQL 行（CENTER）+ 错误行（SOUTH）。 */
    private val sqlHost = JPanel(BorderLayout()).apply { isOpaque = false }

    /** 内容区：轨道（WEST）+ SQL 区（CENTER）。 */
    private val content = JPanel(BorderLayout()).apply { isOpaque = false }

    /**
     * 错误行（全宽红字；有 errorText 才挂载）。报错文本远比轨道宽，塞进 88px 轨道会整段被截成
     * `⛔ ER…`、等于丢信息——故挂在 SQL 区下方通栏。
     */
    private val errorLine = SimpleColoredComponent().apply { isOpaque = false }

    /**
     * 注脚行（全宽灰字）：目标库角标、MCP 徽标、影响行数、去重计数、同结构计数。
     *
     * 这些片段原来都在「元信息行」里，与时间/耗时/来源排在一起。改版后时间/耗时/来源进了 88px
     * 轨道，而它们**宽度不可控**（`[MySQL · jpa_demo@localhost:3306]`、`⛔ Data too long for
     * column 'a'` 之类），塞进轨道会被截成无意义的残片。故统一改挂 SQL 区下方通栏：
     * 既保留全部信息，也不占轨道宽度。无内容时不占高度。
     */
    private val detailLine = SimpleColoredComponent().apply { isOpaque = false }

    private var errorMounted = false
    private var detailMounted = false

    /** 同模板计数快照（子类提供取值来源）。 */
    protected abstract fun templateCountsSnapshot(): Map<String, Int>

    /** 可见条目最大耗时快照（进度条归一基准；子类提供取值来源）。 */
    protected abstract fun maxDurationMsSnapshot(): Long?

    /** 是否显示耗时（日志页/历史页各读设置；历史上历史页曾硬编码 true）。 */
    protected abstract fun showDuration(): Boolean

    /** 元信息行渲染文本（测试断言用）。 */
    val metaTextForTest: String get() = rail.textForTest

    /** 注脚行渲染文本（测试断言用：角标/影响行数/徽章都在这里）。 */
    val detailTextForTest: String
        get() = buildString {
            val it = detailLine.iterator()
            while (it.hasNext()) append(it.next())
        }.trim()

    /** 错误行渲染文本（测试断言用）。 */
    val errorTextForTest: String
        get() = buildString {
            val it = errorLine.iterator()
            while (it.hasNext()) append(it.next())
        }.trim()

    init {
        layout = BorderLayout()
        isOpaque = true
        sqlLinePool[0].isOpaque = false
        linesPanel.add(sqlLinePool[0])
        errorLine.isOpaque = false

        railHost.add(rail, BorderLayout.CENTER)
        railHost.add(railSeparator, BorderLayout.EAST)
        railHost.preferredSize = Dimension(LogEntryRail.RAIL_WIDTH + RAIL_SEPARATOR_WIDTH, 1)
        sqlHost.add(linesPanel, BorderLayout.CENTER)
        content.add(railHost, BorderLayout.WEST)
        content.add(sqlHost, BorderLayout.CENTER)

        // T-CrudColor：色条挂 WEST；T-LogListRail：内容区占满其余（SQL 区拿剩余宽度，轨道固定宽）
        add(colorBar, BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
        border = normalBorder
    }

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val entry = value as? LogSqlEntry

        // T-CrudColor：标记形态分派（读设置 + 纯函数；热路径零分配）
        val tint = entry?.let { SqlListTypeColor.colorOf(it.statementType) }
        // T-UN-09：选中描边退役后，三种样式的选中行统一走平台选中填充
        val useSelectionFill = isSelected
        applyCrudColorMark(this, colorBar, normalBorder, tint, isSelected, list)

        val fg = if (useSelectionFill) (list?.selectionForeground ?: JBColor.foreground())
        else (list?.foreground ?: JBColor.foreground())

        val mode = PluginSettings.getInstance().sqlListDisplayMode()
        val lineFragments = if (entry != null) {
            val width = list?.width ?: 0
            rail.setRailWidth(railWidthFor(width))
            // 热路径守卫：preferredSize 赋值会触发 propertyChange → revalidate。只在轨道宽度真变化时设，
            // 否则每次滚动/重绘都新建 Dimension 并调度一次重排（T-LogListFreeze 同族问题）。
            val hostWidth = rail.railWidth + RAIL_SEPARATOR_WIDTH
            if (railHost.preferredSize.width != hostWidth) {
                railHost.preferredSize = Dimension(hostWidth, 1)
            }
            rail.setForm(railFormFor(mode))
            rail.bind(
                entry = entry,
                isSelected = isSelected,
                maxDurationMs = maxDurationMsSnapshot(),
                foreground = fg,
                showDuration = showDuration(),
            )
            bindDetailLine(entry, isSelected, fg, useSelectionFill, list)
            bindErrorLine(entry, isSelected, fg, useSelectionFill, list)
            sqlLineFragmentsFor(entry, mode)
        } else null

        // SQL 区宽度 = 列表可视宽 - 单元格内边距 - 色条 - 轨道 - 竖线
        val sqlWidth = sqlAreaWidth(list, this, if (entry != null) rail.railWidth else 0)
        ensureSqlLineCount(if (lineFragments != null) lineFragments.size else 1)
        sqlLinePool.forEachIndexed { i, comp ->
            comp.foreground = fg
            comp.clear()
            lineFragments?.getOrNull(i)?.let { fragments ->
                clipSqlFragmentsToWidth(fragments, list, sqlWidth)
                    .forEach { f -> comp.append(f.text, if (isSelected) selectedAttributes(f.attributes, fg) else f.attributes) }
            }
            list?.let { l -> SpeedSearchUtil.applySpeedSearchHighlighting(l, comp, false, useSelectionFill) }
        }
        // T-ClickPreview：不设 toolTipText（悬停逐行弹气泡 = 条目多时卡顿根因），预览改单击触发
        return this
    }

    /** 去重计数快照（仅日志页有去重；历史页恒空）。 */
    protected open fun dedupeCountsSnapshot(): Map<String, Int> = emptyMap()

    /**
     * 注脚行文本缓存（T-LogListRail 性能守卫）。
     *
     * 为什么需要缓存：`details()` 每次调用都要做 3~5 次片段构造（数据库名切分、影响行数格式化、
     * 模板计数查表）+ `buildList` 分配。渲染器**每格绘制都跑一遍**，滚动时一屏 20 格 × 每秒多帧，
     * 在流式灌入（每秒数百条）的场景下会变成可观测的 GC 压力——这正是 T-LogListFreeze 系列
     * 反复出问题的形态。注脚内容只取决于（条目身份 + 是否选中 + 两份计数快照），故按条目缓存。
     *
     * 缓存键用条目引用 + 计数快照引用 + 选中态：计数快照换代（refreshList 每次重建 Map）即失效，
     * 条目被替换（同位置新条目）也因引用不同而失效——不会串味。
     */
    private var detailCacheEntry: LogSqlEntry? = null
    private var detailCacheSelected = false
    private var detailCacheTemplates: Map<String, Int>? = null
    private var detailCacheDedupe: Map<String, Int>? = null
    private var detailCacheFragments: List<SqlTextFragment> = emptyList()

    /**
     * 注脚行绑定：目标库角标 / MCP 徽标 / 影响行数 / 去重计数 / 同结构计数。
     *
     * 这些片段宽度不可控（连接串、影响行数、计数徽章），进不了 88px 轨道，统一改挂 SQL 区下方通栏。
     * 顺序沿用改造前的 [SqlEntryPresentation.metadata] 口径（库 → MCP → 影响行数 → 去重 → 同结构），
     * 保证老用户视觉记忆连续；日期时间与耗时已由轨道承接，故不重复出现在注脚里。
     */
    private fun bindDetailLine(
        entry: LogSqlEntry,
        isSelected: Boolean,
        fg: java.awt.Color,
        useSelectionFill: Boolean,
        list: JList<*>?,
    ) {
        val templates = templateCountsSnapshot()
        val dedupe = dedupeCountsSnapshot()
        val fragments = if (
            detailCacheEntry === entry &&
            detailCacheSelected == isSelected &&
            detailCacheTemplates === templates &&
            detailCacheDedupe === dedupe
        ) {
            detailCacheFragments
        } else {
            SqlEntryPresentation.details(
                entry,
                selected = isSelected,
                templateCounts = templates,
                dedupeCounts = dedupe,
            ).also {
                detailCacheEntry = entry
                detailCacheSelected = isSelected
                detailCacheTemplates = templates
                detailCacheDedupe = dedupe
                detailCacheFragments = it
            }
        }
        detailLine.foreground = fg
        detailLine.clear()
        fragments.forEach {
            detailLine.append(it.text, if (isSelected) selectedAttributes(it.attributes, fg) else it.attributes)
        }
        val shouldMount = fragments.isNotEmpty()
        if (detailMounted != shouldMount) {
            detailMounted = shouldMount
            if (shouldMount) sqlHost.add(detailLine, BorderLayout.SOUTH) else sqlHost.remove(detailLine)
            sqlHost.revalidate()
        }
        if (shouldMount) {
            list?.let { SpeedSearchUtil.applySpeedSearchHighlighting(it, detailLine, false, useSelectionFill) }
        }
    }

    /**
     * 错误行绑定（M56 T-M56-05 语义保留）。
     *
     * 注意 P2：轨道拆成多个 [SimpleColoredComponent] 后，speed search 高亮需逐组件调用；
     * 本基类已对 [rail] 内各行与 SQL 行分别施加，漏一处只会「不亮」、不报错。
     */
    private fun bindErrorLine(
        entry: LogSqlEntry,
        isSelected: Boolean,
        fg: java.awt.Color,
        useSelectionFill: Boolean,
        list: JList<*>?,
    ) {
        val fragment = formatErrorFragment(entry)
        errorLine.foreground = fg
        errorLine.clear()
        if (fragment != null) {
            errorLine.append(
                fragment.first.trimEnd(),
                if (isSelected) selectedAttributes(fragment.second, fg) else fragment.second,
            )
        }
        val shouldMount = fragment != null
        if (errorMounted != shouldMount) {
            errorMounted = shouldMount
            if (shouldMount) sqlHost.add(errorLine, BorderLayout.SOUTH) else sqlHost.remove(errorLine)
            sqlHost.revalidate()
        }
        if (shouldMount) {
            list?.let { SpeedSearchUtil.applySpeedSearchHighlighting(it, errorLine, false, useSelectionFill) }
        }
    }

    /** SQL 行组件池伸缩到 n 行（首位恒保留——[sqlLine] 测试缝；收缩移除末尾）。 */
    private fun ensureSqlLineCount(n: Int) {
        while (sqlLinePool.size < n) {
            val comp = SimpleColoredComponent()
            comp.isOpaque = false
            sqlLinePool += comp
            linesPanel.add(comp)
        }
        while (sqlLinePool.size > maxOf(1, n)) {
            linesPanel.remove(sqlLinePool.removeAt(sqlLinePool.size - 1))
        }
    }

    /**
     * SQL 行片段（T-ListMode 模式分流）：SINGLE_LINE 直渲染不缓存（固定行高 + 主题切换实时生效）；
     * 多行模式走条目级缓存（键含模式+脱敏态，任一变化即重算）。
     */
    private fun sqlLineFragmentsFor(entry: LogSqlEntry, mode: SqlListDisplayMode): List<List<SqlTextFragment>> {
        // R60：展示文本打码、原始条目不动；缓存以模式和脱敏态为键，防切换后复用旧语义片段。
        val maskOn = PluginSettings.getInstance().state.displayMaskEnabled
        val sql = withTerminator(displaySqlForList(entry, mode, maskOn))
        if (mode == SqlListDisplayMode.SINGLE_LINE) {
            return listOf(SqlListFragmentRenderer.renderFragments(sql))
        }
        val semantics = entry.cachedListLineFragments
            ?.takeIf { entry.cachedListLineFragmentsMode == mode && entry.cachedListLineFragmentsMasked == maskOn }
            ?: SqlListFragmentRenderer.semanticLineFragments(sql, mode).also {
                entry.cachedListLineFragments = it
                entry.cachedListLineFragmentsMode = mode
                entry.cachedListLineFragmentsMasked = maskOn
            }
        return visibleSqlLines(SqlListFragmentRenderer.projectLineFragments(semantics), mode)
    }
}
