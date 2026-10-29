package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-StackBizFrame：调用栈摘要的栈底锚点三层判定与「定位帧必在栈内」守卫。
 *
 * <p>背景（2026-10-29 用户报障）：定时任务线程（{@code @Scheduled}）下增强捕获的栈快照里看不到
 * 任何项目调用类，而 meta 行的位置列正常显示 {@code MerchantWalletRechargeServiceImpl.java:32}。
 * 根因是旧实现从栈底穿透时只认本类的 infra 白名单，{@code org.springframework.scheduling.*} 与
 * {@code io.micrometer.*} 不在名单 → 穿透在栈底第一帧停住 → 锚点=栈底 → 业务帧全进省略段
 * （省略段采集期丢弃，弹层展开也补不回来）。
 *
 * <p>覆盖：用户形态（定时任务链）、Web 形态不劣化、fat jar 形态走层二、多段业务帧走 S2 插入、
 * 定位帧缺省时的行为不变。来源判定用 {@link StackLocator#jarOriginProbe} 注入（真机 loader 读数
 * 见计划文档的实施记录，不在单测里伪造）。
 */
public class StackDigestBizTailTest {

    @After
    public void restore() {
        AgentState.setStackMaxFrames(24);
        StackLocator.jarOriginProbe = null;
        StackLocator.setExtraBizJarPrefixes(null);
        StackLocator.clearLocatorCachesForTest();
    }

    private static StackTraceElement f(String cls, String method, int line) {
        return new StackTraceElement(cls, method, cls.substring(cls.lastIndexOf('.') + 1) + ".java", line);
    }

    /** 业务类来自目录（IDE 内跑应用），其余来自 JAR。 */
    private static void businessFromDirectory(String... prefixes) {
        StackLocator.jarOriginProbe = (className, loader) -> {
            for (String p : prefixes) {
                if (className.startsWith(p)) {
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        };
    }

    // ------------------------------------------------------------------
    // 形态一：用户报障原样（@Scheduled 定时任务链）
    // ------------------------------------------------------------------

    private static List<StackTraceElement> scheduledTaskChain() {
        List<StackTraceElement> l = new ArrayList<>();
        l.add(f("java.lang.Thread", "getStackTrace", -1));
        l.add(f("cn.song.mybatisplus.sqlvis.agent.hook.WovenEvents", "emit", 70));
        l.add(f("cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks", "onExecuteExit", 161));
        l.add(f("org.postgresql.jdbc.PgPreparedStatement", "execute", 182));
        l.add(f("com.zaxxer.hikari.pool.ProxyPreparedStatement", "execute", 44));
        l.add(f("com.zaxxer.hikari.pool.HikariProxyPreparedStatement", "execute", -1));
        l.add(f("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0", -1));
        l.add(f("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke", 77));
        l.add(f("jdk.internal.reflect.DelegatingMethodAccessorImpl", "invoke", 43));
        l.add(f("java.lang.reflect.Method", "invoke", 569));
        l.add(f("org.apache.ibatis.logging.jdbc.PreparedStatementLogger", "invoke", 58));
        l.add(f("jdk.proxy3.$Proxy324", "execute", -1));
        l.add(f("org.apache.ibatis.executor.statement.PreparedStatementHandler", "query", 65));
        l.add(f("org.apache.ibatis.executor.statement.RoutingStatementHandler", "query", 80));
        for (int i = 0; i < 8; i++) {
            l.add(f("org.apache.ibatis.executor.SimpleExecutor" + i, "doQuery", 100 + i));
        }
        for (int i = 0; i < 10; i++) {
            l.add(f("org.apache.ibatis.binding.MapperMethod$MethodSignature" + i, "execute", 50 + i));
        }
        for (int i = 0; i < 12; i++) {
            l.add(f("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl" + i, "count", 200 + i));
        }
        for (int i = 0; i < 8; i++) {
            l.add(f("org.springframework.transaction.support.TransactionTemplate" + i, "execute", 300 + i));
        }
        l.add(f("cn.song.wallet.service.impl.MerchantWalletRechargeServiceImpl", "countExpiring", 32));
        l.add(f("cn.song.wallet.task.MerchantWalletRechargeTask", "run", 45));
        l.add(f("cn.song.task.service.impl.BizTaskRecordServiceImpl", "record", 40));
        for (int i = 0; i < 6; i++) {
            l.add(f("org.springframework.aop.framework.CglibAopProxy" + i, "intercept", 700 + i));
        }
        l.add(f("jdk.internal.reflect.DelegatingMethodAccessorImpl", "invoke", 43));
        l.add(f("java.lang.reflect.Method", "invoke", 569));
        l.add(f("org.springframework.scheduling.support.ScheduledMethodRunnable", "runInternal", 130));
        l.add(f("org.springframework.scheduling.support.ScheduledMethodRunnable", "lambda$run$2", 124));
        l.add(f("io.micrometer.observation.Observation", "observe", 498));
        l.add(f("org.springframework.scheduling.support.ScheduledMethodRunnable", "run", 124));
        l.add(f("org.springframework.scheduling.config.Task$OutcomeTrackingRunnable", "run", 87));
        l.add(f("org.springframework.scheduling.support.DelegatingErrorHandlingRunnable", "run", 54));
        return l;
    }

    @Test
    public void 定时任务链_项目帧必须出现在摘要里_调度壳不得占用栈底() {
        businessFromDirectory("cn.song.wallet.", "cn.song.task.");
        StackTraceElement[] stack = scheduledTaskChain().toArray(new StackTraceElement[0]);
        String d = StackDigest.build(stack, 24, StackDigestBizTailTest.class.getClassLoader(), null, null);
        assertNotNull(d);
        assertTrue("业务 Service 必须在摘要内: " + d, d.contains("MerchantWalletRechargeServiceImpl#countExpiring:32"));
        assertTrue("任务入口类必须在摘要内: " + d, d.contains("MerchantWalletRechargeTask#run:45"));
        assertTrue("任务内业务调用必须在摘要内: " + d, d.contains("BizTaskRecordServiceImpl#record:40"));
        assertFalse("调度壳不得占据栈底窗口: " + d,
                d.contains("ScheduledMethodRunnable") || d.contains("DelegatingErrorHandlingRunnable")
                        || d.contains("OutcomeTrackingRunnable") || d.contains("Observation#observe"));
        assertTrue("长度仍须有界", d.length() <= 24 * 96);
    }

    @Test
    public void 定时任务链_无loader提示时也应由框架前缀穿透接管() {
        // 层一依赖 codeSource，探针缺席时全部「未知」→ 层二必须用框架前缀表接管
        StackTraceElement[] stack = scheduledTaskChain().toArray(new StackTraceElement[0]);
        String d = StackDigest.build(stack, 24);
        assertNotNull(d);
        assertFalse("调度壳不得占据栈底窗口: " + d,
                d.contains("ScheduledMethodRunnable") || d.contains("DelegatingErrorHandlingRunnable"));
        assertTrue("栈底应落在最外层非框架帧（任务/业务侧）: " + d,
                d.contains("BizTaskRecordServiceImpl#record:40"));
    }

    // ------------------------------------------------------------------
    // 形态二：Web 请求不得劣化
    // ------------------------------------------------------------------

    @Test
    public void web链_Controller仍在摘要内_过滤器与未知尾帧不进快照() {
        businessFromDirectory("com.acme.");
        StackTraceElement[] s = new StackTraceElement[45];
        for (int i = 0; i < 30; i++) {
            s[i] = f("org.apache.ibatis.executor.Executor" + i, "query", i);
        }
        s[30] = f("com.acme.web.OrderController", "list", 30);
        for (int i = 31; i < 41; i++) {
            s[i] = f("org.springframework.web.servlet.DispatcherServlet", "service", i);
        }
        for (int i = 41; i < 45; i++) {
            s[i] = f("com.vendor.runtime.CustomPipeline" + i, "run", i);
        }
        String d = StackDigest.build(s, 24);
        assertTrue("Controller 必须在摘要内: " + d, d.contains("com.acme.web.OrderController#list:30"));
        assertFalse("过滤器链不得进快照", d.contains("DispatcherServlet"));
        assertFalse("未知第三方尾帧不得抢占栈底", d.contains("CustomPipeline"));
        assertTrue(d.length() <= 24 * 96);
    }

    // ------------------------------------------------------------------
    // 形态三：fat jar 部署（业务类也来自 JAR）——层一不命中，层二接管
    // ------------------------------------------------------------------

    @Test
    public void fatJar形态_层一不命中时层二接管且不抛异常() {
        StackLocator.jarOriginProbe = (className, loader) -> Boolean.TRUE;
        StackTraceElement[] s = new StackTraceElement[40];
        for (int i = 0; i < 30; i++) {
            s[i] = f("com.acme.service.OrderService" + i, "find", i);
        }
        for (int i = 30; i < 40; i++) {
            s[i] = f("org.springframework.scheduling.support.ScheduledMethodRunnable", "run", i);
        }
        String d = StackDigest.build(s, 24);
        assertNotNull(d);
        assertTrue("层二应锚定最外层非框架帧: " + d, d.contains("com.acme.service.OrderService29#find:29"));
        assertFalse("调度壳不得进快照", d.contains("ScheduledMethodRunnable"));
        assertTrue(d.length() <= 24 * 96);
    }

    // ------------------------------------------------------------------
    // 形态四：项目帧分散在长跨度上——定位帧必须被单列插入（S2）
    // ------------------------------------------------------------------

    /** 20 帧框架 + 项目帧 A + 39 帧框架 + 项目帧 B + 9 帧调度壳。 */
    private static StackTraceElement[] spreadProjectFrames() {
        StackTraceElement[] s = new StackTraceElement[70];
        for (int i = 0; i < 20; i++) {
            s[i] = f("org.apache.ibatis.executor.Executor" + i, "query", i);
        }
        s[20] = f("com.acme.service.OrderService", "find", 20);
        for (int i = 21; i < 60; i++) {
            s[i] = f("org.springframework.transaction.support.TransactionTemplate" + i, "execute", i);
        }
        s[60] = f("com.acme.task.OrderTask", "run", 60);
        for (int i = 61; i < 70; i++) {
            s[i] = f("org.springframework.scheduling.support.ScheduledMethodRunnable", "run", i);
        }
        return s;
    }

    @Test
    public void 项目帧分散_定位帧在窗口外时单列插入且省略数扣减() {
        businessFromDirectory("com.acme.");
        StackTraceElement[] s = spreadProjectFrames();
        // 锚点 = 最外层业务帧 OrderTask(60)；顶部窗口 [0,16)；定位帧 OrderService(20) 落在窗口之外
        String d = StackDigest.build(s, 24, StackDigestBizTailTest.class.getClassLoader(),
                "com.acme.service.OrderService", "find");
        String[] segs = d.split("<-");
        assertTrue("定位帧必须出现在摘要里: " + d, d.contains("com.acme.service.OrderService#find:20"));
        assertTrue("栈底业务帧仍在: " + d, d.contains("com.acme.task.OrderTask#run:60"));
        // 省略数 = 53 - 16 - 1（插入段从省略段里扣除）
        assertEquals("<…中间省略 36 帧…>", segs[17]);
        assertEquals("插入段紧跟顶部窗口", "com.acme.service.OrderService#find:20", segs[16]);
        assertEquals("插入段之后才是省略标记", 26, segs.length);
        assertTrue("顺序仍自栈顶到栈底", d.indexOf("OrderService#find:20") < d.indexOf("OrderTask#run:60"));
    }

    @Test
    public void 定位帧已在窗口内时不插入_格式与旧形态一致() {
        businessFromDirectory("com.acme.");
        StackTraceElement[] s = spreadProjectFrames();
        // 定位帧 = 栈底锚点本身（OrderTask 已在底部窗口内）
        String d = StackDigest.build(s, 24, StackDigestBizTailTest.class.getClassLoader(),
                "com.acme.task.OrderTask", "run");
        assertEquals("不得凭空多出段", 25, d.split("<-").length);
        assertEquals("定位帧已在窗口内时不得重复出现",
                d.indexOf("OrderTask#run:60"), d.lastIndexOf("OrderTask#run:60"));
    }

    @Test
    public void 定位帧经Frame出口传入_与全参出口一致() {
        businessFromDirectory("com.acme.");
        StackTraceElement[] s = spreadProjectFrames();
        StackLocator.Frame frame = new StackLocator.Frame(
                "com/acme/service/OrderService.java", 20, "OrderService#find",
                new ArrayList<>(), "L1", "com.acme.service.OrderService", "find");
        String viaFrame = StackDigest.build(s, StackDigestBizTailTest.class.getClassLoader(), frame);
        String viaArgs = StackDigest.build(s, 24, StackDigestBizTailTest.class.getClassLoader(),
                "com.acme.service.OrderService", "find");
        assertEquals(viaArgs, viaFrame);
        assertTrue(viaFrame.contains("com.acme.service.OrderService#find:20"));
    }

    @Test
    public void 定位帧不在快照内_静默忽略不插入() {
        businessFromDirectory("com.acme.");
        StackTraceElement[] s = spreadProjectFrames();
        String d = StackDigest.build(s, 24, StackDigestBizTailTest.class.getClassLoader(),
                "com.acme.service.Missing", "absent");
        assertEquals("身份不在场时不得插入空段", 25, d.split("<-").length);
    }

    @Test
    public void 空栈与null_行为不变() {
        assertNull(StackDigest.build(null, 24, null, null, null));
        assertNull(StackDigest.build(new StackTraceElement[0], 24, null, null, null));
        assertNull(StackDigest.build(new StackTraceElement[0],
                StackDigestBizTailTest.class.getClassLoader(), null));
    }
}
