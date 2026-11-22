package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-PrewarmLateRecover：降级链的**恢复出口**——迟到产物换装（F1）与横幅「重试」动作（F2）。
 *
 * 缺陷形态（真机 2026-09-22 沙盒实测，见 `docs/验收文档/20260920-待分析问题清单.md` P1 补充读数）：
 * 冷启首开预热耗时 3016ms 远超 500ms 预算 → 面板落内存 PSI 降级 + 挂横幅；预热其实成功了，但产物
 * 只落在 `prewarmedTempFile` 字段上（`createEditor` 只在 `editor == null` 时进入），此后无人消费，
 * 横幅在整个对话框生命周期内常驻——用户只能关掉重开。
 *
 * 两条恢复路径分别锁死：
 *  - **F1 迟到换装**：[test迟到产物到达_自动换装并清横幅]（走真实胶水——`await.complete` 返回 false
 *    即「消费侧已放弃」，预热线程据此走迟到通道）、[test用户已编辑_迟到产物不换装且不丢内容]（守卫反向）、
 *    [test面板销毁后_迟到产物不换装不抛]；
 *  - **F2 横幅重试**：[test降级横幅重试动作_点击后重新预热并换装]（动作可见性 + 文案 + 点击后重臂、
 *    就绪换装、清横幅）、[test重试动作_用户已编辑时也换装且内容不丢]（显式重试不受「未编辑」守卫限制，
 *    当前文本原样搬进新文档）。
 *
 * **降级态如何确定性构造**：本环境里「真实首开超时」要靠负载（不可复现），故走 [degradePanel]——
 * 先等本面板预热出结果，再正常装配消费掉产物，然后 `releaseEditor()` 后二次装配：产物已被消费
 * （`prewarmedTempFile === prewarmed` 判假）→ 降级链 + 横幅。这条路线与 P9 记录的同面板二次装配同源，
 * 是 headless 下唯一**与分片顺序无关**的降级入口（不依赖「本 JVM 首个面板」这类脆弱前置）；
 * 「先等预热出结果」这步不可省——否则首装配即降级，而产物迟到到达会被 F1 自动换装，降级态当场消失。
 */
class SqlPreviewPanelPrewarmLateRecoverTest : BasePlatformTestCase() {

    private fun result() = PreviewResult(
        sql = "SELECT id\nFROM user\nWHERE username = ?",
        compactSql = "SELECT id FROM user WHERE username = ?",
        unresolvedParams = listOf("username"),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        columns = listOf("id", "username"),
    )

