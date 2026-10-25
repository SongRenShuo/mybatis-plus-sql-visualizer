package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import cn.song.mybatisplus.visualizer.mcp.McpDbExecutorKt.withDbBlockingContext

/**
 * T-PreviewChainNoise W2 护栏：Database 反射链的阻塞上下文兜底（[withDbBlockingContext]）。
 *
 * 缺陷形态（2026-10-23 用户真机日志）：`ExplainPanelChannel.executeStructured` 用
 * `executeOnPooledThread` 直调 `McpDbExecutor.executeQuery` → `Builder.createBlocking` 内部
 * `runBlockingCancellable` 在裸池线程（无 ProgressIndicator/Job）打
 * `IllegalStateException: There is no ProgressIndicator or Job in this thread` SEVERE。
 * 修法=兜底下沉到 [McpDbExecutor]（含 `PreviewSideQuery` 同坑先例，不许出现第三个漏网点）。
 *
 * 两条断言对应修法的两半：
 *  1. 裸池线程内块里**必须有**当前指示器（平台 `coroutines.kt:75` 的判据即
 *     `ProgressManager.getGlobalProgressIndicator()`，`getProgressIndicator()` 同源）；
 *  2. 已有指示器时**不得替换**（否则丢外层取消语义——MCP 工具跑在协程里带 Job）。
 *
 * K2J 互操作：master 侧 Kotlin 顶层函数落 Java 门面类 [McpDbExecutorKt]（坑 1 拆分惯例），
 * Kotlin 调用须显式 import Java static 后才可裸名调用。
 */
class McpDbExecutorProgressContextTest : BasePlatformTestCase() {

    /** 裸池线程（与生产 `executeOnPooledThread` 同形态）：块内应有兜底指示器。 */
    fun testBarePooledThreadGetsFallbackIndicatorInsideWrapper() {
        val inside = AtomicReference<ProgressIndicator?>()
        val ok = ApplicationManager.getApplication().executeOnPooledThread<Boolean> {
            withDbBlockingContext {
                inside.set(ProgressManager.getInstance().progressIndicator)
                true
            }
        }.get(30, TimeUnit.SECONDS)

        assertTrue("包装体应正常返回", ok)
        assertNotNull(
            "裸池线程内应有兜底指示器（缺失即 createBlocking 会打 no-ProgressIndicator SEVERE）",
            inside.get(),
        )
    }

    /** 已有指示器时原样透传——runProcess 换指示器即丢取消语义，必须走直调分支。 */
    fun testExistingIndicatorIsNotReplaced() {
        val existing = EmptyProgressIndicator()
        val inside = AtomicReference<ProgressIndicator?>()
        ProgressManager.getInstance().runProcess({
            withDbBlockingContext {
                inside.set(ProgressManager.getInstance().progressIndicator)
            }
        }, existing)

        assertSame("已有指示器时必须原样透传（不得被 EmptyProgressIndicator 顶掉）", existing, inside.get())
    }
}
