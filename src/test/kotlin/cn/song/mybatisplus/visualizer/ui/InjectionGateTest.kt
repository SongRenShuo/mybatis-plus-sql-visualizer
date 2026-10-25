package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.DataFlavor

/**
 * T-InjectionGate：`${}` 预览注入确认闸门（T-IG-01 ~ T-IG-03 的 headless 面板验收）。
 *
 * 判定信号是**解析期 `${` token 事实**（`PreviewResult.hasDollarRawSegments`），不是最终文本
 * regex——`${}` 成功替换为模拟值后文本里已无 `${}` 痕迹，regex 会漏掉全部成功场景（用例
 * [test信号_成功替换为模拟值仍为真] 钉住该口径）。
 *
 * 确认框经平台 [TestDialogManager] 拦截（[Messages] 在单元测试模式直返注入答案，headless
 * 零弹层），拦截器同时记录每次弹出的消息正文——据此断言「弹了几次」「弹的是哪一道门」。
 * 「NO 中断」的可观测面 = 结果面板状态：放行才走 `resultPanel.showLoading()`，中止则原样停留。
 */
class InjectionGateTest : BasePlatformTestCase() {

    // 面板内嵌 Editor 须随测试释放，否则 tearDown 报 EditorImpl 泄漏
    private var panel: SqlPreviewPanel? = null

    /** 拦截器记录的消息正文（按弹出顺序）。 */
    private val dialogMessages = mutableListOf<String>()

    /** 逐次答案（用尽后兜底 NO——比真实弹窗更严格，漏答即中止）。 */
    private val dialogAnswers = mutableListOf<Int>()

    override fun tearDown() {
        // 面板内嵌 Editor 须先 clear 释放（headless 下无 removeNotify 兜底），否则 tearDown 报
        // "EditorImpl hasn't been released"（SqlPreviewPanelParamFieldTest 同款约定）
        panel?.let {
            runCatching { SqlPreviewPanelSqlViewKt.clear(it) }
            runCatching { Disposer.dispose(it) }
        }
        panel = null
        super.tearDown()
    }

