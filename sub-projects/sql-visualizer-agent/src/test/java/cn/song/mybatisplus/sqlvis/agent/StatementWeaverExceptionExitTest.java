package cn.song.mybatisplus.sqlvis.agent;

import cn.song.mybatisplus.sqlvis.agent.hook.SqlHookCallbacks;
import cn.song.mybatisplus.sqlvis.agent.hook.StatementRegistry;
import cn.song.mybatisplus.sqlvis.agent.model.SqlEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T-NestGateFix 守卫：timed 面（EXEC_PARAM/EXEC_CTX/BATCH）的 **catch-all 异常出口**。
 *
 * <p>立项背景（计划 §11.8.5 离线定案，结论 B）：织入器原先只在方法体里**显式出现**的
 * `IRETURN/LRETURN/ARETURN/ATHROW` 前插 `onExecuteExit`。异常由被调方法抛出、而本方法体没有
 * try/catch/finally 时（字节层没有 `athrow`），出口点不存在：`NestGate.enter` 计入、
 * `exit` 永不执行 → 该线程 depth 永久 +1，此后整类语句被当成外层抑制（磐维 A 模式
 * `PgStatement.execute` 真机实证）。
 *
 * <p>本类钉四件事（缺一不可）：
 * <ol>
 *   <li><b>行为</b>：夹具方法「委托给会抛的方法、自身无 try/catch」——抛完后同线程后续语句必须
 *       照常产出（修前零事件/后续被抑制）；</li>
 *   <li><b>字节级</b>：真实厂商类（磐维 V1/V2、金仓、达梦、PostgreSQL）每个 timed 方法
 *       handler 数 = 1、显式返回点仍有出口、enter 恒 1；</li>
 *   <li><b>返回形态</b>：int / boolean / ResultSet / void 四种返回各一例真织入 + 运行（含异常路径）；</li>
 *   <li><b>帧</b>：handler 帧只声明出口实际读取的槽（token/this/sql），其余 TOP——且不得被
 *       {@code patchFrames} 二次追加（否则跨类校验期 VerifyError）。</li>
 *   <li><b>t-sql-insight 合并裁决（2026-11-11 dev 并入）</b>：出口插桩只认**真正的返回点**
 *       （IRETURN/LRETURN/FRETURN/DRETURN/ARETURN/RETURN）；原始 {@code athrow} 指令**不插出口**
 *       ——它可能被驱动方法体内部的 handler 捕获（随后正常返回），插了会对「内部 catch 后成功」
 *       产假失败事件（{@code SqlInsightWeaveProbeTest#internallyCaughtThrowMustNotReportAnExecutionError}
 *       钉住）。真正逃逸的异常由方法边界的 catch-all handler 兜住并回调一次（handler 帧常驻，
 *       enter/exit 恒 1:1，本类 1)~3) 的行为断言不变）。</li>
 * </ol>
 */
public class StatementWeaverExceptionExitTest {

    private static final String PKG = "cn.song.mybatisplus.sqlvis.agent";
    private static final String SELF_PREFIX = PKG + ".StatementWeaverExceptionExitTest";
    private static final String NORMAL_PREFIX = PKG + ".FakeVendorFixtures";
    private static final String FIXTURE = SELF_PREFIX + "$ThrowingPs";

    /** 夹具面的 spec：同时覆盖无参 EXEC_CTX、带参 EXEC_PARAM（void 形态）与 BATCH。 */
    private static final StatementWeaver.Kind[] FIXTURE_FACE = {
            StatementWeaver.Kind.EXEC_PARAM,
            StatementWeaver.Kind.EXEC_CTX,
            StatementWeaver.Kind.BATCH,
            StatementWeaver.Kind.ADD_BATCH,
            StatementWeaver.Kind.CLEAR_BATCH,
            StatementWeaver.Kind.PARAM};

    /** 后续正常帧用的既有无参 PS 夹具（同样真织入，才有钩子与事件）。 */
    private static final StatementWeaver.Kind[] PS_FACE = {
            StatementWeaver.Kind.EXEC_CTX, StatementWeaver.Kind.BATCH, StatementWeaver.Kind.PARAM};

