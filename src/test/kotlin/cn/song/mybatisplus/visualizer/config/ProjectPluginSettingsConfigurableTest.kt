package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JTextField

/**
 * 项目级设置页测试（F-44 续 + T-SettingsTruth P4 面板扩容）：
 *  - createComponent 构建「使用项目设置」开关 + 十一项可覆盖字段（State 十一段一一对应）
 *  - 构造后 isModified=false；各控件改动后 isModified 逐项比对为真（T-RA-04 起无标志位）
 *  - apply 写回 ProjectPluginSettings 且 isModified 复位；reset 回灌 state 值
 *  - 空值语义：state null/空字段时控件灌全局值；字符串字段 apply 时 trim
 * 通过组件遍历取控件，无需新增生产缝。tearDown 还原项目/全局设置状态。
 */
class ProjectPluginSettingsConfigurableTest : BasePlatformTestCase() {

    private var savedProjectState: ProjectPluginSettings.State? = null
    private var savedGlobalState: PluginSettings.State? = null

    override fun setUp() {
        super.setUp()
        savedProjectState = ProjectPluginSettings.getInstance(myFixture.project).state.copy()
        savedGlobalState = PluginSettings.getInstance().state.copy()
    }

    override fun tearDown() {
        ProjectPluginSettings.getInstance(myFixture.project).loadState(savedProjectState!!)
        PluginSettings.getInstance().loadState(savedGlobalState!!)
        super.tearDown()
    }

    private fun all(c: java.awt.Component): List<java.awt.Component> {
        val list = mutableListOf(c)
        if (c is java.awt.Container) c.components.forEach { list.addAll(all(it)) }
        return list
    }

    private fun newConfig(): ProjectPluginSettingsConfigurable =
        ProjectPluginSettingsConfigurable(myFixture.project)

    private fun clicks(check: JCheckBox, selected: Boolean) {
        if (check.isSelected != selected) check.doClick()
    }

    fun testCreateComponent_含使用项目设置开关与十一项可覆盖字段() {
        val cfg = newConfig()
        val comp = cfg.createComponent()
        assertNotNull(comp)
        val checks = all(comp).filterIsInstance<JCheckBox>()
        // 开关 + 参数替换 + 格式化 + 逻辑删除注入 + 列名数据源校正（O-58）= 5 个 checkbox
        assertEquals("应有 5 个 checkbox（开关 + 4 个覆盖项）", 5, checks.size)
        // O-58：新覆盖项排在既有 4 个之后（下标 4），文案复用全局页同一 bundle 键
        assertEquals(
            "新覆盖项文案须复用全局页同一 bundle 键",
            MpSqlVisualizerBundle.message("settings.columnCalibration"),
            checks[4].text,
        )
        val combos = all(comp).filterIsInstance<JComboBox<*>>()
        // 方言下拉 + 推断策略下拉
        assertEquals("应有 2 个下拉（方言 + 推断策略）", 2, combos.size)
        assertTrue("方言下拉应有可选项", combos[0].itemCount > 0)
        assertEquals("推断策略下拉应有 SNAKE/CAMEL 两项", 2, combos[1].itemCount)
        // 20260918 用户真机反馈回归守卫：下拉项须为**本地化文案**，不得把枚举原值当显示项
        // （原实现 comboBox(listOf(INFERENCE_SNAKE, INFERENCE_CAMEL)) 致中文界面显示裸值
        //  `SNAKE`/`CAMEL`，与全局设置页同项文案不一致）。断言显示文本 == 全局面板同一组 bundle 键。
        val inferenceLabels = (0 until combos[1].itemCount).map { combos[1].getItemAt(it).toString() }
        assertEquals(
            "推断策略下拉项文案须复用全局面板 bundle 键",
            listOf(
                MpSqlVisualizerBundle.message("settings.tableNameInference.snake"),
                MpSqlVisualizerBundle.message("settings.tableNameInference.camel"),
            ),
            inferenceLabels,
        )
        assertFalse(
            "下拉不得显示枚举原值（应显示本地化文案）：$inferenceLabels",
            inferenceLabels.any { it == PluginSettings.INFERENCE_SNAKE || it == PluginSettings.INFERENCE_CAMEL },
        )
        // 行标签亦须复用全局键（原项目级重复键措辞漂移：表名推断/添加表名前缀 等已收编）
        // 注：DSL v2 row(label) 生成的是普通 JLabel（非 JBLabel），故按 JLabel 收集
        val labels = all(comp).filterIsInstance<javax.swing.JLabel>().map { it.text }
        assertTrue(
            "行标签应复用全局键文案「${MpSqlVisualizerBundle.message("settings.tableNameInference")}」：$labels",
            labels.contains(MpSqlVisualizerBundle.message("settings.tableNameInference")),
        )
        val texts = all(comp).filterIsInstance<JTextField>().filter { it !is com.intellij.ui.components.JBCheckBox }
        // 逻辑删除值 + 表前缀 + 表后缀 + 忽略字段
        assertEquals("应有 4 个文本框", 4, texts.size)
        cfg.disposeUIResources()
    }

    fun testIsModified_构造后未改动为false() {
        val cfg = newConfig()
        cfg.createComponent()
        assertFalse("构造后未改动 isModified 应为 false", cfg.isModified())
        cfg.disposeUIResources()
    }

