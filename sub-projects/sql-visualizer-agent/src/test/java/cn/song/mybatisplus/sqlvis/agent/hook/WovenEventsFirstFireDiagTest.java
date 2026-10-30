package cn.song.mybatisplus.sqlvis.agent.hook;

import cn.song.mybatisplus.sqlvis.agent.FakeVendorFixtures;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T-AttachZeroEvent 守卫（2026-10-09 现场复盘）：织入通道**首条事件**取证打点。
 *
 * 现场：attach 后面板零条目时，「agent 没产事件」与「IDE 消费链丢事件」无从区分（对端
 * markReceived 计数无宿主侧出口）。本守卫锁定首条 stderr 行行为：
 * <ul>
 *   <li>首条 emit 恰打一行（含 op / sqlLen / registryUnresolved，**不含 SQL 原文**），宿主应用控制台可见；</li>
 *   <li>第二条起零输出（AtomicBoolean 一次，不刷屏宿主日志）；</li>
 *   <li>disabled / 空 SQL 短路路径不打点（未产事件的路径保持零噪音）。</li>
 * </ul>
 * 与 IDE 侧 AgentManager「首条事件已到达」INFO 行配对即三分故障域：宿主无首条=agent 侧；
 * 宿主有 IDE 无=传输；两侧都有而面板空=消费链（去重窗口/屏蔽/过滤）。
 */
public class WovenEventsFirstFireDiagTest {

    private PrintStream originalErr;
    private ByteArrayOutputStream captured;

    /** T-CiRelease（20261024）：捕获/读回两侧显式 UTF-8——默认 PrintStream 字符集随平台走
     * （本机 GBK 可编码中文取证行、EN-US runner 的 JDK17 默认 Cp1252 把整段打成 '?'，断言必挂），
     * 读写同 charset 自洽后与本机默认字符集解耦。 */
    private String capturedText() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    @Before
    public void captureStderr() {
        WovenEvents.resetFirstFireForTest();
        StatementRegistry.clearForTest();
        originalErr = System.err;
        captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        // 2026-10-30 用户令「控制台只保留必要的」：取证行已降为 verbose（logLevel=debug）。
        // 本类验的是取证行内容，故显式开 verbose；「默认静默」由下一个用例单独钉住。
        cn.song.mybatisplus.sqlvis.agent.CaptureBudget.setVerboseLogging("debug");
    }

    @After
    public void restoreStderr() {
        System.setErr(originalErr);
        WovenEvents.resetFirstFireForTest();
        StatementRegistry.clearForTest();
        cn.song.mybatisplus.sqlvis.agent.CaptureBudget.setVerboseLogging(null);
    }

    /**
     * 2026-10-30 用户令「控制台 [SqlVisAgent] 输出只保留必要的」：默认级别（warn）下
     * 首条事件取证行**不得**出现在宿主控制台——「钩子在产」这件事由 IDE 侧心跳诊断承载。
     */
    @Test
    public void 默认级别下首条事件不打印() {
        cn.song.mybatisplus.sqlvis.agent.CaptureBudget.setVerboseLogging(null);
        final FakeVendorFixtures.VendorPs stmt = new FakeVendorFixtures.VendorPs();
        final long token = System.nanoTime();
        WovenEvents.emit(stmt, "execute", "SELECT * FROM t", null, null, token, null);
        final String text = capturedText();
        assertFalse("默认级别下不得打印取证行（控制台只保留必要的）：" + text,
                text.contains("织入通道首条事件已产出"));
    }

    @Test
    public void 首条emit恰打一行取证_第二条起静默() {
        final FakeVendorFixtures.VendorPs stmt = new FakeVendorFixtures.VendorPs();
        final long token = System.nanoTime();
        WovenEvents.emit(stmt, "execute", "SELECT * FROM t", null, null, token, null);
        final String first = capturedText();
        assertTrue("首条事件必须打取证行（宿主侧「钩子在产」证据）",
                first.contains("织入通道首条事件已产出"));
        assertTrue("取证行须带 op（现场按 op 分流 execute 族形态）", first.contains("op=execute"));
        assertTrue("取证行须带盲区计数（与 registryUnresolved 现场对账）",
                first.contains("registryUnresolved=0"));
        // 2026-10-14：行内改带长度、不带原文——本行不过脱敏开关，故从源头不许落业务文本
        assertTrue("取证行须带 SQL 长度（取代原 sqlHead 的判读位）", first.contains("sqlLen=15"));
        assertFalse("取证行不得含 SQL 原文: " + first, first.contains("SELECT") || first.contains("FROM t"));
        final int linesAfterFirst = captured.size();
        WovenEvents.emit(stmt, "executeQuery", "SELECT 1", null, null, System.nanoTime(), null);
        assertEquals("只打首条——后续事件零输出（防宿主日志刷屏）", linesAfterFirst, captured.size());
    }

    @Test
    public void 短路路径不打点() {
        // 空 SQL：事件未产，保持零噪音
        WovenEvents.emit(new Object(), "execute", "", null, null, System.nanoTime(), null);
        assertEquals("", capturedText());
        // 首条名额未被空路径消耗：随后真事件照常打点
        WovenEvents.emit(new FakeVendorFixtures.VendorPs(), "execute", "SELECT 1",
                null, null, System.nanoTime(), null);
        assertTrue(capturedText().contains("织入通道首条事件已产出"));
    }
}
