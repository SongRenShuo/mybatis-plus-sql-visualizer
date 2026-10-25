package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.ide.ui.search.SearchableOptionContributor
import com.intellij.ide.ui.search.SearchableOptionProcessor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.openapi.options.ex.ConfigurableWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-SettingsSearchA：插件设置进入 IDE 自带设置搜索索引。
 *
 * 覆盖三件事：
 *  - 数据源正确性：[PluginSettingsFolderConfigurable.ideSearchEntries] 四元组（label/tooltip/
 *    页显示名/EP id）逐条自洽，且与页内搜索清单同源同量；
 *  - Contributor 真跑：直接调 `processOptions`，用一个**记录型** processor 收全部
 *    `addOptions` 调用，断言条目数、参数顺序（text/hit/configurableId/displayName）与
 *    `path=null`/`applyStemming=false`；
 *  - 门二前提：注册进设置树的插件页面是 `ConfigurableWrapper`，它**实现
 *    `SearchableConfigurable` 且 getId() == EP id** —— 这条成立才使得「不用改 9 个类」
 *    的结论可靠（平台 `filterById` 正是按 `instanceof SearchableConfigurable` + `getId()`
 *    过滤命中，243 字节码实证）。
 *
 * 真机 `Settings` 搜索框的端到端命中（走 SearchableOptionsRegistrar 初始化 + 索引查询）
 * 依赖平台索引初始化，headless 下不稳定，按计划 §七 留 runIde 沙盒验收；本类覆盖
 * 「喂进去的东西对不对」这一层，端到端在计划 §九 记录为待验收项。
 */
class SettingsIdeSearchContributorTest : BasePlatformTestCase() {

    private companion object {
        const val ID_SQL_LOG = "cn.song.mybatisplus.visualizer.settings.sqlLog"
        const val ID_PROJECT = "cn.song.mybatisplus.visualizer.settings.project"
    }

    /** 一次 addOptions 调用的六参数快照。 */
    private data class RecordedCall(
        val text: String,
        val path: String?,
        val hit: String?,
        val configurableId: String,
        val configurableDisplayName: String?,
        val applyStemming: Boolean,
    )

    /** 记录型 processor：把每次 addOptions 的六参数原样存下来。 */
    private class RecordingProcessor : SearchableOptionProcessor() {
        val calls = mutableListOf<RecordedCall>()

        override fun addOptions(
            text: String,
            path: String?,
            hit: String?,
            configurableId: String,
            configurableDisplayName: String?,
            applyStemming: Boolean,
        ) {
            calls += RecordedCall(text, path, hit, configurableId, configurableDisplayName, applyStemming)
        }
    }

    // ------------------------------------------------------------------
    // 数据源
    // ------------------------------------------------------------------

    fun test索引条目四元组自洽且与页内搜索同源() {
        val entries = PluginSettingsFolderConfigurable.ideSearchEntries()
        val labels = PluginSettingsFolderConfigurable.registeredSearchLabelsForTest()
        assertEquals("两套搜索必须读同一份清单（条目数相等）", labels.size, entries.size)
        assertEquals("label 序列必须与页内搜索清单逐条对齐", labels, entries.map { it.label() })

        val ids = PluginSettingsFolderConfigurable.registeredTargetsForTest().map { it.first }.toSet()
        for (e in entries) {
            assertTrue("条目标签不应为空（${e.configurableId()}）", e.label().isNotBlank())
            assertTrue(
                "条目 id 必须是已登记的设置页 EP id：${e.configurableId()}（否则平台 filterById 映射不回节点）",
                e.configurableId() in ids,
            )
            assertTrue("页显示名不应为空（${e.configurableId()}）", e.pageDisplayName().isNotBlank())
            assertTrue(
                "页显示名不应退化成 bundle 键原文（键缺失）：${e.pageDisplayName()}",
                !e.pageDisplayName().startsWith("settings.") && !e.pageDisplayName().startsWith("configurable."),
            )
        }
    }

    /** 与 T-SettingsSearchB 的同源直接检验（计划 §七 第二条）：脱敏项在两套搜索里都在。 */
    fun test展示与导出脱敏条目在两套搜索同源命中() {
        val entries = PluginSettingsFolderConfigurable.ideSearchEntries()
        val mask = MpSqlVisualizerBundle.message("settings.display.mask")
        val hit = entries.firstOrNull { it.label() == mask }
        assertNotNull("IDE 搜索索引里应有「展示与导出脱敏」（页内搜索同源）", hit)
        assertEquals("该条目应指向 SQL 日志页", ID_SQL_LOG, hit!!.configurableId())
        assertTrue(
            "tooltip 应带脱敏说明（搜索结果里给用户上下文）",
            hit.tooltip().contains("***") || hit.tooltip().isNotBlank(),
        )
        // 用户报障的四关键词（zh）与英文等价词在 label/tooltip 上可命中
        for (kw in listOf("脱敏", "展示", "导出", "打码")) {
            assertTrue(
                "中文关键词「$kw」应能命中该条目（label=${hit.label()}）",
                hit.label().contains(kw) || hit.tooltip().contains(kw),
            )
        }
    }

    // ------------------------------------------------------------------
    // Contributor 真跑
    // ------------------------------------------------------------------

