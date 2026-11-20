package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import cn.song.mybatisplus.visualizer.xml.XmlIncludeScan
import cn.song.mybatisplus.visualizer.xml.XmlMapperFragments
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 跨命名空间 include 解析测试（T-IC-02/T-IC-03，仿 MapperXmlLocatorTest 的 LightPlatform 先例：
 * `myFixture.addFileToProject` 创建的真实文件可被 FileTypeIndex + PSI 命中）。
 *
 * 覆盖：跨文件命中展开（含动态标签）、property 穿透、歧义降级（多文件同 namespace 同 id）、
 * namespace 不匹配降级、纯文本链（无 project）沿用改动前降级文案、纯逻辑归并的失败原因文案。
 */
class XmlCrossNsFragmentResolverTest : BasePlatformTestCase() {

    private fun addMapper(path: String, content: String): com.intellij.psi.PsiFile =
        myFixture.addFileToProject(path, content)

    private fun preview(text: String, withProject: Boolean = true): PreviewResult =
        XmlPreviewService.INSTANCE.preview(text, "s1", if (withProject) project else null)

    private val baseMapper = """<?xml version="1.0" encoding="UTF-8"?>
<mapper namespace="com.demo.BaseMapper">
  <sql id="cols">id, name</sql>
  <sql id="aliasCol">${'$'}{alias}.id</sql>
  <sql id="cond"><if test="status != null">AND status = #{status}</if></sql>
</mapper>
"""

