package cn.song.mybatisplus.visualizer.ui.ref

import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

/**
 * T-Ibatis2Support 券 F（车道 L8）：iBatis 2.x `<sqlMap>` 文件内的 **id 引用**（跳转 + 查找用法）。
 *
 * ## 覆盖面（计划册券 F 的 F-1 表）
 *
 * | 引用位 | 目标 |
 * |---|---|
 * | `<include refid="x"/>` | 同文件 `<sql id="x">`；未命中再按 `namespace + "." + x` 找 |
 * | 任意标签上的 `resultMap="x"` | 同文件 `<resultMap id="x">` |
 * | 语句上的 `parameterMap="x"` | 同文件 `<parameterMap id="x">` |
 * | `<resultMap extends="x">` | 同文件 `<resultMap id="x">` |
 *
 * 跨文件（经 `sqlMapConfig.xml` 的 `<sqlMap resource=…>` 清单）本期不做——需要解析 config 并建全局
 * 清单，成本另算（已在报告登记为未做项）。
 *
 * ## 两条红线
 *
 * 1. **只产引用，不做校验器形态**：本文件不注册任何错误标注（inspection / annotator 一律不碰），
 *    与 `MybatisXmlReferenceContributor` 同口径——本包 `MybatisXmlReferenceTest` 的源码扫描用例会
 *    连同本文件一起扫（新增件自动进入该扫描面）。
 * 2. **无目标零报红**：所有引用都是 **soft**（[SoftXmlAttributeReference] 的 soft 构造位），
 *    解析不到时平台不标红。
 *
 * ## 零回归
 *
 * 根守卫是 **O(1)** 的根标签名判定：`rootTag?.name == "sqlMap"`。MyBatis `<mapper>` 文件与
 * 其它 XML 一律零引用（`MybatisXmlReferenceContributor` 判 `mapper`、本件判 `sqlMap`，互斥），
 * 故既有 MyBatis 引用/跳转行为逐字节不变。**不在本路径调用 `IbatisXmlRecognizer.recognize`**
 * （含 classpath 索引查询）。
 */
class IbatisXmlIdReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue::class.java),
            IbatisXmlAttributeReferenceProvider,
        )
    }
}

object IbatisXmlAttributeReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val attr = value.parent as? XmlAttribute ?: return PsiReference.EMPTY_ARRAY
        val tag = attr.parent as? XmlTag ?: return PsiReference.EMPTY_ARRAY
        // 根守卫：O(1) 根标签名判定，只在 <sqlMap> 内产引用
        if (!IbatisXmlStatementTargets.isSqlMapFile(value.containingFile)) return PsiReference.EMPTY_ARRAY
        val attributeName = attr.name
        val tagName = tag.name
        val reference: PsiReference? = when {
            attributeName == "refid" && tagName == "include" ->
                IbatisXmlIdReference(value, "sql")
            attributeName == "resultMap" ->
                IbatisXmlIdReference(value, "resultMap")
            attributeName == "extends" && tagName == "resultMap" ->
                IbatisXmlIdReference(value, "resultMap")
            attributeName == "parameterMap" ->
                IbatisXmlIdReference(value, "parameterMap")
            else -> null
        }
        return if (reference == null) arrayOf() else arrayOf(reference)
    }
}

/**
 * `<sql id>` / `<resultMap id>` / `<parameterMap id>` 目标引用（**soft**：目标缺失不标红）。
 *
 * 复用同包的 [SoftXmlAttributeReference] 只为「属性值内剥引号的值区间」这一处共享逻辑；
 * 目标解析全部走 [IbatisXmlStatementTargets]（同文件两段式查找）。
 */
class IbatisXmlIdReference(value: XmlAttributeValue, private val targetTagName: String) :
    SoftXmlAttributeReference(value) {

    override fun resolve(): PsiElement? = IbatisXmlStatementTargets.resolveSameFileIdReference(
        value.containingFile,
        targetTagName,
        value.value,
    )
}
