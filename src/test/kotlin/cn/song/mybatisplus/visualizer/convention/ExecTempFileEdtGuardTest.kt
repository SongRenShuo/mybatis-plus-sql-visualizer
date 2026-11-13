// 自 master src/test/kotlin/.../convention/ExecTempFileEdtGuardTest.kt 移植（A 件直取 + k2j 适配）
// master 终态 183c98b3d（含 M7「EDT 上校验 PSI」反例用例 511ad990a 增量）。
// k2j 适配：守卫目标为 Java 承载件 DataSourceSelectorResultsKt.java（master 是
// DataSourceSelectorResults.kt）——扫描根 src/main/kotlin/.kt → src/main/java/.java；
// 声明锚点按 k2j Java 方法形态改（Kotlin `fun name(` → Java 修饰符行 + 返回类型 + `name(`）；
// chooseAndPrepare/createTempPsiFile 在 Java 侧为多重载（旧参量委托壳），判定取**全部重载体合并**；
// isPrepareActive 在 Java 侧是 `Supplier<Boolean> isPrepareActive = () ->` + `.get()` 消费形态，
// 收口锚点改裸令牌（master 是 Kotlin 局部函数调用 `isPrepareActive(`）。
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
 * 3. 回调必须经 `revalidate`（异步窗口内 PSI 可能失效，直接消费会硬抛
 *    `PsiInvalidElementAccessException`）；
 * 4. `revalidate` 的两处 PSI 判定必须落在 `ReadAction` 内（UI-002：EDT 隐式读权限不可依赖）；
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
        "src/main/java/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResultsKt.java"

    /**
     * 判定内核（VIOLATIONS）：EDT 入口派发形态 + 慢段线程纪律 + 异步续接收口。
     *
     * 输入为 `DataSourceSelectorResultsKt.java` 源码；函数体按名字定位（大括号配平），定位失败即
     * 点名（改名/搬家后守卫必须同步跟进，不得静默空转）。k2j Java 形态：
     * 声明 = 修饰符行（public/private/protected/static/final/synchronized）+ 返回类型 + `name(`，
     * 同名多重载取全部函数体**合并**判定（委托壳不误伤、真身不漏检——ExplainPanelWiringGuardTest
     * 「锚点须迁到含真身的全参签名」教训的多重载推广形态）。
     */
    internal fun tempPsiEdtOffenders(source: String): List<String> {
        val src = stripComments(source)
        val out = ArrayList<String>()

        val choose = mergedBodyOf(src, "chooseAndPrepare")
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

        val async = mergedBodyOf(src, "createTempPsiFileAsync")
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
            if (!async.contains("revalidate(")) {
                out += "createTempPsiFileAsync 回调未经 revalidate——异步窗口内 PSI 失效会硬抛" +
                    "PsiInvalidElementAccessException"
            } else if (async.indexOf("revalidate(") > async.indexOf("invokeLater(")) {
                out += "revalidate 落在 invokeLater 回调里（EDT 上判 PSI 有效性＝平台慢操作，真机" +
                    " 2026-11-12 11:08:53 被记 Plugin to blame: SQL Visualizer）；必须在后台段完成"
            }
        }

        val revalidate = mergedBodyOf(src, "revalidate")
        if (revalidate == null) {
            out += "未定位到 revalidate 函数体（PSI 失效兜底消失）"
        } else {
            val readLocks = Regex("""ReadAction\.compute""").findAll(revalidate).count()
            if (readLocks < 2) {
                out += "revalidate 的 PSI 判定未全部包 ReadAction（实查 $readLocks 处，" +
                    "UI-002：后台线程也要显式读权限）"
            }
            if (!revalidate.contains("isValid")) {
                out += "revalidate 不再判 PSI 有效性（兜底空转）"
            }
        }

        val slow = mergedBodyOf(src, "createTempPsiFile")
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

        val continuation = mergedBodyOf(src, "continuePrepareAfterTempFile")
        if (continuation == null) {
            out += "未定位到 continuePrepareAfterTempFile 函数体（异步续接链消失）"
        } else {
            if (continuation.contains("createTempPsiFile(")) {
                out += "continuePrepareAfterTempFile 内又直接调用 createTempPsiFile（续接在 EDT 上）"
            }
            if (!continuation.contains("failPrepareRequests(")) {
                out += "continuePrepareAfterTempFile 丢失建链失败收口 failPrepareRequests"
            }
            // k2j 形态：Java 侧 isPrepareActive 是 `Supplier<Boolean> isPrepareActive = () ->` 定义 +
            // `isPrepareActive.get()` 消费（master 是 Kotlin 局部函数 `isPrepareActive(` 调用）——
            // 收口锚点取裸令牌，定义或消费任一在场即认。
            if (!continuation.contains("isPrepareActive")) {
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

    /** 自证：同步直调必红、any() 投递必红、缺线程分流必红、丢收口必红、EDT 上校验必红，合规形态不误报。 */
    @Test
    fun `自证_同步直调与any投递与缺分流必被点名`() {
        // k2j 语料按 Java 方法形态（判定内核的声明锚认修饰符行），令牌判据与 master 同一版语义。
        val syncCall = """
            class DataSourceSelectorSample {
                static void chooseAndPrepare(Project project, String sql) {
                    TempPsiFile temp = createTempPsiFile(project, sql, null);
                }
                static void createTempPsiFileAsync(Project project, String sql, String dialect,
                        java.util.function.BiConsumer<TempPsiFile, String> onReady) {
                    Application app = ApplicationManager.getApplication();
                    class RunSlow {
                        void run(java.util.function.BiConsumer<TempPsiFile, String> report) {
                            TempPsiFile pair = createTempPsiFile(project, sql, dialect);
                            revalidate(project, pair);
                            app.invokeLater(() -> report.accept(null, null), ModalityState.nonModal());
                        }
                    }
                    if (!app.isDispatchThread()) {
                        new RunSlow().run(onReady);
                        return;
                    }
                    app.executeOnPooledThread(() -> new RunSlow().run(onReady));
                }
                static TempPsiFile revalidate(Project project, TempPsiFile pair) {
                    ReadAction.compute(() -> pair.psi.isValid());
                    ReadAction.compute(() -> null);
                    return pair;
                }
                static TempPsiFile createTempPsiFile(Project project, String sql, String dialect) {
                    if (ApplicationManager.getApplication().isDispatchThread()) {
                        WriteIntentReadAction.compute(
                                () -> LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile));
                    } else {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
                    }
                    return null;
                }
                static void continuePrepareAfterTempFile(TempPsiFile temp) {
                    if (temp == null) failPrepareRequests(null, null, null, null, null, "x");
                    if (!isPrepareActive()) return;
                }
            }
        """.trimIndent()
        val syncOffenders = tempPsiEdtOffenders(syncCall)
        assertTrue(
            "chooseAndPrepare 同步直调 createTempPsiFile 必须被点名：$syncOffenders",
            syncOffenders.any { it.contains("直接调用 createTempPsiFile") },
        )

        val anyModality = syncCall.replace(
            "app.invokeLater(() -> report.accept(null, null), ModalityState.nonModal());",
            "app.invokeLater(() -> report.accept(null, null), ModalityState.any());",
        ).replace(
            "TempPsiFile temp = createTempPsiFile(project, sql, null);",
            "createTempPsiFileAsync(project, sql, null, (t, r) -> { });",
        )
        val anyOffenders = tempPsiEdtOffenders(anyModality)
        assertTrue(
            "any() 投递必须被点名：$anyOffenders",
            anyOffenders.any { it.contains("any()") },
        )

        // M7 反例：把 PSI 有效性校验挪回 invokeLater 回调（EDT 上判 isValid ＝平台慢操作）必须被点名
        // 语料经 trimIndent 后 RunSlow.run 体内行缩进为 16 空格（原 28 空格被公共前缀剥离）。
        val revalidateOnEdt = syncCall.replace(
            "                revalidate(project, pair);\n",
            "",
        ).replace(
            "app.invokeLater(() -> report.accept(null, null), ModalityState.nonModal());",
            "app.invokeLater(() -> { revalidate(project, pair); report.accept(null, null); }, ModalityState.nonModal());",
        ).replace(
            "TempPsiFile temp = createTempPsiFile(project, sql, null);",
            "createTempPsiFileAsync(project, sql, null, (t, r) -> { });",
        )
        val edtOffenders = tempPsiEdtOffenders(revalidateOnEdt)
        assertTrue(
            "EDT 上校验 PSI 有效性必须被点名：$edtOffenders",
            edtOffenders.any { it.contains("invokeLater 回调里") },
        )

        val noBranch = syncCall.replace(
            "if (ApplicationManager.getApplication().isDispatchThread()) {",
            "if (true) {",
        ).replace(
            "TempPsiFile temp = createTempPsiFile(project, sql, null);",
            "createTempPsiFileAsync(project, sql, null, (t, r) -> { });",
        )
        val branchOffenders = tempPsiEdtOffenders(noBranch)
        assertTrue(
            "refresh 段缺线程分流必须被点名：$branchOffenders",
            branchOffenders.any { it.contains("未按线程分流") },
        )

        val noFailsafe = syncCall
            .replace("TempPsiFile temp = createTempPsiFile(project, sql, null);", "createTempPsiFileAsync(project, sql, null, (t, r) -> { });")
            .replace("if (temp == null) failPrepareRequests(null, null, null, null, null, \"x\");", "")
            .replace("if (!isPrepareActive()) return;", "")
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
            "TempPsiFile temp = createTempPsiFile(project, sql, null);",
            "createTempPsiFileAsync(project, sql, null, (temp, reason) -> continuePrepareAfterTempFile(temp));",
        )
        assertEquals(
            "合规形态不应误报：${tempPsiEdtOffenders(good)}",
            0,
            tempPsiEdtOffenders(good).size,
        )
    }

    // ------------------------------------------------------------------
    // 夹具（k2j Java 形态锚点）
    // ------------------------------------------------------------------

    private fun read(path: String): String {
        val f = File(path)
        assertTrue("守卫目标文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 剥注释（块注释涂白保留换行，行注释截断）——判定只看代码，KDoc/JavaDoc 里「提」禁词不算违规。 */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    /**
     * Java 方法声明点：行首缩进后跟 Java 修饰符，签名行内出现 `name(`（Kotlin `fun` 锚的 Java
     * 等价形态）。修饰符打头 + 词边界，普通调用点/字符串内出现不命中；返回每个声明处 `name(` 的下标。
     */
    private fun declarationIndexes(src: String, fn: String): List<Int> {
        val re = Regex(
            """(?m)^[ \t]*(?:public|private|protected|static|final|synchronized)\s+[\w.<>\[\], ?]*\b""" +
                Regex.escape(fn) + """\s*\(""",
        )
        return re.findAll(src).map { m ->
            val at = m.value.lastIndexOf("$fn(")
            m.range.first + at
        }.toList()
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

    /** 同名全部重载的函数体合并（委托壳 + 全参真身一并纳入判定面）。无任何声明时返回 null。 */
    private fun mergedBodyOf(src: String, fn: String): String? {
        val bodies = declarationIndexes(src, fn).mapNotNull { bodyRange(src, it)?.let { r -> src.substring(r.first, r.last + 1) } }
        return when {
            bodies.isEmpty() -> null
            bodies.size == 1 -> bodies[0]
            else -> bodies.joinToString("\n")
        }
    }
}
