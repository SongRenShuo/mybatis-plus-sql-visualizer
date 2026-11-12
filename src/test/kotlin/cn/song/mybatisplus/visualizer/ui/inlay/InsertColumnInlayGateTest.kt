package cn.song.mybatisplus.visualizer.ui.inlay

import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.codeInsight.hints.BlockConstraints
import com.intellij.codeInsight.hints.CollectorWithSettings
import com.intellij.codeInsight.hints.HorizontalConstraints
import com.intellij.codeInsight.hints.InlayDumpUtil
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.InlayHintsSinkImpl
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.RootInlayPresentation
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * INSERT 目标列 inlay 的**一次性闸门 + 规划缓存**测试（T-PerfSweep6 T-PS6-01）。
 *
 * 本轮唯一带正确性的发现：`InsertColumnInlayCollector` 的一次性闸门是普通 `var`，而平台真机 pass
 * 把分片元素交给 `JobLauncher.invokeConcurrentlyUnderProgress` **并发**跑、闭包共享同一份
 * `enabledCollectors`（243 装机版 `InlayHintsPass.kt` 实证：`skippedCollectors` 是并发集合，
 * 且只在 `collectHints` 返回后才置位）——「判定 + 置位」之间存在真实竞态窗口，两个分片线程可各投
 * 一遍提示（`InlayHintsSinkImpl` 同 offset 聚进同一 list ⇒ 重复 presentation）。
 *
 * 覆盖点：
 *  1. **并发分片下只投递一次**（闸门 AtomicBoolean 的守护用例）：同 collector 并发调 `collect`
 *     N 轮，每轮断言投递条数恰为规划条数。退回普通 `var` 时本用例应在若干轮里读出超额投递
 *     （竞态窗口是纳秒级，故按「k/N 轮复现」计读数，不保证每轮都红——见 §八 实施记录）。
 *  2. **stamp 未变不重算**：同文档连续两次取 collector（模拟平台逐 pass 新建 collector）→
 *     `InsertColumnInlayPlanner.planInvocationCount` 第二次不增（缓存命中）。
 *  3. **stamp 变更后结果正确**：文档改写后重新规划，提示条数/位点随新文本。
 *  4. **越界防御 + 既有提示文案回归**：真实渲染链 dump 与改动前逐字节一致；全部投递位点落在
 *     文档范围内。
 *
 * 注：`InsertColumnInlayCollector` 是文件级 private，用例一律经公共 `getCollectorFor` 取实例
 * （既有 `PreviewInsertColumnInlayProviderTest` 同路）。
 */
class InsertColumnInlayGateTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // T-PreviewInlayNativeYield：让位门在测试平台（IU 2024.3.7.1，DatabaseTools 在位）会把 provider
        // 整体短路成零提示。本类验的是「让位门以内」的既有契约（一次性闸门 + 规划缓存），故先钉死
        // 「原生不在位」，读数与该门上线前逐字一致；让位门本身另有 NativeColumnInlayYieldGateTest。
        NativeColumnInlayYieldGate.resetForTest()
        NativeColumnInlayYieldGate.probe = { false }
    }

    override fun tearDown() {
        try {
            NativeColumnInlayYieldGate.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    private val provider = PreviewInsertColumnInlayProvider()

    private val insertSql = "INSERT INTO user (id, name) VALUES (1, 'admin')"

    /** 并发压力轮数与线程数：轮数取到「竞态窗口 × 轮数」足以让普通 var 形态漏出超额投递的量级。 */
    private val raceRounds = 1200
    private val raceThreads = 6

    /**
     * 只计数的 sink：把 `addInlineElement` 的 offset 记下来，不触碰真实 `InlayModel`。
     *
     * 真机链路的 `InlayHintsSinkImpl` 同 offset 会把多条 presentation 聚进同一 list（重复投递 =
     * 同一位置两份提示），故「投递次数」就是本用例要断言的量——计数 sink 与之一一对齐。
     */
    private class CountingSink : InlayHintsSink {
        val offsets = ConcurrentLinkedQueue<Int>()

        override fun addInlineElement(
            offset: Int,
            relatesToPrecedingText: Boolean,
            presentation: InlayPresentation,
            placeAtTheEndOfLine: Boolean,
        ) {
            offsets.add(offset)
        }

        override fun addInlineElement(
            offset: Int,
            presentation: RootInlayPresentation<*>,
            constraints: HorizontalConstraints?,
        ) {
            offsets.add(offset)
        }

        override fun addBlockElement(
            offset: Int,
            relatesToPrecedingText: Boolean,
            showAbove: Boolean,
            priority: Int,
            presentation: InlayPresentation,
        ) = Unit

        override fun addBlockElement(
            logicalLine: Int,
            showAbove: Boolean,
            presentation: RootInlayPresentation<*>,
            constraints: BlockConstraints?,
        ) = Unit
    }

    /** 配好双锚点（预览文件名 + 语句类型载体），返回 (file, editor)。 */
    private fun configurePreview(sql: String): Pair<PsiFile, Editor> {
        myFixture.configureByText(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, sql)
        val file = myFixture.file
        val editor = myFixture.editor
        PreviewInlayScope.attach(editor, XmlStatementKind.INSERT)
        PreviewInlayScope.attach(file, XmlStatementKind.INSERT)
        return file to editor
    }

    /** 取一个新 collector（模拟平台逐 pass 新建）并收集一次，返回投递的 offset 列表。 */
    private fun collectOnce(file: PsiFile, editor: Editor): List<Int> {
        val sink = CountingSink()
        val collector = provider.getCollectorFor(file, editor, NoSettings(), sink)
            ?: error("预览文件名 + INSERT 载体下 provider 必须给出 collector（作用域闸门回归）")
        collector.collect(file, editor, sink)
        return sink.offsets.toList()
    }

    // ------------------------------------------------------------------
    // 1. 并发分片下只投递一次（先红后绿主用例）
    // ------------------------------------------------------------------

    fun test并发分片下同一collector只投递一次() {
        val (file, editor) = configurePreview(insertSql)
        val expected = 2 // INSERT INTO user (id, name) VALUES (1, 'admin') → 两条提示

        val collectorRef = AtomicReference<InlayHintsCollector?>(null)
        val sinkRef = AtomicReference<CountingSink>(CountingSink())
        val startGen = AtomicInteger(0)
        val doneCount = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)

        val workers = (0 until raceThreads).map { idx ->
            Thread({
                try {
                    ReadAction.compute<Unit, RuntimeException> {
                        for (round in 0 until raceRounds) {
                            while (startGen.get() <= round) {
                                // 自旋等待本轮放行：比 park/unpark 释放更集中，最大化并发窗口
                                if (failure.get() != null) return@compute
                            }
                            val collector = collectorRef.get()
                            val sink = sinkRef.get()
                            if (collector != null) {
                                collector.collect(file, editor, sink)
                            }
                            doneCount.incrementAndGet()
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }, "inlay-race-$idx").apply { isDaemon = true; start() }
        }

        var racedRounds = 0
        var totalDelivered = 0
        var maxDelivered = 0
        try {
            for (round in 0 until raceRounds) {
                sinkRef.set(CountingSink())
                collectorRef.set(
                    provider.getCollectorFor(file, editor, NoSettings(), sinkRef.get())
                        ?: error("预览文件名 + INSERT 载体下 provider 必须给出 collector"),
                )
                startGen.set(round + 1)
                val target = (round + 1) * raceThreads
                while (doneCount.get() < target) {
                    failure.get()?.let { throw AssertionError("并发线程异常", it) }
                }
                val delivered = sinkRef.get().offsets.size
                totalDelivered += delivered
                if (delivered > maxDelivered) maxDelivered = delivered
                if (delivered > expected) racedRounds++
            }
        } finally {
            startGen.set(raceRounds + 1)
            workers.forEach { it.join(5_000) }
        }
        failure.get()?.let { throw AssertionError("并发线程异常", it) }

        assertEquals(
            "闸门必须是原子的：同一 collector 在并发分片下只能投递一轮提示。" +
                "竞态复现读数 $racedRounds/$raceRounds 轮超额（最大单轮投递 $maxDelivered，" +
                "总投递 $totalDelivered / 期望 ${expected * raceRounds}）——普通 var 形态下该读数应 >0",
            0,
            racedRounds,
        )
        assertEquals("总投递数应恰为「每轮规划条数 × 轮数」", expected * raceRounds, totalDelivered)
    }

    // ------------------------------------------------------------------
    // 2. stamp 未变不重算（规划调用计数探针）
    // ------------------------------------------------------------------

    fun test同一文档stamp未变时不重算规划() {
        InsertColumnInlayPlanCache.clearForTest()
        val (file, editor) = configurePreview(insertSql)

        val base = InsertColumnInlayPlanner.planInvocationCount
        val first = collectOnce(file, editor)
        assertEquals("首次调用（空槽）必须真跑规划", base + 1, InsertColumnInlayPlanner.planInvocationCount)
        assertEquals("首次投递两条目标列提示", 2, first.size)

        val afterFirst = InsertColumnInlayPlanner.planInvocationCount
        // 平台每个 highlighting pass 都新建 collector，故「第二次」用新 collector 表达跨 pass 复用
        val second = collectOnce(file, editor)
        assertEquals(
            "stamp 未变 → 跨 collector（跨 pass）第二次不得重跑规划（缓存命中）",
            afterFirst,
            InsertColumnInlayPlanner.planInvocationCount,
        )
        assertEquals("缓存命中复用的结果须与首次逐条一致", first, second)
    }

    fun test不同文档不共享缓存() {
        InsertColumnInlayPlanCache.clearForTest()
        val (fileA, editorA) = configurePreview(insertSql)
        val hintsA = collectOnce(fileA, editorA)

        // 另一个文档（不同文本 → 不同条数）：缓存不得把 A 的结果搬到 B 上
        val (fileB, editorB) = configurePreview("INSERT INTO t (a, b, c) VALUES (1, 2, 3)")
        val hintsB = collectOnce(fileB, editorB)
        assertEquals("第二个文档必须重新规划（文档身份不匹配）", 3, hintsB.size)
        assertEquals("第一个文档的读数不受影响", 2, hintsA.size)
    }

    // ------------------------------------------------------------------
    // 3. stamp 变更后结果正确
    // ------------------------------------------------------------------

    fun test文档改写后重新规划且结果随新文本() {
        InsertColumnInlayPlanCache.clearForTest()
        val (file, editor) = configurePreview("INSERT INTO user (id, name) VALUES (1, 'a')")
        assertEquals("改写前两条提示", 2, collectOnce(file, editor).size)

        val rewritten = "INSERT INTO t (a, b, c) VALUES (1, 2, 3)"
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText(rewritten)
        }
        val after = collectOnce(file, editor)
        assertEquals(
            "stamp 变更 → 必须重规划：新文本 3 列 3 值 → 3 条提示（复用旧结果会得到 2 条）",
            3,
            after.size,
        )

        val countAfterRewrite = InsertColumnInlayPlanner.planInvocationCount
        collectOnce(file, editor)
        assertEquals(
            "改写后第二次收集再次命中缓存（同一新文本、stamp 未变）",
            countAfterRewrite,
            InsertColumnInlayPlanner.planInvocationCount,
        )
    }

    // ------------------------------------------------------------------
    // 4. 越界防御 + 既有提示文案回归
    // ------------------------------------------------------------------

    fun test全部投递位点落在文档范围内() {
        // 越界条目会被静默跳过（平台 addInlineElement 越界即硬断言，显示层功能不得升级成异常）。
        // 本例覆盖含截断/未闭合形态的语料，断言投递位点一律在 [0, textLength]。
        //
        // **判别力补齐（独立复核 minor）**：原先只断言「每个位点都落在范围内」——若规划器退化为
        // 「任何输入都零提示」（正是本防御要兜的场景），offsets 为空、循环体不执行、用例照样绿。
        // 故对**已知良好**的首条语料额外断言「投递非空」，把「退化即红」补上。
        for ((i, sql) in listOf(
            insertSql,
            "INSERT INTO user (id, name) VALUES (1, 'admin'), (2, 'root')",
            "INSERT INTO user (id, name) SELECT x, y FROM s",
            "INSERT INTO user (id, name) VALUES (1, 'admin'", // 未闭合 → 规划器放弃，零提示
            "INSERT INTO user (id, name) SELECT",
        ).withIndex()) {
            InsertColumnInlayPlanCache.clearForTest()
            val (file, editor) = configurePreview(sql)
            val offsets = collectOnce(file, editor)
            val length = editor.document.textLength
            if (i == 0) {
                assertTrue(
                    "已知良好的 INSERT 必须产出至少一条插入列提示（零提示＝规划器已退化，本用例必须变红）",
                    offsets.isNotEmpty(),
                )
            }
            for (offset in offsets) {
                assertTrue(
                    "投递位点 $offset 必须落在 [0, $length] 内（sql=$sql）",
                    offset in 0..length,
                )
            }
        }
    }

    fun test提示文案与渲染链逐字节回归() {
        // 走真实渲染链（InlayHintsSinkImpl + CollectorWithSettings.collectTraversingAndApply + InlayDumpUtil）
        // 断言渲染文本不变——闸门/缓存改动只应省重复计算，不得改渲染。
        InsertColumnInlayPlanCache.clearForTest()
        myFixture.configureByText(PreviewInlayScope.PREVIEW_FILE_NAME_SAMPLE, insertSql)
        val file = myFixture.file
        val editor = myFixture.editor
        PreviewInlayScope.attach(editor, XmlStatementKind.INSERT)
        PreviewInlayScope.attach(file, XmlStatementKind.INSERT)

        val sink = InlayHintsSinkImpl(editor)
        val collector = provider.getCollectorFor(file, editor, NoSettings(), sink)!!
        CollectorWithSettings(collector, provider.key, file.language, sink)
            .collectTraversingAndApply(editor, file, true)
        assertEquals(
            "闸门/缓存改动后渲染文本必须逐字节不变",
            "INSERT INTO user (id, name) VALUES (/*<# id #>*/1, /*<# name #>*/'admin')",
            InlayDumpUtil.dumpHintsInternal(
                insertSql,
                file = file,
                editor = editor,
                document = myFixture.getDocument(file),
            ),
        )
    }
}
