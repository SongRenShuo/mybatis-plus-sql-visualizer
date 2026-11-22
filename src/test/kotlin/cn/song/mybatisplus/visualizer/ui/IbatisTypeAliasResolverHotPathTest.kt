package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-PerfSweep31 券01 的**反向验证守卫**：`IbatisTypeAliasResolver` 不得再出现「模块内容根全走查」
 * 形态（性能分析第八期 §4.3 P2：v1 首次调用对整模块 `iterateChildrenRecursively` + 逐 XML 建 PSI，
 * 跑在预览点击链的持读锁 `ReadAction.compute` 内）。
 *
 * ## 为什么用源码扫描而不是运行时断言
 *
 * 与 `IbatisGutterHotPathTest` 同款方法论：「没走查」是**不存在**的事实——运行时断言只能证明
 * 「这次没炸」，证明不了「没有一条慢路径」。源码扫描把禁止面写成清单，机制漂移（有人顺手把
 * v1 全走查加回来）时直接红。
 *
 * ## 禁止 token（任一命中即红）
 *
 * 1. **VFS 全走查**：`iterateChildrenRecursively` / `VfsUtilCore` / `ModuleRootManager`
 *    （模块内容根枚举是走查的入口面）；
 * 2. **逐文件建 PSI 的批量形态**：`ModuleManager`（v1 用它按名字找模块再枚举根；v2 的模块收窄
 *    走 [ModuleUtilCore] 按文件归属判，不枚举）。
 *
 * 允许面：`ModuleUtilCore.findModuleForFile`（v2 模块收窄的合法形态）、`PsiManager.findFile`
 * （对**索引命中候选**的精确匹配读，量级=候选数个文件）。
 *
 * 自证（防空绿）：合成样本含禁用调用必须被抓出、干净样本必须放行——判定器退化成恒空即红。
 */
class IbatisTypeAliasResolverHotPathTest {

    private val resolverPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/IbatisTypeAliasResolver.kt"

    /** 禁止 token（子串判定；剥注释后命中才计——KDoc 里点名这些 API 不算）。 */
    private val forbiddenTokens: List<String> = listOf(
        "iterateChildrenRecursively",
        "VfsUtilCore",
        "ModuleRootManager",
        "ModuleManager",
    )

    /** 剥块注释与行注释（与 IbatisGutterHotPathTest 同款口径：KDoc 点名 API 不误报）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(8) {
            if (File(dir, resolverPath).exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("repo root with $resolverPath not found from ${System.getProperty("user.dir")}")
    }

    private fun violationsIn(path: String, source: String): List<String> {
        val body = stripComments(source)
        return forbiddenTokens.filter { body.contains(it) }
    }

    @Test
    fun test生产文件不得含模块全走查形态() {
        val source = File(findRepoRoot(), resolverPath).readText()
        val violations = violationsIn(resolverPath, source)
        assertTrue(
            "IbatisTypeAliasResolver 出现全走查形态回归（T-PerfSweep31 券01 守卫）：$violations —— " +
                "别名定位必须走 SqlMapConfigAliasIndex 索引查询，不得枚举模块内容根/逐文件建 PSI",
            violations.isEmpty(),
        )
        // 空绿防御：v2 主查询锚必须在场——没有它，上面禁用词全删也会绿
        assertTrue(
            "源文件应包含索引主查询（SqlMapConfigAliasIndex.getConfigFilesByAlias）",
            stripComments(source).contains("SqlMapConfigAliasIndex"),
        )
    }

    @Test
    fun test判定器自证_含禁用调用的合成样本必须被抓出() {
        val synthetic = """
            import com.intellij.openapi.vfs.VfsUtilCore
            import com.intellij.openapi.roots.ModuleRootManager
            fun scan(project: Project, moduleName: String) {
                val module = ModuleManager.getInstance(project).findModuleByName(moduleName)
                for (root in ModuleRootManager.getInstance(module).contentRoots) {
                    VfsUtilCore.iterateChildrenRecursively(root, { it.isDirectory }, { true })
                }
            }
        """.trimIndent()
        val violations = violationsIn("synthetic", synthetic)
        assertEquals(
            "合成样本含全部四类禁用 token，判定器必须全数抓出（抓不出=守卫恒空=空绿）",
            forbiddenTokens.toSet(),
            violations.toSet(),
        )
    }

    @Test
    fun test判定器自证_干净样本必须放行() {
        val clean = """
            import com.intellij.openapi.module.ModuleUtilCore
            val candidates = SqlMapConfigAliasIndex.getConfigFilesByAlias(project, alias)
            val module = ModuleUtilCore.findModuleForFile(vf, project)?.name ?: continue
        """.trimIndent()
        assertEquals(
            "干净样本（索引查询 + 按文件归属收窄）不得命中任何禁用 token",
            emptyList<String>(),
            violationsIn("clean", clean),
        )
    }
}
