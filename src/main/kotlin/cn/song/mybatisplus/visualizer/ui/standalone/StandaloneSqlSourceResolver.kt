package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService

/**
 * 独立预览工作台的双源解析器（T-SqlPreviewStandalone D-1 / D-1b）。
 *
 * 纯逻辑（无 PSI / 无 IDE 依赖，可独立单测）：把用户粘贴的一段文本解析成
 * [PreviewResult]，供独立预览 tab / 弹框共用同一份识别口径（dbx「执行器认什么检测器
 * 就认什么」纪律——填值面板与检测门同源，不出现两套认知漂移）。
 *
 * 判定门优先级：
 *  1. **XML 语句块**：文本含 `<select|insert|update|delete>` 带 `id` 属性的标签 →
 *     走插件自研的纯文本 XML 链 [XmlPreviewService.preview]（project = null，
 *     行为与 MCP `xmlSnippet` 通道逐字节同源）。主体自研、不引外部实现；dbx 只吸三点
 *     精华：J-1（引号感知标签配对——本链经 DOM 解析天然规避字面量 `<` 干扰，检测门只需
 *     正则锚定 + 标签名配对）、J-2（XML 实体解码，仅当 DOM 原文解析失败才解码重试——
 *     保证合法 XML 里 `a &lt; b` 的实体语义不被破坏）、J-3（空值=不解析逃生门由填值面板
 *     既有语义承接，见计划 D-6）。
 *  2. **纯 SQL**：string-aware `?` 定位复用 [placeholderPositions]（T-PSG 三链共用口径），
 *     `#{name}` 手写扫描（同样跳过字符串字面量与注释），`${}` 原样保留（raw 语义，
 *     置 [PreviewResult.hasDollarRawSegments] 由执行确认闸门承接）。
 *
 * 解析失败永不抛异常：XML 门限命中但 DOM 解析失败 → 返回带 warning 的 XML 结果（面板红字
 * 如实展示原因）；其余形态返回 null（调用方给引导文案）。
 */
object StandaloneSqlSourceResolver {

    /**
     * 输入长度上限（对齐 MCP [cn.song.mybatisplus.visualizer.mcp.McpRenderInput.MAX_SNIPPET_LENGTH]
     * 的既有口径）。超长返回 null，调用方给「过长」提示而不是静默失败。
     */
    const val MAX_TEXT_LENGTH = 100_000

    /** 识别结果类别。 */
    enum class Kind { XML_STATEMENT, PLAIN_SQL }

    /**
     * 解析产物。
     *
     * @param result         直接喂面板/弹框的载荷（XML 路径含 xmlModel，面板自动获得条件勾选/foreach 重放）
     * @param kind           识别类别（状态条展示「识别为 mapper XML 语句块」/「按纯 SQL 解析」）
     * @param statementId    XML 路径取到的语句 id（多语句片段默认取第一个，与 XmlPreviewService
     *                       statementId=null 的既有口径一致）
     * @param statementCount XML 路径检测到的语句块总数（状态条提示「共 N 个，取第一个」）
     * @param paramCount     纯 SQL 路径的占位符总数（`?` + `#{}`；`${}` 不计）
     */
    data class Resolved(
        val result: PreviewResult,
        val kind: Kind,
        val statementId: String? = null,
        val statementCount: Int = 0,
        val paramCount: Int = 0,
    )

    /**
     * 主入口：按判定门优先级解析。null = 无法识别（空文本 / 超长 / 非 SQL 的 XML 等）。
     */
    fun resolve(text: String): Resolved? {
        val input = stripCodeFence(text.trim())
        if (input.isEmpty() || input.length > MAX_TEXT_LENGTH) return null
        val decoded = decodeXmlEntitiesIfEscaped(input)
        if (STATEMENT_TAG_REGEX.containsMatchIn(decoded)) {
            return resolveAsXml(input, decoded)
        }
        // 非 SQL 的 XML（pom 片段等）：以 `<` 开头但检测门未命中 → 不硬塞纯 SQL 解析（A-7 降级）
        if (input.startsWith("<") || decoded.startsWith("<")) return null
        return resolveAsPlainSql(input)
    }

    /**
     * 剪贴板预填候选判定（D-4）：比 [resolve] 宽松——只要「疑似 SQL 或 mapper XML」即预填，
     * 识别结果交给用户按「解析预览」后由状态条如实呈现。null 文本（空/超长）返回 false。
     */
    fun isClipboardCandidate(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (text.length > MAX_TEXT_LENGTH) return false
        val input = stripCodeFence(text.trim())
        if (input.isEmpty()) return false
        if (STATEMENT_TAG_REGEX.containsMatchIn(decodeXmlEntitiesIfEscaped(input))) return true
        if (input.startsWith("<")) return false // XML 但非 mapper 语句块 → 不预填
        return SQL_KEYWORD_START_REGEX.containsMatchIn(input) || placeholderPositions(input).isNotEmpty()
    }

