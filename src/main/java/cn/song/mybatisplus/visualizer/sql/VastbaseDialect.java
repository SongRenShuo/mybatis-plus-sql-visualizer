// T-SyncK2JMaster175 afd3d09 自 master sql/Dialect.kt（VastbaseDialect object，新增）移植
// （master 的 Kotlin 源把全部方言 object 写在同一 Dialect.kt；Java 侧 public 顶层类须独立文件，沿用 k2j 既有布局）
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;

import java.util.List;
import java.util.Set;

/**
 * 海量（Vastbase G100）方言（T-XcDialects3 券 02）：第 14 个方言。openGauss 商业发行版
 * （官方《产品定位》明写基于开源 openGauss 内核），但<b>独立于 {@link OpenGaussDialect}/
 * {@link GaussDbDialect}</b>——保留字（{@link SqlKeywords#VASTBASE_RESERVED} 四模式并集）与身份分叉，upsert 形态与
 * openGauss/GaussDB 同（MySQL 形态）、分页折叠同 PG 族。
 *
 * <h2>证据级别</h2>
 *
 * <p><b>官方文档级</b>（docs.vastdata.com.cn Vastbase G100 V3.0.9《SQL 参考》：SELECT/INSERT/DELETE/
 * EXPLAIN/关键字四表/开发规范）。真机部署在途（DBX 在册无 vastbase 实例），本轮按文档落地，
 * 真机读数到位后逐项升档。</p>
 *
 * <h2>逐项依据</h2>
 * <ul>
 *  <li>事务 {@code BEGIN}：官方 {@code START TRANSACTION} / {@code BEGIN} 都在册；官方注意事项——<b>vsql 中 {@code BEGIN}
 *      不能与 SQL 写在同一行</b>（插件产物是逐条独立语句，不受影响）；</li>
 *  <li>分页<b>只渲染 PG 形态</b>：官方通用文法另有 {@code LIMIT [offset,] count} 逗号形态且文档写 MySQL
 *      语义（首参=跳过行数）——与同内核 openGauss 5.0.0 真机读数同向（逗号形态确为 MySQL 语义，
 *      与 PG 的 {@code LIMIT count OFFSET offset} 写法反向、读者易误读，见 {@link OpenGaussDialect} 的
 *      LIMIT 订正），故只产无歧义的 {@code LIMIT {count} OFFSET {offset}}；</li>
 *  <li>引号双引号 + 折叠 LOWER：官方《开发规范》「对象名不区分大小写，双引号可区分」推定（未真机）；
 *      <b>反引号仅 B 模式可用</b>（默认 A 模式不可），本类不依赖；</li>
 *  <li>upsert <b>MySQL 形态</b> {@code ON DUPLICATE KEY UPDATE c = VALUES(c)}（D3 定案）：官方通用文法
 *      两种形态都收，但默认 A（Oracle）模式无真机证据——取同内核 openGauss 真机已证可用的形态
 *      （openGauss 5.0.0 实测 {@code ON CONFLICT} 语法错误）；<b>真机证 PG 形态可用再切</b>；</li>
 *  <li>DELETE 的 {@code LIMIT} 尾段：官方通用 DELETE 文法含 {@code [ORDER BY][LIMIT]} 但 B 模式 DELETE
 *      专章重写且不含——文档自相矛盾，保守判无（非遗漏），与 openGauss/GaussDB 同口径；</li>
 *  <li>EXPLAIN：官方 {@code FORMAT {TEXT|XML|JSON|YAML}} 默认 TEXT；<b>B 模式下 {@code EXPLAIN} 兼作 {@code DESC}</b>
 *      （{@link ExplainSupport} 已由 ExplainGate 首关键字白名单保证只对完整 SELECT 生成，此处仅登记）；</li>
 *  <li>identity：B 模式 {@code AUTO_INCREMENT} 允许显式写值、PG 模式 {@code GENERATED ALWAYS} 需语句内
 *      {@code OVERRIDING SYSTEM VALUE}（非前置包装）→ {@code renderInsertWrapper} 保持默认 null。</li>
 * </ul>
 *
 * <h2>兼容模式边界</h2>
 *
 * <p>兼容模式是<b>实例级</b>、初始化后不可改（{@code vb_initdb --dbcompatibility} 取 A/B/PG/MSSQL，默认
 * <b>A（Oracle）</b>），URL 里无模式信息可判。本类按默认 A 模式的可用面渲染；B 模式专属能力
 * （反引号、{@code lower_case_table_names}）不用。模式感知不在本轮范围（D2 定案）。</p>
 */
