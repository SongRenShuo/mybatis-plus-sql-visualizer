package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-PerfSweep30 01a/01c：exec 临时文件「单次预览打开写次数」不变式钉。
 *
 * 账本（性能分析 §4.1 账本①）：预热链把预览原文写进 exec 文件后，同一预览打开内的
 * 渲染回填与方言重渲染预热还会重复改写同一文件。收口形态＝预热链 **cache-only**：
 * VFS 已有 exec 条目且内容与本次 SQL 一致 → 零写盘（只做 VFS refresh＋方言映射＋findFile）；
 * 不一致才走既有写路径。
 *
 * 计数缝：[DataSourceSelector.execFileWriteCounter]（01c）——只在真实内容变更点计数
 * （首建裸写 / document 驱动写 / 面板回填），内容判等早退不计数。
 *
 * 两条既有不变量（本组测试不覆盖，由 ExecDocSyncTest 回归钉住，此处依赖其语义）：
 *  ① 首建（VFS 无条目）裸写仍在 execFileWriteLock 锁内，与存在性检查原子；
 *  ② VFS 已有条目绝不裸写（T-ExecDocSync3，防 File Cache Conflict）。
 *
 * 线程形态照生产：预热链只允许 BGT 调用（EDT 入口有守卫拒绝），用例以工作线程裸调 +
 * 测试线程持续抽干 EDT 队列（ExecDocSyncTest.projectExecFileOnBackgroundPumpingEdt 同款）——
 * 链内 DialectPreMapping 两段式把 setMapping 派 EDT 并有界等待，必须边等边泵。
 */
class ExecFileWriteCountTest : BasePlatformTestCase() {

    private lateinit var execFile: File

