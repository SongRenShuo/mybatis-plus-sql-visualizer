package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首次捕获成功指路决策测试（T-UxOnboard T-UX-03；T-NotifyTune 券 B 降频）：
 * shouldGuide 纯函数条件矩阵——开关、每安装一次性、历史非空、窗口已可见、本次已自动展开、headless。
 * headless 下 onEntryAccepted 真实入口不炸且有界，见 FirstCaptureGuideSessionTest。
 */
class FirstCaptureGuideTest {

    @Before
    fun resetSessionMemory() {
        FirstCaptureGuide.resetForTest()
    }

    @Test
    fun `首次捕获_指路`() {
        assertTrue(
            "开关开 + 未展示 + 缓冲恰 1 条 + 非 headless → 指路",
            FirstCaptureGuide.shouldGuide(alreadyGuided = false, bufferCount = 1, unitTestMode = false),
        )
    }

    @Test
    fun `每安装一次性_已展示不再弹`() {
        assertFalse(
            "已展示（持久化记忆）→ 跳过",
            FirstCaptureGuide.shouldGuide(alreadyGuided = true, bufferCount = 1, unitTestMode = false),
        )
    }

    @Test
    fun `历史非空跳过_防打扰`() {
        assertFalse(
            "缓冲已有 2 条（本条非首条）→ 跳过",
            FirstCaptureGuide.shouldGuide(alreadyGuided = false, bufferCount = 2, unitTestMode = false),
        )
        assertFalse(
            "缓冲为空（入列前的回调形态）→ 不指路",
            FirstCaptureGuide.shouldGuide(alreadyGuided = false, bufferCount = 0, unitTestMode = false),
        )
    }

    @Test
    fun `开关关闭_全程静默`() {
        assertFalse(
            "开关关闭 → 不指路（红线准入五问第 3 问）",
            FirstCaptureGuide.shouldGuide(enabled = false, alreadyGuided = false, bufferCount = 1, unitTestMode = false),
        )
    }

    @Test
    fun `窗口已可见_不弹`() {
        assertFalse(
            "日志窗已打开 = 用户已找到面板",
            FirstCaptureGuide.shouldGuide(
                alreadyGuided = false, bufferCount = 1, windowVisible = true, unitTestMode = false,
            ),
        )
    }

    @Test
    fun `本次已自动展开_不叠第二条信号`() {
        assertFalse(
            "自动展开生效 → 窗口本身已是发现（券 B D-6）",
            FirstCaptureGuide.shouldGuide(
                alreadyGuided = false, bufferCount = 1, autoOpened = true, unitTestMode = false,
            ),
        )
    }

    @Test
    fun `headless静默`() {
        assertFalse(
            "isUnitTestMode → 不指路",
            FirstCaptureGuide.shouldGuide(alreadyGuided = false, bufferCount = 1, unitTestMode = true),
        )
    }
}