package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BrowserUtil 泄漏收敛扫描守卫（T-TestGapSpeed T2，2026-11-04）。
 *
 * ## 背景
 *
 * 测试沙盒里 `BrowserUtil.browse`/`BrowserUtil.open` 不走沙盒隔离，直接 ShellExecute 拉起宿主机
 * 默认浏览器/文件管理器（见 docs/memory-archive/pitfalls/pitfall-test-sandbox-browser-leak.md，
 * 用户真机 Edge 周期性自动弹页的归因实录）。此前修复靠三处 `isUnitTestMode` 收敛 +
 * BannedApiSourceScanTest 的**文件级**守卫（含 browse 调用的文件须含 isUnitTestMode 字样），
 * 但该守卫只认 `BrowserUtil.browse(`、按文件判读且无豁免清单——`BrowserUtil.open(` 调用点
 * 完全在覆盖外，文件级判读也无法防「同文件新增一个未收敛调用点」。
 *
 * ## 规则（本守卫）
 *
 * src/main 每个 `BrowserUtil.browse(` / `BrowserUtil.open(` 调用点（掩码后计数，注释/字符串不算）
 * 必须满足其一，否则红：
 *  1. **收敛**——最小围合函数体内可见收敛标记（`isUnitTestMode` 或 fail-safe 助手 `isTestEnvironment`）；
 *     SafeBrowser.isTestEnvironment 的 fail-safe 形态另由 BannedApiSourceScanTest 的语义锚点用例钉死；
 *  2. **豁免**——文件路径在 [EXEMPT_FILES] 显式登记（逐条写理由），且豁免反腐烂：登记文件必须
 *     仍有调用点在场，调用点消失即红（同 WHITELIST 反腐烂口径）。
 *
 * 期望计数（[EXPECTED_TOTAL_CALL_SITES]）与生产调用点同步：新增/删除调用点必须同步本数并过收敛
 * 判据或登记豁免——防「扫面失效仍全绿」的空绿（计数不变则扫描器失明时立即红）。
 *
 * 已知判据边界（登记，勿当精度缺陷）：收敛判据是「围合函数体内含标记」的源码形态启发，不证明
 * 控制流可达性——调用点可以写在标记分支之外仍被判收敛；控制流正确性靠 SafeBrowser 收口本体
 * （fail-safe 短路）与语义锚点用例兜底。属性初始化 lambda（无 `fun` 围合）一律按未收敛处理，
 * 须登记豁免（PluginSettingsPanel 捕获目录缝即此形态）。
 *
 * 本文件被 BannedApiSourceScanTest 的 src/test 禁直调扫描自豁免（本守卫的扫描模式与自证语料
 * 本身含被扫片段）。零平台包引用，进 testFast 快桶。
 */
class BrowserUtilConvergenceGuardTest : BannedApiScanSupport() {

    // ------------------------------------------------------------------
    // 判定内核（可注入合成源码：主用例与自证语料共用同一实现）
    // ------------------------------------------------------------------

    /** 被扫调用形态：裸名与全限定名都以此子串命中（掩码后注释/字符串内容已涂白，不产生假命中）。 */
    private val browserUtilCallApis = listOf("BrowserUtil.browse(", "BrowserUtil.open(")

    /** 围合函数体内可见即判「收敛」的标记。 */
    private val convergedMarkers = listOf("isUnitTestMode", "isTestEnvironment")

    /**
     * 豁免清单（文件路径 → 理由，逐条人工登记）。**字面量表留在本类源码内，不提成共享常量**——
     * 前车之鉴（计划册 T2 陷阱项）：提成常量会让守卫提取不到直接红。
     */
    private val exemptFiles: Map<String, String> = mapOf(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt" to
            "捕获目录打开缝（internal var captureDirectoryOpener 默认 lambda）：打开本地捕获目录" +
                "（目录 URI，文件管理器行为非网页）；且本身是测试缝——PluginSettingsPanelUiTest 注入" +
                "替代实现观察目标路径，平台调用在测试进程不触达。属性初始化 lambda 无 fun 围合，" +
                "无法就地收敛，豁免登记；未来若测试改触达默认实现，须先加 isUnitTestMode 收敛。",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/share/ui/ShareDialog.kt" to
            "导出后打开刚保存的本地 HTML 报告（html 文件 URI，确会拉起默认浏览器渲染本地文件）：仅在" +
                "用户经 FileSaverDialog 手动确认保存后才触发；ShareDialogTest 不触达导出链" +
                "（grep 实证 0 调用）。未来任何测试触达导出链前，须先把这两处改走 util.SafeBrowser " +
                "或加 isUnitTestMode 收敛，不得沿用本豁免。",
    )

