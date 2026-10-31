// T-SyncK2JMaster187 自 master sql/Dialect.kt（OceanBaseDialect object）移植
// （master 的 Kotlin 源把全部方言 object 写在同一 Dialect.kt；Java 侧 public 顶层类须独立文件，沿用 k2j 既有布局）
// T-SyncK2JMaster175 afd3d09 增量：KDoc 由文档级升社区镜像引擎级实证（2026-10-30，OB CE 4.3.5.6）
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * OceanBase（MySQL 模式）方言（T-DialectXcFamily / O-63）：同 MySQL 的 {@code LIMIT [offset,] count}。
 *
 * <p>标识符引用反引号 {@code `x`}。upsert 同 MySQL 的 {@code ON DUPLICATE KEY UPDATE ... VALUES()} 形态。
 * D1=A 定案：<b>只接 MySQL 模式</b>（主流装机面），Oracle 模式不做。</p>
 *
 * <p><b>验证来源（社区镜像引擎级实证，2026-10-30）</b>：OceanBase CE 4.3.5.6
 * （{@code ob_compatibility_mode=MYSQL}，{@code oceanbase/oceanbase-ce:4.3.5-lts}，部署于测试服务器 25881）逐项实测：</p>
 * <ul>
 *  <li>分页：{@code LIMIT 2,2} → 跳过 2 取 2、{@code LIMIT 2 OFFSET 1} → 同结果（<b>5 行源区分性探针</b>，非单行源假绿）；</li>
 *  <li>引号：反引号可用（{@code SELECT 1 AS `x`} 列名 x）；</li>
 *  <li>upsert：{@code ON DUPLICATE KEY UPDATE} 生效（{@code ROW_COUNT()} 1/2/0）；{@code ON CONFLICT DO UPDATE} →
 *      ERROR 1064（PG 语法被拒，与本方言 MySQL 兼容面一致）；</li>
 *  <li>DELETE LIMIT：{@code DELETE … LIMIT 1} 真删 1 行 → {@code supportsDeleteLimit} true 成立；</li>
 *  <li>事务：{@code START TRANSACTION} / {@code BEGIN} 均可，事务内 ROLLBACK/COMMIT 语义正确；</li>
 *  <li>自增：{@code AUTO_INCREMENT} 显式写 100 被接受、下一行续排 101；</li>
 *  <li><b>EXPLAIN 档位（本轮订正）</b>：{@code EXPLAIN <sql>} 返回<b>单列 {@code Query Plan} 的 ASCII 树</b>
 *      （算子 {@code TABLE RANGE/FULL SCAN}、含 {@code partitions(p0)}/{@code is_index_back}），<b>不是</b>官方文档所述的
 *      TRADITIONAL 表格行；{@code EXPLAIN FORMAT=JSON} 是 OB 专用紧凑 JSON（{@code ID/OPERATOR/EST.ROWS}），
 *      非 MySQL {@code query_block} → {@link ExplainSupport} 表登记 <b>TEXT</b> 档（原误登 TABLE，会让索引建议
 *      按 MySQL 列名分支解析而永不触发，T-OBExplainFormat）；</li>
 *  <li>JDBC URL（文档级）：OceanBase Connector/J 官方《Database URL》{@code jdbc:oceanbase://host:port/db}。</li>
 * </ul>
 *
 * <p>保留字：沿用 {@link SqlKeywords#MYSQL_RESERVED}（MySQL 8.0 全量）为基线——OB MySQL 模式的兼容性
 * 声明覆盖语法面，OB 特有保留字差异未逐词核对（真机核对后增量，参照 P-立项-05 的 DM/KingBase 黄金清单
 * 先例）。折叠方向：{@link IdentifierFolding#NONE}（真机实证：{@code lower_case_table_names=1}，表名统一按小写存储、
 * 比较不敏感——{@code CREATE TABLE TProbe3} 落库 {@code tprobe3}、{@code T_DUP} 与 {@code t_dup} 冲突报 ERROR 1050）。
 * <b>与 Linux MySQL 默认（{@code lower_case_table_names=0}）不同</b>，故目录来源按名判定无意义的分支
 * （NONE 族）在此仍成立。</p>
 */
public final class OceanBaseDialect implements Dialect {

    public static final OceanBaseDialect INSTANCE = new OceanBaseDialect();

    private OceanBaseDialect() {}

    @Override
    public String getName() {
        return "OceanBase";
    }

    /** R36：OceanBase 只接 MySQL 模式（D1=A），取 MySQL 形态 `START TRANSACTION`（文档级）（证据三档见 Dialect#getTransactionBegin）。 */
    @Override
    public String getTransactionBegin() {
        return "START TRANSACTION";
    }

    @Override
    public String renderLimit(LimitClause limit) {
        return Dialect.renderMySqlStyleLimit(limit);
    }

    @Override
    public boolean getSupportsDeleteLimit() {
        return true;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public Set<Character> getIdentifierQuotes() {
        return Set.of('`');
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.MYSQL_RESERVED;
    }

    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderMySqlStyleUpsert(this, updateColumns, caseSource);
    }
}
