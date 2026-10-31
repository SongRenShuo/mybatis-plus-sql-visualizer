package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.speedSearch.SpeedSearchUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.border.Border

/**
 * SQL 列表条目单元格渲染器基类（T-LogListHeader）。
 *
 * 版面（自上而下）：
 * ```
 * [色条] #序号 时间 耗时 [库] 类名:行号 (行数) 同结构 N 条   ← 头部行（全部元信息）
 *        SELECT ...                                        ← SQL 第 1 行
 *        FROM ...
 *        WHERE ...
 *        ⛔ 报错（仅 errorText 存在时）
 * ```
 *
 * 演变：`bed58b64` 的 88px 左侧信息轨 + 进度条 + 竖线已全部撤除（用户实测否决：占宽度、
 * 与 SQL 不同轴、扫视要左右来回）。元信息改为头部一行，SQL 区拿整行宽度。
 *
 * 条目分割：靠**上下各 12px 留白 + 底部 1px 略强色线**（见 [ROW_VERTICAL_PADDING]）。
 * 不画斑马纹——深色主题下极淡底色辨识度不足，用户明确否决。
 *
 * 头部行超宽时走 [clipSqlFragments] 的**顺序截断**（从左到右、超出即停、末尾补 `…`）——
 * 与 `2026.10.23` 版本行为一致。曾一度实现「按优先级丢片段」的退化梯度，用户实测否决
 * （「类名为什么要省略，还不如 2026.10.23 版本的」），已整段删除。
 */
internal abstract class RailCellRendererBase : JPanel(BorderLayout()), ListCellRenderer<Any?> {

    /** 头部行（序号/时间/耗时/库/MCP/来源/影响行数/徽章）。 */
    internal val headerLine = SimpleColoredComponent()

    /**
     * 首行兼容别名（存量测试断言元信息用）。**只读**——写入请走 [headerLine]。
     */
    internal val metaLine: SimpleColoredComponent get() = headerLine

    /** SQL 行组件池（T-ListMode：单行模式恒 1 行；多行模式按变换后行数伸缩）。 */
    private val sqlLinePool = mutableListOf(SimpleColoredComponent())

    /** SQL 首行（SINGLE_LINE 恒为唯一 SQL 行；测试断言兼容单行语义）。 */
    internal val sqlLine: SimpleColoredComponent get() = sqlLinePool[0]

    /** 全部 SQL 行（internal 供 UI 测试按行断言多行模式渲染）。 */
    internal val sqlLines: List<SimpleColoredComponent> get() = sqlLinePool

    private val linesPanel = JPanel(GridLayout(0, 1)).apply { isOpaque = false }

    /**
     * T-CrudColor：左侧类型色条（[SqlListTypeColor.STYLE_BAR] 下着色可见；
     * 其他样式 `isOpaque=false` 但仍**占位**——切换样式时文本左缘不位移）。
     * 类型只靠本色条表达，不加「查/增/改/删」字样。
     */
    internal val colorBar = TypeColorBar().apply {
        preferredSize = Dimension(SqlListTypeColor.BAR_WIDTH, 1)
        isOpaque = false
    }

    /**
     * 未选中态 Border：上下留白 + 底部 1px 分隔线。
     *
     * 留白是分条主力——改造前上下各 5px，而条目内 SQL 行距也是 1.45 倍行高，
     * 导致「条目内间距 ≈ 条目间距」，眼睛找不到切分点（用户原话「容易混在一起」）。
     * 分隔线用比 `JBColor.border()` 略强的色，深色主题下才看得见。
     *
     * **留白按模式分流**：单行模式本身是「一屏塞更多条」的密度模式，若也吃 12px 上下留白，
     * 单条从 ~45px 涨到 68px（一屏少看 4 条），与它的用途冲突——故单行用
     * [ROW_VERTICAL_PADDING_DENSE]。两个 Border 实例**预建**，渲染热路径不新建 Border
     * （T-LogListFreeze 同族守卫）。
     */
    internal val normalBorder: Border = borderFor(ROW_VERTICAL_PADDING)

    /** 单行模式用的紧凑 Border（上下 [ROW_VERTICAL_PADDING_DENSE]）。 */
    private val compactBorder: Border = borderFor(ROW_VERTICAL_PADDING_DENSE)

    /** 内容区：头部行（NORTH）+ SQL 行（CENTER）+ 错误行（SOUTH，按需挂载）。 */
    private val content = JPanel(BorderLayout()).apply { isOpaque = false }

