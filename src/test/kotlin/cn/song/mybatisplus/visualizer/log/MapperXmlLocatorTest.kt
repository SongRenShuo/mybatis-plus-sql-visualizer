package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * [MapperXmlLocator] 平台集成测试（T-M55-01 跳转双目标 + F1''' 索引化）：
 *
 * 在 LightPlatform 虚拟项目中创建真实 mapper XML（走 VirtualFile + PsiManager 全链路），
 * 验证：
 *  - 命中：namespace + 语句 id 匹配 → 定位到语句标签起始行（0-based）；
 *  - namespace 不匹配 → null（PSI 校验防线）；
 *  - 无 XML / 语句 id 不存在 → null（回退 Java 跳转，不劣化）；
 *  - 多模块同名 mapper（同 namespace 同 id）→ 取第一个，不抛不阻；
 *  - statementId 形态守卫：无点 / 空串 → null；
 *  - F1'''：定位链走自定义 fileBasedIndex（[MapperNamespaceIndex]）——索引期摊平 namespace、
 *    运行时 getContainingFiles 毫秒级，失效全交平台增量（MPJ 精华采纳，见调研报告 20261115-0202）；
 *    此前的单槽 TTL/头扫/异步重启机制整体退役，对应机制测试随之删除。
 */
class MapperXmlLocatorTest : BasePlatformTestCase() {

    private val mapperXml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.demo.mapper.UserMapper">
    <select id="selectById" resultType="map">SELECT * FROM t_user WHERE id = #{id}</select>
    <insert id="insertUser">INSERT INTO t_user(name) VALUES(#{name})</insert>
</mapper>
"""

    private fun addMapperXml(path: String, content: String) {
        myFixture.addFileToProject(path, content)
    }

    /** 期望行号：语句 id 首次出现所在行（0-based），与 fixture 文本逐行核对。 */
    private fun expectedLineOf(content: String, marker: String): Int =
        content.lineSequence().indexOfFirst { it.contains(marker) }

    fun test命中_定位到语句标签行() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("namespace+id 匹配应命中", loc)
        assertEquals("UserMapper.xml", loc!!.virtualFile.name)
        assertEquals(
            "行号 = 语句标签起始行（0-based）",
            expectedLineOf(mapperXml, "selectById"),
            loc.line,
        )
    }

    fun test命中_insert语句() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.insertUser")
        assertNotNull(loc)
        assertEquals(
            expectedLineOf(mapperXml, "insertUser"),
            loc!!.line,
        )
    }

    fun testNamespace不匹配_返回null() {
        addMapperXml(
            "src/main/resources/mapper/OtherMapper.xml",
            mapperXml.replace("com.demo.mapper.UserMapper", "com.demo.mapper.OtherMapper"),
        )
        assertNull(
            "namespace 精确匹配防线：不匹配应 null",
            MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"),
        )
    }

    fun test语句id不存在_返回null() {
        addMapperXml("src/main/resources/mapper/UserMapper.xml", mapperXml)
        assertNull(
            "同 namespace 但 id 不存在应 null",
            MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.notExists"),
        )
    }

    fun test无XML_返回null() {
        assertNull("项目内无任何 mapper XML 应 null", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
    }

    fun test非Mapper根元素的XML不参与() {
        addMapperXml(
            "src/main/resources/mapper/NotMapper.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <select id="selectById">SELECT 1</select>
</configuration>
""",
        )
        assertNull("根元素非 <mapper> 的 XML 不参与定位", MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById"))
    }

    fun test多模块同名_取第一个不抛() {
        addMapperXml("module-a/src/main/resources/mapper/UserMapper.xml", mapperXml)
        addMapperXml("module-b/src/main/resources/mapper/UserMapper.xml", mapperXml)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("多模块同名应取第一个命中", loc)
        assertEquals("UserMapper.xml", loc!!.virtualFile.name)
    }

    fun testStatementId形态守卫() {
        assertNull("无点号（非 namespace.id 形态）应返回 null", MapperXmlLocator.splitStatementId("selectById"))
        assertNull("空串应返回 null", MapperXmlLocator.splitStatementId(""))
        assertNull("以点结尾应返回 null", MapperXmlLocator.splitStatementId("com.demo.UserMapper."))
        assertNull("以点开头应返回 null", MapperXmlLocator.splitStatementId(".selectById"))
        assertEquals(
            "namespace.id 按最后一个点拆分",
            "com.demo.mapper.UserMapper" to "selectById",
            MapperXmlLocator.splitStatementId("com.demo.mapper.UserMapper.selectById"),
        )
    }

    // ------------------------------------------------------------------
    // F1'''：索引器 namespace 提取内核（纯文本单测，索引期与运行期同源）
    // ------------------------------------------------------------------

    fun testF1三撇_头扫内核_属性序与引号形态() {
        // 正常形态：DOCTYPE 之后根标签带 namespace
        assertEquals(
            "com.demo.mapper.UserMapper",
            MapperNamespaceIndex.namespaceFromHeadText(
                "<?xml version=\"1.0\"?>\n" +
                    "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"x\">\n" +
                    "<mapper namespace=\"com.demo.mapper.UserMapper\">\n" +
                    "    <select id=\"a\">x</select>\n" +
                    "</mapper>\n",
            ),
        )
        // namespace 属性在其它属性之后（非首属性）
        assertEquals(
            "com.demo.B",
            MapperNamespaceIndex.namespaceFromHeadText("<mapper parameterType=\"x\" namespace=\"com.demo.B\">"),
        )
        // 单引号形态
        assertEquals("com.demo.C", MapperNamespaceIndex.namespaceFromHeadText("<mapper namespace='com.demo.C'>"))
    }

    fun testF1三撇_头扫内核_非mapper与mappers前缀不误收() {
        // 根元素不是 mapper（page 内嵌 mapper 子标签）→ 内层 mapper 标签无 namespace → null
        assertNull(
            MapperNamespaceIndex.namespaceFromHeadText(
                "<page namespace=\"com.demo.UserMapper\"><mapper><select id=\"a\"/></mapper></page>",
            ),
        )
        // `<mappers>`（MyBatis 配置根）内的 <mapper> 条目只带 resource/class 不带 namespace
        assertNull(MapperNamespaceIndex.namespaceFromHeadText("<mappers><mapper resource=\"x.xml\"/></mappers>"))
        // 无 mapper 标签
        assertNull(MapperNamespaceIndex.namespaceFromHeadText("<foo namespace=\"x\"/>"))
        // mapper 标签无 namespace 属性
        assertNull(MapperNamespaceIndex.namespaceFromHeadText("<mapper><select id=\"a\"/></mapper>"))
    }
}
