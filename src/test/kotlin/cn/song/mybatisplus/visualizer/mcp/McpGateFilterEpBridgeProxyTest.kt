package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * T-McpGateDynamicFilter（O-68）→ T-MarketCompatProxy → **T-Mcp261FilterContract** 过滤链回归测试。
 *
 * 真机实证（2026-09-23 IU 262.10968，idea.log 两次 SEVERE）：平台「公开的工具」设置页在无
 * 客户端会话时以 **clientInfo=null** 调 applyFilters——入口判空即 NPE，设置页整面空白。
 *
 * 2026-10-29 用户报障（IU-261.25134.95，内置 MCP Server 所有会话 HTTP 500）根因＝261 的契约是
 * `getFilters`/`modify`/`apply`，代理按 262 的 `applyFilters` 分发 → `getFilters` 落 `else`
 * 返回 null → 平台 `CollectionsKt.addAll(collection, null)` 抛 NPE（该链不在 try/catch 内）。
 * 故本用例集钉住两条结构性不变量：
 *  1. **任何未知方法都不返回 null**（集合/Map/Flow/原始类型给空值）——一次 null 就重现事故；
 *  2. **261 形态 B 的过滤器链可跑通**：`modify` 非 null、`apply` 把自家被禁工具从 allowed 摘除
 *     并加进 disallowed、外来工具零触碰、反射失败时原样返回上下文（绝不抛出）。
 *
 * 测试使用本地同签名假接口/假上下文，不静态链接仅在新平台运行时提供的 MCP 接口。
 * k2j 互操作注：桥为 Java 静态承载（providerHandler 静态、invoke 收 null receiver）。
 */
class McpGateFilterEpBridgeProxyTest : BasePlatformTestCase() {

    private val state get() = PluginSettings.getInstance().state

    override fun tearDown() {
        // 应用级服务跨用例存活，恢复默认避免污染同 suite
        state.mcpToolsEnabled = true
        state.mcpDisabledTools.clear()
        super.tearDown()
    }

    /** 反射取 Java 桥的静态 providerHandler() 拿分发体（防回归需要直达 InvocationHandler）。 */
    private fun bridgeHandler(): java.lang.reflect.InvocationHandler {
        val m = McpGateFilterEpBridge::class.java.getDeclaredMethod("providerHandler")
        m.isAccessible = true
        return m.invoke(null) as java.lang.reflect.InvocationHandler
    }

    private fun dispatch(fake: Class<*>, methodName: String, args: Array<Any?>): Any? {
        val method = fake.methods.first { it.name == methodName }
        return bridgeHandler().invoke(Any(), method, args)
    }

    fun test空clientInfo与会话选项的分发路径不得抛空源性NPE() {
        val context = FakeFilterContext()
        try {
            bridgeHandler().invoke(Any(), FakeFilter::class.java.methods.first { it.name == "applyFilters" },
                arrayOf(context, null, null, null))
        } catch (e: NullPointerException) {
            if (e.message?.contains("non-null") == true) {
                fail("分发路径必须容忍 null 环境参数（平台无会话枚举路径传 null，真机 NPE 实证）")
            }
            throw e
        }
        assertTrue("分发必须调用 context.updateState", context.called)
        assertFalse("过滤状态应关闭默认路由", context.enabled ?: true)
    }

    // ---- 券01：未知方法绝不返回 null（结构性安全网） ------------------------------------

    fun test261形态getFilters分发不得返回null() {
        // 平台 261 把 getFilters 的返回值直接 addAll —— null 即会话创建 NPE（2026-10-29 事故）
        val result = dispatch(FakeFormBProvider::class.java, "getFilters", arrayOf(null, null))
        assertNotNull("getFilters 分发必须返回非 null 清单（空清单=门控不生效，可接受）", result)
        assertTrue("返回值必须是 List", result is List<*>)
    }

    fun testgetUpdates分发不得返回null() {
        val result = dispatch(FakeFormBProvider::class.java, "getUpdates", arrayOf(null, null, null))
        assertNotNull("getUpdates 必须返回非 null Flow", result)
        assertTrue("返回值必须是 Flow", result is Flow<*>)
    }

    fun test原始类型返回不得为null() {
        // Proxy 对原始返回类型返回 null 会直接抛 NPE（不是「返回 null」而是崩在代理层）
        val result = dispatch(FakePrimitiveProvider::class.java, "flag", arrayOf())
        assertEquals(false, result as Boolean?)
    }

    fun test集合与Map返回兜底为空值() {
        assertEquals(emptyList<Any>(), McpGateFilterEpBridge.defaultValueFor(List::class.java))
        assertEquals(emptySet<Any>(), McpGateFilterEpBridge.defaultValueFor(Set::class.java))
        assertEquals(emptyMap<Any, Any>(), McpGateFilterEpBridge.defaultValueFor(Map::class.java))
        assertNotNull(McpGateFilterEpBridge.defaultValueFor(Flow::class.java))
        assertTrue((McpGateFilterEpBridge.defaultValueFor(Int::class.javaPrimitiveType!!) as Number).toInt() == 0)
        assertTrue((McpGateFilterEpBridge.defaultValueFor(Long::class.javaPrimitiveType!!) as Number).toLong() == 0L)
    }

