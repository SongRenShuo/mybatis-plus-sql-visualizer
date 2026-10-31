package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.testutil.FakeSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableGroup
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.openapi.options.ex.ConfigurableVisitor
import com.intellij.openapi.options.ex.ConfigurableWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import javax.swing.ListCellRenderer
import javax.swing.ListModel

/**
 * 设置页主页面（左侧树导航父节点）测试（F-44 续 · 补用例大波次）：此前 0 测试。
 * 覆盖：createComponent 列出全部 8 个子页面（搜索空）；搜索无匹配项时列表清空（rebuildList 过滤）。
 * 通过遍历取 JBList 与 SearchTextField，无需新增生产缝。
 * T-UN-20（T-UiNative）增补：行渲染器归一 SimpleColoredComponent 片段通道——组件类型断言
 * （JLabel-HTML/GridBag 通道不允许回潮）+ 双行语义断言（页名/描述行文本仍取 bundle 既有键）。
 */
class PluginSettingsFolderConfigurableTest : BasePlatformTestCase() {

    private companion object {
        const val ID_SQL_LOG_FOR_TEST = "cn.song.mybatisplus.visualizer.settings.sqlLog"
    }

    private fun all(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf(c)
        if (c is java.awt.Container) c.components.forEach { list.addAll(all(it)) }
        return list
    }

