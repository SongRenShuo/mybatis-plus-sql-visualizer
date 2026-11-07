// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt（provider object）
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * T-XmlGap W7：mapper XML 内的引用（跳转 + 查找用法），<b>不做任何错误标注</b>。
 *
 * <p>红线：第十二轮「不做校验器形态回应 CodeHelperPro」继续有效——本类只产引用（resolve），
 * 不注册 inspection/annotator；且所有引用都是 <b>soft</b>（PsiReferenceBase 的 soft 构造位），
 * 目标解析不到时平台不会标红，符合计划 §六 W7「无目标时零报红」的验收判据。</p>
 *
 * <p>覆盖面（计划 §六 W7 行的六类）：</p>
 * <ul>
 *   <li>{@code include@refid} → {@code <sql id>}（同文件 + 跨文件 {@code ns.id}）；</li>
 *   <li>{@code resultMap=} / {@code resultMap@extends} → {@code <resultMap id>}；</li>
 *   <li>{@code parameterMap=} → {@code <parameterMap id>}；</li>
 *   <li>{@code namespace}（mapper 根）→ Java 接口；</li>
 *   <li>statement {@code id} → 接口方法；</li>
 *   <li>{@code resultType}/{@code parameterType}/{@code type}/{@code ofType}/{@code javaType}/{@code typeHandler}
 *       → PsiClass；</li>
 *   <li>{@code jdbcType} → {@code org.apache.ibatis.type.JdbcType} 常量（按类名运行时解析：生产零 mybatis 依赖）；</li>
 *   <li>{@code property}（{@code <result>}/{@code <id>}/{@code <arg>} 等）→ 实体字段链。</li>
 * </ul>
 *
 * <p>移植注记：Kotlin object 单例 → Java {@link #INSTANCE}；主注册类见
 * {@link MybatisXmlReferenceContributor}（同文件 KDoc 一并落位）。</p>
 */
public class MybatisXmlAttributeReferenceProvider extends PsiReferenceProvider {

    /** Kotlin object 单例等价。 */
    public static final MybatisXmlAttributeReferenceProvider INSTANCE = new MybatisXmlAttributeReferenceProvider();

    private MybatisXmlAttributeReferenceProvider() {
    }

    @Override
    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
        if (!(element instanceof XmlAttributeValue)) return PsiReference.EMPTY_ARRAY;
        XmlAttributeValue value = (XmlAttributeValue) element;
        if (!(value.getParent() instanceof XmlAttribute)) return PsiReference.EMPTY_ARRAY;
        XmlAttribute attr = (XmlAttribute) value.getParent();
        if (!(attr.getParent() instanceof XmlTag)) return PsiReference.EMPTY_ARRAY;
        XmlTag tag = (XmlTag) attr.getParent();
        // 根守卫：只在 mapper XML 内产引用（O(1) 祖先判定，与注入链同口径）
        if (!MybatisXmlTargets.isMapperFile(value.getContainingFile())) return PsiReference.EMPTY_ARRAY;
        String attributeName = attr.getName();
        String tagName = tag.getName();
        PsiReference reference;
        if (attributeName.equals("refid") && tagName.equals("include")) {
            reference = new XmlTagIdReference(value, "sql");
        } else if (attributeName.equals("resultMap")) {
            reference = new XmlTagIdReference(value, "resultMap");
        } else if (attributeName.equals("extends") && tagName.equals("resultMap")) {
            reference = new XmlTagIdReference(value, "resultMap");
        } else if (attributeName.equals("parameterMap")) {
            reference = new XmlTagIdReference(value, "parameterMap");
        } else if (attributeName.equals("namespace") && tagName.equals("mapper")) {
            reference = new JavaClassReference(value);
        } else if (attributeName.equals("id") && MybatisXmlTargets.STATEMENT_TAGS.contains(tagName)) {
            reference = new MapperMethodReference(value);
        } else if (CLASS_ATTRIBUTES.contains(attributeName)) {
            reference = new JavaClassReference(value);
        } else if (attributeName.equals("jdbcType")) {
            reference = new JdbcTypeReference(value);
        } else if (attributeName.equals("property")) {
            reference = new BeanPropertyReference(value);
        } else {
            reference = null;
        }
        return reference != null ? new PsiReference[]{reference} : new PsiReference[0];
    }

    private static final Set<String> CLASS_ATTRIBUTES = Set.of(
            "resultType", "parameterType", "type", "ofType", "javaType", "typeHandler");
}