    /** 注入确认框答案（注册到测试根 Disposable，tearDown 自动还原）。 */
    private fun interceptDialogs(vararg answers: Int) {
        dialogMessages.clear()
        dialogAnswers.clear()
        dialogAnswers.addAll(answers.toList())
        TestDialogManager.setTestDialog(
            TestDialog { message ->
                dialogMessages += message
                dialogAnswers.removeFirstOrNull() ?: Messages.NO
            },
            testRootDisposable,
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun newPanel(): SqlPreviewPanel = SqlPreviewPanel(project).also { this.panel = it }

    /** XML 预览结果——走生产链路 [XmlPreviewService.preview]（信号透传一并锁死）。 */
    private fun xmlResult(mapper: String, statementId: String): PreviewResult =
        XmlPreviewService.INSTANCE.preview(mapper, statementId)

    /** 含 `${}` 的 SELECT mapper（OGNL 字面量求值，headless 稳定不依赖模拟参数）。 */
    private fun dollarSelectMapper() = """
        <mapper namespace="com.example.BizMapper">
          <select id="s1" resultType="map">
            SELECT * FROM t_biz ORDER BY ${'$'}{'create_time'}
          </select>
        </mapper>
    """.trimIndent()

    /** 含 `${}` 的 UPDATE mapper（DML 分类判定与闸门先后顺序断言用）。 */
    private fun dollarUpdateMapper() = """
        <mapper namespace="com.example.BizMapper">
          <update id="u1">
            UPDATE t_biz SET a = ${'$'}{'1'} WHERE id = #{id}
          </update>
        </mapper>
    """.trimIndent()

    /** 纯 `#{}` 的 SELECT mapper（防回归：无 raw 段零影响）。 */
    private fun plainSelectMapper() = """
        <mapper namespace="com.example.BizMapper">
          <select id="s1" resultType="map">
            SELECT * FROM t_biz WHERE a = #{a}
          </select>
        </mapper>
    """.trimIndent()

    /** 非 XML 链结果（Wrapper 预览形态，无 `${}` 概念）。 */
    private fun plainResult(sql: String) = PreviewResult(
        sql,
        sql.replace(Regex("\\s+"), " ").trim(),
        emptyList(),
        null,
        null,
        "t_biz",
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

    fun test静态结果没有动态模型仍展示实际生成方言() {
        val result = PreviewResult(
            "SELECT * FROM t_biz",
            "SELECT * FROM t_biz",
            emptyList(),
            null,
            null,
            "t_biz",
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
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            cn.song.mybatisplus.visualizer.sql.OracleDialect.INSTANCE,
            null,
            null,
            null,
        )
        assertNull(result.previewModel)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, result)
        assertEquals("Oracle", p.currentDialect())
        assertEquals("预览临时 PSI 应消费同一实际生成方言", "Oracle", p.previewDesiredDialectName)
    }

    private fun panelState(): String = panel!!.resultPanelForTest().stateForTest()

    /**
     * UI-035 起「无绑定数据源」不再进执行链：EXPLAIN 放行后的可观测面是「无数据源」引导对话框
     * （`showExplainErrorDialog(openDatabase = true)`），不再是把结果面板推进 LOADING。
     * 这里截获该对话框并回读可见文案，供断言「放行后确实给出了引导」。
     */
    private fun captureExplainDialogText(): () -> String? {
        var message: String? = null
        ExplainPlanDialog.explainDialogSinkForTest = java.util.function.Consumer { dialog ->
            message = com.intellij.util.ui.UIUtil.uiTraverser(dialog.contentForTest())
                .filter(com.intellij.ui.components.JBLabel::class.java)
                .toList()
                .mapNotNull { it.text }
                .joinToString(" ")
        }
        return { message }
    }

    /**
     * 断言「放行 = 进入执行链」：放行后 `resultPanel.showLoading()` 已执行，随后沙盒（无 Database
     * 插件）走 Community 降级链落到 ERROR 态；中止则原样停留 EMPTY。故非 EMPTY 即「未中止」。
     */
    private fun assertProceeded(message: String) {
        assertTrue("$message（应已进入执行链，实际状态 ${panelState()}）", panelState() != "EMPTY")
    }

    private fun clipboardText(): String? = runCatching {
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
    }.getOrNull()

    // ------------------------------------------------------------------
    // T-IG-01：信号来自解析期事实（非最终文本 regex）
    // ------------------------------------------------------------------

    fun test信号_成功替换为模拟值仍为真() {
        val result = xmlResult(dollarSelectMapper(), "s1")
        assertTrue("解析期 \${ token 出现过即真", result.isHasDollarRawSegments)
        // 成功替换为 mock 值后文本里已无 `${}` 痕迹——regex 判定在此场景恒漏（信号必须来自解析期事实）
        assertFalse("前置：渲染产物不含 \${ 残余", result.sql.contains("\${"))
        assertEquals("create_time", result.sql.substringAfter("ORDER BY").trim())
        assertTrue("模型层同步暴露（透传链锁死）", result.xmlModel!!.hasDollarRawSegments())
    }

    fun test信号_纯井号与转义形态为假() {
        assertFalse("纯 `#{}` 无 raw 段", xmlResult(plainSelectMapper(), "s1").isHasDollarRawSegments)
        // 转义 `\${` 是 MyBatis 的「原样输出」语义（不参与替换），按 TokenParser 口径不计
        val escaped = """
            <mapper namespace="com.example.BizMapper">
              <select id="s1" resultType="map">SELECT * FROM t_biz WHERE a = \${'$'}{'a'}</select>
            </mapper>
        """.trimIndent()
        assertFalse("转义形态不计入 raw 段", xmlResult(escaped, "s1").isHasDollarRawSegments)
    }

    fun test信号_非XML链恒为假() {
        assertFalse("Wrapper/JPA/日志链无 \${} 概念", plainResult("SELECT * FROM t_biz").isHasDollarRawSegments)
    }

    // ------------------------------------------------------------------
    // T-IG-02：执行闸门（含 raw 段弹门 / 确认放行 / NO 中断 / 二次不弹）
    // ------------------------------------------------------------------

    fun test含raw段执行_弹确认且NO中断() {
        interceptDialogs(Messages.NO)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(dollarSelectMapper(), "s1"))
        assertEquals("前置：未执行时结果面板为空态", "EMPTY", panelState())

        SqlPreviewPanelExecutionKt.executeSql(p)

        assertEquals("含 \${} 语句执行前应弹一次确认", 1, dialogMessages.size)
        assertEquals(
            "确认文案应是新增的 raw 段键（{0} 作参数传入，避开 MessageFormat 花括号陷阱）",
            MpSqlVisualizerBundle.message("execute.confirm.dollarRaw.message", SqlPreviewPanel.DOLLAR_TOKEN),
            dialogMessages[0],
        )
        assertTrue("文案应点明 \${} 记号：${dialogMessages[0]}", dialogMessages[0].contains("\${}"))
        assertEquals("选 NO 应中止（未进入执行：结果面板停留空态）", "EMPTY", panelState())
    }

    fun test含raw段执行_确认放行且本面板不再重复弹() {
        interceptDialogs(Messages.YES)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(dollarSelectMapper(), "s1"))

        SqlPreviewPanelExecutionKt.executeSql(p)
        assertEquals("首次执行应弹确认", 1, dialogMessages.size)
        assertProceeded("确认后放行")

        SqlPreviewPanelExecutionKt.executeSql(p)
        assertEquals("会话级记忆：同面板再执行不重复弹", 1, dialogMessages.size)
        assertProceeded("二次执行照常放行")
    }

