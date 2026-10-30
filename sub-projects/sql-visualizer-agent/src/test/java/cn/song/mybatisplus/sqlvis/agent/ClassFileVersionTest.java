package cn.song.mybatisplus.sqlvis.agent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCatchAll 券 05 守卫：类文件 major 版本闸。
 *
 * <p>现场事实：ASM 9.4 读 {@code h2-2.3.232.jar} 的 {@code META-INF/versions/21} 类（major 65）
 * 抛 {@code Unsupported class file major version 65}。本类钉三件事：
 * <ol>
 *   <li>闸上限与 ASM 版本同源：{@code MAX_SUPPORTED_MAJOR == Opcodes.V25}（ASM 9.8 上限）；
 *       只升 ASM 不改常量（或反之）即转红；</li>
 *   <li>major 读取只看 {@code bytes[6..7]}，magic 不符不越权拦截（垃圾字节仍由织入器报错计数）；</li>
 *   <li>transformer 入口在超限时原字节放行（返回 null）+ 计数 {@code skippedByVersion}，
 *       且**不**落进 failed 计数（否则「名单命中但织不上」的判读被污染）。</li>
 * </ol>
 */
public class ClassFileVersionTest {

    private final SqlHookTransformer transformer = new SqlHookTransformer();

    @Before
    public void setUp() {
        CaptureBudget.resetForTest();
        CatchAllMatcher.resetForTest();
        SqlHookTransformer.resetCountersForTest();
    }

    @After
    public void tearDown() {
        CaptureBudget.resetForTest();
        CatchAllMatcher.resetForTest();
    }

    @Test
    public void 闸上限与ASM支持上限同源() {
        assertEquals("版本闸上限必须等于 ASM 支持的最高 major（升级 ASM 须同步本常量，"
                + "否则新 JDK 的驱动类被误挡在闸外：skippedByVersion 持续增长、零钩子）",
                Opcodes.V25, ClassFileVersion.MAX_SUPPORTED_MAJOR);
        assertEquals("ASM 9.8 的 V25 应为 69（Java 25）", 69, Opcodes.V25);
    }

    @Test
    public void major读取_只看头两字节且magic不符不拦截() {
        assertEquals("Java 8 类 = major 52", 52, ClassFileVersion.majorOf(tinyClass(52)));
        assertEquals("JDK21 = 65", 65, ClassFileVersion.majorOf(tinyClass(65)));
        assertEquals("JDK25 = 69", 69, ClassFileVersion.majorOf(tinyClass(69)));
        assertTrue("未超上限放行", ClassFileVersion.supported(tinyClass(69)));
        assertFalse("超上限必须挡（JDK26 = 70）", ClassFileVersion.supported(tinyClass(70)));

        assertEquals("非 class 字节读不出 major", -1, ClassFileVersion.majorOf(new byte[]{0, 1, 2}));
        assertEquals("长度不足读不出 major", -1, ClassFileVersion.majorOf(new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}));
        assertTrue("magic 不符不越权拦截（交给织入器按原逻辑报错）",
                ClassFileVersion.supported(new byte[]{0, 1, 2, 3, 4, 5, 6, 7}));
        assertTrue("null 不越权拦截", ClassFileVersion.supported(null));
    }

    @Test
    public void transformer入口_超限原字节放行且只计版本闸() throws Exception {
        final byte[] tooNew = tinyClass(70);
        final byte[] out = transformer.transform(getClass().getClassLoader(),
                "org/h2/jdbc/JdbcStatement", null, null, tooNew);

        assertNull("超限类必须原字节放行（返回 null），绝不让 ASM 抛异常", out);
        assertEquals("skippedByVersion 计数", 1L, SqlHookTransformer.skippedByVersionCount());
        assertEquals("同源计数（心跳带出）", 1L, CaptureBudget.skippedByVersionCount());
        assertEquals("不得计入 failed（否则污染『名单命中但织不上』的判读）",
                0L, SqlHookTransformer.failedCount());
        assertEquals("也不得计入名单命中", 0L, SqlHookTransformer.weavableCount());
    }

    @Test
    public void 闸内类照常进入织入分派() throws Exception {
        // 夹具字节是 Java 8（major 52）——闸不拦，进入名单分派（noChange：无方法可织）
        final byte[] bytes = resourceBytes("FakeVendorFixtures$BoomException.class");
        final byte[] out = transformer.transform(getClass().getClassLoader(),
                "org/h2/jdbc/JdbcStatement", null, null, bytes);
        assertEquals("闸内类必须照常走名单分派", 1L, SqlHookTransformer.weavableCount());
        assertEquals(0L, SqlHookTransformer.skippedByVersionCount());
        assertTrue("无方法可织即原字节返回", out == bytes);
    }

    /** 用 ASM 生成一个最小类并把 major 改写成指定值（minor 置 0）。 */
    private static byte[] tinyClass(int major) {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "x/y/Tiny", null,
                "java/lang/Object", null);
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        final byte[] bytes = cw.toByteArray();
        bytes[4] = 0; // minor hi
        bytes[5] = 0; // minor lo
        bytes[6] = (byte) ((major >> 8) & 0xff);
        bytes[7] = (byte) (major & 0xff);
        return bytes;
    }

    private static byte[] resourceBytes(String simpleName) throws Exception {
        final String path = "/cn/song/mybatisplus/sqlvis/agent/" + simpleName;
        try (java.io.InputStream in = ClassFileVersionTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("夹具字节缺失: " + path);
            }
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
