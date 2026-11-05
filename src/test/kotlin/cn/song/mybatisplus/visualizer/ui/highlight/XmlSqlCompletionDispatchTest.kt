package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperInjector
import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperLanguage
import cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGate
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.patterns.ElementPattern
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * XmlSqlCompletionContributor **平台派发全链路**守卫（mapper XML 补全沙盒失效修复轮）。
 *
 * 根因（243 平台 app-client.jar javap 实证）：
 *  - `CompletionService.getVariantsFromContributors` → `CompletionContributor.forParameters` →
 *    `forLanguageHonorDumbness(PsiUtilCore.getLanguageAtOffset(position.containingFile, offset), project)`
 *    ——派发语言取 **position 所在文件**光标偏移处的语言；
 *  - `CompletionInitializationUtil.toInjectedIfAny`：光标落在注入片段内时 position 文件切换为
 *    **注入文件**、坐标换注入侧（`OffsetsInFile.toInjectedIfAny` 走 `DocumentWindow.hostToInjected`）；
 *  - 本插件 `SqlMapperInjector` 把 mapper 语句文本与白名单属性值注入自研 [SqlMapperLanguage]
 *    （`Language("MpSqlMapper")`，**非 XML 方言**）——注入开启（设置默认 true）时语句体光标处
 *    语言 = MpSqlMapper，`completion.contributor language="XML"` 的贡献器**永不被咨询**：
 *    表/列/`#{}`/OGNL/关键字补全全灭（dev-bak 沙盒 3.19「列名补全未验」的真实根因；
 *    动态标签 `<` 位与属性名位因 `<` 终结文本宿主落在注入区间外，仍走 XML 派发不受影响）。
 *
 * 修复形态（本测试钉住两层）：
 *  1. **派发层**：plugin.xml 对 `MpSqlMapper` 语言**双注册**同一实现类（注入位派发入口）+
 *     `language="XML"`（宿主形态保留）。Light 测试环境不加载本插件 plugin.xml，测试经
 *     `CompletionContributorEP` 程序化注册同形态 bean（`EP.getPoint` + registerExtension）；
 *     声明在场由 plugin.xml 资源断言钉住（同 SqlMapperInjectorWiringTest 资源钉口径）。
 *  2. **坐标层**：注入形态下贡献器内宿主还原须从**注入文件自身**取 `DocumentWindow`
 *     （`injectedFile.virtualFile as? VirtualFileWindow`）——`parameters.editor.document` 是
 *     宿主编辑器文档（非 DocumentWindow），旧取法拿不到映射、注入侧偏移直接当宿主偏移用，
 *     position 落 `<mapper` 头部 → findStatementTag null → 零补全（首轮红灯形态）。
 *
 * fixture 约束（同 SqlMapperInjectorWiringTest 两坑实证）：注入 enumerate 管线对非物理文件短路，
 * 须物理临时文件 + document 已提交；补全触发走 `myFixture.completeBasic()`（真实补全会话：
 * dummy 插入 → toInjectedIfAny → forParameters 派发 → 贡献器 → lookup），与既有直调套件
 * （XmlSqlCompletionContributorTest 手工建参绕开派发）互补。
 */
class XmlSqlCompletionDispatchTest : BasePlatformTestCase() {

    /** 语句文本中段（注入区间内、无前缀）触发补全：应产出 SQL 关键字（DISTINCT 未在文中出现）。 */
    private val mapperXml =
        """<mapper namespace="com.example.UserMapper">
  <select id="selectList">SELECT * FROM <caret> WHERE 1=1</select>
</mapper>"""

    private val originalProbe = XmlSynHiGate.foreignInjectionProbe

    override fun setUp() {
        super.setUp()
        // D68 探针缝固定「平台不注入」——免受测试环境 EP 内容漂移（wiring test 同口径）
        XmlSynHiGate.foreignInjectionProbe = { _, _ -> false }
        InjectedLanguageManager.getInstance(project)
            .registerMultiHostInjector(SqlMapperInjector(), testRootDisposable)
        // Light 测试环境不加载本插件 plugin.xml——按修复形态程序化注册双语言 EP bean
        registerContributorEp("XML")
        registerContributorEp("MpSqlMapper")
    }

    override fun tearDown() {
        XmlSynHiGate.foreignInjectionProbe = originalProbe
        super.tearDown()
    }

