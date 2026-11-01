package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-ExecPlanEdtSlowOp 守卫：exec 临时文件（`mpsql-exec.sql`）构建链**不得在 EDT 上做 VFS refresh /
 * PSI 查找**。
 *
 * 缺陷实证（2026-11-01 真机，`docs/验收文档/20261101-1600-OB面板自动化验收第二轮`）：
 * ```
 * SEVERE SlowOperations - Plugin to blame: SQL Visualizer
 * explainSql → promptDataSourceThenRetryStructured → prepareConsole → chooseAndPrepare
 *   → createTempPsiFile → ReadAction.compute { PsiManager.getInstance(project).findFile(vf) }
 * ```
 * `ReadAction` 只保证读语义、不改变线程——整段 PSI 查找跑在 EDT 上，243 平台断言必然 SEVERE。
 * `createTempPsiFile` 是**执行 SQL / 执行计划 / 回滚快照三路共用**入口，所以这是三条路共有的
 * 缺陷面，不是某一条路的偶发。
 *
 * 本守卫锁形态（为什么不是行为测试）：EDT 慢操作断言在 headless 测试里不生效（平台只对真实
 * 应用 EDT 断言），行为测试测不出「慢操作跑在哪个线程」，形态守卫是唯一可机器化的判据。规则：
 *  1. EDT 入口 `chooseAndPrepare` 函数体内不得出现 `PsiManager` / `ReadAction`（慢操作必须在
 *     后台线程），且必须经 [ASYNC_ENTRY] 派发、经 [CONTINUATION] 续接；
 *  2. 慢段本体 `createTempPsiFile` 只允许被 [ASYNC_ENTRY] 调用——工厂文件里任何**第二处**
 *     同步调用点即红（那等于又给 EDT 开了一条同步慢操作通道）；
 *  3. [ASYNC_ENTRY] 必须同时具备 `isDispatchThread` 判定 + `executeOnPooledThread` + `invokeLater`
 *     （EDT 只派发、结果回 EDT）；
 *  4. 慢段本体必须仍含 `refreshAndFindFileByIoFile` + `ReadAction`/`PsiManager`——PSI 获取被
 *     悄悄删掉或改成同步形态同样要红。
 *
 * 函数体一律按**大括号配平**取真实区间（固定长度窗口会跨界读到邻居函数，造成假绿——
 * LastUsedWriteOnEdtGuardTest 反向验证时踩过这个坑）。
 */
class ExecTempFileEdtGuardTest {

    private val resultsPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt"
    private val factoryPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/ExecTempFileFactory.kt"