    fun test勾选使用项目设置_isModified翻转且apply写回() {
        val cfg = newConfig()
        val comp = cfg.createComponent()
        val check = all(comp).filterIsInstance<JCheckBox>().first()
        assertFalse(cfg.isModified())
        check.doClick() // 触发 actionPerformed；isModified 由逐项比对得出（无标志位）
        assertTrue("勾选后应标记为已修改", cfg.isModified())
        cfg.apply()
        assertFalse("apply 后 isModified 应复位", cfg.isModified())
        assertTrue(
            "apply 应将使用项目设置写回",
            ProjectPluginSettings.getInstance(myFixture.project).state.useProjectSettings,
        )
        cfg.disposeUIResources()
    }

    fun testApply_十一项字段全部写回() {
        val global = PluginSettings.getInstance().state
        val cfg = newConfig()
        val comp = cfg.createComponent()
        val checks = all(comp).filterIsInstance<JCheckBox>()
        val useProject = checks[0]
        val replaceParams = checks[1]
        val formatSql = checks[2]
        val injectLogicDelete = checks[3]
        // O-58：新覆盖项（下标 4 = 既有 4 个之后），勾到与全局相反的值以便断言写回
        val columnCalibration = checks[4]
        val combos = all(comp).filterIsInstance<JComboBox<*>>()
        val texts = all(comp).filterIsInstance<JTextField>().filter { it !is com.intellij.ui.components.JBCheckBox }

        clicks(useProject, true)
        clicks(replaceParams, false)
        clicks(formatSql, false)
        clicks(injectLogicDelete, false)
        clicks(columnCalibration, !global.columnCalibrationEnabled) // O-58：勾到与全局相反
        combos[0].selectedItem = "PostgreSQL"
        texts[0].text = "1" // 逻辑删除值
        texts[1].text = "t_" // 表前缀
        texts[2].text = "_log" // 表后缀
        // 推断策略下拉持有 data class 选项（值+本地化文案），按 getItemAt 选第二项（CAMEL）
        // ——直接赋裸值 "CAMEL" 会因 equals 不匹配静默 return（JDK17 setSelectedItem 语义）
        combos[1].selectedItem = combos[1].getItemAt(1)
        texts[3].text = " temp ; created " // 忽略字段（apply 应 trim）

        assertTrue(cfg.isModified())
        cfg.apply()
        assertFalse(cfg.isModified())

        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        assertTrue(st.useProjectSettings)
        assertEquals(false, st.replaceParams)
        assertEquals(false, st.formatSql)
        assertEquals(false, st.injectLogicDelete)
        assertEquals("PostgreSQL", st.dialect)
        assertEquals("1", st.logicDeleteValue)
        assertEquals("t_", st.tablePrefix)
        assertEquals("_log", st.tableSuffix)
        assertEquals(PluginSettings.INFERENCE_CAMEL, st.tableNameInference)
        assertEquals("temp ; created", st.ignoredFields)
        // O-58：新覆盖项按控件态写回（与全局相反 → 必须落进 state，不能被「与全局同值不写」门控吞掉）
        assertEquals(!global.columnCalibrationEnabled, st.columnCalibration)
        cfg.disposeUIResources()
    }

    /**
     * O-58 行为覆盖（不是计数对齐）：新增覆盖项「列名数据源校正」的显示与写回链路，
     * 与既有三个布尔覆盖（replaceParams / formatSql / injectLogicDelete）同范式。
     *
     * 两个方向各断言一次：① state 为 null（跟随全局）且与全局同值 → apply **不得**把 null 钉成
     * 硬覆盖值（T-TS-FixG7 的「跟随全局不被静默改写」语义）；② 勾到与全局相反 → isModified 翻转、
     * apply 必须真的写回（否则新设置项是个改不动的摆设）。
     */
    fun testO58列名校正开关_显示与写回链路() {
        val global = PluginSettings.getInstance().state
        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        global.columnCalibrationEnabled = false
        st.useProjectSettings = true
        st.columnCalibration = null

        val cfg = newConfig()
        val comp = cfg.createComponent()
        val check = checksOf(comp)[4]
        assertFalse("state 为 null 时应显示全局值 false", check.isSelected)
        assertFalse("与全局同值（null 跟随全局）时不得报已修改", cfg.isModified())
        cfg.apply()
        assertNull("未勾选且与全局同值 → apply 不得把 null 钉成硬覆盖值", st.columnCalibration)

        check.doClick()
        assertTrue("勾到与全局相反后应报已修改", cfg.isModified())
        cfg.apply()
        assertEquals("apply 应写入项目级覆盖值 true", true, st.columnCalibration)
        assertFalse("apply 后应复位（控件显示值 ≡ 派生 state）", cfg.isModified())
        cfg.disposeUIResources()
    }

