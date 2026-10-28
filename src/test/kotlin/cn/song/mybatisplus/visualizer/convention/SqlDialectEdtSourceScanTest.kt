package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：主源码不得直取平台 SQL 语法高亮器（T-SqlDialectPrewarm）。
 *
 * ## 守什么
 *
 * `SyntaxHighlighterFactory.getSyntaxHighlighter(SQL FileType, …)` 会经 `SqlLanguageSubstitutor`
 * 落到 `SqlDialectMappings.getMapping`，而 `PerFileMappingsBase.ensureStateLoaded` 是**一次性延迟
 * 加载**——首次查询逐条 `VirtualFileManager.findFileByUrl` 解析持久化映射，内部
 * `PersistentFSImpl.findChildInfo → FSRecordsImpl.update` 被平台归类为慢操作。首查若落在 EDT
 * （项目打开时工具窗内容装配就是），平台记 `Slow operations are prohibited on EDT` 并把本插件
 * 归为 blame（真机栈 2026-10-28）。故除**预热助手**（它把首查放到 BGT 做完）外，主源码不得再直取。
 *
 * ## 为什么用「精确清单」而不是「零命中」
 *
 * 允许清单只有 [ALLOWED_SITE] 一处，且断言是**等值**而非「子集」：一旦预热助手不再直取（被重构走
 * 别的 API），清单变空 → 本用例立刻红，提示同步改这里，而不是静默留一条失效豁免（同仓内
 * `EnumReachabilityGuardTest.WHITELIST` 的反腐烂口径）。
 *
 * ## 已知漏报面
 *
 * 只认「类名 + `.getSyntaxHighlighter(`」这一种形态（允许跨行与全限定写法）。经别名 import、
 * 反射、或包装函数间接调用的形态抓不到；这类形态要靠别的守卫或人眼，不能当已覆盖。
 */
class SqlDialectEdtSourceScanTest : BannedApiScanSupport() {

    /**
     * 主源码中直取平台高亮器的站点（相对仓库根的路径）。
     *
     * @param source 传入合成源码时只判该片段（灵敏度自证用，返回 `sample` 占位）；null = 扫 src/main
     *   全量（`.kt` + `.java`，已剥注释）。
     */
    internal fun syntaxHighlighterFactorySites(source: String? = null): List<String> {
        if (source != null) {
            val stripped = stripComments(source)
            return List(SITE.findAll(stripped).count()) { "sample" }
        }
        return mainKotlinAndJavaSources()
            .filter { (_, code) -> SITE.containsMatchIn(code) }
            .map { (path, _) -> path }
    }

    @Test
    fun test主源码只有预热助手直取平台SQL高亮器() {
        val sites = syntaxHighlighterFactorySites()
        assertEquals(
            "平台 SQL 高亮器只允许在预热助手内直取（其余处会触发 SQL 方言映射延迟加载，EDT 上即平台" +
                "慢操作断言）。新增调用点请改走 SqlDialectMappingPrewarm；命中：$sites",
            listOf(ALLOWED_SITE),
            sites,
        )
    }

    // ------------------------------------------------------------------
    // 灵敏度自证（元守卫要求判定内核被自证语料引用）
    // ------------------------------------------------------------------

    @Test
    fun 自证_裸调用与跨行全限定调用均被抓() {
        assertFalse(
            "裸调用漏抓",
            syntaxHighlighterFactorySites("val h = SyntaxHighlighterFactory.getSyntaxHighlighter(t, p, null)")
                .isEmpty(),
        )
        assertFalse(
            "跨行 + 全限定写法是真实历史形态（LogToSqlPanel 2026-10 之前就长这样），必须能抓",
            syntaxHighlighterFactorySites(
                """
                val h = com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
                    .getSyntaxHighlighter(fileType, project, null)
                """.trimIndent(),
            ).isEmpty(),
        )
    }

    @Test
    fun 自证_JavaSyntaxHighlighterFactory与自研EP类不得误伤() {
        assertTrue(
            "JavaSyntaxHighlighterFactory 是 Java 高亮链（无 per-file 方言映射），误伤会让守卫靠误报换绿灯",
            syntaxHighlighterFactorySites(
                "JavaSyntaxHighlighterFactory.getSyntaxHighlighter(JavaLanguage.INSTANCE, project, null)",
            ).isEmpty(),
        )
        assertTrue(
            "自研 EP 类的同名方法（SqlMapperSyntaxHighlighterFactory.getSyntaxHighlighter）不得被抓",
            syntaxHighlighterFactorySites("SqlMapperSyntaxHighlighterFactory.getSyntaxHighlighter(project, vf)")
                .isEmpty(),
        )
    }

    @Test
    fun 自证_注释里的调用不算() {
        assertTrue(
            "注释里的示例调用不得算违规（剥注释后应无命中）",
            syntaxHighlighterFactorySites("// SyntaxHighlighterFactory.getSyntaxHighlighter(t, p, null)")
                .isEmpty(),
        )
    }

    private fun mainKotlinAndJavaSources(): List<Pair<String, String>> =
        listOf("src/main/kotlin", "src/main/java").flatMap { rel ->
            File(repoRoot(), rel).walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .map { f ->
                    f.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(f.readText())
                }
                .toList()
        }

    private companion object {
        /**
         * 唯一允许点：预热助手把这次首查放到 BGT（见类头）。
         */
        const val ALLOWED_SITE = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/SqlDialectMappingPrewarm.kt"

        /**
         * 类名 + `.getSyntaxHighlighter(`；`\s*` 覆盖真实历史里的换行写法，前置断言排除
         * `JavaSyntaxHighlighterFactory` / `SqlMapperSyntaxHighlighterFactory` 这类以该类名结尾的别名，
         * 同时放行 `com.intellij.openapi.fileTypes.` 全限定前缀。
         */
        val SITE = Regex("""(?<![A-Za-z0-9_])SyntaxHighlighterFactory\s*\.\s*getSyntaxHighlighter\s*\(""")
    }
}