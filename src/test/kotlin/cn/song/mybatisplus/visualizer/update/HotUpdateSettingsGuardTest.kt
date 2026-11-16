package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HotUpdateSettingsGuard：热更新后设置页兜底的判定面。
 *
 * 真机背景（用户 2026-11-13）：从本地安装包热更新后设置页点「确定」无反应、点「取消」正常——
 * 旧 classloader 卸载后 apply 链实例化 configurable 抛 CNFE 被平台吞掉。本类只测**判定**，
 * 关窗/弹泡是 EDT 副作用，交真机与沙盒验收（计划 §五 A1~A3）。
 */
class HotUpdateSettingsGuardTest {

    @Test
    fun `本插件更新载入才处理`() {
        assertTrue(HotUpdateSettingsGuard.shouldHandle(HotUpdateSettingsGuard.PLUGIN_ID, isUpdate = true))
    }

    @Test
    fun `冷启动与别家插件更新都不处理`() {
        // 负门：普通启动（isUpdate=false）不得关任何窗口、不得弹泡
        assertFalse(HotUpdateSettingsGuard.shouldHandle(HotUpdateSettingsGuard.PLUGIN_ID, isUpdate = false))
        // 负门：别人家的热更新与我无关
        assertFalse(HotUpdateSettingsGuard.shouldHandle("com.example.other.plugin", isUpdate = true))
        assertFalse(HotUpdateSettingsGuard.shouldHandle("", isUpdate = true))
    }

    @Test
    fun `PLUGIN_ID 与 plugin_xml 的 id 一字不差`() {
        // 判据靠 id 字符串比对；plugin.xml 改了 id 而这里没跟 ⇒ 兜底静默失效（空绿），故钉死
        val xml = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("资源缺失: META-INF/plugin.xml")
        val id = Regex("<id>\\s*([^<\\s]+)\\s*</id>").find(xml)?.groupValues?.get(1)
        assertEquals("plugin.xml 的 <id> 必须与 HotUpdateSettingsGuard.PLUGIN_ID 一致", id, HotUpdateSettingsGuard.PLUGIN_ID)
    }

    @Test
    fun `设置对话框按类名后缀识别`() {
        // 243 平台实测 FQN（app-client.jar 内，应用级与项目级设置同一个类）
        assertTrue(
            HotUpdateSettingsGuard.isSettingsDialogClass(
                "com.intellij.openapi.options.newEditor.SettingsDialog",
            ),
        )
        // 负门：别的对话框不得被误关（用户的预览面板/确认框都可能正开着且有未保存内容）
        assertFalse(HotUpdateSettingsGuard.isSettingsDialogClass("com.intellij.openapi.ui.Messages\$MyDialog"))
        assertFalse(HotUpdateSettingsGuard.isSettingsDialogClass("com.song.mybatisplus.visualizer.ui.SqlPreviewDialog"))
        // 只有后缀算命中：名字里含该词但结尾不是的仍不碰
        assertFalse(HotUpdateSettingsGuard.isSettingsDialogClass("SettingsDialogWrapperX"))
    }
}
