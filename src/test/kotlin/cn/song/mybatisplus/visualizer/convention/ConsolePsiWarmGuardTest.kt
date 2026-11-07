package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 控制台准备链的 **PSI 预暖线程契约**守卫（T-ThreadFix B，2026-11-04 真机 243 沙盒）。
 *
 * 缺陷实证（一次「预览+执行」共 9 条）：
 * ```
 * SEVERE - SlowOperations
 *   at FileManagerImpl.findDirectory → ProjectFileIndexImpl.isUnderIgnored
 *        → WorkspaceFileIndexDataImpl.ensureIsUpToDate
 *   at PsiUtilCore.getPsiFile → JdbcConsole.getFile → updateScriptModel
 *   at JdbcConsole.getScriptModel
 *   at DataSourceSelectorConsoleKt.prepareAfterEditorResolved(DataSourceSelectorConsole.kt:78)
 * ```
 * `console.getScriptModel()` 内部 `PsiUtilCore.getPsiFile(project, console.getVirtualFile())` 会
 * **首次实例化**该 vf 的 `PsiFile`，`AbstractFileViewProvider.shouldCreatePsi` 每次都走
 * `PsiManager.findDirectory` → `ProjectFileIndex.isUnderIgnored` → WorkspaceFileIndex
 * `ensureIsUpToDate`；平台把它归为慢操作，EDT 上调用即 SEVERE。
 *
 * 不能靠「PSI 已缓存」：exec 临时文件的 PSI 虽在 `createTempPsiFile`（BGT）里建过，但方言映射的
 * `setMapping` 会触发 `LanguageSubstitutors.requestReparsing` → `FileManagerImpl.forceReload`
 * 把它作废（同一轮日志的 write-unsafe 栈里可见这条 forceReload）。
 *
 * 修法形态：`warmConsolePsiThen`（EDT 判定 → 池线程 `ReadAction{PsiManager.findFile}` →
 * `invokeLater(nonModal)` 回 EDT），`prepareAfterEditorResolved` 的**每个**调用点都必须落在它的
 * 续接 lambda 里。为什么是形态守卫：慢操作断言只在真实应用 EDT 上生效，headless 测试线程测不出
 * 「PSI 首次实例化跑在哪个线程」。
 */
class ConsolePsiWarmGuardTest {

    private val consolePath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorConsole.kt"

    /**
     * 判定内核（VIOLATIONS）：预暖链形态 + 续接点落位。
     *
     * 形态要求与 `createTempPsiFileAsync` 同款三段式；另外续接点必须整体落在续接 lambda 作用域内
     * ——直接调用等于「EDT 上首次实例化 PSI」。
     */
    internal fun psiWarmChainOffenders(consoleSource: String): List<String> {
        val src = stripComments(consoleSource)
        val out = ArrayList<String>()
        val warmBody = bodyOf(src, WARM_FN)
        if (warmBody == null) {
            out += "$consolePath: 未定位到 $WARM_FN 函数体（预暖入口消失，EDT 首次 PSI 创建回来了）"
        } else {
            if (!warmBody.contains("isDispatchThread")) {
                out += "$WARM_FN: 缺 isDispatchThread 判定（非 EDT 调用方应就地预暖、就地续接）"
            }
            if (!warmBody.contains("executeOnPooledThread")) {
                out += "$WARM_FN: 缺 executeOnPooledThread（PSI 创建没离开 EDT）"
            }
            if (!warmBody.contains("ReadAction") || !warmBody.contains("PsiManager")) {
                out += "$WARM_FN: 不再含 ReadAction + PsiManager（PSI 没被真的建出来，预暖是空转）"
            }
            if (!warmBody.contains("invokeLater")) {
                out += "$WARM_FN: 缺 invokeLater（结果没回 EDT，续接链会越线程做 UI）"
            }
            if (!warmBody.contains("ModalityState.nonModal()")) {
                out += "$WARM_FN: 续接投递不是 ModalityState.nonModal()——续接链会写控制台文档，" +
                    "any()/默认模态必触发 TransactionGuard Write-unsafe"
            }
            if (warmBody.contains("ModalityState.any()")) {
                out += "$WARM_FN: 用 ModalityState.any() 投递（any() 恒 write-unsafe）"
            }
        }
        val scopes = trailingLambdaScopes(src, "$WARM_FN(")
        var count = 0
        var idx = src.indexOf("$CONTINUATION(")
        while (idx >= 0) {
            if (!isDeclarationAt(src, idx)) {
                count++
                if (scopes.none { idx in it }) {
                    out += "$consolePath:${lineOf(src, idx)} $CONTINUATION 调用点不在 $WARM_FN 续接里" +
                        "（EDT 上首次 PsiManager.findFile = 平台 SlowOperations SEVERE）"
                }
            }
            idx = src.indexOf("$CONTINUATION(", idx + CONTINUATION.length + 1)
        }
        if (count == 0) {
            out += "$consolePath: 未见 $CONTINUATION 调用点（整段准备链被删？）"
        }
        return out
    }

