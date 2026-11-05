package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperInjector
import cn.song.mybatisplus.visualizer.ui.inject.SqlMapperLanguage
import cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGate
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * mapper XML **补全全链路列名候选**守卫（T-XmlCmpl2）。
 *
 * 缺口来源：T-XmlCmplDispatch（687b0a1）新增的 XmlSqlCompletionDispatchTest 只断言**关键字**
 * （`DISTINCT`）——关键字候选在 main 实体解析失败（main=null）时同样产出，故那套件对用户实际
 * 诉求「在语句体里补主实体列名（如 `username`）」**零覆盖**：其夹具 XML 的 namespace 指向不存在
 * 的类（无对应 Java 件），全链路下 main 恒为 null，列名分支从未被真链路走到。
 *
 * 本套件补齐该面：真平台全链路（`completeBasic`：dummy 注入 → `toInjectedIfAny` → 按光标偏移
 * 语言派发 MpSqlMapper → 贡献器宿主还原三形态支① → 门控 → resolveXml → SourceSqlCompleter →
 * prefixMatches）下断言**主实体列名**产出，覆盖两条形态：
 *  - 语句文本中段（注入区间内、前缀空）：列名 `username`/`id` 在候选内；
 *  - 注入区间**尾端点**（光标紧贴文本节点末尾、右侧紧跟子标签 `<include/>`——用户行尾补全的
 *    典型形态）：列名候选照常产出（`findElementAt(textLength)` 边界 + 支① 区间尾映射）。
 *
 * 实体解析夹具说明：`resolveMapperGenericEntity` 只认「基类全限定名后缀 BaseMapper」，Light
 * 沙盒里 `com.baomidou...BaseMapper` 跨件 resolve 为 null（`XmlSqlCompletionContributorTest`
 * KDoc 波次 1 已登记的同源限制）——故用**本地 `base.BaseMapper<T>`** 复现 demo 仓
 * `UserMapper extends BaseMapper<User>` 的泛型解析形态，绕开夹具限制而保留被测代码路径。
 *
 * 前缀注意（本套件首轮红灯的成因，非产品缺陷）：平台 prefixMatcher 大小写敏感
 * （`prefixMatches("INDEX")` 对前缀 `i` 为 false），故小写前缀只命中同名小写列名；若命中恰为
 * 1 项，显式补全会**自动插入**该唯一候选（lookup 不呈现 → `lookupElementStrings` 为 null）。
 * 故区间尾端用例给实体备两枚同首字母小写列（`id`/`image`）以稳定呈现 lookup。
 *
 * EP 注册：Light 环境不加载本插件 plugin.xml，测试经 `CompletionContributorEP` 程序化注册
 * 双语言 bean（形态同 XmlSqlCompletionDispatchTest）；声明面由该套件的 plugin.xml 资源钉覆盖。
 */
class XmlSqlCompletionInjectedChainTest : BasePlatformTestCase() {

    /** 语句文本中段（注入区间内、前缀空——候选不被前缀过滤）。 */
    private val midStatementXml =
        """<mapper namespace="com.example.UserMapper">
  <select id="selectUserPageXml" resultMap="BaseResultMap">
    select <caret> from user
  </select>
</mapper>"""

    /**
     * 注入区间尾端点：光标紧贴文本节点末尾（`select i` 与右侧 `<include/>` 之间）；
     * 前缀 `i` 命中两枚小写列（`id`/`image`）→ lookup 稳定呈现。
     */
    private val rangeEndXml =
        """<mapper namespace="com.example.UserMapper">
  <select id="selectUserPageXml" resultMap="BaseResultMap">
    select i<caret><include refid="Base_Column_List"/>
    from user
  </select>
</mapper>"""

    private val originalProbe = XmlSynHiGate.foreignInjectionProbe

