package cn.song.mybatisplus.visualizer.share.format

import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer
import cn.song.mybatisplus.visualizer.ui.highlight.SqlTokenTypes
import com.intellij.psi.tree.IElementType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * HTML 报告格式化器（F-8 Phase B）。
 *
 * 生成带语法高亮的完整 HTML 报告文件，包含：
 *  - SQL 代码块（复用 [SqlLexer] 分词 + [SqlHighlightColors] 配色）
 *  - 参数对照表
 *  - 元信息（表名 / 方言 / 时间）
 *
 * 输出为独立 HTML 文件，浏览器打开查看。
 */
object HtmlReportFormatter {

    private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 生成完整 HTML 报告。
     *
     * @param sql        要展示的 SQL
     * @param sources    参数来源列表
     * @param paramValues 参数值映射
     * @param tableName  表名（可 null）
     * @param dialect    方言名称（可 null）
     * @return HTML 全文
     */
    fun format(
        sql: String,
        sources: List<String> = emptyList(),
        paramValues: Map<String, String> = emptyMap(),
        tableName: String? = null,
        dialect: String? = null,
    ): String {
        val timestamp = LocalDateTime.now().format(TIMESTAMP_FMT)
        val highlighted = highlightSql(sql)
        val paramsTable = buildParamsTable(sources, paramValues)

        return buildString(2048) {
            append("<!DOCTYPE html>\n")
            append("<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">\n")
            append("<title>SQL Report</title>\n")
            append("<style>\n")
            append("body{font-family:Consolas,Monaco,'Courier New',monospace;margin:20px;background:#282c34;color:#abb2bf}\n")
            append("h1{color:#61afef;font-size:18px;margin-bottom:8px}\n")
            append(".meta{color:#7f848e;font-size:12px;margin-bottom:16px}\n")
            append(".meta span{margin-right:16px}\n")
            append("pre{background:#1e1e1e;border-radius:6px;padding:16px;overflow-x:auto}\n")
            append("code{font-size:13px;line-height:1.5}\n")
            append(".params{margin-top:16px}\n")
            append(".params h2{color:#61afef;font-size:14px}\n")
            append(".params table{border-collapse:collapse;width:100%;font-size:12px}\n")
            append(".params th,.params td{border:1px solid #3e4451;padding:6px 12px;text-align:left}\n")
            append(".params th{background:#21252b;color:#e5c07b}\n")
            append(".kw{color:#c678dd;font-weight:bold}\n")
            append(".tbl{color:#d55fde}\n")
            append(".ident{color:#61afef}\n")
            append(".str{color:#98c379}\n")
            append(".num{color:#d19a66}\n")
            append(".cmt{color:#7f848e;font-style:italic}\n")
            append(".op{color:#abb2bf}\n")
            append(".paren{color:#abb2bf}\n")
            append("</style></head><body>\n")
            append("<h1>SQL Report</h1>\n")
            append("<div class=\"meta\">\n")
            // T-SAF-09：tableName/dialect 来自较低信任的项目文件内容（实体 @TableName 注解
            // 字面量等），与 SQL 标记/参数值同受转义不变式约束（原 :72/:73 漏转义）
            tableName?.let { append("<span>Table: <b>${escapeHtml(it)}</b></span>\n") }
            dialect?.let { append("<span>Dialect: <b>${escapeHtml(it)}</b></span>\n") }
            append("<span>Generated: <b>$timestamp</b></span>\n")
            append("</div>\n")
            append("<pre><code>$highlighted</code></pre>\n")
            append(paramsTable)
            append("</body></html>")
        }
    }

    /** 使用 [SqlLexer] 分词 + CSS 类生成高亮 HTML。 */
    private fun highlightSql(sql: String): String {
        val lexer = SqlLexer()
        lexer.start(sql, 0, sql.length)
        val sb = StringBuilder(sql.length + 256)
        var offset = 0
        while (lexer.tokenType != null) {
            // 处理 token 前的普通文本（转义 HTML 特殊字符）
            if (lexer.tokenStart > offset) {
                sb.append(escapeHtml(sql.substring(offset, lexer.tokenStart)))
            }
            val tokenText = sql.substring(lexer.tokenStart, lexer.tokenEnd)
            val cssClass = cssClassFor(lexer.tokenType)
            if (cssClass != null) {
                sb.append("<span class=\"$cssClass\">")
                sb.append(escapeHtml(tokenText))
                sb.append("</span>")
            } else {
                sb.append(escapeHtml(tokenText))
            }
            offset = lexer.tokenEnd
            lexer.advance()
        }
        // 尾部普通文本
        if (offset < sql.length) {
            sb.append(escapeHtml(sql.substring(offset)))
        }
        return sb.toString()
    }

    /** token 类型 → CSS 类名。 */
    private fun cssClassFor(tokenType: IElementType?): String? = when (tokenType) {
        SqlTokenTypes.KEYWORD -> "kw"
        SqlTokenTypes.TABLE -> "tbl"
        SqlTokenTypes.IDENTIFIER -> "ident"
        SqlTokenTypes.STRING -> "str"
        SqlTokenTypes.NUMBER -> "num"
        SqlTokenTypes.COMMENT -> "cmt"
        SqlTokenTypes.OPERATOR -> "op"
        SqlTokenTypes.PAREN -> "paren"
        else -> null
    }

    /**
     * HTML 特殊字符转义（T-SAF-09：收敛到共享 [cn.song.mybatisplus.visualizer.util.HtmlEscape]，
     * 本文件不再持有本地实现——转义集由共享工具统一为 `& < > " '`）。
     */
    private fun escapeHtml(text: String): String =
        cn.song.mybatisplus.visualizer.util.HtmlEscape.escape(text)

    /** 生成参数对照表 HTML。 */
    private fun buildParamsTable(sources: List<String>, paramValues: Map<String, String>): String {
        if (sources.isEmpty()) return ""
        val sb = StringBuilder(512)
        sb.append("<div class=\"params\"><h2>Parameters</h2>\n")
        sb.append("<table><tr><th>Source</th><th>Value</th></tr>\n")
        for (src in sources) {
            val v = paramValues[src] ?: "?"
            // T-SAF-09：来源名（注解列名等低信任字符串）与配对值同受转义（原 :147 漏转义）
            sb.append("<tr><td><code>${escapeHtml(src)}</code></td><td><code>${escapeHtml(v)}</code></td></tr>\n")
        }
        sb.append("</table></div>\n")
        return sb.toString()
    }
}