    fun test含raw段执行_NO后再执行仍会弹() {
        interceptDialogs(Messages.NO, Messages.NO)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(dollarSelectMapper(), "s1"))

        SqlPreviewPanelExecutionKt.executeSql(p)
        SqlPreviewPanelExecutionKt.executeSql(p)

        assertEquals("NO 不写会话记忆，再执行仍弹", 2, dialogMessages.size)
        assertEquals("两次都中止", "EMPTY", panelState())
    }

    fun test无raw段语句_零影响() {
        interceptDialogs(Messages.NO)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(plainSelectMapper(), "s1"))

        SqlPreviewPanelExecutionKt.executeSql(p)

        assertEquals("无 raw 段不弹任何门", 0, dialogMessages.size)
        assertProceeded("行为逐字节不变（照常执行）")
    }

    fun test无raw段DML_仍只有DML二次确认() {
        interceptDialogs(Messages.YES)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, plainResult("UPDATE t_biz SET a = 1 WHERE id = 1"))

        SqlPreviewPanelExecutionKt.executeSql(p)

        assertEquals("无 raw 段的 DML 只弹既有二次确认（闸门零新增弹窗）", 1, dialogMessages.size)
        assertFalse("DML 确认文案不含 raw 说明", dialogMessages[0].contains("\${}"))
        assertProceeded("DML 确认后放行")
    }

    fun testDML含raw段_闸门排在DML确认之后() {
        interceptDialogs(Messages.YES, Messages.NO)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(dollarUpdateMapper(), "u1"))

        SqlPreviewPanelExecutionKt.executeSql(p)

        assertEquals("DML 确认 + \${} 确认各一次", 2, dialogMessages.size)
        assertTrue("第二道门才是 raw 说明：${dialogMessages[1]}", dialogMessages[1].contains("\${}"))
        assertEquals("raw 门选 NO 应中止在快照/执行之前", "EMPTY", panelState())
    }

    // ------------------------------------------------------------------
    // T-IG-03：EXPLAIN 旁路同门控
    // ------------------------------------------------------------------

    fun testEXPLAIN含raw段_同门控() {
        interceptDialogs(Messages.NO, Messages.YES)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(dollarSelectMapper(), "s1"))
        val dialogText = captureExplainDialogText()
        try {
            SqlPreviewPanelExecutionKt.explainSql(p)
            assertEquals("EXPLAIN 也真实执行，必须弹门", 1, dialogMessages.size)
            assertEquals("NO 中止（未进入执行）", "EMPTY", panelState())
            assertNull("NO 中止时不应再给后续引导", dialogText())

            SqlPreviewPanelExecutionKt.explainSql(p)
            assertEquals("EXPLAIN 确认框 NO 不写记忆，再点仍弹", 2, dialogMessages.size)
            // UI-035：无绑定数据源 → 放行后给引导，不进执行链（旧断言是 state != EMPTY）
            val text = dialogText()
            assertNotNull("确认后应给出「无数据源」引导：state=${panelState()}", text)
            assertTrue(
                "引导文案应为无数据源：$text",
                text!!.contains(MpSqlVisualizerBundle.message("panel.explain.noDataSource")),
            )
        } finally {
            ExplainPlanDialog.explainDialogSinkForTest = null
        }
    }

    fun testEXPLAIN无raw段_不弹门() {
        interceptDialogs(Messages.NO)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(plainSelectMapper(), "s1"))
        val dialogText = captureExplainDialogText()
        try {
            SqlPreviewPanelExecutionKt.explainSql(p)

            assertEquals("无 raw 段的 EXPLAIN 照旧免确认", 0, dialogMessages.size)
            val text = dialogText()
            assertNotNull("放行后应给出「无数据源」引导：state=${panelState()}", text)
            assertTrue(
                "引导文案应为无数据源：$text",
                text!!.contains(MpSqlVisualizerBundle.message("panel.explain.noDataSource")),
            )
        } finally {
            ExplainPlanDialog.explainDialogSinkForTest = null
        }
    }

    // ------------------------------------------------------------------
    // T-IG-03：复制链不做弹窗，产物头部带标记注释
    // ------------------------------------------------------------------

    fun test标记注释常量_计划指定文案() {
        assertEquals(
            "-- contains \${} raw segments (mock values)",
            CopyExecutableSqlAction.DOLLAR_RAW_MARKER,
        )
    }

    fun test复制产物_含raw段带标记注释() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(dollarSelectMapper(), "s1"))
        val editorSql = p.editorDocumentText()
        assertFalse("前置：编辑器已有 SQL 文本", editorSql.isNullOrBlank())

        val copied = CopyExecutableSqlAction(project).execute(p)

        assertTrue("复制应成功", copied)
        val text = clipboardText()
        assertTrue("产物头应带 raw 标记注释：$text", text!!.startsWith(CopyExecutableSqlAction.DOLLAR_RAW_MARKER + "\n"))
        assertTrue("标记之后是原产物", text.removePrefix(CopyExecutableSqlAction.DOLLAR_RAW_MARKER + "\n").contains("t_biz"))
    }

    fun test复制产物_无raw段无标记() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, xmlResult(plainSelectMapper(), "s1"))

        CopyExecutableSqlAction(project).execute(p)

        val text = clipboardText()
        assertFalse("无 raw 段产物逐字节不变：$text", text!!.contains(CopyExecutableSqlAction.DOLLAR_RAW_MARKER))
    }

    // ------------------------------------------------------------------
    // T-PSG-04（N-33）：复制链占位符/参数条数对账——不等时产物头部如实标注（不阻断）
    // ------------------------------------------------------------------

    /** Wrapper 形态结果：指定 SQL 与参数来源（占位符条数与参数条数可不等）。 */
    private fun paramResult(sql: String, params: List<String>) = PreviewResult(
        sql,
        sql,
        params,
        null,
        null,
        "t_biz",
        emptyList(),
    )

    fun test复制产物_占位符多于参数_头部如实标注() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, paramResult("SELECT * FROM t_biz WHERE a = ? AND b = ?", listOf("a")))

        CopyExecutableSqlAction(project).execute(p)

        val text = clipboardText()
        assertTrue(
            "占位符多于参数 → 产物头应如实标注（不阻断复制）：$text",
            text!!.startsWith("-- placeholder/param count mismatch (2 placeholders vs 1 params)\n"),
        )
    }

    fun test复制产物_位点数与参数条数相等_无标注() {
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, paramResult("SELECT * FROM t_biz WHERE a = ?", listOf("a")))

        CopyExecutableSqlAction(project).execute(p)

        val text = clipboardText()
        assertFalse("相等时产物逐字节不变：$text", text!!.contains("placeholder/param count mismatch"))
    }

    fun test复制产物_位点少于参数_不标注_已回填常态() {
        // 面板已回填文本：已填参数的位置不再是 `?`，sources 仍是完整参数表——此向不等是常态，
        // 不应在产物里天天误报（T-PSG-04 有意的不对称口径）
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, paramResult("SELECT * FROM t_biz WHERE a = ? AND b = ?", listOf("a", "b")))
        p.paramFieldForTest(0)!!.text = "1"

        CopyExecutableSqlAction(project).execute(p)

        val text = clipboardText()
        assertFalse("已回填常态不应标注：$text", text!!.contains("placeholder/param count mismatch"))
    }
}
