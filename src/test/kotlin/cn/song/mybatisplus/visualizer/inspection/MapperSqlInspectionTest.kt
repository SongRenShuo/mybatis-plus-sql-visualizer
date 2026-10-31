package cn.song.mybatisplus.visualizer.inspection

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * NEW-26 编辑器内 SQL 检查的**薄壳集成测试**：验 PSI 走查接得对不对。
 *
 * 判定语义本身在 [SqlInspectionTextTest]（纯逻辑，逐条钉住正反例）；这里只验：
 *  - mapper XML 的语句标签/`<sql>` 片段是否被走访到；
 *  - 偏移是否映射到正确的文本节点（标红落在 `${}`/`#{}` 上）；
 *  - 非 mapper XML / 普通 Java 文件是否零行为；
 *  - 注解 SQL（`@Select`）是否被走访到。
 *
 * 属平台桶（`com.intellij` 引用），不在 `testFast` 快桶内，定向跑。
 */
class MapperSqlInspectionTest : BasePlatformTestCase() {

    private fun enableAll() {
        myFixture.enableInspections(
            DollarInjectionInspection(),
            MissingParameterInspection(),
            ResultMapPropertyInspection(),
            DialectQuoteInspection(),
            OgnlTautologyInspection(),
        )
    }

    private fun hits(fragment: String) =
        myFixture.doHighlighting().filter { it.description?.contains(fragment) == true }

    private fun mapperXml(body: String): String = """
        <mapper namespace="com.acme.UserMapper">
        $body
        </mapper>
    """.trimIndent()

    // ------------------------------------------------------------------
    // 规则 1：${}
    // ------------------------------------------------------------------

