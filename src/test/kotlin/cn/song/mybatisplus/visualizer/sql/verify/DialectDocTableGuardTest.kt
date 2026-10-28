package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.IdentifierCaseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文档站方言表与 [Dialect] 实现的一致性守卫（T-DialectDocDrift）。
 *
 * ## 要解决的问题
 *
 * 文档站的方言表是纯手写的 Markdown，与 [Dialect] 实现之间**没有任何机器约束**。
 * 2026-10-26 写对外稿时逐行对账，一次查出三处已经上线的错误：
 *
 *  - `features/wrapper-preview.md` 把 MySQL / MariaDB 写成 `LIMIT n OFFSET m`（实际 `LIMIT 5, 10`）；
 *  - 同一张表把 Oracle 写成 `ROWNUM`（代码里从来没有 ROWNUM，实际是 `OFFSET n ROWS FETCH NEXT m ROWS ONLY`）；
 *  - 达梦被划进 PG 形态那一行（实际与 MySQL 同族）；
 *  - `features/batch-operation.md` 声称 SQL Server / Oracle 用 `MERGE INTO`（实际两者与达梦都降级纯 INSERT），
 *    且整表只列了 4 种方言、漏掉 7 种。
 *
 * 这些错误不是笔误，是「按族推定」的思维习惯在文档里的投影——`DialectDocGoldenTest` 只钉住了
 * 达梦与金仓的保留字和 upsert 形态，分页表与批量表完全在覆盖之外。本守卫把两张表纳入机器约束。
 *
 * ## 判据
 *
 * 1. **覆盖完整**：表格声明的方言集合必须恰好等于 [DialectRegistry.INSTANCE.dialects]（不重不漏）。
 *    漏一种方言即红——「表只列 4 种」这个形态正是 2026-10-26 查出的错误之一。
 * 2. **族归属正确**：每一行声明的形态，必须与 [Dialect] 实现真实产出的族一致。
 *    文档与实现走**同一个归类器**（[classifyPagination] / [classifyUpsert]），比对的是族而非逐字文本，
 *    这样文档可以自由改写措辞（`LIMIT [offset,] count` 与 `LIMIT n, m` 等价），但族归错必红。
 * 3. **identity 包装声明覆盖完整**：`crud-sql.md` 那句「哪些方言需要 `SET IDENTITY_INSERT`」必须
 *    点到全部 [Dialect.renderInsertWrapper] 非 null 的方言（2026-10-26 漏了达梦，达梦真机报
 *    vendorCode -2723）。
 *
 * ## 已知边界
 *
 * - 只校验「族」与「覆盖」，不校验表格里的示例数值（`OFFSET n ROWS FETCH NEXT m ROWS ONLY` 的
 *   `n` / `m` 是占位记法，逐字比对会把文档绑死）。
 * - 归类器是族判定的唯一实现，文档与实现共用它——若归类器本身判错，两边会一致地错。
 *   这一点靠 [漂移自证_分页族归错被抓] 等用例钉住归类器的灵敏度，而不是靠断言两遍。
 * - 文档站是**独立仓库**（兄弟目录），不随插件仓分发。目录不存在时显式绿跳（见 [docsRoot]）——
 *   外部贡献者只 clone 插件仓时不该因此红；但文档站在场而表格解析不出内容时**必须红**，
 *   否则就是「空扫描面空绿」。
 *
 * k2j 互操作（T-SyncK2JMaster136 B2 移植）：DialectRegistry 为 Java 单例（INSTANCE.getDialects()，
 * Kotlin 属性语法自动映射）；LimitClause 探针具名参换位置参；repoRoot 锚 src/main/java。
 */
class DialectDocTableGuardTest {

    // ------------------------------------------------------------------
    // 文档站定位
    // ------------------------------------------------------------------