    /** 头部行与 SQL 行之间的细分隔留白（纯视觉呼吸，不加线）。 */
    private val headerGap = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(1, HEADER_GAP)
    }

    /** SQL 区容器：SQL 行（CENTER）+ 错误行（SOUTH）。 */
    private val sqlHost = JPanel(BorderLayout()).apply { isOpaque = false }

    /**
     * 错误行（全宽红字；有 errorText 才挂载）。报错文本长度不可控，仍需通栏而不是塞进头部行。
     */
    private val errorLine = SimpleColoredComponent().apply { isOpaque = false }

    private var errorMounted = false

    /** 同模板计数快照（子类提供取值来源）。 */
    protected abstract fun templateCountsSnapshot(): Map<String, Int>

    /** 可见条目最大耗时快照（子类提供取值来源）。 */
    protected abstract fun maxDurationMsSnapshot(): Long?

    /** 是否显示耗时（日志页/历史页各读设置）。 */
    protected abstract fun showDuration(): Boolean

    /** 元信息渲染文本（测试断言用）。 */
    val metaTextForTest: String
        get() = buildString {
            val it = headerLine.iterator()
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
        headerLine.isOpaque = false
        sqlLinePool[0].isOpaque = false
        errorLine.isOpaque = false

        sqlHost.add(linesPanel, BorderLayout.CENTER)
        linesPanel.add(sqlLinePool[0])

        content.add(headerLine, BorderLayout.NORTH)
        content.add(headerGap, BorderLayout.CENTER)
        content.add(sqlHost, BorderLayout.SOUTH)

        // 色条挂 WEST；内容区占满其余（SQL 拿整行宽度，不再有固定宽侧栏）
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
    ): java.awt.Component {
        val entry = value as? LogSqlEntry

        // T-CrudColor：标记形态分派（读设置 + 纯函数；热路径零分配）
        val tint = entry?.let { SqlListTypeColor.colorOf(it.statementType) }
        val useSelectionFill = isSelected
        // 留白按模式分流：单行模式密度优先（见 normalBorder KDoc）
        val mode = PluginSettings.getInstance().sqlListDisplayMode()
        val activeBorder = if (mode == SqlListDisplayMode.SINGLE_LINE) compactBorder else normalBorder
        applyCrudColorMark(
            cell = this,
            colorBar = colorBar,
            normalBorder = activeBorder,
            tint = tint,
            isSelected = isSelected,
            list = list,
            dashed = entry != null && SqlListTypeColor.isDdl(entry.statementType),
        )

        val fg = if (useSelectionFill) (list?.selectionForeground ?: JBColor.foreground())
        else (list?.foreground ?: JBColor.foreground())
        val lineFragments = if (entry != null) {
            bindHeaderLine(entry, isSelected, fg, useSelectionFill, list)
            bindErrorLine(entry, isSelected, fg, useSelectionFill, list)
            sqlLineFragmentsFor(entry, mode)
        } else null

        // SQL 区宽度 = 列表可视宽 - 单元格内边距 - 色条（不再有侧栏要扣）
        val sqlWidth = sqlAreaWidth(list, this)
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
     * 头部行绑定：全部元信息片段，按用户定案的「库前置」顺序。
     *
     * 顺序：`#序号 · 时间 · 耗时 · [库] · [MCP] · 类名:行号 · (影响行数) · ⨉去重 · 同结构 N 条`
     *
     * 为什么库在类名之前（用户 2026-10-26 定案方案④）：
     *  - 库角标是**定长短串**（`[MySQL]` / `[PostgreSQL]`），位置固定，扫视时形成稳定锚点；
     *    类名长度随内容浮动，放它之后，宽度不足时先裁它不破坏前几列的稳定性。
     *  - 多数据源场景下「这条打的是哪个库」是判定 SQL 是否走对源的第一判据。
     */
    private fun bindHeaderLine(
        entry: LogSqlEntry,
        isSelected: Boolean,
        fg: java.awt.Color,
        useSelectionFill: Boolean,
        list: JList<*>?,
    ) {
        // 券01（T-PerfSweep27-01）：静态段/来源段按条目缓存，键含全部相关设置维度（键不等即重算，
        // 显示格式严格跟随设置）；⨉去重与×N 同结构徽章是会话级可变输入，恒每次算。
        val cacheKey = SqlEntryPresentation.headerStaticCacheKey(showDuration())
        if (entry.cachedHeaderKey != cacheKey || entry.cachedHeaderCore == null) {
            entry.cachedHeaderCore = SqlEntryPresentation.headerStaticFragments(entry, showDuration())
            entry.cachedHeaderSource = SqlEntryPresentation.headerSourceFragments(entry)
            entry.cachedHeaderKey = cacheKey
        }
        val fragments = entry.cachedHeaderCore.orEmpty() +
            SqlEntryPresentation.headerBadgeFragments(
                entry = entry,
                templateCounts = templateCountsSnapshot(),
                dedupeCounts = dedupeCountsSnapshot(),
            ) +
            entry.cachedHeaderSource.orEmpty()
        val width = sqlAreaWidth(list, this)
        headerLine.foreground = fg
        headerLine.clear()
        clipSqlFragmentsToWidth(fragments, list, width).forEach {
            headerLine.append(it.text, if (isSelected) selectedAttributes(it.attributes, fg) else it.attributes)
        }
        list?.let { SpeedSearchUtil.applySpeedSearchHighlighting(it, headerLine, false, useSelectionFill) }
    }

    /**
     * 错误行绑定（M56 T-M56-05 语义保留）。
     *
     * 报错文本远比头部行其他片段长（`⛔ ERROR: relation "user" does not exist` 量级），
     * 混进头部行会把其余元信息全挤掉——故独立成行，只有 errorText 存在时才占高度。
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
     * 多行模式走条目级缓存（键含模式+脱敏模式，任一变化即重算）。
     */
    private fun sqlLineFragmentsFor(entry: LogSqlEntry, mode: SqlListDisplayMode): List<List<SqlTextFragment>> {
        // R60 → T-MagpieGems W2：展示文本按脱敏模式渲染、原始条目不动；缓存以显示模式和
        // 脱敏模式（OFF/WARN 原样、MASK 打码、BLOCK 占位四态互异）为键，防切换后复用旧语义片段。
        // UI-001 修复：白名单实参贯穿列表显示链；语义缓存键并入白名单（改白名单即重算）。
        val maskMode = PluginSettings.getInstance().displayMaskMode()
        val allowList = PluginSettings.getInstance().displayMaskAllowList()
        val maskCacheKey = maskMode.name + "|" + allowList.joinToString(",")
        if (mode == SqlListDisplayMode.SINGLE_LINE) {
            listSourceFetchCount++
            val sql = withTerminator(displaySqlForList(entry, mode, maskMode, allowList))
            return listOf(SqlListFragmentRenderer.renderFragments(sql))
        }
        // R29-1（T-PerfSweep29-01）：取源只在缓存未命中时执行——脱敏开时 displaySqlForList
        // 内部付 O(SQL 长) 全量词法打码，命中路径每帧重取源后整段丢弃纯属白付。
        val semantics = entry.cachedListLineFragments
            ?.takeIf { entry.cachedListLineFragmentsMode == mode && entry.cachedListLineFragmentsMaskMode == maskCacheKey }
            ?: run {
                listSourceFetchCount++
                SqlListFragmentRenderer.semanticLineFragments(
                    withTerminator(displaySqlForList(entry, mode, maskMode, allowList)),
                    mode,
                )
            }.also {
                entry.cachedListLineFragments = it
                entry.cachedListLineFragmentsMode = mode
                entry.cachedListLineFragmentsMaskMode = maskCacheKey
            }
        return visibleSqlLines(SqlListFragmentRenderer.projectLineFragments(semantics), mode)
    }

    companion object {
        /** 条目上下留白（px）——分条主力手段。改造前是 5，实测条目内行距≈条目间距导致混在一起。 */
        internal const val ROW_VERTICAL_PADDING = 12

        /** 单行模式的紧凑留白（px）——密度优先，不能吃满 12px。 */
        internal const val ROW_VERTICAL_PADDING_DENSE = 8

        /** 头部行与 SQL 首行之间的额外呼吸（px）。 */
        internal const val HEADER_GAP = 3

        /** 条目分隔线——比 `JBColor.border()` 略强，深色主题下才可见。 */
        internal val ROW_DIVIDER: java.awt.Color =
            JBColor.namedColor("mpvs.log.rowDivider", 0xC8C8C8, 0x4A4D52)

    /** 按留白高度构造条目 Border（预建实例，不在渲染热路径调用）。 */
    internal fun borderFor(verticalPadding: Int): Border = BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, ROW_DIVIDER),
        javax.swing.BorderFactory.createEmptyBorder(verticalPadding, 5, verticalPadding, 9),
    )

    /**
     * 券01（T-PerfSweep29-01）测试缝：列表渲染取源（displaySqlForList）实际调用次数。
     * 先红判据（多行缓存命中路径零取源）与反向验证共用；生产链路无读取方，零行为影响
     * （形态对齐 SqlEntryPresentation.headerCoreComputeCount 先例，EDT 单线程读写）。
     */
    internal var listSourceFetchCount = 0
    }
}

