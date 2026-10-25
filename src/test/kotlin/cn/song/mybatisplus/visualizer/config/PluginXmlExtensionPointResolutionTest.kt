package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * plugin.xml EP 名**可解析性**守卫（T-XmlGap 收口新建）。
 *
 * ## 为什么需要这条守卫
 *
 * 平台对 `<extensions>` 下**解析不到**的 EP 名一律**静默忽略**（只打日志；插件照常加载、无任何
 * 用户可见报错）——注册段于是变成死代码。本仓已两次踩同一坑：
 *  - `typedHandlerDelegate`（真名 `typedHandler`）：`#{}` 敲键闭合 handler 永不触发；
 *  - **裸 `<syntaxHighlighterFactory>`（真名 `lang.syntaxHighlighterFactory`）**：注入语言的高亮器
 *    工厂从未注册 → `SyntaxHighlighterFactory.getSyntaxHighlighter` 落到
 *    `PlainSyntaxHighlighterFactory` 默认实现 → mapper 语句文本**整段无色**（用户 2026-10-12 报
 *    「SQL 文本整段白」的真根因；平台声明处 = `intellij.platform.ide.impl.jar` 内
 *    `META-INF/PlatformExtensionPoints.xml` 的 `name="lang.syntaxHighlighterFactory"`，243/262 同名）。
 *
 * ## 判据
 *
 * 取每份描述文件 `<extensions ...>` 块的直接子标签（剥注释后），按 IntelliJ 的 EP 限定名规则
 * 还原：`<defaultExtensionNs>.<tag>`（`defaultExtensionNs` 缺省 `com.intellij`），逐个要求它出现在
 * **平台声明的 EP 全集**里。EP 全集两路取并集（互为兜底，任一路退化不空绿）：
 *  ① `java.class.path` 上全部 jar 内 `META-INF` 目录下的描述文件里的
 *     `<extensionPoint name=|qualifiedName=>`（裸 `name` 按其所属描述文件的 `<id>` 前缀还原，
 *     无 `<id>` 的按核心插件 `com.intellij` 还原）；
 *  ② `PluginManagerCore` 已加载插件描述符的扩展点（反射调用，绕开该 API 对 Kotlin 的 `@Deprecated`
 *     隐藏）。
 *
 * 与 `PluginXmlStructureGuardTest` 的「形态」类规则互补：那条管写法，这条管名字能不能解析。
 *
 * ## 已知豁免（[EXTERNAL_EPS]）
 *
 * 少数 EP 由**测试沙盒未加载**的插件贡献（可选依赖 / 未装插件），真机存在而测试环境缺席——
 * 逐条列明来源，不得图省事整类放行。
 */
class PluginXmlExtensionPointResolutionTest {

    private val xmlNames = listOf(
        "plugin.xml", "kotlin-optional.xml", "database-optional.xml", "diagram-optional.xml",
        "mcp-optional.xml", "acp-transport-optional.xml",
    )

    /**
     * 由测试环境未加载的插件贡献的 EP 限定名（真机/发布环境存在）。
     * 每条注明贡献方；新增豁免必须给出「哪个插件声明了它」的证据，否则就是掩盖真 bug。
     */
    private val externalEps = setOf(
        // 可选依赖 com.intellij.mcpServer（未装时主文件仍须可加载，见 mcp-optional.xml）
        "com.intellij.mcpServer.mcpToolset",
        // 可选依赖 com.intellij.database：声明在 database-plugin.jar 的 META-INF/plugin.xml
        // （`<extensionPoint qualifiedName="com.intellij.database.modelRelationProvider" …>`，
        //  本机 IU 2024.3.7.1 安装目录实测命中 1 处）；测试沙盒 classpath 不含 Database 插件故缺席。
        "com.intellij.database.modelRelationProvider",
        "com.intellij.sql.dialect",
    )

    // ------------------------------------------------------------------
    // 扫描面
    // ------------------------------------------------------------------