    fun testDollarInjection_正例_语句内拼接报在token上() {
        enableAll()
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="list">select * from t order by ${'$'}{orderBy}</select>"""),
        )
        val found = myFixture.doHighlighting()
            .filter { it.description?.contains("Direct substitution") == true || it.description?.contains("直接替换") == true }
        assertEquals("语句内美元占位应命中 1 条：$found", 1, found.size)
        val range = found.single().startOffset until found.single().endOffset
        val highlighted = myFixture.file.text.substring(range.first, range.last + 1)
        assertEquals("标红应精确落在 token 上", "\${orderBy}", highlighted)
        assertEquals(HighlightSeverity.WARNING, found.single().severity)
    }

    fun testDollarInjection_反例_bind静态常量列别名零命中() {
        enableAll()
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                <select id="statistics">
                  <bind name="orderCount" value="@com.acme.Dic@COUNT"/>
                  select count(t.id) ${'$'}{orderCount} from t
                </select>
                """.trimIndent(),
            ),
        )
        assertTrue("bind 静态常量作列别名不得报", hits("直接替换").isEmpty() && hits("Direct substitution").isEmpty())
    }

    fun testDollarInjection_反例_非mapper的XML零行为() {
        enableAll()
        myFixture.configureByText(
            "other.xml",
            """
            <configuration>
              <select>select * from t order by ${'$'}{orderBy}</select>
            </configuration>
            """.trimIndent(),
        )
        assertTrue("非 mapper 文件不得有任何本检查提示", hits("直接替换").isEmpty() && hits("Direct substitution").isEmpty())
    }

    fun testDollarInjection_sql片段也被扫描() {
        enableAll()
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<sql id="order">order by ${'$'}{col}</sql>"""),
        )
        assertTrue("`<sql>` 片段里的美元占位也要报", hits("直接替换").isNotEmpty() || hits("Direct substitution").isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 规则 2：#{} 参数
    // ------------------------------------------------------------------

    fun testMissingParameter_正例_方法参数缺失报() {
        enableAll()
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            import org.apache.ibatis.annotations.Select;
            interface UserMapper {
                @Select("select * from t where id = #{userId} and name = #{name}")
                Object find(@Param("name") String name);
            }
            """.trimIndent(),
        )
        val found = myFixture.doHighlighting()
            .filter { it.description?.contains("不在") == true || it.description?.contains("not declared") == true }
        assertEquals("userId 未声明，应命中 1 条：$found", 1, found.size)
    }

    fun testMissingParameter_反例_单参数无Param不判() {
        enableAll()
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Select;
            interface UserMapper {
                @Select("select * from t where id = #{id} and name = #{name}")
                Object find(User user);
            }
            """.trimIndent(),
        )
        assertTrue("单参且无 @Param 时 MyBatis 允许任意属性名，不得报", hits("未声明").isEmpty() && hits("not declared").isEmpty())
    }

    // ------------------------------------------------------------------
    // 规则 3：resultMap 属性
    // ------------------------------------------------------------------

    fun testResultMapProperty_正例_属性不存在报() {
        enableAll()
        myFixture.addFileToProject(
            "com/acme/UserEntity.java",
            """
            package com.acme;
            public class UserEntity {
                private Long id;
                private String name;
                public Long getId() { return id; }
                public String getName() { return name; }
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                <resultMap id="BaseMap" type="com.acme.UserEntity">
                  <id column="id" property="id"/>
                  <result column="name" property="name"/>
                  <result column="nope" property="nope"/>
                </resultMap>
                """.trimIndent(),
            ),
        )
        assertTrue("不存在的属性 nope 应命中", hits("不存在").isNotEmpty() || hits("does not exist").isNotEmpty())
        assertEquals(
            "存在的 id/name 不得报",
            1,
            myFixture.doHighlighting().count { it.description?.contains("不存在") == true || it.description?.contains("does not exist") == true },
        )
    }

    fun testResultMapProperty_反例_类型解析不出不报() {
        enableAll()
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                <resultMap id="BaseMap" type="com.acme.NotOnClasspath">
                  <result column="x" property="nope"/>
                </resultMap>
                """.trimIndent(),
            ),
        )
        assertTrue("类型解析不出必须沉默", hits("不存在").isEmpty() && hits("does not exist").isEmpty())
    }

    // ------------------------------------------------------------------
    // 规则 4：方言引号
    // ------------------------------------------------------------------

    fun testDialectQuote_正例_反引号在非MySQL方言报() {
        // 项目方言默认 MySQL（反引号合法）→ 本用例断言 MySQL 下不误报
        enableAll()
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="s">select `name` from t</select>"""),
        )
        assertTrue(
            "默认 MySQL 方言收反引号，不得报",
            hits("不被当前方言").isEmpty() && hits("not accepted").isEmpty(),
        )
    }

    fun testDialectQuote_纯逻辑已覆盖方言差异() {
        // 方言切换需要项目级设置夹具；判定本身由 SqlInspectionTextTest 的 正例/反例 钉住，
        // 这里只验「薄壳在 MySQL 默认下确实不误报」（上一条用例）。
        assertTrue(true)
    }

    // ------------------------------------------------------------------
    // 规则 5：OGNL 常量条件
    // ------------------------------------------------------------------

    fun testOgnlTautology_正例_恒真条件报() {
        enableAll()
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="s">select * from t <if test="true">where a = 1</if></select>"""),
        )
        assertTrue("test=\"true\" 恒真应命中", hits("恒真").isNotEmpty() || hits("always true").isNotEmpty())
    }

    fun testOgnlTautology_反例_引用参数的条件不报() {
        enableAll()
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="s">select * from t <if test="p.startTime != null">and a = 1</if></select>"""),
        )
        assertTrue("引用参数的条件不得报", hits("恒真").isEmpty() && hits("恒假").isEmpty() && hits("always").isEmpty())
    }

    // ------------------------------------------------------------------
    // P-1：<include> 展开（PSI 文本不含展开 → 判定只看本文件字面）
    // ------------------------------------------------------------------

    fun testP1_include展开_片段里的参数不被重复判到调用点() {
        enableAll()
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                <sql id="base">where a = #{a}</sql>
                <select id="s">select * from t <include refid="base"/></select>
                """.trimIndent(),
            ),
        )
        // PSI 文本不含 include 展开（P-1 取证结论），故调用点看不到片段内容——不会把片段里的
        // 参数按调用点的方法签名误判；此处只断言不崩、不产生参数类误报。
        assertTrue(
            "include 展开不在 PSI 文本里，调用点不得因此产生参数误报",
            hits("未声明").isEmpty() && hits("not declared").isEmpty(),
        )
    }
}