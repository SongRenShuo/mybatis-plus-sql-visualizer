package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-StartupReadLockFreeze S4-a：EDT document 写派发**不得就地执行**。
 *
 * 事故（2026-11-13 用户 Linux 打开即冻结，栈 2）：EDT 在写意图读锁上下文里就地跑
 * `WriteAction` → `prepareWriteFromWriteIntentBlocking` 升级写锁等读者退出；后台 M2 预取持
 * 阻塞读锁跑索引检索（`ReadAction.compute`→`computeBlocking`，不可取消）→ 互等成环。
 * 修法：`dispatchDocWriteOnEdt` 的 EDT 分支改为 nonModal 投递一拍 + 有界等待（与 BGT 分支同构）。
 *
 * 三条用例各自钉一个语义：
 *  1. 可写上下文（生产常态）——**不再就地写**：投递计数 +1、磁盘/文档保持旧值、函数立即交还；
 *     泵一拍后内容收敛（写体幂等，延后一拍不改变终态）；
 *  2. 不可写上下文——原语义不变：仍转 nonModal 投递、返回 false、不就地写；
 *  3. owner 已销毁——排队拍被丢弃，调用方**不烧满预算**（有界等待的自愈面）。
 *
 * 测试形态说明（判别力所在）：单测线程即 EDT（`BasePlatformTestCase.runInDispatchThread()==true`），
 * 所以「已在 EDT 不自等」这条生产保护改由 [DocWriteContext.deferEvenInUnitTestMode] 强制走生产
 * 形态来观测——默认单测仍按就地等价路径跑（见该测试缝注释），既有 `ExecDocSyncTest` 用例依赖它。
 */
class DocWriteDispatchSafetyTest : BasePlatformTestCase() {

    private lateinit var execFile: File

