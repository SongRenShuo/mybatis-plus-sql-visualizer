package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-PerfSweep32 收口轮元守卫：**测试类不得「有 test 方法却两边都不可见」**——
 *
 * `IbatisTypeAliasResolverHotPathTest`（T-PerfSweep31 券01 守卫）从创建起三个方法都缺 `@Test`
 * 且不继承 `TestCase`：JUnit3 扫描器要求 TestCase 血统、JUnit4 扫描器要求 @Test 注解，
 * 两者皆无 = 该类在 `test`/分片/testFast 全部通道**静默零执行**（连「No tests found」都不报，
 * `--tests` 指名运行时只要有同批 pattern 命中即静默通过）——性能守卫形同虚设一整轮，
 * 直到 T-PerfSweep32 券01 反向验证时「撤闸必红」怎么跑都绿才发现（2026-11-18 实证）。
 *
 * ## 判定（与本次扫描同口径）
 *
 * 测试类含 `fun test…()` 方法、但全文件无 `@Test` 注解、且无 TestCase 血统
 * （`TestCase`/`BasePlatformTestCase` 等 JUnit3 基类子类免注解）→ 违例。
 *
 * 自证：合成样本（有 test 方法、无注解、无血统）必须被抓出；带 @Test 的样本必须放行。
 */
class TestClassVisibilityGuardTest {

    private val testRoot = "src/test/kotlin"

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(8) {
            if (File(dir, "$testRoot/cn/song").exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("repo root with $testRoot not found from ${System.getProperty("user.dir")}")
    }

    private fun violationsIn(source: String, path: String): List<String> {
        val hasTestAnno = source.contains("@Test")
        val hasTestCaseBlood = source.contains("TestCase")
        val testMethods = Regex(
            """(?m)^\s*(?:override\s+)?fun\s+(test[\w\u4e00-\u9fff]*)\s*\(""",
        ).findAll(source).toList()
        return if (testMethods.isNotEmpty() && !hasTestAnno && !hasTestCaseBlood) {
            listOf(
                "$path：${testMethods.size} 个 test 方法但无 @Test 注解且无 TestCase 血统" +
                    "（JUnit3/JUnit4 双扫描器均不可见 = 静默零执行）",
            )
        } else {
            emptyList()
        }
    }

    @Test
    fun `测试类不得静默零执行_有test方法必须可见`() {
        val issues = mutableListOf<String>()
        val root = findRepoRoot()
        File(root, testRoot).walkTopDown()
            .filter { it.isFile && it.name.endsWith("Test.kt") }
            .forEach { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                violationsIn(f.readText(), rel).forEach { issues.add(it) }
            }
        assertTrue("静默零执行测试类：\n" + issues.joinToString("\n"), issues.isEmpty())
    }

    @Test
    fun `自证_无注解无血统的合成样本必须被抓出`() {
        val synthetic = """
            class DeadGuardTest {
                fun testSomethingBroken() {}
            }
        """.trimIndent()
        val issues = violationsIn(synthetic, "synthetic/DeadGuardTest.kt")
        assertTrue("自证失败：无 @Test 无血统样本必须被抓出", issues.isNotEmpty())
    }

    @Test
    fun `自证_带注解或血统的样本必须放行`() {
        val annotated = """
            class LiveGuardTest {
                @Test
                fun testSomethingWorks() {}
            }
        """.trimIndent()
        val junit3 = """
            class LegacyTest : junit.framework.TestCase() {
                fun testSomethingLegacy() {}
            }
        """.trimIndent()
        assertTrue(
            "带 @Test / TestCase 血统的样本不得误报：" + (violationsIn(annotated, "a") + violationsIn(junit3, "b")),
            violationsIn(annotated, "a").isEmpty() && violationsIn(junit3, "b").isEmpty(),
        )
    }
}
