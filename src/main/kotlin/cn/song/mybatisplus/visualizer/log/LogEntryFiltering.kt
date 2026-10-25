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

// SQL 日志条目的过滤/排序/可见性 diff 辅助
// （T-M53-II 2026-10-10：由 SqlLogPanel.kt 原样搬出，同包同可见性，零行为改动。）
/** SQL 末尾分号结束符（显示层统一追加；已以 `;` 结尾或空串不重复；T-M42 提为顶层，openPreview/右键复制/列表复制共用）。 */
internal fun withTerminator(sql: String): String =
    if (sql.isEmpty() || sql.endsWith(";")) sql else sql + ";"

/**
 * 右键菜单选中收敛（T-ListPreviewCopy）：命中行已在多选集合内 → 保留原多选（复制项=批量复制）；
 * 否则收敛为命中行（右键=切换操作目标，与改造前无条件收敛单行等价）。纯函数可独立单测，
 * 日志 tab 与历史 tab 右键菜单共用。
 */
internal fun resolveContextMenuSelection(selectedIndices: IntArray, hitIndex: Int): IntArray =
    if (hitIndex in selectedIndices) selectedIndices else intArrayOf(hitIndex)

/**
 * 批量复制文本（T-ListPreviewCopy）：选中条目升序逐条 [withTerminator] 后 `"\n"` 拼接——
 * 与 JBList 内置 Ctrl+C 多选拼接链路（itemToText 逐条 + join("\n")）逐字节同构，
 * 右键批量复制与键盘复制两条入口产物一致。
 */
internal fun batchCopySql(entries: List<LogSqlEntry>): String =
    entries.joinToString("\n") { withTerminator(it.restoredSql) }

/** 复制菜单项文案（T-ListPreviewCopy）：多选带条数走 [selectedKey]（`{0}`=条数），单选沿用 [baseKey]。 */
internal fun copyMenuText(baseKey: String, selectedKey: String, count: Int): String =
    if (count > 1) MpSqlVisualizerBundle.message(selectedKey, count.toString())
    else MpSqlVisualizerBundle.message(baseKey)

// ---------- T-LogListFreeze：列表刷新分批常量 ----------

/** 首屏立即写入条数（保证快速首屏，EDT 冻结可控）。 */
internal const val REFRESH_FIRST_BATCH_SIZE = 30

/** 每批异步追加条数（invokeLater 每批一次 EDT 脉冲）。 */
internal const val REFRESH_CHUNK_SIZE = 30

/**
 * F80（T-PerfSweep24）：flushRebuildChainSync 单拍同步补完封顶——默认设置（logMaxCount=200）
 * 在途余量 ≤170 全量补完（行为不变）；用户调大 logMaxCount 至数千时，超出部分不再单拍
 * 同步写数千条模型（EDT 冻结随 logMaxCount 线性放大），改由 appendRebuildChunk 逐拍链推进、
 * 本拍刷新挂起链尾补跑。
 */
internal const val FLUSH_SYNC_MAX = 512

/**
 * 日志条目列表（T-M42）：覆写 JBList.itemToText——内置 Ctrl+C 复制默认返回 cellRenderer
 * 渲染文本（HTML 源码，用户实测缺陷），覆写后复制输出纯 SQL（多选逐条换行拼接、
 * CopyPasteManager 写入、Ctrl+C 快捷键注册均由 JBList 既有链路复用）。
 * open：internal 类默认 final，测试子类经公开包装访问 protected itemToText。
 *
 * T-ClickPreviewCopyRestore 二轮（20261008）勘正：上句「Ctrl+C 快捷键注册」经实证**来自平台 LAF**
 * （`List.focusInputMap` 已含 `control C → copy`，headless 实测 `inputMap.get` 直接命中，
 * 早先据 `InputMap.keys()==null` 判「无绑定」是懒加载 InputMap 的假象）；`JBList.installDefaultCopyAction`
 * 只负责 ActionMap 侧。二轮曾在此补绑 InputMap，验证发现冗余（摘除后绑定仍在）已回退——列表 Ctrl+C
 * 本就可达，用户「点击后复制失效」的断点在单击预览浮层抢焦点，修复见 [SqlClickPreview] 的 Swing 层绑定。
 */