    fun testContributor喂入全部条目且参数形态正确() {
        val processor = RecordingProcessor()
        PluginSettingsSearchableOptionContributor().processOptions(processor)

        val entries = PluginSettingsFolderConfigurable.ideSearchEntries()
        assertEquals("应喂入全部索引条目", entries.size, processor.calls.size)
        assertTrue("索引条目应有相当数量（数据源退化？）", processor.calls.size >= 50)

        for ((i, call) in processor.calls.withIndex()) {
            val expected = entries[i]
            assertEquals("text 应取 searchLabel（第 $i 条）", expected.label(), call.text)
            assertEquals("configurableId 应取 EP id（第 $i 条）", expected.configurableId(), call.configurableId)
            assertEquals("displayName 应取页显示名（第 $i 条）", expected.pageDisplayName(), call.configurableDisplayName)
            assertEquals("hit 应取 tooltip（第 $i 条）", expected.tooltip(), call.hit)
            assertEquals(
                "path 传 null（平台自带 Contributor 同形；命中由 configurableId 定位页面）",
                null, call.path,
            )
            assertEquals("applyStemming=false（中文无词干化需求）", false, call.applyStemming)
        }
    }

    /** 每条都带非空 configurableId 且指向真实注册页——否则命中后平台映射不回节点（静默失效）。 */
    fun test每条目id都指向真实注册的EP() {
        val wrappers = allWrappers()
        val ids = wrappers.mapNotNull { it.extensionPoint.id }.toSet()
        for (e in PluginSettingsFolderConfigurable.ideSearchEntries()) {
            assertTrue(
                "条目 id 必须对应真实 EP（plugin.xml 漂移？）：${e.configurableId()} / ${e.label()}",
                e.configurableId() in ids,
            )
        }
        // 项目级页也是合法落点（同一批文案键，另一作用域）
        assertTrue("项目级设置页 id 应在注册表内", ID_PROJECT in ids)
    }

    // ------------------------------------------------------------------
    // 门二前提：包装层已满足 SearchableConfigurable（决定「不用改 9 个类」）
    // ------------------------------------------------------------------

    fun test设置页包装层实现SearchableConfigurable且id等于EPid() {
        val wrappers = allWrappers().filter { it.extensionPoint.id?.startsWith("cn.song.mybatisplus.visualizer.settings") == true }
        assertTrue("应取到插件设置页 wrapper（注册漂移？）", wrappers.size >= 8)
        for (w in wrappers) {
            assertTrue(
                "ConfigurableWrapper 应实现 SearchableConfigurable（平台 filterById 的类型闸门）：${w.extensionPoint.id}",
                w is SearchableConfigurable,
            )
            assertEquals(
                "包装层 getId() 应等于 EP id（平台据此把索引命中映射回树节点）：${w.extensionPoint.id}",
                w.extensionPoint.id,
                (w as SearchableConfigurable).getId(),
            )
        }
    }

    // ------------------------------------------------------------------
    // EP 注册形态
    // ------------------------------------------------------------------

    /**
     * EP 注册必须**是活的标签**，不能只是注释里的字样。
     *
     * 首版写成「原文包含 `<search.optionContributor`」——把注册注释掉后仍然绿（注释里还留着那串字），
     * 属典型空绿守卫。改为先剥 XML 注释再判定，并把「注释掉的注册」作为反向夹具钉进断言：
     * 剥注释后必须找到**真实**注册，且该注册的 implementation 指向本 Contributor。
     */
    fun testPluginXml注册了searchOptionContributor() {
        val raw = repoRoot().resolve("src/main/resources/META-INF/plugin.xml").readText()
        val live = raw.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        val registrations = Regex("""<search\.optionContributor\b[^>]*>""").findAll(live).map { it.value }.toList()
        assertTrue(
            "plugin.xml 应有活的 search.optionContributor 注册（注释里的不算；摘掉则索引为空 → IDE 设置搜索落空）",
            registrations.isNotEmpty(),
        )
        assertTrue(
            "注册应指向本插件的 Contributor：$registrations",
            registrations.any {
                it.contains("cn.song.mybatisplus.visualizer.config.PluginSettingsSearchableOptionContributor")
            },
        )
    }

    /**
     * 反向夹具正对照：证明上一条断言的判定面**看得见注释形态**。
     *
     * 若把「剥注释后找不到注册」写错（比如仍用原文匹配），这里构造的注释态会被判绿——
     * 本用例把它钉成必红，防守卫退化成空绿。
     */
    fun test剥注释判定能识别被注释掉的注册() {
        val commented = """
            <extensions defaultExtensionNs="com.intellij">
            <!-- <search.optionContributor implementation="cn.song.mybatisplus.visualizer.config.PluginSettingsSearchableOptionContributor"/> -->
            </extensions>
        """.trimIndent()
        val live = commented.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(
            "剥注释后不应再看到该注册（否则守卫对「注释掉」形态不可见=空绿）",
            Regex("""<search\.optionContributor\b[^>]*>""").findAll(live).none(),
        )
        // 反证原文匹配会误判为有注册——这正是首版假绿的成因
        assertTrue("原文匹配确实能命中注释里的字样（首版假绿成因留痕）", commented.contains("<search.optionContributor"))
    }

    /** EP 注册的类必须能实例化且是 Contributor 子类（类名写错=静默失效，平台只打日志）。 */
    fun testContributor类可实例化() {
        val cls = Class.forName("cn.song.mybatisplus.visualizer.config.PluginSettingsSearchableOptionContributor")
        assertTrue(
            "注册类应继承 SearchableOptionContributor",
            SearchableOptionContributor::class.java.isAssignableFrom(cls),
        )
        assertNotNull("应可实例化", cls.getDeclaredConstructor().newInstance())
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun allWrappers(): List<ConfigurableWrapper> {
        val group = ConfigurableExtensionPointUtil.getConfigurableGroup(project, true)
        val out = mutableListOf<ConfigurableWrapper>()
        fun walk(list: Array<Configurable>) {
            for (c in list) {
                if (c is ConfigurableWrapper) out.add(c)
                if (c is Configurable.Composite) walk(c.configurables)
            }
        }
        walk(group.configurables)
        return out
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根: ${System.getProperty("user.dir")}")
    }
}
