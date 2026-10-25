package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.ParamKind
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PC2-04（D1=B 定案）：含 UNKNOWN 参数的语句在**执行 / EXPLAIN 前提示一次**（不阻断已确认流程）。
 *
 * 形态对齐 [InjectionGateTest]（N-25 `${}` 注入闸门同款）：确认框经平台 [TestDialogManager] 拦截
 * （headless 零弹层），拦截器记录每次弹出正文——据此断言「弹了几次」「弹的是哪一道门」；
 * 「中止」的可观测面 = 结果面板状态（放行才走 `resultPanel.showLoading()`，中止则原样停留 EMPTY）。
 *
 * 夹具刻意**直构模型**（不经 PSI）：`XmlPreviewService.preview` 的 `paramTypes` 来自
 * `XmlParamTypeResolver`，其 mapper 接口 resolve 在 Light 沙盒恒 null（跨文件 resolve 不可用，
 * 见 HeavyXmlParamTypeResolverTest KDoc），那样构造出的模型 `paramTypes` 恒空 → 永远走不到
 * UNKNOWN 分支。参数类型条目的产出链（PSI → paramTypes）由 `HeavyXmlParamTypeResolverTest`
 * 与 `XmlParamKindTest` 分别钉住；本类只钉**面板侧接线**（信号 → 闸门 → 放行/中止/一次性记忆）。
 *
 * k2j 适配（生产树为 Java，无具名实参与默认参数）：
 *  - `XmlPreviewModel.from(statement, errors, paramTypes)` 的 Kotlin 默认参数在 Java 侧无等价重载，
 *    改走 7 参全量重载并显式补齐缺省实参（`emptySet()`/`emptyMap()`，与 `XmlParamKindTest` 同款）；
 *  - `PreviewResult(...)` 的 data class 具名实参改为 20 参主构造器位置实参，缺省字段按 master 默认值
 *    显式补齐（`emptyList()`/`emptyMap()`/`null`，与 `SqlPreviewPanelParamKindTest` 同款）。
 */
class UnknownParamGateTest : BasePlatformTestCase() {

    // 面板内嵌 Editor 须随测试释放，否则 tearDown 报 EditorImpl 泄漏（InjectionGateTest 同款约定）
    private var panel: SqlPreviewPanel? = null

    /** 拦截器记录的消息正文（按弹出顺序）。 */
    private val dialogMessages = mutableListOf<String>()

    /** 逐次答案（用尽后兜底 NO——比真实弹窗更严格，漏答即中止）。 */
    private val dialogAnswers = mutableListOf<Int>()