    fun testReset_空值控件回灌全局值且回写不改state() {
        val global = PluginSettings.getInstance().state
        global.tablePrefix = "g_"
        global.replaceParams = false
        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        st.useProjectSettings = true
        st.replaceParams = null
        st.tablePrefix = ""

        val cfg = newConfig()
        val comp = cfg.createComponent()
        val checks = all(comp).filterIsInstance<JCheckBox>()
        val texts = all(comp).filterIsInstance<JTextField>().filter { it !is com.intellij.ui.components.JBCheckBox }
        // null → 全局值；空字符串 → 全局值
        assertEquals("replaceParams null 应灌全局值", false, checks[1].isSelected)
        assertEquals("tablePrefix 空应灌全局值", "g_", texts[1].text)
        assertFalse("reset 前未改动不应 modified", cfg.isModified())

        // 控件改回全局一致值后 reset 应还原（不改 state）
        texts[1].text = "x_"
        assertTrue(cfg.isModified())
        cfg.reset()
        assertEquals("reset 后 tablePrefix 控件回灌 state+全局兜底", "g_", texts[1].text)
        assertFalse(cfg.isModified())
        assertEquals("reset 不应写 state", "", st.tablePrefix)
        cfg.disposeUIResources()
    }

    // ------------------------------------------------------------------
    // T-ProjectSettingsScope P1/P2：总开关分态呈现 + 「重置为跟随全局」
    // ------------------------------------------------------------------

    /**
     * 造一份「项目自定义值」与「与之逐项不同的全局值」，用于分态呈现断言。
     * 全局 `dialectAuto=false` 是关键：否则关闭态方言显示的是「智能推断」哨兵项而非全局方言名。
     */
    private fun seedDivergentValues() {
        val global = PluginSettings.getInstance().state
        global.dialectAuto = false
        global.dialect = "MySQL"
        global.replaceParams = false
        global.formatSql = true
        global.injectLogicDelete = false
        global.logicDeleteValue = "g1"
        global.tablePrefix = "g_"
        global.tableSuffix = "_g"
        global.tableNameInference = PluginSettings.INFERENCE_CAMEL
        global.ignoredFields = "gIgnore"
        global.columnCalibrationEnabled = true // O-58

        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        st.useProjectSettings = false
        st.columnCalibration = false // O-58：与全局相反，供分态呈现断言
        st.dialectAuto = false
        st.dialect = "PostgreSQL"
        st.replaceParams = true
        st.formatSql = false
        st.injectLogicDelete = true
        st.logicDeleteValue = "p1"
        st.tablePrefix = "p_"
        st.tableSuffix = "_p"
        st.tableNameInference = PluginSettings.INFERENCE_SNAKE
        st.ignoredFields = "pIgnore"
    }

    private fun checksOf(comp: java.awt.Component) = all(comp).filterIsInstance<JCheckBox>()

    private fun combosOf(comp: java.awt.Component) = all(comp).filterIsInstance<JComboBox<*>>()

    private fun textsOf(comp: java.awt.Component) = all(comp).filterIsInstance<JTextField>()

    /** P1 用例一：关闭态 9 项一律显示全局生效值（页面所见即运行时所用），并置灰。 */
    fun test关闭总开关_九项控件显示全局生效值且置灰() {
        seedDivergentValues()
        val cfg = newConfig()
        val comp = cfg.createComponent()
        val checks = checksOf(comp)
        val combos = combosOf(comp)
        val texts = textsOf(comp)

        assertEquals("关闭态方言应显示全局方言名", "MySQL", combos[0].selectedItem)
        assertEquals("关闭态 replaceParams 应显示全局值", false, checks[1].isSelected)
        assertEquals("关闭态 formatSql 应显示全局值", true, checks[2].isSelected)
        assertEquals("关闭态 injectLogicDelete 应显示全局值", false, checks[3].isSelected)
        assertEquals("关闭态列名校正应显示全局值", true, checks[4].isSelected) // O-58
        assertEquals("关闭态 logicDeleteValue 应显示全局值", "g1", texts[0].text)
        assertEquals("关闭态 tablePrefix 应显示全局值", "g_", texts[1].text)
        assertEquals("关闭态 tableSuffix 应显示全局值", "_g", texts[2].text)
        assertEquals("关闭态 ignoredFields 应显示全局值", "gIgnore", texts[3].text)
        assertEquals(
            "关闭态推断策略应显示全局值",
            MpSqlVisualizerBundle.message("settings.tableNameInference.camel"),
            combos[1].selectedItem.toString(),
        )

        assertFalse("关闭态方言下拉应置灰", combos[0].isEnabled)
        assertFalse("关闭态 replaceParams 应置灰", checks[1].isEnabled)
        assertFalse("关闭态 formatSql 应置灰", checks[2].isEnabled)
        assertFalse("关闭态 injectLogicDelete 应置灰", checks[3].isEnabled)
        assertFalse("关闭态列名校正应置灰", checks[4].isEnabled) // O-58
        assertFalse("关闭态 logicDeleteValue 应置灰", texts[0].isEnabled)
        assertFalse("关闭态 tablePrefix 应置灰", texts[1].isEnabled)
        assertFalse("关闭态 tableSuffix 应置灰", texts[2].isEnabled)
        assertFalse("关闭态 ignoredFields 应置灰", texts[3].isEnabled)
        assertFalse("关闭态推断策略下拉应置灰", combos[1].isEnabled)
        cfg.disposeUIResources()
    }

