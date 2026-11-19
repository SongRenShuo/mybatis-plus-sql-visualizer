package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlPreviewService
import cn.song.mybatisplus.visualizer.psource.JdbcTemplateSqlLocator
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisCallTargets
import cn.song.mybatisplus.visualizer.ui.gutter.IbatisStatementPreview
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 转换 Action（正向 Wrapper→SQL）update() 可见性测试（F-44 续 · 补用例大波次）：
 * 此前 0 测试。update 读取 PSI_FILE 决定启用范围（Java 文件 / XML mapper 可用，其它文件不可用）。
 * 用 MapDataContext 构造 AnActionEvent，确定性校验可见性逻辑，不触发 actionPerformed。
 */
class ConvertToSqlActionTest : BasePlatformTestCase() {

    private fun eventFor(file: com.intellij.psi.PsiFile?, editor: com.intellij.openapi.editor.Editor?): AnActionEvent {
        val dc = MapDataContext()
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

    fun testUpdate_非Java非Xml文件不可见() {
        myFixture.configureByText("a.txt", "x")
        val e = eventFor(myFixture.file, myFixture.editor)
        ConvertToSqlAction().update(e)
        assertFalse("普通文本文件不应启用", e.presentation.isEnabledAndVisible)
    }

    // ------------------------------------------------------------------
    // T-IbatisAtShapeGate（本轮同步引入）：@ 形态语句名认领与错源兜底
    // ------------------------------------------------------------------

    /**
     * T-IbatisAtShapeGate：`组名@命名空间.语句id` 形态在**快捷键回落链**上必须被 iBatis 认领。
     *
     * 真机报障：`baseDao.queryForObject("cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType", inMap)`
     * ——gutter 图标点击正常，Alt+Shift+Q 弹「未在光标处找到可预览的 SQL 源」。根因是形状门正则
     * 不含 `@`：iBatis 两档回落都放弃，`JdbcTemplateSqlLocator`（action 侧无让位门）认领后
     * `isLikelySql` 拒绝 → 动作已 return true → 错源提示。
     */
    fun test带At符号的自研语句名_iBatis形状门认领且渲染出SQL() {
        myFixture.addFileToProject(
            "sqlmap/Account.xml",
            """<sqlMap namespace="cQAccountTypeByAccountType@custmg"><select id="qAccountTypeByAccountType">SELECT * FROM T_ACCOUNT_TYPE WHERE ACCOUNT_TYPE = #accountType#</select></sqlMap>""",
        )
        val dao = myFixture.addFileToProject(
            "com/demo/AccountDaoImpl.java",
            """
            package com.demo;
            public class AccountDaoImpl {
                private Object baseDao;
                public Object query(String accountType) {
                    return baseDao.queryForObject("cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType", accountType);
                }
            }
            """.trimIndent(),
        )
        IbatisXmlStatementTargets.resetPerfLogForTest()
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { (it.value as? String)?.contains("@custmg.") == true }

        // ① 对照：JDBC 通道会认领这个调用（谁先认领决定用户看到什么）
        val jdbcCall = JdbcTemplateSqlLocator.locate(literal)
        assertNotNull("夹具前提：JdbcTemplateSqlLocator 不拦 @ 形态 iBatis 调用", jdbcCall)
        assertNull(
            "JDBC 通道对 @ 形态 statementId 渲染不出 SQL（错源提示的来源）",
            JdbcTemplateSqlPreviewService.preview(jdbcCall!!, literal),
        )

        // ② 修复后：形状门（与 gutter 让位同一函数）认领并渲染出 iBatis SQL
        val target = IbatisCallTargets.resolve(literal)
        assertNotNull("iBatis 形状门应认领 @ 形态调用（修复前为 null → 错源提示）", target)
        assertEquals(
            "cQAccountTypeByAccountType@custmg.qAccountTypeByAccountType",
            target!!.statementId(),
        )
        val preview = IbatisStatementPreview.previewResolved(project, target.statementId(), target.ownerNamespace())
        assertTrue("iBatis 预览应渲染出 SQL：${preview.sql}", preview.sql.contains("FROM T_ACCOUNT_TYPE"))
        assertEquals(listOf("accountType"), preview.unresolvedParams)
    }

    /**
     * T-IbatisAtShapeGate S4：**错源兜底**——JDBC 通道认领且渲染失败后，跳过形状正则再试一次 iBatis。
     */
    fun test错源兜底_形状门不认的语句名经解析命中后接管() {
        myFixture.addFileToProject(
            "sqlmap/Dash.xml",
            """<sqlMap namespace="Dash"><select id="select-by-id">SELECT ID FROM T_DASH WHERE ID = #id#</select></sqlMap>""",
        )
        val dao = myFixture.addFileToProject(
            "com/demo/DashDaoImpl.java",
            """
            package com.demo;
            public class DashDaoImpl {
                private Object client;
                public Object select(int id) { return client.queryForObject("Dash.select-by-id", id); }
            }
            """.trimIndent(),
        )
        IbatisXmlStatementTargets.resetPerfLogForTest()
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "Dash.select-by-id" }

        // 前提：常规形状门拒绝（这就是缺陷链的起点）
        assertNull("形状门不认 `-`，常规认领放弃", IbatisCallTargets.resolve(literal))
        // 前提：JDBC 会认领但渲染不出（错源提示的来源）
        val jdbcCall = JdbcTemplateSqlLocator.locate(literal)
        assertNotNull("JDBC 定位器认领该调用", jdbcCall)
        assertNull("JDBC 渲染不出 SQL", JdbcTemplateSqlPreviewService.preview(jdbcCall!!, literal))

        // 兜底口径：跳过形状正则 → 解析命中 → 渲染出 SQL（命中才接管）
        val target = IbatisCallTargets.resolveIgnoringShape(literal)
        assertNotNull("兜底口径应定位到该调用", target)
        val preview = IbatisStatementPreview.previewResolvedOrNull(project, target!!.statementId(), target.ownerNamespace())
        assertNotNull("语句存在 → 兜底预览接管（不是 null，故不会落「未找到」）", preview)
        assertTrue("兜底渲染出 iBatis SQL：${preview!!.sql}", preview.sql.contains("FROM T_DASH"))
    }

