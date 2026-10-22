package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-FirstExecResultInvisible 单测（纯逻辑 + 接线守卫，计划 §四 S3）。
 *
 * 三组覆盖：
 *  1. [PrepareSingleFlightGate] 单飞 + 排队（F1）：在飞拒绝第二链、排队意图一次性消费、
 *     预览直通不取所有权、卡死逃生；
 *  2. [ConsoleEmbedMode] 折叠判定（F3）：EXECUTE 恒展开，PREVIEW 仅结果区无内容时折叠；
 *  3. 接线守卫（源码扫描，照 [ErrorPositionWiringGuardTest] 先例）：面板 8 个 embedConsole
 *     调用点模式显式化、DS 两处折叠点走 shouldCollapseDivider、F1 消费点接线在位。
 *
 * 与 UI/DBN 链无耦合（gate/枚举纯 JDK），默认 test 任务可跑（chooseAndPrepare/
 * executeOnConsole 不可达同款口径，见 MysqlClientGSuffixSubmitChainTest 头注）。
 */
class PrepareSingleFlightGateTest {

    // ------------------------------------------------------------------
    // 第一组：F1 单飞 + 排队
    // ------------------------------------------------------------------

    private fun intent(tag: String = "i") = QueuedExecuteIntent(
        onConsole = null,
        onEditor = null,
        onError = { msg -> "[$tag] $msg" },
        onErrorRich = null,
        wholeScriptExec = false,
    )

