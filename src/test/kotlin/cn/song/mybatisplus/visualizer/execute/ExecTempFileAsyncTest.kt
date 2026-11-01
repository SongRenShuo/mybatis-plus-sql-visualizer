package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * T-ExecPlanEdtSlowOp：exec 临时文件构建的**异步契约**行为测试。
 *
 * 缺陷实证（2026-11-01 真机）：点「执行计划」→ `prepareConsole` → `chooseAndPrepare` →
 * `createTempPsiFile` → `ReadAction.compute { PsiManager.findFile(vf) }` 全程 EDT，平台抛
 * `SEVERE SlowOperations - Plugin to blame: SQL Visualizer`。修法把慢段整体挪到池线程，
 * EDT 侧只派发。
 *
 * 本用例钉两件事（headless 下平台不对 EDT 慢操作断言，故只能钉线程契约本身）：
 *  1. **EDT 调用立即返回**——返回时回调尚未发生。若有人把 `createTempPsiFileAsync` 改回同步直调，
 *     这条立即变红（反向验证锚点）；
 *  2. **回调在 EDT 上发生**且拿到真实产物（VF/PSI 内容 = 提交 SQL）——续接链的弹窗/控制台
 *     动作依赖这一点。
 */
class ExecTempFileAsyncTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            DataSourceSelector.clearCachedTempPsiFileForTest()
        } finally {
            super.tearDown()
        }
    }

    fun testEdtEntryReturnsBeforeSlowSegmentAndCallbackRunsOnEdt() {
        assertTrue(
            "夹具前提：本测试须跑在 EDT 上（headless 下 isDispatchThread 恒 true）",
            ApplicationManager.getApplication().isDispatchThread,
        )
        val sql = "SELECT 42"
        var callbackRan = false
        var callbackOnEdt = false
        var writeAllowedInCallback = false
        var produced: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null

        DataSourceSelector.createTempPsiFileAsync(project, sql) { pair ->
            callbackRan = true
            callbackOnEdt = ApplicationManager.getApplication().isDispatchThread
            // 契约读数：write-safe 投递（nonModal）下平台会套 runWithWritingAllowed，
            // 回调内 myWritingAllowed=true；any() 投递不套（2026-11-01 真机回归）。
            // isWritingAllowed() 要求写意图锁，无锁时抛——按 DocWriteContext 同款口径兜底 false。
            writeAllowedInCallback = runCatching {
                com.intellij.openapi.application.TransactionGuard.getInstance().isWritingAllowed()
            }.getOrDefault(false)
            produced = pair
        }

        assertFalse(
            "EDT 入口必须异步返回——调用线程同步跑 VFS refresh + PsiManager.findFile 就是 " +
                "SEVERE SlowOperations 本体（2026-11-01 真机栈）",
            callbackRan,
        )

        val deadline = System.currentTimeMillis() + 20_000
        while (!callbackRan && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }

        assertTrue("回调必须在预算内到达（否则点执行后建链挂死）", callbackRan)
        assertTrue("回调必须在 EDT 上执行（续接链全是 UI 动作）", callbackOnEdt)
        assertTrue(
            "续接回调必须处于 write-safe 上下文——否则续接链的文档写（控制台 setText/" +
                "commitDocument）会触发 TransactionGuard Write-unsafe（2026-11-01 真机回归）",
            writeAllowedInCallback,
        )
        val ready = produced
        assertNotNull(
            "临时文件构建应成功（失败原因=${DataSourceSelector.lastTempFileFailureForTest()}）",
            ready,
        )
        assertEquals("PSI 内容应等于提交的 SQL", sql, ready!!.second.text)
        assertEquals(
            "VF 内容应等于提交的 SQL",
            sql,
            String(ready.first.contentsToByteArray(), Charsets.UTF_8),
        )
    }

    /**
     * T-ExecPsiInvalid（2026-11-01 全量门禁 shard2 实证）：异步窗口内 PSI 失效必须被兜住。
     *
     * 形态：池线程 `findFile` 拿到 PSI → 文件内容变化（doc 落盘/自动保存/其他写方覆盖）→ 平台
     * 丢弃旧 PSI（`vf.isValid` 仍 true，只有内容变了）→ EDT 回调里读 `psi.text` 硬抛
     * `PsiInvalidElementAccessException: different providers: …; null(0)`。
     *
     * 本用例直接钉兜底内核 [revalidateOnEdt]：先造一个**已失效**的 PSI（写内容把它顶掉），
     * 再断言兜底返回的是**新解析出的有效 PSI 且内容为新内容**。反向验证：把 [revalidateOnEdt]
     * 改成直返 `pair`，本用例必红（读到死 PSI 抛 PsiInvalidElementAccessException）。
     */
    fun testRevalidateOnEdtRebuildsInvalidatedPsi() {
        val file = myFixture.addFileToProject("exec/psi-revalidate.sql", "SELECT 1")
        val stalePsi = file
        val staleVf = file.virtualFile
        assertTrue("夹具前提：初始 PSI 有效", stalePsi.isValid)

        // 删除旧文件把 PSI 打成失效（平台没有公开的「只失效 PSI」入口；删除是最贴近真实的形态：
        // 用户/其他写方把 mpsql-exec.sql 换掉或删掉后，缓存槽里躺着的正是这种死 PSI）
        ApplicationManager.getApplication().runWriteAction { staleVf.delete(this) }
        assertFalse("夹具前提：文件删除后旧 PSI 必须已失效", stalePsi.isValid)

        val fresh = myFixture.addFileToProject("exec/psi-revalidate.sql", "SELECT 2")
        val rebuilt = revalidateOnEdt(project, fresh.virtualFile to stalePsi)
        assertNotNull("PSI 失效后必须重解析成功，而不是把死 PSI 递进续接链", rebuilt)
        assertTrue("重解析结果必须有效", rebuilt!!.second.isValid)
        assertNotSame("必须换成新 PSI 实例（复用死 PSI 等于没兜）", stalePsi, rebuilt.second)
        assertEquals("重解析结果内容应为最新内容", "SELECT 2", rebuilt.second.text)
        assertSame("VF 不应被替换", fresh.virtualFile, rebuilt.first)

        // 有效 PSI 走热路径：原样返回，不额外重解析
        val hot = revalidateOnEdt(project, rebuilt)
        assertSame("有效 PSI 必须原样返回（热路径零额外开销）", rebuilt.second, hot!!.second)
        assertNull("null 入参原样透传（建链失败语义不变）", revalidateOnEdt(project, null))
    }
}
