package cn.song.mybatisplus.visualizer.xml.ibatis;

/**
 * {@code $x$} 直替行：站点名 + 风险标注（面板侧展示「原文直替（注入面）」）。
 */
public record IbatisDollarRow(String name) {

    public IbatisDollarRow {
        java.util.Objects.requireNonNull(name, "name");
    }
}
