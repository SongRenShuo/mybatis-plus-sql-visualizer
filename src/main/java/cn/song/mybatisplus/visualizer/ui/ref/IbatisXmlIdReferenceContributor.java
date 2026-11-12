// T-SyncK2JMaster183 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/IbatisXmlIdReferenceContributor.kt 移植
package cn.song.mybatisplus.visualizer.ui.ref;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;

/**
 * T-Ibatis2Support 券 F（车道 L8）：iBatis 2.x {@code <sqlMap>} 文件内的 <b>id 引用</b>（跳转 + 查找用法）。
 *
 * <h2>覆盖面（计划册券 F 的 F-1 表）</h2>
 *
 * <table border="1">
 * <tr><th>引用位</th><th>目标</th></tr>
 * <tr><td>{@code <include refid="x"/>}</td><td>同文件 {@code <sql id="x">}；未命中再按 {@code namespace + "." + x} 找</td></tr>
 * <tr><td>任意标签上的 {@code resultMap="x"}</td><td>同文件 {@code <resultMap id="x">}</td></tr>
 * <tr><td>语句上的 {@code parameterMap="x"}</td><td>同文件 {@code <parameterMap id="x">}</td></tr>
 * <tr><td>{@code <resultMap extends="x">}</td><td>同文件 {@code <resultMap id="x">}</td></tr>
 * </table>
 *
 * <p>跨文件（经 {@code sqlMapConfig.xml} 的 {@code <sqlMap resource=…>} 清单）本期不做——需要解析 config 并建全局
 * 清单，成本另算（已在报告登记为未做项）。</p>
 *
 * <h2>两条红线</h2>
 *
 * <ol>
 * <li><b>只产引用，不做校验器形态</b>：本文件不注册任何错误标注（inspection / annotator 一律不碰），
 *     与 {@link MybatisXmlReferenceContributor} 同口径——本包 {@code MybatisXmlReferenceTest} 的源码扫描用例会
 *     连同本文件一起扫（新增件自动进入该扫描面）。</li>
 * <li><b>无目标零报红</b>：所有引用都是 <b>soft</b>（{@link SoftXmlAttributeReference} 的 soft 构造位），
 *     解析不到时平台不标红。</li>
 * </ol>
 *
 * <h2>零回归</h2>
 *
 * <p>根守卫是 <b>O(1)</b> 的根标签名判定：{@code rootTag?.name == "sqlMap"}。MyBatis {@code <mapper>} 文件与
 * 其它 XML 一律零引用（{@code MybatisXmlReferenceContributor} 判 {@code mapper}、本件判 {@code sqlMap}，互斥），
 * 故既有 MyBatis 引用/跳转行为逐字节不变。<b>不在本路径调用 {@code IbatisXmlRecognizer.recognize}</b>
 * （含 classpath 索引查询）。</p>
 */
public class IbatisXmlIdReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlAttributeValue.class),
            IbatisXmlAttributeReferenceProvider.INSTANCE,
        );
    }
}
