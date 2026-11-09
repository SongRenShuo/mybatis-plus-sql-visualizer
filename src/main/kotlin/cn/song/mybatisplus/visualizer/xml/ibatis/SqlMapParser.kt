package cn.song.mybatisplus.visualizer.xml.ibatis

import cn.song.mybatisplus.visualizer.xml.SecureXml
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * `<sqlMap>` 文件解析器（T-Ibatis2Support 券 B）。
 *
 * 解析入口**强制复用** `xml/SecureXml.kt`（全仓唯一 `DocumentBuilder` 工厂，含膨胀预扫与
 * 外部 DTD 拦空）——不自建 `DocumentBuilderFactory`，与 `xml/XmlMapperParser` / `xml/PagesXmlParser` 同口径。
 *
 * **错误分级**（券 B 的健壮性口径）：
 * - **结构级**（XML 语法错、根元素不是 `<sqlMap>`）→ 抛 [IbatisParseException]（调用方按「不是 iBatis 文件」处理）；
 * - **语句级**（未知标签、占位符格式错、`parameterMap` 声明冲突、`include` 找不到片段等）
 *   → **降级 + 收集 warning**，绝不打断整篇；warning 文本一律带语句 id 便于回溯。
 *
 * 不做的事（有意）：不解析 `sqlMapConfig` / `useStatementNamespaces`（本链是**单文件**解析器，
 * 与 engine 默认 `false` 同口径——`<sql id>` 按原样 id 注册，查找时按
 * 「原样 → `namespace + "." + refid`」两级回落，见 [SqlMapScriptBuilder]）。
 */
class SqlMapParser {

