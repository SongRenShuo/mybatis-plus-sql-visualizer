package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JUnit3 语义守卫（T-Junit4Migration P2.5，2026-11-05）。
 *
 * ## 为什么需要
 *
 * 仓内 400+ 个测试类继承平台的 **JUnit3** 测试基类。这类类由 JUnit38ClassRunner 按
 * **方法名 `test*`** 收集用例，JUnit4 的注解与 Assume 语义整体不参与判定：
 *
 *  1. `@Test` / `@Before` / `@After` / `@Rule` / `@RunWith` / `@Ignore` 写了等于没写——用例跑不跑
 *     只看方法名，注解只会让人误判测试框架（T-Junit4Migration P2 清了 47 个类上的 313 条）；
 *  2. `Assume.assumeXxx` 抛出的 AssumptionViolatedException 被平台 runner 记为 **failed**，
 *     「环境不具备就跳过」变成「环境不具备就红」（P1 反向实验实测：同场景 tests=1 failures=1，
 *     failure=org.junit.AssumptionViolatedException；改成打印 `[DB-IT] SKIP` + return 后同一场景
 *     failures=0）；
 *  3. 反引号方法名不以 `test` 开头 → 永不执行（P0 实证：SqlToJavaPanelTest 改名前后 tests= 23 → 28，
 *     5 条用例此前一次都没跑过）。
 *
 * 编译器不报错、运行期也没有任何提示，这三条只能在源码文本上守，故立此守卫。
 *
 * ## 扫描面与漏报面（已知，勿当「已覆盖」）
 *
 * - 只认**顶格**（第 0 列）类声明：字符串字面量里的示例源码是缩进的，天然不误报；
 * - 继承链按「简单类名」做固定点传播，可覆盖中转基类；同名类跨文件会串味（本仓无此形态）；
 * - 多行类声明（基类落到下一行）不识别；类名同名的内部类不区分——与同包其它守卫同口径；
 * - 规则 2 的 import 检查在**多类文件**里只认函数体调用（import 可能属于同文件里的非 JUnit3 类），
 *   单类文件才连带检查 import 行。
 */
class Junit3SemanticsGuardTest {

    /** 一个顶格类声明：[body] 是掩码后的类体，[bodyStart] 是类体首行的 0 基行号。 */
    internal data class ClassDecl(
        val path: String,
        val name: String,
        val line: Int,
        val bases: List<String>,
        val body: String,
        val bodyStart: Int,
        val fileText: String,
    )

    // ------------------------------------------------------------------
    // 判定内核（可注入合成源码：主用例与自证用例共用同一实现）
    // ------------------------------------------------------------------

    /**
     * 抽出全部顶格类声明。先做**字符串/注释掩码**：示例源码写在测试的字符串字面量里，
     * 不掩码会把 `class Foo : BasePlatformTestCase()` 这类示例当成真类扫描（P0 复盘里正是
     * 这条把 13 个「从不执行」错算成散在 5 个类）。
     */
    internal fun classDecls(sources: Map<String, String>): List<ClassDecl> {
        val out = mutableListOf<ClassDecl>()
        sources.forEach { (path, source) ->
            val masked = maskStringsAndComments(source)
            val lines = masked.split('\n')
            val declAt = lines.mapIndexedNotNull { idx, line ->
                CLASS_DECL.find(line)?.let { m -> Triple(idx, m, line) }
            }
            declAt.forEachIndexed { i, (idx, m, _) ->
                val end = declAt.getOrNull(i + 1)?.first ?: lines.size
                val name = m.groupValues[1]
                val rawBases = m.groupValues[2]
                val bases = rawBases
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { IDENT.find(it)?.value ?: it }
                out += ClassDecl(
                    path = path,
                    name = name,
                    line = idx + 1,
                    bases = bases,
                    body = lines.subList(idx + 1, end).joinToString("\n"),
                    bodyStart = idx + 1,
                    fileText = masked,
                )
            }
        }
        return out
    }

