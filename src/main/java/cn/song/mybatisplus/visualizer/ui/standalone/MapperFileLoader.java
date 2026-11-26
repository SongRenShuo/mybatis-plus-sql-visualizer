// T-SyncK2JMaster181 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/standalone/MapperFileLoader.kt 移植
package cn.song.mybatisplus.visualizer.ui.standalone;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;

/**
 * 「从工程里取一段 mapper XML 文本」的唯一实现（T-LoadFilePlacement D-1）。
 *
 * <h2>为什么收拢成一处</h2>
 *
 * <p>这段「弹选择框 + 读文本」原先在两处各写一遍（预览弹窗的「从文件加载…」、补齐片段框的
 * 「从文件选择…」），两处对 {@code FileChooser} 描述符、{@code ReadAction} 包裹与空文本处理的口径迟早会分叉。
 * 现在输入源能力只归一到这里，上层各自只决定「拿到文本之后干什么」。</p>
 *
 * <h2>层位约定（本次改动的核心结论）</h2>
 *
 * <p>读文件是<b>输入源</b>能力，不是<b>展示</b>能力。调用方只应是「需要一段待解析文本」的入口：
 * 独立工作台（载入 → 解析预览）与补齐片段框（抽出 {@code <sql>} 片段）；通用预览弹窗只展示已渲染的
 * SQL，不再拥有该入口。</p>
 *
 * <h2>线程纪律（T-Perf33MergeAlign：选/读分离）</h2>
 *
 * <p>「弹选择框」必须在 EDT（{@code FileChooser.chooseFile} 是 UI 操作）；「读文本」是读权限 IO，
 * 大文件在 EDT 读会卡界面。故本类只拆成两个原语：{@link #chooseFile}（EDT，仅选）+ {@link #loadText}
 * （后台，仅读）——调用方负责把 {@link #loadText} 派到后台线程、回填再回 EDT。</p>
 */
public final class MapperFileLoader {

    private MapperFileLoader() {
    }

    /** 一次成功读取的结果：文件名（状态条要展示来源）+ 文本。 */
    public record LoadedFile(@NotNull String fileName, @NotNull String text) {
    }

    /**
     * 弹单文件选择框（只过滤 xml）并返回选中的虚拟文件；用户取消返回 null。
     *
     * <p><b>必须在 EDT 调用</b>（{@code FileChooser.chooseFile} 是 UI 操作）。读文本交给 {@link #loadText}，
     * 由调用方在后台线程执行——选文件与读文件在两处，各自放在对的线程上。</p>
     *
     * @param titleKey 选择框标题的 bundle 键（两个入口文案不同，但行为同一）。
     * @param parentComponent 选择器的<b>父组件</b>。从模态框里调用时<b>必须</b>传当前框自己的窗口，
     *   否则选择器解析不到 owner：会被压在模态框后面或干脆不显示，用户观感是「点了没反应」
     *   （T-PreviewInfoLayout 问题一：补齐框是 {@code DialogWrapper(project, true)} 模态框，两处原先都传 null）。
     */
    @Nullable
    public static VirtualFile chooseFile(@Nullable Project project, @NotNull String titleKey,
                                        @Nullable Component parentComponent) {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory
            .createSingleFileDescriptor("xml")
            .withTitle(MpSqlVisualizerBundle.message(titleKey));
        // 平台 243 的「带父组件」重载是 (descriptor, parent, project, toSelect)——注意 parent 在前；
        // 若走 (descriptor, project, toSelect) 那条，模态框里没有 owner，选择器会被挡住/不显示。
        return FileChooser.chooseFile(descriptor, parentComponent, project, null);
    }

    /** 2 参形态（master 默认参展开：parentComponent 取 null——仅供无归属窗口的调用点）。 */
    @Nullable
    public static VirtualFile chooseFile(@Nullable Project project, @NotNull String titleKey) {
        return chooseFile(project, titleKey, null);
    }

    /**
     * 读虚拟文件全文（读权限 + 失败兜底）。
     *
     * <p><b>应由调用方在后台线程调用</b>（读权限 IO，大文件在 EDT 会卡界面）。</p>
     *
     * <p>243 编译目标下 {@code ReadAction.compute(ThrowableComputable)} 是唯一可用重载（已标 deprecated，
     * 与仓内既有调用点同处置）。</p>
     */
    @Nullable
    public static String loadText(@NotNull VirtualFile file) {
        try {
            return ReadAction.compute(() -> VfsUtilCore.loadText(file));
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            return null;
        }
    }
}
