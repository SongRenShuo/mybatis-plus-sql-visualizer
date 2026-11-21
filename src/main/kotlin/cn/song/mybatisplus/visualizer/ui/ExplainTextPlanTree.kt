package cn.song.mybatisplus.visualizer.ui

/**
 * PEV-01（postgres-explain-visualizer 精华吸收，计划册 20261121-1756 §一.1）：PG 族 **text EXPLAIN**
 * → 可折叠树模型的**纯逻辑解析器**（无 IDE/UI 类型，裸 JUnit 直测）。
 *
 * 输出复用 [ExplainJsonPlanTree.Node] 形态（label + children）——主控车道合流后接渲染面零改造；
 * 另出 [TextPlanNodeInfo] 结构化扁平清单供 [cn.song.mybatisplus.visualizer.util.ExplainIndexAdvisor]
 * 的计划警示规则单源消费（JSON 档与 text 档共用同一套规则，禁第二套）。
 *
 * 输入形态（pev2 v1.25.0 `from-text` / `11-psql-frames` / `12-line-wrapped-plans` 夹具基线，
 * 语义按其 plan-service.ts 重实现、未拷代码）：
 *  - 缩进 ASCII 树：子节点 `->` 前缀、节点头 `(cost=A..B rows=N width=W)`、
 *    ANALYZE 的 `(actual time=A..B rows=R loops=L)`（R 可为小数）与 `(never executed)`；
 *  - 节点属性行 `Key: value`（Filter / Rows Removed by … / Heap Fetches / Buffers / WAL /
 *    I/O Timings / Buckets..Batches.. / Sort Method / Output / Workers Planned …）；
 *  - Workers 分段容忍：`Worker N: …` 行与其更深层的 JIT/Buffers 续行挂到所属 worker 叶，
 *    不混入节点属性（pev2 只对 Batches 特判 worker 归属，此处按缩进统一路由——worker 级
 *    Batches 不会误入节点结构化字段，计划警示规则因此不会把 worker 溢盘记到节点头上）；
 *  - SubPlan / InitPlan / CTE 分段头：合成一层有名中间节点保留分段名（pev2 把子计划直接挂
 *    上级并打 Parent Relationship 标——本解析器多一层有名节点，渲染面信息更完整，语义等价）；
 *  - 根级 `Planning:` / `JIT:` 块、Trigger 行、Planning Time / Execution Time / Total runtime 行；
 *  - psql 边框表格形态先清洗（`|`/`║`/`│` 框线、全分隔线行、QUERY PLAN 表头、`(N rows)` 尾行、
 *    行首尾成对引号、`↵`/行尾 `+` 续行）再按文本树处理；折行计划按括号配平续接
 *    （pev2 splitIntoLines 语义：括号不配平即续行、列 0 续行并入上行、逗号列表折行续接）。
 *
 * 边界（计划册 §一 PEV-01 边界重立）：MySQL 表格文本**不做**（TABLE 档已有通道）；提交链
 * TEXT 档「不硬解析」红线维持——本解析器只服务**显式粘贴**的用户意图链（「用户贴的就是计划」
 * 口径），两处边界分开写死。
 *
 * 诚实降级红线：解析不出 → [Result.Failure]（结构化原因 + 原文全量保留），**禁启发式猜格式**；
 * 失败不产出任何展示文案（文案归接线车道）。`->` 开头但匹配不上节点头的行直接判失败
 * （[Reason.UNRECOGNIZED_NODE_LINE]）——宁失败不猜；其余杂散行（psql 回显等无 `: ` 的行）
 * 容忍跳过（pev2 同款容错，夹具 46 的 SET/INSERT 回显链靠它活）。
 */
object ExplainTextPlanTree {

    // ---------- 结果与结构化模型 ----------