    fun test跨文件命中_片段展开且无降级警示() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT <include refid="com.demo.BaseMapper.cols"/> FROM t</select>
            </mapper>
        """.trimIndent())
        assertNull("跨命名空间命中不应有警示：${result.warning}", result.warning)
        assertEquals("SELECT id, name FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun test跨文件命中_动态标签片段展开() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t WHERE 1 = 1 <include refid="com.demo.BaseMapper.cond"/></select>
            </mapper>
        """.trimIndent())
        assertNull("跨命名空间动态片段应正常渲染：${result.warning}", result.warning)
        assertEquals("SELECT * FROM t WHERE 1 = 1 AND status = ?", result.sql.replace(Regex("\\s+"), " ").trim())
        assertEquals(listOf("status"), result.unresolvedParams)
    }

    fun test跨文件命中_property穿透() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT <include refid="com.demo.BaseMapper.aliasCol"><property name="alias" value="t1"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        assertNull("property 应穿透到跨文件片段：${result.warning}", result.warning)
        assertEquals("SELECT t1.id FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun test动态全限定refid_property解析后跨文件展开() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        // refid 含 `${}`：先按 include 自带 property 静态替换出全限定 refid，再跨文件查表
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT <include refid="${'$'}{ns}"><property name="ns" value="com.demo.BaseMapper.cols"/></include> FROM t</select>
            </mapper>
        """.trimIndent())
        assertNull("property 解析出的全限定 refid 应跨文件展开：${result.warning}", result.warning)
        assertEquals("SELECT id, name FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun test歧义_同名片段多文件重复声明_降级() {
        // 非法但存在：同 namespace 声明在两个文件（MyBatis 运行时后注册覆盖，本插件歧义降级）
        addMapper(
            "module-a/src/main/resources/mapper/DupMapper.xml",
            """<mapper namespace="com.demo.DupMapper"><sql id="cols">id</sql></mapper>""",
        )
        addMapper(
            "module-b/src/main/resources/mapper/DupMapper.xml",
            """<mapper namespace="com.demo.DupMapper"><sql id="cols">name</sql></mapper>""",
        )
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t <include refid="com.demo.DupMapper.cols"/></select>
            </mapper>
        """.trimIndent())
        assertTrue("歧义应降级并给出原因：${result.warning}", result.warning.orEmpty().contains("重复声明"))
        // T-IncludePlaceholder：缺失/歧义处留注释占位（旧契约是整段抹掉 → 残句）
        assertTrue(
            "缺失处应留 TODO 占位：${result.sql}",
            result.sql.contains("TODO: missing <sql id=\"com.demo.DupMapper.cols\">"),
        )
    }

    fun testnamespace不匹配_降级() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t <include refid="com.demo.Missing.cols"/></select>
            </mapper>
        """.trimIndent())
        assertTrue(
            "无该 namespace 应降级并给出原因：${result.warning}",
            result.warning.orEmpty().contains("未找到 namespace 为 com.demo.Missing"),
        )
        assertTrue(
            "缺失处应留 TODO 占位：${result.sql}",
            result.sql.contains("TODO: missing <sql id=\"com.demo.Missing.cols\">"),
        )
    }

    fun testnamespace匹配但片段不存在_降级() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t <include refid="com.demo.BaseMapper.nope"/></select>
            </mapper>
        """.trimIndent())
        assertTrue(
            "同 namespace 无该片段应降级并给出原因：${result.warning}",
            result.warning.orEmpty().contains("下无片段 nope"),
        )
        // T-IncludePlaceholder：缺失片段处留注释占位（旧契约是整段抹掉 → 残句）
        assertTrue(
            "缺失处应留 TODO 占位：${result.sql}",
            result.sql.contains("TODO: missing <sql id=\"com.demo.BaseMapper.nope\">"),
        )
    }

    fun test纯文本链_无project_沿用旧降级文案() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview(
            """
            <mapper namespace="com.demo.OrderMapper">
              <select id="s1">SELECT * FROM t <include refid="com.demo.BaseMapper.cols"/></select>
            </mapper>
            """.trimIndent(),
            withProject = false,
        )
        // 无 project 不做跨命名空间解析：文案与行为与 T-IncludeCrossNs 改动前逐字节一致
        assertTrue(
            "无 project 应沿用旧降级文案：${result.warning}",
            result.warning.orEmpty().contains(
                "Include refid 'com.demo.BaseMapper.cols' not found in this mapper " +
                    "(cross-namespace include is not supported).",
            ),
        )
        // T-IncludePlaceholder：同一处占位形态（纯文本链也留 TODO）
        assertTrue(
            "缺失处应留 TODO 占位：${result.sql}",
            result.sql.contains("TODO: missing <sql id=\"com.demo.BaseMapper.cols\">"),
        )
    }

    fun test同文件裸id_行为不变() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val result = preview("""
            <mapper namespace="com.demo.OrderMapper">
              <sql id="cols">a, b</sql>
              <select id="s1">SELECT <include refid="cols"/> FROM t</select>
            </mapper>
        """.trimIndent())
        assertNull("本文件片段仍走本文件展开：${result.warning}", result.warning)
        assertEquals("SELECT a, b FROM t", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun testCollect_歧义与未找到的原因文案() {
        val scan = XmlIncludeScan(
            "com.demo.OrderMapper",
            emptySet(),
            setOf("com.demo.DupMapper.cols", "com.demo.Missing.cols"),
        )
        val result = XmlCrossNsFragmentResolver.INSTANCE.collect(
            scan,
            listOf(
                XmlMapperFragments("com.demo.DupMapper", mapOf("cols" to "id")),
                XmlMapperFragments("com.demo.DupMapper", mapOf("cols" to "name")),
            ),
        )
        assertTrue("歧义不产出片段", result.fragments.isEmpty())
        assertTrue(result.failures.getValue("com.demo.DupMapper.cols").contains("歧义"))
        assertTrue(result.failures.getValue("com.demo.Missing.cols").contains("未找到 namespace 为 com.demo.Missing"))
    }

    fun testCollect_无候选refid_返回空表() {
        val result = XmlCrossNsFragmentResolver.INSTANCE.collect(
            XmlIncludeScan("n", setOf("cols"), emptySet()),
            listOf(XmlMapperFragments("other", mapOf("cols" to "id"))),
        )
        assertTrue(result.fragments.isEmpty())
        assertTrue(result.failures.isEmpty())
    }

    // ---------- T-PS5-05：片段表单槽缓存 ----------

    private fun crossNsPreview(refid: String): PreviewResult = preview(
        """
        <mapper namespace="com.demo.OrderMapper">
          <select id="s1">SELECT <include refid="$refid"/> FROM t</select>
        </mapper>
        """.trimIndent(),
    )

// T-MechanismSweep（2026-11-16）：片段表改「按 namespace 查自定义索引」，
    // T-PS5-05 的单槽缓存（TTL/targets 覆盖/文件 isValid）整套退役——下列用例改钉行为面。

    fun test索引化_二次渲染结果一致() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val first = crossNsPreview("com.demo.BaseMapper.cols")
        assertNull("首跑命中：${first.warning}", first.warning)
        val second = crossNsPreview("com.demo.BaseMapper.cols")
        assertEquals("二次渲染结果必须与首跑一致", first.sql, second.sql)
    }

    fun test索引化_未声明的namespace给出失败原因() {
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val other = crossNsPreview("com.demo.OtherMapper.cols")
        assertTrue("不存在的 namespace 仍给出准确失败原因", other.warning != null)
    }

    /** 索引由平台增量维护：新增片段文件后同一会话内立即可见（旧链 5s TTL 内会漏）。 */
    fun test索引化_新增片段文件立即可见() {
        val beforeAdd = crossNsPreview("com.demo.BaseMapper.cols")
        assertTrue("前置：此时确实还没有该片段", beforeAdd.warning != null)
        addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val afterAdd = crossNsPreview("com.demo.BaseMapper.cols")
        assertNull("新增文件必须立即可见：${afterAdd.warning}", afterAdd.warning)
    }

    fun test索引化_文件删除后降级不沿用旧片段() {
        val psi = addMapper("src/main/resources/mapper/BaseMapper.xml", baseMapper)
        val first = crossNsPreview("com.demo.BaseMapper.cols")
        assertNull("删除前命中：${first.warning}", first.warning)
        com.intellij.openapi.application.WriteAction.compute<Unit, RuntimeException> {
            psi.virtualFile.delete(this)
        }
        val after = crossNsPreview("com.demo.BaseMapper.cols")
        assertTrue(
            "文件删除后必须按未找到降级（不得沿用失效文件的片段）",
            after.warning != null && after.warning!!.contains("BaseMapper"),
        )
    }

    // ===== T-BindStaticRefCrossNs：跨命名空间片段内的 bind 静态常量（用户语料 StationAuthScope.xml 形态）=====

    private fun constsFixture(value: String) {
        myFixture.addFileToProject(
            "com/demo/Consts.java",
            "package com.demo; public class Consts { public static final int IDLE = $value; }",
        )
    }

    private fun scopeFragment(fieldName: String): String = """<mapper namespace="com.demo.ConstsMapper">
          <sql id="scope"><bind name="scopeCategory" value="@com.demo.Consts@$fieldName"/>AND cat = #{scopeCategory}</sql>
        </mapper>"""

    private fun includeScope(): PreviewResult = preview(
        """
        <mapper namespace="com.demo.OrderMapper">
          <select id="s1">SELECT * FROM t WHERE 1 = 1
            <include refid="com.demo.ConstsMapper.scope"/></select>
        </mapper>
        """.trimIndent(),
    )

    fun testT_BindStaticRefCrossNs_片段内bind静态常量按项目PSI求值() {
        constsFixture("7")
        addMapper("src/main/resources/mapper/ConstsMapper.xml", scopeFragment("IDLE"))
        val result = includeScope()
        // 判别器：bind 求值结果进 bindValues（面板参数行据此回填）——只扫当前文件时此处恒 null
        val rendered = result.xmlModel!!.renderSql()
        assertEquals("7", rendered.bindValues["scopeCategory"])
        assertTrue(
            "片段内静态常量已解析时不得再报「求值为空」：${rendered.errors}",
            rendered.errors.none { it.contains("求值为空") },
        )
        assertNull("解析成功不应有基线告警：${result.warning}", result.warning)
    }

    fun testT_BindStaticRefCrossNs_片段内字段不存在仍报警() {
        constsFixture("7")
        addMapper("src/main/resources/mapper/ConstsMapper.xml", scopeFragment("MISSING"))
        val result = includeScope()
        // 扫描域扩大后，解析不到的片段 ref 同样进基线告警（面板横幅），与同文件 ref 同口径
        assertTrue("片段内解析不到须给出信号：${result.warning}", result.warning?.contains("MISSING") == true)
    }
}
