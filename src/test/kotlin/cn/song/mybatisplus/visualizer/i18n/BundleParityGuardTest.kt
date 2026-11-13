package cn.song.mybatisplus.visualizer.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * bundle 完整性守卫（T-TestGuard-01）：历史两案的结构性防线——
 *  - m41 工具栏 i18n（docs/memory-archive/bug-fixes/m41-toolbar-i18n-cleanup.md）：中文 bundle
 *    缺键静默回退英文，运行时无报错、只有真机切语言才可见 → 中英 key 集合必须全等；
 *  - feedback-bundle-param-placeholder（docs/memory-archive/pitfalls/feedback-bundle-param-placeholder.md）：
 *    bundle 占位符是 MessageFormat 的 `{0}/{1}`，自定义 `__URL__` 不被替换原样上屏 → 值中的
 *    占位符必须全部为 `{数字}` 形态；
 *  - 源码/plugin.xml 引用的字面量 key 必须存在于 bundle（拼错键时 message() 兜底返回 key
 *    本身不抛错，肉眼极难发现）。
 *
 * 纯逻辑测试（零平台依赖）：直接按 UTF-8 读 classpath 与 src/main 源码。
 */
class BundleParityGuardTest {

    // ------------------------------------------------------------------
    // 资源装载
    // ------------------------------------------------------------------

    private fun loadBundle(suffix: String): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream("messages/MpSqlVisualizerBundle$suffix.properties")
            ?: error("bundle 资源缺失: messages/MpSqlVisualizerBundle$suffix.properties")
        val props = Properties()
        stream.use { props.load(it.reader(Charsets.UTF_8)) }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    private fun enBundle() = loadBundle("")
    private fun zhBundle() = loadBundle("_zh")

    /** 从 user.dir 逐级上溯定位仓库根（含 src/main/kotlin 的目录）。 */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun mainSources(): List<File> =
        repoRoot().resolve("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun metaInfXml(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/$name")
            ?: error("资源缺失: META-INF/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    // ------------------------------------------------------------------
    // 守卫 1：中英 key 全等（m41 案）
    // ------------------------------------------------------------------

    @Test
    fun `中英bundle_key集合全等_缺键清单报告`() {
        val en = enBundle().keys
        val zh = zhBundle().keys
        val missingInZh = (en - zh).sorted()
        val missingInEn = (zh - en).sorted()
        assertTrue(
            "中文 bundle 缺键（运行时静默回退英文，用户切中文看不到）：$missingInZh",
            missingInZh.isEmpty(),
        )
        assertTrue(
            "英文 bundle 缺键（切英文显示中文兜底）：$missingInEn",
            missingInEn.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 守卫 2：占位符必须为 MessageFormat `{数字}` 形态（__URL__ 案）
    // ------------------------------------------------------------------

    private val anyPlaceholder = Regex("""\{([^{}]*)\}""")
    private val customPlaceholder = Regex("""__[A-Za-z0-9]+__""")
    private val numericPlaceholder = Regex("""\{\d+\}""")

    @Test
    fun `占位符守卫_禁自定义形态_模板值禁混入非数字占位符`() {
        // 规则一（坑档原始形态）：__URL__ 式自定义占位符永不被 MessageFormat 替换，任何值禁用。
        // 规则二：值含 {n} 即视为 MessageFormat 模板，此时混入非数字 {xxx} 会在 format 期
        // 抛异常或原样上屏——模板值必须全数字占位。纯静态提示文案里的花括号示例
        // （如 settings.logicDeleteValue.tip 的 "AND deleted = {value}"、导入弹窗的 JSON 示例）
        // 不经 MessageFormat 渲染，属合法展示文本，不在规则内。
        for ((name, bundle) in listOf("en" to enBundle(), "zh" to zhBundle())) {
            val bad = mutableListOf<String>()
            for ((key, value) in bundle) {
                if (customPlaceholder.containsMatchIn(value)) bad += "$key -> __X__ 自定义占位符"
                if (numericPlaceholder.containsMatchIn(value)) {
                    val mixed = anyPlaceholder.findAll(value)
                        .map { it.groupValues[1] }
                        .filter { it.toIntOrNull() == null }
                        .toList()
                    if (mixed.isNotEmpty()) bad += "$key -> 模板混入非数字占位符 $mixed"
                }
            }
            assertTrue("$name bundle 占位符违规：${bad.sorted()}", bad.isEmpty())
        }
    }

    // ------------------------------------------------------------------
    // 守卫 3：源码与 plugin.xml 引用的字面量 key 必须存在
    // ------------------------------------------------------------------

    @Test
    fun `源码message调用的字面量key必须存在于bundle`() {
        val known = enBundle().keys + zhBundle().keys
        val callSite = Regex("""MpSqlVisualizerBundle\.message\(\s*"([^"]+)"""")
        val missing = mainSources()
            .flatMap { file -> callSite.findAll(file.readText()).map { file.name to it.groupValues[1] } }
            // 含 $ 的引用是 Kotlin 模板动态拼 key（feedback.category.$slug 等），静态扫描不可判定，排除
            .filter { (_, key) -> !key.contains('$') && key !in known }
            .map { (file, key) -> "$file: \"$key\"" }
            .sorted()
        assertTrue(
            "源码引用的 bundle key 不存在（message() 对缺键兜底返回 key 原文，UI 会直接显示键名）：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `pluginXml与optionalXml引用的key必须存在于bundle`() {
        val known = enBundle().keys + zhBundle().keys
        val xmls = listOf("plugin.xml", "kotlin-optional.xml", "database-optional.xml", "diagram-optional.xml")
            .map { it to metaInfXml(it) }
        // key="..."（configurable/notificationGroup）与 action 的 text=/description=（resource-bundle 约定即 key）
        val attrRefs = Regex("""(?:key|text|description)="([^"]+)"""")
        // `com.intellij.registryKey` 是例外族（T-StartupReadLockFreeze `sqlvis.mbg.prefetch` 首用）：
        //   - key＝注册表标识符，按平台约定是 `a.b.c` 形态，**不是** bundle 键；
        //   - description＝平台 Registry UI 的纯英文说明，平台不做 `%key%` 替换。
        // 两者都不适用「属性即 bundle 键」的约定，故整元素排除（只此一类，别扩面）。
        val registryKeyElement = Regex("""<registryKey\b[^>]*/>""")
        val missing = xmls
            .flatMap { (name, rawXml) ->
                attrRefs.findAll(registryKeyElement.replace(rawXml, "")).map { name to it.groupValues[1] }
            }
            .filter { (_, key) -> key.contains('.') && key !in known }
            .map { (name, key) -> "$name: \"$key\"" }
            .sorted()
        assertTrue("plugin.xml 引用的 bundle key 不存在：$missing", missing.isEmpty())
    }

    // ------------------------------------------------------------------
    // 兜底健全性：两 bundle 非空
    // ------------------------------------------------------------------

    @Test
    fun `两份bundle均非空`() {
        assertTrue("英文 bundle 不应为空", enBundle().isNotEmpty())
        assertEquals("中英 bundle 键数量应一致", enBundle().size, zhBundle().size)
    }
}
