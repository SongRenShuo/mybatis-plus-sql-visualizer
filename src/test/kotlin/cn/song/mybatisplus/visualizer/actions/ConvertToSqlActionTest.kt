package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisStatementPreview
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 转换 Action（正向 Wrapper→SQL）update() 可见性测试（F-44 续 · 补用例大波次）：
 * 此前 0 测试。update 读取 PSI_FILE 决定启用范围（Java 文件 / XML mapper 可用，其它文件不可用）。
 * 用 MapDataContext 构造 AnActionEvent，确定性校验可见性逻辑，不触发 actionPerformed。
 */
class ConvertToSqlActionTest : BasePlatformTestCase() {

    private fun eventFor(file: com.intellij.psi.PsiFile?, editor: com.intellij.openapi.editor.Editor?): AnActionEvent {
        val dc = MapDataContext()
        dc.put(CommonDataKeys.PROJECT.name, project)
        if (file != null) dc.put(CommonDataKeys.PSI_FILE.name, file)
        if (editor != null) dc.put(CommonDataKeys.EDITOR.name, editor)
        return AnActionEvent(null, dc, ActionPlaces.UNKNOWN, Presentation(), ActionManager.getInstance(), 0)
    }

    fun testUpdate_Java文件可见可用() {
        myFixture.configureByText("A.java", "class A {}")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertTrue("Java 文件应启用", e.presentation.isEnabledAndVisible)
    }

    fun testUpdate_Xml文件可见可用() {
        myFixture.configureByText("a.xml", "<root/>")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertTrue("XML mapper 文件应启用", e.presentation.isEnabledAndVisible)
    }

    fun testUpdate_Kotlin文件主动入口可达且命中Wrapper时给专用降级说明() {
        myFixture.configureByText(
            "Demo.kt",
            """
            fun query() {
                service.ktQuery().eq(User::name, "x").list()
            }
            """.trimIndent(),
        )
        val offset = myFixture.file.text.indexOf("ktQuery")
        myFixture.editor.caretModel.moveToOffset(offset)
        val event = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(event)
        assertTrue("Kotlin 主动快捷入口应保持可达", event.presentation.isEnabledAndVisible)
        assertTrue(ConvertToSqlAction.kotlinWrapperUnavailableAt(myFixture.file, offset))

        val captured = java.util.concurrent.atomic.AtomicReference<com.intellij.notification.Notification?>()
        ConvertToSqlAction.kotlinWrapperUnavailableNoticeForTest = { captured.set(it) }
        try {
            ConvertToSqlAction().actionPerformed(event)
            PlatformTestUtil.waitWithEventsDispatching(
                "Kotlin Wrapper 主动入口应给降级说明",
                { captured.get() != null },
                5_000,
            )
            val notification = captured.get()!!
            assertEquals(MpSqlVisualizerBundle.message("action.kotlinWrapperUnsupported.title"), notification.title)
            assertEquals(MpSqlVisualizerBundle.message("action.kotlinWrapperUnsupported.message"), notification.content)
        } finally {
            ConvertToSqlAction.kotlinWrapperUnavailableNoticeForTest = null
        }
    }

    fun testUpdate_Kotlin普通行不误判Wrapper降级() {
        myFixture.configureByText("Demo.kt", "fun query() = println(\"ok\")")
        val offset = myFixture.file.text.indexOf("println")
        assertFalse(ConvertToSqlAction.kotlinWrapperUnavailableAt(myFixture.file, offset))
    }

    fun testUpdate_非Java非Xml文件不可见() {
        myFixture.configureByText("a.txt", "x")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertFalse("普通文本文件不应启用", e.presentation.isEnabledAndVisible)
    }

    /**
     * T-Ibatis2Support 券 A 收尾：XML 候选判据三路来源。
     *
     * 背景＝通用兜底通道对 iBatis `<sqlMap>` 语句**让位**（防与专用 gutter provider 双图标）后，
     * 若快捷键候选仍只认「MyBatis 语句 + 兜底载体」，Alt+Shift+Q 在 sqlMap 文件里会**空候选**——
     * gutter 图标正常、快捷键什么都列不出来。本用例把三路来源焊死在单一判据上。
     */
    fun testXmlStatementCandidate_三路来源与边界() {
        myFixture.configureByText(
            "ibatis.xml",
            """
            <sqlMap namespace="user">
              <resultMap id="rm" class="A"/>
              <sql id="cols">ID, NAME</sql>
              <select id="leaf">SELECT 1 FROM T</select>
              <select id="dyn">
                WHERE
                <dynamic prepend="AND">
                  <isNotNull property="id">ID = #id#</isNotNull>
                </dynamic>
              </select>
              <statement id="raw">SELECT * FROM T WHERE ID = ?</statement>
            </sqlMap>
            """.trimIndent(),
        )
        val tags = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            com.intellij.psi.xml.XmlTag::class.java,
        )
        fun tag(name: String, id: String? = null) = tags.first {
            it.name == name && (id == null || it.getAttributeValue("id") == id)
        }

