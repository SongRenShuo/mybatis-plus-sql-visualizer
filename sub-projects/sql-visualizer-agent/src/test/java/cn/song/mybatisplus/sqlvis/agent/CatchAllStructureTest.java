package cn.song.mybatisplus.sqlvis.agent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T-CaptureCatchAll 券 10「结构自检」：**新增织入形态必须在隔离 loader 里定义 + 初始化**
 * （JVM 链接期校验），池包装类也要过。
 *
 * <p>为什么池包装类必须进这条防线：接口兜底会命中 HikariCP 的
 * {@code HikariProxyPreparedStatement}/{@code HikariProxyConnection}（{@code final}、直接
 * {@code implements java.sql.PreparedStatement/Connection}）。这类类的方法体是「转发 + 少量分支」，
 * 与白名单驱动的形态不同；织入形态若在某个出口漏补帧/漏对齐槽位，premain 路径会让宿主起不来
 * （达梦 {@code chop_frame} 先例）。attach 路径虽只丢单类，但「结构过不了校验」应当在实施期就被
 * 字节层钉住，而不是等真机冒烟。
 *
 * <p>夹具来源：HikariCP 4.0.3 的 jar **自带**预生成的 {@code HikariProxy*} 类（不是运行时 javassist
 * 生成物），故可以直接读字节、织入、在隔离 loader 里定义并初始化。jar 路径从既有
 * {@code sqlvis.attachPoolChild} 配置解析（不新增构建依赖）。
 *
 * <p>判别力自证：{@link #非法帧合成类_必被同一校验器拒} 用同一 loader 定义一个人为撒谎
 * StackMapTable 的合成类，断言被拒——防止「loader 压根没触发校验」的假绿。
 */
public class CatchAllStructureTest {

    private static final String HIKARI_PS = "com.zaxxer.hikari.pool.HikariProxyPreparedStatement";
    private static final String HIKARI_CONN = "com.zaxxer.hikari.pool.HikariProxyConnection";

    @Before
    public void setUp() {
        CaptureBudget.resetForTest();
        CatchAllMatcher.resetForTest();
    }

    @After
    public void tearDown() {
        CaptureBudget.resetForTest();
        CatchAllMatcher.resetForTest();
    }

    @Test
    public void 池包装PS_兜底语句面织入后过链接期校验() throws Exception {
        assertWovenAndVerifiable(HIKARI_PS, WeaveSpecs.catchAllStatementFace());
    }

    @Test
    public void 池包装Connection_兜底prepare面织入后过链接期校验() throws Exception {
        assertWovenAndVerifiable(HIKARI_CONN, WeaveSpecs.catchAllPrepareFace());
    }

    @Test
    public void 池包装类_织入不改变类型契约() throws Exception {
        final Class<?> defined = assertWovenAndVerifiable(HIKARI_PS, WeaveSpecs.catchAllStatementFace());
        assertTrue("织入后仍必须是 java.sql.PreparedStatement（对象身份/类型契约不变）",
                java.sql.PreparedStatement.class.isAssignableFrom(defined));
        assertTrue("织入后仍是 final 类（只改方法体，不增删成员/修饰符）",
                java.lang.reflect.Modifier.isFinal(defined.getModifiers()));
    }

    @Test
    public void 非法帧合成类_必被同一校验器拒() throws Exception {
        final String dotted = "cn.song.mybatisplus.sqlvis.agent.it.BadFrameStmt";
        final byte[] bad = synthBadFrameClass(dotted.replace('.', '/'));
        try {
            defineAndVerify(dotted, bad, new URL[0], "cn.song.mybatisplus.sqlvis.agent.it.");
            fail("撒谎的 StackMapTable 必须被链接期校验拒绝（否则字节层用例无鉴别力）");
        } catch (VerifyError expected) {
            // 期望：栈图与实际状态不符
        } catch (ClassFormatError expected) {
            // 结构性非法同样算被拒（不同 JVM 判定阶段差异）
        }
    }

    // ---------- 工具 ----------

    private Class<?> assertWovenAndVerifiable(String dotted, StatementWeaver.Kind[] face) throws Exception {
        final byte[] original = hikariClassBytes(dotted);
        final byte[] woven = StatementWeaver.weaveSpec(face, getClass().getClassLoader(), original);
        assertFalse(dotted + " 必须被兜底面真织入（changed）——命中却零插桩即假覆盖",
                Arrays.equals(original, woven));
        return defineAndVerify(dotted, woven, hikariJars(), "com.zaxxer.hikari.");
    }

    /** 织入产物在子优先 loader 定义 + 初始化（初始化 = 链接 + 校验）。 */
    private static Class<?> defineAndVerify(String dotted, byte[] woven, URL[] urls,
                                           String childFirstPrefix) throws Exception {
        final URLClassLoader loader = new URLClassLoader(urls,
                CatchAllStructureTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        if (name.equals(dotted)) {
                            c = defineClass(name, woven, 0, woven.length);
                        } else if (name.startsWith(childFirstPrefix)) {
                            // 被织类的父类/同包类必须与它落在同一 runtime package（否则 IllegalAccessError）
                            c = findClass(name);
                        } else {
                            c = super.loadClass(name, false);
                        }
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
            }
        };
        loader.loadClass(dotted);
        return Class.forName(dotted, true, loader);
    }

    private static URL[] hikariJars() throws Exception {
        final String path = System.getProperty("sqlvis.attachPoolChild");
        assertNotNull("sqlvis.attachPoolChild 未注入——池包装类结构自检失去夹具来源", path);
        final List<URL> urls = new ArrayList<URL>();
        for (String p : path.split(File.pathSeparator)) {
            if (p.endsWith(".jar")) {
                urls.add(new File(p).toURI().toURL());
            }
        }
        assertTrue("attachPoolChild 配置未解析出任何 jar", urls.size() > 0);
        return urls.toArray(new URL[0]);
    }

    private static byte[] hikariClassBytes(String dotted) throws Exception {
        final String resource = dotted.replace('.', '/') + ".class";
        for (URL u : hikariJars()) {
            try (URLClassLoader l = new URLClassLoader(new URL[]{u}, null);
                 InputStream in = l.getResourceAsStream(resource)) {
                if (in != null) {
                    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    return bos.toByteArray();
                }
            }
        }
        throw new IllegalStateException("Hikari jar 内未找到类字节：" + resource);
    }

    /** 合成一个 StackMapTable 撒谎的类（帧声明与真实状态不一致，仅在链接期暴露）。 */
    private static byte[] synthBadFrameClass(String internalName) {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", null);
        final MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", "()V", null, null);
        mv.visitCode();
        final Label entry = new Label();
        mv.visitLabel(entry);
        mv.visitFrame(Opcodes.F_FULL, 1, new Object[]{internalName}, 1, new Object[]{Opcodes.INTEGER});
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
