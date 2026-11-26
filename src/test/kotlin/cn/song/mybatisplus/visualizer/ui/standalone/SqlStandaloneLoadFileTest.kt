package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「从文件加载」归位测试（T-LoadFilePlacement D-5；T-Perf33MergeAlign 选/读分离）。
 *
 * ## 这条能力为什么在工作台
 *
 * 通用预览弹窗（`SqlPreviewService.showResult` 的 21 处入口：5 条语句入口 + 18 个 gutter 图标 +
 * 日志/历史/CRUD）只负责**展示已渲染好的 SQL**；「从哪弄来这段文本」属于输入源，只有工作台有
 * 输入区。放在弹窗里除了语义歧义，还会静默替换掉「这条 SQL 来自哪个 mapper」的来源。
 *
 * ## 线程模型
 *
 * 选文件（EDT）→ 读前 `file.length` 粗筛（EDT）→ 读文本（后台线程）→ 回填/状态（EDT）。
 * 因读盘已挪到后台，涉及「读后回填」的断言都要泵 EDT（见 [awaitStatus]）。
 *
 * 桶位：引 `SqlInputEditor`/平台类，属**平台桶**（不进 testFast 快桶）。
 *
 * k2j 移植注：两个测试缝在 Java 侧是公有字段（`Supplier<VirtualFile>` / `Function<VirtualFile,String>`），
 * Kotlin lambda 需**显式泛型实参**（防空 lambda 在 Java 泛型位置上的推断歧义）。
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
            AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT),
        )
    }

    /** 读盘走后台线程 + `invokeLater(nonModal)`，状态行要泵 EDT 才能读到最终值。 */
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
        val vf = myFixture.addFileToProject("UserMapper.xml", MAPPER_XML).virtualFile
        p.fileChooserForTest = java.util.function.Supplier<VirtualFile> { vf }

        clickLoadFile(p)

        val status = awaitStatus(p, predicate = { it.contains("selectById") })
        assertEquals("输入区应换成文件全文", MAPPER_XML, p.inputTextForTest())
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
        val vf = myFixture.addFileToProject("a.xml", MAPPER_XML).virtualFile
        p.fileChooserForTest = java.util.function.Supplier<VirtualFile> { vf }
        // 读盘改到后台后，「已替换」状态写入→随即被解析结果覆盖，中间窗口很窄。
        // 用闩把读盘卡在后台：放行后首次泵 EDT 才执行 applyLoadedFile，此刻状态必是「已替换」。
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        p.loadTextInvoker = java.util.function.Function<VirtualFile, String> {
            readStarted.countDown()
            releaseRead.await()
            MAPPER_XML
        }

        clickLoadFile(p)
        assertTrue("后台读应已开始", readStarted.await(5, TimeUnit.SECONDS))
        releaseRead.countDown()

        val expected = MpSqlVisualizerBundle.message("standalone.status.loadedFile.replaced", "a.xml")
        val status = awaitStatus(p, predicate = { it == expected })
        assertEquals("替换既有输入时状态行应写明已替换", expected, status)
        assertEquals("输入区应换成文件全文", MAPPER_XML, p.inputTextForTest())
    }

    /** A-3：超长文件（字符数 > MAX_TEXT_LENGTH）不写入输入区，避免把编辑器撑爆。 */
    fun test从文件加载_超长文件不写入输入区() {
        val p = newPanel()
        p.setInputTextForTest("SELECT 1")
        // 字符数刚过上限 1（字节数 100001 < 4×上限），只走 applyLoadedFile 的精确字符数判据
        val huge = "x".repeat(StandaloneSqlSourceResolver.MAX_TEXT_LENGTH + 1)
        val vf = myFixture.addFileToProject("huge.xml", huge).virtualFile
        p.fileChooserForTest = java.util.function.Supplier<VirtualFile> { vf }

        clickLoadFile(p)

        val expected = MpSqlVisualizerBundle.message("standalone.status.loadedFile.tooLong")
        val status = awaitStatus(p, predicate = { it == expected })
        assertEquals("超长文件不得写入输入区", "SELECT 1", p.inputTextForTest())
        assertEquals("应给出超长提示", expected, status)
    }

    /** 入口必须真的挂在输入工具栏上（只测动作类存在会漏掉「忘了加进 group」）。 */
    fun test从文件加载入口挂在输入工具栏() {
        val p = newPanel()
        val names = p.inputToolbarActionNamesForTest()
        assertTrue("输入工具栏应含「从文件加载」动作，实际=$names", names.contains("LoadFileAction"))
    }

    /** T-Perf33MergeAlign：读盘必须发生在后台线程，不能在 EDT 上读（大文件会卡界面）。 */
    fun test从文件加载_读盘不在EDT() {
        val p = newPanel()
        val vf = myFixture.addFileToProject("UserMapper.xml", MAPPER_XML).virtualFile
        p.fileChooserForTest = java.util.function.Supplier<VirtualFile> { vf }
        var wasEdt: Boolean? = null
        p.loadTextInvoker = java.util.function.Function<VirtualFile, String> {
            wasEdt = ApplicationManager.getApplication().isDispatchThread
            MAPPER_XML
        }

        clickLoadFile(p)

        val status = awaitStatus(p, predicate = { it.contains("selectById") })
        assertTrue("应出识别结果：$status", status.contains("selectById"))
        assertEquals("读盘必须发生在后台线程（不在 EDT）", false, wasEdt)
    }

    /** T-Perf33MergeAlign：字节数超 4×上限的文件在读之前就被粗筛拒掉，绝不整份读进内存。 */
    fun test从文件加载_超长文件不读取() {
        val p = newPanel()
        p.setInputTextForTest("SELECT 1")
        // 字节数 > 4×字符上限 ⇒ 字符数必超上限；粗筛在 file.length 上拦，不读盘
        val huge = "x".repeat(StandaloneSqlSourceResolver.MAX_TEXT_LENGTH * 4 + 1)
        val vf = myFixture.addFileToProject("huge.xml", huge).virtualFile
        p.fileChooserForTest = java.util.function.Supplier<VirtualFile> { vf }
        p.loadTextInvoker = java.util.function.Function<VirtualFile, String> {
            fail("超长文件不该被读取")
            ""
        }

        clickLoadFile(p)

        // 粗筛在读之前（EDT 同步）完成：不泵 EDT 即可读到 tooLong
        assertEquals(
            "应给出超长提示（读前 file.length 粗筛）",
            MpSqlVisualizerBundle.message("standalone.status.loadedFile.tooLong"),
            p.statusTextForTest(),
        )
        assertEquals("超长文件不得写入输入区", "SELECT 1", p.inputTextForTest())
    }

    companion object {
        private val MAPPER_XML = """
            <mapper namespace="com.example.UserMapper">
              <select id="selectById">SELECT * FROM t_user WHERE id = #{id}</select>
            </mapper>
        """.trimIndent()
    }
}
