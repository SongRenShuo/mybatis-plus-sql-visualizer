package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-ExecSlowOps F1：SQL 方言映射（SqlDialectMappings）公共编排——反射收编自
 * `SqlPreviewPanel.mapPreviewDialectBeforePsi` 与 `DataSourceSelector.mapDialectBeforePsi` 两处同构代码。
 *
 * 线程归属双实证（262 真机堆栈 + javap）：
 *  - `setMapping` 必须在 EDT——平台内部 `PerFileMappingsBase.setMapping → handleMappingChange →
 *    PsiDocumentManagerImpl.reparseFiles → runWriteAction → beforeVfsChange` 消息总线走到
 *    `VcsVFSListener.beforeContentsChange:671` 的 `assertEventDispatchThread` 硬断言；
 *    BGT 直调抛 RuntimeExceptionWithAttachments（SEVERE ×3 实录，且 VcsVFSListener
 *    before/after 事件对失衡）。平台原生 ChangeDialectAction 全程 EDT 调用，同款约束。
 *  - 只读探测 `getConfiguredMapping(VirtualFile)` 无 EDT 限制（243 app-client.jar /
 *    262 intellij.platform.analysis.impl.jar 双版 javap 实锤 public 签名），可在 BGT
 *    ReadAction 内安全执行。
 *
 * 于是形成两段式：[ensureMapped]（后台线程入口）先读探测——稳态命中
 * （workspace.xml 持久映射在 / 本进程已设过）直接跳过，零断言零 EDT 占用；仅项目首见
 * 经 invokeLater 派发 [setMySqlOnEdt] 并 CountDownLatch 有界等待（BGT 等 EDT，EDT 永不
 * 反向阻塞——无互锁面）。慢操作（refresh/findFile）归 BGT、映射写归 EDT，两侧 SEVERE 同时消除。
 */
