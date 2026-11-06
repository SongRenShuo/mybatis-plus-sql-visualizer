package cn.song.mybatisplus.visualizer.util

/**
 * 共享 HTML 转义工具（T-SAF-09 share.html-report-export.unescaped-tableName-src /
 * T-SAF-10 ui.swing-html-label.unescaped-lower-trust-text）。
 *
 * 背景：HTML 转义此前是**各站点各自为政**的逐点实现（CommunityDialog / ExplainPlanDialog /
 * ExplainPlanResultView / RollbackSqlDialog / SqlResultPanel 内联 / SqlPreviewPanelParamPanel
 * 各一份，转义集与完整度还不一致），没有共享不变式——三个汇聚点（回滚快照行、预览 meta 链、
 * 错误 tooltip）因此漏转义。本类是唯一的转义实现源：各站点一律委托到这里，不再持有本地变体。
 *
 * 转义集：`& < > " '`（Swing HTML 与导出 HTML 报告共用的保守集；`&` 必须最先，防二次转义）。
 *
 * 本类为纯 JVM 实现，可独立单测。
 */
object HtmlEscape {

    /**
     * HTML 转义：数据进入任何 HTML 上下文（Swing `<html>` 标签、导出 HTML 报告）前的统一闸。
     * 只转义数据字符，不动调用方的标记包装（`<b>`/`<code>`/`<br>` 等由调用方自行拼接）。
     */
    fun escape(text: String): String = buildString(text.length + 8) {
        for (ch in text) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }

    /**
     * 工具提示安全化：Swing 工具提示仅在文本以 `<html>` 起结时按 HTML 渲染
     * （javax.swing.plaf.basic.BasicHTML.isHTMLString）——以 `<html>` 起结的低信任文本
     * （如数据库服务器/代理返回的错误消息）可借 tooltip 注入内容并触发 `<img src>` 资源加载。
     *
     * 策略：非 HTML 起结的文本原样返回（纯文本渲染，显示语义逐字不变）；HTML 起结的
     * 文本转义后以字面量显示（阻断注入）。容错起结前的首部空白。
     */
    fun tooltipText(text: String): String {
        val looksHtml = text.startsWith("<html", ignoreCase = true) ||
            text.trimStart().startsWith("<html", ignoreCase = true)
        return if (looksHtml) escape(text) else text
    }
}
