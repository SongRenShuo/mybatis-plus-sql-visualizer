package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.ui.SqlPreviewDialog
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelSqlViewKt
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

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
 *
 * k2j 移植注：生产侧 `clear` 扩展已落 `SqlPreviewPanelSqlViewKt.clear(SqlPreviewPanel)`
 * 门面静态方法（master 为 `cn.song.mybatisplus.visualizer.ui.clear` 扩展导入），测试改走门面。
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
        var found: SqlPreviewDialog? = null
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
        runCatching { dialog.panelForTest()?.let { SqlPreviewPanelSqlViewKt.clear(it) } }
        runCatching { dialog.doDispose() }
    }

    /**
     * L90：`awaitDialog()` 拿到弹窗 ≠ 面板的异步装配（编辑器 + 摘要行 + 警示横幅）已经跑完。
     * 断言「最终会成立的状态」必须轮询到成立；瞬时直读会偶发读到空值。
     */
    private fun awaitWarning(dialog: SqlPreviewDialog, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            waitForEdt()
            last = dialog.panelForTest()?.currentWarning.orEmpty()
            if (last.isNotBlank()) return last
            Thread.sleep(10)
        }
        return last
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

    /** 工作台内嵌预览面板用**工作台专用**空态提示（不再承诺「选中代码」那套编辑器语义）。 */
    fun test工作台内嵌面板用工作台专用空态提示() {
        val p = newPanel()
        assertEquals(
            cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("standalone.preview.emptyHint"),
            p.embeddedPreviewHintForTest(),
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
        val warning = awaitWarning(dialog!!)
        assertTrue("未识别应是信息级说明而非渲染失败：$warning", warning.isNotBlank())
    }

    /**
     * T-PreviewActionRow D-7（A-4）：工作台开窗必须把**原文**带进弹窗——弹窗里才补得了缺失片段。
     *
     * 这条钉的是「透传」这一环：只测 `SqlPreviewDialog(sourceText=…)` 构造证明不了工作台真的传了。
     */
    fun test在弹框中打开_缺片段时弹窗内出现补齐入口() {
        val p = newPanel()
        p.setInputTextForTest(
            """
            <select id="selectByPrimaryKey">
              SELECT <include refid="Base_Column_List"/> FROM CUST_CHANNEL WHERE ID = #{id}
            </select>
            """.trimIndent(),
        )
        clickOpenInDialog(p)
        val dialog = awaitDialog()
        assertNotNull("缺片段的 XML 同样要能开弹窗", dialog)

        val deadline = System.currentTimeMillis() + 5_000
        var visible = false
        while (System.currentTimeMillis() < deadline) {
            waitForEdt()
            visible = dialog!!.panelForTest()?.missingFragmentVisibleForTest() == true
            if (visible) break
            Thread.sleep(10)
        }
        assertTrue("工作台开窗应带上原文，弹窗内应出现「缺少片段…点此补上」入口", visible)
        assertTrue(
            "提示行应指向缺失的 refid",
            dialog!!.panelForTest()!!.missingFragmentTextForTest().contains("Base_Column_List"),
        )
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
        // 成功识别时 warning 本来就可能是空的（没有告警），故这里不能要求「非空」；改为在装配期内
        // 持续观察——假错误一旦出现就能抓到（L90：瞬时直读会漏掉异步填充的告警）。
        var sawUnknown = false
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            waitForEdt()
            if (dialog!!.panelForTest()?.currentWarning.orEmpty().contains("Unknown element")) {
                sawUnknown = true
                break
            }
            Thread.sleep(10)
        }
        assertFalse("iBatis 片段不得再报 Unknown element", sawUnknown)
    }
}
