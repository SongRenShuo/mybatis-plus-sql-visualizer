package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.SQLException

/**
 * T-SAF-03（mcp-select-guard.client-side-only-readonly-gap，D3=A fail-closed）护栏：
 * 连接层只读强制缝（[enforceReadOnlyConnection]）——setReadOnly(true) 入口缺失/调用失败
 * 一律以结构化错误拒绝执行，**绝不降级为读写执行**。
 *
 * 缝级单测（普通 JUnit）：McpDbExecutor 的执行本体反射进 com.intellij.database 平台类，
 * 端到端只读行为归 runIde/真机核对（所有者观察项）：真实 RemoteConnection 上
 * setReadOnly(true) 应解析成功并生效（DBN remote JDBC 镜像 extends java.sql.Connection，
 * setReadOnly 为接口方法；McpDbSignatureCrossVersionTest 同款跨版本核验口径）。
 */
class McpDbExecutorReadOnlyTest {

    /** 平台无关 fake 连接：记录 setReadOnly 调用（方法名与 JDBC Connection 同形）。 */
    @Suppress("unused")
    private class FakeRemoteConnection {
        var readOnlyCalls = 0

        fun setReadOnly(readOnly: Boolean) {
            check(readOnly) { "must enforce read-only" }
            readOnlyCalls++
        }
    }

    /** setReadOnly 抛异常的 fake（驱动不支持/连接已关等失败形态）。 */
    @Suppress("unused")
    private class ThrowingRemoteConnection {
        fun setReadOnly(readOnly: Boolean) {
            throw UnsupportedOperationException("read-only mode is not supported")
        }
    }

    private fun setReadOnlyMethod(clazz: Class<*>) =
        clazz.methods.first { it.name == "setReadOnly" }

    @Test
    fun `enforceReadOnly_成功_以true调用一次`() {
        val method = setReadOnlyMethod(FakeRemoteConnection::class.java)
        val connection = FakeRemoteConnection()
        enforceReadOnlyConnection(connection) { method }
        assertEquals("setReadOnly(true) 应恰被调用一次", 1, connection.readOnlyCalls)
    }

    @Test
    fun `enforceReadOnly_入口缺失_结构化拒绝`() {
        // 平台签名漂移/裁剪发行版（解析缝返回 null）→ 拒绝执行，不降级
        val error = runCatching {
            enforceReadOnlyConnection(FakeRemoteConnection()) { null }
        }.exceptionOrNull()
        assertTrue("应抛 SQLException：$error", error is SQLException)
        assertTrue(
            "错误应带结构化码：${(error as SQLException).message}",
            error.message!!.contains(McpToolErrors.READ_ONLY_ENFORCEMENT_FAILED),
        )
    }

    @Test
    fun `enforceReadOnly_调用失败_结构化拒绝且不降级`() {
        // InvocationTargetException 解包：失败原因进消息，绝不回落读写执行
        val error = runCatching {
            enforceReadOnlyConnection(ThrowingRemoteConnection()) {
                setReadOnlyMethod(ThrowingRemoteConnection::class.java)
            }
        }.exceptionOrNull()
        assertTrue("应抛 SQLException：$error", error is SQLException)
        assertTrue(
            "错误应带结构化码：${(error as SQLException).message}",
            error.message!!.contains(McpToolErrors.READ_ONLY_ENFORCEMENT_FAILED),
        )
        assertTrue(
            "失败原因（驱动不支持）应进消息：${(error as SQLException).message}",
            error.message!!.contains("not supported"),
        )
    }

    @Test
    fun `enforceReadOnly_默认解析缝_平台类缺失环境fail_closed`() {
        // 普通 JUnit（无 DatabaseTools 平台类）:默认解析缝 Class.forName 必失败 → 结构化拒绝。
        // 反向事实（真机上 RemoteConnection.setReadOnly 可解析且生效）归 runIde 所有者核对。
        val error = runCatching {
            enforceReadOnlyConnection(Any())
        }.exceptionOrNull()
        assertTrue("应抛 SQLException：$error", error is SQLException)
        assertTrue(
            "错误应带结构化码：${(error as SQLException).message}",
            error.message!!.contains(McpToolErrors.READ_ONLY_ENFORCEMENT_FAILED),
        )
    }
}