    private static final String CALLBACKS = "cn/song/mybatisplus/sqlvis/agent/hook/SqlHookCallbacks";

    private File dir;
    private ClassLoader loader;
    private boolean savedDisabled;

    @Before
    public void setUp() throws Exception {
        StatementRegistry.clearForTest();
        queue().clear();
        SqlHookCallbacks.resetNestGateForTest();
        savedDisabled = AgentState.isDisabled();
        AgentState.setDisabled(false);
        // 门在本分支默认关（嵌套语义裁决见 NestGate 类 KDoc）——本类守卫的是「门开启时」的
        // 出口配对能力（深度计数/泄漏判据全走 NestGate），故显式开闸跑（同 NestGateTest 先例）。
        nestGateEnabled(true);
        dir = Files.createTempDirectory("sqlvis-exit-").toFile();
        writeWoven(FIXTURE, FIXTURE_FACE);
        writeWoven(NORMAL_PREFIX + "$VendorPs", PS_FACE);
        loader = childFirstLoader();
    }

    @After
    public void tearDown() {
        AgentState.setDisabled(savedDisabled);
        StatementRegistry.clearForTest();
        SqlHookCallbacks.resetNestGateForTest();
        try {
            nestGateEnabled(false);
        } catch (Exception ignore) {
            // 复位失败不推翻用例结论（静态字段随下个用例 setUp 显式重设）
        }
        try {
            queue().clear();
        } catch (Exception ignore) {
            // 队列反射失败不影响用例结论
        }
    }

    /** NestGate 在 hook 包且 package-private，走反射桥（同 NestGateTest 先例）。 */
    private static void nestGateEnabled(boolean value) throws Exception {
        final Class<?> gate = Class.forName("cn.song.mybatisplus.sqlvis.agent.hook.NestGate");
        final java.lang.reflect.Method set = gate.getDeclaredMethod("setEnabledForTest", boolean.class);
        set.setAccessible(true);
        set.invoke(null, value);
    }

    // ---------- 1) 行为：异常出口不得泄漏深度（返回形态逐例） ----------

    /**
     * int 返回：`executeUpdate()` 委托 `boomInt()` 抛出、自身无 try/catch。
     * 修复后：抛出即产 1 条 error 事件、链归零；随后两帧都必须照常产出。
     * 修复前：抛出零事件且 depth 残留 → 第一帧产出后 `innerEmitted` 置位，第二帧起被抑制。
     */
    @Test
    public void 异常出口_int返回_抛后同线程照常产出() throws Exception {
        assertExceptionExitBalanced("executeUpdate", "boom-int");
    }

    @Test
    public void 异常出口_boolean返回_抛后同线程照常产出() throws Exception {
        assertExceptionExitBalanced("execute", "boom-bool");
    }

    @Test
    public void 异常出口_ResultSet返回_抛后同线程照常产出() throws Exception {
        assertExceptionExitBalanced("executeQuery", "boom-rs");
    }

    /**
     * void 返回：`execute(String)` 委托 `boomVoid()` 抛出。timed 面的 void 形态在生产登记表里
     * 不存在（审计全部厂商类，timed 方法只有 int/long/boolean/ResultSet/int[]/long[]），本用例
     * 只用于钉住「RETURN 也走出口 + handler 覆盖异常路径」的完整性。
     */
    @Test
    public void 异常出口_void返回_抛后同线程照常产出() throws Exception {
        final Object ps = newFixture();
        final Object normal = newNormalPs();
        StatementRegistry.register(normal, "SELECT after", "jdbc:exit://db");
        setFail(ps, true);

        final RuntimeException thrown = invokeExpectingThrow(ps, "execute", new Class<?>[]{String.class},
                "SELECT boom-void");
        assertTrue("夹具必须抛 IllegalStateException：" + thrown,
                thrown instanceof IllegalStateException);

        final SqlEvent errorEvent = queue().poll();
        assertNotNull("异常出口必须产一条 error 事件（修复前此处为零）", errorEvent);
        assertNotNull("error 事件必须带 errorText", errorEvent.errorText);
        assertTrue("errorText 应含夹具异常名：" + errorEvent.errorText,
                errorEvent.errorText.contains("IllegalStateException"));
        assertEquals("void 形态的 sql 取自 EXEC_PARAM 首参", "SELECT boom-void", errorEvent.sql);

        assertDepthRecovered(normal);
    }

