package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.util.MpvsUiColors;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL 列表 CRUD 类型颜色标记配色门面（T-CrudColor，纯逻辑可单测）。
 *
 * 职责：把「类型 → 标记色」的映射与混色计算收在一处，供两个列表渲染器
 * （{@link LogEntryListCellRenderer} / {@link HistoryCellRenderer}）共用；设置态直读
 * {@link PluginSettings}（与既有 formatTimestampFragment / formatDurationFragment 同范式）。
 *
 * 标记形态（{@code PluginSettings.State.crudColorStyle}）：
 *  - {@link #STYLE_BAR}：左侧色条（默认）——选中态走平台原生填充，色条不受影响；
 *  - {@link #STYLE_BACKGROUND}：整行淡背景（仅<b>未选中</b>行）——选中行交给平台选中填充
 *    （T-UN-09：自研选中描边/选中混色已退役）；
 *  - {@link #STYLE_OFF}：关闭，与改造前逐字节一致。
 *
 * 类型口径与 {@link LogStatementClassifier} 完全一致（即与类型过滤弹窗同源），保证
 * 「筛出来的 SELECT 行不会被标成红色」。OTHER 恒不标记。
 */
public final class SqlListTypeColor {

    private SqlListTypeColor() {
    }

    /** 左侧色条（默认）。 */
    public static final String STYLE_BAR = "bar";

    /** 整行淡背景（仅未选中行；选中行走平台选中填充）。 */
    public static final String STYLE_BACKGROUND = "background";

    /** 关闭标记（现状行为）。 */
    public static final String STYLE_OFF = "off";

    /** 未选中行的混色比例（淡色调）。 */
    public static final double MIX_NORMAL = 0.14;

    /**
     * 左侧类型色条宽度（px）。
     * 从左内边距中切分（渲染器左内边距 5 + 色条 3 = 原 8），故文本左缘像素位置与改造前一致。
     */
    public static final int BAR_WIDTH = 3;

    public static final String DEFAULT_SELECT = "#4A86E8";
    public static final String DEFAULT_INSERT = "#4CAF50";
    public static final String DEFAULT_UPDATE = "#E67E22";
    public static final String DEFAULT_DELETE = "#D32F2F";

    // 默认色实例（预建，避免 defaultOf 每次解析/分配）。
    // T-UN-13（T-UiNative）：写死兜底色 → namedColor（亮值不变；仅兜底实例收口，
    // 用户配置链 hexOf/parseHex 原样——设置页 hex 串默认值即上方 DEFAULT_* 常量）。
    private static final Color COLOR_SELECT = MpvsUiColors.CRUD_SELECT;
    private static final Color COLOR_INSERT = MpvsUiColors.CRUD_INSERT;
    private static final Color COLOR_UPDATE = MpvsUiColors.CRUD_UPDATE;
    private static final Color COLOR_DELETE = MpvsUiColors.CRUD_DELETE;

    /**
     * 解析结果缓存（每类型至多 1 条，共 ≤ 4 条）。
     *
     * <b>存在的理由不是省那一次字符串解析，而是省掉 Swing 的重绘副作用</b>：实测（JBR21）
     * {@code JComponent.setBackground(new Color(同值))} 会触发 {@code repaint() +1}，而传入<b>同一实例</b>则是 0。
     * 渲染器每格都要给色条/单元格设背景，若每次现解析就会每格多一次 repaint —— 这正是
     * T-LogListFreeze 那类「渲染热路径逐格副作用累积」问题的成因。缓存后同色格复用同一实例。
     */
    private static final ConcurrentHashMap<StatementType, Map.Entry<String, Color>> PARSED_CACHE =
            new ConcurrentHashMap<>();

    /**
     * 类型 → 默认色（用户未配置 / 配置非法时的兜底）。
     *
     * DDL 两档<b>复用</b> DML 的色（T-DdlTypeColor）：CREATE 取增绿、DROP 取删红。
     * 视觉区分不靠色相而靠<b>线条形态</b>（虚线 vs 实心，见 TypeColorBar 的虚线绘制）。
     */
    public static Color defaultOf(StatementType type) {
        switch (type) {
            case SELECT:
                return COLOR_SELECT;
            case INSERT:
                return COLOR_INSERT;
            case UPDATE:
                return COLOR_UPDATE;
            case DELETE:
                return COLOR_DELETE;
            case CREATE:
                return COLOR_INSERT;
            case DROP:
                return COLOR_DELETE;
            default:
                return COLOR_SELECT; // OTHER
        }
    }

    /**
     * 是否 DDL 类型（色条画虚线）。
     *
     * 纯函数，渲染热路径直接调用（不做设置读取）。
     */
    public static boolean isDdl(StatementType type) {
        return type == StatementType.CREATE || type == StatementType.DROP;
    }

    /** 当前样式（读设置薄壳）。 */
    public static String styleOf() {
        return styleOf(PluginSettings.getInstance().getState().getCrudColorStyle());
    }

    /**
     * <b>纯逻辑</b>：原始样式串 → 样式常量；未知值按 {@link #STYLE_BAR} 兜底
     * ——不留「配置写错就整列表失色」的口子。
     */
    public static String styleOf(String raw) {
        switch (raw) {
            case STYLE_BAR:
                return STYLE_BAR;
            case STYLE_BACKGROUND:
                return STYLE_BACKGROUND;
            case STYLE_OFF:
                return STYLE_OFF;
            default:
                return STYLE_BAR;
        }
    }

    /**
     * 类型 → 标记色（读设置薄壳；色值来源见 {@link #hexOf}）。
     *
     * 关闭样式 / OTHER → null（不标记）；空串或非法值回退 {@link #defaultOf}（不抛异常、不写坏配置）。
     */
    public static Color colorOf(StatementType type) {
        return colorOf(type, styleOf(), hexOf(type));
    }

    /**
     * <b>纯逻辑</b>：类型 + 样式 + 色值 → 标记色（null = 不标记）。
     *
     * 与设置解耦，便于零平台依赖单测；{@link #colorOf(StatementType)} 只负责把设置态喂进来。
     */
    public static Color colorOf(StatementType type, String style, String hex) {
        if (STYLE_OFF.equals(style)) {
            return null;
        }
        if (type == StatementType.OTHER) {
            return null;
        }
        // 同色复用同一 Color 实例：既省分配，也避免 Swing setBackground 因「新实例同值」触发 repaint
        Map.Entry<String, Color> cached = PARSED_CACHE.get(type);
        if (cached != null && cached.getKey().equals(hex)) {
            return cached.getValue();
        }
        Color color = parseHex(hex, defaultOf(type));
        PARSED_CACHE.put(type, Map.entry(hex, color));
        return color;
    }

    /** 类型 → 设置中的色值（OTHER → 空串，走 {@link #defaultOf} 兜底）。 */
    public static String hexOf(StatementType type) {
        PluginSettings.State state = PluginSettings.getInstance().getState();
        switch (type) {
            case SELECT:
                return state.getCrudColorSelect();
            case INSERT:
                return state.getCrudColorInsert();
            case UPDATE:
                return state.getCrudColorUpdate();
            case DELETE:
                return state.getCrudColorDelete();
            // DDL 不新增设置项（用户定案）：复用对应 DML 的色
            case CREATE:
                return state.getCrudColorInsert();
            case DROP:
                return state.getCrudColorDelete();
            default:
                return ""; // OTHER
        }
    }

    /**
     * 解析 {@code #RRGGBB}（{@code #} 可省略）；非法 / 空 → fallback。
     *
     * 容错优先：设置项异常必须降级而非抛（与 formatTimestampFragment 对
     * logTimestampFormat 的 IllegalArgumentException 兜底同款）。
     */
    public static Color parseHex(String hex, Color fallback) {
        String raw = hex.trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        if (raw.length() != 6) {
            return fallback;
        }
        long value;
        try {
            value = Long.parseLong(raw, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
        return new Color(
                (int) ((value >> 16) & 0xFF),
                (int) ((value >> 8) & 0xFF),
                (int) (value & 0xFF));
    }

    /**
     * 按比例混色（base 为主）：{@code ratio = 0} 得 base，{@code 1} 得 tint。
     *
     * 用于整行淡背景——与列表当前背景混色即天然适配亮/暗主题，无需为两主题各配一套色值。
     */
    public static Color blend(Color base, Color tint, double ratio) {
        double r = Math.max(0.0, Math.min(1.0, ratio));
        return new Color(
                mixChannel(base.getRed(), tint.getRed(), r),
                mixChannel(base.getGreen(), tint.getGreen(), r),
                mixChannel(base.getBlue(), tint.getBlue(), r));
    }

    /**
     * 逐通道线性插值——<b>显式 half-up</b>（{@code (v + 0.5).floor()}）。
     *
     * 不用 {@code Math.round}（语义同 Kotlin ties-to-even 教训）：
     * Kotlin 版注释——kotlin.math.round 采用 ties-to-even（银行家舍入），
     * {@code round(76.5) = 76} 与常规直觉相反，会让设计稿色值表出现 1/255 的意外偏移。
     */
    private static int mixChannel(int base, int tint, double ratio) {
        return (int) Math.floor(base + (tint - base) * ratio + 0.5);
    }
}
