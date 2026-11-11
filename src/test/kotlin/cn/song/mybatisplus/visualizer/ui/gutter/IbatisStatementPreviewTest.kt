package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-IbatisCallPreview：iBatis 预览共用装配（[IbatisStatementPreview] / [IbatisCallTargets]）用例。
 *
 * 覆盖三条入口（gutter 图标 / Alt+Shift+Q / MCP）共用的取数链与形状门：
 * 1. 调用点定位（入口方法名 + 首参字面量 + `id`/`ns.id` 形态，与 JdbcTemplate 让位同一函数）；
 * 2. 语句标签 → 预览产物（多语句按 id 精确定位、无 id 按 ordinal、跨文件 namespace）；
 * 3. 目标缺失 → 可见失败（空 SQL + 「语句未找到」告警），不是 null。
 */
class IbatisStatementPreviewTest : BasePlatformTestCase() {

    private val sqlMap = """
        <sqlMap namespace="User">
          <select id="selectById">SELECT ID, USER_NAME FROM T_USER WHERE ID = #id#</select>
          <update id="updateUser">UPDATE T_USER SET USER_NAME = #username# WHERE ID = #id#</update>
          <select id="byName">SELECT ID FROM T_USER WHERE USER_NAME = #name#</select>
        </sqlMap>
    """.trimIndent()

