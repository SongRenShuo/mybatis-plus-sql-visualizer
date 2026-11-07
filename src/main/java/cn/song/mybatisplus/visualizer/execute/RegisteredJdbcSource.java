package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.Nullable;

/**
 * 一个在册数据源在提示层的轻量投影。
 *
 * <p>公开（非 internal）：{@code DataSourceSelector.prepareConsole} / {@code selectAndExecute} 与
 * {@code SqlExecutor} 的对应入口是 public API，参数类型不能暴露 internal 类型（Kotlin
 * {@code EXPOSED_PARAMETER_TYPE}）。本类只承载展示所需的名字/URL/句柄，无行为。</p>
 */
public record RegisteredJdbcSource(
        /** 数据源显示名（与平台 Data Sources 列表一致）。 */
        String name,
        /** JDBC URL；reflection 取不到时 null（推断阶段据此跳过，不猜）。 */
        @Nullable String url,
        /** 平台 LocalDataSource 句柄，供「打开数据源属性」定位；测试/取不到时 null。 */
        @Nullable Object handle
) {
}
