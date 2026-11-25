package cn.song.mybatisplus.sqlvis.agent.transport;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T4-1：loopback WebSocket 发送主循环（{@link CaptureTransportMode} 选 ws 时替代 {@link SenderLoop}）。
 *
 * <p><b>为什么是服务端</b>：OpenIDEA 插件只有 {@code WebSocket::connect}（沙箱无裸 socket、无
 * ServerSocket），所以 agent 在 loopback 监听，插件作客户端连入。端口仍由 IDE 前置协商下发
 * （{@code port=…}），与 TCP 路径同源；地址恒为 loopback，不对外暴露。
 *
 * <p><b>事件语义逐字不变</b>：每个出站控制/事件帧的信封 {@code body} 就是 TCP 下 {@code writeFrame}
 * 会写出的同一串 JSON（HELLO、HEARTBEAT、EVENT、CONFIG_ACK 全同形）；入站 CONFIG 的解析与生效
 * 直接复用 {@link SenderLoop#handleInbound}，不复制一份逻辑。差异只在传输层：TCP 是
 * {@code [len][cmd][body]}，WS 是 RFC 6455 文本帧包一层 {@code {"cmd":N,"body":"…"}}。
 *
 * <p><b>会话语义</b>与 TCP 循环逐条对齐：连接即发 HELLO 并补发一条 HEARTBEAT；事件每轮尽出
 * ≤64 条防饿死心跳；出站静默达心跳间隔发 HEARTBEAT；入站 CONFIG 下行到 {@link AgentState}；
 * 会话建立后窗口内从未收到 CONFIG 则主动停采（看门狗）；burst 达上限且无入站数据时跳过读帧。
 * 断线只是本会话结束，主循环回到 {@code accept()} 等插件重连（重连退避由插件侧负责，T4-3）。
 *
 * <p>超限取舍：单帧载荷 &gt; {@link WsFrameCodec#MAX_PAYLOAD}（1 MiB）不写——{@link WsFrameCodec#encode}
 * 抛 {@link IllegalArgumentException}，该事件按「编码失败只丢该条」处理并计数，绝不写一个消费端
 * 必拒的超大帧，也绝不断连接（与 TCP 侧单事件编码失败的口径一致）。
 */
final class WsSenderLoop implements Runnable {

    /** 空转轮询节奏（与 {@link SenderLoop} 同值）。 */
    private static final long POLL_IDLE_MS = 200L;

    /** 单轮事件尽出上限（与 {@link SenderLoop} 同值）。 */
    private static final int EVENT_BURST_MAX = 64;

    /** 握手读超时（毫秒）：插件连上后应立即发升级请求，给 5s 防半开连接。 */
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    /** 连续 accept 失败的最短退避（毫秒），防止监听套接字异常时忙轮询。 */
    private static final long ACCEPT_BACKOFF_MS = 200L;

    /** 入站复用 TCP 侧 CONFIG 处理器（不复制解析/生效逻辑）。 */
    private static final SenderLoop CONFIG_HANDLER = new SenderLoop(0);

    /** 超出 1 MiB 的帧被显式丢弃的累计数（心跳/日志之外的观测面，测试可读）。 */
    private static final AtomicLong OVERSIZED = new AtomicLong();

    /** 一次性告警（未知/畸形入站帧不刷屏宿主日志）。 */
    private static final AtomicBoolean WARNED_INBOUND = new AtomicBoolean();

    private final int serverPort;

    WsSenderLoop(int serverPort) {
        this.serverPort = serverPort;
    }

    /** 超限被丢弃的帧数（T4-3 背压观测口）。 */
    static long oversizedCount() {
        return OVERSIZED.get();
    }

    @Override
    public void run() {
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            // 显式 IPv4 loopback：插件侧契约只允许 ws://127.0.0.1，双栈机器上
            // InetAddress.getLoopbackAddress() 可能落到 ::1，导致插件连不上。
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), serverPort));
        } catch (Throwable t) {
            System.err.println("[SqlVisAgent] WsSenderLoop: 无法监听 loopback 端口 " + serverPort
                    + "，WebSocket 捕获不可用（传输开关回落到 TCP 需重启目标应用）：" + t);
            closeQuietly(server);
            return;
        }
        try {
            while (true) {
                Socket socket = null;
                try {
                    socket = server.accept();
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    final InputStream in = new BufferedInputStream(socket.getInputStream());
                    final OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                    if (!handshake(in, out)) {
                        closeQuietly(socket);
                        continue;
                    }
                    socket.setSoTimeout(SenderLoop.configReadTimeoutMs(SenderLoop.resolveHeartbeatIntervalMs()));
                    runSession(in, out);
                } catch (Throwable t) {
                    // 单个会话失败只结束本会话；插件会按退避重连（T4-3）。accept 本身失败才退避。
                    if (socket == null) {
                        System.err.println("[SqlVisAgent] WsSenderLoop: accept 失败，"
                                + ACCEPT_BACKOFF_MS + "ms 后重试：" + t);
                        sleepQuietly(ACCEPT_BACKOFF_MS);
                    }
                } finally {
                    closeQuietly(socket);
                }
            }
        } finally {
            closeQuietly(server);
        }
    }

    /** 读升级请求并回 101；非法请求回 400 并返回 false。 */
    private static boolean handshake(InputStream in, OutputStream out) throws IOException {
        final String request = WsFrameCodec.readHttpRequest(in);
        if (request == null) {
            return false;
        }
        final String upgrade = WsFrameCodec.header(request, "Upgrade");
        final String connection = WsFrameCodec.header(request, "Connection");
        final String key = WsFrameCodec.header(request, "Sec-WebSocket-Key");
        if (upgrade == null || !upgrade.toLowerCase(java.util.Locale.ROOT).contains("websocket")
                || connection == null || !connection.toLowerCase(java.util.Locale.ROOT).contains("upgrade")
                || key == null || key.isEmpty() || key.length() > 256) {
            WsFrameCodec.rejectUpgrade(out);
            return false;
        }
        out.write(WsFrameCodec.handshakeResponse(key).getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return true;
    }

    /**
     * 一个会话：与 {@link SenderLoop#run()} 的内层循环逐条对齐，只是读写换成 WS 帧。
     */
    private void runSession(InputStream in, OutputStream out) throws IOException, InterruptedException {
        final long heartbeatIntervalMs = SenderLoop.resolveHeartbeatIntervalMs();
        final long watchdogMs = SenderLoop.resolveConfigWatchdogMs();

        writeEnvelope(out, EventBus.CMD_HELLO, "{\"pid\":" + pid() + "}");
        // 连接建立即补发心跳：插件侧在线判定从连上那一刻就新鲜（与 TCP 路径同语义）
        writeEnvelope(out, EventBus.CMD_HEARTBEAT, SenderLoop.heartbeatBody());
        long lastOutboundMs = System.currentTimeMillis();
        long connectedAtMs = lastOutboundMs;
        boolean configSeen = false;
        boolean watchdogFired = false;
        final ByteArrayOutputStream fragments = new ByteArrayOutputStream();

        final cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[] slot =
                new cn.song.mybatisplus.sqlvis.agent.model.SqlEvent[1];
        while (true) {
            int burst = 0;
            if (!AgentState.isDisabled()) {
                while (burst < EVENT_BURST_MAX && EventBus.pollEvent(slot, 0)) {
                    try {
                        writeEnvelope(out, EventBus.CMD_EVENT, EventBus.encodeEvent(slot[0]));
                        burst++;
                        lastOutboundMs = System.currentTimeMillis();
                    } catch (IOException io) {
                        throw io;
                    } catch (Throwable t) {
                        // 编码失败：丢弃该事件继续（EventBus.droppedCount 可观测）
                    }
                }
                final int overflowFlushed =
                        EventBus.drainOverflow(agg -> writeEnvelope(out, EventBus.CMD_EVENT,
                                EventBus.encodeEvent(agg)));
                if (overflowFlushed > 0) {
                    burst += overflowFlushed;
                    lastOutboundMs = System.currentTimeMillis();
                }
            }
            if (System.currentTimeMillis() - lastOutboundMs >= heartbeatIntervalMs) {
                writeEnvelope(out, EventBus.CMD_HEARTBEAT, SenderLoop.heartbeatBody());
                lastOutboundMs = System.currentTimeMillis();
            }

            final boolean inboundReadable = burst < EVENT_BURST_MAX || in.available() > 0;
            if (inboundReadable) {
                try {
                    final WsFrameCodec.Frame frame = WsFrameCodec.readFrame(in);
                    if (frame.opcode == WsFrameCodec.OP_PING) {
                        out.write(WsFrameCodec.encodePong(frame.payload));
                        out.flush();
                    } else if (frame.opcode == WsFrameCodec.OP_CLOSE) {
                        out.write(WsFrameCodec.encodeClose());
                        out.flush();
                        return;
                    } else if (frame.opcode == WsFrameCodec.OP_TEXT
                            || frame.opcode == WsFrameCodec.OP_CONTINUATION) {
                        fragments.write(frame.payload);
                        if (frame.fin) {
                            final String text = new String(fragments.toByteArray(), StandardCharsets.UTF_8);
                            fragments.reset();
                            final WsFrameCodec.Inbound inbound = WsFrameCodec.decodeEnvelope(text);
                            if (inbound == null) {
                                warnInboundOnce("无法解析入站 WS 信封（首 120 字符）："
                                        + abbreviate(text));
                            } else {
                                if (inbound.cmd == EventBus.CMD_CONFIG) {
                                    configSeen = true;
                                }
                                final String ack = CONFIG_HANDLER.handleInbound(inbound.cmd, inbound.body);
                                if (ack != null) {
                                    writeEnvelope(out, EventBus.CMD_CONFIG_ACK, ack);
                                    lastOutboundMs = System.currentTimeMillis();
                                }
                            }
                        }
                    }
                    // 其他 opcode（binary 等）不属于本协议：忽略但不中断
                } catch (SocketTimeoutException idle) {
                    // 无入站数据
                }
            }

            if (!configSeen && !watchdogFired
                    && System.currentTimeMillis() - connectedAtMs >= watchdogMs) {
                watchdogFired = true;
                System.err.println("[T-AgentConfigChannel] WS 会话建立后 " + watchdogMs
                        + "ms 内未收到任何 CONFIG，按保守策略主动停采（fail-open 看门狗，本会话仅此一条；"
                        + "插件侧应按 T4-3 建立后立即下发 CONFIG）");
                AgentState.setDisabledByWatchdog();
            }

            if (burst == 0) {
                final long remainMs = heartbeatIntervalMs - (System.currentTimeMillis() - lastOutboundMs);
                final long sleepMs = Math.max(0L, Math.min(POLL_IDLE_MS, remainMs));
                if (sleepMs > 0L) {
                    Thread.sleep(sleepMs);
                }
            }
        }
    }

    /**
     * 写一个信封文本帧。超限时 {@link WsFrameCodec#envelope} 抛 IllegalArgumentException：
     * 显式计入 {@link #oversizedCount()} 并原样上抛——事件路径的两个 catch
     * （单事件 {@code catch (Throwable)}、账本出账 {@code catch (Throwable)}）各自按
     * 「丢该条 / 计 dropped」处理，控制帧则在会话层结束本次会话。绝不写半帧。
     */
    private static void writeEnvelope(OutputStream out, int cmd, String body) throws IOException {
        try {
            // envelope() 只是 JSON 载荷；必须再包一层 RFC 6455 文本帧头
            out.write(WsFrameCodec.encode(WsFrameCodec.OP_TEXT, WsFrameCodec.envelope(cmd, body)));
            out.flush();
        } catch (IllegalArgumentException tooLarge) {
            final long n = OVERSIZED.incrementAndGet();
            if (n == 1L) {
                System.err.println("[SqlVisAgent] WsSenderLoop: 单帧超过 "
                        + WsFrameCodec.MAX_PAYLOAD + " 字节，显式丢弃（不再累积；累计数经 oversizedCount 可读）："
                        + tooLarge.getMessage());
            }
            throw tooLarge;
        }
    }

    private static void warnInboundOnce(String message) {
        if (WARNED_INBOUND.compareAndSet(false, true)) {
            System.err.println("[SqlVisAgent] WsSenderLoop: " + message + "（仅此一条告警）");
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    private static long pid() {
        try {
            final String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            final int at = name.indexOf('@');
            return at < 0 ? 0 : Long.parseLong(name.substring(0, at));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignore) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignore) {
            }
        }
    }
}