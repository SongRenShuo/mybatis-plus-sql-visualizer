package cn.song.mybatisplus.sqlvis.agent;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T-AttachPoolWarm：真机复现级端到端（MySQL 8 + 存量池连接复用）——harness 照抄
 * {@code AttachGateTest}（子 JVM + loopback server + loadAgent + 读 CMD 帧），换真实 MySQL 驱动，
 * 分模式逐一判「attach 后事件是否到达」。这是 {@code StatementWeaveEndToEndTest} 明确留档的
 * 「先建池再 retransform 的真机时序」判别用例（单测 JVM 拿不到 Instrumentation）。
 *
 * <p>第一轮（A/B/C/D/D2/D3，裸 DriverManager 连接）六模式全绿——含用户主场景（attach 前建连接、
 * attach 后同连接新建 PS）。第二轮补四格，逼近用户现场其余差异：
 * <ul>
 *   <li><b>A2</b>：HikariCP 4.0.3 真实池（Javassist 代理 HikariProxyConnection/PreparedStatement）+ 预热两条物理连接；</li>
 *   <li><b>B2</b>：A2 语义搬到 JDK8 目标 JVM；</li>
 *   <li><b>A3</b>：驱动换代 mysql-connector-java 8.0.28（Spring Boot 2.6.3 依赖管理版）；</li>
 *   <li><b>A4</b>：Debug 模式（-agentlib:jdwp）。</li>
 * </ul>
 *
 * 每条模式把子进程 stdout/stderr 落盘到 {@code build/attachpool-out/<mode>.log}（redirectOutput），
 * 并原文摘出 {@code [SqlVisAgent] attach retransform:} 行与可能的首条事件/盲区行。
 *
 * 环境：MySQL localhost:3306（root/root，库 mpj_demo）。不可达时整类 Assume 跳过（不伪造读数）。
 */
public class AttachPoolWarmGateTest {

    private static final int WAIT_SECONDS = 30;
    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/mpj_demo";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "root";

    /** 达梦（用户现场 application-dm.yml）——DM 模式子进程连它。 */
    private static final String DM_URL = "jdbc:dm://114.66.55.245:20096?schema=MPJ_DEMO";
    private static final String DM_USER = "MPJ_DEMO";
    private static final String DM_PASS = "Mpj_demo_2026";

    /** 用户现场 JDK8（目标 JVM；attach 客户端仍为工具链 JDK）。 */
    private static final String JDK8_JAVA_HOME = "D:/HuanJing/Java/jdk1.8.0_181";
    /** Spring Boot 2.6.3 依赖管理版 MySQL 驱动（与 8.0.33 同挂子进程 classpath，按名各取）。 */
    private static final String MYSQL_JAR_8028 = "mysql-connector-java-8.0.28.jar";
    private static final String MYSQL_JAR_8033 = "mysql-connector-j-8.0.33.jar";

    /** CoolRequest agent jar（runIde 沙盒装、疑似被注入应用 JVM）——模式 D 用。 */
    private static final Path COOL_REQUEST_AGENT =
            Paths.get("C:/Users/USER/.config/.cool-request/request/lib/cool-request-agent.jar");

    /** CoolRequest 原生 hook agent dll（attach 在跑进程的真正入口；jar 无 agentmain，见 D2）。 */
    private static final Path COOL_REQUEST_HOOK_DLL =
            Paths.get("C:/Users/USER/.config/.cool-request/request/lib/cool-request-hook-agentlib.dll");

    private Process child;

    @org.junit.After
    public void cleanupChild() {
        if (child != null) {
            child.destroyForcibly();
            child = null;
        }
    }

    // ---------- 第一轮（裸连接） ----------

    @org.junit.Test
    public void 模式C_对照_attach后新建连接_事件到达() throws Exception {
        // T-CiRelease（20261024）：A2/B2/A3/A4 均有探活门控，主场景 A/B/C 漏接——与类头
        // 「不可达时整类 Assume 跳过」声明不符（20261024 CI 无 MySQL 时三模式红而非跳的实证）。
        org.junit.Assume.assumeTrue("MySQL 不可达，模式C 跳过（环境受限未验证）", mysqlReachable());
        RunResult r = runMode("C", "attachpool_c", new ArrayList<String>(), WAIT_SECONDS * 1000L);
        report("C", r);
        org.junit.Assert.assertTrue("模式C（对照，attach 后新建连接）应收到事件——MySQL 名单/harness 自检失败\n"
                + r.asBlock(), r.eventReceived);
    }

