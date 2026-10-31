package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-TemplateHook S1/S5：[ExternalTemplateSettings] 纯逻辑用例（直接构造，不取平台服务）。
 *
 * 重点钉住「默认关闭时零行为变更」的判据（[ExternalTemplateSettings.isActive]）与读时钳位，
 * 这两条是接线守卫 `ExternalTemplateWiringTest` 的行为侧对照——源码守卫证明调用点在
 * `STATEMENT_TAGS` 爬升之前，本类证明门本身在 enabled=false 时恒不开。
 */
class ExternalTemplateSettingsTest {

    private fun settings() = ExternalTemplateSettings()

    @Test
    fun `出厂默认关闭且tag为sql`() {
        val s = settings()
        assertFalse("默认必须关闭（关闭时全链零行为变更）", s.enabled)
        assertFalse("未配置命令时 isActive 必须为 false", s.isActive())
        assertEquals("sql", s.statementTags)
        assertEquals(setOf("sql"), s.statementTagSet())
        assertEquals("标签名命中", true, s.isStatementTag("sql"))
        assertNull("工作目录留空 = 未配置", s.workingDirectoryOrNull())
    }

    @Test
    fun `enabled为false时即使配好命令也不激活`() {
        val s = settings()
        s.update(
            enabled = false,
            statementTags = "sql,query",
            commandTemplate = "python render.py {template} {sqlId}",
            timeoutSeconds = 10,
            workingDirectory = "",
        )
        assertFalse("总开关关闭时 isActive 恒 false（接线守卫的 enabled=false 语义）", s.isActive())
        assertTrue("tag 判定仍按配置生效（只用于错误提示，不触发渲染）", s.isStatementTag("query"))
    }

    @Test
    fun `enabled且命令非空才激活_空白命令视同未配置`() {
        val s = settings()
        s.update(true, "sql", "   ", 10, "")
        assertFalse("空白命令模板不得激活", s.isActive())
        s.update(true, "sql", "render {template}", 10, "")
        assertTrue(s.isActive())
    }

    @Test
    fun `tag集支持逗号与中文逗号_去空白大小写不敏感`() {
        val s = settings()
        s.update(true, "sql", "cmd", 10, "")
        s.update(true, " SQL , query ，PlanQry ,,", "cmd", 10, "")
        assertEquals(setOf("sql", "query", "planqry"), s.statementTagSet())
        assertTrue(s.isStatementTag(" sql "))
        assertFalse(s.isStatementTag("select"))
    }

    @Test
    fun `超时读时钳1到120`() {
        val s = settings()
        s.update(true, "sql", "cmd", 0, "")
        assertEquals("写时钳到下限", 1, s.timeoutSeconds)
        assertEquals(1, s.effectiveTimeoutSeconds())
        s.update(true, "sql", "cmd", 999, "")
        assertEquals("写时钳到上限", 120, s.timeoutSeconds)
        s.update(true, "sql", "cmd", 30, "")
        assertEquals(30, s.effectiveTimeoutSeconds())
    }

    @Test
    fun `loadState钳越界超时并原样读回其余字段`() {
        val s = settings()
        s.loadState(
            ExternalTemplateSettings.State(
                enabled = true,
                statementTags = "sql2",
                commandTemplate = "cmd {template}",
                timeoutSeconds = 500,
            ),
        )
        assertTrue("开关原样读回", s.enabled)
        assertEquals("命令原样读回", "cmd {template}", s.commandTemplate)
        assertEquals("超时读回时钳到上限", 120, s.timeoutSeconds)
        assertEquals("tags 原样读回", setOf("sql2"), s.statementTagSet())
    }

    @Test
    fun `工作目录不存在时回落null`() {
        val s = settings()
        s.update(true, "sql", "cmd", 10, "/definitely/not/a/dir/mpvs")
        assertNull("目录不存在时返回 null（调用方回落项目根）", s.workingDirectoryOrNull())
        s.update(true, "sql", "cmd", 10, System.getProperty("java.io.tmpdir"))
        assertTrue("已存在目录原样返回", s.workingDirectoryOrNull() != null)
    }
}
