package cn.song.mybatisplus.visualizer.mcp

import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-McpToolset 只读白名单守卫单测（纯逻辑层，无平台/桩 classpath 依赖）。
 *
 * 覆盖计划 §五越权用例全拒矩阵：UPDATE / DDL / 多语句夹带 / 非白名单形态 / 解析失败 fail closed，
 * 以及合法 SELECT（含 CTE / UNION / ORDER BY DESC / REPLACE() 函数）放行。
 */
class McpSelectGuardTest {

    private fun assertDenied(sql: String?, code: String, message: String) {
        val verdict = McpSelectGuard.checkSingleSelect(sql)
        assertFalse(message, verdict.allowed)
        assertEquals(message, code, verdict.errorCode)
        assertTrue(message, !verdict.reason.isNullOrBlank())
    }

    private fun assertAllowed(sql: String) {
        val verdict = McpSelectGuard.checkSingleSelect(sql)
        assertTrue("应放行: $sql → ${verdict.errorCode}: ${verdict.reason}", verdict.allowed)
        assertNull(verdict.errorCode)
        assertNull(verdict.reason)
    }

    // ------------------------------------------------------------------
    // 合法 SELECT 放行
    // ------------------------------------------------------------------

    @Test
    fun `放行_单条普通SELECT`() {
        assertAllowed("SELECT * FROM user WHERE id = 1")
        assertAllowed("select name, age from t_user limit 10")
    }

    @Test
    fun `放行_CTE与UNION`() {
        assertAllowed("WITH c AS (SELECT id FROM a) SELECT * FROM c")
        assertAllowed("SELECT id FROM a UNION SELECT id FROM b ORDER BY id DESC")
    }

    @Test
    fun `放行_ORDERBY_DESC与REPLACE函数不误杀`() {
        assertAllowed("SELECT * FROM t ORDER BY created_at DESC")
        assertAllowed("SELECT REPLACE(name, 'a', 'b') FROM t")
    }

    @Test
    fun `放行_含子查询的SELECT`() {
        assertAllowed("SELECT * FROM t WHERE id IN (SELECT id FROM other)")
    }

    // ------------------------------------------------------------------
    // 写语句全拒（MCP_READ_ONLY）
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_UPDATE`() {
        assertDenied("UPDATE user SET name = 'x' WHERE id = 1", McpToolErrors.READ_ONLY, "UPDATE 应拒")
        assertDenied("update t set a = 1", McpToolErrors.READ_ONLY, "小写 update 应拒")
    }

    @Test
    fun `拒绝_INSERT_DELETE_MERGE`() {
        assertDenied("INSERT INTO t(a) VALUES(1)", McpToolErrors.READ_ONLY, "INSERT 应拒")
        assertDenied("DELETE FROM t WHERE id = 1", McpToolErrors.READ_ONLY, "DELETE 应拒")
        assertDenied("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = 1", McpToolErrors.READ_ONLY, "MERGE 应拒")
    }

    @Test
    fun `拒绝_DDL`() {
        assertDenied("CREATE TABLE t(id INT)", McpToolErrors.READ_ONLY, "CREATE 应拒")
        assertDenied("ALTER TABLE t ADD COLUMN c INT", McpToolErrors.READ_ONLY, "ALTER 应拒")
        assertDenied("DROP TABLE t", McpToolErrors.READ_ONLY, "DROP 应拒")
        assertDenied("TRUNCATE TABLE t", McpToolErrors.READ_ONLY, "TRUNCATE 应拒")
    }

    @Test
    fun `拒绝_会话与权限语句`() {
        assertDenied("GRANT SELECT ON t TO u", McpToolErrors.READ_ONLY, "GRANT 应拒")
        assertDenied("COMMIT", McpToolErrors.READ_ONLY, "COMMIT 应拒")
        assertDenied("USE other_db", McpToolErrors.READ_ONLY, "USE 应拒")
        assertDenied("SET @v = 1", McpToolErrors.READ_ONLY, "SET 应拒")
    }

    // ------------------------------------------------------------------
    // 语句形态拒绝（SQL_BLOCKED）
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_多语句夹带`() {
        assertDenied(
            "SELECT 1; DROP TABLE t",
            McpToolErrors.SQL_BLOCKED,
            "分号多语句应拒",
        )
        assertDenied(
            "SELECT 1;; SELECT 2",
            McpToolErrors.SQL_BLOCKED,
            "双分号夹带应拒",
        )
    }