    @org.junit.Test
    public void 模式A_主场景_存量连接上新建PS_事件到达() throws Exception {
        org.junit.Assume.assumeTrue("MySQL 不可达，模式A 跳过（环境受限未验证）", mysqlReachable());
        RunResult r = runMode("A", "attachpool_a", new ArrayList<String>(), WAIT_SECONDS * 1000L);
        report("A", r);
        org.junit.Assert.assertTrue("模式A（主场景，存量池连接复用）应收到事件\n" + r.asBlock(), r.eventReceived);
    }

    @org.junit.Test
    public void 模式B_attach前prepare的PS复用_一期盲区表现() throws Exception {
        org.junit.Assume.assumeTrue("MySQL 不可达，模式B 跳过（环境受限未验证）", mysqlReachable());
        RunResult r = runMode("B", "attachpool_b", new ArrayList<String>(), 15_000L, null);
        report("B", r);
        org.junit.Assert.assertNotNull("模式B 必须产出 attach retransform 行", r.attachRetransformLine);
        org.junit.Assert.assertNotNull("模式B 必须命中盲区取证行（宿主侧「钩子在跑、SQL 取不到」证据）",
                r.blindSpotLine);
    }

    @org.junit.Test
    public void 模式D_第三方agent干扰_存量连接复用() throws Exception {
        org.junit.Assume.assumeTrue("CoolRequest agent jar 不在位，模式 D 跳过",
                Files.isRegularFile(COOL_REQUEST_AGENT));
        List<String> jvmArgs = new ArrayList<String>();
        jvmArgs.add("-javaagent:" + COOL_REQUEST_AGENT.toString());
        RunResult r = runMode("D", "attachpool_d", jvmArgs, WAIT_SECONDS * 1000L, null);
        report("D", r);
        org.junit.Assert.assertNotNull("模式D 必须产出 attach retransform 行（或子进程早期死亡，见日志）",
                r.attachRetransformLine);
    }

    @org.junit.Test
    public void 模式D2_CoolRequest先attach在跑进程_再挂本agent() throws Exception {
        org.junit.Assume.assumeTrue("CoolRequest agent jar 不在位，模式 D2 跳过",
                Files.isRegularFile(COOL_REQUEST_AGENT));
        RunResult r = runMode("D2", "attachpool_d2", new ArrayList<String>(), WAIT_SECONDS * 1000L,
                COOL_REQUEST_AGENT);
        report("D2", r);
        org.junit.Assert.assertNotNull("模式D2 必须产出 attach retransform 行", r.attachRetransformLine);
    }

    @org.junit.Test(timeout = 120_000L)
    public void 模式D3_CoolRequest原生dll_attach在跑进程_再挂本agent() throws Exception {
        org.junit.Assume.assumeTrue("CoolRequest hook dll 不在位，模式 D3 跳过",
                Files.isRegularFile(COOL_REQUEST_HOOK_DLL));
        RunResult r = runMode("D3", "attachpool_d3", new ArrayList<String>(), WAIT_SECONDS * 1000L,
                COOL_REQUEST_HOOK_DLL, true);
        report("D3", r);
        org.junit.Assert.assertNotNull("模式D3 必须产出 attach retransform 行（或子进程早期死亡，见日志）",
                r.attachRetransformLine);
    }

    // ---------- 第二轮四格（逼近用户现场） ----------

    /** A2（最高优先）：HikariCP 真实池 + 存量物理连接 —— Javassist 代理族形态。 */
    @org.junit.Test
    public void 模式A2_Hikari真实池_存量物理连接复用() throws Exception {
        org.junit.Assume.assumeTrue("MySQL 不可达，A2 跳过（环境受限未验证）", mysqlReachable());
        RunResult r = runMode("H", "attachpool_h", new ArrayList<String>(), WAIT_SECONDS * 1000L,
                null, false, defaultJavaBin(), MYSQL_JAR_8033);
        report("A2", r);
        org.junit.Assert.assertTrue("模式A2（Hikari 真实池 + 存量物理连接）应收到事件——"
                + "这是最可能解释用户现场的一格\n" + r.asBlock(), r.eventReceived);
    }

