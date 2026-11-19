package cn.song.mybatisplus.visualizer.util;

import com.intellij.ui.JBColor;
import java.awt.Color;

/**
 * UI 写死颜色统一出口（T-UiNative T-UN-13，调研报告 §5.10）。
 *
 * 背景：IDE 内 UI 组件此前散落裸 {@code Color(0x…)} / {@code JBColor(0x…, 0x…)} 字面量，第三方主题
 * 无法覆写。统一改走 {@link JBColor#namedColor}：默认值 = 改造前视觉（<b>亮值严格等于原值</b>），
 * 同时注册命名键，主题可用 {@code UIManager} 同名键覆写。
 *
 * 约定：
 *  - 键名一律 {@code mpvs.} 前缀 + 区域段 + 蛇形语义名（{@code mpvs.log.slowQuery}）；
 *  - 亮值 = 现网值；暗值优先取原 {@code JBColor} 已有暗值（直接平移），原本只有单值的色自配
 *    同色相提亮对应色（各常量注释标注来源：现值 / 自配）；
 *  - 243 API 口径（本地 app-client.jar javap 实证）：{@code namedColor(String, int, int)} 与
 *    {@code namedColor(String, Color)} 可用，<b>无</b> {@code namedColor(String, Color, Color)} 三参重载；
 *    不透明色一律走 int 双值重载，含 alpha 的色走 {@code namedColor(name, JBColor(亮, 暗))}
 *    （平台内部 {@code namedColor(String,int,int)} 即此形态的语法糖，暗色兜底语义一致）；
 *  - 不收口范围：SqlHighlightColors（TextAttributesKey 机制）、HtmlReportFormatter
 *    （导出 CSS 非 IDE 组件）、DiagramPanel 的 UIUtil 调色（已主题感知）、用户可配置色
 *    （SqlListTypeColor 设置链，仅兜底色收口）。
 */
public final class MpvsUiColors {

    private MpvsUiColors() {
    }

    // ---------------- SQL 日志列表（log/SqlLogPanel） ----------------

    /** 工具栏慢查询计数红。亮 0xD32F2F 现值；暗 0xE05555 自配（与预览性能提示红同族）。 */
    public static final JBColor SLOW_RED = JBColor.namedColor("mpvs.log.slowQuery", 0xD32F2F, 0xE05555);

    /** 列表元信息灰（时间戳/来源/徽章）。亮 0x9E9E9E 现值；暗 0x808080 自配。 */
    public static final JBColor META_GRAY = JBColor.namedColor("mpvs.log.metaGray", 0x9E9E9E, 0x808080);

    /** 耗时五档——选中态白（白无深浅变体，暗值同亮值）。亮 0xFFFFFF 现值。 */
    public static final JBColor DURATION_SELECTED = JBColor.namedColor("mpvs.log.durationSelected", 0xFFFFFF, 0xFFFFFF);

    /** 耗时五档——无耗时灰。亮 0x9E9E9E 现值；暗 0x808080 自配。 */
    public static final JBColor DURATION_NONE = JBColor.namedColor("mpvs.log.durationNone", 0x9E9E9E, 0x808080);

    /** 耗时五档——慢查询红。亮 0xD32F2F 现值；暗 0xE05555 自配。 */
    public static final JBColor DURATION_SLOW = JBColor.namedColor("mpvs.log.durationSlow", 0xD32F2F, 0xE05555);

    /** 耗时五档——偏慢橙。亮 0xE67E22 现值；暗 0xF39C12 自配（同族扁平橙提亮）。 */
    public static final JBColor DURATION_WARN = JBColor.namedColor("mpvs.log.durationWarn", 0xE67E22, 0xF39C12);

    /** 耗时五档——正常绿。亮 0x4CAF50 现值；暗 0x66BB6A 自配（Material green 400 提亮）。 */
    public static final JBColor DURATION_OK = JBColor.namedColor("mpvs.log.durationOk", 0x4CAF50, 0x66BB6A);

