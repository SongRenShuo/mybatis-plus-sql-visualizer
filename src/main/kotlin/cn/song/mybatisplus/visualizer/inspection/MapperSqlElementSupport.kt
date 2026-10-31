package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * PSI ↔ 纯逻辑内核的**适配层**（NEW-26）。
 *
 * 这是立项册 §二 说的「必须加的一层适配」：判定本体在 [SqlInspectionText]（纯文本 → 结论），
 * 本对象只做两件事——
 *  1. **取数**：从 mapper XML 里按文本节点取 SQL 并保留偏移（不拼整份文件文本，避免 `<bind>`/`test`
 *     的属性值混进扫描面）；
 *  2. **补上下文**：语句 `id` + 根 `namespace` → mapper 接口方法 → 参数名集合（对齐 MyBatis
 *     `ParamNameResolver`）；`<resultMap type>` → 实体类成员名。
 *
 * 线程纪律（P-3）：本对象只在 inspection 的 daemon 回调里被调用（那里已持读锁）；所有索引查询先过
 * `DumbService.isDumb`——**判不了就沉默**，绝不发虚假告警。
 */
object MapperSqlElementSupport {

    /** mapper 语句标签集合（与 [XmlMapperParser.STATEMENT_TAGS] 同源，避免两处漂移）。 */
    val STATEMENT_TAGS: Set<String> = XmlMapperParser.STATEMENT_TAGS

    /** 会被当作 SQL 体扫描的标签：四条语句标签 + `<sql>` 片段。 */
    val SCANNED_TAGS: Set<String> = STATEMENT_TAGS + "sql"

    /** 该标签是否需要作为 SQL 体扫描（语句标签或 `<sql>` 片段）。 */
    fun isScannedTag(tag: XmlTag): Boolean = tag.name in SCANNED_TAGS

    /** 一个文本节点 + 其在所属标签 `text` 中的起始偏移（相对 `XmlTag.textRange.startOffset`）。 */
    data class SqlTextSlice(val node: XmlText, val relativeOffset: Int, val text: String)

    /**
     * 取该标签下**全部文本节点**（递归穿过 `<if>`/`<trim>`/`<foreach>` 等动态标签）。
     *
     * 不取属性值：`<bind value="...">` / `test="..."` 不是 SQL 体，混进来会让 `${}` 规则误报。
     *
     * 用 `XmlText.text`（源文件原文）而不是 `value`（实体解码后的值）：problem 的
     * `rangeInElement` 以**节点原文**为坐标系，用 value 会让含 `&lt;` / CDATA 的语句偏移整体错位。
     * P-2 结论：不做换行/缩进归一化——判定只看 token，偏移必须与源严格对齐。
     */
    fun textSlices(tag: XmlTag): List<SqlTextSlice> {
        val base = tag.textRange.startOffset
        val out = ArrayList<SqlTextSlice>()
        fun walk(element: PsiElement) {
            var child = element.firstChild
            while (child != null) {
                if (child is XmlText) {
                    out += SqlTextSlice(child, child.textRange.startOffset - base, child.text)
                } else if (child is XmlTag) {
                    walk(child)
                }
                child = child.nextSibling
            }
        }
        walk(tag)
        return out
    }

    /** 语句内 `<bind name>` 声明。 */
    fun bindNames(tag: XmlTag): Set<String> =
        descendants(tag, "bind").mapNotNull { it.getAttributeValue("name")?.takeIf(String::isNotBlank) }.toSet()

    /** 语句内 `<foreach item|index>` 声明的名字。 */
    fun foreachNames(tag: XmlTag): Set<String> {
        val out = LinkedHashSet<String>()
        for (node in descendants(tag, "foreach")) {
            node.getAttributeValue("item")?.takeIf(String::isNotBlank)?.let { out += it }
            node.getAttributeValue("index")?.takeIf(String::isNotBlank)?.let { out += it }
        }
        return out
    }

    /** 绑定到静态常量/字面量的 `<bind name>`（`${}` 规则放行用）。 */
    fun staticBindNames(tag: XmlTag): Set<String> =
        descendants(tag, "bind")
            .filter { SqlInspectionText.isStaticBindValue(it.getAttributeValue("value")) }
            .mapNotNull { it.getAttributeValue("name")?.takeIf(String::isNotBlank) }
            .toSet()

    /** 带 `test` 属性的后代标签（OGNL 规则用）。 */
    fun testTags(tag: XmlTag): List<XmlTag> = descendants(tag, null).filter { it.getAttribute("test") != null }

    private fun descendants(tag: XmlTag, name: String?): List<XmlTag> {
        val out = ArrayList<XmlTag>()
        fun walk(element: PsiElement) {
            var child = element.firstChild
            while (child != null) {
                if (child is XmlTag) {
                    if (name == null || child.name == name) out += child
                    walk(child)
                }
                child = child.nextSibling
            }
        }
        walk(tag)
        return out
    }

    // ------------------------------------------------------------------
    // 参数名收集（P-3：dumb 态与解析失败一律 null = 不判定）
    // ------------------------------------------------------------------

