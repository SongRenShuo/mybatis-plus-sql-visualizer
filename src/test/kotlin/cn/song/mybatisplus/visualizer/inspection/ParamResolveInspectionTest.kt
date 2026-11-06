package cn.song.mybatisplus.visualizer.inspection

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * F-02 参数可解析性三规则的**薄壳集成测试**（PSI 接线 + fixture 正/反例）。
 *
 * 判定语义本身在 [ParamResolveRulesTest]（纯逻辑，逐条钉住）；这里只验：
 *  - 规则①：接口方法参数类型解析 → `#{p.noem}` 报、`#{map.key}` 沉默、类型解析不出沉默；
 *  - 规则②：`@Param` 声明未引用报、引用/别名/include 闸门沉默、注解 SQL 同判；
 *  - 规则③：refid 缺失报、本地命中/同 namespace 跨文件命中/动态 refid/不可见 namespace 沉默。
 *
 * 属平台桶（`com.intellij` 引用），不在 `testFast` 快桶内，定向跑。
 */
class ParamResolveInspectionTest : BasePlatformTestCase() {

    private fun mapperXml(body: String): String = """
        <mapper namespace="com.acme.UserMapper">
        $body
        </mapper>
    """.trimIndent()

    private fun hits(fragment: String) =
        myFixture.doHighlighting().filter { it.description?.contains(fragment) == true }

    /** 双语命中（headless bundle 语言随平台环境，正反断言一律中英双查，与既有测试同口径）。 */
    private fun propertyMessages() = hits("不存在属性路径") + hits("does not exist on parameter")

    private fun refMessages() = hits("从未引用") + hits("never referenced")

    private fun fragmentMessages() = hits("未命中") + hits("does not match any")

    // ------------------------------------------------------------------
    // 规则①：参数属性缺失
    // ------------------------------------------------------------------

    private fun addQueryDto() {
        myFixture.addFileToProject(
            "com/acme/UserQuery.java",
            """
            package com.acme;
            public class UserQuery {
                private Long id;
                private String name;
                public Long getId() { return id; }
                public String getName() { return name; }
            }
            """.trimIndent(),
        )
    }

    fun test规则1_正例_属性缺失报() {
        addQueryDto()
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            import org.apache.ibatis.annotations.Select;
            interface UserMapper {
                @Select("select * from t where name = #{p.name}")
                Object list(@Param("p") UserQuery p);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="list">select * from t where id = #{p.noem} and name = #{p.name}</select>"""),
        )
        myFixture.enableInspections(ParamPropertyInspection())
        val found = propertyMessages()
        assertEquals("p.noem 应命中 1 条（p.name 放行）：$found", 1, found.size)
    }

    fun test规则1_反例_Map参数key不判() {
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            import java.util.Map;
            interface UserMapper {
                Object list(@Param("map") Map<String, Object> map);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="list">select * from t where k = #{map.anyKey}</select>"""),
        )
        myFixture.enableInspections(ParamPropertyInspection())
        assertTrue(
            "Map 型参数的 key 静态不可知（竞品同域误报重灾区），必须沉默",
            propertyMessages().isEmpty(),
        )
    }

    fun test规则1_反例_类型解析不出沉默() {
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            interface UserMapper {
                Object list(@Param("p") NotOnClasspath p);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="list">select * from t where id = #{p.noem}</select>"""),
        )
        myFixture.enableInspections(ParamPropertyInspection())
        assertTrue("参数类型解析不出必须沉默", propertyMessages().isEmpty())
    }

    // ------------------------------------------------------------------
    // 规则②：@Param 声明未引用
    // ------------------------------------------------------------------

    fun test规则2_正例_声明未引用报() {
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            interface UserMapper {
                Object list(@Param("userName") String userName, @Param("station") String station);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="list">select * from t where name = #{userName}</select>"""),
        )
        myFixture.enableInspections(ParamReferenceInspection())
        val found = refMessages()
        assertEquals("station 声明未引用应命中 1 条：$found", 1, found.size)
        assertTrue("userName 已引用不得报：$found", found.single().description.orEmpty().contains("station"))
    }

