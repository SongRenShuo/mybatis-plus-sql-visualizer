package cn.song.mybatisplus.visualizer.log;

/**
 * 控制台 filter 重放守卫（T-PreviewFlash，纯逻辑，不依赖任何平台类）。
 *
 * <p>背景（平台契约，反编译出处见开发计划 §3.4）：
 * 平台 {@code ConsoleViewImpl.rehighlightHyperlinksAndFoldings()} 会对控制台整篇文档从第 0 行起重跑全部
 * message filter（退出 dumb mode、配色变更、插件动态加载等都会触发）——即插件挂载的 filter 对同一行
 * 会被调用多次。filter 必须幂等：重跑应被识别并丢弃，而不是把历史行当作全新输出重新入库。
 *
 * <p>判定依据的两个平台事实：
 * <ul>
 * <li>{@code applyFilter(line, entireLength)} 的 {@code entireLength} 实为该行在整篇文档中的行尾偏移
 *   （{@code AsyncFilterRunner$HighlighterJob.analyzeLine}：{@code endOffset = lineStart + lineText.length()}），
 *   正常流式打印时单调递增；</li>
 * <li>重放不修改文档，{@code getModificationStamp()} 不变；控制台 clear/截断则 stamp 变。</li>
 * </ul>
 *
 * <p>四态判定（{@link #decide} 返回结果）：
 *
 * <table>
 * <tr><th>输入特征</th><th>结果</th><th>说明</th></tr>
 * <tr><td>endOffset &gt; watermark</td><td>Process</td><td>流式新增，watermark 前移</td></tr>
 * <tr><td>endOffset ≤ watermark 且 stamp 未变</td><td>Replay</td><td>平台重放，丢弃</td></tr>
 * <tr><td>endOffset ≤ watermark 且 stamp 变、行文本 ≠ 行 0 文本</td><td>Reset</td><td>控制台重置，watermark 归位后处理</td></tr>
 * <tr><td>endOffset ≤ watermark 且 stamp 变、行文本 = 行 0 文本</td><td>Replay</td><td>重放叠加新增混合态，判重放丢弃</td></tr>
 * </table>
 *
 * <p>券 G2（2026-11-07）追加重置形态：{@code endOffset ≤ watermark} 且 stamp 变且<b>文档总长度 &lt; watermark</b>
 * → Reset（文档被 clear/截断缩小，watermark 已陈旧；行 0 比对在该形态下必误判——clear 后首个
 * 重印行必然等于行 0，旧判据把它判成 Replay 并把后续新行一起丢到水位之外）。文档长度不可得
 * （提供者缺省）时回退旧行为，桥侧不可得＝守卫降级停用的既有口径不受影响。</p>
 */
public final class ConsoleReplayGuard {

    /** 四态判定结果。 */
    public enum Decision {
        /** 流式新增，正常处理。 */
        Process,
        /** 平台重放，丢弃（调用方应计数并 debug 日志）。 */
        Replay,
        /** 控制台重置（clear/截断），重置水位后按流式处理。 */
        Reset
    }

    /** 已处理的最大行尾偏移（文档级位置游标）。 */
    private int watermark = 0;

    /** 上次观测到的文档 modificationStamp（Long.MIN_VALUE 表示未初始化）。 */
    private long lastDocStamp = Long.MIN_VALUE;

    public int getWatermark() {
        return watermark;
    }

    /**
     * @param endOffset 行在整篇文档中的行尾偏移（{@code applyFilter} 的 entireLength 参数）。
     * @param docStamp  控制台文档当前 modificationStamp（守卫不可得时传 Long.MIN_VALUE → 按未初始化处理）。
     * @param lineText  当前行文本。
     * @param line0TextProvider 控制台首行文本提供者（惰性：仅 {@code endOffset ≤ watermark} 且 stamp 变的
     *   混合态分支才求值一次——T-PerfSweep F1，流式/纯重放热路径零全文档拷贝成本）。可为 null。
     */
    public Decision decide(int endOffset, long docStamp, String lineText,
                           java.util.function.Supplier<String> line0TextProvider) {
        return decide(endOffset, docStamp, lineText, line0TextProvider, null);
    }

    /**
     * @param docLengthProvider 当前文档总长度提供者（惰性：仅 stamp 变分支才求值；null 或不可得 =
     *   按缺省处理，守卫回退旧行为——T-SqlListCatchup 券 G2）。
     */
    public Decision decide(int endOffset, long docStamp, String lineText,
                           java.util.function.Supplier<String> line0TextProvider,
                           java.util.function.Supplier<Integer> docLengthProvider) {
        if (endOffset > watermark) {
            watermark = endOffset;
            lastDocStamp = docStamp;
            return Decision.Process;
        }
        boolean stampChanged = docStamp != Long.MIN_VALUE && docStamp != lastDocStamp;
        if (!stampChanged) {
            // 纯重放：endOffset 回落且文档未变
            return Decision.Replay;
        }
        // stamp 变了：控制台可能被 clear/截断。
        // 券 G2：文档总长度掉到水位之下 = 文档已被缩小（ConsoleViewImpl.doClear 的
        // deleteString(0, textLength) 平台实证：同一文档实例清空，偏移从头重计）——水位对
        // 新文档是**陈旧值**，此形态下首行文本比对必误判（clear 后首个重印行必然「等于行 0」，
        // 旧判据把它连同其后的全部新行判成 Replay 丢到水位之外＝静默丢数据）。故先于文本
        // 比对核销水位，按 Reset 处理。
        Integer provided = docLengthProvider != null ? docLengthProvider.get() : null;
        int docLength = provided != null ? provided : Integer.MIN_VALUE;
        if (docLength != Integer.MIN_VALUE && docLength < watermark) {
            watermark = endOffset;
            lastDocStamp = docStamp;
            return Decision.Reset;
        }
        // 文档未缩水：用首行文本比对区分「重置」与「重放叠加新增的混合态」。
        lastDocStamp = docStamp;
        String line0Text = line0TextProvider != null ? line0TextProvider.get() : null;
        if (line0Text == null || !lineText.equals(line0Text)) {
            watermark = endOffset;
            return Decision.Reset;
        }
        return Decision.Replay;
    }

    /** 控制台文档被外部重置（如面板清空）时应显式复位，避免脏水位移到新内容。 */
    public void reset() {
        watermark = 0;
        lastDocStamp = Long.MIN_VALUE;
    }
}
