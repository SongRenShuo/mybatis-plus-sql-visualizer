package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 执行临时文件构建的 **EDT 慢操作**守卫（T-ExecSlowOps-BP，2026-11-04 回 port dev `ba314f42`）。
 *
 * 缺陷实证（master 包 `v2026.11.4-beta.1` 真机，`idea.log` 一轮 36 条）：
 * ```
 * SqlPreviewDialog.<init> → showResult → prepareDefaultConsole
 *   → SqlExecutor.prepareConsole → DataSourceSelector.prepareConsole
 *     → chooseAndPrepare → createTempPsiFile
 *       → ReadAction.compute { PsiManager.findFile(vf) }
 *         → FileManagerImpl.findDirectory → ProjectFileIndexImpl.isUnderIgnored
 *           → WorkspaceFileIndexDataImpl.ensureIsUpToDate      ← 慢操作
 * ```
 * `ReadAction` 只保证读语义、不改变线程，PSI 首次实例化实际跑在 EDT。执行 SQL / 执行计划 /
 * 回滚快照三路共用 `prepareConsole → chooseAndPrepare → createTempPsiFile`，冷路径三路皆可达。
 *
 * ## 规则
 *
 * 1. `chooseAndPrepare` **不得直接调用** `createTempPsiFile`——必须经 `createTempPsiFileAsync`
 *    派发（这是本券修复本体；改回同步调用即「EDT 上首次实例化 PSI」）；
 * 2. `createTempPsiFileAsync` 必须齐备三段式（`isDispatchThread` 分流 / `executeOnPooledThread`
 *    池线程 / `invokeLater` 回 EDT）且模态为 `ModalityState.nonModal()`——`any()` 恒 write-unsafe，
 *    续接链会写文档（`ExecWriteSafeDispatchGuardTest` 同口径）；
 * 3. 回调必须经 `revalidateOnEdt`（异步窗口内 PSI 可能失效，直接消费会硬抛
 *    `PsiInvalidElementAccessException`）；
 * 4. `revalidateOnEdt` 的两处 PSI 判定必须落在 `ReadAction` 内（UI-002：EDT 隐式读权限不可依赖）；
 * 5. `createTempPsiFile` 的 refresh 段必须按线程分流（`isDispatchThread`）——池线程上包 WIRA ×
 *    同步 refresh 会自锁（T-PrewarmWilDeadlock）；
 * 6. `continuePrepareAfterTempFile` 必须保留建链失败收口（`failPrepareRequests`）与迟到续接守卫
 *    （`isPrepareActive`）——异步化引入了「临时文件就绪时门已易主」的新竞态面。
 *
 * 为什么是形态守卫：慢操作断言只在真实应用 EDT 上生效，headless 测试线程测不出「PSI 首次实例化
 * 跑在哪个线程」；投递模态同理只能按源码形态钉（`ExecWriteSafeDispatchGuardTest` 类注释口径）。
 */
class ExecTempFileEdtGuardTest {

    private val resultsPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt"

