package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import kotlin.Unit;
import kotlin.jvm.functions.Function4;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Resolve entity names inside the query's module, dependencies and libraries.
 * Call under read access. Missing visible classes never fall back to unrelated modules;
 * equally valid candidates fail closed. Sorting is for diagnostics, not selection.
 * Only name/FQN lookups are performed here, never an all-class-name sweep.
 */
public final class EntityClassResolver {
    public static final EntityClassResolver INSTANCE = new EntityClassResolver();
    private static final Logger LOG = Logger.getInstance(EntityClassResolver.class);
    private EntityClassResolver() {}

    /** @param psiClass unique selected class, or null
     *  @param candidates all equally valid visible candidates */
    public record Resolution(@Nullable PsiClass psiClass, @NotNull List<PsiClass> candidates) {
        public boolean getAmbiguous() { return candidates.size() > 1; }
        public boolean isAmbiguous() { return getAmbiguous(); }
        public PsiClass getPsiClass() { return psiClass; }
        public List<PsiClass> getCandidates() { return candidates; }
    }

    public static @NotNull Resolution resolveByName(@NotNull Project project, @NotNull String entityName,
                                                    @Nullable PsiElement context) {
        return resolveWithAliasCandidates(project, entityName, context, List.of());
    }

    public static @Nullable PsiClass resolveClassByName(@NotNull Project project, @NotNull String entityName,
                                                        @Nullable PsiElement context) {
        return resolveByName(project, entityName, context).psiClass();
    }

