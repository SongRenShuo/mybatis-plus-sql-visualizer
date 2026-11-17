package cn.song.mybatisplus.visualizer.xml.ibatis;

import java.util.List;

/**
 * {@code <sqlMap>} 直接子元素里的 6 种可执行语句标签（{@code sql-map-2.dtd}）。
 *
 * <p>{@code insert}/{@code update}/{@code delete} 的属性集<b>没有</b> {@code resultMap}/{@code resultClass}
 * （DTD 实测）。{@code procedure} 额外少 {@code resultSetType}。三者差异对静态渲染无影响
 * （只影响 resultMap 装配），故此处只保留种类用于 UI 展示与 DTD 校验提示。
 */
public enum IbatisStatementKind {
    SELECT("select"),
    INSERT("insert"),
    UPDATE("update"),
    DELETE("delete"),
    STATEMENT("statement"),
    PROCEDURE("procedure");

    private final String tagName;

    IbatisStatementKind(String tagName) {
        this.tagName = tagName;
    }

    public String getTagName() {
        return tagName;
    }

    /**
     * 全量常量表（顺序 = {@code sql-map-2.dtd} 声明顺序），也是 {@link #byTagName} 的<b>唯一查表源</b>。
     *
     * <p>逐项写成限定形态 {@code IbatisStatementKind.X} 是 {@code convention/EnumReachabilityGuardTest}
     * 的硬要求：该守卫只统计 {@code EnumName.CONST} 限定形态的引用，{@code values()} 流式隐式产出
     * 对它不可见，会被判「死分支 / 死常量」。本枚举的产出是真实存在的（查表即产出），故显式列出——
     * <b>不要退回 {@code values()} 形态</b>，否则门禁红。
     */
    private static final List<IbatisStatementKind> ALL = List.of(
            IbatisStatementKind.SELECT,
            IbatisStatementKind.INSERT,
            IbatisStatementKind.UPDATE,
            IbatisStatementKind.DELETE,
            IbatisStatementKind.STATEMENT,
            IbatisStatementKind.PROCEDURE);

    /** 按标签名查表；不认识的标签名返回 null（降级通道判定）。 */
    public static IbatisStatementKind byTagName(String name) {
        for (IbatisStatementKind kind : ALL) {
            if (kind.tagName.equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
