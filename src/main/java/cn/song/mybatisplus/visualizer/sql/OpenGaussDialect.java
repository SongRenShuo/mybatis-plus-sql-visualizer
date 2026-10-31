// T-SyncK2JMaster187 自 master sql/Dialect.kt（OpenGaussDialect object）移植
// （master 的 Kotlin 源把全部方言 object 写在同一 Dialect.kt；Java 侧 public 顶层类须独立文件，沿用 k2j 既有布局）
// T-SyncK2JMaster175 afd3d09 增量：T-GaussDbDialect（券01 保留字换 OPENGAUSS_RESERVED；券03 LIMIT 语义订正；
// 券04 与 GaussDB 拆分）+ supportsDeleteLimit 显式声明（语法接受、语义未验证）
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * openGauss 方言（T-DialectXcFamily / O-63）：PG 族形态——{@code LIMIT {count} OFFSET {offset}}、
 * 双引号引用、{@link IdentifierFolding#LOWER} 折叠。
 *
 * <p><b>T-GaussDbDialect 券 04 修订</b>：原 D2=A 定案「GaussDB 与 openGauss 共用一个 object、两前缀都归
 * 本方言」已撤销——{@code jdbc:gaussdb://} 改归独立的 {@code GaussDbDialect}。撤销理由不是形态分叉（两边六项
 * 形态相同，见 GaussDbDialect 的 KDoc），而是<b>身份与保留字</b>：GaussDB 官方保留字表比 openGauss
 * 多 11 词，且用户应在设置页/角标/MCP 里看到 GaussDB 而不是 openGauss。本类此后<b>只服务
 * {@code jdbc:opengauss://} 与显式选择 openGauss 的场景</b>。</p>
 *
 * <p><b>验证来源（openGauss 引擎级，DBX MCP 只读通道，openGauss 5.0.0 社区版 / sql_compatibility='A'，
 * 2026-09-23 首测 + 2026-10-29 复核）</b>：</p>
 * <ul>
 *  <li>折叠 LOWER：{@code SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES} → 455 行（裸大写折小写命中）、
 *      {@code FROM information_schema."TABLES"} → {@code relation "information_schema.TABLES" does not
 *      exist on gaussdb}；</li>
 *  <li>反引号不可用：{@code SELECT 1 AS `x`} → {@code syntax error}（双引号族实锤）；</li>
 *  <li><b>LIMIT 形态（T-GaussDbDialect 券 03 订正）</b>：{@code LIMIT a, b} 是<b>原生 MySQL 语义</b>
 *      （offset, count），<b>不存在静默错解</b>。多行源实测：
 *      {@code SELECT * FROM generate_series(1,5) LIMIT 1, 2} → 返回 {@code 2, 3}（2 行）；
 *      真实 455 行源 {@code SELECT table_name FROM information_schema.tables LIMIT 1, 2} → 第 2、3 行。
 *      原结论「按 PG 语义解析被静默错解」的探针是 {@code SELECT 1 LIMIT 1, 2} → 0 行——<b>单行源上
 *      「MySQL 语义」与「PG 语义（OFFSET 1 LIMIT 2）」给出同一读数</b>，两种假设不可区分，属假绿。
 *      本方言仍渲染 PG 形态（{@code LIMIT {count} OFFSET {offset}}），理由改为「该形态在两种解释下同义、
 *      无歧义」，而非「防御错解」；{@code OFFSET n ROWS FETCH NEXT m ROWS ONLY} 亦可用但不必；</li>
 *  <li>EXPLAIN：{@code EXPLAIN (FORMAT JSON) SELECT 1} → 单列 {@code QUERY PLAN} JSON 文本（JSON 档实锤）；
 *      {@code EXPLAIN SELECT 1} → 文本计划树。</li>
 * </ul>
 *
 * <p><b>upsert 特例（真机对照对实锤）</b>：openGauss 的原生 upsert 是 <b>MySQL 形态</b>
 * {@code ON DUPLICATE KEY UPDATE col = VALUES(col)}（EXPLAIN 规划期通过），而 PG 的
 * {@code ON CONFLICT (k) DO UPDATE} 直接语法错误——「PG 族=ON CONFLICT」的族推定在本方言被打脸
 * （KingBase 同为 PG 系但 upsert 相反），照抄 KingBase 先例会产出语法错误 SQL。
 * 官方文档同向（openGauss《INSERT》on_duplicate_key 文法，{@code VALUES(column_name)} 引用在册）。</p>
 *
 * <p>保留字：<b>{@link SqlKeywords#OPENGAUSS_RESERVED}</b>（T-GaussDbDialect 券 01 订正）。此前沿用
 * {@code PG_RESERVED} 是缺陷——真机 {@code pg_get_keywords()} 实测 openGauss 比 PG 多 17 个保留字
 * （{@code rownum} / {@code minus} / {@code modify} / {@code nocycle} / {@code performance} /
 * {@code shrink} / {@code verify} / {@code sysdate} / {@code excluded} / {@code buckets} / {@code authid} /
 * {@code less} / {@code maxvalue} / {@code groupparent} / {@code reject} / {@code procedure} /
 * {@code is}）。其中 {@code rownum} 实测报 {@code ROWNUM cannot be used as an alias}——
 * 列名为 {@code rownum} 的表在旧集合下不会被引用，产物直接语法报错。</p>
 */
public final class OpenGaussDialect implements Dialect {

    public static final OpenGaussDialect INSTANCE = new OpenGaussDialect();

    private OpenGaussDialect() {}

    @Override
    public String getName() {
        return "openGauss";
    }

    /** R36：openGauss 取 PG 形态 `BEGIN`（族推定；本方言 upsert 曾反族，真机核对后增量）（证据三档见 Dialect#getTransactionBegin）。 */
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
     * 折叠方向 LOWER：openGauss 5.0.0 真机实测（2026-09-23，DBX 只读通道），与 PG/KingBase 同族
     * ——目录来源「含大写字母即加引号」。
     */
    @Override
    public IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.LOWER;
    }

    /**
     * DELETE 的 {@code LIMIT n} 尾段（T-GaussDbDialect 券 02）：<b>显式声明不支持</b>（非遗漏，与 Oracle 的
     * {@code transactionBegin} 判无同款写法）。
     *
     * <p>2026-10-29 真机取证分三档，结论比原记录复杂：</p>
     * <ul>
     *  <li><b>语法层</b>：{@code EXPLAIN DELETE FROM db4ai.snapshot WHERE id = 1 LIMIT 1} →
     *      {@code permission denied for relation snapshot}，即已过语法与分析阶段（同库对照组
     *      {@code EXPLAIN INSERT … ON CONFLICT (id) …} 直接报 {@code syntax error at or near "CONFLICT ("}，
     *      证明本通道能可靠区分语法错与权限拦）→ openGauss <b>接受</b>该语法；</li>
     *  <li><b>语义层</b>：是否真的限行<b>未验证</b>——在册实例非超管且 {@code public} 无用户表，DML 全被权限拦下，
     *      无从观察影响行数；</li>
     *  <li><b>依据层</b>：此前 false 的理由是「PG 族无 DELETE LIMIT 尾段（真机佐证 {@code LIMIT 1,2} 按 PG
     *      语义解析）」，该理由已被券 03 推翻（{@code LIMIT a, b} 是 MySQL 语义）。</li>
     * </ul>
     *
     * <p>故保持 false 但换依据：「语法接受、语义未验证」。取舍不对称——渲染了而语义不生效，用户会
     * 以为删除有界而实际无界；不渲染只是少一个尾段。拿到可写实例的语义读数后按实测翻转。</p>
     */
    @Override
    public boolean getSupportsDeleteLimit() {
        return false;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.OPENGAUSS_RESERVED;
    }

    /**
     * MySQL 形态 upsert（真机对照对实锤，见类 KDoc）——复用 {@link Dialect#renderMySqlStyleUpsert}，
     * 列名引用符号随本方言的双引号（{@code VALUES()} 引用形态 openGauss 官方 INSERT 文法在册）。
     */
    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderMySqlStyleUpsert(this, updateColumns, caseSource);
    }
}
