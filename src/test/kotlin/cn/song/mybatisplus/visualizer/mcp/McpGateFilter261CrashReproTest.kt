package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * 2026-10-29 报障的**语句级复现**（不依赖 261 IDE）。
 *
 * 用户 `idea.log` 原文：
 * ```
 * java.lang.NullPointerException: Parameter specified as non-null is null:
 *   method kotlin.collections.CollectionsKt__MutableCollectionsKt.addAll, parameter elements
 *     at com.intellij.mcpserver.impl.McpServerService.getMcpToolsFiltered$intellij_mcpserver(McpServerService.kt:455)
 * ```
 * 该行对应 261 字节码 `527: invokeinterface McpToolFilterProvider.getFilters` →
 * `541: invokestatic CollectionsKt.addAll(Collection, Iterable)`——平台把代理的返回值**直接**
 * 当 `Iterable` 传入，没有任何判空，且该调用不在 try/catch 内（`Exception table` 只覆盖
 * `getTools()`）。
 *
 * 本用例把这两步原样搬进测试：
 *  1. `legacyProvider()` ＝ 2026.10.28 发布形态的代理（只认 `applyFilters`，其余返回 null）；
 *  2. `platformCollect()` ＝ 平台那句 `collected.addAll(provider.getFilters(...))`，经
 *     `CollectionsKt__MutableCollectionsKt.addAll` 真身调用（Kotlin stdlib 的判空就在那里）。
 *
 * 断言两件事：
 *  - 旧形态必须**逐字复现**用户日志里的那句 NPE（证明根因判定没错，不是「推断」）；
 *  - 现行桥的同一句调用必须不抛、返回空清单（证明修复在语句层面成立）。
 *
 * 边界：这里复现的是**失败语句**，不是整条 IDE 链路——真机「`/stream` 从 500 变 200」需要
 * 261 发行版，本机没有（见计划 §十.5 未闭环）。
 *
 * k2j 互操作注：providerHandler 为 Java 静态方法（invoke 收 null receiver，对齐
 * McpGateFilterEpBridgeProxyTest 先例）。
 */
class McpGateFilter261CrashReproTest {

    /** 261 形态 B 的入口签名（`getFilters(Implementation, McpSessionOptions): List`）。 */
    interface Provider261 {
        fun getFilters(clientInfo: Any?, options: Any?): List<Any?>
    }

    /** 2026.10.28 发布形态：分发只认 `applyFilters`，其余（含 261 的 `getFilters`）返回 null。 */
    private fun legacyProvider(): Any = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Provider261::class.java),
    ) { _, _, _ -> null }

    /** 平台侧语句，与 261 字节码 527→541 等价：`collected.addAll(provider.getFilters(...))`。 */
    private fun platformCollect(provider: Any): List<Any?> {
        val collected = mutableListOf<Any?>()
        val filters = Provider261::class.java
            .getMethod("getFilters", Any::class.java, Any::class.java)
            .invoke(provider, null, null)
        // 走 Kotlin stdlib 的 addAll 真身（facade 继承自 package-private 的 file-class，
        // 判空与用户日志里那句 NPE 同一处）：elements 为非空参数，传 null 即抛。
        // 声明类是 package-private，必须 setAccessible 才能 invoke。
        val addAll = Class.forName("kotlin.collections.CollectionsKt")
            .getMethod("addAll", Collection::class.java, Iterable::class.java)
            .apply { isAccessible = true }
        addAll.invoke(null, collected, filters)
        return collected
    }

    @Test
    fun `旧代理形态逐字复现用户日志里的NPE`() {
        try {
            platformCollect(legacyProvider())
            fail("旧形态必须复现 261 事故：getFilters 返回 null → addAll(collection, null)")
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            println("REPRO-261-NPE: ${cause.javaClass.name}: ${cause.message}")
            assertTrue("必须是被包装的 NullPointerException，实际=${cause.javaClass.name}",
                cause is NullPointerException)
            val message = cause.message.orEmpty()
            assertTrue("NPE 文案必须命中用户日志的判空来源：实际=$message",
                message.contains("Parameter specified as non-null is null"))
            assertTrue("必须指出 addAll 的 elements 参数：实际=$message",
                message.contains("addAll") && message.contains("parameter elements"))
        }
    }

    @Test
    fun `现行桥在同一句调用下不抛且返回空清单`() {
        val bridgeProvider = bridgeProviderProxy()
        val collected = platformCollect(bridgeProvider)
        assertEquals("门控退化时平台拿到空清单即可，绝不能是 null", emptyList<Any?>(), collected)
    }

    /** 用桥的私有静态 providerHandler() 包一个实现 261 形态接口的代理（与真机注册形态同源）。 */
    private fun bridgeProviderProxy(): Any {
        val bridgeClass = McpGateFilterEpBridge::class.java
        val handler = bridgeClass.getDeclaredMethod("providerHandler").apply { isAccessible = true }
            .invoke(null) as java.lang.reflect.InvocationHandler
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Provider261::class.java),
            handler,
        )
    }
}