    /** B2：A2 语义原样搬到 JDK8 目标 JVM（JDK8 目标门禁此前 Assume-可跳过）。 */
    @org.junit.Test
    public void 模式B2_JDK8目标_Hikari池存量连接复用() throws Exception {
        org.junit.Assume.assumeTrue("MySQL 不可达，B2 跳过（环境受限未验证）", mysqlReachable());
        org.junit.Assume.assumeTrue("JDK8 不在位，B2 跳过", jdk8JavaBin() != null);
        RunResult r = runMode("H", "attachpool_h", new ArrayList<String>(), WAIT_SECONDS * 1000L,
                null, false, jdk8JavaBin(), MYSQL_JAR_8028);
        report("B2", r);
        org.junit.Assert.assertTrue("模式B2（JDK8 目标 + Hikari 池存量连接）应收到事件\n" + r.asBlock(),
                r.eventReceived);
    }

    /** A3：驱动换代 mysql-connector-java 8.0.28（Spring Boot 2.6.3 版），A2 语义不变。 */
    @org.junit.Test
    public void 模式A3_驱动换代8028_Hikari池存量连接复用() throws Exception {
        org.junit.Assume.assumeTrue("MySQL 不可达，A3 跳过（环境受限未验证）", mysqlReachable());
        RunResult r = runMode("H", "attachpool_h", new ArrayList<String>(), WAIT_SECONDS * 1000L,
                null, false, defaultJavaBin(), MYSQL_JAR_8028);
        report("A3", r);
        org.junit.Assert.assertTrue("模式A3（mysql-connector-java 8.0.28 + Hikari 池）应收到事件\n"
                + r.asBlock(), r.eventReceived);
    }

    /** A4：Debug 模式（-agentlib:jdwp），A2 语义不变。 */
    @org.junit.Test
    public void 模式A4_Debug模式jdwp_Hikari池存量连接复用() throws Exception {
        org.junit.Assume.assumeTrue("MySQL 不可达，A4 跳过（环境受限未验证）", mysqlReachable());
        List<String> jvmArgs = new ArrayList<String>();
        jvmArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006");
        RunResult r = runMode("H", "attachpool_h", jvmArgs, WAIT_SECONDS * 1000L,
                null, false, defaultJavaBin(), MYSQL_JAR_8033);
        report("A4", r);
        org.junit.Assert.assertTrue("模式A4（Debug 模式 + Hikari 池存量连接）应收到事件\n" + r.asBlock(),
                r.eventReceived);
    }

    /**
     * DM（用户 P0 复现）：达梦应用——JDK8 目标 + 存量 DM 连接（attach 前建好并跑一次），attach 后
     * 同连接新建 PS 执行。修复前预期零事件、matched=0（达梦语句面/prepare 面未登记）；登记后应到达。
     * DM 服务器不可达时 Assume 跳过（不伪造读数）。
     */
    @org.junit.Test
    public void 模式DM_达梦存量连接复用_attach事件到达() throws Exception {
        org.junit.Assume.assumeTrue("DM 服务器 114.66.55.245:20096 不可达，DM 格跳过", dmReachable());
        org.junit.Assume.assumeTrue("JDK8 不在位，DM 格跳过", jdk8JavaBin() != null);
        // 用户现场应用很可能跑在 JDK8 上（本机默认 java=JDK8）——DM 格按 JDK8 目标跑
        RunResult r = runMode("DM", "attachpool_dm", new ArrayList<String>(), WAIT_SECONDS * 1000L,
                null, false, jdk8JavaBin(), null);
        report("DM", r);
        org.junit.Assert.assertTrue("模式DM（达梦存量连接复用）应收到事件——用户 P0 的本地复现判据\n"
                + r.asBlock(), r.eventReceived);
    }

    // ---------- 核心运行 ----------

    private RunResult runMode(String mode, String keyword, List<String> extraJvmArgs, long waitMs)
            throws Exception {
        return runMode(mode, keyword, extraJvmArgs, waitMs, null, false);
    }

    private RunResult runMode(String mode, String keyword, List<String> extraJvmArgs, long waitMs,
                              Path preAttachAgentJar) throws Exception {
        return runMode(mode, keyword, extraJvmArgs, waitMs, preAttachAgentJar, false);
    }