    @Test
    fun `单飞_空闲时链持有门`() {
        val gate = PrepareSingleFlightGate()
        val owner = intent("owner")
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, intent = owner, nowMs = 0))
        gate.finishPrepare(owner)
        assertTrue("复位后门应空闲", !gate.prepareInFlight.get())
    }

    @Test
    fun `单飞_在飞时执行意图排队不再进第二链`() {
        val gate = PrepareSingleFlightGate()
        val owner = intent("owner")
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, intent = owner, nowMs = 0))
        // 准备链（预览）在飞期间用户点「执行 SQL」→ 只排队，不建第二条临时文件/会话/控制台
        val queued = intent("exec")
        val decision = gate.tryEnter(executeAfterPrepare = true, intent = queued, nowMs = 10)
        assertEquals(PrepareGateDecision.QUEUE_ONLY, decision)
        assertTrue("排队标记应在位", gate.pendingExecute.get())
        // 完成准备时消费：拿到的是排队时那个意图，且一次性
        assertSame(queued, gate.consumePendingExecute(owner))
        assertNull("意图只能消费一次", gate.consumePendingExecute(owner))
        assertFalse("消费后标记应复位", gate.pendingExecute.get())
    }

    @Test
    fun `排队_同窗口连点后到意图覆盖先到`() {
        val gate = PrepareSingleFlightGate()
        val owner = intent("owner")
        gate.tryEnter(executeAfterPrepare = false, intent = owner, nowMs = 0)
        gate.tryEnter(executeAfterPrepare = true, intent = intent("first"), nowMs = 10)
        val second = intent("second")
        assertEquals(PrepareGateDecision.QUEUE_ONLY, gate.tryEnter(executeAfterPrepare = true, intent = second, nowMs = 20))
        assertSame("最新用户意图优先", second, gate.consumePendingExecute(owner))
    }

    @Test
    fun `预览在飞时直通不排队不取所有权`() {
        val gate = PrepareSingleFlightGate()
        val owner = intent("owner")
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, intent = owner, nowMs = 0))
        // 另一条预览准备到达：PASS_THROUGH——不拦截（拦截会丢切换预览条目的 SQL 同步）、
        // 不取所有权、不置排队
        assertEquals(PrepareGateDecision.PASS_THROUGH, gate.tryEnter(executeAfterPrepare = false, nowMs = 10))
        assertFalse(gate.pendingExecute.get())
        assertNull(gate.consumePendingExecute(owner))
        // 直通链不得动门：finishPrepare 只归持有链（此处模拟持有链仍在飞）
        assertTrue(gate.prepareInFlight.get())
    }

    @Test
    fun `finishPrepare_复位后新链可重新持有`() {
        val gate = PrepareSingleFlightGate()
        val owner = intent("owner")
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = true, intent = owner, nowMs = 0))
        gate.finishPrepare(owner)
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = true, intent = intent("next"), nowMs = 10))
    }

    @Test
    fun `卡死逃生_悬挂超窗后放行新链并清陈旧排队`() {
        val gate = PrepareSingleFlightGate()
        val owner = intent("owner")
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, intent = owner, nowMs = 0))
        gate.tryEnter(executeAfterPrepare = true, intent = intent("stale"), nowMs = 10)
        // 门持有链消亡未复位（runner 失败无 session 回调族）——窗口过后新执行链接管自愈
        val takeover = intent("fresh")
        assertEquals(
            PrepareGateDecision.PROCEED,
            gate.tryEnter(executeAfterPrepare = true, intent = takeover, nowMs = PrepareSingleFlightGate.STUCK_ESCAPE_MS + 1),
        )
        assertNull("陈旧排队意图应被清空（旧链已死，消费无意义）", gate.consumePendingExecute(owner))
        // 接管后终结复位照常
        assertTrue(gate.finishPrepare(takeover))
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(executeAfterPrepare = false, nowMs = PrepareSingleFlightGate.STUCK_ESCAPE_MS + 2))
    }

    private class RequestProbe {
        val outcomes = mutableListOf<ExecutionSubmission>()
        val errors = mutableListOf<String>()
        val request = QueuedExecuteIntent(null, null, { errors += it }, null, false,
            submissionSink = { outcomes += it })
    }

    @Test
    fun `取消准备A同时结算排队B且下一链无遗留`() {
        val gate = PrepareSingleFlightGate()
        val a = RequestProbe()
        val owner = a.request
        val b = RequestProbe()
        gate.tryEnter(false, a.request, 0)
        gate.tryEnter(true, b.request, 10)
        gate.abort(owner, ExecutionSubmission.CANCELLED)
        assertEquals(listOf(ExecutionSubmission.CANCELLED), a.outcomes)
        assertEquals(listOf(ExecutionSubmission.CANCELLED), b.outcomes)
        assertFalse(gate.pendingExecute.get())
        val next = RequestProbe()
        assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(true, next.request, 20))
        assertNull(gate.consumePendingExecute(owner))
        assertTrue(gate.isOwner(next.request))
        assertTrue(next.outcomes.isEmpty())
    }

    @Test
    fun `ABC覆盖只最新请求可提交且当前A明确取消`() {
        val gate = PrepareSingleFlightGate()
        val a = RequestProbe()
        val owner = a.request
        val b = RequestProbe()
        val c = RequestProbe()
        gate.tryEnter(true, a.request, 0)
        gate.tryEnter(true, b.request, 10)
        gate.tryEnter(true, c.request, 20)
        assertEquals(listOf(ExecutionSubmission.CANCELLED), b.outcomes)
        val queued = gate.consumePendingExecute(owner)
        assertSame(c.request, queued)
        val receipt = selectPrepareSubmission(queued, a.request.onSubmission)
        assertEquals(listOf(ExecutionSubmission.CANCELLED), a.outcomes)
        assertTrue(c.outcomes.isEmpty())
        receipt!!.invoke(ExecutionSubmission.SUBMITTED)
        gate.finishPrepare(owner)
        // 迟到回执不得翻转被替代请求，也不能产生第二个终态。
        a.request.onSubmission(ExecutionSubmission.SUBMITTED)
        b.request.onSubmission(ExecutionSubmission.SUBMITTED)
        c.request.onSubmission(ExecutionSubmission.BLOCKED)
        assertEquals(listOf(ExecutionSubmission.CANCELLED), a.outcomes)
        assertEquals(listOf(ExecutionSubmission.CANCELLED), b.outcomes)
        assertEquals(listOf(ExecutionSubmission.SUBMITTED), c.outcomes)
    }

    @Test
    fun `超时接管结算AB且旧定时器不清C`() {
        val gate = PrepareSingleFlightGate()
        val a = RequestProbe()
        val owner = a.request
        val b = RequestProbe()
        val c = RequestProbe()
        gate.tryEnter(true, a.request, 0)
        gate.tryEnter(true, b.request, 10)
        gate.tryEnter(true, c.request, PrepareSingleFlightGate.STUCK_ESCAPE_MS + 1)
        assertEquals(listOf(ExecutionSubmission.BLOCKED), a.outcomes)
        assertEquals(listOf(ExecutionSubmission.BLOCKED), b.outcomes)
        gate.expire(a.request, 60_001)
        gate.expire(b.request)
        assertTrue(gate.isOwner(c.request))
        assertTrue(c.outcomes.isEmpty())
        assertNull(gate.consumePendingExecute(owner))
    }

    @Test
    fun `无下一次点击也能超时结算准备与排队`() {
        val gate = PrepareSingleFlightGate()
        val a = RequestProbe()
        val owner = a.request
        val b = RequestProbe()
        gate.tryEnter(false, a.request, 0)
        gate.tryEnter(true, b.request, 10)
        gate.expire(a.request, 60_001)
        gate.expire(a.request, 60_001)
        assertEquals(listOf(ExecutionSubmission.BLOCKED), a.outcomes)
        assertEquals(listOf(ExecutionSubmission.BLOCKED), b.outcomes)
        assertFalse(gate.prepareInFlight.get())
        assertNull(gate.consumePendingExecute(owner))
    }

    @Test
    fun `缺scriptModel在消费前结算当前与排队`() = assertPrepareFailure(consumed = false, "scriptModel")

    @Test
    fun `缺editorComponent在消费后结算当前与排队`() = assertPrepareFailure(consumed = true, "editorComponent")

    private fun assertPrepareFailure(consumed: Boolean, message: String) {
        val gate = PrepareSingleFlightGate()
        val a = RequestProbe()
        val owner = a.request
        val b = RequestProbe()
        gate.tryEnter(true, a.request, 0)
        gate.tryEnter(true, b.request, 10)
        val pending = if (consumed) gate.consumePendingExecute(owner) else null
        failPrepareRequests(gate, owner, a.request.onSubmission, a.request.onError, pending, message)
        assertEquals(listOf(ExecutionSubmission.BLOCKED), a.outcomes)
        assertEquals(listOf(ExecutionSubmission.BLOCKED), b.outcomes)
        assertEquals(listOf(message), a.errors)
        assertEquals(listOf(message), b.errors)
        assertFalse(gate.prepareInFlight.get())
        assertNull(gate.consumePendingExecute(owner))
    }

    @Test
    fun `提交确认已接管时准备超时不伪报取消`() {
        val gate = PrepareSingleFlightGate()
        val a = RequestProbe()
        val owner = a.request
        gate.tryEnter(true, a.request, 0)
        gate.beginSubmission(owner)
        gate.expire(a.request, 60_001)
        assertTrue(gate.isOwner(a.request))
        assertTrue(a.outcomes.isEmpty())
        a.request.onSubmission(ExecutionSubmission.CANCELLED)
        gate.finishPrepare(owner)
        assertEquals(listOf(ExecutionSubmission.CANCELLED), a.outcomes)
    }

    @Test
    fun `缺准备资源的直通请求也有终态`() {
        val gate = PrepareSingleFlightGate()
        val a = RequestProbe()
        val owner = a.request
        failPrepareRequests(gate, null, a.request.onSubmission, a.request.onError, null, "missing")
        assertEquals(listOf(ExecutionSubmission.BLOCKED), a.outcomes)
        assertEquals(listOf("missing"), a.errors)
    }

    @Test
    fun `A超时B接管后A后台失败和正常收尾不动B及其队列`() {
        for (lateFailure in listOf(true, false)) {
            val gate = PrepareSingleFlightGate()
            val a = RequestProbe()
            val b = RequestProbe()
            val queuedB = RequestProbe()
            gate.tryEnter(true, a.request, 0)
            // 控制时序，不 sleep：A 尚在后台等待，超时后 B 已取得门。
            gate.expire(a.request, 60_001)
            assertEquals(PrepareGateDecision.PROCEED, gate.tryEnter(true, b.request, 60_001))
            gate.tryEnter(true, queuedB.request, 60_002)
            if (lateFailure) {
                // 对应 prepareInConsole 的同步等待失败/异常出口及后段异常出口。
                assertFalse(gate.abort(a.request, ExecutionSubmission.BLOCKED, "late A failure"))
                failPrepareRequests(gate, a.request, a.request.onSubmission, a.request.onError, null, "late A failure")
            }
            // 对应 prepareAfterEditorResolved 的正常收尾；失败后也可能再次到达。
            assertFalse(gate.finishPrepare(a.request))
            assertFalse(gate.beginSubmission(a.request))
            assertNull(gate.consumePendingExecute(a.request))
            assertTrue(gate.isOwner(b.request))
            assertTrue(gate.pendingExecute.get())
            assertSame(queuedB.request, gate.pendingIntent)
            assertTrue(b.outcomes.isEmpty())
            assertTrue(b.errors.isEmpty())
            assertTrue(queuedB.outcomes.isEmpty())
            assertEquals(listOf(ExecutionSubmission.BLOCKED), a.outcomes)
            assertEquals(1, a.errors.size) // 仅后台超时原因，迟到 A 失败不覆写
            // B 自身正常消费、提交和收尾照常，不被旧 A 的 false 返回污染。
            assertSame(queuedB.request, gate.consumePendingExecute(b.request))
            selectPrepareSubmission(queuedB.request, b.request.onSubmission)!!.invoke(ExecutionSubmission.SUBMITTED)
            assertTrue(gate.finishPrepare(b.request))
            assertFalse(gate.prepareInFlight.get())
            assertFalse(gate.pendingExecute.get())
            assertEquals(listOf(ExecutionSubmission.CANCELLED), b.outcomes)
            assertEquals(listOf(ExecutionSubmission.SUBMITTED), queuedB.outcomes)
        }
    }

    @Test
    fun `A迟到失败不妨碍B自身失败结算`() {
        val gate = PrepareSingleFlightGate()
        val a = RequestProbe()
        val b = RequestProbe()
        gate.tryEnter(true, a.request, 0)
        gate.tryEnter(true, b.request, PrepareSingleFlightGate.STUCK_ESCAPE_MS + 1)
        assertFalse(gate.abort(a.request, ExecutionSubmission.BLOCKED, "A"))
        assertTrue(gate.abort(b.request, ExecutionSubmission.BLOCKED, "B"))
        assertEquals(listOf(ExecutionSubmission.BLOCKED), b.outcomes)
        assertEquals(listOf("B"), b.errors)
        assertFalse(gate.prepareInFlight.get())
    }

    @Test
    fun `用户选择超过一分钟仍有效且B排队不能超时接管A`() {
        var now = 0L
        val gate = PrepareSingleFlightGate(clock = { now }, timeoutMessage = { "background timeout" })
        val a = RequestProbe()
        val b = RequestProbe()
        gate.tryEnter(true, a.request)
        gate.beginInteraction(a.request)
        now = 120_000
        assertFalse("手动触发旧timer不能杀用户选择", gate.expire(a.request))
        assertEquals(PrepareGateDecision.QUEUE_ONLY, gate.tryEnter(true, b.request))
        assertTrue(gate.isOwner(a.request))
        assertTrue(a.outcomes.isEmpty())
        assertTrue(b.outcomes.isEmpty())
        gate.endInteraction(a.request)
        assertFalse("离开交互后到达的旧tick不能沿用旧预算", gate.expire(a.request))
        now += 59_999
        assertFalse(gate.expire(a.request))
        assertTrue(gate.isOwner(a.request))
        assertSame(b.request, gate.consumePendingExecute(a.request))
    }

    @Test
    fun `后台仍会到期且消息明确不是主动取消`() {
        var now = 0L
        val gate = PrepareSingleFlightGate(clock = { now }, timeoutMessage = { "background timeout" })
        val a = RequestProbe()
        gate.tryEnter(true, a.request)
        gate.beginInteraction(a.request)
        now = 180_000
        gate.endInteraction(a.request)
        now += 60_000
        assertTrue(gate.expire(a.request))
        assertEquals(listOf(ExecutionSubmission.BLOCKED), a.outcomes)
        assertEquals(listOf("background timeout"), a.errors)
        val b = RequestProbe()
        gate.tryEnter(true, b.request)
        gate.beginInteraction(b.request)
        now += 120_000
        assertTrue(gate.expire(a.request))
        assertTrue(gate.isOwner(b.request))
        assertTrue(b.outcomes.isEmpty())
        assertFalse(gate.expire(b.request))
    }

    @Test
    fun `所有真实准备出口携带owner并在门内校验`() {
        val selector = selectorUnion()
        assertFalse(selector.contains("abortPrepareGate(owns"))
        assertFalse(selector.contains("prepareGate.finishPrepare()"))
        assertFalse(selector.contains("prepareGate.abort(ExecutionSubmission"))
        assertFalse(selector.contains("prepareGate.consumePendingExecute()"))
        assertFalse(selector.contains("prepareGate.beginSubmission()"))
        assertEquals("五个 prepareInConsole 失败出口必须传同一个 owner", 5,
            selector.split("abortPrepareGate(prepareOwner,").size - 1 - 1) // 另一个在 session 方言闸
        assertTrue(selector.contains("onErrorRich, wholeScriptExec, prepareOwner, dialectGateRequest"))
        val gate = sourceOf("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/PrepareSingleFlightGate.kt")
        for (name in listOf("abort", "finishPrepare", "beginSubmission", "consumePendingExecute")) {
            val body = gate.substringAfter("fun $name(").substringBefore("\n    }")
            assertTrue("$name 必须在门内先检查身份", body.contains("if (!matchesOwner(expectedOwner)) return"))
            assertTrue(gate.replace("\r\n", "\n").contains("@Synchronized\n    fun $name("))
        }
    }

    @Test
    fun `真实资源缺失分支和弹窗取消接入终结器`() {
        val selector = selectorUnion()
        assertTrue(selector.contains("if (ownsGate) prepareGate.abort(prepareOwner, ExecutionSubmission.CANCELLED)"))
        assertTrue(selector.contains("prepareGate.expire(prepareOwner)"))
        assertTrue(selector.contains("prepareGate.beginInteraction(prepareOwner)"))
        assertTrue(selector.contains("prepareGate.endInteraction(prepareOwner)"))
        assertTrue(selector.contains("prepareGate.duringInteraction(prepareOwner)"))
        val console = sourceOf("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt")
        for (branch in listOf("if (scriptModel == null)", "if (editorComponentRaw == null)")) {
            val body = console.substringAfter(branch).substringBefore("return")
            assertTrue("$branch 必须结算当前及排队", body.contains("failPrepareRequests("))
            assertTrue(body.contains("onSubmission, onError, queuedIntent"))
        }
        assertTrue(console.contains("selectPrepareSubmission(queuedIntent, onSubmission)"))
        assertTrue(console.contains("if (!isPrepareActive()) return@invokeLater"))
        val signatures = sourceOf("src/test/java/PlatformSignatureChecker.java")
        assertTrue(signatures.contains("missing += checkRunnerFinalStepApi(loader)"))
        assertTrue(signatures.contains("getMethod(\"doFinalStep\", Runnable.class)"))
    }

    // ------------------------------------------------------------------
    // 第二组：F3 折叠判定
    // ------------------------------------------------------------------

    @Test
    fun `embedMode_EXECUTE_恒展开`() {
        assertFalse(ConsoleEmbedMode.EXECUTE.shouldCollapseDivider(resultAreaHasContent = false))
        assertFalse(ConsoleEmbedMode.EXECUTE.shouldCollapseDivider(resultAreaHasContent = true))
    }

    @Test
    fun `embedMode_PREVIEW_无内容折叠`() {
        assertTrue(ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent = false))
    }

    @Test
    fun `embedMode_PREVIEW_有内容不折叠`() {
        // 竞态窗口（执行中快速切换预览条目）内结果已就位 → 不吞用户可见的结果
        assertFalse(ConsoleEmbedMode.PREVIEW.shouldCollapseDivider(resultAreaHasContent = true))
    }

    // ------------------------------------------------------------------
    // 第三组：接线守卫（源码扫描——「实现有、接线漏」单测照不出来，照
    // ErrorPositionWiringGuardTest 先例钉生产调用点）
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun sourceOf(relativePath: String): String =
        File(repoRoot(), relativePath).readText()

    /** T-M53-II（2026-10-10）：组合式重构后 DataSourceSelector 的成员分散到同包三个文件，
     * 形态类断言必须按**全集**读取（只读主文件会因成员搬走而假红，或漏掉真实违规而假绿）。 */
    private fun selectorUnion(): String = listOf(
        "DataSourceSelector.kt",
        "DataSourceSelectorConsole.kt",
        "DataSourceSelectorResults.kt",
    ).joinToString("\n") {
        sourceOf("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/$it")
    }

    /** T-M53-II D2-5：嵌入调用点随执行链搬到同包协作文件，读取面取面板全体源文件并集。 */
    private fun panelUnion(): String {
        val dir = java.io.File(
            java.io.File(System.getProperty("user.dir")).absoluteFile.let { d ->
                var x: java.io.File? = d
                while (x != null && !java.io.File(x, "src/main/kotlin").isDirectory) x = x.parentFile
                x ?: error("未定位到仓库根")
            },
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui",
        )
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("SqlPreviewPanel") && it.extension == "kt" }
            .sortedBy { it.name }
            .joinToString("\n") { it.readText() }
    }

    @Test
    fun `接线_全链执行embed调用点全部显式EXECUTE`() {
        val panel = panelUnion()
        // 五条全链执行链（executeSql 两处 / executeAdhocSql 两处 / EXPLAIN 显式「在控制台打开」一处）。
        // T-PreviewSideQuery P2：EXPLAIN 默认直送改走只读旁路查询，不再经 embedConsole；
        // R42 DML 影响面预演同样改旁路查询——两者都不应再计入全链控制台嵌入点。
        assertEquals(
            5,
            "embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.EXECUTE)".let { needle -> panel.split(needle).size - 1 },
        )
        // executePrepared 的 onResultsReady 两处（executeSql + executeAdhocSql）
        assertEquals(
            2,
            panel.split("embedConsole(fullConsole, embedMode = ConsoleEmbedMode.EXECUTE)").size - 1,
        )
        // 预览准备链唯一入口（prepareDefaultConsole onConsole）
        assertEquals(
            1,
            panel.split("embedConsole(consoleComponent, embedMode = ConsoleEmbedMode.PREVIEW)").size - 1,
        )
        // 旧布尔签名彻底退役（注释除外——源码形态扫描只认实参写法）
        assertFalse(panel.contains("embedConsole(consoleComponent, previewMode = true)"))
        assertFalse(panel.contains("embedConsole(consoleComponent)"))
    }

    @Test
    fun `接线_DS侧折叠点归口shouldCollapseDivider`() {
        val selector = selectorUnion()
        // prepareAfterEditorResolved（Console 侧）+ reuseSharedConsole（Results 侧）两处折叠判定
        assertEquals(
            2,
            selector.split("shouldCollapseDivider(").size - 1,
        )
    }

    @Test
    fun `接线_F1单飞入口与消费点在位`() {
        val selector = selectorUnion()
        // 入口单飞判定（先于共享复用）+ QUEUE_ONLY 只排队 callback(true)
        assertTrue(selector.contains("prepareGate.tryEnter(executeAfterPrepare, queuedIntent)"))
        assertTrue(selector.contains("PrepareGateDecision.QUEUE_ONLY ->"))
        // 消费点：完成准备后按 executePrepared 口径消费（onResultsReady=意图 onConsole）
        assertTrue(selector.contains("queuedIntent = prepareGate.consumePendingExecute(prepareOwner)"))
        assertTrue(selector.contains("onResultsReady = queuedIntent?.onConsole"))
        // 单飞位复位兜底（成功/失败同尽）
        assertTrue(selector.contains("if (prepareOwner != null) prepareGate.finishPrepare(prepareOwner)"))
        // 卡死逃生在 tryEnter 内（门自愈，不在调用方）
        val gate = sourceOf("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/PrepareSingleFlightGate.kt")
        assertTrue(gate.contains("STUCK_ESCAPE_MS"))
    }
}
