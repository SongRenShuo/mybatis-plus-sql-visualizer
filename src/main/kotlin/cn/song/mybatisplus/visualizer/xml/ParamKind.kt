package cn.song.mybatisplus.visualizer.xml

/**
 * 参数类型类别（T-PT-01）：参数面板「按签名」契约的跨层通行证。
 *
 * 刻意定义在 xml 层（而非 psi 层）：[XmlPreviewModel] 及其单测不依赖 PSI / IDE（架构约束见该类 KDoc），
 * 类型类别以本枚举为载体——psi 层只做「Java 类型 → 类别」映射，xml 层只消费类别，
 * 面板按类别挂占位提示与校验警示。
 *
 * 映射不到一律 [UNKNOWN]（行为=升级前：引号兜底、mock 占位 "1"、无占位提示——宁缺勿错）。
 */
enum class ParamKind {
    /** 字符串/字符序列（String / CharSequence）。 */
    TEXT,

    /** 数值（整型浮点族原语与包装类 + `java.math` 的 BigDecimal / BigInteger）。 */
    NUMBER,

    /** 时间（`java.time.*` / `java.util.Date`）。 */
    TEMPORAL,

    /** 集合与数组（List / Collection / Set 族 / 数组）。 */
    COLLECTION,

    /** 对象（DTO/实体/Map）——按属性路径取值，不整体内联。 */
    OBJECT,

    /** 类型未知（无类型信息 / 不可解析 / 映射不到）。 */
    UNKNOWN;

    /**
     * 派生引号视图（T-PT-01）：NUMBER 裸渲染、其余恒引号——与升级前
     * [cn.song.mybatisplus.visualizer.sql.ParamQuote.decide] 的布尔通道逐值等价。
     *
     * TEMPORAL 的**最终字面量**由 T-DateLiteralBackfill 定案：不再只包引号，而是由
     * [cn.song.mybatisplus.visualizer.sql.SqlDateLiteral] 产出 ANSI 类型字面量
     * （`DATE '…'` / `TIMESTAMP '…'`；形态不匹配才回落引号）。[quoteRequired] 仍是 TEMPORAL 的
     * **回退**口径（`ParamHint.quote`），temporal 标记单独走 `ParamHint.temporal`。
     */
    val quoteRequired: Boolean get() = this != NUMBER
}

/**
 * 参数类型信息（T-PT-01）：类别 + nullable 标记。
 *
 * @param kind        类型类别（映射不到为 [ParamKind.UNKNOWN]）
 * @param nullable    是否可空（PSI 原语类型视为非空，其余保守视为可空）
 * @param elementKind 集合参数的元素类别（O-78 T-ForeachItemRender）：`List<Integer>` → NUMBER——
 *   供 foreach item 占位键（`__frch_<item>_<n>`）派生引号/类别；非集合、数组、裸集合（无泛型实参）
 *   或实参类别映射不到时为 null（宁缺勿错，调用方按 UNKNOWN 兜底）
 */
data class ParamTypeInfo(
    val kind: ParamKind,
    val nullable: Boolean = true,
    val elementKind: ParamKind? = null,
)