internal open class SqlEntryList(model: ListModel<LogSqlEntry>) : TextCopyList<LogSqlEntry>(model) {
    /** Ctrl+C 复制文本（T-SqlCopyShortcuts：委派给共享基类；多选逐行拼接由平台既有 copy 动作完成）。 */
    override fun copyTextFor(element: LogSqlEntry): String = textFor(element)

    /**
     * 速度搜索与 Ctrl+C 复制共用的条目文本。
     *
     * T-MagpieGems W2 出口缺口补齐：Ctrl+C 复制是**离开 IDE 面**（剪贴板出产品）——
     * 脱敏 mode != OFF 一律按 MASK 打码（R60 时代本链漏接 [DisplayMasker]，复制原文越权）；
     * OFF 原样（既有默认观感不变）。服务未注册（无平台测试环境）从严回落 MASK。
     */
    internal fun textFor(element: LogSqlEntry): String {
        val maskOff = runCatching { PluginSettings.getInstance().displayMaskMode() }
            .getOrDefault(cn.song.mybatisplus.visualizer.util.DisplayMasker.MaskMode.MASK) ==
            cn.song.mybatisplus.visualizer.util.DisplayMasker.MaskMode.OFF
        val base = if (maskOff) element.restoredSql
        else cn.song.mybatisplus.visualizer.util.DisplayMasker.maskDisplay(element.restoredSql, true)
        return withTerminator(base)
    }
}

internal class ViewportWidthSqlEntryList(model: ListModel<LogSqlEntry>) : SqlEntryList(model) {
    override fun getScrollableTracksViewportWidth(): Boolean = true
}

/**
 * 空态文案写入（T-AgentDiagWrap T-04，独立成顶层函数以便单测）。
 *
 * 为什么不能 `status.text = plain`：`JBList.emptyText` 是 `com.intellij.util.ui.StatusText`
 * （**不是** JLabel），内容落在 `SimpleColoredComponent` 上——它不解析 HTML，
 * 也不把 `\n` 当换行（`\n` 被当普通字符画成零宽，两段文字被无声拼接；超宽只裁不折）。
 * 平台侧唯一的多行手段是 [com.intellij.util.ui.StatusText.appendLine]：每行一个 Fragment，
 * `paintColumnInBounds` 逐 Fragment 单独绘制（**仍不折行**，所以宽度折行由
 * [wrapEmptyTextLines] 先算好）。
 *
 * @param widthPx 列表当前可用像素宽度
 * @param measure 单串像素宽度测量
 */
internal fun applyEmptyTextLines(
    status: com.intellij.util.ui.StatusText,
    text: String,
    widthPx: Int,
    measure: (String) -> Int,
) {
    status.clear()
    wrapEmptyTextLines(text, widthPx, measure).forEach { status.appendLine(it) }
}

/**
 * 排序模式（P-06 / T-LogSortModes）：默认 [TIME_ASC]（时间升序 = 捕获顺序，与未排序时观感一致）；
 * 另支持时间降序、耗时升/降序、行号、[STRUCTURE_CLUSTER]（同结构聚类）、[DATABASE_GROUP]（按目标库分组）。
 * 耗时缺失的条目恒沉底（两个方向的升降序都不让空值顶到前面）。
 *
 * 性能：全部模式都是对「当前可见条目」的一次 `sortedWith`（O(n log n)，n ≤ 缓冲上限 200），
 * 且发生在刷新管线里、不在渲染热路径；[STRUCTURE_CLUSTER] 用的 `structureKey` 是条目级 lazy 属性
 * （每条只算一次指纹），[DATABASE_GROUP] 只做字符串比较。两个新模式的组内次序沿用时间升序（稳定）。
 */
