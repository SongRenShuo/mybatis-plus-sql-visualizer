// T-SyncK2JMaster180 测试波次：对齐 master cd1b94562（D239 壳类形态 + 263 三钩子增量），
// 保留 k2j 专属分歧（ShellClassLoader.INSTANCE 直调、TOOL_CLASSES→toolClassesForTest 只读访问器）。
package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.declaredFunctions

/**
 * MCP 工具集动态壳生成器单测（T-McpDynamicToolset）：不依赖平台/EP，用假接口+真实工具类直测
 * [McpToolsetDynamicRegistrar.shellBytes] 的字节码生成与 [McpToolsetDynamicRegistrar.ShellClassLoader]
 * 装载——真机端到端（平台工具发现/注解扫描/suspend 契约）在 262 沙盒 runIde 批次验证。
 */
class McpToolsetShellFactoryTest {

    /** 模拟平台 McpToolset 接口（空标记接口即可，壳 implements 的类型解析只要求可达）。 */
    interface FakeToolset

    /** 模拟工具基类：三钩子与业务方法全在此层。D239 起壳 extends McpToolsetSupport、本类只作委托目标。 */
    open class FakeTool {
        fun isEnabled(): Boolean = true
        fun isExperimental(): Boolean = false
        fun alwaysIncluded(): Boolean = false
        fun business(): String = "ok"
    }

