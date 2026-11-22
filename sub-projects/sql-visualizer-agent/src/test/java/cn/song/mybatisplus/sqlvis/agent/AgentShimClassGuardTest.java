package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-OldJvmPremainGuard 券02：fat jar 入口引导类形态守卫。
 *
 * <p>钉住两件在源码层看不出来的事：
 * <ol>
 *   <li>manifest 的 {@code Premain-Class} / {@code Agent-Class} 必须是 {@code AgentShim}——直接指
 *       AgentMain 时，JDK 6/7 会在 premain 阶段载不动入口类，JVM 启动即终止（用户现场）；</li>
 *   <li>jar 内 {@code AgentShim.class} 的类文件版本必须是 major 50（Java 6）——主源集按
 *       {@code --release 8} 编译，降版本只发生在 {@code RelocateAsmMain}；这一步失效则引导类
 *       自身也就不可加载，等于没做。</li>
 * </ol>
 *
 * <p>另扫常量池：引导类只许用 Java 6 及更早 API，出现 lambda/indy 或 Java 7+ 类型引用时即便
 * 版本号降下来了，JDK 6 也会在解析阶段失败。反向验证：把 AgentShim 改成 lambda 写法、
 * 或去掉 RelocateAsmMain 的降版本分支，本类必红。
 */
public class AgentShimClassGuardTest {

    private static final String SHIM_ENTRY = "cn/song/mybatisplus/sqlvis/agent/AgentShim.class";
    private static final String SHIM_CLASS_NAME = "cn.song.mybatisplus.sqlvis.agent.AgentShim";
    private static final int JAVA_6_MAJOR = 50;

    /** Java 7+ 才有的类型/机制：出现任一即说明引导类在老的 JVM 上仍会失败。 */
    private static final List<String> FORBIDDEN_REFS = Arrays.asList(
            "java/util/function/",
            "java/util/Objects",
            "java/util/Optional",
            "java/util/concurrent/CompletableFuture",
            "java/util/stream/",
            "java/lang/invoke/",
            "java/util/Base64",
            "java/nio/file/"
    );

    private static File fatJar() {
        File jar = new File("build/dist/mps-sql-agent.jar");
        if (!jar.isFile()) {
            jar = Paths.get("..", "agent", "build", "dist", "mps-sql-agent.jar").toFile();
        }
        return jar;
    }

    @Test
    public void testManifest双入口指向引导类() throws Exception {
        File jar = fatJar();
        Assume.assumeTrue("fat jar 未构建（单独跑本类时跳过）：" + jar.getAbsolutePath(), jar.isFile());
        try (JarFile jf = new JarFile(jar)) {
            Manifest mf = jf.getManifest();
            assertNotNull("jar 缺 manifest", mf);
            assertEquals("Premain-Class 必须是引导类",
                    SHIM_CLASS_NAME, mf.getMainAttributes().getValue("Premain-Class"));
            assertEquals("Agent-Class 必须是引导类",
                    SHIM_CLASS_NAME, mf.getMainAttributes().getValue("Agent-Class"));
            assertEquals("true", mf.getMainAttributes().getValue("Can-Retransform-Classes"));
            assertEquals("true", mf.getMainAttributes().getValue("Can-Redefine-Classes"));
            // 引导类本体确实在 jar 里（入口指向了不存在的类同样是启动期致命）
            assertNotNull("jar 内缺 AgentShim.class", jf.getJarEntry(SHIM_ENTRY));
            // 真正的安装链仍在（引导类只是前置判定，不改主链路）
            assertNotNull("jar 内缺 AgentMain.class",
                    jf.getJarEntry("cn/song/mybatisplus/sqlvis/agent/AgentMain.class"));
        }
    }

    @Test
    public void test引导类类版本为Java6() throws Exception {
        File jar = fatJar();
        Assume.assumeTrue("fat jar 未构建（单独跑本类时跳过）：" + jar.getAbsolutePath(), jar.isFile());
        byte[] bytes = readEntry(jar, SHIM_ENTRY);
        assertTrue("不是 class 字节", bytes.length > 8);
        assertEquals(0xCA, bytes[0] & 0xFF);
        assertEquals(0xFE, bytes[1] & 0xFF);
        assertEquals(0xBA, bytes[2] & 0xFF);
        assertEquals(0xBE, bytes[3] & 0xFF);
        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        assertEquals("引导类必须是 major 50（Java 6），实际 " + major, JAVA_6_MAJOR, major);
    }

