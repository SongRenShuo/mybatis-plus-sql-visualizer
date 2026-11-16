package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-IndexScanSweep2 P1 防回退扫描（对应计划 §六 A2 的源码形态面）：
 * `XmlCompletionSymbols.crossFileSqlIds` / `configPropertyKeys` 的**方法体**不得再出现
 * 「`FileTypeIndex` 取全项目 XML」「`PsiManager.findFile` 逐个建 PSI」的现扫形态——改造后清单来自
 * 项目级名称清单服务 [XmlSymbolNameIndexCache]。
 *
 * 为什么按**方法体**而不是整文件扫：旧现扫实现按计划保留成同文件的私有 `legacyXxx`（Registry 回退
 * 通道），整文件扫会把合法的回退实现也判红。故本类自带「方法体提取」内核，并用合成样本自证它能
 * 区分 `crossFileSqlIds` 与 `legacyCrossFileSqlIds`（**同前缀陷阱**：naive 子串匹配会把后者算进前者）。
 *
 * 行为面（只构建一次 / 改文件立即生效 / dumb 态）由 [XmlSymbolNameIndexCacheTest] 承载；本类只钉源码形态。
 *
 * 命名注意：本类**刻意不含** `Guard` 且不以 `SourceScanTest` 结尾——它不是「判定内核型守卫」，
 * 不进 `convention/GuardSelfTestCoverageMetaGuardTest` 的登记面（该类只要求那两类命名登记）。
 */
class XmlCompletionNameIndexWiringTest {

    private val sourceFile =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/highlight/XmlCompletionSymbols.kt"

    private val wiredFns = listOf("crossFileSqlIds", "configPropertyKeys")

    private val banned = listOf("FileTypeIndex", "findFile(")

    @Test
    fun `清单来源方法体不得回退现扫形态_且必须经名称清单服务`() {
        val source = repoFile(sourceFile).readText()
        for (fn in wiredFns) {
            val body = bodyOf(source, fn)
            assertNotNull("$fn 的方法体未定位到——扫描面失效即空绿，须先修提取内核", body)
            if (body == null) return
            assertEquals(
                "$fn 方法体出现现扫形态（T-IndexScanSweep2 已改为项目级名称清单服务，回退实现应留在 legacyXxx）：$body",
                emptyList<String>(),
                bannedTokens(body),
            )
            assertTrue(
                "$fn 必须经 XmlSymbolNameIndexCache 取清单（否则要么未接线、要么把现扫留在热路径）：$body",
                body.contains("XmlSymbolNameIndexCache"),
            )
        }
    }

    /**
     * 自证：① 老形态 `FileTypeIndex` 必被判出；② 同前缀的 `legacyCrossFileSqlIds` 内容**不得**被算进
     * `crossFileSqlIds`（否则本守卫可以用「把现扫挪进 legacyXxx」洗绿）；③ 干净形态零误报。
     */
    @Test
    fun `自证_方法体提取区分同前缀函数且报出老形态`() {
        val synthetic = """
            class S {
                fun crossFileSqlIds(p: Project): List<String> = runCatching {
                    FileTypeIndex.getFiles(X).map { it }
                }.getOrElse { emptyList() }

                private fun legacyCrossFileSqlIds(p: Project): List<String> = runCatching {
                    PsiManager.getInstance(p).findFile(vf)
                }.getOrElse { emptyList() }
            }
        """.trimIndent()

        val cross = bodyOf(synthetic, "crossFileSqlIds")
        assertNotNull("自证：应能定位 crossFileSqlIds 方法体", cross)
        assertTrue("自证：老形态 FileTypeIndex 必须被判出：$cross", bannedTokens(cross!!).contains("FileTypeIndex"))
        assertFalse(
            "自证：提取不得越界吃到 legacyCrossFileSqlIds 的内容（同前缀陷阱）：$cross",
            cross.contains("PsiManager"),
        )

        val legacy = bodyOf(synthetic, "legacyCrossFileSqlIds")
        assertNotNull("自证：legacy 方法体应能定位", legacy)
        assertTrue(
            "自证：legacy 方法体应含现扫形态（它是合法回退实现）：$legacy",
            bannedTokens(legacy!!).contains("findFile("),
        )

        val clean = "class C {\n    fun crossFileSqlIds(p: Project) = service.list()\n}"
        val cleanBody = bodyOf(clean, "crossFileSqlIds")
        assertNotNull("自证：干净形态应能定位", cleanBody)
        assertTrue("自证：干净形态不得误报：$cleanBody", bannedTokens(cleanBody!!).isEmpty())
    }

    // ------------------------------------------------------------------
    // 方法体提取内核
    // ------------------------------------------------------------------

    /**
     * 类级函数名 → 方法体文本（已剥注释）。
     *
     * 边界按「4 空格缩进的成员起始行」切：`^ {4}` 后紧跟 `}`/`)` 的行是**表达式体的收尾行**
     * （`}.getOrElse { … }`），不算成员起始，故方法体会完整包含它——这正是「把 `FileTypeIndex`
     * 塞进 `.getOrElse` 也不漏」的保证。
     */
    private fun classFnBodies(source: String): Map<String, String> {
        val lines = stripComments(source).lines()
        val memberStarts = lines.indices.filter { MEMBER_START.containsMatchIn(lines[it]) }
        val out = LinkedHashMap<String, String>()
        memberStarts.forEachIndexed { idx, start ->
            val match = FUN_DECL.find(lines[start]) ?: return@forEachIndexed
            val end = memberStarts.getOrElse(idx + 1) { lines.size }
            out[match.groupValues[1].trim('`')] = lines.subList(start, end).joinToString("\n")
        }
        return out
    }

    private fun bodyOf(source: String, fnName: String): String? = classFnBodies(source)[fnName]

    /** 方法体里出现的现扫令牌（空 = 合规）。 */
    private fun bannedTokens(body: String): List<String> = banned.filter { body.contains(it) }

    private fun repoFile(relative: String): File {
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(root, relative)
            if (candidate.isFile) return candidate
            root = root.parentFile ?: return@repeat
        }
        error("未定位到仓库根（$relative 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }

    /** 剥注释（口径同 `ui/ref/MapperFileLookupIndexTest`：KDoc 里点名老实现是正当历史记录）。 */
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

    private companion object {
        /** 成员起始行：4 空格后不是 `}`/`)` 且非空白（表达式体收尾行因此被排除）。 */
        val MEMBER_START = Regex("^ {4}(?![)}])\\S")

        /** 类级函数声明（4 空格缩进；可见性修饰符与反引号名都收）。 */
        val FUN_DECL = Regex(
            "^ {4}(?:(?:private|internal|public|protected|override|suspend|inline)\\s+)*" +
                "fun\\s+(`[^`]+`|[\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(",
        )
    }
}
