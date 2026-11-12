package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.update.DevUpdateMode
import cn.song.mybatisplus.visualizer.update.LocalPackage
import cn.song.mybatisplus.visualizer.update.LocalUpdateDir
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.CheckResult
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner.InstallResult
import cn.song.mybatisplus.visualizer.update.PluginVersion
import cn.song.mybatisplus.visualizer.update.ScanResult
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
        // T-DevUpdateChannel：解锁态是进程级内存态，跨用例会串味——每个用例从「未解锁」起跑
        cn.song.mybatisplus.visualizer.update.DevUpdateMode.resetForTest()
        section = UpdateSection(PluginSettings.getInstance())
        host = javax.swing.JFrame()
        host.contentPane.add(section)
        host.pack()
        host.isVisible = true
    }

    override fun tearDown() {
        DevUpdateMode.resetForTest()
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
    // T-DevUpdateChannel：开发版通道（解锁可见性 / URL 分流 / 渲染保持 / 404 友好化）
    // ------------------------------------------------------------------

    fun testPreviewButtonHiddenByDefault() {
        assertFalse("默认（未解锁）不得露出开发版按钮", section.checkPreviewButton.isVisible)
    }

    fun testPreviewButtonVisibleAfterUnlock() {
        DevUpdateMode.unlock()
        section.updateTexts()
        assertTrue("解锁后应出现开发版按钮", section.checkPreviewButton.isVisible)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.checkPreview"),
            section.checkPreviewButton.text,
        )
    }

    fun testPreviewButtonVisibilitySurvivesUpdateTexts() {
        // P-3 的钉子：语言切换 rebuild 只重建文本，可见性必须按解锁态重算。
        // 模拟 rebuild 的方式：把可见性打回构造默认（false）后再调 updateTexts。
        DevUpdateMode.unlock()
        section.updateTexts()
        assertTrue(section.checkPreviewButton.isVisible)

        section.checkPreviewButton.isVisible = false
        section.updateTexts()
        assertTrue("updateTexts 丢失可见性恢复 → 切语言后按钮消失", section.checkPreviewButton.isVisible)
    }

    fun testPreviewButtonReadsPreviewManifestUrl() {
        DevUpdateMode.unlock()
        section.updateTexts()
        var seen: String? = null
        section.checkRunner = { url -> seen = url; CheckResult.UpToDate }
        section.checkPreviewButton.doClick()
        waitForState(UpdateSection.State.UpToDate)
        assertTrue("开发版按钮必须读预览清单，实际=$seen", seen!!.contains("preview.update.json"))
        assertEquals(UpdateSection.Channel.PREVIEW, section.channel)
        // 状态行标出通道，避免与正式通道读数混淆
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.upToDate") +
                MpSqlVisualizerBundle.message("settings.update.channel.preview"),
            section.statusLabel.text,
        )
    }

    fun testStableButtonStillReadsStableManifestUrl() {
        // 回归：正式按钮一字不变（分流不得污染正式通道）
        var seen: String? = null
        section.checkRunner = { url -> seen = url; CheckResult.UpToDate }
        section.checkButton.doClick()
        waitForState(UpdateSection.State.UpToDate)
        assertTrue("正式按钮应读 update/update.json，实际=$seen", seen!!.endsWith("update/update.json"))
        assertFalse(seen!!.contains("preview.update.json"))
        assertEquals(UpdateSection.Channel.STABLE, section.channel)
        assertEquals(MpSqlVisualizerBundle.message("settings.update.upToDate"), section.statusLabel.text)
    }

    fun testInstallNotPublishedShowsFriendlyTextAndLinks() {
        // 404 友好化：不渲染异常原文，给「还没发布」+ 发布页链接
        val manifest = UpdateManifest("2026.11.6-beta.3", "https://example.com/missing.zip", null, null, null)
        section.renderCheck(CheckResult.Available(manifest))
        section.renderInstall(InstallResult.NotPublished(404))

        assertEquals(UpdateSection.State.Failed, section.state)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.notPublished"),
            section.statusLabel.text,
        )
        assertFalse(
            "状态行不得出现异常/状态码原文：${section.statusLabel.text}",
            section.statusLabel.text.contains("HttpStatusException") ||
                section.statusLabel.text.contains("Not Found for url"),
        )
        assertFalse(
            "不得渲染通用「更新失败」文案",
            section.statusLabel.text.startsWith(MpSqlVisualizerBundle.message("settings.update.installFailed", "").trimEnd()),
        )
        assertTrue("404 态应给发布页兜底链接", section.fallbackLinksContainer.isVisible)
        // 重试路径仍可用
        assertTrue(section.checkButton.isEnabled)
    }

    fun testInstallFailed500StillShowsFailure() {
        // 反扩散：500/IO 等其余失败仍是 Failed（普通「更新失败」文案、不显示兜底链接）
        val manifest = UpdateManifest("2026.11.6-beta.3", "https://example.com/missing.zip", null, null, null)
        section.renderCheck(CheckResult.Available(manifest))
        section.renderInstall(InstallResult.Failed("HTTP 500"))

        assertEquals(UpdateSection.State.Failed, section.state)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.installFailed", "HTTP 500"),
            section.statusLabel.text,
        )
        assertFalse("普通安装失败不给兜底链接", section.fallbackLinksContainer.isVisible)
    }

    // ------------------------------------------------------------------
    // T-LocalUpdateDir：本地目录通道（渲染 / 分流 / 状态行 / 远端回归）
    // ------------------------------------------------------------------

    private fun localPkg(version: String = "2026.11.12"): LocalPackage =
        LocalPackage(version, java.nio.file.Path.of("unused.zip"), "unused.zip", 1, 1, null)

    fun testLocalCheckAvailableRendersAndArmsInstall() {
        section.localScanRunner = { ScanResult.Available(localPkg(), total = 1, skipped = 0) }
        section.localCheckButton.doClick()
        waitForState(UpdateSection.State.Available)
        assertEquals(UpdateSection.Channel.LOCAL, section.channel)
        assertEquals("2026.11.12", section.pendingLocalPackage?.version)
        // 状态行带「（本地目录）」后缀；日志区显示本地包详情（无 Gitee 链接）
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.available", "2026.11.12") +
                MpSqlVisualizerBundle.message("settings.update.channel.local"),
            section.statusLabel.text,
        )
        assertTrue(section.installButton.isVisible && section.installButton.isEnabled)
        assertFalse("本地通道不显示 Gitee 链接", section.giteeLink.isVisible)
    }

    fun testLocalInstallBranchGoesToLocalRunner() {
        var localCalls = 0
        var remoteCalls = 0
        section.localScanRunner = { ScanResult.Available(localPkg(), total = 1, skipped = 0) }
        section.localInstallRunner = { _, _ -> localCalls++; InstallResult.InstalledDynamic("2026.11.12") }
        section.installRunner = { _, _ -> remoteCalls++; InstallResult.InstalledRestartRequired }
        section.localCheckButton.doClick()
        waitForState(UpdateSection.State.Available)
        section.installButton.doClick()
        waitForState(UpdateSection.State.InstalledLive)
        assertEquals("本地通道必须走 localInstallRunner", 1, localCalls)
        assertEquals("本地通道不得走下载链", 0, remoteCalls)
        // InstalledLive 态：安装按钮隐藏（与远端热生效态同渲染）、重启按钮也不出现
        assertFalse(section.installButton.isVisible)
        assertFalse(section.restartButton.isVisible)
    }

    fun testLocalDirInvalidShowsReasonAndNoInstall() {
        section.localScanRunner = { ScanResult.DirInvalid(LocalUpdateDir.DirCheck.NotFound) }
        section.localCheckButton.doClick()
        waitForState(UpdateSection.State.Failed)
        assertEquals(
            MpSqlVisualizerBundle.message(
                "settings.update.checkFailed",
                MpSqlVisualizerBundle.message("settings.update.local.notFound"),
            ) + MpSqlVisualizerBundle.message("settings.update.channel.local"),
            section.statusLabel.text,
        )
        assertFalse(section.installButton.isVisible)
    }

    fun testLocalNoValidPackageShowsSkipCount() {
        section.localScanRunner = {
            ScanResult.NoValidPackage(java.nio.file.Path.of("d"), skipped = 2, skippedSamples = listOf("x"), truncated = false)
        }
        section.localCheckButton.doClick()
        waitForState(UpdateSection.State.Failed)
        assertEquals(
            MpSqlVisualizerBundle.message(
                "settings.update.checkFailed",
                MpSqlVisualizerBundle.message("settings.update.local.noValid", 2),
            ) + MpSqlVisualizerBundle.message("settings.update.channel.local"),
            section.statusLabel.text,
        )
    }

    fun testLocalUpToDateSuffixAndNoInstall() {
        section.localScanRunner = { ScanResult.UpToDate(localPkg("2026.11.11"), total = 1, skipped = 0) }
        section.localCheckButton.doClick()
        waitForState(UpdateSection.State.UpToDate)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.upToDate") +
                MpSqlVisualizerBundle.message("settings.update.channel.local"),
            section.statusLabel.text,
        )
        assertFalse(section.installButton.isVisible)
    }

    fun testLocalDirStatusRowRendersScanResult() {
        section.renderLocalDirStatus(ScanResult.Available(localPkg(), total = 3, skipped = 1))
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.local.ready", 3, "2026.11.12"),
            section.localDirStatusLabel.text,
        )
        section.renderLocalDirStatus(ScanResult.DirInvalid(LocalUpdateDir.DirCheck.InsidePluginsDir))
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.local.insidePluginsDir"),
            section.localDirStatusLabel.text,
        )
        section.renderLocalDirStatus(ScanResult.Disabled)
        assertEquals(MpSqlVisualizerBundle.message("settings.update.local.disabled"), section.localDirStatusLabel.text)
    }

    fun testLocalStatusSurvivesUpdateTexts() {
        // P-3 钉子同源：语言切换 rebuild 只重建文本，状态行必须按最近读数重渲染（不丢状态）
        section.renderLocalDirStatus(ScanResult.UpToDate(localPkg("2026.11.11"), total = 2, skipped = 0))
        section.updateTexts()
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.local.ready.upToDate", 2, "2026.11.11"),
            section.localDirStatusLabel.text,
        )
    }

    fun testRemoteChannelUnpollutedAfterLocal() {
        // 回归：本地通道用过一次后，正式通道读数与后缀不受污染
        section.localScanRunner = { ScanResult.UpToDate(localPkg(), total = 1, skipped = 0) }
        section.localCheckButton.doClick()
        waitForState(UpdateSection.State.UpToDate)
        section.checkRunner = { CheckResult.UpToDate }
        section.checkButton.doClick()
        waitForState(UpdateSection.State.UpToDate)
        assertEquals(UpdateSection.Channel.STABLE, section.channel)
        assertEquals(MpSqlVisualizerBundle.message("settings.update.upToDate"), section.statusLabel.text)
    }

    // ------------------------------------------------------------------
    // T-LocalUpdateDir 真机反馈（2026-11-12）：输入路径后状态行不刷新 / 点「打开」无反应
    // ------------------------------------------------------------------

    /**
     * 输入后防抖校验必须把状态行刷成实际读数（真机症状：填了路径，状态行仍停在
     * 「未设置（此项关闭）」——说明输入后的扫描没跑到或结果没回渲染）。
     */
    fun testLocalDirTypingRefreshesStatusRow() {
        val dir = createTempDir("mpvs-ui-status-")
        try {
            // 用明确高于当前插件的版本，读数走 Available 文案（不受当前版本号变化影响）
            writeFakePluginZip(dir, "2099.1.1")
            section.localDirField.text = dir.absolutePath
            waitForLocalStatus(MpSqlVisualizerBundle.message("settings.update.local.ready", 1, "2099.1.1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 点「打开」必须真的调用打开 seam（真机症状：点按钮毫无反应）。 */
    fun testOpenLocalDirInvokesOpener() {
        val dir = createTempDir("mpvs-ui-open-")
        try {
            writeFakePluginZip(dir, "2099.1.1")
            val opened = mutableListOf<java.nio.file.Path>()
            section.localDirectoryOpener = { opened.add(it) }
            section.localDirField.text = dir.absolutePath
            // 防抖校验与点击存在竞态：先等一次校验落地，再点开
            waitForLocalStatusContains("已找到")
            section.openLocalDirButton.doClick()
            assertEquals("打开按钮必须调用 seam", 1, opened.size)
            assertEquals(dir.toPath().toAbsolutePath().normalize(), opened.single())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 路径非法时点「打开」不调 seam，且状态行给出原因（不静默、不弹窗）。 */
    fun testOpenLocalDirOnInvalidPathShowsReason() {
        var opened = 0
        section.localDirectoryOpener = { opened++ }
        section.localDirField.text = "not-an-absolute-path"
        section.openLocalDirButton.doClick()
        assertEquals("非法路径不得打开", 0, opened)
        assertEquals(
            MpSqlVisualizerBundle.message("settings.update.local.relative"),
            section.localDirStatusLabel.text,
        )
    }

    private fun waitForLocalStatusContains(needle: String) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!section.localDirStatusLabel.text.contains(needle) && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        assertTrue(
            "状态行应包含「$needle」，实际=「${section.localDirStatusLabel.text}」",
            section.localDirStatusLabel.text.contains(needle),
        )
    }

    private fun waitForLocalStatus(expected: String) {
        val deadline = System.currentTimeMillis() + 8_000
        while (section.localDirStatusLabel.text != expected && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        assertEquals(expected, section.localDirStatusLabel.text)
    }

    private fun createTempDir(prefix: String): java.io.File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    /** 造一个标准 IPGP 布局假安装包（顶层插件目录 + lib 内嵌 plugin.xml）。 */
    private fun writeFakePluginZip(dir: java.io.File, version: String) {
        val xml = """
            <idea-plugin>
                <id>${cn.song.mybatisplus.visualizer.update.UpdateSource.PLUGIN_ID}</id>
                <version>$version</version>
            </idea-plugin>
        """.trimIndent()
        val jarBytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { jar ->
                jar.putNextEntry(java.util.zip.ZipEntry("META-INF/plugin.xml"))
                jar.write(xml.toByteArray())
                jar.closeEntry()
            }
        }.toByteArray()
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(java.io.File(dir, "pkg-$version.zip"))).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("mybatis-plus-sql-visualizer/lib/plugin.jar"))
            zip.write(jarBytes)
            zip.closeEntry()
        }
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