    private fun resource(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/$name")
            ?: error("资源缺失: META-INF/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    /** `<extensions>` 块 → (defaultExtensionNs, 直接子标签名列表)。 */
    private fun epTagsUsedIn(xml: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val block = Regex("<extensions\\b([^>]*)>(.*?)</extensions>", RegexOption.DOT_MATCHES_ALL)
        for (m in block.findAll(xml)) {
            val ns = Regex("""defaultExtensionNs\s*=\s*"([^"]+)"""").find(m.groupValues[1])
                ?.groupValues?.get(1) ?: "com.intellij"
            for (t in Regex("""(?m)^\s*<([a-zA-Z][\w.]*)[\s/>]""").findAll(m.groupValues[2])) {
                out += ns to t.groupValues[1]
            }
        }
        return out
    }

    /** EP 限定名规则：`<defaultExtensionNs>.<tag>`（tag 自带点号前缀时同样整体拼在 ns 之后）。 */
    private fun qualified(ns: String, tag: String): String = "$ns.$tag"

    /**
     * ① 扫描 classpath 上全部 jar 内的描述文件，收集平台声明的 EP 限定名。
     *
     * 裸 `name=` 的限定名 = 该描述文件所属插件的 id + `.` + name；描述文件自带 `<id>` 就用它，
     * 没有（核心插件的 `xi:include` 扩展点文件如 `PlatformExtensionPoints.xml`）按核心插件
     * `com.intellij` 还原。`qualifiedName=` 形态直接取字面值。
     */
    private fun declaredFromClasspath(): Set<String> {
        val out = mutableSetOf<String>()
        val cp = System.getProperty("java.class.path").orEmpty()
        for (entry in cp.split(File.pathSeparatorChar)) {
            if (!entry.endsWith(".jar", ignoreCase = true)) continue
            val f = File(entry)
            if (!f.isFile) continue
            try {
                ZipFile(f).use { z ->
                    val it = z.entries()
                    while (it.hasMoreElements()) {
                        val e = it.nextElement()
                        if (e.isDirectory || !e.name.endsWith(".xml")) continue
                        if (!e.name.contains("META-INF") && !e.name.contains("ExtensionPoints")) continue
                        val text = z.getInputStream(e).use { s -> s.readBytes().toString(Charsets.UTF_8) }
                        if (!text.contains("<extensionPoint")) continue
                        val pid = Regex("<id>([^<]+)</id>").find(text)?.groupValues?.get(1) ?: "com.intellij"
                        for (m in Regex("""<extensionPoint\b[^>]*?\bname\s*=\s*"([^"]+)"""").findAll(text)) {
                            out += "$pid.${m.groupValues[1]}"
                        }
                        for (m in Regex("""<extensionPoint\b[^>]*?\bqualifiedName\s*=\s*"([^"]+)"""").findAll(text)) {
                            out += m.groupValues[1]
                        }
                    }
                }
            } catch (_: Exception) {
                // 单个 jar 读失败不致命：全集靠多路并集兜底
            }
        }
        return out
    }

    /** ② 反射取已加载插件描述符声明的 EP（绕开 `PluginManagerCore.getPlugins()` 的 Kotlin 隐藏）。 */
    private fun declaredFromPluginManager(): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val pmc = Class.forName("com.intellij.ide.plugins.PluginManagerCore")
            val plugins = pmc.getMethod("getPlugins").invoke(null) as? Array<*> ?: return out
            val epDescCls = Class.forName("com.intellij.openapi.extensions.ExtensionPointDescriptor")
            val pluginDescCls = Class.forName("com.intellij.openapi.extensions.PluginDescriptor")
            val getEp = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptor")
                .getMethod("getExtensionPoints")
            val getQualified = epDescCls.getMethod("getQualifiedName", pluginDescCls)
            for (p in plugins) {
                if (p == null) continue
                @Suppress("UNCHECKED_CAST")
                val eps = getEp.invoke(p) as? List<*> ?: continue
                for (ep in eps) {
                    if (ep == null) continue
                    (getQualified.invoke(ep, p) as? String)?.let { out += it }
                }
            }
        } catch (_: Exception) {
            // 无 Application / API 漂移时不致命：全集靠 ① 兜底
        }
        return out
    }

    private fun declaredEpNames(): Set<String> = declaredFromClasspath() + declaredFromPluginManager()

    // ------------------------------------------------------------------
    // EP dynamic 属性（热更新可行性守卫，T-SettingsSearchA 追加）
    // ------------------------------------------------------------------

    /**
     * 平台 EP 声明 → 是否 `dynamic="true"`。
     *
     * **为什么这条决定热更新**：`DynamicInstallSupport` 的动态安装先过平台
     * `DynamicPlugins.checkCanUnloadWithoutRestart`，其首道判定是
     * `IdeaPluginDescriptorImpl.isRequireRestart()`；EP 的 dynamic 属性经
     * `ExtensionPointImpl.isDynamic` 参与该标志推导（243 字节码：`ExtensionPointImpl`
     * 持有 `private final boolean isDynamic`）。插件把注册落在**非 dynamic** 的 EP 点位上，
     * 整个插件就退化为「必须重启」——自更新静默回落启动脚本路径，用户感知为
     * 「点了更新但没热生效」。
     *
     * 2026-10-23 实测：本插件 57 个 EP 注册 0 违规（含新增的 `search.optionContributor`，
     * 平台声明处即 `dynamic="true"`）。本守卫防的是**将来**新增 EP 踩到非 dynamic 点位。
     */
    private fun dynamicFlagsFromClasspath(): Map<String, Boolean> {
        val out = mutableMapOf<String, Boolean>()
        val cp = System.getProperty("java.class.path").orEmpty()
        for (entry in cp.split(File.pathSeparatorChar)) {
            if (!entry.endsWith(".jar", ignoreCase = true)) continue
            val f = File(entry)
            if (!f.isFile) continue
            try {
                ZipFile(f).use { z ->
                    val it = z.entries()
                    while (it.hasMoreElements()) {
                        val e = it.nextElement()
                        if (e.isDirectory || !e.name.endsWith(".xml")) continue
                        if (!e.name.contains("META-INF") && !e.name.contains("ExtensionPoints")) continue
                        val text = z.getInputStream(e).use { s -> s.readBytes().toString(Charsets.UTF_8) }
                        if (!text.contains("<extensionPoint")) continue
                        val pid = Regex("<id>([^<]+)</id>").find(text)?.groupValues?.get(1) ?: "com.intellij"
                        for (m in Regex("""<extensionPoint\b[^>]*>""").findAll(text)) {
                            val attrs = m.value
                            val dyn = Regex("""\bdynamic\s*=\s*"true"""").containsMatchIn(attrs)
                            Regex("""\bname\s*=\s*"([^"]+)"""").find(attrs)?.let {
                                out.putIfAbsent("$pid.${it.groupValues[1]}", dyn)
                            }
                            Regex("""\bqualifiedName\s*=\s*"([^"]+)"""").find(attrs)?.let {
                                out.putIfAbsent(it.groupValues[1], dyn)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // 单个 jar 读失败不致命：多路并集兜底
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `全部注册的EP名均可解析`() {
        val declared = declaredEpNames()
        assertTrue(
            "平台 EP 全集仅 ${declared.size} 条——扫描面退化（classpath 未含平台 jar 且 " +
                "PluginManagerCore 不可用），守卫会空绿，先修扫描面",
            declared.size > 100,
        )

        val unresolved = mutableListOf<String>()
        for (name in xmlNames) {
            for ((ns, tag) in epTagsUsedIn(resource(name)).distinct()) {
                val q = qualified(ns, tag)
                if (q in declared || q in externalEps) continue
                unresolved += "$name: <$tag> → $q"
            }
        }
        assertTrue(
            "以下 EP 名解析不到（平台会静默忽略该注册段 = 死代码；请核对限定名——" +
                "例如 syntaxHighlighterFactory 的真名是 lang.syntaxHighlighterFactory）：\n" +
                unresolved.joinToString("\n") { "  - $it" },
            unresolved.isEmpty(),
        )
    }

    /**
     * 本类判据自证：合成 XML 里「真名」须通过、「错名」须被点出——否则提取器 / 限定名还原 /
     * 声明全集任一处退化，主用例都会空绿。
     */
    @Test
    fun `自证_合成XML错名必被点出`() {
        val declared = declaredEpNames()
        val good = """<extensions defaultExtensionNs="com.intellij">
            <lang.syntaxHighlighterFactory language="MpSqlMapper" implementationClass="X"/>
        </extensions>"""
        val bad = """<extensions defaultExtensionNs="com.intellij">
            <syntaxHighlighterFactory language="MpSqlMapper" implementationClass="X"/>
        </extensions>"""
        // 非默认命名空间的块（Kotlin 插件 EP 的注册形态）
        val kotlinNs = """<extensions defaultExtensionNs="org.jetbrains.kotlin">
            <supportsKotlinPluginMode supportsK2="true"/>
        </extensions>"""

        assertTrue(
            "提取器须认出带点号的 EP 写法，实际 ${epTagsUsedIn(good)}",
            epTagsUsedIn(good) == listOf("com.intellij" to "lang.syntaxHighlighterFactory"),
        )
        assertTrue(
            "提取器须认出裸名写法，实际 ${epTagsUsedIn(bad)}",
            epTagsUsedIn(bad) == listOf("com.intellij" to "syntaxHighlighterFactory"),
        )
        assertTrue(
            "提取器须逐块读 defaultExtensionNs，实际 ${epTagsUsedIn(kotlinNs)}",
            epTagsUsedIn(kotlinNs) == listOf("org.jetbrains.kotlin" to "supportsKotlinPluginMode"),
        )

        assertTrue(
            "lang.syntaxHighlighterFactory 须能解析（此条红=平台 EP 全集取不到该扩展点，扫描面坏了）",
            qualified("com.intellij", "lang.syntaxHighlighterFactory") in declared,
        )
        assertTrue(
            "裸 syntaxHighlighterFactory 必须解析不到（这正是 20261012 那个真 bug 的形态）",
            qualified("com.intellij", "syntaxHighlighterFactory") !in declared,
        )
    }

    // ------------------------------------------------------------------
    // 热更新可行性：插件注册不得落在平台非 dynamic 的 EP 点位
    // ------------------------------------------------------------------

    /**
     * 插件每个 EP 注册都必须落在平台声明 `dynamic="true"` 的点位上。
     *
     * 违反的后果：`DynamicPlugins.checkCanUnloadWithoutRestart` 判
     * `IdeaPluginDescriptorImpl.isRequireRestart` 为真 → 自更新静默回落到「重启生效」，
     * 用户点了更新却看不到热生效（不是崩溃，是体验降级，所以更难被发现）。
     *
     * 平台侧查不到声明的注册跳过（外部插件 EP：`runConfigurationExtension` 属 com.intellij.java，
     * `supportsKotlinPluginMode` 属 Kotlin 插件，测试 classpath 里未必带全）——
     * 名字可解析性由 `全部注册的EP名均可解析` 单独负责，本条只管 dynamic 属性。
     */
    @Test
    fun `插件注册的EP均落在平台dynamic点位上`() {
        val flags = dynamicFlagsFromClasspath()
        assertTrue(
            "平台 EP dynamic 声明表仅 ${flags.size} 条——扫描面退化，守卫会空绿，先修扫描面",
            flags.size > 100,
        )
        assertTrue(
            "search.optionContributor 在平台应为 dynamic（本条红=扫描面取不到该 EP 或平台改了声明）",
            flags[qualified("com.intellij", "search.optionContributor")] == true,
        )

        val violations = mutableListOf<String>()
        for (name in xmlNames) {
            for ((ns, tag) in epTagsUsedIn(resource(name)).distinct()) {
                val q = qualified(ns, tag)
                val dynamic = flags[q] ?: continue   // 平台侧无声明：不归本条管
                if (!dynamic) violations += "$name: <$tag> → $q"
            }
        }
        assertTrue(
            "以下 EP 注册落在平台**非 dynamic** 点位上——插件将无法热更新（自更新会静默回落重启）：\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n修法：换到 dynamic 点位，或接受该能力需要重启并在发行说明中标注",
            violations.isEmpty(),
        )
    }

    /**
     * 自证：判定面必须**看得见**非 dynamic 形态。
     *
     * 拿平台里真实存在、且声明为非 dynamic 的 EP 做正对照——若 [dynamicFlagsFromClasspath]
     * 把所有 EP 都读成 true（解析退化），这里会红。
     */
    @Test
    fun `自证_dynamic判定能识别非dynamic点位`() {
        val flags = dynamicFlagsFromClasspath()
        val nonDynamic = flags.filterValues { !it }.keys
        assertTrue(
            "平台声明里应存在非 dynamic 的 EP（否则 dynamic 判定无判别力，主守卫是空绿）；" +
                "实际非 dynamic 数=${nonDynamic.size}",
            nonDynamic.isNotEmpty(),
        )
        assertTrue(
            "search.optionContributor 应为 dynamic（新增该 EP 的前提）",
            flags[qualified("com.intellij", "search.optionContributor")] == true,
        )
        println(
            "DYNAMIC-PROBE 平台 EP 声明=${flags.size}，其中非 dynamic=${nonDynamic.size}；" +
                "示例：" + nonDynamic.take(5).joinToString(),
        )
    }
}
