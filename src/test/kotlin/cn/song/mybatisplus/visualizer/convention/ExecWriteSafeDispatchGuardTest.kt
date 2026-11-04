package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 执行链入口 / 方言映射派发的 **write-safe 投递**守卫（T-ThreadFix C，2026-11-04 真机 243 沙盒）。
 *
 * 缺陷实证（同一次「预览+执行」3 条）：
 * ```
 * SEVERE - TransactionGuardImpl - Write-unsafe context!
 *   at DataSourceSelectorResultsKt.reuseSharedConsole(:750)   [WriteCommandAction { doc.setText }]
 *   at DataSourceSelectorConsoleKt.executeOnConsole(:666)     [WriteAction { writeVfTextViaDocument }]
 *   at SqlExecutor.execute → chooseAndPrepare
 *   at submitExplainDirectConsole$lambda$0$0(SqlPreviewPanelExecutionExplain.kt:198)
 *
 *   at PsiModificationTrackerImpl.fireEvent → FileManagerImpl.forceReload
 *        → FileContentUtilCore.reparseFiles → LanguageSubstitutors.requestReparsing$0
 *   （方言预映射：BGT 上省略模态的 invokeLater 派发 setMapping）
 * ```
 * `ModalityState.any()` 恒 write-unsafe（平台 `TransactionGuardImpl` 构造期只把 nonModal 登记为
 * write-safe），于是整条执行链（复用控制台的 `document.setText`、执行前的 vf 文档同步）都在无写
 * 资格的上下文里改模型。方言预映射同族：从 BGT 用**省略模态**派发 `setMapping` 时，runnable 在
 * 创建那一刻 `myWritingAllowed=false`，平台内部的 `reparseFiles` 与
 * `LanguageSubstitutors.requestReparsing` 两拍写动作全部无写资格。
 *
 * 为什么是形态守卫：write-safe 资格由平台在运行期按事务/模态判定，headless 测试线程上
 * `myWritingAllowed` 在非 EDT 构造期恒真，行为断言测不出「这个回调是以哪个模态排队的」；
 * 投递形态 + 模态实参是唯一可机器化判据（同 `ExecTempFileEdtGuardTest` 规则五的口径）。
 */
class ExecWriteSafeDispatchGuardTest {

    // master 回 port 差异：dev 把执行计划入口拆到 `SqlPreviewPanelExecutionExplain.kt`，
    // master 没有该文件——对应投递点仍在 `SqlPreviewPanelExecution.kt` 的大文件里
    // （`submitExplainDirectConsole`），判据与作用域函数名不变。
    private val explainPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt"
    private val dialectMappingPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DialectPreMapping.kt"

    /**
     * 判定内核（VIOLATIONS）：write-safe 投递形态。
     *
     * @param scopeFn null = 整文件（用于 `DialectPreMapping`：该文件的 `invokeLater` 全是
     *   `setMapping` 写动作派发，没有纯 UI 回调）；非 null = 只查该函数体。
     *
     * 判据：不得出现 `ModalityState.any()`；每个 `invokeLater(` 的实参区（括号配平）必须含
     * `ModalityState.nonModal()`；且作用域内至少有一处 `invokeLater`（防空绿——投递整体被删
     * 或改成同步直调时规则不得静默通过）。
     */
    internal fun writeSafeDispatchOffenders(source: String, scopeFn: String?): List<String> {
        val src = stripComments(source)
        val out = ArrayList<String>()
        val body = if (scopeFn == null) {
            src
        } else {
            bodyOf(src, scopeFn) ?: run {
                out += "未定位到 $scopeFn 函数体（改名/搬家后守卫须同步跟进）"
                return out
            }
        }
        val where = scopeFn ?: "整文件"
        if (body.contains("ModalityState.any()")) {
            out += "$where: 用 ModalityState.any() 投递——any() 恒 write-unsafe" +
                "（TransactionGuardImpl 构造期只把 nonModal 登记为 write-safe）"
        }
        var n = 0
        var idx = body.indexOf("invokeLater(")
        while (idx >= 0) {
            n++
            val args = callArgsText(body, idx + "invokeLater".length)
            if (!args.contains("ModalityState.nonModal()")) {
                out += "$where:${lineOf(body, idx)} invokeLater 的模态不是 ModalityState.nonModal()" +
                    "（省略模态/默认模态在 BGT 创建即 write-unsafe）：${args.take(60)}"
            }
            idx = body.indexOf("invokeLater(", idx + 1)
        }
        if (n == 0) {
            out += "$where: 未见 invokeLater 派发（write-safe 投递被整体删除或改成同步直调？）"
        }
        return out
    }

