package cn.song.mybatisplus.visualizer.xml.ibatis

/**
 * iBatis 2.x 静态渲染链的数据模型（T-Ibatis2Support 券 B）。
 *
 * 本包**与 MyBatis 3 链完全解耦**：不 import `xml/` 下既有解析/渲染件（`Ognl` / `XmlScriptBuilder` /
 * `TokenParser` 均不适用——iBatis 2 无表达式语言、标签集不同、定界符是单字符 `#`/`$`），
 * 只复用 `xml/SecureXml.kt`（全仓唯一 `DocumentBuilder` 工厂）与 `xml/ParamKind.kt`（只读引用）。
 *
 * 所有语义均来自 `org.apache.ibatis:ibatis-sqlmap:2.3.4.726` 的 jar 内源码逐行实测
 * （`-sources.jar` 202 个 `.java`），关键落点在各实现类 KDoc 里逐条标注。
 *
 * **与本仓既有 XML 链相反的两条口径**（券 B 硬要求）：
 * 1. 未知标签 / 解析失败 → **降级为「原文保留 + 收集 warning」**，不抛穿（对照 `xml/XmlScriptBuilder.kt:42`）；
 * 2. `open`/`prepend`/`conjunction`/`close` **逐字拼接、绝不补空格**（`ANDORG_NAME` 必须原样复现）。
 */

/**
 * `<sqlMap>` 直接子元素里的 6 种可执行语句标签（`sql-map-2.dtd`）。
 *
 * `insert`/`update`/`delete` 的属性集**没有** `resultMap`/`resultClass`（DTD 实测）。
 * `procedure` 额外少 `resultSetType`。三者差异对静态渲染无影响（只影响 resultMap 装配），
 * 故此处只保留种类用于 UI 展示与 DTD 校验提示。
 */
enum class IbatisStatementKind(val tagName: String) {
    SELECT("select"),
    INSERT("insert"),
    UPDATE("update"),
    DELETE("delete"),
    STATEMENT("statement"),
    PROCEDURE("procedure");

    companion object {
        fun byTagName(name: String): IbatisStatementKind? = entries.firstOrNull { it.tagName == name }
    }
}

/**
 * 动态标签处理器种类——逐项对应 iBatis `SqlTagHandlerFactory` 的 16 个注册项
 * （14 个条件标签 + `iterate` + `dynamic`）。
 *
 * 判定语义见 [SqlMapConditionEvaluator]；`dynamic` 只做 prepend 消费、`iterate` 是唯一有状态的处理器。
 */
enum class IbatisTagKind(val tagName: String) {
    DYNAMIC("dynamic"),
    ITERATE("iterate"),
    IS_EMPTY("isEmpty"),
    IS_EQUAL("isEqual"),
    IS_GREATER_EQUAL("isGreaterEqual"),
    IS_GREATER_THAN("isGreaterThan"),
    IS_LESS_EQUAL("isLessEqual"),
    IS_LESS_THAN("isLessThan"),
    IS_NOT_EMPTY("isNotEmpty"),
    IS_NOT_EQUAL("isNotEqual"),
    IS_NOT_NULL("isNotNull"),
    IS_NOT_PARAMETER_PRESENT("isNotParameterPresent"),
    IS_NOT_PROPERTY_AVAILABLE("isNotPropertyAvailable"),
    IS_NULL("isNull"),
    IS_PARAMETER_PRESENT("isParameterPresent"),
    IS_PROPERTY_AVAILABLE("isPropertyAvailable");

    companion object {
        fun byTagName(name: String): IbatisTagKind? = entries.firstOrNull { it.tagName == name }
    }
}

/**
 * `#...#` 内联参数解析结果（对应引擎 `ParameterMapping` 的 SQL 文本相关字段）。
 *
 * 只有 [propertyName] / [jdbcTypeName] / [nullValue] 影响面板可见面；`javaType`/`mode`/`numericScale`
 * 只影响 JDBC 绑定（`setParameters`），对 SQL 文本零影响——但保留下来供接线车道做类型提示。
 */
data class IbatisParamMapping(
    val propertyName: String,
    val jdbcTypeName: String? = null,
    val nullValue: String? = null,
    val javaTypeName: String? = null,
    val mode: String? = null,
    val numericScale: Int? = null,
)

/** 一条 `#...#` 的解析产物：SQL 文本中的 `?` 与紧随其后的原始文本段（`$` 替换后仍是字面量）。 */
data class IbatisParsedText(
    val text: String,
    val mappings: List<IbatisParamMapping>,
)

/** XML 脚本节点（iBatis 的动态树）。 */
sealed class IbatisSqlNode

