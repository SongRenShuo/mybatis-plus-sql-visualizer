package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 初始化顺序陷阱守卫（T-CaptureTruth T-CT-07 立项，T-ReachAudit T-RA-05 从 DialogWrapper 专项
 * 推广到**全类**）。
 *
 * ## 陷阱形态
 *
 * 类级 `init {}` 块（含其直接调用的同类方法）在**构造期**执行，而 Kotlin 属性初始化按文本顺序
 * 执行——声明在 `init {}` 之后的属性此刻还持有 JVM 默认值（对象类型为 null、布尔 false、数值 0），
 * **不是声明初值**。非空类型不拦（编译期静默通过），平台对 null 也常常容忍，于是表现为静默失效。
 *
 * 真机实证（T-CT-07）：243 平台 `com/intellij/openapi/ui/DialogWrapper` 字节码直证 `init()` 方法体
 * 内调用 `createActions()`；历史案例 `EnhancedCaptureConfirmDialog`（已随 T-EnhancedCaptureDefaultOn
 * 删除）把 `init()` 写在 Kotlin `init {}` 块里，而第三动作 `disableCaptureAction` 声明在其后 →
 * 传进动作数组的是 null → 平台**静默不渲染该按钮**（沙盒日志无「确认框异常」为证：平台容忍 null
 * 而非抛错），用户真机看不到按钮，误以为功能没做。
 *
 * 运行期测试覆盖不到本陷阱：平台不保存 createActions 的返回数组（243 字节码核实无 Action[] 字段，
 * 数组直接进 createSouthPanel），构造完成后反射读到的永远是「已初始化」的字段值——只能源码级把关。
 *
 * ## 判据（两条，都是判据本身，不是白名单）
 *
 * 违规 = 存在类级 `init {}` 块，其**构造期范围**内引用了「文本声明在该 init 块之后」的类级属性，
 * 且该引用是**读**（引用点之前没有对该属性的纯赋值），且该属性的**声明初值与 JVM 默认值不等**。
 *
 * 1. **初值与 JVM 默认值等值即豁免**（`false` / `0` / `null` / `""` / `emptyList()` 等）：
 *    构造期读到的值与声明初值逐字相等，无行为差异——`SqlLogPanel.historyPanelRegistered = false`
 *    即此类（防重复注册守卫读到的 false 正是它想要的语义）。
 * 2. **构造期先写后读即豁免**：`lateinit var modeButton` 在 `toolbar()` 里先赋值再被读，读取点之前
 *    已有赋值 → 不是「读到未初始化值」。真陷阱（T-CT-07）是**只读不写**（createActions 只把字段
 *    传进数组）。
 *
 * 另有两条**不在扫描面内**的形态，同样不是白名单而是判据：
 * - **构造期范围不含 init 块内的嵌套 fun 体**——那是匿名对象/回调的方法体（如三个
 *   `*SqlCompletionContributor` 的 `TAG_NAME_AT_CARET` / `AGGREGATE_FUNCTIONS` / `DYNAMIC_TAGS`
 *   引用点都在 init 块内 `object : CompletionProvider()` 的 `override fun addCompletions` 里），
 *   执行时机在构造**之后**，属性早已初始化；
 * - 声明在 init 块**之前**的属性（含 `companion object` 侧常量——JVM 伴生对象整体初始化语义）。
 *
 * ## 已知保守面（非缺陷，勿误判）
 *
 * init 块内的 **lambda 体按构造期计**（不区分 `apply {}` 这类立即执行的与 `invokeLater {}` 这类延迟
 * 的）。当前全仓零违规，故不需要白名单；将来若出现「回调 lambda 里读后声明属性」的合法形态，处理
 * 顺序是：① 把该 lambda 体按嵌套 fun 体同法排除（判据收紧）；② 万不得已才加白并在此注明理由。
 *
 * 本守卫是**门禁型**；四轴全仓报告（含 when 模式消费、只写不读字段、Topic 配对等本守卫不覆盖的面）
 * 见 `dev-support/audit_reachability.py`。
 */
class DialogWrapperInitOrderGuardTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 // 行注释与 /* */ 块注释（历史事故常以注释形态留存，不剥会误报）。 */
    private fun stripComments(text: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.split('\n').joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    /**
     * 仅用于结构分析（大括号配平）的字符串字面量置空——字面量里的 `{`/`}` 会干扰深度计算，
     * 深度错位即扫描面错位。剥注释后的文本上再做，故不会吃掉代码里的 `"` 用法。
     */
    private fun stripStrings(text: String): String =
        text.replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
            .replace(Regex("\"(?:\\\\.|[^\"\\\\\n])*\""), "\"\"")

    /** 全仓 src/main 源码（剥注释）。 */
    private fun mainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to stripComments(it.readText()) }
            .toList()

    /** DialogWrapper 子类源码（专项用例用；剥注释）。 */
    private fun dialogSources(): List<Pair<String, List<String>>> =
        mainSources()
            .map { (path, code) -> path to code.split('\n') }
            .filter { (_, lines) -> lines.any { it.contains("DialogWrapper(") } }

    // ------------------------------------------------------------------
    // 通用扫描器（可注入源码：专项用例与灵敏度自证共用同一实现）
    // ------------------------------------------------------------------

    /** 类级属性声明（4 空格缩进）→ 属性名 to 行号。 */
    private fun propertyDeclLines(lines: List<String>): Map<String, Int> {
        val decls = mutableMapOf<String, Int>()
        lines.forEachIndexed { i, line ->
            val m = DECL.find(line) ?: return@forEachIndexed
            if (m.range.first == 0) decls.putIfAbsent(m.groupValues[3], i)
        }
        return decls
    }

    /** 每行「进入时」的大括号深度（用于界定 init 块范围与类级成员层级）。 */
    private fun depths(lines: List<String>): IntArray {
        val out = IntArray(lines.size)
        var d = 0
        lines.forEachIndexed { i, l ->
            out[i] = d
            d += l.count { it == '{' } - l.count { it == '}' }
        }
        return out
    }

    /** init 块/函数体结束行（不含）。 */
    private fun blockEnd(lines: List<String>, dep: IntArray, start: Int): Int {
        val d = dep[start]
        var i = start + 1
        while (i < lines.size && dep[i] > d) i++
        return i
    }

    /** init 块（下标 [idx]）的归属类名：向上找最近一条**顶格**类/对象声明（T-M53-II 跨文件解析用）。 */
    private fun ownerClassAt(lines: List<String>, dep: IntArray, idx: Int): String? {
        for (i in idx - 1 downTo 0) {
            if (dep[i] != 0) continue
            CLASS_DEF.find(lines[i])?.takeIf { it.range.first == 0 }?.let { return it.groupValues[1] }
        }
        return null
    }

    private data class Hit(
        val file: String,
        val line: Int,
        val name: String,
        val initializer: String,
        val readBy: List<String>,
    )

    private data class ScanResult(
        /** 全部「后声明属性被构造期读」候选（含被豁免者，供灵敏度自证）。 */
        val candidates: List<String>,
        val exemptByDefaultValue: List<String>,
        val exemptByWrittenFirst: List<String>,
        val violations: List<Hit>,
        val initBlocks: Int,
        val classesWithBodies: Int,
    )

    private fun scan(sources: List<Pair<String, String>>): ScanResult {
        val candidates = mutableListOf<String>()
        val exemptDefault = mutableListOf<String>()
        val exemptWritten = mutableListOf<String>()
        val violations = mutableListOf<Hit>()
        var initBlocks = 0
        var classesWithBodies = 0

        /**
         * T-M53-II（2026-10-10）：同包扩展方法体索引 —— 组合式重构把 `SqlPreviewPanel.toolbar()`
         * 这类构造期被 init 块调用的方法体搬到了同包协作文件。若不跨文件解析，「构造期先写后读」
         * 这条豁免路径就对真实形态失明（判据形同只认单文件老布局），且 init 块会因 `bodies` 为空
         * 被整体跳过——扫描面漏站点正是本仓反复点名的一类假绿。
         * 键 = (接收者类名, 函数名)，与 init 块归属类名 + init 内被调用名配对使用。
         */
        val extBodies = mutableMapOf<Pair<String, String>, String>()
        sources.forEach { (_, raw) ->
            val ls = stripStrings(raw).split('\n')
            val dp = depths(ls)
            ls.forEachIndexed { fi, l ->
                val m = EXT_FUN_DEF.find(l) ?: return@forEachIndexed
                if (m.range.first != 0) return@forEachIndexed
                val owner = m.groupValues[1].substringAfterLast('.')
                extBodies.putIfAbsent(owner to m.groupValues[2], blockEnd(ls, dp, fi).let { ls.subList(fi, it).joinToString("\n") })
            }
        }

        sources.forEach { (file, raw) ->
            val lines = stripStrings(raw).split('\n')
            val dep = depths(lines)
            lines.forEachIndexed { idx, line ->
                if (INIT_BLOCK.find(line)?.range?.first != 0) return@forEachIndexed
                initBlocks++
                val d = dep[idx]
                val end = blockEnd(lines, dep, idx)
                // 构造期范围 = init 块去掉嵌套 fun 体（匿名对象/回调的方法体，构造后才执行）
                val skip = mutableSetOf<Int>()
                for (i in idx + 1 until end) {
                    if (FUN_DEF.find(lines[i])?.range?.first == 0) skip.addAll(i until blockEnd(lines, dep, i))
                }
                val scope = (idx + 1 until end).filter { it !in skip }.joinToString("\n") { lines[it] }
                val invoked = INVOKED.findAll(scope).map { it.groupValues[1] }
                    .filter { it !in KOTLIN_KEYWORDS }.toMutableSet()
                // 平台契约：DialogWrapper.init() 内部回调 createActions()（243 字节码直证）
                if ("init" in invoked) invoked.add("createActions")
                val decls = mutableMapOf<String, Int>()
                var i = end
                while (i < lines.size && dep[i] >= d) {
                    if (dep[i] == d) {
                        DECL.find(lines[i])?.takeIf { it.range.first == 0 }
                            ?.let { decls.putIfAbsent(it.groupValues[3], i) }
                    }
                    i++
                }
                if (decls.isEmpty()) return@forEachIndexed
                val bodies = mutableMapOf<String, String>()
                lines.forEachIndexed { fi, _ ->
                    val fm = FUN_DEF.find(lines[fi]) ?: return@forEachIndexed
                    if (fm.range.first == 0 && fm.groupValues[2] in invoked && dep[fi] >= d) {
                        bodies[fm.groupValues[2]] = lines.subList(fi, blockEnd(lines, dep, fi)).joinToString("\n")
                    }
                }
                // 补上「构造期被调用、但方法体在同包协作文件里」的扩展方法体（T-M53-II）：
                // 只在 init 块确被调用、且接收者就是本 init 块的归属类时才并入，避免同名误并。
                // 必须在下面的 isEmpty 判定**之前**——否则「构造期调用的方法体全在兄弟文件」的
                // 类会被整体跳过（正是本轮要修的那类漏站点）。
                ownerClassAt(lines, dep, idx)?.let { owner ->
                    invoked.filter { it !in bodies }.forEach { name ->
                        extBodies[owner to name]?.let { bodies[name] = it }
                    }
                }
                if (bodies.isEmpty()) return@forEachIndexed
                classesWithBodies++
                decls.forEach { (name, declLine) ->
                    val re = NAME_CACHE.getOrPut(name) { Regex("\\b" + Regex.escape(name) + "\\b") }
                    if (bodies.none { (_, body) -> re.containsMatchIn(body) }) return@forEach
                    candidates += name
                    // 读点 = 该引用点之前（同一方法体内）没有纯赋值
                    val readBy = bodies.filterValues { body ->
                        var sawWrite = false
                        var read = false
                        re.findAll(body).forEach { m ->
                            if (writesAt(body, m.range.first)) {
                                sawWrite = true
                            } else if (!sawWrite) {
                                read = true
                            }
                        }
                        read
                    }.keys.toList()
                    if (readBy.isEmpty()) {
                        // 构造期范围内该属性「只被写」或「先写后读」——读取点之前已有赋值，非未初始化读
                        exemptWritten += name
                        return@forEach
                    }
                    val decl = lines[declLine]
                    val initValue = initializerOf(lines, declLine)
                    val lateinit = decl.contains("lateinit")
                    val delegated = Regex("\\bby\\s+\\w").containsMatchIn(decl)
                    if (!lateinit && !delegated && (initValue == null || initValue in JVM_DEFAULTS)) {
                        // 初值与 JVM 默认值等值（或无背后字段）：构造期读到的就是声明初值
                        exemptDefault += name
                    } else {
                        violations += Hit(file, declLine + 1, name, initValue ?: "<无初值>", readBy)
                    }
                }
            }
        }
        return ScanResult(candidates, exemptDefault, exemptWritten, violations, initBlocks, classesWithBodies)
    }

    /** 该引用点是否为「纯赋值」`name = expr`（排除 `==`/`!=`/`<=`/`>=`/`+=` 等）。 */
    private fun writesAt(body: String, pos: Int): Boolean =
        PLAIN_WRITE.find(body, pos)?.range?.first == pos

    /**
     * 声明初值文本（`=` 后的表达式）；无初值（自定义访问器 / lateinit）返回 null。
     *
     * 初值跨行（`private val x: T =\n    SomeObject(...)`）时取下一行——只影响报错文案可读性，
     * 判据只关心「是否等于 JVM 默认值」。
     */
    private fun initializerOf(lines: List<String>, declLine: Int): String? {
        val decl = lines[declLine]
        val m = Regex("(?<![=!<>+\\-*/])=(?!=)").find(decl) ?: return null
        val sameLine = decl.substring(m.range.last + 1).trim()
        if (sameLine.isNotEmpty()) return sameLine
        return lines.getOrNull(declLine + 1)?.trim()?.takeIf { it.isNotEmpty() } ?: ""
    }

    @Test
    fun test_init块经方法引用的后声明属性_初值与JVM默认值不等即违规() {
        val result = scan(mainSources())
        // 扫描面失效即假绿：全仓 init 块数与「有同类方法体可解析」的类数都必须非零
        assertTrue("应至少扫描到若干 init 块（扫描面失效即为假绿）：${result.initBlocks}", result.initBlocks >= 20)
        assertTrue("应至少解析到一个 init 块调用的同类方法体（扫描面失效即为假绿）", result.classesWithBodies >= 5)
        // 两条豁免路径必须真实生效过（否则「零违规」可能只是因为扫描面根本没看到那两处形态）
        assertTrue(
            "应至少有一处「初值=JVM 默认值」豁免（SqlLogPanel.historyPanelRegistered = false）：" +
                "${result.exemptByDefaultValue}",
            "historyPanelRegistered" in result.exemptByDefaultValue,
        )
        assertTrue(
            "应至少有一处「构造期先写后读」豁免（SqlPreviewPanel 的 lateinit modeButton 在 toolbar() 里赋值）：" +
                "${result.exemptByWrittenFirst}",
            "modeButton" in result.exemptByWrittenFirst,
        )
        assertTrue(
            "存在 Kotlin 初始化顺序陷阱（构造期读到 JVM 默认值而非声明初值）：\n" +
                result.violations.joinToString("\n") { v ->
                    "${v.file}: ${v.name}（声明@${v.line}）初值 `${v.initializer}` 与 JVM 默认值不等，" +
                        "却在 init 期经 ${v.readBy} 被读——构造期该属性是 null/0/false，" +
                        "调用方（含平台回调）会静默拿到默认值。" +
                        "修法：把声明上移到 init 块之前，或让初值与 JVM 默认值等值"
                },
            result.violations.isEmpty(),
        )
    }

    /**
     * 扫描器灵敏度与豁免自证（合成源码）：四种形态喂给同一实现，断言各自落位。
     *
     * 存在理由：真实全仓当前零违规，若扫描面哪天被改瞎（正则写坏、深度算错），主用例仍会「绿」——
     * 这是守卫类测试最典型的假绿形态。本用例用合成源码把判据钉死：真陷阱必红、两条豁免必生效、
     * 延迟回调（嵌套 fun 体）必不进扫描面。
     */
    @Test
    fun test_扫描器灵敏度与豁免自证() {
        val defect = """
            class SyntheticDefect {
                init {
                    init()
                }
                private fun buildPanel(): Int = 1
                override fun createActions(): Array<Any> = arrayOf(thirdAction)
                private val thirdAction: Any = Any()
            }
        """.trimIndent()
        val defaultValued = """
            class SyntheticDefaultValued {
                init {
                    addTab()
                }
                private fun addTab() {
                    if (!registered) { registered = true }
                }
                private var registered = false
            }
        """.trimIndent()
        val deferred = """
            class SyntheticDeferred {
                init {
                    register(object : Runnable {
                        override fun run() {
                            val x = laterConstant
                            println(x)
                        }
                    })
                }
                private fun register(r: Runnable) {
                    r.hashCode()
                }
                private val laterConstant = 42
            }
        """.trimIndent()
        val writtenFirst = """
            class SyntheticWrittenFirst {
                init {
                    buildToolbar()
                }
                private fun buildToolbar() {
                    modeButton = Any()
                    modeButton.hashCode()
                }
                private lateinit var modeButton: Any
            }
        """.trimIndent()

        val result = scan(listOf(
            "src/main/kotlin/SyntheticDefect.kt" to defect,
            "src/main/kotlin/SyntheticDefaultValued.kt" to defaultValued,
            "src/main/kotlin/SyntheticDeferred.kt" to deferred,
            "src/main/kotlin/SyntheticWrittenFirst.kt" to writtenFirst,
        ))
        val names = result.violations.map { it.name }
        assertTrue(
            "T-CT-07 形态（init() → createActions 读后声明对象属性）必须被判定为违规：$names",
            "thirdAction" in names,
        )
        assertTrue(
            "延迟回调形态（init 块内嵌套 fun 体引用后声明属性）不得进扫描面：${result.candidates}",
            "laterConstant" !in result.candidates,
        )
        assertTrue(
            "初值=JVM 默认值形态（registered = false）必须走豁免而非违规：${result.exemptByDefaultValue}",
            "registered" in result.exemptByDefaultValue && "registered" !in names,
        )
        assertTrue(
            "构造期先写后读形态（lateinit modeButton 先赋值再读）必须走豁免而非违规：${result.exemptByWrittenFirst}",
            "modeButton" in result.exemptByWrittenFirst && "modeButton" !in names,
        )
    }

    private companion object {
        /** 类级属性声明（`@Anno val x` / `private var x` 形态；行首匹配）。 */
        val DECL = Regex(
            "^( *)(?:(?:private|internal|protected|public|open|override|lateinit|const|@\\w+(?:\\([^)]*\\))?)\\s+)*" +
                "(val|var)\\s+(\\w+)"
        )
        val INIT_BLOCK = Regex("( *)init\\s*\\{")
        val FUN_DEF = Regex(
            "^( *)(?:(?:private|internal|protected|public|open|override|suspend|inline|tailrec" +
                "|operator|abstract)\\s+)*fun\\s+(?:<[^>]+>\\s+)?(\\w+)\\s*\\("
        )

        /**
         * 顶格**扩展函数**定义（`fun <接收者>.<名>(`）——T-M53-II 组合式重构后，类的成员方法体
         * 可能被搬到同包协作文件里（如 `SqlPreviewPanel.toolbar()`）。
         * 收 group[1]=接收者（取最后一段）、group[2]=函数名。
         */
        val EXT_FUN_DEF = Regex(
            "^(?:(?:private|internal|protected|public|open|override|suspend|inline|tailrec" +
                "|operator|abstract)\\s+)*fun\\s+([\\w.]+)\\.(\\w+)\\s*\\("
        )

        /** 顶格类/对象声明（用于给 init 块找归属类名，进而解析兄弟文件的扩展方法体）。 */
        val CLASS_DEF = Regex("^[\\w ]*\\b(?:class|object|interface)\\s+(\\w+)")
        val INVOKED = Regex("(?:[\\w.]+\\s*\\.\\s*)?(\\w+)\\s*\\(")
        val PLAIN_WRITE = Regex("[A-Za-z_]\\w*\\s*=(?!=)")

        /** 与 JVM 默认值等值的字面初值：构造期读到它与读到声明初值无行为差异。 */
        val JVM_DEFAULTS = setOf(
            "false", "0", "0L", "0l", "0.0", "0f", "0F", "0.0f", "null", "\"\"",
            "emptyList()", "emptyMap()", "emptySet()", "emptyArray()",
            "listOf()", "mapOf()", "setOf()", "arrayOf()", "Unit",
        )

        /** Kotlin 关键字/常用内联函数：出现在 init 块语句位但不是「同类方法调用」。 */
        val KOTLIN_KEYWORDS = setOf(
            "if", "for", "while", "when", "return", "catch", "synchronized", "require", "check", "error",
            "listOf", "arrayOf", "setOf", "mapOf", "mutableListOf", "also", "let", "run", "apply", "with",
            "use", "repeat", "invokeLater", "invokeAndWait",
        )

        val NAME_CACHE = HashMap<String, Regex>()
    }
}
