package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * SQLite 方言：同 H2 的 {@code LIMIT {count} OFFSET {offset}}。标识符引用双引号 {@code "x"}。
 */
public final class SqliteDialect implements Dialect {

    public static final SqliteDialect INSTANCE = new SqliteDialect();

    private SqliteDialect() {}

    @Override
    public String getName() {
        return "SQLite";
    }

    /** R36：SQLite 3.50.3 引擎实测取 `BEGIN`（`START TRANSACTION` 语法错误）（证据三档见 Dialect#getTransactionBegin）。 */
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

    /** T-DialectQuoteWarn：SQLite 收双引号 / 反引号 / 方括号三种标识符引号（官方文法宽容）。 */
    @Override
    public Set<Character> getIdentifierQuotes() {
        return Set.of('"', '`', '[');
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.SQLITE_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderPgStyleUpsert(this, conflictKey, updateColumns, caseSource);
    }

}