    /**
     * 兜底不得给非 iBatis 工程制造假阳性：语句不存在时 `previewResolvedOrNull` 返回 null，
     * 调用方保持原「未找到」提示（而不是 iBatis「语句未找到」的误导文案）。
     */
    fun test错源兜底_语句不存在时返回null保持原提示() {
        myFixture.addFileToProject(
            "sqlmap/Dash.xml",
            """<sqlMap namespace="Dash"><select id="select-by-id">SELECT ID FROM T_DASH WHERE ID = #id#</select></sqlMap>""",
        )
        val dao = myFixture.addFileToProject(
            "com/demo/OtherDaoImpl.java",
            """
            package com.demo;
            public class OtherDaoImpl {
                private Object cache;
                public void refresh() { cache.update("refreshCache"); }
            }
            """.trimIndent(),
        )
        IbatisXmlStatementTargets.resetPerfLogForTest()
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "refreshCache" }

        val target = IbatisCallTargets.resolveIgnoringShape(literal)
        assertNotNull("入口方法名 + 字面量即进入兜底口径", target)
        assertNull(
            "解析未命中 → null（调用方保持原「未找到」提示，不改写成 iBatis 语句未找到）",
            IbatisStatementPreview.previewResolvedOrNull(project, target!!.statementId(), target.ownerNamespace()),
        )
    }

    /**
     * 结构守卫（S4）：错源兜底必须真的接在 `tryJdbcTemplateFallback` 的失败分支上——
     * 行为用例只能证明兜底函数本身，接线顺序/存在性只能在源码上钉。
     */
    fun test错源兜底接线在JdbcTemplate失败分支() {
        val source = java.io.File(
            "src/main/java/cn/song/mybatisplus/visualizer/actions/ConvertToSqlAction.java",
        ).readText()
        val fnStart = source.indexOf("private boolean tryJdbcTemplateFallback(")
        assertTrue("tryJdbcTemplateFallback 必须存在", fnStart > 0)
        val fnBody = source.substring(fnStart, source.indexOf("\n    }", fnStart))
        assertTrue(
            "JDBC 预览失败分支必须调 resolveIgnoringShape 兜底",
            fnBody.contains("resolveIgnoringShape"),
        )
        assertTrue(
            "兜底必须用 previewResolvedOrNull（未命中保持原提示，不能用 previewResolved）",
            fnBody.contains("previewResolvedOrNull"),
        )
    }
}
