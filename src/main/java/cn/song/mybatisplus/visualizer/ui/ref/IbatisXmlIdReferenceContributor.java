package cn.song.mybatisplus.visualizer.ui.ref;

import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * T-Ibatis2Support 券 F（车道 L8）：iBatis 2.x {@code <sqlMap>} 文件内的 <b>id 引用</b>
 * （跳转 + 查找用法）。
 *
 * <h2>覆盖面（计划册券 F 的 F-1 表）</h2>
 *
 * <table>
 *   <tr><th>引用位</th><th>目标</th></tr>
 *   <tr><td>{@code <include refid="x"/>}</td><td>同文件 {@code <sql id="x">}；未命中再按 {@code namespace + "." + x} 找</td></tr>
 *   <tr><td>任意标签上的 {@code resultMap="x"}</td><td>同文件 {@code <resultMap id="x">}</td></tr>
 *   <tr><td>语句上的 {@code parameterMap="x"}</td><td>同文件 {@code <parameterMap id="x">}</td></tr>
 *   <tr><td>{@code <resultMap extends="x">}</td><td>同文件 {@code <resultMap id="x">}</td></tr>
 * </table>
 *
 * <p>跨文件（经 {@code sqlMapConfig.xml} 的 {@code <sqlMap resource=…>} 清单）本期不做——需要解析
 * config 并建全局清单，成本另算（已在报告登记为未做项）。</p>
 *
 * <h2>两条红线</h2>
 *
 * <ol>
 * <li><b>只产引用，不做校验器形态</b>：本文件不注册任何错误标注（inspection / annotator 一律不碰），
 *     与 MybatisXmlReferenceContributor 同口径——本包 MybatisXmlReferenceTest 的源码扫描用例会
 *     连同本文件一起扫（新增件自动进入该扫描面）。</li>
 * <li><b>无目标零报红</b>：所有引用都是 <b>soft</b>（{@link SoftXmlAttributeReference} 的 soft 构造位），
 *     解析不到时平台不标红。</li>
 * </ol>
 *
 * <h2>零回归</h2>
 *
 * <p>根守卫是 <b>O(1)</b> 的根标签名判定：{@code rootTag?.name == "sqlMap"}。MyBatis {@code <mapper>}
 * 文件与其它 XML 一律零引用（MybatisXmlReferenceContributor 判 mapper、本件判 sqlMap，互斥），
 * 故既有 MyBatis 引用/跳转行为逐字节不变。<b>不在本路径调用 IbatisXmlRecognizer.recognize</b>
 * （含 classpath 索引查询）。</p>
 */
public class IbatisXmlIdReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlAttributeValue.class),
                IbatisXmlAttributeReferenceProvider.INSTANCE);
    }
}

/** refid / resultMap / extends / parameterMap 四类引用位的判定与产出（Kotlin object 等价）。 */
class IbatisXmlAttributeReferenceProvider extends PsiReferenceProvider {

    static final IbatisXmlAttributeReferenceProvider INSTANCE = new IbatisXmlAttributeReferenceProvider();

    private IbatisXmlAttributeReferenceProvider() {
    }

    @Override
    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                           @NotNull ProcessingContext context) {
        if (!(element instanceof XmlAttributeValue value)) {
            return PsiReference.EMPTY_ARRAY;
        }
        XmlAttribute attr = value.getParent() instanceof XmlAttribute parent ? parent : null;
        if (attr == null) {
            return PsiReference.EMPTY_ARRAY;
        }
        XmlTag tag = attr.getParent();
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
        return reference != null ? new PsiReference[]{reference} : PsiReference.EMPTY_ARRAY;
    }
}

/**
 * {@code <sql id>} / {@code <resultMap id>} / {@code <parameterMap id>} 目标引用
 * （<b>soft</b>：目标缺失不标红）。
 *
 * <p>复用同包的 {@link SoftXmlAttributeReference} 只为「属性值内剥引号的值区间」这一处共享逻辑；
 * 目标解析全部走 {@link IbatisXmlStatementTargets}（同文件两段式查找）。</p>
 */
class IbatisXmlIdReference extends SoftXmlAttributeReference {

    private final String targetTagName;

    IbatisXmlIdReference(@NotNull XmlAttributeValue value, @NotNull String targetTagName) {
        super(value);
        this.targetTagName = targetTagName;
    }

    @Override
    public @Nullable PsiElement resolve() {
        return IbatisXmlStatementTargets.resolveSameFileIdReference(
                value.getContainingFile(),
                targetTagName,
                value.getValue());
    }
}