enum class SortMode {
    TIME_ASC,
    TIME_DESC,
    DURATION_ASC,
    DURATION_DESC,
    SEQUENCE,
    STRUCTURE_CLUSTER,
    DATABASE_GROUP,
}

/** 排序方式下拉项（文案 + 模式）。 */
internal data class SortOption(val label: String, val mode: SortMode)

/** 排序方式选项（顺序即下拉序；默认项在首位 = 时间升序）。 */
internal fun sortOptions(): List<SortOption> = listOf(
    SortOption(MpSqlVisualizerBundle.message("log.sort.time.asc"), SortMode.TIME_ASC),
    SortOption(MpSqlVisualizerBundle.message("log.sort.time.desc"), SortMode.TIME_DESC),
    SortOption(MpSqlVisualizerBundle.message("log.sort.duration.asc"), SortMode.DURATION_ASC),
    SortOption(MpSqlVisualizerBundle.message("log.sort.duration.desc"), SortMode.DURATION_DESC),
    SortOption(MpSqlVisualizerBundle.message("log.sort.sequence"), SortMode.SEQUENCE),
    SortOption(MpSqlVisualizerBundle.message("log.sort.structure"), SortMode.STRUCTURE_CLUSTER),
    SortOption(MpSqlVisualizerBundle.message("log.sort.database"), SortMode.DATABASE_GROUP),
)

/**
 * T-SqlLogToolbarEdt W3（2026-10-24）：`SortMode → 文案键` 静态查表。
 *
 * 为什么不再用 [sortOptions]：`sortModeAction.update()` 是**工具栏每次展开都要走的路径**
 * （改 BGT 之前还在 EDT 上），而它每次 `firstOrNull{}` 都要新建 7 个 `SortOption` + 调 7 次
 * `MpSqlVisualizerBundle.message`（含 ResourceBundle 取值）。查表后 update 是常数时间、零分配。
 * [sortOptions] 本身保留给「构建下拉项」这类一次性场景（需要顺序与 label）。
 */
internal fun sortModeLabelKey(mode: SortMode): String = when (mode) {
    SortMode.TIME_ASC -> "log.sort.time.asc"
    SortMode.TIME_DESC -> "log.sort.time.desc"
    SortMode.DURATION_ASC -> "log.sort.duration.asc"
    SortMode.DURATION_DESC -> "log.sort.duration.desc"
    SortMode.SEQUENCE -> "log.sort.sequence"
    SortMode.STRUCTURE_CLUSTER -> "log.sort.structure"
    SortMode.DATABASE_GROUP -> "log.sort.database"
}

/** 「按目标库分组」排序里未知库名的哨兵键（U+FFFF 大于常规字符，未知库恒沉底）。 */
internal const val DATABASE_UNKNOWN_SORT_KEY = "\uFFFF"

/** 类型多选弹窗可勾选的类型（OTHER 不单列；全选时 OTHER 一并透传）。 */
internal val FILTERABLE_TYPES: List<StatementType> = listOf(
    StatementType.SELECT,
    StatementType.INSERT,
    StatementType.UPDATE,
    StatementType.DELETE,
)

/**
 * 类型过滤（纯逻辑，可独立单测）：所选类型覆盖全部可筛选类型时视为「全部」，
 * 透传所有条目（含 OTHER）；否则仅保留分类命中的条目。
 */
internal fun filterByType(entries: List<LogSqlEntry>, activeTypes: Set<StatementType>): List<LogSqlEntry> {
    if (activeTypes.containsAll(FILTERABLE_TYPES)) return entries
    // T-CrudColor：改用条目级缓存属性（行为等价）——避免每次刷新都重跑分类
    // （classify 内 Regex 每次编译 Pattern），与渲染器共用同一份结果。
    return entries.filter { it.statementType in activeTypes }
}

