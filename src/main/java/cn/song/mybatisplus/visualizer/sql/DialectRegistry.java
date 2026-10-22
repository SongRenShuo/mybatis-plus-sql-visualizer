package cn.song.mybatisplus.visualizer.sql;

import java.util.List;

/**
 * 方言注册表：按名称获取方言，未知名称回退 MySQL。
 */
public final class DialectRegistry {

    /** 单例。 */
    public static final DialectRegistry INSTANCE = new DialectRegistry();

    private static final List<Dialect> DIALECTS = List.of(
            MySqlDialect.INSTANCE,
            PostgreSQLDialect.INSTANCE,
            OracleDialect.INSTANCE,
            SqlServerDialect.INSTANCE,
            H2Dialect.INSTANCE,
            SqliteDialect.INSTANCE,
            MariaDbDialect.INSTANCE,
            DmDialect.INSTANCE,
            KingBaseDialect.INSTANCE,
            OceanBaseDialect.INSTANCE,
            OpenGaussDialect.INSTANCE);

    private DialectRegistry() {}

    /** 全部支持方言（顺序即 Settings 下拉顺序）。 */
    public List<Dialect> getDialects() {
        return DIALECTS;
    }

    /**
     * 按名称取方言。
     *
     * @param name 方言名称（对应 {@link Dialect#getName()}）
     * @return 匹配方言；未知名称返回 {@link MySqlDialect}
     */
    public Dialect byName(String name) {
        if (name != null) {
            for (Dialect d : DIALECTS) {
                if (d.getName().equals(name)) return d;
            }
        }
        return MySqlDialect.INSTANCE;
    }

    /** {@link #effective(com.intellij.openapi.project.Project)} 的无项目上下文形态（project=null）。 */
    public Dialect effective() {
        return effective((com.intellij.openapi.project.Project) null);
    }

    /** 旧形态兼容：project 单参（auto 推断/手动解析语义不变，落新决策链）。 */
    public Dialect effective(com.intellij.openapi.project.Project project) {
        return effective(project, null, null, null);
    }

    /** Kotlin 默认参数等价重载：宿主源文件的方言探测目标（O-145 源码链：effective(project, sourceFile)）。 */
    public Dialect effective(com.intellij.openapi.project.Project project,
                             com.intellij.openapi.vfs.VirtualFile sourceFile) {
        return effective(project, sourceFile, null, null);
    }

    /**
     * T-TS-FixC 全参决策入口（O-136 泛化形态）：项目候选共识 + 模块学习 + IDEA 事实桥接入统一决策。
     */
    public DialectDecisionEngine.Decision decision(com.intellij.openapi.project.Project project,
                                                   com.intellij.openapi.vfs.VirtualFile sourceFile,
                                                   com.intellij.psi.PsiFile sqlPsiFile,
                                                   String moduleKey) {
        return decision(project, sourceFile, sqlPsiFile, moduleKey, null);
    }

    /** 仅项目上下文的决策（O-145 入口二：无来源文件/SQL PSI/模块键）。 */
    public DialectDecisionEngine.Decision decision(com.intellij.openapi.project.Project project) {
        return decision(project, null, null, null);
    }

    /** {@link #decision} 的 PreviewSourceContext 形态（O-145 瞬态上下文直取）。 */
    public DialectDecisionEngine.Decision decision(
            cn.song.mybatisplus.visualizer.context.PreviewSourceContext context) {
        return decision(context.project(), context.hostVirtualFile(), context.sqlPsiFile(),
                context.canonicalModuleKey());
    }

    public Dialect effective(cn.song.mybatisplus.visualizer.context.PreviewSourceContext context) {
        String name = decision(context).dialectName();
        return name != null ? byName(name) : MySqlDialect.INSTANCE;
    }

    /**
     * T-TS-FixC：渲染方言统一读取（决策态显式形态；兼容回退 {@link MySqlDialect}）。
     */
    public Dialect effective(com.intellij.openapi.project.Project project,
                             com.intellij.openapi.vfs.VirtualFile sourceFile,
                             com.intellij.psi.PsiFile sqlPsiFile,
                             String moduleKey) {
        String name = decision(project, sourceFile, sqlPsiFile, moduleKey).dialectName();
        return name != null ? byName(name) : MySqlDialect.INSTANCE;
    }