    /** O-90：同模板（疑似 N+1）计数徽章——琥珀色与耗时偏慢同族（attention 不报警）。亮 0xE67E22；暗 0xF39C12。 */
    public static final JBColor TEMPLATE_N1 = JBColor.namedColor("mpvs.log.templateN1", 0xE67E22, 0xF39C12);

    /** T-HdAbsorb D216：N+1 嫌疑徽章——红色行动信号（与「同模板计数」的琥珀提示分层）。亮 0xC62828；暗 0xEF5350。 */
    public static final JBColor NPLUS_ONE_SUSPECT = JBColor.namedColor("mpvs.log.nplusOneSuspect", 0xC62828, 0xEF5350);

    // ---------------- 历史 tab（log/HistorySqlLogPanel） ----------------

    /** 历史面板元信息灰。亮 0x9E9E9E 现值；暗 0x808080 自配（独立键便于与主列表分开覆写）。 */
    public static final JBColor HISTORY_META_GRAY = JBColor.namedColor("mpvs.history.metaGray", 0x9E9E9E, 0x808080);

    // ---------------- CRUD 类型条默认兜底色（log/SqlListTypeColor） ----------------
    // 仅兜底实例收口；用户在设置页配置的 hex 串链路（hexOf/parseHex）原样不动。

    /** SELECT 兜底。亮 0x4A86E8 现值；暗 0x6FA1F0 自配（同相提亮）。 */
    public static final JBColor CRUD_SELECT = JBColor.namedColor("mpvs.crud.select", 0x4A86E8, 0x6FA1F0);

    /** INSERT 兜底。亮 0x4CAF50 现值；暗 0x66BB6A 自配。 */
    public static final JBColor CRUD_INSERT = JBColor.namedColor("mpvs.crud.insert", 0x4CAF50, 0x66BB6A);

    /** UPDATE 兜底。亮 0xE67E22 现值；暗 0xF39C12 自配。 */
    public static final JBColor CRUD_UPDATE = JBColor.namedColor("mpvs.crud.update", 0xE67E22, 0xF39C12);

    /** DELETE 兜底。亮 0xD32F2F 现值；暗 0xE05555 自配。 */
    public static final JBColor CRUD_DELETE = JBColor.namedColor("mpvs.crud.delete", 0xD32F2F, 0xE05555);

    // ---------------- JOIN 关系图（ui/diagram/DiagramRenderer） ----------------
    // 16 色板：亮/暗双值均为原 JBColor 现值（原本就是双值，直接平移，无自配）。
    // SHADOW 含 alpha，走 JBColor 包装形态；其余不透明色走 int 双值重载。

    /** 卡片投影（半透明黑，含 alpha）。亮/暗均为现值。 */
    public static final JBColor DIAGRAM_SHADOW = JBColor.namedColor("mpvs.diagram.shadow",
            new JBColor(new Color(0x00, 0x00, 0x00, 25), new Color(0x00, 0x00, 0x00, 45)));

    /** 卡片底色。 */
    public static final JBColor DIAGRAM_CARD_BG = JBColor.namedColor("mpvs.diagram.cardBackground", 0xFFFFFF, 0x2D2F33);

    /** 卡片交替行底色。 */
    public static final JBColor DIAGRAM_CARD_BG_ALT = JBColor.namedColor("mpvs.diagram.cardBackgroundAlt", 0xF7F8FA, 0x2A2C30);

    /** 卡片边框。 */
    public static final JBColor DIAGRAM_CARD_BORDER = JBColor.namedColor("mpvs.diagram.cardBorder", 0xC8CCD4, 0x50535A);

    /** 主表表头底色。 */
    public static final JBColor DIAGRAM_MAIN_HDR_BG = JBColor.namedColor("mpvs.diagram.mainHeaderBackground", 0xDCE8DC, 0x2E3B2E);

    /** JOIN 表表头底色。 */
    public static final JBColor DIAGRAM_JOIN_HDR_BG = JBColor.namedColor("mpvs.diagram.joinHeaderBackground", 0xDCE5ED, 0x2A3038);

