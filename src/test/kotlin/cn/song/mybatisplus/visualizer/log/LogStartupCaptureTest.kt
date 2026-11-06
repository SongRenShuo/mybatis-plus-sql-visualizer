package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LogStartupCapture 启动条件测试（Wave2-P4）：
 * 测试 shouldStartCapture() 纯函数的条件矩阵。
 *
 * 条件组合：
 *  - suppressed=true → 不启动
 *  - suppressed=false, autoStart=true, hasOpenProject=true → 启动
 *  - suppressed=false, autoStart=false, hasOpenProject=true → 不启动
 *  - suppressed=false, autoStart=false, hasOpenProject=false → 不启动
 *
 * F-03：工具窗随项目启动动作档（decideStartupToolWindowAction 纯函数）条件矩阵同测。
 *
 * 纯函数测试，不需要 PSI 环境。
 */
class LogStartupCaptureTest {

    @Test
    fun `suppressed_true_does_not_start`() {
        // 用户已显式停止 → 无论其他条件如何，不启动
        assertFalse(
            "suppressed=true 时应不启动",
            LogStartupCapture.shouldStartCapture(suppressed = true, autoStart = true, hasOpenProject = true),
        )
        assertFalse(
            "suppressed=true + autoStart=false 应不启动",
            LogStartupCapture.shouldStartCapture(suppressed = true, autoStart = false, hasOpenProject = true),
        )
        assertFalse(
            "suppressed=true + hasOpenProject=false 应不启动",
            LogStartupCapture.shouldStartCapture(suppressed = true, autoStart = true, hasOpenProject = false),
        )
    }

    @Test
    fun `autoStart_true_not_suppressed_starts`() {
        // 设置开启 + 未抑制 + 有项目 → 启动
        assertTrue(
            "autoStart=true, suppressed=false, hasOpenProject=true 应启动",
            LogStartupCapture.shouldStartCapture(suppressed = false, autoStart = true, hasOpenProject = true),
        )
    }

    @Test
    fun `autoStart_false_does_not_start_even_with_project`() {
        // 设置未开启 → 不启动（无论是否有项目）
        assertFalse(
            "autoStart=false + hasOpenProject=true 应不启动",
            LogStartupCapture.shouldStartCapture(suppressed = false, autoStart = false, hasOpenProject = true),
        )
    }

    @Test
    fun `autoStart_false_no_project_does_not_start`() {
        // 设置未开启 + 无项目 → 不启动
        assertFalse(
            "autoStart=false + hasOpenProject=false 应不启动",
            LogStartupCapture.shouldStartCapture(suppressed = false, autoStart = false, hasOpenProject = false),
        )
    }

    @Test
    fun `no_open_project_does_not_start`() {
        // 有设置 + 未抑制 + 无打开项目 → 不启动
        assertFalse(
            "hasOpenProject=false 时应不启动",
            LogStartupCapture.shouldStartCapture(suppressed = false, autoStart = true, hasOpenProject = false),
        )
    }

    // ---------- F-03：工具窗随项目启动动作档 ----------

    @Test
    fun `F03_开关关闭时项目打开零动作`() {
        // autoShow=false → NONE（懒启动：不开窗、不进记录态；autoCapture 的既有链路另行承接）
        assertEquals(
            StartupToolWindowAction.NONE,
            LogStartupCapture.decideStartupToolWindowAction(autoShow = false, suppressed = false),
        )
        assertEquals(
            StartupToolWindowAction.NONE,
            LogStartupCapture.decideStartupToolWindowAction(autoShow = false, suppressed = true),
        )
    }

    @Test
    fun `F03_开关开启未抑制时显示并进入记录态`() {
        assertEquals(
            StartupToolWindowAction.SHOW_AND_CAPTURE,
            LogStartupCapture.decideStartupToolWindowAction(autoShow = true, suppressed = false),
        )
    }

    @Test
    fun `F03_用户显式停止过时只开窗不强制记录态`() {
        assertEquals(
            StartupToolWindowAction.SHOW_ONLY,
            LogStartupCapture.decideStartupToolWindowAction(autoShow = true, suppressed = true),
        )
    }
}
