package cn.song.mybatisplus.visualizer.script

import cn.song.mybatisplus.visualizer.script.api.IEnv
import cn.song.mybatisplus.visualizer.script.api.SqlExecContext
import cn.song.mybatisplus.visualizer.script.api.SqlExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-RollbackSnapshotPreExec P6 脚本引擎内核测试（纯逻辑，testFast 快桶，测试源码零平台包引用）。
 * 注意：本文件注释刻意不出现平台包名字面量——testFast 划桶按源码字面过滤，写了会被踢出快桶。
 *
 * 反向验证缝（收口轮执行，禁 git checkout）：
 * - 把 runScript 的缓存命中判断临时摘掉（强制每次 compile）→ cacheHitDoesNotRecompile 必红；
 * - 把 worker.join(timeout) 改成 join() 无限等 → timeoutInterruptsBusyLoop 必红（死循环挂死测试，
 *   反向验证走编译期摘除后以「超时用例挂起」为红判据，谨慎执行）。
 */
class SqlScriptEngineTest {

    private val engine = SqlScriptEngine(defaultTimeoutMillis = 500)

    private fun preScript(body: String, className: String = "SqlPreScriptImpl"): String = """
        import cn.song.mybatisplus.visualizer.script.api.IEnv;
        import cn.song.mybatisplus.visualizer.script.api.SqlExecContext;
        import cn.song.mybatisplus.visualizer.script.api.SqlPreScript;
        import cn.song.mybatisplus.visualizer.script.api.SqlScriptLog;

        public class $className extends SqlPreScript {
            @Override
            public boolean handlerSql(SqlScriptLog log, SqlExecContext ctx, IEnv env) {
                $body
            }
        }
    """.trimIndent()

    private fun postScript(body: String): String = """
        import cn.song.mybatisplus.visualizer.script.api.IEnv;
        import cn.song.mybatisplus.visualizer.script.api.SqlExecResult;
        import cn.song.mybatisplus.visualizer.script.api.SqlPostScript;
        import cn.song.mybatisplus.visualizer.script.api.SqlScriptLog;

        public class SqlPostScriptImpl extends SqlPostScript {
            @Override
            public void handlerResult(SqlScriptLog log, SqlExecResult result, IEnv env) {
                $body
            }
        }
    """.trimIndent()

    private fun context() = SqlExecContext(
        "UPDATE t_order SET a = 1 WHERE id = 5",
        "UPDATE", "t_order", "fake-ds", "MySQL",
    )

    /** 断言前置脚本走通；失败时把 outcome 全文（含编译诊断）塞进断言消息，避免失败读数失真。 */
    private fun runPreOk(source: String, env: IEnv): SqlScriptEngine.Outcome.Pre {
        val outcome = engine.runPre(source, context(), env)
        assertTrue("期望 Pre，实际=$outcome", outcome is SqlScriptEngine.Outcome.Pre)
        return outcome as SqlScriptEngine.Outcome.Pre
    }

    private fun result() = SqlExecResult(
        3L, listOf("id"), listOf(listOf("1")), false, null, 42L,
    )

    @Test
    fun preScriptReturnsTrueAndLogs() {
        val pre = runPreOk(preScript("log.println(\"check \" + ctx.getStatementKind()); return true;"), IEnv.Impl())
        assertEquals(false, pre.vetoed)
        assertTrue("log 应有输出", pre.logText.contains("check UPDATE"))
    }

    @Test
    fun preScriptCanVeto() {
        val pre = runPreOk(preScript("return false;"), IEnv.Impl())
        assertEquals(true, pre.vetoed)
    }

    @Test
    fun postScriptReadsResultAndWritesEnv() {
        val env = IEnv.Impl()
        val outcome = engine.runPost(
            postScript("env.setVar(\"lastCount\", String.valueOf(result.getAffectedRows())); log.println(\"done\");"),
            result(), env,
        )
        assertTrue(outcome is SqlScriptEngine.Outcome.Post)
        assertEquals("3", env.getVar("lastCount"))
    }

    @Test
    fun postScriptExceptionDoesNotBreakCaller() {
        // 后置失败语义：异常收敛为 ScriptError outcome，不抛出
        val outcome = engine.runPost(postScript("throw new IllegalStateException(\"boom\");"), result(), IEnv.Impl())
        val err = outcome as SqlScriptEngine.Outcome.ScriptError
        assertTrue(err.message!!.contains("boom"))
    }

    @Test
    fun compileErrorCarriesDiagnostics() {
        val outcome = engine.runPre(
            preScript("this line does not compile ???"),
            context(), IEnv.Impl(),
        )
        val err = outcome as SqlScriptEngine.Outcome.CompileError
        assertTrue("诊断应含行号或原因", err.message.isNotBlank())
    }