/**
 * 文本节点（`#PCDATA` / CDATA）。
 *
 * **空白归一化是引擎行为，不是我们的美化**：`SqlText.setText` 把 `\r`/`\n`/`\t` 一律替换为空格
 * （`\t` 只在 `parameterMap` 分支的静态语句里被保留——见 [SqlMapSqlRenderer] 的静态路径）。
 * [isWhitespace] 与引擎同口径＝按 Java `String.trim()`（≤ `' '`）判空。
 *
 * [postParseRequired] 为 true 表示本节点的 `#`/`$` **不在配置期解析**，留给 iterate 每次迭代重算
 * （引擎 `XMLSqlSource.parseDynamicTags` 的 `postParseRequired` 形参 + `IterateTagHandler.isPostParseRequired`）。
 *
 * @param rawText     DOM 取到的原始文本（保留换行/制表符，静态路径的 `parameterMap` 分支要用）
 * @param parsedText  非 postParse 节点在**配置期**完成 `#` 解析后的文本（`?` 形态）；postParse 节点为 null
 * @param mappings    配置期解析出的内联参数（postParse 节点恒为空，运行时逐次重算）
 */
class IbatisTextNode(
    val rawText: String,
    val postParseRequired: Boolean,
    val parsedText: String? = null,
    val mappings: List<IbatisParamMapping> = emptyList(),
) : IbatisSqlNode() {

    /** 引擎 `SqlText.setText` 归一化后的文本（`\r` `\n` `\t` → 空格）。 */
    val text: String = rawText.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')

    /** 实际写入 SQL 的文本：非 postParse 用配置期解析产物，postParse 用归一化原文（运行时再解析）。 */
    val renderText: String = parsedText ?: text

    /** 引擎 `SqlText.isWhiteSpace`（按写入文本的 Java `trim()` 判空）。 */
    val isWhitespace: Boolean = renderText.trim { it <= ' ' }.isEmpty()
}

/**
 * 动态标签节点。
 *
 * 属性一律保留**原始字符串**（`null` 表示属性不存在）：引擎用 `Properties.getProperty` 取值，
 * `prepend=""` 与「无 prepend 属性」语义不同（`isPrependAvailable()` = 非 null 且长度 > 0），
 * 故这里不做空串归一。
 */
class IbatisTagNode(
    val kind: IbatisTagKind,
    val prepend: String?,
    val property: String?,
    val removeFirstPrepend: String?,
    val open: String?,
    val close: String?,
    val conjunction: String?,
    val compareProperty: String?,
    val compareValue: String?,
    val postParseRequired: Boolean,
    val children: List<IbatisSqlNode>,
) : IbatisSqlNode() {

    /**
     * 父标签（引擎 `SqlTag.parent`）；根层节点为 null——`doPrepend` 的 marker 消费以此为前提。
     *
     * 两段式构建（先建子节点、再回填父指针）用 [attachParent]，故不是构造参数。
     */
    var parent: IbatisTagNode? = null
        private set

    /** 脚本构建器的两段式挂载入口（同一包内可见）。 */
    internal fun attachParent(node: IbatisTagNode) {
        parent = node
    }

    val hasPrepend: Boolean get() = !prepend.isNullOrEmpty()
    val hasOpen: Boolean get() = !open.isNullOrEmpty()
    val hasClose: Boolean get() = !close.isNullOrEmpty()
    val hasConjunction: Boolean get() = !conjunction.isNullOrEmpty()
}

/** `<parameterMap>` / `<parameter>` 声明。 */
data class IbatisParameterDef(
    val property: String,
    val javaType: String?,
    val jdbcType: String?,
    val nullValue: String?,
    val mode: String?,
    val typeHandler: String?,
)

/** `<parameterMap id class>`（DTD 里 `class` 是 `#REQUIRED`）。 */
data class IbatisParameterMap(
    val id: String,
    val className: String?,
    val parameters: List<IbatisParameterDef>,
)

/** `<selectKey>` 生成的独立语句（引擎 statement id = `<insertId>-SelectKey`）。 */
class IbatisSelectKey(
    val id: String,
    /** 引擎 `SelectKeyStatement.runAfterSQL`：`type="post"` 优先，否则由位置推断（selectKey 前是否已有非空 SQL 文本）。 */
    val runAfterSql: Boolean,
    val attributes: Map<String, String>,
    val body: List<IbatisSqlNode>,
    val warnings: List<String> = emptyList(),
)

