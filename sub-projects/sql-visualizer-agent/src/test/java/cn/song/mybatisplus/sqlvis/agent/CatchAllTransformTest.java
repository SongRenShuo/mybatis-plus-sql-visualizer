package cn.song.mybatisplus.sqlvis.agent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-CaptureCatchAll 券 06 守卫：transformer 两段式兜底分派（**默认关**，计划 D2=A）。
 *
 * <p>成本红线（调研 §2.2）：裸字节子串预筛 2–5µs/类、类头解析 8–11µs/类，只在第一段命中后才做
 * 第二段；禁止对全部类直接全树解析（200–515µs/类，2 万类应用多花约 0.6 秒）。
 *
 * <p>本类钉四条：
 * <ol>
 *   <li><b>默认关</b>：不置开关时，非白名单类即便直接 implements java.sql.* 也零处理（返回 null，
 *       计数不动）——红线优先，未经真机验证不默认打开；</li>
 *   <li><b>开则生效</b>：开关打开后同一字节被真织入（changed），matched/woven 计数各 +1；</li>
 *   <li><b>两段式预筛</b>：无 {@code java/sql/} 子串的类不进入第二段（计数不动）；</li>
 *   <li><b>失败不外溢</b>：预筛命中但字节非法时返回 null、不触发 kill switch（纯解析失败不是
 *       「兜底形态系统性不可用」的证据）。</li>
 * </ol>
 */
public class CatchAllTransformTest {

    /** 直接 implements java.sql.PreparedStatement 的实体夹具（非白名单）。 */
    private static final String CANDIDATE =
            "cn.song.mybatisplus.sqlvis.agent.FakeVendorFixtures$VendorPs";

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
    public void 默认关_非白名单类零处理() throws Exception {
        final byte[] bytes = resourceBytes(CANDIDATE);
        assertTrue("夹具必须含 java/sql/ 常量（否则预筛用例无鉴别力）",
                SqlHookTransformer.containsJavaSql(bytes));

        final byte[] out = transformer.transform(getClass().getClassLoader(),
                CANDIDATE.replace('.', '/'), null, null, bytes);

        assertNull("默认关时兜底分派不得产出任何字节（红线优先）", out);
        assertEquals(0L, CaptureBudget.transformMatchedCount());
        assertEquals(0L, CaptureBudget.transformWovenCount());
        assertEquals(0L, SqlHookTransformer.weavableCount());
    }

    @Test
    public void 开则生效_两段式确认后真织入() throws Exception {
        CaptureBudget.setTransformMatchEnabledForTest(true);
        final byte[] bytes = resourceBytes(CANDIDATE);

        final byte[] out = transformer.transform(getClass().getClassLoader(),
                CANDIDATE.replace('.', '/'), null, null, bytes);

        assertFalse("兜底分派确认命中后必须真织入（changed）", java.util.Arrays.equals(bytes, out));
        assertEquals(1L, CaptureBudget.transformMatchedCount());
        assertEquals(1L, CaptureBudget.transformWovenCount());
        assertEquals("兜底织入同样计入 transformer 的 weavable/woven 读数",
                1L, SqlHookTransformer.weavableCount());
        assertEquals(1L, SqlHookTransformer.wovenCount());
    }

    @Test
    public void 预筛_无javaSql子串的类不进第二段() throws Exception {
        CaptureBudget.setTransformMatchEnabledForTest(true);
        final byte[] bytes = resourceBytes("cn.song.mybatisplus.sqlvis.agent.FakeVendorFixtures$BoomException");
        assertFalse("异常夹具不含 java/sql/ 常量", SqlHookTransformer.containsJavaSql(bytes));

        assertNull(transformer.transform(getClass().getClassLoader(),
                "cn/song/mybatisplus/sqlvis/agent/FakeVendorFixtures$BoomException", null, null, bytes));
        assertEquals("预筛未命中不得进入类头解析", 0L, CaptureBudget.transformMatchedCount());
    }

    @Test
    public void 排除包_JDK自身类不进入兜底() throws Exception {
        CaptureBudget.setTransformMatchEnabledForTest(true);
        final byte[] bytes = resourceBytes(CANDIDATE);
        assertNull("java.* 前缀排除（JDK 自身实现无织入意义）",
                transformer.transform(getClass().getClassLoader(), "java/sql/Fake", null, null, bytes));
        assertEquals(0L, CaptureBudget.transformMatchedCount());
    }

    @Test
    public void 非法字节_返回null且不触发熔断() {
        CaptureBudget.setTransformMatchEnabledForTest(true);
        // magic + major 52 + "java/sql/" 子串，但类结构非法（常量池缺失）
        final byte[] broken = new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52,
                'j', 'a', 'v', 'a', '/', 's', 'q', 'l', '/'};

        assertNull("解析失败必须返回 null（原字节装载），异常不外溢",
                transformer.transform(getClass().getClassLoader(), "x/y/Broken", null, null, broken));
        assertEquals("预筛命中但类头解析失败不算「已确认匹配」的失败", 0L, CaptureBudget.transformMatchedCount());
        assertFalse("解析失败不得计入连败（否则无关类的畸形字节会误熔断）", CaptureBudget.killed());
        assertEquals(0L, SqlHookTransformer.failedCount());
    }

    @Test
    public void 熔断后_兜底分派整体关闭() throws Exception {
        CaptureBudget.setTransformMatchEnabledForTest(true);
        CaptureBudget.setKillAfterForTest(1);
        CaptureBudget.noteCatchAllFailure("fixture=VerifyError");
        assertTrue(CaptureBudget.killed());

        final byte[] bytes = resourceBytes(CANDIDATE);
        assertNull("kill 后兜底分派不得再产出字节",
                transformer.transform(getClass().getClassLoader(),
                        CANDIDATE.replace('.', '/'), null, null, bytes));
        assertEquals(0L, CaptureBudget.transformMatchedCount());
    }

    private static byte[] resourceBytes(String dotted) throws Exception {
        final String path = dotted.replace('.', '/') + ".class";
        try (java.io.InputStream in = CatchAllTransformTest.class.getClassLoader().getResourceAsStream(path)) {
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