    @Test
    fun `执行入口与方言映射派发必须write-safe投递`() {
        val offenders = ArrayList<String>()
        offenders += writeSafeDispatchOffenders(read(explainPath), "submitExplainDirectConsole")
            .map { "SqlPreviewPanelExecution.kt → $it" }
        offenders += writeSafeDispatchOffenders(read(dialectMappingPath), null)
            .map { "DialectPreMapping.kt → $it" }
        assertTrue(
            "执行链入口/方言映射派发不再是 write-safe 投递（any()/默认模态 = 整条链写文档时打 " +
                "TransactionGuard Write-unsafe，真机 3 条 SEVERE）。违规 ${offenders.size} 处：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 自证：any() 必红、省略模态必红、无派发必红（防空绿）、nonModal 放行。 */
    @Test
    fun `自证_执行入口any与省略模态必被点名`() {
        val anySource = """
            internal fun SqlPreviewPanel.submitExplainDirectConsole(proj: Project, explainSql: String) {
                app.invokeLater({ SqlExecutor.execute(proj, explainSql) }, ModalityState.any())
            }
        """.trimIndent()
        val anyOffenders = writeSafeDispatchOffenders(anySource, "submitExplainDirectConsole")
        assertTrue("any() 必须被点名：$anyOffenders", anyOffenders.any { it.contains("any()") })

        val defaultModality = """
            internal fun SqlPreviewPanel.submitExplainDirectConsole(proj: Project, explainSql: String) {
                app.invokeLater({ SqlExecutor.execute(proj, explainSql) })
            }
        """.trimIndent()
        assertTrue(
            "省略模态必须被点名：${writeSafeDispatchOffenders(defaultModality, "submitExplainDirectConsole")}",
            writeSafeDispatchOffenders(defaultModality, "submitExplainDirectConsole")
                .any { it.contains("不是 ModalityState.nonModal()") },
        )

        val noDispatch = """
            internal fun SqlPreviewPanel.submitExplainDirectConsole(proj: Project, explainSql: String) {
                SqlExecutor.execute(proj, explainSql)
            }
        """.trimIndent()
        assertTrue(
            "作用域内无 invokeLater 必须被点名（防空绿）：${writeSafeDispatchOffenders(noDispatch, "submitExplainDirectConsole")}",
            writeSafeDispatchOffenders(noDispatch, "submitExplainDirectConsole")
                .any { it.contains("未见 invokeLater") },
        )

        val good = """
            internal fun SqlPreviewPanel.submitExplainDirectConsole(proj: Project, explainSql: String) {
                app.invokeLater({ SqlExecutor.execute(proj, explainSql) }, ModalityState.nonModal())
            }
        """.trimIndent()
        assertEquals(
            "nonModal 投递不应误报：${writeSafeDispatchOffenders(good, "submitExplainDirectConsole")}",
            0,
            writeSafeDispatchOffenders(good, "submitExplainDirectConsole").size,
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

    /** 取 `openParenIdx` 处调用的实参区文本（括号配平，不用固定长度窗口）。 */
    private fun callArgsText(src: String, openParenIdx: Int): String {
        if (openParenIdx < 0 || openParenIdx >= src.length || src[openParenIdx] != '(') return ""
        var depth = 0
        var i = openParenIdx
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return src.substring(openParenIdx + 1, i)
                    }
                }
            }
            i++
        }
        return src.substring(openParenIdx + 1)
    }

    private fun lineOf(src: String, idx: Int): Int = src.take(idx).count { it == '\n' } + 1
}