    override fun setUp() {
        super.setUp()
        val base = project.basePath ?: error("project.basePath null")
        execFile = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec/mpsql-exec.sql")
        // 测试隔离（ExecDocSyncTest 同款口径）：VFS 内删除残留 vf（document 缓存按 vf 对象，
        // 裸 IO 删除会让残留 document 经同路径泄入下一用例）+ 清缓存槽 + 清写计数与写入快照。
        val stale = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(execFile)
        }
        if (stale != null) {
            WriteAction.compute<Unit, Throwable> { stale.delete(this) }
        }
        DataSourceSelector.clearCachedTempPsiFileForTest()
        DataSourceSelector.lastWrittenExecContent.clear()
        DataSourceSelector.resetExecFileWriteCountForTest()
        DataSourceSelector.resetExecProjectFileCallCountForTest()
    }

    override fun tearDown() {
        DataSourceSelector.clearCachedTempPsiFileForTest()
        DataSourceSelector.lastWrittenExecContent.clear()
        super.tearDown()
    }

    // ======================== 01a：cache-only 零写 ========================

    /**
     * VFS 已有 exec 条目且内容与本次 SQL 一致（**经插件自身写入路径**证明：document 已加载
     * 或带写入快照）→ 预热链必须零写盘，只做 refresh＋方言映射＋findFile＋填缓存。
     *
     * 判据契约（与写路径同函数 execFileContentMatches）：无 document 且无写入快照时**不读盘**
     * （EDT 禁慢操作，T-DevAcceptFix2 ④）→ 保守走写路径。故本夹具先经生产写入路径落一次
     * （建 document 或写快照），再以 cache-only 重放。
     *
     * 反向验证：摘掉 prewarmExecTempFileInner 的 cache-only 快路径 → 本例红
     * （旧链判等不成立 → 派 document 写 → setBinaryContent 把相同内容再写一遍，计数 +1）。
     */
    fun testCacheOnlyZeroWriteWhenDiskContentMatches() {
        writeIo("SELECT_CACHE_ONLY_A")
        val vf = refreshVf()!!
        // 经生产 document 写漏斗落一次（内容一致），制造「document 已加载 + 写入快照」形态
        DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT_CACHE_ONLY_A")
        DataSourceSelector.resetExecFileWriteCountForTest()

        prewarmOnBackgroundThread("SELECT_CACHE_ONLY_A", cacheOnly = true)

        assertEquals(
            "01a cache-only：VFS 条目内容一致 → 零写（实测 ${DataSourceSelector.execFileWriteCountForTest()} 次）",
            0L,
            DataSourceSelector.execFileWriteCountForTest(),
        )
        assertEquals("cache-only 路径不得改写磁盘内容", "SELECT_CACHE_ONLY_A", diskText())
    }

    /**
     * **01a 的核心判据**：cache-only 命中时必须完全跳过 `projectExecFile`。
     *
     * 为什么不用「写次数」判这个价值：`projectExecFile` 自带的 T-PreviewFast P1 差量快路径在内容
     * 一致时本来就零写（实测：撤掉 cache-only 短路后写次数仍为 0，用例不红）。cache-only 真正
     * 省下的是 projectExecFile 之后的两段 `syncExecVfContentIfNeeded` 收敛——每段都会
     * `dispatchDocWriteOnEdt`（invokeLater + 最长 2s 有界等待），内容一致也照付一次 EDT 往返。
     *
     * 反向验证：撤掉 cache-only 短路（cacheOnlyHit 恒 false）→ 本用例红（计数 1）。
     */
    fun testCacheOnlySkipsProjectExecFile() {
        writeIo("SELECT_SKIP_A")
        val vf = refreshVf()!!
        // 经生产写漏斗制造「document 已加载 + 写入快照」证据，让 cache-only 判等成立
        DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT_SKIP_A")
        // 夹具前提自证：判等函数本身必须认可「内容已一致」，否则后面的 0 次断言是在测别的
        assertTrue(
            "夹具前提：写入后 execFileContentMatches 应判等成立（否则 cache-only 必然回落写路径）",
            DataSourceSelector.execFileContentMatches(project, refreshVf()!!, "SELECT_SKIP_A"),
        )
        DataSourceSelector.resetExecFileWriteCountForTest()
        DataSourceSelector.resetExecProjectFileCallCountForTest()

        prewarmOnBackgroundThread("SELECT_SKIP_A", cacheOnly = true)

        assertEquals(
            "cache-only 命中必须跳过 projectExecFile（省两次 EDT 派发探测）；实测调用 " +
                "${DataSourceSelector.execProjectFileCallCountForTest()} 次",
            0L,
            DataSourceSelector.execProjectFileCallCountForTest(),
        )
    }

    /**
     * 无 document 且无写入快照（外部写入/新进程形态）→ cache-only **不**判等（保守），
     * 走既有写路径一次。钉住判据边界：cache-only 不是「无脑跳过写」，判据与写路径同函数。
     */
    fun testCacheOnlyFallsBackWhenNoEvidence() {
        writeIo("SELECT_CACHE_ONLY_B") // 外部写入：无 document、无写入快照
        refreshVf()
        DataSourceSelector.resetExecFileWriteCountForTest()

        prewarmOnBackgroundThread("SELECT_CACHE_ONLY_B", cacheOnly = true)

        assertEquals(
            "无 document/快照证据时 cache-only 保守走写路径（恰好 1 次）",
            1L,
            DataSourceSelector.execFileWriteCountForTest(),
        )
    }

    /**
     * 无方言重渲染路径：同一次预览打开内 exec 文件总写次数 ≤1。
     * 真实时序＝首开预热裸写 1 次（VFS 无条目，锁内，不变量①）→ 渲染回填内容判等早退
     * （showSql nativeDoc 分支 `nativeDoc.text != text` 为假，不调 setText）→ 无重渲染预热。
     * 夹具以生产 document 写漏斗模拟回填（同文本 → no-op）。
     *
     * 反向验证：摘掉预热链 cache-only / 回填判等 → 计数 2 → 红。
     */
    fun testNoRerenderPathAtMostOneWrite() {
        DataSourceSelector.resetExecFileWriteCountForTest()
        prewarmOnBackgroundThread("SELECT_NR_X", cacheOnly = true)
        assertEquals(
            "夹具前提：首建裸写恰好 1 次（锁内，不变量①）",
            1L,
            DataSourceSelector.execFileWriteCountForTest(),
        )
        val vf = refreshVf()!!
        // 渲染回填：常见形态（无 fill/count/显示模式变化）渲染文本 == 预览原文 → 内容判等早退。
        // 先加载 document（挂载后 showSql nativeDoc 分支形态：nativeDoc.text != text 判等）。
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT_NR_X")

        assertTrue(
            "无方言重渲染路径：exec 文件写次数 ≤1（实测 ${DataSourceSelector.execFileWriteCountForTest()} 次）",
            DataSourceSelector.execFileWriteCountForTest() <= 1L,
        )
        assertEquals("SELECT_NR_X", diskText())
    }

    // ======================== 01c：方言重渲染路径 ≤2 ========================

    /**
     * 方言重渲染路径：首建写 1 次 + 渲染回填写 1 次（经生产 document 写漏斗模拟 showSql
     * nativeDoc 分支语义）→ 后续方言重渲染预热（同渲染文本）必须 cache-only 零写，总次数 ≤2。
     *
     * 反向验证（若回归）：重渲染预热再走写路径 → 计数 3 → 红。
     */
    fun testDialectRerenderPathAtMostTwoWrites() {
        DataSourceSelector.resetExecFileWriteCountForTest()
        prewarmOnBackgroundThread("SELECT_DR_A", cacheOnly = true)
        val vf = refreshVf()!!
        // 模拟渲染回填（生产经 showSql nativeDoc 分支 setText+saveDocument，语义等价：doc 内容变为渲染文本）
        DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT_DR_A_RENDERED")
        val afterBackfill = DataSourceSelector.execFileWriteCountForTest()

        prewarmOnBackgroundThread("SELECT_DR_A_RENDERED", "MySQL", cacheOnly = true)

        assertTrue(
            "方言重渲染路径：预热链写次数 ≤2（回填后 $afterBackfill，重渲染预热后 ${DataSourceSelector.execFileWriteCountForTest()}）",
            DataSourceSelector.execFileWriteCountForTest() <= 2L,
        )
        assertEquals("重渲染预热不得把渲染文本改回预览原文", "SELECT_DR_A_RENDERED", diskText())
    }

    // ======================== 01c：内容判等早退不计数 ========================

    /** document 写漏斗的内容判等早退（T-PreviewFast P5）不计入写次数。 */
    fun testIdenticalDocWriteDoesNotCount() {
        writeIo("SELECT_SAME")
        val vf = refreshVf()!!
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        DataSourceSelector.resetExecFileWriteCountForTest()
        DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT_SAME")
        assertEquals(
            "内容判等早退（no-op）不得计数",
            0L,
            DataSourceSelector.execFileWriteCountForTest(),
        )
    }

    /** 面板渲染回填（showSql nativeDoc 分支 setText+saveDocument）经写漏斗计入写次数。 */
    fun testBackfillWriteCounts() {
        writeIo("SELECT_BF_A")
        val vf = refreshVf()!!
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        DataSourceSelector.resetExecFileWriteCountForTest()
        DataSourceSelector.writeVfTextViaDocument(project, vf, "SELECT_BF_RENDERED")
        assertEquals(
            "渲染回填是真实内容变更 → 计 1 次",
            1L,
            DataSourceSelector.execFileWriteCountForTest(),
        )
    }

    // ======================== 辅助 ========================

    /**
     * 工作线程裸调预热入口（生产 BGT 同形态），测试线程持续抽干 EDT 队列直至链静止。
     * 预算 60s：light fixture 下预热为毫秒级，但每 JVM 首个预热要付插件类加载/服务首建冷启动代价
     * （SqlPreviewPanelPrewarmConsumeTest 实测 6–10s）。
     */
    private fun prewarmOnBackgroundThread(sql: String, dialect: String? = null, cacheOnly: Boolean = false) {
        val log = Logger.getInstance(DataSourceSelector::class.java)
        val worker = Thread {
            DataSourceSelector.prewarmExecTempFileOnBackgroundThread(project, sql, dialect, log, cacheOnly)
        }
        worker.start()
        val start = System.currentTimeMillis()
        while (worker.isAlive && System.currentTimeMillis() - start < 60_000) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(5)
        }
        worker.join(5_000)
        assertFalse("预热线程不应卡死（cache-only 快路径内是否引入了无界等待？）", worker.isAlive)
        // 预热链的 EDT 两段式（方言映射派 EDT 写）可能有尾巴，抽干一拍再断言
        UIUtil.dispatchAllInvocationEvents()
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