    // ---------- XML 路径（自研链，D-1b） ----------

    private fun resolveAsXml(original: String, decoded: String): Resolved? {
        // 依次尝试，第一个成功（xmlModel 非 null）即返回；全部失败返回最后一个结果（带 warning）
        val attempts = buildList {
            add(original)
            if (decoded != original) add(decoded)
            add(wrapAsMapper(decoded, preferFullText = true))
            add(wrapAsMapper(decoded, preferFullText = false))
        }.distinct()
        var last: PreviewResult? = null
        for (candidate in attempts) {
            val result = XmlPreviewService.preview(candidate, statementId = null, project = null)
            if (result.xmlModel != null) {
                return Resolved(
                    result = result,
                    kind = Kind.XML_STATEMENT,
                    statementId = firstStatementId(candidate),
                    statementCount = countStatementBlocks(decoded),
                )
            }
            last = result
        }
        val fallback = last ?: return null
        return Resolved(
            result = fallback,
            kind = Kind.XML_STATEMENT,
            statementId = firstStatementId(decoded),
            statementCount = countStatementBlocks(decoded),
        )
    }

    /**
     * 包根：[XmlMapperParser] 要求根元素 `<mapper>`（解析失败告「不是 MyBatis mapper 文件」）。
     * preferFullText=true：整段包根（保留 `<sql>` include 片段的同文解析）；false：只包检测门
     * 提取出的语句块（正文混入说明文字时兜底）。
     */
    private fun wrapAsMapper(text: String, preferFullText: Boolean): String {
        if (text.startsWith("<mapper", ignoreCase = true)) return text
        if (!preferFullText) {
            val blocks = extractStatementBlocks(text).joinToString("\n") { it.blockText }
            if (blocks.isEmpty()) return ""
            return "<mapper>$blocks</mapper>"
        }
        return "<mapper>$text</mapper>"
    }

    /** 语句块提取：标签名配对（同种标签不嵌套——mapper 语句的 XML 结构保证），不做正则整段匹配。 */
    private fun extractStatementBlocks(text: String): List<StatementBlock> {
        val blocks = mutableListOf<StatementBlock>()
        STATEMENT_TAG_REGEX.findAll(text).forEach { match ->
            val tag = match.groupValues[1].lowercase()
            val start = match.range.first
            if (blocks.any { start >= it.openStart && start < it.closeEnd }) return@forEach
            val closeTag = "</$tag>"
            val closeIdx = text.indexOf(closeTag, match.range.last + 1)
            if (closeIdx < 0) return@forEach
            val end = closeIdx + closeTag.length
            val id = ID_ATTR_REGEX.find(text.substring(start, end))?.groupValues?.get(1)
            blocks.add(StatementBlock(text.substring(start, end), start, end, id))
        }
        return blocks
    }

    private fun countStatementBlocks(text: String): Int = extractStatementBlocks(text).size

    private fun firstStatementId(mapperText: String): String? =
        extractStatementBlocks(mapperText).firstOrNull()?.id

    private data class StatementBlock(
        val blockText: String,
        val openStart: Int,
        val closeEnd: Int,
        val id: String?,
    )