    /** 正常路径：int/boolean/ResultSet 各产一条、void 走 RETURN 出口也产一条。 */
    @Test
    public void 正常路径_四种返回形态各产一条且深度归零() throws Exception {
        final Object ps = newFixture();
        StatementRegistry.register(ps, "SELECT ok", "jdbc:exit://db");

        setFail(ps, false);
        invoke(ps, "executeUpdate");
        invoke(ps, "execute");
        invoke(ps, "executeQuery");
        invoke(ps, "execute", new Class<?>[]{String.class}, "SELECT ok-param");

        assertEquals("四种返回形态各一条事件", 4, drain().size());
        assertEquals("正常路径不得抬升最高深度", 1, SqlHookCallbacks.nestMaxDepth());
        assertEquals("正常路径不得计陈旧", 0L, SqlHookCallbacks.nestStaleCount());
    }

    // ---------- 2) 字节级：真实厂商类逐方法 handler/出口/入口计数 ----------

    /**
     * 真实厂商类：timed 方法的织后计数必须满足
     * `enter=1`、`exit=原显式返回数+1`（handler 那一次）、catch-all handler 数 = 原值+1，
     * 且 handler label 紧随 FrameNode（帧未被 patchFrames 二次追加的形态证据）。
     *
     * <p>覆盖面：磐维 V1/V2（仓库夹具 jar）、金仓 kingbase8、达梦 DM8、PostgreSQL（agent 测试
     * classpath）。任何一个类不可得都直接失败——本用例是 premain 红线（织入类必须可校验）之外的
     * 「异常出口完整性」硬断言，不允许静默 skip。
     */
    @Test
    public void 真实厂商类_timed方法handler唯一且显式返回仍有出口() throws Exception {
        final List<String> audited = new ArrayList<String>();
        for (File jar : panweiJars()) {
            for (String dotted : new String[]{
                    "org.panweidb.jdbc.PgStatement", "org.panweidb.jdbc.PgPreparedStatement"}) {
                final byte[] original = readJarEntry(jar, dotted.replace('.', '/') + ".class");
                try (java.net.URLClassLoader jarLoader = new java.net.URLClassLoader(
                        new java.net.URL[]{jar.toURI().toURL()},
                        StatementWeaverExceptionExitTest.class.getClassLoader())) {
                    audited.add(jar.getName() + ":" + dotted);
                    assertTimedMethodInvariants(dotted, original, jarLoader);
                }
            }
        }
        assertFalse("至少要有磐维 V1/V2 夹具 jar 才能出具字节层读数", panweiJars().isEmpty());

        for (String dotted : new String[]{
                "com.kingbase8.jdbc.KbStatement",
                "dm.jdbc.driver.DmdbStatement",
                "org.postgresql.jdbc.PgStatement"}) {
            final byte[] original = classBytes(dotted);
            audited.add(dotted);
            assertTimedMethodInvariants(dotted, original, ClassLoader.getSystemClassLoader());
        }
        assertTrue("字节层覆盖面不足：" + audited, audited.size() >= 6);
    }

    // ---------- 3) 辅助：行为断言 ----------

