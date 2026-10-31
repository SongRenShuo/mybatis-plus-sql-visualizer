package cn.song.mybatisplus.visualizer.sql;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 方言证据不保存 JDBC URL/凭据；无 UUID 的候选只能带本次决策临时键，禁止持久学习。
 *
 * <p>master 的 {@code object DialectDecisionEngine} 的 Java 承载（全 static 形态；
 * Kotlin data class copy → record 与 {@code withXxx} 派生方法）。</p>
 */
public final class DialectDecisionEngine {

    private DialectDecisionEngine() {
    }

    public enum Status { MANUAL, RESOLVED, AMBIGUOUS, CONFLICT, UNAVAILABLE }

    public enum Confidence { EXACT, STRONG, CONSENSUS, FALLBACK }

    public enum Source {
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

    public enum TargetStatus { MATCH, AUTO_RERENDER_REQUIRED, MANUAL_CONFLICT, TARGET_UNKNOWN }

    /** 候选数据源（id = 平台稳定 UUID 或本次决策临时键）。 */
    public record Candidate(String id, @Nullable String dialectName) {
    }

    /** 决策输入（Kotlin 默认参数 → 便捷构造器）。 */
    public record Input(
            @Nullable String manualDialect,
            @Nullable String capturedDialect,
            boolean explicitScope,
            List<Candidate> scopeCandidates,
            Source scopeSource,
            @Nullable String ideaDialect,
            Source ideaDialectSource,
            @Nullable Candidate learnedModule,
            List<Candidate> projectCandidates,
            @Nullable String lastAgentDialect,
            boolean singleModuleProven,
            /** T-DialectStatusLiveRefresh：面板已绑定/上次使用数据源的方言（具体目标，可为 null）。 */
            @Nullable String boundDataSourceDialect
    ) {
        public Input {
            scopeCandidates = scopeCandidates == null ? List.of() : List.copyOf(scopeCandidates);
            projectCandidates = projectCandidates == null ? List.of() : List.copyOf(projectCandidates);
        }

        public Input(@Nullable String manualDialect, @Nullable String capturedDialect) {
            this(manualDialect, capturedDialect, false, List.of(), Source.IDEA_SCOPE, null, Source.IDEA_DIALECT,
                    null, List.of(), null, false, null);
        }
    }

