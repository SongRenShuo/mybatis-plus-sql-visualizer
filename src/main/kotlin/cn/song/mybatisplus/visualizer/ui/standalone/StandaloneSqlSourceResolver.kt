package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.ui.IbatisXmlPreviewService
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService

/**
 * 独立预览工作台的双源解析器（T-SqlPreviewStandalone D-1 / D-1b）。
 *
 * 纯逻辑（无 PSI / 无 IDE 依赖，可独立单测）：把用户粘贴的一段文本解析成
 * [PreviewResult]，供独立预览 tab / 弹框共用同一份识别口径（dbx「执行器认什么检测器
 * 就认什么」纪律——填值面板与检测门同源，不出现两套认知漂移）。
 *
 * 判定门优先级：
 *  1. **XML 语句块**：文本含 `<select|insert|update|delete|statement|procedure>` 带 `id` 属性的
 *     标签 → 先由 [XmlFlavorDetector] 判风味，再走对应纯文本解析链（MyBatis 3 =
 *     [XmlPreviewService.preview]；iBatis 2 = [IbatisXmlPreviewService.preview]，两者 `project`
 *     均可空、零 PSI 读），首选链不可用时跑另一条链**择优**——`<select>` 标签名两代同名，
 *     证据不足的片段只能靠回退兜底（T-StandaloneIbatisRecognize）。
 *     主体自研、不引外部实现；dbx 只吸三点精华：J-1（引号感知标签配对——本链经 DOM 解析天然
 *     规避字面量 `<` 干扰，检测门只需正则锚定 + 标签名配对）、J-2（XML 实体解码，仅当 DOM 原文
 *     解析失败才解码重试——保证合法 XML 里 `a &lt; b` 的实体语义不被破坏）、J-3（空值=不解析
 *     逃生门由填值面板既有语义承接，见计划 D-6）。
 *  2. **纯 SQL**：string-aware `?` 定位复用 [placeholderPositions]（T-PSG 三链共用口径），
 *     `#{name}` 手写扫描（同样跳过字符串字面量与注释），`${}` 原样保留（raw 语义，
 *     置 [PreviewResult.hasDollarRawSegments] 由执行确认闸门承接）。
 *
 * 解析失败永不抛异常：XML 门限命中但两条链都还原不了 → 返回带 [Resolved.failure] 的结果
 * （面板据此组装「标题｜来源｜语句｜原因｜建议」的可复制文案）；非语句块的 XML 等形态返回 null
 * （调用方给引导文案）。
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
     * @param result         直接喂面板/弹框的载荷（XML 路径含 xmlModel/ibatisParamModel，面板自动获得
     *                       条件勾选/foreach 重放/iBatis 条件行）
     * @param kind           识别类别（状态条展示「识别为 mapper XML 语句块」/「按纯 SQL 解析」）
     * @param statementId    XML 路径取到的语句 id（多语句片段默认取第一个，与 XmlPreviewService
     *                       statementId=null 的既有口径一致）
     * @param statementCount XML 路径检测到的语句块总数（状态条提示「共 N 个，取第一个」）
     * @param paramCount     纯 SQL 路径的占位符总数（`?` + `#{}`；`${}` 不计）
     * @param flavor         XML 路径实际生效的解析链（MyBatis 3 / iBatis 2）；纯 SQL 为 null
     * @param failure        XML 路径两条链都未能还原时的失败事实（供面板组装可复制的结构化文案）；
     *                      成功为 null
     */
    data class Resolved(
        val result: PreviewResult,
        val kind: Kind,
        val statementId: String? = null,
        val statementCount: Int = 0,
        val paramCount: Int = 0,
        val flavor: XmlFlavorDetector.Flavor? = null,
        val failure: Failure? = null,
    )

    /**
     * XML 路径的失败事实（T-StandaloneIbatisRecognize D-4）。
     *
     * 只承载**事实**，文案由面板按 bundle 组装——解析器保持零平台/零 i18n 依赖，
     * 才能在快桶（纯逻辑）里被单测直接驱动。
     *
     * @param flavor           首选链（也就是本次给出的错误来源链）
     * @param statementId      片段里识别到的语句 id（可能为 null）
     * @param statementTagName 语句标签名（`select`/`statement`/…，可能为 null）
     * @param reason           首选链给出的原始原因（通常是引擎级英文报错）
     * @param otherChainFailed 另一条链是否也失败（true 时文案提示「两条链都没能还原」）
     */
    data class Failure(
        val flavor: XmlFlavorDetector.Flavor,
        val statementId: String? = null,
        val statementTagName: String? = null,
        val reason: String? = null,
        val otherChainFailed: Boolean = false,
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
        // T-StandaloneIbatisRecognize：片段级风味判定 → 首选链；首选链不可用则跑另一条链择优。
        // 为什么必须双链：`<select>` 标签名两代同名，判定只能靠专有标签/属性/占位语法等**证据**，
        // 证据不足的片段（只有 `<select id>` + 纯文本）判成哪一边都可能错——回退是兜底，不是冗余。
        val flavor = XmlFlavorDetector.detect(decoded)
        val primary = runChain(flavor, original, decoded)
        if (primary.usable) return toResolved(primary, decoded, flavor)
        val otherFlavor = if (flavor == XmlFlavorDetector.Flavor.IBATIS) {
            XmlFlavorDetector.Flavor.MYBATIS
        } else {
            XmlFlavorDetector.Flavor.IBATIS
        }
        val secondary = runChain(otherFlavor, original, decoded)
        if (secondary.usable) return toResolved(secondary, decoded, otherFlavor)
        // 两条链都没还原：返回**首选链**结果（原因更贴近判定），并带失败事实供面板组装文案
        return toResolved(primary, decoded, flavor, otherChainFailed = secondary.result != null)
    }

    /** 一条解析链的产物。 */
    private data class ChainRun(val result: PreviewResult?, val usable: Boolean)

    /** 按风味跑对应解析链（纯文本、project = null，零 PSI 读）。 */
    private fun runChain(
        flavor: XmlFlavorDetector.Flavor,
        original: String,
        decoded: String,
    ): ChainRun = when (flavor) {
        XmlFlavorDetector.Flavor.MYBATIS -> runMybatis(original, decoded)
        XmlFlavorDetector.Flavor.IBATIS -> runIbatis(original, decoded)
    }

    private fun runMybatis(original: String, decoded: String): ChainRun {
        val candidates = buildList {
            add(original)
            if (decoded != original) add(decoded)
            add(wrapAsMapper(decoded, preferFullText = true))
            add(wrapAsMapper(decoded, preferFullText = false))
        }.filter { it.isNotEmpty() }.distinct()
        var last: PreviewResult? = null
        for (candidate in candidates) {
            val result = XmlPreviewService.preview(candidate, statementId = null, project = null)
            if (result.xmlModel != null) return ChainRun(result, usable = true)
            last = result
        }
        return ChainRun(last, usable = false)
    }

    /**
     * iBatis 2 链：[IbatisXmlPreviewService.preview] 是纯文本入口（project 可空），
     * 要求根元素 `<sqlMap>`——粘贴片段与 MyBatis 侧同样需要包根。
     */
    private fun runIbatis(original: String, decoded: String): ChainRun {
        val candidates = buildList {
            listOf(original, decoded).distinct().forEach { text ->
                if (text.startsWith("<sqlMap", ignoreCase = true)) add(text)
            }
            add(wrapAsSqlMap(decoded, preferFullText = true))
            add(wrapAsSqlMap(decoded, preferFullText = false))
        }.filter { it.isNotEmpty() }.distinct()
        var last: PreviewResult? = null
        for (candidate in candidates) {
            val result = runCatching {
                IbatisXmlPreviewService.preview(
                    fileText = candidate,
                    statementId = null,
                    statementTagName = null,
                    statementOrdinal = 0,
                    project = null,
                )
            }.getOrNull() ?: continue
            if (result.ibatisParamModel != null) return ChainRun(result, usable = true)
            last = result
        }
        return ChainRun(last, usable = false)
    }

    private fun toResolved(
        run: ChainRun,
        decoded: String,
        flavor: XmlFlavorDetector.Flavor,
        otherChainFailed: Boolean = false,
    ): Resolved {
        // 语句定位：优先用成对标签块（含 id/标签名）；片段被截断（无闭合标签）时块提取为空，
        // 退到「扫首个语句标签」——失败文案仍能报出 id 与标签名，用户才知道说的是哪一段。
        val block = firstStatementBlock(decoded)
        val tagMatch = STATEMENT_TAG_REGEX.find(decoded)
        val statementId = block?.id ?: tagMatch?.let { match ->
            ID_ATTR_REGEX.find(decoded.substring(match.range.first))?.groupValues?.get(1)
        }
        val tagName = block?.tagName ?: tagMatch?.groupValues?.get(1)?.lowercase()
        val failure = if (run.usable) {
            null
        } else {
            Failure(
                flavor = flavor,
                statementId = statementId,
                statementTagName = tagName,
                reason = run.result?.warning?.takeIf { it.isNotBlank() },
                otherChainFailed = otherChainFailed,
            )
        }
        return Resolved(
            result = run.result ?: emptyPreviewResult(),
            kind = Kind.XML_STATEMENT,
            statementId = statementId,
            statementCount = countStatementBlocks(decoded),
            flavor = flavor,
            failure = failure,
        )
    }

    /** 空载荷（两条链都拿不到结果时的兜底，面板仍可渲染空态）。 */
    fun emptyPreviewResult(): PreviewResult = PreviewResult(
        sql = "",
        compactSql = "",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = null,
    )

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

    /**
     * iBatis 侧包根：[SqlMapParser] 要求根元素 `<sqlMap>`（解析失败告「不是 iBatis sqlMap 文件」）。
     * 形态与 [wrapAsMapper] 对称：整段包根保留同文 `<sql id>` 片段，或只包检测门提取的语句块。
     */
    private fun wrapAsSqlMap(text: String, preferFullText: Boolean): String {
        if (text.startsWith("<sqlMap", ignoreCase = true)) return text
        if (!preferFullText) {
            val blocks = extractStatementBlocks(text).joinToString("\n") { it.blockText }
            if (blocks.isEmpty()) return ""
            return "<sqlMap>$blocks</sqlMap>"
        }
        return "<sqlMap>$text</sqlMap>"
    }

    /** 语句块提取：标签名配对（同种标签不嵌套——语句映射的 XML 结构保证），不做正则整段匹配。 */
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
            blocks.add(StatementBlock(text.substring(start, end), start, end, id, tag))
        }
        return blocks
    }

    private fun countStatementBlocks(text: String): Int = extractStatementBlocks(text).size

    private fun firstStatementBlock(text: String): StatementBlock? = extractStatementBlocks(text).firstOrNull()

    private data class StatementBlock(
        val blockText: String,
        val openStart: Int,
        val closeEnd: Int,
        val id: String?,
        /** 语句标签名（小写）：`select`/`insert`/`update`/`delete`/`statement`/`procedure`。 */
        val tagName: String,
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
     * 语句标签检测门：`<select|insert|update|delete|statement|procedure>` 带 `id` 属性
     * （语句映射的结构性特征，普通 XML 如 pom 的 `<dependency>` 不命中）。对**解码后**文本判定
     * ——网页复制的 `&lt;select id=&quot;x&quot;&gt;` 形态先解码才看得见。
     *
     * `statement` / `procedure` 是 iBatis 2 专有语句标签（MyBatis 3 无此二名），纳入不误伤。
     */
    private val STATEMENT_TAG_REGEX =
        Regex(
            "<(select|insert|update|delete|statement|procedure)\\b[^>]*\\bid\\s*=",
            RegexOption.IGNORE_CASE,
        )

    private val ID_ATTR_REGEX = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    private val CODE_FENCE_REGEX = Regex("```[A-Za-z]*\\s*\\n([\\s\\S]*?)\\n?```")

    private val SQL_KEYWORD_START_REGEX =
        Regex("^\\s*(select|insert|update|delete|with|explain|show|desc)\\b", RegexOption.IGNORE_CASE)
}