    @Test
    fun `拒绝_锁读_FOR_UPDATE`() {
        assertDenied("SELECT * FROM t WHERE id = 1 FOR UPDATE", McpToolErrors.SQL_BLOCKED, "FOR UPDATE 应拒")
        assertDenied("SELECT * FROM t FOR SHARE", McpToolErrors.SQL_BLOCKED, "FOR SHARE 应拒")
        assertDenied("SELECT * FROM t LOCK IN SHARE MODE", McpToolErrors.SQL_BLOCKED, "共享锁应拒")
    }

    @Test
    fun `拒绝_SELECT_INTO落盘`() {
        assertDenied("SELECT * FROM t INTO OUTFILE '/tmp/x'", McpToolErrors.SQL_BLOCKED, "INTO OUTFILE 应拒")
        assertDenied("SELECT * FROM t INTO DUMPFILE '/tmp/x'", McpToolErrors.SQL_BLOCKED, "INTO DUMPFILE 应拒")
    }

    @Test
    fun `拒绝_解析失败fail_closed`() {
        assertDenied("SELEC * FORM t", McpToolErrors.SQL_BLOCKED, "语法错误应按写处理")
        assertDenied(")))", McpToolErrors.SQL_BLOCKED, "乱码应拒")
    }

    // ------------------------------------------------------------------
    // 入参校验（INVALID_ARGUMENT）
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_空入参`() {
        assertDenied(null, McpToolErrors.INVALID_ARGUMENT, "null 应拒")
        assertDenied("", McpToolErrors.INVALID_ARGUMENT, "空串应拒")
        assertDenied("   ", McpToolErrors.INVALID_ARGUMENT, "空白应拒")
    }

    @Test
    fun `拒绝_超长输入`() {
        val big = "SELECT " + "x".repeat(McpSelectGuard.MAX_SQL_LENGTH + 1)
        assertDenied(big, McpToolErrors.INVALID_ARGUMENT, "超长应拒")
    }

    // ------------------------------------------------------------------
    // 词边界防误伤
    // ------------------------------------------------------------------

    @Test
    fun `标识符含保留字子串不误杀`() {
        assertAllowed("SELECT last_update, update_time, updatex FROM t")
    }

    @Test
    fun `字符串字面量含保留字不误杀_结构判定兜底`() {
        // 字面量剥离后粗扫（'update' 在引号内）；整条仍是单条 SELECT → 放行
        assertAllowed("SELECT * FROM t WHERE remark LIKE '%update%'")
        assertAllowed("SELECT * FROM t WHERE name = 'drop table x'")
    }

    // ------------------------------------------------------------------
    // T-ExplainChainHardening T-ECH-04：analyze 双守卫防回退 + 与 IDE 链白名单对齐
    // ------------------------------------------------------------------

    @Test
    fun `拒绝_analyze 与 EXPLAIN ANALYZE 不弱化`() {
        // 守卫一（结构判定）：JSqlParser 5.3 把 EXPLAIN ANALYZE 解析为 ExplainStatement（非 Select）
        // → READ_ONLY 拒（实测读数见计划 §八）
        assertDenied(
            "EXPLAIN ANALYZE SELECT * FROM t",
            McpToolErrors.READ_ONLY,
            "EXPLAIN ANALYZE 应拒（analyze 会真实执行被修饰语句）",
        )
        // 守卫二（词面粗扫）：analyze 在 FORBIDDEN_WORDS，出现在查询正文同样拒（fail closed）
        assertDenied(
            "SELECT 1 FROM t WHERE analyze = 1",
            McpToolErrors.READ_ONLY,
            "analyze 词面命中即拒",
        )
        // 括号选项变体解析失败 → SQL_BLOCKED（同样拒；fail closed 两条路径任一都不放行）
        assertDenied(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM t",
            McpToolErrors.SQL_BLOCKED,
            "EXPLAIN (ANALYZE, …) 变体应拒",
        )
    }

    @Test
    fun `放行_可 EXPLAIN 的非 SELECT 形态（与 IDE 链白名单对齐）`() {
        // T-ECH-01 白名单含 VALUES/TABLE——本守卫结构判定不得比它更严，否则两链行为分叉
        // （计划 §八 待验证前提 2：实测 Values/TableStatement 均为 Select 子类 → 不过严）
        assertAllowed("VALUES (1)")
        assertAllowed("TABLE users")
    }

    @Test
    fun `拒绝_首词前缀形态标识符（与 T-ECH-01 门同向）`() {
        // 词法门（ExplainGate）与结构守卫（本类）对同一形态同向拒绝，两链不分叉
        assertDenied("SELECTED x FROM t", McpToolErrors.SQL_BLOCKED, "SELECTED 应拒")
        assertDenied("SELECT_foo FROM t", McpToolErrors.SQL_BLOCKED, "SELECT_foo 应拒")
    }

    // ------------------------------------------------------------------
    // T-SelectGuardScalarExempt（O-61）T-SGE-01/02：INSERT() 标量函数豁免
    // ------------------------------------------------------------------

    @Test
    fun `放行_INSERT串函数形态豁免（T-SGE-02 先红）`() {
        // MySQL INSERT(str,pos,len,newstr) 串函数——改前粗扫 \binsert\b 命中函数名误拒 READ_ONLY
        assertAllowed("SELECT INSERT('hello', 1, 0, 'X')")
        // DBX #69 同款判据含「可选空白」：INSERT ( 与 INSERT( 都豁免
        assertAllowed("SELECT INSERT (name, 1, 2, 'x') FROM t")
    }

    @Test
    fun `放行_TRUNCATE数学函数形态豁免（T-SGE-02 扩表 先红）`() {
        // §七 前提 2 全表排查唯一的第三个同形词：MySQL TRUNCATE(X,D) 数学函数（12.6.2）
        // 改前粗扫 \btruncate\b 命中函数名误拒 READ_ONLY（TRUNCATE TABLE 写语句由结构闸照拒）
        assertAllowed("SELECT TRUNCATE(price, 2) FROM orders")
        assertAllowed("SELECT TRUNCATE (x, 0) FROM t")
    }

    @Test
    fun `放行_insert前缀词不误杀（T-SGE-02 相邻形态）`() {
        // \b 词边界既有行为：前缀/后缀相连的标识符不构成词边界
        assertAllowed("SELECT insert_log, inserted FROM t")
    }

    @Test
    fun `拒绝_函数豁免不打开写语句通路（T-SGE-02 安全回归）`() {
        // INSERT 写语句（结构闸 READ_ONLY，非粗扫路径）——INSERT INTO 在既有用例已覆盖，此处补 INSERT…SELECT
        assertDenied("insert into t select * from s", McpToolErrors.READ_ONLY, "INSERT…SELECT 应拒")
        // DBX 同款口径：update 不豁免（MySQL 接受 UPDATE (t) 表形态）——仍拒；
        // 结构闸（READ_ONLY）或解析 fail-closed（SQL_BLOCKED）任一路径都算拒，不钉码
        val verdict = McpSelectGuard.checkSingleSelect("UPDATE (t) SET x = 1")
        assertTrue("UPDATE (t) 形态应拒", !verdict.allowed)
        assertTrue("拒绝必须带原因", !verdict.reason.isNullOrBlank())
    }

    // ------------------------------------------------------------------
    // O-61 §八 增量：解析入口收拢 SqlAstParser（方括号 SELECT 能力扩展 + 负样本四组）
    // ------------------------------------------------------------------

    @Test
    fun `放行_SQLServer方括号引用SELECT（§八收拢 先红）`() {
        // 产品方言层自产形态（Dialect.quoteIfNeeded 走 [...]）——改前解析失败按写拒绝
        assertAllowed("SELECT * FROM [user] WHERE [id] = 1")
        assertAllowed("SELECT [name] FROM [dbo].[user]")
    }

    @Test
    fun `拒绝_方括号开启后多语句走私（§八负样本一）`() {
        assertDenied(
            "SELECT * FROM [user]; DROP TABLE [x]",
            McpToolErrors.SQL_BLOCKED,
            "方括号多语句走私应拒",
        )
    }

    @Test
    fun `拒绝_方括号SELECT_INTO（§八负样本二）`() {
        assertDenied(
            "SELECT [a] INTO [bak] FROM [s]",
            McpToolErrors.SQL_BLOCKED,
            "方括号 INTO 落盘应拒",
        )
    }

    @Test
    fun `拒绝_方括号锁读（§八负样本三）`() {
        assertDenied(
            "SELECT * FROM [t] FOR UPDATE",
            McpToolErrors.SQL_BLOCKED,
            "方括号锁读应拒",
        )
    }

    @Test
    fun `拒绝_方括号字面量剥离后粗扫命中（§八负样本四）`() {
        // 结构合规但剥离字面量后词面含写动词 → 粗扫兜底 READ_ONLY（改前解析失败走 SQL_BLOCKED，先红）
        assertDenied(
            "SELECT 1 FROM [t] WHERE analyze = 1",
            McpToolErrors.READ_ONLY,
            "方括号粗扫兜底应拒",
        )
    }

    @Test
    fun `拒绝_恶意构造输入解析失败仍fail_closed（§八）`() {
        // Throwable/StackOverflowError fail-closed 路径原样保留：语法错误/截断输入照拒
        assertDenied("SELEC * FORM [t]", McpToolErrors.SQL_BLOCKED, "方括号形态语法错误应拒")
        assertDenied("SELECT * FROM [t] WHERE", McpToolErrors.SQL_BLOCKED, "截断语句应拒")
    }

    // ------------------------------------------------------------------
    // T-SAF-11（mcp-select-guard.into-clause-non-recursive-cte-gap）：嵌套 INTO 定案
    // ------------------------------------------------------------------

    @Test
    fun `AST形态定案_CTE内SELECT_INTO的解析形态记录`() {
        // 定案测试（NEEDS-VALIDATION #11）：记录 JSqlParser 5.3 对
        // `WITH c AS (SELECT a INTO b FROM s) SELECT * FROM c` 的三种可能形态——
        //  ①解析失败：守卫 fail-closed（SQL_BLOCKED）已挡，无需修；
        //  ②解析成功、内层 CTE 的 PlainSelect 持 intoTables（外层 null）：改前 hasIntoClause
        //    只查最外层 → 放行（先红），修=递归遍历；
        //  ③解析成功但解析器丢弃 INTO（任何 PlainSelect 都不持）：改前同样放行（先红），
        //    修=词法兜底。
        // 本例按实际命中的形态记录并验证守卫拒绝；无条件拒绝契约由下一例钉死。
        val parsed = try {
            cn.song.mybatisplus.visualizer.execute.SqlAstParser.parseStatements(
                "WITH c AS (SELECT a INTO b FROM s) SELECT * FROM c",
            )
        } catch (t: Throwable) {
            null
        }
        if (parsed == null) {
            assertDenied(
                "WITH c AS (SELECT a INTO b FROM s) SELECT * FROM c",
                McpToolErrors.SQL_BLOCKED,
                "形态①解析失败：fail-closed 应拒",
            )
            return
        }
        val select = parsed.single() as Select
        val top = select as PlainSelect
        val topHolds = !top.intoTables.isNullOrEmpty() || top.intoTempTable != null
        // CTE 体形态：WithItem.select = ParenthesedSelect（内层再持真实 PlainSelect）——逐层探测
        fun anyInto(s: Select?): Boolean = when (s) {
            is PlainSelect -> !s.intoTables.isNullOrEmpty() || s.intoTempTable != null
            is net.sf.jsqlparser.statement.select.ParenthesedSelect -> anyInto(s.select)
            else -> false
        }
        val nestedHolds = top.withItemsList.orEmpty().any { anyInto(it.select) }
        // 形状记录（失败消息携带实测读数）：②nested / ③dropped——两者改前都放行（先红），
        // 修复后任一形态都由 AST 递归或词法兜底拒绝；形态②的断言读数在守卫拒绝消息中可见
        val shape = when {
            topHolds -> "TOP（外层持有，本就应被顶层检查拦下）"
            nestedHolds -> "NESTED（内层 CTE PlainSelect 持 intoTables）"
            else -> "DROPPED（解析器丢弃 INTO，靠词法兜底）"
        }
        assertDenied(
            "WITH c AS (SELECT a INTO b FROM s) SELECT * FROM c",
            McpToolErrors.SQL_BLOCKED,
            "CTE 内建表 INTO 应拒（实测形态=$shape）",
        )
    }

    @Test
    fun `拒绝_CTE与派生表内SELECT_INTO建表（先红）`() {
        // 改前：hasIntoClause 只查最外层 PlainSelect（注释声称「CTE 中的 INTO 仍落在
        // PlainSelect 上」但被查的只是最外层那一个），FORBIDDEN_PHRASES 亦无普通
        // `INTO <标识符>`——若解析成功则两条都放行（先红）；解析失败形态 fail-closed 照拒。
        // 修复后 AST 递归（WithItem/派生表/JOIN 子查询）与词法兜底任一命中即 SQL_BLOCKED。
        assertDenied(
            "WITH c AS (SELECT a INTO b FROM s) SELECT * FROM c",
            McpToolErrors.SQL_BLOCKED,
            "CTE 内建表 INTO 应拒",
        )
        assertDenied(
            "SELECT * FROM (SELECT a INTO b FROM s) t",
            McpToolErrors.SQL_BLOCKED,
            "派生表内建表 INTO 应拒",
        )
    }

    @Test
    fun `放行_括号内普通标识符不因词法兜底误杀`() {
        // 词法兜底回归：into 是普通标识符子串/普通词时不得误拒（词起点判据 + 全词比较）
        assertAllowed("SELECT * FROM t WHERE x IN (SELECT constrainto FROM u)")
        assertAllowed("SELECT (a + b) FROM t")
        assertAllowed("SELECT * FROM t WHERE x IN (1, 2, 3)")
    }
}
