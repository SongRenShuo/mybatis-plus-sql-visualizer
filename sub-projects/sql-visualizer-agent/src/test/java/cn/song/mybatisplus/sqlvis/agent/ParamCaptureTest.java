package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.ParamCapture;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T-CrossChannelResidual（20261105）：参数捕获覆盖面守卫。
 *
 * 背景：agent 侧漏捕一个参数，跨通道「参数值签名」就与控制台日志不等（控制台日志总是打全参数），
 * 同一次执行于是折不上——面板出现一条带耗时/位置、一条全是 `—`。本测试钉住两类此前漏捕的形态：
 *
 *  - 白名单缺 `setNClob` / `setRowId` / `setSQLXML` / `setRef` → 织入器不织钩子，参数整段缺失；
 *  - `setObject(int, Object, int, int)` 四参重载 → 织入器按「≥2 参」织了钩子，`tryCapture` 却按
 *    `args.length > 3` 拒绝，形成「织了不捕」。
 *
 * 同时守住非参数 setter 仍被拒绝（`setFetchSize` / `setMaxRows` / `setQueryTimeout` 不误记）。
 */
public class ParamCaptureTest {

    private static List<String[]> snapshotOf(ParamCapture c) {
        return c.snapshot("SELECT 1", false);
    }

    @Test
    public void 四参setObject_被捕获() {
        ParamCapture c = new ParamCapture();
        // setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
        final boolean captured = c.tryCapture("setObject", new Object[]{1, "42", 12, 2});
        assertTrue("四参 setObject 必须被捕获（此前 args.length > 3 直接拒绝）", captured);
        assertEquals("参数个数", 1, c.size());
        assertEquals("参数值", "42", snapshotOf(c).get(0)[1]);
    }

    @Test
    public void 三参setObject_回归不变() {
        ParamCapture c = new ParamCapture();
        // setObject(int parameterIndex, Object x, int targetSqlType)
        assertTrue(c.tryCapture("setObject", new Object[]{1, 7, 4}));
        assertEquals(1, c.size());
    }

    @Test
    public void 补充四族setter_在白名单且能捕获() {
        assertTrue("setNClob 应在白名单", ParamCapture.isSetter("setNClob"));
        assertTrue("setRowId 应在白名单", ParamCapture.isSetter("setRowId"));
        assertTrue("setSQLXML 应在白名单", ParamCapture.isSetter("setSQLXML"));
        assertTrue("setRef 应在白名单", ParamCapture.isSetter("setRef"));

        ParamCapture c = new ParamCapture();
        // 值形态由实参推断（simpleTypeName），这里只需验证白名单与捕获路径都通
        assertTrue(c.tryCapture("setNClob", new Object[]{1, "nclob-text"}));
        assertTrue(c.tryCapture("setRowId", new Object[]{2, "row-id"}));
        assertTrue(c.tryCapture("setSQLXML", new Object[]{3, "<x/>"}));
        assertTrue(c.tryCapture("setRef", new Object[]{4, "ref"}));
        assertEquals("四族全部入册", 4, c.size());
        assertEquals("参数序号连续、无缺口", 4, snapshotOf(c).size());
    }

    @Test
    public void 非参数setter_仍被拒绝() {
        ParamCapture c = new ParamCapture();
        // 单参形态：织入器侧 args.length >= 2 已挡，这里守 tryCapture 同口径
        assertFalse(c.tryCapture("setFetchSize", new Object[]{100}));
        assertFalse(c.tryCapture("setMaxRows", new Object[]{100}));
        assertFalse(c.tryCapture("setQueryTimeout", new Object[]{30}));
        // 白名单外
        assertFalse(c.tryCapture("setCursorName", new Object[]{"c"}));
        // 首参不是 Integer（不是参数索引）
        assertFalse(c.tryCapture("setString", new Object[]{"1", "x"}));
        // 超过四参上限
        assertFalse(c.tryCapture("setObject", new Object[]{1, "x", 12, 2, 3}));
        assertEquals("拒绝路径不得留下参数", 0, c.size());
    }

    @Test
    public void 非参数setter_不在白名单() {
        assertFalse(ParamCapture.isSetter("setFetchSize"));
        assertFalse(ParamCapture.isSetter("setMaxRows"));
        assertFalse(ParamCapture.isSetter("setQueryTimeout"));
        assertFalse(ParamCapture.isSetter("setPoolable"));
        assertFalse(ParamCapture.isSetter("setEscapeProcessing"));
    }
}
