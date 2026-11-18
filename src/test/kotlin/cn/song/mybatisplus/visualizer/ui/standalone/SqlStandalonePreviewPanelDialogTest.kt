package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.ui.SqlPreviewDialog
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import cn.song.mybatisplus.visualizer.ui.clear
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * 独立预览工作台「在弹框中打开」行为测试（T-StandaloneDialogOpen D-5）。
 *
 * ## 为什么这条用例是主证据
 *
 * 2026-11-18 缺陷：`OpenInDialogAction` 先置 `openDialogPending = true`，再调无参
 * `resolveFromInput()`，被形参默认值 `false` 就地抹掉 → 弹窗永不出现。断言内部标志位
 * 抓不到这类缺陷（标志位本身读起来是对的），必须断言**平台侧真的建了弹窗**——
 * `SqlPreviewService.activePreviewDialogForTest(project)` 就是那个事实。
 *
 * ## 反向验证口径
 *
 * 把 `resolveFromInput(openDialog = true)` 改回无参调用（或把参数传递改回字段），
 * 本类前两条用例必须变红。实测读数记在计划 §八。
 *
 * 桶位：引平台类与 `SqlPreviewDialog`，属**平台桶**（不进 testFast 快桶）。
 */
class SqlStandalonePreviewPanelDialogTest : BasePlatformTestCase() {

    private var panel: SqlStandalonePreviewPanel? = null

    override fun tearDown() {
        runCatching { closeDialog() }
        runCatching { panel?.dispose() }
        panel = null
        super.tearDown()
    }

    private fun newPanel(): SqlStandalonePreviewPanel = SqlStandalonePreviewPanel(project).also { panel = it }

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    /** 轮询等弹窗：解析走后台线程 + `invokeLater(nonModal)`，单次 dispatch 不够。
     *
     * **只认登记事实，不判 `isDisposed`**：headless 测试态没有真实窗口，`DialogWrapper.show()`
     * 返回后弹窗可能立刻处于 disposed（实测 2026-11-18：加 `!isDisposed` 过滤会让 4 条用例全红，
     * 而登记表本身是对的）。真机是否可见由用户 GUI 验收承接。
     */
    private fun awaitDialog(timeoutMs: Long = 5_000) = run {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found: cn.song.mybatisplus.visualizer.ui.SqlPreviewDialog? = null
        while (System.currentTimeMillis() < deadline) {
            waitForEdt()
            val candidate = SqlPreviewService.activePreviewDialogForTest(project)
            if (candidate != null) {
                found = candidate
                break
            }
            Thread.sleep(10)
        }
        found
    }

    private fun closeDialog() {
        val dialog = SqlPreviewService.activePreviewDialogForTest(project) ?: return
        runCatching { dialog.panelForTest()?.clear() }
        runCatching { dialog.doDispose() }
    }

    /** 触发工具栏「在弹框中打开」的真实动作路径（缺陷就在这条路径上）。 */
    private fun clickOpenInDialog(p: SqlStandalonePreviewPanel) {
        val action = p.OpenInDialogAction()
        action.actionPerformed(
            com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                action,
                null,
                com.intellij.openapi.actionSystem.ActionPlaces.TOOLBAR,
                com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            ),
        )
    }

    /** 有合法 SQL 时点「在弹框中打开」→ 平台侧真的建出弹窗（2026-11-18 缺陷回归）。 */
    fun test在弹框中打开_有SQL时真的弹窗() {
        val p = newPanel()
        p.setInputTextForTest("SELECT * FROM T_USER WHERE USER_NAME = ? AND EMAIL = ?")
        clickOpenInDialog(p)
        val dialog = awaitDialog()
        assertNotNull("「在弹框中打开」必须真的弹出预览弹窗", dialog)
    }

    /** 空输入也能开弹窗（用户诉求 2）——同样走真实动作路径。 */
    fun test在弹框中打开_空输入也弹窗() {
        val p = newPanel()
        p.setInputTextForTest("")
        clickOpenInDialog(p)
        val dialog = awaitDialog()
        assertNotNull("空输入时「在弹框中打开」也必须弹窗（空白编辑器）", dialog)
    }

    /** 未识别文本也能开弹窗，且按信息级说明展示原文。 */
    fun test在弹框中打开_未识别文本也弹窗() {
        val p = newPanel()
        p.setInputTextForTest("<dependency><groupId>org.springframework</groupId></dependency>")
        clickOpenInDialog(p)
        val dialog = awaitDialog()
        assertNotNull("未识别文本时也必须弹窗（按原文展示）", dialog)
        val warning = dialog!!.panelForTest()?.currentWarning.orEmpty()
        assertTrue("未识别应是信息级说明而非渲染失败：$warning", warning.isNotBlank())
    }

    /** iBatis 2 片段：弹窗照开，且不再出现 MyBatis 的 Unknown element 假错误。 */
    fun test在弹框中打开_iBatis片段不报UnknownElement() {
        val p = newPanel()
        p.setInputTextForTest(
            """
            <select id="selectByCondition" parameterClass="User" resultMap="userResult">
              SELECT * FROM T_USER
              <dynamic prepend="WHERE">
                <isNotNull prepend="AND " property="id">ID = #id#</isNotNull>
              </dynamic>
            </select>
            """.trimIndent(),
        )
        clickOpenInDialog(p)
        val dialog = awaitDialog()
        assertNotNull("iBatis 片段同样要能开弹窗", dialog)
        val warning = dialog!!.panelForTest()?.currentWarning.orEmpty()
        assertFalse("iBatis 片段不得再报 Unknown element：$warning", warning.contains("Unknown element"))
    }
}
