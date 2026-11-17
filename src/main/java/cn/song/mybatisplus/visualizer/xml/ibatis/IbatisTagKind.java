package cn.song.mybatisplus.visualizer.xml.ibatis;

import java.util.List;

/**
 * 动态标签处理器种类——逐项对应 iBatis {@code SqlTagHandlerFactory} 的 16 个注册项
 * （14 个条件标签 + {@code iterate} + {@code dynamic}）。
 *
 * <p>判定语义见 {@link SqlMapConditionEvaluator}；{@code dynamic} 只做 prepend 消费、
 * {@code iterate} 是唯一有状态的处理器。
 */
public enum IbatisTagKind {
    DYNAMIC("dynamic"),
    ITERATE("iterate"),
    IS_EMPTY("isEmpty"),
    IS_EQUAL("isEqual"),
    IS_GREATER_EQUAL("isGreaterEqual"),
    IS_GREATER_THAN("isGreaterThan"),
    IS_LESS_EQUAL("isLessEqual"),
    IS_LESS_THAN("isLessThan"),
    IS_NOT_EMPTY("isNotEmpty"),
    IS_NOT_EQUAL("isNotEqual"),
    IS_NOT_NULL("isNotNull"),
    IS_NOT_PARAMETER_PRESENT("isNotParameterPresent"),
    IS_NOT_PROPERTY_AVAILABLE("isNotPropertyAvailable"),
    IS_NULL("isNull"),
    IS_PARAMETER_PRESENT("isParameterPresent"),
    IS_PROPERTY_AVAILABLE("isPropertyAvailable");

    private final String tagName;

    IbatisTagKind(String tagName) {
        this.tagName = tagName;
    }

    public String getTagName() {
        return tagName;
    }

    /**
     * 全量常量表（顺序 = 引擎 {@code SqlTagHandlerFactory} 的 16 个注册项顺序），也是
     * {@link #byTagName} 的<b>唯一查表源</b>。逐项写成限定形态 {@code IbatisTagKind.X} 的原因同
     * {@link IbatisStatementKind} 的 ALL 表：守卫 {@code convention/EnumReachabilityGuardTest}
     * 只统计限定形态引用，隐式查表产出对它不可见。<b>不要退回 {@code values()} 形态</b>。
     */
    private static final List<IbatisTagKind> ALL = List.of(
            IbatisTagKind.DYNAMIC,
            IbatisTagKind.ITERATE,
            IbatisTagKind.IS_EMPTY,
            IbatisTagKind.IS_EQUAL,
            IbatisTagKind.IS_GREATER_EQUAL,
            IbatisTagKind.IS_GREATER_THAN,
            IbatisTagKind.IS_LESS_EQUAL,
            IbatisTagKind.IS_LESS_THAN,
            IbatisTagKind.IS_NOT_EMPTY,
            IbatisTagKind.IS_NOT_EQUAL,
            IbatisTagKind.IS_NOT_NULL,
            IbatisTagKind.IS_NOT_PARAMETER_PRESENT,
            IbatisTagKind.IS_NOT_PROPERTY_AVAILABLE,
            IbatisTagKind.IS_NULL,
            IbatisTagKind.IS_PARAMETER_PRESENT,
            IbatisTagKind.IS_PROPERTY_AVAILABLE);

    /** 按标签名查表；未注册标签返回 null（调用方降级为「原文保留 + warning」）。 */
    public static IbatisTagKind byTagName(String name) {
        for (IbatisTagKind kind : ALL) {
            if (kind.tagName.equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