/**
 * 「只看慢查询」过滤（用户 2026-10-14 补，纯逻辑可独立单测）：启用时只保留耗时 ≥ [thresholdMs] 的条目。
 * 耗时未知（null）的条目**不算慢查询**、一并滤掉——「慢查询」是「已知且超阈值」，未知无从判定。
 * 判定函数由调用方注入（面板用 [LogSqlPanel.durationOf]：结构化字段优先、缺失回退日志文本解析），
 * 与「慢查询徽章」同口径。性能：一次 O(n) 遍历，位于刷新管线（非渲染热路径）。
 */
internal fun filterBySlowOnly(
    entries: List<LogSqlEntry>,
    enabled: Boolean,
    thresholdMs: Long,
    durationOf: (LogSqlEntry) -> Long?,
): List<LogSqlEntry> {
    if (!enabled) return entries
    return entries.filter { (durationOf(it) ?: Long.MIN_VALUE) >= thresholdMs }
}

/**
 * 屏蔽规则存量过滤（T-SqlSuppress O-69，纯逻辑，可独立单测）：命中屏蔽指纹集的条目
 * 从列表隐藏（缓冲里仍在——删除规则后复现；闸门已丢弃的历史条目不可恢复，UI 文案明示）。
 * [suppressKeys] 与闸门同一数据源（[SqlSuppressRuleManager.suppressedKeys] 经 [SqlLogManager]
 * 同步进缓冲），空集透传（无规则常态零成本）。
 */
internal fun filterBySuppress(entries: List<LogSqlEntry>, suppressKeys: Set<String>): List<LogSqlEntry> {
    if (suppressKeys.isEmpty()) return entries
    // T-SqlSuppressUx 二段：精确键或结构键（手动开启结构匹配的规则）任一命中即滤除
    return entries.filter { it.suppressKey !in suppressKeys && it.structureKey !in suppressKeys }
}

/**
 * 搜索过滤（纯逻辑，可独立单测）：查询词 contains，匹配还原 SQL 与摘要
 * （摘要含参数值的还原 SQL 前缀；参数值是常见检索目标）；空白查询透传全部。
 *
 * T-LogSearchRowTime 扩展：
 * - 行号——`#5` / `5` 精确 equals [LogSqlEntry.sequenceNumber]（渲染形态 `#123`，所见即可搜；
 *   精确而非 contains：搜 `1` 不应命中全部 1xx 行；0 = 无行号条目天然不命中任何行号查询）
 * - 时间——对显示片段（[timestampDisplayText]，含方括号与设置格式，所见即所搜）contains；
 *   显示开关关闭时片段为 null，时间分支天然不参与
 * [timestampTextOf] 可注入（测试绕开设置读取），默认取当前设置下的真实显示片段。
 *
 * T-HistorySearchToggles：[matchCase] 大小写敏感开关（默认 false=忽略大小写，与历史面板/
 * MCP 链三链语义统一；默认值保既有测试零改动）。行号精确匹配不受开关影响。
 *
 * T-SearchToggleNativeLook（20261010 用户令日志页补正则钮，D133「日志页无正则」拍板翻案）：
 * [regexEnabled] 正则开关生效条件 = 开启开关**或** `regex:` 前缀（[HISTORY_REGEX_PREFIX]，
 * 与历史页同语义）；正则路径命中 restoredSql/summary 两维（行号/时间不参与，对齐历史页）；
 * 编译旗随 [matchCase]；非法正则 fail-closed 回退普通子串（回退查询为原始 query 全文含前缀）。
 * [memo] 传面板持有的 [HistoryRegexMemo] 实例防抖重复编译；纯函数缺省各建临时实例零复用。
 */
