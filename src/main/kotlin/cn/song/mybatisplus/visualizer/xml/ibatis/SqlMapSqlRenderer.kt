package cn.song.mybatisplus.visualizer.xml.ibatis

import cn.song.mybatisplus.visualizer.sql.placeholderPositions
import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle

/**
 * 渲染降级的失败原因分类（T-Ibatis2Gap 券 D）。
 *
 * **没有 UNKNOWN_TAG**：未知标签在解析期就被 [SqlMapScriptBuilder] 降级成文本节点收下
 * （「原文保留 + 告警」），根本到不了渲染抛错点；给它留档位只会造出一个永远走不到的分支
 * （`convention.EnumReachabilityGuardTest` 的零产出守卫会红）。
 */
enum class IbatisRenderFailureKind {
    /** `<iterate>` 的 property 取值不是集合/数组/迭代器（引擎 `SqlMapException`；无参基线下这是「待填值」态，不是错误）。 */
    ITERATE_NOT_COLLECTION,

    /** 引擎同样硬失败的渲染期错误（如 iterate 迭代上下文缺失等内部不变量被破坏）。 */
    RENDER_ERROR,

    /** 其余意外异常（渲染器外部的解析/配置问题）。 */
    PARSE_ERROR,
}

/**
 * 券 D：降级时「哪个顶层节点之后开始未渲染」的结构化事实。
 *
 * 位置口径：解析器（DOM）没有保留原文行号/偏移，故只给**失败节点在 [IbatisStatement.body] 顶层
 * 的位置（0 基）**——即「第 N 个顶层节点（含其后全部内容）未渲染」。渲染到此节点前已写入的
 * 输出与既有回退拼装（顶层文本节点）口径一致。
 */
data class IbatisUnrenderedNode(
    /** 失败的顶层标签名；顶层文本节点抛错时为 null（文本节点路径 token 解析只告警不抛，实践中不会出现）。 */
    val tagName: String?,
    /** 失败节点在 [IbatisStatement.body] 顶层的位置（0 基）。 */
    val topNodeIndex: Int,
    /** 失败原因分类（UI 分档显示：ITERATE_NOT_COLLECTION = 待填值，不是错误）。 */
    val reason: IbatisRenderFailureKind,
    /** 失败涉及的属性名（来自抛点：`<iterate property="ids">` 的 `ids`）——供 UI 提示「该语句需要此参数」。 */
    val property: String? = null,
    /** 引擎/渲染器的原始错误消息（可回溯）。 */
    val message: String? = null,
)

/**
 * 券 D：`<iterate>` 取值非集合的分类载体。
 *
 * 抛点在 [SqlMapSqlRenderer.startIterate]——那里已知标签与 property，包一层才能把
 * 「取值不是集合」升级成可分类（ITERATE_NOT_COLLECTION）与可出分档文案的形态；
 * 上层（`renderBody` 的顶层回退）据此填 [IbatisUnrenderedNode.reason] / [IbatisUnrenderedNode.property]。
 *
 * 直接继承 `RuntimeException` 而不是 `IbatisRenderException`：后者是 final 类，而本车道写集
 * 约束 `IbatisModel.kt` 只许给 `IbatisRenderResult` 追加字段；分类走 [classifyFailure] 的
 * `is` 判定型分支，不依赖类型层级。
 */
class IbatisIterateNotCollectionException(
    /** `<iterate property="...">` 的 property 原值（可为 null——无 property 时取参数对象本身）。 */
    val property: String?,
    message: String,
) : RuntimeException(message)

