package cn.song.mybatisplus.visualizer.ui.gutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iBatis gutter **热路径零 resolve / 零索引查询**的源码扫描守卫
 * （T-Ibatis2Support 券 A 性能判据：`getLineMarkerInfo` 每到达一个 `XML_NAME` token 就被平台调用一次）。
 *
 * ## 为什么用源码扫描而不是运行时断言
 *
 * 「没调 resolve」是**不存在**的事实——运行时断言只能证明「这次没炸」，证明不了「没有一条慢路径」。
 * 源码扫描把禁止面写成清单，机制漂移（有人顺手加一次 classpath 探针 / 一次引用解析）时直接红。
 *
 * ## 判定面
 *
 * 被扫的函数体（剥注释后）：
 * - [IbatisXmlGutterLineMarkerProvider] 的 `getLineMarkerInfo`（平台热路径本体）；
 * - 同文件 `isStatementAnchor`（热路径唯一的判定助手，且被通用兜底检测器复用）；
 * - T-Ibatis2Gap 券 B 扩面（计划册 §六.3）：`IbatisXmlToJavaLineMarkerProvider` /
 *   `IbatisJavaToXmlLineMarkerProvider` 两个跳转 provider 的 `collectSlowLineMarkers` +
 *   `createMarker`（daemon 每趟判定路径；点击期的目标解析不在此面）。
 *
 * 禁止 token 分三类（任一命中即红）：
 * 1. **classpath / 索引查询**：`IbatisXmlRecognizer` / `sqlMapClientOnClasspath` / `JavaPsiFacade` /
 *    `findClass` / `GlobalSearchScope` / `SearchScope`；
 * 2. **引用解析**：`.resolve(` / `PsiReference` / `references` / `findUsages`；
 * 3. **全文件遍历**：`PsiTreeUtil` / `collectElements`（热路径只看「自身 + 父标签」，遍历属越界）。
 *
 * 自证（防空绿）：合成样本含禁用调用必须被抓出、干净样本必须放行——判定器退化成恒空即红。
 */
class IbatisGutterHotPathTest {

    private val providerRelativePath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisXmlGutterLineMarkerProvider.kt"

    private val detectorRelativePath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/xml/XmlInlineSqlDetector.kt"

    /**
     * T-Ibatis2Gap 券 B 扩面：两个跳转 provider 的扫描面。
     *
     * 只扫**函数体**（`collectSlowLineMarkers` 慢趟门控 + `createMarker` 锚定判定），不做整文件扫描——
     * 这两个文件里合法存在点击期目标解析（`IbatisJavaCallSites` 的词索引），整文件扫描会对着
     * 点击路径误报。daemon 每趟真正跑的判定路径就是被扫的这两个函数。
     */
    /**
     * 券 B 两个跳转 provider 被扫的 daemon 判定函数（慢趟门控 + 锚定判定）。
     *
     * T-IbatisCallPreview：Java→XML provider 的 `createMarker` 改为 `createMarkers`（一个调用点产出
     * 两枚 marker），两份 provider 的函数名不再相同，故按文件分列；签名再漂移时提取失败会直接 error。
     */
    private val navProviderScanMarkers: Map<String, List<String>> = mapOf(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisXmlToJavaLineMarkerProvider.kt" to
            listOf("override fun collectSlowLineMarkers", "private fun createMarker"),
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisJavaToXmlLineMarkerProvider.kt" to
            listOf("override fun collectSlowLineMarkers", "private fun createMarkers"),
    )

