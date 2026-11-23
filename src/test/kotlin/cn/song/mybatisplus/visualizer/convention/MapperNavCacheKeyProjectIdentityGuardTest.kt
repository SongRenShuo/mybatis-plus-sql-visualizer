package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-PerfSweep32 券02 的**反向验证守卫**：`resolvedStatementKeys`（Mapper 双向导航共享正缓存）
 * 的键必须带 `project.locationHash` 前缀（性能分析第九期 §5.2 P3：键裸拼 `namespace#id` 时
 * 应用级容器在双工程同开下跨项目串号——A 工程确认过，B 工程直接信）。
 *
 * ## 为什么用源码扫描而不是运行时断言
 *
 * 与 `IbatisTypeAliasResolverHotPathTest` / `IbatisGutterHotPathTest` 同款方法论：「键含项目
 * 身份」是**构造形态**事实——运行时断言只能证明「本用例这次键没串」，证明不了「所有入账/
 * 命中/收回点都走同一构造」。键的触达点分散在两个 provider 的 add/remove/contains 上，
 * 任何人顺手在某处裸拼回旧形态，源码扫描直接红。
 *
 * ## 判定项
 *
 * 1. `cacheKey(` 构造函数存在且签名后 300 字符内含 `locationHash`（键身份的唯一来源）；
 * 2. 裸键构造旧形态（`val key = "$namespace#…"`）剥注释后 0 命中；
 * 3. `cacheKey(project, ` 调用点 ≥ 2（XML 侧 + Java 侧两个 key 构造点都必须走统一构造）。
 *
 * 自证（防空绿）：合成样本含旧形态必须被抓出、干净样本必须放行——判定器退化成恒空即红。
 */
class MapperNavCacheKeyProjectIdentityGuardTest {

    private val providerPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/MapperNavigationLineMarkerProviders.kt"

    /** 剥块注释与行注释（KDoc 点名旧形态不算违例）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(8) {
            if (File(dir, providerPath).exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("repo root with $providerPath not found from ${System.getProperty("user.dir")}")
    }

    private fun violationsIn(source: String): List<String> {
        val body = stripComments(source)
        val issues = mutableListOf<String>()
        if (!body.contains("internal fun cacheKey(project: Project, namespace: String, statementId: String)")) {
            issues.add("缺统一键构造 cacheKey(project, namespace, statementId)")
        }
        val cacheKeyIdx = body.indexOf("internal fun cacheKey(")
        val cacheKeyBody = if (cacheKeyIdx >= 0) body.substring(cacheKeyIdx, minOf(body.length, cacheKeyIdx + 300)) else ""
        if (!cacheKeyBody.contains("locationHash")) {
            issues.add("cacheKey 函数体未含 locationHash——项目身份来源丢失")
        }
        val bareKeys = Regex("\"\\\$namespace#").findAll(body).toList()
        if (bareKeys.isNotEmpty()) {
            issues.add("存在裸键构造旧形态 \"\$namespace#（${bareKeys.size} 处）")
        }
        val cacheKeyCallSites = Regex("cacheKey\\(project, ").findAll(body).count()
        if (cacheKeyCallSites < 2) {
            issues.add("cacheKey(project, …) 调用点不足 2 处（XML 侧 + Java 侧须都走统一构造），实际 $cacheKeyCallSites")
        }
        return issues
    }

    @Test
    fun `生产源键构造必须带项目身份`() {
        val source = File(findRepoRoot(), providerPath).readText()
        val issues = violationsIn(source)
        assertTrue("键卫生守卫违例：\n" + issues.joinToString("\n"), issues.isEmpty())
    }

    @Test
    fun `自证_含旧形态的合成样本必须被抓出`() {
        // 旧形态语料：键裸拼 namespace#id、cacheKey 函数体不带 locationHash、调用点 0 处。
        val dollar = "${'$'}"
        val synthetic = """
            class MapperXmlToJavaLineMarkerProvider {
                companion object {
                    internal fun cacheKey(project: Project, namespace: String, statementId: String): String =
                        "${dollar}namespace#${dollar}statementId"
                }
                private fun createMarker(element: PsiElement): LineMarkerInfo<*>? {
                    val key = "${dollar}namespace#${dollar}id"
                    resolvedStatementKeys.add(key)
                }
            }
        """.trimIndent()
        val issues = violationsIn(synthetic)
        assertTrue("自证失败：旧形态样本必须被抓出（判定器退化成恒空）", issues.isNotEmpty())
        assertTrue("自证样本应命中裸键构造", issues.any { it.contains("裸键构造") })
        assertTrue("自证样本应命中 locationHash 缺失", issues.any { it.contains("locationHash") })
    }

    @Test
    fun `自证_干净样本必须放行`() {
        // 新形态语料：cacheKey 带 locationHash、两侧调用点齐备、无裸键。
        val dollar = "${'$'}"
        val clean = """
            class MapperXmlToJavaLineMarkerProvider {
                companion object {
                    internal fun cacheKey(project: Project, namespace: String, statementId: String): String =
                        "${dollar}{project.locationHash}|${dollar}namespace|${dollar}statementId"
                }
                private fun createMarker(element: PsiElement): LineMarkerInfo<*>? {
                    val project = element.project
                    val key = cacheKey(project, namespace, id)
                    resolvedStatementKeys.add(key)
                }
                private fun createMethodMarker(element: PsiElement): LineMarkerInfo<*>? {
                    val project = element.project
                    val key = cacheKey(project, namespace, method.name)
                    resolvedStatementKeys.contains(key)
                }
            }
        """.trimIndent()
        val issues = violationsIn(clean)
        assertTrue("干净样本不得误报：${issues.joinToString()}", issues.isEmpty())
    }
}