/**
 * iBatis 2.x SQL 渲染器（T-Ibatis2Support 券 B 主体）。
 *
 * 逐行复刻 `XMLSqlSource` + `MappedStatementConfig.setSqlForStatement` + `DynamicSql.process` 的执行链：
 * 静态语句走「配置期一次解析」，动态语句走「每次执行重跑标签树」。两条链的 `#` 处理时机不同，
 * 且**声明了 `parameterMap` 的守卫只对非动态语句生效**（见下）。
 *
 * ## 三条不许「顺手改好」的硬口径
 * 1. **逐字拼接、绝不补空格**：`open`/`prepend`/`conjunction`/`close` 全部原样 `insert`，
 *    所以真实产物会出现 `... ANDORG_NAME LIKE ...` 这种粘连——这是引擎的输出，必须原样复现
 *    （渲染结果**禁止美化重排**，与既有 XML 链的输出习惯相反）；
 * 2. **`$` 永远在动态标签求值之后替换**（`DynamicSql.process` 末尾才 new `SimpleDynamicSql`）；
 * 3. **`parameterMap` 守卫只作用于非动态语句**：`MappedStatementConfig.setSqlForStatement` 里
 *    `sql instanceof DynamicSql` 直接 `setSql(sql)`，根本不会进 `applyInlineParameterMap`——
 *    即「有动态标签 + 声明 parameterMap」的语句，`#` **仍然**转 `?`。
 *
 * ## 静态语句路径的空白口径（两条分支不同，实测自 `SqlText.setText` 与 `StaticSql` 构造器）
 * - `parameterMap == null`：文本经 `InlineParameterMapParser` → `SqlText.setText`，`\r` `\n` `\t` **全部**换空格；
 * - `parameterMap != null`：文本直接进 `StaticSql`，只把 `\r` `\n` 换空格，**制表符保留**。
 *
 * ## 已知偏差（未与 oracle 对齐的形态；对照集 `SqlMapFidelityTest` 覆盖域之外）
 *
 * **A. 真正语义不同的形态（引擎成功、本链不同）——只有一条**
 * - **`${...}` 属性占位替换**：引擎在配置期用 `NodeletUtils.parsePropertyTokens(data, globalProps)` 把
 *   `${key}` 换成全局属性值，取不到时退化为 `key` 本身（例如 `${driver}` → `driver`）；本链**不做该替换**，
 *   按原文保留并在解析期给告警。最小复现：`<select id="s">SELECT * FROM ${tableName}</select>` →
 *   引擎 = `SELECT * FROM tableName`（无 `<properties>` 时），本链 = `SELECT * FROM ${tableName}`。
 *   原因：本链是单文件解析器，不读 `sqlMapConfig` 的 `<properties>`，无全局属性表可用。
 *
 * **B. 引擎硬失败的形态（无 oracle 读数可比，本链按券 B 口径降级）**
 * 以下形态引擎抛异常、整篇加载失败，逐字节对照无定义，故本链统一「原文保留 + warning」：
 * - 未知标签（引擎 `SqlTagHandlerFactory` 返回 null 后**静默丢弃整个子树**，见
 *   `SqlMapScriptBuilder` 的「未识别的标签」/`SqlMapParserTest`）；
 * - 未闭合 `#` / `$`（引擎抛 `SqlMapException("Unterminated ...")` 或 `NoSuchElementException`）；
 * - 内联参数格式错（冒号分段数非 3/≥5、`name=value` 不成对、未知字段、numericScale 非法 →
 *   引擎抛 `SqlMapException`）；
 * - `compareValues` 换算失败（如 `compareValue="N/A"` 比 Integer 属性 → 引擎抛 `NumberFormatException`），
 *   本链折成 `NOT_COMPARABLE` + 告警；
 * - `<include refid>` 找不到片段（引擎抛 `RuntimeException`）；
 * - `parameterMap` 属性指向不存在的图（引擎 `getParameterMap(id)` 抛
 *   `There is no parameter map named ...`），本链告警并按「未声明」处理；
 * - `<iterate property>` 取到的不是集合/数组/迭代器（引擎抛 `SqlMapException`；`property` 为 null 时
 *   引擎在 `collection.getClass()` 直接 NPE），本链抛可回溯的 `IbatisRenderException` 并在语句级降级；
 * - `addIndex` 的 `charAt` 越界（引擎抛 `StringIndexOutOfBoundsException`），本链按「不匹配」跳过。
 */
class SqlMapSqlRenderer {

    private val tokenParser = SqlMapTokenParser()

