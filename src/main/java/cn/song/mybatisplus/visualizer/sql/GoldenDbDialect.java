// T-SyncK2JMaster177 95e107b 自 master sql/Dialect.kt（GoldenDbDialect object，新增）移植
// （master 的 Kotlin 源把全部方言 object 写在同一 Dialect.kt；Java 侧 public 顶层类须独立文件，沿用 k2j 既有布局）
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * GoldenDB（中兴 ZXCLOUD GoldenDB 分布式数据库）方言（T-GoldenDbDialect 券 A）：第 15 个方言。
 *
 * <h2>证据级别</h2>
 *
 * <p><b>官方文档级</b>（《GoldenDB 产品手册_开发支持 V6.1.03.10》，2025-03 构建，379 页；页码即 PDF
 * 物理页号）。无真机（P-1：无公开容器镜像、测试服务器无实例）——全部形态按 PDF 逐字页码取证，
 * <b>未经真机引擎验证</b>；真机到位后逐项升档（照 GaussDB/HighGo/Vastbase 的文档级先例）。</p>
 *
 * <h2>逐项依据（全部带 PDF 页码）</h2>
 * <ul>
 *  <li>分页 {@link #renderLimit}（{@code LIMIT [offset,] row_count}，offset 从 0 起算）：p73 分页
 *      专章，示例 {@code LIMIT 5} / {@code LIMIT 0,5} / {@code LIMIT 5,10} 逐字；</li>
 *  <li>事务 {@code START TRANSACTION}：p83 事务控制语句专章（1.7.3），与 MySQL 同形；</li>
 *  <li>引用反引号 {@code `x`}：p320 {@code SHOW CREATE PROCEDURE} 回显 {@code `root`@`zte-179`} 形态；</li>
 *  <li>upsert {@link #renderUpsert}（{@code ON DUPLICATE KEY UPDATE col = VALUES(col)}）：p66
 *      多行 VALUES + 冲突更新示例逐字；{@code REPLACE INTO}（p282）与 {@code MERGE INTO}（p282，Oracle 风格）
 *      是整语句形态，本插件取 INSERT+后缀形态；</li>
 *  <li>DELETE 的 {@code LIMIT} 尾段 <b>true</b>：1.10.8.2 DELETE 文法（p274）逐字含 {@code [ORDER BY ...]} 与
 *      {@code [LIMIT row_count]}，p276 另给可执行示例
 *      {@code DELETE FROM somelog WHERE user = 'jcole' ORDER BY timestamp_column LIMIT 1;}——文档级双证
 *      （比 GaussDB/openGauss 的「语法接受、语义未验证」更强），故不像 PG 族先取 false；</li>
 *  <li>保留字 {@link SqlKeywords#GOLDENDB_RESERVED}：p351-p355 三张分模式保留字表并集 384 词（见该 KDoc）。</li>
 * </ul>
 *
 * <h2>两条口径边界（防后人读 PDF 走偏）</h2>
 *
 * <p>1. <b>{@code oracleMode} 是驱动连接参数、语义未取证（P-2），不参与方言推断</b>。驱动默认 true
 * （{@code ConnectionPropertiesImpl} 构造器字节码 {@code iconst_1} 实测），但 PDF 全文检索
 * {@code oracle_mode}/{@code compatible_mode}/「兼容模式」零命中——它<b>不是</b>服务端 SQL 兼容模式开关，
 * 依磐维教训（{@code DialectInference} 的 {@code db_compatibility} 误判先例）不按参数名猜语义，
 * 恒按 MySQL 形态渲染；真机对照实验（{@code oracleMode=true/false} 双连接跑
 * {@code SELECT SYSDATE FROM DUAL}）到位前不分叉。</p>
 *
 * <p>2. <b>1.9.6「双库运行兼容」建议（p159-p166）不是方言语法要求，按 MySQL 形态渲染是正确选择</b>。
 * 该章建议「避免使用 LIMIT，使用 ROWNUM」「避免 IFNULL 用 nvl」等——前提是「同一份 SQL 想
 * 同时跑 Oracle」；本插件渲染的 SQL 是<b>给 GoldenDB 单库执行</b>的，没有该需求。GoldenDB 默认
 * SQL 方言是 MySQL 形态（p37「完全兼容 MySQL 客户端」+ p66/p73/p320 三面一致），Oracle 兼容面
 * （ROWNUM/DUAL/SEQUENCE/MERGE INTO，p224-227、p285、p335-367）是增量能力而非默认模式。</p>
 */
public final class GoldenDbDialect implements Dialect {

    public static final GoldenDbDialect INSTANCE = new GoldenDbDialect();

    private GoldenDbDialect() {}

    @Override
    public String getName() {
        return "GoldenDB";
    }

    /** p83 事务控制语句专章（1.7.3），MySQL 同形（见 {@link #getTransactionBegin()}）。 */
    @Override
    public String getTransactionBegin() {
        return "START TRANSACTION";
    }

    /** p73：{@code LIMIT [offset,] row_count}，offset 从 0 起算（MySQL 形态）。 */
    @Override
    public String renderLimit(LimitClause limit) {
        return Dialect.renderMySqlStyleLimit(limit);
    }

    /**
     * p274 DELETE 文法逐字含 {@code [ORDER BY ...] [LIMIT row_count]} + p276 可执行示例
     * {@code DELETE FROM somelog WHERE user = 'jcole' ORDER BY timestamp_column LIMIT 1;}
     * （文档级，真机复核「真删 1 行」后完全销案，P-5）。
     */
    @Override
    public boolean getSupportsDeleteLimit() {
        return true;
    }

    /** p320：{@code SHOW CREATE PROCEDURE} 回显反引号标识符。 */
    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public Set<Character> getIdentifierQuotes() {
        return Set.of('`');
    }

    /** p351-355 三表并集 384 词（见 {@link SqlKeywords#GOLDENDB_RESERVED} KDoc 的噪声清理记录）。 */
    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.GOLDENDB_RESERVED;
    }

    /** p66：{@code ON DUPLICATE KEY UPDATE col_name=expr}（含 {@code c=VALUES(a)+VALUES(b)} 示例逐字）。 */
    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderMySqlStyleUpsert(this, updateColumns, caseSource);
    }
}
