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
 * 证据层级三态口径（2026-09-23）：
 *  - **openGauss：引擎级**（DBX MCP 只读通道，`GaussDB / openGauss 5.0.0`，dbx-test-host:20105/dbx_test，
 *    逐条只读探针）——
 *      - `SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES` → 455 行（裸大写折为小写命中）；
 *        `FROM information_schema."TABLES"` → `relation "information_schema.TABLES" does not exist on gaussdb`
 *        （引号保留大小写 → 大写名不存在）→ 折叠方向 **LOWER** 实锤；
 *      - ``SELECT 1 AS `x` `` → `syntax error at or near "`"` → 反引号不可用，双引号族实锤；
 *      - `EXPLAIN (FORMAT JSON) SELECT 1` → 单列 `QUERY PLAN` JSON 文本 → JSON 格式实锤；
 *        `EXPLAIN SELECT 1` → 文本计划树（默认 TEXT）；
 *      - `SELECT 1 LIMIT 1 OFFSET 0` → 1 行；`SELECT 1 LIMIT 1, 2` → **0 行**（按 PG 语义解析为
 *        OFFSET 1 LIMIT 2——MySQL 形态 LIMIT 会被静默错解，故必须渲染 PG 形态）；
 *      - upsert 对照对（EXPLAIN 不执行只规划，幻表名规划期即失败、语法期错误可区分）：
 *        `EXPLAIN INSERT INTO __no_such_table__ ... ON DUPLICATE KEY UPDATE name = VALUES(name)`
 *        → `relation ... does not exist`（**语法解析通过**）；同语句改 `ON CONFLICT (id) DO UPDATE SET
 *        name = EXCLUDED.name` → `syntax error at or near "CONFLICT ("` → openGauss 的 upsert
 *        **仅 MySQL 形态可用**（与 KingBase=PG 形态先例相反，照抄会产出语法错误 SQL）；
 *  - **OceanBase：文档级**（无真机实例——环境受限未验证，真机留账）——
 *      - JDBC URL `jdbc:oceanbase://host:port/db`：OceanBase Connector/J 官方《Database URL》；
 *      - EXPLAIN：官方 V4.x《SQL 执行计划简介》`EXPLAIN [explain_type] dml_statement`，
 *        `FORMAT = {TRADITIONAL | JSON}`，缺省 TRADITIONAL 表格行 → 同 MySQL 形态；
 *      - MySQL 5.7/8.0 兼容性声明（官方《与 MySQL 兼容性对比》）覆盖反引号 / LIMIT / `ON DUPLICATE KEY
 *        UPDATE`（官方《唯一键冲突》）——形态推定为 MySQL 族，真机核对后增量；
 *  - **GaussDB `jdbc:gaussdb://` 前缀：文档级**（华为 GaussDB 官方《Creating a Database Connection》），
 *    与 `jdbc:opengauss://` 同归 [OpenGaussDialect]（D2=A 定案）。
 *
 * 反向验证夹具：`注册表身份_摘掉注册即红` 断言 `DialectRegistry.byName` 命中本方言对象与推断链
 * 全链落点——摘掉注册后 byName 回落 [MySqlDialect]、推断链值失配，本文件红灯（红/绿读数见计划 §七）。
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
        // PG 族无 DELETE LIMIT 尾段（真机佐证：LIMIT 1,2 按 PG 语义解析——DELETE LIMIT 非 PG 文法）
        assertFalse(OpenGaussDialect.supportsDeleteLimit)
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

    // ---------- 注册表 + URL 推断全链（反向验证的绿态基准） ----------

    @Test
    fun `注册表身份_摘掉注册即红`() {
        // byName 未知名回落 MySQL——本断言在「摘掉注册」反向验证中必红（回落即身份失配）
        assertSame(OceanBaseDialect, DialectRegistry.byName("OceanBase"))
        assertSame(OpenGaussDialect, DialectRegistry.byName("openGauss"))
    }

    @Test
    fun `url推断_三前缀命中且全链落到本方言对象`() {
        // 前缀：oceanbase（OB 官方）+ opengauss（openGauss 官方）+ gaussdb（华为 GaussDB 官方，D2=A 同 object）
        assertEquals("OceanBase", UrlSubprotocolDialects.dialectName("jdbc:oceanbase://host:2881/db"))
        assertEquals("openGauss", UrlSubprotocolDialects.dialectName("jdbc:opengauss://host:5432/db"))
        assertEquals("openGauss", UrlSubprotocolDialects.dialectName("jdbc:gaussdb://host:8000/db"))
        // 全链：推断名 → 注册表命中本方言对象（摘掉任何一环：URL 表或注册表，链路即在此断）
        val ob = DialectInference.infer("jdbc:oceanbase://host:2881/db", null)
        assertSame(OceanBaseDialect, DialectRegistry.byName(ob.dialectName))
        val og = DialectInference.infer(null, "jdbc:opengauss://host:5432/db")
        assertSame(OpenGaussDialect, DialectRegistry.byName(og.dialectName))
        val gdb = DialectInference.infer(null, "jdbc:gaussdb://host:8000/db")
        assertSame(OpenGaussDialect, DialectRegistry.byName(gdb.dialectName))
        // 大小写不敏感既有口径
        assertEquals("openGauss", UrlSubprotocolDialects.dialectName("JDBC:OpenGauss://h/db"))
    }

    @Test
    fun `url推断_未知前缀仍不猜`() {
        // 既有「未收录=不猜」口径不被新前缀稀释
        assertNull(UrlSubprotocolDialects.dialectName("jdbc:tidb://host:4000/db"))
    }
}
