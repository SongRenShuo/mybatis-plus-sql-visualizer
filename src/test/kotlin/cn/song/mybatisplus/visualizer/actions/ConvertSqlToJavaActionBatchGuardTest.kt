package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 E：SQL→Java 批量转换实体查找守卫回归。
 *
 * 批量路径曾在池线程裸跑 ReadAction.compute——PCE/PSI 失效等异常直接终止池任务，
 * invokeLater 永不投递，多语句 Alt+Shift+J 静默无反应（对照单语句路径有 try/catch
 * 兜底）。修复后 [runBatchLookupGuarded]：取消协议（PCE/Cancellation）原样上抛，
 * 其余 Throwable WARN 留痕并返回 null，调用方在 EDT 给用户可见反馈
 * （[batchLookupFailureSinkForTest] 缝，headless 不弹真通知）。
 */
class ConvertSqlToJavaActionBatchGuardTest {

    @Test
    fun `查找成功_原值透传`() {
        val marker = Any()
        assertSame(marker, runBatchLookupGuarded { marker })
    }

    @Test
    fun `查找失败_普通异常收敛为null不炸池任务`() {
        assertNull(runBatchLookupGuarded<String> { throw IllegalStateException("psi invalid") })
    }

    @Test
    fun `查找被取消_PCE原样上抛不吞`() {
        try {
            runBatchLookupGuarded<String> { throw ProcessCanceledException() }
            fail("PCE 应原样上抛（取消协议）")
        } catch (expected: ProcessCanceledException) {
            // 上抛即契约
        }
    }
}
