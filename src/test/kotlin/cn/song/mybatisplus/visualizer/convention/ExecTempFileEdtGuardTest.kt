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
 *     悄悄删掉或改成同步形态同样要红；
 *  5. **结果回调必须是 write-safe 投递**（[writeSafeDeliveryOffenders]，2026-11-01 真机回归）：
 *     [ASYNC_ENTRY] 里每个 `onReady(` 调用点都必须落在 `invokeLater(…, ModalityState.nonModal())`
 *     （或 `DocWriteContext.invokeLaterWriteSafe(` / `submitTransaction(`）之内，且函数体不得出现
 *     `ModalityState.any()`。
 *
 * 规则五为什么必须单钉：既有 `BannedApiScanRuleTest.anyModalityWriteOffenders` 只看**直接**写令牌
 * （`setText(` / `commitDocument(` …）出现在 any() 回调里，看不见「`invokeLater { callback(…) }`」
 * 这种**间接**形态——回调体在别的函数里写文档。本券回归正是这一形态（`onReady` 不在写令牌表内，
 * 首版 BannedApiScanRuleTest 全绿却真机必报），故在此按「投递点 + 模态」直接封口。
 *
 * 为什么规则五是形态守卫而非行为测试：write-safe 资格由平台事务/模态在运行期判定
 * （`TransactionGuardImpl` 构造期把 `myWritingAllowed` 置为 `SwingUtilities.isEventDispatchThread()`，
 * headless 测试线程上该值恒真），行为断言测不出「any() 排出的回调写文档」——形态是唯一可机器化
 * 判据（与 LastUsedWriteOnEdtGuardTest 同理）。
 *
 * 函数体一律按**大括号配平**取真实区间（固定长度窗口会跨界读到邻居函数，造成假绿——
 * LastUsedWriteOnEdtGuardTest 反向验证时踩过这个坑）。
 *
 * 规则六（UI-002，2026-11-04 真机）：**PSI 有效性判定必须在读锁内**。慢段搬上池线程后，
 * `consumeCachedTempPsiFile` 的 `psi.isValid`（BGT）与 `revalidate` 的两处 `isValid`
 * （EDT 回调）都不能靠「EDT 隐式读权限」或「readCatching 兜底」蒙混——新线程模型下
 * `Application.isReadAccessAllowed()` 只看本线程读许可，无锁即打
 * `Read access is allowed from inside read-action only`（真机三连栈）。判定口径：
 * 这两个函数体内的每处 `.isValid` 都必须落在 `ReadAction.compute{…}` 的**大括号作用域**内
 * （不是「函数里出现过 ReadAction」），且判定不许被整段删掉（删了就是空绿）。
 */
class ExecTempFileEdtGuardTest {

    private val resultsPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt"
    private val factoryPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/ExecTempFileFactory.kt"
    private val selectorPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt"

    /**
     * 判定内核（VIOLATIONS）：返回违规点描述。可注入合成源码自证——主用例与自证语料共用同一实现。
     *
     * @param selectorSource [consumeCachedTempPsiFile] 所在文件源码（规则六站点一）；缺省用合规合成语料，
     *   自证用例可只喂 results/factory 两段。
     */
    internal fun offendersOf(
        resultsSource: String,
        factorySource: String,
        selectorSource: String = COMPLIANT_SELECTOR,
    ): List<String> {
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
            // M7 补强（T-PreviewOpenPerfM2，master 2026-11-12）：投递模态必须是 nonModal，
            // 且 PSI 有效性校验（revalidate）必须在后台段完成——落在 invokeLater 回调里就是
            // EDT 上判 isValid，平台 SlowOperations 断言会记「Plugin to blame: SQL Visualizer」
            //（真机 2026-11-12 11:08:53 实证）。
            if (!asyncBody.contains("ModalityState.nonModal()")) {
                out += "$ASYNC_ENTRY: 续接投递不是 ModalityState.nonModal()——" +
                    "续接链会写文档，any()/默认模态必触发 TransactionGuard Write-unsafe"
            }
            if (asyncBody.contains("ModalityState.any()")) {
                out += "$ASYNC_ENTRY: 用 ModalityState.any() 投递（any() 恒 write-unsafe）"
            }
            if (!asyncBody.contains("$REVALIDATE(")) {
                out += "$ASYNC_ENTRY: 回调未经 $REVALIDATE——异步窗口内 PSI 失效会硬抛" +
                    "PsiInvalidElementAccessException"
            } else if (asyncBody.indexOf("$REVALIDATE(") > asyncBody.indexOf("invokeLater(")) {
                out += "$REVALIDATE 落在 invokeLater 回调里（EDT 上判 PSI 有效性＝平台慢操作，真机" +
                    " 2026-11-12 11:08:53 被记 Plugin to blame: SQL Visualizer）；必须在后台段完成"
            }
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
            // master 保留的分流纪律（T-PrewarmWilDeadlock）：refresh 段必须按线程分流——
            // 池线程上包 WIRA × 同步 refresh 会自锁（全 IDE not responding）。
            if (!coreBody.contains("isDispatchThread")) {
                out += "$CORE 的 refresh 段未按线程分流——池线程上包 WIRA × 同步 refresh " +
                    "会自锁（T-PrewarmWilDeadlock，全 IDE not responding）"
            }
        }

