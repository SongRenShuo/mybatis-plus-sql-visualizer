package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LikePosition;

import java.util.Objects;

/**
 * 单个 {@code ?} 的回填提示（M13）：与 {@code GeneratedSql.unresolvedParams} 并行，
 * 在生成时根据列类型与 LIKE 位置计算，供面板按用户输入值回填渲染（引号 / 百分号）。
 *
 * @param quote        该值是否必须加引号：true=String 列恒引号；false=数值等列恒原样；
 *                     null=列类型未知（面板走现有 NUMBER_REGEX 启发式）
 * @param likePosition 非 null 表示该 {@code ?} 属于 LIKE 模式（恒引号，并按位置拼 {@code %}）
 * @param inList       M19.6（2026-08-13 用户实测）：该 {@code ?} 属于 IN 列表（{@code col IN (?, ?)}）——
 *                     面板回填时按逗号分割多值，每个值独立渲染（如输入 {@code 1,2} → {@code ('1','2')}）
 * @param temporal     T-DateLiteralBackfill：该值已知是日期/时间族——渲染时先试 ANSI 类型字面量
 *                     （{@code DATE '…'} / {@code TIMESTAMP '…'}，见 {@link SqlDateLiteral}），形态不匹配再走 {@code quote}。
 *                     默认 false = 既有行为（引号/启发式），保证既有构造与 {@code equals} 不破。
 */
public final class ParamHint {

    private final Boolean quote;
    private final LikePosition likePosition;
    private final boolean inList;
    private final boolean temporal;

    public ParamHint(Boolean quote, LikePosition likePosition, boolean inList, boolean temporal) {
        this.quote = quote;
        this.likePosition = likePosition;
        this.inList = inList;
        this.temporal = temporal;
    }

    /** Kotlin 默认参数（temporal=false）等价重载。 */
    public ParamHint(Boolean quote, LikePosition likePosition, boolean inList) {
        this(quote, likePosition, inList, false);
    }

    public ParamHint(Boolean quote, LikePosition likePosition) {
        this(quote, likePosition, false);
    }

    public ParamHint(Boolean quote) {
        this(quote, null, false);
    }

    public ParamHint() {
        this(null, null, false);
    }

    public Boolean getQuote() {
        return quote;
    }

    public LikePosition getLikePosition() {
        return likePosition;
    }

    public boolean isInList() {
        return inList;
    }

    /** 兼容 Kotlin data class 装箱 Boolean 属性的 getter 命名（getInList）。 */
    public boolean getInList() {
        return inList;
    }

    public boolean isTemporal() {
        return temporal;
    }

    /** 兼容 Kotlin data class 装箱 Boolean 属性的 getter 命名（getTemporal）。 */
    public boolean getTemporal() {
        return temporal;
    }

    public ParamHint copy(Boolean quote, LikePosition likePosition, boolean inList, boolean temporal) {
        return new ParamHint(quote, likePosition, inList, temporal);
    }

    /** Kotlin copy 默认参数（temporal）等价重载。 */
    public ParamHint copy(Boolean quote, LikePosition likePosition, boolean inList) {
        return new ParamHint(quote, likePosition, inList, temporal);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParamHint)) return false;
        ParamHint other = (ParamHint) o;
        return inList == other.inList
            && temporal == other.temporal
            && Objects.equals(quote, other.quote)
            && likePosition == other.likePosition;
    }

    @Override
    public int hashCode() {
        return Objects.hash(quote, likePosition, inList, temporal);
    }

    @Override
    public String toString() {
        return "ParamHint(quote=" + quote + ", likePosition=" + likePosition + ", inList=" + inList
                + ", temporal=" + temporal + ")";
    }
}
