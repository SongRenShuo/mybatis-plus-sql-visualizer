package cn.song.mybatisplus.visualizer.xml;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 语句形态档（T-MbgExamplePreview R5）：已知框架模板的默认勾选修正。
 *
 * <p>目前只有 MBG 生成器一种形态——Example 参数对象里 {@code distinct} 默认 false、
 * {@code orderByClause} 默认 null，集合（oredCriteria / criteria.criteria）在典型调用里各只有 1 项。
 * 无档时不改任何默认值，行为与升级前逐字节一致。
 *
 * @param suppressedConditionIds 不默认启用的条件 id（test 表达式原文）
 * @param forEachCount           集合默认展开次数覆盖（null = 沿用 {@link XmlPreviewModel#DEFAULT_FOREACH_SIZE}）
 */
public record XmlPreviewProfile(Set<String> suppressedConditionIds, @Nullable Integer forEachCount) {

    public XmlPreviewProfile {
        suppressedConditionIds = Set.copyOf(suppressedConditionIds);
    }

    /** 无修正的形态（升级前行为）。 */
    public XmlPreviewProfile() {
        this(Set.of(), null);
    }

    /** MBG 生成器模板的固定 test 文本（{@code distinct} 与 {@code orderByClause != null}）。 */
    public static final XmlPreviewProfile MBG_EXAMPLE =
            new XmlPreviewProfile(Set.of("distinct", "orderByClause != null"), 1);
}
