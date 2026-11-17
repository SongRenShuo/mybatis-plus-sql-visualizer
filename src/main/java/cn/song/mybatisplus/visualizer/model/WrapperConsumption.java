package cn.song.mybatisplus.visualizer.model;

import org.jetbrains.annotations.NotNull;

/**
 * T-PreviewStmtTypeScope：一次被识别到的「消费调用」——wrapper 作为实参传给某个方法，
 * 方法名落在消费集合内（见 {@link ConsumptionKind}），语句类型据此从默认 SELECT 改判。
 *
 * <p>存在的意义是把「为什么是 UPDATE/DELETE」从隐式判型变成可展示的事实：用户点图标时看的是某一条链，
 * 而判型依据可能来自同一个 wrapper 变量在<b>同方法另一条语句</b>上的消费。面板把「方法名 + 行号」摆出来，
 * 用户一眼就能对上（T-PreviewStmtTypeScope M1）。</p>
 *
 * @param kind            判型档位（DELETE / COUNT / EXISTS / UPDATE_ENTITY / PAGE）
 * @param methodName      消费方法名（{@code service.remove(w)} 的 remove、{@code mapper.update(entity, w)} 的 update …）
 * @param referenceOffset wrapper 作为实参的那处引用在文件中的偏移（排序、「锚点之后第一个」判定用）
 * @param statementRange  该消费调用所在<b>语句</b>的偏移区间；{-1, 0}（Kotlin IntRange.EMPTY 等价）= 不可得
 * @param line            1-based 行号；&le;= 0 = 不可得（文档不可用）
 * @param fileName        消费调用所在文件名（wrapper 为字段时可跨文件）
 * @param detail          档位相关的补充事实：实体更新档位下是<b>实体实参的源码文本</b>（{@code update(patch, w)} 的 patch），
 *                        供 SET 占位符逐列生成参数来源（{@code patch.order_no}）——否则整列 SET 共用同一个来源，
 *                        面板只建一行、填一个值就把所有列填成同一个数。
 */
public record WrapperConsumption(
        @NotNull ConsumptionKind kind,
        @NotNull String methodName,
        int referenceOffset,
        @NotNull IntRangeLike statementRange,
        int line,
        @NotNull String fileName,
        @NotNull String detail) {

    /** Kotlin {@code IntRange} 的 Java 等价形态（含 EMPTY 语义：first > last）。 */
    public record IntRangeLike(int first, int last) {

        /** Kotlin IntRange.EMPTY 等价（first=1, last=0）。 */
        public static final IntRangeLike EMPTY = new IntRangeLike(1, 0);

        public boolean isEmpty() {
            return first > last;
        }

        public boolean contains(int value) {
            return value >= first && value <= last;
        }
    }

    public WrapperConsumption {
        if (statementRange == null) {
            statementRange = IntRangeLike.EMPTY;
        }
    }

    /** 必填三项形态（其余取缺省）。 */
    public WrapperConsumption(@NotNull ConsumptionKind kind,
                              @NotNull String methodName,
                              int referenceOffset) {
        this(kind, methodName, referenceOffset, IntRangeLike.EMPTY, 0, "", "");
    }
}
