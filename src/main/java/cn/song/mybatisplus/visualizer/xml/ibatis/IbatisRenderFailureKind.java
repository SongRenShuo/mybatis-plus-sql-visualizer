package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

/**
 * 渲染降级的失败原因分类（T-Ibatis2Gap 券 D）。
 *
 * <p><b>没有 UNKNOWN_TAG</b>：未知标签在解析期就被 {@link SqlMapScriptBuilder} 降级成文本节点收下
 * （「原文保留 + 告警」），根本到不了渲染抛错点；给它留档位只会造出一个永远走不到的分支
 * （{@code convention.EnumReachabilityGuardTest} 的零产出守卫会红）。
 */
public enum IbatisRenderFailureKind {
    /** {@code <iterate>} 的 property 取值不是集合/数组/迭代器（引擎 {@code SqlMapException}；无参基线下这是「待填值」态，不是错误）。 */
    ITERATE_NOT_COLLECTION,

    /** 引擎同样硬失败的渲染期错误（如 iterate 迭代上下文缺失等内部不变量被破坏）。 */
    RENDER_ERROR,

    /** 其余意外异常（渲染器外部的解析/配置问题）。 */
    PARSE_ERROR,
}
