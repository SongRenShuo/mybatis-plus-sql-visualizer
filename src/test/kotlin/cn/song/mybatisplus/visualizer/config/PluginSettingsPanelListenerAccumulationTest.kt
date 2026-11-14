package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 Y15/Y16：设置面板 rebuild 监听器累积 + 非 UI 页误报 rebuild 回归。
 *
 * ①buildSections 在字段级控件上重复挂监听器（同文件顶部红线「挂 addRow 处会随重建叠加」）
 * ——rebuild（语言切换 / 非 UI 页 apply）线性累积：模式预览每键 k 次重算、注入行 k 次触发。
 * 修复后 registerListenerOnce 守卫键集幂等（同键只挂一次）。
 * ②非 UI 分区面板 languageCombo 空 model → apply() 恒报 languageChanged=true → 每次
 * Apply/OK 无谓全量 rebuild（并放大①）。修复后 languageChanged 判定加 SECTION_UI 门。
 *
 * 平台 UI 测试面（L13）：本类为定向面；全量 testShards not run 待收口。
 */
class PluginSettingsPanelListenerAccumulationTest : BasePlatformTestCase() {

    fun testRebuild两次监听器不累积() {
        val panel = PluginSettingsPanel(PluginSettings.getInstance())
        val before = panel.dialectCombo.actionListeners.size
        panel.rebuild()
        panel.rebuild()
        val after = panel.dialectCombo.actionListeners.size
        assertEquals(
            "rebuild 两次后方言下拉的 ActionListener 应恒为 1（守卫前为 3）",
            1,
            after,
        )
        assertEquals("守卫前基线应恰为 1（构建期注册一次）", 1, before)
    }

    fun test非UI页apply不误报语言变更() {
        val panel = PluginSettingsPanel(PluginSettings.getInstance(), setOf(PluginSettingsPanel.SECTION_SQL_LOG))
        // 非 UI 页：languageCombo 空 model——修复前 selectedItem=null 恒 != s.language → 恒 true
        assertFalse("非 UI 页 apply 不应误报 languageChanged", panel.apply())
    }
}