    fun test规则2_反例_全部引用沉默() {
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            interface UserMapper {
                Object list(@Param("userName") String userName, @Param("station") String station);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="list">select * from t where name = #{userName} and sid = #{param2}</select>"""),
        )
        myFixture.enableInspections(ParamReferenceInspection())
        assertTrue("param2 位置别名算引用，必须沉默", refMessages().isEmpty())
    }

    fun test规则2_反例_include闸门沉默() {
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            interface UserMapper {
                Object list(@Param("userName") String userName, @Param("station") String station);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                <sql id="base">where sid = #{station}</sql>
                <select id="list">select * from t where name = #{userName} <include refid="base"/></select>
                """.trimIndent(),
            ),
        )
        myFixture.enableInspections(ParamReferenceInspection())
        assertTrue(
            "语句含 <include>：片段文本不在 PSI，station 在片段里被引用，引用面不可见必须沉默",
            refMessages().isEmpty(),
        )
    }

    fun test规则2_反例_foreach_collection引用沉默() {
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            import java.util.List;
            interface UserMapper {
                Object list(@Param("userName") String userName, @Param("ids") List<Long> ids);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                <select id="list">
                  select * from t where name = #{userName}
                  and id in <foreach collection="ids" item="it" open="(" close=")" separator=",">#{it}</foreach>
                </select>
                """.trimIndent(),
            ),
        )
        myFixture.enableInspections(ParamReferenceInspection())
        assertTrue("foreach collection 属性引用算引用：${refMessages()}", refMessages().isEmpty())
    }

    fun test规则2_注解SQL_声明未引用报() {
        myFixture.configureByText(
            "JavaMapper.java",
            """
            package com.acme;
            import org.apache.ibatis.annotations.Param;
            import org.apache.ibatis.annotations.Select;
            interface UserMapper {
                @Select("select * from t where name = #{userName}")
                Object list(@Param("userName") String userName, @Param("ghost") String ghost);
            }
            """.trimIndent(),
        )
        myFixture.enableInspections(ParamReferenceInspection())
        val found = refMessages()
        assertEquals("ghost 声明未引用应命中：$found", 1, found.size)
    }

    // ------------------------------------------------------------------
    // 规则③：<include refid> 片段缺失
    // ------------------------------------------------------------------

    fun test规则3_正例_refid缺失报() {
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                <sql id="base">where a = 1</sql>
                <select id="s">select * from t <include refid="nope"/></select>
                """.trimIndent(),
            ),
        )
        myFixture.enableInspections(MissingSqlFragmentInspection())
        val found = fragmentMessages()
        assertEquals("nope 应命中 1 条：$found", 1, found.size)
        assertTrue("base 已命中不得报", found.single().description.orEmpty().contains("nope"))
    }

    fun test规则3_反例_本地命中与动态refid与不可见namespace沉默() {
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml(
                """
                <sql id="base">where a = 1</sql>
                <select id="s">
                  select * from t
                  <include refid="base"/>
                  <include refid="${'$'}{tablePrefix}_cols"/>
                  <include refid="com.other.InvisibleMapper.cols"/>
                </select>
                """.trimIndent(),
            ),
        )
        myFixture.enableInspections(MissingSqlFragmentInspection())
        assertTrue(
            "本地命中 / 动态 refid / 不可见 namespace 三类都必须沉默：${fragmentMessages()}",
            fragmentMessages().isEmpty(),
        )
    }

    fun test规则3_反例_同namespace跨文件片段命中() {
        // MyBatis 片段表键 = namespace.id：同 namespace 的其它 mapper 文件也能提供片段
        myFixture.addFileToProject(
            "UserMapperShared.xml",
            mapperXml("""<sql id="shared_cols">id, name</sql>"""),
        )
        myFixture.configureByText(
            "UserMapper.xml",
            mapperXml("""<select id="s">select <include refid="shared_cols"/> from t</select>"""),
        )
        myFixture.enableInspections(MissingSqlFragmentInspection())
        assertTrue(
            "同 namespace 其它文件提供的片段必须命中（MyBatis 片段表语义）：${fragmentMessages()}",
            fragmentMessages().isEmpty(),
        )
    }
}