    /** 解析结果：成功带渲染树与结构化节点清单；失败带结构化原因与原文（诚实降级，不猜不炸）。 */
    sealed class Result {
        /** 解析成功。 */
        data class Success(
            /** 渲染树根清单（首个通常为计划树；其后为根级 Planning/JIT 块、计时行与回显等附属叶，按出现序）。 */
            val roots: List<ExplainJsonPlanTree.Node>,
            /** 计划节点扁平清单（前序遍历，仅真实计划节点——计划警示规则单源的 text 档消费面）。 */
            val planNodes: List<TextPlanNodeInfo>,
            /** 是否 ANALYZE 档（任一节点带 actual 行数或 never executed）。 */
            val isAnalyze: Boolean,
        ) : Result()

        /** 解析失败（[originalText] 全量保留原文——诚实降级由调用方原文展示，本解析器不产展示文案）。 */
        data class Failure(val reason: Reason, val originalText: String) : Result()
    }

    /** 失败原因（结构化表达，不产展示文案）。 */
    enum class Reason {
        /** 空白输入。 */
        EMPTY_INPUT,

        /** 输入呈 JSON 形态——调用方应改走 JSON 档（[ExplainJsonPlanTree]），本解析器不接。 */
        JSON_SHAPED,

        /** 清洗后找不到任何计划节点（无 cost/actual 节点头）——不是 PG 族 text 计划。 */
        NO_PLAN_NODES,

        /** 存在 `->` 开头或带 `(cost=` 节点头标记、但匹配不上节点头的行——形态偏差（典型成因是
         *  复制/粘贴时折行点空格丢失），宁失败不猜，不产出缺根的残树。 */
        UNRECOGNIZED_NODE_LINE,
    }

    /**
     * 结构化计划节点（计划警示规则的 text 档消费面；渲染树之外的扁平前序视图）。
     * 数值字段 null = 该信息本节点未出现（不虚标 0）。
     */
    data class TextPlanNodeInfo(
        /** 节点描述原文（cost/actual 括号前，如 `Bitmap Heap Scan on public.t1`）。 */
        val label: String,
        /** 原文缩进深度（树形关系以 [cn.song.mybatisplus.visualizer.ui.ExplainTextPlanTree.Result.Success.roots] 为准）。 */
        val depth: Int,
        val startupCost: Double?,
        val totalCost: Double?,
        val planRows: Long?,
        val planWidth: Long?,
        /** actual rows（ANALYZE 档；可为小数——PG 对并行/均值节点出小数行数）。 */
        val actualRows: Double?,
        val actualLoops: Long?,
        val actualStartupTime: Double?,
        val actualTotalTime: Double?,
        /** `(never executed)`（ANALYZE 档未执行分支；actual 各值按 pev2 口径记 0）。 */
        val neverExecuted: Boolean,
        /** Finalize / Simple / Partial 并行分段前缀（无则 null）。 */
        val partialMode: String?,
        val heapFetches: Long?,
        val rowsRemovedByFilter: Long?,
        val rowsRemovedByJoinFilter: Long?,
        val rowsRemovedByIndexRecheck: Long?,
        /** Hash 节点 `Buckets: N  Batches: B [ (originally B0) ]  Memory Usage: ..kB` 行的批次数。 */
        val hashBatches: Long?,
        /** 上行的 originally 值；行未带 originally 时按 pev2 口径记 [hashBatches]。 */
        val originalHashBatches: Long?,
        /** HashAggregate 独立 `Batches: N  Memory Usage: ..kB [ Disk Usage: ..kB ]` 行的批次数。 */
        val hashaggBatches: Long?,
        /** HashAggregate 行的 Disk Usage（kB）。 */
        val hashaggDiskUsageKb: Long?,
        /** 其余属性行（key → 原文 value；结构化字段之外的兜底保留，顺序为出现序）。 */
        val attributes: Map<String, String>,
    )

    /** 节点种类：[PLAN] 真实计划节点；[SUBPLAN] SubPlan/InitPlan/CTE 分段合成节点；[SECTION] 根级 Planning/JIT 块；[EXTRA] 根级散叶。 */
    private enum class Kind { PLAN, SUBPLAN, SECTION, EXTRA }