    /** 期望计数：与 src/main 生产调用点同步（当前 5 = SqlLogPanel 1 + SafeBrowser 1 + PluginSettingsPanel 1 + ShareDialog 2）。 */
    private val expectedTotalCallSites = 5

    /** 一个调用站点：文件相对路径、1-based 行号、命中的 API 形态、最小围合函数体（无 fun 围合为空串）。 */
    internal data class BrowserUtilCallSite(
        val path: String,
        val line: Int,
        val api: String,
        val enclosingBody: String,
    )

    /**
     * 调用站点提取器：掩码（注释/字符串涂白、保行）后找 API 调用，配最小围合函数体。
     * 函数体界定 = `fun` 头 → 参数表配平 → 首个 `{` 花括号配平；表达式体函数与 lambda 均无体，
     * 落在其内的调用点按「无围合」处理（未收敛，须豁免）。
     */
    internal fun browserUtilCallSites(sources: List<Pair<String, String>>): List<BrowserUtilCallSite> {
        val sites = mutableListOf<BrowserUtilCallSite>()
        for ((path, raw) in sources) {
            val masked = maskNonCode(raw)
            val bodies = functionBodies(masked)
            for (api in browserUtilCallApis) {
                var idx = masked.indexOf(api)
                while (idx >= 0) {
                    val body = bodies.lastOrNull { idx >= it.first && idx <= it.last }
                    sites += BrowserUtilCallSite(
                        path,
                        lineOf(masked, idx),
                        api,
                        body?.let { masked.substring(it.first, it.last + 1) } ?: "",
                    )
                    idx = masked.indexOf(api, idx + 1)
                }
            }
        }
        return sites
    }

    /** 违规点内核：不在豁免清单且围合体内无收敛标记的站点，报 `路径:行 API`。 */
    internal fun unconvergedBrowserUtilSites(sources: List<Pair<String, String>>): List<String> =
        browserUtilCallSites(sources)
            .filter { site -> site.path !in exemptFiles && convergedMarkers.none { site.enclosingBody.contains(it) } }
            .map { "${it.path}:${it.line} ${it.api}" }

    /**
     * masked 源码里的全部块体函数区间。`fun` 头到首个 `(` 之间只允许标识符/泛型/接收者字符，
     * 防 `fun interface` 的首 `(` 远距离误配把后续调用点误判进假围合体（漏报形态）。
     */
    private fun functionBodies(masked: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (true) {
            i = masked.indexOf("fun ", i)
            if (i < 0) break
            val tokenOk = i == 0 || !(masked[i - 1].isLetterOrDigit() || masked[i - 1] == '_')
            if (tokenOk && headerOpensBody(masked, i)) {
                val paren = masked.indexOf('(', i)
                val close = if (paren >= 0) matchingDelimiter(masked, paren, '(', ')') else -1
                if (close > 0) {
                    var j = close + 1
                    while (j < masked.length && masked[j].isWhitespace()) j++
                    if (j < masked.length && masked[j] == '{') {
                        val end = matchingDelimiter(masked, j, '{', '}')
                        if (end > 0) ranges += j..end
                    }
                }
            }
            i += 4
        }
        return ranges
    }