    override fun tearDown() {
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

    private fun newPanel(): SqlPreviewPanel = SqlPreviewPanel(project).also { this.panel = it }

    private fun panelState(): String = panel!!.resultPanelForTest().stateForTest()

    /**
     * UI-035 起「无绑定数据源」不再进执行链：EXPLAIN 放行后的可观测面是「无数据源」引导对话框
     * （`showExplainErrorDialog(openDatabase = true)`），不再是把结果面板推进 LOADING。
     * 这里截获该对话框并回读可见文案。
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

    // ------------------------------------------------------------------
    // 夹具：`#{a}` 类型未识别、`#{b}` 文本档
    // ------------------------------------------------------------------

    /**
     * 含 UNKNOWN 参数的预览结果：`paramTypes` 里 `a` 有签名条目但类别 UNKNOWN（枚举 / `boolean` /
     * `byte[]` 等真实形态），`b` 为 TEXT。
     *
     * @param withTypes false = 模拟「mapper 接口不可解析」——`paramTypes` 全空（无类型信息不计入）
     */
    private fun unknownResult(withTypes: Boolean = true): PreviewResult {
        val parsed = XmlMapperParser().parse(
            """
            <mapper namespace="com.example.BizMapper">
              <select id="s1">SELECT * FROM t_biz WHERE a = #{a} AND b = #{b}</select>
            </mapper>
            """.trimIndent(),
        )
        val types = if (withTypes) {
            mapOf(
                "a" to ParamTypeInfo(ParamKind.UNKNOWN, true),
                "b" to ParamTypeInfo(ParamKind.TEXT, true),
            )
        } else {
            emptyMap()
        }
        val model = XmlPreviewModel.from(
            parsed.statements.single(),
            parsed.errors,
            emptySet(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            types,
        )
        val sql = "SELECT * FROM t_biz WHERE a = ? AND b = ?"
        return PreviewResult(
            sql,
            sql,
            listOf("a", "b"),
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
            model,
            emptyMap(),
            null,
            null,
            null,
            null,
        )
    }

    private fun unknownMessage(): String =
        MpSqlVisualizerBundle.message("execute.confirm.unknownParams.message", 1)

    // ------------------------------------------------------------------
    // 执行闸门：弹一次 / NO 中止 / 确认后本面板不再弹 / 无 UNKNOWN 零影响
    // ------------------------------------------------------------------

    fun test含UNKNOWN参数执行_弹确认且NO中止() {
        interceptDialogs(Messages.NO)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, unknownResult())
        assertEquals("前置：未执行时结果面板为空态", "EMPTY", panelState())

        SqlPreviewPanelExecutionKt.executeSql(p)

        assertEquals("含 UNKNOWN 参数执行前应弹一次确认", 1, dialogMessages.size)
        assertEquals(
            "确认文案应是新增的 UNKNOWN 参数键（{0} = 未识别参数个数，MessageFormat 数字占位）",
            unknownMessage(),
            dialogMessages[0],
        )
        assertTrue("文案应说明以 ? 占位提交：${dialogMessages[0]}", dialogMessages[0].contains("?"))
        // feedback-bundle-param-placeholder 同族防线：MessageFormat 代入必须真的发生
        //（键缺失/模板异常时 message() 兜底返回原文，`{0}` 会原样上屏，肉眼极难发现）
        assertFalse("占位符应已代入个数（未代入会原样上屏 {0}）：${dialogMessages[0]}", dialogMessages[0].contains("{0}"))
        assertTrue("代入值应为未识别参数个数 1：${dialogMessages[0]}", dialogMessages[0].contains("1"))
        assertEquals("选 NO 应中止（未进入执行：结果面板停留空态）", "EMPTY", panelState())
    }

    fun test含UNKNOWN参数执行_确认放行且本面板不再重复弹() {
        interceptDialogs(Messages.YES)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, unknownResult())

        SqlPreviewPanelExecutionKt.executeSql(p)
        assertEquals("首次执行应弹确认", 1, dialogMessages.size)
        assertProceeded("确认后放行")

        SqlPreviewPanelExecutionKt.executeSql(p)
        assertEquals("会话级记忆：同面板再执行不重复弹（提示只出现一次）", 1, dialogMessages.size)
        assertProceeded("二次执行照常放行（不阻断已确认流程）")
    }

    fun test含UNKNOWN参数执行_NO后再执行仍会弹() {
        interceptDialogs(Messages.NO, Messages.NO)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, unknownResult())

        SqlPreviewPanelExecutionKt.executeSql(p)
        SqlPreviewPanelExecutionKt.executeSql(p)

        assertEquals("NO 不写会话记忆，再执行仍弹", 2, dialogMessages.size)
        assertEquals("两次都中止", "EMPTY", panelState())
    }

    fun test无UNKNOWN参数_零影响() {
        interceptDialogs(Messages.NO)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, unknownResult(withTypes = false))

        SqlPreviewPanelExecutionKt.executeSql(p)

        assertEquals("无类型信息（接口不可解析）不弹——避免每次执行都提示的噪音面", 0, dialogMessages.size)
        assertProceeded("行为逐字节不变（照常执行）")
    }

    // ------------------------------------------------------------------
    // EXPLAIN 旁路同门控
    // ------------------------------------------------------------------

    fun testEXPLAIN含UNKNOWN参数_同门控() {
        interceptDialogs(Messages.NO, Messages.YES)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, unknownResult())
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

    fun testEXPLAIN确认后_与执行共用一次性记忆() {
        interceptDialogs(Messages.YES)
        val p = newPanel()
        SqlPreviewPanelResultViewKt.showResult(p, unknownResult())

        SqlPreviewPanelExecutionKt.executeSql(p)
        assertEquals("执行前弹一次", 1, dialogMessages.size)

        SqlPreviewPanelExecutionKt.explainSql(p)
        assertEquals("同面板已确认过：EXPLAIN 不再重复弹（同一道门一次性记忆）", 1, dialogMessages.size)
        assertProceeded("EXPLAIN 照常放行")
    }
}
