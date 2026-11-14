package cn.song.mybatisplus.visualizer.script

import cn.song.mybatisplus.visualizer.script.api.IEnv
import cn.song.mybatisplus.visualizer.script.api.SqlExecContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-RollbackSnapshotPreExec P7 脚本链编排测试（纯逻辑，testFast；源码零平台包字面量）。
 * 覆盖：三档作用域顺序、前置否决短路、前置异常硬阻断、空清单零执行、后置链失败只记日志。
 *
 * 反向验证缝（收口轮执行，禁 git checkout）：把 runPreChain 的「vetoed 即返回」改成
 * 「继续跑后续」→ vetoStopsChain 必红。
 */
class SqlScriptRunnerTest {

    private fun ctx() = SqlExecContext("UPDATE t SET a = 1 WHERE id = 5", "UPDATE", "t", "fake-ds", "MySQL")

    private fun pre(body: String) = """
        import cn.song.mybatisplus.visualizer.script.api.IEnv;
        import cn.song.mybatisplus.visualizer.script.api.SqlExecContext;
        import cn.song.mybatisplus.visualizer.script.api.SqlPreScript;
        import cn.song.mybatisplus.visualizer.script.api.SqlScriptLog;

        public class PreImpl extends SqlPreScript {
            @Override
            public boolean handlerSql(SqlScriptLog log, SqlExecContext ctx, IEnv env) {
                $body
            }
        }
    """.trimIndent()

    private fun post(body: String) = """
        import cn.song.mybatisplus.visualizer.script.api.IEnv;
        import cn.song.mybatisplus.visualizer.script.api.SqlExecResult;
        import cn.song.mybatisplus.visualizer.script.api.SqlPostScript;
        import cn.song.mybatisplus.visualizer.script.api.SqlScriptLog;

        public class PostImpl extends SqlPostScript {
            @Override
            public void handlerResult(SqlScriptLog log, SqlExecResult result, IEnv env) {
                $body
            }
        }
    """.trimIndent()

    private fun entry(scope: SqlScriptRunner.Scope, source: String) =
        SqlScriptRunner.ScriptEntry(scope, source)

    @Test
    fun vetoStopsChain() {
        val env = IEnv.Impl()
        val outcome = SqlScriptRunner.runPreChain(
            listOf(
                entry(SqlScriptRunner.Scope.GLOBAL, pre("env.setVar(\"first\", \"ran\"); return true;")),
                entry(SqlScriptRunner.Scope.DATA_SOURCE, pre("env.setVar(\"second\", \"ran\"); return false;")),
                entry(SqlScriptRunner.Scope.STATEMENT, pre("env.setVar(\"third\", \"ran\"); return true;")),
            ),
            ctx(), env,
        )
        val vetoed = outcome as SqlScriptRunner.PreOutcome.Vetoed
        assertEquals(SqlScriptRunner.Scope.DATA_SOURCE, vetoed.scope)
        assertEquals("ran", env.getVar("first"))
        assertEquals("ran", env.getVar("second"))
        assertEquals(null, env.getVar("third"))
    }

    @Test
    fun proceedRunsAllScopesInOrder() {
        val env = IEnv.Impl()
        val outcome = SqlScriptRunner.runPreChain(
            listOf(
                entry(SqlScriptRunner.Scope.GLOBAL, pre("env.setVar(\"order\", env.getVar(\"order\") == null ? \"G\" : env.getVar(\"order\") + \"G\"); return true;")),
                entry(SqlScriptRunner.Scope.DATA_SOURCE, pre("env.setVar(\"order\", (env.getVar(\"order\") == null ? \"\" : env.getVar(\"order\")) + \"D\"); return true;")),
                entry(SqlScriptRunner.Scope.STATEMENT, pre("env.setVar(\"order\", (env.getVar(\"order\") == null ? \"\" : env.getVar(\"order\")) + \"S\"); return true;")),
            ),
            ctx(), env,
        )
        assertTrue(outcome is SqlScriptRunner.PreOutcome.Proceed)
        assertEquals("GDS", env.getVar("order"))
    }

    @Test
    fun preScriptErrorBlocks() {
        val outcome = SqlScriptRunner.runPreChain(
            listOf(entry(SqlScriptRunner.Scope.GLOBAL, pre("throw new IllegalStateException(\"boom\");"))),
            ctx(), IEnv.Impl(),
        )
        val blocked = outcome as SqlScriptRunner.PreOutcome.Blocked
        assertTrue(blocked.reason.contains("boom"))
        assertTrue(blocked.reason.contains("GLOBAL"))
    }

    @Test
    fun emptyChainProceedsWithZeroWork() {
        val outcome = SqlScriptRunner.runPreChain(emptyList(), ctx(), IEnv.Impl())
        assertTrue(outcome is SqlScriptRunner.PreOutcome.Proceed)
    }

    @Test
    fun postChainFailureOnlyLogs() {
        val logs = SqlScriptRunner.runPostChain(
            listOf(
                entry(SqlScriptRunner.Scope.GLOBAL, post("env.setVar(\"postOk\", \"yes\");")),
                entry(SqlScriptRunner.Scope.DATA_SOURCE, post("throw new IllegalStateException(\"late boom\");")),
            ),
            cn.song.mybatisplus.visualizer.script.api.SqlExecResult(1, emptyList(), emptyList(), false, null, 5),
            IEnv.Impl(),
        )
        assertTrue(logs.contains("late boom"))
        assertTrue(logs.contains("DATA_SOURCE"))
    }

    @Test
    fun blankSourcesSkipped() {
        val outcome = SqlScriptRunner.runPreChain(
            listOf(
                entry(SqlScriptRunner.Scope.GLOBAL, ""),
                entry(SqlScriptRunner.Scope.DATA_SOURCE, "   "),
            ),
            ctx(), IEnv.Impl(),
        )
        assertTrue(outcome is SqlScriptRunner.PreOutcome.Proceed)
    }
}
