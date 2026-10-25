package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-PreviewChainNoise W1 护栏：exec 文件分叉探测（[DataSourceSelector.probeExecVfDivergence]）
 * 的三态判定表。
 *
 * 缺陷形态（2026-10-23 用户真机日志 ×2）：`syncExecVfContentIfNeeded` 无条件
 * `vf.inputStream.readBytes()` 读盘，经 `createTempPsiFile` 的 EDT 冷路径每次命中
 * 「Slow operations are prohibited on EDT」断言。修法=按线程分流（T-DevAcceptFix2 ④ 同款定例）：
 *  - 有 document 只看内存态（**绝不读盘**——本类的「文档在场时盘上再变也不读」用例钉死这条）；
 *  - 无 document + EDT（allowDiskRead=false）不读盘、判不分叉；
 *  - 无 document + BGT（allowDiskRead=true）读盘比较，保留 T-EC-04 原防御面。
 *
 * 测试文件走生产同款路径 `<projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql`
 * （ExecDocSyncTest 同惯例，含跨用例 document 缓存隔离）。
 */
class ExecVfDivergenceProbeTest : BasePlatformTestCase() {

    private lateinit var execFile: java.io.File

    override fun setUp() {
        super.setUp()
        val base = project.basePath ?: error("project.basePath null")
        execFile = java.io.File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")
        // 测试隔离：document 缓存是应用级（按 vf 对象），残留 document 会泄入本用例
        //（ExecDocSyncTest setUp 同款注释）。VFS 内删除 → 下次 refresh 重建全新 vf。
        val stale = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
        }
        if (stale != null) {
            com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> { stale.delete(this) }
        }
    }

    // ======================== 分支一：有 document（绝不读盘） ========================

    /** ① 有 document 且内容不同 → 分叉（allowDiskRead 取值无关——文档分支不看它）。 */
    fun testDocWithDifferentTextIsDiverged() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf!!)
        assertNotNull(doc)

        assertTrue(
            "document 内容 ≠ sql 应判分叉",
            DataSourceSelector.probeExecVfDivergence(vf, "SELECT 2", false),
        )
        assertTrue(
            "文档分支不依赖 allowDiskRead（允许读盘时同样命中文档分支）",
            DataSourceSelector.probeExecVfDivergence(vf, "SELECT 2", true),
        )
    }

    /** ② 有 document、内容已相等但未保存 → 分叉（F4 收敛形态：doc=新 SQL、磁盘=旧 SQL）。 */
    fun testDocWithEqualTextButUnsavedIsDiverged() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf!!)
        assertNotNull(doc)
        WriteCommandAction.runWriteCommandAction(project) { doc!!.setText("SELECT 2") }
        assertTrue("前置：document 应处于未保存态", fdm.isDocumentUnsaved(doc!!))
        assertEquals("前置：磁盘应仍为旧 SQL", "SELECT 1", diskText())

        assertTrue(
            "内容相等但未保存应判分叉（否则 refresh 前漏收敛、命中 File Cache Conflict）",
            DataSourceSelector.probeExecVfDivergence(vf, "SELECT 2", false),
        )
    }

    /**
     * ③ 有 document、内容相等且已保存 → 不分叉**且不读盘**——本用例是「文档分支绝不读盘」
     * 不变式的判据：盘上另写第三种内容后（不 refresh），若实现读了盘必然判分叉而红。
     * 旧实现（无条件 `vf.inputStream.readBytes()`）在本用例下返回 true。
     */
    fun testDocCleanEqualTextDoesNotReadDisk() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        val fdm = FileDocumentManager.getInstance()
        val doc = fdm.getDocument(vf!!)
        assertNotNull(doc)
        assertFalse("前置：document 应为干净态", fdm.isDocumentUnsaved(doc!!))
        // 外部改盘（不 refresh，VFS/document 均不知情）——文档在场时探测不得触碰磁盘
        writeIo("SELECT 9")

        assertFalse(
            "文档在场且内容相等已保存 → 不分叉，且全程不得读盘",
            DataSourceSelector.probeExecVfDivergence(vf, "SELECT 1", true),
        )
    }

    // ======================== 分支二/三：无 document（按 allowDiskRead 分流） ========================

    /** ④ 无 document + allowDiskRead=false（=EDT 生产形态）→ 不读盘、判不分叉（盘上分叉也不看）。 */
    fun testNoDocOnEdtSkipsDiskProbe() {
        writeIo("SELECT 1")
        val vf = refreshVf()

        assertFalse(
            "EDT + 无 document：短路为不分叉（读盘即慢操作断言来源）",
            DataSourceSelector.probeExecVfDivergence(vf!!, "SELECT 2", false),
        )
    }

    /** ⑤ 无 document + allowDiskRead=true（=BGT 生产形态）→ 恢复 T-EC-04 读盘防御：盘上不同判分叉。 */
    fun testNoDocWithDiskReadDetectsDivergence() {
        writeIo("SELECT 1")
        val vf = refreshVf()

        assertTrue(
            "BGT + 无 document + 盘上不同 → 分叉（T-EC-04 防御面保留）",
            DataSourceSelector.probeExecVfDivergence(vf!!, "SELECT 2", true),
        )
        assertFalse(
            "BGT + 无 document + 盘上相同 → 不分叉（防御面 no-op 常态）",
            DataSourceSelector.probeExecVfDivergence(vf, "SELECT 1", true),
        )
    }

    /**
     * ⑤c BGT 端到端：预热链形态（工作线程调 syncExecVfContentIfNeeded + 泵 EDT）——
     * 读盘探测判出分叉 → dispatchDocWriteOnEdt 的 BGT 有界等待路径 → 盘上收敛。
     * 与 ExecDocSyncTest.projectExecFileOnBackgroundPumpingEdt 同款前提（headless 无 EDT 泵，
     * 须测试侧持续 dispatch；有界预算 2s 内必须完成）。
     */
    fun testBackgroundSyncRepairsDiskDivergence() {
        writeIo("SELECT 1")
        val vf = refreshVf()

        val repaired = syncOnBackgroundPumpingEdt(vf!!, "SELECT 2")

        assertTrue("BGT 读盘分叉应触发修复同步", repaired)
        assertEquals("修复须落盘（T-EC-01 同款收口语义）", "SELECT 2", diskText())
        assertFalse(
            "已收敛 → 再检应为 no-op",
            syncOnBackgroundPumpingEdt(vf, "SELECT 2"),
        )
    }

    /** BGT 工作线程执行 + 泵 EDT（projectExecFileOnBackgroundPumpingEdt 同款，10s 上限防卡死）。 */
    private fun syncOnBackgroundPumpingEdt(vf: VirtualFile, sql: String): Boolean {
        val holder = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread { holder.set(DataSourceSelector.syncExecVfContentIfNeeded(project, vf, sql)) }
        val start = System.currentTimeMillis()
        worker.start()
        while (worker.isAlive && System.currentTimeMillis() - start < 10_000) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(5)
        }
        worker.join(2_000)
        assertFalse("工作线程不应卡死（有界派发未交还？）", worker.isAlive)
        return holder.get()
    }

    /** 生产接线：EDT 调用方（syncExecVfContentIfNeeded 直调）恒走不读盘分支。 */
    fun testSyncEntryOnEdtNeverReadsDiskWithoutDocument() {
        writeIo("SELECT 1")
        val vf = refreshVf()

        // 测试线程=EDT（BasePlatformTestCase 惯例），盘上内容与 sql 不同——
        // 若实现把读盘带进 EDT 分支，这里会判分叉并触发修复（即旧行为，必须红）
        assertFalse(
            "EDT 入口 + 无 document：不得读盘、不得修复",
            DataSourceSelector.syncExecVfContentIfNeeded(project, vf!!, "SELECT 2"),
        )
        assertEquals("未修复 → 磁盘保持原内容", "SELECT 1", diskText())
    }

    private fun writeIo(content: String) {
        assertTrue("exec 目录应可创建", execFile.parentFile.exists() || execFile.parentFile.mkdirs())
        execFile.writeText(content)
    }

    private fun refreshVf(): VirtualFile? = WriteIntentReadAction.compute<VirtualFile?> {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
    }

    private fun diskText(): String = execFile.readText()
}