internal object DialectPreMapping {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DialectPreMapping::class.java)

    /** EDT 映射派发后的有界等待预算（毫秒）——EDT 冻结的病态场景超时放行，findFile 照跑（最差等价旧行为）。 */
    private const val EDT_SET_MAPPING_WAIT_MS = 2000L

    /**
     * 判定能否跳过 setMapping（纯函数，表驱动单测）：
     *  - 进程内幂等短路命中（同 vf 已设过）→ 跳过；
     *  - 读探测确认已配置为目标方言（O-136 泛化：MySQL 目标即「已配置为 MySQL」，含 IDE 重启后
     *    workspace.xml 持久映射幸存；非 MySQL 目标即「已配置为该目标」）→ 跳过；
     *  - 探测失败（null）保守不跳过——宁可多设一次同值映射，不冒 GenericSQL PSI 静默失败的险（M36.5.3）。
     */
    internal fun shouldSkip(inProcessMapped: Boolean, configuredIsMySql: Boolean?): Boolean =
        inProcessMapped || configuredIsMySql == true

    /**
     * 只读探测 vf 的文件级方言映射是否已显式配置为 MySQL。null = 反射/环境异常（无法判定）。
     * 须在 ReadAction 内调用（[ensureMapped] 已代包；EDT 调用方自行保证）。
     */
    internal fun configuredAsMySql(project: Project, vf: VirtualFile): Boolean? = runCatching {
        val mappings = instanceOf(project) ?: return null
        val mysql = findMySql(mappings) ?: return null
        val configured = mappings.javaClass
            .getMethod("getConfiguredMapping", VirtualFile::class.java)
            .invoke(mappings, vf)
        (configured as? com.intellij.lang.Language)?.id?.equals("MySQL", ignoreCase = true) ?: false
    }.getOrNull()

    /**
     * O-136：只读探测 vf 当前配置方言的 language id（null = 未配置/反射失败，无法判定）。
     * 纯读（PerFileMappings 内存表查询），ReadAction 内或 EDT 均可调用。
     */
    internal fun configuredLanguageId(project: Project, vf: VirtualFile): String? = runCatching {
        val mappings = instanceOf(project) ?: return null
        val configured = mappings.javaClass
            .getMethod("getConfiguredMapping", VirtualFile::class.java)
            .invoke(mappings, vf)
        (configured as? com.intellij.lang.Language)?.id
    }.getOrNull()

    /** O-136：按 language id 在 SqlDialectMappings 可用值里找方言 Language 实例（忽略大小写；失败 null）。 */
    internal fun languageById(project: Project, id: String): Any? = runCatching {
        val mappings = instanceOf(project) ?: return null
        @Suppress("UNCHECKED_CAST")
        val values = mappings.javaClass.getMethod("getAvailableValues").invoke(mappings) as? List<Any?>
        values?.firstOrNull { (it as? com.intellij.lang.Language)?.id.equals(id, ignoreCase = true) }
    }.getOrNull()

    /**
     * T-DialectInferFix：**继承值**读取（项目级 → 全局默认），不含本目录自己的显式映射。
     *
     * 与 [configuredLanguageId] 的区别：后者走 `getConfiguredMapping`（文件/目录优先），会把插件
     * 历史上写给预览目录的兼容兜底也读出来——而那条正是要覆盖掉的旧值。这里从**项目根**读
     * 继承链，拿不到再退 `SqlDialectMappings.getConfiguredDefaultSqlDialect()`（设置页「全局 SQL 方言」，
     * 243/253/262/2026.2 均为 public static，本仓既有反射口径同族）。
     */
    /** T-DialectInferFix：项目根继承值 → 全局默认（无需 VirtualFile，渲染侧与映射侧共用同一口径）。 */
    internal fun inheritedLanguageId(project: Project): String? = runCatching {
        val mappings = instanceOf(project) ?: return null
        val root = project.basePath
            ?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
        if (root != null) {
            val inherited = mappings.javaClass
                .getMethod("getConfiguredMapping", VirtualFile::class.java)
                .invoke(mappings, root)
            (inherited as? com.intellij.lang.Language)?.id?.takeIf { it.isNotBlank() }?.let { return it }
        }
        defaultLanguageId(project)
    }.getOrNull()


    /** T-DialectInferFix：平台全局默认 SQL 方言的 language id（用户设置页「全局 SQL 方言」）。 */
    internal fun defaultLanguageId(project: Project): String? = runCatching {
        val dialect = Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
            .getMethod("getConfiguredDefaultSqlDialect")
            .invoke(null)
        (dialect as? com.intellij.lang.Language)?.id
    }.getOrNull()

    /** T-DialectInferFix：本文件/目录**自有**映射的 language id（无则 null；不含继承）。 */
    internal fun configuredImmediateLanguageId(project: Project, vf: VirtualFile): String? = runCatching {
        val mappings = instanceOf(project) ?: return null
        val immediate = mappings.javaClass
            .getMethod("getImmediateMapping", VirtualFile::class.java)
            .invoke(mappings, vf)
        (immediate as? com.intellij.lang.Language)?.id
    }.getOrNull()

    /**
     * T-DialectInferFix：清除插件自己写在预览目录上的显式映射（`setMapping(vf, null)`）。
     *
     * 线程口径与 [ensureMapped] 完全一致：写映射必须 EDT（`VcsVFSListener` 断言），后台线程
     * 派发 + 有界等待。失败只 warn，不影响预览继续（最差保留旧映射）。
     */
    internal fun clearMapping(project: Project, vf: VirtualFile, waitMs: Long = EDT_SET_MAPPING_WAIT_MS): Boolean {
        val clearOnEdt = {
            runCatching {
                val mappings = instanceOf(project) ?: return@runCatching false
                mappings.javaClass.getMethod(
                    "setMapping", VirtualFile::class.java, Any::class.java,
                ).invoke(mappings, vf, null)
                true
            }.getOrElse { e ->
                LOG.warn("[M36] dialect clearMapping skipped: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) return clearOnEdt()
        val done = CountDownLatch(1)
        var cleared = false
        ApplicationManager.getApplication().invokeLater {
            try {
                cleared = clearOnEdt()
            } finally {
                done.countDown()
            }
        }
        return runCatching { done.await(waitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false) && cleared
    }

    /**
     * EDT 执行 setMapping(vf, MySQL)——平台线程断言见类 KDoc，非 EDT 调用直接拒绝并 warn
     * （防御网：调用方编排错误不得静默退化为 BGT 直调）。
     */
    internal fun setMySqlOnEdt(project: Project, vf: VirtualFile): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            LOG.warn("[M36] setMySqlOnEdt called off EDT — refused (VcsVFSListener EDT assert, T-ExecSlowOps F1)")
            return false
        }
        return runCatching {
            val mappings = instanceOf(project) ?: return false
            val mysql = findMySql(mappings) ?: run {
                LOG.warn("[M36] MySQL language dialect not found in SqlDialectMappings (fallback to pusher)")
                return false
            }
            mappings.javaClass.getMethod(
                "setMapping",
                VirtualFile::class.java,
                Any::class.java,
            ).invoke(mappings, vf, mysql)
            true
        }.getOrElse { e ->
            LOG.warn("[M36] dialect setMapping skipped: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * O-136：EDT 执行 setMapping(vf, targetLanguage)——与 [setMySqlOnEdt] 同一线程断言与降级口径，
     * 方言实例由调用方解析（[ExecDialectRouting.resolveTargetLanguage]，SqlDialectMappings 可用值家族）。
     */
    internal fun setLanguageOnEdt(project: Project, vf: VirtualFile, targetLanguage: Any): Boolean {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            LOG.warn("[M36] setLanguageOnEdt called off EDT — refused (VcsVFSListener EDT assert, T-ExecSlowOps F1)")
            return false
        }
        return runCatching {
            val mappings = instanceOf(project) ?: return false
            mappings.javaClass.getMethod(
                "setMapping",
                VirtualFile::class.java,
                Any::class.java,
            ).invoke(mappings, vf, targetLanguage)
            true
        }.getOrElse { e ->
            LOG.warn("[M36] dialect setMapping skipped: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 后台线程两段式入口（MySQL 目标，O-136 前的既有语义保持）：[shouldSkip] 判定（读探测包
     * ReadAction，BGT 合法）→ 命中即返；未命中 invokeLater 派发 EDT 映射 + 有界等待（**禁止在
     * EDT 调用本函数**——误入则退化为同步 [setMySqlOnEdt]，语义仍正确）。返回映射是否已就位；
     * false 不阻断调用方 findFile（最差 = GenericSQL PSI 旧行为）。
     */
    internal fun ensureMapped(
        project: Project,
        vf: VirtualFile,
        inProcessMapped: Boolean,
        waitMs: Long = EDT_SET_MAPPING_WAIT_MS,
    ): Boolean = ensureMapped(project, vf, null, inProcessMapped, waitMs)

    /**
     * O-136 主入口：[targetLanguage] 非 null 时按该方言（数据源方言路由，[ExecDialectRouting]
     * 解析）映射；null 保持 MySQL 旧行为。两段式语义与上同——BGT 读探测稳态跳过（当前配置
     * 已等于目标方言，含 DBN SqlDialectPusher 已推成目标方言的自愈场景）、未命中派 EDT 写。
     */
    internal fun ensureMapped(
        project: Project,
        vf: VirtualFile,
        targetLanguage: Any?,
        inProcessMapped: Boolean,
        waitMs: Long = EDT_SET_MAPPING_WAIT_MS,
    ): Boolean {
        val targetId = (targetLanguage as? com.intellij.lang.Language)?.id
        if (ApplicationManager.getApplication().isDispatchThread) {
            if (inProcessMapped) return true
            return if (targetLanguage != null) setLanguageOnEdt(project, vf, targetLanguage)
            else setMySqlOnEdt(project, vf)
        }
        val configured = runCatching {
            ReadAction.compute<String?, Throwable> { configuredLanguageId(project, vf) }
        }.getOrNull()
        val configuredMatches = when {
            configured == null -> null
            targetId == null -> configured.equals(ExecDialectRouting.MYSQL_LANGUAGE_ID, ignoreCase = true)
            else -> configured.equals(targetId, ignoreCase = true)
        }
        if (shouldSkip(inProcessMapped, configuredMatches)) return true
        val done = CountDownLatch(1)
        var mapped = false
        ApplicationManager.getApplication().invokeLater {
            try {
                mapped = if (targetLanguage != null) setLanguageOnEdt(project, vf, targetLanguage)
                else setMySqlOnEdt(project, vf)
            } finally {
                done.countDown()
            }
        }
        val signaled = runCatching { done.await(waitMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!signaled) {
            LOG.warn("[M36] dialect EDT mapping dispatch timed out after ${waitMs}ms (EDT busy?), continue findFile")
            return false
        }
        return mapped
    }

    private fun instanceOf(project: Project): Any? = runCatching {
        val mappingsClass = Class.forName("com.intellij.sql.dialects.SqlDialectMappings")
        mappingsClass.getMethod("getInstance", Project::class.java).invoke(null, project)
    }.getOrNull()

    private fun findMySql(mappings: Any): Any? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val values = mappings.javaClass.getMethod("getAvailableValues").invoke(mappings) as? List<Any?>
        values?.firstOrNull {
            (it as? com.intellij.lang.Language)?.id.equals("MySQL", ignoreCase = true)
        }
    }.getOrNull()
}
