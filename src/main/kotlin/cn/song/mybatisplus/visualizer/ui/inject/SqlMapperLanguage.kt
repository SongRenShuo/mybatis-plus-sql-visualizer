package cn.song.mybatisplus.visualizer.ui.inject

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer
import com.intellij.lang.Language

/**
 * mapper XML 语句文本/属性值注入用的自研 SQL-like 语言（lexer-only，W2 决策 2-A：不新建第二个
 * 语言——`${}` 与属性值的 OGNL 细着色走**同一个 Lexer 的状态机**，避免注入区间翻倍）。
 *
 * 场景 = mapper XML 的语句标签（select/insert/update/delete）文本 + `XmlSynHiGate` 属性白名单
 * （`test`/`value`/`collection`… 的属性值也是 OGNL 表达式，由同一语言着色）。
 *
 * ## 关键字集口径（T-XmlGap W3 #7）：全方言超集
 *
 * 词集 = 原 [BASE_KEYWORDS]（T-XmlSynHi D1 的 41 词平集，保留为兜底、不删一词）
 * ∪ [SqlLexer.KEYWORDS]（补全器与预览面板共用的 66 词）
 * ∪ [DIALECT_RESERVED]（`sql/Dialect.kt` 十四个实现类 [cn.song.mybatisplus.visualizer.sql.Dialect.reservedWords]
 * 的并集，大写形态）。
 *
 * **取舍（为什么是「全方言超集」而不是按文件方言收敛）**：注入语言是**全局**的（一个 `Language`
 * 实例服务所有项目、所有 mapper 文件），而方言是**项目级**设置（[DialectRegistry.effective] 需要
 * project 上下文）；语言对象在 `SqlLexer`/补全链里拿不到 per-file 方言，也**不应**因此持有
 * project 级状态（语言是应用级单例，持项目态会导致跨项目串味与泄漏）。故取「并集」：
 * 任何方言的保留字都着色——代价是 `RETURNING`（PG/MariaDB 保留字）在 MySQL 项目里也会着色，
 * 属**多着色**（不减色、不误报），精确收敛留后续专项（需要 per-file 方言解析链）。
 *
 * 这样修掉了内部调研报告 §0.9 那条内部不一致：「补全能提示但编辑器里不着色」（两套词集差 25 词）。
 */
object SqlMapperLanguage : Language("MpSqlMapper") {

    /**
     * T-XmlSynHi D1 平集（41 词）。**只增不删**：这是历史基线，删除任何一词都会让既有
     * 文件里原本着色的词失色（回归），新词一律并入而非替换。
     */
    internal val BASE_KEYWORDS: Set<String> = setOf(
        "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "AND", "OR", "NOT", "NULL",
        "IN", "AS", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "ORDER", "BY",
        "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "VALUES", "INTO", "SET", "DISTINCT",
        "CASE", "WHEN", "THEN", "ELSE", "END", "LIKE", "BETWEEN", "EXISTS", "IS", "ASC", "DESC",
    )

    /**
     * W3 #5：OGNL 子状态可判为关键字/字面量的词集。
     *
     * **以 `xml/Ognl.kt` 实际支持为准，不凭记忆加**：该文件词法层认得的词形只有
     * `and` / `or` / `not`（`Parser.parseOr/parseAnd/parseUnary`）、`in`（`RELATIONAL_OPS`，
     * `not in` 由 `isNotIn()` 组合）、`true` / `false` / `null`（`parsePrimary` 的三个字面量分支）。
     * `eq`/`neq`/`lt`/`gt`/`lte`/`gte`/`nin`/`mod`/`new`/`instanceof` 等在 `Ognl.kt` 里
     * **没有**对应实现（`new` 直接抛「unexpected character」，其余按普通标识符解析）——
     * 故不入集（真机 MyBatis OGNL 支持其中一部分，但预览求值器不支持；着色与求值器口径一致
     * 优先于「猜真实 OGNL 全集」，加词前先给 `Ognl.kt` 加实现）。
     *
     * `true`/`false` 不在本集内：它们另有 [SqlMapperTT.BOOLEAN] token（与 Java/JS 把布尔字面量
     * 当关键字的口径分开，见 [SqlMapperSyntaxHighlighter] 的键表注）。
     */
    internal val OGNL_KEYWORDS: Set<String> = setOf("AND", "OR", "NOT", "IN", "NULL")

    /**
     * 十四个方言保留字的并集（大写）。数据源 = `DialectRegistry.dialects` 各实现类的
     * `reservedWords`（小写）——OceanBase 复用 MySQL 集、openGauss 复用 PG 集，并集结果与
     * 九个 `SqlKeywords.kt` 顶层 val 的并集相同。
     */
    internal val DIALECT_RESERVED: Set<String> = DialectRegistry.dialects
        .flatMapTo(linkedSetOf()) { it.reservedWords }
        .mapTo(linkedSetOf()) { it.uppercase() }

    /** 命中即按关键字着色的词集（大小写不敏感；全方言超集，口径见类 KDoc）。 */
    val KEYWORDS: Set<String> = BASE_KEYWORDS + SqlLexer.KEYWORDS + DIALECT_RESERVED

    /**
     * 按词长分桶的关键字表（T-XmlGap W3 配套）。
     *
     * 词集从 41 词涨到数百词后，[SqlMapperHighlightingLexer] 的零分配判定
     * （`regionMatches` 直读缓冲，逐词比长度+忽略大小写）若仍对全表线性扫，每个标识符
     * 都要比数百次长度——分桶后长度不等直接不进桶，实际比较数回到个位数。
     * 桶内保序（`groupBy` 保持原迭代顺序），判定结果与全表扫完全一致（长度必须相等）。
     */
    internal val KEYWORDS_BY_LENGTH: Map<Int, List<String>> = KEYWORDS.groupBy { it.length }
}
