package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 图标资源存在性守卫（T-TestGateGems W1-b，2026-10-24）。
 *
 * 来源：magpie `internal/gui/builtins_test.go:191-244` `TestBuiltinClientsLoaded`——遍历声明表里的
 * 每个资源，检查「文件真的存在」+「真的被引用」，末尾 `if n == 0 { t.Fatal(...) }` 防空跑。
 *
 * 防的缺陷形态：`plugin.xml` 或代码里写了图标路径，但资源没打进 jar。运行时表现为**图标静默空白**
 * （平台 `IconLoader` 找不到资源返回空图标，不抛错），人眼在 PR 里看不出来，只有装了插件才发现。
 *
 * 覆盖两类引用：
 *  ① `plugin.xml` 的 `icon="/icons/x.svg"`；
 *  ② Kotlin 源码里的 `"/icons/x.svg"` 字面量（含 `"/icons/toolbar/$name.svg"` 这类模板——
 *     模板按前缀展开为「该目录下必须有文件」，不断言具体文件名）。
 */
class IconResourceGuardTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null && !File(dir, "src/main/resources").isDirectory) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun resourcesRoot(): File = File(repoRoot(), "src/main/resources")

    /** plugin.xml 与 Kotlin 源码里出现的图标路径（以 / 开头、以 .svg/.png 结尾）。 */
    private fun declaredIconPaths(): List<Pair<String, String>> {
        val root = repoRoot()
        val out = mutableListOf<Pair<String, String>>()
        val literal = Regex(""""(/icons/[^"$]*\.(?:svg|png))"""")
        val xmlAttr = Regex("""icon="(/icons/[^"]*\.(?:svg|png))"""")

        val pluginXml = File(root, "src/main/resources/META-INF/plugin.xml")
        if (pluginXml.isFile) {
            pluginXml.readText().lineSequence().forEachIndexed { i, line ->
                for (m in xmlAttr.findAll(line)) {
                    out += "src/main/resources/META-INF/plugin.xml:${i + 1}" to m.groupValues[1]
                }
            }
        }
        val srcRoot = File(root, "src/main/kotlin")
        srcRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            f.readText().lineSequence().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                for (m in literal.findAll(line)) {
                    out += f.relativeTo(root).path.replace('\\', '/') + ":${i + 1}" to m.groupValues[1]
                }
            }
        }
        return out
    }

    @Test
    fun `声明的图标资源必须真实存在于 resources`() {
        val paths = declaredIconPaths()
        assertTrue("图标引用扫描面为空，守卫未生效", paths.isNotEmpty())

        val missing = paths.filter { (_, path) ->
            !File(resourcesRoot(), path.removePrefix("/")).isFile
        }
        assertTrue(
            "图标路径指向不存在的资源（运行时静默空白图标）：\n" +
                missing.joinToString("\n") { (where, path) -> "  $where  ->  $path" },
            missing.isEmpty(),
        )
    }

    /**
     * 模板拼接的图标目录：`"/icons/toolbar/$name.svg"` 要求该目录下**至少有文件**。
     * 这条防的是「目录被清空但代码还在拼路径」——具体文件名由调用方的枚举决定，静态看不到。
     */
    @Test
    fun `模板拼接的图标目录不得为空`() {
        val root = repoRoot()
        val templates = mutableListOf<Pair<String, String>>()
        val tmpl = Regex(""""(/icons/[^"]*)\$\{?[A-Za-z_][A-Za-z0-9_.]*\}?[^"]*"""")
        File(root, "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { f ->
                f.readText().lineSequence().forEachIndexed { i, line ->
                    if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                    for (m in tmpl.findAll(line)) {
                        templates += f.relativeTo(root).path.replace('\\', '/') + ":${i + 1}" to m.groupValues[1]
                    }
                }
            }
        for ((where, prefix) in templates) {
            val dir = File(resourcesRoot(), prefix.removePrefix("/").substringBeforeLast('/'))
            assertTrue(
                "$where 用模板拼接 $prefix，但目录 ${dir.path} 不存在",
                dir.isDirectory,
            )
            val icons = dir.listFiles()?.filter { it.name.endsWith(".svg") || it.name.endsWith(".png") }.orEmpty()
            assertTrue("$where 用模板拼接 $prefix，但目录下没有任何 svg/png", icons.isNotEmpty())
        }
    }

    /**
     * 自证：判定内核能看见「缺失资源」形态。
     * 直接对合成路径做与生产同款的存在性判断，证明这条断言不是空转。
     *
     * T-TestGateGems 补（2026-10-25 合并 master 后登记进 GUARD_CLASSES 时发现）：本用例原先只对
     * 合成路径做 `File.isFile`，**没有调用 `declaredIconPaths`**——元守卫看到的正是「站点提取器
     * 无自证引用」。故此处补一条对提取器本身的断言：它必须真能从本仓声明里提出站点，否则主用例
     * 的「逐条校验存在性」会退化成空循环（声明表为空 = 恒绿）。
     */
    @Test
    fun `判定内核能看见缺失的图标资源`() {
        val definitelyMissing = "/icons/__definitely_not_here__.svg"
        assertTrue(
            "判定内核看不见缺失资源",
            !File(resourcesRoot(), definitelyMissing.removePrefix("/")).isFile,
        )
        val definitelyPresent = "/icons/sqlLog.svg"
        assertTrue(
            "判定内核看不见已存在资源（正向对照）",
            File(resourcesRoot(), definitelyPresent.removePrefix("/")).isFile,
        )
        val declared = declaredIconPaths()
        assertTrue(
            "站点提取器 declaredIconPaths 在本仓声明里一个图标都没提出来（主用例会退化成空循环）",
            declared.isNotEmpty(),
        )
    }
}