        // iBatis 语句：叶子与含动态子标签两种形态都必须进候选（后者正是用户报障的零图标形态）
        assertTrue("iBatis 叶子 select 应进候选", ConvertToSqlAction.isXmlStatementCandidate(tag("select", "leaf")))
        assertTrue("含 <dynamic> 的 select 也应进候选", ConvertToSqlAction.isXmlStatementCandidate(tag("select", "dyn")))
        assertTrue("iBatis <statement> 应进候选", ConvertToSqlAction.isXmlStatementCandidate(tag("statement", "raw")))
        // 非语句：resultMap / <sql> 片段 / <dynamic> 自身都不该进候选
        assertFalse("resultMap 不是语句", ConvertToSqlAction.isXmlStatementCandidate(tag("resultMap")))
        assertFalse("<sql> 片段不是语句", ConvertToSqlAction.isXmlStatementCandidate(tag("sql")))
        assertFalse("<dynamic> 不是语句", ConvertToSqlAction.isXmlStatementCandidate(tag("dynamic")))
    }

    /** MyBatis 路径零回归：`<mapper>` 下语句仍进候选（判据重构不得改变既有来源）。 */
    fun testXmlStatementCandidate_MyBatis路径不变() {
        myFixture.configureByText(
            "UserMapper.xml",
            """
            <mapper namespace="demo.UserMapper">
              <select id="byId">SELECT * FROM t_user WHERE id = #{id}</select>
              <sql id="cols">id, name</sql>
            </mapper>
            """.trimIndent(),
        )
        val tags = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
            myFixture.file,
            com.intellij.psi.xml.XmlTag::class.java,
        )
        val select = tags.first { it.name == "select" }
        assertTrue("<mapper> 下语句应进候选", ConvertToSqlAction.isXmlStatementCandidate(select))
    }

    /**
     * T-IbatisCallPreview：iBatis DAO 调用行的形状门必须在 JDBC 通道之前认领。
     *
     * 现状（修复前）：`JdbcTemplateSqlLocator` **没有** iBatis 让位门 → 它认领该调用后
     * `JdbcTemplateSqlPreviewService.preview` 用 `isLikelySql("User.selectById")` 拒绝 → 动作
     * 已 `return true` → 用户拿到「未找到可转换的 Wrapper」（错源提示）。
     * 本用例把「JDBC 会认领但渲染不出东西」与「iBatis 门能渲染出 SQL」两侧同时钉住。
     */
    fun testiBatis调用行_预览门先于JDBC通道() {
        myFixture.addFileToProject(
            "sqlmap/User.xml",
            """<sqlMap namespace="User"><select id="selectById">SELECT ID FROM T_USER WHERE ID = #id#</select></sqlMap>""",
        )
        val dao = myFixture.addFileToProject(
            "com/demo/UserDaoImpl.java",
            """
            package com.demo;
            public class UserDaoImpl {
                private Object client;
                public Object selectById(int id) { return client.queryForObject("User.selectById", id); }
            }
            """.trimIndent(),
        )
        IbatisXmlStatementTargets.clearCachesForTest()
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "User.selectById" }

        // 对照：JDBC 通道会认领这个调用（所以「谁先认领」决定用户看到什么）
        val jdbcCall = JdbcTemplateSqlLocator.locate(literal)
        assertNotNull("夹具前提：JdbcTemplateSqlLocator 不拦 iBatis 调用", jdbcCall)
        assertNull(
            "JDBC 通道对 statementId 渲染不出 SQL（错源提示的来源）",
            JdbcTemplateSqlPreviewService.preview(jdbcCall!!, literal),
        )

        // 新门：形状门（与 gutter 让位同一函数）认领并渲染出 iBatis SQL
        val target = IbatisCallTargets.resolve(literal)
        assertNotNull("iBatis 形状门应认领该调用", target)
        assertEquals("User.selectById", target!!.statementId)
        val preview = IbatisStatementPreview.previewResolved(project, target.statementId, target.ownerNamespace)
        assertTrue("iBatis 预览应渲染出 SQL：${preview.sql}", preview.sql.contains("FROM T_USER"))
        assertEquals(listOf("id"), preview.unresolvedParams)
    }
}
