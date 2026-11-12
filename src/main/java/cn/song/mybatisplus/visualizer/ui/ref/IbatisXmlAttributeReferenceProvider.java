// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/IbatisXmlIdReferenceContributor.kt 移植（拆件：object IbatisXmlAttributeReferenceProvider）
package cn.song.mybatisplus.visualizer.ui.ref;

import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

/**
 * iBatis {@code <sqlMap>} 文件内的属性值引用产出口（{@code include@refid} / {@code resultMap=} /
 * {@code parameterMap=} / {@code resultMap@extends} 四类；根守卫 O(1)）。
 *
 * <p>移植注记：Kotlin object 单例 → Java {@link #INSTANCE}（与 {@link MybatisXmlAttributeReferenceProvider} 同形）。</p>
 */
public class IbatisXmlAttributeReferenceProvider extends PsiReferenceProvider {

    /** Kotlin object 单例等价。 */
    public static final IbatisXmlAttributeReferenceProvider INSTANCE = new IbatisXmlAttributeReferenceProvider();

    private IbatisXmlAttributeReferenceProvider() {
    }

    @Override
    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
        if (!(element instanceof XmlAttributeValue value)) {
            return PsiReference.EMPTY_ARRAY;
        }
        XmlAttribute attr = value.getParent() instanceof XmlAttribute attribute ? attribute : null;
        if (attr == null) {
            return PsiReference.EMPTY_ARRAY;
        }
        XmlTag tag = attr.getParent() instanceof XmlTag host ? host : null;
        if (tag == null) {
            return PsiReference.EMPTY_ARRAY;
        }
        // 根守卫：O(1) 根标签名判定，只在 <sqlMap> 内产引用
        if (!IbatisXmlStatementTargets.isSqlMapFile(value.getContainingFile())) {
            return PsiReference.EMPTY_ARRAY;
        }
        String attributeName = attr.getName();
        String tagName = tag.getName();
        PsiReference reference;
        if ("refid".equals(attributeName) && "include".equals(tagName)) {
            reference = new IbatisXmlIdReference(value, "sql");
        } else if ("resultMap".equals(attributeName)) {
            reference = new IbatisXmlIdReference(value, "resultMap");
        } else if ("extends".equals(attributeName) && "resultMap".equals(tagName)) {
            reference = new IbatisXmlIdReference(value, "resultMap");
        } else if ("parameterMap".equals(attributeName)) {
            reference = new IbatisXmlIdReference(value, "parameterMap");
        } else {
            reference = null;
        }
        return reference == null ? PsiReference.EMPTY_ARRAY : new PsiReference[]{reference};
    }
}
