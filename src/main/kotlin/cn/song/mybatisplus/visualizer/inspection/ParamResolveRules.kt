package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.xml.OgnlEvaluator

/**
 * F-02 参数可解析性检查的**纯逻辑内核**（闲时任务册 §一之二 F-02）：三条规则的「文本/数据 → 结论」
 * 判定，零平台依赖（普通 JUnit 可直测，testFast 快桶）。薄壳接线见 [ParamPropertyInspection] /
 * [ParamReferenceInspection] / [MissingSqlFragmentInspection]。
 *
 * 三条规则的 0 误报口径（误报 0 才允许默认开，本批三条一律 `enabledByDefault=false` 起步）：
 *  - **规则① 属性缺失**：只判「root 是方法参数且其类型可解析为用户 DTO」的 `#{root.prop}`，
 *    只判属性路径**首段**；Map 型参数（`#{map.key}`）与不可解析类型一律不判——静态判不了就是误报温床；
 *  - **规则② 声明未引用**：只对显式 `@Param` 判；OGNL 属性（test/value/collection）与词边界
 *    兜底都算引用；`param1..N` 位置别名算引用；
 *  - **规则③ 片段缺失**：动态 refid（含 `${}`）不判；跨命名空间目标文件不在工程内不判；
 *    裸 refid 允许同 namespace 其它文件提供片段（MyBatis 键 = namespace.id，同 namespace 跨文件合法）。
 */
object ParamResolveRules {

    /** 合法属性段（`p.x[0]` 判 `x`；OGNL 运算形态不判，避免误报）。 */
    private val PROPERTY_SEGMENT = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    // ------------------------------------------------------------------
    // 规则①：#{root.prop} 的属性段在参数类型里不存在
    // ------------------------------------------------------------------

    /**
     * `#{root.prop}` 属性段缺失判定。
     *
     * @param text             被分析文本（语句/片段的 SQL 文本）
     * @param paramMemberNames root → 参数类型的成员名集合；**只收可判定的 root**（@Param/多参上下文里
     *                         类型可解析为用户 DTO 的参数）——Map / JDK 类型 / 解析失败不在表里，即不判
     * @param declaredRoots    语句内声明的名字（`<bind name>` / `<foreach item|index>`）——与参数同名的
     *                         bind 会遮蔽参数语义，遇之不判
     * @return 属性段不在成员集合里的 token 结论（偏移为 token 全文）
     */
    fun unknownParamPropertyFindings(
        text: String,
        paramMemberNames: Map<String, Set<String>>,
        declaredRoots: Set<String> = emptySet(),
    ): List<SqlInspectionFinding> = SqlInspectionText.hashTokens(text)
        .filter { token ->
            // 只判 `root.prop…` 形态（root 后紧跟点）；`arr[0].x` 一类下标根不是属性路径
            token.root.isNotEmpty() &&
                token.expression.startsWith("${token.root}.") &&
                token.root in paramMemberNames &&
                token.root !in declaredRoots
        }
        .mapNotNull { token ->
            val segment = token.expression
                .substringAfter('.')
                .substringBefore('[')
                .trim()
            if (!PROPERTY_SEGMENT.matches(segment)) return@mapNotNull null
            if (segment in paramMemberNames.getValue(token.root)) return@mapNotNull null
            SqlInspectionFinding(
                rule = SqlInspectionRule.PARAM_PROPERTY,
                messageKey = "inspection.sqlv.paramProperty.problem",
                messageArgs = listOf(token.expression, token.root),
                start = token.start,
                length = token.raw.length,
                token = token.raw,
            )
        }

    // ------------------------------------------------------------------
    // 规则②：@Param 显式声明但语句从未引用
    // ------------------------------------------------------------------