    /** 禁止 token（正则片段；判定前先剥注释，故 KDoc 里点名这些 API 不会误报）。 */
    private val forbiddenPatterns: List<Pair<String, String>> = listOf(
        "classpath/索引查询" to "IbatisXmlRecognizer",
        "classpath/索引查询" to "sqlMapClientOnClasspath",
        "classpath/索引查询" to "JavaPsiFacade",
        "classpath/索引查询" to "findClass",
        "classpath/索引查询" to "GlobalSearchScope",
        "classpath/索引查询" to "SearchScope",
        "引用解析" to "\\.resolve\\s*\\(",
        // T-Ibatis2Gap 券 B：`PsiReferenceExpression` 的 **类型检查**（`as?` 结构判定）是慢趟门控的
        // 合法 O(1) 形态（Java→XML 跳转 provider 用它锚调用行）；被禁的是「引用解析」本身
        // （拿 PsiReference / .resolve 取目标），故用负向先行排除类型名，不放过真解析形态
        "引用解析" to "PsiReference(?!Expression)",
        "引用解析" to "\\breferences\\b",
        "引用解析" to "findUsages",
        "全文件遍历" to "PsiTreeUtil",
        "全文件遍历" to "collectElements",
    )

    @Test
    fun `provider热路径_getLineMarkerInfo零resolve零索引查询`() {
        val source = readMain(providerRelativePath)
        val body = extractBracedBody(stripComments(source), "override fun getLineMarkerInfo")
            ?: error("无法提取 getLineMarkerInfo 方法体（签名形态漂移？）——扫描面失效按红处理")
        assertNoForbidden("getLineMarkerInfo", body)
    }

    @Test
    fun `provider热路径_isStatementAnchor零resolve零索引查询`() {
        val source = readMain(providerRelativePath)
        val body = extractBracedBody(stripComments(source), "internal fun isStatementAnchor")
            ?: error("无法提取 isStatementAnchor 函数体（签名形态漂移？）——扫描面失效按红处理")
        assertNoForbidden("isStatementAnchor", body)
    }

    /** 让位判定同样跑在热路径上（每个 XML_NAME token 一次），口径与接管方一致。 */
    @Test
    fun `让位判定_isClaimedByKnownStructure零resolve零索引查询`() {
        val source = readMain(detectorRelativePath)
        val body = extractBracedBody(stripComments(source), "fun isClaimedByKnownStructure")
            ?: error("无法提取 isClaimedByKnownStructure 函数体（签名形态漂移？）——扫描面失效按红处理")
        assertNoForbidden("isClaimedByKnownStructure", body)
    }

    /**
     * T-Ibatis2Gap 券 B 扩面（计划册 §六.3：新 provider 上线守卫必须同步扩面）：两个跳转
     * provider 的慢趟门控与锚定判定同样零 resolve / 零索引查询 / 零全文件遍历。项目级目标解析
     * 只许发生在点击 handler（[IbatisJavaCallSites] 词索引 / `IbatisXmlStatementTargets` 缓存），
     * daemon 每趟跑的判定函数不得出现这些形态。
     */
    @Test
    fun `券B两个跳转provider_慢趟门控与锚定判定零resolve零索引查询`() {
        for ((rel, markers) in navProviderScanMarkers) {
            val source = stripComments(readMain(rel))
            for (marker in markers) {
                val body = extractBracedBody(source, marker)
                    ?: error("无法提取 $rel 的 $marker 方法体（签名形态漂移？）——扫描面失效按红处理")
                assertNoForbidden("$rel#$marker", body)
            }
        }
    }

    /**
     * 券 B 扩面自证：判定器必须看得见「跳转 provider 慢趟里塞进项目级搜索」的真实违规形态
     * （合成 collectSlowLineMarkers 样本含词索引调用必须被抓出），干净门控样本必须放行。
     */
    @Test
    fun `自证_券B慢趟判定_含项目级搜索样本必抓_干净样本放行`() {
        val dirty = """
            override fun collectSlowLineMarkers(
                elements: MutableList<out PsiElement>,
                result: MutableCollection<in LineMarkerInfo<*>>,
            ) {
                val scope = GlobalSearchScope.projectScope(element.project)
                return null
            }
        """.trimIndent()
        assertTrue(
            "判定器必须看得见慢趟里的项目级搜索（否则扩面守卫是空绿）",
            forbiddenHitsIn(dirty).isNotEmpty(),
        )
        val clean = """
            override fun collectSlowLineMarkers(
                elements: MutableList<out PsiElement>,
                result: MutableCollection<in LineMarkerInfo<*>>,
            ) {
                val gutterState = runCatching { PluginSettings.getInstance().state }.getOrNull()
                if (gutterState != null && !gutterState.showGutterIcon) return
                if (!GutterGroups.enabledFor(javaClass, gutterState)) return
                elements.mapNotNullTo(result, ::createMarker)
            }
        """.trimIndent()
        assertEquals(
            "干净门控形态不得被误报：${forbiddenHitsIn(clean)}",
            emptyList<String>(),
            forbiddenHitsIn(clean),
        )
    }

