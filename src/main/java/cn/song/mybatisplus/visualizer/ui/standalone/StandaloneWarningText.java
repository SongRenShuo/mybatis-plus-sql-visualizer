// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/StandaloneWarningText.kt 移植
package cn.song.mybatisplus.visualizer.ui.standalone;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立预览的结构化提示文案（T-StandaloneIbatisRecognize D-4 / T-DialogPasteReparse 共用）。
 *
 * <p>为什么单独成对象：工作台面板与预览弹窗<b>两条入口</b>都会走到解析失败/未识别，
 * 文案必须同源——否则同一个片段在页签里看到五段、在弹窗里只看到一句英文报错。</p>
 *
 * <p>形态：单行、{@code ｜} 分段。面板 meta 行与警告横幅都是纯文本 {@code JBLabel}（不渲染 {@code \n}），
 * 多行会挤成一坨且丢分隔；单行分段在 meta / 横幅 / 剪贴板三处形态一致。</p>
 */
public final class StandaloneWarningText {

    private StandaloneWarningText() {
    }

    /** 解析失败五段：标题｜来源｜语句｜原因｜建议（+ 另一条链同样失败时追加一句）。 */
    public static String failure(StandaloneSqlSourceResolver.Failure failure) {
        List<String> parts = new ArrayList<>();
        parts.add(MpSqlVisualizerBundle.message("standalone.error.title"));
        parts.add(MpSqlVisualizerBundle.message("standalone.error.source"));
        String statement = failure.statementId() != null ? failure.statementId() : "?";
        if (failure.statementTagName() != null) {
            statement = statement + " (<" + failure.statementTagName() + ">)";
        }
        parts.add(MpSqlVisualizerBundle.message("standalone.error.statement", statement));
        if (failure.reason() != null) {
            parts.add(MpSqlVisualizerBundle.message("standalone.error.reason", failure.reason()));
        }
        parts.add(MpSqlVisualizerBundle.message(
            failure.flavor() == XmlFlavorDetector.Flavor.IBATIS
                ? "standalone.error.advice.ibatis"
                : "standalone.error.advice.mybatis"));
        if (failure.otherChainFailed()) {
            parts.add(MpSqlVisualizerBundle.message("standalone.error.otherChain"));
        }
        return String.join("｜", parts);
    }

    /** 未识别文本的信息级说明（面板与弹窗同源）。 */
    public static String rawTextNotice() {
        return MpSqlVisualizerBundle.message("standalone.status.unrecognizedInDialog");
    }
}
