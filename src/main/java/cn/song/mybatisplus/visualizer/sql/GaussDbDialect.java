// T-SyncK2JMaster175 afd3d09 自 master sql/Dialect.kt（GaussDbDialect object，新增）移植
// （master 的 Kotlin 源把全部方言 object 写在同一 Dialect.kt；Java 侧 public 顶层类须独立文件，沿用 k2j 既有布局）
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * GaussDB（华为云商用版）方言（T-GaussDbDialect 券 04）：第 12 个方言，独立于 {@link OpenGaussDialect}。
 *
 * <h2>为什么独立成 object</h2>
 *
 * <p>原 D2=A 把 GaussDB 并进 openGauss，依据是「同内核」。2026-10-29 复核判定：<b>形态不必分叉，
 * 身份必须分叉</b>。</p>
 * <ul>
 *  <li><b>形态同形</b>：分页 / 引号 / 折叠 / upsert / EXPLAIN / 事务 / DELETE-LIMIT 七项在两边一致，
 *      本类用与 {@link OpenGaussDialect} <b>同一批</b>私有渲染助手实现，产物逐字节相同
 *      （{@code DialectXcFamilyTest} 有一条逐项对拍用例钉住，防两侧漂移）；</li>
 *  <li><b>保留字真的不同</b>：GaussDB 官方《关键字》页 934 词 / 保留 130，openGauss 862 / 122；
 *      GaussDB 独有保留字 11 词（见 {@link SqlKeywords#GAUSSDB_RESERVED}）——这是唯一有实质后果的分叉点，因为
 *      漏引用即语法错误；</li>
 *  <li><b>身份要分</b>：设置下拉 / 日志角标 / MCP 方言名都应显示 GaussDB；MyBatis-Plus 自 3.5.13 起
 *      也把 {@code GAUSS_DB}（{@code LIMIT ?,?}）与 {@code OPENGAUSS}（{@code LIMIT ? OFFSET ?}）拆成两个 {@code DbType}，
 *      插件反着合并会让按 MP 文档配置的用户对不上号。</li>
 * </ul>
 *
 * <h2>逐项依据</h2>
 * <ul>
 *  <li>分页 {@code LIMIT {count} OFFSET {offset}}：GaussDB 官方《SELECT》文法 {@code LIMIT { [offset,] count | ALL }}
 *      两种写法都收，取无歧义的 PG 形态（逗号形态语义虽为 MySQL 的 offset,count，但与 PG 写法
 *      反向，读者易误读——见 {@link OpenGaussDialect} 的 LIMIT 订正）；</li>
 *  <li>引号双引号 + 折叠 LOWER：GaussDB 不带引号的标识符折小写；B 兼容模式额外收反引号，本类
 *      不依赖它（插件只用双引号）；</li>
 *  <li>upsert {@code ON DUPLICATE KEY UPDATE col = VALUES(col)}：GaussDB 全模式支持；PG 的
 *      {@code ON CONFLICT} 官方明写「仅支持 PG 兼容性」，<b>默认 A（Oracle）兼容模式不可用</b>，故不取；</li>
 *  <li>EXPLAIN {@code (FORMAT JSON)}：与 openGauss 真机同形（单列 {@code QUERY PLAN}，{@code [{"Plan":…}]}）；</li>
 *  <li>事务 {@code BEGIN}；DELETE 的 {@code LIMIT} 尾段显式判无（同 {@link OpenGaussDialect#getSupportsDeleteLimit()}）；</li>
 *  <li>identity 包装：不需要（GaussDB 无 {@code SET IDENTITY_INSERT} 形态）。</li>
 * </ul>
 *
 * <h2>证据级别</h2>
 *
 * <p><b>官方文档级</b>（华为云 GaussDB 集中式 V2.0-10.x SQL 参考：SELECT / INSERT / EXPLAIN /
 * CREATE DATABASE / 关键字）。GaussDB 商用版<b>无公开容器镜像</b>、无真机；各项形态与
 * openGauss 5.0.0 真机读数同源（{@code server_version} 基线 9.2.4）。真机读数到位后升档。</p>
 *
 * <h2>兼容模式边界</h2>
 *
 * <p>{@code sql_compatibility} 是<b>库级</b>属性（A/Oracle 默认、B/MySQL、C/Teradata、PG、M），建库后不可改。
 * 本类按<b>默认 A 模式</b>的可用面渲染；B 模式专属能力（反引号、{@code IFNULL}、{@code DATE_FORMAT}、
 * {@code LAST_INSERT_ID}）<b>不用</b>——真机实测这些在 A 模式直接报函数不存在。模式感知不在本轮范围。</p>
 */
public final class GaussDbDialect implements Dialect {

    public static final GaussDbDialect INSTANCE = new GaussDbDialect();

    private GaussDbDialect() {}

    @Override
    public String getName() {
        return "GaussDB";
    }

    /** R36：GaussDB 官方《BEGIN》在册，取与 openGauss 相同的 `BEGIN`。 */
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

    /** 裸标识符折小写（PG 系），与 openGauss/KingBase/PG 同族。 */
    @Override
    public IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.LOWER;
    }

    /** 见 {@link OpenGaussDialect#getSupportsDeleteLimit()}：语法接受但语义未验证，保守判无（非遗漏）。 */
    @Override
    public boolean getSupportsDeleteLimit() {
        return false;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.GAUSSDB_RESERVED;
    }

    /** MySQL 形态 upsert；`ON CONFLICT` 仅 PG 兼容模式可用，默认 A 模式不可，故不取。 */
    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderMySqlStyleUpsert(this, updateColumns, caseSource);
    }
}