/**
 * SQL 区可用像素宽度。
 *
 * 改造后不再有固定宽侧栏，只扣单元格内边距与色条。
 */
internal fun sqlAreaWidth(list: JList<*>?, cell: JPanel): Int {
    if (list == null || list.width <= 0) return 0
    return list.width - cell.insets.left - cell.insets.right -
        SqlListTypeColor.BAR_WIDTH - list.insets.left - list.insets.right
}

/** 按给定像素宽度裁切片段（宽度已由调用方算好时的入口）。 */
internal fun clipSqlFragmentsToWidth(
    fragments: List<SqlTextFragment>,
    list: JList<*>?,
    maxWidth: Int,
): List<SqlTextFragment> {
    val baseFont = list?.font ?: return fragments
    val metrics = HashMap<Int, java.awt.FontMetrics>(3)
    return clipSqlFragments(fragments, maxWidth) { text, attributes ->
        val style = (if (attributes.style and SimpleTextAttributes.STYLE_BOLD != 0) java.awt.Font.BOLD else 0) or
            (if (attributes.style and SimpleTextAttributes.STYLE_ITALIC != 0) java.awt.Font.ITALIC else 0)
        metrics.getOrPut(style) { list.getFontMetrics(baseFont.deriveFont(style)) }.stringWidth(text)
    }
}
