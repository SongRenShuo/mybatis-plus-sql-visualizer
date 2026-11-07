package cn.song.mybatisplus.visualizer.xml;

import org.jetbrains.annotations.Nullable;

/**
 * 实体探针结果：判定只需要「实体名」与「表名」两个事实。
 *
 * <p>null 探针 = 不是可解析的持久化实体。生产接
 * {@code cn.song.mybatisplus.visualizer.ui.PagesEntityProbeResolver.probe}（PSI 实体解析）。</p>
 *
 * @param entityName 实体名（{@code @Entity(name)} 或简单类名）；提不到为 null
 * @param tableName  实体表名；提不到为 null
 */
public record PagesEntityProbe(@Nullable String entityName, @Nullable String tableName) {
}
