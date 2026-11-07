package cn.song.mybatisplus.visualizer.log

/**
 * 控制台 filter 重放守卫（T-PreviewFlash，纯逻辑，不依赖任何平台类）。
 *
 * 背景（平台契约，反编译出处见开发计划 §3.4）：
 * 平台 `ConsoleViewImpl.rehighlightHyperlinksAndFoldings()` 会对控制台整篇文档从第 0 行起重跑全部
 * message filter（退出 dumb mode、配色变更、插件动态加载等都会触发）——即插件挂载的 filter 对同一行
 * 会被调用多次。filter 必须幂等：重跑应被识别并丢弃，而不是把历史行当作全新输出重新入库。
 *
 * 判定依据的两个平台事实：
 * - `applyFilter(line, entireLength)` 的 `entireLength` 实为该行在整篇文档中的行尾偏移
 *   （`AsyncFilterRunner$HighlighterJob.analyzeLine`：`endOffset = lineStart + lineText.length()`），
 *   正常流式打印时单调递增；
 * - 重放不修改文档，`getModificationStamp()` 不变；控制台 clear/截断则 stamp 变。
 *
 * 四态判定（`decide` 返回密封结果）：
 *
 * | 输入特征 | 结果 | 说明 |
 * |---|---|---|
 * | endOffset > watermark | Process | 流式新增，watermark 前移 |
 * | endOffset ≤ watermark 且 stamp 未变 | Replay | 平台重放，丢弃 |
 * | endOffset ≤ watermark 且 stamp 变、行文本 ≠ 行 0 文本 | Reset | 控制台重置，watermark 归位后处理 |
 * | endOffset ≤ watermark 且 stamp 变、行文本 = 行 0 文本 | Replay | 重放叠加新增混合态，判重放丢弃 |
 *
 * 券 G2（2026-11-07）追加重置形态：`endOffset ≤ watermark` 且 stamp 变且**文档总长度 < watermark**
 * → Reset（文档被 clear/截断缩小，watermark 已陈旧；行 0 比对在该形态下必误判——clear 后首个
 * 重印行必然等于行 0，旧判据把它判成 Replay 并把后续新行一起丢到水位之外）。文档长度不可得
 * （提供者缺省）时回退旧行为，桥侧不可得＝守卫降级停用的既有口径不受影响。
 */
internal class ConsoleReplayGuard {

    sealed interface Decision {
        /** 流式新增，正常处理。 */
        data object Process : Decision

        /** 平台重放，丢弃（调用方应计数并 debug 日志）。 */
        data object Replay : Decision

        /** 控制台重置（clear/截断），重置水位后按流式处理。 */
        data object Reset : Decision
    }

    /** 已处理的最大行尾偏移（文档级位置游标）。 */
    var watermark: Int = 0
        private set

    /** 上次观测到的文档 modificationStamp（Long.MIN_VALUE 表示未初始化）。 */
    private var lastDocStamp: Long = Long.MIN_VALUE

    /**
     * @param endOffset 行在整篇文档中的行尾偏移（`applyFilter` 的 entireLength 参数）。
     * @param docStamp  控制台文档当前 modificationStamp（守卫不可得时传 Long.MIN_VALUE → 按未初始化处理）。
     * @param lineText  当前行文本。
     * @param line0TextProvider 控制台首行文本提供者（惰性：仅 `endOffset ≤ watermark` 且 stamp 变的
     *   混合态分支才求值一次——T-PerfSweep F1，流式/纯重放热路径零全文档拷贝成本）。
     * @param docLengthProvider 当前文档总长度提供者（惰性：仅 stamp 变分支才求值；null 或不可得 =
     *   按缺省处理，守卫回退旧行为——T-SqlListCatchup 券 G2）。
     */
    fun decide(
        endOffset: Int,
        docStamp: Long,
        lineText: String,
        line0TextProvider: (() -> String?)?,
        docLengthProvider: (() -> Int)? = null,
    ): Decision {
        if (endOffset > watermark) {
            watermark = endOffset
            lastDocStamp = docStamp
            return Decision.Process
        }
        val stampChanged = docStamp != Long.MIN_VALUE && docStamp != lastDocStamp
        if (!stampChanged) {
            // 纯重放：endOffset 回落且文档未变
            return Decision.Replay
        }
        // stamp 变了：控制台可能被 clear/截断。
        // 券 G2：文档总长度掉到水位之下 = 文档已被缩小（ConsoleViewImpl.doClear 的
        // `deleteString(0, textLength)` 平台实证：同一文档实例清空，偏移从头重计）——水位对
        // 新文档是**陈旧值**，此形态下首行文本比对必误判（clear 后首个重印行必然「等于行 0」，
        // 旧判据把它连同其后的全部新行判成 Replay 丢到水位之外＝静默丢数据）。故先于文本
        // 比对核销水位，按 Reset 处理。
        val docLength = docLengthProvider?.invoke() ?: Int.MIN_VALUE
        if (docLength != Int.MIN_VALUE && docLength < watermark) {
            watermark = endOffset
            lastDocStamp = docStamp
            return Decision.Reset
        }
        // 文档未缩水：用首行文本比对区分「重置」与「重放叠加新增的混合态」。
        lastDocStamp = docStamp
        val line0Text = line0TextProvider?.invoke()
        return if (line0Text == null || lineText != line0Text) {
            watermark = endOffset
            Decision.Reset
        } else {
            Decision.Replay
        }
    }

    /** 控制台文档被外部重置（如面板清空）时应显式复位，避免脏水位移到新内容。 */
    fun reset() {
        watermark = 0
        lastDocStamp = Long.MIN_VALUE
    }
}