    /**
     * 程序化注册 completionContributor EP bean（与 plugin.xml 声明同形态）。
     * descriptor 须非空（LazyExtensionInstance.getInstance 对 pluginDescriptor 参数有 NotNull 断言，
     * 首轮红灯实证）——挂核心插件描述符：其 classloader 委托系统 classpath，实例化测试类可达。
     */
    private fun registerContributorEp(language: String) {
        val coreDescriptor = PluginManagerCore.getPlugin(PluginId.getId("com.intellij"))
        val fqn = XmlSqlCompletionContributor::class.java.name
        val bean = if (coreDescriptor != null) {
            CompletionContributorEP(language, fqn, coreDescriptor)
        } else {
            CompletionContributorEP().apply {
                this.language = language
                this.implementationClass = fqn
            }
        }
        CompletionContributor.EP.getPoint(ApplicationManager.getApplication())!!
            .registerExtension(bean, testRootDisposable)
    }

    /** 物理临时文件打开 mapper XML 并定位光标（wiring test 两坑口径：物理文件 + document 提交）。 */
    private fun openMapperWithCaret(content: String): Pair<XmlFile, Int> {
        val caret = content.indexOf("<caret>")
        require(caret >= 0) { "content 缺 <caret> 标记" }
        val dir = FileUtil.createTempDirectory("cmpldispatch", "", true)
        val ioFile = File(dir, "mapper.xml").apply { writeText(content.replace("<caret>", "")) }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: throw IllegalStateException("temp file not in VFS")
        myFixture.configureFromExistingVirtualFile(vf)
        myFixture.editor.caretModel.moveToOffset(caret)
        val file = myFixture.file as XmlFile
        val pdm = PsiDocumentManager.getInstance(project)
        pdm.getDocument(file)?.let { doc -> if (!pdm.isCommitted(doc)) pdm.commitDocument(doc) }
        return file to caret
    }

    /** 预热注入管线（喂入宿主触发 getLanguagesToInject）并断言注入位在光标处生效/缺席。 */
    private fun assertInjectionAtCaret(file: XmlFile, caret: Int, expected: Boolean) {
        val manager = InjectedLanguageManager.getInstance(project)
        PsiTreeUtil.collectElements(file) { it is XmlText || it is XmlAttributeValue }.forEach { host ->
            manager.enumerateEx(host, file, false) { _, _ -> }
        }
        val injected = manager.findInjectedElementAt(file, caret)
        if (expected) {
            assertNotNull("注入位应在光标处生效（门控/物理文件契约）", injected)
        } else {
            assertNull("设置关闭时注入位应缺席（让位门反向验证）", injected)
        }
    }

