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
 * 期望计数（[expectedTotalCallSites]）与生产调用点同步：新增/删除调用点必须同步本数并过收敛
 * 判据或登记豁免——防「扫面失效仍全绿」的空绿（计数不变则扫描器失明时立即红）。
 *
 * 已知判据边界（登记，勿当精度缺陷）：收敛判据是「围合函数体内含标记」的源码形态启发，不证明
 * 控制流可达性——调用点可以写在标记分支之外仍被判收敛；控制流正确性靠 SafeBrowser 收口本体
 * （fail-safe 短路）与语义锚点用例兜底。属性初始化 lambda（无方法围合）一律按未收敛处理，
 * 须登记豁免（PluginSettingsPanel 捕获目录缝即此形态）。
 *
 * 本文件被 BannedApiSourceScanTest 的 src/test 禁直调扫描自豁免（本守卫的扫描模式与自证语料
 * 本身含被扫片段）。零平台包引用，进 testFast 快桶。
 *
 * k2j 适配：扫描根 = src/main/java + .java；函数体界定按 **Java 方法形**（`)` `{` 且头部非
 * 控制关键字 / 非 `new` 表达式），其余判据与 master 逐字同源。
 */
class BrowserUtilConvergenceGuardTest : BannedApiScanSupport() {

    // 内核为实例层：maskNonCode/lineOf/matchingDelimiter 是 BannedApiScanSupport 的实例成员，companion 无实例接收者
        // ------------------------------------------------------------------
        // 判定内核（可注入合成源码：主用例与自证语料共用同一实现）
        // ------------------------------------------------------------------

        /** 被扫调用形态：裸名与全限定名都以此子串命中（掩码后注释/字符串内容已涂白，不产生假命中）。 */
        private val BROWSER_UTIL_CALL_APIS = listOf("BrowserUtil.browse(", "BrowserUtil.open(")

        /** 围合函数体内可见即判「收敛」的标记。 */
        private val CONVERGED_MARKERS = listOf("isUnitTestMode", "isTestEnvironment")

        /** Java 控制关键字（头部长这样的是控制流/匿名类，不是方法体，不参与围合）。 */
        private val NON_METHOD_HEADERS = setOf(
            "if", "while", "for", "switch", "catch", "synchronized", "new", "return", "do", "try", "else",
        )

        /**
         * 豁免清单（文件路径 → 理由，逐条人工登记）。**字面量表留在本类源码内，不提成共享常量**——
         * 前车之鉴（计划册 T2 陷阱项）：提成常量会让守卫提取不到直接红。
         */
        private val exemptFiles: Map<String, String> = mapOf(
            "src/main/java/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.java" to
                "捕获目录打开缝（captureDirectoryOpener 默认 lambda）：打开本地捕获目录" +
                    "（目录 URI，文件管理器行为非网页）；且本身是测试缝——PluginSettingsPanelUiTest 注入" +
                    "替代实现观察目标路径，平台调用在测试进程不触达。属性初始化 lambda 无方法围合，" +
                    "无法就地收敛，豁免登记；未来若测试改触达默认实现，须先加 isUnitTestMode 收敛。",
            "src/main/java/cn/song/mybatisplus/visualizer/share/ui/ShareDialog.java" to
                "导出后打开刚保存的本地 HTML 报告（html 文件 URI，确会拉起默认浏览器渲染本地文件）：仅在" +
                    "用户经 FileSaverDialog 手动确认保存后才触发；ShareDialogTest 不触达导出链" +
                    "（grep 实证 0 调用）。未来任何测试触达导出链前，须先把这两处改走 util.SafeBrowser " +
                    "或加 isUnitTestMode 收敛，不得沿用本豁免。",
        )

        /** 期望计数：与 src/main 生产调用点同步（当前 4 = SqlLogPanel 1 + SafeBrowser 1 + PluginSettingsPanel 1 + ShareDialog 1——T-PerfSweep30 04a（T-PerfSweep31 券03 移植）把两处导出回调收敛进共享 onSuccess 后 2→1）。 */
        private val EXPECTED_TOTAL_CALL_SITES = 4

        /** 一个调用站点：文件相对路径、1-based 行号、命中的 API 形态、最小围合函数体（无方法围合为空串）。 */
        internal data class BrowserUtilCallSite(
            val path: String,
            val line: Int,
            val api: String,
            val enclosingBody: String,
        )

