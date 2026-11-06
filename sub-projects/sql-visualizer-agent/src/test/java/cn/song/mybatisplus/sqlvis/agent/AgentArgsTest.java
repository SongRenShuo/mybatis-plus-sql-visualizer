package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** [AgentArgs] 解析测试（§4.3 参数契约）。 */
public class AgentArgsTest {

    @Test
    public void 标准形态() {
        AgentArgs a = AgentArgs.parse("port=53210;logLevel=warn");
        assertEquals(53210, a.getPort());
        assertEquals("warn", a.getLogLevel());
        assertFalse(a.isStartDisabled());
    }

    @Test
    public void 逗号分隔与缺省() {
        AgentArgs a = AgentArgs.parse("port=123,logLevel=info,disable=true");
        assertEquals(123, a.getPort());
        assertEquals("info", a.getLogLevel());
        assertTrue(a.isStartDisabled());
    }

    @Test
    public void 无参缺省端口0放弃挂载() {
        assertEquals(0, AgentArgs.parse(null).getPort());
        assertEquals(0, AgentArgs.parse("").getPort());
        assertEquals(0, AgentArgs.parse("logLevel=warn").getPort());
    }

    @Test
    public void 非法端口容错() {
        assertEquals(0, AgentArgs.parse("port=abc").getPort());
        assertEquals(7, AgentArgs.parse("port=7,unknown=1").getPort());
    }

    /** T-SAF-02：通道令牌键解析（agent 原样持有、HELLO 回带）；旧 IDE 不下发该键 → null。 */
    @Test
    public void 通道令牌解析() {
        AgentArgs a = AgentArgs.parse("port=53210;stackFrames=24;token=abc123def4567890");
        assertEquals("abc123def4567890", a.getToken());
        assertNull("旧 IDE 不下发 token 键 → null（HELLO 不带 token 键，行为与修复前一致）",
                AgentArgs.parse("port=53210").getToken());
        assertNull("空值按未下发处理", AgentArgs.parse("port=53210;token=").getToken());
    }
}
