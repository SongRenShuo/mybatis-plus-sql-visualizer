package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-PSG（N-33）占位符位点与基数对账测试：
 *
 *  1. [placeholderPositions] 的串面口径（单引号字面量 / `--` 行注释 / 块注释内的 `?` 不算占位符；
 *     双引号标识符**不在**保护范围——既有口径，本任务有意不扩大）；
 *  2. [PlaceholderCardinality] 两个对账判定口（面板链严格等值 / 复制链只认占位符多于参数）；
 *  3. 验收 3 源码断言：`placeholderPositions` 的 `fun` 定义体在 src/main 全仓**唯一**
 *     （按定义体断言而非符号名命中数——三处消费方跨包引用，按符号名数必然自相矛盾）；
 *  4. 自洽关系成钉：XML 渲染产物「串感知 `?` 位点数 == `rendered.mappings.size`」。
 *
 * 纯逻辑，无平台依赖。
 */
class PlaceholderPositionsTest {

    // ------------------------------------------------------------------
    // 1. 位点口径
    // ------------------------------------------------------------------

    @Test
    fun `引号字面量内的问号不算占位符`() {
        assertEquals(listOf(44), placeholderPositions("SELECT * FROM t WHERE note = 'a?b' AND id = ?"))
        assertEquals(emptyList<Int>(), placeholderPositions("SELECT 'a?b'"))
    }

    @Test
    fun `单引号转义后的问号仍按字面量处理`() {
        // '它''?' 里的 `?` 是字面量；收尾引号之后才是占位符
        assertEquals(listOf(20), placeholderPositions("SELECT '它''?' AS x, ?"))
    }

    @Test
    fun `行注释与块注释内的问号不算占位符`() {
        assertEquals(listOf(35), placeholderPositions("SELECT id FROM t -- 备注?\nWHERE id = ?"))
        assertEquals(listOf(38), placeholderPositions("SELECT id FROM t /* 占?位 */ WHERE id = ?"))
    }

    @Test
    fun `PG问号操作符形态按占位符计数`() {
        // 双引号标识符不在保护范围（既有口径，本任务不扩大）：`?|` 仍计为占位符
        assertEquals(listOf(24), placeholderPositions("SELECT * FROM t WHERE a ?| b"))
    }

    // ------------------------------------------------------------------
    // 2. 基数对账
    // ------------------------------------------------------------------

    @Test
    fun `对账_严格等值判定`() {
        assertNull("相等不标注", PlaceholderCardinality.mismatchMarker(2, 2))
        assertEquals(
            "-- placeholder/param count mismatch (3 placeholders vs 2 params)",
            PlaceholderCardinality.mismatchMarker(3, 2),
        )
        assertEquals(
            "-- placeholder/param count mismatch (1 placeholders vs 2 params)",
            PlaceholderCardinality.mismatchMarker(1, 2),
        )
    }

    @Test
    fun `对账_复制链只认占位符多于参数`() {
        assertNull("位点少于参数是已回填常态，不标注", PlaceholderCardinality.excessPlaceholderMarker(1, 2))
        assertNull("相等不标注", PlaceholderCardinality.excessPlaceholderMarker(2, 2))
        assertEquals(
            "-- placeholder/param count mismatch (3 placeholders vs 2 params)",
            PlaceholderCardinality.excessPlaceholderMarker(3, 2),
        )
    }

    // ------------------------------------------------------------------
    // 3. 验收 3：位点实现唯一（按 fun 定义体断言）
    // ------------------------------------------------------------------

    @Test
    fun `位点实现_全仓只有一个fun定义体`() {
        val defs = mainKotlinFiles()
            .filter { (_, text) -> Regex("""(?m)^\s*(?:internal\s+)?fun\s+placeholderPositions\s*\(""").containsMatchIn(text) }
            .map { (path, _) -> path }
        assertEquals("placeholderPositions 的 fun 定义体应全仓唯一：$defs", 1, defs.size)
        assertEquals(
            "唯一定义体应落在 sql 包（三处消费方跨包共用同一份位点实现）",
            "src/main/kotlin/cn/song/mybatisplus/visualizer/sql/PlaceholderPositions.kt",
            defs.single(),
        )
        // 三处消费方（面板 / 复制 / 日志）都必须引用同一实现：消费点按符号名出现（≥3 处）
        val consumers = mainKotlinFiles().filter { (path, text) ->
            path != defs.single() && text.contains("placeholderPositions(")
        }.map { (path, _) -> path }
        assertTrue("三处消费方应跨 log/sql/ui 三包引用同一实现，实际：$consumers", consumers.size >= 3)
    }

    @Test
    fun `log包薄封装_countPlaceholders仍在log包内`() {
        val recognizer = mainKotlinFiles()
            .first { (path, _) -> path.endsWith("log/LogEntryPreviewRecognizer.kt") }
        assertTrue(
            "countPlaceholders 是 log 包薄封装（另一路 T-RealEngineCardinality 依赖它在 log 包内可达），不得随位点实现一起搬走",
            Regex("""(?m)^\s*internal\s+fun\s+countPlaceholders\s*\(""").containsMatchIn(recognizer.second),
        )
    }

    // ------------------------------------------------------------------
    // 4. 自洽关系成钉：XML 渲染产物 `?` 位点数 == mappings.size
    // ------------------------------------------------------------------

    @Test
    fun `XML渲染_位点数与参数映射条数全等_含字面量与注释内问号`() {
        val parsed = XmlMapperParser().parse(
            """
            <mapper namespace="n">
              <select id="s1">
                SELECT * FROM t
                -- 备注?（注释内问号不是占位符）
                WHERE note = 'a?b' AND id = #{id} AND name = #{name} /* 占?位 */
              </select>
            </mapper>
            """.trimIndent(),
        )
        val rendered = XmlPreviewModel.from(parsed.statements.single(), parsed.errors).renderSql()
        assertEquals("前置：两个 `#{}` 映射", 2, rendered.mappings.size)
        assertEquals(
            "串感知 `?` 位点数应等于参数映射条数（字面量/注释内 `?` 不计）",
            rendered.mappings.size,
            placeholderPositions(rendered.sql).size,
        )
    }

    // ------------------------------------------------------------------

    /** src/main/kotlin 下全部 .kt（相对路径 → 原文），供验收 3 的源码断言使用。 */
    private fun mainKotlinFiles(): List<Pair<String, String>> {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) {
                val root = dir
                return root.resolve("src/main/kotlin").walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
                    .toList()
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }
}
