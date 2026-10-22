package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * PostgreSQL 方言：{@code LIMIT count OFFSET offset}。
 */
public final class PostgreSQLDialect implements Dialect {

    public static final PostgreSQLDialect INSTANCE = new PostgreSQLDialect();

    private PostgreSQLDialect() {}

    @Override
    public String getName() {
        return "PostgreSQL";
    }

    /** R36：PG 官方 `BEGIN [WORK|TRANSACTION]`（证据三档见 Dialect#getTransactionBegin）。 */
    @Override
    public String getTransactionBegin() {
        return "BEGIN";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        return Dialect.renderPgStyleLimit(limit);
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public Set<Character> getIdentifierQuotes() {
        return Set.of('"');
    }

    /**
     * O-51：PG 裸标识符<b>折叠为小写</b>，故目录来源（字典真实名字）按「含大写字母即加引号」判定
     * ——以引号创建的大写名裸写后被折叠为小写 → 查不到。
     *
     * <p>真机依据（DBX MCP 只读实测，2026-09-22）：{@code SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES}
     * → 366 行（裸写折为小写，命中小写字典名）；{@code FROM information_schema."tables"} → 366 行；
     * {@code FROM information_schema."TABLES"} → {@code relation "information_schema.TABLES" does not exist}。</p>
     */
    @Override
    public IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.LOWER;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.PG_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderPgStyleUpsert(this, conflictKey, updateColumns, caseSource);
    }

}
