package cn.song.mybatisplus.visualizer.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 开发者模式解锁逻辑单测（T-DevUpdateChannel）。
 *
 * 只测纯逻辑（点击窗口 / 阈值 / 属性口径），不碰平台与 UI：
 *  - 默认不解锁；
 *  - 窗口内 7 次解锁、6 次不解锁；
 *  - 相邻点击间隔超窗 → 计数从 1 重开（慢慢点不累积）；
 *  - `-Dmpvs.update.devMode` 的解析口径（`true`/大小写/空白）。
 *
 * UI 侧（按钮可见性、渲染保持）见 `config/UpdateSectionTest`。
 */
class DevUpdateModeTest {

    @Before
    fun setUp() {
        DevUpdateMode.resetForTest()
    }

    @After
    fun tearDown() {
        DevUpdateMode.resetForTest()
    }

    @Test
    fun 默认未解锁时不可用() {
        assertFalse(DevUpdateMode.isEnabled())
    }

    @Test
    fun unlock直接解锁但可被reset清除() {
        DevUpdateMode.unlock()
        assertTrue(DevUpdateMode.isEnabled())
        DevUpdateMode.resetForTest()
        assertFalse(DevUpdateMode.isEnabled())
    }

    @Test
    fun 窗口内连点七次解锁() {
        var unlockedAt = -1
        // 每次间隔 100ms（远小于 2s 窗口）
        for (i in 1..DevUpdateMode.REQUIRED_CLICKS) {
            if (DevUpdateMode.registerClick(now = i * 100L)) unlockedAt = i
        }
        assertEquals("应在第 7 次点击解锁", DevUpdateMode.REQUIRED_CLICKS, unlockedAt)
        assertTrue(DevUpdateMode.isEnabled())
    }

    @Test
    fun 六次点击不解锁() {
        for (i in 1 until DevUpdateMode.REQUIRED_CLICKS) {
            assertFalse("第 $i 次点击不应解锁", DevUpdateMode.registerClick(now = i * 100L))
        }
        assertFalse(DevUpdateMode.isEnabled())
    }

    @Test
    fun 间隔超窗后计数重开() {
        // 前 6 次快速连点
        for (i in 1 until DevUpdateMode.REQUIRED_CLICKS) {
            DevUpdateMode.registerClick(now = i * 100L)
        }
        // 第 7 次点击距上次超过窗口 → 计数从 1 重开，不解锁
        val stale = 600L + DevUpdateMode.CLICK_WINDOW_MS + 1
        assertFalse("超窗后应重新计数", DevUpdateMode.registerClick(now = stale))
        assertFalse(DevUpdateMode.isEnabled())
        // 紧接其后 6 次都在窗口内 → 第 7 次（即总第 7 次有效连点）解锁
        var unlocked = false
        for (i in 1 until DevUpdateMode.REQUIRED_CLICKS) {
            unlocked = DevUpdateMode.registerClick(now = stale + i * 100L) || unlocked
        }
        assertTrue(unlocked)
        assertTrue(DevUpdateMode.isEnabled())
    }

    @Test
    fun 恰好等于窗口边界仍计入() {
        // 边界口径：间隔 == CLICK_WINDOW_MS 视为同一轮（<=）
        for (i in 1 until DevUpdateMode.REQUIRED_CLICKS) {
            DevUpdateMode.registerClick(now = i * DevUpdateMode.CLICK_WINDOW_MS)
        }
        assertTrue(DevUpdateMode.registerClick(now = DevUpdateMode.REQUIRED_CLICKS * DevUpdateMode.CLICK_WINDOW_MS))
        assertTrue(DevUpdateMode.isEnabled())
    }

    @Test
    fun 属性直通只认true且大小写空白不敏感() {
        assertTrue(DevUpdateMode.parseEnabledProperty("true"))
        assertTrue(DevUpdateMode.parseEnabledProperty("TRUE"))
        assertTrue(DevUpdateMode.parseEnabledProperty("  true  "))
        assertFalse(DevUpdateMode.parseEnabledProperty(null))
        assertFalse(DevUpdateMode.parseEnabledProperty(""))
        assertFalse(DevUpdateMode.parseEnabledProperty("   "))
        assertFalse(DevUpdateMode.parseEnabledProperty("false"))
        assertFalse(DevUpdateMode.parseEnabledProperty("1"))
        assertFalse(DevUpdateMode.parseEnabledProperty("yes"))
    }
}
