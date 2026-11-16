package cn.song.mybatisplus.visualizer.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * MP 占位展开端到端测试（T-M26-B，P-立项-03）：mapper XML `${ew.customSqlSegment}` →
 * namespace 关联 Mapper 接口 → ReferencesSearch 找调用 → wrapper 实参展开 → WHERE 片段注入。
 */
class XmlEwResolverTest : BasePlatformTestCase() {

    /** 注入轻量调用查找的预览入口（生产路径在 XmlPreviewService 内部走 ReferencesSearch）。 */
    private fun previewWithFinder(
        xml: String,
        id: String,
        project: com.intellij.openapi.project.Project,
        serviceFile: com.intellij.psi.PsiFile,
    ): cn.song.mybatisplus.visualizer.model.PreviewResult {
        val parsed = cn.song.mybatisplus.visualizer.xml.XmlMapperParser().parse(xml)
        val statement = parsed.statements.first { it.id == id }
        val warnings = mutableListOf<String>()
        // O-77：占位按语句域扫描（statement.rawText），与生产 resolveEwPlaceholders 口径一致
        val placeholders = cn.song.mybatisplus.visualizer.xml.XmlEwExpander.placeholders(statement.rawText ?: xml)
        val finder: (com.intellij.psi.PsiMethod) -> List<com.intellij.psi.PsiElement> = { method ->
            com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(serviceFile, com.intellij.psi.PsiMethodCallExpression::class.java)
                .filter { it.methodExpression.referenceName == method.name }
        }
        val resolved = XmlEwResolver.resolve(parsed.namespace, id, placeholders, project, warnings, finder)
        println("DEBUG-RESOLVED: " + resolved)
        println("DEBUG-WARNINGS: " + warnings)
        val ewBindings = resolved.mapValues { (_, fragments) -> cn.song.mybatisplus.visualizer.xml.MockObject(fragments) }
        val model = cn.song.mybatisplus.visualizer.xml.XmlPreviewModel.from(statement, parsed.errors, extraBindings = ewBindings)
        return XmlPreviewService.toResult(model, warnings)
    }

    private val mapperXml = """
        <mapper namespace="com.example.UserMapper">
          <select id="selectList" resultType="map">
            SELECT * FROM t_user ${'$'}{ew.customSqlSegment}
          </select>
        </mapper>
    """.trimIndent()

    fun testEwCustomSqlSegmentExpanded() {
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.Wrapper;

            public class User {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                java.util.List<Object> selectList(Wrapper ew);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Service.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

            public class Service {
                public void run(UserMapper mapper) {
                    mapper.selectList(new QueryWrapper<com.example.User>().eq("id", 1L).eq("name", "x"));
                }
            }
            """.trimIndent(),
        )
        // Light 测试注入轻量调用查找（生产走 ReferencesSearch，此处直接扫描调用表达式）
        val serviceFile = myFixture.file
        val result = previewWithFinder(mapperXml, "selectList", project, serviceFile)
        assertTrue("应展开 ew.customSqlSegment：${result.sql}", result.sql.contains("WHERE (id = 1"))
        assertTrue("不应有占位求值为空提示：${result.warning}", result.warning?.contains("求值为空") != true)
        assertEquals("SELECT * FROM t_user WHERE (id = 1 AND name = 'x')", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun testEwNoCallDegradesWithWarning() {
        // 接口存在但无调用处 → 降级提示（占位保持空）
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.Wrapper;

            public interface UserMapper {
                java.util.List<Object> selectList(Wrapper ew);
            }
            """.trimIndent(),
        )
        myFixture.configureByText("Unrelated.java", "public class Unrelated { int x = 1; }")
        val result = XmlPreviewService.preview(mapperXml, "selectList", project)
        assertTrue("应提示未找到调用：${result.warning}", result.warning?.contains("未找到") == true)
        // T-MbgExamplePreview R1：预览链占位模式——未展开的 ew 段以 {{表达式}} 显式可见
        assertEquals("SELECT * FROM t_user {{ew.customSqlSegment}}", result.sql.replace(Regex("\\s+"), " ").trim())
    }

    fun testEwNoNamespaceDegrades() {
        val noNsXml = """
            <mapper>
              <select id="selectList">SELECT * FROM t WHERE ${'$'}{ew.customSqlSegment}</select>
            </mapper>
        """.trimIndent()
        val result = XmlPreviewService.preview(noNsXml, "selectList", project)
        assertTrue("应提示缺 namespace：${result.warning}", result.warning?.contains("namespace") == true)
    }

