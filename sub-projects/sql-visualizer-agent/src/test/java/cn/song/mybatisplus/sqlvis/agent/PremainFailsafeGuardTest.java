package cn.song.mybatisplus.sqlvis.agent;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-JRebelPremainCrash 守卫：**premain 路径任何失败都不得外抛**（否则目标 JVM 直接启动失败）。
 *
 * <p>用户现场（2026-10-22，Corretto 21 + JRebel）：JRebel 接管 premain 的类加载后重定位 ASM 包
 * 不可见，{@code DriverRegistry.isStatementWeavable} 触发 {@code StatementWeaver} 类初始化时抛
 * {@code NoClassDefFoundError: cn/song/mybatisplus/sqlvis/agent/asm/Opcodes}；该调用彼时位于
 * {@code retransformLoadedDrivers} 的 try 之外，异常穿出 {@code premain}，JVM 判定
 * {@code -javaagent} 失败并中止启动（{@code processJavaStart failed}）。
 *
 * <p>本类以**源码结构守卫**钉住修复形态（运行期行为验证由 demo 仓
 * {@code agent-premain-hostile-loader-repro} 的 {@code reproC} 承担——那里有真实 jar + 敌意加载器）：
 * <ol>
 *   <li>{@code install} 的失败兜底必须是 {@code catch (Throwable)}——{@code ClassCircularityError}
 *       与 {@code NoClassDefFoundError} 只有 {@code Throwable} 这一共同父类（B3 格实测形态），
 *       只捕 {@code LinkageError} 会漏；</li>
 *   <li>降级路径**不得**触碰织入器/注册表类（类初始化已失败，再触达可能二次抛错）；</li>
 *   <li>降级时**不得**写 {@code sqlvis.agent.active}（写了会让 IDE 侧 attach 误判「已装」而永不重试）；</li>
 *   <li>{@code retransformLoadedDrivers} 的三连谓词必须在其自己的 try 内（谓词本身会触发类初始化）。</li>
 * </ol>
 */
public class PremainFailsafeGuardTest {

    /** 被测源码（相对 agent 子项目根；测试工作目录即该子项目）。 */
    private static final File MAIN_SRC = new File(
            "src/main/java/cn/song/mybatisplus/sqlvis/agent/AgentMain.java");

