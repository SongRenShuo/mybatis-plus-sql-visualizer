package cn.song.mybatisplus.visualizer.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-Mcp261FilterContract 券02：平台过滤器 EP 的契约形态探测。
 *
 * 背景（2026-10-29 用户报障，javap 六份 mcpserver jar 实测）：同一 EP 名在三条版本线上有三种
 * 接口形态——A `applyFilters`（262/263）、B `getFilters(Implementation, SessionOptions): List`
 * （261.24374+/261.25134）、C `getFilters(Implementation): StateFlow`（261.22158）。旧探测只判
 * 「类是否存在」，于是把按 A 写的代理挂到 B 形态上 → `getFilters` 落兜底返回 null → 平台
 * `addAll(collection, null)` 抛 NPE → **内置 MCP Server 对所有客户端 HTTP 500**。
 *
 * 本用例钉住判据：**只认签名，不认类存在**；B 必须「双参 + 返回 List」，其余一律 UNKNOWN
 * （UNKNOWN 的语义是「不注册」——宁可不门控，也不能挂一个会返回 null 的代理）。
 *
 * k2j 互操作注：FilterForm/detectFilterForm/describe 均为 McpGateFilterEpBridge 内嵌枚举与
 * 静态方法（describe 首参收接收者）。
 */
class McpGateFilterFormDetectTest {

    @Test
    fun `A形态_applyFilters在场`() {
        assertEquals(McpGateFilterEpBridge.FilterForm.A_APPLY, McpGateFilterEpBridge.detectFilterForm(FormA::class.java))
    }

    @Test
    fun `B形态_双参getFilters返回List`() {
        assertEquals(McpGateFilterEpBridge.FilterForm.B_GET_LIST, McpGateFilterEpBridge.detectFilterForm(FormB::class.java))
    }

    @Test
    fun `C形态_StateFlow返回归UNKNOWN`() {
        // 261.22158：getFilters 单参、返回 StateFlow —— 不注册（MCP 可用，门控不生效）
        assertEquals(McpGateFilterEpBridge.FilterForm.UNKNOWN, McpGateFilterEpBridge.detectFilterForm(FormC::class.java))
    }

    @Test
    fun `单参返回List也归UNKNOWN_防误判`() {
        // 只按名字匹配就会把这个形态当 B；必须连参数个数一起判
        assertEquals(McpGateFilterEpBridge.FilterForm.UNKNOWN, McpGateFilterEpBridge.detectFilterForm(SingleArgList::class.java))
    }

    @Test
    fun `双参但返回非集合归UNKNOWN`() {
        assertEquals(McpGateFilterEpBridge.FilterForm.UNKNOWN, McpGateFilterEpBridge.detectFilterForm(TwoArgNonList::class.java))
    }

    @Test
    fun `无关接口归UNKNOWN`() {
        assertEquals(McpGateFilterEpBridge.FilterForm.UNKNOWN, McpGateFilterEpBridge.detectFilterForm(Unrelated::class.java))
    }

    @Test
    fun `形态描述带版本线便于日志回溯`() {
        assertEquals("A(applyFilters, 262/263)", McpGateFilterEpBridge.describe(McpGateFilterEpBridge.FilterForm.A_APPLY))
        assertEquals("B(getFilters, 261.24374+)", McpGateFilterEpBridge.describe(McpGateFilterEpBridge.FilterForm.B_GET_LIST))
        assertEquals("UNKNOWN", McpGateFilterEpBridge.describe(McpGateFilterEpBridge.FilterForm.UNKNOWN))
    }

    // ---- 形态替身（签名与各版本线字节码一致，不链接任何平台类型） -------------------------

    /** 262.10968 / 263.7307：`applyFilters(Context, Implementation, SessionOptions, InvocationMode)`。 */
    interface FormA {
        fun applyFilters(context: Any?, clientInfo: Any?, options: Any?, mode: Any?)
        fun getUpdates(clientInfo: Any?, scope: Any?, options: Any?, mode: Any?): Flow<Any>
    }

    /** 261.25134 / 261.24374：`getFilters(Implementation, SessionOptions): List<McpToolFilter>`。 */
    interface FormB {
        fun getFilters(clientInfo: Any?, options: Any?): List<Any?>
        fun getUpdates(clientInfo: Any?, scope: Any?, options: Any?): Flow<Any>
    }

    /** 261.22158：`getFilters(Implementation): StateFlow<List<McpToolFilter>>`。 */
    interface FormC {
        fun getFilters(clientInfo: Any?): StateFlow<List<Any?>>
    }

    interface SingleArgList {
        fun getFilters(clientInfo: Any?): List<Any?>
    }

    interface TwoArgNonList {
        fun getFilters(clientInfo: Any?, options: Any?): Any?
    }

    interface Unrelated {
        fun hello(): String
    }
}
