package cn.song.mybatisplus.sqlvis.agent.hook;

/**
 * 「嵌套只报最内层」门（T-CaptureCatchAll 券 07，计划 D3=A）。
 *
 * <p><b>为什么需要。</b>接口匹配兜底会把连接池/日志包装类一并命中：HikariCP 的
 * {@code HikariProxyPreparedStatement}（{@code final}，{@code implements java.sql.PreparedStatement}）
 * 的 {@code execute()} 只是转发给底层驱动 PS；MyBatis 的 {@code PreparedStatementLogger} 同理
 * （JDK 动态代理）。两者与内层驱动 PS 都织上钩子后，**一次执行产两条事件**：IDE 侧 60s 指纹
 * 去重只会折叠成 {@code ×2}，关掉去重就是两行；{@code EXEC_CTX} 还会抬高
 * {@code unresolvedCount} 并打出误导性类名。
 *
 * <p><b>判据为什么不用类名黑名单。</b>类名黑名单会随生态漂移（Hikari 改包名、MyBatis 换代理实现、
 * 新池化框架出现都要改名单），而且「哪些类算包装类」在字节层根本没有稳定特征。本门改用
 * **调用栈深度**：同一线程内，一个已织入的 execute 方法体里再进入另一个已织入的 execute
 * 方法体 = 嵌套；嵌套链只让**最内层**（先退出者）产事件，外层一律抑制。
 *
 * <p><b>语义与开销。</b>入口自增、出口自减，每线程一个两字段小对象；无嵌套时就是两次
 * ThreadLocal 读（外加一次 remove 归零，避免池线程残留）。所有方法自吞异常并返回「放行」——
 * 去重门自身绝不能成为丢事件或影响宿主的原因。
 *
 * <p><b>已知取舍</b>：真正的嵌套执行（存储过程内部再执行语句、同一线程里语句套语句）只报最内层，
 * 外层那条会缺——计划 §六 已登记，与「双报」相比是更小的损害（双报会污染归属与计数）。
 * 需要恢复旧行为可置 {@code -Dsqlvis.agent.nestGate=false}。
 */
final class NestGate {

    /** 开关（默认开）：false = 关闭去重，恢复「每个已织 execute 都产事件」。 */
    static final String ENABLED_PROP = "sqlvis.agent.nestGate";

    private static volatile boolean enabled = resolveEnabled();

    /** 每线程嵌套态：depth = 当前已织 execute 帧数；innerEmitted = 本链已有内层产过事件。 */
    private static final class State {
        int depth;
        boolean innerEmitted;
    }

    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override
        protected State initialValue() {
            return new State();
        }
    };

    private NestGate() {
    }

    /** 当前线程进入一个已织 execute 帧。 */
    static void enter() {
        if (!enabled) {
            return;
        }
        try {
            STATE.get().depth++;
        } catch (Throwable ignore) {
            // 门自身失败：不计数即等于关闭本帧去重（放行），绝不影响采集
        }
    }

    /**
     * 当前线程离开一个已织 execute 帧。
     *
     * @return true = 本帧是嵌套链的最内层（应产事件）；false = 外层（已有内层产过，抑制）
     */
    static boolean exit() {
        if (!enabled) {
            return true;
        }
        try {
            final State s = STATE.get();
            if (s.depth > 0) {
                s.depth--;
            }
            if (s.depth > 0) {
                if (s.innerEmitted) {
                    return false;
                }
                s.innerEmitted = true;
                return true;
            }
            // 最外层退出：本链若已有内层产过事件则抑制，并把线程态清干净
            final boolean emit = !s.innerEmitted;
            s.innerEmitted = false;
            STATE.remove();
            return emit;
        } catch (Throwable t) {
            return true; // 门自身失败：放行（宁可多报，不可漏报）
        }
    }

    /** 仅测试用：复位线程态与开关。 */
    static void resetForTest() {
        STATE.remove();
        enabled = resolveEnabled();
    }

    static void setEnabledForTest(boolean value) {
        enabled = value;
        STATE.remove();
    }

    private static boolean resolveEnabled() {
        try {
            final String raw = System.getProperty(ENABLED_PROP);
            if (raw != null) {
                final String v = raw.trim();
                if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
                    return false;
                }
            }
        } catch (Throwable ignore) {
            // 非法值按默认开
        }
        return true;
    }
}
