package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.log.STANDALONE_TAB_REGISTRY_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 独立预览工作台装配守卫（T-SqlPreviewStandalone D-7 / 验收 A-6 反向验证的静态面）。
 *
 * 为什么是静态守卫而不是 GUI 用例：registry 开关的「关掉 tab 不出现」真机形态由 GUI 验收
 * 承接（计划 A-6 not run 条款），这里钉的是三处**易漂移的装配事实**：
 *  1. registry 键名常量与 plugin.xml `<registryKey>` 同名——改名只改一处即静默失效（读的是
 *     另一个键，默认值兜底永远为 true，开关成了摆设）；
 *  2. 消费点唯一且用默认值参数（`Registry.is(key, true)`）——改成无参重载后，缺键场景行为
 *     漂移且无处可见；
 *  3. bundle 双语键位 parity（`standalone.*` 与 `log.tab.standalone` 两份 properties 键集一致）。
 */
class StandaloneTabRegistryGuardTest {

    private fun mainFile(rel: String): String = File(rel).readText()

    private fun pluginXml(): String = mainFile("src/main/resources/META-INF/plugin.xml")

    @Test
    fun `registry 键常量与 plugin_xml 登记同名`() {
        assertEquals("sqlvis.preview.standaloneTab", STANDALONE_TAB_REGISTRY_KEY)
        assertTrue(
            "plugin.xml 必须登记 <registryKey key=\"$STANDALONE_TAB_REGISTRY_KEY\"",
            pluginXml().contains("""<registryKey key="$STANDALONE_TAB_REGISTRY_KEY""""),
        )
    }

    @Test
    fun `消费点用带默认值的 is 重载（缺键兜底 true）`() {
        val panelSrc = mainFile("src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt")
        // `is` 是 Kotlin 关键字，Java 侧 Registry.is 必须反引号（MbgExampleCallResolver.kt:164 在册）
        assertTrue(
            "SqlLogPanel 必须以 Registry.`is`(key, true) 消费开关（缺键场景显式兜底）",
            panelSrc.contains("Registry.`is`(STANDALONE_TAB_REGISTRY_KEY, true)"),
        )
    }

    @Test
    fun `action 已注册且走 Tools 菜单与默认键位`() {
        val xml = pluginXml()
        assertTrue(
            "OpenSqlPreview action 必须注册（id=cn.song.mybatisplus.visualizer.OpenSqlPreview）",
            xml.contains("""<action id="cn.song.mybatisplus.visualizer.OpenSqlPreview""""),
        )
        assertTrue("action class 必须指向 OpenSqlPreviewAction", xml.contains("actions.OpenSqlPreviewAction"))
        assertTrue("必须挂 Tools 菜单", xml.contains("""<add-to-group group-id="ToolsMenu" anchor="last"/>"""))
        assertTrue("必须登记默认键位 alt shift P", xml.contains("""first-keystroke="alt shift P""""))
    }

    @Test
    fun `bundle 双语键位 parity（standalone 族与页签键）`() {
        val en = File("src/main/resources/messages/MpSqlVisualizerBundle.properties").readLines()
        val zh = File("src/main/resources/messages/MpSqlVisualizerBundle_zh.properties").readLines()
        fun keysOf(lines: List<String>, prefix: String) = lines
            .filter { it.startsWith(prefix) && !it.startsWith("#") }
            .map { it.substringBefore('=') }.toSet()
        val prefixes = listOf("log.tab.standalone", "standalone.")
        prefixes.forEach { prefix ->
            val enKeys = keysOf(en, prefix)
            val zhKeys = keysOf(zh, prefix)
            assertTrue("en bundle 缺 $prefix 族键", enKeys.isNotEmpty())
            assertEquals("$prefix 族键 en/zh 不齐", enKeys, zhKeys)
        }
    }

    @Test
    fun `解析器与面板、通道三处接线在源码层在场`() {
        val managerSrc = mainFile("src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManager.kt")
        assertTrue(
            "SqlLogManager 必须声明 onOpenStandalonePreview 通道",
            managerSrc.contains("onOpenStandalonePreview"),
        )
        val panelSrc = mainFile("src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt")
        assertTrue("SqlLogPanel 必须注册通道回调", panelSrc.contains("manager.onOpenStandalonePreview ="))
        assertTrue("SqlLogPanel 必须挂载工作台页签", panelSrc.contains("addStandaloneTab"))
        val actionSrc = mainFile(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/actions/OpenSqlPreviewAction.kt",
        )
        assertTrue("action 必须复用解析器的剪贴板候选判定（同源纪律）", actionSrc.contains("isClipboardCandidate"))
    }
}
