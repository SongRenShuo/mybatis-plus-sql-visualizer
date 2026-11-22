package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import javax.swing.JTextField

/**
 * 面板行为测试：实例化真实 SqlPreviewPanel，模拟 JPA 预览的参数交互。
 *
 * 验证：
 *  - JPQL 预览：输入参数后 SQL 实时更新
 *  - DELETE JPQL：全表删除警告显示
 */
class SqlPreviewPanelJpaBehaviorTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun tearDown() {
        // 释放面板编辑器，避免测试框架检测到未释放的编辑器
        panel?.clear()
        panel = null
        super.tearDown()
    }

    /** JPQL 预览：输入参数后 SQL 应实时更新（? → 输入值） */
    fun testPanelJpqlParamInputUpdatesSqlInRealTime() {
        val result = PreviewResult(
            sql = "SELECT id, username FROM user WHERE username = ?",
            compactSql = "SELECT id, username FROM user WHERE username = ?",
            unresolvedParams = listOf("username"),
            paramTable = "-- 参数对照: [username=?]",
            wrapperType = null,
            tableName = "user",
            columns = listOf("id", "username"),
        )
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)

        p.showResult(result)

        // 验证面板已初始化
        assertNotNull("面板应已初始化", p.currentForTest())
        assertEquals("user", p.currentForTest()!!.tableName)

        // 找到参数输入框
        val paramField = p.paramFieldForTest(0)
        assertNotNull("应找到参数输入框", paramField)
        assertTrue("参数输入框应为 JTextField", paramField is JTextField)

        // 模拟用户输入参数值
        paramField!!.text = "admin"

        // 验证参数值已更新
        assertEquals("admin", p.paramValuesForTest()["username"])

        // 验证 SQL 编辑器中 ? 已被替换为 'admin'
        val sqlText = p.editorForTest()?.document?.text
        assertNotNull("SQL 编辑器应有内容", sqlText)
        assertTrue("SQL 应包含输入值 'admin': $sqlText", sqlText!!.contains("admin"))
        assertFalse("SQL 不应再含 ?: $sqlText", sqlText.contains("?"))
    }

    /** DELETE JPQL 预览：应显示全表删除警告 */
    fun testPanelDeleteJpqlShowsFullTableWarning() {
        val result = PreviewResult(
            sql = "DELETE FROM user",
            compactSql = "DELETE FROM user",
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = null,
            tableName = "user",
            warning = "WARNING: 无 WHERE 条件，将删除全表数据！",
            columns = emptyList(),
        )
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)

        p.showResult(result)

        // T-PreviewInfoLayout D-2：警告正文从 meta 可见行移到横幅（与 meta 里那份重复），
        // 断言随之改为「meta 不含正文、警告仍在横幅与 currentWarning」——契约有意变更，非回归。
        val metaText = p.metaLabelForTest()
        assertFalse("meta 行不应再承载警告正文: $metaText", metaText.contains("WARNING"))
        val warning = p.currentWarning.orEmpty()
        assertTrue("警告仍须记录在 currentWarning: $warning", warning.contains("WARNING") || warning.contains("全表"))
        assertEquals(
            "警告应由横幅承载",
            DegradationBanner.Kind.RENDER_WARNING,
            p.degradationBannerForTest().kindForTest(),
        )
    }

    /** 面板初始化时应正确显示 JPQL 预览结果 */
    fun testPanelInitializesWithJpqlPreviewResult() {
        val result = PreviewResult(
            sql = "SELECT id, username, email FROM user WHERE status = ?",
            compactSql = "SELECT id, username, email FROM user WHERE status = ?",
            unresolvedParams = listOf("status"),
            paramTable = "-- 参数对照: [status=?]",
            wrapperType = null,
            tableName = "user",
            columns = listOf("id", "username", "email"),
        )
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)

        p.showResult(result)

        // 验证当前结果
        val current = p.currentForTest()
        assertNotNull("面板应有当前结果", current)
        assertEquals("user", current!!.tableName)
        assertEquals(listOf("status"), current.unresolvedParams)

        // 验证参数输入框存在
        val paramField = p.paramFieldForTest(0)
        assertNotNull("应有参数输入框", paramField)

        // 验证 SQL 编辑器内容
        val sqlText = p.editorForTest()?.document?.text
        assertNotNull("SQL 编辑器应有内容", sqlText)
        assertTrue("SQL 应含 SELECT: $sqlText", sqlText!!.contains("SELECT"))
        assertTrue("SQL 应含 WHERE: $sqlText", sqlText.contains("WHERE"))
    }

    /** 面板参数输入框初始值应为空 */
    fun testPanelParamFieldsStartEmpty() {
        val result = PreviewResult(
            sql = "SELECT id FROM user WHERE age > ? AND status = ?",
            compactSql = "SELECT id FROM user WHERE age > ? AND status = ?",
            unresolvedParams = listOf("minAge", "status"),
            paramTable = "-- 参数对照: [minAge=?, status=?]",
            wrapperType = null,
            tableName = "user",
            columns = listOf("id"),
        )
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)

        p.showResult(result)

        // 验证有两个参数输入框
        val field0 = p.paramFieldForTest(0)
        val field1 = p.paramFieldForTest(1)
        assertNotNull("应有第一个参数输入框", field0)
        assertNotNull("应有第二个参数输入框", field1)

        // 初始值应为空
        assertEquals("第一个参数初始值应为空", "", field0!!.text)
        assertEquals("第二个参数初始值应为空", "", field1!!.text)
    }
}
