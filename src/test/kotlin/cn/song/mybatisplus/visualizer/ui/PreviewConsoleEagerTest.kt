package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PreviewExecWriteDefer：`PreviewConsoleEager` 的口径开关。
 *
 * 背景（用户 2026-11-14 00:10 A/B）：预览打开「准备控制台」会写 `mpsql-exec.sql`，该文件带方言映射 ⇒
 * 平台重算语言替换 + 推文件属性 ⇒ 工程进 dumb（第 1 次写→dumb；第 2 次不写→无 dumb）。惰性口径把
 * 这一步挪到「首次执行」，执行链自己会准备 + 同步 exec 文件。
 */
class PreviewConsoleEagerTest : com.intellij.testFramework.fixtures.BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PreviewConsoleEager.resetForTest()
    }

    override fun tearDown() {
        try {
            PreviewConsoleEager.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    fun test单测口径默认走旧行为() {
        // 单测里默认 eager：既有用例按「打开即准备控制台」写，不能因此大面积改测试
        assertTrue("单测默认 eager（旧行为）", PreviewConsoleEager.enabled())
    }

    fun test显式置惰性时不准备控制台() {
        PreviewConsoleEager.enabledForTest = false
        assertFalse(PreviewConsoleEager.enabled())
        PreviewConsoleEager.recordDeferred()
        assertEquals("延后计数用于守卫", 1, PreviewConsoleEager.deferredCount.get())
    }

    fun test显式置旧行为() {
        PreviewConsoleEager.enabledForTest = true
        assertTrue(PreviewConsoleEager.enabled())
    }

    /**
     * 接线守卫：bind-state 行必须提供「显示执行控制台」链接，且**惰性口径下才显示**。
     *
     * 为什么在这里守：惰性口径下控制台不在打开时创建，而换数据源只能走控制台自带的 Data Sources 菜单
     * （仓内 W12 定案：不自建换源 UI）——没有这个链接，用户就没有「先看控制台/先换源」的入口。
     */
    fun test显示控制台入口仍在且受惰性口径控制() {
        val candidates = listOf(
            java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelParamPanel.kt"),
            java.io.File("../src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelParamPanel.kt"),
        )
        val source = candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("找不到 SqlPreviewPanelParamPanel.kt")
        val codeOnly = source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
        assertTrue("bind-state 行必须有「显示执行控制台」链接", codeOnly.contains("panel.console.show"))
        assertTrue("链接点击必须触发 prepareDefaultConsole", codeOnly.contains("prepareDefaultConsole()"))
        assertTrue(
            "链接可见性必须受三档口径控制（否则 eager/SILENT 下会多出一个没用的入口）",
            codeOnly.contains("PreviewConsoleEager.showShowConsoleEntry()"),
        )
    }

    fun test三档口径的判定矩阵() {
        // LAZY（默认）：不 eager，且给「显示执行控制台」入口
        PreviewConsoleEager.modeForTest = PreviewConsoleEager.Mode.LAZY
        PreviewConsoleEager.enabledForTest = false
        assertFalse("LAZY 不得 eager", PreviewConsoleEager.enabled())
        assertTrue("LAZY 必须给入口", PreviewConsoleEager.showShowConsoleEntry())

        // LAZY_SILENT：不 eager，也不给入口
        PreviewConsoleEager.modeForTest = PreviewConsoleEager.Mode.LAZY_SILENT
        assertFalse(PreviewConsoleEager.enabled())
        assertFalse("LAZY_SILENT 不得给入口", PreviewConsoleEager.showShowConsoleEntry())

        // EAGER：回到旧行为（打开即准备），入口无意义
        PreviewConsoleEager.modeForTest = PreviewConsoleEager.Mode.EAGER
        PreviewConsoleEager.enabledForTest = null
        assertTrue("EAGER 必须 eager", PreviewConsoleEager.enabled())
        assertFalse("EAGER 下不显示入口", PreviewConsoleEager.showShowConsoleEntry())
    }

    fun test设置页默认档是惰性带入口() {
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.State()
        assertEquals("新装/未设置时应为 LAZY（惰性 + 入口）", "LAZY", state.consolePrepMode)
    }

    fun test三档枚举名与存档值一致() {
        // 设置页选项由枚举名派生（见 consolePrepOptions），落盘值也存枚举名——
        // 改名即等于改存档格式，必须有守卫提醒。
        assertEquals(
            listOf("LAZY", "LAZY_SILENT", "EAGER"),
            PreviewConsoleEager.Mode.values().map { it.name },
        )
    }
}
