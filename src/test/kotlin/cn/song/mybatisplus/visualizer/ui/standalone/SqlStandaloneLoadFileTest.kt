package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * 「从文件加载」归位测试（T-LoadFilePlacement D-5）。
 *
 * ## 这条能力为什么在工作台
 *
 * 通用预览弹窗（`SqlPreviewService.showResult` 的 21 处入口：5 条语句入口 + 18 个 gutter 图标 +
 * 日志/历史/CRUD）只负责**展示已渲染好的 SQL**；「从哪弄来这段文本」属于输入源，只有工作台有
 * 输入区。放在弹窗里除了语义歧义，还会静默替换掉「这条 SQL 来自哪个 mapper」的来源。
 *
 * ## 断言口径
 *
 * 走 [SqlStandalonePreviewPanel.LoadFileAction] 的**真实动作路径**（不直接调私有方法）——
 * 接线缺陷（动作没挂上工具栏、写错通道）只有这条路能看见；`filePicker` 是 headless 下替代
 * `FileChooser` 的取数缝，生产路径为 null。
 *
 * 桶位：引 `SqlInputEditor`/平台类，属**平台桶**（不进 testFast 快桶）。
 */
class SqlStandaloneLoadFileTest : BasePlatformTestCase() {

    private var panel: SqlStandalonePreviewPanel? = null

    override fun tearDown() {
        runCatching { panel?.dispose() }
        panel = null
        super.tearDown()
    }

    private fun newPanel(): SqlStandalonePreviewPanel = SqlStandalonePreviewPanel(project).also { panel = it }

    private fun clickLoadFile(p: SqlStandalonePreviewPanel) {
        val action = p.LoadFileAction()
        action.actionPerformed(
            AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, EMPTY_CONTEXT),
        )
    }

    /** 解析走后台线程 + `invokeLater(nonModal)`，状态行要泵 EDT 才能读到最终值。 */
    private fun awaitStatus(
        p: SqlStandalonePreviewPanel,
        predicate: (String) -> Boolean,
        timeoutMs: Long = 5_000,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            val current = p.statusTextForTest().orEmpty()
            if (predicate(current)) return current
            Thread.sleep(10)
        }
        return p.statusTextForTest().orEmpty()
    }

    /** A-2：载入 → 输入区换成文件全文 → 预览随之出结果 → 状态行写明来源文件。 */
    fun test从文件加载_替换输入并解析出语句() {
        val p = newPanel()
        p.filePicker = { MapperFileLoader.LoadedFile("UserMapper.xml", MAPPER_XML) }

        clickLoadFile(p)

        assertEquals("输入区应换成文件全文", MAPPER_XML, p.inputTextForTest())
        val status = awaitStatus(p, predicate = { it.contains("selectById") })
        assertTrue("状态行应给出识别结果：$status", status.contains("selectById"))
        assertTrue(
            "状态行必须写明输入来源（异步解析会覆盖加载提示，来源要跟着结果走）：$status",
            status.contains(MpSqlVisualizerBundle.message("standalone.status.loadedFile", "UserMapper.xml")),
        )
    }

    /** P-1：已有输入被替换时不弹确认，但状态行必须写明「原输入已替换」。 */
    fun test从文件加载_已有输入时状态写明已替换() {
        val p = newPanel()
        p.setInputTextForTest("SELECT 1")
        p.filePicker = { MapperFileLoader.LoadedFile("a.xml", MAPPER_XML) }

        clickLoadFile(p)

        // 不泵 EDT：解析回调尚未回 EDT，此刻读到的就是加载动作自己写的状态（这条文案随后会被
        // 识别结果覆盖，故不能用轮询断言——轮询会竞态到另一条文案上）。
        assertEquals(
            "替换既有输入时状态行应写明已替换",
            MpSqlVisualizerBundle.message("standalone.status.loadedFile.replaced", "a.xml"),
            p.statusTextForTest(),
        )
    }

    /** A-3：超长文件（> MAX_TEXT_LENGTH）不写入输入区，避免把编辑器撑爆。 */
    fun test从文件加载_超长文件不写入输入区() {
        val p = newPanel()
        p.setInputTextForTest("SELECT 1")
        val huge = "x".repeat(StandaloneSqlSourceResolver.MAX_TEXT_LENGTH + 1)
        p.filePicker = { MapperFileLoader.LoadedFile("huge.xml", huge) }

        clickLoadFile(p)

        assertEquals("超长文件不得写入输入区", "SELECT 1", p.inputTextForTest())
        assertEquals(
            "应给出超长提示",
            MpSqlVisualizerBundle.message("standalone.status.loadedFile.tooLong"),
            p.statusTextForTest(),
        )
    }

    /** 入口必须真的挂在输入工具栏上（只测动作类存在会漏掉「忘了加进 group」）。 */
    fun test从文件加载入口挂在输入工具栏() {
        val p = newPanel()
        val names = p.inputToolbarActionNamesForTest()
        assertTrue("输入工具栏应含「从文件加载」动作，实际=$names", names.contains("LoadFileAction"))
    }

    companion object {
        private val MAPPER_XML = """
            <mapper namespace="com.example.UserMapper">
              <select id="selectById">SELECT * FROM t_user WHERE id = #{id}</select>
            </mapper>
        """.trimIndent()
    }
}