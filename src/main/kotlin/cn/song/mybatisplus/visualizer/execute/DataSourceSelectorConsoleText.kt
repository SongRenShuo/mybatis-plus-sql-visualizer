package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.FatalErrors
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import javax.swing.Icon

// T-M53-II（2026-10-10）同法，D202 第六次登记：DataSourceSelector 的「控制台文本探测与判定」
// 内聚成员（结果区清理、残渣/回显/错误签名/状态行判定、提示符解析），以同包扩展函数形式搬出
// ——方法体逐字符搬运；仅原属对象成员的私有 Regex/列表随之落为文件级 private/internal 常量。

    /**
     * M36.5.50：清空 RunnerLayoutUi 的**结果 Tab**（保留 Output 输出与 Tx 控制台），
     * 用于预览切换时消除上个 SQL 的结果残留。反射调用 DBN 的 removeContent。
     * 与 M36.5.45b 的差异：彼时清空后无 realignSplitDivider 兜底导致布局塌缩，已回退；
     * 现 M36.5.46 换入后校准 divider，清空后 bottom 为空组件也不再塌缩。
     */
    internal fun DataSourceSelector.clearResultContents(ui: Any?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (ui == null) return
        runCatching {
            val contents = ui.javaClass.getMethod("getContents").invoke(ui) as? Array<com.intellij.ui.content.Content>
                ?: return
            val remove = ui.javaClass.getMethod("removeContent", com.intellij.ui.content.Content::class.java, Boolean::class.javaPrimitiveType)
            contents.filter { !isOutputContent(it) }.forEach { content ->
                runCatching { remove.invoke(ui, content, true) }.onFailure { ex ->
                    LOG.warn("[M36] clear result content FAILED: ${ex.message}")
                }
            }
            LOG.info("[M36] result contents cleared (preview switch, removed=${contents.count { !isOutputContent(it) }})")
        }.onFailure { e ->
            LOG.warn("[M36] clear result contents FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.57：取 console history 文档长度（反射 LanguageConsoleView historyViewer），
     * 用作早检基线——本次执行后新增文本的起始偏移。
     */
    internal fun DataSourceSelector.consoleHistoryLength(console: Any?): Int {
        if (console == null) return 0
        return runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return 0
            val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return 0
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return 0
            (doc.javaClass.getMethod("getTextLength").invoke(doc) as? Int) ?: 0
        }.getOrDefault(0)
    }

    /**
     * T-ExecSilentNoop F1a 探针：提交前的一次性可执行性预检 + 现场埋点。
     *
     * DBN 侧存在两条**全静默出口**（javap 实证，database-plugin.jar）：`chooseStatements`
     * 空选集且 `execOutside==1` 时既不消费 model 也不提示、直接 return；`beforeExecuteQueries`
     * 遇 `model.statements().isEmpty()` 或 `console.isValid()==false` 返回 false → 整段跳过。
     * 两者都无输出、无结果 Tab、无异常。此探针把这两个出口对应的现场量提前照出来，
     * 归因"查询没提交"具体撞的是哪一条，并据此决定是否走 `executeQueries(freshModel)` 兜底。
     * 纯反射 + runCatching，任何一步失败降级为 null，绝不阻断执行。
     *
     * @return null 表示"DBN 侧可执行（或探不到），按 native 链走"；
     *         非 null 表示"预检判定 DBN 侧会静默拒绝"，返回真实原因描述。
     */
    internal fun DataSourceSelector.extractConsoleResidue(
        console: Any?,
        sqlText: String?,
        LOG: com.intellij.openapi.diagnostic.Logger,
    ): String? {
        if (console == null) return null
        return runCatching {
            val text = newConsoleText(console) ?: return null
            if (text.isBlank()) return null
            val sqlNorm = normalizeForEcho(sqlText.orEmpty())
            val prompt = consolePrompt(console)
            val kept = text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { isConsoleStatusLine(it) }
                .filterNot { isSqlEchoLine(it, sqlNorm, prompt) }
            if (kept.isEmpty()) return null
            val joined = kept.takeLast(RESIDUE_MAX_LINES).joinToString("\n")
            val out = if (joined.length > RESIDUE_MAX_CHARS) joined.take(RESIDUE_MAX_CHARS) else joined
            LOG.info("[T-CEC] console residue (no known signature): ${out.replace('\n', '⏎').take(200)}")
            out
        }.getOrNull()
    }

    /**
     * T-CEC：读取 console history 文档中**基线之后**的新增文本（读不到 / 无新增返 null）。
     *
     * T-PerfSweep2-01 的片段物化契约在此保持（ConsoleErrorExtractTest 逐条断言）：baseline<=0
     * 走整串一次、>0 只切一刀、无增长零物化——本函数是两个定时器逐拍调用的共用读取面，
     * 抽出来只为让兜底呈现复用同一份切片语义。
     */
    internal fun DataSourceSelector.newConsoleText(console: Any?): String? {
        if (console == null) return null
        val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return null
        val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return null
        val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return null
        // T-PerfSweep2-01：getCharsSequence + subSequence 片段读取——本函数被 600ms×8 拍与
        // 400ms×40/100 拍两个定时器**逐拍**调用，此前 doc.getText() 每拍整串拷贝全史
        // （history 随长会话涨到 MB 级后是 EDT 上唯一可感知的 O(全史) 重复分配）。
        // 只物化 baseline 之后的切片；getCharsSequence 与 getText 同属 Document 接口，
        // 反射可达性一致，且免去 BadLocationException 面。
        val seq = doc.javaClass.getMethod("getCharsSequence").invoke(doc) as? CharSequence ?: return null
        // M36.5.57：只扫本次执行之后新增的文本（基线截断）——忽略 history 残留的旧错误。
        // baseline 可能因 DBN 写入历史而增长；若文本长度 <= 基线说明无新增内容，返回 null。
        val baseline = consoleErrorBaseline
        return when {
            baseline <= 0 -> seq.toString()
            seq.length > baseline -> seq.subSequence(baseline, seq.length).toString()
            else -> null
        }
    }

    /**
     * T-CEC-01：未命中判据的控制台新增文本尾部（有界 + 同一段只记一次）——取证用，供下次扩判据
     * 时核对 DBN 真实写入 history 的报文形态（是否带 `[时间戳]` 前缀、是否跨行、SQLSTATE 前缀
     * 是否在场）。行数与字符数双上限，避免长会话刷屏。
     */
    internal fun DataSourceSelector.logUnrecognizedConsoleTail(lines: List<String>, LOG: com.intellij.openapi.diagnostic.Logger) {
        val tail = lines.takeLast(UNRECOGNIZED_TAIL_LINES).joinToString("\n").take(UNRECOGNIZED_TAIL_CHARS)
        if (tail.isBlank() || tail == lastUnrecognizedTail) return
        lastUnrecognizedTail = tail
        LOG.info("[T-CEC] console tail (no error signature matched): ${tail.replace('\n', '⏎')}")
    }

    /** T-CEC-02：错误上下文摘录的最大行数（错误行起算）。 */
    internal const val EXCERPT_MAX_LINES = 8

    /** T-CEC-01：取证日志的行数 / 字符数上限。 */
    private const val UNRECOGNIZED_TAIL_LINES = 6
    private const val UNRECOGNIZED_TAIL_CHARS = 600

    /** T-CEC-03：兜底呈现的行数 / 字符数上限。 */
    private const val RESIDUE_MAX_LINES = 6
    private const val RESIDUE_MAX_CHARS = 600

    /**
     * T-CEC-02：DBN 服务端错误前缀形态 `[SQLSTATE][errorCode]`——DBN 把驱动 SQLException 的
     * SQLState 与 vendor code 拼在报文最前（真机日志实证：`[42S22][1054] Unknown column …`、
     * `[42000][1064] You have an error in your SQL syntax …`）。跨方言同形，比短语词表硬：
     * 原词表认 `syntax error`，却认不出 `You have an error in your SQL syntax`。
     * 5 位定长是关键——`[2026-09-22 08:48:12]` 这类时间戳前缀（19 字符）不会被误命中。
     */
    private val SQLSTATE_PREFIX = Regex("""\[[0-9A-Za-z]{5}]\[\d+]""")

    /** T-CEC-02：Oracle 错误码形态（`ORA-00933` 等）——Oracle 报文常不带 SQLSTATE 前缀。 */
    private val ORACLE_CODE = Regex("""\bORA-\d{5}\b""", RegexOption.IGNORE_CASE)

    /** T-CEC-02：错误签名短语族（结构性判据覆盖不到的方言 / 驱动惯用报文）。 */
    internal val ERROR_SIGNATURES = listOf(
        // M36.5.48 原有 14 条（历史命中系，行为保持不动）
        "sql exception", "syntax error", "sqlstate", "unknown column",
        "unknown table", "doesn't exist", "does not exist", "access denied",
        "deadlock", "error code", "broken pipe", "connection refused",
        "[\u65e5\u8bed\u9519\u8bef]", "sql\uff08", "exception in thread",
        // T-CEC-02 新增：MySQL 语法错（用户 2026-09-22 真机 Output 报文逐字）
        "you have an error in your sql syntax",
        // T-CEC-02 新增：厂商固定报文（MySQL 1062 / 1451、Oracle ORA-00933）
        "duplicate entry", "foreign key constraint",
        "cannot add or update a child row", "cannot delete or update a parent row",
        "sql command not properly ended",
    )

    /**
     * R33 真机走查修复：EXPLAIN 附随的 SHOW WARNINGS note 行（`[HY000][1003] Note: …`，
     * 内含重写查询）——informational，不是执行失败。MySQL 服务端 1003 号属 note 消息段
     * （ER_YES，不落失败码段）；结构化通道回退后直送通道执行 EXPLAIN 成功时，DBN 会把这条
     * note 写进 history，早检逐拍扫到即被 [SQLSTATE_PREFIX] 命中误报红条（真机实证：
     * 结果已正常出，底部红条却是这条 note）。整行排除（不要求行内带 "Note" 字样——DBN
     * 是否透出级别词无源码级实锤，宁可宽放：1003 号在 MySQL 不是失败码）。
     */
    private val NOTE_1003_PREFIX = Regex("""\[HY000]\[1003]""", RegexOption.IGNORE_CASE)

    /** T-CEC-02：一行是否命中错误判据（结构性判据 + 短语族；EXPLAIN note 1003 行除外）。 */
    internal fun DataSourceSelector.isErrorSignatureLine(line: String): Boolean =
        !NOTE_1003_PREFIX.containsMatchIn(line) &&
            (SQLSTATE_PREFIX.containsMatchIn(line) ||
                ORACLE_CODE.containsMatchIn(line) ||
                ERROR_SIGNATURES.any { line.contains(it, ignoreCase = true) })

    /**
     * T-CEC-02：本插件自己写进 Output 的回显行前缀（[writeConsoleOutput] 的「执行失败: 」）——
     * 提取时排除，避免「已上报的错误被自己写回控制台后又当成新错误」的自反馈。
     */
    internal const val EXEC_FAILURE_ECHO_PREFIX = "执行失败:"

    internal fun DataSourceSelector.isOwnEchoLine(line: String): Boolean =
        stripConsolePrefix(line, null).startsWith(EXEC_FAILURE_ECHO_PREFIX)

    /** T-CEC-02：DBN 输出行的 `[时间戳] ` 前缀（用户真机报文逐行带此前缀）。 */
    private val TIMESTAMP_PREFIX = Regex("""^\[\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?]\s*""")

    private fun DataSourceSelector.stripTimestamp(line: String): String {
        val s = line.trim()
        val m = TIMESTAMP_PREFIX.find(s) ?: return s
        return s.substring(m.range.last + 1).trim()
    }

    /** T-CEC-02：剥掉时间戳与（DBN 实读的）提示符前缀——呈现与复制都更干净。 */
    internal fun DataSourceSelector.stripConsolePrefix(line: String, prompt: String?): String {
        var s = stripTimestamp(line)
        if (!prompt.isNullOrBlank() && s.startsWith(prompt)) s = s.substring(prompt.length).trim()
        return s
    }

    /**
     * T-CEC-02：读 DBN 控制台提示符（`mpj_demo>` / `ds>>`，即 `LanguageConsoleView.prompt`——
     * 243 源码 jar 实证为 Kotlin `var prompt: String?`，插件既有 [clearConsolePrompt] 已用 setPrompt）。
     * 取不到返 null：调用方退化为只剥时间戳，不猜提示符形态。
     */
    internal fun DataSourceSelector.consolePrompt(console: Any?): String? = runCatching {
        if (console == null) return null
        val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return null
        (consoleView.javaClass.getMethod("getPrompt").invoke(consoleView) as? String)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** T-CEC-03：回显比对用的归一化（空白折叠 + 小写）。 */
    private val WHITESPACE_RUN = Regex("""\s+""")

    private fun DataSourceSelector.normalizeForEcho(text: String): String =
        text.replace(WHITESPACE_RUN, " ").trim().lowercase()

    /** T-CEC-03：兜底剥离提示符的候选形态（仅当剩余部分是本次 SQL 子串时才据此判为回显）。 */
    private val PROMPT_CANDIDATE = Regex("""^\S{1,64}>+\s+""")

    /**
     * T-CEC-03：该行是否本次执行 SQL 的回显——剥掉时间戳后（必要时再剥提示符）是本次 SQL 文本的
     * 子串即认（多行 SQL 的每一行都是 SQL 的子串）。判据宽松方向是「多剔」：宁可残余变空退回
     * 既有提示，也不把回显当错误报给用户。
     */
    private fun DataSourceSelector.isSqlEchoLine(line: String, sqlNorm: String, prompt: String?): Boolean {
        if (sqlNorm.isEmpty()) return false
        val body = stripTimestamp(line)
        val candidates = mutableListOf(body)
        if (!prompt.isNullOrBlank() && body.startsWith(prompt)) {
            candidates += body.substring(prompt.length).trim()
        }
        PROMPT_CANDIDATE.find(body)?.let { m -> candidates += body.substring(m.value.length).trim() }
        return candidates.any { c ->
            val n = normalizeForEcho(c)
            n.length >= 4 && sqlNorm.contains(n)
        }
    }

    /**
     * T-CEC-03：DBN 状态 / 结果摘要行——不是错误内容，兜底呈现时剔除。
     *
     * 判据刻意偏「多剔」：语句**成功**但 DBN 不建结果 Tab 的场景同样会走满 40 拍窗口（DML 与
     * `use` 这类工具语句的影响行数/完成回显就写在 Output 里），把 `1 row affected` 当残余报出来
     * 等于把成功说成失败；剔多了只是落回既有提示，属可接受方向（宁少勿错）。
     */
    private val CONSOLE_STATUS_LINES = listOf(
        "connected", "completed in", "executing", "fetching", "fetched",
        "cancelled", "canceled", "query ok", "affected", "took ", "elapsed",
    )

    /** T-CEC-03：结果摘要词（`1 row affected` / `N rows` / `updated` 等）——同上，偏多剔。 */
    private val CONSOLE_STATUS_WORDS =
        Regex("""\b(rows?|records?|updated|deleted|inserted)\b""", RegexOption.IGNORE_CASE)

    private fun DataSourceSelector.isConsoleStatusLine(line: String): Boolean {
        val n = stripTimestamp(line).lowercase()
        return CONSOLE_STATUS_LINES.any { n.contains(it) } || CONSOLE_STATUS_WORDS.containsMatchIn(n)
    }

    /** M36.5.48：判断一行是否纯 SQL 语句（避免把「WHERE t.id=1」等当错误）。 */
    internal fun DataSourceSelector.isSqlLine(line: String): Boolean {
        val lower = line.trim().lowercase()
        // 含 SQL 关键字且无错误判据命中 → 大概率是 SQL 文本而非错误
        return (lower.startsWith("select") || lower.startsWith("from") ||
            lower.startsWith("where") || lower.startsWith("join") ||
            lower.startsWith("insert") || lower.startsWith("update") ||
            lower.startsWith("delete") || lower.startsWith("create") ||
            lower.startsWith("alter") || lower.startsWith("drop")) &&
            !isErrorSignatureLine(line)
    }

    /**
     * M36.5.8：清空控制台提示符（SQL 编辑器左侧 "rbac>"/">" 装饰）——用户反馈无用干扰。
     * 反编译实锤：DatabaseLogView 经 LanguageConsoleView.setPrompt(String) 维护（默认 ">"、
     * 带搜索路径时 "<数据源>>"）；置空后若数据源/搜索路径变化可能被插件重设，属可接受回退。
     */
    /**
     * M36.5.62：清空 console 历史文档（LanguageConsoleView historyViewer 的 document）——
     * 预览（execute=false）时清除上一个 SQL 的历史残留。DBN 每次执行/预览都会把 SQL 回显到
     * historyViewer（Output tab），`clearResultContents` 只移除 result Tab（ui.removeContent），
     * **保留 Output content**——Output tab 仍显示上个 SQL 的回显文本（用户实测"预览 sqlA 时下方有
     * 之前预览执行过的 sqlB"）。预览应显示干净空态，须连历史文档一起清空。
     * 注意：执行路径（execute=true）**不清**——执行后 DBN 需重新回显本次 SQL，历史清空会让
     * 执行结果上下文断链，且 consoleErrorBaseline 依赖历史长度基线（清空会改变基线语义）。
     */
    internal fun DataSourceSelector.clearConsoleHistory(console: Any?, LOG: com.intellij.openapi.diagnostic.Logger) {
        if (console == null) return
        runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return
            val historyViewer = consoleView.javaClass.getMethod("getHistoryViewer").invoke(consoleView) ?: return
            val doc = historyViewer.javaClass.getMethod("getDocument").invoke(historyViewer) ?: return
            doc.javaClass.getMethod("setText", String::class.java).invoke(doc, "")
            LOG.info("[M36] console history cleared (preview switch)")
        }.onFailure { e ->
            LOG.warn("[M36] clear console history FAILED: ${e.message}")
        }
    }

    /** M36.5.8：清空控制台提示符（SQL 编辑器左侧 "rbac>"/">" 装饰）——用户反馈无用干扰。
     * 反编译实锤：DatabaseLogView 经 LanguageConsoleView.setPrompt(String) 维护（默认 ">"、
     * 带搜索路径时 "<数据源>>"）；置空后若数据源/搜索路径变化可能被插件重设，属可接受回退。
     */
    internal fun DataSourceSelector.clearConsolePrompt(console: Any, LOG: com.intellij.openapi.diagnostic.Logger) {
        runCatching {
            val consoleView = console.javaClass.getMethod("getConsoleView").invoke(console) ?: return
            consoleView.javaClass.getMethod("setPrompt", String::class.java).invoke(consoleView, "")
            LOG.info("[M36] console prompt cleared")
        }.onFailure { e ->
            LOG.warn("[M36] clear console prompt FAILED: ${e.message}")
        }
    }

    /**
     * M36.5.70：用 DBN ConsoleView 原生 print API 把文本写入 Output 控制台可视区。问题 SQL（报错
     * 无结果行）时 DBN 结果区内 Output 内容视图（JBScrollPane）布局为 0x0（`[TEXT]` 探针实证），
     * historyViewer document 有错误文本但 Output 面板不显示——用户"output 里没有信息"。走
     * ConsoleView.print(String, ConsoleViewContentType) 会经 DBN 原生输出链路写入并刷新显示，
     * 不依赖/不破坏结果区内部布局（结果网格照常）。反射调用避免对 ConsoleViewContentType 的
     * 编译期依赖（其所在模块可能随 IDE 版本变动）。
     *
     * T-FatalPassthrough T-FP-02b：`internal` 供 `FatalPassthroughTest` 直测本函数的**外层 runCatching
     * 边界**（同 [execFileContentMatches] / [isExecDocOpenInEditor] 先例）——私有则行为用例无处注入。
     */
    internal fun DataSourceSelector.consoleEditorUnavailableMessage(path: String): String =
        MpSqlVisualizerBundle.message(
            "execute.failed",
            "控制台编辑器不可得（$path：400ms 内 getEditors(consoleDocument) 恒空，无一方为该文档建过编辑器）",
        )