    /** P1 用例二：关闭态 apply 不得把「显示的全局值」写成项目自定义值（钉住静默丢数据）。 */
    fun test关闭总开关_apply不写回九项残值() {
        seedDivergentValues()
        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        val cfg = newConfig()
        cfg.createComponent()
        cfg.apply()

        assertEquals("apply 不得覆盖方言残值", "PostgreSQL", st.dialect)
        assertEquals("apply 不得覆盖 dialectAuto 残值", false, st.dialectAuto)
        assertEquals("apply 不得覆盖 replaceParams 残值", true, st.replaceParams)
        assertEquals("apply 不得覆盖 formatSql 残值", false, st.formatSql)
        assertEquals("apply 不得覆盖 injectLogicDelete 残值", true, st.injectLogicDelete)
        assertEquals("apply 不得覆盖逻辑删除值残值", "p1", st.logicDeleteValue)
        assertEquals("apply 不得覆盖表前缀残值", "p_", st.tablePrefix)
        assertEquals("apply 不得覆盖表后缀残值", "_p", st.tableSuffix)
        assertEquals("apply 不得覆盖推断策略残值", PluginSettings.INFERENCE_SNAKE, st.tableNameInference)
        assertEquals("apply 不得覆盖忽略字段残值", "pIgnore", st.ignoredFields)
        assertEquals("apply 不得覆盖列名校正残值", false, st.columnCalibration) // O-58
        assertFalse("总开关自身仍按控件态写回", st.useProjectSettings)
        cfg.disposeUIResources()

        // 第二段：关闭态下「空/null = 跟随全局」的字段，apply 后必须仍为空/null——
        // 修复前会把显示的全局值写成硬覆盖值（state 语义被静默改写：跟随全局 → 覆盖为当时全局值）
        val st2 = ProjectPluginSettings.getInstance(myFixture.project).state
        st2.useProjectSettings = false
        st2.tablePrefix = ""
        st2.replaceParams = null
        val cfg2 = newConfig()
        cfg2.createComponent()
        cfg2.apply()
        assertEquals("关闭态 apply 不得把空字段写成硬覆盖值", "", st2.tablePrefix)
        assertNull("关闭态 apply 不得把 null 开关写成硬覆盖值", st2.replaceParams)
        cfg2.disposeUIResources()
    }

    /** P1 用例三：关闭态 isModified 恒 false（否则控件显示全局值 vs state 残值会恒报「已修改」）。 */
    fun test关闭总开关_isModified恒false() {
        seedDivergentValues()
        val cfg = newConfig()
        cfg.createComponent()
        assertFalse("关闭态（控件显示全局值、state 存残值）不得报已修改", cfg.isModified())
        cfg.apply()
        assertFalse("关闭态 apply 后仍不得报已修改", cfg.isModified())
        cfg.disposeUIResources()

        val reopened = newConfig()
        reopened.createComponent()
        assertFalse("关闭态重开设置页不得报已修改", reopened.isModified())
        reopened.disposeUIResources()
    }

    /** P1 用例四：切换总开关即时联动 9 项的可用性与显示值（无需 apply）。 */
    fun test切换总开关_九项可用性与显示值联动() {
        seedDivergentValues()
        val cfg = newConfig()
        val comp = cfg.createComponent()
        val check = checksOf(comp).first()
        val combos = combosOf(comp)
        val texts = textsOf(comp)

        assertFalse("初始总开关应关闭", check.isSelected)
        assertFalse("初始关闭态应置灰", texts[1].isEnabled)
        assertEquals("初始关闭态应显示全局值", "g_", texts[1].text)
        assertEquals("初始关闭态方言应显示全局值", "MySQL", combos[0].selectedItem)

        check.doClick() // 勾选：立即变为可编辑并显示项目自定义值
        assertTrue("勾选后应可编辑", texts[1].isEnabled)
        assertEquals("勾选后应显示项目自定义值", "p_", texts[1].text)
        assertEquals("勾选后方言应显示项目自定义值", "PostgreSQL", combos[0].selectedItem)
        assertEquals("勾选后 replaceParams 应显示项目自定义值", true, checksOf(comp)[1].isSelected)
        assertEquals("勾选后列名校正应显示项目自定义值", false, checksOf(comp)[4].isSelected) // O-58

        check.doClick() // 取消勾选：回到置灰 + 全局值
        assertFalse("取消勾选后应置灰", texts[1].isEnabled)
        assertEquals("取消勾选后应回到全局值", "g_", texts[1].text)
        assertEquals("取消勾选后方言应回到全局值", "MySQL", combos[0].selectedItem)

        check.doClick() // 再次勾选：项目自定义值仍在（未被全局值覆盖）
        assertTrue("再次勾选应可编辑", texts[1].isEnabled)
        assertEquals("再次勾选应恢复项目自定义值", "p_", texts[1].text)
        assertEquals("再次勾选方言应恢复项目自定义值", "PostgreSQL", combos[0].selectedItem)
        cfg.disposeUIResources()
    }

