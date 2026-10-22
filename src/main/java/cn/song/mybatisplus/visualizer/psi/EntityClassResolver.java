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
            if (module == null || group.getKey() == null || group.getValue().size() == 1) canonical.addAll(group.getValue());
            else {
                PsiClass resolved = facade.findClass(group.getKey(), scope);
                for (PsiClass candidate : group.getValue()) {
                    if (Objects.equals(candidate, resolved) || Objects.equals(virtualFile(candidate), virtualFile(resolved))) canonical.add(candidate);
                }
            }
        }
        List<PsiClass> candidates = new ArrayList<>();
        for (PsiClass clazz : canonical) {
            ProgressManager.checkCanceled();
            String explicit = JpaMetadataReader.entityNameOf(clazz);
            String name = explicit != null && !explicit.isEmpty() ? explicit : clazz.getName();
            if (entityName.equals(qualified ? clazz.getQualifiedName() : name)) candidates.add(clazz);
        }
        candidates.sort(Comparator.comparing((PsiClass clazz) -> Objects.toString(clazz.getQualifiedName(), Objects.toString(clazz.getName(), "")))
                .thenComparing(clazz -> virtualFile(clazz) == null ? "" : virtualFile(clazz).getUrl()));
        if (candidates.size() > 1) notifyAmbiguityOnce(project, entityName, module, candidates);
        return new Resolution(candidates.size() == 1 ? candidates.get(0) : null, candidates);
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
        List<String> names = pool.stream().map(c -> Objects.toString(c.getQualifiedName(), Objects.toString(c.getName(), "?"))).toList();
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
                        MpSqlVisualizerBundle.message("preview.entityNameAmbiguity.content", scopeDescription, String.join(", ", names)),
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