    /**
     * 渲染一条语句。
     *
     * @param params 参数对象（`Map` / JavaBean / 标量 / null）。IDE 侧通常传 null（等价于引擎的
     *   「无参基线渲染」，此时 `<isNull>`/`<isEmpty>`/`<isNotParameterPresent>` 分支成立）。
     */
    fun render(statement: IbatisStatement, params: Any? = null): IbatisRenderResult {
        val warnings = ArrayList<String>(statement.warnings)
        val warn: (String) -> Unit = { warnings.add("语句 [${statement.id}]：$it") }
        val mapped = ArrayList<IbatisParamMapping>()
        // 券 D：降级事实收集器——renderBody 抛错时由顶层节点循环填入，回退拼装本身不变
        val unrendered = ArrayList<IbatisUnrenderedNode>()
        // 券 C：parameterMap 声明了、但语句体参数位点没消费到的映射
        val unusedDeclared = ArrayList<IbatisParamMapping>()

        var failureKind: IbatisRenderFailureKind? = null
        val body = runCatching { renderBody(statement, params, mapped, warn, unrendered, unusedDeclared) }
            .getOrElse { error ->
                val kind = classifyFailure(error)
                failureKind = kind
                // 券 D：文案按失败形态分档——禁止再产出与事实不符的「按原文保留 # 直替」
                warn(degradedWarning(kind, unrendered.firstOrNull(), error))
                statement.body.filterIsInstance<IbatisTextNode>().joinToString(separator = "") { it.text }
            }

        val dollars = tokenParser.processDollarSubstitution(body, params)
        dollars.warnings.forEach(warn)

        return IbatisRenderResult(
            sql = dollars.text,
            compactSql = compact(dollars.text),
            paramNames = mapped.map { it.propertyName },
            paramJdbcTypes = mapped.map { it.jdbcTypeName },
            paramNullValues = mapped.map { it.nullValue },
            nullBindFlags = mapped.map { mapping ->
                IbatisProbe.matchesNullValue(
                    params?.let { IbatisProbe.getObject(it, mapping.propertyName) },
                    mapping.nullValue,
                )
            },
            warnings = warnings,
            dollarRawCount = dollars.siteCount,
            dollarRenderedValues = dollars.renderedValues,
            usedStatementIds = listOf(statement.id),
            usedSqlFragmentIds = statement.usedSqlFragmentIds,
            degraded = failureKind != null,
            unrenderedNodes = unrendered.toList(),
            unusedDeclaredParams = unusedDeclared.toList(),
        )
    }

    /**
     * 渲染 `<selectKey>` 生成的语句（引擎里它是独立 `MappedStatement`，id = `<insertId>-SelectKey`）。
     */
    fun renderSelectKey(selectKey: IbatisSelectKey, params: Any? = null): IbatisRenderResult {
        val synthetic = IbatisStatement(
            id = selectKey.id,
            kind = IbatisStatementKind.INSERT,
            kindTagName = "selectKey",
            attributes = selectKey.attributes,
            parameterMapRef = null,
            hasParameterMapDeclaration = false,
            body = selectKey.body,
            selectKeys = emptyList(),
            warnings = selectKey.warnings,
            usedSqlFragmentIds = emptyList(),
        )
        return render(synthetic, params)
    }

    // ------------------------------------------------------------------ 两条路径

    private fun renderBody(
        statement: IbatisStatement,
        params: Any?,
        mapped: MutableList<IbatisParamMapping>,
        warn: (String) -> Unit,
        unrendered: MutableList<IbatisUnrenderedNode>,
        unusedDeclared: MutableList<IbatisParamMapping>,
    ): String {
        if (!statement.isDynamic) return renderStatic(statement, mapped, warn, unusedDeclared)
        val ctx = IbatisTagContext()
        // 顶层片段写进本方法的缓冲（引擎里就是 SqlTagContext 自己的 StringWriter）；
        // 嵌套标签各自用独立缓冲，处理完 doEndFragment/doPrepend 后才条件性地写回父缓冲。
        val out = StringBuilder()
        // 券 D：按顶层节点逐个求值——任一节点抛错时，把「失败的是哪个顶层节点、为什么」记成
        // 结构化事实再原样上抛（回退拼装仍由 render() 的 getOrElse 负责，这里不改产出逻辑）。
        for ((index, child) in statement.body.withIndex()) {
            try {
                when (child) {
                    is IbatisTextNode -> appendText(ctx, child, out)
                    is IbatisTagNode -> processTag(ctx, params, child, out)
                    else -> Unit
                }
            } catch (e: Throwable) {
                // 禁用 API 守卫口径（BannedApiScanRuleTest）：fatal 一律立即透传，不落「未渲染节点」事实——
                // OOM/栈溢出不是某个标签渲染失败，把它记成渲染事实既误导又可能二次OOM。
                if (FatalErrors.isFatal(e)) throw e
                unrendered.add(
                    IbatisUnrenderedNode(
                        tagName = (child as? IbatisTagNode)?.kind?.tagName,
                        topNodeIndex = index,
                        reason = classifyFailure(e),
                        property = (e as? IbatisIterateNotCollectionException)?.property,
                        message = e.message,
                    ),
                )
                throw e
            }
        }
        mapped.addAll(ctx.parameterMappings)
        ArrayList(ctx.warnings).forEach(warn)
        return out.toString()
    }

