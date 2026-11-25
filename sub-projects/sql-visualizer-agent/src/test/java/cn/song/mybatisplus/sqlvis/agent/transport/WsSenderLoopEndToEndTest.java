package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.After;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T4-1：loopback WebSocket 会话端到端（真 socket、真握手、真帧）。
 *
 * <p>这条用例证明「事件语义逐字不变」不是靠读代码推断的：agent 侧 {@link WsSenderLoop} 在一个
 * 临时端口上监听，测试扮演插件客户端完成 HTTP 升级，然后
 * <ul>
 *   <li>连上即收 HELLO（cmd=4）与补发 HEARTBEAT（cmd=2），body 与 TCP 路径同源；</li>
 *   <li>{@code EventBus.offer} 的事件以 cmd=1 帧到达，body 含原 SQL 与 {@code "sql"} 键；</li>
 *   <li>客户端掩码帧下发 CONFIG 后收到 CONFIG_ACK（cmd=5），且 {@link AgentState} 真被停采。</li>
 * </ul>
 * 走的是 {@link WsFrameCodec#readFrame}/{@link WsFrameCodec#decodeEnvelope} 与生产同一套拆帧层，
 * 不是绕过协议直接断言字符串。
 */
public class WsSenderLoopEndToEndTest {

    private Socket socket;

    @After
    public void cleanUp() {
        AgentState.setDisabled(false);
        if (socket != null) {
            try {
                socket.close();
            } catch (java.io.IOException ignore) {
            }
        }
    }

    @Test(timeout = 20_000L)
    public void 会话_收HELLO心跳事件并回CONFIG_ACK() throws Exception {
        AgentState.setDisabled(false);
        final int port = freePort();
        final Thread server = new Thread(new WsSenderLoop(port), "test-ws-capture");
        server.setDaemon(true);
        server.start();

        socket = connectWithRetry(port);
        socket.setSoTimeout(3_000);
        final InputStream in = new BufferedInputStream(socket.getInputStream());
        final OutputStream out = new BufferedOutputStream(socket.getOutputStream());

        final String clientKey = "test-ws-client-key";
        out.write(("GET /capture HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + clientKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        final String response = WsFrameCodec.readHttpRequest(in);
        assertNotNull("必须收到升级回执", response);
        assertTrue(response, response.startsWith("HTTP/1.1 101 "));
        assertTrue(response, response.contains(WsFrameCodec.acceptKey(clientKey)));

        final WsFrameCodec.Inbound hello = readUntil(in, EventBus.CMD_HELLO, 3);
        assertTrue("HELLO body 带 pid：" + hello.body, hello.body.contains("\"pid\""));

        final WsFrameCodec.Inbound heartbeat = readUntil(in, EventBus.CMD_HEARTBEAT, 3);
        assertTrue("心跳 body 与 TCP 同源：" + heartbeat.body, heartbeat.body.contains("\"weavable\""));

        final SqlEvent event = new SqlEvent();
        event.sql = "select 1 from dual";
        event.timestamp = System.currentTimeMillis();
        EventBus.offer(event);
        final WsFrameCodec.Inbound delivered = readUntil(in, EventBus.CMD_EVENT, 10);
        assertTrue("事件 body 含原 SQL：" + delivered.body, delivered.body.contains("select 1 from dual"));
        assertTrue("事件 body 保持 TCP 键名：" + delivered.body, delivered.body.contains("\"sql\""));

        sendConfig(out, EventBus.CMD_CONFIG, "{\"disabled\":true}");
        final WsFrameCodec.Inbound ack = readUntil(in, EventBus.CMD_CONFIG_ACK, 10);
        assertTrue("回执反映生效值：" + ack.body, ack.body.contains("\"disabled\":true"));
        assertEquals(true, AgentState.isDisabled());

        // 复位：停采会清空队列，后续同 JVM 用例不应继承该状态
        sendConfig(out, EventBus.CMD_CONFIG, "{\"disabled\":false}");
        final WsFrameCodec.Inbound resumed = readUntil(in, EventBus.CMD_CONFIG_ACK, 10);
        assertTrue(resumed.body, resumed.body.contains("\"disabled\":false"));
        assertEquals(false, AgentState.isDisabled());
    }

    private static void sendConfig(OutputStream out, int cmd, String body) throws Exception {
        out.write(WsFrameCodec.encodeMasked(WsFrameCodec.OP_TEXT, WsFrameCodec.envelope(cmd, body),
                new byte[] {0x11, 0x22, 0x33, 0x44}));
        out.flush();
    }

    /** 读到指定 cmd 的信封为止（跳过心跳/其他控制帧），超过 max 帧视为失败。 */
    private static WsFrameCodec.Inbound readUntil(InputStream in, int cmd, int maxFrames) throws Exception {
        final StringBuilder seen = new StringBuilder();
        for (int i = 0; i < maxFrames; i++) {
            final WsFrameCodec.Frame frame = WsFrameCodec.readFrame(in);
            if ((frame.opcode & 0x0F) != WsFrameCodec.OP_TEXT) {
                seen.append("op").append(frame.opcode & 0x0F).append(' ');
                continue;
            }
            final WsFrameCodec.Inbound inbound = WsFrameCodec.decodeEnvelope(frame.text());
            assertNotNull("无法解析信封：" + frame.text(), inbound);
            seen.append("cmd").append(inbound.cmd).append(' ');
            if (inbound.cmd == cmd) {
                return inbound;
            }
        }
        throw new AssertionError("在 " + maxFrames + " 帧内未收到 cmd=" + cmd + "，实际读到：" + seen);
    }

    private static int freePort() throws Exception {
        final ServerSocket probe = new ServerSocket(0);
        final int port = probe.getLocalPort();
        probe.close();
        return port;
    }

    private static Socket connectWithRetry(int port) throws Exception {
        ConnectException last = null;
        for (int i = 0; i < 100; i++) {
            final Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 200);
                return candidate;
            } catch (ConnectException refused) {
                last = refused;
                candidate.close();
                Thread.sleep(20L);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("未连上测试 WS 端口");
    }
}