    /**
     * 判定内核（VIOLATIONS）：返回违规点描述。可注入合成源码自证——主用例与自证语料共用同一实现。
     */
    internal fun offendersOf(resultsSource: String, factorySource: String): List<String> {
        val out = ArrayList<String>()
        val results = stripComments(resultsSource)
        val factory = stripComments(factorySource)

        // ---- 规则一：EDT 入口不得直接做 PSI / 慢操作，且必须走异步入口 ----
        val chooseBody = bodyOf(results, EDT_ENTRY)
        if (chooseBody == null) {
            out += "$resultsPath: 未定位到 $EDT_ENTRY 函数体（函数改名/搬家后守卫须同步跟进）"
        } else {
            if (chooseBody.contains("PsiManager")) {
                out += "$EDT_ENTRY: EDT 入口函数体内直接出现 PsiManager（PSI 查找跑在 EDT = SEVERE SlowOperations 本体）"
            }
            if (chooseBody.contains("ReadAction")) {
                out += "$EDT_ENTRY: EDT 入口函数体内直接出现 ReadAction（ReadAction 不改变线程，慢操作仍在 EDT）"
            }
            if (!chooseBody.contains("$ASYNC_ENTRY(")) {
                out += "$EDT_ENTRY: 未经 $ASYNC_ENTRY 派发临时文件构建（慢段回到 EDT）"
            }
            if (!chooseBody.contains("$CONTINUATION(")) {
                out += "$EDT_ENTRY: 异步回调里未续接 $CONTINUATION（后半链丢失 = 点执行不再建链）"
            }
        }

        // ---- 规则二：慢段本体只允许被异步入口调用 ----
        val declAt = declarationAt(factory, CORE)
        val asyncBodyRange = bodyRangeOf(factory, ASYNC_ENTRY)
        if (declAt == null) {
            out += "$factoryPath: 未定位到 $CORE 声明（慢段本体不见了？）"
        }
        var idx = factory.indexOf("$CORE(")
        while (idx >= 0) {
            val isDeclaration = idx == declAt
            val insideAsync = asyncBodyRange != null && idx in asyncBodyRange
            if (!isDeclaration && !insideAsync) {
                out += "$factoryPath:${lineOf(factory, idx)} $CORE 被同步调用（EDT 调用点即慢操作；" +
                    "只允许经 $ASYNC_ENTRY 派发到后台线程）"
            }
            idx = factory.indexOf("$CORE(", idx + CORE.length + 1)
        }

        // ---- 规则三：异步入口必须是「EDT 判定 + 池线程 + 回 EDT」三段形态 ----
        val asyncBody = asyncBodyRange?.let { factory.substring(it.first, it.last + 1) }
        if (asyncBody == null) {
            out += "$factoryPath: 未定位到 $ASYNC_ENTRY 函数体（异步派发入口消失）"
        } else {
            if (!asyncBody.contains("isDispatchThread")) out += "$ASYNC_ENTRY: 缺 isDispatchThread 判定（无从判断该不该派发）"
            if (!asyncBody.contains("executeOnPooledThread")) out += "$ASYNC_ENTRY: 缺 executeOnPooledThread（慢段未离开 EDT）"
            if (!asyncBody.contains("invokeLater")) out += "$ASYNC_ENTRY: 缺 invokeLater（结果未回到 EDT，UI 续接会越线程）"
        }

        // ---- 规则四：慢段本体仍须真的做 refresh + PSI 查找（防被悄悄掏空） ----
        val coreBody = declAt?.let { at -> bodyRange(factory, at)?.let { factory.substring(it.first, it.last + 1) } }
        if (coreBody != null) {
            if (!coreBody.contains("refreshAndFindFileByIoFile")) {
                out += "$CORE: 不再含 refreshAndFindFileByIoFile（VFS 刷新段被删除/搬走，须重新评审守卫）"
            }
            if (!coreBody.contains("ReadAction") || !coreBody.contains("PsiManager")) {
                out += "$CORE: 不再含 ReadAction + PsiManager（PSI 获取被掏空——Runner 枚举会拿不到 PsiFile）"
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // 生产站点规则
    // ------------------------------------------------------------------

    @Test
    fun `执行临时文件构建链不得在 EDT 上做慢操作`() {
        val offenders = offendersOf(read(resultsPath), read(factoryPath))
        assertTrue(
            "执行临时文件构建链的线程纪律被破坏（EDT 上 VFS refresh / PSI 查找 = 平台 SEVERE " +
                "SlowOperations，三路共用入口全部受累）。违规 ${offenders.size} 处：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 判定内核自证（合成语料直接喂判定函数）
    // ------------------------------------------------------------------

    /** 自证：EDT 入口直接 findFile + 同步调用慢段必须被点名（正反两面）。 */
    @Test
    fun `自证_判定内核能看见EDT直接做PSI且不误报异步形态`() {
        val badResults = """
            internal fun DataSourceSelector.chooseAndPrepare(project: Project, sql: String) {
                val temp = createTempPsiFile(project, sql)
                val psi = ReadAction.compute<PsiFile?, Throwable> {
                    PsiManager.getInstance(project).findFile(vf)
                }
            }
        """.trimIndent()
        val badFactory = """
            internal fun DataSourceSelector.createTempPsiFile(project: Project): Pair<VirtualFile, PsiFile>? {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                val psi = ReadAction.compute<PsiFile?, Throwable> { PsiManager.getInstance(project).findFile(vf) }
                return null
            }

            internal fun DataSourceSelector.createTempPsiFileAsync(project: Project, onReady: (Any?) -> Unit) {
                val app = ApplicationManager.getApplication()
                if (!app.isDispatchThread) { onReady(createTempPsiFile(project)); return }
                app.executeOnPooledThread {
                    val pair = createTempPsiFile(project)
                    app.invokeLater({ onReady(pair) }, ModalityState.any())
                }
            }
        """.trimIndent()
        val bad = offendersOf(badResults, badFactory)
        assertTrue("EDT 入口直接做 PSI 必须被点名：$bad", bad.any { it.contains("PsiManager") })
        assertTrue("EDT 入口直接 ReadAction 必须被点名：$bad", bad.any { it.contains("ReadAction") })
        assertTrue("EDT 入口缺异步入口调用必须被点名：$bad", bad.any { it.contains(ASYNC_ENTRY) })
        assertTrue("EDT 入口缺续接调用必须被点名：$bad", bad.any { it.contains(CONTINUATION) })

        val goodResults = """
            internal fun DataSourceSelector.chooseAndPrepare(project: Project, sql: String) {
                createTempPsiFileAsync(project, sql) { temp ->
                    continuePrepareAfterTempFile(project = project, temp = temp)
                }
            }
        """.trimIndent()
        val goodFactory = badFactory
        assertEquals(
            "合规异步形态不应误报：${offendersOf(goodResults, goodFactory)}",
            0,
            offendersOf(goodResults, goodFactory).size,
        )
    }

    /** 自证：工厂里出现第二处同步调用点必须被点名（守卫灵敏度）。 */
    @Test
    fun `自证_第二处同步调用点必被抓`() {
        val results = """
            internal fun DataSourceSelector.chooseAndPrepare(project: Project, sql: String) {
                createTempPsiFileAsync(project, sql) { temp -> continuePrepareAfterTempFile(project, temp) }
            }
        """.trimIndent()
        val factory = """
            internal fun DataSourceSelector.createTempPsiFile(project: Project): Pair<VirtualFile, PsiFile>? {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                val psi = ReadAction.compute<PsiFile?, Throwable> { PsiManager.getInstance(project).findFile(vf) }
                return null
            }

            internal fun DataSourceSelector.createTempPsiFileAsync(project: Project, onReady: (Any?) -> Unit) {
                val app = ApplicationManager.getApplication()
                if (!app.isDispatchThread) { onReady(createTempPsiFile(project)); return }
                app.executeOnPooledThread {
                    val pair = createTempPsiFile(project)
                    app.invokeLater({ onReady(pair) }, ModalityState.any())
                }
            }

            internal fun DataSourceSelector.anotherCaller(project: Project) {
                val temp = createTempPsiFile(project)
            }
        """.trimIndent()
        val offenders = offendersOf(results, factory)
        assertTrue(
            "工厂内第二处同步调用点必须被点名（否则 EDT 又有一条同步慢操作通道）：$offenders",
            offenders.any { it.contains("被同步调用") },
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

    /** 函数体区间：从声明后的首个 `{` 起按大括号配平（非固定长度窗口）。 */
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

    private fun bodyRangeOf(src: String, fn: String): IntRange? =
        declarationAt(src, fn)?.let { bodyRange(src, it) }

    private fun bodyOf(src: String, fn: String): String? =
        bodyRangeOf(src, fn)?.let { src.substring(it.first, it.last + 1) }

    private fun lineOf(src: String, idx: Int): Int = src.take(idx).count { it == '\n' } + 1

    private companion object {
        private const val EDT_ENTRY = "chooseAndPrepare"
        private const val CORE = "createTempPsiFile"
        private const val ASYNC_ENTRY = "createTempPsiFileAsync"
        private const val CONTINUATION = "continuePrepareAfterTempFile"
    }
}
