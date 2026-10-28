package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import cn.song.mybatisplus.visualizer.config.UpdateSection;
import cn.song.mybatisplus.visualizer.update.PluginUpdateRunner;
import cn.song.mybatisplus.visualizer.update.PluginVersion;
import cn.song.mybatisplus.visualizer.update.UpdateManifest;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import kotlin.Unit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.swing.JFrame;

/**
 * 设置页更新分区测试（T-M28-03）：
 *  - 初始态（版本行/开关默认开/安装按钮隐藏）
 *  - 状态机渲染（Available/UpToDate/检查失败/安装完成/安装失败——直接调用 render* 驱动，
 *    不触达网络与平台安装；异步任务由 runIde 沙盒端到端验证）
 */
public class UpdateSectionTest extends BasePlatformTestCase {

    private UpdateSection section;

    /** 窗口宿主（T-M28.1 交互测试）：异步回调按 isDisplayable 判活——无宿主组件
     *  displayable=false 会跳过渲染（真实设置页在对话框内无此问题），测试须放入可见窗口。 */
    private JFrame host;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        section = new UpdateSection(PluginSettings.getInstance());
        host = new JFrame();
        host.getContentPane().add(section);
        host.pack();
        host.setVisible(true);
    }

    @Override
    protected void tearDown() throws Exception {
        host.dispose();
        super.tearDown();
    }

    public void testInitialState() throws Exception {
        // 版本行展示当前插件版本（读自身 META-INF/plugin.xml）
        String cv = PluginVersion.current();
        String expected = MpSqlVisualizerBundle.message("settings.update.currentVersion")
                + ": " + (cv != null ? cv : "—");
        assertEquals(expected, section.getVersionLabel().getText());
        // 抢先体验默认关（稳定版由 Marketplace 推送）
        assertFalse(section.getAutoCheckCheck().isSelected());
        assertEquals(UpdateSection.State.Idle, section.getState());
        // 未检查前：安装按钮隐藏、重启按钮隐藏、状态空白
        assertFalse(section.getInstallButton().isVisible());
        assertFalse(section.getRestartButton().isVisible());
        assertTrue(section.getCheckButton().isEnabled());
    }

    public void testRenderAvailable() throws Exception {
        UpdateManifest manifest = new UpdateManifest("2026.8.15", "https://example.com/p.zip", "line1\nline2", null, null);
        section.renderCheck(new PluginUpdateRunner.CheckResult.Available(manifest));

        assertEquals(UpdateSection.State.Available, section.getState());
        assertTrue("发现新版后安装按钮应可见", section.getInstallButton().isVisible());
        assertTrue(section.getInstallButton().isEnabled());
        assertEquals(
                MpSqlVisualizerBundle.message("settings.update.available", "2026.8.15"),
                section.getStatusLabel().getText());
        // 变更日志文本域展示 changeNotes
        assertTrue(section.getNotesArea().getText().contains("line1"));
        assertTrue(section.getNotesArea().getText().contains("line2"));
    }

    public void testRenderUpToDate() throws Exception {
        section.renderCheck(PluginUpdateRunner.CheckResult.UpToDate.INSTANCE);
        assertEquals(UpdateSection.State.UpToDate, section.getState());
        assertEquals(MpSqlVisualizerBundle.message("settings.update.upToDate"), section.getStatusLabel().getText());
        assertFalse(section.getInstallButton().isVisible());
    }

    public void testRenderCheckFailure() throws Exception {
        // R62：失败按 FailureKind 分流文案（网络态 = fail.network）
        section.renderCheck(new PluginUpdateRunner.CheckResult.Failure(
                PluginUpdateRunner.FailureKind.NETWORK, "network unavailable"));
        assertEquals(UpdateSection.State.Failed, section.getState());
        assertEquals(
                MpSqlVisualizerBundle.message("settings.update.fail.network", "network unavailable"),
                section.getStatusLabel().getText());
        // 失败后可重试
        assertTrue(section.getCheckButton().isEnabled());
        assertFalse(section.getInstallButton().isVisible());
    }

    /** R62 三态渲染：404 态给 Marketplace + Gitee 兜底链接（master 82ee88b1 同款）。 */
    public void testRenderChannelMissing_404态_兜底链接可见() throws Exception {
        section.renderCheck(new PluginUpdateRunner.CheckResult.Failure(
                PluginUpdateRunner.FailureKind.CHANNEL_MISSING, "HTTP 404"));
        assertEquals(UpdateSection.State.Failed, section.getState());
        assertEquals(
                MpSqlVisualizerBundle.message("settings.update.fail.channel404", "HTTP 404"),
                section.getStatusLabel().getText());
        assertTrue("404 态兜底链接行应可见", fallbackLinksVisible());
        assertTrue(section.getCheckButton().isEnabled());
    }

    public void testRenderNetworkFailure_兜底链接不可见() throws Exception {
        section.renderCheck(new PluginUpdateRunner.CheckResult.Failure(
                PluginUpdateRunner.FailureKind.NETWORK, "connect timed out"));
        assertEquals(UpdateSection.State.Failed, section.getState());
        assertTrue(section.getStatusLabel().getText().isEmpty() == false);
        assertFalse("网络态不给兜底链接", fallbackLinksVisible());
    }

    public void testRenderUpToDate_三态之已最新() throws Exception {
        section.renderCheck(PluginUpdateRunner.CheckResult.UpToDate.INSTANCE);
        assertEquals(
                MpSqlVisualizerBundle.message("settings.update.upToDate"),
                section.getStatusLabel().getText());
        assertFalse(fallbackLinksVisible());
    }

    public void testRenderInstalled() throws Exception {
        UpdateManifest manifest = new UpdateManifest("2026.8.15", "https://example.com/p.zip", null, null, null);
        section.renderCheck(new PluginUpdateRunner.CheckResult.Available(manifest));
        section.renderInstall(PluginUpdateRunner.InstallResult.InstalledRestartRequired.INSTANCE);

        assertEquals(UpdateSection.State.Installed, section.getState());
        assertEquals(MpSqlVisualizerBundle.message("settings.update.installed"), section.getStatusLabel().getText());
        // 重启提示与重启按钮可见；安装/检查按钮不可再操作
        assertTrue(section.getRestartButton().isVisible());
        assertFalse(section.getInstallButton().isVisible());
        assertFalse(section.getCheckButton().isEnabled());
    }

    public void testRenderInstalledLive() throws Exception {
        // T-DynUpdate：动态热替换成功 → InstalledLive 态——免重启文案，无重启按钮
        UpdateManifest manifest = new UpdateManifest("2026.8.15", "https://example.com/p.zip", null, null, null);
        section.renderCheck(new PluginUpdateRunner.CheckResult.Available(manifest));
        section.renderInstall(new PluginUpdateRunner.InstallResult.InstalledDynamic("2026.9.11-beta.1"));

        assertEquals(UpdateSection.State.InstalledLive, section.getState());
        assertEquals(MpSqlVisualizerBundle.message("settings.update.installedLive"), section.getStatusLabel().getText());
        assertFalse(section.getRestartButton().isVisible());
        assertFalse(section.getInstallButton().isVisible());
        assertFalse(section.getCheckButton().isEnabled());
    }

    public void testRenderInstallFailed() throws Exception {
        UpdateManifest manifest = new UpdateManifest("2026.8.15", "https://example.com/p.zip", null, null, null);
        section.renderCheck(new PluginUpdateRunner.CheckResult.Available(manifest));
        section.renderInstall(new PluginUpdateRunner.InstallResult.Failed("install rejected"));

        assertEquals(UpdateSection.State.Failed, section.getState());
        assertEquals(
                MpSqlVisualizerBundle.message("settings.update.installFailed", "install rejected"),
                section.getStatusLabel().getText());
        assertTrue(section.getCheckButton().isEnabled());
    }

    public void testAutoCheckFromState() throws Exception {
        PluginSettings.getInstance().getState().setAutoCheckUpdate(false);
        section.resetState();
        assertFalse("开关应跟随配置（取消勾选）", section.getAutoCheckCheck().isSelected());

        PluginSettings.getInstance().getState().setAutoCheckUpdate(true);
        section.resetState();
        assertTrue(section.getAutoCheckCheck().isSelected());
    }

    public void testLanguageSwitchRefreshesTexts() throws Exception {
        // updateTexts 由 rebuild 调用：切换语言后按钮文案随 bundle 刷新（同 M19.2 复选框修复模式）
        section.updateTexts();
        assertEquals(MpSqlVisualizerBundle.message("settings.update.check"), section.getCheckButton().getText());
        assertEquals(MpSqlVisualizerBundle.message("settings.update.restartNow"), section.getRestartButton().getText());
    }

    // ---------------- T-M28.1 按钮交互（此前缺失绑定——用户实测点击无反应；盲区补测） ----------------

    public void testButtonsHaveActionListeners() throws Exception {
        assertTrue("检查更新按钮应绑定事件", section.getCheckButton().getActionListeners().length > 0);
        assertTrue("下载并安装按钮应绑定事件", section.getInstallButton().getActionListeners().length > 0);
        assertTrue("重启按钮应绑定事件", section.getRestartButton().getActionListeners().length > 0);
    }

    public void testCheckButtonClickShowsCheckingThenResult() throws Exception {
        final int[] calls = {0};
        section.setCheckRunner(() -> {
            calls[0]++;
            return PluginUpdateRunner.CheckResult.UpToDate.INSTANCE;
        });
        section.getCheckButton().doClick();
        // 同步进入「检查中」（Backgroundable 从非 EDT 同步执行；fake 立即完成）
        assertEquals(UpdateSection.State.Checking, section.getState());
        assertEquals(MpSqlVisualizerBundle.message("settings.update.checking"), section.getStatusLabel().getText());
        waitForState(UpdateSection.State.UpToDate);
        assertEquals(1, calls[0]);
        assertEquals(MpSqlVisualizerBundle.message("settings.update.upToDate"), section.getStatusLabel().getText());
    }

    public void testInstallButtonClickDownloadsAndInstalls() throws Exception {
        final int[] calls = {0};
        section.setInstallRunner((manifest, indicator) -> {
            calls[0]++;
            return PluginUpdateRunner.InstallResult.InstalledRestartRequired.INSTANCE;
        });
        section.renderCheck(new PluginUpdateRunner.CheckResult.Available(
                new UpdateManifest("2026.8.15", "https://example.com/p.zip", null, null, null)));
        section.getInstallButton().doClick();
        assertEquals(UpdateSection.State.Downloading, section.getState());
        waitForState(UpdateSection.State.Installed);
        assertEquals(1, calls[0]);
        assertTrue("安装完成应显示重启按钮", section.getRestartButton().isVisible());
    }

    public void testRestartButtonClickInvokesRestartAction() throws Exception {
        final int[] restarts = {0};
        section.setRestartAction(() -> {
            restarts[0]++;
            return Unit.INSTANCE;
        });
        section.renderCheck(new PluginUpdateRunner.CheckResult.Available(
                new UpdateManifest("1.0", "https://x/p.zip", null, null, null)));
        section.renderInstall(PluginUpdateRunner.InstallResult.InstalledRestartRequired.INSTANCE);
        section.getRestartButton().doClick();
        assertEquals(1, restarts[0]);
    }

    public void testGiteeLinkPresent() throws Exception {
        // T-M28.4：变更日志关联 Gitee——链接存在且文案走 bundle
        assertEquals(MpSqlVisualizerBundle.message("settings.update.viewOnGitee"), section.getGiteeLink().getText());
    }

    /** 兜底链接行可见性——k2j 侧 fallbackLinksContainer 为私有字段且无测试访问器，
     * 私有字段不可触生产码，测试以反射读取（master 侧直接读公开属性）。 */
    private boolean fallbackLinksVisible() throws Exception {
        java.lang.reflect.Field f = UpdateSection.class.getDeclaredField("fallbackLinksContainer");
        f.setAccessible(true);
        return ((java.awt.Component) f.get(section)).isVisible();
    }

    /** 轮询等待状态（EDT 派发 + 短轮询；fake seam 立即完成，正常一轮即达）。 */
    private void waitForState(UpdateSection.State target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        while (section.getState() != target && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents();
            Thread.sleep(20);
        }
        assertEquals(target, section.getState());
    }
}
