package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 「先开弹窗、后贴内容」重识别测试（T-DialogPasteReparse）。
 *
 * 用户 2026-11-18 场景：从工作台点「在弹框中打开」得到空弹窗，再往里贴 mapper/iBatis 片段——
 * 弹窗编辑器只是普通 SQL 编辑器，贴进去的内容不会被任何链识别（界面显示原文、「无未解析参数」）。
 *
 * ## 为什么直接构造 `SqlPreviewDialog` 而不经 `SqlPreviewService.showResult`
 *
 * headless 下 `DialogWrapper.show()` 之后弹窗会被立即释放（在册现象，见
 * `SqlStandalonePreviewPanelDialogTest` 的 awaitDialog 注释），经 service 拿到的实例已经
 * `dispose()` 过——而本功能的 `dispose()` 会正常停掉待命状态。直接构造能测到构造期的
 * `autoParseOnPaste` 接线与重识别链本身；「service 把参数透传到弹窗」那一跳是单行赋值，
 * 真机连通性归 GUI 验收。
 *
 * ## 为什么注入 `draftTextProvider` 而不写编辑器文档
 *
 * 空结果下弹窗编辑器是**懒建**的（headless 里 `draftDocument()` 为 null），用例注入固定文本
 * 即可驱动与真机完全相同的重识别链（读文本 → 识别 → 回填）。轮询计时器在测试里不跑，
 * 由用例直接调同一函数。
 */
class DialogPasteReparseTest : BasePlatformTestCase() {

    private val ibatisFragment = """
        <select id="selectByCondition" parameterClass="User" resultMap="userResult">
          SELECT * FROM T_USER
          <dynamic prepend="WHERE">
            <isNotNull prepend="AND " property="id">ID = #id#</isNotNull>
            <isEqual prepend="AND " property="status" compareValue="1">STATUS = #status#</isEqual>
          </dynamic>
        </select>
    """.trimIndent()

    private fun newDialog(autoParse: Boolean): SqlPreviewDialog =
        SqlPreviewDialog(project, StandaloneSqlSourceResolver.emptyPreviewResult(), autoParseOnPaste = autoParse)

    private fun closeDialog(dialog: SqlPreviewDialog) {
        runCatching { dialog.panelForTest()?.clear() }
        runCatching { dialog.doDispose() }
    }

    /** 空弹窗待命 + 贴入 iBatis 片段 → 重识别出参数与条件行。 */
    fun test先开弹窗后贴入iBatis片段_能识别出参数与条件行() {
        val dialog = newDialog(autoParse = true)
        try {
            assertTrue("空/原文态开窗应进入待命（等待贴入）", dialog.pasteReparseArmedForTest())

            dialog.draftTextProvider = { ibatisFragment }
            dialog.reparseDraftFromEditor()

            val current = dialog.panelForTest()!!.current
            assertNotNull("重识别后面板应有结果", current)
            assertNotNull("iBatis 片段应产出条件/参数模型", current!!.ibatisParamModel)
            assertEquals("两个条件行", 2, current.ibatisParamModel!!.conditionRows.size)
            assertFalse("命中一次后必须交还手动（否则会与后续编辑打架）", dialog.pasteReparseArmedForTest())
        } finally {
            closeDialog(dialog)
        }
    }

    /** 命中后再次调用不得再改写面板（已交还手动）。 */
    fun test命中之后再调用不改写面板() {
        val dialog = newDialog(autoParse = true)
        try {
            dialog.draftTextProvider = { ibatisFragment }
            dialog.reparseDraftFromEditor()
            val first = dialog.panelForTest()!!.current
            assertNotNull(first)

            dialog.draftTextProvider = { "SELECT 1 FROM DUAL" }
            dialog.reparseDraftFromEditor()

            assertSame("已交还手动：再调用不得替换结果", first, dialog.panelForTest()!!.current)
        } finally {
            closeDialog(dialog)
        }
    }

    /** 正常语句入口开窗不进入待命（用户在那里的编辑是在改产物，不能被自动解析冲掉）。 */
    fun test正常开窗不进入待命() {
        val dialog = newDialog(autoParse = false)
        try {
            assertFalse("默认入口不得进入待命", dialog.pasteReparseArmedForTest())
            dialog.draftTextProvider = { ibatisFragment }
            dialog.reparseDraftFromEditor()
            assertNull("未待命时重识别应无操作", dialog.panelForTest()!!.current?.ibatisParamModel)
        } finally {
            closeDialog(dialog)
        }
    }
}