    private val dao = """
        package com.demo;
        public class UserDaoImpl {
            private Object client;
            private Object jdbcTemplate;
            public Object selectById(int id) { return client.queryForObject("User.selectById", id); }
            public int updateUser(Object u) { return client.update("User.updateUser", u); }
            public Object twoCalls(int id) {
                client.queryForObject("User.selectById", id);
                return client.queryForObject("User.byName", "x");
            }
            public Object missing(int id) { return client.queryForObject("User.noSuch", id); }
            public Object nonEntry() { return client.selectOne("x", "User.selectById"); }
            public Object secondArg(String p) { return client.queryForObject(p, "User.selectById"); }
            public Object jdbcCall() { return jdbcTemplate.queryForList("SELECT ID FROM T_USER WHERE ID = ?"); }
        }
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        IbatisXmlStatementTargets.clearCachesForTest()
    }

    override fun tearDown() {
        try {
            IbatisXmlStatementTargets.clearCachesForTest()
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun addSqlMap(namespace: String = "User"): PsiFile =
        myFixture.addFileToProject("sqlmap/User.xml", sqlMap.replace("namespace=\"User\"", "namespace=\"$namespace\""))

    private fun addDao(): PsiFile = myFixture.addFileToProject("com/demo/UserDaoImpl.java", dao)

    private fun literal(file: PsiFile, value: String): PsiLiteralExpression =
        PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java).first { it.value == value }

    private fun call(file: PsiFile, methodName: String, firstArg: String): PsiMethodCallExpression =
        PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java).first {
            it.methodExpression.referenceName == methodName &&
                it.argumentList.expressions.firstOrNull()?.text == "\"$firstArg\""
        }

    private fun callByMethod(file: PsiFile, methodName: String): PsiMethodCallExpression =
        PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == methodName }

    private fun statementTag(file: PsiFile, id: String): XmlTag =
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).first { it.getAttributeValue("id") == id }

    private fun method(file: PsiFile, name: String): PsiMethod =
        PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == name }

    // ------------------------------------------------------------------
    // 1. 调用点定位（形状门与 JdbcTemplate 让位同一函数）
    // ------------------------------------------------------------------

    fun test调用行定位出statementId与所属类() {
        addSqlMap()
        val javaFile = addDao()
        val target = IbatisCallTargets.resolve(literal(javaFile, "User.selectById"))
        assertNotNull("iBatis 调用行应命中形状门", target)
        assertEquals("User.selectById", target!!.statementId)
        assertEquals("com.demo.UserDaoImpl", target.ownerNamespace)
    }

    fun test形状门负例_SQL文本_非入口方法_首参非字面量() {
        addSqlMap()
        val javaFile = addDao()
        // 首参是 SQL 文本（真正 JDBC 调用）——形状门不得认领
        assertNull(IbatisCallTargets.resolve(literal(javaFile, "SELECT ID FROM T_USER WHERE ID = ?")))
        // 非 SqlMapExecutor 入口方法（`selectOne`）：第二参是 id 形态字面量也不认领
        val nonEntry = callByMethod(javaFile, "selectOne")
        assertNull(IbatisCallTargets.resolve(nonEntry.argumentList.expressions[1]))
        // 入口方法但首参是变量（不是字面量）——第二参的 id 形态字面量同样不认领
        val secondArgCall = PsiTreeUtil.findChildrenOfType(javaFile, PsiMethodCallExpression::class.java)
            .first {
                it.methodExpression.referenceName == "queryForObject" &&
                    it.argumentList.expressions.firstOrNull()?.text == "p"
            }
        assertNull(IbatisCallTargets.resolve(secondArgCall.argumentList.expressions[1]))
    }

    fun test方法体候选按offset序() {
        addSqlMap()
        val javaFile = addDao()
        val targets = IbatisCallTargets.callsInMethod(method(javaFile, "twoCalls"))
        assertEquals(listOf("User.selectById", "User.byName"), targets.map { it.statementId })
    }

    fun test方法体无候选_普通JDBC方法() {
        addSqlMap()
        val javaFile = addDao()
        assertEquals(emptyList<IbatisCallTarget>(), IbatisCallTargets.callsInMethod(method(javaFile, "jdbcCall")))
    }

    /**
     * 回归锚：`return` 关键字不是调用表达式的祖先（是兄弟位），光标落在它上面时
     * 必须回落到「包含调用的语句」里找——MCP/快捷键的「文件+行号」定位默认锚就是行首关键字。
     */
    fun test光标落在return关键字_仍定位到调用() {
        addSqlMap()
        val javaFile = addDao()
        val offset = javaFile.text.indexOf("return client.queryForObject(\"User.selectById\"")
        assertTrue("夹具前提：能找到 selectById 的 return 行", offset > 0)
        val token = javaFile.findElementAt(offset) ?: error("return 关键字 token 未找到")
        val target = IbatisCallTargets.resolve(token)
        assertNotNull("return 关键字应回落到语句内的 iBatis 调用", target)
        assertEquals("User.selectById", target!!.statementId)
    }

    // ------------------------------------------------------------------
    // 2. 语句标签 → 预览产物
    // ------------------------------------------------------------------

    fun testpreviewTag按id精确定位并渲染参数() {
        val file = addSqlMap()
        val result = IbatisStatementPreview.previewTag(statementTag(file, "byName"))
        assertTrue("应渲染 byName：${result.sql}", result.sql.contains("USER_NAME = ?"))
        assertEquals(listOf("name"), result.unresolvedParams)
        assertNotNull("iBatis 参数模型必须随结果进面板", result.ibatisParamModel)
    }

    fun test无id语句按同标签名ordinal定位() {
        val file = myFixture.addFileToProject(
            "sqlmap/NoId.xml",
            """
            <sqlMap namespace="N">
              <select>SELECT 'FIRST' FROM T</select>
              <select>SELECT 'SECOND' FROM T</select>
            </sqlMap>
            """.trimIndent(),
        )
        val second = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name == "select" }[1]
        val result = IbatisStatementPreview.previewTag(second)
        assertTrue("ordinal 定位应取第二条：${result.sql}", result.sql.contains("SECOND"))
    }

    fun testpreviewResolved跨文件按namespace解析() {
        addDao()
        myFixture.addFileToProject(
            "sqlmap/Common.xml",
            """<sqlMap namespace="common"><select id="selectX">SELECT 1 FROM COMMON_T</select></sqlMap>""",
        )
        val result = IbatisStatementPreview.previewResolved(project, "common.selectX", null)
        assertTrue("跨文件解析应取 Common.xml 的语句：${result.sql}", result.sql.contains("COMMON_T"))
    }

    fun testpreviewResolved目标不存在返回可见失败() {
        addSqlMap()
        val result = IbatisStatementPreview.previewResolved(project, "User.noSuch", null)
        assertEquals("目标缺失不得产出半截 SQL", "", result.sql.trim())
        assertNotNull("目标缺失必须给可见告警", result.warning)
        assertTrue(
            "告警应是「语句未找到」档：${result.warning}",
            result.warning!!.contains("定位") || result.warning!!.contains("Could not locate"),
        )
    }
}
