package cn.song.mybatisplus.visualizer.ui.inject

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
import com.intellij.psi.xml.XmlFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W5-10：resultMap `column` 候选来源修正（解析**引用该 resultMap 的 `<select>`** 投影列）
 * + `property`（`<resultMap type>` 类字段）建议 —— 纯逻辑与接线两层。
 */
class ResultMapColumnsGapTest {

    @Test
    fun `extractColumnsForResultMap_只取引用该resultMap的select投影列`() {
        val mapper = """
            <mapper namespace="n">
              <resultMap id="rm1" type="X"><result column="c" property="p"/></resultMap>
              <select id="a" resultMap="rm1">SELECT order_id, user_name FROM t_order</select>
              <select id="b">SELECT un_related_col FROM t_other</select>
              <select id="c" resultMap="rm2">SELECT other_col FROM t_other</select>
            </mapper>
        """.trimIndent()
        val cols = ResultMapColumns.extractColumnsForResultMap(mapper, "rm1")
        assertEquals(listOf("order_id", "user_name"), cols)
        assertFalse("无关 select 的列不得混入：$cols", cols.contains("un_related_col"))
        assertFalse("别的 resultMap 的 select 列不得混入：$cols", cols.contains("other_col"))
        assertFalse("FROM 后的表名不得被当列名：$cols", cols.contains("t_order"))
    }

    @Test
    fun `extractColumnsForResultMap_跨namespace尾段引用匹配`() {
        val mapper = """
            <mapper namespace="n">
              <resultMap id="rm1" type="X"></resultMap>
              <select id="a" resultMap="com.other.Mapper.rm1">SELECT col_a FROM t</select>
            </mapper>
        """.trimIndent()
        assertEquals(listOf("col_a"), ResultMapColumns.extractColumnsForResultMap(mapper, "rm1"))
    }

    @Test
    fun `extractColumnsForResultMap_无引用返回空_不回退全文`() {
        val mapper = """
            <mapper namespace="n">
              <resultMap id="rm9" type="X"></resultMap>
              <select id="a" resultMap="rm1">SELECT only_col FROM t</select>
            </mapper>
        """.trimIndent()
        assertTrue("无 select 引用 rm9 → 空候选（不混入全文列）", ResultMapColumns.extractColumnsForResultMap(mapper, "rm9").isEmpty())
    }

    @Test
    fun `extractColumns_既有全文提取行为不变`() {
        val mapper = """
            <mapper namespace="n">
              <select id="a">SELECT order_id, user_name FROM t_order</select>
              <select id="b">SELECT count(*) AS cnt FROM t</select>
            </mapper>
        """.trimIndent()
        val cols = ResultMapColumns.extractColumns(mapper)
        assertTrue("order_id 在场", "order_id" in cols)
        assertTrue("user_name 在场", "user_name" in cols)
        assertTrue("AS 别名 cnt 在场", "cnt" in cols)
    }
}

/**
 * T-XmlGap W5-10：resultMap 属性值补全接线（`column` 用引用投影列、`property` 用 resultMap
 * type 类字段）——直接调用贡献器（形态照 [cn.song.mybatisplus.visualizer.ui.highlight.XmlSqlCompletionContributorTest]）。
 */
class ResultMapColumnCompletionGapTest : BasePlatformTestCase() {

    @Suppress("ApiStatus")
    private fun completeAt(xml: String): List<String> {
        myFixture.configureByText("m.xml", xml.trimIndent())
        val offset = myFixture.caretOffset
        val position = myFixture.file.findElementAt(offset) ?: error("caret 处无 PSI 元素")
        val parameters = CompletionParameters(
            position,
            myFixture.file,
            CompletionType.BASIC,
            offset,
            1,
            myFixture.editor,
            object : CompletionProcess {
                override fun isAutopopupCompletion() = false
            },
        )
        val resultSet = CollectingResultSet()
        ResultMapColumnCompletionContributor().fillCompletionVariants(parameters, resultSet)
        return resultSet.collected.map { it.lookupString }
    }

    fun test_column候选来自引用该resultMap的select投影列() {
        val strings = completeAt(
            """
            <mapper namespace="n">
              <resultMap id="rm1" type="com.example.Device"><result column="<caret>" property="id"/></resultMap>
              <select id="s" resultMap="rm1">SELECT order_id, user_name FROM t_order</select>
              <select id="t">SELECT unrelated FROM t_other</select>
            </mapper>
            """,
        )
        assertTrue("应含引用 select 的投影列 order_id：$strings", strings.contains("order_id"))
        assertTrue("应含引用 select 的投影列 user_name：$strings", strings.contains("user_name"))
        assertFalse("无关 select 的列不得混入：$strings", strings.contains("unrelated"))
    }

    fun test_property候选来自resultMap的type类字段() {
        myFixture.addFileToProject(
            "Device.java",
            """
            package com.example;
            public class Device {
                private Long id;
                private String name;
            }
            """.trimIndent(),
        )
        val strings = completeAt(
            """
            <mapper namespace="n">
              <resultMap id="rm1" type="com.example.Device"><result column="c" property="<caret>"/></resultMap>
            </mapper>
            """,
        )
        assertTrue("property 候选应含 type 类字段 id：$strings", strings.contains("id"))
        assertTrue("property 候选应含 type 类字段 name：$strings", strings.contains("name"))
    }

    fun test_非结果映射上下文零候选() {
        val strings = completeAt(
            """
            <mapper namespace="n">
              <select id="s" resultMap="rm1" column="<caret>"></select>
            </mapper>
            """,
        )
        assertTrue("非 resultMap 上下文不应产出列候选：$strings", strings.isEmpty())
    }

    private class CollectingResultSet : CompletionResultSet(
        PrefixMatcher.ALWAYS_TRUE,
        java.util.function.Consumer<CompletionResult> { },
        ResultMapColumnCompletionContributor(),
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

/**
 * T-XmlGap W5-10：`columnsForResultMap` 的分槽 memo（同文件多 resultMap 复用；stamp/长度变化失效）。
 */
class ResultMapColumnResultMapMemoTest : BasePlatformTestCase() {

    fun test_同文件同resultMap二次提取_命中memo零重扫() {
        val file = myFixture.configureByText(
            "m.xml",
            "<mapper namespace=\"n\"><select id=\"a\" resultMap=\"rm1\">SELECT order_id FROM t</select></mapper>",
        ) as XmlFile
        ResultMapColumnCompletionMemo.resetForTest()
        val original = ResultMapColumnCompletionMemo.resultMapExtractor
        var count = 0
        ResultMapColumnCompletionMemo.resultMapExtractor = { _, _ -> count++; listOf("order_id") }
        try {
            val first = ResultMapColumnCompletionMemo.columnsForResultMap(file, "rm1")
            val second = ResultMapColumnCompletionMemo.columnsForResultMap(file, "rm1")
            assertEquals("同文件同 resultMap 二次提取只应触发一次全文重扫", 1, count)
            assertEquals(listOf("order_id"), first)
            assertEquals(first, second)
        } finally {
            ResultMapColumnCompletionMemo.resultMapExtractor = original
            ResultMapColumnCompletionMemo.resetForTest()
        }
    }
}
