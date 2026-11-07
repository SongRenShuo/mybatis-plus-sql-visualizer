package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LikePosition;

import java.util.Set;

/**
 * 参数回填引号判定（M23，T-M23-03）：按实体字段类型决定回填值时是否加引号。
 *
 * <p>规则（用户确认，2026-08-14）：<b>除确定的数值类型（int/long 等）外，其他类型默认带引号</b>——</p>
 * <ul>
 *  <li>数值类型（原语与包装类 + {@code java.math} 数值族 BigDecimal/BigInteger）→ false（原样渲染，不加引号）；</li>
 *  <li>其余（String/CharSequence/LocalDateTime/Date/Boolean/char/枚举等）→ true（恒加引号）；</li>
 *  <li>类型未知（null）→ null（面板走 NUMBER_REGEX 数字启发式兜底）。</li>
 * </ul>
 *
 * <p>单一来源：{@code SqlGenerator.quoteHint}（Wrapper 模型路径）与 {@code BatchOperationConverter}
 * （批量模板路径，T-M23-02）共用；XML/注解参数链的类型类别映射（{@code XmlParamTypeResolver.kindOfTypeText}）
 * 亦复用本表判 NUMBER，杜绝两份数值名单漂移。</p>
 */
public final class ParamQuote {

    private ParamQuote() {}

    /**
     * 数值类字段类型（原语/简单类名，确定原样渲染；兼容全限定名与简单名两种来源）。
     *
     * <p>T-PC2-01：补 {@code BigInteger}——与 {@code BigDecimal} 同属 {@code java.math} 数值族，此前漏收致 XML 参数链
     * 误落 UNKNOWN（引号兜底 + mock 占位 "1" + 无校验），属漏收修复而非语义取舍。</p>
     */
    private static final Set<String> NUMERIC_TYPES = Set.of(
        "int", "long", "short", "byte", "float", "double",
        "Integer", "Long", "Short", "Byte", "Float", "Double", "BigDecimal", "BigInteger"
    );

    /**
     * 判定引号。
     *
     * @param fieldType 实体字段类型（全限定名 {@code java.lang.Long} / {@code java.time.LocalDateTime} 或简单名 {@code Long}；
     *                  PSI 解析环境差异，两种形态都归一为简单名匹配）
     * @return true=加引号；false=不加引号；null=类型未知（面板启发式兜底）
     */
    public static Boolean decide(String fieldType) {
        if (fieldType == null) return null;
        int dot = fieldType.lastIndexOf('.');
        String simple = dot >= 0 ? fieldType.substring(dot + 1) : fieldType;
        return NUMERIC_TYPES.contains(simple) ? Boolean.FALSE : Boolean.TRUE;
    }

    /**
     * 字段类型是否日期族（T-DateLiteralBackfill）：与 {@link SqlDateLiteral#isTemporalType} 同一张类型表，
     * 供各模型链构造 {@code ParamHint.temporal}。
     */
    public static boolean isTemporal(String fieldType) {
        return SqlDateLiteral.isTemporalType(fieldType);
    }

    /**
     * 构造回填提示（引号 + 日期族标记一次算出）：各模型链的 {@code ParamHint(quote = ParamQuote.decide(...))}
     * 统一改走本工厂，避免「引号带了、temporal 漏了」的口径分叉。
     */
    public static ParamHint hint(String fieldType, LikePosition likePosition, boolean inList) {
        return new ParamHint(decide(fieldType), likePosition, inList, isTemporal(fieldType));
    }

    /** Kotlin 默认参数（inList=false）等价重载。 */
    public static ParamHint hint(String fieldType, LikePosition likePosition) {
        return hint(fieldType, likePosition, false);
    }

    /** Kotlin 默认参数（likePosition=null, inList=false）等价重载。 */
    public static ParamHint hint(String fieldType) {
        return hint(fieldType, null, false);
    }
}
