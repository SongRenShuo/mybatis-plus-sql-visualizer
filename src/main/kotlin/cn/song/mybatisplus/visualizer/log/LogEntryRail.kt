package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettings.Companion.LOG_TIMESTAMP_SOURCE_LOG
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * 列表条目左侧信息轨（T-LogListRail）。
 *
 * 版面动机：原两行卡片把「时间/耗时/库/来源」平铺在 SQL 之上，每段宽度随内容浮动、无法竖扫，
 * 且 SQL 实际可用宽度被元信息挤压。信息轨把这四条锚点收进固定宽度竖列，SQL 区拿到其余宽度。
 *
 * 列语义（自上而下）：
 *  - `#N HH:mm:ss`　序号 + 时刻（日期不进轨道——单次会话跨天罕见，完整时间戳仍在预览/详情）
 *  - `53ms`　　　　 耗时，按阈值分档着色（与 [formatDurationFragment] 同档）
 *  - 2px 进度条　　 按当前列表**可见条目**最大耗时归一，一眼看出谁慢
 *  - `…alletRecharge` 来源类名（骨架化压缩，见 [railSourceName]）
 *  - 徽章行　　　　 `⨉4` / `×4` 等（仅在有条目级计数时占位）
 *
 * 三种形态（[setForm]）：
 *  - [Form.PACKED]　　　各行列靠上堆叠——关键字换行模式用，SQL 区自身只有三行
 *  - [Form.DISTRIBUTED] 时间顶 / 耗时垂直居中 / 进度条与类名沉底——格式化模式用，SQL 区可高十几行，
 *    轨道若也挤在顶部会留下大片空白
 *  - [Form.COMPACT]　　 压成两行、无进度条与徽章行——单行模式用，行高只容两行文本
 *
 * 结构只在形态/徽章有无变化时重排（[applyForm] 与 [toggleBadgeRow] 各自早退），
 * [bind] 纯写文本——渲染热路径不做组件增删。
 */
internal class LogEntryRail : JPanel(BorderLayout()) {

    /** 形态。 */
    internal enum class Form { PACKED, DISTRIBUTED, COMPACT }

    /** 首行：`#N HH:mm:ss`（测试断言及 `metaLine` 兼容别名均指向本组件）。 */
    internal val timeLine = SimpleColoredComponent()

    /** 耗时行；紧凑形态下在本组件内拼上类名。 */
    internal val durationLine = SimpleColoredComponent()

    /** 类名行（紧凑形态隐藏，类名并入 [durationLine]）。 */
    internal val sourceLine = SimpleColoredComponent()

    /** 耗时进度条（紧凑形态隐藏）。 */
    internal val track = DurationTrackPanel()

    private val timeRow = rowHost()
    private val durationRow = rowHost()
    private val trackRow = rowHost()
    private val sourceRow = rowHost()

    /** 行容器——[BoxLayout] Y 轴，使 [Form.DISTRIBUTED] 能靠 glue 实现顶/中/底分布。 */
    private val rows = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private var form = Form.PACKED

    init {
        isOpaque = false
        listOf(timeLine, durationLine, sourceLine).forEach {
            it.isOpaque = false
            it.border = JBUI.Borders.empty()
        }
        track.border = JBUI.Borders.empty(2, 0, 2, 0)
        listOf(timeRow, durationRow, trackRow, sourceRow).forEach {
            it.isOpaque = false
            it.alignmentX = Component.LEFT_ALIGNMENT
        }
        add(rows, BorderLayout.CENTER)
        applyForm(Form.PACKED)
    }

    /** 轨道宽度（px）——SQL 行量算可用宽度时须扣除本值。 */
    internal val railWidth: Int get() = railWidthPx

    /** 已应用的轨道宽度（缓存，避免读 preferredSize 触发对象分配/属性读）。 */
    private var railWidthPx = RAIL_WIDTH

    /** 首选宽是否已真正写过（与 [railWidthPx] 分开——缓存初值等于默认值时不能据此判定「已写过」）。 */
    private var railWidthApplied = false

    /**
     * 切换形态。渲染器每次绘制都会调用，形态未变即早退（无组件增删、无 revalidate）。
     */
    internal fun setForm(form: Form) = applyForm(form)

    /**
     * 切换轨道宽度（窄侧栏降档）。
     *
     * 只在值变化时重设首选宽：`preferredSize` 赋值会触发 propertyChange → 上层 revalidate，
     * 渲染热路径每格都设一次等于每格调度一次重排（T-LogListFreeze 同族问题的成因）。
     * 首次调用必须真的写一次——否则默认宽度只在构造期设过、窄侧栏降档会失效。
     */
    internal fun setRailWidth(width: Int) {
        if (railWidthApplied && railWidthPx == width) return
        railWidthPx = width
        railWidthApplied = true
        preferredSize = Dimension(width, preferredSize.height)
    }

