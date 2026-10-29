package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T-PgWeave 守卫：{@link StatementWeaver#SPECS} 语句面/prepare 面登记名单的**驱动实证回归**。
 *
 * <p>动因（2026-10-09 真机复现）：用户对运行中 PostgreSQL 应用 attach 增强捕获后面板零条目——
 * 根因是语句面/prepare 面名单只覆盖 MySQL 与 H2，无 PG 类可 retransform，attach（agentmain）
 * 路径下存量池连接又无法回溯 connect 包装，于是 28 秒内事件从未到达 IDE（与去重窗无关）。
 * 本类把「登记名单覆盖哪些驱动类 + 真实驱动类字节能否被织入且产物合法」钉成回归：名单漂移、
 * 织入器对某驱动形态产出非法字节码，都会在此变红。
 *
 * <p>两层判别：
 * <ol>
 *   <li><b>谓词层</b>（环境无关）：每个登记类必须命中正确的一面谓词、且不在另一面——名单与
 *       {@link DriverRegistry} 分工一致（mysql 5/8、h2、PG、Oracle、SQL Server、达梦 timed 面；
 *       达梦 ADD_BATCH/CLEAR_BATCH 面因 chop_frame 未登记，见 {@link StatementWeaver} 类注释）。</li>
 *   <li><b>字节层</b>（需驱动 jar 在测试 classpath）：直读真实驱动类字节 → {@code weaveSpec}
 *       → 断言 changed → 子优先隔离子 loader 定义 + 初始化（链接期 JVM 栈图校验）。mysql-connector
 *       不在 agent 测试 classpath，只走谓词层。</li>
 * </ol>
 *
 * <p>校验器判别力自证：{@link #非法帧合成类_必被同一校验器拒} 用同一 loader 定义一个人为
 * 撒谎 StackMapTable 的合成类，断言 {@code VerifyError}——防止「loader 压根没触发校验」的
 * 假绿（与 {@link StatementWeaverFramePatchVerifyTest} 定义的校验缝互补）。
 */
public class StatementWeaverVendorCoverageTest {

    /**
     * 语句 execute 面登记名（含 PS/Callable）。凡在 agent 测试 classpath 可解析者一律做字节级
     * 校验；缺 jar 的（mysql-connector）只做谓词断言，字节层用 {@link #BYTE_VERIFIABLE_EXCLUDES} 排除。
     */
    private static final String[] STATEMENT_FACE_CLASSES = {
            // MySQL 8.x / 5.x 遗留（agent 测试 classpath 无 mysql-connector，仅谓词层）
            "com.mysql.cj.jdbc.StatementImpl",
            "com.mysql.cj.jdbc.ClientPreparedStatement",
            "com.mysql.cj.jdbc.ServerPreparedStatement",
            "com.mysql.jdbc.StatementImpl",
            "com.mysql.jdbc.PreparedStatement",
            "com.mysql.jdbc.ServerPreparedStatement",
            // H2 2.x
            "org.h2.jdbc.JdbcStatement",
            "org.h2.jdbc.JdbcPreparedStatement",
            "org.h2.jdbc.JdbcCallableStatement",
            // PostgreSQL / openGauss 兼容（本次主因修复面）
            "org.postgresql.jdbc.PgStatement",
            "org.postgresql.jdbc.PgPreparedStatement",
            "org.postgresql.jdbc.PgCallableStatement",
            // Oracle thin（ojdbc 23.x）
            "oracle.jdbc.driver.OracleStatement",
            "oracle.jdbc.driver.OraclePreparedStatement",
            "oracle.jdbc.driver.OracleCallableStatement",
            // SQL Server（mssql-jdbc 12.x）
            "com.microsoft.sqlserver.jdbc.SQLServerStatement",
            "com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement",
            // 达梦 DM8（DmJdbcDriver18 8.1.3.140）——timed 面（入口捕获改造后过校验）；ADD_BATCH/
            // CLEAR_BATCH 出口非 timed 读原局部会被 chop_frame 判死，未登记（见 StatementWeaver 类注释）
            "dm.jdbc.driver.DmdbStatement",
            "dm.jdbc.driver.DmdbPreparedStatement",
            // 华为 GaussDB 商用驱动（gaussdbjdbc 506.0.0.b058）——独立重定位包，与 pgjdbc 无继承关系
            "com.huawei.gaussdb.jdbc.jdbc.PgStatement",
            "com.huawei.gaussdb.jdbc.jdbc.PgPreparedStatement",
            // 华为 openGauss 品牌驱动（opengaussjdbc 506.0.T35）——与商用版同形，独立包名
            "com.huawei.opengauss.jdbc.jdbc.PgStatement",
            "com.huawei.opengauss.jdbc.jdbc.PgPreparedStatement",
            // 社区 openGauss 2.x/3.x（opengauss-jdbc 3.0.0，包组织与 pgjdbc 分家；5.x/7.x 用
            // org.postgresql.*，已在上面那组）
            "org.opengauss.jdbc.PgStatement",
            "org.opengauss.jdbc.PgPreparedStatement",
    };

    /** prepare 面登记名（prepareStatement/prepareCall 出口登记 PS→SQL）。 */
    private static final String[] PREPARE_FACE_CLASSES = {
            "com.mysql.cj.jdbc.ConnectionImpl",
            "com.mysql.jdbc.ConnectionImpl",
            "org.h2.jdbc.JdbcConnection",
            "org.postgresql.jdbc.PgConnection",
            "oracle.jdbc.driver.PhysicalConnection",
            "com.microsoft.sqlserver.jdbc.SQLServerConnection",
            // 达梦连接（prepare 面；该类字节探针通过校验）
            "dm.jdbc.driver.DmdbConnection",
            // 华为 GaussDB 商用 / openGauss 品牌连接（prepare 面）
            "com.huawei.gaussdb.jdbc.jdbc.PgConnection",
            "com.huawei.opengauss.jdbc.jdbc.PgConnection",
            // 社区 openGauss 2.x/3.x 连接
            "org.opengauss.jdbc.PgConnection",
    };

    /** 字节层排除（agent 测试 classpath 无其 jar）。 */
    private static final String[] BYTE_VERIFIABLE_EXCLUDES = {
            "com.mysql.cj.jdbc.StatementImpl",
            "com.mysql.cj.jdbc.ClientPreparedStatement",
            "com.mysql.cj.jdbc.ServerPreparedStatement",
            "com.mysql.jdbc.StatementImpl",
            "com.mysql.jdbc.PreparedStatement",
            "com.mysql.jdbc.ServerPreparedStatement",
            "com.mysql.cj.jdbc.ConnectionImpl",
            "com.mysql.jdbc.ConnectionImpl",
    };

    // ---------- 谓词层 ----------

    @Test
    public void 语句面登记_命中语句面谓词且不在prepare面() {
        for (String dotted : STATEMENT_FACE_CLASSES) {
            assertTrue(dotted + " 应命中语句面谓词（isStatementWeavable）",
                    DriverRegistry.isStatementWeavable(dotted));
            assertFalse(dotted + " 不得同时命中 prepare 面（两面不相交）",
                    DriverRegistry.isPrepareWeavable(dotted));
        }
    }

    @Test
    public void prepare面登记_命中prepare面谓词且不在语句面() {
        for (String dotted : PREPARE_FACE_CLASSES) {
            assertTrue(dotted + " 应命中 prepare 面谓词（isPrepareWeavable）",
                    DriverRegistry.isPrepareWeavable(dotted));
            assertFalse(dotted + " 不得同时命中语句面（两面不相交）",
                    DriverRegistry.isStatementWeavable(dotted));
        }
    }

    /** 面内类必须落在同一面名单，且 specOf 非空（防止 DriverRegistry 与 SPECS 漂移）。 */
    @Test
    public void 登记名单单一真相_与SPECS一致() {
        for (String dotted : STATEMENT_FACE_CLASSES) {
            assertNotNull(dotted + " 未登记进 StatementWeaver.SPECS", StatementWeaver.specOf(dotted));
        }
        for (String dotted : PREPARE_FACE_CLASSES) {
            assertNotNull(dotted + " 未登记进 StatementWeaver.SPECS", StatementWeaver.specOf(dotted));
        }
    }

    // ---------- 厂商对齐守卫（防「连接面登记了、语句面/prepare 面漏登记」的静默缺口复发） ----------

    /**
     * 已知不覆盖的厂商（连接面已登记，但语句面/prepare 面未登记）——显式认领 + 原因，缺项即红。
     * 新增厂商只有两条路：补齐三面，或在此登记不覆盖原因。
     *
     * <p><b>openGauss 谱系事实（T-GaussCapture 实测，旧表述曾写反）。</b>社区驱动
     * {@code org.opengauss:opengauss-jdbc} 的 2.x/3.x 在包 {@code org.opengauss.*}
     * （3.0.0 内 {@code org/postgresql} 条目数为 0），5.x/7.x 才整体回到 {@code org.postgresql.*}
     * （7.0.0 的 {@code META-INF/services/java.sql.Driver} 声明 {@code org.postgresql.Driver}，
     * {@code org/opengauss} 条目数为 0）。因此 {@code org.opengauss.} **不**列入本表：
     * 3.0.0 的 {@code PgStatement/PgPreparedStatement/PgConnection} 已登记并通过字节层校验，
     * 5.x/7.x 由 {@code org.postgresql.} 覆盖。华为商用 {@code com.huawei.gaussdb.*} 与
     * 华为 openGauss 品牌 {@code com.huawei.opengauss.*} 同为独立重定位实现，已各自登记。
     */
    private static final java.util.Map<String, String> VENDOR_NOT_COVERED =
            new java.util.LinkedHashMap<String, String>();

    static {
        VENDOR_NOT_COVERED.put("org.mariadb.",
                "MariaDB 未纳入测试矩阵（无本机 jar / 未实证语句面形态）；如需支持请补语句面/prepare 面");
        VENDOR_NOT_COVERED.put("org.hsqldb.", "未纳入测试矩阵（无本机 jar）");
        VENDOR_NOT_COVERED.put("org.sqlite.", "未纳入测试矩阵（无本机 jar）");
        VENDOR_NOT_COVERED.put("com.gbasedbt.", "国产库，本机无 jar 未实证");
        VENDOR_NOT_COVERED.put("com.kingbase8.", "国产库，本机无 jar 未实证");
    }

    /**
     * T-DmWeave 防复发守卫：{@link DriverRegistry} 连接面已登记的每个厂商，**要么**在
     * {@link StatementWeaver#SPECS} 有语句面/prepare 面条目，**要么**在 {@link #VENDOR_NOT_COVERED}
     * 显式认领不覆盖原因。动因：达梦 connect 面登记在册、语句面/prepare 面一个类都没有，attach 到
     * 运行中的达梦应用时 matched=0、零事件——这类「看着支持、实际不出」的静默缺口从此由本守卫拦下。
     */
    @Test
    public void 厂商对齐守卫_连接面已登记厂商必须有语句或prepare面条目() {
        for (String driverClass : DriverRegistry.driverClassNames()) {
            final String vendor = vendorKey(driverClass);
            if (VENDOR_NOT_COVERED.containsKey(vendor)) {
                continue; // 显式认领不覆盖
            }
            boolean covered = false;
            for (String specClass : StatementWeaver.specClassNames()) {
                if (specClass.startsWith(vendor)) {
                    covered = true;
                    break;
                }
            }
            assertTrue(driverClass + " 的连接面已登记，但厂商前缀 " + vendor
                    + " 在 StatementWeaver.SPECS 无语句面/prepare 面条目——这是「看着支持、attach 后"
                    + "一条不出」的静默缺口来源。请补齐语句面/prepare 面，或在 VENDOR_NOT_COVERED"
                    + " 显式登记不覆盖原因。", covered);
        }
    }

    /** 驱动类 → 厂商前缀（org.x/com.x 取两段，其余取一段：oracle. / dm. 等）。 */
    private static String vendorKey(String driverClass) {
        final String[] p = driverClass.split("\\.");
        if ("org".equals(p[0]) || "com".equals(p[0])) {
            return p[0] + "." + p[1] + ".";
        }
        return p[0] + ".";
    }

    // ---------- 字节层（真实驱动类字节 → 织入 → JVM 链接期校验） ----------

    @Test
    public void 语句面真实驱动类_织入changed且通过JVM校验() throws Exception {
        for (String dotted : STATEMENT_FACE_CLASSES) {
            if (excludedFromByteLayer(dotted)) {
                continue;
            }
            assertWovenAndVerifiable(dotted);
        }
    }

    @Test
    public void prepare面真实驱动类_织入changed且通过JVM校验() throws Exception {
        for (String dotted : PREPARE_FACE_CLASSES) {
            if (excludedFromByteLayer(dotted)) {
                continue;
            }
            assertWovenAndVerifiable(dotted);
        }
    }

    /** PG 主因面直证：PgConnection 的 prepareStatement/prepareCall 出口钩子落点存在（changed）。 */
    @Test
    public void PG主因面_语句面与prepare面均真实织入() throws Exception {
        assertWovenAndVerifiable("org.postgresql.jdbc.PgStatement");
        assertWovenAndVerifiable("org.postgresql.jdbc.PgPreparedStatement");
        assertWovenAndVerifiable("org.postgresql.jdbc.PgCallableStatement");
        assertWovenAndVerifiable("org.postgresql.jdbc.PgConnection");
    }

    /**
     * 达梦主因面直证（T-DmWeave，用户 P0）：DmdbStatement/DmdbPreparedStatement 的 timed 面
     * （入口捕获 this/首参）+ DmdbConnection 的 prepare 面，在**真实达梦字节**上必须 changed 且过
     * JVM 链接期校验——这是「attach 到达梦应用零事件」修复的字节级安全闸（探针曾实证旧形态
     * VerifyError，此处锁死修复后不再回退）。
     */
    @Test
    public void DM主因面_timed面与prepare面均真实织入() throws Exception {
        assertWovenAndVerifiable("dm.jdbc.driver.DmdbStatement");
        assertWovenAndVerifiable("dm.jdbc.driver.DmdbPreparedStatement");
        assertWovenAndVerifiable("dm.jdbc.driver.DmdbConnection");
    }

    // ---------- 校验器判别力自证（防「loader 未触发校验」假绿） ----------

    @Test
    public void 非法帧合成类_必被同一校验器拒() throws Exception {
        final String dotted = "cn.song.mybatisplus.sqlvis.agent.it.BadFrameStmt";
        final byte[] bad = synthBadFrameClass(dotted.replace('.', '/'));
        try {
            defineAndVerify(dotted, bad);
            fail("撒谎的 StackMapTable 必须被链接期校验拒绝（否则字节层用例无鉴别力）");
        } catch (VerifyError expected) {
            // 期望：StackMapTable 与实际状态不符 → VerifyError
        } catch (ClassFormatError expected) {
            // 结构性非法同样算被拒（不同 JVM 判定阶段差异）
        }
    }

    // ---------- 工具 ----------

    private void assertWovenAndVerifiable(String dotted) throws Exception {
        final StatementWeaver.Kind[] face = StatementWeaver.specOf(dotted);
        assertNotNull(dotted + " 未登记进 SPECS", face);
        final byte[] original = resourceBytes(dotted.replace('.', '/') + ".class");
        final byte[] woven = StatementWeaver.weaveSpec(face, ClassLoader.getSystemClassLoader(), original);
        assertFalse(dotted + " 必须被真实织入（changed）——登记了却零命中即假覆盖",
                Arrays.equals(original, woven));
        assertNotNull(dotted + " 织入产物必须通过 JVM 校验（子优先 loader 定义 + 初始化触发链接期校验）",
                defineAndVerify(dotted, woven));
    }

    /**
     * 隔离子 loader 定义 + 初始化织入产物。两点是实测踩出来的硬性前提：
     * <ul>
     *   <li><b>子优先装载驱动包</b>：驱动类常继承**包私有**父类（PG {@code PgCallableStatement}
     *       extends 包私有 {@code PgPreparedStatement}；Oracle {@code PhysicalConnection} extends
     *       包私有 {@code GeneratedPhysicalConnection}）——若父类由父 loader 装载，跨 loader 的
     *       runtime package 不同即 {@code IllegalAccessError: cannot access its superclass}。故对
     *       被织类所属厂商包前缀走 child-first，父类与被织类落在同一 runtime package。</li>
     *   <li><b>初始化触发校验</b>：{@code resolveClass} 在 JDK17 HotSpot 并不触发栈图校验（负向
     *       用例实证——撒谎的 StackMapTable 不被拒），必须 {@code Class.forName(..., true, loader)}
     *       真正初始化（链接含校验），非法字节码才抛 {@code VerifyError}。</li>
     * </ul>
     */
    private static Class<?> defineAndVerify(String dotted, byte[] woven) throws Exception {
        final VerificationLoader loader = new VerificationLoader(dotted, woven, vendorPrefix(dotted));
        loader.loadClass(dotted); // 定义为织入产物
        return Class.forName(dotted, true, loader); // 初始化 = 链接（校验）+ 初始化
    }

    /** 被织类的厂商包前缀（child-first 范围）：org.x / com.x 取两段，其余取一段。 */
    private static String vendorPrefix(String dotted) {
        final String[] parts = dotted.split("\\.");
        if ("org".equals(parts[0]) || "com".equals(parts[0])) {
            return parts[0] + "." + parts[1] + ".";
        }
        return parts[0] + ".";
    }

    private static boolean excludedFromByteLayer(String dotted) {
        for (String e : BYTE_VERIFIABLE_EXCLUDES) {
            if (e.equals(dotted)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] resourceBytes(String path) throws Exception {
        try (InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(path)) {
            assertNotNull("classpath 缺失驱动类夹具：" + path, in);
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 隔离子 loader：被织类（target）返回织入产物；同一厂商包前缀 child-first（驱动 supertype 与
     * 包私有成员落同一 runtime package）；其余委托父（测试 classpath——{@code java.*} 与 agent 回调
     * 类）。{@code findClass} 从系统 classpath 直读其他驱动类字节。
     */
    private static final class VerificationLoader extends ClassLoader {
        private final String target;
        private final byte[] woven;
        private final String childFirstPrefix;

        VerificationLoader(String target, byte[] woven, String childFirstPrefix) {
            super(StatementWeaverVendorCoverageTest.class.getClassLoader());
            this.target = target;
            this.woven = woven;
            this.childFirstPrefix = childFirstPrefix;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (name.equals(target)) {
                        c = defineClass(name, woven, 0, woven.length);
                    } else if (name.startsWith(childFirstPrefix)) {
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

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            final byte[] b = readResourceQuiet(name.replace('.', '/') + ".class");
            if (b == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, b, 0, b.length);
        }
    }

    private static byte[] readResourceQuiet(String path) {
        try (InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * 合成一个 StackMapTable 撒谎的类：方法入口处实际栈为空，帧却声明栈上有 1 个 int。结构性
     * 合法（帧条目数与声明一致），仅在链接期校验时暴露不一致——用来证明 {@link VerificationLoader}
     * 真的触发了 JVM 校验。
     */
    private static byte[] synthBadFrameClass(String internalName) {
        final ClassWriter cw = new ClassWriter(0); // 显式 maxs/帧，禁重算
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
        // 撒谎：入口栈为空，帧声明栈上有一个 int（F_FULL locals=this，stack=[INTEGER]）
        mv.visitFrame(Opcodes.F_FULL, 1, new Object[]{internalName}, 1, new Object[]{Opcodes.INTEGER});
        mv.visitInsn(Opcodes.RETURN); // 返回 void，与声明的 int 栈不符（双重不一致）
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
