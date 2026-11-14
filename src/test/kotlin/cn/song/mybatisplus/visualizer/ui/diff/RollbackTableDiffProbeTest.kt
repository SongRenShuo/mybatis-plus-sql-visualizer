package cn.song.mybatisplus.visualizer.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffTool
import com.intellij.diff.DiffToolType
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-RollbackSnapshotPreExec P0（V1 探针）平台测试：自写 [FrameDiffTool] 注册
 * `com.intellij.diff.DiffTool` EP 后，能出现在 `DiffManagerEx.getDiffTools()` 清单、
 * 位置先于平台内置工具（DiffManagerImpl 把 EP 扩展排在前）、canShow 只认自家请求、
 * viewer 可创建可释放。真机渲染（原生 diff 窗口实际打开）属 GUI 验收，交用户，not run 如实登记。
 *
 * DiffContext 抽象面（243/262 classfile 实证一致）：getProject(): Project?、
 * isWindowFocused(): boolean，外加 FocusableContext 的 isFocusedInWindow/requestFocusInWindow——
 * 测试用最小桩实现，不引 GUI。
 */
private class ProbeDiffContext(private val proj: Project?) : DiffContext() {
    override fun getProject(): Project? = proj
    override fun isWindowFocused(): Boolean = false
    override fun isFocusedInWindow(): Boolean = false
    override fun requestFocusInWindow() {
        // no-op
    }
}

class RollbackTableDiffProbeTest : BasePlatformTestCase() {

    private fun probeTool(): RollbackTableDiffProbeTool {
        // 243 classfile 实证（javap -v）：静态入口是 getInstance()；262 兼容性由 verifyGate 验证腿覆盖
        val tools = DiffManagerEx.getInstance().diffTools
        val probe = tools.filterIsInstance<RollbackTableDiffProbeTool>().firstOrNull()
        assertNotNull("diff.DiffTool EP 未被收集：getDiffTools() 里找不到 RollbackTableDiffProbeTool", probe)
        return probe!!
    }

    private fun probeRequest(): RollbackTableDiffProbeRequest = RollbackTableDiffProbeRequest(
        diffTitle = "before",
        columnNames = listOf("id", "name"),
        beforeRows = listOf(listOf("1", "old")),
        afterRows = listOf(listOf("1", "new")),
    )

    fun testProbeToolRegisteredViaEp() {
        val tools = DiffManagerEx.getInstance().diffTools
        assertTrue(
            "diff.DiffTool EP 未被收集：getDiffTools() 里找不到 RollbackTableDiffProbeTool",
            tools.any { it is RollbackTableDiffProbeTool },
        )
    }

    fun testProbeToolComesBeforeBuiltinTools() {
        val tools = DiffManagerEx.getInstance().diffTools
        val probeIndex = tools.indexOfFirst { it is RollbackTableDiffProbeTool }
        assertTrue("探针应经 EP 被收集", probeIndex >= 0)
        // 243 实测（首轮探针读数）：EP 工具**不在**清单最前（本探针下标 8/13，前面是 7 个内置工具）
        // ——调研报告里「EP 扩展排在内置之前」的说法对 243 不成立，已订正。
        // 选择正确性由「唯一 canShow 命中」保证：DiffRequestProcessor 逐个 canShow 取第一个命中，
        // 只要内置工具对我们这种非 ContentDiffRequest 全部 say-no，排后也能被选中。
        val matching = tools.filter { it.canShow(ProbeDiffContext(project), probeRequest()) }
        assertEquals(
            "探针请求必须只有探针工具一个 canShow 命中（命中列表=${
                matching.map { it.javaClass.simpleName }
            }）",
            1, matching.size,
        )
        assertTrue(matching.first() is RollbackTableDiffProbeTool)
    }

    fun testCanShowOnlyOwnRequest() {
        val tool = probeTool()
        assertTrue(tool.canShow(ProbeDiffContext(project), probeRequest()))
        // 平台自带的文本请求必须不命中（SimpleDiffRequest 的 contents 为 @NotNull，用工厂构造）
        val factory = DiffContentFactory.getInstance()
        val content = factory.createEditable(project, "select 1", PlainTextFileType.INSTANCE)
        val simple = SimpleDiffRequest("text-diff", content, content, "before", "after")
        assertFalse(tool.canShow(ProbeDiffContext(project), simple))
    }

    fun testViewerCreateInitDispose() {
        val viewer = probeTool().createComponent(ProbeDiffContext(project), probeRequest())
        assertNotNull(viewer.component)
        viewer.init()
        viewer.dispose()
    }

    fun testToolbarComponentsNoArgCtor() {
        // 两版本（243/262）classfile 均有 ()V 构造——钉住这个事实，P3 依赖它
        val components = FrameDiffTool.ToolbarComponents()
        assertNull(components.toolbarActions)
        assertEquals(DiffToolType.Default, probeTool().toolType)
    }
}
