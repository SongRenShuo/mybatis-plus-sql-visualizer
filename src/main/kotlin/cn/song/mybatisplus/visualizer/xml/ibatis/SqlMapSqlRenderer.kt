package cn.song.mybatisplus.visualizer.xml.ibatis

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

        val body = runCatching { renderBody(statement, params, mapped, warn) }
            .getOrElse { error ->
                warn("渲染降级（按原文保留 # 直替，参数名不可知）：${error.message}")
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
    ): String {
        if (!statement.isDynamic) return renderStatic(statement, mapped, warn)
        val ctx = IbatisTagContext()
        // 顶层片段写进本方法的缓冲（引擎里就是 SqlTagContext 自己的 StringWriter）；
        // 嵌套标签各自用独立缓冲，处理完 doEndFragment/doPrepend 后才条件性地写回父缓冲。
        val out = StringBuilder()
        processBodyChildren(ctx, params, statement.body, out)
        mapped.addAll(ctx.parameterMappings)
        ArrayList(ctx.warnings).forEach(warn)
        return out.toString()
    }

    /** 非动态语句：`XMLSqlSource.getSql()` → `RawSql` → `applyInlineParameterMap` 的口径。 */
    private fun renderStatic(
        statement: IbatisStatement,
        mapped: MutableList<IbatisParamMapping>,
        warn: (String) -> Unit,
    ): String {
        val raw = statement.body
            .filterIsInstance<IbatisTextNode>()
            .joinToString(separator = "") { it.rawText }
        val declared = statement.parameterMap
        if (declared != null) {
            // 引擎的 if (parameterMap == null) 守卫：原样保留 `#`，只做 \r\n 归一（制表符保留）
            declared.parameters.forEach { parameter ->
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
        val iterate = IbatisIterateContext(collection, tag, parentIterate)
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
