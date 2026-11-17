package cn.song.mybatisplus.visualizer.model;

import org.jetbrains.annotations.NotNull;

/**
 * T-PreviewStmtTypeScope：消费档位——决定预览语句类型的那一档。
 */
public enum ConsumptionKind {
    /** {@code remove} / {@code delete} / {@code deleteJoin} → {@code DELETE FROM}。 */
    DELETE,

    /** {@code count} / {@code selectCount} / {@code selectJoinCount} → {@code SELECT COUNT(*)}。 */
    COUNT,

    /** {@code exists}（链终结点 / 单参消费）→ {@code SELECT EXISTS (…)}。 */
    EXISTS,

    /** {@code update} / {@code updateJoin} / {@code updateJoinAndNull} / {@code saveOrUpdate} 双参 → 实体列展开的 UPDATE。 */
    UPDATE_ENTITY,

    /** 分页消费（{@code page} / {@code selectPage} 等）→ LIMIT 子句；不改语句类型，仅在证据清单里留痕。 */
    PAGE,
}