    private class RawNode(val kind: Kind, val label: String, val depth: Int) {
        var partialMode: String? = null
        var startupCost: Double? = null
        var totalCost: Double? = null
        var planRows: Long? = null
        var planWidth: Long? = null
        var actualRows: Double? = null
        var actualLoops: Long? = null
        var actualStartupTime: Double? = null
        var actualTotalTime: Double? = null
        var neverExecuted: Boolean = false
        var heapFetches: Long? = null
        var rowsRemovedByFilter: Long? = null
        var rowsRemovedByJoinFilter: Long? = null
        var rowsRemovedByIndexRecheck: Long? = null
        var hashBatches: Long? = null
        var originalHashBatches: Long? = null
        var hashaggBatches: Long? = null
        var hashaggDiskUsageKb: Long? = null
        val attrs = LinkedHashMap<String, String>()
        val workers = mutableListOf<WorkerEntry>()
        val children = mutableListOf<RawNode>()
    }

    /** Worker 分段（行文本 + 深层续行原样保留；worker 级统计不进结构化字段——规则不消费 worker 数据）。 */
    private class WorkerEntry(val line: String) {
        val subLines = mutableListOf<String>()
    }

    // ---------- 入口 ----------

    /**
     * 解析 text EXPLAIN。
     *
     * @return 成功 → [Result.Success]；解析不出 → [Result.Failure]（原文全量保留，不猜不炸）
     */
    fun parse(text: String): Result {
        if (text.isBlank()) return Result.Failure(Reason.EMPTY_INPUT, text)
        val cleaned = cleanSource(text)
        val firstContent = cleaned.firstOrNull { it.isNotBlank() }?.trimStart()
            ?: return Result.Failure(Reason.EMPTY_INPUT, text)
        if (firstContent.startsWith("{") || firstContent.startsWith("[")) {
            // JSON 档归 ExplainJsonPlanTree（主控车道接线按双档识别）；这里结构化拒收，不硬解析
            return Result.Failure(Reason.JSON_SHAPED, text)
        }

        val topLevel = mutableListOf<RawNode>()
        val stack = mutableListOf<RawNode>()
        var workerAtDepth: Pair<Int, WorkerEntry>? = null
        var sawUnrecognized = false

        for (rawLine in splitIntoLines(cleaned)) {
            val expanded = rawLine.replace("\t", "    ")
            val firstNonWs = expanded.indexOfFirst { !it.isWhitespace() }
            if (firstNonWs < 0) continue // 空行
            val depth = firstNonWs
            var line = expanded.substring(firstNonWs).trimEnd()
            // 查询回显/表头类行（pev2 headerRegex 意图——其原实现双转义失效，此处按意图还原）
            if (line.startsWith("#") || line.startsWith("---")) continue

            // worker 态清位：回到 worker 行深度或更浅 → 当前行起不再归属该 worker（子节点行正是这个深度）
            val workerState = workerAtDepth
            if (workerState != null && depth <= workerState.first) workerAtDepth = null
            // worker 续行（JIT/Buffers 等比 worker 行更深）→ 原样挂 worker 叶，不参与节点分类
            val activeWorker = workerAtDepth
            if (activeWorker != null && depth > activeWorker.first) {
                activeWorker.second.subLines += line
                continue
            }

            // ① 计划节点行：cost/actual 尾巴匹配（依次试组合形态——pev2 nodeRegex 同语义）
            val tail = matchNodeTail(line)
            if (tail != null) {
                attachSynthetic(buildPlanNode(line, depth, tail), depth, stack, topLevel)
                continue
            }
            if (line.startsWith("->") || line.contains("(cost=")) {
                // 节点行形态偏差（`->` 开头却匹配不上节点头；或带 cost= 节点头标记但匹配不上
                // 尾巴——典型成因是复制时折行点空格丢失）→ 宁失败不猜，不产出缺根的残树
                sawUnrecognized = true
                continue
            }

            // ② SubPlan / InitPlan / CTE 分段头（合成有名中间节点）
            if (SUBPLAN_LINE.matchEntire(line) != null || CTE_LINE.matchEntire(line) != null) {
                attachSynthetic(RawNode(Kind.SUBPLAN, line, depth), depth, stack, topLevel)
                continue
            }
            // ③ Worker 分段行
            if (WORKER_LINE.matchEntire(line) != null) {
                val owner = stack.lastOrNull()
                if (owner != null) {
                    val entry = WorkerEntry(line)
                    owner.workers += entry
                    workerAtDepth = depth to entry
                }
                continue
            }
            // ④ Trigger 行 → 根级叶
            if (TRIGGER_LINE.matchEntire(line) != null) {
                topLevel += RawNode(Kind.EXTRA, line, depth)
                continue
            }
            // ⑤ 根级 JIT / Planning 块头
            if (JIT_LINE.matchEntire(line) != null || PLANNING_LINE.matchEntire(line) != null) {
                attachSynthetic(RawNode(Kind.SECTION, line.trimEnd(':').trim(), depth), depth, stack, topLevel)
                continue
            }
            // ⑥ 其余行 = 属性行（首个 ": " 切 key/value）；无 ": " 的杂散行容忍跳过（psql 回显等）
            val colonIdx = line.indexOf(": ")
            if (colonIdx <= 0) continue
            val key = line.substring(0, colonIdx)
            val value = line.substring(colonIdx + 2)
            // pev2 同款：深度 1 的行恒为根级全局行（对齐框夹具里根节点带 1 空格缩进的形态）
            if (depth == 1) stack.clear() else stack.removeAll { it.depth >= depth }
            val target = stack.lastOrNull()
            if (target == null) {
                topLevel += RawNode(Kind.EXTRA, line, depth)
            } else {
                attachAttr(target, key, value)
            }
        }

        val planNodes = mutableListOf<TextPlanNodeInfo>()
        topLevel.forEach { flatten(it, planNodes) }
        if (sawUnrecognized) return Result.Failure(Reason.UNRECOGNIZED_NODE_LINE, text)
        if (planNodes.isEmpty()) return Result.Failure(Reason.NO_PLAN_NODES, text)
        return Result.Success(
            roots = topLevel.map { renderNode(it) },
            planNodes = planNodes.toList(),
            isAnalyze = planNodes.any { it.actualRows != null || it.neverExecuted },
        )
    }

