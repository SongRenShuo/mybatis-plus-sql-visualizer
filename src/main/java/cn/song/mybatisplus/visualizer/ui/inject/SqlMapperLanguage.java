// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperLanguage.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.DialectRegistry;
import cn.song.mybatisplus.visualizer.ui.highlight.SqlLexer;
import com.intellij.lang.Language;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * mapper XML 语句文本/属性值注入用的自研 SQL-like 语言（lexer-only，W2 决策 2-A：不新建第二个
 * 语言——{@code ${}} 与属性值的 OGNL 细着色走<b>同一个 Lexer 的状态机</b>，避免注入区间翻倍）。
 *
 * <p>场景 = mapper XML 的语句标签（select/insert/update/delete）文本 + {@code XmlSynHiGate} 属性白名单
 * （{@code test}/{@code value}/{@code collection}… 的属性值也是 OGNL 表达式，由同一语言着色）。
 * 与插件自家预览面板的 {@code SqlLexer}（预览面板专用）分立——注入语言要求平台 Language 身份。</p>
 *
 * <p>关键字集口径（T-XmlGap W3 #7）：全方言超集。词集 = 原 {@link #BASE_KEYWORDS}
 * （T-XmlSynHi D1 的 41 词平集，保留为兜底、不删一词）∪ {@code SqlLexer.KEYWORDS}
 * （补全器与预览面板共用的 66 词）∪ {@link #DIALECT_RESERVED}
 * （{@code sql/Dialect} 十四个实现类 {@link Dialect#getReservedWords()} 的并集，大写形态）。</p>
 *
 * <p><b>取舍（为什么是「全方言超集」而不是按文件方言收敛）</b>：注入语言是<b>全局</b>的（一个
 * {@code Language} 实例服务所有项目、所有 mapper 文件），而方言是<b>项目级</b>设置
 * （{@code DialectRegistry.effective} 需要 project 上下文）；语言对象在 {@code SqlLexer}/补全链里
 * 拿不到 per-file 方言，也<b>不应</b>因此持有 project 级状态（语言是应用级单例，持项目态会导致
 * 跨项目串味与泄漏）。故取「并集」：任何方言的保留字都着色——代价是 {@code RETURNING}
 * （PG/MariaDB 保留字）在 MySQL 项目里也会着色，属<b>多着色</b>（不减色、不误报），精确收敛留后续
 * 专项（需要 per-file 方言解析链）。</p>
 *
 * <p>这样修掉了内部调研报告 §0.9 那条内部不一致：「补全能提示但编辑器里不着色」（两套词集差 25 词）。</p>
 *
 * <p>移植注记：Kotlin object 单例 → Java {@code public static final} {@link #INSTANCE}
 * （Kotlin 以 {@code SqlMapperLanguage} 直呼实例处，Java 侧写 {@code SqlMapperLanguage.INSTANCE}）。</p>
 */
public final class SqlMapperLanguage extends Language {

    /** Kotlin object 单例等价（{@code object SqlMapperLanguage : Language("MpSqlMapper")}）。 */
    public static final SqlMapperLanguage INSTANCE = new SqlMapperLanguage();

    private SqlMapperLanguage() {
        super("MpSqlMapper");
    }

    /**
     * T-XmlSynHi D1 平集（41 词）。<b>只增不删</b>：这是历史基线，删除任何一词都会让既有
     * 文件里原本着色的词失色（回归），新词一律并入而非替换。
     */
    public static final Set<String> BASE_KEYWORDS = Set.copyOf(Arrays.asList(
            "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "AND", "OR", "NOT", "NULL",
            "IN", "AS", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "ORDER", "BY",
            "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "VALUES", "INTO", "SET", "DISTINCT",
            "CASE", "WHEN", "THEN", "ELSE", "END", "LIKE", "BETWEEN", "EXISTS", "IS", "ASC", "DESC"
    ));

    /**
     * W3 #5：OGNL 子状态可判为关键字/字面量的词集。
     *
     * <p><b>以 {@code xml/Ognl} 实际支持为准，不凭记忆加</b>：该文件词法层认得的词形只有
     * {@code and} / {@code or} / {@code not}（Parser.parseOr/parseAnd/parseUnary）、{@code in}
     * （RELATIONAL_OPS，{@code not in} 由 isNotIn() 组合）、{@code true} / {@code false} /
     * {@code null}（parsePrimary 的三个字面量分支）。{@code eq}/{@code neq}/{@code lt}/{@code gt}/
     * {@code lte}/{@code gte}/{@code nin}/{@code mod}/{@code new}/{@code instanceof} 等在
     * {@code Ognl} 里<b>没有</b>对应实现（{@code new} 直接抛「unexpected character」，其余按普通
     * 标识符解析）——故不入集（真机 MyBatis OGNL 支持其中一部分，但预览求值器不支持；着色与求值器
     * 口径一致优先于「猜真实 OGNL 全集」，加词前先给 {@code Ognl} 加实现）。</p>
     *
     * <p>{@code true}/{@code false} 不在本集内：它们另有 {@code SqlMapperTT.BOOLEAN} token
     * （与 Java/JS 把布尔字面量当关键字的口径分开，见 SqlMapperSyntaxHighlighter 的键表注）。</p>
     */
    public static final Set<String> OGNL_KEYWORDS = Set.of("AND", "OR", "NOT", "IN", "NULL");

    /**
     * 十四个方言保留字的并集（大写）。数据源 = {@code DialectRegistry} 各实现类的
     * {@link Dialect#getReservedWords()}（小写）——OceanBase 复用 MySQL 集、openGauss 复用 PG 集，
     * 并集结果与九个 {@code SqlKeywords} 顶层 val 的并集相同。
     */
    public static final Set<String> DIALECT_RESERVED = buildDialectReserved();

    private static Set<String> buildDialectReserved() {
        LinkedHashSet<String> lower = new LinkedHashSet<>();
        for (Dialect dialect : DialectRegistry.INSTANCE.getDialects()) {
            lower.addAll(dialect.getReservedWords());
        }
        LinkedHashSet<String> upper = new LinkedHashSet<>();
        for (String word : lower) {
            upper.add(word.toUpperCase());
        }
        return Collections.unmodifiableSet(upper);
    }

    /** 命中即按关键字着色的词集（大小写不敏感；全方言超集，口径见类 KDoc）。 */
    public static final Set<String> KEYWORDS = buildKeywords();

    private static Set<String> buildKeywords() {
        LinkedHashSet<String> union = new LinkedHashSet<>(BASE_KEYWORDS);
        union.addAll(SqlLexer.KEYWORDS);
        union.addAll(DIALECT_RESERVED);
        return Collections.unmodifiableSet(union);
    }

    /**
     * 按词长分桶的关键字表（T-XmlGap W3 配套）。
     *
     * <p>词集从 41 词涨到数百词后，{@code SqlMapperHighlightingLexer} 的零分配判定
     * （{@code regionMatches} 直读缓冲，逐词比长度+忽略大小写）若仍对全表线性扫，每个标识符
     * 都要比数百次长度——分桶后长度不等直接不进桶，实际比较数回到个位数。
     * 桶内保序（groupBy 保持原迭代顺序），判定结果与全表扫完全一致（长度必须相等）。</p>
     */
    public static final Map<Integer, List<String>> KEYWORDS_BY_LENGTH = buildKeywordsByLength();

    private static Map<Integer, List<String>> buildKeywordsByLength() {
        Map<Integer, List<String>> buckets = new LinkedHashMap<>();
        for (String keyword : KEYWORDS) {
            buckets.computeIfAbsent(keyword.length(), len -> new ArrayList<>()).add(keyword);
        }
        return Collections.unmodifiableMap(buckets);
    }
}