    /**
     * 继承 JUnit3 基类的类名集合（按简单类名做**固定点传播**，中转基类也收）。
     *
     * 不用 `isJunit3(x): Boolean` 这种形态谓词：本包元守卫要求已登记守卫类里的类级 Boolean 函数
     * 逐条登记，集合返回值把判定逻辑收在一个内核里，登记面更干净。
     */
    internal fun junit3ClassNames(decls: List<ClassDecl>): Set<String> {
        val out = mutableSetOf<String>()
        var changed = true
        while (changed) {
            changed = false
            decls.forEach { d ->
                if (d.name in out) return@forEach
                if (d.bases.any { it in JUNIT3_BASES || it in out }) {
                    out += d.name
                    changed = true
                }
            }
        }
        return out
    }

    /**
     * 规则 1：JUnit3 类上不得出现 JUnit4 注解（无效，写了只会误导）。
     *
     * 报错信息带「为什么」：这些注解在 JUnit3 收集口径里根本不参与判定，删掉行为零变化；
     * 真正决定用例跑不跑的是方法名。
     */
    internal fun annotationViolations(decl: ClassDecl): List<String> {
        val out = mutableListOf<String>()
        decl.body.split('\n').forEachIndexed { i, line ->
            val m = JUNIT4_ANNOTATION.find(line) ?: return@forEachIndexed
            out += "${decl.path}:${decl.bodyStart + i + 1} 类 ${decl.name} 上的 @${m.groupValues[1]} 在 JUnit3 下不生效" +
                "（JUnit38ClassRunner 只按 test* 方法名收集用例）：请删除该注解"
        }
        return out
    }

    /**
     * 规则 2：JUnit3 类不得 import/调用 `Assume.assume*`——平台 runner 把
     * AssumptionViolatedException 记为 failed，跳过语义会变成红。
     *
     * [fileClassNames] 用于避免误报：多类文件里的 import 可能属于同文件的非 JUnit3 类，
     * 那种情况下只校验函数体调用。
     */
    internal fun assumeViolations(decl: ClassDecl, fileClassNames: List<String>): List<String> {
        val out = mutableListOf<String>()
        if (fileClassNames.size <= 1) {
            val importAt = decl.fileText.split('\n').indexOfFirst { it.trim().startsWith("import org.junit.Assume") }
            if (importAt >= 0) {
                out += "${decl.path}:${importAt + 1} JUnit3 类所在的单类文件 import 了 org.junit.Assume" +
                    "（JUnit3 runner 把它记为 failed）：改用打印 `[DB-IT] SKIP: <原因>` + return 的显式绿跳"
            }
        }
        decl.body.split('\n').forEachIndexed { i, line ->
            if (!ASSUME_CALL.containsMatchIn(line)) return@forEachIndexed
            out += "${decl.path}:${decl.bodyStart + i + 1} 类 ${decl.name} 调用 Assume.assume*" +
                "（JUnit3 runner 把 AssumptionViolatedException 记为 failed，不是 skipped）：" +
                "改用打印 `[DB-IT] SKIP: <原因>` + return"
        }
        return out
    }

    /**
     * 规则 3：反引号方法名必须以 `test` 开头——JUnit3 按方法名收集，不以 test 开头则**永不执行**，
     * 而且这种漏网在任何报告里都不出现（连 skipped 都没有）。
     */
    internal fun namingViolations(decl: ClassDecl): List<String> {
        val out = mutableListOf<String>()
        decl.body.split('\n').forEachIndexed { i, line ->
            val m = BACKTICK_FUN.find(line) ?: return@forEachIndexed
            val name = m.groupValues[1]
            if (!name.startsWith("test")) {
                out += "${decl.path}:${decl.bodyStart + i + 1} 方法 `$name` 不以 test 开头——" +
                    "JUnit3 按方法名收集用例，这条用例永远不会执行：改名为 `test $name`"
            }
        }
        return out
    }

