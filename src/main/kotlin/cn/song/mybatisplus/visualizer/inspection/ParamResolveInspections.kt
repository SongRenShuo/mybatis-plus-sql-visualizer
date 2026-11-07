package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * F-02 规则①：**`#{root.prop}` 的属性段在参数类型里不存在**（闲时任务册 §一之二）。
 *
 * 判定在 [ParamResolveRules.unknownParamPropertyFindings]；本类只做 PSI 取数：
 *  - 语句 `id` → namespace 接口方法 → 各参数的**可判定类型**（用户 DTO 且成员集非空）→
 *    `root → 成员名` 表（[paramMemberNamesOf]）；
 *  - Map / Collection 型参数、JDK 类型、解析失败**一律不进表**——`#{map.key}` 的 key 静态不可知，
 *    这正是竞品同域检查的误报重灾区（0 误报口径）；
 *  - `ew` / `_parameter` 等框架保留根不判（[SqlInspectionText.RESERVED_ROOTS]）。
 *
 * 只判属性路径**首段**（`p.a.b` 判 `a`）——深层逐段解析静态不可靠，与 resultMap 规则同口径。
 */
class ParamPropertyInspection : MapperSqlInspectionBase() {

    override val rule: SqlInspectionRule = SqlInspectionRule.PARAM_PROPERTY

    override fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) {
        if (tag.name !in MapperSqlElementSupport.STATEMENT_TAGS) return
        val resolved = MapperSqlElementSupport.parameterRootsWithMethod(tag) ?: return
        val memberNames = paramMemberNamesOf(resolved.second)
        if (memberNames.isEmpty()) return
        // bind/foreach 声明与参数同名时会遮蔽参数语义，遇之不判
        val declared = MapperSqlElementSupport.bindNames(tag) + MapperSqlElementSupport.foreachNames(tag)
        for (slice in MapperSqlElementSupport.textSlices(tag)) {
            ParamResolveRules.unknownParamPropertyFindings(slice.text, memberNames, declared).forEach { finding ->
                reportInText(holder, slice, finding)
            }
        }
    }

    override fun inspectAnnotation(annotation: PsiAnnotation, holder: ProblemsHolder) {
        val method = MapperSqlElementSupport.enclosingMethod(annotation) ?: return
        val memberNames = paramMemberNamesOf(method)
        if (memberNames.isEmpty()) return
        val text = MapperSqlElementSupport.annotationSqlText(annotation, annotation.project) ?: return
        ParamResolveRules.unknownParamPropertyFindings(text, memberNames).forEach { finding ->
            reportOnAnnotation(holder, annotation, finding)
        }
    }

    /** root → 参数类型成员名；只收「用户 DTO 且成员非空」的参数（Map/Collection/JDK/不可解析全部排除）。 */
    private fun paramMemberNamesOf(method: PsiMethod): Map<String, Set<String>> {
        val parameters = method.parameterList.parameters
        if (parameters.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Set<String>>()
        for (param in parameters) {
            // 框架保留根不判（ew 是 MP Wrapper 的 OGNL 语义，成员静态形态与 getter 不对应）
            val rootName = (paramAnnotationValue(param) ?: param.name)?.takeIf { it.isNotBlank() } ?: continue
            if (rootName in SqlInspectionText.RESERVED_ROOTS) continue
            val psiClass = (param.type as? PsiClassType)?.resolve() ?: continue
            if (!isJudgeableDto(psiClass)) continue
            val members = MapperSqlElementSupport.memberNames(psiClass)
            if (members.isNotEmpty()) out[rootName] = members
        }
        // paramN 位置别名（对齐 SqlInspectionText.paramRoots 的门槛：多参或任一 @Param 才有别名）
        if (out.isNotEmpty() && (parameters.size > 1 || parameters.any { paramAnnotationValue(it) != null })) {
            parameters.forEachIndexed { index, param ->
                val rootName = (paramAnnotationValue(param) ?: param.name) ?: return@forEachIndexed
                out[rootName]?.let { members -> out["param${index + 1}"] = members }
            }
        }
        return out
    }

    /** 可判定 DTO：用户项目类、非枚举、不继承 Map/Collection（键与元素动态，判了必误报）。 */
    private fun isJudgeableDto(psiClass: PsiClass): Boolean {
        val qn = psiClass.qualifiedName ?: return false
        if (qn.startsWith("java.") || qn.startsWith("javax.")) return false
        if (psiClass.isEnum) return false
        return !inheritsDynamicKeyed(psiClass, 0)
    }

    /** 类（含父类链/接口）是否继承 Map/Collection——动态键/元素容器，属性判定不适用。 */
    private fun inheritsDynamicKeyed(cls: PsiClass?, depth: Int): Boolean {
        if (cls == null || depth > MAX_HIERARCHY) return false
        if (cls.qualifiedName == "java.util.Map" || cls.qualifiedName == "java.util.Collection") return true
        return cls.interfaces.any { inheritsDynamicKeyed(it, depth + 1) } ||
            inheritsDynamicKeyed(runCatching { cls.superClass }.getOrNull(), depth + 1)
    }

    private companion object {
        const val MAX_HIERARCHY = 16
    }
}