    /**
     * 「声明了却没被引用」的参数名。
     *
     * @param paramNames      显式 `@Param` 值（**按方法参数位置序**；未注解位置为 null——位置必须
     *                        占住，`param1..N` 别名才对得上号）
     * @param referencedRoots 语句里出现过的根名（`#{}`/`${}` token 根 + OGNL 属性根 + `param1..N` 展开）
     * @param statementText   语句源文本——**词边界兜底面**：OGNL 解析失败等未知形态只要在文本里
     *                        出现过参数名（整词），就不报（宁漏报不误报）
     */
    fun unreferencedParamNames(
        paramNames: List<String?>,
        referencedRoots: Set<String>,
        statementText: String = "",
    ): List<String> = paramNames.withIndex().filterNot { (index, name) ->
        if (name == null || name.isBlank()) return@filterNot true
        if (name in referencedRoots) return@filterNot true
        // MyBatis 位置别名：paramN 与第 N 个参数等价，用了别名即算引用
        if ("param${index + 1}" in referencedRoots) return@filterNot true
        wordContained(name, statementText)
    }.mapNotNull { it.value }

    /** 整词出现判定（两侧不是标识符字符）。 */
    private fun wordContained(name: String, text: String): Boolean =
        text.isNotEmpty() &&
            Regex("""(?<![A-Za-z0-9_$])${Regex.escape(name)}(?![A-Za-z0-9_$])""").containsMatchIn(text)

    /**
     * 语句文本里出现过的**根名**全集：`#{}` / `${}` token 根 + 属性表达式（test/value/collection）
     * 的 OGNL 根。供规则②的薄壳收集引用面用。
     */
    fun referencedRootsOf(tokenRoots: Collection<String>, attributeExpressions: Collection<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (root in tokenRoots) {
            out += root
            out += root.substringBefore('.')
        }
        for (expr in attributeExpressions) {
            out += runCatching { OgnlEvaluator.rootVariables(expr) }.getOrDefault(emptySet())
        }
        return out
    }

    // ------------------------------------------------------------------
    // 规则③：<include refid> 片段解析
    // ------------------------------------------------------------------

    /** refid 解析结论。 */
    enum class RefidVerdict {
        /** 命中片段（本文件 / 同 namespace 其它文件 / 限定引用目标文件）。 */
        RESOLVED,

        /** 可判定地缺失（工程内可见面全查过仍没有）→ 报。 */
        MISSING,

        /** 判不了（动态 refid / 目标 namespace 的 mapper 文件不在工程可见面）→ 沉默。 */
        UNDECIDED,
    }

    /**
     * refid 解析判定。
     *
     * @param refid               refid 原文（含动态形态原样传入）
     * @param currentNamespace    当前 mapper 根 namespace（裸 refid 与同 namespace 限定引用的查表键）
     * @param localSqlIds         本文件全部 `<sql id>`
     * @param sameNamespaceSqlIds 同 namespace **其它** mapper 文件的 `<sql id>`（MyBatis 片段表键为
     *                            `namespace.id`，同 namespace 跨文件合法；工程可见面扫不出时传空集）
     * @param qualifiedLookup     限定引用（`ns.id`）目标文件查表：true=命中 / false=文件可见但无此片段 /
     *                            null=目标文件不在工程可见面（判不了）
     */
    fun refidVerdict(
        refid: String,
        currentNamespace: String?,
        localSqlIds: Set<String>,
        sameNamespaceSqlIds: Set<String>,
        qualifiedLookup: (namespace: String, id: String) -> Boolean?,
    ): RefidVerdict {
        val ref = refid.trim()
        if (ref.isEmpty() || ref.contains("\${")) return RefidVerdict.UNDECIDED
        val dot = ref.lastIndexOf('.')
        if (dot > 0) {
            val ns = ref.substring(0, dot).trim()
            val id = ref.substring(dot + 1).trim()
            if (ns.isEmpty() || id.isEmpty()) return RefidVerdict.UNDECIDED
            // 限定引用指向当前 namespace：与裸 refid 同一张片段表（本文件 + 同 namespace 其它文件）
            if (currentNamespace != null && ns == currentNamespace) {
                return if (id in localSqlIds || id in sameNamespaceSqlIds) RefidVerdict.RESOLVED else RefidVerdict.MISSING
            }
            return when (qualifiedLookup(ns, id)) {
                true -> RefidVerdict.RESOLVED
                false -> RefidVerdict.MISSING
                null -> RefidVerdict.UNDECIDED
            }
        }
        return when {
            ref in localSqlIds -> RefidVerdict.RESOLVED
            ref in sameNamespaceSqlIds -> RefidVerdict.RESOLVED
            else -> RefidVerdict.MISSING
        }
    }
}