    /**
     * 语句 `id` → namespace 接口方法 → `#{}` 合法根名集合。
     *
     * @return null = 不判定（dumb 态 / interface 或方法解析不出 / 单参无 `@Param`）
     */
    fun parameterRoots(tag: XmlTag): Set<String>? {
        val project = tag.project
        if (project.isDefault || DumbService.isDumb(project)) return null
        val root = (tag.containingFile as? XmlFile)?.rootTag ?: return null
        val namespace = root.getAttributeValue("namespace")?.takeIf(String::isNotBlank) ?: return null
        val statementId = tag.getAttributeValue("id")?.takeIf(String::isNotBlank) ?: return null
        val method = runCatching { MybatisXmlTargets.resolveInterfaceMethod(project, namespace, statementId) }
            .getOrNull() ?: return null
        return methodParamRoots(method)
    }

    /** 方法参数 → 合法根名（`@Param` 值优先 + `param1..N`）；单参且无 `@Param` → null（不可判定）。 */
    fun methodParamRoots(method: PsiMethod): Set<String>? {
        val parameters = method.parameterList.parameters
        if (parameters.isEmpty()) return setOf("_parameter", "_databaseId")
        var hasParamAnnotation = false
        val names = parameters.map { param ->
            val paramAnnotation = param.annotations.firstOrNull {
                it.qualifiedName?.substringAfterLast('.') == "Param"
            }
            if (paramAnnotation != null) {
                hasParamAnnotation = true
                stringConstant(paramAnnotation) ?: param.name
            } else {
                param.name
            }
        }
        return SqlInspectionText.paramRoots(names, hasParamAnnotation, parameters.size)
    }

    private fun stringConstant(annotation: PsiAnnotation): String? {
        val value = annotation.findAttributeValue(null) ?: return null
        return runCatching {
            JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper.computeConstantExpression(value)
        }.getOrNull()?.toString()
    }

    // ------------------------------------------------------------------
    // resultMap 类型 → 成员名集合
    // ------------------------------------------------------------------

    /**
     * `<resultMap type>` 解析出的类成员名（字段名 + getter/setter 派生的属性名，含父类链）。
     *
     * @return null = 不判定（type 未声明 / 解析不出 / dumb 态）
     */
    fun typeMemberNames(project: Project, typeValue: XmlAttributeValue?): Set<String>? {
        val raw = typeValue?.value ?: return null
        if (raw.isBlank() || DumbService.isDumb(project) || project.isDefault) return null
        val psiClass = runCatching { MybatisXmlTargets.resolveClass(project, typeValue, raw) }.getOrNull() ?: return null
        return memberNames(psiClass)
    }

    /** 类成员名集合：字段名 + `getX`/`isX`/`setX` 派生的 lowerCamel 属性名（含父类链）。 */
    fun memberNames(psiClass: PsiClass): Set<String> {
        val out = LinkedHashSet<String>()
        var cls: PsiClass? = psiClass
        var depth = 0
        while (cls != null && depth++ < MAX_HIERARCHY_DEPTH) {
            val current = cls
            for (field in runCatching { current.fields }.getOrDefault(emptyArray())) {
                if (field.hasModifierProperty(PsiModifier.STATIC)) continue
                field.name?.let { out += it }
            }
            for (method in runCatching { current.methods }.getOrDefault(emptyArray())) {
                val name = method.name
                when {
                    name.length > 3 && name.startsWith("get") && name[3].isUpperCase() ->
                        out += name.substring(3).replaceFirstChar { it.lowercaseChar() }
                    name.length > 2 && name.startsWith("is") && name[2].isUpperCase() ->
                        out += name.substring(2).replaceFirstChar { it.lowercaseChar() }
                    name.length > 3 && name.startsWith("set") && name[3].isUpperCase() ->
                        out += name.substring(3).replaceFirstChar { it.lowercaseChar() }
                }
            }
            cls = runCatching { current.superClass }.getOrNull()
        }
        return out
    }

    // ------------------------------------------------------------------
    // 注解 SQL
    // ------------------------------------------------------------------

    /** 注解元素：`@Select("…")` 的 value 属性值（锚点）。 */
    fun annotationValueElement(annotation: PsiAnnotation): PsiElement =
        annotation.findAttributeValue(null) ?: annotation

    /** 注解 SQL 文本（对齐 MyBatis `String.join(" ", value).trim()`；求值失败 → null）。 */
    fun annotationSqlText(annotation: PsiAnnotation, project: Project): String? {
        val value = annotation.findAttributeValue(null) ?: return null
        val helper = runCatching { JavaPsiFacade.getInstance(project).constantEvaluationHelper }.getOrNull() ?: return null
        val parts = when (value) {
            is com.intellij.psi.PsiArrayInitializerMemberValue ->
                value.initializers.mapNotNull { runCatching { helper.computeConstantExpression(it) }.getOrNull()?.toString() }
            else -> listOfNotNull(runCatching { helper.computeConstantExpression(value) }.getOrNull()?.toString())
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(" ").trim().ifEmpty { null }
    }

    /** 注解所在的 mapper 方法。 */
    fun enclosingMethod(annotation: PsiAnnotation): PsiMethod? =
        PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java)

    private const val MAX_HIERARCHY_DEPTH = 16
}