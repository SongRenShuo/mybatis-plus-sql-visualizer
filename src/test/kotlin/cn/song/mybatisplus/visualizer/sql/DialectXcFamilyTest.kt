package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-DialectXcFamily（O-63）：信创方言补全族（OceanBase + openGauss）单测。
 *
 * 证据层级三态口径（2026-09-23 首测，2026-10-29 T-GaussDbDialect 复核并订正一处）：
 *  - **openGauss：引擎级**（DBX MCP 只读通道，**openGauss 5.0.0 社区版**，dbx-test-host:20105/dbx_test，
 *    `sql_compatibility = 'A'`，逐条只读探针）——
 *      - `SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES` → 455 行（裸大写折为小写命中）；
 *        `FROM information_schema."TABLES"` → `relation "information_schema.TABLES" does not exist on gaussdb`
 *        （引号保留大小写 → 大写名不存在）→ 折叠方向 **LOWER** 实锤；
 *      - ``SELECT 1 AS `x` `` → `syntax error at or near "`"` → 反引号不可用，双引号族实锤；
 *      - `EXPLAIN (FORMAT JSON) SELECT 1` → 单列 `QUERY PLAN` JSON 文本 → JSON 格式实锤；
 *        `EXPLAIN SELECT 1` → 文本计划树（默认 TEXT）；
 *      - **LIMIT 逗号形态（券 03 订正）**：`LIMIT a, b` 是**原生 MySQL 语义**（offset, count），
 *        **不存在静默错解**。多行源实测 `SELECT * FROM generate_series(1,5) LIMIT 1, 2` → 返回
 *        `2, 3`；真实 455 行源 `SELECT table_name FROM information_schema.tables LIMIT 1, 2` →
 *        第 2、3 行。原记录「`SELECT 1 LIMIT 1, 2` → 0 行，按 PG 语义解析被静默错解」是**假绿**：
 *        单行源上「MySQL 语义」与「PG 语义（OFFSET 1 LIMIT 2）」**给出同一读数 0 行**，两种假设
 *        不可区分。渲染 PG 形态的理由改述为「两解同义、消除歧义」；
 *      - `DELETE … LIMIT n`（券 02）：`EXPLAIN DELETE FROM db4ai.snapshot WHERE id = 1 LIMIT 1` →
 *        `permission denied for relation snapshot`（已过语法与分析阶段；同库 `ON CONFLICT` 对照组
 *        直接报 `syntax error`，证明通道能区分）→ openGauss **接受**该语法；是否真的限行**未验证**；
 *      - upsert 对照对（EXPLAIN 不执行只规划，幻表名规划期即失败、语法期错误可区分）：
 *        `EXPLAIN INSERT INTO __no_such_table__ ... ON DUPLICATE KEY UPDATE name = VALUES(name)`
 *        → `relation ... does not exist`（**语法解析通过**）；同语句改 `ON CONFLICT (id) DO UPDATE SET
 *        name = EXCLUDED.name` → `syntax error at or near "CONFLICT ("` → openGauss 的 upsert
 *        **仅 MySQL 形态可用**（与 KingBase=PG 形态先例相反，照抄会产出语法错误 SQL）；
 *  - **保留字：引擎级**（券 01）：`pg_get_keywords()` 653 词，`R`=93 / `T`=27 / `C`=63；
 *    `SELECT 1 AS rownum` → `ROWNUM cannot be used as an alias`（旧 `PG_RESERVED` 基线漏包）；
 *  - **OceanBase：文档级**（无真机实例——环境受限未验证，真机留账）——
 *      - JDBC URL `jdbc:oceanbase://host:port/db`：OceanBase Connector/J 官方《Database URL》；
 *      - EXPLAIN：官方 V4.x《SQL 执行计划简介》`EXPLAIN [explain_type] dml_statement`，
 *        `FORMAT = {TRADITIONAL | JSON}`，缺省 TRADITIONAL 表格行 → 同 MySQL 形态；
 *      - MySQL 5.7/8.0 兼容性声明（官方《与 MySQL 兼容性对比》）覆盖反引号 / LIMIT / `ON DUPLICATE KEY
 *        UPDATE`（官方《唯一键冲突》）——形态推定为 MySQL 族，真机核对后增量；
 *  - **GaussDB：文档级**（华为云 GaussDB 集中式 V2.0-10.x SQL 参考 + 官方驱动表
 *    `jdbc:gaussdb://`）。**T-GaussDbDialect（券 04）起不再与 `jdbc:opengauss://` 共用
 *    [OpenGaussDialect]**——D2=A 已撤销，理由见 [GaussDbDialect] 的 KDoc（形态不分叉、
 *    保留字与身份分叉）。GaussDB 商用版无公开容器镜像，无真机。
 *
 * 反向验证夹具（两次，读数见计划 §七）：`注册表身份_摘掉注册即红` 断言 `DialectRegistry.byName`
 * 命中本方言对象与推断链全链落点——摘掉注册后 byName 回落 [MySqlDialect]、推断链值失配；
 * `openGauss保留字_真机黄金清单逐词一致` 摘掉任一保留字即红。
 */