    /** 非动态语句：`XMLSqlSource.getSql()` → `RawSql` → `applyInlineParameterMap` 的口径。 */
    private fun renderStatic(
        statement: IbatisStatement,
        mapped: MutableList<IbatisParamMapping>,
        warn: (String) -> Unit,
        unusedDeclared: MutableList<IbatisParamMapping>,
    ): String {
        val raw = statement.body
            .filterIsInstance<IbatisTextNode>()
            .joinToString(separator = "") { it.rawText }
        val declared = statement.parameterMap
        if (declared != null) {
            // 引擎的 if (parameterMap == null) 守卫：原样保留 `#`，只做 \r\n 归一（制表符保留）
            //
            // 券 C（参数名按位对齐）：这类语句体的绑定语义是**按位置**把 parameterMap 的映射依序
            // 绑到语句体的参数位点（手写 `?`，或 `#x#` 原样保留形态下的内联参数对）上。故
            // paramNames 只取**前 N 个**声明映射（N = string-aware 位点数），剩余映射不丢——
            // 透出 unusedDeclaredParams 供 UI 提示「声明了但未消费」。位点数多于声明数时全部
            // 声明都被消费（与保真对照 oracle 一致：oracle 返回全部声明映射）。
            val siteCount = countParameterSites(raw)
            declared.parameters.take(siteCount).forEach { parameter ->
                mapped.add(
                    IbatisParamMapping(
                        propertyName = parameter.property,
                        jdbcTypeName = parameter.jdbcType,
                        nullValue = parameter.nullValue,
                        javaTypeName = parameter.javaType,
                        mode = parameter.mode,
                    ),
                )
            }
            declared.parameters.drop(siteCount).forEach { parameter ->
                unusedDeclared.add(
                    IbatisParamMapping(
                        propertyName = parameter.property,
                        jdbcTypeName = parameter.jdbcType,
                        nullValue = parameter.nullValue,
                        javaTypeName = parameter.javaType,
                        mode = parameter.mode,
                    ),
                )
            }
            return raw.replace('\r', ' ').replace('\n', ' ')
        }
        val parsed = tokenParser.parseInlineParameterMap(
            raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' '),
        )
        parsed.warnings.forEach(warn)
        mapped.addAll(parsed.mappings)
        return parsed.text
    }

    // ------------------------------------------------------------------ 标签树求值

    private fun processBodyChildren(
        ctx: IbatisTagContext,
        params: Any?,
        children: List<IbatisSqlNode>,
        out: StringBuilder,
    ) {
        for (child in children) {
            when (child) {
                is IbatisTextNode -> appendText(ctx, child, out)
                is IbatisTagNode -> processTag(ctx, params, child, out)
                else -> Unit
            }
        }
    }

    private fun appendText(ctx: IbatisTagContext, child: IbatisTextNode, out: StringBuilder) {
        if (child.isWhitespace) {
            out.append(child.renderText)
            return
        }
        if (!child.postParseRequired) {
            out.append(child.renderText)
            ctx.parameterMappings.addAll(child.mappings)
            return
        }
        // iterate 内的文本：`#`/`$` 只存原文，每次迭代重算（引擎 postParseRequired 分支）
        var text = child.text
        val iterate = ctx.peekIterateContext()
        if (iterate != null && iterate.allowNext) {
            iterate.next()
            iterate.allowNext = false
            if (!iterate.hasNext()) iterate.isFinal = true
        }
        if (iterate != null) text = iteratePropertyReplace(text, iterate)
        val parsed = tokenParser.parseInlineParameterMap(text)
        ctx.warnings.addAll(parsed.warnings)
        out.append(parsed.text)
        ctx.parameterMappings.addAll(parsed.mappings)
    }

    /**
     * 引擎 `DynamicSql.iteratePropertyReplace`：对 `#...#` / `$...$` 逐点做下标替换。
     *
     * **照抄它的两个怪异之处**：① 替换后循环仍用旧的 `startIndex`/`endIndex` 继续扫（长度变化不重算）；
     * ② 替换的是 `#` 与 `$` 之间**整段文本**（不只是属性名），所以 `#x#` 里的 `x` 若无 `[]` 则原样返回。
     */
    private fun iteratePropertyReplace(text: String, iterate: IbatisIterateContext): String {
        val buffer = StringBuilder(text)
        for (marker in arrayOf("#", "$")) {
            var startIndex = 0
            var endIndex = -1
            while (startIndex > -1 && startIndex < buffer.length) {
                startIndex = buffer.indexOf(marker, endIndex + 1)
                endIndex = buffer.indexOf(marker, startIndex + 1)
                if (startIndex > -1 && endIndex > -1) {
                    val replacement = runCatching {
                        iterate.addIndexToTagProperty(buffer.substring(startIndex + 1, endIndex))
                    }.getOrElse { return buffer.toString() }
                    buffer.replace(startIndex + 1, endIndex, replacement)
                }
            }
        }
        return buffer.toString()
    }

    /** 引擎 `DynamicSql.processBodyChildren` 的 `child instanceof SqlTag` 分支（do-while 结构逐条照搬）。 */
    private fun processTag(ctx: IbatisTagContext, params: Any?, tag: IbatisTagNode, out: StringBuilder) {
        var response = INCLUDE_BODY
        do {
            val body = StringBuilder()
            response = doStartFragment(ctx, params, tag)
            if (response != SKIP_BODY) {
                processBodyChildren(ctx, params, tag.children, body)
                response = doEndFragment(ctx, params, tag, body)
                doPrepend(ctx, params, tag, body)
                if (response != SKIP_BODY && body.isNotEmpty()) out.append(body)
            }
        } while (response == REPEAT_BODY)

        ctx.popRemoveFirstPrependMarker(tag)
        val iterate = ctx.peekIterateContext()
        if (iterate != null && iterate.tag === tag) {
            ctx.setAttribute(tag, null)
            ctx.popIterateContext()
        }
    }

    private fun doStartFragment(ctx: IbatisTagContext, params: Any?, tag: IbatisTagNode): Int =
        when (tag.kind) {
            IbatisTagKind.DYNAMIC -> {
                ctx.pushRemoveFirstPrependMarker(tag)
                INCLUDE_BODY
            }
            IbatisTagKind.ITERATE -> startIterate(ctx, params, tag)
            else -> {
                // 引擎 ConditionalTagHandler.doStartFragment：先压 marker，再判条件
                ctx.pushRemoveFirstPrependMarker(tag)
                if (conditionEvaluator(ctx).isCondition(tag.kind, ctx, tag, params)) INCLUDE_BODY else SKIP_BODY
            }
        }

    /** 引擎 `IterateTagHandler.doStartFragment`（`prop` 会被改写为加下标形态，并用于 `setProperty`）。 */
    private fun startIterate(ctx: IbatisTagContext, params: Any?, tag: IbatisTagNode): Int {
        val existing = ctx.getAttribute(tag)
        if (existing != null) {
            if (tag.removeFirstPrepend == RFP_ITERATE) ctx.reEnableRemoveFirstPrepend()
            return if (existing.hasNext()) INCLUDE_BODY else SKIP_BODY
        }
        val parentIterate = ctx.peekIterateContext()
        ctx.pushRemoveFirstPrependMarker(tag)
        var prop = tag.property
        val collection: Any?
        if (prop != null && prop != "") {
            if (parentIterate != null && parentIterate.allowNext) {
                parentIterate.next()
                parentIterate.allowNext = false
                if (!parentIterate.hasNext()) parentIterate.isFinal = true
            }
            if (parentIterate != null) prop = parentIterate.addIndexToTagProperty(prop)
            collection = IbatisProbe.getObject(params, prop)
        } else {
            collection = params
        }
        val iterate = try {
            IbatisIterateContext(collection, tag, parentIterate)
        } catch (e: IbatisRenderException) {
            // 抛点已知标签与 property——包成可分类异常（券 D：ITERATE_NOT_COLLECTION 分档 + property 透出）
            throw IbatisIterateNotCollectionException(tag.property, e.message ?: "iterate 取值不是集合")
        }
        iterate.property = prop ?: ""
        ctx.setAttribute(tag, iterate)
        ctx.pushIterateContext(iterate)
        return if (iterate.hasNext()) INCLUDE_BODY else SKIP_BODY
    }

    private fun doEndFragment(ctx: IbatisTagContext, params: Any?, tag: IbatisTagNode, body: StringBuilder): Int {
        if (tag.kind == IbatisTagKind.ITERATE) return endIterate(ctx, tag, body)
        if (tag.kind != IbatisTagKind.DYNAMIC && tag.kind != IbatisTagKind.ITERATE) {
            // 引擎 ConditionalTagHandler.doEndFragment：先推进 iterate，再走基类 close 逻辑
            val iterate = ctx.peekIterateContext()
            if (iterate != null && iterate.allowNext) {
                iterate.next()
                iterate.allowNext = false
                if (!iterate.hasNext()) iterate.isFinal = true
            }
        }
        return baseEndFragment(tag, body)
    }

    /** 引擎 `BaseTagHandler.doEndFragment`：非 iterate 标签的 `close` 在原体非空时**原样追加**。 */
    private fun baseEndFragment(tag: IbatisTagNode, body: StringBuilder): Int {
        if (tag.hasClose && tag.kind != IbatisTagKind.ITERATE && hasContent(body)) {
            body.append(tag.close)
        }
        return INCLUDE_BODY
    }

    /** 引擎 `IterateTagHandler.doEndFragment`。 */
    private fun endIterate(ctx: IbatisTagContext, tag: IbatisTagNode, body: StringBuilder): Int {
        val iterate = ctx.getAttribute(tag)
            ?: throw IbatisRenderException("iterate 迭代上下文缺失：property='${tag.property}'")
        if (!iterate.hasNext() && !iterate.isFinal) return baseEndFragment(tag, body)
        if (iterate.allowNext) iterate.next()
        if (hasContent(body)) {
            if (iterate.someSubElementsHaveContent) {
                if (tag.hasConjunction) body.insert(0, tag.conjunction)
            } else {
                iterate.isPrependEnabled = true
                if (tag.hasOpen) body.insert(0, tag.open)
            }
            iterate.someSubElementsHaveContent = true
        }
        if (iterate.isLast() && iterate.someSubElementsHaveContent && tag.hasClose) {
            body.append(tag.close)
        }
        iterate.allowNext = true
        return if (iterate.isFinal) baseEndFragment(tag, body) else REPEAT_BODY
    }

    private fun doPrepend(ctx: IbatisTagContext, params: Any?, tag: IbatisTagNode, body: StringBuilder) {
        if (tag.kind == IbatisTagKind.ITERATE) {
            val iterate = ctx.getAttribute(tag) ?: return
            if (iterate.isPrependEnabled) {
                basePrepend(ctx, tag, body)
                iterate.isPrependEnabled = false
            }
            return
        }
        basePrepend(ctx, tag, body)
    }

    /**
     * 引擎 `BaseTagHandler.doPrepend`——**逐字 insert，不补空格**。
     *
     * `open`/`close` 对 iterate 由 [endIterate] 处理，故这里显式排除 iterate（与引擎的
     * `!(handler instanceof IterateTagHandler)` 同口径）。
     */
    private fun basePrepend(ctx: IbatisTagContext, tag: IbatisTagNode, body: StringBuilder) {
        if (tag.hasOpen && tag.kind != IbatisTagKind.ITERATE && hasContent(body)) {
            body.insert(0, tag.open)
        }
        if (tag.hasPrepend && hasContent(body)) {
            if (tag.parent != null && ctx.peekParentRemoveFirstPrepend()) {
                ctx.disableParentRemoveFirstPrepend()
            } else {
                body.insert(0, tag.prepend)
            }
        }
    }

    private fun conditionEvaluator(ctx: IbatisTagContext): SqlMapConditionEvaluator =
        SqlMapConditionEvaluator { ctx.warnings.add(it) }

    // ------------------------------------------------------------------ 工具

    /** 券 D：抛错 → 失败原因分类（枚举三个常量的唯一产出点，`EnumReachabilityGuardTest` 口径）。 */
    private fun classifyFailure(error: Throwable): IbatisRenderFailureKind = when (error) {
        is IbatisIterateNotCollectionException -> IbatisRenderFailureKind.ITERATE_NOT_COLLECTION
        is IbatisRenderException -> IbatisRenderFailureKind.RENDER_ERROR
        else -> IbatisRenderFailureKind.PARSE_ERROR
    }

    /** 券 D：按失败形态分档的降级告警文案（bundle 三键，中英双份；禁出现「按原文保留 # 直替」）。 */
    private fun degradedWarning(
        kind: IbatisRenderFailureKind,
        firstUnrendered: IbatisUnrenderedNode?,
        error: Throwable,
    ): String = when (kind) {
        IbatisRenderFailureKind.ITERATE_NOT_COLLECTION ->
            MpSqlVisualizerBundle.message(
                "ibatis.render.degrade.iterateNotCollection",
                (error as? IbatisIterateNotCollectionException)?.property ?: "",
            )
        else -> {
            val tag = firstUnrendered?.tagName
            if (tag != null) {
                MpSqlVisualizerBundle.message("ibatis.render.degrade.abortedAtTag", tag)
            } else {
                MpSqlVisualizerBundle.message("ibatis.render.degrade.abortedUnknown")
            }
        }
    }

    /**
     * 券 C：语句体的参数位点数 = string-aware `?`（复用 `sql/PlaceholderPositions.kt` 的既有口径，
     * 字符串/注释里的 `?` 不算）+ 内联 `#x#` 对数（`parameterMap` 守卫分支 `#` 原样保留，成对出现
     * 即一个参数位点；`##` 转义字面量与字符串内的 `#` 不算）。
     */
    private fun countParameterSites(text: String): Int =
        placeholderPositions(text).size + inlineHashPairCount(text)

    /** 内联 `#x#` 参数对计数（string-aware；`##` 转义按两个字面量 `#` 跳过；未闭合残段不成对不计数）。 */
    private fun inlineHashPairCount(text: String): Int {
        var inString = false
        var hashCount = 0
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
                    c == '#' -> {
                        if (i + 1 < text.length && text[i + 1] == '#') {
                            i += 2
                            continue
                        }
                        hashCount++
                    }
                }
            }
            i++
        }
        return hashCount / 2
    }

    /** 引擎的 `bodyContent.toString().trim().length() > 0`——Java `trim()` 剔的是 `<= ' '`，非 `isWhitespace`。 */
    private fun hasContent(body: StringBuilder): Boolean =
        body.isNotEmpty() && body.toString().trim { it <= ' ' }.isNotEmpty()

    /** 空白折叠（仅用于 `compactSql`；不改 [IbatisRenderResult.sql] 的逐字节形态）。 */
    private fun compact(sql: String): String =
        sql.split(' ', '\r', '\n', '\t').filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        /** 引擎 `SqlTagHandler.INCLUDE_BODY`。 */
        const val INCLUDE_BODY = 0

        /** 引擎 `SqlTagHandler.SKIP_BODY`。 */
        const val SKIP_BODY = 1

        /** 引擎 `SqlTagHandler.REPEAT_BODY`。 */
        const val REPEAT_BODY = 2

        const val RFP_ITERATE = "iterate"
    }
}
