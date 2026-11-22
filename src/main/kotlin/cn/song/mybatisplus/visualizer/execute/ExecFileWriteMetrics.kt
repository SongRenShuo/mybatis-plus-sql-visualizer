package cn.song.mybatisplus.visualizer.execute

import java.util.concurrent.atomic.AtomicLong

/**
 * T-PerfSweep30 01a/01c：exec 临时文件写链的测试缝计数器。
 *
 * 独立成文件是为了守住 `DataSourceSelector.kt` 的 2000 行预算（GiantFileBudgetTest）——计数缝
 * 只是观测设施，不属于执行链逻辑。对外 API 仍是 `DataSourceSelector.execFileWriteCountForTest()`
 * 等（下方扩展函数），调用点与测试无需感知搬家。
 *
 * ## 两个计数各管什么（口径不同，勿混用）
 *
 * - [writeCounter]「真实内容写次数」：只在**内容确实变更**的写点自增（首建裸写 / document 驱动写 /
 *   面板回填）。内容判等早退不计数。
 * - [projectFileCallCounter]「预热链是否走到 projectExecFile」：cache-only 命中应完全跳过它。
 *   为什么不能用写次数证明 cache-only 的价值——`projectExecFile` 自带的 T-PreviewFast P1 差量
 *   快路径在内容一致时本来就零写（实测：撤掉 cache-only 短路后写次数仍为 0，用例不红）。cache-only
 *   真正省下的是它之后两段 `syncExecVfContentIfNeeded` 收敛（各付一次 invokeLater + 最长 2s 有界等待）。
 */
internal object ExecFileWriteMetrics {

    /** 真实内容写次数（内容判等早退不计数）。 */
    val writeCounter: AtomicLong = AtomicLong(0L)

    /** 预热链进入 `projectExecFile` 的次数（cache-only 命中应为 0）。 */
    val projectFileCallCounter: AtomicLong = AtomicLong(0L)

    /** 记一次真实内容写。 */
    fun countWrite() {
        writeCounter.incrementAndGet()
    }

    /** 记一次 `projectExecFile` 进入。 */
    fun countProjectFileCall() {
        projectFileCallCounter.incrementAndGet()
    }
}

// ---------------------------------------------------------------------------
// 对外测试缝（保持既有调用形态：DataSourceSelector.xxxForTest()）
// ---------------------------------------------------------------------------

/** T-PerfSweep30 01c 测试缝：读真实内容写次数。 */
internal fun DataSourceSelector.execFileWriteCountForTest(): Long = ExecFileWriteMetrics.writeCounter.get()

/** T-PerfSweep30 01c 测试缝：清零（测试隔离用）。 */
internal fun DataSourceSelector.resetExecFileWriteCountForTest() {
    ExecFileWriteMetrics.writeCounter.set(0L)
}

/** T-PerfSweep30 01a 测试缝：读 `projectExecFile` 调用次数。 */
internal fun DataSourceSelector.execProjectFileCallCountForTest(): Long =
    ExecFileWriteMetrics.projectFileCallCounter.get()

/** T-PerfSweep30 01a 测试缝：清零（测试隔离用）。 */
internal fun DataSourceSelector.resetExecProjectFileCallCountForTest() {
    ExecFileWriteMetrics.projectFileCallCounter.set(0L)
}
