package cn.song.mybatisplus.visualizer.heavy

import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.ui.IbatisTypeAliasResolver
import cn.song.mybatisplus.visualizer.ui.IbatisXmlPreviewService
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import cn.song.mybatisplus.visualizer.ui.IbatisParameterClassResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-IbatisParamClassQuote：iBatis 裸 `#name#` 的回填引号来自 `parameterClass` 的属性类型。
 *
 * 走 Heavy 桶：短名 → 类 的检索依赖真实索引（Light 跨文件 resolve/索引不可用，见
 * `EntityClassFinderHeavyTest` 的同类说明），这里用 [createTestProjectStructure] 把类落磁盘。
 *
 * 端到端口径与面板同源：`preview` → `paramHints` → [ParameterReplacer.buildExecutableSql]。
 */
class IbatisParamClassQuoteHeavyTest : HeavyPlatformTestCase() {

    private val xml =
        """<?xml version="1.0" encoding="UTF-8"?>
           |<!DOCTYPE sqlMap PUBLIC "-//iBATIS.com//DTD SQL Map 2.0//EN" "http://ibatis.apache.org/dtd/sql-map-2.dtd">
           |<sqlMap namespace="User">
           |  <select id="byName" parameterClass="User">SELECT ID FROM T_USER WHERE USER_NAME = #username#</select>
           |  <select id="byAge" parameterClass="User">SELECT ID FROM T_USER WHERE AGE = #age#</select>
           |  <select id="byAgeJdbc" parameterClass="User">SELECT ID FROM T_USER WHERE AGE = #age:VARCHAR#</select>
           |</sqlMap>
        """.trimMargin()

    /** 在项目 src 根下创建 Java 源文件（磁盘 + 索引），返回 PsiClass。 */
    private fun addClassFile(name: String, content: String): PsiClass =
        WriteCommandAction.runWriteCommandAction(project, Computable {
            val root = createTestProjectStructure()
            val src = root.findChild("src") ?: root.createChildDirectory(this, "src")
            val vf = src.createChildData(this, name)
            VfsUtil.saveText(vf, content.trimIndent())
            PsiManager.getInstance(project).findFile(vf)!!
        }).let { file -> PsiTreeUtil.findChildOfType(file, PsiClass::class.java)!! }

    /** 在项目 src 根下落一个 XML（iBatis config 用；不进 PsiClass 查找）。 */
    private fun addXmlFile(name: String, content: String) {
        WriteCommandAction.runWriteCommandAction(project, Computable {
            val root = createTestProjectStructure()
            val src = root.findChild("src") ?: root.createChildDirectory(this, "src")
            val vf = src.findChild(name) ?: src.createChildData(this, name)
            VfsUtil.saveText(vf, content.trimIndent())
        })
    }

    private fun addUserClass() = addClassFile(
        "User.java",
        """
        package p;

        public class User {
            private String username;
            private Integer age;
        }
        """,
    )

    private fun backfill(id: String, values: Map<String, String>, parameterClass: String = "User"): String {
        val result = IbatisXmlPreviewService.preview(
            xml, id,
            project = project,
            parameterClass = parameterClass,
        )
        return ParameterReplacer.buildExecutableSql(
            sql = result.sql,
            sources = result.unresolvedParams,
            paramValues = values,
            hints = result.paramHints,
        )
    }

    fun testBareParameterQuotedByParameterClass() {
        addUserClass()
        assertEquals(
            "SELECT ID FROM T_USER WHERE USER_NAME = '1213'",
            backfill("byName", mapOf("username" to "1213")),
        )
    }

    /**
     * 诊断用例（解析链三段读数一次拿）：短名索引命中数 / facade 结果 / resolver 输出。
     * 失败消息里同时给出三者，避免逐段加探针反复跑 Heavy（单轮十几分钟）。
     */
    fun testDiagnosticResolutionChain() {
        addUserClass()
        val scope = GlobalSearchScope.allScope(project)
        val byShortName = PsiShortNamesCache.getInstance(project).getClassesByName("User", scope)
        val cls = byShortName.singleOrNull()
        val field = cls?.findFieldByName("username", true)
        val type = field?.type
        val typeHint = type?.let { IbatisParameterClassResolver.hintOfType(it) }
        val strict = runCatching {
            IbatisParameterClassResolver.resolveStrict(project, "User", listOf("username"))
        }
        assertFalse(
            "shortName=${byShortName.size} cls=${cls?.qualifiedName} field=${field?.name} " +
                "type=$type typeHint=$typeHint strict=${strict.getOrNull()} " +
                "strictErr=${strict.exceptionOrNull()}",
            strict.getOrNull().isNullOrEmpty(),
        )
    }