    override fun setUp() {
        super.setUp()
        // D68 探针缝固定「平台不注入」——免受测试环境 EP 内容漂移（wiring/dispatch 套件同口径）
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

    /** demo 仓同名形态：`UserMapper extends BaseMapper<User>`（BaseMapper 用本地件复现泛型解析）。 */
    private fun addDemoShapedEntity() {
        myFixture.addFileToProject("base/BaseMapper.java", "package base;\n\npublic interface BaseMapper<T> {\n}\n")
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;

            import base.BaseMapper;
            import java.util.List;

            @com.baomidou.mybatisplus.annotation.TableName("user")
            public class User {
                private Integer id;
                private String image;
                private String username;
                private String password;
            }

            public interface UserMapper extends BaseMapper<User> {
                List<User> selectUserPageXml();
            }
            """.trimIndent(),
        )
    }

    /**
     * 物理临时文件打开 mapper XML 并定位光标（wiring/dispatch 两坑口径：注入 enumerate 管线对
     * 非物理文件短路，须物理文件 + document 已提交）。
     */
    private fun openMapperWithCaret(content: String): Pair<XmlFile, Int> {
        val caret = content.indexOf("<caret>")
        require(caret >= 0) { "content 缺 <caret> 标记" }
        val dir = FileUtil.createTempDirectory("xmlcmplchain", "", true)
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

    /** 预热注入管线并断言光标处注入生效 + 注入文件为 VirtualFileWindow（派发/支① 前提）。 */
    private fun assertInjectedAtCaretWithMapperLanguage(file: XmlFile, caret: Int) {
        val manager = InjectedLanguageManager.getInstance(project)
        PsiTreeUtil.collectElements(file) { it is XmlText || it is XmlAttributeValue }.forEach { host ->
            manager.enumerateEx(host, file, false) { _, _ -> }
        }
        val injected = manager.findInjectedElementAt(file, caret)
        assertNotNull("光标处应处于 MpSqlMapper 注入片段内（注入门控/物理文件契约）", injected)
        val injectedFile = injected!!.containingFile
        assertTrue(
            "注入文件 virtualFile 应为 VirtualFileWindow（宿主还原支① 前提）",
            injectedFile.virtualFile is VirtualFileWindow,
        )
    }

    private fun completeBasicAndGetStrings(): List<String> {
        DumbModeTestUtils.waitForSmartMode(project)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    /**
     * 全链路 · 语句文本中段：主实体（demo 同款 BaseMapper 泛型路径）列名必须在候选内。
     * 缺 main 解析时红灯形态：仅关键字（`username`/`id` 非关键字 → 用户按补全空响应）。
     */
    fun test全链路_语句文本中段_主实体列名候选产出() {
        addDemoShapedEntity()
        val (file, caret) = openMapperWithCaret(midStatementXml)
        assertInjectedAtCaretWithMapperLanguage(file, caret)
        val strings = completeBasicAndGetStrings()
        assertTrue(
            "全链路应产出主实体列 username（dispatch+宿主还原+门控+resolveXml+SourceSqlCompleter）：$strings",
            strings.contains("username"),
        )
        assertTrue("同批应产出主实体列 id：$strings", strings.contains("id"))
    }

    /**
     * 全链路 · 注入区间尾端点（光标紧贴文本节点末尾、右侧紧跟子标签）：列名候选照常产出。
     * `id`/`image` 均非关键字 → 其在场即证列名分支在区间尾端点（`findElementAt(textLength)`
     * 边界 + 支① 区间尾映射）仍走到。
     */
    fun test全链路_注入区间尾端点_列名候选产出() {
        addDemoShapedEntity()
        val (file, caret) = openMapperWithCaret(rangeEndXml)
        assertInjectedAtCaretWithMapperLanguage(file, caret)
        val strings = completeBasicAndGetStrings()
        assertTrue("注入区间尾端点应产出主实体列 id（前缀 i；非关键字，证列名分支）：$strings", strings.contains("id"))
        assertTrue("同批应产主实体列 image：$strings", strings.contains("image"))
    }

    /**
     * dumb 态派发门（T-XmlCmpl2 修复钉）：平台 `forLanguageHonorDumbness` →
     * `DumbService.filterByDumbAwareness` 在 dumb 态**丢弃一切非 DumbAware 贡献器**——
     * 本类须为 [com.intellij.openapi.project.DumbAware]（与同包 AnnotationSqlCompletionContributor、
     * WrapperCompletionContributor 两兄弟口径一致），否则索引期（DbSrc 方言 push / 项目初扫等
     * dumb 窗口）按补全整体零响应。反向验证：移除 DumbAware 声明 → 本用例红。
     */
    fun test_dumb态_FilterByDumbAwareness仍派发本贡献器() {
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val consulted = CompletionContributor
                .forLanguageHonorDumbness(SqlMapperLanguage, project)
                .any { it is XmlSqlCompletionContributor }
            assertTrue(
                "dumb 态应仍派发 XmlSqlCompletionContributor（DumbAware 口径缺失时平台整链丢弃）",
                consulted,
            )
        }
    }
}
