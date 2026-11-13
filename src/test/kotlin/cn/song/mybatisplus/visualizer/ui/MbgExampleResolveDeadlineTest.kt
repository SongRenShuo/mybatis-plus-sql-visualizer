package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-StartupReadLockFreeze §14 回归：M2 预算必须落在**检索内部**，不能只落在「调用方等多久」上。
 *
 * 事故形态（2026-11-13 沙盒真机日志）：
 * ```
 * [open-perf] total=3275ms parse+ctx=38 profile=1 m2=3219 model=17
 * ```
 * 预览主链 `SqlPreviewService.previewAsync` 把整段装配包在 `ReadAction.compute` 里，于是
 * `ApplicationManager.getApplication().isReadAccessAllowed` 恒为真，`resolve()` 走「已持读锁 →
 * 就地嵌套」分支，`blockingGet(RESOLVE_BUDGET_MS)` 那条永远不执行 ⇒ 800ms 上限被绕过。
 *
 * 两条断言：
 *  1. 截止判定本身（纯逻辑）；
 *  2. 源码层：`resolve()` 的两条分支都必须把 `deadlineAt` 传进 `resolveInReadAction`
 *     —— 只钉行为不钉接线，下次有人加分支照样能绕过去。
 */
class MbgExampleResolveDeadlineTest {

    @Test
    fun `截止判定：过期立即为真，未到为假，无限制恒为假`() {
        val now = System.currentTimeMillis()
        assertTrue(MbgExampleCallResolver.isDeadlineHitForTest(now - 1))
        assertFalse(MbgExampleCallResolver.isDeadlineHitForTest(now + 10_000))
        // 语义：Long.MAX_VALUE ＝ 不设限（纯逻辑单测与预取链复用），不能被当成「已过期」
        assertFalse(MbgExampleCallResolver.isDeadlineHitForTest(Long.MAX_VALUE))
    }

    @Test
    fun `两条 resolve 分支都把截止时刻传进检索`() {
        val src = resolverSource()
        val deadlinePasses = Regex("""resolveInReadAction\(project, namespace, statementId, deadlineAt\)""")
            .findAll(src)
            .count()
        // 就地嵌套分支 + 非阻塞有界分支，各一处；少一处就说明又出现「预算被绕过」的形态
        assertTrue(
            "resolve() 的两条分支都必须传 deadlineAt（实际命中 $deadlinePasses 处）：" +
                "少传即恢复「已持读锁时无上限」的事故形态",
            deadlinePasses >= 2,
        )
        // 截止判定必须真的在**检索内部**使用（否则传了参数也不生效）。
        // 注意别把测试缝 `isDeadlineHitForTest` 的调用算进来——首版按「全文件出现 ≥2 次」判定，
        // 摘除探针后仍为 2（缝自己算一次）⇒ 反向验证假绿，故改为只看 findCallSite 函数体。
        val findCallSiteBody = src.substringAfter("private fun findCallSite")
            .substringBefore("private fun ")
        assertTrue(
            "findCallSite 内必须自查 isDeadlineHit(deadlineAt)：预算要落在检索里，" +
                "只落在调用方等待上会被「已持读锁」分支绕过",
            "isDeadlineHit(deadlineAt)" in findCallSiteBody,
        )
    }

    @Test
    fun `预览首开不得同步调用 M2 检索`() {
        val src = xmlPreviewSource()
        assertFalse(
            "XmlPreviewService 不得再调用同步 resolve(...)：首开必须只读缓存 + 转后台" +
                "（真机 refsSeen=13 却 2.4s，引擎内部耗时无法被逐条自查打断）",
            Regex("""MbgExampleCallResolver\.resolve\(""").containsMatchIn(src),
        )
        assertTrue("首开必须走 resolveCachedOnly", "resolveCachedOnly(" in src)
        assertTrue("未命中必须转后台 warmForPreview", "warmForPreview(" in src)
        assertTrue("结果必须带 m2AwaitKey 供面板等落地", "m2AwaitKey" in src)
    }

    private fun resolverSource(): String = sourceOf(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/MbgExampleCallResolver.kt",
    )

    private fun xmlPreviewSource(): String = sourceOf(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/XmlPreviewService.kt",
    )

    private fun sourceOf(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, relativePath)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("未找到 $relativePath（user.dir=${System.getProperty("user.dir")}）")
    }
}
