package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * 独立预览的结构化提示文案（T-StandaloneIbatisRecognize D-4 / T-DialogPasteReparse 共用）。
 *
 * 为什么单独成对象：工作台面板与预览弹窗**两条入口**都会走到解析失败/未识别，
 * 文案必须同源——否则同一个片段在页签里看到五段、在弹窗里只看到一句英文报错。
 *
 * 形态：单行、`｜` 分段。面板 meta 行与警告横幅都是纯文本 `JBLabel`（不渲染 `\n`），
 * 多行会挤成一坨且丢分隔；单行分段在 meta / 横幅 / 剪贴板三处形态一致。
 */
internal object StandaloneWarningText {

    /** 解析失败五段：标题｜来源｜语句｜原因｜建议（+ 另一条链同样失败时追加一句）。 */
    fun failure(failure: StandaloneSqlSourceResolver.Failure): String {
        val parts = mutableListOf(MpSqlVisualizerBundle.message("standalone.error.title"))
        parts += MpSqlVisualizerBundle.message("standalone.error.source")
        val statement = buildString {
            append(failure.statementId ?: "?")
            failure.statementTagName?.let { append(" (<").append(it).append(">)") }
        }
        parts += MpSqlVisualizerBundle.message("standalone.error.statement", statement)
        failure.reason?.let { parts += MpSqlVisualizerBundle.message("standalone.error.reason", it) }
        parts += MpSqlVisualizerBundle.message(
            if (failure.flavor == XmlFlavorDetector.Flavor.IBATIS) {
                "standalone.error.advice.ibatis"
            } else {
                "standalone.error.advice.mybatis"
            },
        )
        if (failure.otherChainFailed) {
            parts += MpSqlVisualizerBundle.message("standalone.error.otherChain")
        }
        return parts.joinToString("｜")
    }

    /** 未识别文本的信息级说明（面板与弹窗同源）。 */
    fun rawTextNotice(): String = MpSqlVisualizerBundle.message("standalone.status.unrecognizedInDialog")
}