    private RunResult runMode(String mode, String keyword, List<String> extraJvmArgs, long waitMs,
                              Path preAttachFile, boolean preAttachAsPath) throws Exception {
        return runMode(mode, keyword, extraJvmArgs, waitMs, preAttachFile, preAttachAsPath,
                defaultJavaBin(), null);
    }

    private RunResult runMode(String mode, String keyword, List<String> extraJvmArgs, long waitMs,
                              Path preAttachFile, boolean preAttachAsPath,
                              String javaBinOverride, String mysqlJarName) throws Exception {
        final String javaBin = javaBinOverride != null ? javaBinOverride : defaultJavaBin();
        final File agentJar = locateAgentJar();
        final String childClasses = Paths.get("build", "classes", "java", "childapp")
                .toAbsolutePath().toString();
        // Hikari 模式（H）：子进程 classpath 取显式 pool classpath（Hikari+javassist+slf4j+指定驱动）；
        // 其余模式维持第一轮行为（测试 classpath 里扫出的 mysql-connector-j 8.0.33 + childapp）
        final String childClasspath = ("H".equals(mode) || "DM".equals(mode))
                ? buildPoolClasspath(mysqlJarName)
                : locateMysqlJar().toAbsolutePath() + File.pathSeparator + childClasses;

        // 1) loopback server（IDE 角色）
        java.net.ServerSocket server =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        server.setSoTimeout(WAIT_SECONDS * 1000);

        // 2) 子 JVM
        final Path outDir = Paths.get("build", "attachpool-out");
        Files.createDirectories(outDir);
        final Path childLog = outDir.resolve("mode-" + mode + ".log");
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        cmd.addAll(extraJvmArgs);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(childClasspath);
        cmd.add("cn.song.mybatisplus.sqlvis.agent.ChildPoolApp");
        cmd.add(mode);
        cmd.add("DM".equals(mode) ? DM_URL : JDBC_URL);
        cmd.add("DM".equals(mode) ? DM_USER : DB_USER);
        cmd.add("DM".equals(mode) ? DM_PASS : DB_PASS);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(childLog.toFile());
        child = pb.start();

        // 2.5) 等子 JVM 报 READY <pid>（warm 资源已在位 = 存量连接/PS/池已建）
        String pidLine = waitForLine(childLog, "READY ");
        org.junit.Assert.assertNotNull("子 JVM 未就绪（mode=" + mode + ", java=" + javaBin + "）:\n"
                + tail(childLog), pidLine);
        final long pid = Long.parseLong(pidLine.split(" ")[1].trim());

        // 3) attach + loadAgent（可选：先挂第三方 agent 到在跑进程，模拟 CoolRequest 已注入）
        com.sun.tools.attach.VirtualMachine vm =
                com.sun.tools.attach.VirtualMachine.attach(Long.toString(pid));
        String preAttachResult = null;
        if (preAttachFile != null) {
            try {
                if (preAttachAsPath) {
                    vm.loadAgentPath(preAttachFile.toString());
                } else {
                    vm.loadAgent(preAttachFile.toString(), "");
                }
                preAttachResult = (preAttachAsPath ? "loadAgentPath" : "loadAgent") + ":ok";
            } catch (Throwable t) {
                preAttachResult = (preAttachAsPath ? "loadAgentPath" : "loadAgent")
                        + ":" + t.getClass().getName() + ": " + t.getMessage();
            }
        }
        try {
            vm.loadAgent(agentJar.getAbsolutePath(), "port=" + server.getLocalPort() + ";logLevel=info");
        } catch (com.sun.tools.attach.AgentLoadException e) {
            if (!"0".equals(e.getMessage())) {
                org.junit.Assert.fail("loadAgent 失败且非已知形态: " + e);
            }
        }

        // 4) accept 会话 + 读帧 + 下发 CONFIG disabled=false（对齐真机，压住 15s 看门狗）
        java.net.Socket session = server.accept();
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<String>();
        List<String> hellos = new CopyOnWriteArrayList<String>();
        CopyOnWriteArrayList<String> acks = new CopyOnWriteArrayList<String>();
        Thread reader = readerThread(session, events, hellos, acks);
        sendConfig(session, false);

        // 5) 等事件（或超时）
        boolean got = waitForEvent(events, keyword, waitMs);
        Thread.sleep(500); // 让 attach retransform 行落盘

        RunResult r = new RunResult();
        r.mode = mode;
        r.eventReceived = got;
        r.eventSample = firstEventContaining(events, keyword);
        r.helloCount = hellos.size();
        r.attachRetransformLine = lineContaining(childLog, "[SqlVisAgent] attach retransform:");
        r.firstFireLine = lineContaining(childLog, "[SqlVisAgent] 织入通道首条事件已产出");
        r.blindSpotLine = lineContaining(childLog, "织入通道登记盲区");
        r.watchdogLine = lineContaining(childLog, "未收到任何 CONFIG");
        r.preAttachResult = preAttachResult;
        r.warmLine = lineContaining(childLog, "WARM-");
        r.childLogPath = childLog.toAbsolutePath().toString();
        r.childTail = tail(childLog, 30);

        // 6) 清理
        try { vm.detach(); } catch (Throwable ignore) { }
        try { session.close(); } catch (Throwable ignore) { }
        try { server.close(); } catch (Throwable ignore) { }
        if (child != null) { child.destroy(); }
        return r;
    }

