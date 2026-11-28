package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D264 守卫：伪条件白名单必须覆盖 converter 的赋值方法面（`PsiConditionWhitelistCoverageGuardTest`）。
 *
 * ## 要解决的问题（本轮两次实证）
 *
 * 车道 A 的修 4 引入了 `isPseudoConditionCall`——链上「既非条件、也非结构、也非工厂」的方法名
 * 会被当作伪条件从链上剔除（防 `buildQuery` 这类工具方法名被渲染成条件）。
 * 它的白名单是 `WrapperLocator.CONDITION_CHAIN_METHOD_NAMES`。
 *
 * 但**白名单与 converter 的能力面会漂移**，本轮同一形态踩了两次：
 *  1. 车道 A 漏 `setSql`/`setIncrBy`/`setDecrBy`/`setIfExists` —— CI 分片 1 实测红
 *     （S58 期望 `SET status = 2, updated_at = now()` 实际只剩 `SET status = 2`）；
 *  2. 车道 B 的修 5「未知方法可见化」把 MPJ 逻辑删除六方法与 `on(...)` 误判为未知——
 *     那是**假警告**（条件其实被正常消费），由 B 自己复查抓到。
 *
 * 两次都是「某个方法语义上属于链、但不在白名单」→ 判定函数误伤。人工逐条对账不可靠，
 * 故把「白名单 ⊇ converter 赋值方法集」做成结构性断言。
 *
 * ## 判据
 *
 * 1. `LambdaUpdateConverter.SET_METHODS` 的每个方法名必须在 `CONDITION_CHAIN_METHOD_NAMES` 里；
 * 2. `UpdateJoinConverter.SET_METHODS` 同上（它是全集，含 `setApply`/`setUpdateEntity*`/`setAlias`）；
 * 3. `QueryWrapperParser` 的条件方法分派表里的方法名也必须在白名单里（防反向漏）。
 *
 * 判据 1、2 用**源码文本提取**（两个 `SET_METHODS` 是 private，跨文件读取源码比反射更稳，
 * 也符合本仓 `*SourceScanTest` 的一贯做法）。
 */
class PsiConditionWhitelistCoverageGuardTest {

    private val wrapperLocatorPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/psi/WrapperLocator.kt"
    private val lambdaUpdatePath = "src/main/kotlin/cn/song/mybatisplus/visualizer/converter/LambdaUpdateConverter.kt"
    private val updateJoinPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/converter/UpdateJoinConverter.kt"
    private val parserPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/converter/QueryWrapperParser.kt"

    /** 判定内核：converter 支持但白名单缺失的方法名（空集 = 合规）。 */
    internal fun uncoveredSettMethods(
        whitelistSource: String,
        converterSources: List<String>,
    ): List<String> {
        val whitelist = condWhitelistNames(whitelistSource)
        return converterSources
            .flatMap { setMethodNamesOf(it) }
            .distinct()
            .filter { it !in whitelist }
            .sorted()
    }