    /** `fun` 头到首个 `(` 之间是否只有函数名/泛型/接收者字符（遇 `{`/`=`/换行外的杂字符即放弃）。 */
    private fun headerOpensBody(masked: String, funIdx: Int): Boolean {
        var k = funIdx + 4
        while (k < masked.length) {
            val c = masked[k]
            when {
                c == '(' -> return true
                c.isLetterOrDigit() || c == '_' || c == '.' || c == '`' || c == '?' ||
                    c == '<' || c == '>' || c.isWhitespace() -> k++
                else -> return false
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // 主用例
    // ------------------------------------------------------------------

    @Test
    fun `src主源码BrowserUtil调用点必须处于收敛分支或登记豁免`() {
        val sources = rawMainSources()
        assertTrue(
            "src/main 扫描面为空（路径改名 / user.dir 异常？）——空扫描面会让本守卫静默空绿",
            sources.isNotEmpty(),
        )
        val sites = browserUtilCallSites(sources)
        assertEquals(
            "BrowserUtil 调用面期望计数漂移（新增/删除调用点必须同步 expectedTotalCallSites 并过收敛判据" +
                "或登记豁免——计数断言同时钉住扫描器不失明）：实际 ${sites.map { "${it.path}:${it.line} ${it.api}" }}",
            expectedTotalCallSites,
            sites.size,
        )
        val bad = unconvergedBrowserUtilSites(sources)
        assertTrue(
            "src/main 出现未收敛的 BrowserUtil 调用点（$bad）——测试沙盒触达即 ShellExecute 弹宿主机" +
                "浏览器/文件管理器（pitfall-test-sandbox-browser-leak 实录）。修法：调用点围合函数内加 " +
                "isUnitTestMode 短路、或改走 util.SafeBrowser（fail-safe 收口）、或确属测试进程不触达的" +
                "本地文件行为才可在 exemptFiles 登记豁免（逐条写理由）",
            bad.isEmpty(),
        )
    }

    @Test
    fun `豁免清单反腐烂_登记文件必须仍有调用点在场`() {
        // 同 EnumReachabilityGuardTest 的 WHITELIST 反腐烂口径：豁免条目的存在前提是「还有调用点
        // 被它豁免」——调用点收敛掉/删掉后豁免条目必须随之删除，否则豁免表变成「顺手弹个浏览器」
        // 的隐性许可池。
        val sites = browserUtilCallSites(rawMainSources())
        val pathsWithSites = sites.map { it.path }.toSet()
        val stale = exemptFiles.keys.filter { it !in pathsWithSites }
        assertTrue(
            "豁免清单腐烂：以下文件已无 BrowserUtil 调用点，豁免条目应随之删除——$stale",
            stale.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 自证语料（合成源码喂进同一判定实现；元守卫要求本用例名含自证关键词）
    // ------------------------------------------------------------------

    @Test
    fun `守卫自证_未收敛调用点必红_收敛豁免与注释形态各归其位`() {
        // ① 未收敛（裸调用，无守卫）必红：站点行号、路径、API 形态逐项钉住
        val unconverged = """
            class Demo {
                fun go(url: String) {
                    BrowserUtil.browse(url)
                }
            }
        """.trimIndent()
        val srcs = listOf("synthetic-unconverged.kt" to unconverged)
        val sites = browserUtilCallSites(srcs)
        assertEquals("合成样本应恰提取 1 个站点", 1, sites.size)
        assertEquals("站点行号按掩码文本（保行）计", 3, sites.single().line)
        assertEquals(
            "未收敛站点必须上报（路径:行 API）",
            listOf("synthetic-unconverged.kt:3 BrowserUtil.browse("),
            unconvergedBrowserUtilSites(srcs),
        )

        // ② 同围合函数内 isUnitTestMode 收敛 → 放行
        val converged = """
            fun go(url: String) {
                if (ApplicationManager.getApplication().isUnitTestMode) return
                BrowserUtil.browse(url)
            }
        """.trimIndent()
        assertTrue(
            "围合体内含 isUnitTestMode 应判收敛放行",
            unconvergedBrowserUtilSites(listOf("synthetic-converged.kt" to converged)).isEmpty(),
        )

        // ③ SafeBrowser 助手形态（isTestEnvironment）+ open 形态 → 放行（收口语义另由锚点用例钉死）
        val safeHelper = """
            fun go(url: String) {
                if (isTestEnvironment()) return
                BrowserUtil.open(url)
            }
        """.trimIndent()
        assertTrue(
            "围合体内含 isTestEnvironment 助手调用应判收敛放行",
            unconvergedBrowserUtilSites(listOf("synthetic-safe.kt" to safeHelper)).isEmpty(),
        )

        // ④ 豁免清单放行：未收敛样本落在豁免路径上不报（同时钉住清单键=仓根相对路径格式）
        val exemptPath = exemptFiles.keys.first()
        assertTrue(
            "豁免路径上的未收敛站点应放行",
            unconvergedBrowserUtilSites(listOf(exemptPath to unconverged)).isEmpty(),
        )

        // ⑤ 注释与字符串里的片段不算调用点（掩码涂白，防假命中虚增计数）
        val commentAndString = """
            fun go() {
                // BrowserUtil.browse( 注释里的片段不算
                log("BrowserUtil.browse( 字符串里的片段也不算")
            }
        """.trimIndent()
        assertEquals(
            "注释/字符串内片段不得计入站点",
            0,
            browserUtilCallSites(listOf("synthetic-noise.kt" to commentAndString)).size,
        )

        // ⑥ 属性初始化 lambda（无 fun 围合）必报——PluginSettingsPanel 捕获目录缝的真实形态
        val propertyLambda = """
            val opener = { dir: String -> BrowserUtil.open(dir) }
        """.trimIndent()
        assertEquals(
            "无 fun 围合的属性 lambda 调用点必须按未收敛上报",
            listOf("synthetic-lambda.kt:1 BrowserUtil.open("),
            unconvergedBrowserUtilSites(listOf("synthetic-lambda.kt" to propertyLambda)),
        )
    }
}
