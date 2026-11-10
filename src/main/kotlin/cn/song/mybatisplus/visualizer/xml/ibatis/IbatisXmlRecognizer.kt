package cn.song.mybatisplus.visualizer.xml.ibatis

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlDoctype
import com.intellij.psi.xml.XmlFile
import java.util.Locale

/**
 * iBatis 2.x XML 文件识别器（T-Ibatis2Support 券 A 第 1 条）。
 *
 * ## 为什么是「三选二」而不是单条判据
 *
 * 三条纠正均为**实测结论**（写死在这里，防后按常识回退）：
 *
 * 1. `-//Apache Software Foundation//DTD SQL Map 2.0//EN` **不是** iBatis 的 publicId——全 jar 里该串
 *    只出现在 `META-INF/MANIFEST.MF` 的 `Implementation-Vendor`，`SqlMapClasspathEntityResolver` 不认它。
 *    → 本识别器**容忍**它（不因它判否），但**绝不**把它当 iBatis 的判定证据。
 * 2. **无 DOCTYPE 的裸 `<sqlMap>` 不是运行时可用形态**（iBatis 配置期 `NodeletParser.setValidation(true)`
 *    开 DTD 校验，裸根会校验失败并抛异常）→ 不能只信根元素；同时仓库里可能有死文件/未注册片段
 *    （`<select>` 片段被拷来当资料）→ 也不能只信 DOCTYPE。这就是「三选二」的由来。
 * 3. 文件命名（`*_SqlMap.xml`、`SqlMapConfig.xml`）只是社区约定，**只能当提示、不能当判据**，
 *    故本识别器不接收文件名入参。
 *
 * ## 信号表
 *
 * | 信号 | 取值 |
 * |---|---|
 * | [Signal.ROOT_TAG] | 根元素 `sqlMap` / `sqlMapConfig`（MyBatis 3 是 `mapper` / `configuration`） |
 * | [Signal.DOCTYPE] | publicId / systemId ∈ [IBATIS_PUBLIC_IDS] / [IBATIS_SYSTEM_IDS] |
 * | [Signal.CLASSPATH] | 工程 classpath 上有 `com.ibatis.sqlmap.client.SqlMapClient` |
 *
 * 命中 **≥ [MIN_SIGNALS]（= 2）** 条判为 iBatis（[Verdict.isIbatis]），并把命中的信号集合一并返回
 * （诊断 + 测试断言用：只命中一条时能直接看出是哪条、为什么没到阈值）。
 *
 * ## 文件结构（纯逻辑与薄封装的分界）
 *
 * - **纯逻辑**（零 `com.intellij`、零副作用、输入全显式）：[recognize] 的四参重载 + [Verdict] +
 *   [Signal] + 各判定常量——单测可零 PSI 跑。
 * - **薄封装**：同为 [recognize] 的 `PsiFile` 重载 + [sqlMapClientOnClasspath]——只做
 *   `PsiFile → (根标签名, publicId, systemId, classpath 标志)` 的证据采集，判定一律回到纯逻辑。
 *
 * 消费方：iBatis 的 gutter / 预览接管链（`ui/gutter/SqlMapXmlGutterLineMarkerProvider`）与
 * `xml/XmlInlineSqlDetector.isClaimedByKnownStructure` 的让位分支——识别口径只有这一处，
 * 不允许各调用点各写一份标签名表。
 */
object IbatisXmlRecognizer {

    /**
     * 判为 iBatis 所需的最少命中信号数（三选二）。
     */
    const val MIN_SIGNALS: Int = 2

    /**
     * iBatis 根元素名（小写比较）：`sqlMap` = 语句映射文件，`sqlMapConfig` = 全局配置。
     * MyBatis 3 的 `mapper` / `configuration` 不在表内——本识别器对 MyBatis 文件必须判否。
     */
    private val IBATIS_ROOT_TAGS: Set<String> = setOf("sqlmap", "sqlmapconfig")

    /**
     * iBatis 2.x DOCTYPE 的合法 systemId（2.1.7 与 2.3.4.726 两代 DTD 的两种域形态，
     * 语句映射与全局配置各一份）。
     */
    val IBATIS_SYSTEM_IDS: Set<String> = setOf(
        "http://ibatis.apache.org/dtd/sql-map-2.dtd",
        "http://www.ibatis.com/dtd/sql-map-2.dtd",
        "http://ibatis.apache.org/dtd/sql-map-config-2.dtd",
        "http://www.ibatis.com/dtd/sql-map-config-2.dtd",
    )

    /** iBatis 2.x DOCTYPE 的合法 publicId（配置版与语句映射版同形）。 */
    val IBATIS_PUBLIC_IDS: Set<String> = setOf(
        "-//iBATIS.com//DTD SQL Map 2.0//EN",
        "-//ibatis.apache.org//DTD SQL Map 2.0//EN",
        "-//iBATIS.com//DTD SQL Map Config 2.0//EN",
        "-//ibatis.apache.org//DTD SQL Map Config 2.0//EN",
    )

    /** classpath 类特征：iBatis 2.x 门面接口（`ibatis-sqlmap` jar 的稳定入口）。 */
    const val SQL_MAP_CLIENT_CLASS: String = "com.ibatis.sqlmap.client.SqlMapClient"

    /** 命中信号（见类 KDoc 的表）。 */
    enum class Signal {
        /** 根元素 ∈ `sqlMap` / `sqlMapConfig`。 */
        ROOT_TAG,

