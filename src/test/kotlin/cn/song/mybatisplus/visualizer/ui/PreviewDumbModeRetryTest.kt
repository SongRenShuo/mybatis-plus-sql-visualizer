package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-PerfSweep30 02d：预览转换链的 DumbMode 防护（性能分析 §4.1 账本②）。
 *
 * 缺陷形态：previewAsync 把 IndexNotReadyException 吞成 null＋WARN，用户点了没反应；
 * ConvertToSqlAction 四连 fallback 同样吞。收口形态：
 *  ① IndexNotReadyException 单独分流 → DumbService.runWhenSmart 重试一次；
 *  ② 重试成功 → 正常 onResult（用户无感）；
 *  ③ 重试仍失败 → 可见归因（通知，复用既有平台通知基础设施），不得静默。
 *
 * 反向验证（撤闸必红）：把 catch 顺序改回「IndexNotReady 落进通用 Throwable 分支」→
 * 本用例的 [testDumb期重试成功仍交付预览] / [testDumb持续失败给可见归因] 必红
 * （前者拿不到结果、后者拿不到归因通知）。
 *
 * 与「runners=0 方言不匹配/NOT_A_SQL_DATASOURCE」归因链互斥：那是执行链的数据源归因
 * （ExecDialectRouting/EmptyRunnersHint），本处是转换链的索引归因，不共用入口。
 */
class PreviewDumbModeRetryTest : BasePlatformTestCase() {

    private var notices = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        notices = mutableListOf()
        SqlPreviewService.indexNotReadyNoticeForTest = { notices.add(it.content) }
    }

    override fun tearDown() {
        SqlPreviewService.indexNotReadyNoticeForTest = null
        super.tearDown()
    }

    /** 重试一次即成功（索引在 runWhenSmart 里已就绪）→ 正常交付预览结果，无归因通知。 */
    fun testDumb期重试成功仍交付预览() {
        var attempts = 0
        val result = runPreviewAsyncDumb {
            attempts++
            if (attempts == 1) throw IndexNotReadyException.create()
            null // PreviewResult? 为 null 也走 onResult（交付语义），attempts 才是判据
        }

        assertTrue("重试必须发生（首次 IndexNotReady 后 runWhenSmart 内再跑一次）：attempts=$attempts", attempts >= 2)
        assertEquals("重试成功不得再给可见归因（用户无感）", 0, notices.size)
        assertEquals("onResult 必须被调用一次", 1, result)
    }

    /** 重试仍失败（索引持续未就绪）→ 可见归因（通知）＋ onResult(null)，不得静默。 */
    fun testDumb持续失败给可见归因() {
        var attempts = 0
        val result = runPreviewAsyncDumb {
            attempts++
            throw IndexNotReadyException.create()
        }

        assertTrue("重试必须发生：attempts=$attempts", attempts >= 2)
        assertEquals("重试仍失败必须给可见归因（一条通知）", 1, notices.size)
        // 归因文案走 bundle（中/英随 locale）；断言「非空且非 notFound 文案」即可判别没走错通道
        val text = notices.first()
        assertTrue("归因文案须非空", text.isNotBlank())
        assertTrue(
            "归因不得复用 notFound 文案（会与「未找到 SQL 源」混道）：$text",
            !text.contains("No previewable SQL source", ignoreCase = true) &&
                !text.contains("未在光标处找到", ignoreCase = true),
        )
        assertEquals("onResult 必须被调用一次（null 交付既有降级语义）", 1, result)
    }

    /** 非 IndexNotReady 的普通异常保持既有语义：不重试、不归因、onResult(null)。 */
    fun test普通异常不重试不归因() {
        var attempts = 0
        val result = runPreviewAsyncDumb {
            attempts++
            throw IllegalStateException("boom")
        }

        assertEquals("普通异常不得触发 dumb 重试", 1, attempts)
        assertEquals("普通异常不得发 dumb 归因通知", 0, notices.size)
        assertEquals(1, result)
    }

    /**
     * 驱动 [SqlPreviewService.previewAsync] 并等异步链收敛：
     *  - 池线程执行 ReadAction（compute 可能抛 IndexNotReady）；
     *  - runWhenSmart 回调在 dumb 解除后执行（测试里 DumbService 非 dumb → 立即调度，
     *    平台实现内部走队列，需抽干 EDT 队列）；
     *  - onResult 经 invokeLater 回 EDT。
     * 返回 onResult 调用次数（结果本身为 null 亦计数）。
     */
    private fun runPreviewAsyncDumb(compute: () -> cn.song.mybatisplus.visualizer.model.PreviewResult?): Int {
        val app = ApplicationManager.getApplication()
        var delivered = 0
        val done = java.util.concurrent.CountDownLatch(1)
        app.executeOnPooledThread {
            SqlPreviewService.previewAsync(project, compute) {
                delivered++
                done.countDown()
            }
        }
        val start = System.currentTimeMillis()
        while (done.count > 0 && System.currentTimeMillis() - start < 60_000) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(5)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("previewAsync 未在预算内回调（dumb 重试链是否卡死？）", done.count == 0L)
        return delivered
    }

    /** 夹具前提：测试 fixture 处于 smart 态（否则 runWhenSmart 永不解除，用例会挂）。 */
    fun testFixture处于Smart态() {
        assertNotNull(DumbService.getInstance(project))
        assertTrue("fixture 应处于 smart 态（BasePlatformTestCase 不模拟 dumb）", !DumbService.isDumb(project))
    }
}
