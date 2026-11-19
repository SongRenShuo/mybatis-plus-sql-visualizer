package cn.song.mybatisplus.visualizer.ui.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺失 include 片段检测与补齐测试（T-IncludePlaceholder A 券）。
 *
 * 纯逻辑、零平台依赖（自动进 testFast 快桶）。夹具覆盖：只贴语句（缺片段）、
 * 片段与语句同贴（不缺）、namespace 前缀与裸 id 的两种写法（不得误报）、补齐内容两种形态。
 *
 * k2j 移植注：生产侧 MissingSqlFragmentSupport 已是 Java（missingRefids/extractFragment/
 * withFragments 三个静态方法），Kotlin 测试直调即兼容；withFragments 的
 * Map&lt;String,String&gt; 形参接受 Kotlin mapOf。
 */
class MissingSqlFragmentSupportTest {

    @Test
    fun `只贴语句时检出缺失片段`() {
        val text = """
            <select id="q" parameterClass="User">
              SELECT <include refid="userColumns"/> FROM T_USER
            </select>
        """.trimIndent()
        assertEquals(listOf("userColumns"), MissingSqlFragmentSupport.missingRefids(text))
    }

    @Test
    fun `片段与语句同贴时不报缺失`() {
        val text = """
            <sql id="userColumns">id, name</sql>
            <select id="q">SELECT <include refid="userColumns"/> FROM T_USER</select>
        """.trimIndent()
        assertTrue(MissingSqlFragmentSupport.missingRefids(text).isEmpty())
    }

    @Test
    fun `namespace 前缀 refid 与裸 id 定义视为同名`() {
        // iBatis 允许 `refid="User.userColumns"` 而定义写成裸 `id="userColumns"`（同一 namespace）
        val text = """
            <sql id="userColumns">id, name</sql>
            <select id="q">SELECT <include refid="User.userColumns"/> FROM T_USER</select>
        """.trimIndent()
        assertTrue("末段同名不得误报缺失", MissingSqlFragmentSupport.missingRefids(text).isEmpty())
    }

    @Test
    fun `多个缺失按名排序输出`() {
        val text = """
            <select id="q">SELECT <include refid="b"/>, <include refid="a"/> FROM T</select>
        """.trimIndent()
        assertEquals(listOf("a", "b"), MissingSqlFragmentSupport.missingRefids(text))
    }

    @Test
    fun `无 include 时不报缺失`() {
        assertTrue(MissingSqlFragmentSupport.missingRefids("<select id=\"q\">SELECT 1</select>").isEmpty())
        assertTrue(MissingSqlFragmentSupport.missingRefids("SELECT 1 FROM DUAL").isEmpty())
    }

    @Test
    fun `从文件文本里抽出指定片段内容`() {
        val file = """
            <sqlMap namespace="User">
              <sql id="userColumns">id, user_name, email</sql>
              <select id="q">SELECT <include refid="userColumns"/> FROM T_USER</select>
            </sqlMap>
        """.trimIndent()
        assertEquals("id, user_name, email", MissingSqlFragmentSupport.extractFragment(file, "userColumns"))
    }

    @Test
    fun `片段属性顺序与大小写不影响抽取`() {
        val file = """<SQL resultClass="map" ID="cols">a, b</SQL>"""
        assertEquals("a, b", MissingSqlFragmentSupport.extractFragment(file, "cols"))
    }

    @Test
    fun `文件里没有该片段时返回 null`() {
        val file = """<sql id="other">x</sql>"""
        assertTrue(MissingSqlFragmentSupport.extractFragment(file, "userColumns") == null)
    }

    @Test
    fun `补齐纯内容时自动包 sql 标签`() {
        val merged = MissingSqlFragmentSupport.withFragments(
            "<select id=\"q\">SELECT <include refid=\"cols\"/> FROM T</select>",
            mapOf("cols" to "id, name"),
        )
        assertTrue("应自动包成 <sql id>：$merged", merged.contains("<sql id=\"cols\">id, name</sql>"))
        assertTrue("原文本应保留在后：$merged", merged.endsWith("<select id=\"q\">SELECT <include refid=\"cols\"/> FROM T</select>"))
    }

    @Test
    fun `补齐整段 sql 标签时原样拼入不二次包壳`() {
        val merged = MissingSqlFragmentSupport.withFragments(
            "<select id=\"q\">SELECT <include refid=\"cols\"/> FROM T</select>",
            mapOf("cols" to "<sql id=\"cols\">id</sql>"),
        )
        assertEquals(
            "<sql id=\"cols\">id</sql>\n<select id=\"q\">SELECT <include refid=\"cols\"/> FROM T</select>",
            merged,
        )
    }

    @Test
    fun `补齐后缺失集合收敛`() {
        val statement = "<select id=\"q\">SELECT <include refid=\"cols\"/> FROM T</select>"
        assertTrue(MissingSqlFragmentSupport.missingRefids(statement).isNotEmpty())
        val merged = MissingSqlFragmentSupport.withFragments(statement, mapOf("cols" to "id"))
        assertTrue("补齐后不应再缺失：$merged", MissingSqlFragmentSupport.missingRefids(merged).isEmpty())
    }
}
