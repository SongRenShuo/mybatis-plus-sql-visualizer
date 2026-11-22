package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * 「从工程里取一段 mapper XML 文本」的唯一实现（T-LoadFilePlacement D-1）。
 *
 * ## 为什么收拢成一处
 *
 * 这段「弹选择框 + 读文本」原先在两处各写一遍（预览弹窗的「从文件加载…」、补齐片段框的
 * 「从文件选择…」），两处对 `FileChooser` 描述符、`ReadAction` 包裹与空文本处理的口径迟早会分叉。
 * 现在输入源能力只归一到这里，上层各自只决定「拿到文本之后干什么」。
 *
 * ## 层位约定（本次改动的核心结论）
 *
 * 读文件是**输入源**能力，不是**展示**能力。调用方只应是「需要一段待解析文本」的入口：
 * 独立工作台（载入 → 解析预览）与补齐片段框（抽出 `<sql>` 片段）；通用预览弹窗只展示已渲染的
 * SQL，不再拥有该入口。
 *
 * ## 线程纪律（T-Perf33MergeAlign：选/读分离）
 *
 * 「弹选择框」必须在 EDT（`FileChooser.chooseFile` 是 UI 操作）；「读文本」是读权限 IO，
 * 大文件在 EDT 读会卡界面。故本对象只拆成两个原语：[chooseFile]（EDT，仅选）+ [loadText]
 * （后台，仅读）——调用方负责把 [loadText] 派到后台线程、回填再回 EDT。
 */
internal object MapperFileLoader {

    /** 一次成功读取的结果：文件名（状态条要展示来源）+ 文本。 */
    class LoadedFile(val fileName: String, val text: String)

    /**
     * 弹单文件选择框（只过滤 xml）并返回选中的虚拟文件；用户取消返回 null。
     *
     * **必须在 EDT 调用**（`FileChooser.chooseFile` 是 UI 操作）。读文本交给 [loadText]，
     * 由调用方在后台线程执行——选文件与读文件在两处，各自放在对的线程上。
     *
     * @param titleKey 选择框标题的 bundle 键（两个入口文案不同，但行为同一）。
     * @param parentComponent 选择器的**父组件**。从模态框里调用时**必须**传当前框自己的窗口，
     *   否则选择器解析不到 owner：会被压在模态框后面或干脆不显示，用户观感是「点了没反应」
     *   （T-PreviewInfoLayout 问题一：补齐框是 `DialogWrapper(project, true)` 模态框，两处原先都传 null）。
     */
    fun chooseFile(
        project: Project?,
        titleKey: String,
        parentComponent: java.awt.Component? = null,
    ): VirtualFile? {
        val descriptor = FileChooserDescriptorFactory
            .createSingleFileDescriptor("xml")
            .withTitle(MpSqlVisualizerBundle.message(titleKey))
        // 平台 243 的「带父组件」重载是 (descriptor, parent, project, toSelect)——注意 parent 在前；
        // 若走 (descriptor, project, toSelect) 那条，模态框里没有 owner，选择器会被挡住/不显示。
        return FileChooser.chooseFile(descriptor, parentComponent, project, null)
    }

    /**
     * 读虚拟文件全文（读权限 + 失败兜底）。
     *
     * **应由调用方在后台线程调用**（读权限 IO，大文件在 EDT 会卡界面）。
     *
     * 243 编译目标下 `ReadAction.compute(ThrowableComputable)` 是唯一可用重载（已标 deprecated，
     * 与仓内既有调用点同处置）。
     */
    fun loadText(file: VirtualFile): String? = runCatching {
        @Suppress("DEPRECATION") // 243 编译目标：ReadAction.compute(ThrowableComputable) 仅此重载
        com.intellij.openapi.application.ReadAction.compute<String, Throwable> {
            VfsUtilCore.loadText(file)
        }
    }.getOrNull()
}
