// 自 master ui/icons/MpvsIcons.kt 移植（T-ToolbarIconSemantics：工具栏图标统一入口）
package cn.song.mybatisplus.visualizer.ui.icons;

import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * 工具栏图标统一入口（T-ToolbarIconSemantics）。
 *
 * <p>四个页签共用：SQL 日志、日志转 SQL、SQL→Java、SQL 历史。</p>
 *
 * <p>图形统一克隆自 IntelliJ expui 图标集（Apache 2.0，资源内保留原始版权注释），
 * 而不是引用 {@code com.intellij.icons.AllIcons} 字段——AllIcons 在 243 会回落经典实心图标、
 * 在 262 使用 expui 线性图标，同一按钮跨版本形态不一致。克隆到插件资源后，
 * 所有 IDE 版本都渲染同一套图形。</p>
 */
public final class MpvsIcons {

    private MpvsIcons() {
    }

    /** 从插件资源加载工具栏图标；同目录的 {@code _dark.svg} 由 IconLoader 按主题自动选用。 */
    @NotNull
    private static Icon icon(@NotNull String name) {
        return IconLoader.getIcon("/icons/toolbar/" + name + ".svg", MpvsIcons.class);
    }

    // ---------- SQL 日志 ----------

    /** 捕捉开始：绿色三角。 */
    public static final Icon CaptureStart = icon("run");

    /** 捕捉停止：红色方块。 */
    public static final Icon CaptureStop = icon("stop");

    /** 增强捕获暂停：双竖条。 */
    public static final Icon AgentPause = icon("pause");

    /** 增强捕获恢复：绿色三角带竖条。 */
    public static final Icon AgentResume = icon("resume");

    /** 清空列表 / 输入区 / 结果区：垃圾桶。 */
    public static final Icon Clear = icon("clear");

    /** 设置：齿轮。 */
    public static final Icon Settings = icon("settings");

    /** 屏蔽规则：自绘「眼睛 + 红斜线」。 */
    public static final Icon Suppress = icon("eyeOff");

    /** 搜索：放大镜。 */
    public static final Icon Search = icon("search");

    /** 停靠预览：分栏面板。 */
    public static final Icon DockedPreview = icon("dockedPreview");

    /** 滚动到底：列表 + 向下箭头。 */
    public static final Icon ScrollToEnd = icon("scrollToEnd");

    /** 排序方式：降序条。 */
    public static final Icon Sort = icon("sortDuration");

    /** 交流群：品牌聊天气泡（仓内自绘，不属于平台克隆）。 */
    public static final Icon Community = IconLoader.getIcon("/icons/communityChat.svg", MpvsIcons.class);

    /** 帮助：问号。 */
    public static final Icon Help = icon("help");

    /** SQL 类型过滤：漏斗。 */
    public static final Icon TypeFilter = icon("filter");

    // ---------- 四页签共用 ----------

    /** 转换 / 执行：绿色三角（与捕捉开始同形）。 */
    public static final Icon Convert = icon("run");

    /** 执行还原产物：绿色三角。 */
    public static final Icon Execute = icon("run");

    /** 从剪贴板读取：粘贴。 */
    public static final Icon Paste = icon("paste");

    /** 复制：复制。 */
    public static final Icon Copy = icon("copy");

    /** 格式化 / 压缩多行：格式化代码。 */
    public static final Icon Format = icon("format");

    /** 导出：导出（SQL 日志与 SQL 历史右键统一）。 */
    public static final Icon Export = icon("export");

    /** 返回：左箭头。 */
    public static final Icon Back = icon("back");

    /** 刷新：刷新。 */
    public static final Icon Refresh = icon("refresh");

    /** Diff 对比。 */
    public static final Icon Diff = icon("diff");

    /** 跳转源码：编辑源。 */
    public static final Icon EditSource = icon("editSource");

    /** 跳转 Mapper XML：XML 文件图标。 */
    public static final Icon MapperXml = icon("mapperXml");

    /** Mapper 接口方法侧的配对跳转标记（红头）。 */
    public static final Icon MapperMethod = icon("mapperMethod");

    /** Mapper XML 语句侧的配对跳转标记（青头）。 */
    public static final Icon MapperStatement = icon("mapperStatement");
}
