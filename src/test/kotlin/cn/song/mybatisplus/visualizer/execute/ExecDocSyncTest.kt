package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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
 * T-ExecDocSync：执行文件 File Cache Conflict 根治测试。
 *
 * 用户真机（easy-code-demo）：mpsql-exec.sql 弹「File Cache Conflict——Changes have been
 * made ... in memory and on disk」。根因三点（同问题族第三轮，前两轮 M36.5.13/M36.5.43）：
 *  - T-EC-01：document 驱动写只改内存不落盘，document 恒留「已修改未保存」窗口；
 *  - T-EC-02：WriteAction 失败兜底无条件裸写盘（绕过 document = 冲突的外部改写条件）；
 *  - T-EC-03：BGT 预热与 EDT 冷路径双写方不互斥；T-EC-04：裸写路径防御收口。
 *
 * 沙盒无 Database 插件，projectExecFile 全链纯 VFS/PSI 可直测（ExecTempFileFixTest 同惯例）。
 * 测试文件走生产同款路径 `<projectBase>/.idea/sql-visualizer/exec/mpsql-exec.sql`。
 */
class ExecDocSyncTest : BasePlatformTestCase() {

    private lateinit var execFile: File

    override fun setUp() {
        super.setUp()
        val base = project.basePath ?: error("project.basePath null")
        execFile = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")
        // 测试隔离：BasePlatformTestCase 各用例共用 fixture 目录，且 FileDocumentManager 的
        // document 缓存是应用级（按 vf 对象）——上一用例加载的 document 会经同路径 vf 泄入
        // 本用例（首轮实证：canFallBack 首断言/裸写兜底/并发用例全被残留 document 污染）。
        // VFS 内删除（非裸 IO delete）→ 下次 refresh 重建全新 vf，缓存不跨用例串场。
        val stale = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
        }
        if (stale != null) {
            com.intellij.openapi.application.WriteAction.compute<Unit, Throwable> { stale.delete(this) }
        }
    }

    // ======================== T-EC-01：document 分支同临界区落盘 ========================

    /**
     * 核心回归：projectExecFile 走 document 分支后**磁盘立即**是新 SQL，document 不滞留
     * 「已修改未保存」态——修复前 setText+commit 只改内存，磁盘靠 IDE 自动保存追赶，
     * 窗口期内任何外部改盘即弹 File Cache Conflict（真机截图实证）。
     */
    fun testDocBranchPersistsSqlToDiskImmediately() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        // 模拟编辑器持有 document（已加载 = 有冲突面）
        val doc = FileDocumentManager.getInstance().getDocument(vf!!)
        assertNotNull("document 应已加载", doc)
        assertEquals("SELECT 1", doc!!.text)

        DataSourceSelectorResultsKt.projectExecFile(project, "SELECT 2")

        assertEquals("T-EC-01：落盘不依赖 IDE 自动保存——写后磁盘立即是新 SQL", "SELECT 2", diskText())
        assertEquals("document 同步为新 SQL", "SELECT 2", doc.text)
        // 262 平台 Document 接口已无 isModified()（javap 实证，仅余 isLineModified）——
        // 「已修改未保存」态的正确查询是 FileDocumentManager.isDocumentUnsaved
        assertFalse(
            "T-EC-01 核心：document 不得滞留「已修改未保存」态",
            FileDocumentManager.getInstance().isDocumentUnsaved(doc),
        )
    }

    // ============ T-ExecDocSync3：VFS 已有该文件即绝不裸写（T-EC-02 守卫退役） ============

    /**
     * 核心回归（第四轮真机日志实证的收口）：WriteAction 失败/EDT 派发超时 → **磁盘一律不动**，
     * 且不再抛异常（旧 writeConflictGuard 已退役）。
     *
     * 真机失败链（2026-09-17 真实 IDE 2024.3 日志）：EDT 忙（冷启动索引）→ dispatch 有界等待 2s
     * 超时返回 false → 旧守卫查 getCachedDocument 得 null（彼时排队中的 EDT 写任务尚未执行、
     * document 还没建）→ 放行裸写改盘 → 排队任务随后建出 document 并标脏 → 裸写引发的 VFS 变更
     * 落到这个新文档上 → MemoryDiskConflictResolver 弹「重载 mpsql-exec.sql？」。即插件与自己的
     * 排队写赛跑。此用例钉死「VFS 有该文件时不裸写」。
     */
    fun testNoRawWriteWhenDocWriteFailsAndVfExists() {
        writeIo("SELECT 1")
        refreshVf() // vf 在 VFS（= 存在 document 可能），不预加载 document —— 正是真机超时那一刻的形态
        DataSourceSelector.execWriteActionFailureInjection = true
        try {
            val f = DataSourceSelectorResultsKt.projectExecFile(project, "SELECT 2")
            assertNotNull("应返回文件路径（调用方需要 ioFile 做 refresh/映射）", f)
            assertEquals(
                "T-ExecDocSync3：裸写必须被跳过——磁盘保持旧值（宁旧一拍，由后续 console doc 同步链收敛）",
                "SELECT 1",
                diskText(),
            )
        } finally {
            DataSourceSelector.execWriteActionFailureInjection = false
        }
    }

    /** document 已加载时同样不裸写（原守卫拒绝路径的等价保证，改为不抛异常、不落盘）。 */
    fun testNoRawWriteWhenDocWriteFailsAndDocumentLoaded() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        FileDocumentManager.getInstance().getDocument(vf!!)
        DataSourceSelector.execWriteActionFailureInjection = true
        try {
            val f = DataSourceSelectorResultsKt.projectExecFile(project, "SELECT 2")
            assertNotNull(f)
            assertEquals("有 document 时不得裸写（旧守卫语义保留，只是不再抛异常）", "SELECT 1", diskText())
        } finally {
            DataSourceSelector.execWriteActionFailureInjection = false
        }
    }

    /** 首次创建（VFS 无条目）→ 裸写仍合法（无 vf 可挂 document，无冲突面）。 */
    fun testRawWriteStillUsedWhenVfMissing() {
        assertFalse("前置：文件应不存在", execFile.exists())
        val f = DataSourceSelectorResultsKt.projectExecFile(project, "SELECT 1")
        assertNotNull(f)
        assertEquals("VFS 无条目时裸写创建（首次）", "SELECT 1", diskText())
    }

    // ======================== T-PreviewFast：差量快路径（P1）与判据（P3） ========================

    /**
     * P1 判据表驱动：内容一致 → true；不一致 → false。
     * T-DevAcceptFix2 ④（20261006 第二轮验收）：**无 document 时不再读 vf 内容**（EDT 禁 VFS 慢操作），
     * 改判「本进程最近写入内容 + 写入时 VFS 时间戳」内存标记——标记缺失/时间戳不符即 false。
     */
    fun testExecFileContentMatchesTableDriven() {
        writeIo("SELECT 1")
        val vf = refreshVf()!!
        assertFalse(
            "无 document 且无写入标记（外部写入/新进程）→ false（宁可多写一次，不读盘）",
            DataSourceSelector.execFileContentMatches(project, vf, "SELECT 1"),
        )
        // 经生产写入路径打标记（内容 + 时间戳）
        DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT 1")
        assertTrue(
            "无 document 但写入标记命中（内容 + 时间戳一致）→ true",
            DataSourceSelector.execFileContentMatches(project, vf, "SELECT 1"),
        )
        assertFalse(
            "无 document 且标记内容不同 → false",
            DataSourceSelector.execFileContentMatches(project, vf, "SELECT 2"),
        )

        FileDocumentManager.getInstance().getDocument(vf)
        assertTrue("有 document 时以 document 文本为准：一致 → true", DataSourceSelector.execFileContentMatches(project, vf, "SELECT 1"))
        assertFalse("有 document 时以 document 文本为准：不一致 → false", DataSourceSelector.execFileContentMatches(project, vf, "SELECT 2"))
    }

    /**
     * P1 核心：目标内容与现状一致 → **不派发、不落盘**（真机连续预览同一 SQL 的主路径）。
     * 断言手段：注入 WriteAction 失败——若快路径生效，注入根本不会执行，磁盘与文档均无变化且不抛。
     */
    fun testIdenticalContentShortCircuitsWithoutWrite() {
        writeIo("SELECT 1")
        val vf = refreshVf()!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        assertNotNull(doc)
        DataSourceSelector.execWriteActionFailureInjection = true
        try {
            val f = DataSourceSelectorResultsKt.projectExecFile(project, "SELECT 1")
            assertNotNull("快路径应正常返回文件路径", f)
            assertEquals("内容一致：磁盘不动", "SELECT 1", diskText())
            assertFalse("内容一致：文档不得被标脏", FileDocumentManager.getInstance().isDocumentUnsaved(doc!!))
        } finally {
            DataSourceSelector.execWriteActionFailureInjection = false
        }
    }

    /** P3 判据：exec 文件「正被编辑器打开」才为真——文档加载但无编辑器时仍为 false（预热可刷新）。 */
    fun testIsExecDocOpenInEditorReflectsEditorPresence() {
        writeIo("SELECT 1")
        val vf = refreshVf()!!
        assertFalse("无已加载 document → false", DataSourceSelector.isExecDocOpenInEditor(vf))
        FileDocumentManager.getInstance().getDocument(vf)
        assertFalse("document 已加载但无编辑器 → false", DataSourceSelector.isExecDocOpenInEditor(vf))
    }

    // ======================== T-EC-04：裸写路径防御收口 ========================

    /** 文件不存在（首次执行）：裸写创建 + refresh 可见 + 防御收口 no-op。 */
    fun testFreshFileRawWriteThenVfsVisibleAndSyncNoop() {
        assertFalse("前置：文件应不存在", execFile.exists())

        val f = DataSourceSelectorResultsKt.projectExecFile(project, "SELECT 1")
        assertNotNull(f)
        assertTrue("裸写应已创建文件", f!!.exists())

        val vf = refreshVf()
        assertNotNull("refresh 后 VFS 应可见", vf)
        assertFalse(
            "刚裸写的内容与 sql 一致 → syncExecVfContentIfNeeded 应为 no-op",
            DataSourceSelector.syncExecVfContentIfNeeded(project, vf!!, "SELECT 1"),
        )
        assertEquals("SELECT 1", diskText())
    }

    /**
     * T-PreviewChainNoise W1 契约改写：EDT 上无 document 时**不读盘、不修复**（返回 false）——
     * 原 T-EC-04 用例「盘上分叉 → repaired=true」的判定语义移到 BGT 判据
     * （ExecVfDivergenceProbeTest.testNoDocWithDiskReadDetectsDivergence /
     * testBackgroundSyncRepairsDiskDivergence，生产里该分支只有 BGT 预热链会走）。
     *
     * 本用例是新旧实现的判别器：旧实现无条件读盘，会判出分叉并 repaired=true（红）；
     * 新实现 EDT + 无 document 短路为不分叉（绿）且磁盘保持原内容。
     */
    fun testSyncVfContentIfNeededSkipsDiskProbeOnEdtWithoutDocument() {
        writeIo("SELECT 1")
        val vf = refreshVf()

        val repaired = DataSourceSelector.syncExecVfContentIfNeeded(project, vf!!, "SELECT 2")

        assertFalse("EDT + 无 document：禁止读盘探测，也不得触发修复", repaired)
        assertEquals("未修复 → 磁盘保持原内容", "SELECT 1", diskText())
    }

    // ======================== T-EC-03：双写方并发（smoke） ========================

    /**
     * 两线程并发写同一执行文件不抛错、终态为某一写方的完整内容（execFileWriteLock 串行化）。
     * 并发全程在 BGT 裸调（与生产 prewarmExecTempFileInner 同形态）；文件未 refresh 期间
     * existing==null 恒走裸写路径，无需平台写锁，不会与 EDT 测试线程互锁。撕裂检测受
     * OS 单次 write 粒度限制，锁的强保证另由临界区结构审查背书——本测试为无异常一致性 smoke。
     */
    fun testConcurrentWritersNoTornContent() {
        val p1 = "A".repeat(65536) + "SELECT_1;"
        val p2 = "B".repeat(65536) + "SELECT_2;"
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = listOf(p1, p2).map { payload ->
            Thread {
                repeat(4) {
                    try {
                        DataSourceSelectorResultsKt.projectExecFile(project, payload)
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertTrue("并发写不应抛错：${errors.joinToString { "${it.javaClass.simpleName}: ${it.message}" }}", errors.isEmpty())
        threads.forEach { assertFalse("工作线程不应卡死", it.isAlive) }

        val final = diskText()
        assertTrue(
            "终态内容必须是某一写方的完整 payload（不得交错混写）",
            final == p1 || final == p2,
        )
    }

    // ============ T-FixWriteSafe：BGT 有界派发的 write-safe 闸门（T-WriteSafeCtx 留档补口） ============

    /**
     * 闸门判「不可写」：派发出去的那一拍**不得**自行写文档，而要转 nonModal 投递并把控制权**立即**
     * 交还调用方——调用方按 T-ExecDocSync3 记 "raw write SKIPPED"、不裸写，内容由延后那一拍收敛。
     *
     * 线程形态照生产：工作线程裸调 [DataSourceSelectorResultsKt.projectExecFile]（BGT 派发 + 2s 有界等待），
     * 测试 EDT 泵事件队列替它执行那一拍。两条判据各自钉死一个语义：
     *  - `deferredCount` 递增 = 闸门拦下了同步写、改投递（**改前此断言必红**：写体会在排到的那一拍里
     *    直接执行，计数不动）；
     *  - 耗时 < 1800ms = 不白等 `EDT_EXEC_DOC_SYNC_WAIT_MS` 预算（若闸门在不可写时仍等满 2s，必 ≥2000ms）。
     * 注意此处**不能**断言「磁盘仍是旧值」：nonModal 那一拍在无模态存续时同样立刻可执行，可能就在
     * 同一次泵事件里跑掉——生产亦如此（延后到模态结束，而不是永远延后）。
     */
    fun testBoundedDispatchDefersWriteWhenContextUnsafe() {
        writeIo("SELECT 1")
        refreshVf()
        DocWriteContext.writeAllowedProbe = { false }
        try {
            val before = DocWriteContext.deferredCount.get()
            val (elapsed, returned) = projectExecFileOnBackgroundPumpingEdt("SELECT 2")

            assertNotNull("派发未完成也应返回文件路径（调用方需要它做 refresh/映射）", returned)
            assertEquals("不可写：应转 nonModal 投递一拍（而非在那一拍里同步写）", before + 1, DocWriteContext.deferredCount.get())
            assertTrue("不可写时必须立即交还调用方，不得白等 2s 预算（实测 ${elapsed}ms）", elapsed < 1800)

            // 延后那一拍（nonModal）跑在 write-safe 上下文里 → 内容收敛（幂等写，重跑安全）
            DocWriteContext.writeAllowedProbe = { true }
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("延后那一拍应把 SQL 写进文档并落盘", "SELECT 2", diskText())
        } finally {
            DocWriteContext.writeAllowedProbe = null
        }
    }

    /** 闸门判「可写」（常态：默认模态=NON_MODAL，平台已套 runWithWritingAllowed）：就地写、同拍交还，零投递——改前行为逐字保留。 */
    fun testBoundedDispatchWritesInlineWhenContextAllowed() {
        writeIo("SELECT 1")
        refreshVf()
        DocWriteContext.writeAllowedProbe = { true }
        try {
            val before = DocWriteContext.deferredCount.get()
            val (elapsed, returned) = projectExecFileOnBackgroundPumpingEdt("SELECT 2")

            assertNotNull(returned)
            assertEquals("可写：派发那一拍就应落盘（有界等待等到它）", "SELECT 2", diskText())
            assertEquals("可写：闸门不得产生投递", before, DocWriteContext.deferredCount.get())
            assertTrue("可写路径不得引入额外等待（实测 ${elapsed}ms）", elapsed < 1800)
        } finally {
            DocWriteContext.writeAllowedProbe = null
        }
    }

    // ============ T-PreviewEdtGuard S2：EDT 直调派发的 write-safe 闸门（R33 三验 2 组补口） ============
    //
    // R33 三验真机日志实证 2 组 Write-unsafe context（帧=writeVfTextViaDocument）：EDT 直调分支
    // 原样直跑 docWriteOnce，write-unsafe 活动（any() 投递/「写不安全进入」的模态泵）里
    // WriteAction.compute 不 confer 写安全资格（T-WriteSafeCtx 平台源码定例）→ commitDocument
    // 的 PSI 事件链即报错。修复=该分支同样过 DocWriteContext.runWriteSafe 闸。
    //
    // 证据边界（与 DocWriteContextTest 头注同口径）：真实 TransactionGuard 断言需生产 EDT 活动
    // 语义，单测环境 TestLogger 只收集不抛、且 isUnitTestMode 恒放行——这里用 writeAllowedProbe
    // 注入「当前不可写」观测**闸门分支行为**（同 T-FixWriteSafe 两例），真实判据交真机门禁。

    /**
     * 核心：闸门判「不可写」时 EDT 直调必须**不同步写**（磁盘/document 均保持旧值）、转 nonModal
     * 投递一拍并**立即返回 false**（不白等）。
     *
     * 反证（摘掉修复必红）：分支改回 `return docWriteOnce(...)` → 写体当场执行（磁盘变 SELECT 2、
     * deferredCount 不动、返回 true），本例三条断言全红。
     */
    fun testEdtDirectDispatchDefersWriteWhenContextUnsafe() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        val doc = FileDocumentManager.getInstance().getDocument(vf!!)
        assertNotNull("document 应已加载（有冲突面）", doc)
        DocWriteContext.writeAllowedProbe = { false }
        try {
            val before = DocWriteContext.deferredCount.get()
            val ok = DataSourceSelectorResultsKt.dispatchDocWriteOnEdt(project, vf, "SELECT 2")

            assertFalse("不可写：应立即交还 false（不白等）", ok)
            assertEquals("不可写：应转 nonModal 投递一拍", before + 1, DocWriteContext.deferredCount.get())
            assertEquals("不可写：磁盘不得同步写", "SELECT 1", diskText())
            assertEquals("不可写：document 不得同步写", "SELECT 1", doc!!.text)

            // 延后那一拍（nonModal，平台已套 runWithWritingAllowed）→ 模拟「下一拍已可写」再泵队列
            DocWriteContext.writeAllowedProbe = { true }
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("延后一拍应把 SQL 写进文档并落盘（写体幂等）", "SELECT 2", diskText())
        } finally {
            DocWriteContext.writeAllowedProbe = null
        }
    }

    /** 闸门判「可写」（常态用户活动）：就地写、返回 true、零投递——改前行为逐字保留。 */
    fun testEdtDirectDispatchWritesInlineWhenContextAllowed() {
        writeIo("SELECT 1")
        val vf = refreshVf()
        FileDocumentManager.getInstance().getDocument(vf!!)
        DocWriteContext.writeAllowedProbe = { true }
        try {
            val before = DocWriteContext.deferredCount.get()
            val ok = DataSourceSelectorResultsKt.dispatchDocWriteOnEdt(project, vf, "SELECT 2")

            assertTrue("可写：应就地写并返回 true", ok)
            assertEquals("可写：磁盘应立即是新 SQL", "SELECT 2", diskText())
            assertEquals("可写：闸门不得产生投递", before, DocWriteContext.deferredCount.get())
        } finally {
            DocWriteContext.writeAllowedProbe = null
        }
    }

    // ======================== 辅助 ========================

    /**
     * 工作线程裸调 [DataSourceSelectorResultsKt.projectExecFile]（生产 BGT 预热/提交链同形态——正是
     * [DataSourceSelectorResultsKt.dispatchDocWriteOnEdt] 两段式派发的唯一触发面），测试 EDT（= 测试线程，
     * `BasePlatformTestCase.runInDispatchThread()==true`）循环泵事件队列替它执行派发出去的那一拍。
     * 返回 (耗时ms, 返回值)；耗时含工作线程全链时长，故可判「有没有白等有界预算」。
     */
    private fun projectExecFileOnBackgroundPumpingEdt(sql: String): Pair<Long, java.io.File?> {
        val holder = java.util.concurrent.atomic.AtomicReference<java.io.File?>()
        val worker = Thread { holder.set(DataSourceSelectorResultsKt.projectExecFile(project, sql)) }
        val start = System.currentTimeMillis()
        worker.start()
        while (worker.isAlive && System.currentTimeMillis() - start < 10_000) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(5)
        }
        worker.join(2_000)
        assertFalse("工作线程不应卡死（有界派发未交还？）", worker.isAlive)
        return System.currentTimeMillis() - start to holder.get()
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
