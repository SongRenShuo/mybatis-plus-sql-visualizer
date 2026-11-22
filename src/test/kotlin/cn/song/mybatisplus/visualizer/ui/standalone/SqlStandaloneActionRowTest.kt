package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.ui.copyNotificationActionsForTest
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseEvent

/**
 * 工作台底部产物动作行 + 缺失片段补齐链测试（T-PreviewActionRow D-1 / D-6）。
 *
 * ## 为什么底部这一排必须钉住
 *
 * 页签此前**没有任何产物出口**：`复制 SQL` 全仓只在弹窗底部，用户要取走去注释的 SQL 必须先进
 * 弹窗（或手工全选 Ctrl+C）。这排按钮把「输入侧（解析/格式化/从文件加载）」与「产物侧
 * （执行/复制/在弹框中打开/清空）」分开，任何一侧被误删都应由本类变红。
 *
 * 另外钉住两件容易回归的事：
 *  - `copySql` 在页签里**必须** `offerClosePanelAction = false`——否则成功通知会挂一条
 *    「关闭 SQL 面板」死链接（`onClosePanelRequested` 只有弹窗注入）；
 *  - 缺失片段补齐在页签里走「面板提示行 → 工作台回调 → 重解析」真实链路（不是直接调私有方法）。
 *
 * 桶位：引 `SqlPreviewPanel`/`BasePlatformTestCase`，属**平台桶**（不进 testFast 快桶）。
 */
class SqlStandaloneActionRowTest : BasePlatformTestCase() {

    private var panel: SqlStandalonePreviewPanel? = null

    override fun tearDown() {
        runCatching { panel?.dispose() }
        panel = null
        super.tearDown()
    }

    private fun newPanel(): SqlStandalonePreviewPanel = SqlStandalonePreviewPanel(project).also { panel = it }

    private fun click(p: SqlStandalonePreviewPanel, action: AnAction) {
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, EMPTY_CONTEXT))
    }

    /** 解析走后台线程 + `invokeLater(nonModal)`：泵 EDT 直到内嵌面板建立。 */
    private fun awaitPanel(p: SqlStandalonePreviewPanel, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (p.embeddedPanelForTest() != null) return true
            Thread.sleep(10)
        }
        return p.embeddedPanelForTest() != null
    }

    /** 泵 EDT 直到内嵌面板的当前载荷满足条件（补齐重解析是异步的）。 */
    private fun awaitPayload(
        p: SqlStandalonePreviewPanel,
        predicate: (String) -> Boolean,
        timeoutMs: Long = 5_000,
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            last = p.embeddedPanelForTest()?.current?.sql.orEmpty()
            if (predicate(last)) return last
            Thread.sleep(10)
        }
        return last
    }

    // ------------------------------------------------------------------
    // D-1/D-2：动作分区
    // ------------------------------------------------------------------

    fun test顶部五个图标_底部只留执行与复制() {
        val p = newPanel()
        // T-PreviewActionRow 二轮（用户反馈）：清空 / 在弹框中打开 回到顶部——它们是按位置记忆的
        // 「随时可用」操作；底部只保留两个真正新增的产物出口（执行 SQL / 复制 SQL），不做两处重复。
        assertEquals(
            "顶部工具栏应为 解析 / 格式化 / 从文件加载 / 清空 / 在弹框中打开",
            listOf("ResolveAction", "FormatAction", "LoadFileAction", "ClearAction", "OpenInDialogAction"),
            p.inputToolbarActionNamesForTest(),
        )
        assertEquals(
            "底部动作行应只剩 执行 / 复制",
            listOf("ExecuteAction", "CopySqlAction"),
            p.bottomActionNamesForTest(),
        )
    }

    fun test未解析时执行与复制禁用_解析后启用_清空后回到禁用() {
        val p = newPanel()
        assertEquals("未解析时执行应禁用", false, p.bottomButtonEnabledForTest("ExecuteAction"))
        assertEquals("未解析时复制应禁用", false, p.bottomButtonEnabledForTest("CopySqlAction"))

        p.setInputTextForTest("SELECT id FROM t_user WHERE name = ?")
        p.resolveFromInput()
        assertTrue("解析应建立内嵌面板", awaitPanel(p))
        assertEquals("解析后执行应可用", true, p.bottomButtonEnabledForTest("ExecuteAction"))
        assertEquals("解析后复制应可用", true, p.bottomButtonEnabledForTest("CopySqlAction"))

        click(p, p.ClearAction())
        assertEquals("清空后复制应回到禁用", false, p.bottomButtonEnabledForTest("CopySqlAction"))
    }

    // ------------------------------------------------------------------
    // D-1：复制 SQL（页签里此前完全没有这个入口）
    // ------------------------------------------------------------------

    fun test复制SQL_去注释且通知不挂关闭面板() {
        val clipboard = cn.song.mybatisplus.visualizer.testutil.installFakeClipboard(testRootDisposable)
        val p = newPanel()
        p.setInputTextForTest("SELECT id\n-- 这条注释不该进剪贴板\nFROM t_user WHERE name = ?")
        p.resolveFromInput()
        assertTrue("解析应建立内嵌面板", awaitPanel(p))

        val notificationActions = mutableListOf<String>()
        p.embeddedPanelForTest()!!.copyNotificationActionsForTest = notificationActions
        click(p, p.CopySqlAction())

        val copied = clipboard.getContents()
            ?.getTransferData(DataFlavor.stringFlavor) as? String
        assertNotNull("应复制到剪贴板", copied)
        assertTrue("应含 SQL 主体，实际：$copied", copied!!.contains("FROM t_user"))
        assertFalse("SQL 注释应被剥离，实际：$copied", copied.contains("这条注释不该进剪贴板"))
        assertTrue(
            "页签里不该挂「关闭 SQL 面板」（没有面板可关，点了没反应）：$notificationActions",
            notificationActions.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // D-5/D-6：缺失片段补齐（工作台侧）
    // ------------------------------------------------------------------

    fun test点击缺少片段链接_补齐后TODO占位消失() {
        val p = newPanel()
        p.setInputTextForTest(MISSING_INCLUDE_XML)
        p.resolveFromInput()
        assertTrue("解析应建立内嵌面板", awaitPanel(p))

        val label = p.embeddedMissingFragmentLabelForTest()
        assertNotNull("工作台有原文，应出现补齐链接", label)
        assertTrue("补齐链接应可见", label!!.isVisible)

        // 注入补齐内容（headless 不能弹模态框），其余链路（记 overrides → 重解析 → 回填面板）全真
        p.missingFragmentPrompt = { "ID, CHANNEL_NAME" }
        label.dispatchEvent(
            MouseEvent(
                label,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )

        val sql = awaitPayload(p, predicate = { !it.contains("TODO: missing") && it.contains("CHANNEL_NAME") })
        assertTrue("补上的片段应展开进 SQL，实际：$sql", sql.contains("CHANNEL_NAME"))
        assertFalse("TODO 占位应消失，实际：$sql", sql.contains("TODO: missing"))
        assertFalse("补齐后链接应收起", label.isVisible)
    }

    companion object {
        private val MISSING_INCLUDE_XML = """
            <select id="selectByPrimaryKey">
              SELECT <include refid="Base_Column_List"/> FROM CUST_CHANNEL WHERE ID = #{id}
            </select>
        """.trimIndent()
    }
}