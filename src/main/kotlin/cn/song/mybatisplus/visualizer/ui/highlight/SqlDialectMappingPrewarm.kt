package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import cn.song.mybatisplus.visualizer.util.FatalErrors
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * SQL 方言映射预热（T-SqlDialectPrewarm）。
 *
 * ## 要解决的问题
 *
 * 给「SQL 文件类型」建编辑器（或显式取该 FileType 的语法高亮器）会经 `SqlLanguageSubstitutor`
 * 落到 `SqlDialectMappings.getMapping`，而 `PerFileMappingsBase.ensureStateLoaded` 是**一次性延迟
 * 加载**：首次查询时把持久化的「文件 → 方言」映射逐条 `VirtualFileManager.findFileByUrl` 解析成
 * `VirtualFile`，内部 `PersistentFSImpl.findChildInfo → FSRecordsImpl.update` 被平台归类为慢操作。
 * 这次首查若发生在 EDT（项目打开时工具窗内容装配就是），平台即记
 * `SlowOperations: Slow operations are prohibited on EDT` 并把插件归为 blame。
 *
 * 这里把首查**提前到 BGT** 做完：此后所有 EDT 侧查询都命中已加载状态（`ensureStateLoaded` 第二
 * 次起直接返回）。预热只解决「首查时机」，不改变任何功能性行为。
 *
 * ## 契约
 *
 *  - 失败只记 WARN、**绝不抛给调用方**——预热是优化，不能阻断工具窗装配；
 *  - 单测模式短路：测试基座没有 Database 插件，平台慢操作断言在测试里是硬抛，不能让预热把测试搞红；
 *  - 无 `SQL` FileType（社区版 / 未装 Database）短路，并记住「无可预热」以免反复探测；
 *  - 每个 project 只成功预热一次，用弱键集合记状态（不持有 Project 强引用）；
 *  - **允许并发重复调用**：状态已加载后重复预热是空转（`ensureStateLoaded` 内部已 synchronized
 *    且首查后 deferred 置 null），故不做 in-flight 去重，换取实现简单。
 */
object SqlDialectMappingPrewarm {

    /** EDT 侧有界等待上限：超时即照原样继续装配（最坏退化为「本次未预热」，不比改动前更差）。 */
    internal const val DEFAULT_TIMEOUT_MS = 300L

    private val LOG = Logger.getInstance(SqlDialectMappingPrewarm::class.java)

    /** 已完成预热的项目（弱键：项目销毁后自动摘除）。 */
    private val warmed: MutableSet<Project> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Project, Boolean>()))

    /** 异步预热（fire-and-forget）。调用方无需等待；失败静默降级。 */
    fun prewarm(project: Project) {
        if (!needsWarm(project)) return
        runCatching {
            // 显式 Runnable：区分 executeOnPooledThread 的 Runnable/Callable 两个重载，避免推断歧义
            ApplicationManager.getApplication().executeOnPooledThread(Runnable { prewarmNow(project) })
        }.onFailure { LOG.warn("sql dialect mapping prewarm dispatch failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 同步执行预热，**只允许在 BGT 调用**（EDT 上调用等于把原问题照搬回来）。
     *
     * @return 是否真的做了预热工作（已预热 / 单测 / 无 SQL FileType 均返回 false）
     */
    fun prewarmNow(project: Project): Boolean {
        if (!needsWarm(project)) return false
        // 无 SQL FileType：没有方言映射可查（未装 Database 时本插件多处以该 FileType 为能力探针），
        // 记「无可预热」避免每次启动重复探测。
        val fileType = FileTypeManager.getInstance().getStdFileType("SQL") ?: run {
            markWarmed(project)
            return false
        }
        val startedAt = System.nanoTime()
        return try {
            ProgressManager.getInstance().runProcess({
                @Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                ReadAction.compute<Unit, Throwable> {
                    // 本句即触发 SqlDialectMappings 的一次性状态加载（见类头）；返回值只用于驱动加载
                    SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null)
                    Unit
                }
            }, EmptyProgressIndicator())
            markWarmed(project)
            LOG.info("sql dialect mapping prewarm done in ${(System.nanoTime() - startedAt) / 1_000_000}ms")
            true
        } catch (t: Throwable) {
            // 不置 warmed：下次仍有补齐机会；本次调用方照原逻辑继续。JVM 级致命错误不吞（T-FatalPassthrough）
            if (FatalErrors.isFatal(t)) throw t
            LOG.warn("sql dialect mapping prewarm failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * EDT 侧用法：派发到 BGT 并**有界**等待完成。
     *
     * 顺序是承重墙——调用点必须早于「创建 SQL 文件类型编辑器」，否则预热没赶上首查就没有意义。
     *
     * @return true=预热已完成；false=已预热过 / 不可预热 / 超时 / 失败（调用方都应按原逻辑继续）
     */
    @JvmOverloads // Java 调用点（SqlLogToolWindowFactory）免带超时实参
    fun prewarmAndAwait(project: Project, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (!needsWarm(project)) return false
        val future: java.util.concurrent.Future<Boolean> = try {
            ApplicationManager.getApplication().executeOnPooledThread(
                java.util.concurrent.Callable { prewarmNow(project) },
            )
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t
            LOG.warn("sql dialect mapping prewarm dispatch failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            LOG.warn("sql dialect mapping prewarm timed out after ${timeoutMs}ms; assembling anyway")
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t
            LOG.warn("sql dialect mapping prewarm failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** 测试缝：清掉某项目的预热标记（用例间隔离）。 */
    internal fun resetForTest(project: Project) {
        warmed.remove(project)
    }

    /** 测试缝：当前是否已记为「预热完成」。 */
    internal fun isWarmedForTest(project: Project): Boolean = warmed.contains(project)

    private fun needsWarm(project: Project): Boolean {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || project.isDisposed) return false
        return !warmed.contains(project)
    }

    private fun markWarmed(project: Project) {
        warmed.add(project)
    }
}