    /** P2：重置按钮清空 9 项（dialectAuto 一并归 null），总开关保持不动。 */
    fun test重置为跟随全局_九项归零且总开关保持() {
        val global = PluginSettings.getInstance().state
        global.tablePrefix = "g_"
        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        st.useProjectSettings = true
        st.dialectAuto = false
        st.dialect = "PostgreSQL"
        st.replaceParams = true
        st.formatSql = false
        st.injectLogicDelete = true
        st.logicDeleteValue = "1"
        st.tablePrefix = "p_"
        st.tableSuffix = "_p"
        st.tableNameInference = PluginSettings.INFERENCE_CAMEL
        st.ignoredFields = "pIgnore"

        val cfg = newConfig()
        val comp = cfg.createComponent()
        val resetButton = all(comp).filterIsInstance<JButton>()
            .firstOrNull { it.text == MpSqlVisualizerBundle.message("settings.project.reset") }
        assertNotNull("项目页应有「重置为跟随全局」按钮", resetButton)
        resetButton!!.doClick()

        assertEquals("dialect 应归空", "", st.dialect)
        assertNull("dialectAuto 应归 null（跟随全局）", st.dialectAuto)
        assertNull("replaceParams 应归 null", st.replaceParams)
        assertNull("formatSql 应归 null", st.formatSql)
        assertNull("injectLogicDelete 应归 null", st.injectLogicDelete)
        assertEquals("logicDeleteValue 应归空", "", st.logicDeleteValue)
        assertEquals("tablePrefix 应归空", "", st.tablePrefix)
        assertEquals("tableSuffix 应归空", "", st.tableSuffix)
        assertEquals("tableNameInference 应归空", "", st.tableNameInference)
        assertEquals("ignoredFields 应归空", "", st.ignoredFields)
        assertNull("columnCalibration 应归 null（跟随全局）", st.columnCalibration) // O-58
        assertTrue("总开关不得被重置按钮改动", st.useProjectSettings)

        // 控件随之刷新：开启态下 9 项全空 = 跟随全局，故显示全局值
        assertEquals("重置后控件应回灌全局值", "g_", textsOf(comp)[1].text)
        assertFalse("重置后不应报已修改", cfg.isModified())
        cfg.disposeUIResources()
    }

    /**
     * T-TS-FixG7 阻断项守卫：开启态点「重置为跟随全局」后再改任意一项并 apply，
     * **其余 8 项必须仍为 ""/null（跟随全局）**，不得被钉成「当时的全局快照」。
     *
     * 缺陷路径（原实现）：resetToGlobal 把 9 项置空 → refreshControls 按 P1 回灌全局值
     * → 用户改一项使 isModified()==true → apply 无条件写回 9 个控件的显示值（= 全局值）
     * → state 里的「跟随全局」被静默改成硬覆盖：全局值日后变更本项目不再跟随，
     * 而 UI 数值与重置瞬间完全相同、用户看不出差别。作者原用例
     * [test重置为跟随全局_九项归零且总开关保持] 只断言点击瞬间的 state，从不调 apply，故给了假绿。
     */
    fun test重置后改一项再apply_其余八项仍跟随全局() {
        val global = PluginSettings.getInstance().state
        global.dialectAuto = false
        global.dialect = "MySQL"
        global.replaceParams = false
        global.formatSql = true
        global.injectLogicDelete = false
        global.logicDeleteValue = "g1"
        global.tablePrefix = "g_"
        global.tableSuffix = "_g"
        global.tableNameInference = PluginSettings.INFERENCE_CAMEL
        global.ignoredFields = "gIgnore"

        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        st.useProjectSettings = true
        st.dialectAuto = false
        st.dialect = "PostgreSQL"
        st.replaceParams = true
        st.formatSql = false
        st.injectLogicDelete = true
        st.logicDeleteValue = "p1"
        st.tablePrefix = "p_"
        st.tableSuffix = "_p"
        st.tableNameInference = PluginSettings.INFERENCE_SNAKE
        st.ignoredFields = "pIgnore"

        val cfg = newConfig()
        val comp = cfg.createComponent()
        val resetButton = all(comp).filterIsInstance<JButton>()
            .firstOrNull { it.text == MpSqlVisualizerBundle.message("settings.project.reset") }
        assertNotNull("项目页应有「重置为跟随全局」按钮", resetButton)
        resetButton!!.doClick()
        assertFalse("重置后不应报已修改", cfg.isModified())

        // 用户显式改一项（忽略字段），此时其余 8 项控件显示的仍是回灌进来的全局值
        textsOf(comp)[3].text = "edited"
        assertTrue("改一项后应报已修改", cfg.isModified())
        cfg.apply()
        assertFalse("apply 后应复位", cfg.isModified())

        assertEquals("dialect 应仍为跟随全局（空）", "", st.dialect)
        assertNull("dialectAuto 应仍为 null（跟随全局）", st.dialectAuto)
        assertNull("replaceParams 应仍为 null（跟随全局）", st.replaceParams)
        assertNull("formatSql 应仍为 null（跟随全局）", st.formatSql)
        assertNull("injectLogicDelete 应仍为 null（跟随全局）", st.injectLogicDelete)
        assertEquals("logicDeleteValue 应仍为空（跟随全局）", "", st.logicDeleteValue)
        assertEquals("tablePrefix 应仍为空（跟随全局）", "", st.tablePrefix)
        assertEquals("tableSuffix 应仍为空（跟随全局）", "", st.tableSuffix)
        assertEquals("tableNameInference 应仍为空（跟随全局）", "", st.tableNameInference)
        assertNull("columnCalibration 应仍为 null（跟随全局，不得被钉成全局快照）", st.columnCalibration) // O-58
        assertEquals("用户改过的那一项应写回", "edited", st.ignoredFields)
        cfg.disposeUIResources()
    }