    /**
     * 解析一份 `<sqlMap>` 文本。
     *
     * @throws IbatisParseException XML 语法错、根元素不是 `<sqlMap>`、文档为空
     */
    fun parse(fileText: String): IbatisSqlMap {
        val document = parseDocument(fileText)
        val root = document.documentElement ?: throw IbatisParseException("XML 文档为空（没有根元素）。")
        if (root.tagName != SQL_MAP_TAG) {
            throw IbatisParseException("不是 iBatis sqlMap 文件：根元素为 <${root.tagName}>（期望 <$SQL_MAP_TAG>）。")
        }
        val warnings = ArrayList<String>()
        val namespace = attribute(root, "namespace")

        val fragments = collectSqlFragments(root, warnings)
        val parameterMaps = collectParameterMaps(root, warnings)
        val statements = collectStatements(root, namespace, fragments, parameterMaps, warnings)

        return IbatisSqlMap(
            namespace = namespace,
            statements = statements,
            parameterMaps = parameterMaps,
            sqlFragmentIds = fragments.keys.toList(),
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------------ 片段与参数图

    /** `<sql id>` 收集：id 原样注册（engine 默认 `useStatementNamespaces=false` 口径）。 */
    private fun collectSqlFragments(root: Element, warnings: MutableList<String>): Map<String, Element> {
        val fragments = LinkedHashMap<String, Element>()
        for (element in childElements(root)) {
            if (element.tagName != "sql") continue
            val id = attribute(element, "id")
            if (id == null) {
                warnings.add("文件级：<sql> 缺少 id 属性，已忽略（引擎会抛 Duplicate/NullPointer 类错误）。")
                continue
            }
            if (fragments.containsKey(id)) {
                // 引擎：`Duplicate <sql>-include 'x' found.`
                warnings.add("文件级：<sql id=\"$id\"> 重复声明，保留首个（引擎会抛 Duplicate <sql>-include）。")
                continue
            }
            fragments[id] = element
        }
        return fragments
    }

    private fun collectParameterMaps(root: Element, warnings: MutableList<String>): List<IbatisParameterMap> {
        val maps = ArrayList<IbatisParameterMap>()
        for (element in childElements(root)) {
            if (element.tagName != "parameterMap") continue
            val id = attribute(element, "id")
            if (id == null) {
                warnings.add("文件级：<parameterMap> 缺少 id 属性（DTD 为 #REQUIRED），已忽略。")
                continue
            }
            val parameters = childElements(element)
                .filter { it.tagName == "parameter" }
                .map { parameter ->
                    IbatisParameterDef(
                        property = attribute(parameter, "property").orEmpty(),
                        javaType = attribute(parameter, "javaType"),
                        jdbcType = attribute(parameter, "jdbcType"),
                        nullValue = attribute(parameter, "nullValue"),
                        mode = attribute(parameter, "mode"),
                        typeHandler = attribute(parameter, "typeHandler"),
                    )
                }
            maps.add(
                IbatisParameterMap(
                    id = id,
                    // DTD 里 class 是 #REQUIRED（官方指南说可选，DTD 不是）——缺失按告警处理
                    className = attribute(element, "class")
                        ?: run {
                            warnings.add("文件级：<parameterMap id=\"$id\"> 缺少 class 属性（DTD 为 #REQUIRED）。")
                            null
                        },
                    parameters = parameters,
                ),
            )
        }
        return maps
    }

    // ------------------------------------------------------------------ 语句

    private fun collectStatements(
        root: Element,
        namespace: String?,
        fragments: Map<String, Element>,
        parameterMaps: List<IbatisParameterMap>,
        warnings: MutableList<String>,
    ): List<IbatisStatement> {
        val declaredParameterMaps = parameterMaps.associateBy { applyNamespace(it.id, namespace) }
        val statements = ArrayList<IbatisStatement>()
        for (element in childElements(root)) {
            val kind = IbatisStatementKind.byTagName(element.tagName) ?: continue
            statements.add(
                buildStatement(element, kind, namespace, fragments, declaredParameterMaps, warnings),
            )
        }
        return statements
    }

    private fun buildStatement(
        element: Element,
        kind: IbatisStatementKind,
        namespace: String?,
        fragments: Map<String, Element>,
        declaredParameterMaps: Map<String, IbatisParameterMap>,
        fileWarnings: MutableList<String>,
    ): IbatisStatement {
        val tagName = element.tagName
        val rawId = attribute(element, "id")
        val id = rawId ?: run {
            fileWarnings.add("文件级：<$tagName> 缺少 id 属性（DTD 为 #REQUIRED），已按 <$tagName@行?> 占位。")
            "<无 id 的 $tagName>"
        }
        val warnings = ArrayList<String>()
        val warn: (String) -> Unit = { warnings.add("语句 [$id]：$it") }

        // 语句级属性（`insert`/`update`/`delete` 的 DTD 无 resultMap/resultClass——这里只做提示，不阻断解析）
        if (kind != IbatisStatementKind.SELECT && kind != IbatisStatementKind.STATEMENT &&
            kind != IbatisStatementKind.PROCEDURE
        ) {
            if (element.hasAttribute("resultMap") || element.hasAttribute("resultClass")) {
                warn("<$tagName> 按 DTD 不接受 resultMap/resultClass 属性（引擎侧结果装配失败）")
            }
        }

        val parameterMapRef = attribute(element, "parameterMap")
        val resolvedParameterMap = parameterMapRef?.let { declaredParameterMaps[applyNamespace(it, namespace)] }
        if (parameterMapRef != null && resolvedParameterMap == null) {
            // 引擎在此处 `getParameterMap(id)` 直接抛「There is no parameter map named ...」
            warn("parameterMap=\"$parameterMapRef\" 在本文件内找不到对应 <parameterMap>（引擎会抛异常）")
        }

        val builder = SqlMapScriptBuilder(fragments, namespace, warn)
        val body = runCatching { builder.build(element) }.getOrElse { error ->
            warn("语句体解析失败（按原文保留）：${error.message}")
            emptyList()
        }
        if (parameterMapRef != null && body.none { it is IbatisTagNode }) {
            // 券 B 第 5 条：**非动态**语句声明 parameterMap 时 `#` 不转 `?`（引擎 applyInlineParameterMap 的
            // `if (parameterMap == null)` 守卫）；这类语句真正生效的是手写 `?`，是配置错误的高发形态。
            warn("声明了 parameterMap 且不含动态标签：体内 # 不转 ?（引擎行为），请确认语句使用手写 ? 占位")
        }

        val selectKeys = if (kind == IbatisStatementKind.INSERT) {
            buildSelectKeys(element, id, namespace, fragments, warn)
        } else {
            childElements(element).filter { it.tagName == "selectKey" }.forEach {
                warn("<selectKey> 只允许挂在 <insert> 下（引擎会抛 You cant set a select key ...），已忽略")
            }
            emptyList()
        }

        val attributes = element.attributes.let { attrs ->
            (0 until attrs.length).associate { index ->
                attrs.item(index).nodeName to attrs.item(index).nodeValue
            }
        }

        return IbatisStatement(
            id = id,
            kind = kind,
            kindTagName = tagName,
            attributes = attributes,
            parameterMapRef = parameterMapRef,
            hasParameterMapDeclaration = parameterMapRef != null,
            body = body,
            parameterMap = resolvedParameterMap,
            usedSqlFragmentIds = builder.usedFragmentIds.toList(),
            selectKeys = selectKeys,
            warnings = warnings,
        )
    }

    /**
     * `<selectKey>` 装配（复刻 `SqlStatementParser.findAndParseSelectKey`）：
     * 顺序由**位置**推断（selectKey 之前是否已有非空文本），`type="pre|post"` 显式覆盖。
     */
    private fun buildSelectKeys(
        insert: Element,
        insertId: String,
        namespace: String?,
        fragments: Map<String, Element>,
        warn: (String) -> Unit,
    ): List<IbatisSelectKey> {
        var foundSqlFirst = false
        val children = insert.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) ?: continue
            when (child.nodeType) {
                org.w3c.dom.Node.TEXT_NODE, org.w3c.dom.Node.CDATA_SECTION_NODE -> {
                    val data = (child as org.w3c.dom.CharacterData).data.orEmpty()
                    if (data.trim { it <= ' ' }.isNotEmpty()) foundSqlFirst = true
                }
                org.w3c.dom.Node.ELEMENT_NODE -> {
                    val element = child as Element
                    if (element.tagName != "selectKey") continue
                    val type = attribute(element, "type")
                    val runAfterSql = if (type != null) type == "post" else foundSqlFirst
                    val warnings = ArrayList<String>()
                    val innerWarn: (String) -> Unit = { warnings.add("语句 [$insertId-SelectKey]：$it") }
                    val builder = SqlMapScriptBuilder(fragments, namespace, innerWarn)
                    val body = runCatching { builder.build(element) }.getOrElse { error ->
                        innerWarn("selectKey 语句体解析失败（按原文保留）：${error.message}")
                        emptyList()
                    }
                    val attributes = element.attributes.let { attrs ->
                        (0 until attrs.length).associate { i ->
                            attrs.item(i).nodeName to attrs.item(i).nodeValue
                        }
                    }
                    return listOf(
                        IbatisSelectKey(
                            id = "$insertId-SelectKey",
                            runAfterSql = runAfterSql,
                            attributes = attributes,
                            body = body,
                            warnings = warnings,
                        ),
                    )
                }
            }
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ 工具

    /** 解析 XML 文本为 DOM（安全配置见 `xml/SecureXml.kt`：全仓唯一工厂，含膨胀预扫）。 */
    private fun parseDocument(fileText: String): Document =
        runCatching { SecureXml.parse(fileText) }
            .getOrElse { throw IbatisParseException("XML 解析失败：${it.message}") }

    /** 引擎 `XmlParserState.applyNamespace`（只用于 `parameterMap` 这类**无条件**前缀的场景）。 */
    private fun applyNamespace(id: String, namespace: String?): String =
        if (!namespace.isNullOrEmpty() && id.indexOf('.') < 0) "$namespace.$id" else id

    private fun childElements(element: Element): List<Element> {
        val result = ArrayList<Element>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let { result.add(it) }
        }
        return result
    }

    private companion object {
        const val SQL_MAP_TAG = "sqlMap"
    }
}
