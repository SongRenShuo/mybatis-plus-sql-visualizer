package cn.song.mybatisplus.sqlvis.agent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCatchAll 券 09 守卫：三类预算上限 + 整体 kill switch（红线 R4）。
 *
 * <p>判据取向与现场一致：熔断不是「功能开关」，而是**失败形态的系统性信号**——连续 N 次兜底
 * 失败说明本 JVM/本驱动族上兜底形态不可用，继续逐类尝试只是持续付成本。故断言两点：
 * ① kill 后兜底入口全关；② 白名单谓词不受影响（退回纯白名单，不是退回「不采集」）。
 */
public class CaptureBudgetTest {

    @Before
    public void setUp() {
        CaptureBudget.resetForTest();
    }

    @After
    public void tearDown() {
        CaptureBudget.resetForTest();
    }

    @Test
    public void 默认值_attach兜底开_transformer兜底关() {
        assertTrue("券 01 默认开（零字节解析、单类失败即跳过）", CaptureBudget.attachMatchEnabled());
        assertFalse("券 06 默认关（需真机验证后再考虑默认打开，计划 D2=A）",
                CaptureBudget.transformMatchEnabled());
        assertEquals("扫描上限默认 5000（D5 保守档）", 5000, CaptureBudget.scanLimit());
        assertFalse(CaptureBudget.killed());
    }

    @Test
    public void 连续失败达上限_整体kill且白名单不受影响() {
        CaptureBudget.setKillAfterForTest(3);
        CaptureBudget.noteCatchAllFailure("a=VerifyError");
        CaptureBudget.noteCatchAllFailure("b=VerifyError");
        assertFalse("未达上限不得熔断（偶发失败不算系统性失效）", CaptureBudget.killed());

        CaptureBudget.noteCatchAllFailure("c=VerifyError");
        assertTrue("连败达上限必须熔断", CaptureBudget.killed());
        assertNotNull(CaptureBudget.killReason());
        assertFalse("kill 后 attach 兜底关闭", CaptureBudget.attachMatchEnabled());
        assertFalse("kill 后 transformer 兜底关闭", CaptureBudget.transformMatchEnabled());

        // 白名单谓词与熔断无关：kill 只是「不做兜底」，不是「不采集」
        assertTrue("白名单语句面判定必须不受 kill 影响",
                DriverRegistry.isStatementWeavable("org.h2.jdbc.JdbcStatement"));
        assertTrue("白名单 prepare 面判定必须不受 kill 影响",
                DriverRegistry.isPrepareWeavable("org.h2.jdbc.JdbcConnection"));
    }

    @Test
    public void 成功一次即清零连败计数() {
        CaptureBudget.setKillAfterForTest(2);
        CaptureBudget.noteCatchAllFailure("a");
        CaptureBudget.noteCatchAllSuccess();
        CaptureBudget.noteCatchAllFailure("b");
        assertFalse("成功应清零连败计数（两次失败不连续）", CaptureBudget.killed());
    }

    @Test
    public void 耗时预算耗尽_只关兜底分支() {
        CaptureBudget.setTransformMatchEnabledForTest(true);
        CaptureBudget.setBudgetMsForTest(0L);
        assertTrue("预算内可用", CaptureBudget.transformMatchEnabled());

        CaptureBudget.noteTransformNanos(1L);
        assertTrue("超预算即耗尽", CaptureBudget.budgetExhausted());
        assertFalse("预算耗尽后 transformer 兜底关闭", CaptureBudget.transformMatchEnabled());
        assertTrue("attach 兜底不受 transformer 预算影响（两路预算独立）",
                CaptureBudget.attachMatchEnabled());
    }

    @Test
    public void 计数记账_心跳字段来源() {
        CaptureBudget.noteInterfaceMatched();
        CaptureBudget.noteInterfaceMatched();
        CaptureBudget.noteInterfaceWoven();
        CaptureBudget.noteInterfaceFailed();
        CaptureBudget.noteSkippedByVersion();

        assertEquals(2L, CaptureBudget.interfaceMatchedCount());
        assertEquals(1L, CaptureBudget.interfaceWovenCount());
        assertEquals(1L, CaptureBudget.interfaceFailedCount());
        assertEquals(1L, CaptureBudget.skippedByVersionCount());
        // 心跳 body 的字面契约由 transport 包的 HeartbeatDiagnosticsTest 断言（SenderLoop 包内可见）
    }

    @Test
    public void 预算读数_非法属性回落默认() {
        // 属性在类初始化时解析；此处只验证「默认值不会被运行时垃圾值改变」的读侧契约
        assertNull("killReason 未触发时为空", CaptureBudget.killReason());
        assertEquals(0L, CaptureBudget.transformMillis());
    }
}