    fun testNumericFieldStaysBare() {
        addUserClass()
        assertEquals(
            "SELECT ID FROM T_USER WHERE AGE = 18",
            backfill("byAge", mapOf("age" to "18")),
        )
    }

    fun testJdbcTypeWinsOverFieldType() {
        addUserClass()
        // 占位符写着 VARCHAR、字段是 Integer：既有口径 jdbcType 胜出 → 加引号
        assertEquals(
            "SELECT ID FROM T_USER WHERE AGE = '18'",
            backfill("byAgeJdbc", mapOf("age" to "18")),
        )
    }

    /**
     * L34 回归：项目里有多个同名参数类时**不得猜**——测试项目是单模块（两个候选同模块），
     * 模块收窄救不了，必须弃权、退回面板启发式（裸渲染），而不是贴到随便一个 `User` 上。
     * 模块收窄的正例由 `IbatisParamCandidatePickTest` 覆盖（纯逻辑，不需要多模块工程）。
     */
    fun testMultipleSameNameCandidatesKeepsHeuristic() {
        addClassFile("UserInP.java", "package p; public class User { private String username; }")
        addClassFile("UserInQ.java", "package q; public class User { private String username; }")
        val result = IbatisXmlPreviewService.preview(xml, "byName", project = project, parameterClass = "User")
        assertTrue(
            "多候选且同模块无法区分时应弃权：${result.paramHints}",
            result.paramHints.all { it.quote == null },
        )
    }

    fun testUnresolvableParameterClassKeepsHeuristic() {
        // 不注册 User 类：短名索引零命中 → 不产提示，面板启发式（1213 裸渲染）
        assertEquals(
            "SELECT ID FROM T_USER WHERE USER_NAME = 1213",
            backfill("byName", mapOf("username" to "1213")),
        )
    }

    fun testNoProjectKeepsHeuristic() {
        addUserClass()
        val result = IbatisXmlPreviewService.preview(xml, "byName")
        assertEquals("SELECT ID FROM T_USER WHERE USER_NAME = ?", result.sql)
        assertTrue("无 project 时不应产 hint：${result.paramHints}", result.paramHints.all { it.quote == null })
    }

    /**
     * T-MbgPreviewParityAndOpenPerf P6：`<typeAlias>` 比「短名唯一命中」更能消歧义——
     * 两个同名 `User` 落在**同一模块**，短名 + 模块收窄救不了（同模块多候选 ⇒ 弃权），
     * 而 sqlMapConfig 的别名直接给出 FQN。
     */
    fun testTypeAliasResolvesAmbiguousShortName() {
        IbatisTypeAliasResolver.clearCache()
        addClassFile("UserInP.java", "package p; public class User { private String username; }")
        addClassFile("UserInQ.java", "package q; public class User { private String username; }")
        addXmlFile(
            "sqlmap-config.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
               |<!DOCTYPE sqlMapConfig PUBLIC "-//iBATIS.com//DTD SQL Map Config 2.0//EN" "http://ibatis.apache.org/dtd/sql-map-config-2.dtd">
               |<sqlMapConfig>
               |  <typeAlias alias="User" type="p.User"/>
               |</sqlMapConfig>
            """.trimMargin(),
        )
        val result = IbatisXmlPreviewService.preview(
            xml, "byName",
            project = project,
            parameterClass = "User",
            moduleName = ModuleManager.getInstance(project).modules.first().name,
        )
        assertEquals(
            "别名指向 p.User ⇒ 应拿到类型并加引号：${result.paramHints}",
            true,
            result.paramHints.single().quote,
        )
    }
}