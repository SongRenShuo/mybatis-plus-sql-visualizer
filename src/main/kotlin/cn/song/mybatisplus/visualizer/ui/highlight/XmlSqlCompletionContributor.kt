package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.sql.stripXmlTags
import cn.song.mybatisplus.visualizer.ui.SourceSqlContext
import cn.song.mybatisplus.visualizer.ui.XmlParamTypeResolver
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider
import cn.song.mybatisplus.visualizer.ui.inject.ResultMapColumnCompletionMemo
import cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGate
import cn.song.mybatisplus.visualizer.xml.ParamKind
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.injected.editor.DocumentWindow
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * Mapper XML 源码补全贡献器（T-M27-03/04；T-XmlGap W4/W5 扩面）。
 *
 * 背景：为 `<select|insert|update|delete>` 语句与 `<sql>` 片段提供补全，全部基于**纯文本**分析
 * （[stripXmlTags] 等长剥离 + [SourceSqlCompleter]）或**有界 PSI**（[XmlCompletionSymbols]）：
 *
 *  a. **动态标签补全**：光标前非空白字符是 `<` → 补 [SourceSqlSuggestionFactory.DYNAMIC_TAGS]；
 *  b. **属性名补全（T-XmlGap W4-3）**：标签内属性名位置 → [MyBatisXmlCompletionData.tagAttributes]，
 *     含 `<select|insert|update|delete|selectKey>` 与 `<resultMap|id|result|collection|association|…>`（DTD 逐条）；
 *  c. **属性值补全（T-XmlGap W4-2：按属性名分流）**：见 [addAttributeValueCompletions]；
 *  d. **`#{}` 结构属性（T-XmlGap W4-1）**：`#{…,` 后切属性键，`key=` 后给枚举值；
 *  e. **`${}` OGNL（T-XmlGap W5-4/5/6）**：变量来源 + 智能片段 + 运算符 + `@类@静态成员`；
 *  f. **SQL 补全（T-XmlGap W5-7/8/9）**：关键字 + 函数表 + 单字母捷径 + 表/列/别名列 +
 *     select 投影位（resultType 类属性）+ 反引号内列名。
 *
 * **守卫（性能红线，O(1) 语法判定，零 resolve）**：position 祖先链上的**最近语句标签或 `<sql>` 片段**
 * 且根标签名属语句宿主（MyBatis `mapper` / iBatis `sqlMap`——T-Ibatis2Support 券 D 放宽），
 * 否则直接 return。iBatis 文件只走 SQL 文本补全分支（见 `addCompletions` 内 isIbatis 段的语义边界）。
 * 所有补全项 addElement 前经 `result.prefixMatcher.prefixMatches(...)` 过滤。
 *
 * 设计决策（保留）：双语言注册 + 注入片段 caret 宿主还原（T-PerfSweep16/T-XmlCmplDispatch）。
 *
 * 注册见 plugin.xml `<completion.contributor language="XML" .../>` + `language="MpSqlMapper"`（主控统一注册）。
 *
 * 实现 [DumbAware]（T-XmlCmpl2）：dumb 态照常被咨询；触索引的来源（跨文件 sql id / config properties）
 * 在 dumb 态返回空（见 [XmlCompletionSymbols]）。
 */
class XmlSqlCompletionContributor : CompletionContributor(), DumbAware {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    // 宿主还原（T-PerfSweep16 修复 + T-XmlCmplDispatch 二轮修正）：见原实现注释（三形态）
                    val injectedFile = parameters.position.containingFile
                    val injectionHost = injectedFile
                        ?.let { InjectedLanguageManager.getInstance(it.project).getInjectionHost(it) }
                    val hostFile = injectionHost?.containingFile
                    val position: PsiElement
                    val offset: Int
                    if (hostFile != null && hostFile.textLength > 0) {
                        val hostOffset = (injectedFile.virtualFile as? VirtualFileWindow)?.documentWindow
                            ?.injectedToHost(parameters.offset)
                            ?: (parameters.editor.document as? DocumentWindow)?.injectedToHost(parameters.offset)
                            ?: parameters.offset
                        position = hostFile.findElementAt(hostOffset.coerceIn(0, hostFile.textLength - 1)) ?: return
                        offset = hostOffset.coerceIn(0, hostFile.textLength)
                    } else {
                        position = parameters.position
                        offset = parameters.offset
                    }