    /**
     * 灌值守卫（T-TS-FixG7 补）：injectLogicDelete 的灌值此前无「非控件默认态」断言覆盖——
     * 种子 `global.injectLogicDelete=false` 与 JBCheckBox 默认未勾选同值，且开启态断言只覆盖
     * replaceParams，故把 [ProjectPluginSettingsConfigurable.refreshControls] 里该行灌值删掉，
     * 全套用例仍绿（假绿盲区）。
     *
     * 本用例让灌值表达式的两个分支各断言一次 **true**（控件默认值为 false，漏灌必红）：
     *  - 段一 关闭态分支（`else globalValue`）：全局 true + 项目 false → 关闭态须显示 true；
     *  - 段二 开启态分支（`stateValue ?: globalValue`）：全局 false + 项目 true → 开启态须显示 true。
     */
    fun test灌值守卫_逻辑删除注入两分支均显示非控件默认值() {
        val global = PluginSettings.getInstance().state
        val st = ProjectPluginSettings.getInstance(myFixture.project).state

        // 段一：关闭态显示全局值 true（漏灌则控件停在默认 false）
        global.injectLogicDelete = true
        st.useProjectSettings = false
        st.injectLogicDelete = false
        val off = newConfig()
        val offComp = off.createComponent()
        assertEquals(
            "关闭态 injectLogicDelete 应显示全局值 true",
            true,
            checksOf(offComp)[3].isSelected,
        )
        off.disposeUIResources()

        // 段二：开启态显示项目值 true（漏灌则控件停在默认 false）
        global.injectLogicDelete = false
        st.useProjectSettings = true
        st.injectLogicDelete = true
        val on = newConfig()
        val onComp = on.createComponent()
        assertEquals(
            "开启态 injectLogicDelete 应显示项目值 true",
            true,
            checksOf(onComp)[3].isSelected,
        )
        // 同一种子下切到关闭态：应回到全局值 false（防「断言方向写反」的假守卫）
        val master = checksOf(onComp).first()
        master.doClick()
        assertEquals(
            "关闭态 injectLogicDelete 应回到全局值 false",
            false,
            checksOf(onComp)[3].isSelected,
        )
        on.disposeUIResources()
    }

    fun test模块数据源学习可查看并独立清除() {
        val store = cn.song.mybatisplus.visualizer.sql.ModuleDataSourceBindingStore.getInstance(project)
        store.clear()
        store.remember("gradle:app", "uuid-1")
        store.remember("gradle:api", "uuid-2")
        val cfg = newConfig()
        cfg.createComponent()
        assertTrue(cfg.moduleBindingLabelForTest()?.text.orEmpty().contains("2"))
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(
            com.intellij.openapi.ui.TestDialog { com.intellij.openapi.ui.Messages.YES },
            testRootDisposable,
        )
        cfg.clearModuleBindingsButtonForTest()?.doClick()
        assertTrue(store.state.bindings.isEmpty())
        assertTrue(cfg.moduleBindingLabelForTest()?.text.orEmpty().contains("0"))
        cfg.disposeUIResources()
    }

    /**
     * P3 前置对照实验（落地为路由守卫）：设置搜索的 navigateTo 走
     * `Settings.find(类)` = `ConfigurableVisitor.findByType(类, 设置树根组)`，
     * 故「登记进搜索索引」是否等于可跳转，取决于项目级 configurable 在不在设置树里。
     *
     * 实测：项目上下文命中（ConfigurableWrapper），project=null（Welcome 屏设置）不命中——
     * 后者本就没有项目页，属已知边界。正对照：同一棵树必须能找到全局页，证明查找机制本身有效。
     */
    fun testP3路由断言_项目级configurable在项目上下文设置树中可被find命中() {
        val projectGroup = com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
            .getConfigurableGroup(myFixture.project, true)
        assertNotNull(
            "项目上下文设置树应含项目级设置页（否则搜索索引登记项点不动=幽灵项）",
            com.intellij.openapi.options.ex.ConfigurableVisitor.findByType(
                ProjectPluginSettingsConfigurable::class.java,
                listOf(projectGroup),
            ),
        )
        assertNotNull(
            "正对照：同一设置树应能找到全局设置页（证明查找机制非恒非空）",
            com.intellij.openapi.options.ex.ConfigurableVisitor.findByType(
                PluginSettingsFolderConfigurable::class.java,
                listOf(projectGroup),
            ),
        )
        val appGroup = com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
            .getConfigurableGroup(null as com.intellij.openapi.project.Project?, true)
        // 对照记录（非断言）：Welcome 屏设置（无项目）本就没有项目页，该上下文下条目点不动属已知边界
        println(
            "P3-PROBE project=null（Welcome 屏）树中项目页 = " +
                com.intellij.openapi.options.ex.ConfigurableVisitor.findByType(
                    ProjectPluginSettingsConfigurable::class.java,
                    listOf(appGroup),
                ),
        )
    }

