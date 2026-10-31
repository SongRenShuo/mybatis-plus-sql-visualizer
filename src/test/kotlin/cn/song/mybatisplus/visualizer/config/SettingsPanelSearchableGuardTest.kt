package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import java.io.File
import javax.swing.JLabel
import javax.swing.JToggleButton

/**
 * T-SettingsSearchB 防复发守卫：设置页「面板上有控件」与「搜索索引里有条目」必须配对。
 *
 * 背景（docs/开发计划/20261023-1304-T-SettingsSearchB）：`buildSearchableFields()` 是手写
 * 硬编码清单，每次往面板加控件都得记得同文件补登记，且无机制拦住漏加——`cb55e546`
 * （T-SettingsTruth P3）刚补录 13 项，本轮 `settings.display.mask`（展示与导出脱敏）又漏，
 * 用户搜「脱敏/展示/导出/打码」全部翻不到。本守卫把两件事绑死：
 *
 *  - 断言一（本项重点）：拿**真实面板**（`PluginSettingsPanel(settings).getRoot()`，非手搓
 *    组件树——合成样本只能证明规则看得见「我造的形态」）逐一遍历 JToggleButton/JLabel 的
 *    可见文本，每个非纯说明性文本必须能在搜索索引（`registeredSearchLabelsForTest()`）
 *    里配对，或在显式白名单里（逐条写理由）。zh/en 两种语言都扫（文本按**盘上双语 bundle**
 *    反解回 key 再比对，语言无关——companion 的索引清单在类首次触达时按当刻语言冻结，
 *    直接比对文本会在同 JVM 里被测试顺序打翻）。
 *  - 断言二：用户报障的四关键词（脱敏/展示/导出/打码）在搜索框里都能命中
 *    「展示与导出脱敏」条目（真实 rebuildList 链路）。
 *  - 断言三：该条目点击跳转的 hint（=label 文本）能在 SQL 日志页**真实面板**定位到控件
 *    （防「跳了但不亮」，复用 findHighlightTargetForTest 测试缝）；zh/en 双语路径都走。
 *  - 断言四：英文文案的数据层关键词（mask/display/export/sensitive）在 label 或 tip 上
 *    可命中（bundle base 即英文文件，en 面板文本与之同源）。
 *
 * **正反验证纪律**：本守卫的反向验证＝临时删掉 `buildSearchableFields()` 里
 * `settings.display.mask` 那条登记 → 断言一必须变红；恢复后回到全绿。正反两次读数
 * 留存在计划 §实施记录。空绿守卫（规则看不见真实形态）不算验证过。
 *
 * 与既有守卫的分工：SettingsRealityGuardTest 断言四是反方向（bundle 键必须被源码引用，
 * 防「键没人用」幽灵文案）；本守卫拦「控件没人搜」（文本没进索引）。互补不重叠。
 */
class SettingsPanelSearchableGuardTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 断言一：真实面板文本 ↔ 搜索索引配对（守卫主体）
    // ------------------------------------------------------------------

    fun test面板输入型文本均能在搜索索引命中或列白名单() {
        val settings = PluginSettings.getInstance()
        val original = settings.state.language
        try {
            // 索引清单在类首次触达时按当刻语言冻结——先回 zh 再取测试缝，与全测试套
            // 「默认中文」纪律一致（渲染类用例同样依赖索引语言=zh）
            settings.state.language = "zh"
            val indexLabels = PluginSettingsFolderConfigurable.registeredSearchLabelsForTest()
            assertTrue("搜索索引应非空（测试缝失效？）", indexLabels.isNotEmpty())
            val indexKeys = indexLabels.mapNotNull { textToKeys[it] }.flatten().toSet()
            // 索引侧反解不出 key 的条目说明盘上 bundle 与运行期文案漂移，本身就是异常
            val unresolvable = indexLabels.filter { textToKeys[it] == null }
            assertTrue(
                "索引 label 应能经盘上双语 bundle 反解回 key（漂移条目：$unresolvable）",
                unresolvable.isEmpty(),
            )

            val violations = mutableListOf<String>()
            for (lang in listOf("zh", "en")) {
                settings.state.language = lang
                val panel = PluginSettingsPanel(settings)
                UIUtil.dispatchAllInvocationEvents()
                val dynamic = dynamicExemptComponents(panel)
                val collected = mutableListOf<Pair<Component, String>>()
                collectTexts(panel.getRoot(), collected)
                // 面板应扫出足量文本（扫描面/组件树遍历失效→空绿的哨兵）
                assertTrue("[$lang] 真实面板应扫出输入型文本（>=40 条）", collected.size >= 40)
                for ((comp, text) in collected) {
                    if (dynamic.any { it === comp || isDescendantOf(comp, it) }) continue
                    val where = generateSequence(comp.parent) { it.parent }
                        .take(4).joinToString(" < ") { it.javaClass.simpleName }
                    val keys = textToKeys[text]
                    if (keys == null) {
                        violations += "[$lang] 文案无法回解 bundle 键（动态文本未列组件豁免？）: \"$text\" (${comp.javaClass.simpleName} in $where)"
                        continue
                    }
                    if (keys.none { it in indexKeys || it in whitelistKeys() }) {
                        violations += "[$lang] 面板文本不在搜索索引且未列白名单: \"$text\" (key=${keys.joinToString()} @ $where)"
                    }
                }
            }
            assertTrue(
                "面板存在搜不到的设置文本（往面板加控件必须同步在 buildSearchableFields() 补登记，" +
                    "或以逐条理由进白名单）：\n" + violations.joinToString("\n"),
                violations.isEmpty(),
            )
        } finally {
            settings.state.language = original
        }
    }

    // ------------------------------------------------------------------
    // 断言二：用户报障的四关键词经真实搜索链路命中
    // ------------------------------------------------------------------

    fun test展示与导出脱敏四关键词可搜索命中() {
        val settings = PluginSettings.getInstance()
        val original = settings.state.language
        try {
            settings.state.language = "zh"
            val cfg = PluginSettingsFolderConfigurable()
            val comp = cfg.createComponent()
            val list = allComponents(comp).filterIsInstance<JBList<*>>().first()
            val search = allComponents(comp).filterIsInstance<SearchTextField>().first()
            val expected = MpSqlVisualizerBundle.message("settings.display.mask")
            for (kw in listOf("脱敏", "展示", "导出", "打码")) {
                search.text = kw
                UIUtil.dispatchAllInvocationEvents()
                val hit = (0 until list.model.size).any { idx ->
                    val lines = renderedLines(list, list.model.getElementAt(idx))
                    lines.isNotEmpty() && joinedText(lines[0]) == expected
                }
                assertTrue("关键词「$kw」应命中「展示与导出脱敏」条目（FieldItem 首行=预期文案）", hit)
            }
            search.text = ""
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            settings.state.language = original
        }
    }

    // ------------------------------------------------------------------
    // 断言三：搜索条目跳转的 hint 能在 SQL 日志页真实面板定位到控件
    // ------------------------------------------------------------------

    fun test展示与导出脱敏hint在SQL日志页真实面板可定位() {
        val settings = PluginSettings.getInstance()
        val original = settings.state.language
        try {
            settings.state.language = "zh"
            val folder = PluginSettingsFolderConfigurable()
            for (lang in listOf("zh", "en")) {
                settings.state.language = lang
                val root = SqlLogConfigurable().createComponent()
                // T-MagpieGems W2：复选框改分级下拉，settings.display.mask 文本落在模式行的行标签上
                val hint = MpSqlVisualizerBundle.message("settings.display.mask")
                val target = folder.findHighlightTargetForTest(root, hint)
                assertNotNull("[$lang] hint「$hint」应定位到 SQL 日志页控件（定位不到=用户「跳了但不亮」）", target)
                assertTrue(
                    "[$lang] 定位目标应是模式行行标签（displayMaskModeCombo 的 label）",
                    target is javax.swing.JLabel,
                )
                // 白名单行同理可定位（W2 新增条目，防「跳了不亮」）
                val allowHint = MpSqlVisualizerBundle.message("settings.display.mask.allowList")
                val allowTarget = folder.findHighlightTargetForTest(root, allowHint)
                assertNotNull("[$lang] hint「$allowHint」应定位到白名单行控件", allowTarget)
                assertTrue("[$lang] 白名单定位目标应是行标签", allowTarget is javax.swing.JLabel)
            }
        } finally {
            settings.state.language = original
        }
    }

    // ------------------------------------------------------------------
    // 断言四：英文文案的数据层关键词可命中（en 面板文本与 bundle base 同源）
    // ------------------------------------------------------------------

    fun test英文文案关键词数据层可命中() {
        // zh 四关键词（脱敏/展示/导出/打码）的英文用户等价词；label 或 tooltip 命中即算
        // （搜索判定 rebuildList 同口径：label.contains(q) || tooltip.contains(q)）
        for (kw in listOf("mask", "display", "export", "sensitive")) {
            val label = MpSqlVisualizerBundle.messageEn("settings.display.mask")
            val tip = MpSqlVisualizerBundle.messageEn("settings.display.mask.tip")
            assertTrue(
                "英文关键词「$kw」应在 label/tip 上可命中（label=$label）",
                label.lowercase().contains(kw) || tip.lowercase().contains(kw),
            )
        }
    }

    // ------------------------------------------------------------------
    // 白名单（豁免逐条留痕；四类见各集合注释。**新增开关禁入④**——④是既有缺口台账）
    // ------------------------------------------------------------------

    private fun whitelistKeys(): Set<String> = GROUP_TITLE_KEYS + DISCLOSURE_KEYS + ROW_LABEL_KEYS + UNINDEXED_SWITCH_KEYS

    /** ① 页内分组标题（TitledSeparator 内嵌 label）：纯视觉分节，不是设置项。 */
    private val GROUP_TITLE_KEYS = setOf(
        "settings.section.preview", "settings.section.tableMapping", "settings.section.codeGen",
        "settings.log.tab.mybatis", "settings.log.tab.list", "settings.log.tab.crudColor",
        "settings.log.tab.orm", "settings.log.tab.autoSave", "settings.log.tab.sourceCapture",
        "settings.section.ui", "settings.section.shortcuts", "settings.section.updateDiagnostics",
    )

    /** ② 说明/披露/链接行（R20/隐私披露/存储边界说明）：无自身状态值，不可被当作设置项搜出。 */
    private val DISCLOSURE_KEYS = setOf(
        "settings.sourceFrameSkipPrefixes.hint",
        "settings.storage.captureFiles", "settings.storage.previewHistory",
        "settings.storage.paramHistory", "settings.storage.clearBoundary",
        "settings.update.changelog", "settings.update.viewOnGitee", "settings.update.feedbackPool",
        // R62 兜底链接行（通道 404 态才可见的 Gitee/Marketplace 外链）
        "settings.update.fallback.marketplace", "settings.update.fallback.gitee",
    )

    /**
     * ③ 组合控件的行标签：为行内主控件或单选组作标签——主控件自身已在索引
     * （如 crudColor 的样式下拉、autoSave 三件），标签文本不再重复登记。
     */
    private val ROW_LABEL_KEYS = setOf(
        "settings.emptyParamFill",           // 三档单选组的组行标签（选项见④）
        "settings.preview.executionTimeout", // 执行超时输入行标签
        "settings.log.crudColor.select", "settings.log.crudColor.insert",
        "settings.log.crudColor.update", "settings.log.crudColor.delete", // 色板行标签（样式下拉已索引）
        "settings.enhancedCapture.stackMaxFrames", // 栈帧数输入行标签
        "settings.pluginSettings",           // 导入/导出按钮行的行标签
        "settings.shortcut.explain", "settings.shortcut.count", "settings.shortcut.sample", // 快捷键行标签（shortcut/sqlToJava 已索引）
    )

    /**
     * ④ 既有未索引的真实开关/单选选项（T-SettingsSearchB 首轮扫描的存量缺口台账，
     * 登记与否另立任务；**后续新增开关不得落此处，必须进 buildSearchableFields()**）。
     */
    private val UNINDEXED_SWITCH_KEYS = setOf(
        "settings.paginationSim", "settings.paginationCount",
        "settings.explain.structured", "settings.explain.retention",
        "settings.libraryEntitySweep",
        "settings.log.showDuration", "settings.log.dataSourceBadgeDetail",
        "settings.log.shardingsphere.enabled", "settings.log.autoInjectInterceptor",
        "settings.gutterGroup.mapperXml", "settings.gutterGroup.annotation",
        "settings.gutterGroup.jpaHql", "settings.gutterGroup.jdbc",
        "settings.gutterGroup.inlineFallback", "settings.gutterGroup.other",
        "settings.xmlSynHi", "settings.onboardingAnchorTip",
        "settings.update.autoCheck",
        // 单选选项（组标签见③或已索引：timestampSource 组标签已在索引）
        "settings.emptyParamFill.placeholder", "settings.emptyParamFill.null", "settings.emptyParamFill.empty",
        "settings.log.timestampSource.log", "settings.log.timestampSource.capture",
    )

    /**
     * ⑤ 动态状态行（按**组件身份**豁免，不按文本）：文本运行期生成（缓存大小/模式预览/
     * 推断结果）或初始为空的反馈行，无静态 bundle 键可反解。
     * explainPlanRetentionDisclosure 的值含 `<br>`，剥标签后与 bundle 原文不再逐字相等，
     * 键反解路线走不通，同样按组件豁免（语义上属②说明行）。
     */
    private fun dynamicExemptComponents(panel: PluginSettingsPanel): List<Component> = listOf(
        panel.cacheInfoLabel,
        panel.enhancedStatusLabel,
        panel.statusLabel,
        panel.storageActionStatusLabel,
        panel.logAutoSavePatternPreview,
        panel.dialectInferredLabel,
        panel.prefixValidationRow,       // 校验结果行容器（内含私有 label，初始空白）
        panel.explainPlanRetentionDisclosure,
        panel.updateSection.versionLabel,
        panel.updateSection.statusLabel,
        panel.updateSection.installedLabel,
    )

    // ------------------------------------------------------------------
    // 扫描与 bundle 反解
    // ------------------------------------------------------------------

    /** 递归收集 JToggleButton/JLabel 的可见文本（剥 HTML 标签+反转义，空文本跳过）。 */
    private fun collectTexts(c: Component, out: MutableList<Pair<Component, String>>) {
        plainText(c)?.let { out += c to it }
        if (c is Container) for (child in c.components) collectTexts(child, out)
    }

    /** 采集面：JToggleButton（JBCheckBox/JBRadioButton，用户搜索的主要对象）与 JLabel（行标签/说明）。 */
    private fun plainText(c: Component): String? {
        val raw = when (c) {
            is JToggleButton -> c.text
            is JLabel -> c.text
            else -> return null
        } ?: return null
        val stripped = raw.replace(Regex("<[^>]*>"), "")
        val unescaped = stripped
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        val trimmed = unescaped.trim()
        if (trimmed.isEmpty()) return null
        // 纯占位符字形（如构建号行在无 MPV-Build 时的回退值「—」，PluginBuildInfo.displayText）：
        // 运行期生成的值展示，无 bundle 键亦无搜索语义
        if (trimmed.matches(Regex("[—\\-–\\s]+"))) return null
        return trimmed
    }

    private fun isDescendantOf(comp: Component, ancestor: Component): Boolean {
        var p: Container? = comp.parent
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent
        }
        return false
    }

    /**
     * 盘上双语 bundle 的「文本 → 键」反解表（base=英文、_zh=中文，合并同文异键）。
     * 不走运行期 ResourceBundle：索引清单语言冻结后，文本比对会被测试顺序打翻；按 key 比对语言无关。
     */
    private val textToKeys: Map<String, Set<String>> by lazy {
        val merged = mutableMapOf<String, MutableSet<String>>()
        for (file in listOf("MpSqlVisualizerBundle.properties", "MpSqlVisualizerBundle_zh.properties")) {
            val f = repoRoot().resolve("src/main/resources/messages/$file")
            check(f.isFile) { "bundle 文件应存在: $f" }
            for (raw in f.readLines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
                val idx = line.indexOf('=')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trimStart()
                merged.getOrPut(value) { mutableSetOf() }.add(key)
            }
        }
        merged
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    // ---------- 渲染断言辅助（与 PluginSettingsFolderConfigurableTest 同款） ----------

    private fun allComponents(c: Component): List<Component> {
        val list = mutableListOf(c)
        if (c is Container) c.components.forEach { list.addAll(allComponents(it)) }
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
    private fun renderedLines(list: JBList<*>, value: Any?): List<SimpleColoredComponent> {
        val renderer = list.cellRenderer as javax.swing.ListCellRenderer<Any?>
        return allComponents(renderer.getListCellRendererComponent(list, value, 0, false, false))
            .filterIsInstance<SimpleColoredComponent>()
    }
}