    /**
     * 抛一次异常 → 断言 error 事件 + 随后同线程两帧都产出 + 最高深度回到 1（未泄漏）。
     *
     * <p>修前形态：抛出的帧无出口（零事件且 depth=1 残留）→ 第一帧产出并置 `innerEmitted`，
     * 第二帧被抑制（1 条事件、maxDepth=2）。
     */
    private void assertExceptionExitBalanced(String method, String expectedMessage) throws Exception {
        final Object ps = newFixture();
        final Object normal = newNormalPs();
        StatementRegistry.register(ps, "SELECT boom", "jdbc:exit://db");
        StatementRegistry.register(normal, "SELECT after", "jdbc:exit://db");

        setFail(ps, true);
        final RuntimeException thrown = invokeExpectingThrow(ps, method, new Class<?>[0]);
        assertTrue("夹具必须抛 IllegalStateException（" + method + "）",
                thrown instanceof IllegalStateException);
        assertTrue("异常消息应来自委托方法：" + thrown.getMessage(),
                thrown.getMessage() != null && thrown.getMessage().contains(expectedMessage));

        final SqlEvent errorEvent = queue().poll();
        assertNotNull("异常出口必须产一条 error 事件（修复前此处为零，且 depth 残留）", errorEvent);
        assertNotNull("error 事件必须带 errorText", errorEvent.errorText);
        assertTrue("errorText 应含夹具异常名：" + errorEvent.errorText,
                errorEvent.errorText.contains("IllegalStateException"));

        setFail(ps, false);
        assertDepthRecovered(normal);
    }

    /** 后续同线程两帧都必须产出；最高深度必须停在 1（没有嵌套/泄漏）。 */
    private void assertDepthRecovered(Object normal) throws Exception {
        normal.getClass().getMethod("executeUpdate").invoke(normal);
        normal.getClass().getMethod("executeUpdate").invoke(normal);

        final List<SqlEvent> after = drain();
        assertEquals("异常之后同线程两帧都必须照常产出（修复前第二帧被当成外层抑制）",
                2, after.size());
        assertEquals("正常帧的 sql 必须来自登记表", "SELECT after", after.get(0).sql);
        assertEquals("最高深度必须回落到 1（修复前异常帧残留使深度攀升到 2）",
                1, SqlHookCallbacks.nestMaxDepth());
        assertEquals("异常出口不得被计为陈旧嵌套", 0L, SqlHookCallbacks.nestStaleCount());
    }

    private Object newFixture() throws Exception {
        return Class.forName(FIXTURE, true, loader).getDeclaredConstructor().newInstance();
    }

    private Object newNormalPs() throws Exception {
        return Class.forName(PKG + ".FakeVendorFixtures$VendorPs", true, loader)
                .getDeclaredConstructor().newInstance();
    }

    private static void setFail(Object ps, boolean value) throws Exception {
        final Field f = ps.getClass().getField("fail");
        f.setBoolean(ps, value);
    }

