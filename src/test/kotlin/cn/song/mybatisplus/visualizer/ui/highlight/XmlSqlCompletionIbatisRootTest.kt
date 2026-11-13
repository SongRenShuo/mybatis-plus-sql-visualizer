package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.patterns.ElementPattern
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-Ibatis2Support 券 D：补全根守卫放宽到 iBatis `<sqlMap>` 的用例（`ui/highlight` 侧）。
 *
 * 券 D 的口径是「**只**放宽到能让 iBatis 语句文本内的补全生效」，且「不做」优于「接错语义」：
 *  - **正向**：iBatis 语句文本（`<select>` 正文）走 SQL 补全分支 → 关键字/函数候选在场；
 *  - **不过度放宽**：`<sqlMapConfig>`（iBatis 全局配置）仍零补全（与 `mapper`/`sqlMap` 之外的根一致）；
 *  - **不接错语义**：iBatis **属性值位**零补全——属性值是属性名/字面量，既不是 OGNL 也不是 SQL 文本，
 *    MyBatis 的属性值分流（`test`/`property`/`refid`/…）与 `${}`/`#{}` 分支对 iBatis 一律不接；
 *  - **MyBatis 回归对照**：同形夹具根换 `mapper` 仍出同一批关键字（守卫放宽未动 MyBatis 路径）。
 *
 * 降级说明（如实登记）：iBatis 无 `namespace` ⇒ `XmlSqlCompletionContextResolver.resolveXml` 的
 * Mapper 接口链为空 ⇒ 表/列/实体候选为空；本类只断言**关键字**候选在场，不断言表/列候选
 * （那是券 G「类型映射」的欠账，不在券 D）。
 *
 * 触发方式与 [XmlSqlCompletionContributorTest] 一致：直接构造 [CompletionParameters] + 测试专用
 * 收集器直调 `fillCompletionVariants`（不经平台补全会话、不混入平台 XML 补全项）。
 */
class XmlSqlCompletionIbatisRootTest : BasePlatformTestCase() {

    private fun completeAt(xml: String, fileName: String = "Demo_SqlMap.xml"): List<String> {
        myFixture.configureByText(fileName, xml.trimIndent())
        val offset = myFixture.caretOffset
        val position = myFixture.file.findElementAt(offset) ?: error("caret 处无 PSI 元素")
        val parameters = CompletionParameters(
            position,
            myFixture.file,
            CompletionType.BASIC,
            offset,
            /* invocationCount */ 1,
            myFixture.editor,
            object : CompletionProcess {
                override fun isAutopopupCompletion() = false
            },
        )
        val resultSet = CollectingResultSet()
        XmlSqlCompletionContributor().fillCompletionVariants(parameters, resultSet)
        return resultSet.collected.map { it.lookupString }
    }

    // ------------------------------------------------------------------
    // 正向：iBatis 语句文本的 SQL 补全
    // ------------------------------------------------------------------

    fun test券D_IBatis语句文本_关键字补全生效() {
        val strings = completeAt(
            """
            <sqlMap namespace="Demo">
              <select id="sel">SELECT <caret></select>
            </sqlMap>
            """,
        )
        assertTrue("iBatis 语句文本应出 DISTINCT 候选：$strings", strings.contains("DISTINCT"))
        assertTrue("iBatis 语句文本应出 FROM 候选：$strings", strings.contains("FROM"))
    }

    fun test券D_IBatis_sql片段文本_关键字补全生效() {
        val strings = completeAt(
            """
            <sqlMap namespace="Demo">
              <sql id="cols">SELECT <caret></sql>
            </sqlMap>
            """,
        )
        assertTrue("`<sql id>` 片段正文也应出 SQL 候选：$strings", strings.contains("DISTINCT"))
    }

    // ------------------------------------------------------------------
    // 不过度放宽 / 不接错语义
    // ------------------------------------------------------------------

    fun test券D_sqlMapConfig根_零补全() {
        val strings = completeAt(
            """
            <sqlMapConfig>
              <select id="s">SELECT <caret></select>
            </sqlMapConfig>
            """,
            fileName = "SqlMapConfig.xml",
        )
        assertTrue("`<sqlMapConfig>` 不是语句宿主，应零补全：$strings", strings.isEmpty())
    }

    fun test券D_IBatis属性值位_零补全() {
        // 属性值不是 SQL 文本，也不是 OGNL——iBatis 属性值分流明确不接（券 D 决定）。
        // 若有人把 MyBatis 的属性值/OGNL 分支套到 iBatis 上，此处会出候选 → 红。
        val strings = completeAt(
            """
            <sqlMap namespace="Demo">
              <select id="sel">
                SELECT * FROM t
                <isNotNull property="<caret>">AND 1=1</isNotNull>
              </select>
            </sqlMap>
            """,
        )
        assertTrue("iBatis 属性值位应零补全（不接 MyBatis 语义）：$strings", strings.isEmpty())
    }

    // ------------------------------------------------------------------
    // MyBatis 回归对照（同形夹具，根换 mapper）
    // ------------------------------------------------------------------

    fun test券D_MyBatis路径不变_关键字补全仍生效() {
        val strings = completeAt(
            """
            <mapper namespace="com.example.Nope">
              <select id="sel">SELECT <caret></select>
            </mapper>
            """,
            fileName = "Mapper.xml",
        )
        assertTrue("MyBatis 路径不回归：仍出 DISTINCT", strings.contains("DISTINCT"))
        assertTrue("MyBatis 路径不回归：仍出 FROM", strings.contains("FROM"))
        assertFalse("MyBatis 路径不应被 iBatis 分支接管（DISTINCT 在场即可）", strings.isEmpty())
    }

    /** 直调发现贡献器的收集器（与既有测试同形：前缀恒匹配，前缀过滤由用例 caret 位置负责）。 */
    private class CollectingResultSet : CompletionResultSet(
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
