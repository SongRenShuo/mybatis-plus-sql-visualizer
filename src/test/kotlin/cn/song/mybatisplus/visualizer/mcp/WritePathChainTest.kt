package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEW-23 安全写路径「计划链」测试：DML 场景一条链跑通、回滚语句与 sqlv_rollback_plan 逐字节一致、
 * 无界写必须被判 BLOCKED（闸门反向验证的绿侧读数）、chain=false 零差异、工具数不变。
 */
class WritePathChainTest {

    private fun chain(sql: String, pk: String = "", dialect: String = "") =
        SqlVisualizerCheckSqlTool().checkSql(sql, chain = true, primaryKeyColumns = pk, dialect = dialect)

    @Test
    fun `有界INSERT_清单跑通并给CONFIRM闸门与COMPLETE回滚`() {
        val output = chain("INSERT INTO t_user (id, name) VALUES (1, 'Tom')", pk = "id", dialect = "MySQL")
        assertTrue(output, output.contains("pre-execution checklist:"))
        assertTrue(output, output.contains("riskLevel: LOW — bounded write"))
        assertTrue(output, output.contains("executionGate: CONFIRM"))
        assertTrue(output, output.contains("targetTable: t_user"))
        assertTrue(output, output.contains("targetDialect: MySQL"))
        assertTrue(output, output.contains("step1-render:"))
        assertTrue(output, output.contains("step2-plan: no impact probe: Only UPDATE/DELETE statements"))
        assertTrue(output, output.contains("step3-rollback: COMPLETE"))
        assertTrue(output, output.contains("step4-execute: not performed by MCP"))
        assertTrue(output, output.contains("rollback plan:"))
        assertTrue(output, output.contains("DELETE FROM t_user WHERE id = 1"))
    }

    @Test
    fun `有界DELETE_清单给只读COUNT试算且回滚诚实UNABLE`() {
        val output = chain("DELETE FROM t_user WHERE id = 1", pk = "id", dialect = "MySQL")
        assertTrue(output, output.contains("riskLevel: LOW"))
        assertTrue(output, output.contains("executionGate: CONFIRM"))
        assertTrue(
            output,
            output.contains("impact probe (read-only COUNT, run it before executing): SELECT COUNT(*) FROM t_user WHERE id = 1"),
        )
        assertTrue(output, output.contains("step3-rollback: UNABLE"))
        assertTrue(output, output.contains("statements: (none"))
    }

    @Test
    fun `无界写_必须BLOCKED`() {
        val output = chain("DELETE FROM t_user")
        assertTrue(output, output.contains("riskLevel: HIGH — unbounded write (MISSING_WHERE)"))
        assertTrue(output, output.contains("executionGate: BLOCKED"))
        assertTrue(output, output.contains("do not run this statement through any automated path"))
    }

    @Test
    fun `恒真谓词_必须BLOCKED`() {
        val output = chain("UPDATE t_user SET name = 'x' WHERE 1 = 1")
        assertTrue(output, output.contains("riskLevel: HIGH"))
        assertTrue(output, output.contains("executionGate: BLOCKED"))
        assertTrue(output, output.contains("TAUTOLOGY"))
    }

    @Test
    fun `DDL_必须BLOCKED且无可推导回滚`() {
        val output = chain("TRUNCATE TABLE t_user")
        assertTrue(output, output.contains("executionGate: BLOCKED"))
        assertTrue(output, output.contains("DDL is not statically bounded"))
        assertTrue(output, output.contains("step3-rollback: UNABLE"))
    }

    @Test
    fun `多语句混合_取最高风险面`() {
        val output = chain("UPDATE t_user SET name = 'x' WHERE id = 1; DELETE FROM t_user WHERE 1 = 1")
        assertTrue(output, output.contains("statements: 2"))
        assertTrue(output, output.contains("executionGate: BLOCKED"))
        assertTrue(output, output.contains("riskLevel: HIGH"))
    }

    @Test
    fun `纯查询_READ_ONLY并指向sqlv_explain`() {
        val output = chain("SELECT id, name FROM t_user WHERE id = 1")
        assertTrue(output, output.contains("riskLevel: NONE — read-only statement"))
        assertTrue(output, output.contains("executionGate: READ_ONLY"))
        assertTrue(output, output.contains("step2-plan: call sqlv_explain"))
        assertTrue(output, output.contains("step3-rollback: not applicable"))
        assertFalse("只读语句不出回滚计划", output.contains("rollback plan:"))
    }

    @Test
    fun `回滚语句与sqlv_rollback_plan同文本`() {
        val sql = "INSERT INTO t_user (id, name) VALUES (1, 'Tom')"
        val planOnly = SqlVisualizerRollbackPlanTool().rollbackPlan(sql, "id")
        val output = chain(sql, pk = "id", dialect = "MySQL")
        assertTrue(
            "清单尾部应与 sqlv_rollback_plan 的输出逐字节一致（同一 SQL 文本、同一主键口径）",
            output.trimEnd().endsWith(planOnly.trimEnd()),
        )
    }

    @Test
    fun `chain关闭_输出与接线前逐字节一致`() {
        val sql = "DELETE FROM t WHERE 1 = 1"
        val legacy = SqlVisualizerCheckSqlTool().checkSql(sql)
        val explicitOff = SqlVisualizerCheckSqlTool().checkSql(sql, chain = false)
        assertEquals(legacy, explicitOff)
        assertFalse("chain=false 不得追加清单", legacy.contains("pre-execution checklist"))
    }

    @Test
    fun `未给方言_不给影响面试算`() {
        val output = chain("DELETE FROM t_user WHERE id = 1")
        assertTrue(output, output.contains("pass 'dialect' to get the read-only COUNT probe"))
        assertFalse("未指定方言不得凭空渲染试算语句", output.contains("SELECT COUNT(*)"))
    }

    @Test
    fun `方言不可识别_预期错误`() {
        val error = runCatching { chain("DELETE FROM t WHERE id = 1", dialect = "OracleX") }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
    }

    @Test
    fun `不新增MCP工具数_仍为十一`() {
        assertEquals("NEW-23 只做参数组合，不得新增工具", 11, McpToolGate.TOOL_NAMES.size)
    }
}
