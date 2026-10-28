package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.CheckResult
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.InstallResult
import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.update.UpdateManifest
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * 设置页更新分区测试（T-M28-03）：
 *  - 初始态（版本行/开关默认开/安装按钮隐藏）
 *  - 状态机渲染（Available/UpToDate/检查失败/安装完成/安装失败——直接调用 render* 驱动，
 *    不触达网络与平台安装；异步任务由 runIde 沙盒端到端验证）
 */
class UpdateSectionTest : BasePlatformTestCase() {

    private lateinit var section: UpdateSection

    /** 窗口宿主（T-M28.1 交互测试）：异步回调按 `isDisplayable` 判活——无宿主组件
     *  displayable=false 会跳过渲染（真实设置页在对话框内无此问题），测试须放入可见窗口。 */
    private lateinit var host: javax.swing.JFrame

    override fun setUp() {
        super.setUp()
        section = UpdateSection(PluginSettings.getInstance())
        host = javax.swing.JFrame()
        host.contentPane.add(section)
        host.pack()
        host.isVisible = true
    }

    override fun tearDown() {
        host.dispose()
        super.tearDown()
    }

    fun testInitialState() {
        // 版本行展示当前插件版本（读自身 META-INF/plugin.xml）
        val expected = MpSqlVisualizerBundle.message("settings.update.currentVersion") +
            ": " + (PluginVersion.current() ?: "—")
        assertEquals(expected, section.versionLabel.text)
        // 抢先体验默认关（稳定版由 Marketplace 推送）
        assertFalse(section.autoCheckCheck.isSelected)
        assertEquals(UpdateSection.State.Idle, section.state)
        // 未检查前：安装按钮隐藏、重启按钮隐藏、状态空白
        assertFalse(section.installButton.isVisible)
        assertFalse(section.restartButton.isVisible)
        assertTrue(section.checkButton.isEnabled)
    }