    /** 决策结果（不可变；派生形态经 {@code withXxx}）。 */
    public record Decision(
            Status status,
            @Nullable String dialectName,
            Confidence confidence,
            @Nullable Source source,
            List<String> dataSourceIds,
            List<Source> evidence,
            @Nullable String compatibilityFallbackName
    ) {
        public Decision {
            dataSourceIds = dataSourceIds == null ? List.of() : List.copyOf(dataSourceIds);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public Decision withStatus(Status newStatus) {
            return new Decision(newStatus, dialectName, confidence, source, dataSourceIds, evidence,
                    compatibilityFallbackName);
        }

        public Decision withSource(Source newSource) {
            return new Decision(status, dialectName, confidence, newSource, dataSourceIds, evidence,
                    compatibilityFallbackName);
        }

        public Decision withEvidencePlus(Source extra) {
            List<Source> merged = new ArrayList<>(evidence);
            merged.add(extra);
            return new Decision(status, dialectName, confidence, source, dataSourceIds, merged,
                    compatibilityFallbackName);
        }
    }

    public record TargetCheck(TargetStatus status, @Nullable String renderDialect, @Nullable String targetDialect) {
    }

    public static Decision decide(Input input) {
        String manual = knownName(input.manualDialect());
        if (manual != null) {
            return resolved(manual, Confidence.STRONG, Source.MANUAL, List.of(), List.of(Source.MANUAL))
                    .withStatus(Status.MANUAL);
        }
        String captured = knownName(input.capturedDialect());
        if (captured != null) {
            return resolved(captured, Confidence.EXACT, Source.CAPTURED_URL, List.of(),
                    List.of(Source.CAPTURED_URL));
        }
        String idea = knownName(input.ideaDialect());
        if (input.explicitScope()) {
            Decision fromScope = fromCandidates(input.scopeCandidates(), input.scopeSource(), idea,
                    input.ideaDialectSource());
            if (fromScope != null) {
                return fromScope;
            }
        }
        if (idea != null) {
            return resolved(idea, Confidence.STRONG, input.ideaDialectSource(), List.of(),
                    List.of(input.ideaDialectSource()));
        }
        // T-DialectStatusLiveRefresh（方案 B）：已绑定/上次使用数据源是具体执行目标，证据强于
        // 模块学习与项目候选；置于 scope/idea 决策之后，保留既有 CONFLICT 阻断语义。
        String bound = knownName(input.boundDataSourceDialect());
        if (bound != null) {
            return resolved(bound, Confidence.STRONG, Source.BOUND_DATASOURCE, List.of(),
                    List.of(Source.BOUND_DATASOURCE));
        }
        Candidate learnedModule = input.learnedModule();
        if (learnedModule != null) {
            String learned = knownName(learnedModule.dialectName());
            if (learned != null) {
                return resolved(learned, Confidence.STRONG, Source.LEARNED_MODULE,
                        List.of(learnedModule.id()), List.of(Source.LEARNED_MODULE));
            }
        }
        Decision projectDecision = fromCandidates(input.projectCandidates(), Source.PROJECT_CONSENSUS, null,
                Source.IDEA_DIALECT);
        if (projectDecision != null) {
            String agent = input.singleModuleProven() ? knownName(input.lastAgentDialect()) : null;
            if (agent != null && projectDecision.status() == Status.RESOLVED
                    && !Objects.equals(projectDecision.dialectName(), agent)) {
                return new Decision(Status.CONFLICT, null, Confidence.FALLBACK, Source.PROJECT_CONSENSUS,
                        projectDecision.dataSourceIds(), projectDecision.withEvidencePlus(Source.PROJECT_AGENT).evidence(),
                        projectDecision.compatibilityFallbackName());
            }
            return projectDecision;
        }
        if (input.singleModuleProven()) {
            String agent = knownName(input.lastAgentDialect());
            if (agent != null) {
                return resolved(agent, Confidence.STRONG, Source.PROJECT_AGENT, List.of(),
                        List.of(Source.PROJECT_AGENT));
            }
        }
        return unavailable(null, List.of(), List.of());
    }

    public static TargetCheck checkTarget(Decision decision, @Nullable String targetDialect) {
        String target = knownName(targetDialect);
        if (target == null) {
            return new TargetCheck(TargetStatus.TARGET_UNKNOWN, decision.dialectName(), null);
        }
        String rendered = decision.dialectName();
        if (rendered == null) {
            return new TargetCheck(TargetStatus.AUTO_RERENDER_REQUIRED, null, target);
        }
        if (rendered.equals(target)) {
            return new TargetCheck(TargetStatus.MATCH, rendered, target);
        }
        TargetStatus status = decision.status() == Status.MANUAL
                ? TargetStatus.MANUAL_CONFLICT
                : TargetStatus.AUTO_RERENDER_REQUIRED;
        return new TargetCheck(status, rendered, target);
    }

    private static Decision fromCandidates(List<Candidate> raw, Source source, @Nullable String idea,
                                           Source ideaSource) {
        List<Candidate> distinctRaw = new ArrayList<>(new LinkedHashSet<>(raw));
        List<Candidate> candidates = distinctRaw.stream()
                .filter(c -> c.id() != null && !c.id().isBlank())
                .sorted(Comparator.comparing(Candidate::id, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(c -> c.dialectName() == null ? "" : c.dialectName()))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> ids = candidates.stream().map(Candidate::id).distinct().toList();
        List<String> names = candidates.stream().map(c -> knownName(c.dialectName())).toList();
        Set<String> distinct = new LinkedHashSet<>(names);
        List<Source> evidence = idea != null ? List.of(source, ideaSource) : List.of(source);
        if (distinct.size() > 1) {
            return new Decision(Status.AMBIGUOUS, null, Confidence.FALLBACK, source, ids, evidence, null);
        }
        String name = names.get(0);
        if (name == null) {
            if (idea != null) {
                return resolved(idea, Confidence.STRONG, ideaSource, ids, evidence);
            }
            return unavailable(source, ids, evidence);
        }
        if (idea != null && !idea.equals(name)) {
            return new Decision(Status.CONFLICT, null, Confidence.FALLBACK, source, ids, evidence, null);
        }
        return resolved(name, ids.size() == 1 ? Confidence.STRONG : Confidence.CONSENSUS, source, ids, evidence);
    }

    private static Decision resolved(String name, Confidence confidence, Source source,
                                     List<String> ids, List<Source> evidence) {
        return new Decision(source == Source.MANUAL ? Status.MANUAL : Status.RESOLVED,
                name, confidence, source, ids, evidence, null);
    }

    private static Decision unavailable(@Nullable Source source, List<String> ids, List<Source> evidence) {
        return new Decision(Status.UNAVAILABLE, null, Confidence.FALLBACK, source, ids, evidence, "MySQL");
    }

    private static String knownName(@Nullable String value) {
        if (value == null) {
            return null;
        }
        for (Dialect dialect : DialectRegistry.INSTANCE.getDialects()) {
            if (dialect.getName().equalsIgnoreCase(value)) {
                return dialect.getName();
            }
        }
        return null;
    }
}