    /**
     * J-2：XML 实体解码——仅当文本出现 `&lt;`/`&gt;`（网页/文档复制的整段转义形态）才做，
     * 且只解码五个标准实体。合法 XML 里的 `a &lt; b` 由 DOM 解析正确还原（先试原文、解码仅作
     * 重试候选，见 [resolveAsXml] 的 attempts 序），不重演 dbx #11699 的实体分号切分问题。
     */
    private fun decodeXmlEntitiesIfEscaped(text: String): String {
        if (!text.contains("&lt;") && !text.contains("&gt;")) return text
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    // ---------- 纯 SQL 路径（D-1） ----------

    private fun resolveAsPlainSql(input: String): Resolved {
        val qPositions = placeholderPositions(input)
        val braced = scanBracedParams(input)
        // 事件合并（按位点排序）：`?` 原位保留、`#{name}` → `?`；同名参数共用一个填值行
        //（MyBatis 语义，与 XML 链 mappings 的同名复用一致）
        data class Event(val pos: Int, val endExclusive: Int, val name: String, val isBraced: Boolean)
        val events = buildList {
            qPositions.forEach { add(Event(it, it + 1, "", false)) }
            braced.forEach { add(Event(it.start, it.endExclusive, it.name, true)) }
        }.sortedBy { it.pos }
        val placeholderSql = StringBuilder(input.length)
        val sources = mutableListOf<String>()
        var qIndex = 0
        var prev = 0
        var dollarRaw = false
        for (event in events) {
            if (event.pos < prev) continue // `#{}` 扫描与 `?` 位点在字符串/注释上口径不同，重叠事件跳过
            placeholderSql.append(input, prev, event.pos)
            placeholderSql.append('?')
            prev = event.endExclusive
            val name = if (event.isBraced) {
                event.name.ifBlank { "param${qIndex + 1}" }
            } else {
                qIndex++
                "param$qIndex"
            }
            sources.add(name)
        }
        placeholderSql.append(input, prev, input.length)
        // `${}` raw 段识别（string-aware，与 `?` 同口径——字符串字面量里的 `${}` 是字面量不是占位）
        dollarRaw = hasDollarRawSegment(input)
        val sql = placeholderSql.toString()
        return Resolved(
            result = PreviewResult(
                sql = sql,
                compactSql = sql,
                unresolvedParams = sources,
                paramTable = ParameterReplacer.buildParamTable(sources),
                wrapperType = null,
                tableName = null,
                hasDollarRawSegments = dollarRaw,
            ),
            kind = Kind.PLAIN_SQL,
            paramCount = events.size,
        )
    }

    /**
     * `#{name}` 扫描（string-aware）：跳过单引号字符串（`''` 转义）、`--` 行注释、块注释——
     * 字面量里的 `#{x}` 是普通文本不当参数（dbx 同语法的 string-fragment 策略属进阶形态，
     * v1 不做，识别失败即如实不识别）。`${}` 不在扫描范围（raw 语义不参与填值）。
     */
    private fun scanBracedParams(text: String): List<BracedToken> {
        val tokens = mutableListOf<BracedToken>()
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length && text[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    inString = false
                }
            } else {
                when {
                    c == '\'' -> inString = true
                    c == '-' && i + 1 < text.length && text[i + 1] == '-' -> {
                        while (i < text.length && text[i] != '\n') i++
                    }
                    c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                        i += 2
                        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i++
                    }
                    c == '#' && i + 1 < text.length && text[i + 1] == '{' -> {
                        val close = text.indexOf('}', i + 2)
                        if (close >= 0) {
                            val name = text.substring(i + 2, close).trim()
                            tokens.add(BracedToken(i, close + 1, name))
                            i = close + 1
                            continue
                        }
                    }
                }
            }
            i++
        }
        return tokens
    }

    /** `${}` raw 段检测（string-aware，口径与 [scanBracedParams] 一致）。 */
    private fun hasDollarRawSegment(text: String): Boolean {
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < text.length && text[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    inString = false
                }
            } else {
                when {
                    c == '\'' -> inString = true
                    c == '-' && i + 1 < text.length && text[i + 1] == '-' -> {
                        while (i < text.length && text[i] != '\n') i++
                    }
                    c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                        i += 2
                        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i++
                    }
                    c == '$' && i + 1 < text.length && text[i + 1] == '{' -> return true
                }
            }
            i++
        }
        return false
    }

    private data class BracedToken(val start: Int, val endExclusive: Int, val name: String)

    // ---------- 通用预处理 ----------

    /**
     * markdown 代码围栏剥离（P-8 语料）：```sql / ```xml / 裸 ``` 围栏取首个围栏体；
     * 无围栏原样返回。
     */
    private fun stripCodeFence(text: String): String {
        if (!text.contains("```")) return text
        val matcher = CODE_FENCE_REGEX.find(text) ?: return text
        return matcher.groupValues[1].trim()
    }

    /**
     * 语句标签检测门：`<select|insert|update|delete>` 带 `id` 属性（mapper 语句的结构性特征，
     * 普通 XML 如 pom 的 `<dependency>` 不命中）。对**解码后**文本判定——网页复制的
     * `&lt;select id=&quot;x&quot;&gt;` 形态先解码才看得见。
     */
    private val STATEMENT_TAG_REGEX =
        Regex("<(select|insert|update|delete)\\b[^>]*\\bid\\s*=", RegexOption.IGNORE_CASE)

    private val ID_ATTR_REGEX = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    private val CODE_FENCE_REGEX = Regex("```[A-Za-z]*\\s*\\n([\\s\\S]*?)\\n?```")

    private val SQL_KEYWORD_START_REGEX =
        Regex("^\\s*(select|insert|update|delete|with|explain|show|desc)\\b", RegexOption.IGNORE_CASE)
}