    /** Merge a COMPLETE bounded alias search, never a first-hit/truncated proof of uniqueness. */
    public static @NotNull Resolution resolveWithAliasCandidates(@NotNull Project project, @NotNull String entityName,
            @Nullable PsiElement context, @NotNull Collection<PsiClass> aliasCandidates) {
        if (entityName.isBlank()) return new Resolution(null, List.of());
        ProgressManager.checkCanceled();
        Module module = contextModule(project, context);
        GlobalSearchScope scope = searchScope(project, context);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        boolean qualified = entityName.contains(".");
        PsiClass[] direct = qualified ? facade.findClasses(entityName, scope)
                : PsiShortNamesCache.getInstance(project).getClassesByName(entityName, scope);
        List<PsiClass> combined = new ArrayList<>(Arrays.asList(direct));
        combined.addAll(aliasCandidates);
        Map<List<String>, PsiClass> visible = new LinkedHashMap<>();
        for (PsiClass clazz : combined) {
            ProgressManager.checkCanceled();
            VirtualFile file = virtualFile(clazz);
            if (clazz.isValid() && file != null && scope.contains(file)) {
                visible.putIfAbsent(Arrays.asList(clazz.getQualifiedName(), file.getUrl()), clazz);
            }
        }
        // Resolve duplicate source/binary definitions by classpath semantics.
        // T-ShadowEntityResolve P0a：同一 FQN 多份定义里**工程内容优先于依赖库**（库内宿主拿不到
        // 模块上下文，allScope 下两份都留 → 假歧义）。必须逐 FQN 组内判断；跨 FQN 同短名是真正的
        // 重名冲突，不借此收窄。
        // T-ShadowEntityResolve W6：不再以「模块上下文存在」为前提仲裁。宿主在 jar 里时两个候选是
        // **两个模块各一份 `.java`**（jar 内那份已被平台同 FQN 去重淘汰），内容/形态两步都失效；
        // 但两个候选的**模块身份**确定可得，而模块依赖方向就是运行期 classpath 序——依赖方自己的
        // 类先命中。只看「分属两模块且一方依赖另一方」，无关模块仍不收窄，保持 fail-closed。
        Map<String, List<PsiClass>> groups = new LinkedHashMap<>();
        for (PsiClass clazz : visible.values()) groups.computeIfAbsent(clazz.getQualifiedName(), key -> new ArrayList<>()).add(clazz);
        List<PsiClass> canonical = new ArrayList<>();
        for (var group : groups.entrySet()) {
            List<PsiClass> byModuleOrder = group.getKey() == null || group.getValue().size() == 1
                    ? group.getValue()
                    : preferDependentModule(project, group.getValue());
            // 门控 context != null：无宿主＝无从判断这份同 FQN 定义是不是这条查询该用的那份，
            // 保持既有 fail-closed 契约（同文件 testContextlessDuplicateFqnIsNotArbitrarilyDeduplicated）。
            canonical.addAll(context == null ? byModuleOrder : preferProjectContent(project, byModuleOrder));
        }
        List<PsiClass> candidates = new ArrayList<>();
        for (PsiClass clazz : canonical) {
            ProgressManager.checkCanceled();
            String explicit = JpaMetadataReader.entityNameOf(clazz);
            String name = explicit != null && !explicit.isEmpty() ? explicit : clazz.getName();
            if (entityName.equals(qualified ? clazz.getQualifiedName() : name)) candidates.add(clazz);
        }
        // T-ShadowEntityResolve P0b：同短名的**非实体类**（DTO/工具类）不构成 JPA 实体歧义——Hibernate
        // 解析 `FROM <名>` 只认映射实体。仅在确有映射实体时收窄；收窄后仍多个实体则继续 fail closed。
        // 门控 context != null：完全无上下文时保持「跨源码/库必须唯一」的既有契约。
        if (candidates.size() > 1 && context != null) {
            List<PsiClass> mapped = new ArrayList<>();
            for (PsiClass clazz : candidates) {
                if (cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader.isLikelyMappedEntityClass(clazz)) {
                    mapped.add(clazz);
                }
            }
            if (!mapped.isEmpty()) {
                candidates = mapped;
            }
        }
        candidates.sort(Comparator.comparing((PsiClass clazz) -> Objects.toString(clazz.getQualifiedName(), Objects.toString(clazz.getName(), "")))
                .thenComparing(clazz -> virtualFile(clazz) == null ? "" : virtualFile(clazz).getUrl()));
        if (!candidates.isEmpty() || context == null || Objects.equals(scope, GlobalSearchScope.allScope(project))) {
            if (candidates.size() > 1) notifyAmbiguityOnce(project, entityName, module, candidates);
            return new Resolution(candidates.size() == 1 ? candidates.get(0) : null, candidates);
        }
        // T-ShadowEntityResolve W3：**零候选拓宽**。workspace 形态（Maven 多模块同载）下，宿主 DAO 在
        // 提供方模块源码里＝模块内容，`contextModule` 是提供方模块；实体却可能只存在于**下游**业务
        // 模块（依赖方向 demo→lib，lib 看不见 demo）——用户注释掉 lib 内实体后宿主作用域内零候选，
        // 预览降级为 `task.*`（20261023 真机复现）。宿主作用域「看不见」不等于「不存在」：放宽到
        // allScope 再查一次，**全项目唯一命中且确为映射实体**才采用；多命中维持 fail closed
        // （T-EntityNameScope 防「挑错实体」的核心保护不放松）。
        GlobalSearchScope wideScope = GlobalSearchScope.allScope(project);
        PsiClass[] wideDirect = qualified ? facade.findClasses(entityName, wideScope)
                : PsiShortNamesCache.getInstance(project).getClassesByName(entityName, wideScope);
        Map<List<String>, PsiClass> wideVisible = new LinkedHashMap<>();
        for (PsiClass clazz : wideDirect) {
            ProgressManager.checkCanceled();
            VirtualFile file = virtualFile(clazz);
            if (clazz.isValid() && file != null && wideScope.contains(file)) {
                wideVisible.putIfAbsent(Arrays.asList(clazz.getQualifiedName(), file.getUrl()), clazz);
            }
        }
        List<PsiClass> wideCandidates = new ArrayList<>();
        for (PsiClass clazz : wideVisible.values()) {
            ProgressManager.checkCanceled();
            String explicit = JpaMetadataReader.entityNameOf(clazz);
            String name = explicit != null && !explicit.isEmpty() ? explicit : clazz.getName();
            if (entityName.equals(qualified ? clazz.getQualifiedName() : name)
                    && JpaMetadataReader.isLikelyMappedEntityClass(clazz)) {
                wideCandidates.add(clazz);
            }
        }
        if (wideCandidates.size() == 1) {
            return new Resolution(wideCandidates.get(0), wideCandidates);
        }
        // 零个或多个宽候选：多命中属**全项目真歧义**——按既有纪律走「歧义终态」（不挑一个 +
        // 提示一次，LRU 去重），让用户看到候选清单而不是无声降级。
        if (wideCandidates.size() > 1) notifyAmbiguityOnce(project, entityName, module, wideCandidates);
        return new Resolution(null, wideCandidates);
    }

