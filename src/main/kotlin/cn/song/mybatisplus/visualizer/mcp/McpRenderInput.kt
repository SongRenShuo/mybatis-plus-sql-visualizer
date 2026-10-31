package cn.song.mybatisplus.visualizer.mcp

/**
 * T-McpToolset sqlv_render_sql 入参校验与形态分发（纯逻辑层，无 IDE 类型）。
 *
 * 计划 §二：入参「项目相对文件路径 + 行号」或「源码片段 + 触发面类型」。一期片段形态仅支持
 * mapper XML 文本（[cn.song.mybatisplus.visualizer.ui.XmlPreviewService] 为 text 形态入口；
 * Java 片段无 PSI 锚点不可定位——渲染链是 PSI 语义的，裁剪写进实施记录）。
 */
object McpRenderInput {

    /** 触发面类型（sourceType）允许值：一期只有 mapper XML 片段。 */
    const val SOURCE_TYPE_XML_MAPPER = "xml_mapper"

    /** 行号合法范围（1-based；0 = 未提供/取整文件首个语句）。 */
    const val MAX_LINE = 1_000_000

    /** 片段长度上限。 */
    const val MAX_SNIPPET_LENGTH = 100_000

    /** 校验通过的入参形态。 */
    data class Parsed(
        val filePath: String?,
        val line: Int?,
        val xmlSnippet: String?,
        val sourceType: String?,
        /**
         * T-TemplateHook：可选参数 JSON，原样交给用户配置的外部渲染命令（`{paramsJson}` 占位符）。
         * null = 未提供；只对路径形态有意义，片段形态忽略。**不进任何日志/错误文本**。
         */
        val paramsJson: String? = null,
    )

    /**
     * 入参校验：路径形态（filePath 非空）与片段形态（xmlSnippet+sourceType=xml_mapper）二选一；
     * 两者都给时路径形态优先。行号只允许正整数。
     *
     * T-TemplateHook：新增可选 [paramsJson]（默认 null）——不传时与接线前逐字节一致；
     * 只透传给路径形态（片段形态没有外部命令挂点）。
     */
    fun parse(
        filePath: String?,
        line: Int?,
        xmlSnippet: String?,
        sourceType: String?,
        paramsJson: String? = null,
    ): Pair<Parsed?, String?> {
        val file = filePath?.trim()?.takeIf { it.isNotEmpty() }
        if (file != null) {
            if (line != null && (line < 0 || line > MAX_LINE)) {
                return null to McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "line must be within 1..$MAX_LINE (1-based); got $line.",
                )
            }
            val json = paramsJson?.trim()?.takeIf { it.isNotEmpty() }
            return Parsed(file, line?.takeIf { it > 0 }, null, null, json) to null
        }
        val snippet = xmlSnippet?.trim()?.takeIf { it.isNotEmpty() }
        if (snippet != null) {
            if (snippet.length > MAX_SNIPPET_LENGTH) {
                return null to McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "xmlSnippet exceeds the maximum length of $MAX_SNIPPET_LENGTH characters.",
                )
            }
            val type = sourceType?.trim()?.takeIf { it.isNotEmpty() }
            if (type != null && type != SOURCE_TYPE_XML_MAPPER) {
                return null to McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "sourceType '$type' is not supported for snippet rendering; " +
                        "supported: $SOURCE_TYPE_XML_MAPPER. For Java sources pass filePath+line instead.",
                )
            }
            return Parsed(null, null, snippet, type ?: SOURCE_TYPE_XML_MAPPER) to null
        }
        return null to McpToolErrors.code(
            McpToolErrors.INVALID_ARGUMENT,
            "provide either (filePath, line) of the source location or (xmlSnippet, " +
                "sourceType=$SOURCE_TYPE_XML_MAPPER) for mapper XML text.",
        )
    }

    /** 项目相对路径归一化（统一正斜杠、拒绝绝对路径与 `..` 上跳）。 */
    fun normalizeFilePath(raw: String): String? {
        val path = raw.trim().replace('\\', '/').trimStart('/')
        if (path.isEmpty() || path.contains("..") || Regex("^[A-Za-z]:").containsMatchIn(path)) return null
        return path
    }
}