internal fun filterBySearch(
    entries: List<LogSqlEntry>,
    query: String,
    matchCase: Boolean = false,
    regexEnabled: Boolean = false,
    memo: HistoryRegexMemo = HistoryRegexMemo(),
    timestampTextOf: (LogSqlEntry) -> String? = ::timestampDisplayText,
): List<LogSqlEntry> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    if (regexEnabled || q.startsWith(HISTORY_REGEX_PREFIX)) {
        val pattern = resolveHistoryRegex(q.removePrefix(HISTORY_REGEX_PREFIX), matchCase, memo)
        if (pattern != null) {
            return entries.filter { entry ->
                pattern.containsMatchIn(entry.restoredSql) || pattern.containsMatchIn(entry.summary)
            }
        }
        // 非法正则：回退普通子串匹配（继续走下方通用路径）
    }
    val rowNumber = q.removePrefix("#").toLongOrNull()?.takeIf { it > 0 }
    return entries.filter {
        it.restoredSql.contains(q, ignoreCase = !matchCase)
            || it.summary.contains(q, ignoreCase = !matchCase)
            || (rowNumber != null && it.sequenceNumber == rowNumber)
            || timestampTextOf(it)?.contains(q, ignoreCase = !matchCase) == true
    }
}

/**
 * 时间显示片段文本（T-LogSearchRowTime）：`[yyyy-MM-dd HH:mm:ss]` 形态（去段间距尾随空格），
 * 与列表渲染逐字节一致（所见即所搜/所复制的单一真源——格式/来源/开关语义全在
 * [formatTimestampFragment]）；开关关闭返回 null（页面上没有时间片段可搜可复制）。
 */
internal fun timestampDisplayText(entry: LogSqlEntry): String? =
    formatTimestampFragment(entry)?.first?.trim()

/**
 * 批量复制时间戳（T-LogSearchRowTime）：显示片段去方括号（用户定调：复制出纯时间文本
 * `2024-01-15 10:30:45`，不带显示装饰括号）逐行拼接，与 [batchCopySql] 同构；
 * 显示开关关闭时片段为 null 被跳过——全部关闭时返回空串（菜单项据此置灰）。
 */
internal fun batchCopyTimestamps(entries: List<LogSqlEntry>): String =
    entries.mapNotNull { timestampDisplayText(it)?.removeSurrounding("[", "]") }.joinToString("\n")

// ---------- T-Perf-03：流式增量刷新差分（纯逻辑，可独立单测） ----------

/**
 * M56 T-M56-00：动态聚焦页签过滤层（纯逻辑，插在 [filterByType] 与 [filterBySearch] 之间）。
 * [FocusFilter.method] 匹配 [LogSqlEntry.mapperStatement]（agent 通道直取 / 桥通道标记第 4 段，
 * 忽略大小写）；[FocusFilter.requestTag] 精确匹配 [LogSqlEntry.requestTag]。
 * 双条件为「且」；null 维度透传；全 null 等价不过滤。T-M56-06 起 method 扩展真实方法边界
 * `{Class}#{method}` 形态（届时在 [focusMethodKey] 统一登记来源）。
 */
internal data class FocusFilter(
    val method: String? = null,
    val requestTag: String? = null,
) {
    /** 是否为空过滤（全维度 null = 透传）。 */
    val isPassthrough: Boolean get() = method == null && requestTag == null
}

/** 条目的聚焦 method 键（当前 = mapper 语句 id；T-M56-06 扩展真实方法边界）。 */
internal fun focusMethodKey(entry: LogSqlEntry): String? = entry.mapperStatement

internal fun filterByFocus(entries: List<LogSqlEntry>, focus: FocusFilter?): List<LogSqlEntry> {
    if (focus == null || focus.isPassthrough) return entries
    return entries.filter { e ->
        val methodOk = focus.method?.let { f ->
            focusMethodKey(e)?.equals(f, ignoreCase = true) == true
        } ?: true
        val tagOk = focus.requestTag?.let { f -> f == e.requestTag } ?: true
        methodOk && tagOk
    }
}

