// T-SyncK2JMaster181 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/MissingFragmentFillFlow.kt 移植
package cn.song.mybatisplus.visualizer.ui.standalone;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 缺失 {@code <sql id>} 片段的补齐流程（T-PreviewActionRow D-6）。
 *
 * <h2>为什么抽出来</h2>
 *
 * <p>这条流程原先写死在独立工作台里（{@code SqlStandalonePreviewPanel.promptMissingFragment}），而弹窗侧
 * 完全没有——用户在工作台「在弹框中打开」后看到 {@code /* TODO: missing <sql id="…"> *&#47;}，却无处可补。
 * 补齐本身和「谁在展示」无关，两个面只是各有自己的 overrides 与「补完之后怎么重解析」。</p>
 *
 * <h2>分工</h2>
 *
 * <ul>
 *  <li>{@link #promptViaDialog}：弹补齐框（保留「从文件选择…」入口，复用 {@link MapperFileLoader}）；
 *      取消/空内容返回 null。</li>
 *  <li>{@link #record}：把内容记进调用方的 overrides 表（纯函数，可单测）。</li>
 * </ul>
 *
 * <p>重解析由调用方做——工作台重跑 {@link StandaloneSqlSourceResolver} 并回填内嵌面板，弹窗用原文重解析后
 * 回填自己的面板；两边共用同一条识别链，不出现第二套口径。</p>
 */
public final class MissingFragmentFillFlow {

    private MissingFragmentFillFlow() {
    }

    /** 弹框问用户要内容（一次只问一个 refid：片段可能再引用别的缺失片段，补完重解析会自然收敛）。 */
    @Nullable
    public static String promptViaDialog(@Nullable Project project, @NotNull String refid) {
        MissingFragmentDialog dialog = new MissingFragmentDialog(project, refid);
        if (!dialog.showAndGet()) {
            return null;
        }
        String content = dialog.content();
        return content != null && !content.isBlank() ? content : null;
    }

    /** 记进 overrides；空白内容视为「没补」（返回 false，调用方不应触发重解析）。 */
    public static boolean record(@NotNull String refid, @Nullable String content,
                                 @NotNull Map<String, String> overrides) {
        String body = content != null ? content.trim() : "";
        if (body.isEmpty()) {
            return false;
        }
        overrides.put(refid, body);
        return true;
    }
}
