package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import java.awt.Color

/**
 * SQL 列表 CRUD 类型颜色标记配色门面（T-CrudColor，纯逻辑可单测）。
 *
 * 职责：把「类型 → 标记色」的映射与混色计算收在一处，供两个列表渲染器
 * （[LogEntryListCellRenderer] / [HistoryCellRenderer]）共用；设置态直读
 * [PluginSettings]（与既有 `formatTimestampFragment` / `formatDurationFragment` 同范式）。
 *
 * 标记形态（[PluginSettings.State.crudColorStyle]）：
 *  - [STYLE_BAR]：左侧色条（默认）——选中态走平台原生填充，色条不受影响；
 *  - [STYLE_BACKGROUND]：整行淡背景（仅**未选中**行）——选中行交给平台选中填充
 *    （T-UN-09：自研选中描边/选中混色已退役）；
 *  - [STYLE_OFF]：关闭，与改造前逐字节一致。
 *
 * 类型口径与 [LogStatementClassifier] 完全一致（即与类型过滤弹窗同源），保证
 * 「筛出来的 SELECT 行不会被标成红色」。OTHER 恒不标记。
 */
object SqlListTypeColor {

    /** 左侧色条（默认）。 */
    const val STYLE_BAR = "bar"

    /** 整行淡背景（仅未选中行；选中行走平台选中填充）。 */
    const val STYLE_BACKGROUND = "background"

    /** 关闭标记（现状行为）。 */
    const val STYLE_OFF = "off"

    /** 未选中行的混色比例（淡色调）。 */
    const val MIX_NORMAL = 0.14

    /**
     * 左侧类型色条宽度（px）。
     * 从左内边距中切分（渲染器左内边距 5 + 色条 3 = 原 8），故文本左缘像素位置与改造前一致。
     */
    const val BAR_WIDTH = 3

    const val DEFAULT_SELECT = "#4A86E8"
    const val DEFAULT_INSERT = "#4CAF50"
    const val DEFAULT_UPDATE = "#E67E22"
    const val DEFAULT_DELETE = "#D32F2F"

    // 默认色实例（预建，避免 defaultOf 每次解析/分配）。
    // T-UN-13（T-UiNative）：写死兜底色 → namedColor（亮值不变；仅兜底实例收口，
    // 用户配置链 hexOf/parseHex 原样——设置页 hex 串默认值即上方 DEFAULT_* 常量）。
    private val COLOR_SELECT: Color = MpvsUiColors.CRUD_SELECT
    private val COLOR_INSERT: Color = MpvsUiColors.CRUD_INSERT
    private val COLOR_UPDATE: Color = MpvsUiColors.CRUD_UPDATE
    private val COLOR_DELETE: Color = MpvsUiColors.CRUD_DELETE

    /**
     * 解析结果缓存（每类型至多 1 条，共 ≤ 4 条）。
     *
     * **存在的理由不是省那一次字符串解析，而是省掉 Swing 的重绘副作用**：实测（JBR21）
     * `JComponent.setBackground(new Color(同值))` 会触发 `repaint() +1`，而传入**同一实例**则是 0。
     * 渲染器每格都要给色条/单元格设背景，若每次现解析就会每格多一次 repaint —— 这正是
     * T-LogListFreeze 那类「渲染热路径逐格副作用累积」问题的成因。缓存后同色格复用同一实例。
     */
    private val parsedCache = java.util.concurrent.ConcurrentHashMap<StatementType, Pair<String, Color>>()

    /**
     * 类型 → 默认色（用户未配置 / 配置非法时的兜底）。
     *
     * DDL 两档**复用** DML 的色（T-DdlTypeColor）：CREATE 取增绿、DROP 取删红。
     * 视觉区分不靠色相而靠**线条形态**（虚线 vs 实心，见 [ddlBarStyleOf]）。
     */
    fun defaultOf(type: StatementType): Color = when (type) {
        StatementType.SELECT -> COLOR_SELECT
        StatementType.INSERT -> COLOR_INSERT
        StatementType.UPDATE -> COLOR_UPDATE
        StatementType.DELETE -> COLOR_DELETE
        StatementType.CREATE -> COLOR_INSERT
        StatementType.DROP -> COLOR_DELETE
        StatementType.OTHER -> COLOR_SELECT
    }

