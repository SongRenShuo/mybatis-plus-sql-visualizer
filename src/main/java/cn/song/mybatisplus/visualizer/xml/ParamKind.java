// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/xml/ParamKind.kt 移植
package cn.song.mybatisplus.visualizer.xml;

/**
 * 参数类型类别（T-PT-01）：参数面板「按签名」契约的跨层通行证。
 *
 * <p>刻意定义在 xml 层（而非 psi 层）：{@link XmlPreviewModel} 及其单测不依赖 PSI / IDE（架构约束见该类 KDoc），
 * 类型类别以本枚举为载体——psi 层只做「Java 类型 → 类别」映射，xml 层只消费类别，
 * 面板按类别挂占位提示与校验警示。</p>
 *
 * <p>映射不到一律 {@link #UNKNOWN}（行为=升级前：引号兜底、mock 占位 "1"、无占位提示——宁缺勿错）。</p>
 */
public enum ParamKind {
    /** 字符串/字符序列（String / CharSequence）。 */
    TEXT,

    /** 数值（整型浮点族原语与包装类 + {@code java.math} 的 BigDecimal / BigInteger）。 */
    NUMBER,

    /** 时间（{@code java.time.*} / {@code java.util.Date}）。 */
    TEMPORAL,

    /** 集合与数组（List / Collection / Set 族 / 数组）。 */
    COLLECTION,

    /** 对象（DTO/实体/Map）——按属性路径取值，不整体内联。 */
    OBJECT,

    /** 类型未知（无类型信息 / 不可解析 / 映射不到）。 */
    UNKNOWN;

    /**
     * 派生引号视图（T-PT-01）：NUMBER 裸渲染、其余恒引号——与升级前
     * {@code cn.song.mybatisplus.visualizer.sql.ParamQuote.decide} 的布尔通道逐值等价。
     *
     * <p>TEMPORAL 的<b>最终字面量</b>由 T-DateLiteralBackfill 定案：不再只包引号，而是由
     * {@code cn.song.mybatisplus.visualizer.sql.SqlDateLiteral} 产出 ANSI 类型字面量
     * （{@code DATE '…'} / {@code TIMESTAMP '…'}；形态不匹配才回落引号）。{@link #isQuoteRequired()}
     * 仍是 TEMPORAL 的<b>回退</b>口径（{@code ParamHint.quote}），temporal 标记单独走
     * {@code ParamHint.temporal}。</p>
     */
    public boolean isQuoteRequired() {
        return this != NUMBER;
    }
}