    /**
     * T-ShadowEntityResolve：同一 FQN 的多份定义里优先保留**工程内容**（content root）的那份。
     * 调用方保证 definitions 属于同一 FQN；只在「组内既有工程定义又有库定义」时收窄——两份都在工程
     * 或都在库时原样返回，仍交由调用方按歧义 fail closed（排序只用于展示，不伪造确定性）。
     * 依据是 classpath 语义而非偏好：工程输出恒排在依赖 jar 之前。
     *
     * W5b：`isInContent` 不足以区分——jar 物理放在 `src/main/resources/lib/` 时 IDE 会把 jar 内
     * class 也判成内容（用户现场两个候选都是 `[工程]`），于是 `inContent.size == size` ⇒ 不收窄
     * ⇒ 歧义 ⇒ 降级。故内容判据之外再叠一层**源码形态优先**（见 narrowAmbiguous 第 3 步）。
     */
    private static List<PsiClass> preferProjectContent(@NotNull Project project, @NotNull List<PsiClass> definitions) {
        if (definitions.size() <= 1) return definitions;
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        Map<Module, Set<Module>> depsCache = new HashMap<>();
        return narrowAmbiguous(definitions,
                clazz -> {
                    VirtualFile vf = virtualFile(clazz);
                    return vf != null && fileIndex.isInContent(vf);
                },
                clazz -> {
                    VirtualFile vf = virtualFile(clazz);
                    return vf == null ? null : fileIndex.getModuleForFile(vf);
                },
                (a, b) -> {
                    Module dep = a instanceof Module m ? m : null;
                    Module target = b instanceof Module t ? t : null;
                    if (dep == null || target == null) return false;
                    Set<Module> deps = depsCache.computeIfAbsent(dep, key -> {
                        Set<Module> result = new HashSet<>();
                        ModuleUtilCore.getDependencies(dep, result);
                        return result;
                    });
                    return deps.contains(target);
                });
    }