    /**
     * 是否 DDL 类型（色条画虚线）。
     *
     * 纯函数，渲染热路径直接调用（不做设置读取）。
     */
    fun isDdl(type: StatementType): Boolean =
        type == StatementType.CREATE || type == StatementType.DROP

    /** 当前样式（读设置薄壳）。 */
    fun styleOf(): String = styleOf(PluginSettings.getInstance().state.crudColorStyle)

    /**
     * **纯逻辑**：原始样式串 → 样式常量；未知值按 [STYLE_BAR] 兜底
     * ——不留「配置写错就整列表失色」的口子。
     */
    fun styleOf(raw: String): String = when (raw) {
        STYLE_BAR -> STYLE_BAR
        STYLE_BACKGROUND -> STYLE_BACKGROUND
        STYLE_OFF -> STYLE_OFF
        else -> STYLE_BAR
    }

    /**
     * 类型 → 标记色（读设置薄壳；色值来源见 [hexOf]）。
     *
     * 关闭样式 / OTHER → null（不标记）；空串或非法值回退 [defaultOf]（不抛异常、不写坏配置）。
     */
    fun colorOf(type: StatementType): Color? = colorOf(type, styleOf(), hexOf(type))

    /**
     * **纯逻辑**：类型 + 样式 + 色值 → 标记色（null = 不标记）。
     *
     * 与设置解耦，便于零平台依赖单测；[colorOf] 只负责把设置态喂进来。
     */
    fun colorOf(type: StatementType, style: String, hex: String): Color? {
        if (style == STYLE_OFF) return null
        if (type == StatementType.OTHER) return null
        // 同色复用同一 Color 实例：既省分配，也避免 Swing setBackground 因「新实例同值」触发 repaint
        val cached = parsedCache[type]
        if (cached != null && cached.first == hex) return cached.second
        val color = parseHex(hex, defaultOf(type))
        parsedCache[type] = hex to color
        return color
    }

    /** 类型 → 设置中的色值（OTHER → 空串，走 [defaultOf] 兜底）。 */
    fun hexOf(type: StatementType): String {
        val state = PluginSettings.getInstance().state
        return when (type) {
            StatementType.SELECT -> state.crudColorSelect
            StatementType.INSERT -> state.crudColorInsert
            StatementType.UPDATE -> state.crudColorUpdate
            StatementType.DELETE -> state.crudColorDelete
            // DDL 不新增设置项（用户定案）：复用对应 DML 的色
            StatementType.CREATE -> state.crudColorInsert
            StatementType.DROP -> state.crudColorDelete
            StatementType.OTHER -> ""
        }
    }

    /**
     * 解析 `#RRGGBB`（`#` 可省略）；非法 / 空 → [fallback]。
     *
     * 容错优先：设置项异常必须降级而非抛（与 `formatTimestampFragment` 对
     * `logTimestampFormat` 的 `IllegalArgumentException` 兜底同款）。
     */
    fun parseHex(hex: String, fallback: Color): Color {
        val raw = hex.trim().removePrefix("#")
        if (raw.length != 6) return fallback
        val value = try {
            raw.toLong(16)
        } catch (_: NumberFormatException) {
            return fallback
        }
        return Color(
            ((value shr 16) and 0xFF).toInt(),
            ((value shr 8) and 0xFF).toInt(),
            (value and 0xFF).toInt(),
        )
    }

    /**
     * 按比例混色（base 为主）：`ratio = 0` 得 base，`1` 得 tint。
     *
     * 用于整行淡背景——与列表当前背景混色即天然适配亮/暗主题，无需为两主题各配一套色值。
     */
    fun blend(base: Color, tint: Color, ratio: Double): Color {
        val r = ratio.coerceIn(0.0, 1.0)
        return Color(
            mixChannel(base.red, tint.red, r),
            mixChannel(base.green, tint.green, r),
            mixChannel(base.blue, tint.blue, r),
        )
    }

    /**
     * 逐通道线性插值——**显式 half-up**（`(v + 0.5).floor()`）。
     *
     * 不用 `kotlin.math.round`：它采用 ties-to-even（银行家舍入），
     * `round(76.5) = 76` 与常规直觉相反，会让设计稿色值表出现 1/255 的意外偏移。
     */
    private fun mixChannel(base: Int, tint: Int, ratio: Double): Int =
        kotlin.math.floor(base + (tint - base) * ratio + 0.5).toInt()
}
