package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 首用引导「一次性 / 版本粒度持久化」语义（平台测试，真实 PluginSettings 状态翻转）：
 *  - [MapperProjectPrimer.tryClaim] 领取即写持久化记忆；
 *  - 同版本二次领取失败（重开工程不再弹）；
 *  - 版本变化后允许重新领取；
 *  - 新字段随 State 往返持久化（模拟 IDE 重启 loadState）。
 *
 * headless 静默（`isUnitTestMode` 不探测、不写记忆）由纯函数
 * `OnboardingStatusSnapshotTest.headless下不探测` 覆盖——本类只在**显式调用领取**的路径上断言状态。
 */
class MapperProjectPrimerClaimTest : BasePlatformTestCase() {

    private var oldVersion = ""

    override fun setUp() {
        super.setUp()
        oldVersion = PluginSettings.getInstance().state.mapperPrimerVersion
        MapperProjectPrimer.resetForTest()
        PluginSettings.getInstance().state.mapperPrimerVersion = ""
    }

    override fun tearDown() {
        try {
            MapperProjectPrimer.resetForTest()
            PluginSettings.getInstance().state.mapperPrimerVersion = oldVersion
        } finally {
            super.tearDown()
        }
    }

    fun `test首次领取成功并写入持久化记忆`() {
        assertTrue("首次领取应成功", MapperProjectPrimer.tryClaim("2026.10.31"))
        assertEquals(
            "领取即消耗：版本必须已写入持久化记忆（先写记忆再弹通知）",
            "2026.10.31",
            PluginSettings.getInstance().state.mapperPrimerVersion,
        )
    }

    fun `test同版本二次领取失败_重开工程不再弹`() {
        assertTrue(MapperProjectPrimer.tryClaim("2026.10.31"))
        assertFalse("同版本二次领取必须失败（一次性 / 版本粒度）", MapperProjectPrimer.tryClaim("2026.10.31"))
        assertEquals("已提示版本保持不变", "2026.10.31", PluginSettings.getInstance().state.mapperPrimerVersion)
    }

    fun `test版本变化后允许再领取一次`() {
        assertTrue(MapperProjectPrimer.tryClaim("2026.10.31"))
        assertTrue("版本升级后允许再提示一次", MapperProjectPrimer.tryClaim("2026.11.01"))
        assertEquals("2026.11.01", PluginSettings.getInstance().state.mapperPrimerVersion)
    }

    fun `test新字段随State持久化往返保留`() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.mapperPrimerVersion = "2026.12.24"
            val saved = settings.getState()
            assertEquals("getState 应反映最新记忆", "2026.12.24", saved.mapperPrimerVersion)
            // 模拟重启：新实例 loadState
            val fresh = PluginSettings()
            fresh.loadState(saved)
            assertEquals("loadState 后记忆保留（重启不再弹）", "2026.12.24", fresh.state.mapperPrimerVersion)
            assertEquals("出厂默认：从未提示", "", PluginSettings.State().mapperPrimerVersion)
        } finally {
            settings.loadState(snapshot)
        }
    }
}