        /** DOCTYPE 的 publicId 或 systemId 命中 iBatis 2.x 官方 DTD 标识。 */
        DOCTYPE,

        /** 工程 classpath 上有 `com.ibatis.sqlmap.client.SqlMapClient`。 */
        CLASSPATH,
    }

    /**
     * 识别结论：是否 iBatis + 命中的信号集合（诊断用——只命中一条时看这里就知道差哪条）。
     */
    data class Verdict(
        val isIbatis: Boolean,
        val signals: Set<Signal>,
    ) {
        /** 命中信号数（= [signals] 大小；[Signal] 集合无重复，等价 `signals.size`）。 */
        val signalCount: Int get() = signals.size
    }

    // ------------------------------------------------------------------
    // 纯逻辑（零 com.intellij / 零副作用）
    // ------------------------------------------------------------------

    /**
     * 三选二判定（纯函数）。四个入参全部显式传入，调用方负责取证（见 `PsiFile` 重载）。
     *
     * @param rootTagName 根标签名；非 XML / 无根元素传 null。
     * @param doctypePublicId DOCTYPE 的 publicId（`PUBLIC` 形态才有）；无 DOCTYPE / 仅 SYSTEM 传 null。
     * @param doctypeSystemId DOCTYPE 的 systemId（`SYSTEM` 与 `PUBLIC` 两种形态都可能有）。
     * @param hasIbatisOnClasspath 工程 classpath 上是否有 [SQL_MAP_CLIENT_CLASS]。
     */
    fun recognize(
        rootTagName: String?,
        doctypePublicId: String?,
        doctypeSystemId: String?,
        hasIbatisOnClasspath: Boolean,
    ): Verdict {
        val signals = linkedSetOf<Signal>()
        if (rootTagName?.trim()?.lowercase(Locale.ROOT) in IBATIS_ROOT_TAGS) {
            signals += Signal.ROOT_TAG
        }
        if (doctypePublicId?.trim() in IBATIS_PUBLIC_IDS || doctypeSystemId?.trim() in IBATIS_SYSTEM_IDS) {
            signals += Signal.DOCTYPE
        }
        if (hasIbatisOnClasspath) {
            signals += Signal.CLASSPATH
        }
        return Verdict(signals.size >= MIN_SIGNALS, signals)
    }

    /** 是否 iBatis 文件（[recognize] 的布尔简写）。 */
    fun isIbatis(
        rootTagName: String?,
        doctypePublicId: String?,
        doctypeSystemId: String?,
        hasIbatisOnClasspath: Boolean,
    ): Boolean = recognize(rootTagName, doctypePublicId, doctypeSystemId, hasIbatisOnClasspath).isIbatis

    // ------------------------------------------------------------------
    // 薄封装（只在 `PsiFile` 与纯逻辑之间搬证据；判定不在这里）
    // ------------------------------------------------------------------

    /**
     * 从 PSI 取证据后判定（**须在读操作内调用**；内部包 [ReadAction]，gutter / annotator 路径天然
     * 持有读权限）。
     *
     * 证据采集：根标签 = [XmlFile.getRootTag]；DOCTYPE = `file.document.prolog.doctype` 的
     * publicId / systemId；classpath 由 [sqlMapClientOnClasspath] 探。
     *
     * 非 XML 文件 / 解析不出文档时按「无信号」处理（全 null + classpath 探针结果），不抛异常。
     */
    fun recognize(file: PsiFile): Verdict = ReadAction.compute<Verdict, Throwable> {
        val xml = file as? XmlFile
        val rootTagName = xml?.rootTag?.name
        val doctype = xml?.document?.prolog?.doctype
        recognize(
            rootTagName = rootTagName,
            doctypePublicId = publicIdOf(doctype),
            doctypeSystemId = systemIdOf(doctype),
            hasIbatisOnClasspath = sqlMapClientOnClasspath(file.project),
        )
    }

    /** 工程 classpath 上是否有 [SQL_MAP_CLIENT_CLASS]（**须在读操作内调用**）。 */
    fun sqlMapClientOnClasspath(project: Project): Boolean =
        runCatching {
            // 与 ProjectTypeDetector 同口径：allScope 覆盖库（module 级 scope 不含依赖 jar 时恒 null）。
            JavaPsiFacade.getInstance(project)
                .findClass(SQL_MAP_CLIENT_CLASS, GlobalSearchScope.allScope(project)) != null
        }.getOrDefault(false)

    /**
     * DOCTYPE 的 publicId。
     *
     * `XmlDoctype` 接口声明 `@NlsSafe String getPublicId()`（非空），但 243 平台实现
     * `XmlDoctypeImpl.getPublicId()` 是 `@Nullable`（`SYSTEM` 形态 / 裸 `<!DOCTYPE x>` 时返回 null）——
     * 两种映射下都能拿到 null 而不炸：平台类型无断言时直得 null，插入非空断言时由 runCatching 归 null。
     */
    private fun publicIdOf(doctype: XmlDoctype?): String? =
        runCatching { doctype?.publicId }.getOrNull()

    /** DOCTYPE 的 systemId（同 [publicIdOf]：接口非空 / 实现可空，一致收口）。 */
    private fun systemIdOf(doctype: XmlDoctype?): String? =
        runCatching { doctype?.systemId }.getOrNull()
}