    private static RuntimeException invokeExpectingThrow(Object target, String method,
                                                        Class<?>[] paramTypes, Object... args)
            throws Exception {
        try {
            target.getClass().getMethod(method, paramTypes).invoke(target, args);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                return (RuntimeException) cause;
            }
            throw new AssertionError("夹具应抛 RuntimeException，实际=" + cause, cause);
        }
        throw new AssertionError(method + " 未按预期抛出异常（夹具前置失效）");
    }

    private static void invoke(Object target, String method) throws Exception {
        target.getClass().getMethod(method).invoke(target);
    }

    private static void invoke(Object target, String method, Class<?>[] paramTypes, Object... args)
            throws Exception {
        target.getClass().getMethod(method, paramTypes).invoke(target, args);
    }

    // ---------- 4) 辅助：字节级断言 ----------

    private static void assertTimedMethodInvariants(String dotted, byte[] original, ClassLoader defLoader)
            throws Exception {
        final StatementWeaver.Kind[] face = StatementWeaver.specOf(dotted);
        assertNotNull(dotted + " 未登记进 SPECS", face);
        final byte[] woven = StatementWeaver.weaveSpec(face, defLoader, original);
        assertFalse(dotted + " 必须真实织入（changed）", java.util.Arrays.equals(original, woven));

        final Map<String, Counts> before = count(original);
        final Map<String, Counts> after = count(woven);
        int timed = 0;
        for (Map.Entry<String, Counts> e : after.entrySet()) {
            final Counts b = before.get(e.getKey());
            final Counts a = e.getValue();
            if (b == null || !isTimedMethodName(e.getKey())) {
                continue;
            }
            timed++;
            final String label = dotted + "#" + e.getKey();
            assertEquals(label + " 原字节不应已有钩子", 0, b.enter + b.exit);
            assertEquals(label + " enter 必须恰好 1", 1, a.enter);
            assertEquals(label + " 显式返回点必须仍有出口，handler 另加 1 次（原始 athrow 不插出口："
                            + "可能被驱动内部 catch——逃逸异常由 handler 兜住回调，见类 KDoc 5)）：" + e.getKey(),
                    b.returns + 1, a.exit);
            assertEquals(label + " 必须恰好新增 1 个 catch-all handler",
                    b.catchAllHandlers + 1, a.catchAllHandlers);
            assertTrue(label + " handler 后必须紧跟帧（否则跨类校验 VerifyError）",
                    a.handlerFollowedByFrame);
        }
        assertTrue(dotted + " 必须有 timed 方法被织入（登记了却零命中即假覆盖）", timed > 0);
    }

    /** 方法名属于 timed 面（execute 族带参/无参 + 批处理）。 */
    private static boolean isTimedMethodName(String nameAndDesc) {
        final String name = nameAndDesc.substring(0, nameAndDesc.indexOf('('));
        final String desc = nameAndDesc.substring(nameAndDesc.indexOf('('));
        if ("execute".equals(name) || "executeQuery".equals(name) || "executeUpdate".equals(name)
                || "executeLargeUpdate".equals(name)) {
            return desc.startsWith("(Ljava/lang/String;") || desc.startsWith("()");
        }
        if ("executeBatch".equals(name) || "executeLargeBatch".equals(name)) {
            return desc.startsWith("()");
        }
        return false;
    }

    private static Map<String, Counts> count(byte[] bytes) {
        final Map<String, Counts> map = new LinkedHashMap<String, Counts>();
        new ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, final String name,
                                                               final String desc, String sig,
                                                               String[] exceptions) {
                final Counts c = new Counts();
                map.put(name + desc, c);
                return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                                   org.objectweb.asm.Label end,
                                                   org.objectweb.asm.Label handler, String type) {
                        if (type == null) {
                            c.catchAllHandlers++;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mn, String md,
                                                boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && CALLBACKS.equals(owner)) {
                            if ("onStatementEnter".equals(mn)) {
                                c.enter++;
                            } else if ("onExecuteExit".equals(mn)) {
                                c.exit++;
                            }
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        switch (opcode) {
                            case Opcodes.IRETURN:
                            case Opcodes.LRETURN:
                            case Opcodes.FRETURN:
                            case Opcodes.DRETURN:
                            case Opcodes.ARETURN:
                            case Opcodes.RETURN:
                                c.returns++;
                                break;
                            case Opcodes.ATHROW:
                                // t-sql-insight 合并裁决：原始 athrow 不再插出口（可能被驱动内部
                                // catch，插了会对「内部 catch 后成功返回」产假失败事件）——单独计数，
                                // 出口判据只认真正的返回点（见 assertTimedMethodInvariants）。
                                c.athrowCount++;
                                break;
                            default:
                                break;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        // handler 帧存在性：需要标签身份，故二次走 ClassNode（只在测试里做，量小）
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        for (MethodNode mn : node.methods) {
            final Counts c = map.get(mn.name + mn.desc);
            if (c == null) {
                continue;
            }
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                if (tcb.type != null) {
                    continue;
                }
                AbstractInsnNode n = nextReal(tcb.handler);
                c.handlerFollowedByFrame = c.handlerFollowedByFrame || (n instanceof FrameNode);
            }
        }
        return map;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode node) {
        AbstractInsnNode n = node.getNext();
        while (n != null && n instanceof LabelNode) {
            n = n.getNext();
        }
        return n;
    }

    private static final class Counts {
        int enter;
        int exit;
        int returns;
        int athrowCount;
        int catchAllHandlers;
        boolean handlerFollowedByFrame;
    }

    // ---------- 5) 辅助：字节/loader ----------

    private void writeWoven(String dotted, StatementWeaver.Kind[] face) throws Exception {
        final byte[] original = classBytes(dotted);
        final byte[] woven = StatementWeaver.weaveSpec(face,
                ClassLoader.getSystemClassLoader(), original);
        final File cls = new File(dir, dotted.replace('.', '/') + ".class");
        cls.getParentFile().mkdirs();
        Files.write(cls.toPath(), woven);
        assertTrue(dotted + " 必须真被织入（夹具前置）",
                !java.util.Arrays.equals(original, woven));
    }

    private static byte[] classBytes(String dotted) throws Exception {
        final String path = dotted.replace('.', '/') + ".class";
        try (InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(path)) {
            assertNotNull("编译产物缺失：" + path, in);
            return readAll(in);
        }
    }

    private static byte[] readJarEntry(File jar, String entry) throws Exception {
        try (JarFile jf = new JarFile(jar)) {
            final java.util.jar.JarEntry e = jf.getJarEntry(entry);
            assertNotNull(jar.getName() + " 内无条目 " + entry, e);
            try (InputStream in = jf.getInputStream(e)) {
                return readAll(in);
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private ClassLoader childFirstLoader() throws Exception {
        return new java.net.URLClassLoader(new java.net.URL[]{dir.toURI().toURL()},
                ClassLoader.getSystemClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(SELF_PREFIX) || name.startsWith(NORMAL_PREFIX)) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            c = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<SqlEvent> queue() throws Exception {
        final Field field = cn.song.mybatisplus.sqlvis.agent.transport.EventBus.class
                .getDeclaredField("QUEUE");
        field.setAccessible(true);
        return (BlockingQueue<SqlEvent>) field.get(null);
    }

    private static List<SqlEvent> drain() {
        final List<SqlEvent> out = new ArrayList<SqlEvent>();
        try {
            SqlEvent e;
            while ((e = queue().poll()) != null) {
                out.add(e);
            }
        } catch (Exception ignore) {
            // 反射失败按空处理（用例断言自然变红）
        }
        return out;
    }

    private static List<File> panweiJars() {
        final List<File> out = new ArrayList<File>();
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            final File drivers = new File(new File(new File(dir, "database-lab"), "vendor"), "drivers");
            if (drivers.isDirectory()) {
                final File[] jars = drivers.listFiles(new java.io.FilenameFilter() {
                    @Override
                    public boolean accept(File d, String n) {
                        return n.startsWith("panweidb-jdbc-") && n.endsWith(".jar");
                    }
                });
                if (jars != null) {
                    java.util.Arrays.sort(jars);
                    out.addAll(java.util.Arrays.asList(jars));
                }
                return out;
            }
            dir = dir.getParentFile();
        }
        return out;
    }

    // ---------- 夹具 ----------

    /**
     * 织入夹具：所有 execute 族方法都「自身无 try/catch、委托给会抛的方法」——异常从被调方法
     * 逃逸时本方法体没有 `athrow`，正是 §11.8.5 定案的漏出口形态。
     */
    public static class ThrowingPs {
        public boolean fail;

        public int executeUpdate() {
            if (fail) {
                return boomInt();
            }
            return 7;
        }

        public boolean execute() {
            if (fail) {
                return boomBool();
            }
            return false;
        }

        public ResultSet executeQuery() {
            if (fail) {
                return boomRs();
            }
            return null;
        }

        public void execute(String sql) {
            if (fail) {
                boomVoid();
            }
        }

        private int boomInt() {
            throw new IllegalStateException("boom-int");
        }

        private boolean boomBool() {
            throw new IllegalStateException("boom-bool");
        }

        private ResultSet boomRs() {
            throw new IllegalStateException("boom-rs");
        }

        private void boomVoid() {
            throw new IllegalStateException("boom-void");
        }
    }
}
