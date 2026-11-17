package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

/**
 * 占位符行（{@code #x#} 家族 / parameterMap 声明参数）：参数名 + 类型提示 + 值输入（面板侧持有）。
 */
public record IbatisPlaceholderRow(
        String name,
        @Nullable String jdbcType,
        @Nullable String nullValue) {

    public IbatisPlaceholderRow {
        java.util.Objects.requireNonNull(name, "name");
    }

    public IbatisPlaceholderRow(String name) {
        this(name, null, null);
    }
}
