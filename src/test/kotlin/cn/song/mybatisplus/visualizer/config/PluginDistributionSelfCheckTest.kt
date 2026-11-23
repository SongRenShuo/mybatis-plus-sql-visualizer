package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件分发自检（M14-01）：从测试 classpath 校验打包关键内容，防打包遗漏回归。
 *
 * 覆盖：
 *  - plugin.xml 注册了设置页 configurable 与正确插件名；
 *  - 插件图标位于主 JAR 的 META-INF（官方规范约定路径）；
 *  - bundle 默认中文（settings 未注册时兜底简体中文，M14）。
 */
class PluginDistributionSelfCheckTest {

    private fun resourceText(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("资源缺失: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `pluginXml_注册设置页configurable`() {
        val xml = resourceText("META-INF/plugin.xml")
        // 官方规范：必须用 applicationConfigurable 注册（com.intellij.configurable 为 abstract EP 不可直接注册）
        assertTrue("应用级设置页应注册 applicationConfigurable", xml.contains("applicationConfigurable"))
        assertTrue("configurable 应挂在 tools 分组", xml.contains("parentId=\"tools\""))
        assertTrue("设置页 id 应存在", xml.contains("cn.song.mybatisplus.visualizer.settings"))
        assertTrue("不应使用 abstract 的 <configurable> 元素", !xml.contains("<configurable"))
    }

    @Test
    fun `pluginXml_插件名统一为全名`() {
        val xml = resourceText("META-INF/plugin.xml")
        assertTrue("插件名应为 MyBatis SQL Visualizer（T-D244 B1 更名）", xml.contains("<name>MyBatis SQL Visualizer</name>"))
        assertTrue("不应残留旧名 MP SQL Visualizer", !xml.contains("MP SQL Visualizer"))
        // 2026-08-28 更名：MyBatis-Plus SQL Visualizer → SQL Visualizer（ID 不变），旧名列入防回归
        assertTrue("不应残留旧名 MyBatis-Plus SQL Visualizer", !xml.contains("MyBatis-Plus SQL Visualizer"))
        // 2026-11-21 更名（T-D244 B1）：SQL Visualizer → MyBatis SQL Visualizer（ID 不变），旧名列入防回归
        assertTrue("不应残留旧名 SQL Visualizer 独名", !xml.contains("<name>SQL Visualizer</name>"))
    }

    @Test
    fun `pluginXml_不依赖kotlin插件_支持动态加载`() {
        // M14.7：移除 org.jetbrains.kotlin 运行时依赖（Kotlin 插件非动态 → 依赖它会导致免重启失败）；
        // 本插件不处理 Kotlin PSI，kotlin-stdlib 由构建自动打包
        // M32-F-4：添加 org.jetbrains.kotlin 可选依赖 + supportsKotlinPluginMode 扩展（Marketplace 要求）
        val xml = resourceText("META-INF/plugin.xml")
        assertTrue("应保留 com.intellij.java 依赖", xml.contains("<depends>com.intellij.java</depends>"))
        // 不应有硬性 Kotlin 依赖（无 optional 标记的 <depends>org.jetbrains.kotlin</depends>）
        assertTrue("不应硬性依赖 org.jetbrains.kotlin 插件",
            !xml.contains("<depends>org.jetbrains.kotlin</depends>"))
        // 允许可选 Kotlin 依赖（optional="true"）+ supportsKotlinPluginMode 扩展（Marketplace 合规）
        assertTrue("应有可选 Kotlin 依赖", xml.contains("optional=\"true\""))
        assertTrue("应有 Kotlin plugin mode 声明",
            xml.contains("supportsKotlinPluginMode"))
    }

    @Test
    fun `插件图标_位于主JAR的META-INF约定路径`() {
        // 官方规范：pluginIcon.svg 须在主 JAR 的 META-INF（无 <icon> 声明）
        assertNotNull("META-INF/pluginIcon.svg 应存在", javaClass.classLoader.getResource("META-INF/pluginIcon.svg"))
        assertNotNull("META-INF/pluginIcon_dark.svg 应存在", javaClass.classLoader.getResource("META-INF/pluginIcon_dark.svg"))
        val xml = resourceText("META-INF/plugin.xml")
        // 去掉注释后不应存在 <icon> 元素（约定路径方式，注释中的文字不算）
        val noComments = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue("不应声明 <icon> 自定义路径（约定路径方式）", !Regex("<icon[ >]").containsMatchIn(noComments))
    }

    @Test
    fun `pluginXml_PluginSettings无XML双注册`() {
        // M16.1：@Service(Service.Level.APP) 注解式注册后，plugin.xml 不应再有 applicationService——
        // 双注册导致持久化绑定错实例（语言切换保存失效的根因）
        val xml = resourceText("META-INF/plugin.xml")
        val noComments = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(
            "不应有 PluginSettings 的 applicationService XML 注册（@Service 注解已声明）",
            !noComments.contains("applicationService") || !noComments.contains("PluginSettings"),
        )
    }

    @Test
    fun `pluginXml_configurable声明displayName或key_匹配getDisplayName覆写`() {
        // M16.1 修正：覆写 getDisplayName() 的 Configurable 必须声明 displayName 或 key 属性
        // （Plugin DevKit 验证规则，缺失报错 "Define 'displayName' or 'key' attributes matching..."）；
        // 声明 bundle+key（key 由 IDE 默认 locale 解析，getDisplayName 覆写保留作动态兜底）
        val xml = resourceText("META-INF/plugin.xml")
        val noComments = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val configurableSection = noComments.substringAfter("applicationConfigurable", "")
        assertTrue(
            "applicationConfigurable 应声明 displayName 或 key 属性（匹配 getDisplayName 覆写）",
            configurableSection.contains("key=") || configurableSection.contains("displayName="),
        )
        if (configurableSection.contains("key=")) {
            assertTrue("声明 key 时应同时声明 bundle", configurableSection.contains("bundle="))
        }
    }

    @Test
    fun `bundle_默认中文_环境未注册时兜底简体中文`() {
        // 测试环境无 PluginSettings service → runCatching 兜底中文（用户要求默认中文）
        assertEquals("简体中文", MpSqlVisualizerBundle.message("settings.language.zh"))
        assertEquals("界面语言", MpSqlVisualizerBundle.message("settings.language"))
        // T-M41：快捷键标签须写明功能（用户反馈「快捷键」不知道是哪个的）
        assertEquals("Convert to SQL 快捷键", MpSqlVisualizerBundle.message("settings.shortcut"))
    }

    @Test
    fun `bundle_悬停预览配置文案已清理`() {
        // M14.1：悬停预览功能删除，对应配置文案不残留
        assertEquals("settings.autoPreview", MpSqlVisualizerBundle.message("settings.autoPreview"))
        assertEquals("settings.hoverDelay", MpSqlVisualizerBundle.message("settings.hoverDelay"))
        // gutter 开关文案不再带（M10）
        assertEquals("在代码左侧显示预览 SQL 图标", MpSqlVisualizerBundle.message("settings.showGutterIcon"))
    }

}
