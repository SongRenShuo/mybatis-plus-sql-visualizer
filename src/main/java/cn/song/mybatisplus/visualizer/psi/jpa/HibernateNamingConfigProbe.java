package cn.song.mybatisplus.visualizer.psi.jpa;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.TextOccurenceProcessor;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiModificationTracker;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 工程 Hibernate 命名约定探测（T-EmbeddedColPrefix 方案 A）。
 *
 * <p>实体本身不携带「嵌入列名怎么拼」这一信息——它由 Hibernate 的隐式命名策略决定
 * （见 {@link EmbeddedColumnNaming}）。本类从工程配置里找这个信号：<b>在实体所在模块</b>的
 * {@code src/main/resources} 下扫 {@code application*.yml/.yaml/.properties}，命中组件路径命名策略的
 * 写法即视为该模块用前缀约定。</p>
 *
 * <p>为什么按<b>模块</b>而不是整个工程扫（20261013 修正）：命名策略是<b>每个持久化单元</b>的事，
 * 而多模块工程里各模块可以有各自的 {@code application.yml}。按工程全扫时，只要任何一个兄弟模块
 * 声明了 component-path，其它模块的嵌入列就会被错误套上前缀（demo 仓同时放了「无前缀」与
 * 「组件路径」两套语料，正是这种形态）。故取「实体文件所在模块」的资源目录为扫描范围；
 * 认不出模块布局（无 {@code pom.xml}/{@code build.gradle*} 上溯命中）时回落到工程全扫，行为与改前一致。</p>
 *
 * <p>其它设计取舍：
 * <ul>
 *   <li><b>只认强信号</b>：匹配命名策略类名/关键字，不做「看着像」的模糊判定。判不出返回 false，
 *       由设置项兜底（{@code EmbeddedColumnNamingResolver}）——宁可漏判（用户可显式开）也不误判；</li>
 *   <li><b>纯文本判定与取文件分离</b>：{@link #indicatesComponentPath} 与 {@link #nearestModuleRootPath}
 *       都不碰平台 API，可脱离工程单测；</li>
 *   <li><b>按 PSI 版本 + 扫描范围缓存</b>：实体解析会被反复触发，每次读配置不可接受。</li>
 * </ul></p>
 */
public final class HibernateNamingConfigProbe {

    /**
     * 组件路径命名策略的识别标记（全名短名 + Hibernate 配置关键字写法）。
     *
     * <p>{@code ImplicitNamingStrategyComponentPathImpl} 是 Hibernate 5/6 的实现类；
     * {@code DefaultComponentSafeNamingStrategy} 是它移植前的 Hibernate 4 源类（老工程仍可能配这个名字）。</p>
     */
    public static final List<String> COMPONENT_PATH_MARKERS = List.of(
            "ImplicitNamingStrategyComponentPathImpl",
            "DefaultComponentSafeNamingStrategy",
            "component-path",
            "component_path"
    );

    /** 配置文件主名前缀（Spring Boot 的 {@code application} 族）。 */
    private static final String CONFIG_FILE_PREFIX = "application";

    /** 配置文件扩展名。 */
    private static final Set<String> CONFIG_EXTENSIONS = Set.of("yml", "yaml", "properties");

    /** 源码式配置走词索引按类名精确匹配，不遍历全部 Java 源文件。 */
    private static final List<String> COMPONENT_PATH_CLASS_NAMES = List.of(
        "ImplicitNamingStrategyComponentPathImpl", "DefaultComponentSafeNamingStrategy");
    /** 模块内标准 JPA/Hibernate 配置，工程级还按设置键搜索任意文件。 */
    public static final List<String> JPA_CONFIG_FILE_NAMES = List.of("persistence.xml", "orm.xml", "hibernate.cfg.xml");
    private static final String NAMING_STRATEGY_KEY = "implicit_naming_strategy";
    private static final List<String> NAMING_STRATEGY_TYPES = List.of(
        "ImplicitNamingStrategy", "ImplicitNamingStrategyJpaCompliantImpl",
        "ImplicitNamingStrategyLegacyJpaImpl", "ImplicitNamingStrategyLegacyHbmImpl");
    private static final String ATTRIBUTE_PATH_TYPE = "AttributePath";
    /** 只拿末端属性 getProperty 的策略不应命中；这三种调用能读取父段/整条路径。 */
    private static final List<String> ATTRIBUTE_PATH_WALK_MARKERS = List.of("getParent", "getFullPath", "getDepth");
    private static final String META_INF_DIR = "META-INF";

    /** 模块根标记文件（任一命中即视为模块根）。 */
    public static final List<String> MODULE_MARKER_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    /** 模块内配置所在相对路径。 */
    private static final String RESOURCES_RELATIVE_PATH = "src/main/resources";

    /** 未识别出模块布局时的缓存键（工程全扫）。 */
    private static final String PROJECT_WIDE_KEY = "<project-wide>";

    private static final class Cached {
        final long stamp;
        final boolean value;

        Cached(long stamp, boolean value) {
            this.stamp = stamp;
            this.value = value;
        }
    }

    private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * 券03（T-PerfSweep28-03，R28-4）缓存容量上限：键含项目基路径与模块资源目录，多项目长会话
     * 缓慢累积且无项目关闭摘除。超限整体清空（HbmXml 负缓存/EntityResolver 同范式；值为纯布尔
     * 判定结果，重算成本 = 一次资源目录扫描，可接受）。可写仅测试缝。
     */
    public static volatile int maxCacheEntries = 512;

    /** 当前缓存条目数（测试 / 诊断用）。 */
    public static int cacheSize() {
        return CACHE.size();
    }

    /** 券03 测试缝：按给定键走同一写入守卫路径（灌键验证上限收敛；生产链路无调用点）。 */
    public static void putForTest(String key, long stamp, boolean value) {
        store(key, new Cached(stamp, value));
    }

    /** 券03 测试缝：清空缓存（用例隔离；生产链路无调用点）。 */
    public static void clearCacheForTest() {
        CACHE.clear();
    }

    /** 写入守卫单一事实：超限整体清空再写（{@link #maxCacheEntries} 语义）。 */
    private static void store(String key, Cached entry) {
        if (CACHE.size() >= maxCacheEntries) {
            CACHE.clear();
        }
        CACHE.put(key, entry);
    }

    private HibernateNamingConfigProbe() {
    }

    /**
     * 工程（或更准确地说：{@code anchor} 所在模块）是否使用「组件路径」嵌入列命名。
     *
     * @param anchor 正在解析的实体文件；null 时按整个工程扫（调用方拿不到文件时的保守兜底）
     */
    public static boolean projectUsesComponentPath(@NotNull Project project, @Nullable VirtualFile anchor) {
        VirtualFile resourcesDir = moduleResourcesDir(anchor);
        if (resourcesDir != null) {
            // 已识别模块只看自身资源，不能把兄弟模块的工程级信号带进来。
            return cached(project, resourcesDir.getPath(), () -> ReadAction.compute(() -> scanResourcesDir(resourcesDir)));
        }
        // JAR 条目无法上溯本地模块，退到工程配置 + 代码式配置 + 自定义组件路径策略。
        return cached(project, PROJECT_WIDE_KEY, () -> ReadAction.compute(() ->
            scanProjectConfigs(project) || projectSourcesReferenceComponentPathStrategy(project)
                || projectHasCustomComponentPathStrategy(project)));
    }

    private static boolean cached(Project project, String scopeKey, java.util.function.BooleanSupplier compute) {
        String basePath = project.getBasePath();
        String key = (basePath != null ? basePath : project.getName()) + "|" + scopeKey;
        long stamp = PsiModificationTracker.getInstance(project).getModificationCount();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.stamp == stamp) return cached.value;
        boolean value = compute.getAsBoolean();
        // 券03（T-PerfSweep28-03）：写入走统一守卫（超限整体清空再写，语义见 maxCacheEntries）
        store(key, new Cached(stamp, value));
        return value;
    }

    /** {@link #projectUsesComponentPath(Project, VirtualFile)} 的无锚文件形态（Kotlin 默认参数 {@code anchor = null}）。 */
    public static boolean projectUsesComponentPath(@NotNull Project project) {
        return projectUsesComponentPath(project, null);
    }

    /** 实体文件所在模块的 {@code src/main/resources}；认不出模块布局时返回 null（回落工程全扫）。 */
    @Nullable
    private static VirtualFile moduleResourcesDir(@Nullable VirtualFile anchor) {
        if (anchor == null) return null;
        LocalFileSystem fs = LocalFileSystem.getInstance();
        String moduleRootPath = nearestModuleRootPath(anchor.getPath(), path -> {
            VirtualFile dir = fs.findFileByPath(path);
            if (dir == null) return false;
            for (String marker : MODULE_MARKER_FILES) {
                if (dir.findChild(marker) != null) return true;
            }
            return false;
        });
        if (moduleRootPath == null) return null;
        VirtualFile moduleRoot = fs.findFileByPath(moduleRootPath);
        if (moduleRoot == null) return null;
        VirtualFile resources = VfsUtilCore.findRelativeFile(RESOURCES_RELATIVE_PATH, moduleRoot);
        return resources != null && resources.isDirectory() ? resources : null;
    }

    /** 模块资源目录本体 + META-INF，不越出该模块范围。 */
    private static boolean scanResourcesDir(VirtualFile resourcesDir) {
        try {
            VirtualFile metaInf = resourcesDir.findChild(META_INF_DIR);
            for (VirtualFile dir : new VirtualFile[]{resourcesDir, metaInf}) {
                if (dir == null) continue;
                for (VirtualFile child : dir.getChildren()) {
                    if (!child.isDirectory() && isNamingConfigFile(child.getName())
                            && indicatesComponentPath(readText(child))) return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean scanProjectConfigs(Project project) {
        try {
            return applicationConfigMentions(project) || configFileMentionsNamingStrategyKey(project);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Boot 的连字符键 naming.implicit-strategy 由 application 族扩展名扫描补齐。 */
    private static boolean applicationConfigMentions(Project project) {
        try {
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
            for (String ext : CONFIG_EXTENSIONS) {
                for (VirtualFile file : FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                    if (isApplicationConfig(file.getName()) && indicatesComponentPath(readText(file))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 点分设置键的单个标识符词可命中任意配置文件，不把可识别面限定成几个文件名。 */
    private static boolean configFileMentionsNamingStrategyKey(Project project) {
        PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
        short context = (short) (UsageSearchContext.IN_CODE | UsageSearchContext.IN_STRINGS
            | UsageSearchContext.IN_COMMENTS | UsageSearchContext.IN_PLAIN_TEXT);
        boolean[] found = {false};
        helper.processElementsWithWord((TextOccurenceProcessor) (element, offset) -> {
            var file = element.getContainingFile();
            if (file != null && indicatesComponentPath(file.getText())) {
                found[0] = true;
                return false;
            }
            return true;
        }, GlobalSearchScope.projectScope(project), NAMING_STRATEGY_KEY, context, true);
        return found[0];
    }

    /** 代码式配置：工程源码内引用内置组件路径策略类名，词索引 O(命中) 且命中即停。 */
    private static boolean projectSourcesReferenceComponentPathStrategy(Project project) {
        try {
            PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
            short context = (short) (UsageSearchContext.IN_CODE | UsageSearchContext.IN_STRINGS);
            for (String className : COMPONENT_PATH_CLASS_NAMES) {
                boolean[] found = {false};
                helper.processElementsWithWord((TextOccurenceProcessor) (element, offset) -> {
                    if (element.getContainingFile() instanceof PsiJavaFile) {
                        found[0] = true;
                        return false;
                    }
                    return true;
                }, GlobalSearchScope.projectScope(project), className, context, true);
                if (found[0]) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 自定义组件路径策略须同时参与隐式命名并读取 AttributePath 父段/全路径；只改表名的不认。
     * 纯文本特征不解析控制流；依赖 JAR 内无源码的自定义实现、非下划线连接符仍是已知边界。
     */
    private static boolean projectHasCustomComponentPathStrategy(Project project) {
        try {
            PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
            short context = (short) (UsageSearchContext.IN_CODE | UsageSearchContext.IN_STRINGS);
            for (String type : NAMING_STRATEGY_TYPES) {
                boolean[] found = {false};
                helper.processElementsWithWord((TextOccurenceProcessor) (element, offset) -> {
                    var file = element.getContainingFile();
                    if (file instanceof PsiJavaFile && looksLikeComponentPathStrategy(file.getText())) {
                        found[0] = true;
                        return false;
                    }
                    return true;
                }, GlobalSearchScope.projectScope(project), type, context, true);
                if (found[0]) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean looksLikeComponentPathStrategy(String text) {
        if (!text.contains(ATTRIBUTE_PATH_TYPE)) return false;
        for (String marker : ATTRIBUTE_PATH_WALK_MARKERS) if (text.contains(marker)) return true;
        return false;
    }

    private static boolean isNamingConfigFile(String name) {
        return isApplicationConfig(name) || JPA_CONFIG_FILE_NAMES.contains(name);
    }

    private static boolean isApplicationConfig(String name) {
        if (!name.startsWith(CONFIG_FILE_PREFIX)) return false;
        for (String ext : CONFIG_EXTENSIONS) {
            if (name.endsWith("." + ext)) return true;
        }
        return false;
    }

    private static String readText(VirtualFile file) {
        try {
            return VfsUtilCore.loadText(file);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 纯文本判定（可单测）：配置文本里出现组件路径命名策略的任一写法即命中。
     *
     * <p>先剔除整行注释（{@code #} / {@code !} 起首，覆盖 properties 与 yml 两种注释形态）——被注释掉的
     * 配置不是生效配置，把它当命中会把工程判定反。</p>
     */
    public static boolean indicatesComponentPath(String text) {
        StringBuilder active = new StringBuilder();
        boolean first = true;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            if (!first) active.append('\n');
            active.append(line);
            first = false;
        }
        for (String marker : COMPONENT_PATH_MARKERS) {
            if (containsIgnoreCase(active.toString(), marker)) return true;
        }
        return false;
    }

    /** Kotlin {@code contains(ignoreCase = true)} 等价（区域无关大小写比对）。 */
    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack.length() < needle.length()) return false;
        for (int i = 0; i + needle.length() <= haystack.length(); i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return true;
        }
        return false;
    }

    /**
     * 纯路径判定（可单测）：自 {@code anchorPath} 向上找最近一个「含模块标记文件」的目录。
     *
     * @param anchorPath 实体文件的绝对路径（{@code /} 分隔）
     * @param isModuleDir 判定某目录是否为模块根（真实实现查 {@code pom.xml}/{@code build.gradle*} 是否存在）
     * @return 模块根路径；一路到根都没命中返回 null
     */
    @Nullable
    public static String nearestModuleRootPath(@NotNull String anchorPath, @NotNull Predicate<String> isModuleDir) {
        int idx = anchorPath.lastIndexOf('/');
        String dir = idx < 0 ? "" : anchorPath.substring(0, idx);
        while (!dir.isEmpty()) {
            if (isModuleDir.test(dir)) return dir;
            int parentIdx = dir.lastIndexOf('/');
            String parent = parentIdx < 0 ? "" : dir.substring(0, parentIdx);
            if (parent.equals(dir)) return null;
            dir = parent;
        }
        return null;
    }
}
