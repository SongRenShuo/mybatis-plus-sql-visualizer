package cn.song.mybatisplus.visualizer.ui.ref

import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-XmlGap W7 引用解析用例：mapper XML 内九类属性 → 目标元素。
 *
 * 形态说明：测试夹具**不自动加载 plugin.xml 的 EP**（仓内既有注入器测试同样是手工注册），故本类
 * 直接驱动 [MybatisXmlAttributeReferenceProvider]（provider 级断言），EP 接线由类尾守卫用例按
 * 「读 plugin.xml 文本 + 正则」的既有形态钉住。
 *
 * 红线相关：所有引用必须 `isSoft == true`（目标缺失时零报红），本类有专门负路径用例。
 */
class MybatisXmlReferenceTest : BasePlatformTestCase() {

    private fun refsOf(attrName: String): List<com.intellij.psi.PsiReference> {
        val file = myFixture.file as XmlFile
        val value = PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue::class.java)
            .first { (it.parent as? XmlAttribute)?.name == attrName }
        return MybatisXmlAttributeReferenceProvider
            .getReferencesByElement(value, ProcessingContext())
            .toList()
    }

    private fun singleResolve(attrName: String) = refsOf(attrName).single().resolve()

    // ------------------------------------------------------------------
    // 同文件 / 跨文件 XML id
    // ------------------------------------------------------------------

    /** `include@refid` → 同文件 `<sql id>`。 */
    fun testW7_refid_同文件_sql片段() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <sql id="frag">WHERE 1=1</sql>
  <select id="s">SELECT * FROM t <include refid="frag"/></select>
</mapper>""",
        )
        val target = singleResolve("refid")
        assertNotNull("refid 应解析到 <sql id>", target)
        val tag = PsiTreeUtil.getParentOfType(target, XmlTag::class.java)
        assertEquals("sql", tag?.name)
        assertEquals("frag", tag?.getAttributeValue("id"))
    }

    /** `include@refid` 跨文件 `ns.id` → 另一 mapper 文件的 `<sql id>`。 */
    fun testW7_refid_跨文件_namespace点号() {
        myFixture.addFileToProject(
            "other/OtherMapper.xml",
            """<mapper namespace="other.ns"><sql id="frag2">WHERE 2=2</sql></mapper>""",
        )
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <select id="s">SELECT * FROM t <include refid="other.ns.frag2"/></select>
</mapper>""",
        )
        val target = singleResolve("refid")
        assertNotNull("跨文件 ns.id 应解析到对端 <sql id>", target)
        assertEquals("OtherMapper.xml", target!!.containingFile?.name)
    }

    /** `resultMap=` → 同文件 `<resultMap id>`。 */
    fun testW7_resultMap_同文件() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <resultMap id="rm" type="java.lang.String"/>
  <select id="s" resultMap="rm">SELECT 1</select>
</mapper>""",
        )
        val tag = PsiTreeUtil.getParentOfType(singleResolve("resultMap"), XmlTag::class.java)
        assertEquals("resultMap", tag?.name)
        assertEquals("rm", tag?.getAttributeValue("id"))
    }

    /** `resultMap@extends` → 同文件 `<resultMap id>`。 */
    fun testW7_extends_同文件resultMap() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <resultMap id="base" type="java.lang.String"/>
  <resultMap id="child" type="java.lang.String" extends="base"/>
</mapper>""",
        )
        val tag = PsiTreeUtil.getParentOfType(singleResolve("extends"), XmlTag::class.java)
        assertEquals("resultMap", tag?.name)
        assertEquals("base", tag?.getAttributeValue("id"))
    }

    /** `parameterMap=` → 同文件 `<parameterMap id>`。 */
    fun testW7_parameterMap_同文件() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <parameterMap id="pm" type="java.lang.String"/>
  <select id="s" parameterMap="pm">SELECT 1</select>