/** 同步增量追加条数上限：超过则回退全量重建（全量重建带分批 EDT 写入，大批量更稳）。 */
internal const val MAX_SYNC_APPEND = 300

/**
 * 新旧可见列表差分结果（增量刷新决策；元素按同一性 `===` 比较——条目为不可变数据类，
 * 缓冲内同一条目恒为同一实例）。（T-Perf-03 自 dev 移植）
 */
internal sealed class VisibleDiff {
    /** 头部淘汰 `evicted` 条 + 尾部追加 `appended`（缓冲满后的流式 steady state；appended 可为空）。 */
    data class HeadEvictAppend(val evicted: Int, val appended: List<LogSqlEntry>) : VisibleDiff()
    /** 从 `index` 起插入 `entries`（尾部纯追加时 index == old.size）。 */
    data class Insert(val index: Int, val entries: List<LogSqlEntry>) : VisibleDiff()
    /** 尾部纯移除：移除 `[from, old.size)`。 */
    data class TailRemove(val from: Int) : VisibleDiff()
    /** 无变化。 */
    object Same : VisibleDiff()
    /** 无法增量（离散增删/超大追加）——全量重建。 */
    object FullRebuild : VisibleDiff()
}

/** 同一性公共前缀长度。 */
internal fun commonPrefixLength(old: List<LogSqlEntry>, new: List<LogSqlEntry>): Int {
    val n = minOf(old.size, new.size)
    var i = 0
    while (i < n && old[i] === new[i]) i++
    return i
}

/** 同一性公共后缀长度（不与 prefix 重叠）。 */
internal fun commonSuffixLength(old: List<LogSqlEntry>, new: List<LogSqlEntry>, prefix: Int): Int {
    val n = minOf(old.size, new.size) - prefix
    var i = 0
    while (i < n && old[old.size - 1 - i] === new[new.size - 1 - i]) i++
    return i
}

/** [old] 的 [aStart..] 与 [new] 的 [bStart..] 前 [length] 个元素是否同一。 */
internal fun identityPrefix(old: List<LogSqlEntry>, aStart: Int, new: List<LogSqlEntry>, bStart: Int, length: Int): Boolean {
    if (old.size - aStart < length || new.size - bStart < length) return false
    for (i in 0 until length) if (old[aStart + i] !== new[bStart + i]) return false
    return true
}

/**
 * 可见列表差分：流式灌入时把「clear+全量重建」降阶为「尾部追加 / 头部淘汰+追加」。
 *
 * 判定顺序：
 *  1. 头部淘汰对齐（evicted>0 且 old 去头后与 new 前缀同一）→ HeadEvictAppend；
 *  2. 前缀/后缀差分 → 纯插入（含尾部追加）/ 尾部纯移除 / 无变化；
 *  3. 其余（过滤、搜索、去重裁剪、排序导致的离散增删）→ FullRebuild。
 */
internal fun diffVisible(old: List<LogSqlEntry>, new: List<LogSqlEntry>, evicted: Int): VisibleDiff {
    if (evicted > 0 && old.size >= evicted && new.size >= old.size - evicted &&
        identityPrefix(old, evicted, new, 0, old.size - evicted)
    ) {
        return VisibleDiff.HeadEvictAppend(evicted, new.drop(old.size - evicted))
    }
    val prefix = commonPrefixLength(old, new)
    val suffix = commonSuffixLength(old, new, prefix)
    val removed = old.size - prefix - suffix
    val added = new.size - prefix - suffix
    return when {
        removed == 0 && added == 0 -> VisibleDiff.Same
        removed == 0 && added in 1..MAX_SYNC_APPEND -> VisibleDiff.Insert(prefix, new.subList(prefix, prefix + added))
        added == 0 && suffix == 0 -> VisibleDiff.TailRemove(prefix)
        else -> VisibleDiff.FullRebuild
    }
}