    // ---------- 节点行匹配 ----------

    /** 节点头尾部数据（pev2 nodeRegex 的捕获组结构化）。 */
    private class NodeTail(
        val descEnd: Int,
        val startupCost: Double?,
        val totalCost: Double?,
        val planRows: Long?,
        val planWidth: Long?,
        val actualStartupTime: Double?,
        val actualTotalTime: Double?,
        val actualRows: Double?,
        val actualLoops: Long?,
        val neverExecuted: Boolean,
    )

    /**
     * 节点头匹配：可选 `->` 前缀、可选 Finalize/Simple/Partial 前缀、描述、cost/actual 尾巴。
     * 尾部正则按组合形态依次尝试（cost+actual → cost+never → 仅 cost → 仅 actual → 仅 never），
     * 全部锚定行尾——`Index Cond: (id = 4)` 这类属性行不会被误判为节点。
     */
    private fun matchNodeTail(line: String): NodeTail? {
        val (rest, _) = stripNodePrefix(line)
        val costActual = NODE_TAIL_COST_ACTUAL.find(rest)
        val costNever = NODE_TAIL_COST_NEVER.find(rest)
        val costOnly = NODE_TAIL_COST.find(rest)
        val actualOnly = NODE_TAIL_ACTUAL.find(rest)
        val neverOnly = NODE_TAIL_NEVER.find(rest)
        fun d(m: MatchResult, i: Int) = m.groupValues[i].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        fun l(m: MatchResult, i: Int) = m.groupValues[i].takeIf { it.isNotEmpty() }?.toLongOrNull()
        return when {
            costActual != null -> NodeTail(
                descEnd = costActual.range.first,
                startupCost = d(costActual, 1), totalCost = d(costActual, 2),
                planRows = l(costActual, 3), planWidth = l(costActual, 4),
                actualStartupTime = d(costActual, 5), actualTotalTime = d(costActual, 6),
                actualRows = d(costActual, 7), actualLoops = l(costActual, 8),
                neverExecuted = false,
            )
            costNever != null -> NodeTail(
                descEnd = costNever.range.first,
                startupCost = d(costNever, 1), totalCost = d(costNever, 2),
                planRows = l(costNever, 3), planWidth = l(costNever, 4),
                actualStartupTime = null, actualTotalTime = null,
                actualRows = 0.0, actualLoops = 0L,
                neverExecuted = true,
            )
            costOnly != null -> NodeTail(
                descEnd = costOnly.range.first,
                startupCost = d(costOnly, 1), totalCost = d(costOnly, 2),
                planRows = l(costOnly, 3), planWidth = l(costOnly, 4),
                actualStartupTime = null, actualTotalTime = null,
                actualRows = null, actualLoops = null,
                neverExecuted = false,
            )
            actualOnly != null -> NodeTail(
                descEnd = actualOnly.range.first,
                startupCost = null, totalCost = null, planRows = null, planWidth = null,
                actualStartupTime = d(actualOnly, 1), actualTotalTime = d(actualOnly, 2),
                actualRows = d(actualOnly, 3), actualLoops = l(actualOnly, 4),
                neverExecuted = false,
            )
            neverOnly != null -> NodeTail(
                descEnd = neverOnly.range.first,
                startupCost = null, totalCost = null, planRows = null, planWidth = null,
                actualStartupTime = null, actualTotalTime = null,
                actualRows = 0.0, actualLoops = 0L,
                neverExecuted = true,
            )
            else -> null
        }
    }