    private static String readMain() throws Exception {
        assertTrue("找不到 AgentMain 源码：" + MAIN_SRC.getAbsolutePath(), MAIN_SRC.isFile());
        try (InputStream in = new FileInputStream(MAIN_SRC)) {
            final byte[] buf = new byte[(int) MAIN_SRC.length()];
            int off = 0;
            while (off < buf.length) {
                final int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        }
    }

    /** 取方法体（按首个 `{` 起的括号配平），失败返回 null。 */
    private static String bodyOf(String src, String signature) {
        final int sig = src.indexOf(signature);
        if (sig < 0) return null;
        final int open = src.indexOf('{', sig);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, i + 1);
            }
        }
        return null;
    }

    /** ① install 的兜底必须是 catch (Throwable)，且不得收窄成 LinkageError/NoClassDefFoundError。 */
    @Test
    public void install兜底必须捕获Throwable() throws Exception {
        final String install = bodyOf(readMain(), "private static synchronized void install(String agentArgs");
        assertNotNull("未找到 install(String, Instrumentation) 方法体——守卫失去锚点，需同步更新本测试", install);
        assertTrue("install 必须包 catch (Throwable)：ClassCircularityError 与 NCDFE 只有 Throwable 这一个共同父类",
                install.contains("catch (Throwable"));
        assertFalse("install 不得只捕 LinkageError（会漏掉 ClassCircularityError 形态）",
                install.contains("catch (LinkageError"));
        assertFalse("install 不得只捕 NoClassDefFoundError",
                install.contains("catch (NoClassDefFoundError"));
    }

    /** ② 降级路径不得再触达织入器/注册表类（类初始化已失败，二次触达可能二次抛错）。 */
    @Test
    public void 降级路径不得触碰织入器或注册表() throws Exception {
        final String degrade = bodyOf(readMain(), "private static void degrade(Throwable t)");
        assertNotNull("未找到 degrade(Throwable) 方法体——守卫失去锚点", degrade);
        for (String banned : new String[]{
                "DriverRegistry", "StatementWeaver", "ConnectWeaver",
                "SqlHookTransformer", "WeaveSpecs", "EventBus"}) {
            assertFalse("降级路径不得引用 " + banned + "（该类的初始化可能就是失败原因本身）",
                    degrade.contains(banned));
        }
    }

    /** ③ 降级时不得写 active 标记——否则 IDE 侧 attach 会误判「已装」而永不重试。 */
    @Test
    public void 降级路径不得写active标记() throws Exception {
        final String degrade = bodyOf(readMain(), "private static void degrade(Throwable t)");
        assertNotNull("未找到 degrade(Throwable) 方法体——守卫失去锚点", degrade);
        assertFalse("降级路径不得写 ACTIVE_MARKER（会让 attach 永久不重试）",
                degrade.contains("ACTIVE_MARKER"));
        assertFalse("降级路径不得调用 System.setProperty",
                degrade.contains("setProperty"));
        assertFalse("降级路径不得把 installed 置 true",
                degrade.contains("installed = true"));
    }

    /** ④ 三连谓词必须在自己的 try 内（谓词会触发 StatementWeaver 类初始化）。 */
    @Test
    public void 三连谓词必须在单类try内() throws Exception {
        final String loop = bodyOf(readMain(), "private static void retransformLoadedDrivers(Instrumentation inst)");
        assertNotNull("未找到 retransformLoadedDrivers 方法体——守卫失去锚点", loop);

        final int isWeavable = loop.indexOf("DriverRegistry.isWeavable(name)");
        assertTrue("未找到 DriverRegistry.isWeavable(name) 调用点（方法体可能已重构，需同步本测试）",
                isWeavable > 0);
        // 谓词之前最近的 try 必须存在且未被闭合：用「谓词前最后一个 try 的位置 > 最后一个 catch/} 收口」近似判定
        final int tryBefore = loop.lastIndexOf("try {", isWeavable);
        assertTrue("三连谓词前必须有 try（谓词自身会触发织入器类初始化并可能抛 NCDFE/ClassCircularityError）",
                tryBefore > 0);
        final int catchBefore = loop.lastIndexOf("catch (Throwable t)", isWeavable);
        assertFalse("三连谓词不得位于任何 catch 块之后（说明它仍在 try 之外）",
                catchBefore > tryBefore && catchBefore < isWeavable);
    }

    /** ⑤ 静态块预加载失败必须留痕（此前 catch(Throwable ignore) 把第一现场彻底抹掉）。 */
    @Test
    public void 静态块预加载失败必须留痕() throws Exception {
        final String src = readMain();
        final int clinit = src.indexOf("static {");
        assertTrue("未找到静态初始化块", clinit > 0);
        final int end = src.indexOf("public static void premain", clinit);
        final String block = src.substring(clinit, end);
        assertFalse("静态块不得再以 catch (Throwable ignore) {} 静默吞掉预加载失败——"
                        + "那是「ASM 不可见」的第一现场证据",
                block.contains("catch (Throwable ignore)"));
        assertTrue("静态块 catch 内必须有 stderr 留痕",
                block.contains("System.err.println"));
    }

    /** ⑥ 谓词不得再经 StatementWeaver 触发 ASM 需求（B 案：名单上提后 DriverRegistry 直连 WeaveSpecs）。 */
    @Test
    public void 名单查询不得触发StatementWeaver类初始化() throws Exception {
        final File registry = new File(
                "src/main/java/cn/song/mybatisplus/sqlvis/agent/DriverRegistry.java");
        assertTrue("找不到 DriverRegistry 源码", registry.isFile());
        final String src = new String(java.nio.file.Files.readAllBytes(registry.toPath()), "UTF-8");

        final int stmt = src.indexOf("public static boolean isStatementWeavable");
        final int prep = src.indexOf("public static boolean isPrepareWeavable");
        assertTrue("未找到两个面谓词（签名可能已改，需同步本测试）", stmt > 0 && prep > stmt);

        final List<String> offenders = new ArrayList<String>();
        for (int i = stmt; i < prep; i++) {
            // 允许出现 StatementWeaver.Kind 这一**嵌套枚举**类型名（引用嵌套枚举不触发外层 <clinit>），
            // 但不得调用 StatementWeaver.specOf —— 那才是触发外层类初始化的入口
            if (src.startsWith("StatementWeaver.specOf", i)) offenders.add("StatementWeaver.specOf");
        }
        assertTrue("名单谓词不得调用 StatementWeaver.specOf（会触发 implements Opcodes 的类初始化，"
                + "第三方 agent 干扰 ASM 可见性时直接抛）——应委托零 ASM 依赖的 WeaveSpecs；实际命中 "
                + offenders, offenders.isEmpty());
        assertTrue("名单谓词应委托 WeaveSpecs.specOf",
                src.substring(stmt, prep).contains("WeaveSpecs.specOf"));
    }
}
