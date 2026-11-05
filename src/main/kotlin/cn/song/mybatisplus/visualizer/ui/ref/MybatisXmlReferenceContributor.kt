package cn.song.mybatisplus.visualizer.ui.ref

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * T-XmlGap W7：mapper XML 内的引用（跳转 + 查找用法），**不做任何错误标注**。
 *
 * 红线：第十二轮「不做校验器形态回应 CodeHelperPro」继续有效——本文件只产引用（`resolve`），
 * 不注册 inspection/annotator；且所有引用都是 **soft**（[PsiReferenceBase] 的 soft 构造位），
 * 目标解析不到时平台不会标红，符合计划 §六 W7「无目标时零报红」的验收判据。
 *
 * 覆盖面（计划 §六 W7 行的六类）：
 *  - `include@refid` → `<sql id>`（同文件 + 跨文件 `ns.id`）
 *  - `resultMap=` / `resultMap@extends` → `<resultMap id>`
 *  - `parameterMap=` → `<parameterMap id>`
 *  - `namespace`（mapper 根）→ Java 接口
 *  - statement `id` → 接口方法
 *  - `resultType`/`parameterType`/`type`/`ofType`/`javaType`/`typeHandler` → `PsiClass`
 *  - `jdbcType` → `org.apache.ibatis.type.JdbcType` 常量（按类名运行时解析：生产零 mybatis 依赖）
 *  - `property`（`<result>`/`<id>`/`<arg>` 等）→ 实体字段链
 */
class MybatisXmlReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            MybatisXmlAttributeReferenceProvider,
        )
    }
}

object MybatisXmlAttributeReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attr = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val tag = attr.parent as? XmlTag ?: return PsiReference.EMPTY_ARRAY
        // 根守卫：只在 mapper XML 内产引用（O(1) 祖先判定，与注入链同口径）
        if (!MybatisXmlTargets.isMapperFile(value.containingFile)) return PsiReference.EMPTY_ARRAY
        val attributeName = attr.name
        val tagName = tag.name
        val reference: PsiReference? = when {
            attributeName == "refid" && tagName == "include" ->
                XmlTagIdReference(value, "sql")
            attributeName == "resultMap" ->
                XmlTagIdReference(value, "resultMap")
            attributeName == "extends" && tagName == "resultMap" ->
                XmlTagIdReference(value, "resultMap")
            attributeName == "parameterMap" ->
                XmlTagIdReference(value, "parameterMap")
            attributeName == "namespace" && tagName == "mapper" ->
                JavaClassReference(value)
            attributeName == "id" && tagName in MybatisXmlTargets.STATEMENT_TAGS ->
                MapperMethodReference(value)
            attributeName in CLASS_ATTRIBUTES ->
                JavaClassReference(value)
            attributeName == "jdbcType" ->
                JdbcTypeReference(value)
            attributeName == "property" ->
                BeanPropertyReference(value)
            else -> null
        }
        return if (reference == null) arrayOf() else arrayOf(reference)
    }

    private val CLASS_ATTRIBUTES = setOf(
        "resultType", "parameterType", "type", "ofType", "javaType", "typeHandler",
    )
}

/** 引用基类：全部 **soft**（解析不到不标红，红线要求）。 */
abstract class SoftXmlAttributeReference(
    protected val value: XmlAttributeValue,
) : PsiReferenceBase<XmlAttributeValue>(value, valueRangeInElement(value), true) {

    override fun getCanonicalText(): String = value.value

    override fun getVariants(): Array<Any> = emptyArray()

    companion object {
        /** 属性值元素内的**值区间**（剥引号；相对坐标）。 */
        fun valueRangeInElement(value: XmlAttributeValue): TextRange {
            val abs = value.valueTextRange
            val base = value.textRange.startOffset
            val start = (abs.startOffset - base).coerceAtLeast(0)
            val end = (abs.endOffset - base).coerceAtMost(value.textLength)
            return if (start < end) TextRange(start, end) else TextRange(0, value.textLength)
        }
    }
}

/** `<sql id>` / `<resultMap id>` / `<parameterMap id>` 目标引用。 */
class XmlTagIdReference(value: XmlAttributeValue, private val targetTagName: String) :
    SoftXmlAttributeReference(value) {

    override fun resolve(): PsiElement? = MybatisXmlTargets.resolveIdReference(
        value.project,
        value.containingFile,
        targetTagName,
        value.value,
    )
}

/** 类名属性（`resultType` 族 / `namespace`）→ `PsiClass`。 */
class JavaClassReference(value: XmlAttributeValue) : SoftXmlAttributeReference(value) {

    override fun resolve(): PsiElement? = MybatisXmlTargets.resolveClass(value.project, value, value.value)
}

/** `jdbcType="VARCHAR"` → `JdbcType` 枚举常量字段。 */
class JdbcTypeReference(value: XmlAttributeValue) : SoftXmlAttributeReference(value) {

    override fun resolve(): PsiElement? = MybatisXmlTargets.resolveJdbcTypeField(value.project, value.value)
}

/** statement `id` → namespace 接口的同名方法。 */
class MapperMethodReference(value: XmlAttributeValue) : SoftXmlAttributeReference(value) {

    override fun resolve(): PsiElement? {
        val namespace = MybatisXmlTargets.rootNamespace(value.containingFile) ?: return null
        return MybatisXmlTargets.resolveInterfaceMethod(value.project, namespace, value.value)
    }
}

/**
 * `property="a.b"` → 实体字段链。
 *
 * 持有者类的判定是**有界**的：向上找最近的带 `type`/`resultType`/`parameterType`/`javaType`
 * 属性的祖先标签（`<resultMap type>` / `<collection ofType>` / `<association javaType>` 等），
 * 找不到再退到所属语句标签的 `parameterType`。都拿不到 → 返回 null（soft，不标红）。
 */
class BeanPropertyReference(value: XmlAttributeValue) : SoftXmlAttributeReference(value) {

    override fun resolve(): PsiElement? {
        val holder = holderClass(value) ?: return null
        val text = value.value
        return if (text.contains('.')) {
            MybatisXmlTargets.resolvePropertyChain(value.project, holder, text)
        } else {
            MybatisXmlTargets.resolvePropertyField(value.project, holder, text)
        }
    }

    private fun holderClass(value: XmlAttributeValue): PsiClass? {
        val project = value.project
        var tag: XmlTag? = (value.parent as? XmlAttribute)?.parent as? XmlTag
        var depth = 0
        // F97（T-PerfSweep26）：同一 resolve 会话内的类名解析结果 memo——祖先链每层最多问
        // HOLDER_ATTRIBUTES.size 个属性，最坏 24×5 次 resolveClass；同层多属性常解析出同一类，
        // 局部 Map 把重复 resolveClass 压成查表（会话级、无 stale 面：跨 resolve 不保留）。
        val resolved = HashMap<String, PsiClass?>(HOLDER_ATTRIBUTES.size * 2)
        while (tag != null && depth++ < MAX_ANCESTOR_DEPTH) {
            for (name in HOLDER_ATTRIBUTES) {
                val raw = tag.getAttributeValue(name) ?: continue
                val hit = if (resolved.containsKey(raw)) resolved[raw] else MybatisXmlTargets.resolveClass(project, value, raw)
                resolved[raw] = hit
                if (hit != null) return hit
            }
            tag = tag.parentTag
        }
        return null
    }

    private companion object {
        val HOLDER_ATTRIBUTES = listOf("type", "resultType", "parameterType", "javaType", "ofType")
        const val MAX_ANCESTOR_DEPTH = 24
    }
}
