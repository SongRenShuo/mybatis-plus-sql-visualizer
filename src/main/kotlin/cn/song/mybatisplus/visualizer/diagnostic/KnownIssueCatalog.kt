package cn.song.mybatisplus.visualizer.diagnostic

import java.util.Locale

/**
 * 档 2：一条「已知问题」特征规则（纯数据 + 纯函数，零平台依赖）。
 *
 * 匹配口径：**大小写不敏感的子串**（不用正则——用户日志里什么字符都有，正则回溯会炸），
 * 判定单位是「一条日志记录」＝一行 + 其后的栈续行（续行以空白开头）。按整段尾部文本匹配会
 * 把两条不相干的独立行拼成一次命中，按单行匹配又会漏掉「消息在头、特征栈帧在后面」的形态
 * （ui-001/ui-002 都属于后者，真机日志实证）。
 *
 * 三段判定，全部满足才算命中：
 *  - [allOf]：每一条子串都必须出现（AND）；
 *  - [anyOf]：为空则不约束，否则至少出现一个（OR）；
 *  - [eachOfAny]：每个子列表各自「至少出现一个」（组间 AND、组内 OR）——用于
 *    「异常类名 × 驱动包名」这类双维特征：两个维度都得命中，但每个维度内部是选择题。
 */
internal data class KnownIssueRule(
    val id: String,
    val titleKey: String,
    val actionKey: String,
    val allOf: List<String> = emptyList(),
    val anyOf: List<String> = emptyList(),
    val eachOfAny: List<List<String>> = emptyList(),
    /** 命中后该项结论的等级（多条命中取最高）。 */
    val severity: CheckLevel,
    /** 已修复版本；null＝仍在册（本轮两条已知缺陷都只在 dev 修好、尚未发行，故为 null）。 */
    val fixedIn: String? = null,
) {
    // 统一小写化：规则作者写原样特征即可，不必关心大小写；匹配侧同样先小写。
    private val required: List<String> = allOf.map { it.lowercase(Locale.ROOT) }
    private val optional: List<String> = anyOf.map { it.lowercase(Locale.ROOT) }
    private val grouped: List<List<String>> = eachOfAny.map { group -> group.map { it.lowercase(Locale.ROOT) } }

    /** [record] 必须是**已小写化**的一条日志记录；调用方先小写化整段文本，避免逐规则重复转换。 */
    fun matches(record: String): Boolean {
        if (required.any { !record.contains(it) }) return false
        if (optional.isNotEmpty() && optional.none { record.contains(it) }) return false
        return grouped.all { group -> group.any { record.contains(it) } }
    }
}

/** 一次扫描的命中：规则 + 出现次数（同一规则在同一条记录里只计一次）。 */
internal data class KnownIssueHit(val rule: KnownIssueRule, val count: Int) {
    val id: String get() = rule.id
}

/**
 * 第一批已知问题表（全部来自真机实证，见 `docs/验收文档/20261104-0410-dev-vs-master-262沙盒增量功能验收-验收文档.md`）。
 *
 * 纪律：这里只放**特征**，文案一律走 bundle（[KnownIssueRule.titleKey]/[KnownIssueRule.actionKey]）——
 * 报告要能贴工单，中英文案与代码解耦；同时保证报告里只有规则 id + 结论文案 + 次数，不含日志原文。
 */
internal object KnownIssueCatalog {

    /** 报告最多列出的命中条数（再多就是刷屏，用户要的是「先修哪个」）。 */
    const val MAX_HITS = 5

    /** 单条记录参与匹配的最大字符数：一条超长栈（含大段 SQL 上下文）没必要全量匹配。 */
    private const val MAX_RECORD_CHARS = 8 * 1024

