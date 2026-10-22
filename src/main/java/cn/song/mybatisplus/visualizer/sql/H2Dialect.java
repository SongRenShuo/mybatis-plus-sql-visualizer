package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * H2 方言：{@code LIMIT {count} OFFSET {offset}}。
 * upsert 原生形态为整语句 {@code MERGE INTO t (cols) KEY (conflict) VALUES (...)}。
 */
public final class H2Dialect implements Dialect {

    public static final H2Dialect INSTANCE = new H2Dialect();

    private H2Dialect() {}

    @Override
    public String getName() {
        return "H2";
    }

    /** R36：H2 2.3.232 六模式（含 MySQL/PG/Oracle/MSSQLServer 兼容模式）引擎实测取 `BEGIN`（证据三档见 Dialect#getTransactionBegin）。 */
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

    /** T-DialectQuoteWarn：H2 收双引号 / 反引号 / 方括号三种标识符引号（引擎宽容）。 */
    @Override
    public Set<Character> getIdentifierQuotes() {
        return Set.of('"', '`', '[');
    }

    /**
     * O-51：H2 裸标识符<b>折叠为大写</b>（与 Oracle 同族）→ UPPER 族，目录来源的字典名
     * 「含小写字母即加引号」。
     *
     * <p>引擎级对照实验依据（H2 2.3.232 REGULAR，{@code DialectH2VerificationTest.标识符折叠方向_H2引擎实测对照}
     * 逐次复现）：引号创建的 {@code "fold_lower"} <b>裸写</b>访问失败（引擎折为 {@code FOLD_LOWER} 去找，
     * {@code Table "FOLD_LOWER" not found}）、引号创建的 {@code "FOLD_UPPER"} 裸写命中 → 折为大写。
     * 此前归 NONE（按「其余方言」继承默认）与实测相反，本轮按实测订正——NONE 下带引号创建的小写
     * 字典名会被裸写 → 折叠为大写 → 对象不存在。</p>
     */
    @Override
    public IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.UPPER;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.H2_RESERVED;
    }

    @Override
    public String renderUpsertStatement(String tableName, List<String> columns, String conflictKey,
                                        IdentifierCaseSource caseSource) {
        String cols = columns.stream().map(it -> quoteIfNeeded(it, caseSource)).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(it -> "?").collect(Collectors.joining(", "));
        return "MERGE INTO " + quoteIfNeeded(tableName, caseSource) + " (" + cols + ") KEY (" +
                quoteIfNeeded(conflictKey, caseSource) + ") VALUES (" + placeholders + ")";
    }

}