</mapper>""",
        )
        val tag = PsiTreeUtil.getParentOfType(singleResolve("parameterMap"), XmlTag::class.java)
        assertEquals("parameterMap", tag?.name)
        assertEquals("pm", tag?.getAttributeValue("id"))
    }

    // ------------------------------------------------------------------
    // Java 侧目标
    // ------------------------------------------------------------------

    /** `namespace` → Java 接口。 */
    fun testW7_namespace_接口() {
        myFixture.addFileToProject("com/demo/UserMapper.java", "package com.demo;\npublic interface UserMapper { int count(); }\n")
        myFixture.configureByText("mapper.xml", """<mapper namespace="com.demo.UserMapper"/>""")
        val target = singleResolve("namespace")
        assertNotNull("namespace 应解析到接口", target)
        assertEquals("com.demo.UserMapper", (target as com.intellij.psi.PsiClass).qualifiedName)
    }

    /** statement `id` → 接口方法。 */
    fun testW7_statementId_接口方法() {
        myFixture.addFileToProject("com/demo/UserMapper.java", "package com.demo;\npublic interface UserMapper { int count(); }\n")
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="com.demo.UserMapper"><select id="count">SELECT 1</select></mapper>""",
        )
        val target = singleResolve("id")
        assertNotNull("statement id 应解析到接口方法", target)
        assertEquals("count", (target as PsiMethod).name)
    }

    /** `resultType` → 类（短名走 namespace 接口同包解析）。 */
    fun testW7_resultType_短名类() {
        myFixture.addFileToProject("com/demo/UserMapper.java", "package com.demo;\npublic interface UserMapper { }\n")
        myFixture.addFileToProject("com/demo/UserVo.java", "package com.demo;\npublic class UserVo { String name; }\n")
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="com.demo.UserMapper"><select id="s" resultType="UserVo">SELECT 1</select></mapper>""",
        )
        val target = singleResolve("resultType")
        assertNotNull("短名应经同包解析到类", target)
        assertEquals("com.demo.UserVo", (target as com.intellij.psi.PsiClass).qualifiedName)
    }

    /** `resultType` 全限定名直接命中。 */
    fun testW7_resultType_全限定名() {
        myFixture.addFileToProject("com/demo/UserVo.java", "package com.demo;\npublic class UserVo { String name; }\n")
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="s" resultType="com.demo.UserVo">SELECT 1</select></mapper>""",
        )
        assertEquals("com.demo.UserVo", (singleResolve("resultType") as com.intellij.psi.PsiClass).qualifiedName)
    }

    /** `jdbcType` → MyBatis `JdbcType` 枚举常量（按类名运行时解析，生产零 mybatis 依赖）。 */
    fun testW7_jdbcType_枚举常量() {
        myFixture.addFileToProject(
            "org/apache/ibatis/type/JdbcType.java",
            "package org.apache.ibatis.type;\npublic enum JdbcType { VARCHAR, INTEGER }\n",
        )
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <resultMap id="rm" type="java.lang.String">
    <result column="c" property="p" jdbcType="VARCHAR"/>
  </resultMap>
</mapper>""",
        )
        val target = singleResolve("jdbcType")
        assertNotNull("jdbcType 应解析到枚举常量", target)
        assertEquals("VARCHAR", (target as PsiField).name)
    }

    /** `property` → 实体字段（持有者类来自最近祖先标签的 `type`）。 */
    fun testW7_property_实体字段() {
        myFixture.addFileToProject("com/demo/UserVo.java", "package com.demo;\npublic class UserVo { private String name; }\n")
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <resultMap id="rm" type="com.demo.UserVo">
    <result column="user_name" property="name"/>
  </resultMap>
</mapper>""",
        )
        val target = singleResolve("property")
        assertNotNull("property 应解析到实体字段", target)
        assertEquals("name", (target as PsiField).name)
    }

    // ------------------------------------------------------------------
    // 负路径（红线：无目标零报红）
    // ------------------------------------------------------------------

    /** 目标不存在 → `resolve()` 返回 null；引用仍必须存在且 **soft**（不标红）。 */
    fun testW7_目标缺失_soft引用零报红() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="s">SELECT 1 <include refid="missing"/></select></mapper>""",
        )
        val refs = refsOf("refid")
        assertEquals("引用本身仍产生（供重命名/查找用法）", 1, refs.size)
        assertNull("目标不存在 → resolve 为 null", refs.single().resolve())
        assertTrue("引用须为 soft：目标缺失时平台不标红（红线）", refs.single().isSoft)
    }

    /** 非 mapper 根标签（如 mybatis-config）→ 零引用。 */
    fun testW7_非mapper根_零引用() {
        myFixture.configureByText(
            "config.xml",
            """<configuration><properties><property name="a" value="b"/></properties></configuration>""",
        )
        val file = myFixture.file as XmlFile
        val value = PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue::class.java)
            .first { (it.parent as? XmlAttribute)?.name == "name" }
        assertTrue(
            "非 mapper 文件不产引用（根守卫）",
            MybatisXmlAttributeReferenceProvider.getReferencesByElement(value, ProcessingContext()).isEmpty(),
        )
    }

    /** 未覆盖的属性（如 `statementType`）→ 零引用（不做无意义引用，避免噪音）。 */
    fun testW7_未覆盖属性_零引用() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="s" statementType="PREPARED">SELECT 1</select></mapper>""",
        )
        val file = myFixture.file as XmlFile
        val value = PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue::class.java)
            .first { (it.parent as? XmlAttribute)?.name == "statementType" }
        assertTrue(
            "未覆盖属性不产引用",
            MybatisXmlAttributeReferenceProvider.getReferencesByElement(value, ProcessingContext()).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // T-MapperXmlTargetsIndex（2026-11-16）：namespace→文件改走自定义索引
    // （F96 的「清单单槽 TTL + namespace 结果级 memo」随 FileTypeIndex 全扫一起退役）
    // ------------------------------------------------------------------

    /** 同一 namespace 连续解析结果稳定——索引查询无 TTL 换代抖动，不会「这一刻有、下一刻没有」。 */
    fun testF96_同namespace连续解析_结果稳定() {
        myFixture.addFileToProject(
            "m/UserMapper.xml",
            """<mapper namespace="m.user"><sql id="f">SELECT 1</sql></mapper>""",
        )
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n"><select id="s">SELECT * FROM t <include refid="m.user.f"/></select></mapper>""",
        )

        val first = MybatisXmlTargets.findMapperFileByNamespace(project, "m.user")
        assertNotNull("首次解析应命中", first)
        val second = MybatisXmlTargets.findMapperFileByNamespace(project, "m.user")
        assertNotNull("二次解析应命中", second)
        assertEquals("两次解析必须落到同一文件", first!!.virtualFile, second!!.virtualFile)
    }

    /** 负结果不被记住：不存在的 namespace 连续解析都返回 null。 */
    fun testF96_不存在namespace_连续解析都为null() {
        myFixture.configureByText("mapper.xml", """<mapper namespace="n"><select id="s">SELECT 1</select></mapper>""")
        assertNull(MybatisXmlTargets.findMapperFileByNamespace(project, "no.such.ns"))
        assertNull(MybatisXmlTargets.findMapperFileByNamespace(project, "no.such.ns"))
    }

    /** 新增同 namespace 文件立即可见——索引由平台增量维护，没有旧实现的 5 秒 TTL 空窗。 */
    fun testF96_新增同namespace文件_立即可见() {
        myFixture.configureByText("mapper.xml", """<mapper namespace="n"><select id="s">SELECT 1</select></mapper>""")
        assertNull("前置：此时确实还没有该 namespace", MybatisXmlTargets.findMapperFileByNamespace(project, "m.same"))
        myFixture.addFileToProject("m/A.xml", """<mapper namespace="m.same"><sql id="a">SELECT 1</sql></mapper>""")
        assertNotNull(
            "新增文件必须在同一会话内立即可见（旧链 5s TTL 内会漏）",
            MybatisXmlTargets.findMapperFileByNamespace(project, "m.same"),
        )
    }

    // ------------------------------------------------------------------
    // F97（T-PerfSweep26）：holderClass 祖先链批内 memo
    // ------------------------------------------------------------------

    /**
     * 祖先链上重复出现的同一类名只解析一次（批内 memo）。
     *
     * 形态：多层嵌套且**每层都带同一个解析不成功的类名**——holderClass 逐层问 5 个属性名，
     * 只有解析失败才会继续往上走满祖先链，因此这个形态才真正暴露「同名重复解析」。
     * 修复前每层各解析一次（本例 3 次），memo 后压到 1 次。
     */
    fun testF97_同名类在祖先链重复出现_只解析一次() {
        myFixture.configureByText(
            "mapper.xml",
            """<mapper namespace="n">
  <resultMap id="rm" type="dto.Missing">
    <association property="a" javaType="dto.Missing">
      <association property="b" javaType="dto.Missing">
        <result property="notThere" column="c"/>
      </association>
    </association>
  </resultMap>
</mapper>""",
        )
        val file = myFixture.file as XmlFile
        val value = PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue::class.java)
            .first { (it.parent as? XmlAttribute)?.name == "property" && it.value == "notThere" }

        MybatisXmlTargets.classResolveCount.set(0)
        val target = BeanPropertyReference(value).resolve()
        assertNull("持有者类不存在时属性应解析为 null", target)
        assertEquals(
            "三层同名类名应被批内 memo 合并为一次 resolveClass（F97）",
            1,
            MybatisXmlTargets.classResolveCount.get(),
        )
    }

    // ------------------------------------------------------------------
    // EP 接线守卫（形态同 SqlMapperInjectorWiringTest 的 plugin.xml 正则守卫）
    // ------------------------------------------------------------------

    /** plugin.xml 须声明 `psi.referenceContributor language="XML"`（EP 声明冻结但接线不可丢）。 */
    fun testW7_pluginXml_psiReferenceContributor接线在场() {
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)
        assertTrue(
            "plugin.xml 须含 <psi.referenceContributor language=\"XML\" implementation=…MybatisXmlReferenceContributor>",
            Regex(
                "<psi\\.referenceContributor\\s+language=\"XML\"\\s+implementation=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.ref\\.MybatisXmlReferenceContributor\"",
            ).containsMatchIn(xml),
        )
    }

    /** 红线守卫：本包源码不得出现 inspection/annotator/ProblemHolder 类注册面。 */
    fun testW7_红线_本包无校验器形态() {
        val dir = java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref")
        assertTrue("W7 源码目录存在", dir.isDirectory)
        val banned = listOf("LocalInspectionTool", "Annotator", "ProblemHighlightType", "HighlightInfo", "InspectionManager")
        for (f in dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()) {
            val text = f.readText()
            for (token in banned) {
                assertFalse("W7 不得引入校验器形态（红线）：${f.name} 出现 $token", text.contains(token))
            }
        }
    }
}
