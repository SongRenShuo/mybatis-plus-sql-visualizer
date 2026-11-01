package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        var produced: Pair<com.intellij.openapi.vfs.VirtualFile, PsiFile>? = null

        DataSourceSelector.createTempPsiFileAsync(project, sql) { pair ->
            callbackRan = true
            callbackOnEdt = ApplicationManager.getApplication().isDispatchThread
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
}