    // ---- 券03：261 形态 B 的过滤器链 ----------------------------------------------------

    fun test261过滤器modify不得返回null且apply摘除隐藏工具() {
        state.mcpToolsEnabled = false // 总开关关＝全部自家工具隐藏
        val ctx = FakeContext261(
            disallowedTools = emptySet(),
            allowedTools = setOf(FakeTool261("sqlv_render_sql"), FakeTool261("search_text")),
        )
        val filter = McpGateFilterEpBridge.buildFilter261(
            FakeToolFilter261::class.java,
            FakeModification261::class.java,
        )
        val modification = FakeToolFilter261::class.java
            .getMethod("modify", FakeContext261::class.java)
            .invoke(filter, ctx)
        assertNotNull("modify 必须返回非 null——平台紧接 .apply(context)，无判空", modification)
        val result = FakeModification261::class.java
            .getMethod("apply", FakeContext261::class.java)
            .invoke(modification, ctx) as FakeContext261
        assertEquals("自家被禁工具必须从 allowed 摘除", setOf("search_text"), result.allowedNames())
        assertEquals("自家被禁工具必须进 disallowed（与平台 DisallowMcpTools 同语义）",
            setOf("sqlv_render_sql"), result.disallowedNames())
    }

    fun test261过滤器对未命中工具零触碰() {
        // 门控全开：无隐藏项 → 原样返回同一上下文（不制造新对象、不改集合）
        val ctx = FakeContext261(emptySet(), setOf(FakeTool261("sqlv_render_sql"), FakeTool261("git_status")))
        assertSame(ctx, McpGateFilterEpBridge.filterContext261(ctx))
    }

    fun test261过滤器反射失败时原样返回上下文() {
        // 契约漂移/反射异常绝不能让平台链崩（261 的过滤链不在 try/catch 内）
        val ctx = ThrowingContext261()
        assertSame(ctx, McpGateFilterEpBridge.filterContext261(ctx))
    }

    fun test261过滤器空上下文返回null且不抛() {
        assertNull(McpGateFilterEpBridge.filterContext261(null))
    }

    // ---- 测试替身 ----------------------------------------------------------------------

    interface FakeFilter {
        fun applyFilters(context: FakeFilterContext?, clientInfo: Any?, invocationMode: Any?, options: Any?)
    }

    class FakeFilterContext {
        var called = false
        var enabled: Boolean? = null

        fun updateState(enabled: Boolean?, routerOnly: Boolean?, predicate: kotlin.jvm.functions.Function1<Any?, Boolean>) {
            called = true
            this.enabled = enabled
            check(routerOnly == null)
            check(!predicate(null))
        }
    }

    /** 261.25134 形态 B 的接口形状（双参 getFilters 返回 List）。 */
    interface FakeFormBProvider {
        fun getFilters(clientInfo: Any?, options: Any?): List<Any?>
        fun getUpdates(clientInfo: Any?, scope: Any?, options: Any?): Flow<Any>
    }

    interface FakePrimitiveProvider {
        fun flag(): Boolean
    }

    interface FakeToolFilter261 {
        fun modify(context: FakeContext261?): FakeModification261
    }

    interface FakeModification261 {
        fun apply(context: FakeContext261?): FakeContext261
    }

    class FakeTool261(val name: String) {
        fun getDescriptor(): FakeDescriptor261 = FakeDescriptor261(name)
    }

    class FakeDescriptor261(val name: String)

    class FakeContext261(
        private val disallowedTools: Set<Any?>,
        private val allowedTools: Set<Any?>,
    ) {
        fun getDisallowedTools(): Set<Any?> = disallowedTools
        fun getAllowedTools(): Set<Any?> = allowedTools
        fun copy(disallowedTools: Set<Any?>, allowedTools: Set<Any?>): FakeContext261 =
            FakeContext261(disallowedTools, allowedTools)

        fun allowedNames(): Set<String> = allowedTools.mapNotNull { (it as? FakeTool261)?.name }.toSet()
        fun disallowedNames(): Set<String> = disallowedTools.mapNotNull { (it as? FakeTool261)?.name }.toSet()
    }

    /** 读取 allowed 即抛——验证反射失败降级为「原样返回」。 */
    class ThrowingContext261 {
        fun getDisallowedTools(): Set<Any?> = emptySet()
        fun getAllowedTools(): Set<Any?> = throw IllegalStateException("boom")
        fun copy(disallowedTools: Set<Any?>, allowedTools: Set<Any?>): ThrowingContext261 = this
    }
}