    /** 渲染器片段拼接文本（helper 与 PluginSettingsFolderConfigurableTest 同款）。 */
    private fun joinedText(component: com.intellij.ui.SimpleColoredComponent): String {
        val sb = StringBuilder()
        val it = component.iterator()
        while (it.hasNext()) sb.append(it.next())
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderedLines(
        list: com.intellij.ui.components.JBList<*>,
        value: Any?,
    ): List<com.intellij.ui.SimpleColoredComponent> =
        all((list.cellRenderer as javax.swing.ListCellRenderer<Any?>)
            .getListCellRendererComponent(list, value, 0, false, false))
            .filterIsInstance<com.intellij.ui.SimpleColoredComponent>()

    /**
     * P3 登记守卫：11 项（总开关 + 10 项可覆盖字段，O-58 起新增列名数据源校正）逐个可被搜索命中，且命中行的「所属页面」
     * 指向项目页 displayName——即条目确实路由到 [ProjectPluginSettingsConfigurable]。
     * 遍历方式照抄 PluginSettingsFolderConfigurableTest（该文件归另一并行子任务所有，本地复制最小 helper）。
     */
    fun testP3搜索索引_十一个项目页条目可命中且路由项目页() {
        val cfg = PluginSettingsFolderConfigurable()
        val comp = cfg.createComponent()
        val list = all(comp).filterIsInstance<com.intellij.ui.components.JBList<*>>().first()
        val search = all(comp).filterIsInstance<com.intellij.ui.SearchTextField>().first()
        val pageName = MpSqlVisualizerBundle.message("configurable.settings.project.displayName")
        val projectKeys = listOf(
            "settings.project.useProjectSettings",
            "settings.dialect",
            "settings.replaceParams",
            "settings.formatSql",
            "settings.injectLogicDelete",
            "settings.logicDeleteValue",
            "settings.tablePrefix",
            "settings.tableSuffix",
            "settings.tableNameInference",
            "settings.ignoredFields",
            "settings.columnCalibration", // O-58：新增覆盖项必须同时进页面与搜索索引
        )
        for (key in projectKeys) {
            val label = MpSqlVisualizerBundle.message(key)
            search.text = label
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            val routedToProjectPage = (0 until list.model.size).any { idx ->
                val lines = renderedLines(list, list.model.getElementAt(idx))
                lines.size == 2 && joinedText(lines[1]) == pageName
            }
            assertTrue(
                "搜索「$label」应命中所属页面为「$pageName」的项目页条目",
                routedToProjectPage,
            )
        }
    }

    // ------------------------------------------------------------------
    // T-FS-08：开启态「清空文本项 = 跟随全局」的语义定案 + 三处同源
    // ------------------------------------------------------------------

    /**
     * T-FS-08 守卫：开启态把文本项清空后 apply = 用户显式声明「回到跟随全局」。
     *
     * 定案依据（为何不是「清空 = 显式空值」）：项目页**没有逐字段的「跟随全局」复选框**，
     * 单字段的「回到跟随全局」在 UI 上唯一的表达方式就是清空该字段（tooltip
     * [settings.project.field.tip] 与 [ProjectPluginSettings.State] 各文本字段的 KDoc 均已
     * 写死「空串 = 使用全局」）；页面上唯一的显式入口是整页「重置为跟随全局」按钮，
     * 若把清空定成「显式空值」，单字段的跟随全局将无 UI 入口。state 侧同向：9 项里 3 个
     * 布尔项是 `null = 跟随全局`，文本项 `空串 = 跟随全局`（[ProjectPluginSettings.effectiveXxx]
     * 一律 `isNotBlank()` 回退），改成「显式空值」需把 String 改 String? 并连带持久化格式
     * 与全部 effective 取值口径。
     *
     * 缺陷形态（修复前）：清空 → apply 把 state 写成空串（= 跟随全局）→ 控件仍停在空串，
     * 而 state 推导出的显示值是全局值 → [ProjectPluginSettingsConfigurable.isModified] 逐项比对
     * 恒报已修改（用户点「应用」后按钮不灭，重开设置页显示又变回全局值）。
     * 修复口径：apply 写回后按同一取值函数（[ProjectPluginSettingsConfigurable.refreshControls] →
     * shownText/expectedDialectItem）重新推导控件显示值，「显示 ≡ 派生(state)」不变量在 apply 后成立。
     */
    fun testTFS08_开启态清空文本项并apply_按跟随全局回灌且isModified复位() {
        val global = PluginSettings.getInstance().state
        global.logicDeleteValue = "g1"
        global.tablePrefix = "g_"
        global.tableSuffix = "_g"
        global.ignoredFields = "gIgnore"

        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        st.useProjectSettings = true
        st.logicDeleteValue = "p1"
        st.tablePrefix = "p_"
        st.tableSuffix = "_p"
        st.ignoredFields = "pIgnore"

        val cfg = newConfig()
        val comp = cfg.createComponent()
        val texts = textsOf(comp)
        assertEquals("文本项应为 4 个（逻辑删除值/表前缀/表后缀/忽略字段）", 4, texts.size)
        assertEquals(
            "开启态初始应显示项目自定义值",
            listOf("p1", "p_", "_p", "pIgnore"),
            texts.map { it.text },
        )
        assertFalse("初始不应报已修改", cfg.isModified())

        val globalShown = listOf("g1", "g_", "_g", "gIgnore")
        for (i in texts.indices) {
            texts[i].text = "" // 用户清空 = 回到跟随全局（页面无逐字段「跟随全局」开关，清空即该语义）
            assertTrue("清空第 ${i + 1} 项后应报已修改", cfg.isModified())
            cfg.apply()
            assertEquals(
                "清空第 ${i + 1} 项 apply 后控件应回灌全局生效值（页面所见即运行时所用）",
                globalShown[i],
                texts[i].text,
            )
            assertFalse("清空第 ${i + 1} 项 apply 后不得恒报已修改", cfg.isModified())
        }

        assertEquals("logicDeleteValue 应归空（跟随全局）", "", st.logicDeleteValue)
        assertEquals("tablePrefix 应归空（跟随全局）", "", st.tablePrefix)
        assertEquals("tableSuffix 应归空（跟随全局）", "", st.tableSuffix)
        assertEquals("ignoredFields 应归空（跟随全局）", "", st.ignoredFields)
        val settings = ProjectPluginSettings.getInstance(myFixture.project)
        assertEquals("运行时有效值应回落到全局", "g1", settings.effectiveLogicDeleteValue())
        assertEquals("运行时有效值应回落到全局", "g_", settings.effectiveTablePrefix())

        // 重开设置页同形：显示全局值、不报已修改（钉住「回灌后控件与 state 同源」）
        val reopened = newConfig()
        val reopenedComp = reopened.createComponent()
        assertEquals("重开后应显示全局生效值", globalShown, textsOf(reopenedComp).map { it.text })
        assertFalse("重开后不应报已修改", reopened.isModified())
        cfg.disposeUIResources()
        reopened.disposeUIResources()
    }

    /**
     * T-FS-08 同源守卫：apply 只在「控件显示值 != state 推导值」时写回（T-TS-FixG7），
     * 而「清空后 apply」必须真的写回（不能被该门控吞掉）——两者是同一条判据的两面。
     *
     * 本用例把「清空 = 跟随全局」在**已有覆盖的项目值**上验一次：state 非空时清空必须归空；
     * 若门控写成「state 非空即跳过」这类别的判据，此用例必红（state 会残留 p_，运行时不再跟随全局）。
     */
    fun testTFS08_清空已有覆盖值_apply后state归空而非残留() {
        val global = PluginSettings.getInstance().state
        global.tablePrefix = "g_"
        val st = ProjectPluginSettings.getInstance(myFixture.project).state
        st.useProjectSettings = true
        st.tablePrefix = "p_"

        val cfg = newConfig()
        val comp = cfg.createComponent()
        textsOf(comp)[1].text = ""
        cfg.apply()
        assertEquals("清空必须写回空串（回到跟随全局）", "", st.tablePrefix)
        assertEquals("有效值回落到全局", "g_", ProjectPluginSettings.getInstance(myFixture.project).effectiveTablePrefix())
        assertEquals("控件回灌全局值", "g_", textsOf(comp)[1].text)
        assertFalse("apply 后不应报已修改", cfg.isModified())
        cfg.disposeUIResources()
    }

    // ------------------------------------------------------------------
    // T-FS-09：navigateTo 高亮目标定位（DSL v2 生成的组件）
    // ------------------------------------------------------------------

    /**
     * T-FS-09 守卫：项目页 11 项搜索条目在其页面上**均能定位到高亮目标**（O-58 起新增列名数据源校正）。
     *
     * 缺陷形态（修复前）：[PluginSettingsFolderConfigurable] 的 `findComponent` 只认
     * `JBLabel`，而项目页走 DSL v2（`row(label)` 生成的是普通 `JLabel`）→ 6 行
     * （方言下拉 / 推断策略下拉 / 4 个文本框）的行标签定位不到，双击搜索项只跳页不闪烁，
     * 用户看到「跳了但不亮」的隐性缺陷；全局页用 `JBLabel(label)` 摆行标签，故不受影响。
     *
     * 边界（如实登记）：闪烁本身是视觉行为，headless 只能断言「目标可定位」这半边；
     * 真机走查见交付汇报的「环境受限未验证」清单。
     */
    fun testTFS09_十一项搜索条目在项目页均可定位到高亮目标() {
        val page = ProjectPluginSettingsConfigurable(myFixture.project).createComponent()
        val folder = PluginSettingsFolderConfigurable()
        val projectKeys = listOf(
            "settings.project.useProjectSettings",
            "settings.dialect",
            "settings.replaceParams",
            "settings.formatSql",
            "settings.injectLogicDelete",
            "settings.logicDeleteValue",
            "settings.tablePrefix",
            "settings.tableSuffix",
            "settings.tableNameInference",
            "settings.ignoredFields",
            "settings.columnCalibration", // O-58：新增覆盖项也必须能定位到高亮目标（名实相符）
        )
        val missed = projectKeys.filter {
            folder.findHighlightTargetForTest(page, MpSqlVisualizerBundle.message(it)) == null
        }
        assertTrue(
            "以下搜索条目的 hint 在项目页定位不到任何控件（双击条目会「跳了但不亮」）：$missed",
            missed.isEmpty(),
        )
        // 正对照：不存在的 hint 必须定位不到（证明查找不是恒真，上面的断言有判别力）
        assertNull(
            "正对照：乱串 hint 不得命中任何控件",
            folder.findHighlightTargetForTest(page, "TFS09-NO-SUCH-HINT-XYZ"),
        )
    }
}
