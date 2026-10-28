package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SQL 方言映射预热（T-SqlDialectPrewarm）。
 *
 * <h2>要解决的问题</h2>
 *
 * <p>给「SQL 文件类型」建编辑器（或显式取该 FileType 的语法高亮器）会经 {@code SqlLanguageSubstitutor}
 * 落到 {@code SqlDialectMappings.getMapping}，而 {@code PerFileMappingsBase.ensureStateLoaded} 是<b>一次性延迟
 * 加载</b>：首次查询时把持久化的「文件 → 方言」映射逐条 {@code VirtualFileManager.findFileByUrl} 解析成
 * {@code VirtualFile}，内部 {@code PersistentFSImpl.findChildInfo → FSRecordsImpl.update} 被平台归类为慢操作。
 * 这次首查若发生在 EDT（项目打开时工具窗内容装配就是），平台即记
 * {@code SlowOperations: Slow operations are prohibited on EDT} 并把插件归为 blame。</p>
 *
 * <p>这里把首查<b>提前到 BGT</b> 做完：此后所有 EDT 侧查询都命中已加载状态（{@code ensureStateLoaded} 第二
 * 次起直接返回）。预热只解决「首查时机」，不改变任何功能性行为。</p>
 *
 * <h2>契约</h2>
 * <ul>
 *  <li>失败只记 WARN、<b>绝不抛给调用方</b>——预热是优化，不能阻断工具窗装配；</li>
 *  <li>单测模式短路：测试基座没有 Database 插件，平台慢操作断言在测试里是硬抛，不能让预热把测试搞红；</li>
 *  <li>无 {@code SQL} FileType（社区版 / 未装 Database）短路，并记住「无可预热」以免反复探测；</li>
 *  <li>每个 project 只成功预热一次，用弱键集合记状态（不持有 Project 强引用）；</li>
 *  <li><b>允许并发重复调用</b>：状态已加载后重复预热是空转（{@code ensureStateLoaded} 内部已 synchronized
 *      且首查后 deferred 置 null），故不做 in-flight 去重，换取实现简单。</li>
 * </ul>
 */
public final class SqlDialectMappingPrewarm {

    /** EDT 侧有界等待上限：超时即照原样继续装配（最坏退化为「本次未预热」，不比改动前更差）。 */
    public static final long DEFAULT_TIMEOUT_MS = 300L;

    private static final Logger LOG = Logger.getInstance(SqlDialectMappingPrewarm.class);

    /** 已完成预热的项目（弱键：项目销毁后自动摘除）。 */
    private static final Set<Project> WARMED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private SqlDialectMappingPrewarm() {
    }

    /** 异步预热（fire-and-forget）。调用方无需等待；失败静默降级。 */
    public static void prewarm(Project project) {
        if (!needsWarm(project)) return;
        try {
            // 显式 Runnable：区分 executeOnPooledThread 的 Runnable/Callable 两个重载，避免推断歧义
            ApplicationManager.getApplication().executeOnPooledThread((Runnable) () -> prewarmNow(project));
        } catch (Throwable t) {
            LOG.warn("sql dialect mapping prewarm dispatch failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 同步执行预热，<b>只允许在 BGT 调用</b>（EDT 上调用等于把原问题照搬回来）。
     *
     * @return 是否真的做了预热工作（已预热 / 单测 / 无 SQL FileType 均返回 false）
     */
    public static boolean prewarmNow(Project project) {
        if (!needsWarm(project)) return false;
        // 无 SQL FileType：没有方言映射可查（未装 Database 时本插件多处以该 FileType 为能力探针），
        // 记「无可预热」避免每次启动重复探测。
        FileType fileType = FileTypeManager.getInstance().getStdFileType("SQL");
        if (fileType == null) {
            markWarmed(project);
            return false;
        }
        long startedAt = System.nanoTime();
        try {
            ProgressManager.getInstance().runProcess((Runnable) () -> {
                // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                ReadAction.compute(
                        (com.intellij.openapi.util.ThrowableComputable<Object, RuntimeException>) () -> {
                            // 本句即触发 SqlDialectMappings 的一次性状态加载（见类头）；返回值只用于驱动加载
                            SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null);
                            return null;
                        });
            }, new EmptyProgressIndicator());
            markWarmed(project);
            LOG.info("sql dialect mapping prewarm done in "
                    + ((System.nanoTime() - startedAt) / 1_000_000) + "ms");
            return true;
        } catch (Throwable t) {
            // 不置 warmed：下次仍有补齐机会；本次调用方照原逻辑继续。JVM 级致命错误不吞（T-FatalPassthrough）
            if (FatalErrors.isFatal(t)) throw (Error) t;
            LOG.warn("sql dialect mapping prewarm failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * EDT 侧用法：派发到 BGT 并<b>有界</b>等待完成。
     *
     * <p>顺序是承重墙——调用点必须早于「创建 SQL 文件类型编辑器」，否则预热没赶上首查就没有意义。</p>
     *
     * @return true=预热已完成；false=已预热过 / 不可预热 / 超时 / 失败（调用方都应按原逻辑继续）
     */
    public static boolean prewarmAndAwait(Project project) {
        return prewarmAndAwait(project, DEFAULT_TIMEOUT_MS);
    }

    /** Java 调用点（SqlLogToolWindowFactory）免带超时实参（master @JvmOverloads 默认参数重载）。 */
    public static boolean prewarmAndAwait(Project project, long timeoutMs) {
        if (!needsWarm(project)) return false;
        Future<Boolean> future;
        try {
            // 显式 Callable：区分 executeOnPooledThread 的 Runnable/Callable 两个重载（master 同款注记）
            future = ApplicationManager.getApplication()
                    .executeOnPooledThread((Callable<Boolean>) () -> prewarmNow(project));
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            LOG.warn("sql dialect mapping prewarm dispatch failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warn("sql dialect mapping prewarm timed out after " + timeoutMs + "ms; assembling anyway");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw (Error) t;
            LOG.warn("sql dialect mapping prewarm failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /** 测试缝：清掉某项目的预热标记（用例间隔离）。 */
    public static void resetForTest(Project project) {
        WARMED.remove(project);
    }

    /** 测试缝：当前是否已记为「预热完成」。 */
    public static boolean isWarmedForTest(Project project) {
        return WARMED.contains(project);
    }

    private static boolean needsWarm(Project project) {
        Application app = ApplicationManager.getApplication();
        if (app.isUnitTestMode() || project.isDisposed()) return false;
        return !WARMED.contains(project);
    }

    private static void markWarmed(Project project) {
        WARMED.add(project);
    }
}
