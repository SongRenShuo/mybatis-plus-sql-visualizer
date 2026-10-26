package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettings.Companion.LOG_TIMESTAMP_SOURCE_LOG
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
// T-UiCoord F2：下拉按钮文本截尾省略复用 meta 行的同款纯函数（避免第二份省略实现）
import cn.song.mybatisplus.visualizer.ui.clipTailToWidth
import com.intellij.util.ui.JBUI
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.TextCopyList
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import javax.swing.Action
import javax.swing.JComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.awt.RelativePoint
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JRadioButtonMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.JToggleButton
import javax.swing.ListModel
import javax.swing.SwingConstants
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// SQL 日志条目的单元格渲染片段与列表渲染器
// （T-M53-II 2026-10-10：由 SqlLogPanel.kt 原样搬出，同包同可见性，零行为改动。）
/** 元信息灰（时间戳/来源/徽章；与原 HTML 通道 #9E9E9E 一致）。T-UN-13（T-UiNative）：改 namedColor，亮值不变。 */
private val LIST_META_GRAY = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MpvsUiColors.META_GRAY)

/**
 * T-CrudColor：按标记形态应用「色条 / 整行淡背景」——**两个列表渲染器共用**（§1.2.1）。
 *
 * 三分支：
 *  - 关闭 / OTHER（[tint] = null）→ 与改造前逐字节一致；
 *  - [SqlListTypeColor.STYLE_BAR] → 色条着色，单元格背景走平台选中填充（色条不受影响，选中不丢色）；
 *  - [SqlListTypeColor.STYLE_BACKGROUND] → **未选中**行底色与列表背景淡混色（类型标记）；
 *    选中行交给平台选中填充（T-UN-09 描边退役——自研选中视觉整体下线，不再干预 Border）。
 */
internal fun applyCrudColorMark(
    cell: JPanel,
    colorBar: JPanel,
    normalBorder: Border,
    tint: Color?,
    isSelected: Boolean,
    list: javax.swing.JList<*>?,
) {
    val listBg = list?.background ?: cell.background
    val selectionBg = list?.selectionBackground ?: listBg
    when (SqlListTypeColor.styleOf()) {
        SqlListTypeColor.STYLE_BAR -> {
            if (tint == null) {
                setOpaqueIfChanged(colorBar, false)
            } else {
                setBackgroundIfChanged(colorBar, tint)
                setOpaqueIfChanged(colorBar, true)
            }
            setBorderIfChanged(cell, normalBorder)
            setBackgroundIfChanged(cell, if (isSelected) selectionBg else listBg)
        }
        SqlListTypeColor.STYLE_BACKGROUND -> {
            setOpaqueIfChanged(colorBar, false)
            setBorderIfChanged(cell, normalBorder)
            setBackgroundIfChanged(
                cell,
                when {
                    isSelected -> selectionBg
                    tint == null -> listBg
                    else -> SqlListTypeColor.blend(listBg, tint, SqlListTypeColor.MIX_NORMAL)
                },
            )
        }
        else -> { // STYLE_OFF
            setOpaqueIfChanged(colorBar, false)
            setBorderIfChanged(cell, normalBorder)
            setBackgroundIfChanged(cell, if (isSelected) selectionBg else listBg)
        }
    }
}

/**
 * 仅在变化时设置边框（T-CrudColor 性能守卫）。
 *
 * 实测（JBR 21）：`JComponent.setBorder(新实例)` **即使值与旧值相同也会触发 `repaint() +1`**，
 * 而传入**同一实例**则 revalidate/repaint 均为 0。渲染器逐格调用，若每格无脑赋值就会把
 * 渲染热路径变成「每格一次重绘调度」——正是 T-LogListFreeze 那类副作用累积问题的成因。
 * 用**同一性**（`!==`）比较：本函数只会在预建的两个 Border 实例间切换，身份比较即可，
 * 且避免 `CompoundBorder.equals` 的深度比较开销。
 */
private fun setBorderIfChanged(cell: JPanel, border: Border) {
    if (cell.border !== border) cell.border = border
}

/**
 * 仅在变化时设置背景（T-CrudColor 性能守卫）。
 *
 * 实测（JBR 21）：`setBackground(新 Color 实例)` 即使 RGB 相同也会触发 `repaint() +1`；
 * 传入同一实例则为 0。配合 [SqlListTypeColor.colorOf] 的实例缓存，同色格不再产生任何 repaint。
 */
private fun setBackgroundIfChanged(target: JPanel, color: Color) {
    if (target.background != color) target.background = color
}

/** 仅在变化时设置不透明（`setOpaque` 同值本身零副作用，此处为语义显式化）。 */
private fun setOpaqueIfChanged(target: JPanel, opaque: Boolean) {
    if (target.isOpaque != opaque) target.isOpaque = opaque
}


