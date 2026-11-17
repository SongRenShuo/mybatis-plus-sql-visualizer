package cn.song.mybatisplus.visualizer.xml.ibatis;

import cn.song.mybatisplus.visualizer.sql.ParamHint;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * T-IbatisFix3 缺陷三：{@code #x:jdbcType#} 的 <b>jdbcType → 参数类别 → 回填引号提示</b> 映射（纯函数）。
 *
 * <h2>缺陷现场</h2>
 *
 * <p>{@code #email:VARCHAR:0#} 填 {@code 11212} 预览出 {@code WHERE EMAIL = 11212}——<b>没有引号</b>。
 * 原因是服务层（{@code ui/IbatisXmlPreviewService.toPreviewResult}）只把 {@code ?} 位点数量/参数名交给
 * 面板，<b>从未构造 {@code PreviewResult.paramHints}</b>：面板 {@code backfillParamsBySource} 拿不到提示时
 * 按 NUMBER_REGEX 启发式判定，{@code 11212} 是纯数字 → 裸渲染。jdbcType 明明写着 VARCHAR。
 *
 * <h2>口径（沿用既有 {@link ParamKind} 六类契约，不新造类型系统）</h2>
 *
 * <ul>
 * <li>{@code VARCHAR} / {@code CHAR} / {@code TEXT} / {@code CLOB} 族 → {@link ParamKind#TEXT}（恒引号）；</li>
 * <li>{@code INTEGER} / {@code NUMERIC} / {@code DECIMAL} / 浮点 / {@code BIT} → {@link ParamKind#NUMBER}
 *     （裸渲染）；</li>
 * <li>{@code DATE} / {@code TIME} / {@code TIMESTAMP} / {@code DATETIME} → {@link ParamKind#TEMPORAL}
 *     （{@code ParamHint.quote=true} 为回退口径，temporal=true 让面板先试 ANSI 类型字面量
 *     {@code DATE '…'}，形态不匹配再回落引号——与 MyBatis 链 T-DateLiteralBackfill 同口径）；</li>
 * <li><b>映射不到 / jdbcType 缺省 → 不产出提示</b>（{@link ParamHint} 默认 quote=null）：面板维持既有
 *     启发式，{@code #x#} 无类型参数的行为与修复前逐字节一致（宁缺勿错）。</li>
 * </ul>
 *
 * <p>大小写不敏感（iBatis DTD 里 jdbcType 由用户手写，{@code varchar} / {@code VARCHAR} 都真实存在）。
 */
public final class IbatisParamTypeHints {

    private IbatisParamTypeHints() {
    }

    /** 字符串族 jdbcType（JDBC 标准名 + 各库常见的 {@code TEXT} 方言名）。 */
    private static final Set<String> TEXT_TYPES = Set.of(
            "CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR",
            "CLOB", "NCLOB", "TEXT", "LONGTEXT", "MEDIUMTEXT", "TINYTEXT", "STRING", "UUID");

    /** 数值族 jdbcType（含布尔位与各库的 {@code NUMBER} / {@code SERIAL} 形态）。 */
    private static final Set<String> NUMBER_TYPES = Set.of(
            "TINYINT", "SMALLINT", "INTEGER", "INT", "BIGINT", "MEDIUMINT",
            "FLOAT", "REAL", "DOUBLE", "NUMERIC", "DECIMAL", "NUMBER",
            "BIT", "BOOLEAN", "SERIAL", "BIGSERIAL");

    /** 日期时间族 jdbcType（{@code DATETIME} 是 MySQL 等方言名）。 */
    private static final Set<String> TEMPORAL_TYPES = Set.of(
            "DATE", "TIME", "TIMESTAMP", "DATETIME", "TIMESTAMPTZ", "TIMETZ");

    /** jdbcType → 类型类别；映射不到（含 null / 空串）返回 null = 不产出提示。 */
    @Nullable
    public static ParamKind kindOf(@Nullable String jdbcType) {
        if (jdbcType == null) {
            return null;
        }
        String type = jdbcType.trim().toUpperCase();
        if (type.isEmpty()) {
            return null;
        }
        if (TEXT_TYPES.contains(type)) {
            return ParamKind.TEXT;
        }
        if (NUMBER_TYPES.contains(type)) {
            return ParamKind.NUMBER;
        }
        if (TEMPORAL_TYPES.contains(type)) {
            return ParamKind.TEMPORAL;
        }
        return null;
    }

    /**
     * jdbcType → 回填提示；映射不到返回 null（= 面板维持启发式，与修复前一致）。
     *
     * <p>{@code quote} 取 {@link ParamKind#isQuoteRequired()}（NUMBER 之外恒引号），
     * temporal 单独标记时间族以便面板先试 ANSI 字面量。
     */
    @Nullable
    public static ParamHint hintOf(@Nullable String jdbcType) {
        ParamKind kind = kindOf(jdbcType);
        if (kind == null) {
            return null;
        }
        return new ParamHint(kind.isQuoteRequired(), null, false, kind == ParamKind.TEMPORAL);
    }

    /**
     * 与 {@code paramNames} 同序的提示清单（jdbcTypes 短/长为 null 时按「无提示」补位）。
     *
     * <p>供 {@code IbatisXmlPreviewService.toPreviewResult} 一次装配；quote=null 的元素位面板按
     * 启发式处理，所以清单长度必须与 SQL 里 {@code ?} 的数量一致（调用方已保证）。
     */
    public static List<ParamHint> hintsFor(List<@Nullable String> jdbcTypes) {
        List<ParamHint> hints = new ArrayList<>(jdbcTypes.size());
        for (String jdbcType : jdbcTypes) {
            ParamHint hint = hintOf(jdbcType);
            hints.add(hint != null ? hint : new ParamHint());
        }
        return List.copyOf(hints);
    }
}