    /**
     * 绑定条目投影（纯写文本；只在徽章有无翻转时增删徽章行）。
     *
     * @param maxDurationMs 当前列表可见条目的最大耗时（进度条归一基准；null / 非正数则不画填充）
     * @param showDuration  是否显示耗时（设置页开关；历史页此前硬编码 true，本次统一走本参数）
     * @param badges        条目级徽章片段（去重计数 / 同结构计数）；空表示无
     */
    internal fun bind(
        entry: LogSqlEntry,
        isSelected: Boolean,
        maxDurationMs: Long?,
        foreground: Color,
        showDuration: Boolean,
    ) {
        timeLine.foreground = foreground
        timeLine.clear()
        railTimeText(entry).takeIf { it.isNotEmpty() }?.let {
            timeLine.append(it, if (isSelected) primary(foreground) else meta())
        }

        durationLine.foreground = foreground
        durationLine.clear()
        sourceLine.foreground = foreground
        sourceLine.clear()

        val duration = if (showDuration) formatDurationFragment(entry.executionTimeMs, isSelected) else null
        val source = railSourceText(entry)
        when (form) {
            // 紧凑形态：耗时与类名合一行（单行模式行高只容两行）
            Form.COMPACT -> {
                duration?.let { durationLine.append(it.first.trimEnd() + "  ", it.second) }
                source?.let { durationLine.append(it.first, it.second) }
            }

            Form.PACKED, Form.DISTRIBUTED -> {
                duration?.let { durationLine.append(it.first.trimEnd(), it.second) }
                source?.let { sourceLine.append(it.first, it.second) }
            }
        }

        track.bind(entry.executionTimeMs, maxDurationMs)
    }

    /** 轨道全部文本拼接（测试断言用：首行 + 耗时行 + 类名行）。 */
    internal val textForTest: String
        get() = buildString {
            listOf(timeLine, durationLine, sourceLine).forEach { comp ->
                val it = comp.iterator()
                while (it.hasNext()) append(it.next())
                append(' ')
            }
        }.trim()

    private fun meta(): SimpleTextAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)

    private fun primary(foreground: Color): SimpleTextAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, foreground)

    private fun rowHost(): JPanel = JPanel(BorderLayout()).apply { isOpaque = false }

    /**
     * 重建行结构与分布。三种形态的差异全部集中在这里：
     * 紧凑形态只挂两行；其余形态按「堆叠」或「顶/中/底分布」挂行，分布靠 glue 实现。
     */
    private fun applyForm(next: Form) {
        if (form == next && rows.componentCount > 0) return
        form = next
        rows.removeAll()

        timeRow.removeAll()
        timeRow.add(timeLine, BorderLayout.EAST)
        durationRow.removeAll()
        trackRow.removeAll()
        trackRow.add(track, BorderLayout.CENTER)
        sourceRow.removeAll()
        sourceRow.add(sourceLine, BorderLayout.EAST)

        when (next) {
            Form.COMPACT -> {
                durationRow.add(durationLine, BorderLayout.WEST)
                rows.add(timeRow)
                rows.add(durationRow)
            }

            Form.PACKED -> {
                durationRow.add(durationLine, BorderLayout.EAST)
                rows.add(timeRow)
                rows.add(durationRow)
                rows.add(trackRow)
                rows.add(sourceRow)
            }

            Form.DISTRIBUTED -> {
                durationRow.add(durationLine, BorderLayout.EAST)
                rows.add(timeRow)
                // 上下 glue 把耗时压到垂直中心
                rows.add(Box.createVerticalGlue())
                rows.add(durationRow)
                rows.add(Box.createVerticalGlue())
                rows.add(trackRow)
                rows.add(sourceRow)
            }
        }
        rows.revalidate()
        rows.repaint()
    }


    companion object {
        /** 常规轨道宽度：88px 可容纳骨架化后的类名（13 字符 + 前缀省略号）。 */
        const val RAIL_WIDTH = 88

        /** 窄侧栏下的轨道宽度。 */
        const val RAIL_NARROW_WIDTH = 76

        /** 窄侧栏判定阈值（列表可视宽度小于此值即降档）。 */
        const val NARROW_LIST_WIDTH = 500
    }
}

/** 轨道宽度按列表可视宽度分流：窄侧栏收窄，给 SQL 让出空间。 */
internal fun railWidthFor(listWidth: Int): Int =
    if (listWidth in 1 until LogEntryRail.NARROW_LIST_WIDTH) LogEntryRail.RAIL_NARROW_WIDTH
    else LogEntryRail.RAIL_WIDTH