    /**
     * 同一 FQN 多份定义的收窄决策**纯逻辑**（与 PSI 挂载解耦，可用假数据稳定钉住）。三步依次执行，
     * **每一步只在能明确分出胜负时收窄**，否则进入下一步；全部不奏效则原样返回，交回调用方按歧义
     * fail closed（不伪造胜者）：
     *
     *  1. **工程内容优先**——组内既有内容定义又有库定义时取内容；全内容/全非内容时不收窄。
     *  2. **模块依赖方向**——恰好分属两个模块且一方（传递）依赖另一方时取**依赖方**；依赖方向
     *     即运行期 classpath 序（本模块输出先于其依赖），也是「新增同名类覆盖依赖方」的生效规则。
     *     无关模块、互为依赖、或组内含无可归属定义（库内）时此步不收窄。
     *  3. **源码形态优先**——`.java` 对上 `.class` 时取源码；全源码/全二进制时不收窄。
     *
     * 顺序有意为之：先按内容剔除库定义，模块方向才可能落在「恰好两个模块」上（含库定义时模块不可
     * 归属，方向无从判定）。
     */
    public static @NotNull List<PsiClass> narrowAmbiguous(@NotNull List<PsiClass> definitions,
            @NotNull Predicate<PsiClass> isContent,
            @NotNull Function<PsiClass, Object> moduleKey,
            @NotNull BiPredicate<Object, Object> dependsOn) {
        if (definitions.size() <= 1) return definitions;

        // 1) 工程内容优先
        List<PsiClass> content = new ArrayList<>();
        for (PsiClass clazz : definitions) {
            if (isContent.test(clazz)) content.add(clazz);
        }
        List<PsiClass> current = content.isEmpty() || content.size() == definitions.size() ? definitions : content;
        if (current.size() <= 1) return current;

        // 2) 模块依赖方向（依赖方生效）
        Map<Object, List<PsiClass>> byModule = new LinkedHashMap<>();
        for (PsiClass clazz : current) {
            byModule.computeIfAbsent(moduleKey.apply(clazz), key -> new ArrayList<>()).add(clazz);
        }
        if (byModule.size() == 2 && !byModule.containsKey(null)) {
            Iterator<Object> keys = byModule.keySet().iterator();
            Object first = keys.next();
            Object second = keys.next();
            boolean firstDependsOnSecond = dependsOn.test(first, second);
            boolean secondDependsOnFirst = dependsOn.test(second, first);
            if (firstDependsOnSecond != secondDependsOnFirst) {
                current = byModule.get(firstDependsOnSecond ? first : second);
                if (current.size() <= 1) return current;
            }
        }

        // 3) 源码形态优先
        List<PsiClass> sourceForm = new ArrayList<>();
        for (PsiClass clazz : current) {
            VirtualFile vf = virtualFile(clazz);
            if (vf != null && "java".equals(vf.getExtension())) sourceForm.add(clazz);
        }
        return sourceForm.isEmpty() || sourceForm.size() == current.size() ? current : sourceForm;
    }

    /** narrowAmbiguous 的两参便捷重载（moduleKey/dependsOn 缺省为「不可归属/不依赖」）。 */
    public static @NotNull List<PsiClass> narrowAmbiguous(@NotNull List<PsiClass> definitions,
            @NotNull Predicate<PsiClass> isContent) {
        return narrowAmbiguous(definitions, isContent, clazz -> null, (a, b) -> false);
    }

    /**
     * T-ShadowEntityResolve W6：同 FQN 的多份定义**分属两个模块**时，优先保留**依赖方**那份。
     *
     * 依据是运行期 classpath 序：本模块的输出先于它所依赖的模块 ⇒ 依赖方自己的同名类先命中。
     * 这正是「在工程里新增一份同包同名类去覆盖依赖模块/jar 里的类」在运行期生效的规则。
     *
     * 只在能明确判定方向（一方（传递）依赖另一方，且反向不成立）时收窄；其余情形（无关模块、
     * 互为依赖、牵扯库定义导致模块不可知）原样返回，交回调用方按歧义 fail closed，不伪造胜者。
     */
    private static List<PsiClass> preferDependentModule(@NotNull Project project, @NotNull List<PsiClass> definitions) {
        if (definitions.size() <= 1) return definitions;
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        Map<Module, List<PsiClass>> byModule = new LinkedHashMap<>();
        for (PsiClass clazz : definitions) {
            VirtualFile vf = virtualFile(clazz);
            byModule.computeIfAbsent(vf == null ? null : fileIndex.getModuleForFile(vf), key -> new ArrayList<>()).add(clazz);
        }
        // 必须恰好两个模块且都可知；有库定义（module == null）时无法比方向
        if (byModule.size() != 2 || byModule.containsKey(null)) return definitions;
        Iterator<Module> keys = byModule.keySet().iterator();
        Module moduleA = keys.next();
        Module moduleB = keys.next();
        Set<Module> depsOfA = new HashSet<>();
        ModuleUtilCore.getDependencies(moduleA, depsOfA);
        Set<Module> depsOfB = new HashSet<>();
        ModuleUtilCore.getDependencies(moduleB, depsOfB);
        boolean aDependsOnB = depsOfA.contains(moduleB);
        boolean bDependsOnA = depsOfB.contains(moduleA);
        if (aDependsOnB == bDependsOnA) return definitions; // 互不依赖（或互为依赖）⇒ 不猜
        // A 依赖 B ⇒ A 是依赖方，A 的那份生效
        return byModule.get(aDependsOnB ? moduleA : moduleB);
    }

