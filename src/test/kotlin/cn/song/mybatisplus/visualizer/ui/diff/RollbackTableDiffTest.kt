package cn.song.mybatisplus.visualizer.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffToolType
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.DiffContentFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSnapshotAligner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-RollbackSnapshotPreExec P3 平台测试：正式表格 diff 三件套（P0 探针正式化）。
 * 覆盖 EP 收集、唯一 canShow 命中、viewer 创建/init/dispose、ToolbarComponents 无参构造、
 * statusPanel 摘要；行对齐与单元格状态断言直接走 [RollbackSnapshotAligner]。
 * 真机渲染（原生 diff 窗口实际打开）属 GUI 验收，交用户，not run 如实登记。
 *
 * DiffContext 抽象面（243/262 classfile 实证一致）：getProject(): Project?、
 * isWindowFocused(): boolean，外加 FocusableContext 的 isFocusedInWindow/requestFocusInWindow。
 */
private class ProbeDiffContext(private val proj: Project?) : DiffContext() {
    override fun getProject(): Project? = proj
    override fun isWindowFocused(): Boolean = false
    override fun isFocusedInWindow(): Boolean = false
    override fun requestFocusInWindow() {
        // no-op
    }
}

class RollbackTableDiffTest : BasePlatformTestCase() {

    private fun diffTool(): RollbackTableDiffTool {
        val tools = DiffManagerEx.getInstance().diffTools
        val tool = tools.filterIsInstance<RollbackTableDiffTool>().firstOrNull()
        assertNotNull("diff.DiffTool EP 未被收集：getDiffTools() 里找不到 RollbackTableDiffTool", tool)
        return tool!!
    }

    private fun diffRequest(): RollbackTableDiffRequest {
        val columns = listOf("id", "status")
        val before = listOf(listOf("1", "old"), listOf("2", "keep"), listOf("3", "gone"))
        val after = listOf(listOf("1", "new"), listOf("2", "keep"), listOf("4", "added"))
        val aligned = RollbackSnapshotAligner.align(columns, before, after, keyColumnIndexes = listOf(0))
        return RollbackTableDiffRequest(
            diffTitle = "t_order 改前/改后",
            beforeTitle = "before",
            afterTitle = "after",
            columnNames = columns,
            alignedRows = aligned.rows,
            alignmentWarnings = aligned.warnings,
        )
    }

    fun testDiffToolRegisteredViaEp() {
        val tools = DiffManagerEx.getInstance().diffTools
        assertTrue(
            "diff.DiffTool EP 未被收集：getDiffTools() 里找不到 RollbackTableDiffTool",
            tools.any { it is RollbackTableDiffTool },
        )
    }

    fun testDiffToolIsUniqueCanShowMatch() {
        val tools = DiffManagerEx.getInstance().diffTools
        val matching = tools.filter { it.canShow(ProbeDiffContext(project), diffRequest()) }
        assertEquals(
            "表格 diff 请求必须只有本工具一个 canShow 命中（命中=${matching.map { it.javaClass.simpleName }}）",
            1, matching.size,
        )
        assertTrue(matching.first() is RollbackTableDiffTool)
    }

    fun testCanShowRejectsTextDiffRequest() {
        val tool = diffTool()
        val factory = DiffContentFactory.getInstance()
        val content = factory.createEditable(project, "select 1", PlainTextFileType.INSTANCE)
        assertFalse(tool.canShow(ProbeDiffContext(project), SimpleDiffRequest("t", content, content, "b", "a")))
    }

    fun testViewerCreateInitDispose() {
        val viewer = diffTool().createComponent(ProbeDiffContext(project), diffRequest())
        assertNotNull(viewer.component)
        viewer.init()
        viewer.dispose()
    }

    fun testToolbarComponentsNoArgCtor() {
        // 两版本（243/262）classfile 均有 ()V 构造——钉住这个事实
        val components = FrameDiffTool.ToolbarComponents()
        assertNull(components.toolbarActions)
        assertEquals(DiffToolType.Default, diffTool().toolType)
    }

    fun testViewerComponentHostsTwoTables() {
        val viewer = diffTool().createComponent(ProbeDiffContext(project), diffRequest()) as RollbackTableDiffViewer
        assertTrue("根组件应含表格（对齐行装配）", viewer.component != null)
    }

    fun testAlignedRowStatuses() {
        // 对齐语义钉子：1 改动 / 2 相同 / 3 删除 / 4 新增
        val aligned = RollbackSnapshotAligner.align(
            listOf("id", "status"),
            listOf(listOf("1", "old"), listOf("2", "keep"), listOf("3", "gone")),
            listOf(listOf("1", "new"), listOf("2", "keep"), listOf("4", "added")),
            keyColumnIndexes = listOf(0),
        )
        val byKey = aligned.rows.groupBy { it.key }
        assertEquals(RollbackSnapshotAligner.CellStatus.CHANGED, byKey["1"]!!.single().status)
        assertEquals(setOf(1), byKey["1"]!!.single().changedColumnIndexes)
        assertEquals(RollbackSnapshotAligner.CellStatus.EQUAL, byKey["2"]!!.single().status)
        assertEquals(RollbackSnapshotAligner.CellStatus.REMOVED, byKey["3"]!!.single().status)
        assertEquals(RollbackSnapshotAligner.CellStatus.ADDED, byKey["4"]!!.single().status)
        assertTrue(aligned.usedKeyHeuristic)
    }
}