/**
 * F-02 规则②：**`@Param("x")` 显式声明但对应语句从未引用**（声明/引用两侧不一致——
 * XML 侧写错名字由 SqlvMissingParameter 报，本规则补 Java 侧视角）。
 *
 * 引用面（算「被引用」）：`#{}` / `${}` token 根、OGNL 属性（`test` / `<bind value>` /
 * `<foreach collection>`）、`param1..N` 位置别名、语句文本中的整词出现（兜底面，宁漏报不误报）。
 *
 * 0 误报闸门：语句含 `<include>`（片段文本不在 PSI，引用面看不见）→ 整句沉默；OGNL 取根失败
 * 由词边界兜底；非显式 `@Param` 的参数不判（参数名编译不可见 / 单参属性自由）。
 */
class ParamReferenceInspection : MapperSqlInspectionBase() {

    override val rule: SqlInspectionRule = SqlInspectionRule.PARAM_REFERENCE

    override fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) {
        if (tag.name !in MapperSqlElementSupport.STATEMENT_TAGS) return
        // P-1 取证结论：PSI 文本不含 include 展开内容——片段里的参数引用看不见，必须沉默
        if (hasDescendantTag(tag, "include")) return
        val method = MapperSqlElementSupport.parameterRootsWithMethod(tag)?.second ?: return
        val explicitNames = explicitParamNamesByPosition(method) ?: return
        val referenced = referencedRootsOf(tag)
        for (name in ParamResolveRules.unreferencedParamNames(explicitNames, referenced, tag.text)) {
            holder.registerProblem(
                tag,
                MpSqlVisualizerBundle.message("inspection.sqlv.paramRef.problem", name),
                com.intellij.codeInspection.ProblemHighlightType.WARNING,
            )
        }
    }

    override fun inspectAnnotation(annotation: PsiAnnotation, holder: ProblemsHolder) {
        val method = MapperSqlElementSupport.enclosingMethod(annotation) ?: return
        val explicitNames = explicitParamNamesByPosition(method) ?: return
        val text = MapperSqlElementSupport.annotationSqlText(annotation, annotation.project) ?: return
        // 注解 SQL 没有 OGNL 属性面；token 根 + 整词兜底即全部引用面
        val referenced = ParamResolveRules.referencedRootsOf(
            SqlInspectionText.hashTokens(text).map { it.root } + SqlInspectionText.dollarTokens(text).map { it.root },
            emptyList(),
        )
        for (name in ParamResolveRules.unreferencedParamNames(explicitNames, referenced, text)) {
            holder.registerProblem(
                MapperSqlElementSupport.annotationValueElement(annotation),
                MpSqlVisualizerBundle.message("inspection.sqlv.paramRef.problem", name),
                com.intellij.codeInspection.ProblemHighlightType.WARNING,
            )
        }
    }

    /** 逐位置显式 `@Param` 值（未注解位置 null，位置必须占住供 `param1..N` 别名对号）；全无 `@Param` → null（不判）。 */
    private fun explicitParamNamesByPosition(method: PsiMethod): List<String?>? {
        val parameters = method.parameterList.parameters
        if (parameters.isEmpty()) return null
        val values = parameters.map { paramAnnotationValue(it) }
        return if (values.all { it == null }) null else values
    }

    /** 语句里的引用根全集：token 根 + OGNL 属性（test / bind value / foreach collection）。 */
    private fun referencedRootsOf(tag: XmlTag): Set<String> {
        val tokenRoots = ArrayList<String>()
        for (slice in MapperSqlElementSupport.textSlices(tag)) {
            tokenRoots += SqlInspectionText.hashTokens(slice.text).map { it.root }
            tokenRoots += SqlInspectionText.dollarTokens(slice.text).map { it.root }
        }
        val attrExprs = ArrayList<String>()
        collectOgnlAttributes(tag, attrExprs)
        return ParamResolveRules.referencedRootsOf(tokenRoots, attrExprs)
    }

    /** 递归收集动态属性表达式（`test`、`<bind value>`、`<foreach collection>`）。 */
    private fun collectOgnlAttributes(tag: XmlTag, sink: MutableList<String>) {
        tag.getAttributeValue("test")?.let { sink += it }
        when (tag.name) {
            "bind" -> tag.getAttributeValue("value")?.let { sink += it }
            "foreach" -> tag.getAttributeValue("collection")?.let { sink += it }
        }
        for (child in tag.subTags) collectOgnlAttributes(child, sink)
    }

    private fun hasDescendantTag(tag: XmlTag, name: String): Boolean {
        if (tag.name == name) return true
        return tag.subTags.any { hasDescendantTag(it, name) }
    }
}