    /** 表头下划线。 */
    public static final JBColor DIAGRAM_HEADER_LINE = JBColor.namedColor("mpvs.diagram.headerLine", 0xB8BCC4, 0x4A4E56);

    /** 行分隔线。 */
    public static final JBColor DIAGRAM_SEPARATOR = JBColor.namedColor("mpvs.diagram.separator", 0xE4E6EA, 0x3A3D42);

    /** 单元文本色。 */
    public static final JBColor DIAGRAM_TEXT = JBColor.namedColor("mpvs.diagram.text", 0x1A1A1A, 0xBBBBBB);

    /** 类型列文本色。 */
    public static final JBColor DIAGRAM_TYPE_COLOR = JBColor.namedColor("mpvs.diagram.typeColor", 0x888888, 0x808080);

    /** 主键标记色。 */
    public static final JBColor DIAGRAM_PK_COLOR = JBColor.namedColor("mpvs.diagram.primaryKey", 0x9A6800, 0xE2C079);

    /** 连线色。 */
    public static final JBColor DIAGRAM_LINE_COLOR = JBColor.namedColor("mpvs.diagram.line", 0x555555, 0x777777);

    /** 连线标签底色。 */
    public static final JBColor DIAGRAM_LABEL_BG = JBColor.namedColor("mpvs.diagram.labelBackground", 0xFFFFFF, 0x33363B);

    /** 连线标签边框。 */
    public static final JBColor DIAGRAM_LABEL_BORDER = JBColor.namedColor("mpvs.diagram.labelBorder", 0xC0C0C0, 0x50535A);

    /** JOIN 类型徽章底色。 */
    public static final JBColor DIAGRAM_JOIN_BADGE_BG = JBColor.namedColor("mpvs.diagram.joinBadgeBackground", 0xE8EAED, 0x3A3D42);

    /** JOIN 类型徽章文本色。 */
    public static final JBColor DIAGRAM_JOIN_BADGE_TEXT = JBColor.namedColor("mpvs.diagram.joinBadgeText", 0x555555, 0x999999);

    // ---------------- 设置页（config/PluginSettingsFolderConfigurable） ----------------

    /** 定位闪烁描边（黄橙）。亮/暗均为原 JBColor 现值。 */
    public static final JBColor HIGHLIGHT_BORDER = JBColor.namedColor("mpvs.settings.highlightBorder", 0xE6C068, 0x6B5B2E);

    /** 定位闪烁底色（淡黄）。亮/暗均为原 JBColor 现值。 */
    public static final JBColor HIGHLIGHT_BACKGROUND = JBColor.namedColor("mpvs.settings.highlightBackground", 0xFFFAE6, 0x3A3320);

    // ---------------- 反馈（feedback/ErrorReportDialog） ----------------

    /** 提交失败状态行红。亮 0xC0392B 现值；暗 0xE74C3C 自配（同族扁平红提亮）。 */
    public static final JBColor STATUS_ERROR = JBColor.namedColor("mpvs.feedback.statusError", 0xC0392B, 0xE74C3C);

    // ---------------- SQL 预览性能提示（ui/SqlPreviewPanel，HTML font color 注入） ----------------

    /** 性能提示 ERROR/WARN 段色。亮 0xE05555 现值；暗 0xF08080 自配。 */
    public static final JBColor PERF_ERROR_TEXT = JBColor.namedColor("mpvs.preview.perfError", 0xE05555, 0xF08080);

    /** 性能提示 INFO 段色。亮 0x3B82F6 现值；暗 0x60A5FA 自配（同族蓝提亮）。 */
    public static final JBColor PERF_INFO_TEXT = JBColor.namedColor("mpvs.preview.perfInfo", 0x3B82F6, 0x60A5FA);

    /**
     * Color → {@code #rrggbb}（HTML {@code font color} 注入用；补零到 6 位，防主题覆写值小于
     * 0x100000 时 {@code Integer.toHexString} 丢前导零产生坏 HTML）。
     */
    public static String htmlHex(Color color) {
        return "#" + String.format("%06x", color.getRGB() & 0xFFFFFF);
    }
}
