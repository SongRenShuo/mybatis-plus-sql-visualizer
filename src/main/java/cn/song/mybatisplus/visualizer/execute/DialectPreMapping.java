package cn.song.mybatisplus.visualizer.execute;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.lang.Language;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * T-ExecSlowOps F1：SQL 方言映射（SqlDialectMappings）公共编排——反射收编自
 * {@code SqlPreviewPanel.mapPreviewDialectBeforePsi} 与 {@code DataSourceSelector.mapDialectBeforePsi} 两处同构代码。
 *
 * 线程归属双实证（262 真机堆栈 + javap）：
 *  - {@code setMapping} 必须在 EDT——平台内部 {@code PerFileMappingsBase.setMapping → handleMappingChange →
 *    PsiDocumentManagerImpl.reparseFiles → runWriteAction → beforeVfsChange} 消息总线走到
 *    {@code VcsVFSListener.beforeContentsChange:671} 的 {@code assertEventDispatchThread} 硬断言；
 *    BGT 直调抛 RuntimeExceptionWithAttachments（SEVERE ×3 实录，且 VcsVFSListener
 *    before/after 事件对失衡）。平台原生 ChangeDialectAction 全程 EDT 调用，同款约束。
 *  - 只读探测 {@code getConfiguredMapping(VirtualFile)} 无 EDT 限制（243 app-client.jar /
 *    262 intellij.platform.analysis.impl.jar 双版 javap 实锤 public 签名），可在 BGT
 *    ReadAction 内安全执行。
 *
 * 于是形成两段式：{@link #ensureMapped}（后台线程入口）先读探测——稳态命中
 * （workspace.xml 持久映射在 / 本进程已设过）直接跳过，零断言零 EDT 占用；仅项目首见
 * 经 invokeLater 派发 {@link #setMySqlOnEdt} 并 CountDownLatch 有界等待（BGT 等 EDT，EDT 永不
 * 反向阻塞——无互锁面）。慢操作（refresh/findFile）归 BGT、映射写归 EDT，两侧 SEVERE 同时消除。
 */
public final class DialectPreMapping {

    private static final Logger LOG = Logger.getInstance(DialectPreMapping.class);

    /** EDT 映射派发后的有界等待预算（毫秒）——EDT 冻结的病态场景超时放行，findFile 照跑（最差等价旧行为）。 */
    private static final long EDT_SET_MAPPING_WAIT_MS = 2000L;

    private DialectPreMapping() {
    }

    /**
     * 判定能否跳过 setMapping（纯函数，表驱动单测）：
     *  - 进程内幂等短路命中（同 vf 已设过）→ 跳过；
     *  - 读探测确认已配置为目标方言（O-136 泛化：MySQL 目标即「已配置为 MySQL」，含 IDE 重启后
     *    workspace.xml 持久映射幸存；非 MySQL 目标即「已配置为该目标」）→ 跳过；
     *  - 探测失败（null）保守不跳过——宁可多设一次同值映射，不冒 GenericSQL PSI 静默失败的险（M36.5.3）。
     */
    public static boolean shouldSkip(boolean inProcessMapped, Boolean configuredIsMySql) {
        return inProcessMapped || Boolean.TRUE.equals(configuredIsMySql);
    }

    /**
     * 只读探测 vf 的文件级方言映射是否已显式配置为 MySQL。null = 反射/环境异常（无法判定）。
     * 须在 ReadAction 内调用（ensureMapped 已代包；EDT 调用方自行保证）。
     */
    public static Boolean configuredAsMySql(Project project, VirtualFile vf) {
        try {
            Object mappings = instanceOf(project);
            if (mappings == null) {
                return null;
            }
            Object mysql = findMySql(mappings);
            if (mysql == null) {
                return null;
            }
            Object configured = mappings.getClass()
                    .getMethod("getConfiguredMapping", VirtualFile.class)
                    .invoke(mappings, vf);
            if (!(configured instanceof Language)) {
                return false;
            }
            return ((Language) configured).getID().equalsIgnoreCase("MySQL");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * O-136：只读探测 vf 当前配置方言的 language id（null = 未配置/反射失败，无法判定）。
     * 纯读（PerFileMappings 内存表查询），ReadAction 内或 EDT 均可调用。
     */
    static String configuredLanguageId(Project project, VirtualFile vf) {
        try {
            Object mappings = instanceOf(project);
            if (mappings == null) {
                return null;
            }
            Object configured = mappings.getClass()
                    .getMethod("getConfiguredMapping", VirtualFile.class)
                    .invoke(mappings, vf);
            return configured instanceof Language language ? language.getID() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** O-136：按 language id 在 SqlDialectMappings 可用值里找方言 Language 实例（忽略大小写；失败 null）。
     *  master 为 internal（模块级可见）——k2j 预览链（ui 包）需调，Java 侧 public 对应模块内跨包访问。 */
    public static Object languageById(Project project, String id) {
        try {
            Object mappings = instanceOf(project);
            if (mappings == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) mappings.getClass().getMethod("getAvailableValues").invoke(mappings);
            if (values == null) {
                return null;
            }
            for (Object v : values) {
                if (v instanceof Language language && language.getID().equalsIgnoreCase(id)) {
                    return v;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * T-DialectInferFix：**继承值**读取（项目级 → 全局默认），不含本目录自己的显式映射。
     *
     * <p>与 {@link #configuredLanguageId} 的区别：后者走 {@code getConfiguredMapping}（文件/目录优先），会把插件
     * 历史上写给预览目录的兼容兜底也读出来——而那条正是要覆盖掉的旧值。这里从<b>项目根</b>读
     * 继承链，拿不到再退 {@code SqlDialectMappings.getConfiguredDefaultSqlDialect()}（设置页「全局 SQL 方言」，
     * 243/253/262/2026.2 均为 public static，本仓既有反射口径同族）。
     */
    /** T-DialectInferFix：项目根继承值 → 全局默认（无需 VirtualFile，渲染侧与映射侧共用同一口径）。 */
    public static String inheritedLanguageId(Project project) {
        try {
            Object mappings = instanceOf(project);
            if (mappings == null) {
                return null;
            }
            String basePath = project.getBasePath();
            VirtualFile root = basePath != null
                    ? com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath)
                    : null;
            if (root != null) {
                Object inherited = mappings.getClass()
                        .getMethod("getConfiguredMapping", VirtualFile.class)
                        .invoke(mappings, root);
                if (inherited instanceof Language language) {
                    String id = language.getID();
                    if (id != null && !id.isBlank()) {
                        return id;
                    }
                }
            }
            return defaultLanguageId(project);
        } catch (Throwable t) {
            return null;
        }
    }

    /** T-DialectInferFix：平台全局默认 SQL 方言的 language id（用户设置页「全局 SQL 方言」）。 */
    public static String defaultLanguageId(Project project) {
        try {
            Object dialect = Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
                    .getMethod("getConfiguredDefaultSqlDialect")
                    .invoke(null);
            return dialect instanceof Language language ? language.getID() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** T-DialectInferFix：本文件/目录**自有**映射的 language id（无则 null；不含继承）。 */
    public static String configuredImmediateLanguageId(Project project, VirtualFile vf) {
        try {
            Object mappings = instanceOf(project);
            if (mappings == null) {
                return null;
            }
            Object immediate = mappings.getClass()
                    .getMethod("getImmediateMapping", VirtualFile.class)
                    .invoke(mappings, vf);
            return immediate instanceof Language language ? language.getID() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * T-DialectInferFix：清除插件自己写在预览目录上的显式映射（{@code setMapping(vf, null)}）。
     *
     * <p>线程口径与 {@link #ensureMapped} 完全一致：写映射必须 EDT（{@code VcsVFSListener} 断言），后台线程
     * 派发 + 有界等待。失败只 warn，不影响预览继续（最差保留旧映射）。
     */
    public static boolean clearMapping(Project project, VirtualFile vf, long waitMs) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return clearMappingOnEdt(project, vf);
        }
        CountDownLatch done = new CountDownLatch(1);
        final boolean[] cleared = {false};
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                cleared[0] = clearMappingOnEdt(project, vf);
            } finally {
                done.countDown();
            }
        });
        boolean signaled;
        try {
            signaled = done.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException t) {
            signaled = false;
            Thread.currentThread().interrupt();
        }
        return signaled && cleared[0];
    }

    /** 默认等待预算重载（master 默认参数）。 */
    public static boolean clearMapping(Project project, VirtualFile vf) {
        return clearMapping(project, vf, EDT_SET_MAPPING_WAIT_MS);
    }

    private static boolean clearMappingOnEdt(Project project, VirtualFile vf) {
        try {
            Object mappings = instanceOf(project);
            if (mappings == null) {
                return false;
            }
            mappings.getClass()
                    .getMethod("setMapping", VirtualFile.class, Object.class)
                    .invoke(mappings, vf, null);
            return true;
        } catch (Throwable e) {
            LOG.warn("[M36] dialect clearMapping skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * O-136：EDT 执行 setMapping(vf, targetLanguage)——与 {@link #setMySqlOnEdt} 同一线程断言与降级口径，
     * 方言实例由调用方解析（ExecDialectRouting.resolveTargetLanguage，SqlDialectMappings 可用值家族）。
     */
    static boolean setLanguageOnEdt(Project project, VirtualFile vf, Object targetLanguage) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            LOG.warn("[M36] setLanguageOnEdt called off EDT — refused (VcsVFSListener EDT assert, T-ExecSlowOps F1)");
            return false;
        }
        try {
            Object mappings = instanceOf(project);
            if (mappings == null) {
                return false;
            }
            mappings.getClass()
                    .getMethod("setMapping", VirtualFile.class, Object.class)
                    .invoke(mappings, vf, targetLanguage);
            return true;
        } catch (Throwable e) {
            if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(e)) {
                throw (Error) e;
            }
            LOG.warn("[M36] dialect setMapping skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * EDT 执行 setMapping(vf, MySQL)——平台线程断言见类 KDoc，非 EDT 调用直接拒绝并 warn
     * （防御网：调用方编排错误不得静默退化为 BGT 直调）。
     */
    public static boolean setMySqlOnEdt(Project project, VirtualFile vf) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            LOG.warn("[M36] setMySqlOnEdt called off EDT — refused (VcsVFSListener EDT assert, T-ExecSlowOps F1)");
            return false;
        }
        try {
            Object mappings = instanceOf(project);
            if (mappings == null) {
                return false;
            }
            Object mysql = findMySql(mappings);
            if (mysql == null) {
                LOG.warn("[M36] MySQL language dialect not found in SqlDialectMappings (fallback to pusher)");
                return false;
            }
            mappings.getClass()
                    .getMethod("setMapping", VirtualFile.class, Object.class)
                    .invoke(mappings, vf, mysql);
            return true;
        } catch (Throwable e) {
            LOG.warn("[M36] dialect setMapping skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 后台线程两段式入口（MySQL 目标，O-136 前的既有语义保持）：{@link #shouldSkip} 判定（读探测包
     * ReadAction，BGT 合法）→ 命中即返；未命中 invokeLater 派发 EDT 映射 + 有界等待（<b>禁止在
     * EDT 调用本函数</b>——误入则退化为同步 {@link #setMySqlOnEdt}，语义仍正确）。返回映射是否已就位；
     * false 不阻断调用方 findFile（最差 = GenericSQL PSI 旧行为）。
     */
    public static boolean ensureMapped(Project project, VirtualFile vf, boolean inProcessMapped, long waitMs) {
        return ensureMapped(project, vf, null, inProcessMapped, waitMs);
    }

    /** 默认等待预算重载（master 默认参数）。 */
    public static boolean ensureMapped(Project project, VirtualFile vf, boolean inProcessMapped) {
        return ensureMapped(project, vf, null, inProcessMapped, EDT_SET_MAPPING_WAIT_MS);
    }

    /** O-136 目标方言形态（waitMs 缺省，master 默认参数）。 */
    public static boolean ensureMapped(Project project, VirtualFile vf, Object targetLanguage,
                                       boolean inProcessMapped) {
        return ensureMapped(project, vf, targetLanguage, inProcessMapped, EDT_SET_MAPPING_WAIT_MS);
    }

    /**
     * O-136 主入口：{@code targetLanguage} 非 null 时按该方言（数据源方言路由，ExecDialectRouting
     * 解析）映射；null 保持 MySQL 旧行为。两段式语义与上同——BGT 读探测稳态跳过（当前配置
     * 已等于目标方言，含 DBN SqlDialectPusher 已推成目标方言的自愈场景）、未命中派 EDT 写。
     */
    public static boolean ensureMapped(Project project, VirtualFile vf, Object targetLanguage,
                                       boolean inProcessMapped, long waitMs) {
        String targetId = targetLanguage instanceof Language language ? language.getID() : null;
        if (ApplicationManager.getApplication().isDispatchThread()) {
            if (inProcessMapped) {
                return true;
            }
            return targetLanguage != null
                    ? setLanguageOnEdt(project, vf, targetLanguage)
                    : setMySqlOnEdt(project, vf);
        }
        String configured;
        try {
            configured = ReadAction.compute(() -> configuredLanguageId(project, vf));
        } catch (Throwable t) {
            configured = null;
        }
        Boolean configuredMatches;
        if (configured == null) {
            configuredMatches = null;
        } else if (targetId == null) {
            configuredMatches = configured.equalsIgnoreCase(ExecDialectRouting.MYSQL_LANGUAGE_ID);
        } else {
            configuredMatches = configured.equalsIgnoreCase(targetId);
        }
        if (shouldSkip(inProcessMapped, configuredMatches)) {
            return true;
        }
        CountDownLatch done = new CountDownLatch(1);
        final boolean[] mapped = {false};
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                mapped[0] = targetLanguage != null
                        ? setLanguageOnEdt(project, vf, targetLanguage)
                        : setMySqlOnEdt(project, vf);
            } finally {
                done.countDown();
            }
        });
        boolean signaled = false;
        try {
            signaled = done.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException t) {
            signaled = false;
            Thread.currentThread().interrupt();
        }
        if (!signaled) {
            LOG.warn("[M36] dialect EDT mapping dispatch timed out after " + waitMs + "ms (EDT busy?), continue findFile");
            return false;
        }
        return mapped[0];
    }

    private static Object instanceOf(Project project) {
        try {
            Class<?> mappingsClass = Class.forName("com.intellij.sql.dialects.SqlDialectMappings");
            return mappingsClass.getMethod("getInstance", Project.class).invoke(null, project);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object findMySql(Object mappings) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) mappings.getClass().getMethod("getAvailableValues").invoke(mappings);
            if (values == null) {
                return null;
            }
            for (Object v : values) {
                if (v instanceof Language && ((Language) v).getID().equalsIgnoreCase("MySQL")) {
                    return v;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
