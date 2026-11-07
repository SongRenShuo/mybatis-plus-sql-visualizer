package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 枚举常量可达性守卫（T-ReachAudit T-RA-05；F6 `DiffType.CHANGE` 顺带覆盖）。
 *
 * ## 陷阱形态（两族，同源于已修缺陷）
 *
 * - **死分支**（T-ConsentTruth 形态，真机报障）：枚举某态在代码里被**比较**（`if (outcome == X)`）
 *   或用作 **when 模式**，却全仓没有任何产出点（赋值 / return / 实参 / when 结果位 / 映射值 / if-else
 *   分支值）→ 该分支恒假或恒走 else。T-PS4-01 三态化时只接上 `DISABLE_CAPTURE`，`ACCEPTED` 无产出 →
 *   `confirm()` 恒 false → 增强捕获经 UI 完全不可达（用户「点确认但开启标识没变且不生效」）。
 * - **死常量**（F6 形态）：常量声明后全仓零引用（既不消费也不产出）——`DiffType.CHANGE` 既无产出点
 *   也无渲染分支，「某行被修改」这一语义无法表达。
 *
 * ## 判据
 *
 * 扫描面 `src/main`（**剥离注释后**）。对每个 `enum class` 的每个常量统计 `EnumName.CONST` 限定形态
 * 引用点的位置类型：
 * - 消费 = 被比较（`==` `!=` `===` `!==` `<=` `>=`）或后随 `->`（when 模式）；
 * - 产出 = 赋值 / `return` / 前随 `->`（when 结果位）/ 实参（`(` `,`）/ 映射值（`to`）/ if-else 分支值
 *   / 块末表达式（后随 `}` `)` `;` `,`）；
 * - 无法判定按消费计（保守：宁可多报，不可漏报）。
 *
 * 违规 = 该常量**零产出点**，且其**枚举类型活跃**（类型名在 src/main 代码里被引用过，排除声明位）。
 * 两个维度都不满足时不算违规：类型本身在 src/main 零引用（如 KDoc 明示预留位 `CommentSource`）——
 * 无消费者就无从「静默失效」，只作报告级信息（见 `dev-support/audit_reachability.py` 轴 1）。
 *
 * ## 两类扫描器误报必须规避（T-ReachAudit 实证，勿回退）
 *
 * ① **必须先剥注释**：KDoc 里的大写词会被当枚举常量（`model/BatchModel.kt` 的 `VALUES/CONFLICT/KEY`
 *    实为文档词），KDoc 交叉引用 `[CommentSource.SCHEMA]` 会被当「消费」；本守卫在剥注释文本上统计，
 *    并用 `test_扫描器灵敏度自证` 把这两点钉死。
 * ② 引用一律按 `EnumName.CONST` 限定形态统计：`import` 后裸用常量名无法静态归属（已知漏报面），
 *    反射/`values()`/`valueOf()` 驱动的产出与消费同样不可见。
 *
 * ## 白名单
 *
 * `WHITELIST` 每条必须带理由注释，且 `test_白名单条目必须仍是未产出的活跃常量` 会断言条目仍然成立——
 * 一旦该常量获得产出点（或枚举被删），守卫立即红并提示删除条目，防止白名单腐烂成永久豁免。
 *
 * 本守卫是**门禁型**（误报会阻断构建，故判据取「零产出 + 类型活跃」这一确定子集）；四轴全仓报告
 * （含 when 模式消费清单、只写不读字段、Topic 配对）见 `dev-support/audit_reachability.py`。
 */
class EnumReachabilityGuardTest {

    // T-TestPerf6：repoRoot/stripComments 挪 companion（快照构建在 companion 内），语义不变
    private fun repoRoot(): File = Companion.repoRoot()

    /** 剥离 // 行注释与 /* */ 块注释（KDoc 里的文档词与交叉引用不算代码引用）。 */
    private fun stripComments(text: String): String = Companion.stripComments(text)

    /** 全仓 src/main 源码（剥注释）。T-TestPerf6 起读快照（两个门禁用例共享一次遍历+读取+剥注释；scan/enumDecls 的判定逻辑与「入口统一 strip」契约不动）。 */
    private fun mainSources(): List<Pair<String, String>> = mainSourcesSnapshot

    // ------------------------------------------------------------------
    // 扫描器（可注入源码：主用例与灵敏度自证共用同一实现）
    // ------------------------------------------------------------------

    private data class EnumDecl(val file: String, val line: Int, val name: String, val constants: List<String>)

    private data class Row(
        val file: String,
        val line: Int,
        val enumName: String,
        val constant: String,
        val produced: Int,
        val consumed: Int,
        val consumedAtLines: List<Int>,
        val enumLive: Boolean,
    ) {
        val key: String get() = "$enumName.$constant"
    }

