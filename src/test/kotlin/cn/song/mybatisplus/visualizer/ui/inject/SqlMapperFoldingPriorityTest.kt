package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.Language
import com.intellij.lang.folding.LanguageFolding
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 折叠**优先级**的结构取证（T-XmlGap 收口：计划 §十 遗留项「折叠占位 `select#id` 与平台标签折叠
 * 谁胜出」的隔离观察）。
 *
 * ## 判据链（全部落在平台源码 + 实测上，不靠猜）
 *
 * 1. XML 语言的折叠 builder 由 `LanguageFolding.allForLanguageOrAny(XML)` 汇总；EP 声明里的
 *    `order="first"` 使我方排在**第 0 位**（本类断言）。
 * 2. 多个 builder 由 `CompositeFoldingBuilder` 合并，其合并规则是
 *    **「同范围先到先得」**（`rangesCoveredByDescriptors.add(descriptor.getRange())` 为 false 即丢弃；
 *    `intellij.platform.core.jar` 内 `LanguageFolding`/`CompositeFoldingBuilder` 反编译实读）——
 *    所以「我方区会不会被平台吞掉」只取决于**范围是否逐字相同**。
 * 3. 实测范围：我方 = **整个标签**（`select` 的 `textRange`），平台 `XmlFoldingBuilder` = **标签内容区**
 *    （开标签 `>` 之后到闭标签 `</` 之前）与**父标签内容区**——两者范围不同 ⇒ **不互相吞**，
 *    三个区在模型里共存。
 * 4. 于是「谁胜出」不是模型层去重决定的，而是**渲染层**：光标在标签体内按 `Ctrl+Minus` 折叠，
 *    命中的是包含光标的最内层区（平台的内容区，占位 `...`）；而 gutter 上位于 `<select>` 那一行的
 *    标记对应**我方整标签区**（占位 `select#id`）。真机读数见开发计划 §十「沙盒走查（五）」。
 *
 * 本类只钉 1~3（可自动复核的结构事实）；第 4 条是渲染行为，用真机读数记录，不做自动化断言
 * （Light 沙箱里编辑器的 folding model 恒为空——实测 `model.allFoldRegions` = 0，故无法在单测里
 * 观察渲染结果）。
 */
class SqlMapperFoldingPriorityTest : BasePlatformTestCase() {

    private val xml = """<mapper namespace="n">
  <select id="findById">
    SELECT * FROM t
    WHERE id = #{id}
  </select>
</mapper>"""

    private fun xmlFoldingBuilders(): List<com.intellij.lang.folding.FoldingBuilder> {
        myFixture.configureByText("mapper.xml", xml)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val xmlLanguage = Language.findLanguageByID("XML") ?: error("XML 语言不在")
        return LanguageFolding.INSTANCE.allForLanguageOrAny(xmlLanguage)
    }

    private fun regionsOf(builder: com.intellij.lang.folding.FoldingBuilder): List<com.intellij.lang.folding.FoldingDescriptor> {
        val document = PsiDocumentManager.getInstance(project).getDocument(myFixture.file)!!
        return LanguageFolding.buildFoldingDescriptors(builder, myFixture.file, document, false).toList()
    }

    /** 1：`order="first"` 生效——我方 builder 在 XML 语言里排第 0 位。 */
    fun testW8_我方折叠builder在XML语言中排第一() {
        val builders = xmlFoldingBuilders()
        assertTrue("XML 语言应有多个折叠 builder（我方 + 平台 XmlFoldingBuilder），实际 ${builders.size}", builders.size >= 2)
        assertEquals(
            "`order=\"first\"` 应使我方 SqlMapperFoldingBuilder 排第一（同范围先到先得时我方优先）；" +
                "实际顺序=${builders.map { it.javaClass.simpleName }}",
            SqlMapperFoldingBuilder::class.java,
            builders[0].javaClass,
        )
    }

    /** 3：我方区 = 整个标签；平台区 = 内容区；两者范围**不逐字相同** ⇒ 不被 `CompositeFoldingBuilder` 吞。 */
    fun testW8_我方整标签区与平台内容区范围不同_不被同范围去重吞掉() {
        val builders = xmlFoldingBuilders()
        val tag = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlTag::class.java).first { it.name == "select" }
        val ours = regionsOf(builders[0])
        val oursSelect = ours.singleOrNull { it.range == tag.textRange }
            ?: error("我方应有且仅有一个「整个 select 标签」区，实际=${ours.map { it.range }}")
        assertEquals("我方占位须为 `select#id`", "select#findById", oursSelect.placeholderText)

        val platform = builders.firstOrNull { it.javaClass.name == "com.intellij.lang.xml.XmlFoldingBuilder" }
            ?: error("平台 XmlFoldingBuilder 应在 XML 语言的 builder 列表内")
        val platformRanges = regionsOf(platform).map { it.range }
        assertTrue("平台 XmlFoldingBuilder 对该文件应有折叠区（内容区形态）", platformRanges.isNotEmpty())
        assertTrue(
            "我方区与平台区**不得**范围逐字相同——否则 `CompositeFoldingBuilder` 的同范围去重会把后者丢掉，" +
                "「谁胜出」就变成模型层问题；实测我方=${oursSelect.range}，平台=$platformRanges",
            platformRanges.none { it == oursSelect.range },
        )
        assertTrue(
            "平台区应是**内容区**（起点在我方起点之后、终点在我方终点之前）：平台=$platformRanges，我方=${oursSelect.range}",
            platformRanges.any { it.startOffset > oursSelect.range.startOffset && it.endOffset < oursSelect.range.endOffset },
        )
    }

    /** 作用面边界回归位：非 mapper 文件我方零区（不抢平台区）——「先到先得」护栏的前提。 */
    fun testW8_非mapper文件我方零区() {
        myFixture.configureByText(
            "mybatis-config.xml",
            """<configuration>
  <settings>
    <setting name="cacheEnabled" value="true"/>
  </settings>
</configuration>""",
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val document = PsiDocumentManager.getInstance(project).getDocument(myFixture.file)!!
        val ours = SqlMapperFoldingBuilder().buildFoldRegions(myFixture.file, document, false)
        assertEquals("非 mapper 文件我方应零区（不与平台抢）", 0, ours.size)
    }
}