    /** 节点行公共前缀剥离（`->` 与 Finalize/Simple/Partial）——匹配与建节点两处必须用同一剥离序。 */
    private fun stripNodePrefix(line: String): Pair<String, String?> {
        var rest = line
        if (rest.startsWith("->")) rest = rest.substring(2).trimStart()
        val partial = PARTIAL_PREFIX.find(rest)
        if (partial != null) rest = rest.substring(partial.range.last + 1).trimStart()
        return rest to partial?.groupValues?.get(1)
    }

    private fun buildPlanNode(line: String, depth: Int, tail: NodeTail): RawNode {
        val (rest, partialMode) = stripNodePrefix(line)
        val strippedDesc = rest.substring(0, tail.descEnd).trim()
        // 标签保留完整描述（Partial 前缀拼回）——pev2 把 partialMode 单存、节点类型丢前缀；
        // 渲染面按原文展示更诚实，结构化字段仍单独携带 partialMode 供归一化
        val description = if (partialMode != null) "$partialMode $strippedDesc" else strippedDesc
        val node = RawNode(Kind.PLAN, description, depth)
        node.partialMode = partialMode
        node.startupCost = tail.startupCost
        node.totalCost = tail.totalCost
        node.planRows = tail.planRows
        node.planWidth = tail.planWidth
        node.actualStartupTime = tail.actualStartupTime
        node.actualTotalTime = tail.actualTotalTime
        node.actualRows = tail.actualRows
        node.actualLoops = tail.actualLoops
        node.neverExecuted = tail.neverExecuted
        return node
    }

    // ---------- 树装配 ----------

    /**
     * 节点/分段头入树（pev2 elementsAtDepth 栈语义）：弹出深度 ≥ 本行的栈项后挂到栈顶宿主；
     * 宿主为空（首节点或栈被根级全局行清空）→ 并列根，保信息不丢。
     */
    private fun attachSynthetic(node: RawNode, depth: Int, stack: MutableList<RawNode>, topLevel: MutableList<RawNode>) {
        stack.removeAll { it.depth >= depth }
        val parent = stack.lastOrNull()
        if (parent == null) topLevel += node else parent.children += node
        stack += node
    }