    /**
     * 枚举声明清单（文件 / 行号 / 名 / 常量表）。
     *
     * 判据前提：剥离注释（KDoc 文档词与交叉引用不算代码引用）——在本函数入口统一做，
     * 调用方（含合成源码用例）不必各自记得。
     */
    private fun enumDecls(sources: List<Pair<String, String>>): List<EnumDecl> {
        val decls = mutableListOf<EnumDecl>()
        sources.forEach { (file, raw) ->
            val code = stripComments(raw)
            ENUM_DEF.findAll(code).forEach { m ->
                val brace = code.indexOf('{', m.range.last + 1)
                if (brace < 0) return@forEach
                decls += EnumDecl(
                    file = file,
                    line = code.substring(0, m.range.first).count { it == '\n' } + 1,
                    name = m.groupValues[1],
                    constants = enumConstants(code.substring(brace + 1, matchBrace(code, brace) - 1)),
                )
            }
        }
        return decls
    }

    /** 从 `{` 到配对 `}` 之后一位的下标（枚举体范围）。 */
    private fun matchBrace(text: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return text.length
    }

    /**
     * 枚举常量表：截到顶层 `;`（常量段终止），逐项取末个标识符为常量名。
     *
     * 取「末个标识符」而非「全大写词」是为了兼容 PascalCase 常量（`State.UpToDate`、
     * `GuideChoice.OpenSettings`）；构造实参与条目体按大括号/圆括号配平剔除，注解名剔除。
     */
    private fun enumConstants(body: String): List<String> {
        var depth = 0
        var cut = body.length
        body.forEachIndexed { i, c ->
            when {
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ';' && depth == 0 && cut == body.length -> cut = i
            }
        }
        val sb = StringBuilder()
        depth = 0
        body.substring(0, cut).forEach { c ->
            when {
                c == '(' || c == '{' -> depth++
                c == ')' || c == '}' -> depth--
                depth == 0 -> sb.append(c)
            }
        }
        val constants = mutableListOf<String>()
        sb.split(',').forEach { chunk ->
            val ids = Regex("[A-Za-z_]\\w*").findAll(chunk.replace(Regex("@\\w+"), "")).map { it.value }.toList()
            val last = ids.lastOrNull() ?: return@forEach
            if (last !in constants) constants += last
        }
        return constants
    }

    /**
     * 文本尾部是否匹配 `pattern`（等价于「正则 + `$` 锚」）。
     *
     * 不用 `$` 锚是因为 Kotlin 普通字符串里 `$` 是模板起始符，写 `"\\s*$"` 会被词法层吃掉、
     * 写成 `"\\s*\\$"` 又变成「字面美元符」——两种写法都静默失效（T-RA-05 首版实测：判据整体
     * 退化成「只看引用点后面」，全仓误报 40 处）。尾部判定一律走本函数。
     */
    private fun tailMatches(pattern: String, text: String): Boolean =
        Regex(pattern).findAll(text).lastOrNull()?.range?.last == text.length - 1

    /** 引用点是否为产出点（false = 消费点；无法判定按消费计）。 */
    private fun isProduction(code: String, start: Int, end: Int): Boolean {
        // 回退跳过限定链（`Foo.Bar.CONST` 的 `Foo.Bar.`）——只看限定链之前是什么
        var i = start
        while (i > 0 && (code[i - 1].isLetterOrDigit() || code[i - 1] == '_' ||
                code[i - 1] == '.' || code[i - 1] == ']')
        ) {
            i--
        }
        val before = code.substring(maxOf(0, i - 40), i).trimEnd()
        val after = code.substring(end, minOf(code.length, end + 40))
        if (Regex("^\\s*->").containsMatchIn(after)) return false // when 模式
        if (tailMatches("(===|!==|==|!=|<=|>=)\\s*", before)) return false // 被比较
        if (tailMatches("when\\s*\\(\\s*", before)) return false // when 主语位
        // 赋值 / return / when 结果位 / 实参（`(` `,`）/ if-else 分支值（前随 `)`）/
        // 映射值（`to`）/ 块末表达式
        if (tailMatches("(=|return(?:@[A-Za-z_]\\w*)?|->|\\(|,|\\)|\\bto|\\bin|\\bby|\\belse|\\?:|\\+=|&&|\\|\\|)\\s*", before)) {
            return true
        }
        if (Regex("^\\s*[});,]").containsMatchIn(after)) return true // 块末表达式
        if (Regex("^\\s*(to|in)\\b").containsMatchIn(after)) return true // 中缀操作数
        return false
    }

