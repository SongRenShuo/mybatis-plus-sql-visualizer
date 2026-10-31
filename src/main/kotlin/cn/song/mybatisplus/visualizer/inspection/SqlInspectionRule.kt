package cn.song.mybatisplus.visualizer.inspection

/**
 * 编辑器内 SQL 检查（NEW-26）的五条首批规则。
 *
 * **为什么按规则拆成独立 inspection**：平台给每个 `<localInspection>` 的 `shortName` 一棵独立的
 * 开关（Settings | Editor | Inspections），用户可逐条关掉。五条规则共用一个纯逻辑内核
 * （[SqlInspectionText]），但各自注册、各自可在设置树里关闭——这是「每条独立可开关」的落点，
 * 比自建设置页少一整套持久化与 UI。
 *
 * 覆盖面（立项册 §二 经核实后的范围）：**mapper XML** 的 `<select|insert|update|delete>` 与
 * `<sql>` 片段，以及 **Java 注解 SQL**（`@Select`/`@Insert`/`@Update`/`@Delete`）。预览面板与
 * 运行日志里的临时 SQL 没有源文件 PSI，不在覆盖面上。
 */
enum class SqlInspectionRule(
    /** 规则短 id（测试与报告引用；不影响平台）。 */
    val id: String,
    /** 平台检查短名：与 plugin.xml `shortName` 同值，也是 `@SuppressWarnings` / XML suppress id。 */
    val shortName: String,
    /** 检查设置树里的显示名（bundle 键）。 */
    val displayNameKey: String,
    /** 是否默认开启（P-4 语料误报为 0 才允许 true；见立项册 §二 待验证前提）。 */
    val enabledByDefault: Boolean,
) {
    /** `${}` 直接替换注入风险。 */
    DOLLAR_INJECTION(
        id = "dollarInjection",
        shortName = "SqlvDollarInjection",
        displayNameKey = "inspection.sqlv.dollar.displayName",
        enabledByDefault = true,
    ),

    /** `#{}` 引用的参数在 mapper 接口方法签名里不存在。 */
    MISSING_PARAMETER(
        id = "missingParameter",
        shortName = "SqlvMissingParameter",
        displayNameKey = "inspection.sqlv.param.displayName",
        enabledByDefault = true,
    ),

    /** `<resultMap>` 的 `property` 在 `type` 实体类里不存在。 */
    RESULT_MAP_PROPERTY(
        id = "resultMapProperty",
        shortName = "SqlvResultMapProperty",
        displayNameKey = "inspection.sqlv.resultMap.displayName",
        enabledByDefault = true,
    ),

    /** 标识符引号（反引号 / 方括号）与当前方言能力矩阵不兼容。 */
    DIALECT_QUOTE(
        id = "dialectQuote",
        shortName = "SqlvDialectQuote",
        displayNameKey = "inspection.sqlv.dialect.displayName",
        enabledByDefault = true,
    ),

    /** OGNL `test` 表达式恒真 / 恒假（动态分支永不生效或永真放行）。 */
    OGNL_TAUTOLOGY(
        id = "ognlTautology",
        shortName = "SqlvOgnlTautology",
        displayNameKey = "inspection.sqlv.ognl.displayName",
        enabledByDefault = true,
    ),
}

/** 一条检查结论（纯数据，不含任何平台类型——偏移由调用方映射回 PSI）。 */
data class SqlInspectionFinding(
    val rule: SqlInspectionRule,
    /** 文案键（中英双语，见 messages/MpSqlVisualizerBundle*.properties）。 */
    val messageKey: String,
    /** 文案实参（MessageFormat 占位符顺序）。 */
    val messageArgs: List<String>,
    /**
     * 命中片段在**被分析文本**中的起始偏移；-1 = 无法精确定位（调用方退化为整元素锚定）。
     *
     * 注意：只有「直接对源文本扫描」的规则（`${}` / `#{}` / 方言引号）才有精确偏移；
     * 语义类规则（resultMap 属性、OGNL 常量条件）由 PSI 层直接锚在具体属性值上。
     */
    val start: Int = -1,
    /** 命中片段长度（[start] >= 0 时有效）。 */
    val length: Int = 0,
    /** 命中的原始 token（快速修复按它定位替换；空串 = 无 token）。 */
    val token: String = "",
)