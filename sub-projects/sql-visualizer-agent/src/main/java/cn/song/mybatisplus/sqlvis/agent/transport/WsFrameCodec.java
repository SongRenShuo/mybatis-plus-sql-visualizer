package cn.song.mybatisplus.sqlvis.agent.transport;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * T4-1：RFC 6455 帧编解码（agent 侧 WebSocket 服务端用）。
 *
 * <p>只实现捕获链需要的最小面：文本帧（含分片续帧由调用方聚合）、ping/pong、close、握手回执与
 * 信封（{@code {"cmd":N,"body":"…"}}）。零外部依赖、Java 8 API——目标应用从 JDK 8 起。
 *
 * <p>为什么信封里放 {@code body} 字符串而不是把事件字段摊平：TCP 帧的 body 是既有
 * {@code Jsons.writeEvent} 输出，字面契约（键名/键序/转义）被 IDE 侧与 301 例测试钉住。WS 只换
 * 传输，不重写事件 JSON——{@code body} 逐字节就是 TCP 下会写出的那一串，语义零漂移。
 *
 * <p>长度边界：单帧载荷上限 {@link #MAX_PAYLOAD}（1 MiB）。服务端只发不掩码帧（RFC 要求），
 * 入站客户端帧必须带掩码，否则按协议错处理（调用方断开重连）。
 */
public final class WsFrameCodec {

    /** 1 MiB：与 TCP 侧 {@code SenderLoop.MAX_FRAME_LEN} 同值，Rust 消费端同上限。 */
    public static final int MAX_PAYLOAD = 1 << 20;

    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** RFC 6455 握手固定 GUID。 */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WsFrameCodec() {
    }

    /** 一个已解码帧。 */
    public static final class Frame {
        public final boolean fin;
        public final int opcode;
        public final byte[] payload;

        public Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /** 信封解析结果。 */
    public static final class Inbound {
        public final int cmd;
        public final String body;

        public Inbound(int cmd, String body) {
            this.cmd = cmd;
            this.body = body;
        }
    }

    /** 文本帧（服务端→客户端：FIN=1、不掩码）。 */
    public static byte[] encodeText(String text) {
        return encode(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 单帧（FIN=1，不掩码）。载荷超过 {@link #MAX_PAYLOAD} 抛 IllegalArgumentException——
     * 显式拒绝而不是写一个消费端必然拒收的超大帧。
     */
    public static byte[] encode(int opcode, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("WebSocket payload too large: " + payload.length
                    + " > " + MAX_PAYLOAD);
        }
        int header = 2;
        if (payload.length >= 126 && payload.length <= 0xFFFF) {
            header += 2;
        } else if (payload.length > 0xFFFF) {
            header += 8;
        }
        final byte[] frame = new byte[header + payload.length];
        frame[0] = (byte) (0x80 | (opcode & 0x0F));
        if (payload.length < 126) {
            frame[1] = (byte) payload.length;
        } else if (payload.length <= 0xFFFF) {
            frame[1] = 126;
            frame[2] = (byte) (payload.length >>> 8);
            frame[3] = (byte) payload.length;
        } else {
            frame[1] = 127;
            final long length = payload.length;
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) (length >>> (8 * (7 - i)));
            }
        }
        System.arraycopy(payload, 0, frame, header, payload.length);
        return frame;
    }

    /**
     * 客户端掩码帧（测试与对称性用；RFC 6455 要求客户端→服务端必须掩码）。
     */
    public static byte[] encodeMasked(int opcode, byte[] payload, byte[] maskKey) {
        if (maskKey == null || maskKey.length != 4) {
            throw new IllegalArgumentException("mask key must be 4 bytes");
        }
        final byte[] frame = encode(opcode, payload);
        // 置掩码位
        frame[1] = (byte) (frame[1] | 0x80);
        // 头部原长度字段之后插入 4 字节掩码：先扩容尾部再整体搬移载荷
        final int headerEnd = frame.length - payload.length;
        final byte[] masked = new byte[frame.length + 4];
        System.arraycopy(frame, 0, masked, 0, headerEnd);
        System.arraycopy(maskKey, 0, masked, headerEnd, 4);
        for (int i = 0; i < payload.length; i++) {
            masked[headerEnd + 4 + i] = (byte) (payload[i] ^ maskKey[i & 3]);
        }
        return masked;
    }

    public static byte[] encodePong(byte[] payload) {
        return encode(OP_PONG, payload);
    }

    public static byte[] encodeClose() {
        return encode(OP_CLOSE, new byte[0]);
    }

    /**
     * 阻塞读一帧。首字节读超时（{@link SocketTimeoutException}）原样上抛＝空闲轮询；
     * 帧读到一半超时按非超时 IOException 上抛（流已错位，调用方断开重连）。
     */
    public static Frame readFrame(InputStream in) throws IOException {
        final int b0;
        try {
            b0 = in.read();
        } catch (SocketTimeoutException idle) {
            throw idle;
        }
        if (b0 < 0) {
            throw new EOFException("WebSocket peer closed");
        }
        final int b1 = readByte(in);
        final boolean fin = (b0 & 0x80) != 0;
        final int opcode = b0 & 0x0F;
        final boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;
        if (length == 126) {
            length = ((long) readByte(in) << 8) | readByte(in);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte(in);
            }
            if (length < 0) {
                throw new IOException("invalid WebSocket length");
            }
        }
        if (length > MAX_PAYLOAD) {
            throw new IOException("WebSocket frame too large: " + length + " > " + MAX_PAYLOAD);
        }
        final byte[] maskKey;
        if (masked) {
            maskKey = readExactly(in, 4);
        } else {
            maskKey = null;
        }
        final byte[] payload = readExactly(in, (int) length);
        if (maskKey != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskKey[i & 3]);
            }
        }
        return new Frame(fin, opcode, payload);
    }

    private static int readByte(InputStream in) throws IOException {
        final int b;
        try {
            b = in.read();
        } catch (SocketTimeoutException partial) {
            throw new IOException("WebSocket frame truncated", partial);
        }
        if (b < 0) {
            throw new EOFException("WebSocket frame truncated");
        }
        return b;
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        final byte[] out = new byte[length];
        int read = 0;
        while (read < length) {
            final int n;
            try {
                n = in.read(out, read, length - read);
            } catch (SocketTimeoutException partial) {
                throw new IOException("WebSocket frame truncated", partial);
            }
            if (n < 0) {
                throw new EOFException("WebSocket frame truncated");
            }
            read += n;
        }
        return out;
    }

    /** RFC 6455 §4.2.2 的 Sec-WebSocket-Accept 值。 */
    public static String acceptKey(String clientKey) {
        try {
            final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            final byte[] digest = sha1.digest((clientKey + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** 101 握手回执（含空行结尾）。 */
    public static String handshakeResponse(String clientKey) {
        return "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(clientKey) + "\r\n"
                + "\r\n";
    }

    /** 信封帧：{@code {"cmd":N,"body":"…"}}（UTF-8 文本帧载荷）。 */
    public static byte[] envelope(int cmd, String body) {
        final StringBuilder sb = new StringBuilder(32 + (body == null ? 4 : body.length()));
        sb.append("{\"cmd\":").append(cmd).append(",\"body\":");
        if (body == null) {
            sb.append("null");
        } else {
            jsonString(sb, body);
        }
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解析信封（宽容：未知结构返回 null）。只认本插件自己的字面形态，不做通用 JSON——
     * agent 内禁外部依赖，入站命令只有 CONFIG 一种。
     */
    public static Inbound decodeEnvelope(String text) {
        if (text == null) {
            return null;
        }
        final int cmdKey = text.indexOf("\"cmd\":");
        if (cmdKey < 0) {
            return null;
        }
        int i = cmdKey + "\"cmd\":".length();
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        final int cmdStart = i;
        if (i < text.length() && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
            i++;
        }
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            i++;
        }
        if (i == cmdStart) {
            return null;
        }
        final int cmd;
        try {
            cmd = Integer.parseInt(text.substring(cmdStart, i));
        } catch (NumberFormatException bad) {
            return null;
        }
        int bodyKey = text.indexOf("\"body\":", i);
        if (bodyKey < 0) {
            return null;
        }
        int j = bodyKey + "\"body\":".length();
        while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
            j++;
        }
        if (j >= text.length() || text.charAt(j) != '"') {
            return new Inbound(cmd, null);
        }
        j++;
        final StringBuilder body = new StringBuilder(text.length() - j);
        while (j < text.length()) {
            final char c = text.charAt(j++);
            if (c == '"') {
                return new Inbound(cmd, body.toString());
            }
            if (c == '\\' && j < text.length()) {
                final char esc = text.charAt(j++);
                switch (esc) {
                    case '"':
                    case '\\':
                    case '/':
                        body.append(esc);
                        break;
                    case 'b':
                        body.append('\b');
                        break;
                    case 'f':
                        body.append('\f');
                        break;
                    case 'n':
                        body.append('\n');
                        break;
                    case 'r':
                        body.append('\r');
                        break;
                    case 't':
                        body.append('\t');
                        break;
                    case 'u':
                        if (j + 4 <= text.length()) {
                            try {
                                body.append((char) Integer.parseInt(text.substring(j, j + 4), 16));
                                j += 4;
                            } catch (NumberFormatException bad) {
                                body.append('u');
                            }
                        } else {
                            body.append('u');
                        }
                        break;
                    default:
                        body.append(esc);
                        break;
                }
            } else {
                body.append(c);
            }
        }
        return null; // 未闭合的字符串
    }

    private static void jsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** 读 HTTP 升级请求头（到空行为止，上限 16 KiB），返回原文；超限/对端关闭返回 null。 */
    public static String readHttpRequest(InputStream in) throws IOException {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(512);
        while (buffer.size() < 16 * 1024) {
            final int b = in.read();
            if (b < 0) {
                return null;
            }
            buffer.write(b);
            final byte[] data = buffer.toByteArray();
            final int n = data.length;
            if (n >= 4 && data[n - 4] == '\r' && data[n - 3] == '\n'
                    && data[n - 2] == '\r' && data[n - 1] == '\n') {
                return new String(data, StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    /** 从升级请求里取指定头（大小写不敏感），缺失返回 null。 */
    public static String header(String request, String name) {
        if (request == null) {
            return null;
        }
        final String[] lines = request.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            final int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (lines[i].substring(0, colon).trim().equalsIgnoreCase(name)) {
                return lines[i].substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** 写 400 后返回（握手失败）。 */
    public static void rejectUpgrade(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}