                    // 守卫：祖先链最近语句标签或 `<sql>` 片段 + 语句宿主根（MyBatis `mapper` /
                    // iBatis `sqlMap`——T-Ibatis2Support 券 D 放宽；iBatis 分支只需 SQL 文本补全，
                    // 见下方 isIbatis 段的语义边界说明）
                    val enclosingTag = findEnclosingStatementTag(position) ?: return
                    val rootName = rootTag(enclosingTag).name
                    val isIbatis = rootName.equals(XmlSynHiGate.IBATIS_SQL_MAP_ROOT_TAG, ignoreCase = true)
                    if (rootName != "mapper" && !isIbatis) return

                    val prefix = result.prefixMatcher
                    val fileText = position.containingFile?.text ?: return
                    val statementStart = enclosingTag.textRange.startOffset
                    val statementText = fileText.substring(statementStart, enclosingTag.textRange.endOffset)
                    val caretInStatement = (offset - statementStart).coerceIn(0, statementText.length)
                    val beforeCaret = statementText.substring(0, caretInStatement)

                    // T-Ibatis2Support 券 D：iBatis 文件只放宽**语句文本内的 SQL 补全**（表/列/别名列/
                    // 关键字/函数）。其余分支的语义属 MyBatis、对 iBatis 不成立，故一律不接（「不做」
                    // 优于「接错语义」）：
                    //  - a. 动态标签补全：iBatis 动态标签是 `<isXxx>`/`<iterate>`/`<dynamic>`，与
                    //     MyBatis 的 if/foreach/choose 不是同一套 → 不接；
                    //  - b. 属性值分流：iBatis 属性值是属性名/字面量（无 OGNL、无 namespace 接口），
                    //     走 addAttributeValueCompletions 只会拿到 MyBatis 符号源（详见 XmlSynHiGate
                    //     的 allowAttributeValue KDoc）→ 不接；
                    //  - d/e. `${}` OGNL 与 `#{}` 参数名：iBatis 是 `#name#`/`$name$`，不是 OGNL 也不是
                    //     `#{}` → 不接。
                    // 未做项登记：iBatis `<statement>`/`<procedure>` 不在 findEnclosingStatementTag
                    // 的宿主集内；`<include refid>` ↔ `<sql id>` 补全属属性值分流面（须 iBatis 专属
                    // 候选源——现有 `refid` 路由会混入 MyBatis 跨文件 `<sql id>`）→ 均未做。
                    if (isIbatis) {
                        // 属性值位**连 SQL 分支也不进**：属性值不是语句文本，SQL 关键字补全在这里
                        // 同样是错位语义（且下面的属性值分流对 iBatis 已明确不接）。
                        if (PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java, false) != null) return
                        addSqlCompletions(enclosingTag, statementText, caretInStatement, result)
                        return
                    }

                    // a. 动态标签：光标前非空白字符是 `<`（`1=1 <caret>` 等插入点）
                    if (SourceSqlSuggestionFactory.DYNAMIC_TAG_PATTERN.containsMatchIn(beforeCaret)) {
                        SourceSqlSuggestionFactory.DYNAMIC_TAGS.forEach { tag ->
                            if (prefix.prefixMatches(tag)) result.addElement(SourceSqlSuggestionFactory.xmlTag(tag))
                        }
                        return
                    }

                    // b. 属性值位置（position 在 XmlAttributeValue 内）→ 按属性名分流（W4-2）
                    val attributeValue = PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java, false)
                    if (attributeValue != null) {
                        val attribute = attributeValue.parent as? XmlAttribute
                        val ownerTag = attribute?.parent as? XmlTag
                        if (attribute?.name != null && ownerTag != null) {
                            addAttributeValueCompletions(
                                attributeName = attribute.name,
                                ownerTag = ownerTag,
                                enclosingTag = enclosingTag,
                                caretElement = position,
                                beforeCaret = beforeCaret,
                                result = result,
                            )
                        }
                        return
                    }

                    // c. 属性名位置：position 在 XmlAttribute 上（属性名 token 内）或语句文本
                    //    光标前匹配 `<tagName  `（标签内属性名位）
                    val nearestTag = PsiTreeUtil.getParentOfType(position, XmlTag::class.java)
                    val attributeNamePosition = nearestTag != null && (
                        position.parent is XmlAttribute ||
                            SourceSqlSuggestionFactory.ATTRIBUTE_POSITION_PATTERN.containsMatchIn(beforeCaret)
                        )
                    if (attributeNamePosition) {
                        MyBatisXmlCompletionData.tagAttributes(nearestTag.name).forEach { attribute ->
                            if (prefix.prefixMatches(attribute)) result.addElement(SourceSqlSuggestionFactory.xmlAttribute(attribute))
                        }
                        return
                    }

                    // d. `${…}` 未闭合 → OGNL 变量 + 智能片段 + 运算符 + @类@静态成员（W5-4/5/6）
                    if (SourceSqlSuggestionFactory.isInsideDollarBrace(beforeCaret)) {
                        addOgnlCompletions(
                            enclosingTag = enclosingTag,
                            caretElement = position,
                            beforeCaret = beforeCaret,
                            result = result,
                            collectionAliases = false,
                            includeConfigProperties = true,
                        )
                        return
                    }

                    // e. `#{…}` 未闭合 → 逗号后结构属性（W4-1）/ 否则参数名（含 foreach item·index、bind）
                    if (SourceSqlSuggestionFactory.isInsideHashBrace(beforeCaret)) {
                        val inside = beforeCaret.substring(beforeCaret.lastIndexOf("#{") + 2)
                        if (inside.contains(',')) {
                            addParamStructureCompletions(inside, result)
                        } else {
                            addHashParamCompletions(inside, enclosingTag, position, result)
                        }
                        return
                    }

                    // f. SQL 补全：语句文本等长剥离标签 → 纯文本分析 → prefixMatches 过滤
                    //   （iBatis 分支复用同一实现——两类文件的语句文本都是 SQL，见 isIbatis 段）
                    addSqlCompletions(enclosingTag, statementText, caretInStatement, result)
                }
            },
        )
    }

    /**
     * 语句文本的 SQL 补全（原「f. SQL 补全」分支，T-Ibatis2Support 券 D 原样抽出供 iBatis 复用）。
     *
     * 大小写口径（T-XmlGap 收口第三轮真机实测）：候选词表多为**大写**关键字/函数而平台前缀匹配器
     * **区分大小写** ⇒ 小写键入无候选（用户观感「补全不灵」）。SQL 关键字/函数与未加引号的标识符
     * 语义上都大小写不敏感，故本分支在平台匹配之外再补一次忽略大小写匹配；其余分支（参数名/实体
     * 字段等 Java 侧标识符）保持平台原语义。
     *
     * iBatis 侧的降级说明：`resolveContext` 走根 `namespace` 找 Mapper 接口，iBatis 无 namespace
     * ⇒ 上下文恒空 ⇒ 表/列/实体候选为空，只出 SQL 关键字/函数与 `<resultMap>` 的
     * `column`/`property` 反引号候选（`quotedIdentifierCandidates` 按标签名扫，iBatis 同名标签在场）。
     * 这是「不做错」的降级形态：不产出错误候选，仅覆盖面小于 MyBatis。
     */
    private fun addSqlCompletions(
        enclosingTag: XmlTag,
        statementText: String,
        caretInStatement: Int,
        result: CompletionResultSet,
    ) {
        val prefix = result.prefixMatcher
        val stripped = stripXmlTags(statementText)
        val sqlContext = resolveContext(enclosingTag)
        val projectionProperties = projectionProperties(enclosingTag, sqlContext)
        val quotedCandidates = quotedIdentifierCandidates(enclosingTag)
        val typedPrefix = prefix.prefix
        fun sqlMatches(candidate: String): Boolean =
            prefix.prefixMatches(candidate) ||
                (typedPrefix.isNotEmpty() && candidate.startsWith(typedPrefix, ignoreCase = true))
        SourceSqlCompleter.sqlCompletions(
            stripped,
            caretInStatement,
            sqlContext,
            projectionProperties,
            quotedCandidates,
        ).forEach { item ->
            if (item.allLookupStrings.any { sqlMatches(it) }) result.addElement(item)
        }
    }

    // ------------------------------------------------------------------
    // b. 属性值分流（T-XmlGap W4-2）
    // ------------------------------------------------------------------

    /**
     * 属性值补全按**属性名**分流（W4-2）。每条：
     *  - `test`/`value`/`collection` → OGNL（变量 + 片段 + 运算符；`collection` 追加集合快捷别名）
     *  - `id`（语句标签）→ namespace 接口方法名；`keyProperty` → 方法参数名 + 实体字段名
     *  - `refid` → 本文件 `<sql id>` + 跨文件 `namespace.id`
     *  - `resultMap`/`extends` → 本文件 `<resultMap id>`；`parameterMap` → 本文件 `<parameterMap id>`
     *  - `resultType`/`type`/`ofType`/`parameterType`/`typeHandler`/`javaType` → 有界类名
     *  - `property`（及 `<arg name>`）→ 实体字段名（resultMap 上下文取 `<resultMap type>` 类字段）
     *  - `column`/`keyColumn`/`columnPrefix` → 列名（resultMap 的 `result`/`id` 交由
     *    [cn.song.mybatisplus.visualizer.ui.inject.ResultMapColumnCompletionContributor] 独占，
     *    避免重复候选）
     *  - `jdbcType`/`mode`/`statementType`/`resultSetType`/`fetchType`/`order` → 枚举值
     *  - 布尔属性（useGeneratedKeys/flushCache/…）→ true/false
     *  - `select` → 本文件语句 id（arg/association/collection 指向的 statement）
     *  - `timeout`/`fetchSize`/`numericScale`/`resultSets`/… → 无合理候选，明确不产
     *
     * 其余属性值 → 不产出（不落入 SQL 分支，避免噪声）。
     */
    private fun addAttributeValueCompletions(
        attributeName: String,
        ownerTag: XmlTag,
        enclosingTag: XmlTag,
        caretElement: PsiElement,
        beforeCaret: String,
        result: CompletionResultSet,
    ) {
        val prefix = result.prefixMatcher
        val project = ownerTag.project
        val tagName = ownerTag.name
        val inResultMap = ancestorResultMapTag(ownerTag) != null

        fun emitAll(values: List<String>) {
            values.forEach { value ->
                if (value.isNotBlank() && prefix.prefixMatches(value)) {
                    result.addElement(SourceSqlSuggestionFactory.literalValue(value))
                }
            }
        }

        when {
            attributeName == "test" || attributeName == "collection" || attributeName == "value" -> {
                addOgnlCompletions(
                    enclosingTag = enclosingTag,
                    caretElement = caretElement,
                    beforeCaret = beforeCaret,
                    result = result,
                    collectionAliases = attributeName == "collection",
                    includeConfigProperties = true,
                )
            }

            attributeName == "id" && tagName in MyBatisXmlCompletionData.STATEMENT_TAGS ->
                emitAll(XmlCompletionSymbols.mapperMethodNames(project, enclosingTag))

            attributeName == "keyProperty" -> {
                val context = resolveContext(enclosingTag)
                emitAll(context.paramNames + XmlCompletionSymbols.entityFieldNames(context))
            }

            attributeName == "refid" -> {
                val sameFile = XmlCompletionSymbols.localIds(enclosingTag, "sql")
                val crossFile = XmlCompletionSymbols.crossFileSqlIds(project, enclosingTag.containingFile as? XmlFile)
                emitAll(sameFile + crossFile)
            }

            attributeName == "resultMap" || attributeName == "extends" ->
                emitAll(XmlCompletionSymbols.localIds(enclosingTag, "resultMap"))

            attributeName == "parameterMap" ->
                emitAll(XmlCompletionSymbols.localIds(enclosingTag, "parameterMap"))

            attributeName in MyBatisXmlCompletionData.CLASS_NAME_ATTRIBUTES -> {
                val context = resolveContext(enclosingTag)
                emitAll(XmlCompletionSymbols.classCandidates(project, enclosingTag, context))
            }

            attributeName == "property" -> {
                val fields = if (inResultMap) {
                    XmlCompletionSymbols.resultMapTypeFields(ownerTag)
                } else {
                    XmlCompletionSymbols.classPropertyNames(project, ownerTag, XmlCompletionSymbols.entityFieldNames(resolveContext(enclosingTag)))
                }
                emitAll(fields)
            }

            attributeName == "name" && (tagName == "arg" || tagName == "idArg") ->
                emitAll(XmlCompletionSymbols.entityFieldNames(resolveContext(enclosingTag)))

            attributeName == "column" -> {
                // resultMap 的 result/id：由 ResultMapColumnCompletionContributor 独占（防重复候选）
                if (inResultMap && (tagName == "result" || tagName == "id")) return
                emitAll(resultMapReferencedColumns(ownerTag) ?: XmlCompletionSymbols.entityColumnNames(resolveContext(enclosingTag)))
            }

            attributeName == "columnPrefix" || attributeName == "keyColumn" ->
                emitAll(resultMapReferencedColumns(ownerTag) ?: XmlCompletionSymbols.entityColumnNames(resolveContext(enclosingTag)))

            attributeName == "jdbcType" -> emitAll(MyBatisXmlCompletionData.JDBC_TYPE_VALUES)
            attributeName == "mode" -> emitAll(MyBatisXmlCompletionData.PARAM_MODE_VALUES)
            attributeName == "statementType" -> emitAll(MyBatisXmlCompletionData.STATEMENT_TYPE_VALUES)
            attributeName == "resultSetType" -> emitAll(MyBatisXmlCompletionData.RESULT_SET_TYPE_VALUES)
            attributeName == "fetchType" -> emitAll(MyBatisXmlCompletionData.FETCH_TYPE_VALUES)
            attributeName == "order" && tagName == "selectKey" -> emitAll(MyBatisXmlCompletionData.ORDER_VALUES)
            attributeName in MyBatisXmlCompletionData.BOOLEAN_ATTRIBUTES -> emitAll(MyBatisXmlCompletionData.BOOLEAN_VALUES)
            attributeName == "select" -> emitAll(XmlCompletionSymbols.localStatementIds(enclosingTag))
            // timeout/fetchSize/numericScale/resultSets/scale/databaseId/lang/name(bind)/… → 无合理候选，不产
        }
    }

    /**
     * resultMap 上下文（`result`/`id` 之外）的列候选：解析引用该 resultMap 的 `<select>` 投影列
     * （T-XmlGap W5-10 的同一来源）；非 resultMap 上下文 → null（调用方回退实体列）。
     */
    private fun resultMapReferencedColumns(ownerTag: XmlTag): List<String>? {
        val resultMapTag = ancestorResultMapTag(ownerTag) ?: return null
        val id = resultMapTag.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: return null
        val file = ownerTag.containingFile as? XmlFile ?: return null
        return ResultMapColumnCompletionMemo.columnsForResultMap(file, id)
    }

    // ------------------------------------------------------------------
    // c. `#{…}` 结构属性与参数名（T-XmlGap W4-1）
    // ------------------------------------------------------------------

    /** `#{…,` 逗号后：`key=` 形态给对应枚举值；否则给 7 项属性键（`key=` 形态，便于直接续敲）。 */
    private fun addParamStructureCompletions(inside: String, result: CompletionResultSet) {
        val prefix = result.prefixMatcher
        val afterComma = inside.substringAfterLast(',')
        val eqIndex = afterComma.indexOf('=')
        if (eqIndex >= 0) {
            val key = afterComma.substring(0, eqIndex).trim()
            MyBatisXmlCompletionData.paramAttributeValues(key).forEach { value ->
                if (prefix.prefixMatches(value)) result.addElement(SourceSqlSuggestionFactory.literalValue(value))
            }
        } else {
            MyBatisXmlCompletionData.paramAttributeKeyHints().forEach { hint ->
                if (prefix.prefixMatches(hint)) result.addElement(SourceSqlSuggestionFactory.literalValue(hint))
            }
        }
    }

    /** `#{…` 无逗号：参数名（@Param/形参/paramN）+ OGNL 结构性变量（bind / foreach item·index）；
     *  第二段（`x.`）时给 foreach item 元素字段或主实体字段名。 */
    private fun addHashParamCompletions(
        inside: String,
        enclosingTag: XmlTag,
        caretElement: PsiElement,
        result: CompletionResultSet,
    ) {
        val prefix = result.prefixMatcher
        val context = resolveContext(enclosingTag)
        val secondSegment = SourceSqlSuggestionFactory.OGNL_PREFIX_PATTERN.containsMatchIn(inside)
        if (secondSegment) {
            val owner = inside.substringBeforeLast('.').substringAfterLast(',').trim()
            val itemFields = XmlCompletionSymbols.foreachItemFields(enclosingTag, caretElement, owner)
            val fields = if (itemFields.isNotEmpty()) itemFields else mainEntityFieldNames(context)
            fields.forEach { name -> if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name)) }
            return
        }
        context.paramNames.forEach { name ->
            if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
        }
        OgnlVariableSources.collect(caretElement, enclosingTag, enclosingTag.project, includeConfigProperties = false)
            .forEach { name ->
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
            }
    }

    // ------------------------------------------------------------------
    // d. OGNL（T-XmlGap W5-4/5/6）
    // ------------------------------------------------------------------

    /**
     * OGNL 值内补全：方法参数名 + 主实体 Java 字段名（fieldName）+ **变量来源**
     * （`<bind>` / foreach item·index / include property / `<properties>` key / `_databaseId`·`_parameter`）
     * + **智能片段**（按类型）+ **运算符** + `@类@静态成员`。
     * 光标前有 `param.` 前缀时只补第二段（既有语义保留）。
     */
    private fun addOgnlCompletions(
        enclosingTag: XmlTag,
        caretElement: PsiElement,
        beforeCaret: String,
        result: CompletionResultSet,
        collectionAliases: Boolean,
        includeConfigProperties: Boolean,
    ) {
        val prefix = result.prefixMatcher
        val context = resolveContext(enclosingTag)
        val fieldNames = mainEntityFieldNames(context)
        val secondSegment = SourceSqlSuggestionFactory.OGNL_PREFIX_PATTERN.containsMatchIn(beforeCaret)
        if (secondSegment) {
            fieldNames.forEach { name ->
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
            }
        } else {
            context.paramNames.forEach { name ->
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
            }
            fieldNames.forEach { name ->
                if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
            }
            OgnlVariableSources.collect(caretElement, enclosingTag, enclosingTag.project, includeConfigProperties)
                .forEach { name ->
                    if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
                }
            if (collectionAliases) {
                COLLECTION_ALIAS_NAMES.forEach { name ->
                    if (prefix.prefixMatches(name)) result.addElement(SourceSqlSuggestionFactory.param(name))
                }
            }
        }
        // W5-5 智能片段（按可推断类型；已输入变量名 token 为空则不产）
        val variableToken = SourceSqlSuggestionFactory.OGNL_TOKEN_AT_CARET.find(beforeCaret)?.value.orEmpty()
        if (variableToken.isNotEmpty() && !variableToken.endsWith(".")) {
            val kind = variableKind(enclosingTag, variableToken)
            MyBatisXmlCompletionData.ognlSnippets(variableToken, kind).forEach { snippet ->
                if (prefix.prefixMatches(snippet)) {
                    result.addElement(SourceSqlSuggestionFactory.ognlSnippet(snippet, variableToken))
                }
            }
        }
        // W5-6 运算符
        MyBatisXmlCompletionData.OGNL_OPERATORS.forEach { op ->
            if (prefix.prefixMatches(op)) result.addElement(SourceSqlSuggestionFactory.param(op))
        }
        // W5-6 `@类@静态成员`
        addAtMemberCompletions(enclosingTag.project, beforeCaret, result)
    }

    /** `@类@静态成员`（W5-6）：能解析则给静态成员，解析不到或代价大只给语法骨架（不做无界搜索）。 */
    private fun addAtMemberCompletions(project: Project, beforeCaret: String, result: CompletionResultSet) {
        val atToken = SourceSqlSuggestionFactory.OGNL_AT_TOKEN_AT_CARET.find(beforeCaret)?.value ?: return
        val prefix = result.prefixMatcher
        val closed = AT_CLOSED.find(atToken)
        if (closed != null) {
            val className = closed.groupValues[1]
            val clazz: PsiClass? = XmlCompletionSymbols.resolveClass(project, className)
            if (clazz != null) {
                staticMembersOf(clazz).forEach { member ->
                    val text = "@$className@$member"
                    if (prefix.prefixMatches(text)) result.addElement(SourceSqlSuggestionFactory.param(text))
                }
            } else {
                // 类不可解析 → 只给语法骨架（不做无界类搜索）
                val skeleton = atToken + "成员"
                if (prefix.prefixMatches(skeleton)) result.addElement(SourceSqlSuggestionFactory.param(skeleton))
            }
        } else {
            // 只敲了 `@…`：给语法骨架
            val skeleton = "@类@静态成员"
            if (prefix.prefixMatches(skeleton)) result.addElement(SourceSqlSuggestionFactory.param(skeleton))
        }
    }

    /** PsiClass 的静态方法与静态字段名（有界：单类成员）。 */
    private fun staticMembersOf(clazz: PsiClass): List<String> = runCatching {
        val methods = clazz.methods.filter { it.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) }
            .mapNotNull { it.name }
        val fields = clazz.fields.filter { it.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) }
            .mapNotNull { it.name }
        (methods + fields).distinct()
    }.getOrElse { emptyList() }

    /** 变量 token → 类型类别（[XmlParamTypeResolver] 推断；推不出 → null → 通用片段）。 */
    private fun variableKind(enclosingTag: XmlTag, token: String): ParamKind? = runCatching {
        val project = enclosingTag.project
        val namespace = XmlCompletionSymbols.mapperNamespace(enclosingTag) ?: return null
        val statementId = enclosingTag.getAttributeValue("id") ?: return null
        XmlParamTypeResolver.resolveTypes(project, namespace, statementId)[token]?.kind
    }.getOrNull()

    /** 主实体 Java 字段名（OGNL 用 fieldName）。 */
    private fun mainEntityFieldNames(context: SourceSqlContext): List<String> =
        XmlCompletionSymbols.entityFieldNames(context)

    // ------------------------------------------------------------------
    // e/f. select 投影位与反引号候选（T-XmlGap W5-7/8）
    // ------------------------------------------------------------------

    /** select 投影位候选：语句 `resultType` 类字段（解析不到回退主实体字段名）。 */
    private fun projectionProperties(enclosingTag: XmlTag, context: SourceSqlContext): List<String> {
        if (enclosingTag.name != "select") return emptyList()
        val resultType = enclosingTag.getAttributeValue("resultType")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val fields = XmlCompletionSymbols.fieldsOfClass(enclosingTag.project, resultType)
        return if (fields.isNotEmpty()) fields else mainEntityFieldNames(context)
    }

    /** 反引号内候选：本文件全部 `<resultMap>` 的 `column` + `property` 属性值（W5-8）。 */
    private fun quotedIdentifierCandidates(enclosingTag: XmlTag): List<String> = runCatching {
        val root = rootTag(enclosingTag)
        val out = LinkedHashSet<String>()
        root.findSubTags("resultMap").forEach { resultMap ->
            PsiTreeUtil.findChildrenOfType(resultMap, XmlTag::class.java).forEach { child ->
                if (child.name == "result" || child.name == "id") {
                    child.getAttributeValue("column")?.takeIf { it.isNotBlank() }?.let { out += it }
                    child.getAttributeValue("property")?.takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
        }
        out.toList()
    }.getOrElse { emptyList() }

    /** 补全上下文解析（异常 → 空上下文，只补关键字）。 */
    private fun resolveContext(enclosingTag: XmlTag): SourceSqlContext =
        runCatching { XmlSqlCompletionContextResolver.resolveXml(enclosingTag.project, enclosingTag) }
            .getOrElse { EMPTY_SOURCE_SQL_CONTEXT }

    // ------------------------------------------------------------------
    // 守卫
    // ------------------------------------------------------------------

    /**
     * 祖先链上**最近的补全宿主标签**：语句标签（select/insert/update/delete）、`<sql>` 片段、
     * `<resultMap>`、`<parameterMap>`。
     *
     * T-XmlGap 扩展：原仅认语句标签（`<sql>`/`<resultMap>` 内零补全），现纳入——
     *  - `<sql>` 片段是 include 的复用体，含 SQL 与 `${}`（W5-4 include property 可见域）；
     *  - `<resultMap>`/`<parameterMap>` 的属性值（property/jdbcType/javaType/typeHandler/column…，
     *    W4-2 分流目标）都在其中，原守卫下不可达。
     * 从 position 向上爬（strict=false 兼容 position 自身即 XmlTag 的补全复制文件形态），
     * 跳过中间动态标签——纯语法判定，零 resolve。
     */
    private fun findEnclosingStatementTag(position: PsiElement): XmlTag? {
        var element: PsiElement? = position
        while (element != null) {
            if (element is XmlTag) {
                val name = element.name
                if (name in XmlSqlGutterLineMarkerProvider.STATEMENT_TAGS ||
                    name == "sql" || name == "resultMap" || name == "parameterMap"
                ) {
                    return element
                }
            }
            element = element.parent
        }
        return null
    }

    /** 最近的 `<resultMap>` 祖先标签（result/id 等属性值分流用）。 */
    private fun ancestorResultMapTag(tag: XmlTag): XmlTag? {
        var cur: XmlTag? = tag
        while (cur != null) {
            if (cur.name == "resultMap") return cur
            cur = cur.parentTag
        }
        return null
    }

    /** 沿 parentTag 链取根标签（`<mapper>` 判定用）。 */
    private fun rootTag(tag: XmlTag): XmlTag {
        var cur = tag
        while (cur.parentTag != null) cur = cur.parentTag!!
        return cur
    }

    // ------------------------------------------------------------------
    // 静态数据
    // ------------------------------------------------------------------

    /**
     * foreach `collection` 属性专属快捷名（T-FCA-03）：MyBatis 把「单集合参数且无 `@Param`」包装成
     * `ParamMap` 后的别名键（`list` / `collection` / `array`）。只对 `collection` 属性开放。
     */
    private val COLLECTION_ALIAS_NAMES = listOf("list", "collection", "array")

    /** resolve 失败降级上下文（只补关键字/动态标签，不阻塞补全）。 */
    private val EMPTY_SOURCE_SQL_CONTEXT = SourceSqlContext(null, emptyList(), emptyList())

    private companion object {
        /** `@…@…`（已闭合第二段）token。 */
        val AT_CLOSED = Regex("""^@([\w.$]+)@([\w]*)$""")
    }
}
