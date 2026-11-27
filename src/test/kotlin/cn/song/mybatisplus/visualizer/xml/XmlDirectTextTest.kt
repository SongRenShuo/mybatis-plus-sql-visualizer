package cn.song.mybatisplus.visualizer.xml

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * XML 直属文本提取的实体解码口径（T-XmlEntityDecode）。
 *
 * 背景：`XmlText.text` 是 PSI 源文本（实体字符原样），而 MyBatis 运行时由 XML 解析器
 * 还原实体——`&gt;=` 在真机 SQL 里是 `>=`。未解码会让预览/复制出的 SQL 带着 `&gt;`，
 * 直接执行必然语法错误（用户报障：`<sql id>` 片段预览 WHERE 条件显示 `&gt;=`）。
 *
 * 语义边界（与 DOM 链 `PagesXmlParser`「CDATA 内字面量不解码」一致）：
 *  - 普通文本段：解码五个标准实体 + 数字字符引用；
 *  - CDATA 段：只剥包裹、绝不解码（MyBatis 同样保留字面量）；
 *  - 只解码一次（`&amp;gt;` → `&gt;`，不得继续还原成 `>`）。
 */
class XmlDirectTextTest : BasePlatformTestCase() {

    private fun tag(text: String, tagName: String = "sql"): XmlTag {
        myFixture.configureByText(
            "sample.xml",
            "<mapper namespace=\"n\">$text</mapper>",
        )
        return PsiTreeUtil.collectElementsOfType(myFixture.file as XmlFile, XmlTag::class.java)
            .first { it.name == tagName }
    }

    fun test比较运算符实体_普通文本段解码() {
        val t = tag("<sql id=\"f\">WHERE a &gt;= #{fromTime} AND b &lt;= #{toTime}</sql>")
        assertEquals(
            "普通文本段的标准实体必须还原为比较运算符",
            "WHERE a >= #{fromTime} AND b <= #{toTime}",
            directTextOf(t),
        )
    }

    fun test五标准实体与数字引用_全部还原() {
        val t = tag("<sql id=\"f\">a &lt; b &gt; c &amp; d &quot;e&quot; &apos;f&apos; &#62; &#x3C;</sql>")
        assertEquals("a < b > c & d \"e\" 'f' > <", directTextOf(t))
    }

    fun test只解码一次_双重转义保持字面() {
        val t = tag("<sql id=\"f\">a &amp;gt;= b</sql>")
        assertEquals("a &gt;= b", directTextOf(t))
    }

    fun testCDATA段_字面量不解码() {
        // 与 PagesXmlParser「CDATA 内文本为字面量」同口径：CDATA 里的 &gt; 就是 &gt;
        val t = tag("<sql id=\"f\"><![CDATA[WHERE a &gt;= 1]]></sql>")
        assertEquals("WHERE a &gt;= 1", directTextOf(t))
    }

    fun test普通文本与CDATA混排_各按各的口径() {
        val t = tag("<sql id=\"f\">SELECT &gt;= 0<![CDATA[ AND a &gt; 1 ]]>AND b &lt; 2</sql>")
        assertEquals("SELECT >= 0 AND a &gt; 1 AND b < 2", directTextOf(t))
    }

    fun test未知实体_原样保留() {
        val t = tag("<sql id=\"f\">a &nbsp; b</sql>")
        assertEquals("未声明的实体不猜解，保留原文", "a &nbsp; b", directTextOf(t))
    }

    fun test子标签内容不混入() {
        val t = tag("<sql id=\"f\">A &gt; 0<if test=\"x\">B &lt; 1</if></sql>")
        assertEquals("只取直属文本段", "A > 0", directTextOf(t))
    }
}