/**
 * F-02 规则③：**`<include refid="…">` 指向的 `<sql>` 片段解析不到**。
 *
 * 判定在 [ParamResolveRules.refidVerdict]；本类只做查表取数：
 *  - 本文件片段表（PSI 直接读 `<sql id>`）；
 *  - 同 namespace 其它文件的片段表（**懒扫描**：仅当本文件未命中才扫工程可见面——无 include 的
 *    文件零成本；MyBatis 片段表键 = `namespace.id`，同 namespace 跨文件合法，必须查过才能下结论）；
 *  - 限定引用（`ns.id`）→ 目标 namespace 的文件清单（复用 [MybatisXmlTargets.findMapperFileByNamespace]）；
 *    目标 namespace 不在工程可见面 → UNDECIDED 沉默。
 *
 * 不判：动态 refid（含 `${property}`）；JAR/依赖内的 mapper 片段工程索引看不到（登记的漏报面，
 * 默认 disabledByDefault=true 的原因之一）。
 */
class MissingSqlFragmentInspection : MapperSqlInspectionBase() {

    override val rule: SqlInspectionRule = SqlInspectionRule.MISSING_SQL_FRAGMENT

    override fun inspectMapperTag(tag: XmlTag, holder: ProblemsHolder) {
        // 每文件判一次：挂在根 <mapper> 上，遍历全部 <include>
        if (tag.name != "mapper") return
        val project = tag.project
        if (project.isDefault || DumbService.isDumb(project)) return
        val namespace = tag.getAttributeValue("namespace")?.takeIf { it.isNotBlank() } ?: return
        val localSqlIds = tag.subTags.filter { it.name == "sql" }
            .mapNotNull { it.getAttributeValue("id")?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val includes = collectIncludes(tag)
        if (includes.isEmpty()) return

        // 懒扫描：裸 refid / 同 namespace 限定引用本文件未命中时，才扫同 namespace 其它文件的片段表
        val needsSibling = includes.any { (refid) ->
            val ref = refid.trim()
            ref.isNotEmpty() && !ref.contains("\${") && (
                (!ref.contains('.') && ref !in localSqlIds) ||
                    (ref.contains('.') && ref.substringBeforeLast('.').trim() == namespace &&
                        ref.substringAfterLast('.').trim() !in localSqlIds)
                )
        }
        val siblingIds = if (needsSibling) {
            sameNamespaceSqlIds(project, namespace, tag.containingFile as? XmlFile)
        } else {
            emptySet()
        }

        for ((refid, valueElement) in includes) {
            val verdict = ParamResolveRules.refidVerdict(
                refid, namespace, localSqlIds, siblingIds,
            ) { ns, id ->
                // 目标 namespace 的片段表（全部可见文件并集；namespace 不可见 → null = 判不了）
                targetNamespaceSqlIds(project, ns)?.contains(id)
            }
            if (verdict != ParamResolveRules.RefidVerdict.MISSING) continue
            holder.registerProblem(
                valueElement ?: tag,
                messageOf(
                    SqlInspectionFinding(
                        rule = rule,
                        messageKey = "inspection.sqlv.fragment.problem",
                        messageArgs = listOf(refid.trim()),
                    ),
                ),
                com.intellij.codeInspection.ProblemHighlightType.WARNING,
            )
        }
    }

    /** 递归收集 `<include>` 的（refid 原文, 值元素）。 */
    private fun collectIncludes(tag: XmlTag): List<Pair<String, PsiElement?>> {
        val out = ArrayList<Pair<String, PsiElement?>>()
        fun walk(node: XmlTag) {
            if (node.name == "include") {
                val refid = node.getAttributeValue("refid")
                if (refid != null) out += refid to (node.getAttribute("refid")?.valueElement)
            }
            for (child in node.subTags) walk(child)
        }
        walk(tag)
        return out
    }

    /**
     * 同 namespace 其它 mapper 文件的片段 id 并集（不含当前文件；当前文件由 PSI 的 localSqlIds 覆盖，
     * 与索引可见性解耦——headless/新落盘文件索引未就绪时不产生假缺失）。
     */
    private fun sameNamespaceSqlIds(project: Project, namespace: String, current: XmlFile?): Set<String> {
        val out = HashSet<String>()
        for (file in mapperFilesByNamespace(project, namespace)) {
            if (file === current) continue
            file.rootTag?.subTags
                ?.filter { it.name == "sql" }
                ?.forEach { it.getAttributeValue("id")?.trim()?.takeIf(String::isNotEmpty)?.let(out::add) }
        }
        return out
    }

    /** 目标 namespace 的片段 id 并集；工程可见面无该 namespace 的 mapper 文件 → null（判不了）。 */
    private fun targetNamespaceSqlIds(project: Project, namespace: String): Set<String>? {
        // 单文件 namespace（绝大多数）走既有单槽缓存路径，避免重扫
        MybatisXmlTargets.findMapperFileByNamespace(project, namespace)?.let { file ->
            val ids = file.rootTag?.subTags
                ?.filter { it.name == "sql" }
                ?.mapNotNull { it.getAttributeValue("id")?.trim()?.takeIf(String::isNotEmpty) }
                ?.toSet()
            return ids
        }
        val files = mapperFilesByNamespace(project, namespace)
        if (files.isEmpty()) return null
        val out = HashSet<String>()
        for (file in files) {
            file.rootTag?.subTags
                ?.filter { it.name == "sql" }
                ?.forEach { it.getAttributeValue("id")?.trim()?.takeIf(String::isNotEmpty)?.let(out::add) }
        }
        return out
    }

    /** 工程可见面内、根 namespace 匹配的 mapper XML 文件（索引直查，无缓存——inspection 调用频度低）。 */
    private fun mapperFilesByNamespace(project: Project, namespace: String): List<XmlFile> {
        val xmlFileType = runCatching { FileTypeManager.getInstance().getStdFileType("XML") }.getOrNull() ?: return emptyList()
        return runCatching {
            FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
                .mapNotNull { vf -> PsiManager.getInstance(project).findFile(vf) as? XmlFile }
                .filter { file ->
                    file.rootTag?.name == "mapper" && file.rootTag?.getAttributeValue("namespace") == namespace
                }
        }.getOrDefault(emptyList())
    }
}

/** F-02 薄壳的 PSI 取数小件：显式 `@Param` 值提取（与 [MapperSqlElementSupport.methodParamRoots] 同口径）。 */
private fun paramAnnotationValue(param: PsiParameter): String? {
    val annotation = param.annotations.firstOrNull { it.qualifiedName == "org.apache.ibatis.annotations.Param" }
        ?: param.annotations.firstOrNull { it.qualifiedName?.substringAfterLast('.') == "Param" }
        ?: return null
    val value = annotation.findAttributeValue("value")
    val literal = (value as? PsiLiteralExpression)?.value as? String
    if (literal != null) return literal.takeIf { it.isNotBlank() }
    // 沙盒下注解类不可解析 → 常量求值兜底（与 MapperSqlElementSupport.stringConstant 同法）
    return runCatching {
        JavaPsiFacade.getInstance(annotation.project)
            .constantEvaluationHelper.computeConstantExpression(value)
    }.getOrNull()?.toString()?.takeIf { it.isNotBlank() }
}
