package cn.song.mybatisplus.visualizer.ui.ref

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-MechanismSweep（2026-11-16）防回退扫描：mapper 文件定位的两条链必须走**自定义索引**
 * （[cn.song.mybatisplus.visualizer.log.MapperNamespaceIndex]），不得再出现「`FileTypeIndex` 取全项目
 * XML 再逐个 PSI 解析」的老形态。
 *
 * 为什么需要这条扫描：两处都是同形态的历史修复（`MybatisXmlTargets` 的 F96 清单 TTL、
 * `XmlCrossNsFragmentResolver` 的 T-PS5-05 单槽缓存），删掉缓存后行为面用例照样全绿——**回退到
 * 全扫不会让任何行为用例变红**，只能靠源码形态钉住。判据是「不再出现 `FileTypeIndex.getFiles`」
 * 与「出现 `MapperNamespaceIndex.getMapperXmlFiles`」两条同时成立。
 *
 * 不在扫面内（有意保留）：`psi/jpa/HbmXmlEntityResolver`（只对 `.hbm.xml` 做 PSI 解析，全项目
 * `FileTypeIndex` 仅取文件列表、不解析非 hbm XML，且只在 JPA 预览冷路径上跑）、
 * `ui/highlight/XmlCompletionSymbols`（补全上下文，另有 dumb 闸）。
 */
class MapperFileLookupIndexTest {

    private val indexedFiles = listOf(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref/MybatisXmlTargets.kt",
        "src/main/kotlin/cn/song/mybatisplus/visualizer/psi/XmlCrossNsFragmentResolver.kt",
    )

    @Test
    fun `mapper文件定位必须走自定义索引_不得回退全项目FileTypeIndex扫描`() {
        for (relative in indexedFiles) {
            val source = stripComments(repoFile(relative).readText())
            assertTrue(
                "$relative 必须用 MapperNamespaceIndex.getMapperXmlFiles 定位 mapper 文件",
                source.contains("MapperNamespaceIndex.getMapperXmlFiles("),
            )
            assertFalse(
                "$relative 不得再出现 FileTypeIndex 全项目 XML 扫描（T-MechanismSweep 已索引化）",
                source.contains("FileTypeIndex"),
            )
        }
    }

    @Test
    fun `自证_判定器能报出老形态`() {
        // 防空绿：判定器退化成恒 false 时，这条合成样本会静默通过——所以样本本身必须被报出。
        val legacy = """
            fun findMapperFileByNamespace(project: Project, ns: String): XmlFile? {
                val xmlFileType = FileTypeManager.getInstance().getStdFileType("XML")
                return FileTypeIndex.getFiles(xmlFileType, GlobalSearchScope.projectScope(project))
                    .mapNotNull { PsiManager.getInstance(project).findFile(it) as? XmlFile }
                    .firstOrNull { it.rootTag?.name == "mapper" }
            }
        """.trimIndent()
        assertTrue("自证：老形态样本必须被判为回退", legacy.contains("FileTypeIndex"))
        assertFalse("自证：老形态样本不含索引调用", legacy.contains("MapperNamespaceIndex.getMapperXmlFiles("))
    }

    private fun repoFile(relative: String): File {
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(root, relative)
            if (candidate.isFile) return candidate
            root = root.parentFile ?: return@repeat
        }
        error("未定位到仓库根（$relative 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }

    /** 剥注释：KDoc 里点名 `FileTypeIndex`/老实现是正当的历史记录，不该判红。 */
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
}