/**
 * 可见条目的最大耗时（进度条归一基准）。
 *
 * 单遍遍历、零分配：不用 `mapNotNull{}.maxOrNull()`——后者要为每条可见条目分配一个列表槽，
 * 而本函数在**每次列表刷新**时都跑。缓冲上限默认 200，但 `logMaxCount` 可被用户调到几千~几万，
 * 流式灌入下每秒多次刷新，临时列表会变成可观的 GC 压力（T-LogListFreeze 同族问题的成因）。
 */
internal fun maxDurationOf(entries: List<LogSqlEntry>): Long? {
    var max: Long? = null
    for (i in entries.indices) {
        val ms = entries[i].executionTimeMs ?: continue
        if (max == null || ms > max) max = ms
    }
    return max
}

/**
 * 2px 耗时进度条。
 *
 * 归一基准是**当前列表可见条目的最大耗时**而非慢查询阈值：阈值默认 1000ms，而一次捕获里最慢的
 * 条目常常只有几十毫秒——按阈值归一所有条都会被画成接近空槽，条失去意义。按可见最大值归一则
 * 每次刷新都有可读的分布。槽底用极淡的分隔线同族色，任何主题下都不抢前景。
 */
internal class DurationTrackPanel : JPanel() {
    private var fraction = 0.0
    private var fillColor: Color = MpvsUiColors.DURATION_OK

    init {
        isOpaque = false
        val h = TRACK_HEIGHT + 4
        preferredSize = Dimension(1, h)
        minimumSize = Dimension(1, h)
    }

    internal fun bind(ms: Long?, maxMs: Long?) {
        val next = when {
            ms == null || maxMs == null || maxMs <= 0L -> 0.0
            else -> (ms.toDouble() / maxMs.toDouble()).coerceIn(0.0, 1.0)
        }
        val nextColor = durationTrackColor(ms)
        // 渲染热路径：值未变不触发重绘（与 setBackgroundIfChanged 同族守卫）
        if (next == fraction && nextColor === fillColor) return
        fraction = next
        fillColor = nextColor
        repaint()
    }

    internal fun fractionForTest(): Double = fraction

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = TRACK_HEIGHT
            val y = (height - h) / 2
            g2.color = TRACK_BACKGROUND
            g2.fillRoundRect(0, y, width, h, h, h)
            if (fraction > 0.0) {
                g2.color = fillColor
                g2.fillRoundRect(0, y, (width * fraction).toInt().coerceAtLeast(1), h, h, h)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        /** 条高（px）。 */
        const val TRACK_HEIGHT = 2

        /** 槽底色——比分隔线更淡，未填充时几乎不可见。 */
        private val TRACK_BACKGROUND: Color = JBColor.namedColor("mpvs.log.durationTrackBg", 0x33363C, 0x3A3D42)
    }
}

/** 进度条填充色：与耗时数字同档（绿/橙/红），无耗时不着色。 */
internal fun durationTrackColor(ms: Long?): Color {
    if (ms == null) return MpvsUiColors.DURATION_NONE
    val threshold = PluginSettings.getInstance().state.slowQueryThresholdMs
    return when {
        ms >= threshold -> MpvsUiColors.DURATION_SLOW
        ms >= threshold / 2 -> MpvsUiColors.DURATION_WARN
        else -> MpvsUiColors.DURATION_OK
    }
}

/**
 * 轨道首行文本：`#N HH:mm:ss`。
 *
 * 时间不直用用户配置的完整格式（默认 `yyyy-MM-dd HH:mm:ss` = 19 字符，88px 放不下）：走
 * [railTimeFormat] 剥掉日期片段，保留用户自定义的时间部分（含毫秒）。
 * 时间戳开关关闭时只留序号（与既有「日志时间戳」设置语义一致）。
 */
internal fun railTimeText(entry: LogSqlEntry): String {
    val state = PluginSettings.getInstance().state
    val seq = if (entry.sequenceNumber > 0) "#${entry.sequenceNumber}" else ""
    if (!state.logTimestampEnabled) return seq
    val ts = when (state.logTimestampSource) {
        LOG_TIMESTAMP_SOURCE_LOG -> entry.logTimestamp ?: entry.timestamp
        else -> entry.timestamp
    }
    val time = try {
        railFormatterCached(railTimeFormatCached(state.logTimestampFormat)).format(Date(ts))
    } catch (_: IllegalArgumentException) {
        railFormatterCached(SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT_TIME_ONLY).format(Date(ts))
    }
    return if (seq.isEmpty()) time else "$seq $time"
}

/**
 * [railTimeFormat] 的结果缓存（T-LogListRail 性能守卫）。
 *
 * 载体是单变量而非 Map：配置串是全局单值，渲染时只会命中同一个键。每格渲染都重走一遍
 * 剥 token 的字符扫描在流式灌入下是纯浪费——此缓存把「每格」降为「每次设置变更」。
 */
