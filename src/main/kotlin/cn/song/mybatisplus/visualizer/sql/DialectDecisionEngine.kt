package cn.song.mybatisplus.visualizer.sql

/** 方言证据不保存 JDBC URL/凭据；无 UUID 的候选只能带本次决策临时键，禁止持久学习。 */
object DialectDecisionEngine {
    enum class Status { MANUAL, RESOLVED, AMBIGUOUS, CONFLICT, UNAVAILABLE }
    enum class Confidence { EXACT, STRONG, CONSENSUS, FALLBACK }
    enum class Source {
        MANUAL, CAPTURED_URL,
        IDEA_SCOPE, IDEA_DIALECT,
        IDEA_PROVIDER, IDEA_CONFIGURED_SCOPE, IDEA_EFFECTIVE_DIALECT,
        LEARNED_MODULE, PROJECT_CONSENSUS, PROJECT_AGENT,
        /** T-DialectStatusLiveRefresh：面板已绑定/上次使用数据源（具体执行目标，非猜测）。 */
        BOUND_DATASOURCE,
        /** T-DialectInferFix：项目配置文件里的 JDBC URL（无 IDE 数据源时的运行时事实，低置信）。 */
        PROJECT_CONFIG,
        /** T-DialectInferFix：构建文件里的数据库驱动坐标（无数据源、无配置时的工程事实，低置信）。 */
        PROJECT_DEPENDENCY,
        /** T-DialectInferFix：SQL 文本里的方言独占特征（最后兜底，仅强特征唯一命中时采用）。 */
        SQL_SYNTAX,
    }
    enum class TargetStatus { MATCH, AUTO_RERENDER_REQUIRED, MANUAL_CONFLICT, TARGET_UNKNOWN }

    data class Candidate(val id: String, val dialectName: String?)

    data class Input(
        val manualDialect: String? = null,
        val capturedDialect: String? = null,
        val explicitScope: Boolean = false,
        val scopeCandidates: List<Candidate> = emptyList(),
        val scopeSource: Source = Source.IDEA_SCOPE,
        val ideaDialect: String? = null,
        val ideaDialectSource: Source = Source.IDEA_DIALECT,
        val learnedModule: Candidate? = null,
        val projectCandidates: List<Candidate> = emptyList(),
        val lastAgentDialect: String? = null,
        val singleModuleProven: Boolean = false,
        /** T-DialectStatusLiveRefresh：面板已绑定/上次使用数据源的方言（具体目标，可为 null）。 */
        val boundDataSourceDialect: String? = null,
    )

    data class Decision(
        val status: Status,
        val dialectName: String?,
        val confidence: Confidence,
        val source: Source?,
        val dataSourceIds: List<String> = emptyList(),
        val evidence: List<Source> = emptyList(),
        val compatibilityFallbackName: String? = null,
    )

    data class TargetCheck(val status: TargetStatus, val renderDialect: String?, val targetDialect: String?)

    fun decide(input: Input): Decision {
        knownName(input.manualDialect)?.let {
            return resolved(it, Confidence.STRONG, Source.MANUAL)
                .copy(status = Status.MANUAL)
        }
        knownName(input.capturedDialect)?.let {
            return resolved(it, Confidence.EXACT, Source.CAPTURED_URL)
        }
        val idea = knownName(input.ideaDialect)
        if (input.explicitScope) {
            fromCandidates(input.scopeCandidates, input.scopeSource, idea, input.ideaDialectSource)?.let { return it }
        }
        idea?.let { return resolved(it, Confidence.STRONG, input.ideaDialectSource) }
        // T-DialectStatusLiveRefresh（方案 B）：已绑定/上次使用数据源是具体执行目标，证据强于
        // 模块学习与项目候选；置于 scope/idea 决策之后，保留既有 CONFLICT 阻断语义。
        knownName(input.boundDataSourceDialect)?.let {
            return resolved(it, Confidence.STRONG, Source.BOUND_DATASOURCE)
        }
        input.learnedModule?.let { candidate ->
            knownName(candidate.dialectName)?.let {
                return resolved(it, Confidence.STRONG, Source.LEARNED_MODULE, listOf(candidate.id))
            }
        }
        fromCandidates(input.projectCandidates, Source.PROJECT_CONSENSUS, null)?.let { projectDecision ->
            val agent = if (input.singleModuleProven) knownName(input.lastAgentDialect) else null
            if (agent != null && projectDecision.status == Status.RESOLVED &&
                projectDecision.dialectName != agent) {
                return Decision(Status.CONFLICT, null, Confidence.FALLBACK, Source.PROJECT_CONSENSUS,
                    projectDecision.dataSourceIds, projectDecision.evidence + Source.PROJECT_AGENT)
            }
            return projectDecision
        }
        if (input.singleModuleProven) {
            knownName(input.lastAgentDialect)?.let {
                return resolved(it, Confidence.STRONG, Source.PROJECT_AGENT)
            }
        }
        return unavailable()
    }

    fun checkTarget(decision: Decision, targetDialect: String?): TargetCheck {
        val target = knownName(targetDialect)
            ?: return TargetCheck(TargetStatus.TARGET_UNKNOWN, decision.dialectName, null)
        val rendered = decision.dialectName
            ?: return TargetCheck(TargetStatus.AUTO_RERENDER_REQUIRED, null, target)
        if (rendered == target) return TargetCheck(TargetStatus.MATCH, rendered, target)
        val status = if (decision.status == Status.MANUAL) TargetStatus.MANUAL_CONFLICT
            else TargetStatus.AUTO_RERENDER_REQUIRED
        return TargetCheck(status, rendered, target)
    }

    private fun fromCandidates(raw: List<Candidate>, source: Source, idea: String?,
                               ideaSource: Source = Source.IDEA_DIALECT): Decision? {
        val candidates = raw.filter { it.id.isNotBlank() }
            .distinct().sortedWith(compareBy(Candidate::id, { it.dialectName.orEmpty() }))
        if (candidates.isEmpty()) return null
        val ids = candidates.map(Candidate::id).distinct()
        val names = candidates.map { knownName(it.dialectName) }
        val distinct = names.toSet()
        val evidence = if (idea != null) listOf(source, ideaSource) else listOf(source)
        if (distinct.size > 1) {
            return Decision(Status.AMBIGUOUS, null, Confidence.FALLBACK, source, ids, evidence)
        }
        val name = names.first() ?: return if (idea != null) {
            resolved(idea, Confidence.STRONG, ideaSource, ids, evidence)
        } else unavailable(source, ids, evidence)
        if (idea != null && idea != name) {
            return Decision(Status.CONFLICT, null, Confidence.FALLBACK, source, ids, evidence)
        }
        return resolved(name, if (ids.size == 1) Confidence.STRONG else Confidence.CONSENSUS,
            source, ids, evidence)
    }

    private fun resolved(name: String, confidence: Confidence, source: Source,
                         ids: List<String> = emptyList(), evidence: List<Source> = listOf(source)): Decision =
        Decision(if (source == Source.MANUAL) Status.MANUAL else Status.RESOLVED,
            name, confidence, source, ids, evidence)

    private fun unavailable(source: Source? = null, ids: List<String> = emptyList(),
                            evidence: List<Source> = emptyList()): Decision =
        Decision(Status.UNAVAILABLE, null, Confidence.FALLBACK, source, ids, evidence, "MySQL")

    private fun knownName(value: String?): String? = value?.let { candidate ->
        DialectRegistry.dialects.firstOrNull { it.name.equals(candidate, ignoreCase = true) }?.name
    }
}