    /** 等智能态后触发真实补全会话，收集 lookup 候选串（EDT 可用的事件泵等待，dumb 过滤器依赖此态）。 */
    private fun completeBasicAndGetStrings(): List<String> {
        DumbModeTestUtils.waitForSmartMode(project)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    /**
     * 注入开启（默认态）：语句文本光标在 MpSqlMapper 注入片段内——平台真实补全会话
     * （completeBasic）须产出 SQL 关键字候选。修复前红灯形态：production 无 MpSqlMapper EP
     * （派发层断）/ 测试程序化注册 EP 后坐标还原断（position 落 mapper 头部零补全）。
     */
    fun test注入开启_语句文本内补全_平台全链路产出候选() {
        val (file, caret) = openMapperWithCaret(mapperXml)
        assertInjectionAtCaret(file, caret, expected = true)
        val strings = completeBasicAndGetStrings()
        assertTrue(
            "注入片段内补全应至少产出关键字 DISTINCT（全链路：派发+宿主还原+候选计算）：$strings",
            strings.contains("DISTINCT"),
        )
    }

    /**
     * 注入关闭：光标落宿主 XML——补全经 language="XML" 注册派发，同内容同断言
     * （宿主形态回归钉：修复不得破坏既有 XML 派发路径）。
     */
    fun test注入关闭_宿主XML形态补全_平台全链路产出候选() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.xmlSynHiEnabled
        settings.loadState(settings.state.copy(xmlSynHiEnabled = false))
        try {
            val (file, caret) = openMapperWithCaret(mapperXml)
            assertInjectionAtCaret(file, caret, expected = false)
            val strings = completeBasicAndGetStrings()
            assertTrue(
                "宿主 XML 形态补全应产出关键字 DISTINCT：$strings",
                strings.contains("DISTINCT"),
            )
        } finally {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = old))
        }
    }

    /**
     * **生产坐标形态**钉（fixture/生产分叉的判别器）：注入形态下平台建参是
     * 「position/offset=注入侧 + editor=宿主编辑器（文档非 window）」（243 生产链字节码实证：
     * toInjectedIfAny 后建参 + invokeCompletionWithTracing 的 getTopLevelEditor 解包），而
     * completeBasic 经 EditorTestFixture.getCompletionEditor 包 EditorWindow——旧还原代码在
     * fixture 形态恰好工作、生产态拿不到映射（首轮沙盒失效第二层根因）。本用例照生产形态
     * 手工建参（注入位 position/offset + myFixture.editor 宿主文档）直调贡献器：
     * 还原须从注入文件 DocumentWindow 取映射，旧代码此形态零补全（反向验证判别器）。
     */
    fun test注入形态_生产坐标建参_宿主还原产出候选() {
        val (file, caret) = openMapperWithCaret(mapperXml)
        assertInjectionAtCaret(file, caret, expected = true)
        val manager = InjectedLanguageManager.getInstance(project)
        val injectedLeaf = manager.findInjectedElementAt(file, caret)
            ?: throw AssertionError("注入位应存在")
        val injectedFile = injectedLeaf.containingFile!!
        val window = injectedFile.virtualFile as? VirtualFileWindow
            ?: throw AssertionError("注入文件 virtualFile 应为 VirtualFileWindow")
        val parameters = CompletionParameters(
            injectedLeaf,
            injectedFile,
            CompletionType.BASIC,
            /* offset = 注入侧坐标（toInjectedIfAny 语义） */ window.documentWindow.hostToInjected(caret),
            /* invocationCount */ 1,
            /* editor = 生产形态：宿主编辑器（文档非 DocumentWindow） */ myFixture.editor,
            object : CompletionProcess {
                override fun isAutopopupCompletion() = false
            },
        )
        @Suppress("ApiStatus")
        val resultSet = ProductionFormResultSet()
        XmlSqlCompletionContributor().fillCompletionVariants(parameters, resultSet)
        val strings = resultSet.collected.map { it.lookupString }
        assertTrue(
            "生产坐标形态（注入侧 offset + 宿主编辑器）宿主还原应产出关键字 DISTINCT：$strings",
            strings.contains("DISTINCT"),
        )
    }

    /** plugin.xml 双语言 EP 声明钉（派发层修复的声明面，资源断言同 wiring test 口径）。 */
    fun test_pluginXml双语言completionContributor声明在场() {
        val pluginXml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        val fqn = Regex.escape(XmlSqlCompletionContributor::class.java.name)
        assertTrue(
            "plugin.xml 须含 language=\"XML\" 的 XmlSqlCompletionContributor 注册",
            Regex("<completion\\.contributor\\s+language=\"XML\"\\s+implementationClass=\"$fqn\"").containsMatchIn(pluginXml),
        )
        assertTrue(
            "plugin.xml 须含 language=\"MpSqlMapper\" 的 XmlSqlCompletionContributor 注册（注入片段派发入口）",
            Regex("<completion\\.contributor\\s+language=\"MpSqlMapper\"\\s+implementationClass=\"$fqn\"").containsMatchIn(pluginXml),
        )
    }

    /**
     * 直调收集器（照 XmlSqlCompletionContributorTest.CollectingResultSet 形态）：
     * 前缀恒匹配（生产形态用例只验还原坐标正确性，前缀语义由贡献器内 prefixMatches 自理）。
     */
    private class ProductionFormResultSet : CompletionResultSet(
        PrefixMatcher.ALWAYS_TRUE,
        java.util.function.Consumer<CompletionResult> { },
        XmlSqlCompletionContributor(),
    ) {
        private val sink = mutableListOf<LookupElement>()

        val collected: List<LookupElement> get() = sink

        override fun addElement(element: LookupElement) {
            sink += element
        }

        override fun withPrefixMatcher(matcher: PrefixMatcher): CompletionResultSet = this

        override fun withPrefixMatcher(prefix: String): CompletionResultSet = this

        override fun withRelevanceSorter(sorter: CompletionSorter): CompletionResultSet = this

        override fun addLookupAdvertisement(text: String) = Unit

        override fun caseInsensitive(): CompletionResultSet = this

        override fun restartCompletionOnPrefixChange(prefixCondition: ElementPattern<String>) = Unit

        override fun restartCompletionWhenNothingMatches() = Unit
    }
}
