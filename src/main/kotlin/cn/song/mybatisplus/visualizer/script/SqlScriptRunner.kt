package cn.song.mybatisplus.visualizer.script

import cn.song.mybatisplus.visualizer.script.api.IEnv
import cn.song.mybatisplus.visualizer.script.api.SqlExecContext
import cn.song.mybatisplus.visualizer.script.api.SqlExecResult
import cn.song.mybatisplus.visualizer.script.api.SqlScriptLog

/**
 * T-RollbackSnapshotPreExec P7：作用域脚本链编排（纯逻辑，可进 testFast）。
 *
 * 顺序（D-2 全部级）：全局 → 数据源 → 语句级；空档位跳过。任一前置脚本返回 false =
 * 软否决（停止后续脚本与执行）；任一脚本编译失败/异常/超时 = 硬阻断（前置）或仅记录（后置）。
 * 引擎保证超时中断；本类只做顺序与聚合。
 */
object SqlScriptRunner {

    /** 作用域档位（执行顺序即枚举顺序）。 */
    enum class Scope { GLOBAL, DATA_SOURCE, STATEMENT }

    /** 一次脚本链执行的原始脚本清单（按 [Scope] 顺序展开后的有序对）。 */
    data class ScriptEntry(val scope: Scope, val source: String)

    sealed interface PreOutcome {
        /** 全部放行。 */
        data class Proceed(val logText: String) : PreOutcome

        /** 被某档前置脚本否决。 */
        data class Vetoed(val scope: Scope, val logText: String) : PreOutcome

        /** 硬阻断（编译失败/异常/超时/形态不合规）。 */
        data class Blocked(val reason: String, val logText: String) : PreOutcome
    }

    /** 按顺序运行前置脚本链；空清单直接放行（零编译零执行）。 */
    fun runPreChain(
        scripts: List<ScriptEntry>,
        context: SqlExecContext,
        env: IEnv,
        timeoutMillisPerScript: Long = SqlScriptEngine.DEFAULT_TIMEOUT_MILLIS,
    ): PreOutcome {
        val logs = StringBuilder()
        val engine = SqlScriptEngine(timeoutMillisPerScript)
        for ((scope, source) in scripts) {
            if (source.isBlank()) continue
            when (val outcome = engine.runPre(source, context, env, timeoutMillisPerScript)) {
                is SqlScriptEngine.Outcome.Pre ->
                    if (outcome.vetoed) {
                        logs.append(outcome.logText)
                        return PreOutcome.Vetoed(scope, logs.toString())
                    }
                SqlScriptEngine.Outcome.Empty -> {}
                is SqlScriptEngine.Outcome.CompileError -> {
                    logs.append(outcome.logText)
                    return PreOutcome.Blocked("前置脚本编译失败（${scope.name}）：${outcome.message}", logs.toString())
                }
                is SqlScriptEngine.Outcome.ScriptError -> {
                    logs.append(outcome.logText)
                    return PreOutcome.Blocked("前置脚本异常（${scope.name}）：${outcome.message}", logs.toString())
                }
                is SqlScriptEngine.Outcome.Timeout -> {
                    logs.append(outcome.logText)
                    return PreOutcome.Blocked("前置脚本超时（${scope.name}，${outcome.timeoutMillis}ms 已中断）", logs.toString())
                }
                is SqlScriptEngine.Outcome.BadScript -> {
                    logs.append(outcome.logText)
                    return PreOutcome.Blocked("前置脚本形态不合规（${scope.name}）：${outcome.message}", logs.toString())
                }
                is SqlScriptEngine.Outcome.Post -> {}
            }
        }
        return PreOutcome.Proceed(logs.toString())
    }

    /** 后置脚本链：失败只聚合日志，不影响已执行结果（照 CoolRequest 后置语义）。 */
    fun runPostChain(
        scripts: List<ScriptEntry>,
        result: SqlExecResult,
        env: IEnv,
        timeoutMillisPerScript: Long = SqlScriptEngine.DEFAULT_TIMEOUT_MILLIS,
    ): String {
        val logs = StringBuilder()
        val engine = SqlScriptEngine(timeoutMillisPerScript)
        for ((scope, source) in scripts) {
            if (source.isBlank()) continue
            when (val outcome = engine.runPost(source, result, env, timeoutMillisPerScript)) {
                is SqlScriptEngine.Outcome.Post -> logs.append(outcome.logText)
                SqlScriptEngine.Outcome.Empty -> {}
                else ->
                    logs.append("后置脚本（${scope.name}）问题：").append(outcomeDescribe(outcome)).append('\n')
            }
        }
        return logs.toString()
    }

    private fun outcomeDescribe(outcome: SqlScriptEngine.Outcome): String = when (outcome) {
        is SqlScriptEngine.Outcome.CompileError -> "编译失败：${outcome.message}"
        is SqlScriptEngine.Outcome.ScriptError -> "异常：${outcome.message}"
        is SqlScriptEngine.Outcome.Timeout -> "超时（${outcome.timeoutMillis}ms 已中断）"
        is SqlScriptEngine.Outcome.BadScript -> "形态不合规：${outcome.message}"
        else -> outcome.toString()
    }
}
