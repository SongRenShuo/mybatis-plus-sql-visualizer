package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.intellij.openapi.util.TextRange

/**
 * T-Ibatis2Support 券 D：**语句宿主根放宽**（`mapper` → `mapper` / iBatis `sqlMap`）的用例。
 *
 * 覆盖券 D 第 1/4/6 条（注入宿主放宽、折叠、反缩进/变更局部性/模板上下文）与「主动改写文档的
 * 便利功能不随放宽进 iBatis」这条边界（[ParamBraceTypedHandler]）：
 *
 *  - **正向**：根 `<sqlMap>` 文件的语句文本被注入（[SqlMapperInjector] 实际 startInjecting +
 *    addPlace）、[XmlSynHiGate.allowStatementText]/[XmlSynHiGate.inMapperFile] 放行、折叠建区、
 *    变更局部性放大到语句标签、模板上下文为真；
 *  - **二次判定面（不过度放宽）**：`<sqlMapConfig>`（iBatis 全局配置，无语句体）**不**放行；
 *    非语句宿主根（`<configuration>`）**不**放行；
 *  - **边界**：`ParamBraceTypedHandler`（敲 `#` 自动补 `{}`，会**写入** MyBatis 语法）在 iBatis
 *    文件里**不**接管——放宽只给被动能力，主动改写文档的便利功能保持 MyBatis 专属。
 *
 * 反向验证（券 D ①）：摘掉 [XmlSynHiGate.inMapperFile] 的 `sqlMap` 放宽 → 本类 iBatis 用例必红
 * （注入不放行 / 折叠零区 / 脏区为 null / 上下文为假）。
 *
 * **命名口径**：本类刻意**不**以 `Guard` 收尾——它验的是能力放宽的正向行为与边界，不是「扫描判定
 * 内核 + 自证语料」形状的守卫；而 `convention/GuardSelfTestCoverageMetaGuardTest` 的文件名闭包会把
 * 任何文件名含 `Guard` 的测试类当成必须登记进 `GUARD_CLASSES` 的守卫（未登记即红）。
 */
class IbatisStatementHostRootTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private val ibatisXml = """<sqlMap namespace="Demo">
  <select id="findById">
    SELECT * FROM t WHERE id = #id:VARCHAR#
  </select>
</sqlMap>"""

    private fun configureIbatis(xml: String = ibatisXml): XmlTag {
        myFixture.configureByText("Demo_SqlMap.xml", xml)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, XmlTag::class.java).first { it.name == "select" }
    }

    /** 直调注入器的记录式 registrar（与 XmlSynHiGateProbeTaxTest 同形）。 */
    private class RecordingRegistrar : MultiHostRegistrar {
        var started = false
            private set
        var places = 0
            private set

        override fun startInjecting(language: Language): MultiHostRegistrar {
            started = true
            return this
        }

        override fun addPlace(
            prefix: String?,
            suffix: String?,
            host: PsiLanguageInjectionHost,
            rangeInsideHost: TextRange,
        ): MultiHostRegistrar {
            places++
            return this
        }

        override fun doneInjecting() {}
    }

    private fun withNoForeignInjection(block: () -> Unit) {
        val original = XmlSynHiGate.foreignInjectionProbe
        XmlSynHiGate.foreignInjectionProbe = { _, _ -> false }
        try {
            block()
        } finally {
            XmlSynHiGate.foreignInjectionProbe = original
        }
    }

    // ------------------------------------------------------------------
    // 1. 注入宿主放宽（券 D 第 1 条）
    // ------------------------------------------------------------------

    fun test券D_根sqlMap_语句文本守卫放行() {
        val select = configureIbatis()
        assertTrue("iBatis `<sqlMap>` 根的语句标签应放行 inMapperFile", XmlSynHiGate.inMapperFile(select))
        assertTrue("iBatis 语句文本应放行 allowStatementText", XmlSynHiGate.allowStatementText(select))
    }

    fun test券D_根sqlMap_语句文本实际被注入() {
        val select = configureIbatis()
        val host = select.children.filterIsInstance<XmlText>().first { it.text.contains("SELECT") }
        val registrar = RecordingRegistrar()
        withNoForeignInjection {
            SqlMapperInjector().getLanguagesToInject(registrar, host)
        }
        assertTrue("iBatis 语句文本应被注入（startInjecting）", registrar.started)
        assertEquals("注入位恰一个（区间级互补：无外来占用 → 整段）", 1, registrar.places)
    }

    fun test券D_二次判定_sqlMapConfig不放行() {
        // iBatis 全局配置文件（无语句体）不是语句宿主——放宽只认 `sqlMap`
        myFixture.configureByText(
            "SqlMapConfig.xml",
            "<sqlMapConfig><settings cacheModelsEnabled=\"true\"/></sqlMapConfig>",
        )
        val root = (myFixture.file as XmlFile).rootTag!!
        assertFalse("`<sqlMapConfig>` 不得被放宽进来", XmlSynHiGate.inMapperFile(root))
        assertFalse("`<sqlMapConfig>` 语句文本守卫不放行", XmlSynHiGate.allowStatementText(root))
    }

    fun test券D_二次判定_非语句宿主根仍拒绝() {
        myFixture.configureByText(
            "configuration.xml",
            "<configuration><select id=\"a\">SELECT 1 FROM t</select></configuration>",
        )
        val select = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlTag::class.java).first { it.name == "select" }
        assertFalse("非语句宿主根仍拒绝（MyBatis 配置文件不是 mapper）", XmlSynHiGate.inMapperFile(select))
    }

    fun test券D_MyBatis回归_mapper根仍放行() {
        myFixture.configureByText(
            "mapper.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT 1 FROM t</select></mapper>",
        )
        val select = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlTag::class.java).first { it.name == "select" }
        assertTrue("MyBatis `mapper` 根判定不回归", XmlSynHiGate.inMapperFile(select))
        assertTrue(XmlSynHiGate.isStatementHostRootName("mapper"))
        assertFalse("MyBatis 根不得被判成 iBatis", XmlSynHiGate.isIbatisSqlMapRoot(select))
    }

    // ------------------------------------------------------------------
    // 2. 折叠（券 D 第 4 条）
    // ------------------------------------------------------------------

    fun test券D_折叠_sqlMap多行语句建区() {
        val select = configureIbatis()
        val document: Document = PsiDocumentManager.getInstance(project).getDocument(myFixture.file)!!
        val regions = SqlMapperFoldingBuilder().buildFoldRegions(myFixture.file, document, quick = false)
        assertEquals("iBatis 多行 select 应建一个 fold 区", 1, regions.size)
        assertEquals("占位文本仍为 `标签名#id`", "select#findById", regions.single().placeholderText)
        assertEquals("折叠范围须为整个语句标签", select.textRange, regions.single().range)
    }

    fun test券D_折叠_非语句宿主根仍零区() {
        myFixture.configureByText(
            "config.xml",
            "<configuration><select id=\"s\">\n  SELECT 1\n</select></configuration>",
        )
        val document: Document = PsiDocumentManager.getInstance(project).getDocument(myFixture.file)!!
        val regions = SqlMapperFoldingBuilder().buildFoldRegions(myFixture.file, document, quick = false)
        assertEquals("非语句宿主根不得建区", 0, regions.size)
    }

    // ------------------------------------------------------------------
    // 3. 变更局部性 / 模板上下文（券 D 第 6 条）
    // ------------------------------------------------------------------

    fun test券D_变更局部性_放大到语句标签() {
        configureIbatis()
        val text = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java)
            .first { it.text.contains("SELECT") }
        val scope = SqlMapperChangeLocalityDetector().getChangeHighlightingDirtyScopeFor(text)
        assertNotNull("iBatis 语句文本编辑须放大脏区（注入区间重算）", scope)
        assertEquals("脏区收窄到所属语句标签", "select", (scope as XmlTag).name)
    }

    fun test券D_变更局部性_非语句宿主文件不介入() {
        myFixture.configureByText(
            "other.xml",
            "<configuration><settings><setting name=\"a\" value=\"b\"/></settings></configuration>",
        )
        val text = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java).firstOrNull()
        if (text != null) {
            assertNull(
                "非语句宿主文件不放大脏区",
                SqlMapperChangeLocalityDetector().getChangeHighlightingDirtyScopeFor(text),
            )
        }
    }

    fun test券D_模板上下文_语句内为真_非语句宿主为假() {
        configureIbatis()
        val ctx = MybatisSqlTemplateContextType()
        val file = myFixture.file
        val selectText = PsiTreeUtil.collectElementsOfType(file, XmlText::class.java)
            .first { it.text.contains("SELECT") }
        assertTrue("iBatis 语句文本内 → 上下文为真", ctx.isInContext(file, selectText.textRange.startOffset + 2))

        myFixture.configureByText("SqlMapConfig2.xml", "<sqlMapConfig><settings/></sqlMapConfig>")
        assertFalse("<sqlMapConfig> 根 → 上下文为假", ctx.isInContext(myFixture.file, 20))
    }

    // ------------------------------------------------------------------
    // 4. 边界：主动改写文档的便利功能不随放宽进 iBatis（ParamBraceTypedHandler）
    // ------------------------------------------------------------------

    fun test券D_敲井号_IBatis文件不自动补花括号() {
        // `#{}` 是 MyBatis 语法；在 iBatis 文件里敲 `#` 自动补 `{}` 会**写入错误语法**，
        // 必须保持 MyBatis 专属（放宽只给被动能力）。生产实现不得接管 → CONTINUE。
        val base = "<sqlMap namespace=\"Demo\">\n" +
            "  <select id=\"a\">SELECT * FROM t WHERE id = <caret></select>\n" +
            "</sqlMap>"
        val caret = base.indexOf("<caret>")
        myFixture.configureByText("Demo_SqlMap.xml", base.replace("<caret>", ""))
        myFixture.editor.caretModel.moveToOffset(caret)
        val result = ParamBraceTypedHandler().beforeCharTyped(
            '#', project, myFixture.editor, myFixture.file, myFixture.file.fileType,
        )
        assertEquals(
            "iBatis 文件敲 `#` 不得接管（不得补 `{}`）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.CONTINUE,
            result,
        )
    }

    fun test券D_敲井号_MyBatis文件仍接管() {
        // 对照：MyBatis 路径行为不变（同形夹具、根换 mapper）
        val base = "<mapper namespace=\"n\">\n" +
            "  <select id=\"a\">SELECT * FROM t WHERE id = <caret></select>\n" +
            "</mapper>"
        val caret = base.indexOf("<caret>")
        myFixture.configureByText("mapper.xml", base.replace("<caret>", ""))
        myFixture.editor.caretModel.moveToOffset(caret)
        val result = ParamBraceTypedHandler().beforeCharTyped(
            '#', project, myFixture.editor, myFixture.file, myFixture.file.fileType,
        )
        assertEquals(
            "MyBatis 文件仍接管（STOP）",
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            result,
        )
    }

    /** 廉价判据自证：`isStatementHostRootName` 只认两个根名，其余（含 null）一律否。 */
    fun test券D_根名判据_只认mapper与sqlMap() {
        assertTrue(XmlSynHiGate.isStatementHostRootName("mapper"))
        assertTrue(XmlSynHiGate.isStatementHostRootName("sqlMap"))
        assertTrue("大小写不敏感（iBatis 声明可写 SQLMAP）", XmlSynHiGate.isStatementHostRootName("sqlmap"))
        assertFalse(XmlSynHiGate.isStatementHostRootName("sqlMapConfig"))
        assertFalse(XmlSynHiGate.isStatementHostRootName("configuration"))
        assertFalse(XmlSynHiGate.isStatementHostRootName(null))
    }
}
