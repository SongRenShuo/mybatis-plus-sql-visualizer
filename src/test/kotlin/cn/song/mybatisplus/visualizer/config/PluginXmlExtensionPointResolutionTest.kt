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
}