    val rules: List<KnownIssueRule> = listOf(
        KnownIssueRule(
            id = "ui-001",
            titleKey = "envCheck.log.ui-001.title",
            actionKey = "envCheck.log.ui-001.action",
            // 消息行在后、触发栈帧（ScanBannedApiUsages）在后 → 必须按记录窗口匹配，不能按单行。
            allOf = listOf("slow operations are prohibited on edt", "scanbannedapiusages"),
            severity = CheckLevel.WARN,
        ),
        KnownIssueRule(
            id = "ui-002",
            titleKey = "envCheck.log.ui-002.title",
            actionKey = "envCheck.log.ui-002.action",
            allOf = listOf("read access is allowed from inside read-action only", "consumecachedtemppsifile"),
            severity = CheckLevel.WARN,
        ),
        KnownIssueRule(
            id = "driver-missing",
            titleKey = "envCheck.log.driver-missing.title",
            actionKey = "envCheck.log.driver-missing.action",
            eachOfAny = listOf(
                listOf("noclassdeffounderror", "classnotfoundexception"),
                // 只认各驱动的主包名；不用 "driver" 之类的泛词，否则
                // 「Loading class `com.mysql.cj.jdbc.Driver'. This is deprecated」这类正常日志会误报。
                // 点号与斜杠两种写法都要在：`ClassNotFoundException` 用点号类名，
                // `NoClassDefFoundError` 打印内部名（com/mysql/...），漏一种就漏一类真机形态。
                listOf(
                    "com.mysql", "com/mysql",
                    "org.postgresql", "org/postgresql",
                    "com.microsoft.sqlserver", "com/microsoft/sqlserver",
                    "oracle.jdbc", "oracle/jdbc",
                    "dm.jdbc", "dm/jdbc",
                ),
            ),
            severity = CheckLevel.FAIL,
        ),
        KnownIssueRule(
            id = "psi-invalid",
            titleKey = "envCheck.log.psi-invalid.title",
            actionKey = "envCheck.log.psi-invalid.action",
            allOf = listOf("psiinvalidelementaccessexception"),
            severity = CheckLevel.WARN,
        ),
        KnownIssueRule(
            id = "exec-temp-fail",
            titleKey = "envCheck.log.exec-temp-fail.title",
            actionKey = "envCheck.log.exec-temp-fail.action",
            allOf = listOf("failed to create temporary sql file"),
            severity = CheckLevel.WARN,
        ),
        KnownIssueRule(
            id = "table-unresolved",
            titleKey = "envCheck.log.table-unresolved.title",
            actionKey = "envCheck.log.table-unresolved.action",
            allOf = listOf("unable to resolve table"),
            severity = CheckLevel.WARN,
        ),
        KnownIssueRule(
            id = "platform-component",
            titleKey = "envCheck.log.platform-component.title",
            actionKey = "envCheck.log.platform-component.action",
            allOf = listOf("cannot init component state"),
            severity = CheckLevel.WARN,
        ),
    )

    /**
     * 扫描一段日志文本，返回按「等级降序 → 次数降序」排好的命中表。
     *
     * 纯函数：[text] 由调用方从 [IdeaLogTails] 读来，本函数不碰磁盘、不碰平台。
     */
    fun scan(text: String, rules: List<KnownIssueRule> = this.rules): List<KnownIssueHit> {
        if (text.isEmpty() || rules.isEmpty()) return emptyList()
        val lower = text.lowercase(Locale.ROOT)
        val counts = HashMap<String, Int>()
        forEachRecord(lower) { record ->
            for (rule in rules) {
                if (rule.matches(record)) counts.merge(rule.id, 1) { a, b -> a + b }
            }
        }
        if (counts.isEmpty()) return emptyList()
        return rules.mapNotNull { rule -> counts[rule.id]?.let { KnownIssueHit(rule, it) } }
            .sortedWith(
                compareByDescending<KnownIssueHit> { it.rule.severity.severity }
                    .thenByDescending { it.count }
                    .thenBy { it.id },
            )
    }

    /**
     * 按「从第 0 列开始的非空行」切分记录，缩进行（栈续行）并入上一条记录。
     *
     * 这样一条异常连同它的 `at ...` 栈帧是一个匹配单位：消息与栈帧各在不同行也能一起命中。
     */
    private fun forEachRecord(lower: String, block: (String) -> Unit) {
        val buf = StringBuilder(512)
        var hasRecord = false
        for (line in lower.lineSequence()) {
            val continuation = line.isNotEmpty() && (line[0] == '\t' || line[0] == ' ')
            if (continuation && hasRecord) {
                if (buf.length < MAX_RECORD_CHARS) {
                    buf.append('\n')
                    val room = MAX_RECORD_CHARS - buf.length
                    buf.append(if (line.length <= room) line else line.substring(0, room))
                }
            } else {
                if (hasRecord) block(buf.toString())
                buf.setLength(0)
                hasRecord = line.isNotEmpty()
                if (hasRecord) {
                    buf.append(if (line.length <= MAX_RECORD_CHARS) line else line.substring(0, MAX_RECORD_CHARS))
                }
            }
        }
        if (hasRecord) block(buf.toString())
    }
}
