// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/IbatisXmlIdReferenceContributor.kt 移植（拆件：class IbatisXmlIdReference）
package cn.song.mybatisplus.visualizer.ui.ref;

import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.Nullable;

/**
 * {@code <sql id>} / {@code <resultMap id>} / {@code <parameterMap id>} 目标引用（<b>soft</b>：目标缺失不标红）。
 *
 * <p>复用同包的 {@link SoftXmlAttributeReference} 只为「属性值内剥引号的值区间」这一处共享逻辑；
 * 目标解析全部走 {@link IbatisXmlStatementTargets}（同文件两段式查找）。</p>
 */
public class IbatisXmlIdReference extends SoftXmlAttributeReference {

    private final String targetTagName;

    public IbatisXmlIdReference(XmlAttributeValue value, String targetTagName) {
        super(value);
        this.targetTagName = targetTagName;
    }

    @Override
    @Nullable
    public PsiElement resolve() {
        return IbatisXmlStatementTargets.resolveSameFileIdReference(
            value.getContainingFile(),
            targetTagName,
            value.getValue(),
        );
    }
}
