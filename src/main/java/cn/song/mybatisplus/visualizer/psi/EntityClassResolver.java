package cn.song.mybatisplus.visualizer.psi;

import cn.song.mybatisplus.visualizer.psi.jpa.JpaMetadataReader;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
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
        // Duplicate source/binary definitions follow the actual platform classpath, not iteration order.
        Map<String, List<PsiClass>> groups = new LinkedHashMap<>();
        for (PsiClass clazz : visible.values()) groups.computeIfAbsent(clazz.getQualifiedName(), key -> new ArrayList<>()).add(clazz);
        List<PsiClass> canonical = new ArrayList<>();
        for (var group : groups.entrySet()) {
            // T-ShadowEntityResolve P0a：同一 FQN 多份定义里**工程内容优先于依赖库**（库内宿主拿不到
            // 模块上下文，上面的 classpath 序分支会整段跳过 → allScope compare 跨 order-entry 返 0
            // → 两份都留 → 假歧义）。必须逐 FQN 组内判断；跨 FQN 同短名是真正的重名冲突，不借此收窄。
            // 门控 context != null：无宿主＝无从判断哪份是这条查询该用的，保持既有 fail-closed 契约。
            List<PsiClass> byClasspath = new ArrayList<>();
            if (module == null || group.getKey() == null || group.getValue().size() == 1) {
                byClasspath.addAll(group.getValue());
            } else {
                PsiClass resolved = facade.findClass(group.getKey(), scope);
                for (PsiClass candidate : group.getValue()) {
                    if (Objects.equals(candidate, resolved) || Objects.equals(virtualFile(candidate), virtualFile(resolved))) {
                        byClasspath.add(candidate);
                    }
                }
            }
            canonical.addAll(context == null ? byClasspath : preferProjectContent(project, byClasspath));
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
        if (candidates.size() > 1) notifyAmbiguityOnce(project, entityName, module, candidates);
        return new Resolution(candidates.size() == 1 ? candidates.get(0) : null, candidates);
    }

    /**
     * T-ShadowEntityResolve：同一 FQN 的多份定义里优先保留**工程内容**（content root）的那份。
     * 调用方保证 definitions 属于同一 FQN；只在「组内既有工程定义又有库定义」时收窄——两份都在工程
     * 或都在库时原样返回，仍交由调用方按歧义 fail closed（排序只用于展示，不伪造确定性）。
     * 依据是 classpath 语义而非偏好：工程输出恒排在依赖 jar 之前。
     */
    private static List<PsiClass> preferProjectContent(@NotNull Project project, @NotNull List<PsiClass> definitions) {
        if (definitions.size() <= 1) return definitions;
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        List<PsiClass> inContent = new ArrayList<>();
        for (PsiClass clazz : definitions) {
            VirtualFile vf = virtualFile(clazz);
            if (vf != null && fileIndex.isInContent(vf)) inContent.add(clazz);
        }
        return inContent.isEmpty() || inContent.size() == definitions.size() ? definitions : inContent;
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