    private fun scan(sources: List<Pair<String, String>>): List<Row> {
        // 判据前提：剥离注释（KDoc 文档词与交叉引用不算代码引用）——在扫描器入口统一做，
        // 调用方（含合成源码用例）不必各自记得
        val normalized = sources.map { (file, raw) -> file to stripComments(raw) }
        val code = normalized.joinToString("\n") { it.second }
        val lineStarts = mutableListOf(0)
        code.forEachIndexed { i, c -> if (c == '\n') lineStarts += i + 1 }
        fun lineOf(pos: Int): Int {
            val idx = java.util.Collections.binarySearch(lineStarts, pos)
            return if (idx >= 0) idx + 1 else -idx
        }
        return enumDecls(normalized).flatMap { decl ->
            val occurrences = Regex("\\b" + Regex.escape(decl.name) + "\\b").findAll(code).toList()
            // 活跃 = 排除「enum class X」声明位后，全仓仍有该类型名的代码引用
            val live = occurrences.any { m ->
                !tailMatches("\\bclass\\s+", code.substring(maxOf(0, m.range.first - 12), m.range.first))
            }
            decl.constants.map { constant ->
                var produced = 0
                var consumed = 0
                val consumedAt = mutableListOf<Int>()
                occurrences.forEach { m ->
                    val afterName = m.range.last + 1
                    if (code.getOrNull(afterName) != '.') return@forEach
                    val id = IDENT.find(code, afterName + 1) ?: return@forEach
                    if (id.range.first != afterName + 1 || id.value != constant) return@forEach
                    if (isProduction(code, m.range.first, id.range.last + 1)) {
                        produced++
                    } else {
                        consumed++
                        consumedAt += lineOf(m.range.first)
                    }
                }
                Row(decl.file, decl.line, decl.name, constant, produced, consumed, consumedAt, live)
            }
        }
    }

    // ------------------------------------------------------------------

    @Test
    fun test_枚举常量被消费却零产出即违规_死分支与死常量() {
        val rows = scan(mainSources())
        assertTrue("应至少扫描到 200 个枚举常量（扫描面失效即为假绿）：${rows.map { it.key }.distinct().size}", rows.size >= 200)
        val violations = rows
            .filter { it.enumLive && it.produced == 0 }
            .filter { it.key !in WHITELIST }
        val detail = violations.joinToString("\n") { r ->
            val kind = if (r.consumed > 0) "死分支（有消费、零产出）" else "死常量（零引用）"
            "  ${r.file}:${r.line} ${r.key} —— $kind" +
                (if (r.consumed > 0) "，消费点行 ${r.consumedAtLines}" else "")
        }
        assertTrue(
            "枚举存在不可达常量（声明了却不产出，消费者恒走不到 / 语义无法表达）：\n$detail\n" +
                "修法二选一：① 补产出点（赋值/return/实参/when 结果位）；② 删除该常量（含其消费分支）。" +
                "若属「有意保留的未产出位」，在 WHITELIST 加条目并写清理由（守卫会校验条目仍然成立）",
            violations.isEmpty(),
        )
    }

    @Test
    fun test_扫描器灵敏度自证_KDoc词与注释交叉引用不误报() {
        val synthetic = """
            enum class SyntheticKind {
                INSERT,
                /**
                 * UPSERT：`INSERT ... ON CONFLICT (id) DO UPDATE SET` 与 `MERGE INTO t (c) KEY (id)`
                 * 等文档词 VALUES / CONFLICT / KEY 不得被当常量。
                 */
                UPSERT,
            }

            /** 预留位（KDoc 交叉引用 [SyntheticReserved.SCHEMA] 不算代码消费）。 */
            enum class SyntheticReserved { SCHEMA, ANNOTATION }

            enum class SyntheticCamel { UpToDate, Checking }

            enum class SyntheticDefect { LIVE, DEAD }

            enum class SyntheticProduced { P, Q }

            object SyntheticConsumer {
                fun use(kind: SyntheticKind): Boolean = kind == SyntheticKind.INSERT
                fun useCamel(state: SyntheticCamel): Boolean = state == SyntheticCamel.UpToDate
                fun useDefect(d: SyntheticDefect): Boolean = d == SyntheticDefect.DEAD
                fun useProduced(p: SyntheticProduced): Boolean = p == SyntheticProduced.P
                fun produce(): SyntheticProduced = SyntheticProduced.P
                fun produceViaLabel(): SyntheticProduced = run { return@run SyntheticProduced.Q }
            }
        """.trimIndent()
        val sources = listOf("src/main/kotlin/Synthetic.kt" to synthetic)
        val decls = enumDecls(sources).associateBy { it.name }
        assertEquals(
            "KDoc 里的文档词（VALUES/CONFLICT/KEY）不得被当枚举常量：${decls["SyntheticKind"]?.constants}",
            listOf("INSERT", "UPSERT"),
            decls["SyntheticKind"]?.constants,
        )
        assertEquals(
            "PascalCase 常量名必须完整提取：${decls["SyntheticCamel"]?.constants}",
            listOf("UpToDate", "Checking"),
            decls["SyntheticCamel"]?.constants,
        )

        val rows = scan(sources).associateBy { it.key }
        val dead = rows["SyntheticDefect.DEAD"]!!
        assertTrue("被比较且零产出的常量必须判为违规：$dead", dead.produced == 0 && dead.consumed > 0)
        assertTrue("被产出且被消费的常量不得判违规：${rows["SyntheticProduced.P"]}", rows["SyntheticProduced.P"]!!.produced > 0)
        assertTrue("return@run 有标签返回必须算真实产出：${rows["SyntheticProduced.Q"]}",
            rows["SyntheticProduced.Q"]!!.produced > 0)
        val consumedOnly = rows["SyntheticKind.INSERT"]!!
        assertTrue("被比较且零产出的常量应判为违规：$consumedOnly", consumedOnly.produced == 0 && consumedOnly.consumed > 0)
        assertTrue(
            "仅出现在 KDoc 交叉引用里的枚举不得算活跃（否则 CommentSource 预留位会误报）：" +
                "${rows["SyntheticReserved.SCHEMA"]}",
            rows["SyntheticReserved.SCHEMA"]!!.enumLive.not(),
        )
    }

