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
 */
internal object MapperFileLoader {

    /** 一次成功读取的结果：文件名（状态条要展示来源）+ 文本。 */
    class LoadedFile(val fileName: String, val text: String)

    /**
     * 弹单文件选择框（只过滤 xml）并读回文本；用户取消、读取失败或内容为空都返回 null。
     *
     * @param titleKey 选择框标题的 bundle 键（两个入口文案不同，但行为同一）。
     */
    fun choose(project: Project?, titleKey: String): LoadedFile? {
        val descriptor = FileChooserDescriptorFactory
            .createSingleFileDescriptor("xml")
            .withTitle(MpSqlVisualizerBundle.message(titleKey))
        val file: VirtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return null
        val text = loadText(file)?.takeIf { it.isNotBlank() } ?: return null
        return LoadedFile(file.name, text)
    }

    /**
     * 读虚拟文件全文（读权限 + 失败兜底）。
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