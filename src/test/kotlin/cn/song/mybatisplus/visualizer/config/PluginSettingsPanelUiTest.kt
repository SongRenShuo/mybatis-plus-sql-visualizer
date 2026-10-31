package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.history.SqlHistoryEntry
import cn.song.mybatisplus.visualizer.history.SqlHistoryManager
import cn.song.mybatisplus.visualizer.ui.ParamHistoryCache
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox

/**
 * 设置面板「布局 / 兼容性 / 交互」UI 测试（F-44-6 续 · 用户要求「整个插件 UI 用例」）：
 *
 * 此前仅覆盖绑定/持久化逻辑（PluginSettingsPanelBindingTest 等），真实渲染与布局未测——用户明确点名
 * 「跨 IDE 布局 / section 显隐」是薄弱点。本测试模拟真实 Settings 打开：
 *  - 面板构建即出根组件、关键控件（方言/语言/复选框/清除缓存按钮）均可见且有正首选尺寸
 *    （headless 下 preferredSize 由真实字体度量计算，能证明控件在真实 IDE 中会渲染、不塌缩/消失）；
 *  - section 过滤：仅启用 SQL 日志段 → 其余段（含方言/语言/反向形态等下拉）全部不构建、不占布局；
 *  - 真实交互：勾选「启动自动监听」→ isModified 翻转（Apply 按钮可点击）；切换语言 → 写穿 + 重建不崩、
 *    控件仍存活（验证 M19 写穿式语言切换在真实 Settings 链路下的稳定性）。
 *
 * UI-001 补充：给真实滚动宿主设定尺寸，再递归 doLayout 即可在 headless 下验证实际 bounds。
 * preferredSize 冒烟仅证明控件有内容，不能证明常规宽度下文本未被推离视口。
 */
class PluginSettingsPanelUiTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    fun testPreviewChineseControlsAndPrivacyFitDefaultSettingsWidth() = assertPreviewLayout("zh", 700)

    fun testPreviewEnglishControlsAndPrivacyFitDefaultSettingsWidth() = assertPreviewLayout("en", 700)

    fun testPreviewChineseNarrowSettingsKeepsScrollAccess() = assertPreviewLayout("zh", 360)

    fun testPreviewEnglishNarrowSettingsKeepsScrollAccess() = assertPreviewLayout("en", 360)

    /** Set real bounds before layout; a headless hierarchy does not need a native window peer. */
    private fun layoutTree(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutTree)
        }
    }

    private fun assertPreviewLayout(language: String, width: Int) {
        val settings = PluginSettings.getInstance()
        val before = settings.state.copy()
        val configurable = SqlPreviewConfigurable()
        try {
            settings.state.language = language
            val root = configurable.createComponent()
            val panel = configurable.panelForTest()
            // Match Settings' scroll host rather than forcing only the inner grid to an artificial size.
            val scroll = com.intellij.ui.components.JBScrollPane(root)
            scroll.setSize(width, 600)
            repeat(3) { layoutTree(scroll) }
            val viewport = scroll.viewport
            val controls = allComponents(root).filter {
                it.isVisible && (it is JBCheckBox || it is JComboBox<*> || it is JBTextField ||
                    it is javax.swing.JRadioButton || (it is javax.swing.JLabel && !it.text.isNullOrBlank()))
            }
            assertTrue("Must exercise the actual preview controls", controls.size > 25)
            val contentWidth = if (width >= 700) viewport.extentSize.width else root.width
            for (control in controls) {
                val bounds = javax.swing.SwingUtilities.convertRectangle(control.parent, control.bounds, root)
                assertTrue("$language/$width ${control.javaClass.simpleName}: $bounds outside $contentWidth text=${(control as? javax.swing.JLabel)?.text} html=${(control as? javax.swing.JComponent)?.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey)}",
                    bounds.width > 0 && bounds.height > 0 && bounds.x >= 0 && bounds.x + bounds.width <= contentWidth)
                if (control is JBCheckBox || control is javax.swing.JLabel) {
                    val html = (control as javax.swing.JComponent).getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) as? javax.swing.text.View
                    if (html != null) {
                        assertTrue("HTML text must not be clipped horizontally: $bounds",
                            html.getPreferredSpan(javax.swing.text.View.X_AXIS) <= control.width)
                        assertTrue("Wrapped text must get its full row height: $bounds",
                            html.getPreferredSpan(javax.swing.text.View.Y_AXIS) <= control.height)
                    }
                }
            }
            val disclosure = panel.explainPlanRetentionDisclosure
            val text = disclosure.text
            assertTrue(text.contains("mp-sql-visualizer.xml"))
            assertTrue(text.contains("APP") || text.contains("application-level"))
            assertTrue(text.contains("未经脱敏") || text.contains("unmasked"))
            assertTrue(text.contains("凭据") || text.contains("credentials"))
            assertTrue(text.contains("清除预览历史") || text.contains("Clear preview history"))
            assertTrue(text.contains("不删除已有计划") || text.contains("does not delete existing plans"))
            assertNull("Privacy disclosure must be visible without hovering", disclosure.toolTipText)
            assertTrue("Disclosure must wrap, not become a one-line label", disclosure.height >= disclosure.getFontMetrics(disclosure.font).height * 3)
            assertTrue("Vertical scrolling remains available", scroll.verticalScrollBar.isVisible)
            if (width >= 700) {
                assertFalse("Default Settings content must not require horizontal scrolling ($language)", scroll.horizontalScrollBar.isVisible)
            } else {
                assertTrue("Narrow Settings must keep horizontal access rather than truncate", scroll.horizontalScrollBar.isVisible)
                scroll.horizontalScrollBar.value = scroll.horizontalScrollBar.maximum
                scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
                assertTrue("Can reach the rightmost content", viewport.viewPosition.x > 0)
                assertTrue("Can reach the bottom rows", viewport.viewPosition.y > 0)
            }
        } finally {
            configurable.disposeUIResources()
            settings.loadState(before)
        }
    }

    /** 递归收集容器内所有组件（含嵌套）。 */
    private fun allComponents(c: Component): List<Component> {
        val list = mutableListOf(c)
        if (c is Container) for (child in c.components) list += allComponents(child)
        return list
    }

    /** 真实控件类型（排除根容器本身），用于渲染/布局断言。 */
    private val REAL_TYPES = listOf(JComboBox::class, JBCheckBox::class, JBLabel::class, JButton::class, JBTextField::class)

    fun test面板构建_关键控件可见且有正首选尺寸() {
        val settings = PluginSettings.getInstance()
        val panel = PluginSettingsPanel(settings)
        try {
            waitForEdt()
            val root = panel.getRoot()
            assertNotNull("根面板应非空", root)

            val realControls = allComponents(root)
                .filter { it !== root && it.isVisible && REAL_TYPES.any { t -> t.isInstance(it) } }
            assertTrue("应存在可见的真实控件（下拉/复选/标签/按钮/文本）", realControls.isNotEmpty())

            // 所有可见真实控件的首选尺寸 > 0（headless 由字体度量计算）：证明在真实 IDE 中会渲染、
            // 不会因布局塌缩而「消失」（用户点名的跨 IDE 布局错乱信号）。
            // 例外：空文本 JBLabel 是故意的占位/包裹行（addRow(p, g, "", checkBox)），首选宽 0 属正常。
            val zeroPreferred = realControls.filter {
                (it.preferredSize.width <= 0 || it.preferredSize.height <= 0) &&
                    !(it is JBLabel && it.text.isBlank())
            }
            assertEquals(
                "所有含文本/内容的可见真实控件应有正首选尺寸（无渲染塌缩/消失）: ${zeroPreferred.map { it::class.simpleName }}",
                0,
                zeroPreferred.size,
            )

            // 关键控件经 ForTest 缝可触达
            assertNotNull("清除缓存按钮应存在", panel.clearCacheButtonForTest())
            assertNotNull("反向转换快捷键编辑行应存在", panel.reverseShortcutEditorForTest())
        } finally {
            // 无显式 dispose，仅释放引用
        }
    }

    fun testSection可见性_仅SQL日志段隐藏其他段控件() {
        val settings = PluginSettings.getInstance()
        // 全量：6 个 section（部分含子分组，故分隔线数 >= 6）
        val full = PluginSettingsPanel(settings)
        // 仅 SQL 日志段：其余 section 不应被构建
        val sqlLogOnly = PluginSettingsPanel(settings, setOf(PluginSettingsPanel.SECTION_SQL_LOG))
        try {
            waitForEdt()
            val fullCombos = allComponents(full.getRoot()).count { it is JComboBox<*> }
            val sqlLogCombos = allComponents(sqlLogOnly.getRoot()).count { it is JComboBox<*> }

            assertTrue("全量面板应含多个下拉（方言/语言/反向形态/表名推断等）", fullCombos >= 3)
            // T-CrudColor：SQL 日志段新增「CRUD 标记样式」下拉，故由 0 → 1；
            // T-DisplayModeToSettings：新增「SQL 显示模式」下拉，故由 1 → 2
            // T-MagpieGems W2：展示脱敏由复选框改「脱敏模式」分级下拉，故由 2 → 3
            // （断言本意是「其余 section 的下拉未被构建」，绝对数随该 section 内控件变化）
            assertEquals("仅 SQL 日志段应只含 标记样式 + 显示模式 + 脱敏模式 三个下拉（其余 section 被隐藏）", 3, sqlLogCombos)

            val fullSeparators = allComponents(full.getRoot()).count { it is TitledSeparator }
            val sqlLogSeparators = allComponents(sqlLogOnly.getRoot()).count { it is TitledSeparator }
            assertTrue("全量面板分组数应多于仅 SQL 日志段（section 显隐生效）", fullSeparators > sqlLogSeparators)
        } finally {
            // no-op
        }
    }

    fun test勾选启动自动监听_isModified翻转() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = false
            val panel = PluginSettingsPanel(settings)
            waitForEdt()
            assertFalse("初始应与配置一致（未勾选 → 无变化）", panel.isModified())

            panel.setLogAutoCaptureCheck(true)
            assertTrue("勾选「启动自动监听」应检测到变化（Apply 可点击）", panel.isModified())

            panel.setLogAutoCaptureCheck(false)
            assertFalse("取消勾选应回到与配置一致", panel.isModified())
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    fun test切换语言触发写穿与重建_控件仍存活() {
        val settings = PluginSettings.getInstance()
        val oldLang = settings.state.language
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            waitForEdt()
            assertFalse("初始语言与配置一致，无修改", panel.isModified())

            // 切换语言：写穿式持久化（立即写入 state）+ 置 languagePending（Apply 按钮启用）
            // + invokeLater 重建表单。M19.1 设计：语言变更不靠 OK→apply，选中即生效，但仍需 Apply 清 pending。
            panel.selectLanguage("en")
            waitForEdt() // 泵 EDT，使 invokeLater 重建执行
            assertTrue("切换语言应写穿并置 languagePending → isModified 为 true（Apply 可点击）", panel.isModified())
            assertEquals("语言应写穿持久化到配置 state", "en", settings.state.language)

            // 重建后仍能取到根面板与关键控件（不崩、不丢失）
            val rootAfter = panel.getRoot()
            assertNotNull("重建后根面板应仍可取", rootAfter)
            val combosAfter = allComponents(rootAfter).count { it is JComboBox<*> }
            assertTrue("重建后下拉控件应仍存在", combosAfter >= 1)
        } finally {
            // 复位语言配置（pending 标记随面板实例 GC，不影响其他测试）
            settings.state.language = oldLang
        }
    }

    fun test捕获历史动作_只打开当前自定义目录且不删除文件() {
        val settings = PluginSettings.getInstance()
        val oldPath = settings.state.logAutoSavePath
        val dir = java.nio.file.Files.createTempDirectory("mpsv-storage-open-").toFile()
        val sentinel = dir.resolve("user-owned.sql").apply { writeText("SELECT 1") }
        try {
            settings.state.logAutoSavePath = dir.absolutePath
            val panel = PluginSettingsPanel(settings, setOf(PluginSettingsPanel.SECTION_SQL_LOG))
            var opened: java.io.File? = null
            panel.captureDirectoryOpener = { opened = it }

            panel.openCaptureDirectoryButtonForTest().doClick()

            assertEquals("应打开设置中的自定义目录", dir.canonicalFile, opened?.canonicalFile)
            assertTrue("打开目录动作不得删除自定义目录", dir.isDirectory)
            assertTrue("打开目录动作不得删除目录内用户文件", sentinel.isFile)
            assertTrue("动作结果应有诚实反馈", panel.storageActionStatusForTest().text.isNotBlank())
        } finally {
            settings.state.logAutoSavePath = oldPath
            sentinel.delete()
            dir.delete()
        }
    }

    fun test两类清理动作_分别确认分别清理且不删除共享设置() {
        val settings = PluginSettings.getInstance()
        val state = settings.state
        val oldLanguage = state.language
        val history = SqlHistoryManager.getInstance()
        val cache = ParamHistoryCache()
        try {
            history.clear()
            cache.clear()
            state.language = "storage-clear-sentinel"

            history.add(SqlHistoryEntry(
                sql = "SELECT * FROM user WHERE id = ?",
                compactSql = "SELECT * FROM user WHERE id = ?",
                favorite = true,
                tags = mutableListOf("保留标签"),
                paramSnapshot = linkedMapOf("id" to "history-secret"),
            ))
            val key = cache.computeKey("user", "SELECT * FROM user WHERE id = ?")!!
            cache.save(key, mapOf("id" to "param-secret"))
            cache.saveAsScheme("命名方案", key, mapOf("id" to "scheme-secret"))

            val panel = PluginSettingsPanel(settings, setOf(PluginSettingsPanel.SECTION_SQL_LOG))
            assertFalse(
                "预览历史与参数历史必须是两个标签明确不同的动作",
                panel.clearPreviewHistoryButtonForTest().text == panel.clearParamHistoryButtonForTest().text,
            )
            panel.storageClearConfirmation = { false }
            panel.clearPreviewHistoryButtonForTest().doClick()
            assertEquals("取消确认不得清预览历史", 1, history.entries.size)

            panel.storageClearConfirmation = { true }
            panel.clearPreviewHistoryButtonForTest().doClick()
            assertTrue("预览历史应连同收藏/标签/参数快照整条清空", history.entries.isEmpty())
            assertEquals("预览历史动作不得越界清参数方案", mapOf("id" to "scheme-secret"), cache.recall(key))

            history.add(SqlHistoryEntry(sql = "SELECT 2", compactSql = "SELECT 2"))
            panel.clearParamHistoryButtonForTest().doClick()
            assertNull("已打开缓存实例不得继续召回旧参数", cache.recall(key))
            assertEquals("参数动作不得越界清预览历史", 1, history.entries.size)
            assertEquals("不得删除共享 XML 中的无关设置", "storage-clear-sentinel", state.language)
            assertEquals("默认参数历史字段应写空", "", state.paramHistoryJson)
            assertEquals("命名方案字段应写空", "", state.paramSchemesJson)
            assertEquals("激活方案字段应写空", "", state.paramActiveScheme)
            assertEquals("按键激活指针字段应写空", "", state.paramActiveSchemeByKey)
        } finally {
            history.clear()
            cache.clear()
            state.language = oldLanguage
        }
    }
}