    @Test
    fun `壳类生成_装载_实例化与继承语义`() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.FakeTool" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            FakeTool::class.java,
            FakeToolset::class.java,
            shellName,
        )

        // 类版本 V17（major 61，offset 6-7；魔数 0xCAFEBABE 之后 2 字节 minor + 2 字节 major）
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals("壳类字节码版本应为 major 61（Java 17）", 61, major)

        // k2j 适配：ShellClassLoader 落为静态嵌套单例（define 为实例方法），经 INSTANCE 直调
        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(shellName, bytes)
        // D239：壳类父类改为 McpToolsetSupport（三钩子来源）；工具类只作委托目标，不再被继承
        assertEquals("壳 extends McpToolsetSupport（D239 形态）", McpToolsetSupport::class.java, shell.superclass)
        assertTrue(
            "壳 implements 指定接口",
            shell.interfaces.any { it.name == FakeToolset::class.java.name },
        )
        assertTrue("simpleName 应以业务类名开头（唯一后缀为防重 define），实际 ${shell.simpleName}", shell.simpleName.startsWith("FakeTool"))

        val instance = shell.getDeclaredConstructor(String::class.java).newInstance("fake_tool")
        val tool = instance as McpToolsetSupport
        assertTrue("三钩子继承可调（McpToolsetSupport 提供）", tool.isEnabled())
        assertTrue(!tool.isExperimental())
        assertTrue(!tool.alwaysIncluded())
        // FakeTool 无 @McpTool 方法 → 不生成委托；但业务方法要留占位（D239 四轮：复制的
        // metadata 描述全部成员，缺一个 kotlin-reflect 就抛 Could not compute caller）。
        // FakeTool 只声明了 business()（isEnabled 等三个钩子与基类同签名，被跳过）。
        val declared = shell.declaredMethods.map { it.name }
        assertTrue("无 @McpTool 时不应生成工具委托，实际：$declared", !declared.contains("sqlv_check_sql"))
        assertTrue("业务方法应有占位，实际：$declared", declared.contains("business"))
    }

    // ---- D239：真实工具类（带 @McpTool 方法）作为委托源 --------------------------------

    /**
     * D239：真实工具类（带 `@McpTool` 方法）作为委托源时，壳类必须
     * ①自己声明同名方法 ②复制方法注解 ③复制 class 级 `kotlin.Metadata`。
     *
     * 这是真机「只列前 1~3 个工具」的修复点：旧零成员壳类在 kotlin-reflect 眼里
     * `declared=0 / kotlinMeta=false`，平台扫不到工具方法 → 展开失败 → 遍历中断。
     */
    @Test
    fun `带注解的工具方法生成委托并复制注解与元数据`() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.Probe" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            SqlVisualizerCheckSqlTool::class.java,
            FakeToolset::class.java,
            shellName,
        )
        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(shellName, bytes)
        val declared = shell.declaredMethods.map { it.name }
        assertTrue("壳类应声明 sqlv_check_sql，实际：$declared", declared.contains("sqlv_check_sql"))
        val m = shell.declaredMethods.first { it.name == "sqlv_check_sql" }
        val ann = m.getAnnotation(com.intellij.mcpserver.annotations.McpTool::class.java)
        assertNotNull("委托方法应复制 @McpTool", ann)
        assertEquals("sqlv_check_sql", ann!!.name)
        // D239 三轮：必须复制 Metadata——没有它 kotlin-reflect 走 Java 视图，委托方法的
        // Continuation 会当成普通参数，平台 CallableBridge 直接判不可序列化。
        // k2j 结构性差异（T-SyncK2JMaster180 登记）：k2j 生产树 100% Java，tool 源类是 .java
        //（本就无 kotlin.Metadata），shellBytes 的 Metadata 复制链无源可读、壳亦不含 Metadata；
        // 平台对 Java 壳走 Java 反射面（declaredMethods + 注解扫描），不依赖 Metadata——
        // 本断言随之改约为「源无 Metadata → 壳无 Metadata 且委托/注解面完整」（上方两断言覆盖）。
        assertNull(
            "k2j 源类是 Java（无 Metadata 可复制），壳不应凭空携带 Metadata",
            shell.getAnnotation(Metadata::class.java),
        )
        // 委托目标可达：new 实例 + 调用转发方法（suspend 形态直接传 null Continuation 会 NPE，
        // 故只断言方法存在与注解正确，不做真实调用）
    }

    /**
     * D239 三轮：壳类工具方法必须「平台可序列化」——真机失败条件是 Continuation 被当成普通参数。
     *
     * master 形态：源 .kt 类有 kotlin.Metadata，壳复制后 kotlin-reflect 认出 suspend。
     * **k2j 结构性差异（T-SyncK2JMaster180 登记）**：k2j 生产树 100% Java——源 tool 类本就是
     * Java 方法（无 suspend/Continuation 形态），壳是 Java 字节码、平台走 Java 反射面，
     * 序列化安全的等价断言 = 壳方法参数表**不含 kotlin.coroutines.Continuation**。
     */
    @Test
    fun `壳类工具方法在 kotlin-reflect 里保留 suspend 语义`() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.ProbeKt" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            SqlVisualizerCheckSqlTool::class.java,
            FakeToolset::class.java,
            shellName,
        )
        val shell = McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(shellName, bytes)
        val m = shell.declaredMethods.firstOrNull { it.name == "sqlv_check_sql" }
        assertNotNull("壳类应声明工具方法", m)
        assertTrue(
            "壳方法参数表不得含 Continuation（平台序列化失败的等价条件），实际：${m!!.parameterTypes.map { it.name }}",
            m.parameterTypes.none { kotlin.coroutines.Continuation::class.java.isAssignableFrom(it) },
        )
    }

    /**
     * D239 三轮：**全部壳类**的工具方法都必须「平台可序列化」。
     *
     * 真机 2026-11-19 17:47 实测：只有第一个 toolset 出工具，其余 10 个仍报
     * `Continuation ... of parameter 'argN' is not serializable`。
     *
     * **k2j 结构性差异（T-SyncK2JMaster180 登记）**：k2j 生产树 100% Java——源 tool 类是 Java
     * 方法（无 suspend 形态），壳是 Java 字节码。序列化安全的等价断言 = 逐壳的 @McpTool
     * 委托方法**参数表不含 kotlin.coroutines.Continuation**（master 的 isSuspend 断言的 Java 形态）。
     *
     * k2j 适配：TOOL_CLASSES 为 private——走 toolClassesForTest() 只读访问器（master internal 语义）。
     */
    @Test
    fun `全部壳类的工具方法都保留 suspend 语义`() {
        val failures = mutableListOf<String>()
        McpToolsetDynamicRegistrar.toolClassesForTest().forEachIndexed { index, toolClass ->
            val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.ProbeAll${index}_" + System.nanoTime()
            val bytes = McpToolsetDynamicRegistrar.shellBytes(toolClass, FakeToolset::class.java, shellName)
            val shell = McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(shellName, bytes)
            val toolMethods = shell.declaredMethods.filter {
                it.getAnnotation(com.intellij.mcpserver.annotations.McpTool::class.java) != null
            }
            if (toolMethods.isEmpty()) {
                failures.add("${toolClass.simpleName}: 壳类没有带 @McpTool 的委托方法")
            } else {
                toolMethods.forEach { m ->
                    if (m.parameterTypes.any { kotlin.coroutines.Continuation::class.java.isAssignableFrom(it) }) {
                        failures.add(
                            "${toolClass.simpleName}: ${m.name} 参数表含 Continuation，" +
                                "参数=${m.parameterTypes.map { it.name }}",
                        )
                    }
                }
            }
        }
        assertTrue("以下壳类的工具方法序列化安全断言失败：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * D239 四轮：壳类必须覆盖业务类的**全部**方法签名。
     *
     * 复制的 metadata 描述了业务类全部成员，kotlin-reflect 展开函数表时逐个按签名找 Java 方法，
     * 缺一个就抛 `KotlinReflectionInternalError: Could not compute caller for function`，
     * 整个 toolset 归零（真机 2026-11-19 18:01 实证）。本用例按签名对账。
     */
    @Test
    fun `壳类覆盖业务类全部方法签名`() {
        val problems = mutableListOf<String>()
        McpToolsetDynamicRegistrar.toolClassesForTest().forEachIndexed { index, toolClass ->
            val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.ProbeSig${index}_" + System.nanoTime()
            val bytes = McpToolsetDynamicRegistrar.shellBytes(toolClass, FakeToolset::class.java, shellName)
            val shell = McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(shellName, bytes)
            val shellSigs = shell.declaredMethods.mapTo(HashSet()) { signatureOf(it) }
            toolClass.declaredMethods
                .filterNot { it.isSynthetic }
                .filterNot { signatureOf(it) in shellSigs }
                .forEach { problems.add("${toolClass.simpleName}: 缺 ${signatureOf(it)}") }
        }
        assertTrue("壳类缺少业务类方法签名：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun signatureOf(m: java.lang.reflect.Method): String =
        m.name + "(" + m.parameterTypes.joinToString(",") { it.name } + ")"

    /** F29 反向验证探针：同 FQN 二次 define 必 LinkageError（ShellClassLoader 单例、define 不可逆）——
     *  台账（definedShells/epRegistered）防的就是这个：无台账的部分失败重试必撞此形态。 */
    @Test
    fun testF29_同FQN二次define必LinkageError() {
        val shellName = "cn.song.mybatisplus.visualizer.mcp.gen.FakeToolF29" + System.nanoTime()
        val bytes = McpToolsetDynamicRegistrar.shellBytes(
            FakeTool::class.java,
            FakeToolset::class.java,
            shellName,
        )
        McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(shellName, bytes)
        try {
            McpToolsetDynamicRegistrar.ShellClassLoader.INSTANCE.define(shellName, bytes)
            fail("同 FQN 二次 define 应抛 LinkageError——该形态即 F29 台账要防的重试炸点")
        } catch (expected: LinkageError) {
            // 预期
        }
    }

    /** F29 台账语义：registerToolset 经 define 缓存复用——同 FQN 两次调用不炸、Class 实例同一（无重 define）。 */
    @Test
    fun testF29_台账复用_二次调用不重define() {
        val fqn = "cn.song.mybatisplus.visualizer.mcp.gen.FakeToolLedger" + System.nanoTime()
        val iface = FakeToolset::class.java
        // 平台 EP 不存在时注册步会失败（runCatching 于 registerOnce 兜底）——本测试直接驱动 registerToolset
        // 的 define/台账面：两次调用第二次必须走缓存（assertSame 钉死无重 define，LinkageError 即红）。
        val first = runCatching { McpToolsetDynamicRegistrar.registerToolsetForTest(FakeTool::class.java, iface, fqn) }
        val cls1 = McpToolsetDynamicRegistrar.definedShells[fqn]
        assertTrue("首次调用应完成 define 入台账", cls1 != null)
        runCatching { McpToolsetDynamicRegistrar.registerToolsetForTest(FakeTool::class.java, iface, fqn) }
        val cls2 = McpToolsetDynamicRegistrar.definedShells[fqn]
        assertSame("二次调用必须复用缓存实例（重 define = LinkageError = F29 形态）", cls1, cls2)
    }

    // ---- T-McpGlobalBreakHardening（20261119）：263 新增三钩子必须由基类补齐 --------------

    /**
     * 263（IU-263.7307.200）给 `McpToolset` 加了 `isUserConfigurable` / `displayName` /
     * `displayDescription`（三个都有接口默认体，动态壳继承即可满足）。动态壳 implements 平台接口、
     * 钩子全部继承 [McpToolsetSupport]——基类不补齐时，263 真机 tools/list 里 11 个 sqlv 工具只剩
     * 注册清单第一个（20261119 实测：92 个工具仅 sqlv_render_sql 在列）。本用例钉住「钩子在基类、
     * 语义正确」，防下次接口增长再静默丢工具。
     */
    @Test
    fun `263新增三钩子在基类补齐且displayName按类名分组`() {
        val base = McpToolsetSupport::class.java
        listOf("isUserConfigurable", "displayName", "displayDescription").forEach { hook ->
            assertTrue("263 新钩子必须在 McpToolsetSupport 补齐（缺失 = 263 上工具静默消失）: $hook",
                base.methods.any { it.name == hook })
        }
        val sample = object : McpToolsetSupport("sqlv_render_sql") {}
        assertTrue("isUserConfigurable 必须 true（false 的 toolset 不进外部会话清单）", sample.isUserConfigurable())
        assertEquals(
            "displayName 按运行时类 simpleName 分组（T-McpToolNaming 与 262 category shortName 同源）",
            sample.javaClass.simpleName,
            sample.displayName(),
        )
        assertEquals("工具集描述无独立文案，给空串", "", sample.displayDescription())
        assertEquals("带兜底的重载必须透传平台 fallback", "fb", sample.displayDescription("fb"))
    }
}
