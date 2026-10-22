// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlReferenceContributor.kt（BeanPropertyReference）
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code property="a.b"} → 实体字段链。
 *
 * <p>持有者类的判定是<b>有界</b>的：向上找最近的带 {@code type}/{@code resultType}/{@code parameterType}/
 * {@code javaType} 属性的祖先标签（{@code <resultMap type>} / {@code <collection ofType>} /
 * {@code <association javaType>} 等），找不到再退到所属语句标签的 {@code parameterType}。
 * 都拿不到 → 返回 null（soft，不标红）。</p>
 */
public class BeanPropertyReference extends SoftXmlAttributeReference {

    public BeanPropertyReference(XmlAttributeValue value) {
        super(value);
    }

    @Override
    @Nullable
    public PsiElement resolve() {
        PsiClass holder = holderClass(value);
        if (holder == null) return null;
        String text = value.getValue();
        if (text.contains(".")) {
            return MybatisXmlTargets.resolvePropertyChain(value.getProject(), holder, text);
        }
        return MybatisXmlTargets.resolvePropertyField(value.getProject(), holder, text);
    }

    @Nullable
    private PsiClass holderClass(XmlAttributeValue value) {
        com.intellij.openapi.project.Project project = value.getProject();
        XmlTag tag = value.getParent() instanceof XmlAttribute
                && ((XmlAttribute) value.getParent()).getParent() instanceof XmlTag
                ? (XmlTag) ((XmlAttribute) value.getParent()).getParent() : null;
        int depth = 0;
        // F97（T-PerfSweep26）：同一 resolve 会话内的类名解析结果 memo——祖先链每层最多问
        // HOLDER_ATTRIBUTES.size 个属性，最坏 24×5 次 resolveClass；同层多属性常解析出同一类，
        // 局部 Map 把重复 resolveClass 压成查表（会话级、无 stale 面：跨 resolve 不保留）。
        java.util.HashMap<String, @Nullable PsiClass> resolved = new java.util.HashMap<>(HOLDER_ATTRIBUTES.size() * 2);
        while (tag != null && depth++ < MAX_ANCESTOR_DEPTH) {
            for (String name : HOLDER_ATTRIBUTES) {
                String raw = tag.getAttributeValue(name);
                if (raw == null) continue;
                PsiClass hit = resolved.containsKey(raw) ? resolved.get(raw) : MybatisXmlTargets.resolveClass(project, value, raw);
                resolved.put(raw, hit);
                if (hit != null) return hit;
            }
            tag = tag.getParentTag();
        }
        return null;
    }

    private static final List<String> HOLDER_ATTRIBUTES =
            List.of("type", "resultType", "parameterType", "javaType", "ofType");

    private static final int MAX_ANCESTOR_DEPTH = 24;
}