    /** Shared scope for direct, alias and metadata lookup; never broadens a known module. */
    public static @NotNull GlobalSearchScope searchScope(@NotNull Project project, @Nullable PsiElement context) {
        Module module = contextModule(project, context);
        return module != null ? GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module) : GlobalSearchScope.allScope(project);
    }

    public static @Nullable Module contextModule(@NotNull Project project, @Nullable PsiElement context) {
        if (context == null) return null;
        PsiFile file = context instanceof PsiFile psiFile ? psiFile : context.getContainingFile();
        if (file == null) return null;
        VirtualFile vf = file.getOriginalFile().getVirtualFile();
        if (vf == null) vf = file.getVirtualFile();
        return vf == null ? null : ProjectFileIndex.getInstance(project).getModuleForFile(vf);
    }

    private static VirtualFile virtualFile(PsiClass clazz) {
        return clazz == null || clazz.getContainingFile() == null ? null : clazz.getContainingFile().getVirtualFile();
    }

    /** @param module query module
     *  @param entityName queried name
     *  @param candidates candidate file URLs */
    private record NoticeKey(Module module, String entityName, List<String> candidates) {}
    private static final Key<Map<NoticeKey, Boolean>> AMBIGUITY_NOTIFIED_KEY = Key.create("mp-visualizer.entityNameAmbiguityNotified");

    /** F102：歧义提示去重集容量上界（超出按 LRU 淘汰最旧）。 */
    private static final int AMBIGUITY_NOTICE_MAX = 64;

    private static final String PREVIEW_NOTIFICATION_GROUP = "cn.song.mybatisplus.visualizer.preview";
    public static volatile Function4<Project, String, String, List<String>, Unit> ambiguitySinkForTest;

    private static void notifyAmbiguityOnce(Project project, String entityName, Module module, List<PsiClass> pool) {
        // T-ShadowEntityResolve P2：**用户可见文案**带来源标签（工程/库）。同 FQN 双份时候选 FQN
        // 完全相同，只列 FQN 等于打印「候选：A, A」——标签正是区分「同名不同类」与「同一类两份定义」
        // 的维度。测试缝与日志保留**原始 FQN**（既有用例按 FQN 清单断言契约）。
        ProjectFileIndex noticeIndex = ProjectFileIndex.getInstance(project);
        List<String> names = pool.stream().map(c -> Objects.toString(c.getQualifiedName(), Objects.toString(c.getName(), "?"))).toList();
        List<String> labelledNames = pool.stream().map(c -> {
            String fqn = Objects.toString(c.getQualifiedName(), Objects.toString(c.getName(), "?"));
            VirtualFile vf = virtualFile(c);
            boolean inContent = vf != null && noticeIndex.isInContent(vf);
            String origin = MpSqlVisualizerBundle.message(inContent
                    ? "preview.entityNameAmbiguity.originProject" : "preview.entityNameAmbiguity.originLibrary");
            return fqn + " [" + origin + "]";
        }).toList();
        // T-ShadowEntityResolve 诊断：歧义时把每个候选的判定依据写进 IDE 日志（Help → Show Log in Explorer）。
        // 用户现场出现过「两个候选都是 [工程]」的读数，需要区分 isInContent / isInLibraryClasses / 形态，
        // 否则只能靠猜工程配置。日志只在此路径输出，不影响正常解析。
        // （master 为 runCatching〔捕 Throwable〕；k2j 按 fatal 透传守卫纪律收窄为 Exception——
        //   诊断日志路径不存在 Error 级失配点，Error 本就该向上穿透。）
        try {
            int i = 0;
            for (PsiClass clazz : pool) {
                VirtualFile vf = virtualFile(clazz);
                Module candidateModule = vf == null ? null : noticeIndex.getModuleForFile(vf);
                LOG.info("entity-ambiguity[" + i + "] " + clazz.getQualifiedName()
                        + " ext=" + (vf == null ? null : vf.getExtension())
                        + " inContent=" + (vf != null && noticeIndex.isInContent(vf))
                        + " inLibClasses=" + (vf != null && noticeIndex.isInLibraryClasses(vf))
                        + " inLibSource=" + (vf != null && noticeIndex.isInLibrarySource(vf))
                        + " module=" + (candidateModule == null ? null : candidateModule.getName())
                        + " url=" + (vf == null ? null : vf.getUrl()));
                i++;
            }
        } catch (Exception ignore) {
            // 诊断日志失败绝不阻断解析与提示
        }
        NoticeKey key = new NoticeKey(module, entityName, pool.stream().map(c -> virtualFile(c) == null ? "" : virtualFile(c).getUrl()).toList());
        // F102（T-PerfSweep26）：去重集改有界 LRU——此前按 (module, entity, urls) 元组在项目
        // 生命周期内只增不减（量级=项目内歧义实体数，实际很小但无上界）。超出 MAX 后淘汰最旧，
        // 代价=极老歧义项可能再次提示一次（可接受：提示是幂等告知，非状态机）。
        Map<NoticeKey, Boolean> seen;
        synchronized (project) {
            seen = project.getUserData(AMBIGUITY_NOTIFIED_KEY);
            if (seen == null) {
                seen = new LinkedHashMap<>(AMBIGUITY_NOTICE_MAX + 1, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<NoticeKey, Boolean> eldest) {
                        return size() > AMBIGUITY_NOTICE_MAX;
                    }
                };
                project.putUserData(AMBIGUITY_NOTIFIED_KEY, seen);
            }
        }
        boolean firstTime;
        synchronized (seen) {
            if (seen.containsKey(key)) {
                seen.put(key, true); // accessOrder：命中即刷新 LRU 位置
                firstTime = false;
            } else {
                seen.put(key, true);
                firstTime = true;
            }
        }
        if (!firstTime) return;
        String scopeDescription = module != null ? module.getName() : "project / libraries";
        LOG.info("Entity name " + entityName + " is ambiguous in " + scopeDescription + "; no class selected: " + String.join(", ", names));
        var sink = ambiguitySinkForTest;
        if (sink != null) { sink.invoke(project, entityName, scopeDescription, names); return; }
        NotificationGroupManager.getInstance().getNotificationGroup(PREVIEW_NOTIFICATION_GROUP)
                .createNotification(MpSqlVisualizerBundle.message("preview.entityNameAmbiguity.title", entityName),
                        MpSqlVisualizerBundle.message("preview.entityNameAmbiguity.content", scopeDescription, String.join(", ", labelledNames)),
                        NotificationType.WARNING).notify(project);
    }

    public static void clearAmbiguityNotifiedForTest(Project project) {
        synchronized (project) { project.putUserData(AMBIGUITY_NOTIFIED_KEY, null); }
    }

    /** F102 测试缝：当前去重集条目数（有界性断言用；未初始化返回 0）。 */
    public static int ambiguityNoticeCountForTest(Project project) {
        synchronized (project) {
            Map<NoticeKey, Boolean> seen = project.getUserData(AMBIGUITY_NOTIFIED_KEY);
            return seen != null ? seen.size() : 0;
        }
    }

    /** F102 测试缝：去重集容量上界（与实现常量同源，避免用例写死数字漂移）。 */
    public static int ambiguityNoticeMaxForTest() {
        return AMBIGUITY_NOTICE_MAX;
    }
}