// ----------
// T-PerfSweep3 #3：SimpleDateFormat 复用——[formatTimestampFragment] 是「每可见单元格渲染 +
// 每条目搜索匹配/批量复制」的双热路径（SqlLogPanel 与 HistorySqlLogPanel 两面板共用本单点），
// 此前每次调用新建 SimpleDateFormat。SimpleDateFormat 非线程安全，照 SqlLogAutoSaver 的
// T-Perf-12 先例做 ThreadLocal + 格式串键缓存：键=格式串天然跟随设置变更（换格式=新条目，
// 无需失效钩子）；消费形态是 EDT 渲染/搜索与右键菜单，ThreadLocal 在覆盖多线程的同时免同步。
// format(Date) 每次完整重置内部 calendar，复用同一实例输出与逐次新建逐字节一致；
// 缓存规模上界=用户可配置的格式串种类数（个位数量级），非法格式串构造即抛不进缓存、
// 每次重试构造（与逐次新建同语义，仍由调用处 catch 回落默认格式）。
// ----------
private val timestampFormatterCache =
    ThreadLocal.withInitial<HashMap<String, SimpleDateFormat>> { HashMap() }

private fun timestampFormatter(formatStr: String): SimpleDateFormat =
    timestampFormatterCache.get().getOrPut(formatStr) { SimpleDateFormat(formatStr) }

/** 时间戳片段：未开启时间戳返回 null。片段文本带尾随空格做段间距（原 HTML `<span>` 尾空格语义）。 */
internal fun formatTimestampFragment(entry: LogSqlEntry): Pair<String, SimpleTextAttributes>? {
    val state = PluginSettings.getInstance().state
    if (!state.logTimestampEnabled) return null
    val ts = when (state.logTimestampSource) {
        LOG_TIMESTAMP_SOURCE_LOG -> entry.logTimestamp ?: entry.timestamp
        else -> entry.timestamp
    }
    val formatted = try {
        timestampFormatter(state.logTimestampFormat).format(Date(ts))
    } catch (_: IllegalArgumentException) {
        timestampFormatter(SqlLogPanel.DEFAULT_TIMESTAMP_FORMAT).format(Date(ts))
    }
    return "[$formatted] " to LIST_META_GRAY
}

/** 耗时片段：颜色分档与原 HTML 通道一致（选中白/无耗时灰/慢红/偏慢橙/正常绿）；无耗时显示 "—" 占位。 */
internal fun formatDurationFragment(ms: Long?, isSelected: Boolean): Pair<String, SimpleTextAttributes> {
    val threshold = PluginSettings.getInstance().state.slowQueryThresholdMs
    val color = when {
        // T-UN-13（T-UiNative）：五档写死色 → namedColor（亮值不变）
        isSelected -> MpvsUiColors.DURATION_SELECTED
        ms == null -> MpvsUiColors.DURATION_NONE   // 灰：无耗时
        ms >= threshold -> MpvsUiColors.DURATION_SLOW // 红：慢查询
        ms >= threshold / 2 -> MpvsUiColors.DURATION_WARN // 橙：偏慢
        else -> MpvsUiColors.DURATION_OK           // 绿：正常
    }
    val text = ms?.let { "${it}ms" } ?: "—"
    return "$text " to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
}

/** M56 T-M56-02：增强捕获角标已按用户要求移除（增强为默认通道，逐行标来源属噪音）。 */

/**
 * O-90：同模板计数徽章（N+1 捕获后聚类标记）——counts[structureKey] 达 [TEMPLATE_BADGE_MIN_COUNT]
 * 才显示「×N 同模板」，与去重 ⨉N 徽章相邻同落点（元信息行尾）。计数由调用方传面板全量条目的
 * [structureKeyCounts] 聚合快照（徽章计数不随当前过滤波动）；无计数/未达阈值返回 null 不显示。
 * 文案走双语 bundle（log.panel.templateBadge）；着色 [MpvsUiColors.TEMPLATE_N1]（选中白同规）。
 */
internal fun formatTemplateCountFragment(
    entry: LogSqlEntry,
    counts: Map<String, Int>,
    isSelected: Boolean,
): Pair<String, SimpleTextAttributes>? {
    val n = counts[entry.structureKey] ?: return null
    if (n < TEMPLATE_BADGE_MIN_COUNT) return null
    val color = if (isSelected) Color(0xFFFFFF) else MpvsUiColors.TEMPLATE_N1
    return MpSqlVisualizerBundle.message("log.panel.templateBadge", n) + " " to
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
}

/**
 * T-AgentDialectLoop：目标库角标（agent 通道由连接 URL 推断）；未知返回 null 不显示。
 *
 * T-DataSourceBadgeMode（O-133，D135 显示侧门控）：默认仅显示数据库类型——` · ` 分隔符唯一产自
 * [cn.song.mybatisplus.visualizer.log.agent.JdbcUrlDialects.badgeName]，OFF 态切其前段即纯方言段
 * （落盘/历史读回/MCP 检索链仍消费全量串，后开开关不丢信息，切换即时生效）；
 * ON 态原样全量（`[MySQL · jpa_demo@localhost:3306]`）。无 ` · ` 分隔符的串（方言解析失败仅存
 * 连接事实）OFF 态原样保留——辨不出方言时保留最后上下文优于显示空白。null 恒 null 不显示。
 * 热路径直读设置沿 [formatDurationFragment] 先例（每 cell 一次服务查找，V38 基准同量级）。
 */