    /**
     * 文档站根目录；不在场时返回 null（调用方显式绿跳）。
     *
     * 优先取环境变量 `MPV_DOCS_ROOT`（CI / 非标准目录布局），其次取插件仓的兄弟目录。
     * 两者都不命中返回 null，由用例打印跳过原因——不用 `assumeTrue`，因为平台 runner 会把
     * `AssumptionViolatedException` 记成失败（同 `DataSourceSelectorIntegrationTest` 的既有口径）。
     */
    private fun docsRoot(): File? {
        System.getenv("MPV_DOCS_ROOT")?.let { configured ->
            val dir = File(configured).absoluteFile
            if (File(dir, "features").isDirectory) return dir
        }
        val sibling = File(repoRoot().parentFile, "mybatis-plus-sql-visualizer-docs").absoluteFile
        return if (File(sibling, "features").isDirectory) sibling else null
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    // ------------------------------------------------------------------
    // 族归类器（文档与实现共用，族判定的唯一事实源）
    // ------------------------------------------------------------------

    /**
     * 分页形态归类。
     *
     * 判定顺序有讲究：
     *  - `ROWNUM` 排最前。2026-10-26 的原始错误写法是 `` `ROWNUM` / `FETCH FIRST` ``，
     *    它同时含 `FETCH`——若让 `FETCH` 先判，这种「混着写的旧形态」会被归进 OFFSET_FETCH 族，
     *    与 Oracle 实现的族恰好一致，守卫当场失去灵敏度（反向验证实测抓不住）。
     *    ROWNUM 在实现里**完全不存在**，出现即错，故单独判为 [PAGINATION_ROWNUM]。
     *  - `FETCH` 先于 `OFFSET`：Oracle / SQL Server 的形态
     *    （`OFFSET n ROWS FETCH NEXT m ROWS ONLY`）同时含这两个词，先判 `OFFSET` 会误归 PG 族。
     *  - `LIMIT [` 与 `LIMIT n,` 先于裸 `OFFSET`：MySQL 形态 `LIMIT [offset,] count` 不含 OFFSET 字样，
     *    文档也可能写成 `LIMIT n, m`，两种写法都收。
     */
    internal fun classifyPagination(form: String): String {
        val s = form.uppercase()
        return when {
            "ROWNUM" in s -> PAGINATION_ROWNUM
            "FETCH" in s -> PAGINATION_OFFSET_FETCH
            "LIMIT [" in s || Regex("LIMIT\\s+\\d+\\s*,").containsMatchIn(s) -> PAGINATION_MYSQL
            "OFFSET" in s -> PAGINATION_PG
            else -> PAGINATION_UNKNOWN
        }
    }

    /**
     * upsert 形态归类。
     *
     * `ON DUPLICATE` 必须先于 `ON CONFLICT`：openGauss 那行写着
     * 「`ON DUPLICATE KEY UPDATE`（不是 PG 的 `ON CONFLICT`）」，两个词都在场，顺序反了会归错。
     */
    internal fun classifyUpsert(form: String): String {
        val s = form.uppercase()
        return when {
            "ON DUPLICATE" in s -> UPSERT_DUP_KEY
            "ON CONFLICT" in s -> UPSERT_ON_CONFLICT
            "MERGE INTO" in s -> UPSERT_MERGE_STMT
            "降级纯 INSERT" in form || "PLAIN INSERT" in s -> UPSERT_PLAIN_INSERT
            else -> UPSERT_UNKNOWN
        }
    }

    /** 实际实现的族（文档表格要与之对齐）。 */
    private fun actualPaginationFamilies(): Map<String, String> =
        DialectRegistry.INSTANCE.dialects.associate { d ->
            normalizeDialectName(d.name) to classifyPagination(d.renderLimit(PAGINATION_PROBE))
        }

    private fun actualUpsertFamilies(): Map<String, String> =
        DialectRegistry.INSTANCE.dialects.associate { d ->
            normalizeDialectName(d.name) to classifyUpsert(upsertFormOf(d))
        }

    /** 把实现的真实产出还原成「文档会怎么写」的形态文本，再交给同一个归类器。 */
    private fun upsertFormOf(d: Dialect): String {
        d.renderUpsertStatement("t", listOf("id", "name"), "id")?.let { return it }
        d.renderUpsert("id", listOf("name"))?.let { return it }
        return "降级纯 INSERT"
    }

    // ------------------------------------------------------------------
    // 判定内核（暴露给自证语料注入合成样本）
    // ------------------------------------------------------------------

    /**
     * 比对一张文档表与实现族，返回违规描述。
     *
     * @param docRows 文档表解析出的（方言单元、形态单元）行
     * @param actual  归一化方言名 → 实际族
     * @param label   表名（违规信息里点名用）
     */
    internal fun docTableViolations(
        docRows: List<DocRow>,
        actual: Map<String, String>,
        label: String,
        classify: (String) -> String,
    ): List<String> {
        val violations = mutableListOf<String>()

        if (docRows.isEmpty()) {
            violations += "$label: 未解析到任何表格行（表格被改名/删掉，或表头文字变了）"
            return violations
        }

        val declared = mutableMapOf<String, String>()
        docRows.forEach { row ->
            val family = classify(row.form)
            // 方言单元可能写成一格多方言（`MySQL / MariaDB / OceanBase（MySQL 模式）`），
            // 解析层已拆过一次；这里再拆一次是安全网——自证样本可以只给合并写法。
            row.dialects.flatMap { it.split('/') }.map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { raw ->
                    val key = normalizeDialectName(raw)
                    val previous = declared.put(key, family)
                    if (previous != null && previous != family) {
                        violations += "$label: 方言 $raw 同时被声明为 $previous 与 $family（同一方言只能归一族）"
                    }
                }
        }

        val missing = actual.keys - declared.keys
        val extra = declared.keys - actual.keys
        if (missing.isNotEmpty()) {
            violations += "$label: 缺少方言 ${missing.sorted().joinToString(", ")}（表必须覆盖全部 ${actual.size} 种）"
        }
        if (extra.isNotEmpty()) {
            violations += "$label: 出现未知方言 ${extra.sorted().joinToString(", ")}（与 DialectRegistry 对不上）"
        }

        declared.forEach { (dialect, family) ->
            val real = actual[dialect] ?: return@forEach
            if (real != family) {
                violations += "$label: 方言 $dialect 文档声明 $family，实现实际为 $real"
            }
        }
        return violations
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `文档站分页表与Dialect实现一致`() {
        assertDocTable(
            path = "features/wrapper-preview.md",
            headerFirstCell = "方言",
            secondHeaderHint = "分页语法",
            label = "wrapper-preview 分页表（zh）",
            actual = actualPaginationFamilies(),
            classify = ::classifyPagination,
        )
    }

    @Test
    fun `文档站分页表与Dialect实现一致_en`() {
        assertDocTable(
            path = "en/features/wrapper-preview.md",
            headerFirstCell = "Dialect",
            secondHeaderHint = "Syntax",
            label = "wrapper-preview 分页表（en）",
            actual = actualPaginationFamilies(),
            classify = ::classifyPagination,
        )
    }

    @Test
    fun `文档站UPSERT表与Dialect实现一致`() {
        assertDocTable(
            path = "features/batch-operation.md",
            headerFirstCell = "方言",
            secondHeaderHint = "冲突子句",
            label = "batch-operation UPSERT 表（zh）",
            actual = actualUpsertFamilies(),
            classify = ::classifyUpsert,
        )
    }

    @Test
    fun `文档站UPSERT表与Dialect实现一致_en`() {
        assertDocTable(
            path = "en/features/batch-operation.md",
            headerFirstCell = "Dialect",
            secondHeaderHint = "Conflict Clause",
            label = "batch-operation UPSERT 表（en）",
            actual = actualUpsertFamilies(),
            classify = ::classifyUpsert,
        )
    }

    /**
     * `crud-sql.md` 里「哪些方言需要 `SET IDENTITY_INSERT`」的散文声明必须覆盖全部实现方言。
     *
     * 只做**正向**检查（需要包装的必须被点名），不做反向——那句里同时有「其他方言无需包装」的
     * 概括表述，反向检查会把概括词误判成方言名。2026-10-26 的错误形态正是「漏了达梦」，
     * 正向检查足以抓住。
     */
    @Test
    fun `文档站identity包装声明覆盖全部需要包装的方言`() {
        val docs = docsRoot() ?: return skip("文档站不在场，跳过 identity 包装声明校验")
        val needsWrapping = DialectRegistry.INSTANCE.dialects
            .filter { it.renderInsertWrapper("t", "", IdentifierCaseSource.ENTITY) != null }
            .map { it.name }
        assertTrue("实现里应有方言需要 identity 包装（否则本用例失去意义）", needsWrapping.isNotEmpty())

        listOf("features/crud-sql.md", "en/features/crud-sql.md").forEach { rel ->
            val file = File(docs, rel)
            assertTrue("$rel 不存在", file.isFile)
            val line = file.readLines(Charsets.UTF_8)
                .firstOrNull { "IDENTITY_INSERT" in it }
                ?: error("$rel 里找不到提到 SET IDENTITY_INSERT 的那一行（声明被删或改写？）")
            needsWrapping.forEach { dialect ->
                assertTrue(
                    "$rel 的 identity 包装声明漏了 $dialect（该方言 renderInsertWrapper 非 null）：$line",
                    line.contains(dialect, ignoreCase = true) ||
                        line.contains(normalizeForContains(dialect), ignoreCase = true),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 漂移自证（守卫灵敏度：合成错误样本必须被抓）
    // ------------------------------------------------------------------

    @Test
    fun `漂移自证_分页族归错被抓`() {
        // 复刻 2026-10-26 的真实错误形态：MySQL 写成 PG 形态、达梦被划进 PG 族。
        //
        // 刻意**不含** `ROWNUM / FETCH FIRST` 这种写法：归类器见到 FETCH 就归 OFFSET_FETCH，
        // 与 Oracle 实现的族恰好一致，那样这条用例就测不出东西（假绿）。Oracle 的「文档写 ROWNUM」
        // 这个真实错误形态由下面 `漂移自证_ROWNUM写法被归为未知族` 单独钉住。
        val wrong = listOf(
            DocRow(listOf("MySQL / MariaDB"), "`LIMIT n OFFSET m`"),
            DocRow(listOf("DM（达梦） / KingBase（金仓）"), "`LIMIT n OFFSET m`"),
        )
        val actual = mapOf(
            "mysql" to PAGINATION_MYSQL,
            "mariadb" to PAGINATION_MYSQL,
            "dm" to PAGINATION_MYSQL,
            "kingbase" to PAGINATION_PG,
        )
        val violations = docTableViolations(wrong, actual, "自证表", ::classifyPagination)
        assertTrue("MySQL 归错应被抓：$violations", violations.any { "mysql" in it })
        assertTrue("达梦归错应被抓：$violations", violations.any { "dm" in it })
        assertTrue("KingBase 归类正确不应被报：$violations", violations.none { "kingbase" in it })
    }

    @Test
    fun `漂移自证_ROWNUM写法被抓`() {
        // 2026-10-26 查出的真实错误：文档把 Oracle 分页写成 `` `ROWNUM` / `FETCH FIRST` ``。
        //
        // 这条样本里 `FETCH` 在场——首版归类器让 FETCH 先判，于是这种「旧形态混着写」被归进
        // OFFSET_FETCH 族、与实现同族，反向验证实测**抓不住**。现由 ROWNUM 优先判定堵住。
        val rownumForm = "`ROWNUM` / `FETCH FIRST`"
        assertEquals(PAGINATION_ROWNUM, classifyPagination(rownumForm))
        assertEquals(PAGINATION_ROWNUM, classifyPagination("`ROWNUM`"))

        val violations = docTableViolations(
            listOf(DocRow(listOf("Oracle"), rownumForm)),
            mapOf("oracle" to PAGINATION_OFFSET_FETCH),
            "自证表",
            ::classifyPagination,
        )
        assertTrue("ROWNUM 写法应被判为与实现不一致：$violations", violations.any { "oracle" in it })
    }

    @Test
    fun `漂移自证_漏方言被抓`() {
        // 复刻 2026-10-26 的 batch-operation 表：只列了 4 种方言
        val incomplete = listOf(
            DocRow(listOf("MySQL / MariaDB"), "`ON DUPLICATE KEY UPDATE`"),
            DocRow(listOf("PostgreSQL"), "`ON CONFLICT (key) DO UPDATE SET`"),
            DocRow(listOf("SQL Server"), "`MERGE INTO`"),
            DocRow(listOf("Oracle"), "`MERGE INTO`"),
        )
        val actual = mapOf(
            "mysql" to UPSERT_DUP_KEY,
            "mariadb" to UPSERT_DUP_KEY,
            "postgresql" to UPSERT_ON_CONFLICT,
            "sqlserver" to UPSERT_PLAIN_INSERT,
            "oracle" to UPSERT_PLAIN_INSERT,
            "h2" to UPSERT_MERGE_STMT,
        )
        val violations = docTableViolations(incomplete, actual, "自证表", ::classifyUpsert)
        assertTrue("漏方言应被抓：$violations", violations.any { "缺少方言" in it })
        assertTrue("SQL Server 的 MERGE 误报应被抓：$violations", violations.any { "sqlserver" in it })
        assertTrue("Oracle 的 MERGE 误报应被抓：$violations", violations.any { "oracle" in it })
    }

    @Test
    fun `漂移自证_upsert族归错被抓`() {
        // openGauss 那行的「不是 PG 的 ON CONFLICT」不能被归类器误判成 ON_CONFLICT
        assertEquals(UPSERT_DUP_KEY, classifyUpsert("`ON DUPLICATE KEY UPDATE`（不是 PG 的 `ON CONFLICT`）"))
        assertEquals(UPSERT_DUP_KEY, classifyUpsert("`ON DUPLICATE KEY UPDATE` (not PG's `ON CONFLICT`)"))
        assertEquals(UPSERT_PLAIN_INSERT, classifyUpsert("无原生简洁形态，降级纯 INSERT"))
        assertEquals(UPSERT_PLAIN_INSERT, classifyUpsert("No native concise form; degrades to a plain INSERT"))
        assertEquals(UPSERT_MERGE_STMT, classifyUpsert("整语句 `MERGE INTO t (cols) KEY (key) VALUES (...)`"))
        assertEquals(PAGINATION_OFFSET_FETCH, classifyPagination("`OFFSET n ROWS FETCH NEXT m ROWS ONLY`"))
        assertEquals(PAGINATION_MYSQL, classifyPagination("`LIMIT [offset,] count`"))
        assertEquals(PAGINATION_PG, classifyPagination("`LIMIT count OFFSET offset`"))
        // ROWNUM 出现即判错（实现里没有这个形态），不再落到 UNKNOWN
        assertEquals(PAGINATION_ROWNUM, classifyPagination("`ROWNUM` / `FETCH FIRST`"))
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 文档表的一行：[dialects] 为方言单元按 `/` 拆开后的原始名，[form] 为形态单元原文。 */
    internal data class DocRow(val dialects: List<String>, val form: String)

    private fun assertDocTable(
        path: String,
        headerFirstCell: String,
        secondHeaderHint: String,
        label: String,
        actual: Map<String, String>,
        classify: (String) -> String,
    ) {
        val docs = docsRoot() ?: return skip("文档站不在场，跳过 $label")
        val file = File(docs, path)
        assertTrue("$path 不存在（文档站结构变了？）", file.isFile)
        val rows = parseMarkdownTable(file.readText(Charsets.UTF_8), headerFirstCell, secondHeaderHint, path)
        val violations = docTableViolations(rows, actual, label, classify)
        assertTrue("$label 与 Dialect 实现不一致：\n  " + violations.joinToString("\n  "), violations.isEmpty())
    }

    /**
     * 解析 Markdown 表格：定位表头行（首格 = [headerFirstCell]），跳过 `|---|` 分隔行，
     * 收集后续以 `|` 开头的行直到空行。
     *
     * **表头定位必须按「单元格精确相等」而非子串包含**：2026-10-26 首版实现用
     * `line.contains("方言") && line.contains("冲突子句")`，结果命中了 batch-operation 里
     * `| \`saveOrUpdate\` | UPSERT | 插入或更新（冲突子句按方言渲染） |` 这条**数据行**——
     * 表头判据本身成了假绿入口（解析到 1 行 `upsert`，然后报「未知方言 upsert」）。
     * 现改为：切出单元格后要求首格等于 [headerFirstCell]、且存在某格等于 [secondHeaderHint]。
     *
     * `\|` 是表格内转义（SQL Server 那行的 `OFFSET {offset\|0}`），先换成占位符再按 `|` 切，
     * 否则会把一个单元格切成两半、形态串位。
     */
    private fun parseMarkdownTable(
        markdown: String,
        headerFirstCell: String,
        secondHeaderHint: String,
        path: String,
    ): List<DocRow> {
        val lines = markdown.lines()
        val headerIdx = lines.indexOfFirst { line ->
            if (!line.trimStart().startsWith("|")) return@indexOfFirst false
            val cells = splitRow(line.trim())
            cells.firstOrNull() == headerFirstCell && cells.any { it == secondHeaderHint }
        }
        if (headerIdx < 0) {
            error("$path 里找不到表头（首格「$headerFirstCell」、某列「$secondHeaderHint」）——表格被改名或删掉了")
        }

        val rows = mutableListOf<DocRow>()
        var i = headerIdx + 1
        if (i < lines.size && lines[i].contains("---")) i++
        while (i < lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith("|")) break
            i++
            val cells = splitRow(line)
            // 两张受检表都是**两列**（方言 / 形态）：方言在第 0 格、形态在第 1 格。
            // 首版按三列取（cells[1]/cells[2]）导致全部「未解析到任何表格行」。
            if (cells.size < 2) continue
            val dialects = cells[0].split('/').map { it.trim() }.filter { it.isNotEmpty() }
            if (dialects.isEmpty()) continue
            rows += DocRow(dialects, cells[1])
        }
        return rows
    }

    private fun splitRow(line: String): List<String> {
        val escaped = line.replace("\\|", "\u0000")
        return escaped.split('|')
            .map { it.replace("\u0000", "\\|").trim() }
            .let { cells ->
                // 首尾各有一个空元素（行以 | 开头、以 | 结尾），去掉
                val trimmed = cells.dropWhile { it.isEmpty() }
                if (trimmed.isNotEmpty() && trimmed.last().isEmpty()) trimmed.dropLast(1) else trimmed
            }
    }

    /**
     * 方言名归一化：剥掉括号限定并去空格小写。
     *
     * 文档里同一方言有多个写法（`OceanBase（MySQL 模式）` / `OceanBase (MySQL mode)`、
     * `DM（达梦）` / `DM (Dameng)`、`KingBase（金仓）` / `KingBase（人大金仓）` / `KingBase`），
     * 而 [Dialect.name] 只有一种。归一化后按同一个键比对。
     */
    internal fun normalizeDialectName(raw: String): String =
        raw.substringBefore('（').substringBefore('(').trim().lowercase().replace(" ", "")

    private fun normalizeForContains(name: String): String =
        name.substringBefore('（').substringBefore('(').trim()

    private fun skip(reason: String) {
        println("[DialectDocTableGuardTest] SKIP: $reason")
    }

    private companion object {
        const val PAGINATION_MYSQL = "LIMIT_n,m"
        const val PAGINATION_PG = "LIMIT_OFFSET"
        const val PAGINATION_OFFSET_FETCH = "OFFSET_FETCH"

        /** 实现里不存在的形态；文档出现即错（2026-10-26 的真实错误就是 Oracle 写了 ROWNUM）。 */
        const val PAGINATION_ROWNUM = "ROWNUM_UNSUPPORTED"
        const val PAGINATION_UNKNOWN = "UNKNOWN_PAGINATION"

        const val UPSERT_DUP_KEY = "ON_DUPLICATE_KEY"
        const val UPSERT_ON_CONFLICT = "ON_CONFLICT"
        const val UPSERT_MERGE_STMT = "MERGE_STATEMENT"
        const val UPSERT_PLAIN_INSERT = "PLAIN_INSERT"
        const val UPSERT_UNKNOWN = "UNKNOWN_UPSERT"

        /** 带 offset 的分页探针：能同时暴露 `LIMIT n, m` 与 `LIMIT m OFFSET n` 两种族的分歧。 */
        val PAGINATION_PROBE = LimitClause(5L, 10L)
    }
}
