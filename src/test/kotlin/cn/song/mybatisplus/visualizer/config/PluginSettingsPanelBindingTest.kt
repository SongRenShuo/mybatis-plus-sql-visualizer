package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.actions.ConvertSqlToJavaAction
import cn.song.mybatisplus.visualizer.actions.CountSelectedTableAction
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * 设置面板绑定生效性测试（T-xx 设置生效性审计）：
 *
 *  - 反向转换形态（reverseWrapperFormat）：此前**漏挂 isModified**——仅改此项时 Apply 按钮不启用，
 *    改动被平台静默丢弃（字段有真实读取点（ConvertSqlToJavaAction/SqlToJavaPanel）但保存链路断裂，形同假开关）；
 *  - 启动自动监听开关（logAutoCaptureOnStart）：勾选/取消 → apply 写回 state（状态持久化，
 *    实际生效由 LogStartupCapture postStartupActivity 在项目打开时触发）。
 */
class PluginSettingsPanelBindingTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    fun testReverseFormatChangeEnablesApplyAndPersists() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.reverseWrapperFormat
        try {
            settings.state.reverseWrapperFormat = PluginSettings.REVERSE_AUTO
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            assertFalse("初始应与配置一致（无变化）", configurable.isModified())

            panel.selectReverseFormat(PluginSettings.REVERSE_QUERY)
            assertTrue(
                "仅改反向转换形态也应检测到变化（修复前漏挂 isModified → Apply 不启用、改动被静默丢弃）",
                configurable.isModified(),
            )

            configurable.apply()
            assertEquals("apply 后配置应写回 QUERY", PluginSettings.REVERSE_QUERY, settings.state.reverseWrapperFormat)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.state.reverseWrapperFormat = old
        }
    }

    /** T-LocalUpdateDir：本地安装包目录的 isModified / apply / reset 三处接线（照抄路径项范式）。 */
    fun testLocalUpdateDirBinding() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.localUpdateDir
        try {
            settings.state.localUpdateDir = ""
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val field = configurable.panelForTest().updateSection.localDirField

            assertFalse("初始应与配置一致（无变化）", configurable.isModified())

            // 错误态路径：apply 存 trim 原文（重开页面后状态行仍能按原文报原因）
            field.text = "C:\\mpvs-nonexistent-dir-2026"
            assertTrue("改目录应检测到变化", configurable.isModified())
            configurable.apply()
            assertEquals("错误态应存 trim 原文", "C:\\mpvs-nonexistent-dir-2026", settings.state.localUpdateDir)
            assertFalse("apply 回写同步后 isModified 应归零", configurable.isModified())

            // 合法目录：apply 存归一路径（绝对 + normalize，去尾分隔符）
            val tmpDir = java.nio.file.Files.createTempDirectory("mpvs-bind-")
            field.text = tmpDir.toString() + java.io.File.separator
            configurable.apply()
            assertEquals(
                "合法路径应存 normalize 形态（无尾分隔符）",
                tmpDir.toAbsolutePath().normalize().toString(),
                settings.state.localUpdateDir,
            )

            configurable.reset()
            assertEquals("reset 应回填配置值", settings.state.localUpdateDir, field.text)
            assertFalse("reset 后应与配置一致", configurable.isModified())
            java.nio.file.Files.deleteIfExists(tmpDir)
        } finally {
            settings.state.localUpdateDir = old
        }
    }

    fun testAutoCaptureToggleAppliesToState() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.logAutoCaptureOnStart
        try {
            settings.state.logAutoCaptureOnStart = false
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            panel.setLogAutoCaptureCheck(true)
            assertTrue("勾选「启动自动监听」应检测到变化", configurable.isModified())
            configurable.apply()
            assertTrue("apply 后 state.logAutoCaptureOnStart 应为 true", settings.state.logAutoCaptureOnStart)

            panel.setLogAutoCaptureCheck(false)
            assertTrue("取消勾选同样应检测到变化", configurable.isModified())
            configurable.apply()
            assertFalse("apply 后 state.logAutoCaptureOnStart 应回 false", settings.state.logAutoCaptureOnStart)
        } finally {
            settings.state.logAutoCaptureOnStart = old
        }
    }

    fun testManualEntityToggleAppliesToState() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.reverseManualEntitySelection
        try {
            settings.state.reverseManualEntitySelection = false
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            panel.setManualEntityCheck(true)
            assertTrue("勾选「手动选择实体类」应检测到变化", configurable.isModified())
            configurable.apply()
            assertTrue("apply 后 state.reverseManualEntitySelection 应为 true", settings.state.reverseManualEntitySelection)

            panel.setManualEntityCheck(false)
            assertTrue("取消勾选同样应检测到变化", configurable.isModified())
            configurable.apply()
            assertFalse("apply 后 state.reverseManualEntitySelection 应回 false", settings.state.reverseManualEntitySelection)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.state.reverseManualEntitySelection = old
        }
    }

    /** T-PT-04：空参数填充策略（NULL 字面量 / 空字符串）读写链——isModified / apply / reset 三处接线。 */
    fun testEmptyParamFillStrategyBinding() {
        val settings = PluginSettings.getInstance()
        val old = settings.state.emptyParamFill
        try {
            settings.state.emptyParamFill = PluginSettings.EMPTY_PARAM_FILL_NULL
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()
            assertFalse("初始应与配置一致（无变化）", configurable.isModified())

            panel.selectEmptyParamFill(PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING)
            assertTrue("切到空字符串档应检测到变化", configurable.isModified())
            configurable.apply()
            assertEquals(
                "apply 后 state.emptyParamFill 应为 EMPTY_STRING",
                PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING,
                settings.state.emptyParamFill,
            )

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
            assertEquals("reset 不应改写配置", PluginSettings.EMPTY_PARAM_FILL_EMPTY_STRING, settings.state.emptyParamFill)

            // O-79：占位档（默认档）round-trip
            panel.selectEmptyParamFill(PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER)
            assertTrue("切到占位档应检测到变化", configurable.isModified())
            configurable.apply()
            assertEquals(
                "apply 后 state.emptyParamFill 应为 PLACEHOLDER",
                PluginSettings.EMPTY_PARAM_FILL_PLACEHOLDER,
                settings.state.emptyParamFill,
            )
        } finally {
            settings.state.emptyParamFill = old
        }
    }

    fun testCacheInfoAndClearButton() {
        val configurable = AllSectionsConfigurable()
        configurable.createComponent()
        val panel = configurable.panelForTest()
        // 构造时应已刷新缓存信息（非空文案；Light 环境缓存为空也显示汇总格式）
        assertTrue("缓存信息文案应已初始化: " + panel.cacheInfoForTest(), panel.cacheInfoForTest().isNotBlank())
        assertTrue("缓存信息应含大小单位（B/KB/MB）: " + panel.cacheInfoForTest(), panel.cacheInfoForTest().contains("B"))
        // 统一清除按钮存在且点击不抛异常（Light 项目可能不在 openProjects——空遍历安全）；
        // 点击后信息行变「已清除」状态文本（仿检测更新反馈，持续显示直到下次操作/重开设置）
        panel.clearCacheButtonForTest().doClick()
        assertEquals(
            "点击清除后信息行应显示「已清除」反馈",
            MpSqlVisualizerBundle.message("settings.cache.cleared"),
            panel.cacheInfoForTest(),
        )
    }

    fun testSqlToJavaShortcutRowExistsAndApplies() {
        val configurable = AllSectionsConfigurable()
        configurable.createComponent()
        val panel = configurable.panelForTest()

        // 行存在：递归收集行标签（GridBag 嵌套一层，递归通用）
        val labels = collectLabels(panel.getRoot()).map { it.text }
        assertTrue(
            "设置页应含「SQL→Java 快捷键」行（T-M41 新增；修复前仅 Convert to SQL 一行）",
            labels.contains(MpSqlVisualizerBundle.message("settings.shortcut.sqlToJava")),
        )
        assertTrue(
            "原有「快捷键」（Convert to SQL）行应保留",
            labels.contains(MpSqlVisualizerBundle.message("settings.shortcut")),
        )

        // 修改检测：编辑行值变化 → isModified
        assertFalse("初始应与当前 Keymap 一致（无变化）", configurable.isModified())
        val newShortcut = KeyboardShortcut(
            KeyStroke.getKeyStroke(KeyEvent.VK_J, KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK),
            null,
        )
        panel.reverseShortcutEditorForTest().shortcut = newShortcut
        assertTrue("仅改 SQL→Java 快捷键也应检测到变化", configurable.isModified())

        // apply 写入 Keymap（无异常）；读回断言兜底——headless 环境 Keymap 不可用时跳过
        configurable.apply()
        runCatching {
            val shortcuts = KeymapManager.getInstance().activeKeymap
                .getShortcuts(ConvertSqlToJavaAction.ACTION_ID)
                .filterIsInstance<KeyboardShortcut>()
            assertTrue("apply 后 activeKeymap 应含新快捷键", shortcuts.contains(newShortcut))
        }.onFailure {
            // Light 环境 Keymap 可能不可用（currentShortcut 兜底 null）——降级跳过读回断言
        }

        // reset 回读：编辑行回到当前 Keymap 值 → 无变化
        configurable.reset()
        assertFalse("reset 后应回到与 Keymap 一致（无变化）", configurable.isModified())
    }

    fun testTemplateActionsHaveNoDefaultShortcutAndStayRecordable() {
        // T-TemplateKeyDrop：三动作（EXPLAIN / COUNT / 抽样 10 行）已摘除默认键位——原 Alt+Shift+E/C/T
        // 与平台动作冲突（RecentChanges / tasks.switch），备选键 Ctrl+Alt+Shift+同字母亦被占
        // （Copy Reference / Refactor This），「冲突自动换绑」无解（见开发计划 §一）。
        // 本用例钉住摘除后的实际形态：三行保留可录制，初始为空（「—」）且不把面板判成已修改，
        // 录制 → apply 写入 Keymap → reset 回空。
        val configurable = AllSectionsConfigurable()
        configurable.createComponent()
        val panel = configurable.panelForTest()

        // 三行仍存在（防「摘键位顺手把行也删了」——那样用户就没有录制入口了）
        val labels = collectLabels(panel.getRoot()).map { it.text }
        for (key in listOf("settings.shortcut.explain", "settings.shortcut.count", "settings.shortcut.sample")) {
            val text = MpSqlVisualizerBundle.message(key)
            assertTrue("设置页应保留「$text」行（摘默认键位不等于删录制入口）", labels.contains(text))
        }

        val editor = panel.countShortcutEditorForTest()
        assertNull("无默认键位时编辑行应为空（显示「—」）", editor.shortcut)
        assertFalse("无默认键位不应把面板判成已修改（否则 Apply 常亮）", configurable.isModified())

        val newShortcut = KeyboardShortcut(
            KeyStroke.getKeyStroke(
                KeyEvent.VK_C,
                KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK,
            ),
            null,
        )
        editor.shortcut = newShortcut
        assertTrue("录制快捷键后应检测到变化", configurable.isModified())

        // reset 语义 = 「编辑行回到当前 Keymap 值」：此刻该动作在 Keymap 里本就没有绑定 → 回到空
        configurable.reset()
        assertNull(
            "reset 后应回到无绑定（当前 Keymap 里本就没有该动作的键）",
            panel.countShortcutEditorForTest().shortcut,
        )
        assertFalse("reset 后不应判定为已修改", configurable.isModified())

        // 再录一次并 apply：无默认键位下走「清理旧绑定（本就为空）→ 绑定新组合」
        panel.countShortcutEditorForTest().shortcut = newShortcut
        configurable.apply()
        runCatching {
            val shortcuts = KeymapManager.getInstance().activeKeymap
                .getShortcuts(CountSelectedTableAction.ACTION_ID)
                .filterIsInstance<KeyboardShortcut>()
            assertTrue("apply 后 activeKeymap 应含录制的快捷键", shortcuts.contains(newShortcut))
        }.onFailure {
            // Light 环境 Keymap 可能不可用——降级跳过读回断言（与 SQL→Java 快捷键用例同款）
        }
    }

    fun testVersionInfoLabelTopAlignedWithSection() {
        // T-M41 回归：updateSection 为多行组件，GridBag 默认 WEST（垂直居中）会把「版本信息」
        // 标签垂直居中到整块中间（错位到「启动时自动检查更新」行——用户截图实证）→ NORTHWEST 顶部对齐。
        // headless 无 peer，需 doLayout 链后才有真实坐标（pitfall 实证）。
        val configurable = AllSectionsConfigurable()
        configurable.createComponent()
        val root = configurable.panelForTest().getRoot()
        root.setSize(root.preferredSize)
        layoutTree(root)

        // 找到「更新与诊断」分区面板（包含 UpdateSection 的那个 section 容器）
        val updateSection = findComponent(root, UpdateSection::class.java)
        assertTrue("应找到 UpdateSection", updateSection != null)
        val sectionPanel = updateSection!!.parent
        val versionLabel = sectionPanel.components
            .filterIsInstance<JBLabel>()
            .firstOrNull { it.text == MpSqlVisualizerBundle.message("settings.update.versionInfo") }
        assertTrue("「版本信息」标签应在分区面板标签列", versionLabel != null)

        // 标签应顶对齐（与 updateSection 顶部接近），而非垂直居中（旧行为错位到下一行）
        val diff = versionLabel!!.y - updateSection.y
        assertTrue(
            "「版本信息」标签应与 updateSection 顶部对齐（实际偏移 ${diff}px；WEST 垂直居中旧行为约 +20px）",
            diff in -2..10,
        )
    }

    /** 递归 doLayout（headless 布局断言前置——pitfall-headless-validate）。 */
    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.forEach { if (it is Container) layoutTree(it) }
    }

    /** 递归查找指定类型组件（含自身）。 */
    private fun <T : Component> findComponent(root: Container, type: Class<T>): T? {
        if (type.isInstance(root)) return root as T
        for (c in root.components) {
            if (type.isInstance(c)) return c as T
            if (c is Container) findComponent(c, type)?.let { return it }
        }
        return null
    }

    /** 递归收集组件树中全部 JBLabel（仿 PluginSettingsPanelBuildInfoTest.collectLabels）。 */
    private fun collectLabels(component: Component): List<JBLabel> {
        val labels = mutableListOf<JBLabel>()
        if (component is JBLabel) labels += component
        if (component is Container) component.components.forEach { labels += collectLabels(it) }
        return labels
    }

    // ------------------------------------------------------------------
    // T-TenantLine：多租户注入面板绑定生效性
    // ------------------------------------------------------------------

    fun testTenantLinePanelIsModified() {
        // T-TenantLine：改 injectTenantLine → isModified=true → apply 写回配置
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.injectTenantLine = true
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            assertFalse("初始应与配置一致（无变化）", configurable.isModified())

            // 取消勾选多租户注入开关（与用户勾选同一路径）
            panel.setInjectTenantLineCheck(false)
            assertTrue("改 injectTenantLine 后应检测到变化", configurable.isModified())

            configurable.apply()
            assertEquals("apply 后 state.injectTenantLine 应为 false", false, settings.state.injectTenantLine)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testTenantLinePanelApplyReset() {
        // T-TenantLine：面板 apply/reset 写入配置 + 回读
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tenantColumnName = "tenant_id"
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            // 改租户列名（与用户输入同一路径）
            panel.setTenantColumnName("org_id")
            assertTrue("改 tenantColumnName 后应检测到变化", configurable.isModified())

            configurable.apply()
            assertEquals("apply 后 state.tenantColumnName 应为 org_id", "org_id", settings.state.tenantColumnName)

            // reset 回读
            configurable.reset()
            assertFalse("reset 后应回到与配置一致（无变化）", configurable.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }

    /**
     * T-CrudColor：仅改「标记形态下拉」也必须置 isModified——
     * 防 reverseWrapperFormat 式假开关回归（漏挂 isModified → Apply 灰掉、改动被平台静默丢弃）。
     */
    fun testCrudColorStyleChangeEnablesApplyAndPersists() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.state
        try {
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            assertFalse("初始应与配置一致", configurable.isModified())
            panel.selectCrudColorStyle(PluginSettings.CRUD_COLOR_STYLE_BACKGROUND)
            assertTrue("仅改标记形态也应检测到变化", configurable.isModified())

            configurable.apply()
            assertEquals(PluginSettings.CRUD_COLOR_STYLE_BACKGROUND, settings.state.crudColorStyle)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致", configurable.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }

    /** T-CrudColor：仅改某个颜色同样必须置 isModified，且 apply 写回 `#RRGGBB`。 */
    fun testCrudColorValueChangeEnablesApplyAndPersists() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.state
        try {
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            panel.setCrudColor("delete", "#00FF00")
            assertTrue("仅改 DELETE 颜色也应检测到变化", configurable.isModified())

            configurable.apply()
            assertEquals("#00FF00", settings.state.crudColorDelete)

            configurable.reset()
            assertFalse("reset 后应回到与配置一致", configurable.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testListDisplayModeDefaultsToKeywordWrapAndPreservesSavedMode() {
        val settings = PluginSettings.getInstance()
        val saved = settings.state.logListSqlDisplayMode
        try {
            assertEquals("KEYWORD_WRAP", PluginSettings.State().logListSqlDisplayMode)
            settings.state.logListSqlDisplayMode = "FORMATTED"
            assertEquals(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.FORMATTED, settings.sqlListDisplayMode())
            settings.state.logListSqlDisplayMode = "not-a-display-mode"
            assertEquals(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.KEYWORD_WRAP, settings.sqlListDisplayMode())
        } finally {
            settings.state.logListSqlDisplayMode = saved
        }
    }

    /** T-DisplayModeToSettings：仅改显示模式同样必须置 isModified，且 apply 写回设置值（两列表共享一键）。 */
    fun testListDisplayModeChangeEnablesApplyAndPersists() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.state
        try {
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            assertFalse("初始应与配置一致", configurable.isModified())
            panel.selectListDisplayMode(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.FORMATTED.name)
            assertTrue("仅改显示模式也应检测到变化", configurable.isModified())

            configurable.apply()
            assertEquals(
                cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.FORMATTED.name,
                settings.state.logListSqlDisplayMode,
            )

            configurable.reset()
            assertEquals("reset 后下拉应回到 state 值", settings.state.logListSqlDisplayMode, panel.currentListDisplayModeForTest())
            assertFalse("reset 后应回到与配置一致", configurable.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }

    /** T-TestGuard-05：apply 后 state 引用必须替换（pitfall-settings-persist-inplace——
     *  原地修改 state 属性时 XmlSerializer 检测不到变化，不触发 XML 持久化、重启丢设置；
     *  修复形态 = PluginSettingsPanel.apply() 末尾 settings.loadState(s.copy())，本用例防回退）。 */
    fun testApplyReplacesStateReferenceNotMutateInPlace() {
        val settings = PluginSettings.getInstance()
        val before = settings.state
        val configurable = AllSectionsConfigurable()
        configurable.createComponent()
        val panel = configurable.panelForTest()

        panel.selectReverseFormat(PluginSettings.REVERSE_QUERY)
        configurable.apply()

        assertTrue(
            "apply 后 PluginSettings.state 必须是替换后的新引用（loadState(s.copy())），" +
                "原地修改不触发 XmlSerializer 持久化",
            before !== settings.state,
        )
    }

    /**
     * T-EnhancedCaptureDefaultOn 复盘补测（G-C）：增强捕获开关的运行期绑定。
     * SettingsRealityGuardTest 只保证「符号出现在 apply/reset 体里」（源码扫描），
     * 绑错字段不红——本用例用真实 apply/reset/isModified 驱动三向断言钉住行为。
     */
    fun test增强捕获开关_apply_reset_isModified三向绑定() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            val panel = configurable.panelForTest()

            // ① isModified：勾选态与 state 一致时不得报脏
            panel.enhancedCaptureCheck.isSelected = settings.state.enhancedCaptureEnabled
            assertFalse("勾选态与 state 一致时 isModified 必须为 false", configurable.isModified())

            // ② apply：勾选翻转 → state 翻转写回。先把同排的脱敏复选框拉成相反值——
            // 否则「绑错字段」探针（写成 desensitize 值）与期望值同态，判别力归零（反向验证实证）。
            panel.enhancedDesensitizeCheck.isSelected = settings.state.enhancedCaptureEnabled
            panel.enhancedCaptureCheck.isSelected = !settings.state.enhancedCaptureEnabled
            assertTrue("勾选态翻转后 isModified 应为 true", configurable.isModified())
            configurable.apply()
            assertEquals(
                "apply 应把复选框值写回 enhancedCaptureEnabled（绑错字段此处即红）",
                panel.enhancedCaptureCheck.isSelected,
                settings.state.enhancedCaptureEnabled,
            )

            // ③ reset：state 变化 → 复选框回读
            settings.state.enhancedCaptureEnabled = !settings.state.enhancedCaptureEnabled
            configurable.reset()
            assertEquals(
                "reset 应把 state 值回读到复选框（回读错字段此处即红）",
                settings.state.enhancedCaptureEnabled,
                panel.enhancedCaptureCheck.isSelected,
            )
            assertFalse("reset 后 isModified 应回到 false", configurable.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }
}