    /**
     * 判定内核（VIOLATIONS）：EDT 入口派发形态 + 慢段线程纪律 + 异步续接收口。
     *
     * 输入为 `DataSourceSelectorResults.kt` 源码；函数体按名字定位（大括号配平），定位失败即
     * 点名（改名/搬家后守卫必须同步跟进，不得静默空转）。
     */
    internal fun tempPsiEdtOffenders(source: String): List<String> {
        val src = stripComments(source)
        val out = ArrayList<String>()

        val choose = bodyOf(src, "chooseAndPrepare")
        if (choose == null) {
            out += "未定位到 chooseAndPrepare 函数体（入口改名/搬家后守卫须同步跟进）"
        } else {
            if (choose.contains("createTempPsiFile(")) {
                out += "chooseAndPrepare 直接调用 createTempPsiFile——EDT 上首次实例化 PSI，" +
                    "平台 SlowOperations SEVERE（真机 36 条）；必须经 createTempPsiFileAsync 派发"
            }
            if (!choose.contains("createTempPsiFileAsync(")) {
                out += "chooseAndPrepare 未见 createTempPsiFileAsync 派发（异步化被整体回退？）"
            }
        }

        val async = bodyOf(src, "createTempPsiFileAsync")
        if (async == null) {
            out += "未定位到 createTempPsiFileAsync 函数体（EDT 侧唯一派发入口消失）"
        } else {
            if (!async.contains("isDispatchThread")) {
                out += "createTempPsiFileAsync 缺 isDispatchThread 判定（非 EDT 调用方无法分流）"
            }
            if (!async.contains("executeOnPooledThread")) {
                out += "createTempPsiFileAsync 缺 executeOnPooledThread（慢段没离开 EDT）"
            }
            if (!async.contains("invokeLater(")) {
                out += "createTempPsiFileAsync 缺 invokeLater（结果没回 EDT，续接链会越线程做 UI）"
            }
            if (!async.contains("ModalityState.nonModal()")) {
                out += "createTempPsiFileAsync 续接投递不是 ModalityState.nonModal()——" +
                    "续接链会写文档，any()/默认模态必触发 TransactionGuard Write-unsafe"
            }
            if (async.contains("ModalityState.any()")) {
                out += "createTempPsiFileAsync 用 ModalityState.any() 投递（any() 恒 write-unsafe）"
            }
            if (!async.contains("revalidateOnEdt(")) {
                out += "createTempPsiFileAsync 回调未经 revalidateOnEdt——异步窗口内 PSI 失效会硬抛" +
                    "PsiInvalidElementAccessException"
            }
        }

        val revalidate = bodyOf(src, "revalidateOnEdt")
        if (revalidate == null) {
            out += "未定位到 revalidateOnEdt 函数体（PSI 失效兜底消失）"
        } else {
            val readLocks = Regex("""ReadAction\.compute""").findAll(revalidate).count()
            if (readLocks < 2) {
                out += "revalidateOnEdt 的 PSI 判定未全部包 ReadAction（实查 $readLocks 处，" +
                    "UI-002：EDT 隐式读权限不可依赖）"
            }
            if (!revalidate.contains("isValid")) {
                out += "revalidateOnEdt 不再判 PSI 有效性（兜底空转）"
            }
        }

        val slow = bodyOf(src, "createTempPsiFile")
        if (slow == null) {
            out += "未定位到 createTempPsiFile 函数体（慢段本体消失）"
        } else {
            if (!slow.contains("isDispatchThread")) {
                out += "createTempPsiFile 的 refresh 段未按线程分流——池线程上包 WIRA × 同步 refresh " +
                    "会自锁（T-PrewarmWilDeadlock，全 IDE not responding）"
            }
            if (!slow.contains("refreshAndFindFileByIoFile")) {
                out += "createTempPsiFile 不再 refresh VFS（慢段形态变更，须重新评审本守卫）"
            }
        }

        val continuation = bodyOf(src, "continuePrepareAfterTempFile")
        if (continuation == null) {
            out += "未定位到 continuePrepareAfterTempFile 函数体（异步续接链消失）"
        } else {
            if (continuation.contains("createTempPsiFile(")) {
                out += "continuePrepareAfterTempFile 内又直接调用 createTempPsiFile（续接在 EDT 上）"
            }
            if (!continuation.contains("failPrepareRequests(")) {
                out += "continuePrepareAfterTempFile 丢失建链失败收口 failPrepareRequests"
            }
            if (!continuation.contains("isPrepareActive(")) {
                out += "continuePrepareAfterTempFile 丢失迟到续接守卫 isPrepareActive" +
                    "（异步窗口内门已易主仍会建第二条会话/控制台）"
            }
        }
        return out
    }