class DialectXcFamilyTest {

    // ---------- OceanBase（MySQL 模式族：backtick / NONE 折叠 / LIMIT n,m / ON DUPLICATE KEY UPDATE） ----------

    @Test
    fun `oceanbase_分页_MySQL形态`() {
        assertEquals("LIMIT 10", OceanBaseDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 5, 10", OceanBaseDialect.renderLimit(LimitClause(offset = 5, count = 10)))
        // 运行时占位符（T-M29-03 形态）
        assertEquals(
            "LIMIT ?, ?",
            OceanBaseDialect.renderLimit(
                LimitClause(offset = SqlValue.Placeholder("(pageNum - 1) * pageSize"), count = SqlValue.Placeholder("pageSize")),
            ),
        )
        // DELETE LIMIT 尾段：MySQL 语法族（官方兼容性声明覆盖）
        assertTrue(OceanBaseDialect.supportsDeleteLimit)
    }

    @Test
    fun `oceanbase_引用与保留字`() {
        // 反引号（MySQL 模式兼容面，官方《与 MySQL 兼容性对比》——文档级）
        assertEquals("`id`", OceanBaseDialect.quoteIdentifier("id"))
        assertEquals("`order`", OceanBaseDialect.quoteIfKeyword("order"))
        // 折叠方向推定 NONE：目录来源不按大小写判定（保留字行为不变）
        assertEquals(IdentifierFolding.NONE, OceanBaseDialect.identifierFolding)
        assertEquals("Issue_New", OceanBaseDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.CATALOG))
        assertEquals("Issue_New", OceanBaseDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.ENTITY))
    }

    @Test
    fun `oceanbase_upsert_MySQL形态`() {
        assertEquals(
            "ON DUPLICATE KEY UPDATE name = VALUES(name)",
            OceanBaseDialect.renderUpsert("id", listOf("name")),
        )
        assertEquals(
            "ON DUPLICATE KEY UPDATE `table` = VALUES(`table`), name = VALUES(name)",
            OceanBaseDialect.renderUpsert("id", listOf("table", "name")),
        )
    }

    // ---------- openGauss（PG 族：双引号 / LOWER 折叠 / LIMIT..OFFSET；upsert 特例=MySQL 形态） ----------

    @Test
    fun `opengauss_分页_PG形态`() {
        assertEquals("LIMIT 10", OpenGaussDialect.renderLimit(LimitClause(count = 10)))
        assertEquals("LIMIT 10 OFFSET 5", OpenGaussDialect.renderLimit(LimitClause(offset = 5, count = 10)))
        assertEquals(
            "LIMIT ? OFFSET ?",
            OpenGaussDialect.renderLimit(
                LimitClause(offset = SqlValue.Placeholder("(pageNum - 1) * pageSize"), count = SqlValue.Placeholder("pageSize")),
            ),
        )
        // 券 02：DELETE 的 LIMIT 尾段**显式判无**（非遗漏）。语法层真机实测是「接受」的
        // （`EXPLAIN DELETE … LIMIT 1` 只报 permission denied，同库 ON CONFLICT 对照组直接报
        // syntax error，证明通道能区分），但语义是否真的限行未验证（实例非超管、无用户表）。
        // 取舍不对称：渲染了而不生效 → 用户以为删除有界而实际无界；不渲染只是少一个尾段。
        assertFalse(OpenGaussDialect.supportsDeleteLimit)
    }

    /**
     * 券 03：分页只产 PG 形态，不产 MySQL 的逗号形态。
     *
     * 为什么这条要写成断言而不是只留注释：openGauss 的 `LIMIT a, b` 实测是**原生 MySQL 语义**
     * （offset, count），不是错解；但它与 PG 的 `OFFSET a LIMIT b` 语义相同而写法相反，渲染成
     * 逗号形态会让读者按 PG 语义理解反。故钉死「只产出 `LIMIT n OFFSET m`」，改成逗号形态即红。
     *
     * 方法学警示（写在这里防止下一次重犯）：原「openGauss 会静默错解 LIMIT 逗号形态」的结论来自
     * 单行源探针 `SELECT 1 LIMIT 1, 2` → 0 行——**单行源上「MySQL 语义（跳过 1 行取 2 行）」与
     * 「PG 语义（OFFSET 1 LIMIT 2）」给出同一读数**，两种假设不可区分，属假绿。语义类探针必须用
     * 多行源：`SELECT * FROM generate_series(1,5) LIMIT 1, 2` → `2, 3`。
     */
    @Test
    fun `opengauss_分页_只产PG形态不产逗号形态`() {
        val rendered = OpenGaussDialect.renderLimit(LimitClause(offset = 5, count = 10))
        assertEquals("LIMIT 10 OFFSET 5", rendered)
        assertFalse("不得产出 MySQL 的 LIMIT a, b 逗号形态：$rendered", rendered.contains(','))

        val runtime = OpenGaussDialect.renderLimit(
            LimitClause(offset = SqlValue.Placeholder("(pageNum - 1) * pageSize"), count = SqlValue.Placeholder("pageSize")),
        )
        assertEquals("LIMIT ? OFFSET ?", runtime)
        assertFalse("运行时占位符形态同样不得含逗号：$runtime", runtime.contains(','))
    }

    @Test
    fun `opengauss_引用与折叠_LOWER族`() {
        // 双引号（真机：反引号语法错误）
        assertEquals("\"id\"", OpenGaussDialect.quoteIdentifier("id"))
        assertEquals("\"table\"", OpenGaussDialect.quoteIfKeyword("table"))
        // 折叠方向 LOWER（真机 455 行 / relation does not exist 实锤）
        assertEquals(IdentifierFolding.LOWER, OpenGaussDialect.identifierFolding)
        // 目录来源：含大写必须包（裸写被折为小写 → 查不到）；全小写裸写
        assertEquals("\"Issue_New\"", OpenGaussDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.CATALOG))
        assertEquals("issue_new", OpenGaussDialect.quoteIfNeeded("issue_new", IdentifierCaseSource.CATALOG))
        // 实体来源逐字不变（O-51 族判定只许在 CATALOG 分支）
        assertEquals("Issue_New", OpenGaussDialect.quoteIfNeeded("Issue_New", IdentifierCaseSource.ENTITY))
    }

    /**
     * openGauss upsert = **MySQL 形态**（真机对照对实锤：`ON DUPLICATE KEY UPDATE` 解析通过、
     * PG 的 `ON CONFLICT` 语法错误）。与 KingBase 同为 PG 系但 upsert 形态相反——
     * 「PG 族=ON CONFLICT」的族推定在本方言被打脸，本用例把真机结论钉死。
     */
    @Test
    fun `opengauss_upsert_MySQL形态_非PG_ON_CONFLICT`() {
        assertEquals(
            "ON DUPLICATE KEY UPDATE name = VALUES(name)",
            OpenGaussDialect.renderUpsert("id", listOf("name")),
        )
        // 保留字列用本方言符号（双引号），非反引号
        assertEquals(
            "ON DUPLICATE KEY UPDATE \"table\" = VALUES(\"table\"), id = VALUES(id)",
            OpenGaussDialect.renderUpsert("id", listOf("table", "id")),
        )
        // 整语句形态（H2 那种）不存在
        assertNull(OpenGaussDialect.renderUpsertStatement("t", listOf("id", "name"), "id"))
    }

    // ---------- GaussDB 独立方言（券 04）：形态与 openGauss 逐字节一致，保留字与身份分叉 ----------

    /**
     * 券 04 的核心断言：**形态不分叉**。
     *
     * 拆出 [GaussDbDialect] 的理由是保留字与身份，不是渲染形态。本用例把「七项形态两边一致」
     * 钉成机器约束——任何人给 GaussDbDialect 单独改分页/引号/折叠/upsert/事务/DELETE-LIMIT 的
     * 实现，导致与 openGauss 分叉，这里立刻红。这样「形态复用」是**被验证的事实**，
     * 而不是 KDoc 里的一句声明。
     */
    @Test
    fun `gaussdb_形态与openGauss逐项一致`() {
        listOf(
            LimitClause(count = 10),
            LimitClause(offset = 5, count = 10),
            LimitClause(offset = SqlValue.Placeholder("offset"), count = SqlValue.Placeholder("size")),
        ).forEach { limit ->
            assertEquals("分页形态应与 openGauss 一致：$limit", OpenGaussDialect.renderLimit(limit), GaussDbDialect.renderLimit(limit))
        }
        assertEquals(OpenGaussDialect.quoteIdentifier("Mixed_Case"), GaussDbDialect.quoteIdentifier("Mixed_Case"))
        assertEquals(OpenGaussDialect.identifierQuotes, GaussDbDialect.identifierQuotes)
        assertEquals(OpenGaussDialect.identifierFolding, GaussDbDialect.identifierFolding)
        assertEquals(OpenGaussDialect.supportsDeleteLimit, GaussDbDialect.supportsDeleteLimit)
        assertEquals(OpenGaussDialect.transactionBegin, GaussDbDialect.transactionBegin)
        assertEquals(
            OpenGaussDialect.renderUpsert("id", listOf("name")),
            GaussDbDialect.renderUpsert("id", listOf("name")),
        )
        assertNull(GaussDbDialect.renderUpsertStatement("t", listOf("id", "name"), "id"))
        assertNull("GaussDB 不需要 identity 包装（无 SET IDENTITY_INSERT 形态）", GaussDbDialect.renderInsertWrapper("t", "INSERT INTO t VALUES (?)"))
        // 目录来源按族引用（LOWER 族）一致
        listOf("Issue_New", "issue_new", "order").forEach { name ->
            assertEquals(
                "目录名引用应与 openGauss 一致：$name",
                OpenGaussDialect.quoteIfNeeded(name, IdentifierCaseSource.CATALOG),
                GaussDbDialect.quoteIfNeeded(name, IdentifierCaseSource.CATALOG),
            )
        }
    }

    /** 券 04：保留字与身份**必须**分叉——这是拆方言的唯一实质理由。 */
    @Test
    fun `gaussdb_保留字与身份独立于openGauss`() {
        assertEquals("GaussDB", GaussDbDialect.name)
        // 保留字是超集（GaussDB 官方页保留列比 openGauss 多 11 词）
        assertTrue("GaussDB 保留字应包含 openGauss 全量", GaussDbDialect.reservedWords.containsAll(OpenGaussDialect.reservedWords))
        assertTrue("GaussDB 应有 openGauss 没有的保留字", GaussDbDialect.reservedWords.size > OpenGaussDialect.reservedWords.size)
        // 抽样：GaussDB 独有保留字必须被引用（openGauss 侧不引用，两边产物因此不同）
        listOf("regexp", "rlike", "crossbucket", "sessiontimezone").forEach { word ->
            assertEquals("\"$word\"", GaussDbDialect.quoteIfKeyword(word))
            assertEquals("openGauss 不应引用 GaussDB 独有保留字 $word", word, OpenGaussDialect.quoteIfKeyword(word))
        }
    }

    // ---------- EXPLAIN 渲染（模板经 ExplainSupport 表） ----------

    @Test
    fun `explain渲染_OceanBase同MySQL形态`() {
        val sample = "SELECT * FROM users WHERE id > 1"
        assertEquals("EXPLAIN $sample", OceanBaseDialect.renderExplainStatement(sample))
        assertEquals(ExplainOutputFormat.TABLE, ExplainSupport.formatFor("OceanBase"))
    }

    @Test
    fun `explain渲染_openGauss_KingBase同款JSON形态`() {
        val sample = "SELECT * FROM users WHERE id > 1"
        assertEquals("EXPLAIN (FORMAT JSON) $sample", OpenGaussDialect.renderExplainStatement(sample))
        assertEquals(ExplainOutputFormat.JSON, ExplainSupport.formatFor("openGauss"))
        // 幂等包装（T-ExplainDbl）在新方言同样成立
        assertEquals(
            "EXPLAIN (FORMAT JSON) $sample",
            OpenGaussDialect.renderExplainStatementIdempotent("EXPLAIN (FORMAT JSON) $sample"),
        )
    }

    /** 券 04：GaussDB 的 EXPLAIN 与 openGauss 同形（官方文档级；真机留账）。 */
    @Test
    fun `explain渲染_gaussdb同款JSON形态`() {
        val sample = "SELECT * FROM users WHERE id > 1"
        assertEquals("EXPLAIN (FORMAT JSON) $sample", GaussDbDialect.renderExplainStatement(sample))
        assertEquals(ExplainOutputFormat.JSON, ExplainSupport.formatFor("GaussDB"))
        assertEquals(
            "EXPLAIN (FORMAT JSON) $sample",
            GaussDbDialect.renderExplainStatementIdempotent("EXPLAIN (FORMAT JSON) $sample"),
        )
        // 与 openGauss 逐字节一致（形态不分叉）
        assertEquals(OpenGaussDialect.renderExplainStatement(sample), GaussDbDialect.renderExplainStatement(sample))
    }

    // ---------- 注册表 + URL 推断全链（反向验证的绿态基准） ----------

    @Test
    fun `注册表身份_摘掉注册即红`() {
        // byName 未知名回落 MySQL——本断言在「摘掉注册」反向验证中必红（回落即身份失配）
        assertSame(OceanBaseDialect, DialectRegistry.byName("OceanBase"))
        assertSame(OpenGaussDialect, DialectRegistry.byName("openGauss"))
        assertSame(GaussDbDialect, DialectRegistry.byName("GaussDB"))
    }

    @Test
    fun `url推断_三前缀命中且全链落到本方言对象`() {
        // 前缀：oceanbase（OB 官方）+ opengauss（openGauss 官方）+ gaussdb（华为 GaussDB 官方）。
        // T-GaussDbDialect（券 04）：gaussdb 不再与 opengauss 同 object，落到独立的 GaussDbDialect。
        assertEquals("OceanBase", UrlSubprotocolDialects.dialectName("jdbc:oceanbase://host:2881/db"))
        assertEquals("openGauss", UrlSubprotocolDialects.dialectName("jdbc:opengauss://host:5432/db"))
        assertEquals("GaussDB", UrlSubprotocolDialects.dialectName("jdbc:gaussdb://host:8000/db"))
        // 全链：推断名 → 注册表命中本方言对象（摘掉任何一环：URL 表或注册表，链路即在此断）
        val ob = DialectInference.infer("jdbc:oceanbase://host:2881/db", null)
        assertSame(OceanBaseDialect, DialectRegistry.byName(ob.dialectName))
        val og = DialectInference.infer(null, "jdbc:opengauss://host:5432/db")
        assertSame(OpenGaussDialect, DialectRegistry.byName(og.dialectName))
        val gdb = DialectInference.infer(null, "jdbc:gaussdb://host:8000/db")
        assertSame(GaussDbDialect, DialectRegistry.byName(gdb.dialectName))
        // 大小写不敏感既有口径
        assertEquals("openGauss", UrlSubprotocolDialects.dialectName("JDBC:OpenGauss://h/db"))
        assertEquals("GaussDB", UrlSubprotocolDialects.dialectName("JDBC:GaussDB://h/db"))
    }

    @Test
    fun `url推断_未知前缀仍不猜`() {
        // 既有「未收录=不猜」口径不被新前缀稀释
        assertNull(UrlSubprotocolDialects.dialectName("jdbc:tidb://host:4000/db"))
    }
}