    // ---------- 结果承载 ----------

    private static final class RunResult {
        String mode;
        boolean eventReceived;
        String eventSample;
        int helloCount;
        String attachRetransformLine;
        String firstFireLine;
        String blindSpotLine;
        String watchdogLine;
        String preAttachResult;
        String warmLine;
        String childLogPath;
        String childTail;

        String asBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n===== ATTACH-POOL MODE ").append(mode).append(" =====\n");
            sb.append("eventReceived=").append(eventReceived).append('\n');
            sb.append("helloCount=").append(helloCount).append('\n');
            sb.append("warm=").append(warmLine).append('\n');
            sb.append("eventSample=").append(eventSample).append('\n');
            sb.append("attachRetransform=").append(attachRetransformLine).append('\n');
            sb.append("firstFire=").append(firstFireLine).append('\n');
            sb.append("blindSpot=").append(blindSpotLine).append('\n');
            sb.append("watchdog=").append(watchdogLine).append('\n');
            sb.append("preAttach=").append(preAttachResult).append('\n');
            sb.append("childLog=").append(childLogPath).append('\n');
            sb.append("--- child tail ---\n").append(childTail).append('\n');
            sb.append("=====================");
            return sb.toString();
        }
    }

    private static void report(String mode, RunResult r) {
        System.out.println(r.asBlock());
        System.out.flush();
    }

    // ---------- 辅助 ----------

    private static String defaultJavaBin() {
        Path exe = Paths.get(System.getProperty("java.home"), "bin", "java.exe");
        return exe.toFile().exists() ? exe.toString()
                : Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** 用户现场 JDK8 的 java 可执行；不在位返回 null（调用方 Assume 跳过）。 */
    private static String jdk8JavaBin() {
        Path exe = Paths.get(JDK8_JAVA_HOME, "bin", "java.exe");
        return exe.toFile().exists() ? exe.toString() : null;
    }

    private static void sendConfig(java.net.Socket session, boolean disabled) throws Exception {
        OutputStream out = session.getOutputStream();
        byte[] body = ("{\"disabled\":" + disabled + ",\"desensitize\":true,\"stackFrames\":24}")
                .getBytes(StandardCharsets.UTF_8);
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        d.writeInt(body.length);
        d.writeByte(3); // CMD_CONFIG
        d.write(body);
        d.flush();
    }

    private static Thread readerThread(java.net.Socket session, final List<String> events,
                                       final List<String> hellos, final List<String> acks) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.DataInputStream in = new java.io.DataInputStream(session.getInputStream());
                    while (true) {
                        int len = in.readInt();
                        int cmd = in.readUnsignedByte();
                        byte[] body = new byte[len];
                        in.readFully(body);
                        String s = new String(body, StandardCharsets.UTF_8);
                        if (cmd == 4) {
                            hellos.add(s);
                        } else if (cmd == 1) {
                            events.add(s);
                        } else if (cmd == 5) {
                            acks.add(s);
                        }
                    }
                } catch (Throwable ignore) {
                    // 会话结束
                }
            }
        }, "attachpool-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean waitForEvent(List<String> events, String keyword, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (firstEventContaining(events, keyword) != null) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static String firstEventContaining(List<String> events, String keyword) {
        for (String e : events) {
            if (e.contains(keyword)) {
                return e.length() > 400 ? e.substring(0, 400) : e;
            }
        }
        return null;
    }

    private static String waitForLine(Path log, String prefix) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line = lineContaining(log, prefix);
            if (line != null) {
                return line;
            }
            Thread.sleep(200);
        }
        return null;
    }

    private static String lineContaining(Path log, String marker) {
        try {
            if (!Files.exists(log)) {
                return null;
            }
            for (String line : readLinesUtf8(log)) {
                if (line.contains(marker)) {
                    return line;
                }
            }
        } catch (Exception ignore) {
            // 读取失败视为未命中
        }
        return null;
    }

    private static String tail(Path log) {
        return tail(log, 10);
    }

    private static String tail(Path log, int n) {
        try {
            List<String> lines = readLinesUtf8(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 子进程 stdout 按 UTF-8 读（子进程以 -Dfile.encoding=UTF-8 启动）——**不能用 ISO_8859_1**：
     * 中文取证行（织入通道首条/登记盲区）按 ISO_8859_1 解码成 mojibake，contains 恒不命中。
     * 用 byte→String 解码（畸形字节替换，不抛 MalformedInputException）。
     */
    private static List<String> readLinesUtf8(Path log) throws Exception {
        String all = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<String>();
        for (String s : all.split("\r?\n", -1)) {
            out.add(s);
        }
        return out;
    }

    /**
     * Hikari 子进程 classpath：从 {@code sqlvis.attachPoolChild} 配置取 HikariCP/javassist/slf4j/
     * 两个 MySQL 驱动；按文件名只挑一个 MySQL 驱动（A2 用 8.0.33、A3/B2 用 8.0.28），再挂 childapp classes。
     */
    private static String buildPoolClasspath(String mysqlJarName) {
        final String prop = System.getProperty("sqlvis.attachPoolChild", "");
        if (prop.isEmpty()) {
            throw new AssertionError("sqlvis.attachPoolChild 未注入（build.gradle.kts 的 systemProperty）");
        }
        final String childClasses = Paths.get("build", "classes", "java", "childapp")
                .toAbsolutePath().toString();
        List<String> libs = new ArrayList<String>();
        String chosenMysql = null;
        for (String j : prop.split(File.pathSeparator)) {
            if (j == null || j.isEmpty()) {
                continue;
            }
            final String fn = Paths.get(j).getFileName().toString();
            if (fn.toLowerCase().contains("mysql-connector")) {
                if (mysqlJarName != null && fn.equalsIgnoreCase(mysqlJarName)) {
                    chosenMysql = j;
                }
                continue;
            }
            libs.add(j);
        }
        if (chosenMysql == null && mysqlJarName != null) {
            throw new AssertionError("指定 MySQL 驱动不在 attachPoolChild: " + mysqlJarName);
        }
        List<String> all = new ArrayList<String>();
        if (chosenMysql != null) {
            all.add(chosenMysql);
        }
        all.addAll(libs);
        all.add(childClasses);
        return String.join(File.pathSeparator, all);
    }

    private static File locateAgentJar() {
        Path p = Paths.get("build", "dist", "mps-sql-agent.jar");
        if (!Files.exists(p)) {
            p = Paths.get("..", "agent", "build", "dist", "mps-sql-agent.jar");
        }
        if (!Files.exists(p)) {
            throw new AssertionError("agent fat jar 未找到（agentFatJar 任务产物）");
        }
        return p.toFile();
    }

    private static Path locateMysqlJar() {
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (e.contains("mysql-connector-j") && e.endsWith(".jar")) {
                return Paths.get(e);
            }
        }
        throw new AssertionError("mysql-connector-j jar 不在测试 classpath");
    }

    /** 供其它用例复用的探活：仅当 MySQL 可达才跑（不可达时 Assume 跳过，不伪造读数）。 */
    static boolean mysqlReachable() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 3306), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用户现场达梦库探活（demo application-dm.yml 的 URL host:port）。 */
    static boolean dmReachable() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("114.66.55.245", 20096), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
