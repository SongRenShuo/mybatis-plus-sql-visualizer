package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 F：Action 后台 ReadAction 载荷守卫回归。
 *
 * 本仓 9 处 `catch (t: Throwable)` 站点曾把取消（PCE——ReadAction 被写操作取消/进度取消）
 * 一并吞成「未找到」空结果，EDT 分支随即弹误导性轻通知。修复后各站点统一走
 * [actionPayloadGuarded]：取消协议原样上抛，其余 Throwable 收敛为站点原兜底值。
 */
class ActionPayloadGuardTest {

    @Test
    fun `成功_原值透传`() {
        assertEquals(42, actionPayloadGuarded(0) { 42 })
    }

    @Test
    fun `普通异常_收敛为站点兜底值`() {
        val default = Triple(emptyList<String>(), emptyList<String>(), false)
        assertEquals(default, actionPayloadGuarded(default) { throw IllegalStateException("psi invalid") })
    }

    @Test
    fun `取消_PCE原样上抛不吞`() {
        try {
            actionPayloadGuarded(0) { throw ProcessCanceledException() }
            fail("PCE 应原样上抛（取消协议）")
        } catch (expected: ProcessCanceledException) {
            // 上抛即契约
        }
    }
}
