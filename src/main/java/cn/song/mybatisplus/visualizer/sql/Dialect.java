package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.util.SqlStatementSplitter;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据库方言 SPI（T-M5-03/F-14）。
 *
 * <p>方言差异点（M5 实现范围）：
 *  - 分页子句：{@link #renderLimit}（MySQL 用 LIMIT，PostgreSQL 用 LIMIT+OFFSET，Oracle 用 FETCH）
 *  - 标识符引用：{@link #quoteIdentifier}（MySQL 反引号，PG/Oracle 双引号；默认不引用，按需启用）
 *  - 关键字判定：{@link #getReservedWords}（T-M23.4 按方言保留字集合，{@link #quoteIfKeyword} 只判本方言）
 *  - upsert 冲突子句：{@link #renderUpsert}（T-M43 方言差异点第 4 项；null → 无「INSERT+后缀」原生
 *    upsert，渲染降级纯 INSERT）与 {@link #renderUpsertStatement}（P-立项-05：整语句形态，如 H2 MERGE..KEY）
 *  - identity 包装：{@link #renderInsertWrapper}（O-52；null → 本方言无需包装，默认）
 *
 * <p>新增方言：实现本接口并注册进 {@link DialectRegistry}（六件套：分页形态、引用符号、保留字集合、
 * 折叠方向族 {@link #getIdentifierFolding}、upsert 形态、测试）。
 * 验证基线（P-立项-05）：各方言输出形态经 H2 兼容模式 / SQLite 真实引擎 / 官方文档验证，
 * 详见 docs/开发计划/20260828-2000-P-立项-05-方言输出真实验证-报告.md。
 */
public interface Dialect {

    /** 方言名称（与 Settings 下拉项一致）。 */
    String getName();

    /**
     * 渲染完整分页子句（含关键字，如 {@code LIMIT 10} / {@code LIMIT 10 OFFSET 5}）。
     *
     * @param limit 分页模型
     * @return 追加到 SQL 末尾的分页子句
     */
    String renderLimit(LimitClause limit);

    /**
     * 标识符引用（表名/列名包裹）。
     * 默认原样返回（不引用）；需要时子类覆写（如 MySQL 反引号）。
     */
    default String quoteIdentifier(String identifier) {
        return identifier;
    }

    /**
     * 本方言<b>可接受的标识符引号字符</b>集合（T-DialectQuoteWarn，{@code dialect_quote_mismatch} 判定用）。
     *
     * <p>存<b>开引号字符</b>：反引号 {@code `} / 双引号 {@code "} / 方括号 {@code [}（方括号的闭引号
     * {@code ]} 不单列）。空集 = <b>不做引号方言判定</b>（基类兜底：无方言上下文时宁可不报也不误报）。</p>
     *
     * <p>各方言按<b>实际宽容度</b>给集合，不按「品牌印象」一刀切：
     * <ul>
     *   <li>MySQL / MariaDB / OceanBase：仅反引号（双引号默认是字符串字面量，{@code ANSI_QUOTES} 下才成标识符）；</li>
     *   <li>PostgreSQL / Oracle / DM / KingBase / openGauss：仅双引号；</li>
     *   <li>SQL Server：方括号 + 双引号（{@code QUOTED_IDENTIFIER} <b>默认 ON</b>，双引号合法，判错即误报）；</li>
     *   <li>SQLite / H2：双引号 + 反引号 + 方括号（两者都收多种引号）。</li>
     * </ul></p>
     *
     * <p><b>不要</b>用 {@code quoteIdentifier("x").charAt(0)} 反推——基类 {@link #quoteIdentifier} 原样返回，
     * 反推会得到字母 {@code x}（而非引号）。本属性是引号方言判定的唯一事实源。</p>
     */
    default Set<Character> getIdentifierQuotes() {
        return Set.of();
    }

    /**
     * 本方言保留字集合（T-M23.4，小写；{@link #quoteIfKeyword} 判定用）。
     */
    Set<String> getReservedWords();

    /**
     * 标识符<b>按需引用</b>（T-M23.4）：命中<b>本方言</b>保留字（大小写不敏感）→ {@link #quoteIdentifier}
     * 包裹（符号按方言：MySQL 反引号 / PG 双引号 / SQLServer 方括号）；否则原样返回。
     *
     * <p>语义：引用幂等安全（被引用的词在本方言是保留字，包裹后合法）——宁可多包、不漏包；
     * 仅渲染层调用，模型层列名保持原始值（参数对照/补全/勾选匹配不受影响）。</p>
     */
    default String quoteIfKeyword(String identifier) {
        if (getReservedWords().contains(identifier.toLowerCase())) return quoteIdentifier(identifier);
        return identifier;
    }

    /**
     * 标识符<b>按名字大小写来源</b>引用（O-42 / T-OracleQuoteCaseSource；O-51 / T-IdentifierFoldingFamily
     * 把「单方言特判」升级为按 {@link #getIdentifierFolding} 折叠方向族分流）。
     *
     * <p>与 {@link #quoteIfKeyword} 的差别：本方法额外知道该名字<b>从哪来</b>——同一串字符在两种来源下
     * 的「正确引用形态」可以相反。默认实现按 {@link #getIdentifierFolding} 分流：NONE 族逐字节等价于
     * {@link #quoteIfKeyword}（故 MySQL / MariaDB / SQLite / SQL Server / DM 产物不变），
     * UPPER / LOWER 两族只在目录来源下加判定。</p>
     *
     * <p>分流口径（条件<b>显式含</b> {@link IdentifierCaseSource#CATALOG}）：</p>
     * <ul>
     *   <li>CATALOG + {@link IdentifierFolding#UPPER}：名字是<b>字典里的真实名字</b>（自省元数据返回，未归一）。
     *       Oracle 裸标识符折叠为大写，故「含小写字母」的名字<b>必须加引号</b>才能命中；全大写才可裸写；</li>
     *   <li>CATALOG + {@link IdentifierFolding#LOWER}：裸标识符折叠为小写，故「含大写字母」的名字<b>必须加引号</b>
     *       （以引号创建的大写名裸写后被折叠为小写 → 查不到，方向与 UPPER 族相反）；</li>
     *   <li>CATALOG + {@link IdentifierFolding#NONE}：不折叠（或大小写不敏感匹配）→ 按名判定无意义，
     *       落 {@link #quoteIfKeyword}；</li>
     *   <li>{@link IdentifierCaseSource#ENTITY}：名字是<b>小写派生名</b>（{@code @TableName}/{@code @TableField}
     *       注解值，或 camelCase → snake_case 的 {@code NamingStrategy.toSnakeCase} 产物）——这些正是
     *       「不带引号创建」的名字，折叠后恰好命中，<b>加引号反而必然失败</b>。故实体来源<b>不论族</b>都走既有
     *       {@link #quoteIfKeyword} 逐字不变（这是 C 方案「无条件收紧」被否决的依据；
     *       族判定只允许写在 CATALOG 分支里——写进默认路径会连带污染 ENTITY）。</li>
     * </ul>
     *
     * <p>未接线点（{@link IdentifierCaseSource#ENTITY} 默认）维持旧行为——新增重载 + 逐点接线，
     * 不改既有函数语义。</p>
     */
    default String quoteIfNeeded(String identifier, IdentifierCaseSource caseSource) {
        if (caseSource == IdentifierCaseSource.CATALOG) return quoteCatalogName(this, identifier);
        return quoteIfKeyword(identifier);
    }

    /**
     * 标识符<b>折叠方向族</b>（O-51 / T-IdentifierFoldingFamily）：本方言把裸标识符折叠到哪个方向。
     *
     * <p>{@link #quoteIfNeeded} 的目录来源分支按本属性分流——同一串字典名在不同族下的正确引用形态相反
     * （判据落点见 {@link #quoteIfNeeded} 的 KDoc）：</p>
     * <ul>
     *  <li>{@link IdentifierFolding#UPPER}（Oracle 真库实测：{@code FROM dual} 命中 {@code DUAL}、
     *      {@code FROM "dual"} → ORA-00942）；</li>
     *  <li>{@link IdentifierFolding#LOWER}（PG / KingBase 真机实测：{@code FROM INFORMATION_SCHEMA.TABLES}
     *      折为小写命中、{@code FROM information_schema."TABLES"} → relation does not exist）；</li>
     *  <li>{@link IdentifierFolding#NONE}（不折叠或大小写不敏感匹配 → 按名判定无意义）。</li>
     * </ul>
     *
     * <p>默认 {@link IdentifierFolding#NONE}（保持既有行为）。维度是<b>折叠方向</b>而非数据库品牌——DM8 实测
     * 大小写不敏感（{@code sys.sysobjects} / {@code sys."sysobjects"} / {@code sys."SYSOBJECTS"} 三形态均
     * 1029 行）→ NONE，与 DBX 把 {@code {dameng, db2}} 归 {@code UPPER_FOLDING} 的口径分歧按我方实测处理
     * （计划 §八 待验证前提 1）。</p>
     *
     * <p>H2 2.3.232 引擎实测裸写折为大写（与 Oracle 同族）→ <b>UPPER</b>（{@link H2Dialect} 覆写）；
     * SQLite / SQL Server 未实测（SQL Server 大小写取决于 collation），维持 NONE。</p>
     */
    default IdentifierFolding getIdentifierFolding() {
        return IdentifierFolding.NONE;
    }

    /**
     * UPSERT 冲突子句（T-M43：设置里出现 = 承诺可用）。
     *
     * <p>返回 null → 本方言无「INSERT + 后缀」形态的原生 upsert 语法（Oracle/SQL Server/DM 的
     * 原生形态是复杂 MERGE；P-立项-05 修正：H2 的 {@code ON CONFLICT} 在其任何模式下都不可用），
     * 渲染方降级为纯 INSERT（保证 SQL 在本方言可执行）；非 null → 追加到 INSERT 语句末尾的冲突子句。</p>
     *
     * <p>若本方言的原生 upsert 是<b>整语句形态</b>（如 H2 的 MERGE..KEY），则实现
     * {@link #renderUpsertStatement} 并保持本方法返回 null。</p>
     *
     * @param conflictKey    冲突键列名（原始值，实现内按需引用；MySQL 形态无冲突键列表，仍须传入用于勾选判定）
     * @param updateColumns  冲突后更新列名（原始值，不含冲突键；实现内按需引用）
     * @param caseSource     列名的大小写来源（O-51）：目录来源时按 {@link #getIdentifierFolding} 族引用，
     *                       与 INSERT 基座（{@code BatchModel.renderSql}）同一判定——否则会产出
     *                       「基座加引号、后缀裸写」的半包形态（PG 族含大写目录名下后者被折叠为小写 → 列不存在）
     */
    default String renderUpsert(String conflictKey, List<String> updateColumns) {
        return renderUpsert(conflictKey, updateColumns, IdentifierCaseSource.ENTITY);
    }

    /** {@link #renderUpsert(String,List,IdentifierCaseSource)} 的默认来源形态（ENTITY）。 */
    default String renderUpsert(String conflictKey, List<String> updateColumns,
                                IdentifierCaseSource caseSource) {
        return null;
    }

    /**
     * UPSERT <b>整语句</b>形态（P-立项-05）：方言原生 upsert 非「INSERT + 后缀」结构时使用——
     * 如 H2 的 {@code MERGE INTO t (cols) KEY (conflict) VALUES (...)}（H2 2.3.232 引擎实证：
     * {@code ON CONFLICT} 后缀在任何 H2 模式均语法错误，{@code DO UPDATE} 仅存在于真 PostgreSQL）。
     *
     * <p>返回非 null → 渲染方（BatchModel UPSERT 形态）直接使用该整语句，不再渲染 INSERT 基座；
     * 默认返回 null → 走 {@link #renderUpsert} 后缀形态（或其 null 降级纯 INSERT）。</p>
     *
     * @param tableName   表名（原始值，实现内按需引用）
     * @param columns     全部列名（原始值，含冲突键，与 VALUES 的 {@code ?} 顺序一致；实现内按需引用）
     * @param conflictKey 冲突键列名（原始值；必在 columns 内）
     * @param caseSource  列名的大小写来源（O-51）：语义同 {@link #renderUpsert} 的同名参数
     */
    default String renderUpsertStatement(String tableName, List<String> columns, String conflictKey) {
        return renderUpsertStatement(tableName, columns, conflictKey, IdentifierCaseSource.ENTITY);
    }

    /** {@link #renderUpsertStatement(String,List,String,IdentifierCaseSource)} 的默认来源形态（ENTITY）。 */
    default String renderUpsertStatement(String tableName, List<String> columns, String conflictKey,
                                         IdentifierCaseSource caseSource) {
        return null;
    }

    /**
     * INSERT 的 <b>identity 包装</b>（O-52 / T-IdentityInsertWrap）：显式把值写进自增列所需的
     * 前置/后置语句（SQL Server 为 {@code SET IDENTITY_INSERT <t> ON/OFF}）。
     *
     * <p>返回 null → <b>本方言无需包装</b>（默认）——调用方按原样渲染 INSERT，
     * 故不含自增列的 INSERT 与全部非包装方言的产物<b>逐字节不变</b>。
     * 非 null → 调用方以返回值整体替换 INSERT 文本（返回值内已含 {@code sql} 原文）。</p>
     *
     * <p><b>判定不在这里</b>：「本次 INSERT 的列清单是否含自增列」由调用方判定（自增标记只有生成侧
     * 持有：CRUD 侧 {@code CrudColumn.autoGenerated}、回滚侧快照登记的 identity 列），本方法只回答
     * 「本方言的包装形态长什么样」。</p>
     *
     * <p>表名走 {@link #quoteIfNeeded}（<b>与 INSERT 基座同一函数、同一 {@code caseSource}</b>）——
     * 包装与 INSERT 里的引用形态因此一致（SQL Server 上两者是不同的语法位，引用形态不一致会指向不同对象）。</p>
     *
     * @param tableName  表名（原始值，实现内按 {@link #quoteIfNeeded} 引用）
     * @param sql        已渲染的 INSERT 文本（不含结尾分号）
     * @param caseSource 表名的大小写来源（O-42/O-51）：语义同 {@link #renderUpsert} 的同名参数
     */
    default String renderInsertWrapper(String tableName, String sql) {
        return renderInsertWrapper(tableName, sql, IdentifierCaseSource.ENTITY);
    }

    /** {@link #renderInsertWrapper(String,String,IdentifierCaseSource)} 的默认来源形态（ENTITY）。 */
    default String renderInsertWrapper(String tableName, String sql, IdentifierCaseSource caseSource) {
        return null;
    }

    /**
     * EXPLAIN 执行计划语句（M37.2 / P-立项-06 N-1）：把查询包装为本方言的 EXPLAIN 形态。
     *
     * <p><b>单一事实源已迁移到 {@link ExplainSupport} 表</b>（T-ExplainChainHardening T-ECH-02 / O-48）：
     * 本方法默认实现读表，各方言<b>不再逐处覆写</b>——此前「没覆写」同时承担「不支持」与「忘了写」
     * 两种含义，且新增方言时无从判定该不该有 EXPLAIN。支持面、输出格式与真机实证出处见
     * {@link ExplainSupport#getEntries()}；渲染产物逐字节不变（模板占位符替换即旧覆写的字符串拼接，
     * {@code DialectExplainTest} 逐方言断言表与实现一致）。</p>
     *
     * <p>返回 null → 本方言不支持（调用方按钮降级提示）。</p>
     *
     * <p>被包语句的合法性由 {@link ExplainGate} 判定（T-ECH-01，两链同调；本方法自身不做首关键字判定——
     * 直接调用者如 MCP 链在调用点接门）。</p>
     *
     * @param sql 不含结尾分号的查询语句
     * @return 可直接提交执行的 EXPLAIN 语句，null = 不支持
     */
    default String renderExplainStatement(String sql) {
        return ExplainSupport.render(getName(), sql);
    }

    /**
     * T-ExplainDbl：幂等 EXPLAIN 包装——文本已以 EXPLAIN 关键字开头（词边界、忽略大小写）时
     * 原样返回（去首尾空白与结尾分号后），否则委托 {@link #renderExplainStatement}。
     *
     * <p>根因场景：首次点击「执行计划」后包装好的 EXPLAIN 语句经执行链写入控制台文档，
     * 再点一次在已包装文本上二次加前缀 → {@code EXPLAIN FORMAT=JSON EXPLAIN FORMAT=JSON SELECT ...}
     * → MySQL 1064 语法错误。</p>
     *
     * <p>被包语句的首关键字边界判定（T-ECH-01）<b>不在此处</b>：本方法返回 null 的语义是「方言不支持」，
     * 与「语句不可 EXPLAIN」需要不同的用户提示，故由调用方（{@code SqlPreviewPanel.explainSql} /
     * {@code SqlVisualizerExplainTool}）先经 {@link ExplainGate#isExplainableSource} 判定后再调用本方法。
     * <b>T-ECH-01 订正（独立复核旁路）</b>：已包装文本（幂等分支）此前<b>无条件</b>原样返回，于是
     * {@code EXPLAIN SELECT 1; DROP TABLE t} 这类「已包装 + 追加语句」的文本绕过调用方的门被整段交回
     * 执行链（IDE 链只有 confirmDollarRawSegments/confirmUnknownParams 两闸，没有 executeSql 的
     * DML 确认闸）——第二条 DDL 会真实执行，正是 D1=A 要堵的形态；且首条包装成功后控制台文档恰是
     * EXPLAIN 文本，用户追加一句即可命中。故已包装文本只在<b>单条语句</b>时原样返回（T-ExplainDbl
     * 防双前缀的既有价值保留），多条一律拒（fail closed）。
     * 注：不复用 {@link ExplainGate#isExplainableSource} 判已包装文本——它认的是被包语句首词，而
     * PG/KingBase/H2/SQLite 的已包装文本首词是包装选项（{@code (FORMAT JSON, VERBOSE)} 等），直接判会
     * 把合法已包装文本误拒，打掉幂等短路本身。</p>
     */
    default String renderExplainStatementIdempotent(String sql) {
        String trimmed = sql.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == ';') end--;
        trimmed = trimmed.substring(0, end).trim();
        if (ExplainGate.startsWithExplainKeyword(trimmed)) {
            if (SqlStatementSplitter.splitStripped(trimmed).size() > 1) {
                return null;
            }
            return trimmed;
        }
        return renderExplainStatement(trimmed);
    }

    /**
     * DELETE 语句是否支持 `LIMIT n` 尾段（O-8 缺陷 C）：MySQL 语法族支持
     * （`DELETE FROM t WHERE ... LIMIT n`），PostgreSQL/Oracle/SQL Server 等不支持——
     * DELETE 的 `last("LIMIT n")` 尾段仅在本方言为 true 时渲染，否则静默省略
     * （与分页子句的方言渲染策略一致）。
     */
    default boolean getSupportsDeleteLimit() {
        return false;
    }

    /**
     * 分页子句是否<b>强制要求语句带 {@code ORDER BY}</b>（默认 false）。
     *
     * <p>T-SqlServerLimitOrder（2026-10-30 真机实证）：SQL Server 的 {@code OFFSET … ROWS FETCH NEXT …} 语法
     * <b>必须</b>跟在 {@code ORDER BY} 之后，否则引擎直接报 {@code Incorrect syntax near '0'}。而渲染链
     * {@code cn.song.mybatisplus.visualizer.sql.SqlGenerator} 只在 {@code orderBy} 非空时才输出 ORDER BY，
     * 于是「无排序 + 分页」的预览产物在 SQL Server 上是<b>非法 T-SQL</b>。</p>
     *
     * <p>旧测试没抓到，是因为 {@code DialectH2VerificationTest} 用 H2 的 {@code MODE=MSSQLServer} 跑——H2 的
     * 兼容模式<b>不强制</b>这条规则（假绿，与 T-OBExplainFormat 同型）。</p>
     *
     * <p>为 true 时，渲染方在 {@code limit != null && orderBy 为空} 时补一个语义中性的
     * {@code ORDER BY (SELECT NULL)}（T-SQL 社区标准写法，表示「不指定顺序」），保证产物可执行。</p>
     */
    default boolean getLimitRequiresOrderBy() {
        return false;
    }

    /**
     * R36（V46 证伪①）：<b>显式事务开始语句</b>（回放脚本的事务包裹用；不含结尾分号）。
     *
     * <p>语义：非 null = 本方言有「显式开启事务」的语句，回放脚本可用 {@code <本值>;}（前）+
     * {@code COMMIT;}（后）包裹；null = <b>本方言无显式事务开始语句</b>——事务随首条 DML 隐式开启，
     * 中途回退须由客户端持有同一连接/会话执行 ROLLBACK（脚本层无从表达）。此时回放脚本
     * <b>诚实省略</b>包裹，UI 禁用该选项并说明原因（不产出 .sql 里必然语法错误的伪包裹）。</p>
     *
     * <p>边界与证据（三档，逐档标级别；核对先例照 O-9 闸门边界）：</p>
     * <ul>
     * <li><b>本机真引擎实测</b>（H2 2.3.232 六模式 REGULAR/MySQL/MariaDB/PostgreSQL/Oracle/
     *    MSSQLServer + SQLite 3.50.3）：两引擎均取 {@code BEGIN}，且<b>语义成立</b>——BEGIN + INSERT +
     *    ROLLBACK → 行不存在；同形 COMMIT → 行存在；对照「不开显式事务时 ROLLBACK 对自动
     *    提交行无效」。两引擎的 {@code START TRANSACTION} 均为语法错误，故不取。</li>
     * <li><b>官方文法（族/兼容面推定）</b>：MySQL/MariaDB 与 OceanBase（D1=A 只接 MySQL 模式）取
     *    {@code START TRANSACTION}；PostgreSQL 族取 {@code BEGIN}（官方 BEGIN [WORK|TRANSACTION]），
     *    KingBase / openGauss 随族取 {@code BEGIN}；SQL Server 取 {@code BEGIN TRANSACTION}。</li>
     * <li><b>判无（保守禁用）</b>：Oracle——{@code BEGIN} 在 Oracle 是 PL/SQL 块起始，SQL Reference 无
     *    {@code BEGIN [TRANSACTION]} 语句形态；DM（达梦）默认 Oracle 兼容模式同形。两款均无本机引擎
     *    → 依据「宁可少包，不可产出语法错误脚本」判无，真机核对后增量。</li>
     * </ul>
     */
    default String getTransactionBegin() {
        return null;
    }

    /**
     * T-ExplainDbl：判断语句是否已以 EXPLAIN 关键字开头——实现已迁至
     * {@link ExplainGate#startsWithExplainKeyword}（T-ECH-01 把两处词法边界判定收口一处），
     * 此处不再保留第二份实现。
     */

    /** 共享渲染辅助：MySQL 形态 {@code LIMIT [offset,] count}（无 offset 时 {@code LIMIT count}）。
     *  供 MySQL / MariaDB / DM 复用。 */
    static String renderMySqlStyleLimit(LimitClause limit) {
        String offsetPart = limit.getOffset() != null ? renderLimitValue(limit.getOffset()) + ", " : "";
        return "LIMIT " + offsetPart + renderLimitValue(limit.getCount());
    }

    /** 共享渲染辅助：PostgreSQL 形态 {@code LIMIT {count} OFFSET {offset}}（无 offset 时仅 {@code LIMIT {count}}）。
     *  供 PostgreSQL / H2 / SQLite / KingBase 复用。 */
    static String renderPgStyleLimit(LimitClause limit) {
        String offsetPart = limit.getOffset() != null ? " OFFSET " + renderLimitValue(limit.getOffset()) : "";
        return "LIMIT " + renderLimitValue(limit.getCount()) + offsetPart;
    }

    /** T-M29-03：分页子句数值渲染——字面量原样、运行时值 {@code ?}（service.page 的 pageNum/pageSize 变量）。 */
    static String renderLimitValue(SqlValue value) {
        if (value instanceof SqlValue.Literal l) return l.getRaw();
        return "?";
    }

    /**
     * 共享渲染辅助：MySQL 形态 {@code ON DUPLICATE KEY UPDATE col = VALUES(col), ...}。
     * 供 MySQL / MariaDB 复用（VALUES() 对 5.7~8.0+/MariaDB 全版本可用）。
     *
     * <p>O-51：列名引用走 {@link #quoteIfNeeded}（而非裸 {@link #quoteIfKeyword}），与 INSERT 基座同一判定；
     * MySQL / MariaDB 为 NONE 族，实体/目录来源产物逐字节不变。</p>
     *
     * <p>T-DialectXcFamily（O-63）扩员：OceanBase（MySQL 模式）与 openGauss 也以本形态为原生 upsert——
     * 前者为 MySQL 兼容面（官方《与 MySQL 兼容性对比》+《唯一键冲突》）；后者<b>真机实证</b>仅本形态可用
     * （openGauss 5.0.0：{@code ON DUPLICATE KEY UPDATE} 解析通过、PG 的 {@code ON CONFLICT} 语法错误，
     * 2026-09-23 DBX 只读通道）——「PG 族=ON CONFLICT」的族推定在 openGauss 不成立（见 {@link OpenGaussDialect}）。</p>
     */
    static String renderMySqlStyleUpsert(Dialect self, List<String> updateColumns,
                                         IdentifierCaseSource caseSource) {
        return "ON DUPLICATE KEY UPDATE " + updateColumns.stream()
                .map(it -> self.quoteIfNeeded(it, caseSource) + " = VALUES(" + self.quoteIfNeeded(it, caseSource) + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * 共享 upsert 辅助：PostgreSQL 形态 {@code ON CONFLICT (pk) DO UPDATE SET col = EXCLUDED.col, ...}。
     * 供 PostgreSQL / H2 / SQLite / KingBase 复用。
     *
     * <p>O-51：列名引用走 {@link #quoteIfNeeded}——PG 族（LOWER）目录来源含大写时必须加引号，否则会产出
     * 「基座加引号、后缀裸写」的半包形态：{@code INSERT INTO "Issue_New" ("Id") ... ON CONFLICT (Id) DO UPDATE
     * SET Id = EXCLUDED.Id} 里的裸写 {@code Id} 被折叠为小写 → 列不存在。SQLite 为 NONE 族，产物逐字节不变。</p>
     */
    static String renderPgStyleUpsert(Dialect self, String conflictKey, List<String> updateColumns,
                                      IdentifierCaseSource caseSource) {
        return "ON CONFLICT (" + self.quoteIfNeeded(conflictKey, caseSource) + ") DO UPDATE SET " +
                updateColumns.stream()
                        .map(it -> self.quoteIfNeeded(it, caseSource) + " = EXCLUDED." + self.quoteIfNeeded(it, caseSource))
                        .collect(Collectors.joining(", "));
    }

    /**
     * identity 包装的<b>共用形态</b>（SQL Server 与 DM8 逐字符相同）：{@code SET IDENTITY_INSERT <表> ON;} +
     * INSERT + {@code SET IDENTITY_INSERT <表> OFF;}。
     *
     * <p>抽成一处而非两处复写：两库语法位与关键字逐字相同（DM8 的报错文案本身就点名
     * {@code SET IDENTITY_INSERT}），复写会让「改一处忘一处」的形态差异无人发现。</p>
     *
     * <p><b>真机证据（2026-09-23，DM8）</b>：DM8 对「显式给自增列赋值」的拒绝与 SQL Server 同形——
     * {@code dm.jdbc.driver.DMException: 仅当指定列列表，且SET IDENTITY_INSERT为ON时，才能对自增列赋值}
     * （vendorCode -2723）；加本包装后同一语句实测<b>成功</b>（{@code SET … ON} → INSERT 影响 1 行 →
     * {@code SET … OFF}），且「不指定自增列」的 INSERT 无需包装即可成功（对照）。SQL Server 侧仍无真机实例
     * （形态取自 DBX {@code d715b16b} 同款实现与 T-SQL 文法），故本函数的<b>共用形态在 DM8 上已真机验证、
     * SQL Server 上仍未验</b>。</p>
     */
    static String identityInsertWrapper(Dialect self, String tableName, String sql,
                                        IdentifierCaseSource caseSource) {
        String ref = self.quoteIfNeeded(tableName, caseSource);
        return "SET IDENTITY_INSERT " + ref + " ON;\n" + sql + "\nSET IDENTITY_INSERT " + ref + " OFF;";
    }

    /**
     * 目录来源名字的按族引用（O-51）：{@link #getIdentifierFolding} 决定「哪种大小写形态必须加引号」。
     *
     * <p>不满足折叠命中条件时落回 {@link #quoteIfKeyword}——<b>保留字命中即包的既有行为不变</b>
     * （Oracle 的 {@code NUMBER} / {@code LEVEL}、PG 的 {@code table} 等仍包）。</p>
     */
    private static String quoteCatalogName(Dialect self, String identifier) {
        switch (self.getIdentifierFolding()) {
            case UPPER:
                return containsLowerCase(identifier)
                        ? self.quoteIdentifier(identifier)
                        : self.quoteIfKeyword(identifier);
            case LOWER:
                return containsUpperCase(identifier)
                        ? self.quoteIdentifier(identifier)
                        : self.quoteIfKeyword(identifier);
            case NONE:
            default:
                return self.quoteIfKeyword(identifier);
        }
    }

    /** Kotlin {@code identifier.any { it.isLowerCase() }} 等价。 */
    private static boolean containsLowerCase(String identifier) {
        for (int i = 0; i < identifier.length(); i++) {
            if (Character.isLowerCase(identifier.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Kotlin {@code identifier.any { it.isUpperCase() }} 等价。 */
    private static boolean containsUpperCase(String identifier) {
        for (int i = 0; i < identifier.length(); i++) {
            if (Character.isUpperCase(identifier.charAt(i))) {
                return true;
            }
        }
        return false;
    }

}
