import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假 IDE 探针（真机级端到端验证，T-AgentE2EFakeIde）。
 *
 * <p>替代 IntelliJ GUI：本进程只做「IDE 侧协议端」该做的事——loopback ServerSocket 监听、
 * 接受 agent 回连、按协议收发帧、把收到的帧按「时刻 / 命令 / 摘要」打日志；不依赖 runIde、
 * 不依赖任何 IDE 平台类。
 *
 * <p>协议契约（与仓库源码逐值一致）：
 * <pre>
 *   帧   = [4 字节大端长度][1 字节命令][body UTF-8]
 *   命令 = 1 EVENT / 2 HEARTBEAT / 3 CONFIG(IDE→app) / 4 HELLO / 5 CONFIG_ACK(app→IDE)
 *   CONFIG body = {"disabled":&lt;bool&gt;,"desensitize":&lt;bool&gt;[,"skipPrefixes":["a."]]}
 *   ACK    body = {"disabled":&lt;bool&gt;,"desensitize":&lt;bool&gt;,"skipPrefixes":&lt;int&gt;}
 * </pre>
 * 权威定义见 {@code SenderLoop} / {@code EventBus}（应用侧）与 {@code AgentProtocol.kt}（IDE 侧）。
 *
 * <p>两种驱动方式：
 * <ol>
 *   <li>{@code --script <file>}：按脚本逐步执行（可复跑、确定性；本目录 README 推荐的用法）；</li>
 *   <li>无 {@code --script}：从 stdin 读同名指令（人工交互排障用）。</li>
 * </ol>
 *
 * <p>脚本指令（每行一条，{@code #} 起头为注释）：
 * <pre>
 *   mark &lt;text&gt;                      打印分节标记
 *   sleep &lt;ms&gt;                        等待
 *   wait-hello &lt;ms&gt;                   等 HELLO 帧（超时即 FAIL）
 *   config &lt;json&gt;                     下发 CONFIG 帧（cmd=3）
 *   expect-ack &lt;substr&gt;               等下一帧 ACK 且 body 含 substr（超时/不含即 FAIL）
 *   reset-events                      事件计数与事件表归零（读数窗口起点）
 *   wait-http &lt;url&gt; &lt;ms&gt;             轮询 URL 直到有 HTTP 响应（应用就绪同步点；不看状态码）
 *   get &lt;url&gt;                         HTTP GET（触发业务 SQL），打印状态码与响应长度
 *   assert-no-events &lt;ms&gt;             窗口内事件数必须为 0
 *   assert-events &lt;ms&gt;                窗口内事件数必须 &gt; 0
 *   assert-event-sf-sl &lt;file&gt;:&lt;line&gt; 窗口内至少一条事件的 sf/sl 等于给定值
 *   report-events                     打印窗口内全部事件（sql 前 80 字 + sf/sl/mk/p）
 *   summary                           打印计数与失败清单
 * </pre>
 *
 * <p>退出码：0 = 全部断言通过；1 = 有断言失败（失败清单在 SUMMARY 段）。
 */
public final class FakeIde {

    static final int CMD_EVENT = 1;
    static final int CMD_HEARTBEAT = 2;
    static final int CMD_CONFIG = 3;
    static final int CMD_HELLO = 4;
    static final int CMD_CONFIG_ACK = 5;

    /** 单帧 body 上限（与两侧 MAX_FRAME_LEN 同值）。 */
    static final int MAX_FRAME_LEN = 1 << 20;

    /** 等 ACK 的超时：agent 每轮入站读超时 1s + 轮询 200ms，5s 足够宽裕。 */
    static final long ACK_TIMEOUT_MS = 5000L;

    private final int port;
    private final PrintWriter out;
    private final long t0 = System.currentTimeMillis();

    private volatile Socket sock;
    private volatile DataOutputStream dout;
    private final Object sendLock = new Object();

    private final AtomicInteger eventTotal = new AtomicInteger();
    private final AtomicInteger heartbeatTotal = new AtomicInteger();
    private final AtomicInteger ackTotal = new AtomicInteger();
    private final AtomicInteger unknownCmdTotal = new AtomicInteger();

    /** 窗口内事件（reset-events 清空）：每项 = {sql, sf, sl, mk, p}。 */
    private final List<String[]> windowEvents = Collections.synchronizedList(new ArrayList<String[]>());
    private final AtomicInteger windowEventCount = new AtomicInteger();

    /** 收到的全部 ACK body（按到达序）。 */
    private final LinkedBlockingQueue<String> ackQueue = new LinkedBlockingQueue<String>();

    private final CountDownLatch helloLatch = new CountDownLatch(1);
    private volatile long helloPid = -1L;
    private volatile long helloAtMs = -1L;

    private final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicInteger passCount = new AtomicInteger();

    private volatile boolean stopRequested = false;

    /** 是否已打过 SUMMARY（`summary` 指令或 quit 前的兜底，只打一次）。 */
    private boolean summaryPrinted = false;

    private FakeIde(int port, PrintWriter out) {
        this.port = port;
        this.out = out;
    }

    // ──────────────────────────── main ────────────────────────────

    public static void main(String[] args) throws Exception {
        int port = 53999;
        String scriptPath = null;
        String logPath = null;
        for (int i = 0; i < args.length; i++) {
            final String a = args[i];
            if ("--port".equals(a) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--script".equals(a) && i + 1 < args.length) {
                scriptPath = args[++i];
            } else if ("--log".equals(a) && i + 1 < args.length) {
                logPath = args[++i];
            } else {
                System.err.println("未知参数: " + a);
                System.err.println("用法: FakeIde [--port <n>] [--script <file>] [--log <file>]");
                System.exit(2);
            }
        }

        PrintWriter fileOut = null;
        if (logPath != null) {
            fileOut = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(logPath, false), Charset.forName("UTF-8")), true);
        }
        final PrintWriter tee = fileOut == null
                ? new PrintWriter(new OutputStreamWriter(System.out, Charset.forName("UTF-8")), true)
                : new TeeWriter(new PrintWriter(new OutputStreamWriter(System.out, Charset.forName("UTF-8")), true),
                        fileOut);

        final FakeIde ide = new FakeIde(port, tee);
        ide.startServer();
        final int rc = ide.runDriver(scriptPath);
        tee.println();
        tee.println("PROBE-EXIT rc=" + rc);
        tee.flush();
        if (fileOut != null) {
            fileOut.flush();
        }
        // 探针进程退出即断开与 agent 的连接（脚本已跑完，后续行为由调用方收口）
        ide.closeSocket();
        System.exit(rc);
    }

    // ──────────────────────────── 服务端 ────────────────────────────

    private void startServer() throws IOException {
        final ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port), 4);
        log("LISTENING 127.0.0.1:" + port + "（等待 agent 回连；agent 在 premain 阶段即连接）");
        final Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stopRequested) {
                    try {
                        final Socket s = server.accept();
                        log("ACCEPT 来自 " + s.getRemoteSocketAddress() + "（agent 回连）");
                        onAccept(s);
                    } catch (Throwable t) {
                        if (!stopRequested) {
                            log("ACCEPT 异常: " + t);
                        }
                    }
                }
            }
        }, "fake-ide-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void onAccept(Socket s) {
        closeSocket();
        sock = s;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(1000);
            dout = new DataOutputStream(s.getOutputStream());
        } catch (IOException e) {
            log("建立输出流失败: " + e);
            return;
        }
        final Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(s);
            }
        }, "fake-ide-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** 读帧循环：`[4 字节 len][1 字节 cmd][len 字节 body]`（与两侧同形）。 */
    private void readLoop(Socket s) {
        try {
            final DataInputStream in = new DataInputStream(s.getInputStream());
            while (!stopRequested) {
                final int len;
                try {
                    len = in.readInt();
                } catch (java.net.SocketTimeoutException idle) {
                    continue; // 空闲轮询：无入站数据
                }
                if (len <= 0 || len > MAX_FRAME_LEN) {
                    log("入站帧长度非法 len=" + len + "，断开");
                    return;
                }
                final int cmd = in.readUnsignedByte();
                final byte[] body = new byte[len];
                in.readFully(body);
                final String payload = new String(body, Charset.forName("UTF-8"));
                onFrame(cmd, payload);
            }
        } catch (Throwable t) {
            if (!stopRequested) {
                log("连接断开/读帧结束: " + t);
            }
        }
    }

    private void onFrame(int cmd, String body) {
        switch (cmd) {
            case CMD_EVENT: {
                eventTotal.incrementAndGet();
                windowEventCount.incrementAndGet();
                final String sql = strField(body, "sql");
                final String sf = strField(body, "sf");
                // 注意：sl 在 agent 侧 Jsons.writeEvent 里是**数字**字段（"sl":42），不是字符串
                final String sl = rawField(body, "sl");
                final String mk = strField(body, "mk");
                final String p = rawField(body, "p");
                final String sd = strField(body, "sd");
                final String cands = rawField(body, "cands");
                // T-IbatisPanwei：事件 url 是 IDE 侧角标/方言映射的唯一输入（JdbcUrlDialects /
                // UrlSubprotocolDialects），探针把它打出来，才能在无 GUI 的真机验收里核对
                // 「jdbc:panweidb: → Panwei（磐维） / Oracle」这条链拿到了什么原材料。
                final String url = strField(body, "url");
                windowEvents.add(new String[]{sql, sf, sl, mk, p, sd, cands});
                log("EVENT #" + eventTotal.get() + " sf=" + sf + " sl=" + sl
                        + " mk=" + mk + " sql=" + clip(sql, 80) + " p=" + clip(p, 160)
                        + " u=" + clip(url, 120));
                log("        sd=" + clip(sd, 900));
                break;
            }
            case CMD_HEARTBEAT:
                heartbeatTotal.incrementAndGet();
                log("HEARTBEAT #" + heartbeatTotal.get() + " body=" + body);
                break;
            case CMD_HELLO: {
                final String pid = rawField(body, "pid");
                helloPid = parseLong(pid, -1L);
                helloAtMs = System.currentTimeMillis() - t0;
                log("HELLO pid=" + pid + " body=" + body + "（+ " + helloAtMs + "ms 起连）");
                helloLatch.countDown();
                break;
            }
            case CMD_CONFIG_ACK:
                ackTotal.incrementAndGet();
                ackQueue.offer(body);
                log("CONFIG_ACK #" + ackTotal.get() + " body=" + body);
                break;
            default:
                unknownCmdTotal.incrementAndGet();
                log("UNKNOWN cmd=" + cmd + " body=" + body);
                break;
        }
    }

    // ──────────────────────────── 发送 ────────────────────────────

    private void sendFrame(int cmd, String payload) throws IOException {
        final DataOutputStream o = dout;
        if (o == null) {
            throw new IOException("尚无 agent 连接，无法发送 cmd=" + cmd);
        }
        final byte[] body = payload.getBytes(Charset.forName("UTF-8"));
        synchronized (sendLock) {
            o.writeInt(body.length);
            o.writeByte(cmd);
            o.write(body);
            o.flush();
        }
        log("SEND cmd=" + cmd + " body=" + payload);
    }

    private void closeSocket() {
        final Socket s = sock;
        sock = null;
        dout = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignore) {
            }
        }
    }

    // ──────────────────────────── 驱动 ────────────────────────────

    private int runDriver(String scriptPath) throws IOException {
        final BufferedReader reader;
        if (scriptPath != null) {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(scriptPath), Charset.forName("UTF-8")));
        } else {
            reader = new BufferedReader(new InputStreamReader(System.in, Charset.forName("UTF-8")));
            log("无 --script：从 stdin 读指令（mark/sleep/wait-hello/config/expect-ack/reset-events/"
                    + "get/assert-no-events/assert-events/assert-event-sf-sl/report-events/summary/quit）");
        }
        try {
            String line;
            int no = 0;
            while ((line = reader.readLine()) != null) {
                no++;
                final String cmdLine = line.trim();
                if (cmdLine.isEmpty() || cmdLine.startsWith("#")) {
                    continue;
                }
                log("");
                log(">>> [" + no + "] " + cmdLine);
                if (!dispatch(cmdLine)) {
                    summaryPrinted = true; // quit 前已打过分结，避免重复
                    break;
                }
            }
        } catch (Throwable t) {
            log("驱动异常: " + t);
            failures.add("驱动异常: " + t);
        } finally {
            try {
                reader.close();
            } catch (IOException ignore) {
            }
        }
        if (!summaryPrinted) {
            printSummary();
        }
        return failures.isEmpty() ? 0 : 1;
    }
    /** 执行一条指令；返回 false = 结束驱动。 */
    private boolean dispatch(String line) throws Exception {
        final int sp = line.indexOf(' ');
        final String op = sp < 0 ? line : line.substring(0, sp);
        final String arg = sp < 0 ? "" : line.substring(sp + 1).trim();

        if ("mark".equals(op)) {
            log("======== " + arg + " ========");
        } else if ("sleep".equals(op)) {
            Thread.sleep(parseLong(arg, 1000L));
        } else if ("wait-hello".equals(op)) {
            final long timeout = parseLong(arg, 30000L);
            if (helloLatch.await(timeout, TimeUnit.MILLISECONDS)) {
                pass("HELLO 到达 pid=" + helloPid + " (+" + helloAtMs + "ms)");
            } else {
                fail("等 HELLO 超时 " + timeout + "ms —— agent 未回连（检查 -javaagent 与端口）");
            }
        } else if ("config".equals(op)) {
            try {
                sendFrame(CMD_CONFIG, arg);
                pass("已下发 CONFIG");
            } catch (IOException e) {
                fail("下发 CONFIG 失败: " + e);
            }
        } else if ("expect-ack".equals(op)) {
            final String want = stripQuotes(arg);
            final String got = ackQueue.poll(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (got == null) {
                fail("等 CONFIG_ACK 超时 " + ACK_TIMEOUT_MS + "ms（期望含 " + want + "）——控制通道未通");
            } else if (got.contains(want)) {
                pass("CONFIG_ACK 到达且含 \"" + want + "\"：" + got);
            } else {
                fail("CONFIG_ACK 不含 \"" + want + "\"：" + got);
            }
        } else if ("reset-events".equals(op)) {
            windowEvents.clear();
            windowEventCount.set(0);
            log("事件窗口已归零（累计 " + eventTotal.get() + " 条）");
        } else if ("wait-http".equals(op)) {
            final int sp2 = arg.lastIndexOf(' ');
            final String url = sp2 < 0 ? arg : arg.substring(0, sp2).trim();
            final long timeout = sp2 < 0 ? 90000L : parseLong(arg.substring(sp2 + 1), 90000L);
            waitHttp(url, timeout);
        } else if ("get".equals(op)) {
            httpGet(arg);
        } else if ("assert-no-events".equals(op)) {
            final long ms = parseLong(arg, 2000L);
            Thread.sleep(ms);
            final int n = windowEventCount.get();
            if (n == 0) {
                pass("停采窗口 " + ms + "ms 内事件数 = 0");
            } else {
                fail("停采窗口 " + ms + "ms 内仍有 " + n + " 条 EVENT 帧（停采未生效）");
                dumpWindow();
            }
        } else if ("assert-events".equals(op)) {
            final long ms = parseLong(arg, 2000L);
            Thread.sleep(ms);
            final int n = windowEventCount.get();
            if (n > 0) {
                pass("窗口 " + ms + "ms 内事件数 = " + n + "（采集生效）");
            } else {
                fail("窗口 " + ms + "ms 内事件数 = 0（采集未恢复）");
            }
        } else if ("assert-event-sf-sl".equals(op)) {
            final String want = stripQuotes(arg);
            final int colon = want.lastIndexOf(':');
            final String wantFile = colon < 0 ? want : want.substring(0, colon);
            final String wantLine = colon < 0 ? "" : want.substring(colon + 1);
            final List<String[]> snap = snapshot();
            boolean hit = false;
            for (String[] e : snap) {
                final String sf = e[1] == null ? "" : e[1];
                if ((sf.endsWith(wantFile) || sf.equals(wantFile)) && wantLine.equals(e[2])) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                pass("窗口内存在事件 sf:sl = " + want);
            } else {
                fail("窗口内无事件 sf:sl = " + want + "；实际：" + sfSlPairs(snap));
            }
        } else if ("report-events".equals(op)) {
            dumpWindow();
        } else if ("summary".equals(op)) {
            printSummary();
            summaryPrinted = true;
        } else if ("quit".equals(op)) {
            return false;
        } else {
            fail("未知指令: " + line);
        }
        return true;
    }

    /**
     * 轮询 URL 直到有任意 HTTP 响应（应用就绪同步点）。只看「连得上且拿到状态码」，
     * 不看状态码本身——4xx/5xx 也说明 Spring 已在服务（业务数据缺失不该被当成「应用没起来」）。
     */
    private void waitHttp(String url, long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(2000);
                c.setReadTimeout(10000);
                final int code = c.getResponseCode();
                pass("应用就绪：GET " + url + " -> HTTP " + code + "（第 " + attempt + " 次探测）");
                return;
            } catch (Throwable t) {
                log("  等待应用就绪（第 " + attempt + " 次）：" + t);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ie) {
                break;
            }
        }
        fail("等应用就绪超时 " + timeoutMs + "ms：" + url);
    }

    private void httpGet(String url) {        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(20000);
            final int code = c.getResponseCode();
            final InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            final StringBuilder sb = new StringBuilder();
            if (is != null) {
                final BufferedReader r = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
                String l;
                while ((l = r.readLine()) != null) {
                    sb.append(l);
                    if (sb.length() > 200000) {
                        break;
                    }
                }
                r.close();
            }
            log("GET " + url + " -> HTTP " + code + " len=" + sb.length() + " body=" + clip(sb.toString(), 120));
            if (code >= 400) {
                fail("业务请求返回 HTTP " + code + "：" + url);
            } else {
                pass("业务请求 HTTP " + code + "：" + url);
            }
        } catch (Throwable t) {
            fail("业务请求异常 " + url + " -> " + t);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ──────────────────────────── 输出 ────────────────────────────

    private void printSummary() {
        log("");
        log("──────── SUMMARY ────────");
        log("HELLO=" + (helloPid >= 0 ? ("pid=" + helloPid + " @" + helloAtMs + "ms") : "未收到")
                + " HEARTBEAT=" + heartbeatTotal.get()
                + " EVENT(累计)=" + eventTotal.get()
                + " CONFIG_ACK=" + ackTotal.get()
                + " UNKNOWN_CMD=" + unknownCmdTotal.get());
        final List<String> snap = new ArrayList<String>(failures);
        log("断言 PASS=" + passCount.get() + " FAIL=" + snap.size());
        if (snap.isEmpty()) {
            log("RESULT: ALL-PASS");
        } else {
            log("RESULT: FAILED");
            for (String f : snap) {
                log("  FAIL: " + f);
            }
        }
        log("─────────────────────────");
    }

    private void dumpWindow() {
        final List<String[]> snap = snapshot();
        log("窗口内事件 " + snap.size() + " 条；sf:sl 分布 = " + sfSlPairs(snap));
        for (String[] e : snap) {
            log("  sql=" + clip(e[0], 100) + " | sf=" + e[1] + " | sl=" + e[2]
                    + " | mk=" + e[3] + " | p=" + clip(e[4], 200));
            log("    cands=" + clip(e[6], 400));
            log("    sd=" + clip(e[5], 900));
        }
    }

    private static String sfSlPairs(List<String[]> evts) {
        final StringBuilder sb = new StringBuilder();
        for (String[] e : evts) {
            final String k = (e[1] == null ? "" : e[1]) + ":" + e[2];
            if (sb.indexOf(k) < 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(k);
            }
        }
        return sb.length() == 0 ? "(无)" : sb.toString();
    }

    private List<String[]> snapshot() {
        synchronized (windowEvents) {
            return new ArrayList<String[]>(windowEvents);
        }
    }

    private void pass(String msg) {
        passCount.incrementAndGet();
        log("  PASS " + msg);
    }

    private void fail(String msg) {
        failures.add(msg);
        log("  FAIL " + msg);
    }

    private void log(String msg) {
        out.println("[" + (System.currentTimeMillis() - t0) + "ms] " + msg);
        out.flush();
    }

    // ──────────────────────────── 工具 ────────────────────────────

    /** 极简 JSON 字符串字段抽取（agent 侧 Jsons 写出的形态；支持 \\" \\\\ \\n \\r \\t \\uXXXX）。 */
    static String strField(String json, String key) {
        if (json == null) {
            return null;
        }
        final String k = "\"" + key + "\":\"";
        final int at = json.indexOf(k);
        if (at < 0) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = at + k.length(); i < json.length(); i++) {
            final char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                final char n = json.charAt(++i);
                if (n == 'n') {
                    sb.append('\n');
                } else if (n == 'r') {
                    sb.append('\r');
                } else if (n == 't') {
                    sb.append('\t');
                } else if (n == 'u' && i + 4 < json.length()) {
                    sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                } else {
                    sb.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 极简 JSON 原始值抽取（数字或数组，按括号配平截取；未命中返回 null）。 */
    static String rawField(String json, String key) {
        if (json == null) {
            return null;
        }
        final String k = "\"" + key + "\":";
        final int at = json.indexOf(k);
        if (at < 0) {
            return null;
        }
        int i = at + k.length();
        if (i >= json.length()) {
            return null;
        }
        if (json.charAt(i) == '[') {
            int depth = 0;
            final StringBuilder sb = new StringBuilder();
            boolean inStr = false;
            for (; i < json.length(); i++) {
                final char c = json.charAt(i);
                sb.append(c);
                if (inStr) {
                    if (c == '\\') {
                        if (i + 1 < json.length()) {
                            sb.append(json.charAt(++i));
                        }
                    } else if (c == '"') {
                        inStr = false;
                    }
                } else if (c == '"') {
                    inStr = true;
                } else if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            return sb.toString();
        }
        final int end = indexOfAny(json, i, ",}");
        return end < 0 ? json.substring(i) : json.substring(i, end);
    }

    private static int indexOfAny(String s, int from, String chars) {
        for (int i = from; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    static String clip(String s, int max) {
        if (s == null) {
            return "null";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(+" + (s.length() - max) + ")";
    }

    private static String stripQuotes(String s) {
        String v = s.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static long parseLong(String s, long dflt) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return dflt;
        }
    }

    /** stdout + 文件双写。 */
    private static final class TeeWriter extends PrintWriter {
        private final PrintWriter a;
        private final PrintWriter b;

        TeeWriter(PrintWriter a, PrintWriter b) {
            super(a);
            this.a = a;
            this.b = b;
        }

        @Override
        public void println(String x) {
            a.println(x);
            a.flush();
            b.println(x);
            b.flush();
        }

        @Override
        public void flush() {
            a.flush();
            b.flush();
        }
    }

    private FakeIde() {
        throw new AssertionError();
    }
}
