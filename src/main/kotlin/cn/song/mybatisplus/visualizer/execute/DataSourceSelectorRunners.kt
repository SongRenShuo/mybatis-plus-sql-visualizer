package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.psi.PsiFile

/**
 * 原生 Runner 反射枚举（[DataSourceSelector] 家族独立件）。
 *
 * 为什么单独成文件：master 的 T-Exec262ReadLock 修复（`RunnerEnumeration` + `WriteIntentReadAction`
 * 包裹 + 三态归因）随合流带进 dev 后，`DataSourceSelector.kt` 从 2000 涨到 2068，越过
 * `GiantFileBudgetTest` 的 2000 行红线。按仓内定例「合流推翻行数预算的正解是继续拆，不是提额」，
 * 把「平台 Runner 反射枚举」这一整块独立出来——它与控制台装配、准备链、last-used 记忆都不耦合，
 * 只依赖平台类名常量与反射入口。
 *
 * 可见性说明：`RUNNERS_CLASS` 原为 `DataSourceSelector` 内的 `private const`（private 是文件级作用域，
 * 扩展函数在另一个文件里看不到），故一并搬到本文件；`SESSION_CLASS` 仍是 `internal const`，
 * 在扩展函数体内经 dispatch receiver 直接可见。
 */

/** DatabaseRunners 类名（反射入口）。 */
private const val RUNNERS_CLASS = "com.intellij.database.console.DatabaseRunners"

/**
 * 反射调用 DatabaseRunners.getAttachDataSourceRunners 获取原生 Runner 列表。
 * [sessionConsumer] 在选中 runner 执行后收到 DatabaseSession。
 *
 * T-ExplainSwitchNative W8（2026-10-24 用户实测「点击切换数据源只有一个数据源？当前可是
 * 配置了很多」）：**用户主动切换**时优先调平台切换专用入口 `getSwitchDataSourceRunners`，
 * 失败（含签名/无会话）则回退 attach 版。
 *
 * ⚠️ W8-hotfix（同日）：`getSwitchDataSourceRunners` 的签名是
 * **`(PsiFile, String, DatabaseSession, Consumer)` 四参**（javap 实证），比 attach 版多一个
 * 「当前会话」——首版按三参反射调用必然 `NoSuchMethodException`，被 runCatching 吞成空列表
 * → `runners=0` → 用户看到「没有与该 SQL 方言匹配的已配置数据源」红条（切换数据源点了就报错）。
 * 现按真实签名调用；[currentSession] 为 null（预览尚未建会话，切换入口的常态）时**直接用
 * attach 版**——这正是切换入口此前一直正常工作的形态。
 *
 * @param allDataSources 用户主动切换时为 true（尝试切换专用入口）
 * @param currentSession 当前已 attach 的会话（无则走 attach 版）
 */
internal fun DataSourceSelector.getAttachDataSourceRunners(
    psiFile: PsiFile,
    sql: String,
    sessionConsumer: com.intellij.util.Consumer<Any>,
    allDataSources: Boolean = false,
    currentSession: Any? = null,
): List<Any> =
    enumerateDataSourceRunners(psiFile, sql, sessionConsumer, allDataSources, currentSession).runners

/**
 * Runner 枚举详情（T-Exec262ReadLock 观察缝）：[runners] + 平台调用异常 [failure]（未抛为 null）。
 *
 * 归因分流用：runners 为空时，「平台调用抛异常」与「平台正常返回零个可用源」是两回事——
 * 前者是插件/平台缺陷（给「调用 IDE 数据库接口失败」+ 异常首行），后者才是方言不匹配或
 * 数据源不可连接（既有文案）。旧实现把两者都吞成空列表，用户被误导去改 Dialects 设置
 * （2026-11-02 用户实测现场）。
 */
internal class RunnerEnumeration(val runners: List<Any>, val failure: Throwable?)

/**
 * 枚举原生 Runner（含平台调用异常详情），[getAttachDataSourceRunners] 的详情版。
 *
 * T-Exec262ReadLock A（2026-11-02 用户日常 IDEA 2026.2 实测「执行 SQL 报方言不匹配」）：
 * 262 起 EDT 不再隐式持有读锁，平台 `DatabaseRunners.getAttachDataSourceRunners` →
 * `DbImplUtil.getForcedDataSourceForExec` → `DataSourcePerFileMappingsImpl.getMapping`
 * 内部的读访问断言硬抛 `Read access is allowed from inside read-action only`（idea.log
 * 4605 行完整栈）；runCatching 吞成空列表 → `runners=0` → 误报方言不匹配。
 * 平台链内部会 attach 控制台（写意图），故按 AGENTS.md 口径包 `WriteIntentReadAction.compute`
 * 而非 ReadAction（ReadAction 内开 WIRA 会硬抛）。**重载选型（javap 双平台实证）**：
 * `compute(ThrowableComputable)` 仅 243 有——262 把它改名 `computeThrowable`（`run(ThrowableRunnable)`
 * 同步改名 `runThrowable`），编译期引用在 262 运行时直接 `NoSuchMethodError`（2026-11-02
 * 用户真机二次实测）；`compute(Computable)` 为 243~262 交集，且 Kotlin lambda 抛受检异常
 * 在 JVM 字节码层合法（Kotlin 无受检异常检查），`InvocationTargetException` 照常穿透由外层
 * `runCatching` 捕获，语义与 ThrowableComputable 形态一致。同文件 `RollbackSqlDialog.kt`
 * 的既有用法即单类型实参形态。
 */
internal fun DataSourceSelector.enumerateDataSourceRunners(
    psiFile: PsiFile,
    sql: String,
    sessionConsumer: com.intellij.util.Consumer<Any>,
    allDataSources: Boolean = false,
    currentSession: Any? = null,
): RunnerEnumeration {
    val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)
    val attach: () -> RunnerEnumeration = {
        var failure: Throwable? = null
        val runners = runCatching {
            val clazz = Class.forName(RUNNERS_CLASS)
            val method = clazz.getMethod(
                "getAttachDataSourceRunners",
                PsiFile::class.java,
                String::class.java,
                com.intellij.util.Consumer::class.java,
            )
            val result = WriteIntentReadAction.compute<Any?> {
                method.invoke(null, psiFile, sql, sessionConsumer)
            }
            (result as? List<*>)?.filterNotNull() ?: emptyList()
        }.onFailure { e ->
            failure = e
            LOG.warn("[M36] getAttachDataSourceRunners reflection FAILED: $e", e)
        }.getOrDefault(emptyList())
        RunnerEnumeration(runners, failure)
    }
    if (!allDataSources || currentSession == null) return attach()
    // 切换专用入口：四参（多 currentSession）；任何失败都回退 attach 版，绝不返回空。
    val switched = runCatching {
        val clazz = Class.forName(RUNNERS_CLASS)
        val method = clazz.getMethod(
            "getSwitchDataSourceRunners",
            PsiFile::class.java,
            String::class.java,
            Class.forName(SESSION_CLASS),
            com.intellij.util.Consumer::class.java,
        )
        val result = WriteIntentReadAction.compute<Any?> {
            method.invoke(null, psiFile, sql, currentSession, sessionConsumer)
        }
        (result as? List<*>)?.filterNotNull() ?: emptyList()
    }.onFailure { e ->
        LOG.warn("[M36] getSwitchDataSourceRunners reflection FAILED (fallback to attach): $e", e)
    }.getOrNull() ?: return attach()
    return if (switched.isEmpty()) attach() else RunnerEnumeration(switched, null)
}