    /** 全仓（或注入的任意源码集）的 JUnit3 语义违规点。 */
    internal fun junit3Violations(sources: Map<String, String>): List<String> {
        val decls = classDecls(sources)
        val junit3 = junit3ClassNames(decls)
        return decls
            .filter { it.name in junit3 }
            .flatMap { decl ->
                val fileClassNames = decls.filter { it.path == decl.path }.map { it.name }
                annotationViolations(decl) + assumeViolations(decl, fileClassNames) + namingViolations(decl)
            }
    }

    // ------------------------------------------------------------------
    // 扫描辅助
    // ------------------------------------------------------------------

    /**
     * 把字符串字面量与注释替换成空格（保留换行与列位置，行号仍可回推）。
     *
     * 三处形态都收：行注释、块注释（含 KDoc）、普通/原生字符串与字符字面量。
     */
    private fun maskStringsAndComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }

                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    out.append(' ').append(' ')
                    i += 2
                    while (i < text.length && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append(' ').append(' ')
                        i += 2
                    }
                }

                text.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("   ")
                        i += 3
                    }
                }

                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < text.length && text[i] != quote) {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            out.append("  ")
                            i += 2
                            continue
                        }
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append(' ')
                        i++
                    }
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 全量测试源码：包相对路径（`cn/song/...` 之后）→ 文本。 */
    private fun testSources(): Map<String, String> {
        val root = repoRoot().resolve("src/test/kotlin")
        val prefix = "cn/song/mybatisplus/visualizer/"
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                val full = file.relativeTo(root).path.replace('\\', '/')
                val rel = if (full.startsWith(prefix)) full.removePrefix(prefix) else full
                rel to file.readText()
            }
            .toList()
            .toMap()
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `test仓内JUnit3测试类不得出现无效注解与Assume_反引号方法须以test开头`() {
        val violations = junit3Violations(testSources())
        assertTrue(
            "JUnit3 语义违规（继承 JUnit3 基类的类由 JUnit38ClassRunner 按 test* 方法名收集：\n" +
                "  · @Test/@Before/@After/@Rule/@RunWith/@Ignore 全部无效，写了只会误导后来人；\n" +
                "  · Assume.assume* 抛 AssumptionViolatedException → 平台 runner 记为 failed（不是 skipped）；\n" +
                "  · 反引号方法名不以 test 开头 → 用例永不执行，且任何报告里都不出现）：\n" +
                violations.joinToString("\n") { "  - $it" },
            violations.isEmpty(),
        )
    }

    // ---------------- 合成样本自证（每条规则：违规必红 + 合规必绿） ----------------

    @Test
    fun `test自证_注解规则_JUnit3类带JUnit4注解必红_无注解必绿`() {
        val bad = mapOf(
            "A.kt" to """
                class BadTest : BasePlatformTestCase() {
                    @Test
                    fun testOk() { }
                }
            """.trimIndent(),
        )
        val badHits = annotationViolations(classDecls(bad).single())
        assertTrue("JUnit3 类上的 @Test 必须被抓：$badHits", badHits.size == 1 && badHits[0].contains("@Test"))

        val good = mapOf(
            "B.kt" to """
                class GoodTest : BasePlatformTestCase() {
                    fun testOk() { }
                }
            """.trimIndent(),
        )
        val goodHits = annotationViolations(classDecls(good).single())
        assertTrue("合规样本不得报红：$goodHits", goodHits.isEmpty())
    }

    @Test
    fun `test自证_Assume规则_JUnit3类调用assume必红_显式绿跳必绿`() {
        val bad = mapOf(
            "C.kt" to """
                class AssumeTest : BasePlatformTestCase() {
                    fun testDb() {
                        Assume.assumeTrue("MySQL 未就绪", false)
                    }
                }
            """.trimIndent(),
        )
        val badDecl = classDecls(bad).single()
        val badHits = assumeViolations(badDecl, listOf(badDecl.name))
        assertTrue("JUnit3 类里的 Assume.assume* 必须被抓：$badHits", badHits.size == 1)

        val good = mapOf(
            "D.kt" to """
                class SkipTest : BasePlatformTestCase() {
                    fun testDb() {
                        if (System.getenv("NOPE") == null) {
                            println("[DB-IT] SKIP: 环境不具备")
                            return
                        }
                        error("never")
                    }
                }
            """.trimIndent(),
        )
        val goodDecl = classDecls(good).single()
        val goodHits = assumeViolations(goodDecl, listOf(goodDecl.name))
        assertTrue("显式绿跳不得报红：$goodHits", goodHits.isEmpty())
    }

    @Test
    fun `test自证_命名规则_反引号方法不以test开头必红_以test开头必绿`() {
        val bad = mapOf(
            "E.kt" to """
                class NamingTest : BasePlatformTestCase() {
                    fun `canAutoInfer_returnsTrue`() { }
                }
            """.trimIndent(),
        )
        val badHits = namingViolations(classDecls(bad).single())
        assertTrue("不以 test 开头的反引号方法必须被抓（永不执行）：$badHits", badHits.size == 1)

        val good = mapOf(
            "F.kt" to """
                class NamingOkTest : BasePlatformTestCase() {
                    fun `test canAutoInfer_returnsTrue`() { }
                    fun testPlainName() { }
                }
            """.trimIndent(),
        )
        val goodHits = namingViolations(classDecls(good).single())
        assertTrue("test 前缀方法不得报红：$goodHits", goodHits.isEmpty())
    }

    @Test
    fun `test自证_中转基类与字符串字面量_继承链须传播且示例源码不参与扫描`() {
        val sources = mapOf(
            "G.kt" to """
                abstract class MidBase : BasePlatformTestCase() {
                }

                class ChildTest : MidBase() {
                    @Test
                    fun testOk() { }
                }

                class PlainJunit4Test {
                    @Test
                    fun `anything`() { }
                }
            """.trimIndent(),
        )
        val hits = junit3Violations(sources)
        assertTrue("中转基类的子类必须被识别（@Test 必红）：$hits", hits.size == 1 && hits[0].contains("ChildTest"))
        assertTrue("非 JUnit3 类的 @Test 不得被误报：$hits", hits.none { it.contains("PlainJunit4Test") })

        val withStringSample = mapOf(
            "H.kt" to (
                "class PlainHolder {\n" +
                    "    val sample = \"\"\"\n" +
                    "        class SampleTest : BasePlatformTestCase() {\n" +
                    "            @Test\n" +
                    "            fun `never`() { }\n" +
                    "        }\n" +
                    "    \"\"\"\n" +
                    "}\n"
                ),
        )
        assertTrue(
            "字符串字面量里的示例源码不得参与扫描：${junit3Violations(withStringSample)}",
            junit3Violations(withStringSample).isEmpty(),
        )
    }

    private companion object {
        /** JUnit3 基类（简单类名）；中转基类由固定点传播接上。 */
        val JUNIT3_BASES = setOf(
            "BasePlatformTestCase",
            "LightPlatformTestCase",
            "LightJavaCodeInsightFixtureTestCase",
            "LightCodeInsightFixtureTestCase",
            "HeavyPlatformTestCase",
            "UsefulTestCase",
            "PlatformTestCase",
            "TestCase",
        )

        /** 顶格类声明；基类部分取到 `{` 之前。 */
        val CLASS_DECL = Regex(
            "^(?:internal |private |open |abstract |sealed )*class\\s+([\\p{L}_][\\p{L}\\p{N}_]*)" +
                "\\s*(?:<[^>]*>)?\\s*(?:\\([^)]*\\))?\\s*(?::\\s*([^\\{]+?))?\\s*\\{",
        )
        val IDENT = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")
        val JUNIT4_ANNOTATION = Regex("^\\s*@(Test|Before|After|Rule|RunWith|Ignore)\\b")
        val ASSUME_CALL = Regex("\\bAssume\\.assume[\\p{L}]*\\s*\\(")
        val BACKTICK_FUN = Regex(
            "^\\s*(?:private |internal |public |protected |override |suspend |inline |operator )*fun\\s+`([^`]+)`\\s*\\(",
        )
    }
}
