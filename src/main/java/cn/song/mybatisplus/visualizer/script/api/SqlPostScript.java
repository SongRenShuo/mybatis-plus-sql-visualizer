package cn.song.mybatisplus.visualizer.script.api;

/**
 * T-RollbackSnapshotPreExec P6：后置脚本基类（脚本 extends 它并覆写 [handlerResult]）。
 *
 * <p><b>签名不得修改</b>（理由同 SqlPreScript）。后置脚本失败**不影响已执行结果**——
 * 异常只进脚本日志区并轻提示（照 CoolRequest 后置语义，`JavaCodeEngine.java:103-113`）。
 *
 * <p>典型用法：从结果集提炼变量 {@code env.setVar("lastCount", String.valueOf(result.getAffectedRows()))}，
 * 供下一次前置脚本取用。
 */
public abstract class SqlPostScript {

    /**
     * SQL 执行成功后回调（失败/否决的执行不会到达这里）。
     *
     * @param log    脚本输出通道（禁 System.out）
     * @param result 只读结果（影响行数/结果集样本/快照 id/耗时）
     * @param env    脚本变量环境
     * @throws Exception 任何异常都只记录，不影响已执行的 SQL
     */
    public abstract void handlerResult(SqlScriptLog log, SqlExecResult result, IEnv env) throws Exception;
}
