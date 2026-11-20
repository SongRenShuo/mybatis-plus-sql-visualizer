package cn.song.mybatisplus.visualizer.ui.standalone

import com.intellij.openapi.project.Project

/**
 * 缺失 `<sql id>` 片段的补齐流程（T-PreviewActionRow D-6）。
 *
 * ## 为什么抽出来
 *
 * 这条流程原先写死在独立工作台里（`SqlStandalonePreviewPanel.promptMissingFragment`），而弹窗侧
 * 完全没有——用户在工作台「在弹框中打开」后看到 `/* TODO: missing <sql id="…"> */`，却无处可补。
 * 补齐本身和「谁在展示」无关，两个面只是各有自己的 overrides 与「补完之后怎么重解析」。
 *
 * ## 分工
 *
 * - [promptViaDialog]：弹补齐框（保留「从文件选择…」入口，复用 [MapperFileLoader]）；取消/空内容返回 null。
 * - [record]：把内容记进调用方的 overrides 表（纯函数，可单测）。
 *
 * 重解析由调用方做——工作台重跑 [SqlStandaloneSourceResolver] 并回填内嵌面板，弹窗用原文重解析后
 * 回填自己的面板；两边共用同一条识别链，不出现第二套口径。
 */
internal object MissingFragmentFillFlow {

    /** 弹框问用户要内容（一次只问一个 refid：片段可能再引用别的缺失片段，补完重解析会自然收敛）。 */
    fun promptViaDialog(project: Project?, refid: String): String? {
        val dialog = MissingFragmentDialog(project, refid)
        if (!dialog.showAndGet()) return null
        return dialog.content().takeIf { it.isNotBlank() }
    }

    /** 记进 overrides；空白内容视为「没补」（返回 false，调用方不应触发重解析）。 */
    fun record(refid: String, content: String?, overrides: MutableMap<String, String>): Boolean {
        val body = content?.trim().orEmpty()
        if (body.isEmpty()) return false
        overrides[refid] = body
        return true
    }
}