    @Test
    fun timeoutInterruptsBusyLoop() {
        // sleep 才能被 interrupt 打断（裸 spin 循环对中断免疫、线程会残留）；
        // InterruptedException 必须就地捕获（override 未声明 throws，javac 会拦）
        val outcome = engine.runPre(
            preScript(
                "while (ctx.getSqlText() != null) { " +
                    "try { Thread.sleep(10); } catch (InterruptedException e) { throw new RuntimeException(e); } " +
                    "} return true;",
            ),
            context(), IEnv.Impl(),
        )
        assertTrue(
            "期望 Timeout（500ms 内中断），实际=$outcome",
            outcome is SqlScriptEngine.Outcome.Timeout,
        )
        assertEquals(500L, (outcome as SqlScriptEngine.Outcome.Timeout).timeoutMillis)
    }

    @Test
    fun cacheHitDoesNotRecompile() {
        val source = preScript("return true;", className = "SqlPreScriptImpl")
        val before = engine.compileCount.get()
        engine.runPre(source, context(), IEnv.Impl())
        val afterFirst = engine.compileCount.get()
        engine.runPre(source, context(), IEnv.Impl())
        engine.runPre(source, context(), IEnv.Impl())
        assertEquals("同 hash 二次/三次执行不得重编译", afterFirst, engine.compileCount.get())
        assertTrue("首次执行应真实编译一次", afterFirst == before + 1)
    }

    @Test
    fun emptyTemplateSkipsCompile() {
        val before = engine.compileCount.get()
        val preOutcome = engine.runPre(SqlScriptTemplates.PRE_TEMPLATE, context(), IEnv.Impl())
        val postOutcome = engine.runPost(SqlScriptTemplates.POST_TEMPLATE, result(), IEnv.Impl())
        assertTrue(preOutcome is SqlScriptEngine.Outcome.Empty)
        assertTrue(postOutcome is SqlScriptEngine.Outcome.Empty)
        assertEquals("空模板必须零编译", before, engine.compileCount.get())
    }

    @Test
    fun emptyScriptDetectionIsTextLevel() {
        // 模板改注释/空白/行序仍是空；加任何代码立即非空
        val commented = SqlScriptTemplates.PRE_TEMPLATE.replace(
            "return true;",
            "// my custom logic\nreturn true;",
        )
        assertTrue(SqlScriptTemplates.isEmptyScript(commented, SqlScriptTemplates.Kind.PRE))
        val edited = SqlScriptTemplates.PRE_TEMPLATE.replace(
            "return true;",
            "log.println(\"custom\"); return true;",
        )
        assertTrue(!SqlScriptTemplates.isEmptyScript(edited, SqlScriptTemplates.Kind.PRE))
    }

    @Test
    fun badScriptWithoutBaseClass() {
        val outcome = engine.runPre(
            """
            public class NotAScript {
                public boolean handlerSql(cn.song.mybatisplus.visualizer.script.api.SqlScriptLog log,
                    cn.song.mybatisplus.visualizer.script.api.SqlExecContext ctx,
                    cn.song.mybatisplus.visualizer.script.api.IEnv env) { return true; }
            }
            """.trimIndent(),
            context(), IEnv.Impl(),
        )
        assertTrue(outcome is SqlScriptEngine.Outcome.BadScript)
    }

    @Test
    fun unknownExternalClassFailsCompile() {
        // classpath 只给插件类 + JDK：引用不存在的第三方类必须编译失败（不继承用户项目 classpath）
        val outcome = engine.runPre(
            preScript("com.nonexistent.library.Thing.doWork(); return true;"),
            context(), IEnv.Impl(),
        )
        assertTrue(outcome is SqlScriptEngine.Outcome.CompileError)
    }

    @Test
    fun envRoundTripAndListeners() {
        val env = IEnv.Impl()
        val events = mutableListOf<String>()
        env.addListener { name, value -> events.add("$name=$value") }
        env.setVar("a", "1")
        env.setVar("b", "2")
        env.setVar("a", "3")
        env.removeVar("b")
        assertEquals("3", env.getVar("a"))
        assertEquals(null, env.getVar("b"))
        assertEquals(setOf("a=1", "b=2", "a=3", "b=null"), events.toSet())
        assertEquals(1, env.vars().size)
    }

    @Test
    fun envNullValueMeansRemove() {
        val env = IEnv.Impl()
        env.setVar("k", "v")
        env.setVar("k", null)
        assertEquals(null, env.getVar("k"))
    }
}
