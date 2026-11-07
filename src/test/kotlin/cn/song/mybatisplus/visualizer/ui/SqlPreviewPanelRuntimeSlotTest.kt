package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.execute.DocWriteContext
import cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues
import cn.song.mybatisplus.visualizer.sql.SlotKind
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import cn.song.mybatisplus.visualizer.sql.ValueType
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-RuntimeSlotFill 券 C：**面板接线**。
 *
 * 券 B 的引擎（`ParameterReplacer.buildExecutableSqlWithSlots`）已经能填占位，但它开着的口子是
 * 「面板没有 slotValues 状态」——预览渲染与导出回放各走各的，会出现「预览填了、复制/导出没填」。
 * 本类钉住四件事：
 *  1. 无占位时参数区**零变化**（不建任何占位行）；
 *  2. 有占位时一行一占位，控件按 kind（VALUE 类型下拉 / LOOP 展开预览 / TERNARY 分支下拉 /
 *     OPTIONAL 包含开关）；
 *  3. 填值后预览文本立即变化（走真实渲染链，不是只改显示层）；
 *  4. 留空时四条链路（预览 / 复制 / 导出回放 / 执行）输出与改动前**逐字节一致**。
 */
class SqlPreviewPanelRuntimeSlotTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    private val loopToken = "/* 运行时值: 循环内追加 id=… / or id=…（运行时重复） · rt1 */"
    private val startTimeToken = "/* 运行时值: startTime · rt2 */"
    private val ternaryToken = "/* 运行时值: k == 0 ? x : y · rt3 */"
    private val optionalToken = "/* 运行时值: keyword != null · rt4 */"

    private val loopSlot = RuntimeSlot(
        id = "rt1",
        kind = SlotKind.LOOP,
        expression = "循环内追加 id=… / or id=…（运行时重复）",
        template = RuntimeSlot.joinBranches(listOf("id='%s' ", "or id='%s' ")),
        hint = ParamHint(),
        token = loopToken,
    )

    private val startTimeSlot = RuntimeSlot(
        id = "rt2",
        kind = SlotKind.VALUE,
        expression = "startTime",
        template = null,
        hint = ParamHint(quote = true),
        token = startTimeToken,
    )

    private val ternarySlot = RuntimeSlot(
        id = "rt3",
        kind = SlotKind.TERNARY,
        expression = "k == 0 ? x : y",
        template = RuntimeSlot.joinBranches(listOf("", " AND status = %s")),
        hint = ParamHint(quote = false),
        token = ternaryToken,
    )

    private val optionalSlot = RuntimeSlot(
        id = "rt4",
        kind = SlotKind.OPTIONAL,
        expression = "keyword != null",
        template = RuntimeSlot.joinBranches(listOf(" AND order_no LIKE '%%s%' ")),
        hint = ParamHint(),
        token = optionalToken,
    )

    /** 夹具 1/2 的形态：循环占位在括号里，时间值落在字符串字面量内（券 B 的引号内口径）。 */
    private val baseSql = "SELECT id, order_no FROM t_order WHERE ($loopToken) " +
        "AND create_time >= '$startTimeToken'"

    private val allKindsSql = "SELECT id, order_no FROM t_order WHERE ($loopToken) " +
        "AND create_time >= '$startTimeToken' $ternaryToken $optionalToken ORDER BY id"

    override fun setUp() {
        super.setUp()
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        // 关掉格式化：断言要逐字节比较，格式化会重排空白（与 ForeachValuesTest 同口径）
        runCatching { PluginSettings.getInstance().state.formatSql = false }
        // renderSql 的写安全闸：单测没有真实写动作，注入「允许写」等价生产 EDT 写帧
        DocWriteContext.writeAllowedProbe = { true }
    }

    override fun tearDown() {
        DocWriteContext.writeAllowedProbe = null
        panel?.let { runCatching { it.clear() } }
        panel?.let { runCatching { Disposer.dispose(it) } }
        panel = null
        runCatching { PluginSettings.getInstance().state.formatSql = true }
        super.tearDown()
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun result(sql: String, slots: List<RuntimeSlot>) = PreviewResult(
        sql = sql,
        compactSql = sql,
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = null,
        tableName = "t_order",
        runtimeSlots = slots,
        renderedDialect = MySqlDialect,
    )

    private fun openPanel(sql: String, slots: List<RuntimeSlot>): SqlPreviewPanel {
        val p = SqlPreviewPanel(project)
        panel = p
        Disposer.register(testRootDisposable, p)
        p.showResult(result(sql, slots))
        return p
    }

    /** 渲染管线最终产物（含显示模式变换；本类关掉格式化，故与回填文本逐字节一致）。 */
    private fun renderedText(p: SqlPreviewPanel): String = p.lastRenderedSqlForTest().orEmpty()

    private fun slotLabelText(p: SqlPreviewPanel, id: String): String? {
        val row = p.paramPanelComponentsForTest()
            .firstOrNull { it.name == "runtimeSlotRow_$id" } as? java.awt.Container ?: return null
        return (row.components.firstOrNull() as? javax.swing.JLabel)?.text
    }

    /** 标签 tooltip 是**未截断**全文（列宽放不下时中段省略，tooltip 兜底）。 */
    private fun slotLabelTooltip(p: SqlPreviewPanel, id: String): String? {
        val row = p.paramPanelComponentsForTest()
            .firstOrNull { it.name == "runtimeSlotRow_$id" } as? java.awt.Container ?: return null
        return (row.components.firstOrNull() as? javax.swing.JComponent)?.toolTipText
    }

    // ------------------------------------------------------------------
    // 1. 无占位 = 参数区零变化
    // ------------------------------------------------------------------

    fun testNoSlotsBuildsNoSlotRows() {
        val p = openPanel(baseSql, emptyList())
        assertEquals("无占位时不许建任何占位行（默认零变化）", 0, p.slotRowCountForTest())
        assertTrue(
            "参数区不得出现占位相关组件: ${p.paramPanelComponentsForTest().map { it.name }}",
            p.paramPanelComponentsForTest().none { it.name?.startsWith("runtimeSlot") == true },
        )
        assertEquals("无占位时渲染文本 = 原 SQL", baseSql, renderedText(p))
    }

    // ------------------------------------------------------------------
    // 2. 有占位 = 一行一占位，控件按 kind
    // ------------------------------------------------------------------

    fun testSlotRowsMatchSlotCountAndKindControls() {
        val p = openPanel(allKindsSql, listOf(loopSlot, startTimeSlot, ternarySlot, optionalSlot))
        assertEquals("行数 = 占位数", 4, p.slotRowCountForTest())

        val loop = p.slotRowControlsForTest("rt1")!!
        assertEquals(SlotKind.LOOP, loop.kind)
        assertNotNull("LOOP 必须有展开预览标签", loop.previewLabel)
        assertNull("LOOP 不给类型下拉（多值列表语义由模板决定）", loop.typeCombo)

        val value = p.slotRowControlsForTest("rt2")!!
        assertEquals(SlotKind.VALUE, value.kind)
        assertNotNull("VALUE 必须有类型下拉", value.typeCombo)
        assertEquals("默认类型 = 自动", ValueType.AUTO, value.typeCombo!!.selectedItem)
        assertNull("VALUE 没有分支下拉", value.branchCombo)

        val ternary = p.slotRowControlsForTest("rt3")!!
        assertEquals(SlotKind.TERNARY, ternary.kind)
        assertNotNull("TERNARY 必须有分支下拉", ternary.branchCombo)
        assertEquals("默认未选分支", 0, ternary.branchCombo!!.selectedIndex)
        assertTrue(
            "分支项要带模板预览: ${ternary.branchCombo!!.getItemAt(2)}",
            ternary.branchCombo!!.getItemAt(2).contains("AND status = %s"),
        )

        val optional = p.slotRowControlsForTest("rt4")!!
        assertEquals(SlotKind.OPTIONAL, optional.kind)
        assertNotNull("OPTIONAL 必须有「包含这段」复选框", optional.includeBox)
        assertTrue("默认勾选「包含这段」", optional.includeBox!!.isSelected)

        // 行标签：运行时值 #n · 表达式摘要（超列宽时中段省略，全文在 tooltip；未超宽时 tooltip 为空）
        assertTrue(
            "行标签应带序号: ${slotLabelText(p, "rt1")}",
            slotLabelText(p, "rt1")!!.contains("运行时值 #1"),
        )
        val fullLabel = slotLabelTooltip(p, "rt1") ?: slotLabelText(p, "rt1")
        assertTrue(
            "标签全文应含表达式摘要: $fullLabel",
            fullLabel!!.contains("循环内追加"),
        )
    }

    // ------------------------------------------------------------------
    // 3. 填值 → 预览随值变（走真实渲染链）
    // ------------------------------------------------------------------

    fun testFillingValuesUpdatesPreviewSql() {
        val p = openPanel(baseSql, listOf(loopSlot, startTimeSlot))
        // 用户填值：循环 1,2,3；时间值落在引号内（只转义、不重复加引号）
        p.slotRowControlsForTest("rt1")!!.field.text = "1,2,3"
        p.slotRowControlsForTest("rt2")!!.field.text = "2026-01-01 00:00:00"

        val text = renderedText(p)
        assertTrue("循环应展开成模板形态: $text", text.contains("(id='1' or id='2' or id='3' )"))
        assertTrue("时间值应填进引号内（不出现 ''值''）: $text", text.contains("create_time >= '2026-01-01 00:00:00'"))
        assertFalse("已填的占位不许残留注释: $text", text.contains("运行时值"))

        // LOOP 右侧灰字展开预览（前 3 项）
        val preview = p.slotRowControlsForTest("rt1")!!.previewLabel!!
        assertTrue("展开预览应显示前 3 项: ${preview.text}", preview.text.contains("id='1' or id='2' or id='3'"))
        assertTrue("有预览时标签文本非空", preview.text.isNotEmpty())

        // 值确实进了面板状态（复制/导出/执行读的就是它）
        assertEquals("1,2,3", p.slotValuesForTest()["rt1"]?.text)
    }

    fun testTernaryBranchAndOptionalIncludeDrivePreview() {
        val p = openPanel(allKindsSql, listOf(loopSlot, startTimeSlot, ternarySlot, optionalSlot))

        // 三元选假分支 + 值 1 → AND status = 1
        val ternary = p.slotRowControlsForTest("rt3")!!
        ternary.branchCombo!!.selectedIndex = 2
        ternary.field.text = "1"
        assertTrue("选假分支后应出现片段: ${renderedText(p)}", renderedText(p).contains("AND status = 1"))

        // 可选片段：填值 → 片段出现；取消勾选 → 片段消失
        val optional = p.slotRowControlsForTest("rt4")!!
        optional.field.text = "A01"
        assertTrue("填值后可选片段应出现: ${renderedText(p)}", renderedText(p).contains("AND order_no LIKE '%A01%'"))
        // 程序化 setSelected 不触发 ActionListener（Swing 语义），用 doClick 等价用户点击
        optional.includeBox!!.doClick()
        assertFalse("取消「包含这段」后片段应消失: ${renderedText(p)}", renderedText(p).contains("LIKE '%A01%'"))
    }

    // ------------------------------------------------------------------
    // 4. 留空 = 原样：四条链路逐字节一致
    // ------------------------------------------------------------------

    fun testFilledValuesReachCopyExportAndExecuteChains() {
        val slots = listOf(loopSlot, startTimeSlot)
        val p = openPanel(baseSql, slots)
        p.slotRowControlsForTest("rt1")!!.field.text = "1,2,3"
        p.slotRowControlsForTest("rt2")!!.field.text = "2026-01-01 00:00:00"
        val preview = renderedText(p)
        assertFalse("预览里不许残留占位: $preview", preview.contains("运行时值"))

        // ① 复制链：面板快照 → 组装产物（已无注释可剥，应与预览逐字节一致）
        val copy = CopyExecutableSqlAction.assembleProduct(
            rawSql = baseSql,
            slots = slots,
            slotValues = p.slotValuesSnapshotForTest(),
            dialectName = MySqlDialect.name,
        )
        assertEquals("复制链必须拿到同一份占位值", preview, copy.text)

        // ② 导出回放链：把编辑器文本打回未回填形态（模拟用户手改 / 控制台漂移），
        //    导出取词口必须按同一份值补填——这正是「导出没填」的复现面
        val doc = p.editorForTest()!!.document
        WriteCommandAction.runWriteCommandAction(project) { doc.setText(baseSql) }
        assertEquals("导出回放链必须按同一份值回填", preview, p.previewSqlForExport())
        assertEquals("对照：裸 previewSqlForReadonly 仍是占位形态", baseSql, p.previewSqlForReadonly())

        // ③ 执行链：同一份值渲染出的待执行文本
        assertEquals(
            "执行链必须拿到同一份占位值",
            preview,
            ExecuteSqlSupport.renderForExecution(baseSql, slots, p.slotValuesSnapshotForTest(), MySqlDialect.name),
        )
    }

    fun testEmptySlotValuesKeepFourChainsByteIdentical() {
        val slots = listOf(loopSlot, startTimeSlot)
        val p = openPanel(baseSql, slots)

        // 面板状态：没填任何值
        assertTrue("刚打开时占位值表为空", p.slotValuesForTest().isEmpty())

        // ① 预览链
        assertEquals("预览必须与改动前逐字节一致", baseSql, renderedText(p))
        // ② 复制链（空值 = 只剩既有的「剥注释」动作，slotsApplied 为空）
        val copy = CopyExecutableSqlAction.assembleProduct(
            rawSql = baseSql,
            slots = slots,
            slotValues = p.slotValuesSnapshotForTest(),
            dialectName = MySqlDialect.name,
        )
        assertEquals("复制链留空应与改动前逐字节一致", SqlCommentStripper.strip(baseSql), copy.text)
        assertEquals("留空不算回填", emptyList<String>(), copy.slotsApplied)
        // ③ 导出回放链（面板取词口）
        assertEquals("导出回放留空应与输入逐字节一致", baseSql, p.fillRuntimeSlots(baseSql))
        // ④ 执行链
        assertEquals(
            "执行链留空应与输入逐字节一致",
            baseSql,
            ExecuteSqlSupport.renderForExecution(
                baseSql,
                slots,
                RuntimeSlotValues.EMPTY,
                MySqlDialect.name,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 5. 切语言后占位行仍在且文本更新（面板的文案刷新路径 = 重建）
    // ------------------------------------------------------------------

    fun testLanguageSwitchKeepsSlotRowsAndRefreshesText() {
        val settings = PluginSettings.getInstance()
        val oldLanguage = runCatching { settings.state.language }.getOrNull()
        try {
            settings.state.language = "zh"
            val p = openPanel(allKindsSql, listOf(loopSlot, startTimeSlot, ternarySlot, optionalSlot))
            assertTrue("中文文案: ${slotLabelText(p, "rt1")}", slotLabelText(p, "rt1")!!.contains("运行时值"))

            settings.state.language = "en"
            // 语言切换在面板里走「重建参数区」（同一份结果再走一遍 showResult 即生产刷新路径）
            p.showResult(result(allKindsSql, listOf(loopSlot, startTimeSlot, ternarySlot, optionalSlot)))
            assertEquals("切语言后占位行仍在", 4, p.slotRowCountForTest())
            assertTrue("英文文案: ${slotLabelText(p, "rt1")}", slotLabelText(p, "rt1")!!.contains("Runtime value"))
        } finally {
            runCatching { settings.state.language = oldLanguage ?: "zh" }
        }
    }

    // ------------------------------------------------------------------
    // 6. 券 B 的拒绝不静默：模板 %s 计数不符要显示出来
    // ------------------------------------------------------------------

    fun testTemplateMismatchWarningIsSurfacedInPanel() {
        val brokenTemplate = RuntimeSlot(
            id = "rt9",
            kind = SlotKind.OPTIONAL,
            expression = "broken",
            // 两个 %s：源码本身含 %s 的形态，券 B 会拒绝展开并提示
            template = RuntimeSlot.joinBranches(listOf(" AND a LIKE '%%s%' AND b LIKE '%%s%' ")),
            hint = ParamHint(),
            token = "/* 运行时值: broken · rt9 */",
        )
        val p = openPanel("SELECT 1 FROM t WHERE 1 = 1 /* 运行时值: broken · rt9 */", listOf(brokenTemplate))
        p.slotRowControlsForTest("rt9")!!.field.text = "x"

        val warnings = p.slotRenderWarningsForTest()
        assertTrue("模板 %s 计数不符必须报出来: $warnings", warnings.any { it.message.contains("拒绝展开") })
        val label = p.runtimeSlotWarningLabel
        assertNotNull("诊断行应已建出", label)
        assertTrue("诊断行文本应带原因: ${label!!.text}", label.text.contains("拒绝展开"))
    }
}