    @Test
    fun test_白名单条目必须仍是未产出的活跃常量() {
        val rows = scan(mainSources()).associateBy { it.key }
        WHITELIST.forEach { (key, reason) ->
            assertTrue("白名单条目理由不得为空（$key）", reason.isNotBlank())
            val row = rows[key] ?: error("白名单条目 $key 已不存在（常量被删或改名）——请同步删除该条目")
            assertTrue(
                "白名单条目 $key 已获得产出点（不再需要豁免）——请从 WHITELIST 删除该条目",
                row.produced == 0 && row.enumLive,
            )
        }
    }

    private companion object {
        val ENUM_DEF = Regex("\\benum\\s+class\\s+(\\w+)")
        val IDENT = Regex("[A-Za-z_]\\w*")

        private fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir")).absoluteFile
            repeat(6) {
                if (File(dir, "src/main/kotlin").isDirectory) return dir
                dir = dir.parentFile ?: return@repeat
            }
            error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
        }

        /** 剥离 // 行注释与 /* */ 块注释（KDoc 里的文档词与交叉引用不算代码引用）。 */
        private fun stripComments(text: String): String {
            val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text) { m ->
                m.value.replace(Regex("[^\n]"), " ")
            }
            return noBlock.split('\n').joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
        }

        // T-TestPerf6：src/main 扫描快照——同一次 JVM 内文件内容恒定，两个门禁用例共享一次
        // 遍历+读+剥注释；scan/enumDecls 的判定逻辑不动（自证用例依赖「入口统一 strip」契约）。
        private val mainSourcesSnapshot: List<Pair<String, String>> by lazy {
            repoRoot().resolve("src/main").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(it.readText()) }
                .toList()
        }

        /**
         * 白名单（每条必须带理由）。
         *
         * - `SubmitPhase.SUCCESS`：`ErrorReportDialog.submitStatusText` 的 when 分支返回 null，
         *   其 KDoc 明示「成功即关窗，不需要写状态行」——提交成功走 `close(OK_EXIT_CODE)`，
         *   该态由设计决定永不产出（穷尽性映射保留位）。**不是**静默失效：分支行为与文档一致。
         *   注意：这条豁免的代价是「该形态的死分支不再报警」——若将来有第二例，先判断能否改判据
         *   （如按「消费点是否为穷尽 when」细分），再考虑加白。
         * - `Capability.EXPLAIN`（R52，2026-10-08 收口门禁抓出）：同类形态——能力矩阵需**单列**
         *   EXPLAIN 行以保矩阵完整，但该枚举刻意**不产出** EXPLAIN 缺口：EXPLAIN 缺口归既有
         *   `ExplainGate` 轻通知（不重复进执行确认弹窗，见 `DialectCapabilities` KDoc）。消费点＝
         *   矩阵渲染（`summaryText`），产出点缺席系设计。**不是**静默失效：矩阵列与文档一致。
         */
        val WHITELIST = mapOf(
            "SubmitPhase.SUCCESS" to
                "KDoc 明示预留：成功即关窗，不需要写状态行（ErrorReportDialog.submitStatusText 分支返回 null）",
            "Capability.EXPLAIN" to
                "有意保留的未产出位（R52）：矩阵单列 EXPLAIN 保完整，缺口归 ExplainGate 轻通知、不进确认弹窗",
            "Kind.GEOGRAPHY" to
                "有意保留的未产出位（F-15）：还原层拿不到列类型（geometry/geography 只能按值反解），" +
                "生产恒用 GEOMETRY 前缀；常量留给真库列类型接入后按列分派，语法对称仅前缀不同",
        )
    }
}
