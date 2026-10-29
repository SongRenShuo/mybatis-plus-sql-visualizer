package cn.song.mybatisplus.visualizer.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * PluginSettings 持久化链路测试（中英文切换失效定位）：
 *  - PersistentStateComponent 契约：getState → loadState 往返（模拟 IDE 保存/重启加载）保留 language
 *  - Configurable apply/reset 链路：设置页切换 → state 写回 → 重新打开读取
 *  - 双注册检查：@Service 注解与 plugin.xml applicationService 并存的隐患验证
 */
class PluginSettingsPersistenceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
    }

    fun testStateRoundTripKeepsLanguage() {
        // 模拟 IDE 持久化：修改 state → getState（保存快照）→ loadState（重启加载）→ language 保留 en
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "en"
            val snapshot = settings.getState()
            assertEquals("getState 应反映最新 language", "en", snapshot.language)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(snapshot)
            assertEquals("loadState 后 language 保留 en", "en", fresh.state.language)
        } finally {
            settings.state.language = old
        }
    }

    fun testConfigurableApplyWritesLanguageThenResetReadsIt() {
        // 设置页链路：切 en → apply → state=en；重新打开（新 panel）reset → combo 选中 en
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "zh"
            // 模拟用户在语言下拉选择 English 后 apply（直接改 state 等价于 combo 写回路径）
            settings.state.language = "en"
            val configurable = AllSectionsConfigurable()
            configurable.createComponent()
            // reset 应从 state 读取 en
            configurable.reset()
            // 验证 panel 当前语言（通过再次 apply 不改变 state 证明 reset 已同步）。
            // M23.9：createComponent 返回 getRoot()（JComponent）而非面板本身，原 `panel is
            // PluginSettingsPanel` 恒 false 是死分支（警告暴露）——改经 panelForTest 取回表单面板
            val s = settings.state
            val reasons = mutableListOf<String>()
            val p = configurable.panelForTest()
            // 通过 apply 后 state 是否变化推断表单与 state 一致性（无法直接访问私有字段）
            val before = PluginSettings.State().apply {
                dialect = s.dialect; replaceParams = s.replaceParams; formatSql = s.formatSql
                showGutterIcon = s.showGutterIcon; injectLogicDelete = s.injectLogicDelete
                tablePrefix = s.tablePrefix; tableSuffix = s.tableSuffix; language = s.language
            }
            p.apply()
            if (s.dialect != before.dialect) reasons += "dialect ${before.dialect}->${s.dialect}"
            if (s.replaceParams != before.replaceParams) reasons += "replaceParams"
            if (s.formatSql != before.formatSql) reasons += "formatSql"
            if (s.showGutterIcon != before.showGutterIcon) reasons += "showGutterIcon"
            if (s.injectLogicDelete != before.injectLogicDelete) reasons += "injectLogicDelete"
            if (s.tablePrefix != before.tablePrefix) reasons += "tablePrefix ${before.tablePrefix}->${s.tablePrefix}"
            if (s.tableSuffix != before.tableSuffix) reasons += "tableSuffix ${before.tableSuffix}->${s.tableSuffix}"
            if (s.language != before.language) reasons += "language ${before.language}->${s.language}"
            assertTrue("reset 后表单与 state 不一致: $reasons", reasons.isEmpty())
            assertEquals("state.language 保持 en", "en", settings.state.language)
        } finally {
            settings.state.language = old
        }
    }

    fun testLanguageSwitchImmediateInBundle() {
        // 回归：M14.7 已修复的切换即时生效（bundle 读取链）
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        settings.state.language = "en"
        try {
            assertEquals("Interface language", cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("settings.language"))
        } finally {
            settings.state.language = old
        }
    }

    fun testLanguageWriteThroughOnSelection() {
        // M19（用户实测：设置切语言确定后重开仍原样）：平台 Settings 框架 OK 仅对「当前选中页」且
        // isModified()==true 的 configurable 调用 apply()（反编译 2026.2.1 ConfigurableEditor 验证），
        // 改完语言切到其他页再确定 → apply 被静默跳过 → 写穿保证选中即持久化、不依赖 apply 链路。
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            panel.selectLanguage("en")   // 模拟用户在下拉中选择 English——不调用 apply()
            assertEquals("写穿后 state.language 应立即为 en（不依赖 apply 链路）", "en", settings.state.language)
        } finally {
            settings.state.language = old
        }
    }

    fun testLanguageWriteThroughApplyEnabledAndIdempotent() {
        // M19.1（用户实测：写穿后「应用」按钮灰掉，只能点确定）：
        // 平台 Apply 按钮启用 = configurable.isModified()——写穿后表单与配置已一致，
        // 靠 languagePending「待应用」标记保持 isModified()=true；apply() 消费标记后幂等。
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            panel.selectLanguage("en")
            assertEquals("写穿后 state.language 已为 en", "en", settings.state.language)
            assertTrue("写穿后 Apply 应可用（isModified()=true）", panel.isModified())
            assertTrue("apply() 应报告语言变更（pending 消费）", panel.apply())
            assertFalse("再次 apply() 幂等（返回 false）", panel.apply())
            assertFalse("应用后 isModified() 恢复 false（Apply 灰属正常）", panel.isModified())
            assertEquals("state.language 保持 en", "en", settings.state.language)
            panel.selectLanguage("en")   // 重复选同值：写穿守卫应 no-op（不重新置 pending）
            assertFalse("重复选同值不重新置 pending", panel.isModified())
        } finally {
            settings.state.language = old
        }
    }

    fun testLanguageWriteThroughRefreshesCheckboxTexts() {
        // M19.2（用户实测：切换语言后右侧复选框文本仍中文、确定重开才变）：
        // 复选框为字段实例（构造时按当时语言固化文本），rebuild 重建布局不会更新其文本——
        // 修复：文本在 buildRows 内每轮重设。本用例验证 zh→en rebuild 后全部复选框为英文。
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            // O-58：新复选框的文案刷新正反两面各断一次（先 zh 后 en，防「断言方向写反」的假守卫）
            val zhTexts = mutableListOf<String>()
            collectCheckBoxTexts(panel.getRoot(), zhTexts)
            assertTrue(
                "zh 下 O-58 列名数据源校正开关应为中文文案: $zhTexts",
                zhTexts.any { it.contains("按数据源校正列名") },
            )
            // R33 T-ExplainStructured：EXPLAIN 结构化渲染开关文案正反两面（zh 侧）
            assertTrue(
                "zh 下 R33 EXPLAIN 结构化渲染开关应为中文文案: $zhTexts",
                zhTexts.any { it.contains("结构化渲染 EXPLAIN") },
            )
            // T-CountSimToggle：分页 COUNT 子开关文案正反两面（zh 侧）
            assertTrue(
                "zh 下 T-CountSimToggle 分页 COUNT 子开关应为中文文案: $zhTexts",
                zhTexts.any { it.contains("附分页 COUNT 查询预览") },
            )
            // T-DataSourceBadgeMode：数据源角标完整上下文开关文案正反两面（zh 侧，先 zh 后 en 防断言方向写反）
            assertTrue(
                "zh 下 T-DataSourceBadgeMode 数据源角标开关应为中文文案: $zhTexts",
                zhTexts.any { it.contains("数据源角标显示完整上下文") },
            )
            // 模拟用户选择 English（写穿）→ 重建表单（invokeLater 在测试环境不保证执行，直接调用）
            panel.selectLanguage("en")
            panel.rebuild()
            val texts = mutableListOf<String>()
            collectCheckBoxTexts(panel.getRoot(), texts)
            val hasCjk = { s: String -> s.any { it.code in 0x4E00..0x9FFF } }
            assertTrue("rebuild 后复选框不应残留中文: $texts", texts.none(hasCjk))
            assertTrue("rebuild 后复选框应含英文文案: $texts", texts.any { it.contains("Replace") } && texts.any { it.contains("Format") })
            assertTrue(
                "rebuild 后 O-58 列名数据源校正开关应刷新为英文文案: $texts",
                texts.any { it.contains("Calibrate column names") },
            )
            // R33 T-ExplainStructured：EXPLAIN 结构化渲染开关文案（en 侧）
            assertTrue(
                "rebuild 后 R33 EXPLAIN 结构化渲染开关应刷新为英文文案: $texts",
                texts.any { it.contains("Structured EXPLAIN") },
            )
            // T-CountSimToggle：分页 COUNT 子开关文案（en 侧）
            assertTrue(
                "rebuild 后 T-CountSimToggle 分页 COUNT 子开关应刷新为英文文案: $texts",
                texts.any { it.contains("Attach pagination COUNT query preview") },
            )
            // T-DataSourceBadgeMode：数据源角标完整上下文开关文案（en 侧）
            assertTrue(
                "rebuild 后 T-DataSourceBadgeMode 数据源角标开关应刷新为英文文案: $texts",
                texts.any { it.contains("full data source context") },
            )
            // M28：更新分区新增「启动时自动检查更新」开关（默认开）——复选框总数 4 → 5；
            // T-M31.14 SQL 日志组 +5；手动选择实体类开关 +1（T-M38+）；关闭工具窗口停止监听 +1；
            // T-TenantLine：多租户注入开关 +1；Hibernate 日志捕获开关 +1
            // F-6：INSERT 追加租户列开关 +1；数据权限占位提示开关 +1
            // M47：Spring JdbcTemplate 日志捕获开关 +1
            // F-43：自动保存历史 SQL 开关 +1
            // 2026-08-27 筛选精简：移除「显示类型筛选条」开关 -1（SQL 类型多选改面板工具栏直出）
            // F-44-7：源码位置自动注入开关 +1；T-SqlDuration：耗时显示开关 +1；
            // T-Dgm-04：JOIN 图原生框架渲染开关 +1（合并双方共同确认的最终基线）
            // JdbcTemplate SQL 字符串预览开关 +1
            // T-VPairAnno：竖向排列值行尾列名标注开关 +1
            // T-HistParam：字面量参数恢复开关 +1
            // T-AutoFold：SQL 预览字段较多时自动折叠开关 +1
            // M56 T-M56-08：增强捕获开关 + 脱敏开关 +2
            // T-UxOnboard T-UX-01：首用锚点引导通知开关 +1（界面节）
            // T-PreviewWhitespaceToggle O-44a：空白符可视化开关 +1（界面节，默认关＝不设置该属性）
            // O-58：列名数据源校正开关 +1（表名映射节）
            // O-72 T-XmlInlineSql：通用 XML 内嵌 SQL 检测开关 +1（界面节）
            // R2 W3 历史工作流：gutter 六分组开关 +6（mapperXml/annotation/jpaHql/jdbc/inlineFallback/other）
            // T-PerfSweep16 F46（40ee1fd）：新增「mapper XML 语法高亮」开关（xmlSynHiCheck）+1 后为 38；
            // R12 T-PaginationSim：分页拦截器模拟开关（paginationSimCheck）+1 后为 39；
            // R10 T-ShardingSphereCompat：ShardingSphere 双段日志捕获开关（logShardingSphereEnabledCheck）+1 后为 40；
            // R33 T-ExplainStructured：EXPLAIN 结构化渲染开关（explainStructuredCheck）+1 后为 41；
            // R60 T-LaneB D96：展示/导出链脱敏开关（displayMaskCheck）+1 后为 42；
            // T-CountSimToggle：分页 COUNT 子开关（paginationCountCheck）+1 后为 43；
            // T-DataSourceBadgeMode：数据源角标完整上下文开关（dataSourceBadgeDetailCheck）+1 后为 44；
            // R90：独立默认关闭的原始计划保留授权 +1 后为 45，不与结构化渲染共用；
            // T-JarEmbedded 优化方案 2：后台扫描依赖库以反查实体开关（librarySweepCheck）+1 后为 46。
            // T-MapperNavCoexistYield：与 MyBatisCodeHelperPro 共存时让出 Mapper 跳转图标开关（mapperNavYieldCheck）+1 后为 47。
            // 同步先例 ccd0f74（R2 设置页六组 gutter 复选框曾 31→37 同步）
            assertTrue(texts.any { it.contains("Retain raw EXPLAIN plans") })
            assertEquals("复选框总数应为 47", 47, texts.size)
        } finally {
            settings.state.language = old
        }
    }

    /** 收集组件树中全部 JCheckBox 文本（M19.2 复选框文案刷新断言辅助）。 */
    /** 递归收集全部 JBLabel 文本（按组件树布局顺序；T-M31.8 分组重构后行标签在组面板第二层）。 */
    private fun collectLabelTexts(component: java.awt.Component): List<String> {
        val texts = mutableListOf<String>()
        if (component is com.intellij.ui.components.JBLabel) texts += component.text ?: ""
        if (component is java.awt.Container) {
            component.components.forEach { texts += collectLabelTexts(it) }
        }
        return texts.filter { it.isNotBlank() }
    }

    private fun collectCheckBoxTexts(component: java.awt.Component, out: MutableList<String>) {
        if (component is javax.swing.JCheckBox) out += component.text
        if (component is java.awt.Container) {
            component.components.forEach { collectCheckBoxTexts(it, out) }
        }
    }

    /**
     * O-58：新开关必须经持久化往返保持——本轮只补了「文案刷新」与「计数」，没补「改了值 → 落盘 →
     * 重载 → 值保持」，而 getState/loadState 往返才是持久化契约本体（文案刷新与它无关）。
     */
    fun testStateRoundTripKeepsColumnCalibration() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.columnCalibrationEnabled = false
            val saved = settings.getState().copy()          // 模拟落盘快照
            settings.loadState(PluginSettings.State())        // 清成出厂默认（true）
            assertTrue("清空后应为出厂默认 true", settings.state.columnCalibrationEnabled)
            settings.loadState(saved)                         // 模拟重启重载
            assertFalse("往返后 columnCalibrationEnabled 应保持 false", settings.state.columnCalibrationEnabled)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testStateRoundTripKeepsExplainStructuredRender() {
        // R33 T-ExplainStructured：EXPLAIN 结构化渲染开关随 State 持久化往返保留（O-58 同款契约）；
        // T-DevAcceptFix ②（20261006 验收，D67 采 B 案）：默认关——EXPLAIN 默认走原生控制台；
        // 结构化通道保留一版（显式开启的值随 State 往返保留）。
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.explainStructuredRender = true
            val saved = settings.getState().copy()          // 模拟落盘快照
            settings.loadState(PluginSettings.State())        // 清成出厂默认（false）
            assertFalse("清空后应为出厂默认 false（20261006 验收定案）", settings.state.explainStructuredRender)
            settings.loadState(saved)                         // 模拟重启重载
            assertTrue("往返后显式开启值应保持 true", settings.state.explainStructuredRender)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testStateRoundTripKeepsDataSourceBadgeDetail() {
        // T-DataSourceBadgeMode（O-133）：数据源角标完整上下文开关随 State 持久化往返保留（O-58 同款契约）；
        // 默认关＝仅显示数据库类型（用户 2026-10-09 需求定案），显式开启的值随 State 往返保留。
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            assertFalse("出厂默认应为关（仅显示数据库类型）", PluginSettings.State().dataSourceBadgeDetail)
            settings.state.dataSourceBadgeDetail = true
            val saved = settings.getState().copy()          // 模拟落盘快照
            settings.loadState(PluginSettings.State())        // 清成出厂默认（false）
            assertFalse("清空后应为出厂默认 false", settings.state.dataSourceBadgeDetail)
            settings.loadState(saved)                         // 模拟重启重载
            assertTrue("往返后显式开启值应保持 true", settings.state.dataSourceBadgeDetail)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testStateRoundTripKeepsTablePrefixSuffix() {
        // T-M16.2-03：表前缀/后缀随 State 持久化往返保留（getState → loadState，模拟 IDE 保存/重启加载）
        val settings = PluginSettings.getInstance()
        val oldPrefix = settings.state.tablePrefix
        val oldSuffix = settings.state.tableSuffix
        try {
            settings.state.tablePrefix = "sys_"
            settings.state.tableSuffix = "_log"
            val snapshot = settings.getState()
            assertEquals("getState 应反映最新 tablePrefix", "sys_", snapshot.tablePrefix)
            assertEquals("getState 应反映最新 tableSuffix", "_log", snapshot.tableSuffix)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(snapshot)
            assertEquals("loadState 后 tablePrefix 保留", "sys_", fresh.state.tablePrefix)
            assertEquals("loadState 后 tableSuffix 保留", "_log", fresh.state.tableSuffix)
        } finally {
            settings.state.tablePrefix = oldPrefix
            settings.state.tableSuffix = oldSuffix
        }
    }

    fun testAutoFoldStateRoundTripKeepsDefaults() {
        // T-AutoFold：自动折叠两字段随 State 持久化往返保留；默认值 = 关 / 阈值 10（关闭时零行为变更）
        val settings = PluginSettings.getInstance()
        val oldEnabled = settings.state.autoFoldEnabled
        val oldThreshold = settings.state.autoFoldFieldThreshold
        try {
            settings.state.autoFoldEnabled = true
            settings.state.autoFoldFieldThreshold = 7
            val snapshot = settings.getState()
            assertEquals("getState 应反映最新 autoFoldEnabled", true, snapshot.autoFoldEnabled)
            assertEquals("getState 应反映最新 autoFoldFieldThreshold", 7, snapshot.autoFoldFieldThreshold)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(snapshot)
            assertEquals("loadState 后 autoFoldEnabled 保留", true, fresh.state.autoFoldEnabled)
            assertEquals("loadState 后 autoFoldFieldThreshold 保留", 7, fresh.state.autoFoldFieldThreshold)
            // 出厂默认：关 / 10（测试 JVM 内 State() 新实例即出厂配置）
            val defaults = PluginSettings.State()
            assertFalse("自动折叠默认应关闭", defaults.autoFoldEnabled)
            assertEquals("自动折叠阈值默认应为 10", 10, defaults.autoFoldFieldThreshold)
        } finally {
            settings.state.autoFoldEnabled = oldEnabled
            settings.state.autoFoldFieldThreshold = oldThreshold
        }
    }

    fun testStorageRoamingDisabledContract() {
        // T-M16.3-01（用户报告语言切换保存失效根因）：官方规范（IntelliJ Platform SDK——Services）——
        // **应用级轻服务（@Service(APP)）+ PersistentStateComponent 必须禁用 roaming**
        // （roamingType = RoamingType.DISABLED）；默认 RoamingType.DEFAULT 使状态进入设置同步
        // （Settings Sync）管理通道，与轻服务持久化不兼容 → 确定后重开/重启设置回滚。
        // 编译期契约断言，防回归。
        val state = PluginSettings::class.java.getAnnotation(com.intellij.openapi.components.State::class.java)
        assertNotNull("@State 注解应存在（PersistentStateComponent 持久化声明）", state)
        val storage = state!!.storages.first()
        assertEquals(
            "应用级轻服务的存储必须禁用 roaming",
            com.intellij.openapi.components.RoamingType.DISABLED,
            storage.roamingType,
        )
    }

    fun testStateRoundTripKeepsM19_4TableConfig() {
        // T-M19.4-02：四项设置增强随 State 持久化往返保留（getState → loadState，模拟 IDE 保存/重启加载）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tableNameInference = PluginSettings.INFERENCE_CAMEL
            settings.state.ignoredFields = "transientField; remark"
            settings.state.removeTablePrefix = "t_;tmp_"
            settings.state.removeTableSuffix = "_log;_bak"
            val saved = settings.getState()
            assertEquals("getState 应反映最新 tableNameInference", PluginSettings.INFERENCE_CAMEL, saved.tableNameInference)
            assertEquals("getState 应反映最新 ignoredFields", "transientField; remark", saved.ignoredFields)
            assertEquals("getState 应反映最新 removeTablePrefix", "t_;tmp_", saved.removeTablePrefix)
            assertEquals("getState 应反映最新 removeTableSuffix", "_log;_bak", saved.removeTableSuffix)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(saved)
            assertEquals("loadState 后 tableNameInference 保留", PluginSettings.INFERENCE_CAMEL, fresh.state.tableNameInference)
            assertEquals("loadState 后 ignoredFields 保留", "transientField; remark", fresh.state.ignoredFields)
            assertEquals("loadState 后 removeTablePrefix 保留", "t_;tmp_", fresh.state.removeTablePrefix)
            assertEquals("loadState 后 removeTableSuffix 保留", "_log;_bak", fresh.state.removeTableSuffix)
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testPanelResetApplyM19_4RowsSynced() {
        // T-M19.4-02：设置面板 4 新行 isModified/apply/reset 联动——reset 从 state 回读表单，
        // 表单与配置一致时 isModified=false、apply 不改变 state（通过 apply 后 state 是否变化
        // 推断表单与 state 一致性，无法直接访问私有字段）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tableNameInference = PluginSettings.INFERENCE_CAMEL
            settings.state.ignoredFields = "a; b"
            settings.state.removeTablePrefix = "t_"
            settings.state.removeTableSuffix = "_log"
            val panel = PluginSettingsPanel(settings)
            panel.reset()
            assertFalse("reset 后表单与 state 一致 → isModified=false", panel.isModified())
            val before = settings.state.copy()
            panel.apply()
            assertEquals("apply 不应改变 state（reset 已同步表单）", before, settings.state)
            assertFalse("apply 后 isModified 仍为 false", panel.isModified())
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testPanelTableConfigRowsOrderAndPlaceholderHint() {
        // T-M19.4（用户反馈调整）：行顺序 = 添加表前缀 → 添加表后缀 → 移除表名前缀 → 移除表名后缀 →
        // 忽略字段名 → 表名推断策略；仅「移除表名前缀/移除表名后缀/忽略字段名」3 个支持多值的输入栏
        // 空时显示灰色提示「多个时使用;来分隔」（添加表前缀/后缀为**单值**，无提示），
        // 语言切换 rebuild 后 placeholder 随语言刷新
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.language = "zh"
            val panel = PluginSettingsPanel(settings)
            // 行顺序：递归收集全部行标签文本（T-M31.8 分组重构后行标签在组面板第二层，
            // 直接子组件只含 TitledSeparator + 组面板——须递归；相对顺序断言不受组标题干扰）
            val labels = collectLabelTexts(panel.getRoot())
            val expected = listOf("添加表前缀", "添加表后缀", "移除表名前缀", "移除表名后缀", "忽略字段名", "表名推断策略")
            val indices = expected.map { labels.indexOf(it) }
            assertTrue(
                "行顺序应为 $expected，实际行标签: $labels",
                indices.none { it < 0 } && indices.zipWithNext().all { (a, b) -> a < b },
            )
            // placeholder：3 个支持多值的输入栏 + 日志过滤关键字 + F-43 保存路径 + F-43 文件名模板
            // + T-HistoryFileRetention 文件保留上限（默认 30 提示）
            // （添加表前缀/后缀单值 + shortcut 只读框均不计入）
            val zhHints = mutableListOf<String>()
            collectTextFieldEmptyTexts(panel.getRoot(), zhHints)
            assertEquals("placeholder 应设置于 7 个输入栏（+F-43 保存路径+文件名模板+文件保留上限）", 7, zhHints.size)
            // 多值输入栏 + 日志过滤关键字的 placeholder 为「多个时使用;来分隔」
            val multiValueHints = zhHints.count { it == "多个时使用;来分隔" }
            assertEquals("多值输入栏 placeholder 数量应为 4", 4, multiValueHints)

            // 切英文 rebuild 后 placeholder 随语言刷新
            settings.state.language = "en"
            panel.rebuild()
            val enHints = mutableListOf<String>()
            collectTextFieldEmptyTexts(panel.getRoot(), enHints)
            assertEquals("英文 placeholder 数量为 7（+F-43 保存路径+文件名模板+文件保留上限）", 7, enHints.size)
        } finally {
            settings.loadState(snapshot)
        }
    }

    /** 收集组件树中全部 JBTextField 的非空 placeholder 提示（emptyText；辅助断言用）。 */
    private fun collectTextFieldEmptyTexts(component: java.awt.Component, out: MutableList<String>) {
        if (component is com.intellij.ui.components.JBTextField && component.emptyText.text.isNotBlank()) {
            out += component.emptyText.text
        }
        if (component is java.awt.Container) {
            component.components.forEach { collectTextFieldEmptyTexts(it, out) }
        }
    }

    // ------------------------------------------------------------------
    // T-TenantLine：多租户注入配置持久化
    // ------------------------------------------------------------------

    fun testTenantLineConfigPersistence() {
        // T-TenantLine：injectTenantLine + tenantColumnName 随 State 持久化往返保留
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.injectTenantLine = false
            settings.state.tenantColumnName = "org_id"
            val saved = settings.getState()
            assertEquals("getState 应反映最新 injectTenantLine", false, saved.injectTenantLine)
            assertEquals("getState 应反映最新 tenantColumnName", "org_id", saved.tenantColumnName)
            // 模拟重启：新实例 loadState（IDE 从磁盘反序列化后调用）
            val fresh = PluginSettings()
            fresh.loadState(saved)
            assertEquals("loadState 后 injectTenantLine 保留", false, fresh.state.injectTenantLine)
            assertEquals("loadState 后 tenantColumnName 保留", "org_id", fresh.state.tenantColumnName)
        } finally {
            settings.loadState(snapshot)
        }
    }

    // ------------------------------------------------------------------
    // T-Fix-SettingsScope：设置导入不得静默重置文件里没有的字段
    // ------------------------------------------------------------------

    fun testImportJsonKeepsFieldsAbsentFromFile() {
        // [SettingsIO.exportToJson] 只写 21 个「可覆盖项」键，而 State 有 71 个 var 字段。
        // 导入必须按「JSON 未出现的键保留现值」合并：否则一次导入会把参数历史、增强捕获
        // 授权记忆、反馈身份、日志自动保存目录等文件里根本没有的字段静默重置为出厂默认
        // （用户侧表现为不可见的数据丢失，且 [SettingsIO] KDoc 自称「不含全局项」却又把
        // autoCheckUpdate/ignoredUpdateVersions 一并重置，自相矛盾）。
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.paramHistoryJson = """[{"sql":"select 1"}]"""
            settings.state.enhancedCaptureConfirmed = true
            settings.state.reportSubmitterName = "tester"
            settings.state.logAutoSavePath = "D:/logs"
            settings.state.historyMaxSize = 42
            settings.state.showGutterIcon = false
            settings.state.autoCheckUpdate = true
            settings.state.ignoredUpdateVersions = "1.2.3"

            PluginSettings.fromJson(
                """
                {
                  "version": ${SettingsIO.CURRENT_VERSION},
                  "dialect": "PostgreSQL",
                  "tablePrefix": "imported_"
                }
                """.trimIndent(),
            )

            // 文件里出现的键 → 被文件值覆盖
            assertEquals("文件中的 dialect 应覆盖现值", "PostgreSQL", settings.state.dialect)
            assertEquals("文件中的 tablePrefix 应覆盖现值", "imported_", settings.state.tablePrefix)
            // 文件里没有的键 → 保留现值
            assertEquals("参数历史应保留", """[{"sql":"select 1"}]""", settings.state.paramHistoryJson)
            assertEquals("增强捕获授权记忆应保留", true, settings.state.enhancedCaptureConfirmed)
            assertEquals("反馈身份应保留", "tester", settings.state.reportSubmitterName)
            assertEquals("日志自动保存目录应保留", "D:/logs", settings.state.logAutoSavePath)
            assertEquals("历史上限应保留", 42, settings.state.historyMaxSize)
            assertEquals("Gutter 图标开关应保留", false, settings.state.showGutterIcon)
            assertEquals("更新检查开关应保留（SettingsIO 自称不含全局项）", true, settings.state.autoCheckUpdate)
            assertEquals("忽略版本列表应保留", "1.2.3", settings.state.ignoredUpdateVersions)
        } finally {
            settings.loadState(snapshot)
        }
    }

    /**
     * T-EnhancedCaptureDefaultOn G3：旧 XML/JSON 的确认记忆与拒绝冷却在加载时幂等归一。
     * 只碰这两个字段——用户显式关闭的 enhancedCaptureEnabled 必须原样保留。
     */
    fun testLoadStateNormalizesLegacyConsentFields() {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            val legacyEnabled = snapshot.copy().apply {
                enhancedCaptureEnabled = true
                enhancedCaptureConfirmed = false
                enhancedCaptureRejectedAtMs = 123_456L
            }
            settings.loadState(legacyEnabled)
            assertEquals("旧 confirmed=false 应归一为 true", true, settings.state.enhancedCaptureConfirmed)
            assertEquals("旧拒绝时刻应归一为 0", 0L, settings.state.enhancedCaptureRejectedAtMs)
            assertTrue("用户开着增强时不得被归一关掉", settings.state.enhancedCaptureEnabled)

            val legacyDisabled = snapshot.copy().apply {
                enhancedCaptureEnabled = false
                enhancedCaptureConfirmed = false
                enhancedCaptureRejectedAtMs = 999L
            }
            settings.loadState(legacyDisabled)
            assertEquals("用户显式关闭必须保持关闭", false, settings.state.enhancedCaptureEnabled)
            assertEquals("确认记忆仍归一为 true", true, settings.state.enhancedCaptureConfirmed)
            assertEquals("拒绝时刻仍归一为 0", 0L, settings.state.enhancedCaptureRejectedAtMs)
        } finally {
            settings.loadState(snapshot)
        }
    }
}
