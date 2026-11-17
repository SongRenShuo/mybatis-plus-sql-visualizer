package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

/**
 * iterate 行：集合属性名 + 值列表输入（逗号/换行分隔，面板侧持有）。
 */
public record IbatisIterateRow(
        String id,
        String property,
        @Nullable String conjunction) {

    public IbatisIterateRow {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(property, "property");
    }

    public IbatisIterateRow(String id, String property) {
        this(id, property, null);
    }
}