        // ---- 规则五：结果回调必须是 write-safe 投递（any() 恒 write-unsafe） ----
        out += writeSafeDeliveryOffenders(factorySource)

        // ---- 规则六：PSI 有效性判定必须在读锁内（BGT / EDT 都不隐含读权限） ----
        out += psiValidityReadLockOffenders(selectorSource, factorySource)

        // ---- 规则七（master 演进并轨）：异步续接链收口必须齐备 ----
        val continuationBody = bodyOf(results, CONTINUATION)
        if (continuationBody == null) {
            out += "$resultsPath: 未定位到 $CONTINUATION 函数体（异步续接链消失）"
        } else {
            if (continuationBody.contains("$CORE(")) {
                out += "$CONTINUATION 内又直接调用 $CORE（续接在 EDT 上）"
            }
            if (!continuationBody.contains("failPrepareRequests(")) {
                out += "$CONTINUATION 丢失建链失败收口 failPrepareRequests"
            }
            if (!continuationBody.contains("isPrepareActive(")) {
                out += "$CONTINUATION 丢失迟到续接守卫 isPrepareActive" +
                    "（异步窗口内门已易主仍会建第二条会话/控制台）"
            }
        }
        return out
    }

    /**
     * 判定内核（VIOLATIONS）：PSI 有效性判定是否处于读锁作用域。
     *
     * UI-002（2026-11-04 真机）：`createTempPsiFile` 搬上池线程后，缓存命中路径的
     * `consumeCachedTempPsiFile` 里 `runCatching { psi.isValid }` 没有读锁——平台
     * `FileManagerImpl.evaluateValidity`（`@RequiresReadLock`）打
     * `Read access is allowed from inside read-action only`（真机同一栈三连：evaluateValidity /
     * findDirectory / ensureIsUpToDate）。同族风险在 `revalidate`（EDT 回调内两处 `isValid`），
     * 那里同样显式取读锁，不依赖 EDT 隐式权限。
     *
     * 判定：两个函数体内的**每处 `.isValid`** 都必须落在 `ReadAction.compute{…}` 的括号作用域内；
     * 只看「函数里出现过 ReadAction」会被「锁包在别处、判定裸奔」骗过（反向验证锚点：摘掉
     * `ReadAction` 必须变红）。另：判定整体消失也算违规（防空绿——缓存会把死 PSI 递进续接链）。
     */
    internal fun psiValidityReadLockOffenders(selectorSource: String, factorySource: String): List<String> {
        val out = ArrayList<String>()
        val selector = stripComments(selectorSource)
        val consumeRange = bodyRangeOf(selector, CONSUME_CACHE)
        if (consumeRange == null) {
            out += "$selectorPath: 未定位到 $CONSUME_CACHE 函数体（函数改名/搬家后守卫须同步跟进）"
        } else {
            out += unprotectedIsValidSites(
                selector.substring(consumeRange.first, consumeRange.last + 1),
                CONSUME_CACHE,
                selectorPath,
                lineBase = lineOf(selector, consumeRange.first) - 1,
            )
        }
        val factory = stripComments(factorySource)
        val revalidateRange = bodyRangeOf(factory, REVALIDATE)
        if (revalidateRange == null) {
            out += "$factoryPath: 未定位到 $REVALIDATE 函数体（PSI 失效兜底消失？）"
        } else {
            out += unprotectedIsValidSites(
                factory.substring(revalidateRange.first, revalidateRange.last + 1),
                REVALIDATE,
                factoryPath,
                lineBase = lineOf(factory, revalidateRange.first) - 1,
            )
        }
        return out
    }

    /**
     * 单个函数体：每处 `.isValid` 必须在 `ReadAction.compute{…}` 作用域内；且不得一处都没有。
     *
     * @param lineBase 函数体首行之前的行数（违规行号按**整文件**行号报，方便直接跳源码）
     */
    private fun unprotectedIsValidSites(
        body: String,
        fn: String,
        path: String,
        lineBase: Int,
    ): List<String> {
        val out = ArrayList<String>()
        val scopes = readActionComputeScopes(body)
        var count = 0
        var idx = body.indexOf(".isValid")
        while (idx >= 0) {
            count++
            if (scopes.none { idx in it }) {
                out += "$path:${lineBase + lineOf(body, idx)} $fn 的 `.isValid` 不在 ReadAction 作用域内" +
                    "（无读锁读 PSI = 平台 Read access is allowed from inside read-action only）"
            }
            idx = body.indexOf(".isValid", idx + 1)
        }
        if (count == 0) {
            out += "$path: $fn 未见 `.isValid`（PSI 有效性判定被整体删除？缓存会把死 PSI 递进续接链）"
        }
        return out
    }

    /**
     * `ReadAction.compute{…}` 的作用域区间（大括号配平取**内部**）。
     *
     * 认形态：`ReadAction.compute {` / `ReadAction.compute<T, E> {`——取 `{` 前的紧邻文本匹配，
     * 避免把「函数里别处的 ReadAction」当成保护；`ReadAction.run` 同样收（语义等价）。
     */
    private fun readActionComputeScopes(body: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        val pattern = Regex("""ReadAction\.(compute|run)(<[^>{]*>)?\s*\{""")
        for (m in pattern.findAll(body)) {
            val open = body.indexOf('{', m.range.first)
            if (open < 0) continue
            var depth = 0
            var i = open
            while (i < body.length) {
                when (body[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            out += (open + 1)..(i - 1)
                            break
                        }
                    }
                }
                i++
            }
        }
        return out
    }

    /**
     * 判定内核（VIOLATIONS）：[ASYNC_ENTRY] 的**结果回调投递**是否处于 write-safe 上下文。
     *
     * 2026-11-01 真机回归：首版用 `invokeLater({ onReady(pair) }, ModalityState.any())`——any() 恒
     * write-unsafe（不在 `TransactionGuardImpl.myWriteSafeModalities` 表内，构造期只登记 nonModal），
     * 续接链里的文档写（共享控制台 `editor.document.setText`、控制台文档 `setText` +
     * `commitDocument`）每次点「执行计划」都紧跟一条 `SEVERE TransactionGuardImpl … Write-unsafe
     * context`（16:41:44 / 16:48:03 / 16:53:49 与点击时间戳一一对应）。
     *
     * 判定：每个 `onReady(` 调用点都必须落在 write-safe 投递之内——
     *  - `invokeLater(…, ModalityState.nonModal())`（实参区按**括号配平**取，不用固定窗口），或
     *  - `DocWriteContext.invokeLaterWriteSafe(` / `submitTransaction(`（平台/仓内既有 write-safe 收口）。
     * 另外函数体整体不得出现 `ModalityState.any()`。
     */
    internal fun writeSafeDeliveryOffenders(factorySource: String): List<String> {
        val src = stripComments(factorySource)
        val body = bodyOf(src, ASYNC_ENTRY)
            ?: return listOf("$factoryPath: 未定位到 $ASYNC_ENTRY 函数体（异步投递入口消失）")
        val out = ArrayList<String>()
        if (body.contains("ModalityState.any()")) {
            out += "$ASYNC_ENTRY: 用 ModalityState.any() 投递——any() 恒 write-unsafe" +
                "（TransactionGuardImpl 构造期只把 nonModal 登记为 write-safe），续接链文档写必触发" +
                " TransactionGuard Write-unsafe 断言"
        }
        var idx = body.indexOf("$ON_READY(")
        if (idx < 0) out += "$ASYNC_ENTRY: 未见 $ON_READY( 调用（结果回调不见了？）"
        while (idx >= 0) {
            val verdict = deliveryVerdict(body, idx)
            if (!verdict.writeSafe) {
                out += "$ASYNC_ENTRY:${lineOf(body, idx)} $ON_READY 不在 write-safe 投递里（${verdict.how}）"
            }
            idx = body.indexOf("$ON_READY(", idx + ON_READY.length + 1)
        }
        return out
    }

    /** 判定辅助：`callIdx` 处的 `onReady(` 是否被 write-safe 投递包住（投递形态 + 模态实参）。 */
    internal fun deliveryVerdict(body: String, callIdx: Int): DeliveryVerdict {
        val before = body.substring(0, callIdx)
        // 取「最近的一个投递调用」——按出现位置比较，避免跨语句误认
        var bestIdx = -1
        var bestKind = ""
        var bestSafe = false
        for ((name, safe) in DELIVERY_CALLS) {
            val i = before.lastIndexOf(name)
            if (i > bestIdx) {
                bestIdx = i
                bestKind = name
                bestSafe = safe
            }
        }
        if (bestIdx < 0) return DeliveryVerdict(false, "未找到任何投递调用（同步直调 = 慢操作 + 非 EDT 回调）")
        if (bestSafe) return DeliveryVerdict(true, bestKind)
        // invokeLater：取实参区（括号配平）判模态
        val args = callArgsText(body, bestIdx + bestKind.length - 1)
        val writeSafe = args.contains("ModalityState.nonModal()")
        return DeliveryVerdict(
            writeSafe,
            if (writeSafe) "invokeLater(nonModal)" else "invokeLater 模态不是 nonModal：${args.take(60)}",
        )
    }

    /** 投递形态：name → 该形态是否天然 write-safe（invokeLater 需另看模态实参）。 */
    private data class DeliveryCall(val name: String, val writeSafeByItself: Boolean)

    internal data class DeliveryVerdict(val writeSafe: Boolean, val how: String)

    /** 取 `openParenIdx` 处调用的实参区文本（**括号配平**，不用固定长度窗口）。 */
    private fun callArgsText(src: String, openParenIdx: Int): String {
        if (openParenIdx < 0 || openParenIdx >= src.length || src[openParenIdx] != '(') return ""
        var depth = 0
        var i = openParenIdx
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(openParenIdx + 1, i)
                }
            }
            i++
        }
        return src.substring(openParenIdx + 1)
    }

    // ------------------------------------------------------------------
    // 生产站点规则
    // ------------------------------------------------------------------

    @Test
    fun `执行临时文件构建链不得在 EDT 上做慢操作`() {
        val offenders = offendersOf(read(resultsPath), read(factoryPath), read(selectorPath))
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
                    app.invokeLater({ onReady(pair) }, ModalityState.nonModal())
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
            internal fun DataSourceSelector.continuePrepareAfterTempFile(project: Project, temp: Any?) {
                if (temp == null) failPrepareRequests(null, null, null, null, null, "x")
                if (!isPrepareActive()) return
            }
        """.trimIndent()
        assertEquals(
            "合规异步形态不应误报：${offendersOf(goodResults, goodAsyncFactory())}",
            0,
            offendersOf(goodResults, goodAsyncFactory()).size,
        )
    }

    /**
     * 自证（master M7 演进并轨，T-PreviewOpenPerfM2）：同步直调必红、any() 投递必红、
     * 缺线程分流必红、丢续接收口必红、EDT 上校验 PSI 必红，合规形态不误报。
     */
    @Test
    fun `自证_M7并轨_any投递与EDT上校验与缺分流与丢收口必被点名`() {
        val goodResults = """
            internal fun DataSourceSelector.chooseAndPrepare(project: Project, sql: String) {
                createTempPsiFileAsync(project, sql) { temp -> continuePrepareAfterTempFile(project, temp) }
            }
            internal fun DataSourceSelector.continuePrepareAfterTempFile(project: Project, temp: Any?) {
                if (temp == null) failPrepareRequests(null, null, null, null, null, "x")
                if (!isPrepareActive()) return
            }
        """.trimIndent()
        val goodFactory = """
            internal fun DataSourceSelector.createTempPsiFile(project: Project): Any? {
                if (ApplicationManager.getApplication().isDispatchThread) {
                    WriteIntentReadAction.compute<Any?> { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f) }
                } else {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
                }
                val psi = ReadAction.compute<PsiFile?, Throwable> { PsiManager.getInstance(project).findFile(vf) }
                return null
            }
            internal fun revalidate(project: Project, pair: Any?): Any? {
                ReadAction.compute<Boolean, Throwable> { psi.isValid }
                ReadAction.compute<Any?, Throwable> { null }
                return pair
            }
            internal fun DataSourceSelector.createTempPsiFileAsync(project: Project, onReady: (Any?) -> Unit) {
                val app = ApplicationManager.getApplication()
                fun runSlow(report: () -> Unit) {
                    val pair = createTempPsiFile(project)
                    revalidate(project, pair)
                    app.invokeLater({ report() }, ModalityState.nonModal())
                }
                if (!app.isDispatchThread) {
                    runSlow { onReady(null) }
                    return
                }
                app.executeOnPooledThread { runSlow { onReady(null) } }
            }
        """.trimIndent()
        assertEquals(
            "合规形态不应误报：${offendersOf(goodResults, goodFactory)}",
            0,
            offendersOf(goodResults, goodFactory).size,
        )

        // any() 投递必红（规则三 + 规则五双点名）
        val anyModality = goodFactory.replace("ModalityState.nonModal()", "ModalityState.any()")
        val anyOffenders = offendersOf(goodResults, anyModality)
        assertTrue("any() 投递必须被点名：$anyOffenders", anyOffenders.any { it.contains("any()") })

        // M7 反例：把 PSI 有效性校验挪回 invokeLater 回调（EDT 上判 isValid）必须被点名
        val edtValidation = goodFactory
            .replace("revalidate(project, pair)\n", "")
            .replace(
                "app.invokeLater({ report() }, ModalityState.nonModal())",
                "app.invokeLater({ revalidate(project, pair); report() }, ModalityState.nonModal())",
            )
        val edtOffenders = offendersOf(goodResults, edtValidation)
        assertTrue(
            "EDT 上校验 PSI 有效性必须被点名：$edtOffenders",
            edtOffenders.any { it.contains("invokeLater 回调里") },
        )

        // refresh 段缺线程分流必红（T-PrewarmWilDeadlock）
        val noBranch = goodFactory.replace(
            "if (ApplicationManager.getApplication().isDispatchThread) {",
            "if (true) {",
        )
        val branchOffenders = offendersOf(goodResults, noBranch)
        assertTrue(
            "refresh 段缺线程分流必须被点名：$branchOffenders",
            branchOffenders.any { it.contains("未按线程分流") },
        )

        // 丢续接收口必红（failPrepareRequests / isPrepareActive）
        val noFailsafeResults = goodResults
            .replace("if (temp == null) failPrepareRequests(null, null, null, null, null, \"x\")", "")
            .replace("if (!isPrepareActive()) return", "")
        val failsafeOffenders = offendersOf(noFailsafeResults, goodFactory)
        assertTrue(
            "丢建链失败收口必须被点名：$failsafeOffenders",
            failsafeOffenders.any { it.contains("failPrepareRequests") },
        )
        assertTrue(
            "丢迟到续接守卫必须被点名：$failsafeOffenders",
            failsafeOffenders.any { it.contains("isPrepareActive") },
        )
    }

    /**
     * 自证：write-safe 投递四态——`any()` / 省略模态（默认模态）必红，`nonModal` /
     * `invokeLaterWriteSafe` 不误报。这是 2026-11-01 真机回归（any() → Write-unsafe）的锚点。
     */
    @Test
    fun `自证_write-safe投递判定能看见any与省略模态且放行nonModal`() {
        val anyModality = """
            internal fun DataSourceSelector.createTempPsiFileAsync(project: Project, onReady: (Any?) -> Unit) {
                val app = ApplicationManager.getApplication()
                app.executeOnPooledThread {
                    app.invokeLater({ onReady(createTempPsiFile(project)) }, ModalityState.any())
                }
            }
        """.trimIndent()
        val anyOffenders = writeSafeDeliveryOffenders(anyModality)
        assertTrue("any() 投递必须被点名：$anyOffenders", anyOffenders.any { it.contains("any()") })
        assertTrue(
            "any() 投递下的 onReady 必须被判「不在 write-safe 投递里」：$anyOffenders",
            anyOffenders.any { it.contains("不在 write-safe 投递里") },
        )

        val defaultModality = """
            internal fun DataSourceSelector.createTempPsiFileAsync(project: Project, onReady: (Any?) -> Unit) {
                ApplicationManager.getApplication().invokeLater({ onReady(createTempPsiFile(project)) })
            }
        """.trimIndent()
        assertTrue(
            "省略模态（defaultModalityState）投递必须被点名：${writeSafeDeliveryOffenders(defaultModality)}",
            writeSafeDeliveryOffenders(defaultModality).any { it.contains("不在 write-safe 投递里") },
        )

        assertEquals(
            "nonModal 投递不应误报：${writeSafeDeliveryOffenders(goodAsyncFactory())}",
            0,
            writeSafeDeliveryOffenders(goodAsyncFactory()).size,
        )

        val viaDocWriteContext = """
            internal fun DataSourceSelector.createTempPsiFileAsync(project: Project, onReady: (Any?) -> Unit) {
                val pair = createTempPsiFile(project)
                DocWriteContext.invokeLaterWriteSafe(project, "exec temp file ready") { onReady(pair) }
            }
        """.trimIndent()
        assertEquals(
            "DocWriteContext.invokeLaterWriteSafe（仓内 write-safe 收口）不应误报：" +
                "${writeSafeDeliveryOffenders(viaDocWriteContext)}",
            0,
            writeSafeDeliveryOffenders(viaDocWriteContext).size,
        )
    }

    /**
     * 自证：PSI 有效性判定的读锁作用域判定——裸判 / 「ReadAction 在别处但判定裸奔」/ 判定被删
     * 三态必红，合规包锁不误报。这是 UI-002（2026-11-04 真机 BGT 无读锁读 PSI）的锚点。
     */
    @Test
    fun `自证_PSI有效性判定必须落在ReadAction作用域内`() {
        // 形态一：裸判（真机形态——runCatching 只兜返回值，平台照样记 error）
        val nakedSelector = """
            internal fun DataSourceSelector.consumeCachedTempPsiFile(project: Project): Pair<VirtualFile, PsiFile>? {
                return cachedTempPsiFile?.let { (vf, psi) ->
                    if (isCacheUsable(project, vf) && runCatching { psi.isValid }.getOrDefault(true)) vf to psi else null
                }
            }
        """.trimIndent()
        val naked = psiValidityReadLockOffenders(nakedSelector, goodAsyncFactory())
        assertTrue("裸 psi.isValid 必须被点名：$naked", naked.any { it.contains("不在 ReadAction 作用域内") })

        // 形态二：ReadAction 出现在同一函数里，但没包住判定——「出现过 ReadAction」不算数
        val lockedElsewhere = """
            internal fun DataSourceSelector.consumeCachedTempPsiFile(project: Project): Pair<VirtualFile, PsiFile>? {
                val name = ReadAction.compute<String, Throwable> { vf.name }
                return cachedTempPsiFile?.let { (vf, psi) ->
                    if (isCacheUsable(project, vf) && psi.isValid) vf to psi else null
                }
            }
        """.trimIndent()
        val elsewhere = psiValidityReadLockOffenders(lockedElsewhere, goodAsyncFactory())
        assertTrue(
            "ReadAction 在别处、判定裸奔同样必须被点名（作用域判定不是「函数里出现过」）：$elsewhere",
            elsewhere.any { it.contains("不在 ReadAction 作用域内") },
        )

        // 形态三：判定被整体删除——空绿防线
        val deleted = """
            internal fun DataSourceSelector.consumeCachedTempPsiFile(project: Project): Pair<VirtualFile, PsiFile>? {
                return cachedTempPsiFile?.let { (vf, psi) -> if (isCacheUsable(project, vf)) vf to psi else null }
            }
        """.trimIndent()
        val gone = psiValidityReadLockOffenders(deleted, goodAsyncFactory())
        assertTrue("PSI 判定被整体删除必须被点名：$gone", gone.any { it.contains("未见 `.isValid`") })

        // 形态四：revalidate 的 EDT 侧判定裸奔
        val badFactory = """
            internal fun revalidate(project: Project, pair: Pair<VirtualFile, PsiFile>?): Pair<VirtualFile, PsiFile>? {
                if (pair == null) return null
                val (vf, psi) = pair
                if (psi.isValid) return pair
                val fresh = ReadAction.compute<PsiFile?, Throwable> { PsiManager.getInstance(project).findFile(vf) }
                return if (fresh != null && fresh.isValid) vf to fresh else null
            }
        """.trimIndent()
        val edtNaked = psiValidityReadLockOffenders(COMPLIANT_SELECTOR, badFactory)
        assertTrue("revalidate 的裸 psi.isValid 必须被点名：$edtNaked", edtNaked.any { it.contains("不在 ReadAction 作用域内") })
        assertTrue(
            "revalidate 锁外 fresh.isValid 必须被点名：$edtNaked",
            edtNaked.count { it.contains("不在 ReadAction 作用域内") } >= 2,
        )

        // 合规：两站点都在读锁内 → 不误报
        assertEquals(
            "合规读锁形态不应误报：${psiValidityReadLockOffenders(COMPLIANT_SELECTOR, goodAsyncFactory())}",
            0,
            psiValidityReadLockOffenders(COMPLIANT_SELECTOR, goodAsyncFactory()).size,
        )
    }

    /** 合成合规异步入口：两分支都经 `invokeLater(…, nonModal)` 投递（与生产形态同构）。 */
    private fun goodAsyncFactory(): String = """
        internal fun DataSourceSelector.createTempPsiFile(project: Project): Pair<VirtualFile, PsiFile>? {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            val psi = ReadAction.compute<PsiFile?, Throwable> { PsiManager.getInstance(project).findFile(vf) }
            return null
        }

        internal fun revalidate(project: Project, pair: Pair<VirtualFile, PsiFile>?): Pair<VirtualFile, PsiFile>? {
            if (pair == null) return null
            val (vf, psi) = pair
            if (ReadAction.compute<Boolean, Throwable> { psi.isValid }) return pair
            val fresh = ReadAction.compute<PsiFile?, Throwable> {
                PsiManager.getInstance(project).findFile(vf)?.takeIf { it.isValid }
            }
            return if (fresh != null) vf to fresh else null
        }

        internal fun DataSourceSelector.createTempPsiFileAsync(project: Project, onReady: (Any?) -> Unit) {
            val app = ApplicationManager.getApplication()
            if (!app.isDispatchThread) {
                val pair = revalidate(project, createTempPsiFile(project))
                app.invokeLater({ onReady(pair) }, ModalityState.nonModal())
                return
            }
            app.executeOnPooledThread {
                val pair = revalidate(project, createTempPsiFile(project))
                app.invokeLater({ onReady(pair) }, ModalityState.nonModal())
            }
        }
    """.trimIndent()

    /** 自证：工厂里出现第二处同步调用点必须被点名（守卫灵敏度）。 */
    @Test
    fun `自证_第二处同步调用点必被抓`() {
        val results = """
            internal fun DataSourceSelector.chooseAndPrepare(project: Project, sql: String) {
                createTempPsiFileAsync(project, sql) { temp -> continuePrepareAfterTempFile(project, temp) }
            }
        """.trimIndent()
        val factory = goodAsyncFactory() + "\n\n" + """
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
        private const val ON_READY = "onReady"

        /** 规则六站点：缓存命中路径（BGT）与异步窗口兜底（EDT 回调）。 */
        private const val CONSUME_CACHE = "consumeCachedTempPsiFile"
        private const val REVALIDATE = "revalidate"

        /** 规则六缺省语料（合规形态）：自证用例只喂 results/factory 两段时不误报。 */
        private const val COMPLIANT_SELECTOR = """
            internal fun DataSourceSelector.consumeCachedTempPsiFile(project: Project): Pair<VirtualFile, PsiFile>? {
                return cachedTempPsiFile?.let { (vf, psi) ->
                    val ok = try {
                        ReadAction.compute<Boolean, Throwable> { psi.isValid }
                    } catch (e: Throwable) {
                        false
                    }
                    if (isCacheUsable(project, vf) && ok) vf to psi else null
                }
            }
        """

        /** 投递形态白/黑名单：`invokeLater` 需看模态实参，另两个自身即 write-safe 收口。 */
        private val DELIVERY_CALLS = listOf(
            DeliveryCall("invokeLaterWriteSafe(", true),
            DeliveryCall("submitTransaction(", true),
            DeliveryCall("invokeLater(", false),
        )
    }
}