    /** 片段拼接文本（与 HistoryCellRendererTest 同款）。 */
    private fun joinedText(component: SimpleColoredComponent): String {
        val sb = StringBuilder()
        val it = component.iterator()
        while (it.hasNext()) sb.append(it.next())
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun rendererOf(list: JBList<*>): ListCellRenderer<Any?> =
        list.cellRenderer as ListCellRenderer<Any?>

    /** 渲染 value 并按 DFS 序取所有 SimpleColoredComponent 行（两行结构：[名称行, 描述行]）。 */
    private fun renderedLines(list: JBList<*>, value: Any?, selected: Boolean = false): List<SimpleColoredComponent> =
        all(rendererOf(list).getListCellRendererComponent(list, value, 0, selected, false))
            .filterIsInstance<SimpleColoredComponent>()

    private fun createList(): Pair<JBList<*>, SearchTextField> {
        val cfg = PluginSettingsFolderConfigurable()
        val comp = cfg.createComponent()
        val list = all(comp).filterIsInstance<JBList<*>>().first()
        val search = all(comp).filterIsInstance<SearchTextField>().first()
        return list to search
    }

    fun testCreateComponent_空搜索列出全部9个子页面() {
        val cfg = PluginSettingsFolderConfigurable()
        val comp = cfg.createComponent()
        val list = all(comp).filterIsInstance<JBList<*>>().first()
        assertEquals(
            "默认应列出 9 个子页面（主五页 + shortcuts/mcp + bannedApi + 外部模板，" +
                "T-ShortcutSettings/T-BannedApiCheck/T-TemplateHook）",
            9,
            (list.model as ListModel<*>).size,
        )
    }

    fun test搜索过滤_无匹配项时列表清空() {
        val cfg = PluginSettingsFolderConfigurable()
        val comp = cfg.createComponent()
        val list = all(comp).filterIsInstance<JBList<*>>().first()
        val search = all(comp).filterIsInstance<SearchTextField>().first()
        // 设置搜索文本触发 DocumentListener → rebuildList；EDT 上同步派发
        search.text = "zzzzz"
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("无匹配项时列表应为空", 0, (list.model as ListModel<*>).size)
    }

    // ---------- T-UN-20：落地页行渲染器归一 SimpleColoredComponent 片段通道 ----------

    fun test渲染器_子页面行_SimpleColoredComponent两行_页名加粗描述灰() {
        val (list, _) = createList()
        assertEquals("固定 56px 行高语义保持", 56, list.fixedCellHeight)
        val lines = renderedLines(list, list.model.getElementAt(0))
        assertEquals("双行结构（页名 + 描述）", 2, lines.size)
        // 语义断言：行文本仍取 bundle 既有键
        assertEquals("首行页名取 bundle 既有键", MpSqlVisualizerBundle.message("settings.section.ui"), joinedText(lines[0]))
        assertEquals("次行描述取 bundle 既有键", MpSqlVisualizerBundle.message("settings.section.ui.desc"), joinedText(lines[1]))
        // 着色语义：页名加粗、描述元信息灰
        val nameIter = lines[0].iterator(); nameIter.next()
        assertTrue("页名片段应加粗（原 Font.BOLD 语义）", nameIter.textAttributes.style and SimpleTextAttributes.STYLE_BOLD != 0)
        val descIter = lines[1].iterator(); descIter.next()
        assertTrue("描述片段不应加粗", descIter.textAttributes.style and SimpleTextAttributes.STYLE_BOLD == 0)
        assertEquals("描述片段用元信息灰（与日志/历史页同源）", MpvsUiColors.META_GRAY, descIter.textAttributes.fgColor)
        // 图标语义保持：行首图标非空
        val icon = all(rendererOf(list).getListCellRendererComponent(list, list.model.getElementAt(0), 0, false, false))
            .filterIsInstance<javax.swing.JLabel>().firstOrNull()?.icon
        assertTrue("子页面行应带页图标", icon != null)
    }

    fun test渲染器_选中态走平台选中填充_不自绘() {
        val (list, _) = createList()
        val selected = rendererOf(list).getListCellRendererComponent(list, list.model.getElementAt(0), 0, true, false)
        assertEquals("选中背景取列表选中色（平台选中填充）", list.selectionBackground, selected.background)
        val lines = renderedLines(list, list.model.getElementAt(0), selected = true)
        assertEquals("选中态仍为双行", 2, lines.size)
        val descIter = lines[1].iterator(); descIter.next()
        assertEquals("选中态描述随 selectionForeground（不再固定灰）", null, descIter.textAttributes.fgColor)
    }

    fun test渲染器_设置项行_字段名加粗与所属页面() {
        val (list, search) = createList()
        // 搜索命中设置项字段（FieldItem）：字段名在上、所属页面在下
        val fieldLabel = MpSqlVisualizerBundle.message("settings.dialect")
        search.text = fieldLabel
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("搜索应命中设置项", list.model.size > 0)
        // T-ProjectSettingsScope P3 增补：项目页同项文案入索引后，同一查询会命中**多条同名字段行**
        // （全局方言 + 项目方言）。渲染器是共享实例（nameLine/descLine 复用），逐条渲染后再断言
        // 会让所有行都指向最后渲染的那一份——故改为「先定位首个命中项的下标，再重渲染该项」，
        // 断言面与原意一致（字段名行加粗、次行为所属页面名）。
        val matchedIndex = (0 until list.model.size).firstOrNull { idx ->
            val lines = renderedLines(list, list.model.getElementAt(idx))
            lines.size == 2 && joinedText(lines[0]) == fieldLabel
        }
        assertTrue("应渲染出设置项行", matchedIndex != null)
        val lines = renderedLines(list, list.model.getElementAt(matchedIndex!!))
        assertEquals("设置项次行应为所属页面名", MpSqlVisualizerBundle.message("settings.section.preview"), joinedText(lines[1]))
        val fieldIter = lines[0].iterator(); fieldIter.next()
        assertTrue("字段名片段应加粗", fieldIter.textAttributes.style and SimpleTextAttributes.STYLE_BOLD != 0)
    }

    // ---------- N5：对话框内导航按 EP id 查（按类查会全树实例化 → EDT 慢操作） ----------

    private fun settingsGroups(): List<ConfigurableGroup> =
        listOf(ConfigurableExtensionPointUtil.getConfigurableGroup(project, true))

    /** 树里全部 wrapper（只读 EP 元数据，不触发 configurable 实例化）。 */
    private fun allWrappers(groups: List<ConfigurableGroup>): List<ConfigurableWrapper> {
        val out = mutableListOf<ConfigurableWrapper>()
        fun walk(list: Array<Configurable>) {
            for (c in list) {
                if (c is ConfigurableWrapper) out.add(c)
                if (c is Configurable.Composite) walk(c.configurables)
            }
        }
        groups.forEach { walk(it.configurables) }
        return out
    }

    private fun wrapperWithId(groups: List<ConfigurableGroup>, id: String): ConfigurableWrapper? =
        allWrappers(groups).firstOrNull { it.extensionPoint.id == id }

    /** 登记目标类 → EP id（经生产侧测试缝，避免用例里再抄一份 id 字面量）。 */
    private fun idOf(cls: Class<out Configurable>): String =
        PluginSettingsFolderConfigurable.registeredTargetsForTest().first { it.second == cls }.first

    /**
     * N5 判别性用例：对话框内按 id 定位**不得**顺带实例化兄弟页。
     *
     * 探针用 `ConfigurableWrapper.getRawConfigurable()`——它只回读缓存实例、不触发创建，
     * 所以「null = 没被实例化」是可信读数（正对照见 [test按类查会实例化全树_正对照]）。
     */
    fun test导航按id查_不实例化无关设置页() {
        val cfg = PluginSettingsFolderConfigurable()
        val groups = settingsGroups()
        val sqlLogId = idOf(SqlLogConfigurable::class.java)
        val uiId = idOf(UiActionsConfigurable::class.java)

        val target = cfg.resolveTarget(FakeSettings(groups), sqlLogId)
        assertNotNull("按 id 应命中目标页", target)

        val targetWrapper = wrapperWithId(groups, sqlLogId)
        assertNotNull("设置树里应有目标页 wrapper", targetWrapper)
        assertNotNull("命中后目标页应被实例化（否则等于没跳）", targetWrapper!!.rawConfigurable)

        val sibling = wrapperWithId(groups, uiId)
        assertNotNull("设置树里应有兄弟页 wrapper", sibling)
        assertNull(
            "按 id 查不得实例化无关设置页——按类查（ConfigurableVisitor.findByType）会实例化全树",
            sibling!!.rawConfigurable,
        )

        // 真机报告的触发点：Kotlin 插件的 scripting 设置页构造器里做文件名索引访问
        // （KotlinScriptingSettingsConfigurable.java:46 → FilenameIndex），修复前被按类查顺带实例化
        // → EDT 慢操作断言。它声明了 EP id（preferences.language.Kotlin.scripting），故按 id 查不再碰它。
        val kotlinScripting = allWrappers(groups).firstOrNull {
            epClassName(it).contains("KotlinScriptingSettingsConfigurable")
        }
        assertNotNull("夹具树里应含 Kotlin scripting 设置页（真机触发点；缺则本断言无判别力）", kotlinScripting)
        assertNull(
            "按 id 查不得实例化 Kotlin scripting 设置页（真机 EDT 慢操作触发点）",
            kotlinScripting!!.rawConfigurable,
        )
    }

    /**
     * N5 正对照：证明上面的探针**看得见实例化**（否则「兄弟页没被实例化」是空绿）。
     *
     * 按类查是修复前的形态：`ConfigurableWrapper.cast` 里那条「类型不相容就不创建」的短路，
     * 因 `ConfigurableEP.ClassProducer` 未覆写 `ObjectProducer.getType()`（恒 null）而成死代码。
     */
    fun test按类查会实例化全树_正对照() {
        val groups = settingsGroups()
        ConfigurableVisitor.findByType(UiActionsConfigurable::class.java, groups)
        val wrapper = wrapperWithId(groups, idOf(UiActionsConfigurable::class.java))
        assertNotNull("设置树里应有该页 wrapper", wrapper)
        assertNotNull("正对照：按类查应实例化该页（探针有效）", wrapper!!.rawConfigurable)
    }

    /**
     * N5 一致性守卫：每个登记目标的 id 必须与类指向**同一个** EP。
     *
     * 导航改成按 id 查之后，id 写错（或与 `plugin.xml` 漂移）会静默跳到别处/跳不动——纯 UI 断言
     * 看不出来（本仓「幽灵搜索项」的同类风险）。比对走 EP 元数据，不实例化。
     */
    fun test登记目标id与类指向同一设置页() {
        val wrappers = allWrappers(settingsGroups())
        val targets = PluginSettingsFolderConfigurable.registeredTargetsForTest()
        assertEquals("登记目标数（9 个子页 + 项目页）", 10, targets.size)
        for ((id, cls) in targets) {
            val wrapper = wrappers.firstOrNull { it.extensionPoint.id == id }
            assertNotNull("设置树里找不到该 id 的 EP：$id（id 与 plugin.xml 漂移？）", wrapper)
            val declared = wrapper!!.extensionPoint.instanceClass ?: wrapper.extensionPoint.implementationClass
            assertEquals("id 指向的 EP 类与登记类不一致：$id", cls.name, declared)
        }
    }

    /**
     * N5 待验证前提 P1 探针：按 id 查时 `ConfigurableWrapper.getId()` 对**未声明 id** 的 wrapper
     * 会退化成 `cast(SearchableConfigurable.class, this)` → 仍然实例化。故「按 id 查是否真的不碰
     * 无关页」取决于树里有没有无 id 的 EP——本探针把这个事实打出来。
     *
     * 夹具树 ≠ 真机全树（真机结论以计划 §六 验收为准），此处只作读数留痕。
     */
    fun test探针_设置树内无id的EP清单() {
        val wrappers = allWrappers(settingsGroups())
        assertTrue("设置树不应为空", wrappers.isNotEmpty())
        val withoutId = wrappers.filter { it.extensionPoint.id == null }
        println(
            "N5-PROBE 设置树 wrapper=${wrappers.size}，无 id 的=${withoutId.size}；" +
                "前 10 个：" + withoutId.take(10).joinToString { epClassName(it) },
        )
        val kotlinScripting = wrappers.firstOrNull { epClassName(it).contains("KotlinScriptingSettingsConfigurable") }
        println(
            "N5-PROBE KotlinScriptingSettingsConfigurable 在夹具树里=${kotlinScripting != null}，" +
                "EP id=${kotlinScripting?.extensionPoint?.id}",
        )
    }

    // ------------------------------------------------------------------
    // T-SettingsFocusFromLink：待定位目标 + hint 定位哨兵
    // ------------------------------------------------------------------

    fun test待定位目标置与消费_消费即清() {
        PluginSettingsFolderConfigurable.requestFocusOnOpenForTest(ID_SQL_LOG_FOR_TEST, "hint-x")
        val got = PluginSettingsFolderConfigurable.consumePendingFocus()
        assertNotNull("置入后应可消费", got)
        assertEquals(ID_SQL_LOG_FOR_TEST, got!!.pageId)
        assertEquals("hint-x", got.hint)
        assertNull("消费即清（防重复定位）", PluginSettingsFolderConfigurable.consumePendingFocus())
    }

    /**
     * P2 哨兵（防「跳了但不亮」）：三处引导链接的 hint 必须能在 **SQL 日志页真实面板**中定位到控件。
     * hint 一律取 bundle 当前语言完整值（与设置行渲染同源）——硬编码中文在英文环境下必然定位失败。
     */
    fun test三处链接hint在SQL日志页可定位() {
        val root = SqlLogConfigurable().createComponent()
        assertNotNull("SQL 日志设置页应可创建面板", root)
        val folder = PluginSettingsFolderConfigurable()
        for (key in listOf(
            "settings.enhancedCapture",
            "settings.log.autoInjectInterceptor",
            "settings.log.autoSave",
        )) {
            val hint = MpSqlVisualizerBundle.message(key)
            assertNotNull(
                "hint「$hint」（键 $key）应能定位到设置控件（否则用户点链接「跳了但不亮」）",
                folder.findHighlightTargetForTest(root, hint),
            )
        }
    }

    private fun epClassName(wrapper: ConfigurableWrapper): String {
        val ep = wrapper.extensionPoint
        return ep.instanceClass ?: ep.implementationClass ?: ep.providerClass ?: "?"
    }
}
