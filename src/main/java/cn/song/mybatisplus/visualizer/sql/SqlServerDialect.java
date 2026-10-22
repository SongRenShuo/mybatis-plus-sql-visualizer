package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;

import java.util.Set;

/**
 * SQL Server 方言（2012+）：{@code OFFSET {offset|0} ROWS FETCH NEXT {count} ROWS ONLY}。
 * 标识符引用方括号 {@code [x]}。
 */
public final class SqlServerDialect implements Dialect {

    public static final SqlServerDialect INSTANCE = new SqlServerDialect();

    private SqlServerDialect() {}

    @Override
    public String getName() {
        return "SQL Server";
    }

    /** R36：T-SQL 官方 `BEGIN TRANSACTION`（缩写 `BEGIN TRAN`）（证据三档见 Dialect#getTransactionBegin）。 */
    @Override
    public String getTransactionBegin() {
        return "BEGIN TRANSACTION";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        SqlValue offset = limit.getOffset() != null ? limit.getOffset() : new SqlValue.Literal("0");
        return "OFFSET " + Dialect.renderLimitValue(offset) + " ROWS FETCH NEXT " +
                Dialect.renderLimitValue(limit.getCount()) + " ROWS ONLY";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier + "]";
    }

    /** T-DialectQuoteWarn：{@code QUOTED_IDENTIFIER} 默认 ON → 方括号与双引号都合法（判双引号错即误报）。 */
    @Override
    public Set<Character> getIdentifierQuotes() {
        return Set.of('[', '"');
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.SQLSERVER_RESERVED;
    }

    /**
     * O-52 / T-IdentityInsertWrap：SQL Server 拒绝向 identity 列显式写值（错误 544
     * {@code Cannot insert explicit value for identity column in table ... when IDENTITY_INSERT is set to OFF}），
     * 必须先 {@code SET IDENTITY_INSERT <表> ON}，语句结束后 OFF。
     *
     * <p>该开关是<b>会话级</b>且同一时刻每会话只能有一个表为 ON，故包装必须<b>逐条 INSERT 成对</b>出现
     * （多条 INSERT 合批时不能只在整段首尾各包一次）。</p>
     *
     * <p>表名与 INSERT 基座共用 {@link #quoteIfNeeded}（同一 {@code caseSource}）——{@code SET IDENTITY_INSERT}
     * 的目标位与 {@code INSERT INTO} 的目标位是两处独立语法位，引用形态不一致会指向不同对象。
     * <b>未真机验证</b>：本环境无 SQL Server 实例（DBX MCP 在册连接无 sqlserver 类型），形态取自
     * DBX {@code d715b16b} 同款实现与 T-SQL 文法；真机端到端登记为环境受限未验证。</p>
     */
    @Override
    public String renderInsertWrapper(String tableName, String sql, IdentifierCaseSource caseSource) {
        return Dialect.identityInsertWrapper(this, tableName, sql, caseSource);
    }

}