    @Test
    fun `控制台PSI预暖链形态与续接点必须齐备`() {
        val offenders = psiWarmChainOffenders(read(consolePath))
        assertTrue(
            "控制台准备链的 PSI 预暖形态被破坏（EDT 上首次 PsiManager.findFile = 平台 " +
                "SlowOperations SEVERE，真机一次点击 9 条）。违规 ${offenders.size} 处：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 自证：预暖形态缺段必红、续接点裸调必红、合规形态不误报（正反两面）。 */
    @Test
    fun `自证_预暖链缺段与续接点裸调必被点名`() {
        val directCall = """
            internal fun DataSourceSelector.prepareInConsole(project: Project, console: Any) {
                val editor = findEditor()
                prepareAfterEditorResolved(project, vf, console, editor)
            }
        """.trimIndent()
        val naked = psiWarmChainOffenders(directCall)
        assertTrue("预暖入口缺失必须被点名：$naked", naked.any { it.contains("未定位到 warmConsolePsiThen") })
        assertTrue(
            "续接点裸调必须被点名：$naked",
            naked.any { it.contains("不在 warmConsolePsiThen 续接里") },
        )

        val warmWithoutPool = """
            internal fun warmConsolePsiThen(project: Project, vf: VirtualFile, console: Any?, onReady: () -> Unit) {
                if (!app.isDispatchThread) {
                    onReady()
                    return
                }
                app.invokeLater({ onReady() }, ModalityState.any())
            }
        """.trimIndent()
        val shape = psiWarmChainOffenders(warmWithoutPool)
        assertTrue("缺 executeOnPooledThread 必须被点名：$shape", shape.any { it.contains("executeOnPooledThread") })
        assertTrue("缺 ReadAction+PsiManager 必须被点名：$shape", shape.any { it.contains("PsiManager") })
        assertTrue("any() 投递必须被点名：$shape", shape.any { it.contains("any()") })

        val good = """
            internal fun warmConsolePsiThen(project: Project, vf: VirtualFile, console: Any?, onReady: () -> Unit) {
                if (!app.isDispatchThread) {
                    ReadAction.compute<Unit, Throwable> { PsiManager.getInstance(project).findFile(vf) }
                    onReady()
                    return
                }
                app.executeOnPooledThread {
                    ReadAction.compute<Unit, Throwable> { PsiManager.getInstance(project).findFile(vf) }
                    app.invokeLater({ onReady() }, ModalityState.nonModal())
                }
            }

            internal fun DataSourceSelector.prepareInConsole(project: Project, console: Any) {
                warmConsolePsiThen(project, vf, console) {
                    prepareAfterEditorResolved(project, vf, console)
                }
            }
        """.trimIndent()
        assertEquals(
            "合规预暖形态不应误报：${psiWarmChainOffenders(good)}",
            0,
            psiWarmChainOffenders(good).size,
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

    /** 某 token 出现处是否为**声明**（前面是 `fun`）——续接点统计只算真实调用。 */
    private fun isDeclarationAt(src: String, idx: Int): Boolean {
        var back = idx
        while (back > 0 && (src[back - 1].isLetterOrDigit() || src[back - 1] == '.' || src[back - 1] == '_')) {
            back--
        }
        var s = back
        while (s > 0 && src[s - 1].isWhitespace()) s--
        return s >= 3 && src.regionMatches(s - 3, "fun", 0, 3) &&
            (s - 4 < 0 || !(src[s - 4].isLetterOrDigit() || src[s - 4] == '_'))
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

    /** 每个 `callToken` 调用点 + 其尾随 lambda 的作用域（无尾随 lambda 则只含实参区）。 */
    private fun trailingLambdaScopes(src: String, callToken: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var idx = src.indexOf(callToken)
        while (idx >= 0) {
            val open = idx + callToken.length - 1
            if (open in 0 until src.length && src[open] == '(') {
                val close = matchingDelimiter(src, open, '(', ')')
                if (close > 0) {
                    var i = close + 1
                    while (i < src.length && src[i].isWhitespace()) i++
                    val end = if (i < src.length && src[i] == '{') matchingDelimiter(src, i, '{', '}') else -1
                    out += idx..(if (end > 0) end else close)
                }
            }
            idx = src.indexOf(callToken, idx + callToken.length)
        }
        return out
    }

    /** 括号/大括号配平取闭合下标（跳过字符串字面量里出现的分隔符）。 */
    private fun matchingDelimiter(src: String, openIdx: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = openIdx
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun lineOf(src: String, idx: Int): Int = src.take(idx).count { it == '\n' } + 1

    private companion object {
        private const val WARM_FN = "warmConsolePsiThen"
        private const val CONTINUATION = "prepareAfterEditorResolved"
    }
}
