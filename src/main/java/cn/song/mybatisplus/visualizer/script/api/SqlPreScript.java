package cn.song.mybatisplus.visualizer.script.api;

/**
 * T-RollbackSnapshotPreExec P6：前置脚本基类（脚本 extends 它并覆写 [handlerSql]）。
 *
 * <p><b>签名不得修改</b>——方法名/参数/返回值任何一个变了都会编译失败或无法被引擎调用
 * （引擎按精确签名 MethodHandle 调用，CoolRequest `JavaCodeEngine.java:210-224` 同款）。
 *
 * <p>返回 {@code false} = 软否决本次 SQL 执行（编排层提示「被前置脚本否决」，不弹模态框）；
 * 抛异常 = 硬阻断（执行不发生，异常与日志进脚本日志区）。
 *
 * <p>红线提醒：脚本以当前用户权限运行任意 Java 代码，本插件不做沙箱（与 CoolRequest /
 * IDE Scripting Console 同风险模型）——只写你完全理解的代码。
 */
public abstract class SqlPreScript {

    /**
     * SQL 提交前回调。
     *
     * @param log  脚本输出通道（禁 System.out）
     * @param ctx  本次执行的只读上下文（SQL/类型/目标表/数据源/方言）
     * @param env  脚本变量环境（读上次结果、写本次产出）
     * @return true = 放行继续执行；false = 否决本次执行
     * @throws Exception 任何异常都会阻断执行并进脚本日志
     */
    public abstract boolean handlerSql(SqlScriptLog log, SqlExecContext ctx, IEnv env) throws Exception;
}
