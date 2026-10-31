package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import cn.song.mybatisplus.sqlvis.agent.transport.EventBus;
import cn.song.mybatisplus.sqlvis.agent.util.MdcAccessor;
import cn.song.mybatisplus.sqlvis.agent.util.StackDigest;
import cn.song.mybatisplus.sqlvis.agent.util.StackLocator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Statement/PreparedStatement 代理（采集核心）：
 * - setXxx 实参直取：显式签名枚举（§4.2 #4 主策略，禁用 startsWith("set") 宽泛匹配——
 *   DebugTools setFetchSize 误记坑）；值经脱敏层后入事件。
 * - execute 族：nanoTime 精确耗时、异常直捕（类名+消息，原样重抛）、影响行数直取；
 *   调用栈在 emitEvent 捕获一次、定位与摘要两用（T-PS4-09：locate 与 StackDigest 共享同一
 *   快照，禁每条 SQL 两次 getStackTrace；定位按调用方缓存，禁 per-execute 全栈解析热点）。
 * - 请求聚合：MDC 反射兜底（T-M56-07，一期 filter hook 待二期）。
 * 采集红线：任何钩子内部异常不得外溢影响执行语义（异常路径只记录原始异常后原样重抛）。
 */
final class StatementHandler implements InvocationHandler {

    private final Object raw;
    /** prepareStatement 时捕获的 SQL（Statement 纯无参：execute(String) 时取参数）。 */
    private final String preparedSql;

    /** connect 时的连接 URL（T-AgentDialectLoop：透传到事件供面板识别目标库）。 */
    private final String url;

    /** 参数槽：显式枚举 setXxx 捕获（index → 值文本/类型名）。 */
    private final ParamCapture params = new ParamCapture();

    /** 批处理语句累积（addBatch(String)）。 */
    private final StringBuilder batchSqls = new StringBuilder();

    StatementHandler(Object raw, String preparedSql, String url) {
        this.raw = raw;
        this.preparedSql = preparedSql;
        this.url = url;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final String name = method.getName();
        // T-AgentResiduals R5：本短路是**唯一**的停采闸门（连接/语句包装一律在位，不再有连接级短路）。
        // 位置有讲究：排在参数捕获（第 1 步）与批处理累积（第 2 步）之前，故停采期间既不产出事件、
        // 也不累积参数与批 SQL——恢复采集后不会把停采期间的参数错配到恢复后的语句上。
        if (AgentState.isDisabled()) {
            return doInvoke(method, args);
        }
        // 1) 参数捕获（显式枚举，ParamCapture 拒绝非白名单方法——setFetchSize 等不误记）
        if (args != null && args.length >= 2) {
            final boolean captured = params.tryCapture(name, args);
            if (captured) {
                return doInvoke(method, args);
            }
        }
        // 2) 批处理累积
        if ("addBatch".equals(name) && args != null && args.length == 1 && args[0] instanceof String) {
            if (batchSqls.length() > 0) {
                batchSqls.append(";\n");
            }
            batchSqls.append(args[0]);
            return doInvoke(method, args);
        }
        // 3) execute 族 → 事件
        if (EventBuilder.isExecuteFamily(name)) {
            return executeWithCapture(method, args, name);
        }
        return doInvoke(method, args);
    }

    private Object doInvoke(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(raw, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private Object executeWithCapture(Method method, Object[] args, String name) throws Throwable {
        // execute(String) 形态：语句 SQL 取参数；PreparedStatement：取 prepare 时捕获
        String sql = preparedSql;
        if (sql == null && args != null && args.length >= 1 && args[0] instanceof String) {
            sql = (String) args[0];
        }
        if ("executeBatch".equals(name) && batchSqls.length() > 0) {
            sql = batchSqls.toString();
        }
        final long startNanos = System.nanoTime();
        // T-AgentLogTime：采集时刻取 execute **开始**那一刻（与 nanoTime 同点）——日志时间相关器
        // 未命中时回落的「捕获时间」即语句开始执行时刻，与 MyBatis 打印的 Preparing 日志行
        // （打印于 execute 之前）只差毫秒级；若取在 finally（旧写法），回落值会晚一个执行耗时。
        final long startMillis = System.currentTimeMillis();
        Object result = null;
        Throwable failure = null;
        // T-StatementWeave 双通道去重门（WeaveGate）：invoke 到的 raw 方法体已被语句面织入——
        // 不设门，织入通道会对同一条 SQL 再产出一条事件。置位期间织入入口钩子短路（token=0）。
        WeaveGate.enterProxy();
        try {
            result = method.invoke(raw, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            failure = e.getCause() != null ? e.getCause() : e;
            throw failure;
        } finally {
            WeaveGate.exitProxy();
            final long durationNanos = System.nanoTime() - startNanos;
            try {
                emitEvent(name, sql, result, failure, durationNanos, startMillis);
            } catch (Throwable ignore) {
                // 事件构造/发送失败不影响执行结果
            }
        }
        return result;
    }

    private void emitEvent(String op, String sql, Object result, Throwable failure, long durationNanos,
                           long startMillis) {
        if (AgentState.isDisabled() || sql == null || sql.isEmpty()) {
            return;
        }
        // T-AgentSourceJump：loader hint 取被织入对象（驱动/连接侧类，开发态=应用 loader）——
        // StackLocator 据此判定帧来源是否 JAR（L1 来源过滤）；取不到退上下文 loader
        ClassLoader hint = null;
        try {
            hint = raw.getClass().getClassLoader();
        } catch (Throwable ignore) {
            // 保持 null，StackLocator 内部退上下文 loader
        }
        // T-PS4-09 一次捕获两用：同一快照喂 locate（定位）与 StackDigest（摘要）——原实现两者
        // 各自 getStackTrace，每条 SQL 两次全栈捕获（第七轮审计①）。摘要捕获点自 StackDigest.build
        // 内部上移至本方法，头帧由 StackDigest#build 变为 emitEvent，16 帧截断下仅头部差一自身帧、
        // 尾部多含一帧业务帧；过滤规则不变（仅跳 Thread#getStackTrace 自身帧）。
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        final StackLocator.Frame frame = StackLocator.locate(stack, hint);
        Long updateCount = EventBuilder.extractUpdateCount(op, result, raw);
        SqlEvent event = new SqlEvent();
        event.sql = sql;
        event.timestamp = startMillis;
        event.durationNanos = durationNanos;
        event.updateCount = updateCount;
        event.errorText = failure == null ? null
                : failure.getClass().getName() + ": " + failure.getMessage();
        event.sourceFile = frame == null ? null : frame.sourceFile;
        event.sourceLine = frame == null ? 0 : frame.line;
        event.methodKey = frame == null ? null : frame.methodKey;
        event.sourceCandidates = frame == null ? null : frame.candidates;
        event.stackDigest = StackDigest.build(stack, hint, frame);
        event.requestTag = MdcAccessor.requestTag();
        event.params = params.snapshot(sql, AgentState.isDesensitize());
        event.url = url; // connect 时 URL（面板据此展示目标库类型；测试/池代理下可能为 null）
        event.threadName = Thread.currentThread().getName();
        EventBus.offer(event);
    }

}
