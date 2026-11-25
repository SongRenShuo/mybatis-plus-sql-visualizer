package cn.song.mybatisplus.sqlvis.agent.transport;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T4-1：RFC 6455 帧编解码与信封的金标准（agent 侧 WebSocket 服务端）。
 *
 * <p>验收要求「至少一个 ws 帧编解码测试」；本类把四条契约钉在字节上：
 * <ul>
 *   <li>文本帧头 FIN=1/opcode=1、服务端不掩码，长度字段 7/16/64 位三档正确；</li>
 *   <li>客户端掩码帧能解码回原文（服务端必须接受真实客户端的掩码）；</li>
 *   <li>握手 {@code Sec-WebSocket-Accept} 与 RFC 6455 示例逐字一致；</li>
 *   <li>信封 {@code {"cmd":N,"body":"…"}} 往返后 body 逐字节不变（含引号/换行/Unicode 转义）。</li>
 * </ul>
 */
public class WsFrameCodecTest {

    @Test
    public void 文本帧_头字节与载荷逐字节正确() {
        final byte[] frame = WsFrameCodec.encodeText("hi");
        assertArrayEquals(new byte[] {(byte) 0x81, 0x02, 'h', 'i'}, frame);
    }

    @Test
    public void 长度字段_126与65536走扩展长度() {
        final byte[] medium = WsFrameCodec.encodeText(repeat('a', 126));
        assertEquals((byte) 0x81, medium[0]);
        assertEquals(126, medium[1] & 0xFF);
        assertEquals(0, medium[2] & 0xFF);
        assertEquals(126, medium[3] & 0xFF);
        assertEquals(2 + 2 + 126, medium.length);

        final byte[] large = WsFrameCodec.encodeText(repeat('b', 65536));
        assertEquals(127, large[1] & 0xFF);
        // 64 位长度 0x0000000000010000：第 7、8 字节为 0x01、0x00
        assertEquals(1, large[7] & 0xFF);
        assertEquals(0, large[8] & 0xFF);
        assertEquals(2 + 8 + 65536, large.length);
    }

    @Test
    public void 客户端掩码帧可解码回原文() throws Exception {
        final byte[] payload = "select 1".getBytes(StandardCharsets.UTF_8);
        final byte[] key = new byte[] {0x37, (byte) 0xfa, 0x21, 0x3d};
        final byte[] masked = WsFrameCodec.encodeMasked(WsFrameCodec.OP_TEXT, payload, key);
        assertTrue("客户端帧必须带掩码位", (masked[1] & 0x80) != 0);
        final WsFrameCodec.Frame frame =
                WsFrameCodec.readFrame(new ByteArrayInputStream(masked));
        assertTrue(frame.fin);
        assertEquals(WsFrameCodec.OP_TEXT, frame.opcode);
        assertEquals("select 1", frame.text());
    }

    @Test
    public void 不掩码帧同样可解码_用于服务端对称性() throws Exception {
        final WsFrameCodec.Frame frame =
                WsFrameCodec.readFrame(new ByteArrayInputStream(WsFrameCodec.encodeText("x")));
        assertEquals("x", frame.text());
    }

    @Test
    public void 握手回执与RFC6455示例一致() {
        // RFC 6455 §1.3 的示例 key 与其 accept 值（逐字符构造，避免长串被工具改写）
        final String clientKey = new String(new char[] {
            'd', 'G', 'h', 'l', 'I', 'H', 'N', 'h', 'b', 'X', 'B', 's', 'Z', 'S', 'B', 'u',
            'b', '2', '5', 'j', 'Z', 'Q', '=', '='});
        final String expectedAccept = new String(new char[] {
            's', '3', 'p', 'P', 'L', 'M', 'B', 'i', 'T', 'x', 'a', 'Q', '9', 'k', 'Y', 'G',
            'z', 'z', 'h', 'Z', 'R', 'b', 'K', '+', 'x', 'O', 'o', '='});
        assertEquals(expectedAccept, WsFrameCodec.acceptKey(clientKey));
        final String response = WsFrameCodec.handshakeResponse(clientKey);
        assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
        assertTrue(response.contains("Upgrade: websocket\r\n"));
        assertTrue(response.contains("Sec-WebSocket-Accept: " + expectedAccept + "\r\n"));
        assertTrue("回执必须以空行结束", response.endsWith("\r\n\r\n"));
    }

    @Test
    public void 信封往返_body含引号换行Unicode时不失真() {
        final String body = "{\"disabled\":true,\"q\":\"a\\\"b\",\"n\":\"行\\n二\",\"u\":\"中\"}";
        final WsFrameCodec.Inbound decoded =
                WsFrameCodec.decodeEnvelope(new String(WsFrameCodec.envelope(EventBus.CMD_CONFIG, body),
                        StandardCharsets.UTF_8));
        assertEquals(EventBus.CMD_CONFIG, decoded.cmd);
        assertEquals(body, decoded.body);
    }

    @Test
    public void 信封缺body键返回null而不抛错() {
        final WsFrameCodec.Inbound decoded =
                WsFrameCodec.decodeEnvelope(new String(WsFrameCodec.envelope(4, null),
                        StandardCharsets.UTF_8));
        assertEquals(4, decoded.cmd);
        assertNull(decoded.body);
        assertNull(WsFrameCodec.decodeEnvelope("not an envelope"));
        assertNull(WsFrameCodec.decodeEnvelope("{\"cmd\":1,\"body\":\"unterminated"));
    }

    @Test
    public void 超过1MiB的单帧显式拒绝而不是写出() {
        try {
            WsFrameCodec.encodeText(repeat('x', WsFrameCodec.MAX_PAYLOAD + 1));
            fail("超限必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    @Test
    public void 入站超限帧按协议错抛IOException() {
        final byte[] header = new byte[10];
        header[0] = (byte) 0x81;
        header[1] = 127;
        final long tooLarge = (long) WsFrameCodec.MAX_PAYLOAD + 1;
        for (int i = 0; i < 8; i++) {
            header[2 + i] = (byte) (tooLarge >>> (8 * (7 - i)));
        }
        try {
            WsFrameCodec.readFrame(new ByteArrayInputStream(header));
            fail("超限入站帧必须抛 IOException 走重连");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    @Test
    public void 关闭帧与pong帧的opcode正确() {
        final byte[] close = WsFrameCodec.encodeClose();
        assertEquals(WsFrameCodec.OP_CLOSE, close[0] & 0x0F);
        assertTrue((close[0] & 0x80) != 0);
        final byte[] pong = WsFrameCodec.encodePong(new byte[] {1, 2});
        assertEquals(WsFrameCodec.OP_PONG, pong[0] & 0x0F);
        assertEquals(2, pong[1] & 0xFF);
    }

    @Test
    public void 升级请求头解析_大小写不敏感() {
        final String request = "GET /capture HTTP/1.1\r\n"
                + "host: 127.0.0.1:9000\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: abc==\r\n"
                + "\r\n";
        assertEquals("websocket", WsFrameCodec.header(request, "upgrade"));
        assertEquals("Upgrade", WsFrameCodec.header(request, "connection"));
        assertEquals("abc==", WsFrameCodec.header(request, "sec-websocket-key"));
        assertNull(WsFrameCodec.header(request, "missing"));
        assertFalse(WsFrameCodec.header(request, "upgrade").isEmpty());
    }

    private static String repeat(char c, int count) {
        final char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }
}