    /** 整个 provider 文件（剥注释）都不得出现 classpath 探针：判定之外的地方同样不允许引入慢路径。 */
    @Test
    fun `provider整文件不含classpath探针`() {
        val code = stripComments(readMain(providerRelativePath))
        val hits = forbiddenPatterns
            .filter { (_, pattern) -> Regex(pattern).containsMatchIn(code) }
            .map { (kind, pattern) -> "$kind:$pattern" }
        assertEquals(
            "IbatisXmlGutterLineMarkerProvider 整文件命中禁用 API（热路径类文件不得引入 resolve/索引查询）：$hits",
            emptyList<String>(),
            hits,
        )
    }

    // ------------------------------------------------------------------
    // 自证：判定器必须看得见真实违规形态
    // ------------------------------------------------------------------

    @Test
    fun `自证_含classpath探针的样本必须被抓出`() {
        val sample = """
            override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
                if (!IbatisXmlRecognizer.sqlMapClientOnClasspath(element.project)) return null
                return null
            }
        """.trimIndent()
        assertTrue("判定器必须看得见 IbatisXmlRecognizer 调用（否则本守卫是空绿）", forbiddenHitsIn(sample).isNotEmpty())
    }

    @Test
    fun `自证_含引用解析的样本必须被抓出`() {
        val sample = """
            override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
                return tag.references.firstOrNull()?.resolve()?.let { null }
            }
        """.trimIndent()
        assertTrue("判定器必须看得见 references/resolve（否则本守卫是空绿）", forbiddenHitsIn(sample).size >= 2)
    }

    @Test
    fun `自证_干净样本必须放行`() {
        val sample = """
            override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
                val tag = (element as? XmlToken)?.parent as? XmlTag ?: return null
                if (tag.name !in STATEMENT_TAGS || tag.parentTag?.name != SQL_MAP_TAG) return null
                return null
            }
        """.trimIndent()
        assertEquals("零 resolve 形态不得被误报：${forbiddenHitsIn(sample)}", emptyList<String>(), forbiddenHitsIn(sample))
    }

    // ------------------------------------------------------------------
    // 判定内核与工具
    // ------------------------------------------------------------------

    private fun forbiddenHitsIn(code: String): List<String> =
        forbiddenPatterns.filter { (_, pattern) -> Regex(pattern).containsMatchIn(code) }
            .map { (kind, pattern) -> "$kind:$pattern" }

    private fun assertNoForbidden(where: String, code: String) {
        val hits = forbiddenHitsIn(code)
        assertTrue(
            "$where 命中禁用 API（gutter 热路径必须零 resolve / 零索引查询 / 零全文件遍历）：$hits",
            hits.isEmpty(),
        )
    }

    private fun readMain(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("扫描面失效（文件缺失）：$relative", file.isFile)
        return file.readText()
    }

    /** 取 [marker] 之后第一个配平花括号块（跑在剥注释后的文本上，注释里的花括号不干扰配平）。 */
    private fun extractBracedBody(code: String, marker: String): String? {
        val start = code.indexOf(marker)
        if (start < 0) return null
        val open = code.indexOf('{', start + marker.length)
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /** 剥行注释与块注释（KDoc 里点名 `IbatisXmlRecognizer` / `零 resolve` 属说明文字，不算违规）。 */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }
}