        /**
         * 调用站点提取器：掩码（注释/字符串涂白、保行）后找 API 调用，配最小围合函数体。
         * 函数体界定 = Java 方法形（头 `(`…`)` `{`，头部非控制关键字/`new`）；lambda 体落在方法体
         * 之内即可被围合体覆盖，独立 lambda（属性初始化器）按「无围合」处理（未收敛，须豁免）。
         */
        internal fun browserUtilCallSites(sources: List<Pair<String, String>>): List<BrowserUtilCallSite> {
            val sites = mutableListOf<BrowserUtilCallSite>()
            for ((path, raw) in sources) {
                val masked = maskNonCode(raw)
                val bodies = functionBodies(masked)
                for (api in BROWSER_UTIL_CALL_APIS) {
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
                .filter { site -> site.path !in exemptFiles && CONVERGED_MARKERS.none { site.enclosingBody.contains(it) } }
                .map { "${it.path}:${it.line} ${it.api}" }

        /**
         * masked .java 源码里的全部方法体区间：`{` 的最近前缀是 `)` 且其配对 `(` 的头部
         * 是方法形（非控制关键字 / 非 `new` 匿名类）。类体/初始化块（`{` 前非 `)`）自然出局。
         */
        private fun functionBodies(masked: String): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var i = 0
            while (true) {
                val open = masked.indexOf('{', i)
                if (open < 0) break
                val headerStart = methodHeaderOpenParen(masked, open)
                if (headerStart >= 0) {
                    val end = matchingDelimiter(masked, open, '{', '}')
                    if (end > 0) ranges += open..end
                }
                i = open + 1
            }
            return ranges
        }

        /**
         * `{` 前回溯：最近 `)` → 向前配平到它的 `(` → 头部词法是「方法形」才返回 `(` 位；
         * 否则 -1。头部形：`(` 前的最后一个标识符不是控制关键字，且从 `(` 向回看到
         * `;`/`}`/`{`/换行前的可见字符里不含 `=`（赋值/字段初始化）与 `new`。
         */
        private fun methodHeaderOpenParen(masked: String, openBrace: Int): Int {
            var k = openBrace - 1
            while (k >= 0 && masked[k].isWhitespace()) k--
            if (k < 0 || masked[k] != ')') return -1
            val openParen = matchingDelimiterBackward(masked, k)
            if (openParen < 0) return -1
            // 头部（openParen 向前的可见字符）最后一个标识符
            var j = openParen - 1
            while (j >= 0 && masked[j].isWhitespace()) j--
            if (j < 0) return -1
            val identEnd = j
            while (j >= 0 && (masked[j].isLetterOrDigit() || masked[j] == '_')) j--
            val ident = masked.substring(j + 1, identEnd + 1)
            if (ident.isEmpty() || ident in NON_METHOD_HEADERS) return -1
            // 头部里出现 `new `（匿名类体）或 `=`（赋值右值 lambda）→ 非方法
            val header = masked.substring(maxOf(0, openParen - 400), openParen)
            if (Regex("\\bnew\\s+[^()]*$").containsMatchIn(header.substringAfterLast(';'))) return -1
            return openParen
        }

        /** 从 `)` 向前找配对 `(`（忽略嵌套与掩码内内容——掩码已保证串内括号被涂白）。 */
        private fun matchingDelimiterBackward(code: String, close: Int): Int {
            var depth = 0
            var i = close
            while (i >= 0) {
                when (code[i]) {
                    ')' -> depth++
                    '(' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i--
            }
            return -1
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
            EXPECTED_TOTAL_CALL_SITES,
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
                void go(String url) {
                    BrowserUtil.browse(url);
                }
            }
        """.trimIndent()
        val srcs = listOf("synthetic-unconverged.java" to unconverged)
        val sites = browserUtilCallSites(srcs)
        assertEquals("合成样本应恰提取 1 个站点", 1, sites.size)
        assertEquals("站点行号按掩码文本（保行）计", 3, sites.single().line)
        assertEquals(
            "未收敛站点必须上报（路径:行 API）",
            listOf("synthetic-unconverged.java:3 BrowserUtil.browse("),
            unconvergedBrowserUtilSites(srcs),
        )

        // ② 同围合方法内 isUnitTestMode 收敛 → 放行
        val converged = """
            void go(String url) {
                if (ApplicationManager.getApplication().isUnitTestMode) return;
                BrowserUtil.browse(url);
            }
        """.trimIndent()
        assertTrue(
            "围合体内含 isUnitTestMode 应判收敛放行",
            unconvergedBrowserUtilSites(listOf("synthetic-converged.java" to converged)).isEmpty(),
        )

        // ③ 注释里的 API 字样不算调用点（掩码涂白）
        val commented = """
            // 见 BrowserUtil.browse( 之上的讨论
            /* BrowserUtil.open( 也写在注释里 */
            void go(String url) {
                SafeBrowser.browse(url);
            }
        """.trimIndent()
        assertEquals(
            "注释形态不得计入站点",
            0,
            browserUtilCallSites(listOf("synthetic-commented.java" to commented)).size,
        )
    }
}