private var railTimeFormatSource: String? = null
private var railTimeFormatResult: String = SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT_TIME_ONLY

/** [railTimeFormat] 的缓存入口（热路径用）。 */
internal fun railTimeFormatCached(configured: String): String {
    if (railTimeFormatSource == configured) return railTimeFormatResult
    val computed = railTimeFormat(configured)
    railTimeFormatSource = configured
    railTimeFormatResult = computed
    return computed
}

/** 轨道格式化器缓存（键=格式串；与 [formatTimestampFragment] 同款 ThreadLocal 复用范式）。 */
private val railFormatterCache = ThreadLocal.withInitial<HashMap<String, SimpleDateFormat>> { HashMap() }

private fun railFormatterCached(format: String): SimpleDateFormat =
    railFormatterCache.get().getOrPut(format) { SimpleDateFormat(format) }

/** 日期 token，按长度降序避免 `yyyy` 被 `yy` 截断。 */
private val RAIL_DATE_TOKENS = listOf("yyyy", "yy", "MMMM", "MMM", "MM", "M", "dd", "d", "EEEE", "EEE", "E")

/** 日期 token 之后允许一并剥掉的分隔符。 */
private val RAIL_TIME_SEPARATORS = charArrayOf('-', '/', ' ', ',', '.', 'T', 't')

/**
 * 把配置的时间格式剥成「时段格式」：去掉日期 token 与紧随其后的分隔符。
 *
 * 例：`yyyy-MM-dd HH:mm:ss` → `HH:mm:ss`；`HH:mm:ss.SSS` 原样保留；
 * 剥完不含任何时间 token（用户只配了日期）则回落 `HH:mm:ss`。
 * 纯函数便于单测——剥错会让轨道整列显示错误时刻，属高频误用面。
 */
internal fun railTimeFormat(configured: String): String {
    if (configured.isBlank()) return SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT_TIME_ONLY
    var out = configured
    for (token in RAIL_DATE_TOKENS) out = stripDateToken(out, token)
    out = out.trim().trim('-', '/', '.', ',', ' ', 'T', 't')
    val hasTimeToken = out.any { it == 'H' || it == 'h' || it == 'K' || it == 'k' || it == 'm' || it == 's' }
    return if (out.isEmpty() || !hasTimeToken) SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT_TIME_ONLY else out
}

/** 剥掉一个日期 token 及其后紧跟的分隔符（不碰引号内字面量）。 */
private fun stripDateToken(input: String, token: String): String {
    val sb = StringBuilder(input.length)
    var i = 0
    var inQuote = false
    while (i < input.length) {
        val c = input[i]
        if (c == '\'') {
            inQuote = !inQuote
            sb.append(c)
            i++
            continue
        }
        if (!inQuote && input.startsWith(token, i)) {
            i += token.length
            while (i < input.length && input[i] in RAIL_TIME_SEPARATORS) i++
            continue
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/**
 * 轨道类名文本。
 *
 * 原实现取前 11 字符，实测 `BizCarStationOccupyInfoServiceImpl` 与 `BizCarStationOccupyController`
 * 都压成 `BizCarStati…`，完全无法区分。改为**先剥公共后缀再取尾部**——业务词通常在尾部
 * （`…alletRecharge` / `…ionOccupyInfo`），比取头部有辨识力。
 */
internal fun railSourceText(entry: LogSqlEntry): Pair<String, SimpleTextAttributes>? {
    if (SourceMarkers.isMpPaginationCount(entry.sourceFile)) {
        return "MP 分页计数" to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)
    }
    val path = entry.sourceFile ?: return null
    val fileName = path.substringAfterLast('/')
    val className = fileName.substringBeforeLast('.', fileName)
    val line = if (entry.sourceLine > 0) ":${entry.sourceLine}" else ""
    return (railSourceName(className) + line) to
        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)
}

/** 公共后缀——剥掉后仍不足以表达类身份时才继续截断。 */
private val RAIL_SOURCE_SUFFIXES = listOf(
    "ServiceImpl", "Controller", "Repository", "Service", "Mapper", "Impl",
)

/** 骨架化后允许的最大字符数（88px 轨道实测容纳 13 字符不截断）。 */
internal const val RAIL_SOURCE_MAX_CHARS = 14

/** 骨架化类名：剥公共后缀 → 仍超 [RAIL_SOURCE_MAX_CHARS] 则保留尾部。 */
internal fun railSourceName(className: String): String {
    var base = className
    for (suffix in RAIL_SOURCE_SUFFIXES) {
        if (base.length > suffix.length && base.endsWith(suffix)) {
            base = base.dropLast(suffix.length)
            break
        }
    }
    if (base.length <= RAIL_SOURCE_MAX_CHARS) return base
    return "…" + base.takeLast(RAIL_SOURCE_MAX_CHARS - 1)
}
