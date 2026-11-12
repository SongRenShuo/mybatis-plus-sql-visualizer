package cn.song.mybatisplus.visualizer.update

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动自动检查决策单测（T-M28-05）：开关联动 + 单次守卫（纯函数；
 * 通知 UI 与真实检查链路由 runIde 沙盒端到端验证）。
 *
 * T-M28.3/28.6 追加：通知内容纯函数（版本号行 + 变更日志有序列表）。
 * 内测通道改造追加：忽略版本守卫 + 忽略操作纯函数测试。
 */
class StartupUpdateCheckTest {

    @Test
    fun checkEnabledAndNotCheckedRuns() {
        assertTrue(StartupUpdateCheck.shouldStartupCheck(autoCheck = true, alreadyChecked = false))
    }

    @Test
    fun checkDisabledSkips() {
        assertFalse(StartupUpdateCheck.shouldStartupCheck(autoCheck = false, alreadyChecked = false))
        assertFalse(StartupUpdateCheck.shouldStartupCheck(autoCheck = false, alreadyChecked = true))
    }

    @Test
    fun alreadyCheckedSkips() {
        assertFalse(StartupUpdateCheck.shouldStartupCheck(autoCheck = true, alreadyChecked = true))
    }

    // ---------------- T-LocalUpdateDir：启动本地检测决策（纯函数） ----------------

    @Test
    fun localCheckRunsWhenDirConfiguredAndNotChecked() {
        assertTrue(StartupUpdateCheck.shouldStartupCheckLocal("C:\\pkgs", alreadyCheckedLocal = false))
        assertTrue(StartupUpdateCheck.shouldStartupCheckLocal("/srv/plugins-pkgs", alreadyCheckedLocal = false))
    }

    @Test
    fun localCheckSkipsWhenDirBlank() {
        assertFalse(StartupUpdateCheck.shouldStartupCheckLocal("", alreadyCheckedLocal = false))
        assertFalse(StartupUpdateCheck.shouldStartupCheckLocal("   ", alreadyCheckedLocal = false))
    }

    @Test
    fun localCheckSkipsWhenAlreadyChecked() {
        assertFalse(StartupUpdateCheck.shouldStartupCheckLocal("C:\\pkgs", alreadyCheckedLocal = true))
    }

    // ---------------- 通知内容（版本号行 + 变更日志有序列表） ----------------

    @Test
    fun notificationContentShowsVersionLineThenChangeNotes() {
        // 第一行：版本号行；随后：变更日志有序列表
        val manifest = UpdateManifest(
            "2026.8.22-beta.1",
            "https://example.com/p.zip",
            "v2026.8.22：JPA 预览支持\nSQL 日志捕获\n",
            null, null,
        )
        val content = StartupUpdateCheck.notificationContent(manifest)
        val expectedVersionLine = MpSqlVisualizerBundle.message("update.notification.versionLine", "2026.8.22-beta.1")
        assertTrue("应以版本号行开头", content.startsWith(expectedVersionLine))
        assertTrue("版本号行后应跟 <br>", content.contains("$expectedVersionLine<br>"))
        assertTrue("应渲染有序列表", content.contains("<ol><li>JPA 预览支持</li><li>SQL 日志捕获</li>"))
        assertTrue("列表应以 </ol> 结尾", content.contains("</ol>"))
    }

    @Test
    fun notificationContentWithNoChangeNotesShowsVersionLineWithoutLink() {
        // 无变更日志时：只显示版本号行（无空 <ol>）；T-DynUpdate：正文不再内嵌 Gitee 链接——
        // Gitee 入口收敛为动作行「查看 Gitee 发布页」按钮，正文内嵌链接与动作行重复（用户实测）
        val manifest = UpdateManifest("2026.8.22", "https://example.com/p.zip", null, null, null)
        val content = StartupUpdateCheck.notificationContent(manifest)
        val expectedVersionLine = MpSqlVisualizerBundle.message("update.notification.versionLine", "2026.8.22")
        assertTrue("应以版本号行开头", content.startsWith(expectedVersionLine))
        assertFalse("无变更日志时不应渲染空列表", content.contains("<ol>"))
        assertFalse("正文不应再内嵌 Gitee 链接（动作行已有）", content.contains("href="))
    }

    @Test
    fun notificationContentEscapesHtml() {
        val manifest = UpdateManifest("1.0", "https://x/p.zip", "v1：<if test=\"x & y\">", null, null)
        val content = StartupUpdateCheck.notificationContent(manifest)
        assertTrue("应 HTML 转义", content.contains("&lt;if test=\"x &amp; y\"&gt;"))
    }

    @Test
    fun stripVersionPrefixVariants() {
        assertEquals("功能A", StartupUpdateCheck.stripVersionPrefix("v2026.8.15：功能A"))
        assertEquals("功能A", StartupUpdateCheck.stripVersionPrefix("2026.8.15: 功能A"))
        assertEquals("功能A", StartupUpdateCheck.stripVersionPrefix("功能A"))
        assertEquals("", StartupUpdateCheck.stripVersionPrefix("v2026.8.15："))
    }

    // ---------------- 忽略版本（纯函数全分支覆盖见 VersionIgnoreTest） ----------------
}
