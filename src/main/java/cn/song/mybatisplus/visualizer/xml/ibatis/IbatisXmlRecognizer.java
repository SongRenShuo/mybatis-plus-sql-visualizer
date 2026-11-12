// T-SyncK2JMaster178 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ibatis/IbatisXmlRecognizer.kt 移植
package cn.song.mybatisplus.visualizer.xml.ibatis;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlDoctype;
import com.intellij.psi.xml.XmlFile;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * iBatis 2.x XML 文件识别器（T-Ibatis2Support 券 A 第 1 条）。
 *
 * <h2>为什么是「三选二」而不是单条判据</h2>
 *
 * 三条纠正均为<b>实测结论</b>（写死在这里，防后按常识回退）：
 *
 * <ol>
 * <li>{@code -//Apache Software Foundation//DTD SQL Map 2.0//EN} <b>不是</b> iBatis 的 publicId——全 jar 里该串
 * 只出现在 {@code META-INF/MANIFEST.MF} 的 {@code Implementation-Vendor}，{@code SqlMapClasspathEntityResolver}
 * 不认它。→ 本识别器<b>容忍</b>它（不因它判否），但<b>绝不</b>把它当 iBatis 的判定证据。</li>
 * <li><b>无 DOCTYPE 的裸 {@code <sqlMap>} 不是运行时可用形态</b>（iBatis 配置期
 * {@code NodeletParser.setValidation(true)} 开 DTD 校验，裸根会校验失败并抛异常）→ 不能只信根元素；
 * 同时仓库里可能有死文件/未注册片段（{@code <select>} 片段被拷来当资料）→ 也不能只信 DOCTYPE。
 * 这就是「三选二」的由来。</li>
 * <li>文件命名（{@code *_SqlMap.xml}、{@code SqlMapConfig.xml}）只是社区约定，
 * <b>只能当提示、不能当判据</b>，故本识别器不接收文件名入参。</li>
 * </ol>
 *
 * <h2>信号表</h2>
 *
 * <table><tr><th>信号</th><th>取值</th></tr>
 * <tr><td>{@link Signal#ROOT_TAG}</td><td>根元素 {@code sqlMap} / {@code sqlMapConfig}（MyBatis 3 是
 * {@code mapper} / {@code configuration}）</td></tr>
 * <tr><td>{@link Signal#DOCTYPE}</td><td>publicId / systemId ∈ {@link #IBATIS_PUBLIC_IDS} / {@link #IBATIS_SYSTEM_IDS}</td></tr>
 * <tr><td>{@link Signal#CLASSPATH}</td><td>工程 classpath 上有 {@code com.ibatis.sqlmap.client.SqlMapClient}</td></tr>
 * </table>
 *
 * <p>命中 <b>≥ {@link #MIN_SIGNALS}（= 2）</b>条判为 iBatis（{@link Verdict#isIbatis()}），并把命中的信号集合
 * 一并返回（诊断 + 测试断言用：只命中一条时能直接看出是哪条、为什么没到阈值）。</p>
 *
 * <h2>文件结构（纯逻辑与薄封装的分界）</h2>
 *
 * <ul>
 * <li><b>纯逻辑</b>（零 {@code com.intellij}、零副作用、输入全显式）：{@link #recognize(String, String, String, boolean)}
 * 四参重载 + {@link Verdict} + {@link Signal} + 各判定常量——单测可零 PSI 跑。</li>
 * <li><b>薄封装</b>：同为 {@code recognize} 的 {@code PsiFile} 重载 + {@link #sqlMapClientOnClasspath(Project)}——
 * 只做 {@code PsiFile → (根标签名, publicId, systemId, classpath 标志)} 的证据采集，判定一律回到纯逻辑。</li>
 * </ul>
 *
 * <p>消费方：iBatis 的 gutter / 预览接管链（{@code ui/gutter/SqlMapXmlGutterLineMarkerProvider}）与
 * {@code xml/XmlInlineSqlDetector.isClaimedByKnownStructure} 的让位分支——识别口径只有这一处，
 * 不允许各调用点各写一份标签名表。</p>
 *
 * <p>master 侧 {@code object}：Java 侧静态门面。</p>
 */
public final class IbatisXmlRecognizer {

    private IbatisXmlRecognizer() {
    }

    /** 判为 iBatis 所需的最少命中信号数（三选二）。 */
    public static final int MIN_SIGNALS = 2;

    /**
     * iBatis 根元素名（小写比较）：{@code sqlMap} = 语句映射文件，{@code sqlMapConfig} = 全局配置。
     * MyBatis 3 的 {@code mapper} / {@code configuration} 不在表内——本识别器对 MyBatis 文件必须判否。
     */
    private static final Set<String> IBATIS_ROOT_TAGS = Set.of(
            "sqlmap",
            "sqlmapconfig"
    );

    /**
     * iBatis 2.x DOCTYPE 的合法 systemId（2.1.7 与 2.3.4.726 两代 DTD 的两种域形态，
     * 语句映射与全局配置各一份）。
     */
    public static final Set<String> IBATIS_SYSTEM_IDS = Set.of(
            "http://ibatis.apache.org/dtd/sql-map-2.dtd",
            "http://www.ibatis.com/dtd/sql-map-2.dtd",
            "http://ibatis.apache.org/dtd/sql-map-config-2.dtd",
            "http://www.ibatis.com/dtd/sql-map-config-2.dtd"
    );

    /** iBatis 2.x DOCTYPE 的合法 publicId（配置版与语句映射版同形）。 */
    public static final Set<String> IBATIS_PUBLIC_IDS = Set.of(
            "-//iBATIS.com//DTD SQL Map 2.0//EN",
            "-//ibatis.apache.org//DTD SQL Map 2.0//EN",
            "-//iBATIS.com//DTD SQL Map Config 2.0//EN",
            "-//ibatis.apache.org//DTD SQL Map Config 2.0//EN"
    );

    /** classpath 类特征：iBatis 2.x 门面接口（{@code ibatis-sqlmap} jar 的稳定入口）。 */
    public static final String SQL_MAP_CLIENT_CLASS = "com.ibatis.sqlmap.client.SqlMapClient";

    /** 命中信号（见类 KDoc 的表）。 */
    public enum Signal {
        /** 根元素 ∈ {@code sqlMap} / {@code sqlMapConfig}。 */
        ROOT_TAG,

        /** DOCTYPE 的 publicId 或 systemId 命中 iBatis 2.x 官方 DTD 标识。 */
        DOCTYPE,

        /** 工程 classpath 上有 {@code com.ibatis.sqlmap.client.SqlMapClient}。 */
        CLASSPATH,
    }

    /** 识别结论：是否 iBatis + 命中的信号集合（诊断用——只命中一条时看这里就知道差哪条）。 */
    public record Verdict(
            boolean isIbatis,
            Set<Signal> signals
    ) {

        /** 命中信号数（= {@code signals()} 大小；{@link Signal} 集合无重复）。 */
        public int signalCount() {
            return signals.size();
        }
    }

    // ------------------------------------------------------------------
    // 纯逻辑（零 com.intellij / 零副作用）
    // ------------------------------------------------------------------

    /**
     * 三选二判定（纯函数）。四个入参全部显式传入，调用方负责取证（见 {@code PsiFile} 重载）。
     *
     * @param rootTagName          根标签名；非 XML / 无根元素传 null。
     * @param doctypePublicId      DOCTYPE 的 publicId（{@code PUBLIC} 形态才有）；无 DOCTYPE / 仅 SYSTEM 传 null。
     * @param doctypeSystemId      DOCTYPE 的 systemId（{@code SYSTEM} 与 {@code PUBLIC} 两种形态都可能有）。
     * @param hasIbatisOnClasspath 工程 classpath 上是否有 {@link #SQL_MAP_CLIENT_CLASS}。
     */
    public static Verdict recognize(String rootTagName, String doctypePublicId, String doctypeSystemId, boolean hasIbatisOnClasspath) {
        Set<Signal> signals = new LinkedHashSet<>();
        if (rootTagName != null && IBATIS_ROOT_TAGS.contains(rootTagName.trim().toLowerCase(Locale.ROOT))) {
            signals.add(Signal.ROOT_TAG);
        }
        if ((doctypePublicId != null && IBATIS_PUBLIC_IDS.contains(doctypePublicId.trim()))
                || (doctypeSystemId != null && IBATIS_SYSTEM_IDS.contains(doctypeSystemId.trim()))) {
            signals.add(Signal.DOCTYPE);
        }
        if (hasIbatisOnClasspath) {
            signals.add(Signal.CLASSPATH);
        }
        return new Verdict(signals.size() >= MIN_SIGNALS, signals);
    }

    /** 是否 iBatis 文件（{@link #recognize(String, String, String, boolean)} 的布尔简写）。 */
    public static boolean isIbatis(String rootTagName, String doctypePublicId, String doctypeSystemId, boolean hasIbatisOnClasspath) {
        return recognize(rootTagName, doctypePublicId, doctypeSystemId, hasIbatisOnClasspath).isIbatis();
    }

    // ------------------------------------------------------------------
    // 薄封装（只在 `PsiFile` 与纯逻辑之间搬证据；判定不在这里）
    // ------------------------------------------------------------------

    /**
     * 从 PSI 取证据后判定（<b>须在读操作内调用</b>；内部包 {@link ReadAction}，gutter / annotator 路径天然
     * 持有读权限）。
     *
     * <p>证据采集：根标签 = {@link XmlFile#getRootTag()}；DOCTYPE = {@code file.document.prolog.doctype} 的
     * publicId / systemId；classpath 由 {@link #sqlMapClientOnClasspath(Project)} 探。</p>
     *
     * <p>非 XML 文件 / 解析不出文档时按「无信号」处理（全 null + classpath 探针结果），不抛异常。</p>
     */
    public static Verdict recognize(PsiFile file) {
        return ReadAction.compute(() -> {
            XmlFile xml = file instanceof XmlFile xmlFile ? xmlFile : null;
            String rootTagName = xml != null && xml.getRootTag() != null ? xml.getRootTag().getName() : null;
            XmlDoctype doctype = xml != null && xml.getDocument() != null && xml.getDocument().getProlog() != null
                    ? xml.getDocument().getProlog().getDoctype()
                    : null;
            return recognize(
                    rootTagName,
                    publicIdOf(doctype),
                    systemIdOf(doctype),
                    sqlMapClientOnClasspath(file.getProject())
            );
        });
    }

    /** 工程 classpath 上是否有 {@link #SQL_MAP_CLIENT_CLASS}（<b>须在读操作内调用</b>）。 */
    public static boolean sqlMapClientOnClasspath(Project project) {
        try {
            // 与 ProjectTypeDetector 同口径：allScope 覆盖库（module 级 scope 不含依赖 jar 时恒 null）。
            return JavaPsiFacade.getInstance(project)
                    .findClass(SQL_MAP_CLIENT_CLASS, GlobalSearchScope.allScope(project)) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * DOCTYPE 的 publicId。
     *
     * <p>{@code XmlDoctype} 接口声明 {@code @NlsSafe String getPublicId()}（非空），但 243 平台实现
     * {@code XmlDoctypeImpl.getPublicId()} 是 {@code @Nullable}（{@code SYSTEM} 形态 / 裸 {@code <!DOCTYPE x>}
     * 时返回 null）——两种映射下都能拿到 null 而不炸：平台类型无断言时直得 null，
     * 插入非空断言时由 runCatching 归 null。</p>
     */
    private static String publicIdOf(XmlDoctype doctype) {
        try {
            return doctype != null ? doctype.getPublicId() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** DOCTYPE 的 systemId（同 {@link #publicIdOf(XmlDoctype)}：接口非空 / 实现可空，一致收口）。 */
    private static String systemIdOf(XmlDoctype doctype) {
        try {
            return doctype != null ? doctype.getSystemId() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
