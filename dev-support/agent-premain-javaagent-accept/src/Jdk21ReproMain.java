package demo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * JDK 21 上最小可复现：第三方 agent「让重定位 ASM 不可见」时，本插件 premain 是否会让
 * 目标 JVM 起不来。
 *
 * <p><b>它复刻的是什么</b>：用户现场是 JRebel 接管 premain 的类加载后，
 * {@code cn/song/mybatisplus/sqlvis/agent/asm/Opcodes} 在 agent 的加载器里解析不到。
 * 最贴近且不依赖 JRebel 授权的做法，是**把该 class 从 agent jar 里删掉**——对 JVM 而言
 * 两件事等价（都是「这个类在这个加载器里不存在」），且完全确定、可重放。
 *
 * <p><b>为什么要先"预热语句类"</b>：本插件只在 {@code getAllLoadedClasses()} 里出现
 * **登记表内的语句/驱动类**时才会调用出问题的那段谓词。干净启动的小应用
 * {@code matched=0}，根本走不到崩溃点（这也是「本机不复现」的原因之一）。
 * 故本 demo 在前置 agent 的 premain 里先 {@code Class.forName} 一个语句面类，
 * 让后续 premain 必然命中——这才构成与用户现场等价的触发条件。
 *
 * <p><b>三种模式</b>：
 * <ul>
 *   <li>{@code prep} —— 生成"缺 Opcodes"的 agent jar 副本（演示前置步骤）；</li>
 *   <li>{@code child} —— 作为「目标应用」被真实 {@code -javaagent} 启动（正/负例都用它）；</li>
 *   <li>{@code run} —— 编排：分别用修复版裸 jar 与缺类 jar 起子进程，读退出码与 stderr 给结论。</li>
 * </ul>
 *
 * <p>注意本类**不做任何断言式"作弊"**：子进程是真的 JVM、真的 {@code -javaagent}、
 * 真的启动 Spring 之外的最小 main。修复是否有效，只看子进程能不能活着走到
 * {@code [App] started}。
 */
public final class Jdk21ReproMain {