    override fun setUp() {
        super.setUp()
        val base = project.basePath ?: error("project.basePath null")
        execFile = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")
        // 与 ExecDocSyncTest 同款隔离：document 缓存是应用级（按 vf 对象），上一用例的 document
        // 会经同路径 vf 泄入本用例 → VFS 内删除，下次 refresh 拿到全新 vf。
        val stale = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
        }
        if (stale != null) {
            com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> { stale.delete(this) }
        }
        DocWriteContext.writeAllowedProbe = null
        DocWriteContext.deferEvenInUnitTestMode = false
    }

    override fun tearDown() {
        try {
            DocWriteContext.writeAllowedProbe = null
            DocWriteContext.deferEvenInUnitTestMode = false
        } finally {
            super.tearDown()
        }
    }

    // ============ ① 可写上下文：EDT 分支不再就地写，改投递一拍 ============

    /**
     * 反证（把 EDT 分支改回「可写就地执行」→ 本用例必红）：改回后写体在本拍执行，
     * 磁盘立刻变 `SELECT 2`、`deferredCount` 不动。
     */
    fun testEdtBranchDefersInsteadOfWritingInlineWhenContextAllowed() {
        writeIo("SELECT 1")
        val vf = refreshVf()!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        assertNotNull("document 应已加载（有冲突面）", doc)
        DocWriteContext.deferEvenInUnitTestMode = true
        DocWriteContext.writeAllowedProbe = { true }
        try {
            val before = DocWriteContext.deferredCount.get()
            val start = System.currentTimeMillis()
            val accepted = DataSourceSelector.dispatchDocWriteOnEdt(project, vf, "SELECT 2")
            val elapsed = System.currentTimeMillis() - start

            assertEquals("必须投递一拍（不得就地写）", before + 1, DocWriteContext.deferredCount.get())
            assertTrue("EDT 不得自等自己的排队事件（实测 ${elapsed}ms）", elapsed < 1500)
            assertTrue("投递语义：已受理", accepted)
            assertEquals("投递拍之前磁盘不得变", "SELECT 1", diskText())
            assertEquals("投递拍之前文档不得变", "SELECT 1", doc!!.text)

            UIUtil.dispatchAllInvocationEvents()
            assertEquals("延后那一拍应收敛磁盘（写体幂等）", "SELECT 2", diskText())
            assertEquals("延后那一拍应收敛文档", "SELECT 2", doc.text)
        } finally {
            DocWriteContext.writeAllowedProbe = null
            DocWriteContext.deferEvenInUnitTestMode = false
        }
    }

    // ============ ② 不可写上下文：原语义不变（投递 + 返回 false） ============

    /**
     * T-PreviewEdtGuard S2 语义逐字保留：判为不可写 → 转 nonModal 投递一拍并**立即**返回 false，
     * 不就地写、不白等预算。改前行为即如此（`runWriteSafe` 的 else 分支）。
     */
    fun testEdtBranchKeepsUnwritableSemantics() {
        writeIo("SELECT 1")
        val vf = refreshVf()!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        assertNotNull(doc)
        DocWriteContext.writeAllowedProbe = { false }
        try {
            val before = DocWriteContext.deferredCount.get()
            val start = System.currentTimeMillis()
            val ok = DataSourceSelector.dispatchDocWriteOnEdt(project, vf, "SELECT 2")
            val elapsed = System.currentTimeMillis() - start

            assertFalse("不可写：应立即交还 false（原语义）", ok)
            assertEquals("不可写：仍应转 nonModal 投递一拍", before + 1, DocWriteContext.deferredCount.get())
            assertTrue("不可写：不得白等预算（实测 ${elapsed}ms）", elapsed < 1500)
            assertEquals("不可写：磁盘不得同步写", "SELECT 1", diskText())
            assertEquals("不可写：文档不得同步写", "SELECT 1", doc!!.text)

            // 下一拍的平台上下文是 write-safe（nonModal 包装），模拟其判定成立后收敛
            DocWriteContext.writeAllowedProbe = { true }
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("延后一拍应收敛", "SELECT 2", diskText())
        } finally {
            DocWriteContext.writeAllowedProbe = null
        }
    }

    // ============ ③ 丢弃的派发拍：不阻塞到超时之后 ============

    /**
     * owner（项目）已销毁 → 排队那一拍在被泵到时丢弃，写体不执行；
     * 调用方即使因无人泵队列等满预算，也只等**预算**（300ms），不会无限挂住。
     *
     * 线程形态：工作线程（BGT）调 [DocWriteContext.invokeLaterWriteSafeAndWait]，
     * 测试 EDT 在 join 期间不泵 → 那一拍不可能执行，只能走超时分支。
     */
    fun testDisposedOwnerTickDroppedWithoutHangingCaller() {
        DocWriteContext.deferEvenInUnitTestMode = true
        DocWriteContext.writeAllowedProbe = { true }
        val owner = Disposer.newDisposable()
        Disposer.dispose(owner)
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        val holder = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val worker = Thread {
            holder.set(
                DocWriteContext.invokeLaterWriteSafeAndWait(owner, "test disposed owner", 300L) {
                    ran.set(true)
                    true
                },
            )
        }
        val start = System.currentTimeMillis()
        try {
            worker.start()
            worker.join(5_000)
            val elapsed = System.currentTimeMillis() - start
            assertFalse("工作线程不应卡死（有界等待失效？）", worker.isAlive)
            assertEquals("owner 已销毁：应返回 false", java.lang.Boolean.FALSE, holder.get())
            assertTrue("只等预算 300ms，实测 ${elapsed}ms", elapsed < 1_500)
            assertFalse("写体不得执行", ran.get())

            // 泵一拍：排队的 runnable 命中 Disposer.isDisposed → 丢弃（写体仍不执行）
            UIUtil.dispatchAllInvocationEvents()
            assertFalse("被丢弃的拍不得补写", ran.get())
        } finally {
            DocWriteContext.writeAllowedProbe = null
            DocWriteContext.deferEvenInUnitTestMode = false
        }
    }

    // ============ ④ 新 API 的 BGT 正路：有界等待拿到真实结果 ============

    /** BGT 调用 [DocWriteContext.invokeLaterWriteSafeAndWait]：投递 → 测试 EDT 泵那一拍 → 拿到 true。 */
    fun testBackgroundCallerGetsDispatchedResult() {
        DocWriteContext.deferEvenInUnitTestMode = true
        DocWriteContext.writeAllowedProbe = { true }
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        val holder = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val worker = Thread {
            holder.set(
                DocWriteContext.invokeLaterWriteSafeAndWait(project, "test bounded bgt", 2_000L) {
                    ran.set(true)
                    true
                },
            )
        }
        val start = System.currentTimeMillis()
        try {
            worker.start()
            while (worker.isAlive && System.currentTimeMillis() - start < 5_000) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(5)
            }
            worker.join(2_000)
            assertFalse("工作线程不应卡死", worker.isAlive)
            assertEquals("有界等待应拿到写体返回值 true", java.lang.Boolean.TRUE, holder.get())
            assertTrue("写体应已执行", ran.get())
        } finally {
            DocWriteContext.writeAllowedProbe = null
            DocWriteContext.deferEvenInUnitTestMode = false
        }
    }

    // ======================== 辅助 ========================

    private fun writeIo(content: String) {
        assertTrue("exec 目录应可创建", execFile.parentFile.exists() || execFile.parentFile.mkdirs())
        execFile.writeText(content)
    }

    private fun refreshVf(): VirtualFile? = WriteIntentReadAction.compute<VirtualFile?> {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
    }

    private fun diskText(): String = execFile.readText()
}