    /**
     * `CONDITION_CHAIN_METHOD_NAMES` 里的字面量方法名。
     *
     * 必须定位**定义行**（`val CONDITION_CHAIN_METHOD_NAMES`）而不是首次出现处——
     * 该常量在 `isPseudoConditionCall` 里先被引用（`if (name in CONDITION_CHAIN_METHOD_NAMES)`），
     * 从引用处往后找第一个 `setOf(` 会抓到 `MPJ_STRUCTURE_METHOD_NAMES`（本轮自测抓到的自身 bug）。
     */
    internal fun condWhitelistNames(source: String): Set<String> {
        val defIdx = Regex("val\\s+CONDITION_CHAIN_METHOD_NAMES").find(source)?.range?.first ?: return emptySet()
        val setOfIdx = source.indexOf("setOf(", defIdx)
        if (setOfIdx < 0) return emptySet()
        var depth = 0
        var i = setOfIdx + "setOf".length
        val end = run {
            while (i < source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return@run i }
                }
                i++
            }
            source.length
        }
        val body = source.substring(setOfIdx, end)
        return Regex("\"([A-Za-z][A-Za-z0-9]*)\"").findAll(body).map { it.groupValues[1] }.toSet()
    }

    /**
     * converter 侧的 SET 方法名字面量：取该文件全部 `const val SET* = "..."` 声明的右值
     * （两个 converter 都用常量拼 SET_METHODS，直接提取常量更稳、不受集合换行影响）。
     */
    internal fun setMethodNamesOf(source: String): List<String> =
        Regex("const val\\s+SET[A-Z_]*\\s*=\\s*\"([A-Za-z0-9]+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

    /**
     * 判定内核之二：条件变体后缀必须两侧同源（空集 = 合规）。
     *
     * 解析侧 `QueryWrapperParser` 用 `endsWith("IfExists")` / `endsWith("IfNotEmpty")` 把变体名
     * 归一化回基底条件方法；收集侧 `WrapperLocator.stripConditionVariantSuffix` 用同一组后缀判断
     * 「这条调用算不算条件」。两侧一旦漂移，就会出现「解析器认识、收集器不认识」的方法名，
     * 表现为条件被静默剔除——D264 集成期 `eqIfExists`/`likeRightIfExists` 就是这样丢的。
     *
     * 返回对称差（只在一侧出现的后缀），按两侧各自从源码字面量提取。
     */
    internal fun variantSuffixDrift(
        parserSource: String,
        locatorSource: String,
    ): List<String> {
        val parser = parserSuffixes(parserSource)
        val locator = locatorSuffixes(locatorSource)
        return (parser - locator).sorted().map { "仅解析侧:$it" } +
            (locator - parser).sorted().map { "仅收集侧:$it" }
    }

    /** 解析侧后缀：`endsWith("X")` 的字面量。 */
    internal fun parserSuffixes(source: String): Set<String> =
        Regex("endsWith\\(\"([A-Za-z][A-Za-z0-9]*)\"\\)")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

    /** 收集侧后缀：`stripConditionVariantSuffix` 函数体内的 `endsWith("X")` 字面量。 */
    internal fun locatorSuffixes(source: String): Set<String> {
        val start = Regex("fun\\s+stripConditionVariantSuffix").find(source)?.range?.first ?: return emptySet()
        val body = source.substring(start, minOf(source.length, start + 1200))
        return parserSuffixes(body)
    }

    @Test
    fun `伪条件白名单必须覆盖converter赋值方法面`() {
        val whitelist = readSource(wrapperLocatorPath)
        val converters = listOf(readSource(lambdaUpdatePath), readSource(updateJoinPath))
        val missing = uncoveredSettMethods(whitelist, converters)
        assertTrue(
            "CONDITION_CHAIN_METHOD_NAMES 漏了 converter 支持的赋值方法：$missing\n" +
                "这些方法会被 isPseudoConditionCall 当伪条件剔除，产物丢 SET 子句" +
                "（D264 实测：S58 期望 `SET status = 2, updated_at = now()` 实际只剩 `SET status = 2`）。\n" +
                "修法：把缺的方法名补进 $wrapperLocatorPath 的 CONDITION_CHAIN_METHOD_NAMES。",
            missing.isEmpty(),
        )
    }

    @Test
    fun `条件变体后缀必须解析侧与收集侧同源`() {
        val drift = variantSuffixDrift(readSource(parserPath), readSource(wrapperLocatorPath))
        assertTrue(
            "条件变体后缀两侧不一致：$drift\n" +
                "解析侧（QueryWrapperParser）用 endsWith 归一化变体名回基底条件方法，" +
                "收集侧（WrapperLocator.stripConditionVariantSuffix）必须用同一组后缀，\n" +
                "否则「解析器认识、收集器不认识」的方法会被当伪条件静默剔除" +
                "（D264 实测：eqIfExists/likeRightIfExists 被剔 → WHERE 少两条 AND）。",
            drift.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 自证语料
    // ------------------------------------------------------------------

    /** 自证：仅解析侧有后缀时必被点名（覆盖本轮真实形态）。 */
    @Test
    fun `自证_仅解析侧有后缀必被点名`() {
        val parser = """rawCall.methodName.endsWith("IfExists") -> rawCall.copy(methodName = "")"""
        val locator = """private fun stripConditionVariantSuffix(name: String): String = name"""
        val drift = variantSuffixDrift(parser, locator)
        assertTrue("仅解析侧有 IfExists 必须被点名，实际=$drift", drift == listOf("仅解析侧:IfExists"))
    }

    /** 自证：两侧同源时放行（防「永远报错」型假守卫）。 */
    @Test
    fun `自证_两侧同源时放行`() {
        val parser = """call.methodName.endsWith("IfExists")"""
        val locator = """private fun stripConditionVariantSuffix(name: String) = when { name.endsWith("IfExists") -> 1 else -> 2 }"""
        assertTrue("同源时不得报错", variantSuffixDrift(parser, locator).isEmpty())
    }

    /** 自证：收集侧函数被删/改名时不得静默空转。 */
    @Test
    fun `自证_收集侧函数缺失时不静默空转`() {
        val parser = """call.methodName.endsWith("IfExists")"""
        val locator = """// stripConditionVariantSuffix 被删了"""
        val drift = variantSuffixDrift(parser, locator)
        assertTrue("收集侧函数缺失必须被点名，实际=$drift", drift.contains("仅解析侧:IfExists"))
    }

    /** 自证：白名单缺 `setSql` 时，判定内核必须点名它（不得空转）。 */
    @Test
    fun `自证_白名单缺setSql必被点名`() {
        val whitelist = """
            private val CONDITION_CHAIN_METHOD_NAMES: Set<String> = setOf("eq", "ne", "set")
        """.trimIndent()
        val converter = """
            private const val SET = "set"
            private const val SET_SQL = "setSql"
        """.trimIndent()
        val missing = uncoveredSettMethods(whitelist, listOf(converter))
        assertTrue("缺 setSql 必须被点名，实际=$missing", missing == listOf("setSql"))
    }

    /** 自证：白名单齐备时放行（防「永远报错」型假守卫）。 */
    @Test
    fun `自证_白名单齐备时放行`() {
        val whitelist = """
            private val CONDITION_CHAIN_METHOD_NAMES: Set<String> = setOf("eq", "set", "setSql")
        """.trimIndent()
        val converter = """
            private const val SET = "set"
            private const val SET_SQL = "setSql"
        """.trimIndent()
        assertTrue("齐备时不得报错", uncoveredSettMethods(whitelist, listOf(converter)).isEmpty())
    }

    /** 自证：白名单整体缺失（改名/删除）时必须报错，不能静默空转。 */
    @Test
    fun `自证_白名单缺失时不静默空转`() {
        val converter = """
            private const val SET_SQL = "setSql"
        """.trimIndent()
        val missing = uncoveredSettMethods("// 白名单被删了", listOf(converter))
        assertTrue("白名单缺失必须把 converter 的方法全点名，实际=$missing", missing.contains("setSql"))
    }

    private fun readSource(relPath: String): String {
        val f = java.io.File(relPath)
        assertTrue("源码不存在（工作目录不对？）：$relPath", f.isFile)
        return f.readText()
    }
}
