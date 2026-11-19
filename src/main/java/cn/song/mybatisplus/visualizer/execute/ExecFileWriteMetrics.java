// T-SyncK2JMaster180 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/execute/ExecFileWriteMetrics.kt 移植
package cn.song.mybatisplus.visualizer.execute;

import java.util.concurrent.atomic.AtomicLong;

/**
 * T-PerfSweep30 01a/01c：exec 临时文件写链的测试缝计数器。
 *
 * <p>独立成文件是为了守住 {@code DataSourceSelector} 的 2000 行预算（GiantFileBudgetTest）——计数缝
 * 只是观测设施，不属于执行链逻辑。对外 API 仍是 {@code DataSourceSelector.execFileWriteCountForTest()}
 * 等（下方扩展函数落点），调用点与测试无需感知搬家。</p>
 *
 * <h2>两个计数各管什么（口径不同，勿混用）</h2>
 *
 * <ul>
 *  <li>{@link #writeCounter}「真实内容写次数」：只在<b>内容确实变更</b>的写点自增（首建裸写 / document 驱动写 /
 *      面板回填）。内容判等早退不计数。</li>
 *  <li>{@link #projectFileCallCounter}「预热链是否走到 projectExecFile」：cache-only 命中应完全跳过它。
 *      为什么不能用写次数证明 cache-only 的价值——{@code projectExecFile} 自带的 T-PreviewFast P1 差量
 *      快路径在内容一致时本来就零写（实测：撤掉 cache-only 短路后写次数仍为 0，用例不红）。cache-only
 *      真正省下的是它之后两段 {@code syncExecVfContentIfNeeded} 收敛（各付一次 invokeLater + 最长 2s 有界等待）。</li>
 * </ul>
 */
public final class ExecFileWriteMetrics {

    private ExecFileWriteMetrics() {
    }

    /** 真实内容写次数（内容判等早退不计数）。 */
    public static final AtomicLong writeCounter = new AtomicLong(0L);

    /** 预热链进入 {@code projectExecFile} 的次数（cache-only 命中应为 0）。 */
    public static final AtomicLong projectFileCallCounter = new AtomicLong(0L);

    /** 记一次真实内容写。 */
    public static void countWrite() {
        writeCounter.incrementAndGet();
    }

    /** 记一次 {@code projectExecFile} 进入。 */
    public static void countProjectFileCall() {
        projectFileCallCounter.incrementAndGet();
    }

    // ---------------------------------------------------------------------------
    // 对外测试缝（保持既有调用形态：DataSourceSelector.xxxForTest()）
    // master 顶层扩展函数 fun DataSourceSelector.xxx() → 静态方法首参带 receiver（k2j 惯例）
    // ---------------------------------------------------------------------------

    /** T-PerfSweep30 01c 测试缝：读真实内容写次数。 */
    public static long execFileWriteCountForTest(DataSourceSelector selector) {
        return writeCounter.get();
    }

    /** T-PerfSweep30 01c 测试缝：清零（测试隔离用）。 */
    public static void resetExecFileWriteCountForTest(DataSourceSelector selector) {
        writeCounter.set(0L);
    }

    /** T-PerfSweep30 01a 测试缝：读 {@code projectExecFile} 调用次数。 */
    public static long execProjectFileCallCountForTest(DataSourceSelector selector) {
        return projectFileCallCounter.get();
    }

    /** T-PerfSweep30 01a 测试缝：清零（测试隔离用）。 */
    public static void resetExecProjectFileCallCountForTest(DataSourceSelector selector) {
        projectFileCallCounter.set(0L);
    }
}