    /** 目标 JVM 用哪个类作为「预热语句面」——必须逐字命中插件的名单。 */
    static final String PRELOAD_CLASS = "com.mysql.cj.jdbc.StatementImpl";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Jdk21ReproMain <prep|child|run> [args...]");
            System.exit(2);
        }
        switch (args[0]) {
            case "prep" -> prep(args);
            case "child" -> child();
            case "run" -> run(args);
            default -> {
                System.err.println("unknown mode: " + args[0]);
                System.exit(2);
            }
        }
    }

    // ------------------------------------------------------------------ child

    /** 「目标应用」：能被 {@code -javaagent} 挂上、打印一行就正常退出。 */
    private static void child() {
        System.out.println("[App] started, java=" + System.getProperty("java.version"));
    }

    // ------------------------------------------------------------------ prep

    /**
     * 生成缺 {@code asm/Opcodes.class} 的 agent jar 副本。
     *
     * <p>为什么删这一个类就够：插件把 ASM 重定位到 {@code ...agent.asm} 包，而
     * {@code StatementWeaver} 的父接口正是 {@code asm/Opcodes}——它一缺席，织入器类初始化
     * 就必然失败，与用户现场指向同一个缺失类。
     */
    private static void prep(String[] args) throws Exception {
        final File src = new File(args[1]);
        final File dst = new File(args[2]);
        final String removed = copyJarSkipping(src, dst,
                "cn/song/mybatisplus/sqlvis/agent/asm/Opcodes.class");
        System.out.println("[prep] source = " + src.getAbsolutePath());
        System.out.println("[prep] output = " + dst.getAbsolutePath());
        System.out.println("[prep] removed = " + removed + "（不存在则说明该 jar 已缺类）");
    }

    /** 复制 jar，跳过指定条目；返回被跳过的条目名（没跳过返回 null）。 */
    private static String copyJarSkipping(File src, File dst, String skip) throws Exception {
        dst.getParentFile().mkdirs();
        String skipped = null;
        try (ZipFile zin = new ZipFile(src);
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(dst))) {
            final Enumeration<? extends ZipEntry> en = zin.entries();
            while (en.hasMoreElements()) {
                final ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                if (e.getName().equals(skip)) {
                    skipped = e.getName();
                    continue;
                }
                zout.putNextEntry(new ZipEntry(e.getName()));
                try (InputStream in = zin.getInputStream(e)) {
                    in.transferTo(zout);
                }
                zout.closeEntry();
            }
        }
        return skipped;
    }

    // ------------------------------------------------------------------ run

    /**
     * 编排：用真实 {@code -javaagent} 起两个子进程。
     *
     * <p>参数：{@code run <agentJar> <preAgentJar> <mysqlDriverJar> <outDir> <javaExe>}
     */
    private static void run(String[] args) throws Exception {
        final File agentJar = new File(args[1]);
        final File preAgentJar = new File(args[2]);
        final File mysqlJar = new File(args[3]);
        final File outDir = new File(args[4]);
        final String javaExe = args[5];
        outDir.mkdirs();

        final File brokenJar = new File(outDir, "mps-sql-agent-no-opcodes.jar");
        copyJarSkipping(agentJar, brokenJar, "cn/song/mybatisplus/sqlvis/agent/asm/Opcodes.class");

        System.out.println("================================================================");
        System.out.println("  JDK 21 premain 崩溃复现 / 修复验证");
        System.out.println("================================================================");
        System.out.println("  java        = " + javaExe);
        verifyJavaVersion(javaExe);
        System.out.println("  agentJar    = " + agentJar.getAbsolutePath() + "  (" + agentJar.length() + " B)");
        System.out.println("  agent 含 Opcodes = " + containsEntry(agentJar, "cn/song/mybatisplus/sqlvis/agent/asm/Opcodes.class"));
        System.out.println("  agent 含 WeaveSpecs(修复标志) = " + containsEntry(agentJar, "cn/song/mybatisplus/sqlvis/agent/WeaveSpecs.class"));
        System.out.println("  缺类 jar    = " + brokenJar.getName());
        System.out.println();

        final String cp = System.getProperty("java.class.path");
        final boolean hasFix = containsEntry(agentJar, "cn/song/mybatisplus/sqlvis/agent/WeaveSpecs.class");
        int failed = 0;

        // ① 完整 agent（对照：类齐全时无论哪版都该正常）
        failed += cell("1. 完整 agent（对照：应正常启动）", outDir, "cell1-intact",
                javaExe, cp, preAgentJar, agentJar, mysqlJar, true);

        // ② 缺 Opcodes + 当前 agent（核心：新版应活、旧版应死）
        failed += cell("2. 缺 asm/Opcodes（核心：修复版应正常启动）", outDir, "cell2-broken",
                javaExe, cp, preAgentJar, brokenJar, mysqlJar, true);

        // ③ 缺 Opcodes + 不预热 + 无驱动 —— 固化一条曾被误判的事实：
        //    **`matched=0` 不等于谓词没被调用**。retransformLoadedDrivers 对每一个已加载类
        //    都依次调用三个谓词，`matched` 只在命中后才 +1；只要其中任一次执行触发
        //    StatementWeaver 类初始化，旧版就崩——与「有没有类命中」无关。
        //    因此本格的期望随 agent 是否含修复而变：修复版应活，旧版应崩。由 hasFix 决定。
        final String cell3Title = hasFix
                ? "3. 缺 asm/Opcodes + 不预热（修复版应活：兜底接住「谓词必被调用」）"
                : "3. 缺 asm/Opcodes + 不预热（旧版应崩：matched=0 也逃不过）";
        failed += cell(cell3Title, outDir, "cell3-no-warmup", javaExe, cp, null, brokenJar, null, false,
                /*expectStart=*/ hasFix);

        System.out.println();
        System.out.println("================================================================");
        if (failed == 0) {
            System.out.println("  结论：全部格符合预期");
        } else {
            System.out.println("  结论：" + failed + " 格与预期不符（详见 " + outDir.getAbsolutePath() + "）");
        }
        System.out.println("================================================================");
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * 跑一格。
     *
     * @param warmup 是否挂前置 agent 预热语句类
     * @return 0=符合预期，1=不符
     */
    private static int cell(String title, File outDir, String logName, String javaExe, String cp,
                            File preAgent, File agentJar, File mysqlJar, boolean warmup) throws Exception {
        return cell(title, outDir, logName, javaExe, cp, preAgent, agentJar, mysqlJar, warmup, true);
    }

    /** @param expectStart 期望应用能正常启动（false 表示本格期望它崩，用于固化「谓词必被调用」） */
    private static int cell(String title, File outDir, String logName, String javaExe, String cp,
                            File preAgent, File agentJar, File mysqlJar, boolean warmup,
                            boolean expectStart) throws Exception {
        System.out.println("--- " + title + " ---");
        final List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        if (preAgent != null && warmup) {
            cmd.add("-javaagent:" + preAgent.getAbsolutePath());
        }
        cmd.add("-javaagent:" + agentJar.getAbsolutePath() + "=port=" + freePort() + ";logLevel=warn");
        cmd.add("-cp");
        cmd.add(mysqlJar != null && mysqlJar.isFile() ? cp + File.pathSeparator + mysqlJar.getAbsolutePath() : cp);
        cmd.add("demo.Jdk21ReproMain");
        cmd.add("child");

        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        final String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        final int code = p.waitFor();
        try (OutputStream fo = new FileOutputStream(new File(outDir, logName + ".log"))) {
            fo.write(out.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        final boolean started = out.contains("[App] started");
        final boolean fatal = out.contains("FATAL ERROR in native method")
                || out.contains("processJavaStart failed");
        final boolean ncdfe = out.contains("NoClassDefFoundError")
                && out.contains("asm/Opcodes");
        final boolean degraded = out.contains("[SqlVisAgent] install degraded:");

        System.out.println("    exit=" + code + "  appStarted=" + started + "  fatal=" + fatal
                + "  ncdfe(asm/Opcodes)=" + ncdfe + "  degradedLine=" + degraded);
        for (String line : out.split("\\R")) {
            if (line.contains("[App]") || line.contains("[SqlVisAgent]") || line.contains("FATAL")
                    || line.startsWith("Caused by") || line.contains("DriverRegistry.isStatementWeavable")) {
                System.out.println("    | " + line);
            }
        }

        // 判定分两层：
        // ① 应用必须活着起来（不是 FATAL）——这是修复要保证的；
        // ② 需要预热的格必须真的走到谓词（matched>=1）。否则「不崩」可能只是因为根本没触发，
        //    是假绿（首次实现就踩过：classpath 没 mysql 驱动 → 预热 CNFE → matched=0 → 全绿但无意义）。
        boolean ok;
        String why;
        if (expectStart) {
            ok = started && !fatal;
            why = ok ? "应用正常启动，未致命" : "应用未正常启动（期望启动）";
            if (ok && warmup && !out.contains("matched=1") && !out.matches("(?s).*matched=[2-9].*")) {
                ok = false;
                why = "预热未生效（matched=0）：本格没真正命中登记类，判 red 以防假绿——请确认 -MysqlJar 指向 8.x";
            }
        } else {
            // 反向格：期望崩（用于证明「旧版即使 matched=0 也会崩」）
            ok = fatal || !started;
            why = ok ? "如期致命（证明谓词对每个已加载类都会被调用，与 matched 无关）"
                     : "未如预期崩溃——该 agent 可能已含修复";
        }
        System.out.println("    → " + (ok ? "PASS（" + why + "）" : "FAIL（" + why + "）"));
        System.out.println();
        return ok ? 0 : 1;
    }

    // ------------------------------------------------------------------ util

    private static boolean containsEntry(File jar, String name) {
        try (ZipFile z = new ZipFile(jar)) {
            return z.getEntry(name) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void verifyJavaVersion(String javaExe) {
        try {
            final Process p = new ProcessBuilder(javaExe, "-version")
                    .redirectErrorStream(true).start();
            final String v;
            try (InputStream in = p.getInputStream()) {
                v = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            p.waitFor();
            for (String line : v.split("\\R")) {
                if (line.contains("version")) {
                    System.out.println("  java -version: " + line.trim());
                    break;
                }
            }
        } catch (Exception ignore) {
            System.out.println("  java -version: <读取失败>");
        }
    }

    /** 取一个空闲端口（agent 要求 port>0 才会继续安装）。 */
    private static int freePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            return 47999;
        }
    }

    private Jdk21ReproMain() {
    }
}
