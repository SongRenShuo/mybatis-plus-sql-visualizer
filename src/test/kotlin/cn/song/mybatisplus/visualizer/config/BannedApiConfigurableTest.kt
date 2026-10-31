package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.inspection.BannedApiRule
import cn.song.mybatisplus.visualizer.inspection.BannedApiRuleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JComponent

/**
 * 禁用 API 设置子页 headless 冒烟（T-BannedApiCheck S2）。
 *
 * 只验「页面能建起来 + 列表跟随管理器」：表单装配走 FormBuilder/Splitter/JBList，构造期抛异常
 * 在 headless 测试里能直接暴露（真机只是弹不出页面）；即时生效语义由
 * [BannedApiRuleManagerTest] 的增删改测覆盖。
 *
 * 属平台桶（`com.intellij` 引用），不在 `testFast` 快桶内，定向跑。
 */
class BannedApiConfigurableTest : BasePlatformTestCase() {

    fun test页面可构建_列表跟随规则管理器即时刷新() {
        val manager = BannedApiRuleManager()
        val configurable = BannedApiConfigurable { manager }

        assertEquals(
            "显示名取 bundle 键",
            cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("settings.section.bannedApi"),
            configurable.displayName,
        )

        val component: JComponent = configurable.createComponent()
        assertNotNull("页面组件应构建成功", component)
        assertTrue("初始无规则", configurable.rulesForTest().isEmpty())

        manager.addRule(
            BannedApiRule(
                className = "com.acme.LegacyDao",
                methodName = "save.*",
                description = "改用 NewDao",
            ),
        )
        assertEquals("管理器变更后列表即时刷新（关闭即生效语义）", 1, configurable.rulesForTest().size)

        configurable.disposeUIResources()
    }
}
