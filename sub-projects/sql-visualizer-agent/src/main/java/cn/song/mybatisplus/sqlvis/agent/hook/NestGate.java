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
 * ThreadLocal 读（外加一次复位，避免池线程残留）。判定按「**真的产出过**」而不是「进入过」：
 * 内层取不到 SQL（登记盲区）时不置位，外层仍可兜底产出（见 {@link #markEmitted()}）。
 * 所有方法自吞异常并返回「放行」——去重门自身绝不能成为丢事件或影响宿主的原因。
 *
 * <p><b>已知取舍</b>：真正的嵌套执行（存储过程内部再执行语句、同一线程里语句套语句）只报最内层，
 * 外层那条会缺——计划 §六 已登记，与「双报」相比是更小的损害（双报会污染归属与计数）。
 *
 * <p><b>默认关（t-sql-insight 并入 master 2026-10-30 裁决）</b>：本门在 master 侧默认开
 * （T-CaptureCatchAll 券 07，随 v2026.10.31-beta.1 发行）；但 SQL 洞察线的执行身份模型
 * （[ExecutionScope]：异对象嵌套＝两次独立执行、各自身份、No text/time dedup，守卫＝
 * SqlInsightWeaveProbeTest#nestedDifferentStatementRemainsDistinctAndCanArriveOutOfOrder）
 * 与「外层一律抑制」正面相撞，运行时行为不可调和。本分支以洞察保真度优先＝默认关；
 * 生产确遇 Hikari/MyBatis 包装层双报可置 {@code -Dsqlvis.agent.nestGate=true} 恢复只报
 * 最内层（NestGateTest 以显式开闸钉住门能力不回归）。分支→dev 合流时须显式重拍默认值。
 */
final class NestGate {

    /** 开关（默认关，见类 KDoc 裁决）：true = 开启去重（嵌套只报最内层）。 */
    static final String ENABLED_PROP = "sqlvis.agent.nestGate";

    private static volatile boolean enabled = resolveEnabled();

    /** 每线程嵌套态：depth = 当前已织 execute 帧数；innerEmitted = 本链已有内层产过事件。 */
    private static final class State {
        int depth;
        boolean innerEmitted;
        /**
         * 上次出栈帧的 token（T-OracleWrapperRegistry 同批修复）：一次调用可能命中**两条** ATHROW 出口钩子
         * （try 内抛出的 athrow + 处理器清理后的重抛），同一帧会被出口钩子访问两次。若两次都出栈，
         * 深度会被多减一次、嵌套判定随之错位（真机实证：一条失败 DDL 产出 3 条事件）。
         */
        long lastExitToken;
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
            final State s = STATE.get();
            if (s.depth == 0) {
                // 上一条链已完结：复用同一 State 并复位（等价于旧实现在出口 remove，
                // 但不会每次执行都新建对象，也不留「深度 0 却仍标记已产出」的残态）。
                // lastExitToken 也在这里清——它只服务「同帧第二条 ATHROW」这一窗口。
                s.innerEmitted = false;
                s.lastExitToken = 0L;
            }
            s.depth++;
        } catch (Throwable ignore) {
            // 门自身失败：不计数即等于关闭本帧去重（放行），绝不影响采集
        }
    }

    /**
     * 当前线程离开一个已织 execute 帧。
     *
     * @param token 入口回调返回的计时起点（同一帧的唯一标识）
     * @return true = 本帧**可以尝试**产出（链上还没有内层真的产过事件）
     */
    static boolean exit(long token) {
        if (!enabled) {
            return true;
        }
        try {
            final State s = STATE.get();
            if (s.lastExitToken != 0L && s.lastExitToken == token) {
                // 同一帧的第二个出口（try 内 athrow + 处理器重抛，字节码层是两条 ATHROW）：
                // 已经出栈过一次，不再重复出栈、也不再产出。**这条判定必须排在深度复位之前**——
                // 最外层帧第一次出栈时深度已归零，若先按「深度<=0」放行，同帧第二条 ATHROW 会再产一条
                // （真机实证：一条失败 DDL 出 3 条事件）。
                return false;
            }
            s.lastExitToken = token;
            if (s.depth > 0) {
                s.depth--;
            }
            final boolean mayEmit = !s.innerEmitted;
            if (s.depth == 0) {
                // 最外层已退出：此后没有更外层需要被抑制，清标记即为完整复位。
                // lastExitToken 刻意不清——同帧的第二条 ATHROW 紧随其后，要靠它去重；下一次
                // enter()（depth==0）才重置。
                s.innerEmitted = false;
            }
            return mayEmit;
        } catch (Throwable t) {
            return true; // 门自身失败：放行（宁可多报，不可漏报）
        }
    }

    /**
     * 本帧**真的产出事件之后**调用（T-OracleWrapperRegistry）。
     *
     * <p><b>为什么不能像旧实现那样在 {@link #exit()} 里就置位。</b>{@code exit()} 只知道「本帧
     * 拿到发言权」，还不知道 SQL 是否取得到：登记表盲区（内层物理语句无登记）会让本帧一条都不产。
     * 旧实现把「进入过」当「产出过」，于是一次「内层盲区、外层有登记」的嵌套——正是 Oracle 包装器
     * 语句的形态——连外层那条本可产出的 SQL 一起被吞掉。置位只在这里做，且仅当 `depth > 0`
     * （最外层后面没有帧需要抑制）。
     */
    static void markEmitted() {
        if (!enabled) {
            return;
        }
        try {
            final State s = STATE.get();
            if (s.depth > 0) {
                s.innerEmitted = true;
            }
        } catch (Throwable ignore) {
            // 门自身失败：不抑制外层（宁可多报，不可漏报）
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
                if ("true".equalsIgnoreCase(v) || "1".equals(v)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
            // 非法值按默认关
        }
        return false;
    }
}