public final class VastbaseDialect implements Dialect {

    public static final VastbaseDialect INSTANCE = new VastbaseDialect();

    private VastbaseDialect() {}

    @Override
    public String getName() {
        return "Vastbase";
    }

    /** R36：官方 `BEGIN`/`START TRANSACTION` 均在册且<b>社区镜像引擎级实证可用</b>（A 模式 2026-10-30；vsql 中 BEGIN 不可与 SQL 同行——插件产物逐条独立，不受影响）。 */
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
     * 裸标识符折小写（<b>默认兼容模式口径</b>）。<b>社区镜像引擎级实证</b>（Vastbase G100 V3.0 Build 9 /
     * openGauss 6.0.0，镜像 apecloud/vastbase:v3.0.9.2-ape1，2026-10-30 探针）：
     * A（Oracle，<b>实例默认</b>）模式——裸大写 {@code INFORMATION_SCHEMA.TABLES} 703 行命中、
     * {@code information_schema."tables"} 703 行、{@code information_schema."TABLES"} 报
     * {@code relation "information_schema.TABLES" does not exist}。
     *
     * <p><b>B（MySQL）模式不折叠</b>（同批实测）：{@code lower_case_table_names = 0}、表名保留创建时大小写
     * 并大小写敏感——{@code T_Mix} 落库仍是 {@code T_Mix}，查 {@code t_mix} / {@code T_MIX} 均报不存在；
     * {@code INFORMATION_SCHEMA.TABLES} 报错、{@code information_schema.tables} 628 行。</p>
     *
     * <p><b>为什么仍声明 LOWER 族而不按模式分叉</b>：本方言不做模式感知（D2 定案），而 LOWER 族的目录来源
     * 规则（{@code Dialect.quoteIfNeeded} 的 CATALOG 分支：字典名<b>含大写字母即加引号</b>）在 B 模式下
     * <b>恰好也给对</b>——{@code T_Mix} → {@code "T_Mix"}（引号保留大小写，命中）、{@code t_mix} → 裸写（不折叠，命中）。
     * 即族声明是「默认模式的真实折叠方向」，规则本身对两模式都安全。真机模式感知立项时再拆分。</p>
     */
    @Override
    public IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.LOWER;
    }

    /**
     * <b>社区镜像引擎级实证为 true</b>（A 模式，2026-10-30）：官方通用 DELETE 文法含
     * {@code [ORDER BY][LIMIT]} 而 B 模式专章不含——文档自相矛盾，实测落在通用文法一侧：
     * {@code DELETE FROM t WHERE ... LIMIT 1} 真删 1 行（{@code DELETE 1}），带 {@code ORDER BY ... LIMIT}
     * 同样生效。P-7 的「拿到可写实例后翻 true」条件已满足。
     * <b>B 模式同向</b>（25433 实例实测）：单表 {@code DELETE ... LIMIT 1} 同样真删 1 行；B 模式另支持
     * 多表形态 {@code DELETE FROM t1 USING t1, t2 WHERE ...}（两模式都接受 LIMIT，B 专章的「不含 LIMIT」
     * 属官方文档欠记载，非引擎限制）。
     */
    @Override
    public boolean getSupportsDeleteLimit() {
        return true;
    }

    @Override
    public Set<String> getReservedWords() {
        return SqlKeywords.VASTBASE_RESERVED;
    }

    /**
     * PG 形态 upsert（D3 切换条款已触发）：官方通用文法两种都在册，出厂曾按「A 模式无
     * 真机证据」取 MySQL 形态兜底；<b>社区镜像引擎级实证（A 模式，2026-10-30）两种形态
     * 均可用</b>——{@code ON CONFLICT (id) DO UPDATE SET note = EXCLUDED.note} 规划与真跑覆盖
     * 均生效、{@code DO NOTHING} 可用，{@code ON DUPLICATE KEY UPDATE}（含 {@code VALUES(col)}）同样生效
     * ——按 D3 预留条款切到 PG 形态（与本方言 PG 族渲染及 LOWER 折叠一致）。
     */
    @Override
    public String renderUpsert(String conflictKey, List<String> updateColumns,
                               IdentifierCaseSource caseSource) {
        return Dialect.renderPgStyleUpsert(this, conflictKey, updateColumns, caseSource);
    }
}