    @Test
    public void test引导类常量池无Java7以上引用() throws Exception {
        File jar = fatJar();
        Assume.assumeTrue("fat jar 未构建（单独跑本类时跳过）：" + jar.getAbsolutePath(), jar.isFile());
        byte[] bytes = readEntry(jar, SHIM_ENTRY);
        ConstantPool cp = parseConstantPool(bytes);

        assertFalse("引导类不得使用 MethodHandle 常量（JDK 6 解析即失败）", cp.hasMethodHandle);
        assertFalse("引导类不得使用 InvokeDynamic 常量（lambda/字符串拼接诱导）", cp.hasInvokeDynamic);

        List<String> hits = new ArrayList<String>();
        for (String ref : cp.utf8) {
            for (String forbidden : FORBIDDEN_REFS) {
                if (ref.startsWith(forbidden) || ref.contains(forbidden)) {
                    hits.add(ref);
                }
            }
        }
        assertTrue("引导类引用了 Java 7+ 类型，JDK 6 上仍会失败：\n" + hits, hits.isEmpty());
    }

    @Test
    public void test引导类只反射调用AgentMain() throws Exception {
        File jar = fatJar();
        Assume.assumeTrue("fat jar 未构建（单独跑本类时跳过）：" + jar.getAbsolutePath(), jar.isFile());
        ConstantPool cp = parseConstantPool(readEntry(jar, SHIM_ENTRY));
        // 双入口名字必须在常量池里（manifest 指向的方法得真的存在）
        assertTrue("引导类应定义 premain", cp.utf8.contains("premain"));
        assertTrue("引导类应定义 agentmain", cp.utf8.contains("agentmain"));
        // 目标类名以字符串形态出现（反射加载，不编译期依赖 AgentMain——那会让引导类重新变成 major 52）
        assertTrue("引导类应通过类名字符串反射加载 AgentMain",
                cp.utf8.contains(SHIM_CLASS_NAME.replace("AgentShim", "AgentMain")));
    }

    // ---------- 常量池最小解析 ----------

    private static final class ConstantPool {
        final List<String> utf8 = new ArrayList<String>();
        boolean hasMethodHandle;
        boolean hasInvokeDynamic;
    }

    private static ConstantPool parseConstantPool(byte[] b) {
        ConstantPool cp = new ConstantPool();
        int count = u2(b, 8);
        int off = 10;
        for (int i = 1; i < count; i++) {
            int tag = b[off++] & 0xFF;
            switch (tag) {
                case 1: { // Utf8
                    int len = u2(b, off);
                    off += 2;
                    cp.utf8.add(new String(b, off, len, StandardCharsets.UTF_8));
                    off += len;
                    break;
                }
                case 3: case 4: // Integer / Float
                    off += 4;
                    break;
                case 5: case 6: // Long / Double（占两个槽位）
                    off += 8;
                    i++;
                    break;
                case 7: case 8: case 16: case 19: case 20: // Class/String/Module/Package/MethodType
                    off += 2;
                    break;
                case 9: case 10: case 11: case 12: case 17: case 18: // Field/Method/IMethod/NameAndType/Dynamic/InvokeDynamic
                    cp.hasInvokeDynamic |= (tag == 18);
                    off += 4;
                    break;
                case 15: // MethodHandle
                    cp.hasMethodHandle = true;
                    off += 3;
                    break;
                default:
                    throw new IllegalStateException("未知常量池 tag " + tag + " @ " + (off - 1));
            }
        }
        return cp;
    }

    private static int u2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static byte[] readEntry(File jar, String name) throws Exception {
        try (JarFile jf = new JarFile(jar)) {
            JarEntry e = jf.getJarEntry(name);
            assertNotNull(name + " 不在 jar 内", e);
            try (InputStream in = jf.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }
    }
}