    fun testEwWithoutProjectKeepsOldBehavior() {
        // 无 project（纯文本）→ 不展开：产物里以占位标出缺段。
        // T-MbgExamplePreview R6：preview() 的 warning 只留解析期/静态告警，渲染期提示由面板按
        // 本轮勾选重渲染后经 xmlErrors 显示——故此处改断言产物形态。
        val result = XmlPreviewService.preview(mapperXml, "selectList", null)
        assertEquals(
            "SELECT * FROM t_user {{ew.customSqlSegment}}",
            result.sql.replace(Regex("\\s+"), " ").trim(),
        )
    }

    // O-77 T-EwStatementScope：同文件他语句的 ew 占位不再记账到本语句——
    // 普通语句预览不得误报「无 Wrapper 参数（ew）」（修复前全文扫描把他语句占位算到本语句头上）
    fun testCrossStatementEwPlaceholderNotAttributedToPlainStatement() {
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.Wrapper;

            public class User {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                java.util.List<Object> selectByWrapper(Wrapper ew);
                java.util.List<Object> selectByCondition(String orderNo);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Service.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

            public class Service {
                public void run(UserMapper mapper) {
                    mapper.selectByWrapper(new QueryWrapper<com.example.User>().eq("id", 1L));
                }
            }
            """.trimIndent(),
        )
        val xml = """
            <mapper namespace="com.example.UserMapper">
              <select id="selectByWrapper" resultType="map">
                SELECT * FROM t_user ${'$'}{ew.customSqlSegment}
              </select>
              <select id="selectByCondition" resultType="map">
                SELECT * FROM t_user WHERE id = #{id}
              </select>
            </mapper>
        """.trimIndent()
        val plain = XmlPreviewService.preview(xml, "selectByCondition", project)
        assertNull("普通语句不应有 ew 警示：${plain.warning}", plain.warning)
        assertEquals("SELECT * FROM t_user WHERE id = ?", plain.sql.replace(Regex("\\s+"), " ").trim())
        // Light 夹具 ReferencesSearch 搜不到调用（既有 testEwCustomSqlSegmentExpanded 同因走注入 finder），
        // 展开能力断言走 previewWithFinder——helper 已按语句域扫占位，与生产口径一致
        val withEw = previewWithFinder(xml, "selectByWrapper", project, myFixture.file)
        assertTrue(
            "带 ew 语句应照旧展开：sql=${withEw.sql} warning=${withEw.warning}",
            withEw.sql.contains("WHERE (id = 1"),
        )
    }

    // O-77：不同变量名占位按语句各自解析（守卫：语句域扫描不得破坏多 wrapper 变量场景）
    fun testDifferentWrapperVariablesResolvedPerStatement() {
        myFixture.addFileToProject(
            "UserMapper.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.Wrapper;

            public class User {
                private Long id;
                private String name;
            }

            public interface UserMapper {
                java.util.List<Object> selectByEw(Wrapper ew);
                java.util.List<Object> selectByEw2(Wrapper ew2);
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "Service.java",
            """
            package com.example;
            import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

            public class Service {
                public void run(UserMapper mapper) {
                    mapper.selectByEw(new QueryWrapper<com.example.User>().eq("id", 1L));
                    mapper.selectByEw2(new QueryWrapper<com.example.User>().eq("name", "x"));
                }
            }
            """.trimIndent(),
        )
        val xml = """
            <mapper namespace="com.example.UserMapper">
              <select id="selectByEw" resultType="map">
                SELECT * FROM t_user ${'$'}{ew.customSqlSegment}
              </select>
              <select id="selectByEw2" resultType="map">
                SELECT * FROM t_user ${'$'}{ew2.customSqlSegment}
              </select>
            </mapper>
        """.trimIndent()
        val byEw = previewWithFinder(xml, "selectByEw", project, myFixture.file)
        assertTrue(
            "ew 语句应按本语句调用处展开：sql=${byEw.sql} warning=${byEw.warning}",
            byEw.sql.contains("WHERE (id = 1"),
        )
        val byEw2 = previewWithFinder(xml, "selectByEw2", project, myFixture.file)
        assertTrue(
            "ew2 语句应按本语句调用处展开：sql=${byEw2.sql} warning=${byEw2.warning}",
            byEw2.sql.contains("WHERE (name = 'x'"),
        )
        assertTrue("不应有跨语句误报：${byEw.warning}", byEw.warning?.contains("无 Wrapper 参数") != true)
        assertTrue("不应有跨语句误报：${byEw2.warning}", byEw2.warning?.contains("无 Wrapper 参数") != true)
    }
}