    fun testRenderAvailable() {
        val manifest = UpdateManifest("2026.8.15", "https://example.com/p.zip", "line1\nline2", null, null)
        section.renderCheck(CheckResult.Available(manifest))

        assertEquals(UpdateSection.State.Available, section.state)
        assertTrue("发现新版后安装按钮应可见", section.installButton.isVisible)
        assertTrue(section.installButton.isEnabled)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.available", "2026.8.15"),
            section.statusLabel.text,
        )
        // 变更日志文本域展示 changeNotes
        assertTrue(section.notesArea.text.contains("line1"))
        assertTrue(section.notesArea.text.contains("line2"))
    }

    fun testRenderUpToDate() {
        section.renderCheck(CheckResult.UpToDate)
        assertEquals(UpdateSection.State.UpToDate, section.state)
        assertEquals(MpSqlVisualizerBundle.message("settings.update.upToDate"), section.statusLabel.text)
        assertFalse(section.installButton.isVisible)
    }

    fun testRenderCheckFailure() {
        section.renderCheck(
            CheckResult.Failure(cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.FailureKind.NETWORK, "network unavailable"),
        )
        assertEquals(UpdateSection.State.Failed, section.state)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.fail.network", "network unavailable"),
            section.statusLabel.text,
        )
        // 失败后可重试
        assertTrue(section.checkButton.isEnabled)
        assertFalse(section.installButton.isVisible)
    }

    // ---------- R62（T-LaneB，V121.3）：三态渲染——404 态给 Marketplace+Gitee 兜底链接 ----------

    fun testRenderChannelMissing_404态_兜底链接可见() {
        section.renderCheck(
            CheckResult.Failure(cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.FailureKind.CHANNEL_MISSING, "HTTP 404"),
        )
        assertEquals(UpdateSection.State.Failed, section.state)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.fail.channel404", "HTTP 404"),
            section.statusLabel.text,
        )
        assertTrue("404 态兜底链接行应可见", section.fallbackLinksContainer.isVisible)
        assertTrue(section.checkButton.isEnabled)
    }

    fun testRenderNetworkFailure_兜底链接不可见() {
        section.renderCheck(
            CheckResult.Failure(cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.FailureKind.NETWORK, "connect timed out"),
        )
        assertEquals(UpdateSection.State.Failed, section.state)
        assertTrue(section.statusLabel.text.isNotEmpty())
        assertFalse("网络态不给兜底链接", section.fallbackLinksContainer.isVisible)
    }

    fun testRenderUpToDate_三态之已最新() {
        section.renderCheck(CheckResult.UpToDate)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.upToDate"),
            section.statusLabel.text,
        )
        assertFalse(section.fallbackLinksContainer.isVisible)
    }

    fun testRenderInstalled() {
        val manifest = UpdateManifest("2026.8.15", "https://example.com/p.zip", null, null, null)
        section.renderCheck(CheckResult.Available(manifest))
        section.renderInstall(InstallResult.InstalledRestartRequired)

        assertEquals(UpdateSection.State.Installed, section.state)
        assertEquals(MpSqlVisualizerBundle.message("settings.update.installed"), section.statusLabel.text)
        // 重启提示与重启按钮可见；安装/检查按钮不可再操作
        assertTrue(section.restartButton.isVisible)
        assertFalse(section.installButton.isVisible)
        assertFalse(section.checkButton.isEnabled)
    }

    fun testRenderInstalledLive() {
        // T-DynUpdate：动态热替换成功 → InstalledLive 态——免重启文案，无重启按钮
        val manifest = UpdateManifest("2026.8.15", "https://example.com/p.zip", null, null, null)
        section.renderCheck(CheckResult.Available(manifest))
        section.renderInstall(InstallResult.InstalledDynamic("2026.9.11-beta.1"))

        assertEquals(UpdateSection.State.InstalledLive, section.state)
        assertEquals(MpSqlVisualizerBundle.message("settings.update.installedLive"), section.statusLabel.text)
        assertFalse(section.restartButton.isVisible)
        assertFalse(section.installButton.isVisible)
        assertFalse(section.checkButton.isEnabled)
    }

    fun testRenderInstallFailed() {
        val manifest = UpdateManifest("2026.8.15", "https://example.com/p.zip", null, null, null)
        section.renderCheck(CheckResult.Available(manifest))
        section.renderInstall(InstallResult.Failed("install rejected"))

        assertEquals(UpdateSection.State.Failed, section.state)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.installFailed", "install rejected"),
            section.statusLabel.text,
        )
        assertTrue(section.checkButton.isEnabled)
    }

    fun testAutoCheckFromState() {
        PluginSettings.getInstance().state.autoCheckUpdate = false
        section.resetState()
        assertFalse("开关应跟随配置（取消勾选）", section.autoCheckCheck.isSelected)

        PluginSettings.getInstance().state.autoCheckUpdate = true
        section.resetState()
        assertTrue(section.autoCheckCheck.isSelected)
    }

    fun testLanguageSwitchRefreshesTexts() {
        // updateTexts 由 rebuild 调用：切换语言后按钮文案随 bundle 刷新（同 M19.2 复选框修复模式）
        section.updateTexts()
        assertEquals(MpSqlVisualizerBundle.message("settings.update.check"), section.checkButton.text)
        assertEquals(MpSqlVisualizerBundle.message("settings.update.restartNow"), section.restartButton.text)
    }

    // ---------------- T-M28.1 按钮交互（此前缺失绑定——用户实测点击无反应；盲区补测） ----------------

    fun testButtonsHaveActionListeners() {
        assertTrue("检查更新按钮应绑定事件", section.checkButton.actionListeners.isNotEmpty())
        assertTrue("下载并安装按钮应绑定事件", section.installButton.actionListeners.isNotEmpty())
        assertTrue("重启按钮应绑定事件", section.restartButton.actionListeners.isNotEmpty())
    }

    fun testCheckButtonClickShowsCheckingThenResult() {
        var calls = 0
        section.checkRunner = { calls++; CheckResult.UpToDate }
        section.checkButton.doClick()
        // 瞬态观测同 testInstallButtonClick 的调度竞态（T-CiRelease 20261024 实证：EDT 可能抢在
        // 断言前派发完成回调）：Checking/UpToDate 均为「点击已进入检查流程」的合法观测，
        // 对应文案两可；流程正确性由下方 calls==1 + waitForState(UpToDate) 严格把关。
        if (section.state == UpdateSection.State.Checking) {
            assertEquals(MpSqlVisualizerBundle.message("settings.update.checking"), section.statusLabel.text)
        } else {
            assertEquals(UpdateSection.State.UpToDate, section.state)
        }
        waitForState(UpdateSection.State.UpToDate)
        assertEquals(1, calls)
        assertEquals(MpSqlVisualizerBundle.message("settings.update.upToDate"), section.statusLabel.text)
    }

    fun testInstallButtonClickDownloadsAndInstalls() {
        var calls = 0
        section.installRunner = { _, _ -> calls++; InstallResult.InstalledRestartRequired }
        section.renderCheck(CheckResult.Available(UpdateManifest("2026.8.15", "https://example.com/p.zip", null, null, null)))
        section.installButton.doClick()
        // 瞬态观测存在调度竞态：EDT 线程可能在本断言前已派发 invokeLater 的 renderInstall
        //（CI 20261024 实测偶发，本机测试线程几乎总赢）。Downloading/Installed 均为「点击已
        // 进入安装流程」的合法观测；流程正确性由下方 calls==1 + waitForState(Installed) 严格把关。
        assertTrue(
            "点击后应立即进入安装流程（Downloading 或已完成 Installed），实际 ${section.state}",
            section.state == UpdateSection.State.Downloading || section.state == UpdateSection.State.Installed,
        )
        waitForState(UpdateSection.State.Installed)
        assertEquals(1, calls)
        assertTrue("安装完成应显示重启按钮", section.restartButton.isVisible)
    }

    fun testRestartButtonClickInvokesRestartAction() {
        var restarts = 0
        section.restartAction = { restarts++ }
        section.renderCheck(CheckResult.Available(UpdateManifest("1.0", "https://x/p.zip", null, null, null)))
        section.renderInstall(InstallResult.InstalledRestartRequired)
        section.restartButton.doClick()
        assertEquals(1, restarts)
    }

    fun testGiteeLinkPresent() {
        // T-M28.4：变更日志关联 Gitee——链接存在且文案走 bundle
        assertEquals(MpSqlVisualizerBundle.message("settings.update.viewOnGitee"), section.giteeLink.text)
    }

    // ------------------------------------------------------------------
    // T-SelfUpdateRollback（O-150）W2：回滚入口
    // ------------------------------------------------------------------

    fun test回滚按钮态_备份可用启用_不可用禁用() {
        section.rollbackAvailability = {
            cn.song.mybatisplus.visualizer.update.UpdateBackupStore.BackupInfo(
                fromVersion = "2026.10.1",
                sha256 = "ab",
                createdAt = 1L,
                pluginDirName = "mybatis-plus-sql-visualizer",
            )
        }
        section.refreshRollbackAvailability()
        assertTrue("备份在场按钮应可用", section.rollbackButton.isEnabled)
        section.rollbackAvailability = { null }
        section.refreshRollbackAvailability()
        assertFalse("无备份按钮必须禁用（诚实降级，绝不假装可点）", section.rollbackButton.isEnabled)
    }

    /** UI-002：备份在会话中途出现后，重开设置页（resetState）须重算可用性，不再滞后灰置。 */
    fun test回滚按钮态_reset时重算可用性() {
        section.rollbackAvailability = { null }
        section.resetState()
        assertFalse("无备份 reset 后仍禁用", section.rollbackButton.isEnabled)
        section.rollbackAvailability = {
            cn.song.mybatisplus.visualizer.update.UpdateBackupStore.BackupInfo("2026.10.1", "ab", 1L, "d")
        }
        section.resetState()
        assertTrue("备份中途出现后 reset 须启用按钮（UI-002）", section.rollbackButton.isEnabled)
    }

    fun test回滚流程_确认通过_调度成功渲染重启提示() {
        section.rollbackAvailability = {
            cn.song.mybatisplus.visualizer.update.UpdateBackupStore.BackupInfo("2026.10.1", "ab", 1L, "d")
        }
        section.refreshRollbackAvailability()
        var confirmed = 0
        section.rollbackConfirm = { confirmed++; true }
        var rolled = 0
        section.rollbackRunner = {
            rolled++
            cn.song.mybatisplus.visualizer.update.UpdateBackupStore.RestoreResult.Scheduled("9.9.9")
        }
        section.rollbackButton.doClick()
        assertEquals(1, confirmed)
        assertEquals(1, rolled)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.rollback.scheduled", "9.9.9"),
            section.rollbackStatusLabel.text,
        )
        assertFalse("调度后按钮禁用防重复调度", section.rollbackButton.isEnabled)
    }

    fun test回滚流程_确认拒绝_不调度() {
        section.rollbackAvailability = {
            cn.song.mybatisplus.visualizer.update.UpdateBackupStore.BackupInfo("2026.10.1", "ab", 1L, "d")
        }
        section.refreshRollbackAvailability()
        section.rollbackConfirm = { false }
        var rolled = 0
        section.rollbackRunner = { rolled++; cn.song.mybatisplus.visualizer.update.UpdateBackupStore.RestoreResult.Scheduled("x") }
        section.rollbackButton.doClick()
        assertEquals("用户取消则零调度", 0, rolled)
    }

    fun test回滚流程_无备份结果_状态行如实披露() {
        section.rollbackAvailability = {
            cn.song.mybatisplus.visualizer.update.UpdateBackupStore.BackupInfo("2026.10.1", "ab", 1L, "d")
        }
        section.refreshRollbackAvailability()
        section.rollbackConfirm = { true }
        section.rollbackRunner = { cn.song.mybatisplus.visualizer.update.UpdateBackupStore.RestoreResult.NoBackup("gone") }
        section.rollbackButton.doClick()
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.rollback.noBackup"),
            section.rollbackStatusLabel.text,
        )
    }

    fun test禁用态点击_不弹确认不调度() {
        section.rollbackAvailability = { null }
        section.refreshRollbackAvailability()
        var confirmed = 0
        section.rollbackConfirm = { confirmed++; true }
        section.rollbackButton.doClick()
        assertEquals("禁用态必须短路", 0, confirmed)
    }

    /** 轮询等待状态（EDT 派发 + 短轮询；fake seam 立即完成，正常一轮即达）。 */
    private fun waitForState(target: UpdateSection.State) {
        val deadline = System.currentTimeMillis() + 10_000
        while (section.state != target && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        assertEquals(target, section.state)
    }
}