/** 一条可执行语句（`select`/`insert`/`update`/`delete`/`statement`/`procedure`）。 */
class IbatisStatement(
    val id: String,
    val kind: IbatisStatementKind,
    val kindTagName: String,
    val attributes: Map<String, String>,
    /** `parameterMap` 属性原值（未加 namespace 前缀）；缺省 null。 */
    val parameterMapRef: String?,
    /**
     * 引擎 `MappedStatementConfig.applyInlineParameterMap` 的 `if (parameterMap == null)` 守卫判据。
     *
     * **只在非动态语句上生效**：动态语句（有任一动态标签）走 `DynamicSql`，`#` 一律转 `?`
     * （`setSqlForStatement` 的 `sql instanceof DynamicSql` 分支）——这是本链必须复刻的形态。
     */
    val hasParameterMapDeclaration: Boolean,
    val body: List<IbatisSqlNode>,
    /** 命中的 `<parameterMap>` 声明（按 `applyNamespace` 解析）；缺省 null。 */
    val parameterMap: IbatisParameterMap? = null,
    /** 语句体内展开过的 `<sql id>`（解析期收集，渲染产物直接透出）。 */
    val usedSqlFragmentIds: List<String> = emptyList(),
    val selectKeys: List<IbatisSelectKey> = emptyList(),
    val warnings: List<String> = emptyList(),
) {

    /** 引擎 `XMLSqlSource` 的 `isDynamic`：出现任一已注册标签即为动态语句。 */
    val isDynamic: Boolean = body.any { it is IbatisTagNode }
}

/** 一个 `<sqlMap>` 文件的解析产物（语句 + 参数图 + 片段清单 + 降级告警）。 */
class IbatisSqlMap(
    /** `namespace` 属性原值；`#IMPLIED`，可为 null（iBatis 2 里 namespace 不是必填）。 */
    val namespace: String?,
    val statements: List<IbatisStatement>,
    val parameterMaps: List<IbatisParameterMap>,
    /** `<sql id>` 清单（原样 id，与 engine 默认 `useStatementNamespaces=false` 的注册键一致）。 */
    val sqlFragmentIds: List<String>,
    val warnings: List<String>,
) {

    fun statementById(id: String): IbatisStatement? = statements.firstOrNull { it.id == id }

    /** 含 `selectKey` 生成的语句在内的全部 id（供 UI 列出）。 */
    val allStatementIds: List<String>
        get() = statements.flatMap { listOf(it.id) + it.selectKeys.map { sk -> sk.id } }
}

/** iBatis 文件结构级失败（XML 语法错 / 根元素不是 `<sqlMap>`）——语句级问题一律降级为 warning。 */
class IbatisParseException(message: String) : RuntimeException(message)

/** 渲染期硬错误（引擎同样抛出的形态，如 iterate 的属性不是集合/数组/迭代器）。 */
class IbatisRenderException(message: String) : RuntimeException(message)

/**
 * 渲染产物（纯数据，不依赖 `model/PreviewResult.kt`——类型转换由接线车道负责）。
 *
 * @param sql            `?` 形态的 SQL（`#` 已转 `?`；`$` 已按参数值直替）
 * @param compactSql     空白折叠后的单行形态（面板「紧凑视图」用；**不改变 [sql] 的保真性**）
 * @param paramNames     按 `?` 出现顺序的参数名（iterate 内为 `ids[0]`/`ids[1]` 形态）
 * @param paramJdbcTypes 与 [paramNames] 同序的 `jdbcType`（`#p:TYPE#` 的第二段）
 * @param paramNullValues 与 [paramNames] 同序的 `nullValue` 哨兵（`#p:TYPE:NULL值#` 的第三段）
 * @param nullBindFlags 与 [paramNames] 同序：该参数值是否等于 `nullValue` 哨兵（true ⇒ 引擎按 SQL NULL 绑定）
 * @param warnings       降级/配置错误告警（含语句 id 可回溯）
 * @param dollarRawCount `$x$` 直替**站点数**（含值为 null 被静默丢弃的站点）
 * @param dollarRenderedValues 实际写入 SQL 的 `$` 取值（**注入闸门接既有 `${}` 确认通道**用）
 * @param usedStatementIds 本次渲染涉及的语句 id（含展开的 `<include>` 归属主语句）
 * @param usedSqlFragmentIds 展开过的 `<sql id>` 清单
 */
data class IbatisRenderResult(
    val sql: String,
    val compactSql: String,
    val paramNames: List<String>,
    val paramJdbcTypes: List<String?>,
    val paramNullValues: List<String?>,
    /** 与 [paramNames] 同序的哨兵命中标记（参数值 == `nullValue` ⇒ 引擎按 SQL NULL 绑定）。 */
    val nullBindFlags: List<Boolean>,
    val warnings: List<String>,
    val dollarRawCount: Int,
    val dollarRenderedValues: List<String>,
    val usedStatementIds: List<String>,
    val usedSqlFragmentIds: List<String>,
) {

    /** 是否含 `$` 直替段（注入闸门判据，等价于既有 `PreviewResult.hasDollarRawSegments` 语义）。 */
    val hasDollarRawSegments: Boolean get() = dollarRenderedValues.isNotEmpty()
}
