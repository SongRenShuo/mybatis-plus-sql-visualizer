package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * O-95 T-PreviewEdtGuard S3：预览面板绑定数据源常驻指示行测试。
 *
 * 覆盖三件事：①无绑定态显示未绑定灰字文案（与 R33 回退通知同源，headless 真实链
 * fail-closed 即未绑定）；②绑定态显示「已绑定：<源名>」（名字经 [SqlPreviewPanel.bindStateNameProvider]
 * 测试缝注入——生产同型读数走 DataSourceSelector.boundConsoleDataSourceName，无 Database
 * 插件的沙盒测试任务不可达，与 ExecTempFileFixTest 同款口径）并随渲染刷新（绑定丢失回流未绑定）；
 * ③版式：指示行在预设行之下（D2=A 灰字文本行定案，BoxLayout Y_AXIS 组件序=视觉序）。
 *
 * O-96 T-PreviewBindAssociation S1 追加：指示行三级化的**记忆回退态**（无活控制台但
 * last-used 记忆命中在籍数据源 →「关联（上次使用）：<源名>」）——记忆经
 * [DataSourceSelector.plantLastUsedMemoryForTest] 直写 PropertiesComponent（生产同源存储），
 * 在籍名单/UUID 解析经 [DataSourceSelector.rememberedLookupForTest] 缝注入（无 Database
 * 插件沙盒真链恒空），生产链 DataSourceSelector.rememberedDataSourceName 原样经过。
 * 三态覆盖：记忆命中（UUID 精确）/ UUID 失效（数据源已删：名字不在籍回落未绑定、
 * 名字唯一命中按 T-SI-02 口径仍显示）/ 同名歧义（弃记忆回落未绑定，不猜首条）。
 */
class SqlPreviewPanelBindStateTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        // O-88：折叠态已静态化——复位防跨用例串扰（ParamSchemes 同款）
        runCatching { SqlPreviewPanel.resetPresetCollapsedForTest() }
        // T-ModuleScopedLastUsed：进态也清一次全部层级（上条用例/上次运行残留的模块级键会让
        // 「是否模块级」判定读到脏值）
        runCatching { DataSourceSelector.forgetAllLastUsed(project) }
    }

    override fun tearDown() {
        // O-97 治理（T-TestPerf15）：本类 setUp 改写 formatSql，出口恢复出厂默认防跨类泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        panel?.bindStateNameProvider = null
        // O-96：object 单例静态缝复位（防跨用例串扰）
        DataSourceSelector.rememberedLookupForTest = null
        // T-ModuleScopedLastUsed：清**全部层级**——只清项目级双键会让上一条用例写入的模块级键
        // 残留到本条（实测症状：本条只有项目级记忆，isModule 却为 true，灰字冒称「本模块」）。
        runCatching { DataSourceSelector.forgetAllLastUsed(project) }
        runCatching { panel?.let { SqlPreviewPanelSqlViewKt.clear(it) } }
        panel = null
        super.tearDown()
    }

    /** 普通参数语料（无 xml/batch 模型 → 走方案行+指示行+参数行主链）。 */
    private fun buildResult(vararg params: String): PreviewResult = PreviewResult(
        // k2j 适配：20 参位置构造（无模型槽全取空缺省）
        "SELECT * FROM t WHERE id = ?",
        "SELECT * FROM t WHERE id = ?",
        params.toList(),
        ParameterReplacer.buildParamTable(params.toList()),
        null,
        null,
        null,
        emptyList(),
        emptyMap(),
        null,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        null,
        null,
        null,
        null,
    )

    private fun openPanel(vararg params: String): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        SqlPreviewPanelResultViewKt.showResult(p, buildResult(*params))
        UIUtil.dispatchAllInvocationEvents()
        return p
    }

    fun test无绑定时显示未绑定灰字文案() {
        val p = openPanel("id")
        val label = p.bindStateLabelForTest()
        assertNotNull("参数区应建绑定指示行标签", label)
        assertEquals(
            "未绑定应显示未绑定文案（panel.bindstate.unbound，与 R33 回退通知同源文案族）",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            label!!.text,
        )
        assertEquals(
            "灰字形态=平台 contextHelp 前景（与面板既有灰字形态一致，非自创颜色）",
            UIUtil.getContextHelpForeground(),
            label.foreground,
        )
    }

    fun test有绑定态显示数据源名并随渲染刷新回流() {
        val p = openPanel("id")
        p.bindStateNameProvider = { "mp_demo@localhost" }
        p.renderSqlForTest() // renderSql 尾部单点刷新（绑定态变更的既有刷新通道，零监听器口径）
        assertEquals(
            "绑定态应显示「已绑定：<源名>」（数据源名与 R33 结构化视图头部同源）",
            MpSqlVisualizerBundle.message("panel.bindstate.bound", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
        // 变更回流：绑定丢失（控制台 clearPrepared/失效 → 名取不到）→ 下一轮渲染回到未绑定灰字
        p.bindStateNameProvider = null
        p.renderSqlForTest()
        assertEquals(
            "绑定丢失后应回流未绑定文案",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    /**
     * T-ModuleScopedLastUsed W3 回归（沙盒验收实测缺陷）：
     *
     * 只有**项目级**记忆（无模块级键）时，灰字必须是「数据源（上次使用）」，
     * **不得**冒称「本模块上次使用」。
     *
     * 缺陷成因（首版实现）：判据用 `rememberedDataSourceName(proj, moduleKey) ==
     * rememberedDataSourceName(proj, null)`——而前者在模块级为空时会回落项目级，两边恒相等，
     * 于是「纯项目级兜底」被误判成「模块级命中」。真机取证：workspace.xml 只有
     * `mpsql.visualizer.last.runner`，无任何 `...runner.<moduleKey>` 键，灰字却显示「本模块」。
     *
     * 修法：直接读模块级键是否有值（k2j：SqlPreviewPanelParamPanelKt.rememberedIsModuleScoped）。
     * 本用例钉住它。
     */
    fun test仅有项目级记忆时不得冒称本模块() {
        plantRegisteredDataSources(
            listOf("proj_level@localhost"),
        ) { id -> if (id == "uuid-proj") "proj_level@localhost" else null }
        // 只写项目级（plantLastUsedMemoryForTest 写的就是项目级双键，与真机 workspace.xml 同源）
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-proj", "proj_level@localhost")

        val p = openPanel("id")
        p.sourceModuleKey = "mybatis-plus-join-demo-pom"
        p.renderSqlForTest()

        assertEquals(
            "无模块级键时必须是项目级兜底文案（不得显示「本模块上次使用」）",
            MpSqlVisualizerBundle.message("panel.bindstate.associated", "proj_level@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    /** 对照：真写了模块级键时，才显示「本模块」文案。 */
    fun test有模块级记忆时显示本模块文案() {
        plantRegisteredDataSources(
            listOf("mod_level@localhost"),
        ) { id -> if (id == "uuid-mod") "mod_level@localhost" else null }
        val moduleKey = "mybatis-plus-join-demo-pom"
        // k2j：Java 四参重载 rememberRunner(project, name, dataSourceId, moduleKey)
        DataSourceSelector.rememberRunner(project, "mod_level@localhost", "uuid-mod", moduleKey)

        val p = openPanel("id")
        p.sourceModuleKey = moduleKey
        p.renderSqlForTest()

        assertEquals(
            "有模块级键时必须显示「本模块上次使用」",
            MpSqlVisualizerBundle.message("panel.bindstate.associatedModule", "mod_level@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    /**
     * T-DevAcceptFix ①（20261006 验收）：指示行迁顶栏——「更多」按钮（工具栏 EAST 末位）正下方
     * 右对齐常驻薄行，与右侧内嵌控制台头部数据源显示相邻；不再挂参数区预设行之下。
     */
    fun test指示行迁顶栏更多按钮下方() {
        val p = openPanel("id")
        val label = p.bindStateLabelForTest()
        assertNotNull("面板应建绑定指示行标签（常驻行，init 构建）", label)
        val row = label!!.parent
        assertNotNull("指示行应有行容器", row)
        // ① 迁出参数区：不得再列入参数区组件序（原「预设行之下」位置废止）
        assertFalse(
            "指示行不得再挂在参数区内（T-DevAcceptFix ① 迁顶栏）",
            p.paramPanelComponentsForTest().any { it === row },
        )
        // ② 与工具栏同挂北区宿主，位置=工具栏行（NORTH）之下的 CENTER 位
        val northHost = p.toolbarForTest().parent.parent
        assertSame("指示行应与工具栏同挂北区宿主", northHost, row.parent)
        val layout = northHost.layout as java.awt.BorderLayout
        assertSame("工具栏应挂 NORTH", p.toolbarForTest().parent, layout.getLayoutComponent(java.awt.BorderLayout.NORTH))
        assertSame(
            "指示行应挂北区宿主 CENTER（「更多」按钮所在工具栏行正下方）",
            row,
            layout.getLayoutComponent(java.awt.BorderLayout.CENTER),
        )
        // ③ 右对齐：与工具栏右缘（更多按钮一侧）对齐
        assertEquals(
            "指示行应右对齐（更多按钮正下方）",
            java.awt.FlowLayout.RIGHT,
            (row.layout as java.awt.FlowLayout).alignment,
        )
    }

    /** T-DevAcceptFix ①：常驻语义——无参数预览（参数区走「无参数」早退分支）指示行仍在场。 */
    fun test无参数预览指示行仍常驻() {
        val p = openPanel() // 无参语料：unresolvedParams 空 → 参数区早退
        val label = p.bindStateLabelForTest()
        assertNotNull("无参数预览也应有常驻指示行（迁顶栏后不再依赖参数区建行）", label)
        assertEquals(
            "未绑定应显示未绑定文案",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            label!!.text,
        )
    }

    // ------------------------------------------------------------------
    // O-96 T-PreviewBindAssociation S1：last-used 记忆回退态（指示行三级化）
    // ------------------------------------------------------------------

    /** O-96：在籍数据源缝注入（名单 + UUID→名解析），模拟 Database 插件在籍注册表。 */
    private fun plantRegisteredDataSources(names: List<String>, nameById: (String) -> String?) {
        // K2J 互操作：Java 静态字段类型是 Function<Project, kotlin.Pair<List, java.util.function.Function>>——
        // Kotlin 函数类型变量不自动 SAM 转换，须显式 `java.util.function.Function { ... }`；
        // `to` 产出 kotlin.Pair 照用（字段声明即 kotlin.Pair）。
        DataSourceSelector.rememberedLookupForTest =
            java.util.function.Function { _: com.intellij.openapi.project.Project ->
                names to java.util.function.Function { id: String -> nameById(id) }
            }
    }

    fun test记忆命中显示关联上次使用文案() {
        plantRegisteredDataSources(
            listOf("mp_demo@localhost", "warehouse@db2"),
        ) { id -> if (id == "uuid-1") "mp_demo@localhost" else null }
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-1", "mp_demo@localhost")
        val p = openPanel("id")
        p.renderSqlForTest() // renderSql 尾部单点刷新（零监听器口径）
        assertEquals(
            "记忆命中应显示「关联（上次使用）：<源名>」（panel.bindstate.associated，文案区分不冒称已绑定）",
            MpSqlVisualizerBundle.message("panel.bindstate.associated", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    fun testUUID失效且名字不在籍回落未绑定() {
        plantRegisteredDataSources(
            listOf("warehouse@db2"),
        ) { _ -> null } // uuid-dead 指认的数据源已删除（getDataSourceById → null，前提 3 fail-closed）
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-dead", "gone@db")
        val p = openPanel("id")
        p.renderSqlForTest()
        assertEquals(
            "UUID 失效且记忆名不在籍应回落未绑定（禁静默猜）",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    fun testUUID失效但名字唯一命中仍显示关联() {
        plantRegisteredDataSources(
            listOf("mp_demo@localhost", "warehouse@db2"),
        ) { _ -> null } // UUID 分量失效 → T-SI-02 口径退回名字判定
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-dead", "mp_demo@localhost")
        val p = openPanel("id")
        p.renderSqlForTest()
        assertEquals(
            "UUID 失效但名字唯一命中应仍显示关联（与执行链静默复用同口径，指示与执行不脱节）",
            MpSqlVisualizerBundle.message("panel.bindstate.associated", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    fun test同名歧义回落未绑定() {
        plantRegisteredDataSources(
            listOf("mp_demo@localhost", "mp_demo@localhost"),
        ) { id -> if (id == "uuid-1") "mp_demo@localhost" else null }
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-1", "mp_demo@localhost")
        val p = openPanel("id")
        p.renderSqlForTest()
        assertEquals(
            "同名多条歧义应回落未绑定（T-SI-02 同款：不猜首条；展示路径只读不清记忆）",
            MpSqlVisualizerBundle.message("panel.bindstate.unbound"),
            p.bindStateLabelForTest()!!.text,
        )
    }

    // ------------------------------------------------------------------
    // O-96fix2：控制台挂载即刷新指示行（执行路径无 renderSql 的补拍刷新）
    // ------------------------------------------------------------------

    /**
     * 真机缺陷复现（O-96 复测轮）：执行 SQL 挂载控制台的路径**不走 renderSql**（SqlExecutor
     * execute/executePrepared 的 onConsole → embedConsole，控制台文档已是执行 SQL，重渲染会冲掉），
     * 指示行停留在挂载前的记忆态「关联（上次使用）」，手动点刷新才升级「已绑定」。
     * 修复=embedConsole 单点补一拍刷新（全挂载路径统一钩子）。
     * 本用例：记忆态（关联）面板 → 模拟挂载（bindStateNameProvider 测试缝替代生产
     * boundConsoleDataSourceName，与 test有绑定态同款口径）→ 只 dispatch 不 renderSql →
     * 指示行应即时升级「已绑定」。
     */
    fun test挂载控制台后指示行即时升级无需重渲染() {
        plantRegisteredDataSources(
            listOf("mp_demo@localhost"),
        ) { id -> if (id == "uuid-1") "mp_demo@localhost" else null }
        DataSourceSelector.plantLastUsedMemoryForTest(project, "uuid-1", "mp_demo@localhost")
        val p = openPanel("id")
        assertEquals(
            "前置：无活控制台时应显示记忆态关联文案",
            MpSqlVisualizerBundle.message("panel.bindstate.associated", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
        // 活控制台上线（生产链在 onConsole 回调前已写 preparedConsole/记忆，测试缝同步在挂载前置好）
        p.bindStateNameProvider = { "mp_demo@localhost" }
        p.embedConsoleForTest(javax.swing.JPanel())
        UIUtil.dispatchAllInvocationEvents() // 挂载补拍的 invokeLater 刷新——不经 renderSql
        assertEquals(
            "挂载控制台后指示行应即时升级「已绑定」（无需手动刷新/重渲染）",
            MpSqlVisualizerBundle.message("panel.bindstate.bound", "mp_demo@localhost"),
            p.bindStateLabelForTest()!!.text,
        )
    }
}
