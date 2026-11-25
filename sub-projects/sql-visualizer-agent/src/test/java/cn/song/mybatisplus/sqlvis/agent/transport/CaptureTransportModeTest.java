package cn.song.mybatisplus.sqlvis.agent.transport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T4-1：传输开关的默认值钉在 TCP 上（存量 301 例语义等价的前提）。
 *
 * <p>{@code EventBus.start} 只在启动时读一次系统属性；本类不启动任何线程，只断言解析结果。
 */
public class CaptureTransportModeTest {

    private String previous;

    @Before
    public void capturePrevious() {
        previous = System.getProperty(CaptureTransportMode.TRANSPORT_PROP);
    }

    @After
    public void restorePrevious() {
        if (previous == null) {
            System.clearProperty(CaptureTransportMode.TRANSPORT_PROP);
        } else {
            System.setProperty(CaptureTransportMode.TRANSPORT_PROP, previous);
        }
    }

    @Test
    public void 默认传输仍是裸TCP() {
        System.clearProperty(CaptureTransportMode.TRANSPORT_PROP);
        assertEquals(CaptureTransportMode.Mode.TCP, CaptureTransportMode.resolve());
        assertFalse("不设属性绝不允许切到 ws", CaptureTransportMode.isWebSocket());
    }

    @Test
    public void 系统属性ws启用WebSocket() {
        System.setProperty(CaptureTransportMode.TRANSPORT_PROP, "ws");
        assertEquals(CaptureTransportMode.Mode.WS, CaptureTransportMode.resolve());
        assertTrue(CaptureTransportMode.isWebSocket());
        System.setProperty(CaptureTransportMode.TRANSPORT_PROP, "  WebSocket ");
        assertEquals(CaptureTransportMode.Mode.WS, CaptureTransportMode.resolve());
    }

    @Test
    public void 显式tcp与非法值回落TCP() {
        for (String value : new String[] {"tcp", "TCP", "", "wss", "smtp"}) {
            System.setProperty(CaptureTransportMode.TRANSPORT_PROP, value);
            assertEquals("value=" + value, CaptureTransportMode.Mode.TCP, CaptureTransportMode.resolve());
        }
    }
}