    private fun attachAttr(node: RawNode, key: String, value: String) {
        node.attrs[key] = value
        when (key) {
            "Heap Fetches" -> node.heapFetches = value.trim().toLongOrNull()
            "Rows Removed by Filter" -> node.rowsRemovedByFilter = value.trim().toLongOrNull()
            "Rows Removed by Join Filter" -> node.rowsRemovedByJoinFilter = value.trim().toLongOrNull()
            "Rows Removed by Index Recheck" -> node.rowsRemovedByIndexRecheck = value.trim().toLongOrNull()
            "Buckets" -> BUCKETS_VALUE.matchEntire(value.trim())?.let { m ->
                node.hashBatches = m.groupValues[1].toLongOrNull()
                node.originalHashBatches = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
                    ?: node.hashBatches
            }
            "Batches" -> HASHAGG_BATCHES_VALUE.matchEntire(value.trim())?.let { m ->
                node.hashaggBatches = m.groupValues[1].toLongOrNull()
                node.hashaggDiskUsageKb = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
            }
        }
    }

    private fun flatten(node: RawNode, out: MutableList<TextPlanNodeInfo>) {
        if (node.kind == Kind.PLAN) out += node.toInfo()
        node.children.forEach { flatten(it, out) }
    }

    private fun RawNode.toInfo() = TextPlanNodeInfo(
        label = label,
        depth = depth,
        startupCost = startupCost,
        totalCost = totalCost,
        planRows = planRows,
        planWidth = planWidth,
        actualRows = actualRows,
        actualLoops = actualLoops,
        actualStartupTime = actualStartupTime,
        actualTotalTime = actualTotalTime,
        neverExecuted = neverExecuted,
        partialMode = partialMode,
        heapFetches = heapFetches,
        rowsRemovedByFilter = rowsRemovedByFilter,
        rowsRemovedByJoinFilter = rowsRemovedByJoinFilter,
        rowsRemovedByIndexRecheck = rowsRemovedByIndexRecheck,
        hashBatches = hashBatches,
        originalHashBatches = originalHashBatches,
        hashaggBatches = hashaggBatches,
        hashaggDiskUsageKb = hashaggDiskUsageKb,
        attributes = attrs.toMap(),
    )

    // ---------- 渲染树 ----------

    /** 渲染节点标签：描述 + `(rows=N)`（Plan Rows 优先退 Actual Rows，与 [ExplainJsonPlanTree.planLabel] 同口径）+ never executed 标记。 */
    private fun renderNode(node: RawNode): ExplainJsonPlanTree.Node {
        val label = if (node.kind == Kind.PLAN) {
            buildString {
                append(node.label)
                val rows = node.planRows ?: node.actualRows?.toLong()
                if (rows != null) append(" (rows=").append(rows).append(')')
                if (node.neverExecuted) append(" (never executed)")
            }
        } else {
            node.label
        }
        val children = ArrayList<ExplainJsonPlanTree.Node>()
        node.attrs.forEach { (k, v) -> children += ExplainJsonPlanTree.Node("$k: $v") }
        node.workers.forEach { w ->
            children += ExplainJsonPlanTree.Node(w.line, w.subLines.map { ExplainJsonPlanTree.Node(it) })
        }
        node.children.forEach { children += renderNode(it) }
        return ExplainJsonPlanTree.Node(label, children)
    }

    // ---------- 清洗与续接 ----------

