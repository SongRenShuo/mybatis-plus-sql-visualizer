package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Real loopback frames: legacy negotiation remains unchanged, v2 control bypasses SQL queue. */
public class SqlInsightControlFrameTest {
    @Test public void controlRequiresOptInAndStopIsOnlyRunScopedEvidence() throws Exception {
        boolean saved = AgentState.isDisabled();
        AgentState.setDisabled(false);
        EventBus.clearQueued();
        Thread sender = null;
        Socket peer = null;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            sender = new Thread(new SenderLoop(server.getLocalPort(), 1, 1, 2000, 1));
            sender.setDaemon(true);
            sender.start();
            peer = server.accept();
            peer.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(peer.getInputStream());
            DataOutputStream out = new DataOutputStream(peer.getOutputStream());
            Frame hello = read(in);
            assertEquals(EventBus.CMD_HELLO, hello.cmd);
            assertTrue(hello.body.contains("\"protocolVersion\":2"));
            assertEquals(EventBus.CMD_HEARTBEAT, read(in).cmd);
            SenderLoop.writeFrame(out, EventBus.CMD_CONFIG, "{\"disabled\":false}");
            assertEquals(EventBus.CMD_CONFIG_ACK, read(in).cmd);
            // A second legacy CONFIG must still produce ACK as the next frame, not cmd6.
            SenderLoop.writeFrame(out, EventBus.CMD_CONFIG, "{\"disabled\":false}");
            assertEquals(EventBus.CMD_CONFIG_ACK, read(in).cmd);
            SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                    "{\"disabled\":true,\"captureState\":true}");
            assertEquals(EventBus.CMD_CONFIG_ACK, read(in).cmd);
            Frame state = read(in);
            assertEquals(EventBus.CMD_CAPTURE_STATE, state.cmd);
            assertTrue("kind=STOP 断言失败，实际 body=" + state.body,
                    state.body.contains("\"kind\":\"STOP\""));
            assertTrue(state.body.contains("\"terminal\":true"));
            assertTrue(state.body.contains("NO_REQUEST_BOUNDARIES"));
            assertTrue(state.body.contains("NO_DELIVERY_ACK"));
            SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                    "{\"disabled\":false,\"captureState\":false}");
            assertEquals(EventBus.CMD_CONFIG_ACK, read(in).cmd);
            SenderLoop.writeFrame(out, EventBus.CMD_CONFIG, "{\"disabled\":false}");
            assertEquals(EventBus.CMD_CONFIG_ACK, read(in).cmd);
        } finally {
            if (sender != null) sender.interrupt();
            if (peer != null) peer.close();
            if (sender != null) sender.join(3000);
            AgentState.setDisabled(saved);
            EventBus.clearQueued();
        }
    }
    @Test public void negotiatedSocketCarriesRequestAndTransactionCommands() throws Exception {
        boolean saved = AgentState.isDisabled();
        AgentState.setDisabled(false); EventBus.clearQueued();
        Thread sender = null; Socket peer = null;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            sender = new Thread(new SenderLoop(server.getLocalPort(), 1, 1, 2000, 1));
            sender.setDaemon(true); sender.start(); peer = server.accept(); peer.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(peer.getInputStream());
            DataOutputStream out = new DataOutputStream(peer.getOutputStream());
            assertEquals(4, read(in).cmd); assertEquals(2, read(in).cmd);
            SenderLoop.writeFrame(out, EventBus.CMD_CONFIG,
                    "{\"disabled\":false,\"requestTracing\":true,\"transactionTracing\":true,\"captureStateToken\":\"ab12\"}");
            assertEquals(5, read(in).cmd); assertEquals(6, read(in).cmd);
            long request = cn.song.mybatisplus.sqlvis.agent.hook.RequestHooks.enter(null);
            cn.song.mybatisplus.sqlvis.agent.hook.RequestHooks.exit(request, null);
            TraceBus.emit(8, TraceBus.revision(), CaptureLedger.captureEpoch(), TraceBus.facts("COMMIT"));
            Frame start = read(in), end = read(in), transaction = read(in);
            assertEquals(7, start.cmd); assertEquals(7, end.cmd); assertEquals(8, transaction.cmd);
            assertTrue(start.body.contains("ab12")); assertTrue(end.body.contains("END"));
            assertTrue(transaction.body.contains("ab12"));
        } finally {
            if (sender != null) sender.interrupt(); if (peer != null) peer.close();
            if (sender != null) sender.join(3000);
            TraceBus.disconnected(); AgentState.setDisabled(saved); EventBus.clearQueued();
        }
    }

    @Test public void nonceEchoIsBoundedAndReplacedOnlyByExplicitConfig() {
        boolean saved = AgentState.isDisabled();
        try {
            SenderLoop loop = new SenderLoop(0);
            assertFalse(loop.captureStateBody().contains("captureStateToken"));
            loop.handleInbound(EventBus.CMD_CONFIG, "{\"captureState\":true,\"captureStateToken\":\"aB0123\"}");
            assertTrue(loop.captureStateBody().contains("\"captureStateToken\":\"aB0123\""));
            loop.handleInbound(EventBus.CMD_CONFIG, "{\"captureState\":true}");
            assertTrue(loop.captureStateBody().contains("aB0123"));
            loop.handleInbound(EventBus.CMD_CONFIG, "{\"captureStateToken\":\"ff22\"}");
            assertTrue(loop.captureStateBody().contains("ff22"));
            assertFalse(loop.captureStateBody().contains("aB0123"));
            loop.handleInbound(EventBus.CMD_CONFIG, "{\"captureStateToken\":\"not-hex\"}");
            assertFalse(loop.captureStateBody().contains("captureStateToken"));
            char[] tooLong = new char[65]; java.util.Arrays.fill(tooLong, 'a');
            loop.handleInbound(EventBus.CMD_CONFIG, "{\"captureStateToken\":\"" + new String(tooLong) + "\"}");
            assertFalse(loop.captureStateBody().contains("captureStateToken"));
        } finally { AgentState.setDisabled(saved); }
    }

    private static Frame read(DataInputStream in) throws IOException {
        int len = in.readInt(), cmd = in.readUnsignedByte();
        byte[] bytes = new byte[len]; in.readFully(bytes);
        return new Frame(cmd, new String(bytes, StandardCharsets.UTF_8));
    }
    private static class Frame {
        final int cmd; final String body;
        Frame(int cmd, String body) { this.cmd = cmd; this.body = body; }
    }
}