    /**
     * 有界等待预热出结果（成功或失败），等待期间持续抽干 EDT 队列——方言预映射把 `setMapping`
     * 经 `invokeLater` 派给 EDT 并有界等待，纯 sleep 会把那 2s 预算烧掉（同
     * [SqlPreviewPanelPrewarmConsumeTest.awaitPrewarmProduct] 的实测教训）。
     *
     * **失败时重臂一次**（allowRearm）：重负载下 VFS refresh / `PsiManager.findFile` 会偶发失败
     * （本机 7 个 gradle daemon 并行时实测），此时按生产路径 [SqlPreviewPanel.retryPrewarm] 重新
     * 臂一轮再等——它同时验证了重试链本身可用。两次都拿不到产物才判环境异常（不静默跳过，
     * 否则缺陷回归时本用例会假绿）。
     */
    private fun awaitPrewarmProduct(
        panel: SqlPreviewPanel,
        allowRearm: Boolean = true,
    ): Pair<com.intellij.openapi.vfs.VirtualFile, com.intellij.psi.PsiFile>? {
        var await = panel.prewarmAwaitForTest()
        assertNotNull("面板构造期应已发起预热", await)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !await!!.isDone()) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("预热应在预算内出结果（成功或失败）", await!!.isDone())
        val product = await.joinOrNull()
        if (product != null || !allowRearm) return product
        panel.retryPrewarmForTest()
        return awaitPrewarmProduct(panel, allowRearm = false)
    }

    private fun editorDocumentFile(panel: SqlPreviewPanel): com.intellij.openapi.vfs.VirtualFile? {
        val doc = panel.editorForTest()?.document ?: return null
        return FileDocumentManager.getInstance().getFile(doc)
    }

    /**
     * 有界等待「换装完成」（编辑器文档落到项目内文件上），期间持续抽干 EDT 队列。
     *
     * 为什么不能只抽一次：换装两条出口的时机不同——预热在预算内就绪时走 `scheduleOnce` 的 onReady，
     * 超预算时走迟到通道（预热线程发布时发现消费侧已放弃 → `invokeLater` 回 EDT）。机器有负载时
     * 后者是常态，单次抽队列会在产物还没到达时就断言失败（本用例首版即踩：重试轮预热 500ms 未就绪）。
     */
    private fun awaitUpgrade(
        panel: SqlPreviewPanel,
        budgetMs: Long = 30_000,
    ): com.intellij.openapi.vfs.VirtualFile? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            editorDocumentFile(panel)?.let { return it }
            Thread.sleep(10)
        }
        return editorDocumentFile(panel)
    }

    /**
     * 把面板推进**稳定**降级态（见类 KDoc），并断言前置成立。
     *
     * 必须先 `awaitPrewarmProduct(panel)` 等本面板自己的预热出结果——否则（JVM 首个面板预热超预算时）
     * 首装配就走降级，而预热产物随后**迟到到达会被本次新功能自动换装**，前置当场失效（首版即踩：
     * 断言「降级文档不应挂在项目内临时文件上」时面板已经自己恢复成主路径）。等预热出结果后：
     * 首装配消费产物 → 释放编辑器 → 二次装配必判「产物已消费」→ 降级，且**没有在途产物**会来换装。
     */
    private fun degradePanel(panel: SqlPreviewPanel) {
        awaitPrewarmProduct(panel)
        // T-FirstOpenDegradedLabel 二轮：生产路径在「产物已消费」时会自动补一轮预热（语义回到
        // 「在途」→ 宽限期静默）。本类要的是**稳定**降级态来验迟到换装，故夹具显式置位
        // prewarmAutoRetried，让这一轮走「已消费」的硬失败档——测试意图，不是绕过实现。
        panel.prewarmAutoRetried = true
        panel.showResult(result())
        UIUtil.dispatchAllInvocationEvents()
        panel.releaseEditorForTest()
        panel.showResult(result())
        UIUtil.dispatchAllInvocationEvents()
        assertNull("降级文档不应挂在项目内临时文件上（前置）", editorDocumentFile(panel))
        assertNotNull("降级态应登记降级文档身份（换装判据的身份字段）", panel.degradedDocumentForTest())
        assertEquals(
            "降级态应挂 DEGRADED 横幅（前置）",
            DegradationBanner.Kind.DEGRADED,
            panel.degradationBannerForTest().kindForTest(),
        )
    }

    // ------------------------------------------------------------------
    // F1：迟到产物换装
    // ------------------------------------------------------------------

    fun test迟到产物到达_自动换装并清横幅() {
        // 产物由第二个面板提供：降级态那条路线里 releaseEditor 会删掉本面板的临时文件，
        // 用已删除的文件做「迟到产物」不成立——donor 的产物是磁盘上真实存在的文件。
        val donor = SqlPreviewPanel(project)
        val panel = SqlPreviewPanel(project)
        try {
            val product = awaitPrewarmProduct(donor)
            assertNotNull(
                "headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）",
                product,
            )
            degradePanel(panel)

            // 真实胶水等价驱动：预热线程发布时 `PrewarmAwait.complete` 返回 false（消费侧已放弃）
            // → 面板登记的迟到通道被触发 → 回 EDT 换装。
            val await = panel.prewarmAwaitForTest()
            assertNotNull(await)
            assertFalse(
                "消费侧已放弃后 complete 必须返回 false（胶水据此走迟到通道）",
                await!!.complete(product!!),
            )
            UIUtil.dispatchAllInvocationEvents()

            val file = awaitUpgrade(panel)
            assertNotNull("迟到产物到达应换装成「项目内文件背书」的编辑器（主路径），实际仍为降级文档", file)
            assertTrue("换装后的文档应挂在项目预览目录内，实际：${file!!.path}", file.path.contains("sql-visualizer"))
            assertNull("换装后降级文档身份应清空", panel.degradedDocumentForTest())
            assertNotEquals(
                "换装后应清除 DEGRADED 横幅（主路径装配统一清除）",
                DegradationBanner.Kind.DEGRADED,
                panel.degradationBannerForTest().kindForTest(),
            )
            assertNotNull("换装后 psiFile 应带真实 VFS 文件（内存 PSI 降级产物无 VFS 文件）", panel.psiFileForTest()?.virtualFile)
        } finally {
            panel.clear()
            donor.clear()
        }
    }

    fun test用户已编辑_迟到产物不换装且不丢内容() {
        val donor = SqlPreviewPanel(project)
        val panel = SqlPreviewPanel(project)
        try {
            val product = awaitPrewarmProduct(donor)
            assertNotNull("headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）", product)
            degradePanel(panel)

            val doc = panel.editorForTest()!!.document
            WriteCommandAction.runWriteCommandAction(project) { doc.setText("SELECT 1 /* user edit */") }
            assertFalse(
                "用户编辑后文本应与插件最后写入不同（前置）",
                doc.text == panel.lastRenderedSqlForTest(),
            )

            panel.onLatePrewarmProductForTest(product!!)
            UIUtil.dispatchAllInvocationEvents()

            assertNull("用户已编辑时不得换装（不抢编辑现场）", editorDocumentFile(panel))
            assertEquals("用户文本不得被覆盖", "SELECT 1 /* user edit */", doc.text)
            assertEquals(
                "未换装时横幅应保留",
                DegradationBanner.Kind.DEGRADED,
                panel.degradationBannerForTest().kindForTest(),
            )
            assertNotNull("降级文档身份应保留（下次到达仍可换装）", panel.degradedDocumentForTest())
        } finally {
            panel.clear()
            donor.clear()
        }
    }

    fun test面板销毁后_迟到产物不换装不抛() {
        val donor = SqlPreviewPanel(project)
        val panel = SqlPreviewPanel(project)
        try {
            val product = awaitPrewarmProduct(donor)
            assertNotNull("headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）", product)
            degradePanel(panel)
            panel.dispose()

            // 不得抛（面板销毁后预热线程可能才发布产物）
            panel.onLatePrewarmProductForTest(product!!)
            UIUtil.dispatchAllInvocationEvents()
            assertNull("面板已销毁时不得换装", editorDocumentFile(panel))
        } finally {
            // dispose() 不释放编辑器，归还职责在面板侧（fixture tearDown 会断言「无未释放 Editor」）
            panel.releaseEditorForTest()
            donor.clear()
        }
    }

    // ------------------------------------------------------------------
    // F2：横幅「重试」动作
    // ------------------------------------------------------------------

    fun test降级横幅重试动作_点击后重新预热并换装() {
        val panel = SqlPreviewPanel(project)
        try {
            degradePanel(panel)
            val banner = panel.degradationBannerForTest()
            assertTrue("降级横幅应显示「重试」动作（onRetry 已接上）", banner.retryVisibleForTest())
            assertEquals(
                "动作文案应按当前语言解析 i18n 键",
                MpSqlVisualizerBundle.message("panel.banner.retry"),
                banner.retryTextForTest(),
            )

            banner.clickRetryForTest() // 等价于用户点击
            val await = panel.prewarmAwaitForTest()
            assertNotNull("重试应重新臂一轮预热", await)
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && !await!!.isDone()) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
            assertTrue("重试轮预热应在预算内出结果", await!!.isDone())

            // 两条出口都要认：预算内就绪走 onReady、超预算走迟到通道——统一用有界轮询等换装落定
            val file = awaitUpgrade(panel)
            assertNotNull("重试就绪后应换装成「项目内文件背书」的编辑器", file)
            assertNotEquals(
                "换装后应清除 DEGRADED 横幅",
                DegradationBanner.Kind.DEGRADED,
                banner.kindForTest(),
            )
        } finally {
            panel.clear()
        }
    }

    /**
     * 用户显式点「重试」时不得被「文本未变」守卫挡住（否则编辑过预览之后重试就是按不出反应的假动作）。
     * 口径同计划 §七 前提 5：把**当前文档文本**作为新装配的 SQL，用户内容不丢。
     */
    fun test重试动作_用户已编辑时也换装且内容不丢() {
        val panel = SqlPreviewPanel(project)
        try {
            degradePanel(panel)
            val doc = panel.editorForTest()!!.document
            WriteCommandAction.runWriteCommandAction(project) { doc.setText("SELECT 2 /* user edit */") }

            panel.degradationBannerForTest().clickRetryForTest()
            val file = awaitUpgrade(panel)
            assertNotNull("用户显式点重试 → 应换装（不再被「文本未变」守卫挡住）", file)
            assertEquals(
                "用户文本应原样搬进新文档（内容不丢）",
                "SELECT 2 /* user edit */",
                panel.editorForTest()!!.document.text,
            )
            assertNotEquals(
                "换装后应清除 DEGRADED 横幅",
                DegradationBanner.Kind.DEGRADED,
                panel.degradationBannerForTest().kindForTest(),
            )
        } finally {
            panel.clear()
        }
    }
}