    /**
     * 清洗（pev2 cleanupSource 语义）：框线字符、全分隔线行、QUERY PLAN 表头、`(N rows)` 尾行、
     * 行首尾成对引号、`↵`/行尾 `+` 续行。保留框线字符后的空格——根节点缩进深度语义与 pev2 一致。
     */
    private fun cleanSource(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("↵", "\n").lines()) {
            var line = raw.trimEnd('\r')
            if (line.isBlank()) {
                out += line
                continue
            }
            // 全分隔线行（覆盖 +---+ / --- / ─── / ═══ / ├─┤ / └─┘ / ╔═╗ 等全形态，一行判据代替 pev2 的逐形态正则）
            if (line.length >= 3 && line.all { it in SEPARATOR_CHARS }) continue
            // 首尾框线字符（| ║ │）
            val trimmedStart = line.trimStart()
            if (trimmedStart.isNotEmpty() && trimmedStart[0] in FRAME_CHARS) line = trimmedStart.substring(1)
            val trimmedEnd = line.trimEnd()
            if (trimmedEnd.isNotEmpty() && trimmedEnd.last() in FRAME_CHARS) line = trimmedEnd.dropLast(1)
            // 行首尾成对引号剥离（pgAdmin 等复制件形态）
            val t = line.trim()
            if (t.length >= 2 && t.first() == t.last() && (t.first() == '"' || t.first() == '\'')) {
                line = t.substring(1, t.length - 1)
            }
            val trimmed = line.trim()
            // QUERY PLAN 表头
            if (trimmed == "QUERY PLAN") continue
            // 行数尾行 (9 rows) / (3 lignes)
            if (ROWCOUNT_LINE.matches(trimmed)) continue
            // 行尾 `+` 续行（含独立 "+" 行——pev2 `\s*\+\n` 语义；"+ " 前须有空白，避免吃掉表达式内的加号紧贴行尾的形态……
            // pev2 同款判据：加号前是空白或整行只有加号才算续行）
            val noTrail = line.trimEnd()
            if (noTrail.endsWith("+") && (noTrail.length == 1 || noTrail[noTrail.length - 2].isWhitespace())) {
                line = noTrail.dropLast(1)
                if (line.isBlank()) continue
            }
            out += line
        }
        return out
    }

    /**
     * 折行续接（pev2 splitIntoLines 语义）：上一行括号不配平 → 本行是续行并入；列 0 起笔的行
     * （折行把缩进吞掉）并入上行；首个非空白是 `(` 或右括号先于左括号 → 续行并入；
     * 上行以逗号收尾且缩进不同 → 列表折行续接；其余成行。`#` 回显行不参与续接
     * （防回显行把列 0 的根节点行吞进去——pev2 无此防护，此处按其 headerRegex 意图补齐）。
     */
    private fun splitIntoLines(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) {
            if (line.trimStart().startsWith("#")) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.count { it == '(' } != prev.count { it == ')' }) {
                out[out.size - 1] = prev + line
                continue
            }
            if (INFO_LINE_HEADER.containsMatchIn(line)) {
                out += line
                continue
            }
            val trimmed = line.trim()
            val startsNonSpace = line.isNotEmpty() && !line[0].isWhitespace()
            if (startsNonSpace || trimmed.startsWith("(") || closingFirst(line)) {
                if (out.isNotEmpty()) out[out.size - 1] = out[out.size - 1] + line else out += line
                continue
            }
            if (out.isNotEmpty() && prev != null && prev.trimEnd().endsWith(",") &&
                !sameIndent(prev, line) && !trimmed.startsWith("->")
            ) {
                out[out.size - 1] = prev + line
                continue
            }
            out += line
        }
        return out
    }

    /** 右括号先于左括号出现（折行续段的典型形态）。 */
    private fun closingFirst(s: String): Boolean {
        val close = s.indexOf(')')
        val open = s.indexOf('(')
        return close != -1 && open != -1 && close < open
    }

    private fun sameIndent(a: String, b: String): Boolean =
        a.indexOfFirst { !it.isWhitespace() } == b.indexOfFirst { !it.isWhitespace() }

    // ---------- 形态正则（pev2 语义转写；pattern 提 object 单例，F59 同款纪律） ----------

    /** 节点头尾巴（pev2 estimationPattern + actualPattern 组合形态）。 */
    private val NODE_TAIL_COST_ACTUAL = Regex(
        """\(cost=(\d+(?:\.\d+)?)\.\.(\d+(?:\.\d+)?)\s+rows=(\d+)\s+width=(\d+)\)""" +
            """\s+\(actual(?:\s+time=(\d+(?:\.\d+)?)\.\.(\d+(?:\.\d+)?))?\s+rows=(\d+(?:\.\d+)?)\s+loops=(\d+)\)\s*$""",
    )
    private val NODE_TAIL_COST_NEVER = Regex(
        """\(cost=(\d+(?:\.\d+)?)\.\.(\d+(?:\.\d+)?)\s+rows=(\d+)\s+width=(\d+)\)\s+\(never executed\)\s*$""",
    )
    private val NODE_TAIL_COST = Regex(
        """\(cost=(\d+(?:\.\d+)?)\.\.(\d+(?:\.\d+)?)\s+rows=(\d+)\s+width=(\d+)\)\s*$""",
    )
    private val NODE_TAIL_ACTUAL = Regex(
        """\(actual(?:\s+time=(\d+(?:\.\d+)?)\.\.(\d+(?:\.\d+)?))?\s+rows=(\d+(?:\.\d+)?)\s+loops=(\d+)\)\s*$""",
    )
    private val NODE_TAIL_NEVER = Regex("""\(never executed\)\s*$""")

    /** 并行分段前缀（Finalize/Simple/Partial Aggregate 等形态）。 */
    private val PARTIAL_PREFIX = Regex("""^(Finalize|Simple|Partial)\s+""")

    /** SubPlan / InitPlan 分段头（整行形态，可带编号与 `(returns $N)`）。 */
    private val SUBPLAN_LINE = Regex("""^(?:SubPlan|InitPlan)\s*(?:\d+)?\s*(?:\(returns\b.*?\))?\s*$""")

    /** CTE 分段头（`CTE <name>`）。 */
    private val CTE_LINE = Regex("""^CTE\s+\S+\s*$""")

    /** Worker 分段行（`Worker N: …`）。 */
    private val WORKER_LINE = Regex("""^Worker\s+\d+:\s*(.*)$""")

    /** Trigger 行（`Trigger <name>: time=<n> calls=<n>`）。 */
    private val TRIGGER_LINE = Regex("""^Trigger\s+.+:\s+time=\d+(?:\.\d+)?\s+calls=\d+\s*$""")

    /** 根级 JIT 块头。 */
    private val JIT_LINE = Regex("""^JIT:\s*$""")

    /** 根级 Planning 块头。 */
    private val PLANNING_LINE = Regex("""^Planning:\s*$""")

    /** 行数尾行（`(9 rows)` / `(3 lignes)` 等）。 */
    private val ROWCOUNT_LINE = Regex("""^\(\d+\s+\S+\)$""")

    /** Buckets 行值形态：`1024  Batches: 1  Memory Usage: 9kB`（可带 originally 括注）。 */
    private val BUCKETS_VALUE = Regex(
        """^\d+(?:\s+\(originally\s+\d+\))?\s+Batches:\s+(\d+)(?:\s+\(originally\s+(\d+)\))?\s+Memory Usage:\s+\d+kB$""",
    )

    /** HashAggregate 独立 Batches 行值形态：`5  Memory Usage: 161kB [ Disk Usage: 200kB ]`。 */
    private val HASHAGG_BATCHES_VALUE = Regex("""^(\d+)\s+Memory Usage:\s+\d+kB(?:\s+Disk Usage:\s+(\d+)kB)?$""")

    /** 根级信息行头（pev2 splitIntoLines 的防误吸收清单）。 */
    private val INFO_LINE_HEADER = Regex(
        """^(?:Total\s+runtime|Planning(?:\s+time)?|Execution\s+time|Time|Filter|Output|JIT|Trigger|Settings|Serialization)""",
        RegexOption.IGNORE_CASE,
    )

    /** psql 框线字符。 */
    private val FRAME_CHARS = charArrayOf('|', '║', '│')

    /** 分隔线字符全集（行内全为这些字符且 ≥3 长即判为分隔线）。 */
    private val SEPARATOR_CHARS = setOf(
        '+', '-', '=', '─', '═', '━', '┄', '┼', '├', '┤', '└', '┘', '┌', '┐',
        '╠', '╣', '╚', '╝', '╔', '╗', '╞', '╡', '╟', '╢',
    )
}