    /**
     * T-DialectAutoInfer（O-136 重构后的唯一事实源）：统一方言决策。
     *
     * <p>优先级（与 {@link DialectDecisionEngine#decide} 一致）：手动 &gt; 事件精确事实（captured）&gt;
     * IDEA 事实桥（SQL PSI/数据源 provider/scope 候选）&gt; 已绑定数据源 &gt; 模块学习 &gt; 项目候选共识
     * &gt; 最近 agent URL（单模块才采信）&gt; 兼容回退 MySQL（compatibilityFallbackName）。
     * 原始 SQL PSI/模块作用域经参数显式传入；无候选时按 {@link DialectDecisionEngine} 语义判
     * UNAVAILABLE 并携带兼容回退名，调用方可经 {@code decision()} 区分真实结论与兼容回退。</p>
     *
     * @param project               项目上下文（null=无项目上下文，直接判 UNAVAILABLE+MySQL 回退）
     * @param sourceFile            非预览链的源文件（无 SQL PSI 时的 IDEA 方言探测目标）
     * @param sqlPsiFile            实际 SQL PSI（DatabaseTools 控制台等；O-145 W1 桥的入口）
     * @param moduleKey             宿主模块稳定键（模块学习绑定查取）
     * @param boundDataSourceDialect 面板已绑定/上次使用数据源的方言（T-DialectStatusLiveRefresh）
     */
    public DialectDecisionEngine.Decision decision(com.intellij.openapi.project.Project project,
                                                   com.intellij.openapi.vfs.VirtualFile sourceFile,
                                                   com.intellij.psi.PsiFile sqlPsiFile,
                                                   String moduleKey,
                                                   String boundDataSourceDialect) {
        cn.song.mybatisplus.visualizer.config.PluginSettings.State settings;
        try {
            settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState();
        } catch (Throwable e) {
            settings = null;
        }
        String manualName = projectManualDialectName(project);
        if (manualName == null && settings != null) manualName = settings.getDialect();
        Boolean auto = projectDialectAuto(project);
        if (auto == null && settings != null) auto = settings.getDialectAuto();
        if (auto == null) auto = true;
        if (!auto) {
            return DialectDecisionEngine.decide(new DialectDecisionEngine.Input(manualName, null));
        }
        if (project == null) {
            // O-93：无推断通道时 auto 回退 MySQL，手动残值不参与（见 KDoc「边界」）
            return DialectDecisionEngine.decide(new DialectDecisionEngine.Input(null, null));
        }
        java.util.List<DialectDecisionEngine.Candidate> candidates = DataSourceUrlProbe.candidateDialects(project);
        boolean singleModule = false;
        try {
            singleModule = com.intellij.openapi.module.ModuleManager.getInstance(project)
                    .getModules().length == 1;
        } catch (Throwable ignored) {
            // runCatching().getOrDefault(false) 等价
        }
        String agentDialect = UrlSubprotocolDialects.dialectName(
                cn.song.mybatisplus.visualizer.log.agent.AgentUrlRegistry.lastConnectionUrl(project));
        DialectDecisionEngine.Candidate learnedModule = null;
        if (moduleKey != null) {
            try {
                learnedModule = ModuleDataSourceBindingStore.getInstance(project).learnedCandidate(project, moduleKey);
            } catch (Throwable ignored) {
                // runCatching().getOrNull() 等价
            }
        }
        com.intellij.openapi.vfs.VirtualFile dialectFile = sqlPsiFile != null && sqlPsiFile.getVirtualFile() != null
                ? sqlPsiFile.getVirtualFile()
                : sourceFile;
        if (sqlPsiFile != null && dialectFile != null) {
            IdeaSqlContextBridge.Snapshot snapshot = IdeaSqlContextBridge.resolve(project, sqlPsiFile, dialectFile);
            if (snapshot.availability() != IdeaSqlContextBridge.Availability.DATABASE_TOOLS_UNAVAILABLE) {
                return IdeaSqlDialectDecision.decide(
                        null,
                        null,
                        snapshot,
                        learnedModule,
                        candidates,
                        agentDialect,
                        singleModule,
                        boundDataSourceDialect);
            }
        }
        String ideaDialect = sourceFile != null ? IdeaSourceDialectProbe.dialectName(project, sourceFile) : null;
        return DialectDecisionEngine.decide(new DialectDecisionEngine.Input(
                null,
                null,
                false,
                java.util.List.of(),
                DialectDecisionEngine.Source.IDEA_SCOPE,
                ideaDialect,
                DialectDecisionEngine.Source.IDEA_EFFECTIVE_DIALECT,
                learnedModule,
                candidates,
                agentDialect,
                singleModule,
                boundDataSourceDialect));
    }

    /**
     * T-TS-FixC：项目级有效方言模式（null=无项目上下文/项目级服务不可用，调用方按全局判定）。
     *
     * <p>复用 {@link cn.song.mybatisplus.visualizer.config.ProjectPluginSettings#effectiveDialectAuto}
     * 而非直读 {@code state.dialectAuto}：项目级方法内部已内置「总开关关闭 / dialectAuto=null → 跟随全局」
     * 回退语义，直读会把这层回退丢掉（T-TS-06 已在 SqlPreviewService 侧踩过同款）。</p>
     */
    private Boolean projectDialectAuto(com.intellij.openapi.project.Project project) {
        if (project == null) return null;
        try {
            return cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
                    .getInstance(project).effectiveDialectAuto();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * T-TS-FixC：项目级有效手动方言名（null=无项目上下文/项目级服务不可用，调用方按全局判定）。
     *
     * <p>同样复用 {@link cn.song.mybatisplus.visualizer.config.ProjectPluginSettings#effectiveDialect}
     * （启用且非空才取项目值，否则已回退全局）。</p>
     */
    private String projectManualDialectName(com.intellij.openapi.project.Project project) {
        if (project == null) return null;
        try {
            return cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
                    .getInstance(project).effectiveDialect();
        } catch (Throwable e) {
            return null;
        }
    }
}