internal fun formatDatabaseFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    val full = entry.databaseName ?: return null
    val db = if (PluginSettings.getInstance().state.dataSourceBadgeDetail) full else full.substringBefore(" · ")
    val color = if (isSelected) Color(0xFFFFFF) else Color(0x00695C)
    return "[$db] " to SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
}

/**
 * T-McpAuditTrail T-MAT-05：MCP 通道徽标——`source == MCP`（MCP 执行类工具跑过的语句）才显示，
 * 其余来源返回 null 不显示（与目标库角标同规：未知即不占位，零噪音）。
 *
 * 落点与既有角标一致（时间/耗时/目标库之后、来源之前），协议同为
 * `Pair<String, SimpleTextAttributes>?`——两页渲染器（[LogEntryListCellRenderer] 与历史页
 * [HistoryCellRenderer]）共用本函数，避免「同条目两页显示不一致」。
 * 读回侧来源由 [SqlSource.MCP] 的 pd 载荷回填（见 SqlLogParamCodec）。
 */
internal fun formatMcpFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    if (entry.source != SqlSource.MCP) return null
    val color = if (isSelected) Color(0xFFFFFF) else Color(0x7B1FA2)
    return "[MCP] " to SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
}

/** M56 T-M56-05：影响行数片段（DML 语义；null 未知不显示）。 */
internal fun formatUpdateCountFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    val u = entry.updateCount ?: return null
    // 负值 = 无有效计数（JDBC getUpdateCount 的 -1 语义；旧版 agent 会误报 -1）→ 不显示
    if (u < 0) return null
    val color = if (isSelected) Color(0xFFFFFF) else Color(0x607D8B)
    return "(${u} rows) " to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
}

/** T-PerfSweep26-07（F105）：报错片段空白折叠正则提文件级常量——原 inline 构造在渲染热路径每次重建，提常量后一次复用。 */
private val ERROR_FRAGMENT_WHITESPACE_REGEX = Regex("\\s+")

/** M56 T-M56-05：报错片段（errorText 同列展示；红字截断首段，悬停完整文本在预览窗）。 */
internal fun formatErrorFragment(entry: LogSqlEntry): Pair<String, SimpleTextAttributes>? {
    val err = entry.errorText ?: return null
    val oneLine = err.replace(ERROR_FRAGMENT_WHITESPACE_REGEX, " ")
    val text = if (oneLine.length > 80) oneLine.substring(0, 80) + "…" else oneLine
    return "⛔ $text " to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Color(0xD32F2F))
}

/** 来源片段：未知来源返回 null；选中态走组件前景（selectionForeground），非选中灰。 */
internal fun formatSourceFragment(entry: LogSqlEntry, isSelected: Boolean): Pair<String, SimpleTextAttributes>? {
    val source = when {
        SourceMarkers.isMpPaginationCount(entry.sourceFile) -> "MP 分页计数"
        entry.sourceFile != null -> {
            // F-44-7：标记路径始终使用正斜杠（与 CallerSourceLocator 一致），不能用 File.separatorChar
            val name = entry.sourceFile.substringAfterLast('/')
            if (entry.sourceLine > 0) "$name:${entry.sourceLine}" else name
        }
        else -> return null
    }
    return "[$source] " to if (isSelected) SimpleTextAttributes.REGULAR_ATTRIBUTES else LIST_META_GRAY
}

/**
 * 日志列表条目单元格渲染器（T-LogListHeader）。
 *
 * 版面：`[色条] 头部行（全部元信息） / SQL 行… / 可选错误行`。
 * 头部行顺序与优先级裁剪见 [RailCellRendererBase.bindHeaderLine] 与
 * [clipFragmentsByPriority]；条目分割靠上下留白 + 底部细线。
 *
 * 演变：`bed58b64` 的 88px 左侧信息轨与进度条已被用户实测否决并撤除；
 * `metaLine` 保留为头部行的兼容别名，存量测试断言零改动继续有效。
 *
 * 渲染热路径约束（T-LogListFreeze 系列）：不在逐格渲染里增删组件、不新建 Border/Color 实例。
 */
internal class LogEntryListCellRenderer : RailCellRendererBase() {
    /** 去重丢弃计数快照（徽章渲染数据源；由 [SqlLogPanel.refreshList] 更新）。 */
    var dedupeCounts: Map<String, Int> = emptyMap()

    /** O-90：同模板计数快照（「×N 同结构」徽章渲染数据源；refreshList 随 dedupeCounts 一并更新）。 */
    var templateCounts: Map<String, Int> = emptyMap()

    /** 当前列表可见条目的最大耗时（refreshList 更新；进度条已退役，仍留作诊断读数与测试断言）。 */
    var maxDurationMs: Long? = null

    override fun templateCountsSnapshot(): Map<String, Int> = templateCounts

    override fun maxDurationMsSnapshot(): Long? = maxDurationMs

    override fun dedupeCountsSnapshot(): Map<String, Int> = dedupeCounts

    override fun showDuration(): Boolean =
        PluginSettings.getInstance().state.showDurationInLogList
}