    @Test
    fun `执行临时文件构建必须离开EDT且续接收口齐备`() {
        val offenders = tempPsiEdtOffenders(read(resultsPath))
        assertTrue(
            "执行临时文件构建的 EDT 慢操作纪律被破坏（真机一轮 36 条 SlowOperations，" +
                "栈在 chooseAndPrepare → createTempPsiFile → PsiManager.findFile）。" +
                "违规 ${offenders.size} 处：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 自证：同步直调必红、any() 投递必红、缺线程分流必红、丢收口必红，合规形态不误报。 */
    @Test
    fun `自证_同步直调与any投递与缺分流必被点名`() {
        val syncCall = """
            internal fun DataSourceSelector.chooseAndPrepare(project: Project) {
                val temp = createTempPsiFile(project, sql, null)
            }
            internal fun DataSourceSelector.createTempPsiFileAsync(project: Project, sql: String, onReady: () -> Unit) {
                val app = ApplicationManager.getApplication()
                if (!app.isDispatchThread) {
                    app.invokeLater({ onReady() }, ModalityState.nonModal())
                    return
                }
                app.executeOnPooledThread {
                    app.invokeLater({ onReady(revalidateOnEdt(project, null)) }, ModalityState.nonModal())
                }
            }
            internal fun revalidateOnEdt(project: Project, pair: Any?): Any? {
                ReadAction.compute<Boolean, Throwable> { psi.isValid }
                ReadAction.compute<Any?, Throwable> { null }
                return pair
            }
            internal fun DataSourceSelector.createTempPsiFile(project: Project, sql: String): Any? {
                if (ApplicationManager.getApplication().isDispatchThread) {
                    WriteIntentReadAction.compute<Any?> { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f) }
                } else {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
                }
                return null
            }
            internal fun DataSourceSelector.continuePrepareAfterTempFile(temp: Any?) {
                if (temp == null) failPrepareRequests(null, null, null, null, null, "x")
                if (!isPrepareActive()) return
            }
        """.trimIndent()
        val syncOffenders = tempPsiEdtOffenders(syncCall)
        assertTrue(
            "chooseAndPrepare 同步直调 createTempPsiFile 必须被点名：$syncOffenders",
            syncOffenders.any { it.contains("直接调用 createTempPsiFile") },
        )

        val anyModality = syncCall.replace(
            "app.invokeLater({ onReady() }, ModalityState.nonModal())",
            "app.invokeLater({ onReady() }, ModalityState.any())",
        ).replace("val temp = createTempPsiFile(project, sql, null)", "createTempPsiFileAsync(project, sql) { }")
        val anyOffenders = tempPsiEdtOffenders(anyModality)
        assertTrue(
            "any() 投递必须被点名：$anyOffenders",
            anyOffenders.any { it.contains("any()") },
        )

        val noBranch = syncCall.replace(
            "if (ApplicationManager.getApplication().isDispatchThread) {",
            "if (true) {",
        ).replace("val temp = createTempPsiFile(project, sql, null)", "createTempPsiFileAsync(project, sql) { }")
        val branchOffenders = tempPsiEdtOffenders(noBranch)
        assertTrue(
            "refresh 段缺线程分流必须被点名：$branchOffenders",
            branchOffenders.any { it.contains("未按线程分流") },
        )

        val noFailsafe = syncCall
            .replace("val temp = createTempPsiFile(project, sql, null)", "createTempPsiFileAsync(project, sql) { }")
            .replace("if (temp == null) failPrepareRequests(null, null, null, null, null, \"x\")", "")
            .replace("if (!isPrepareActive()) return", "")
        val failsafeOffenders = tempPsiEdtOffenders(noFailsafe)
        assertTrue(
            "续接链丢失败收口必须被点名：$failsafeOffenders",
            failsafeOffenders.any { it.contains("failPrepareRequests") },
        )
        assertTrue(
            "续接链丢迟到守卫必须被点名：$failsafeOffenders",
            failsafeOffenders.any { it.contains("isPrepareActive") },
        )

        val good = syncCall.replace(
            "val temp = createTempPsiFile(project, sql, null)",
            "createTempPsiFileAsync(project, sql) { temp -> continuePrepareAfterTempFile(temp) }",
        )
        assertEquals(
            "合规形态不应误报：${tempPsiEdtOffenders(good)}",
            0,
            tempPsiEdtOffenders(good).size,
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun read(path: String): String {
        val f = File(path)
        assertTrue("守卫目标文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 剥注释（块注释涂白保留换行，行注释截断）——判定只看代码，KDoc 里「提」禁词不算违规。 */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    /** 函数声明点：`fun [接收者.]name(` 里 `(` 的下标；普通调用点不命中。 */
    private fun declarationAt(src: String, fn: String): Int? {
        var at = src.indexOf("$fn(")
        while (at > 0) {
            var back = at
            while (back > 0 && (src[back - 1].isLetterOrDigit() || src[back - 1] == '.' || src[back - 1] == '_')) {
                back--
            }
            var s = back
            while (s > 0 && src[s - 1].isWhitespace()) s--
            if (s >= 3 && src.regionMatches(s - 3, "fun", 0, 3) &&
                (s - 4 < 0 || !(src[s - 4].isLetterOrDigit() || src[s - 4] == '_'))
            ) {
                return at
            }
            at = src.indexOf("$fn(", at + fn.length + 1)
        }
        return null
    }

    /** 函数体区间：从声明后的首个 `{` 起按大括号配平（非固定长度窗口，避免跨界读到邻居函数）。 */
    private fun bodyRange(src: String, declAt: Int): IntRange? {
        val open = src.indexOf('{', declAt)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return open..i
                }
            }
            i++
        }
        return null
    }

    private fun bodyOf(src: String, fn: String): String? =
        declarationAt(src, fn)?.let { bodyRange(src, it) }?.let { src.substring(it.first, it.last + 1) }
}
