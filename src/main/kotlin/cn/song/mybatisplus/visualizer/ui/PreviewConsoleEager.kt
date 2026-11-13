package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.registry.Registry

/**
 * T-PreviewExecWriteDefer：**预览打开时是否「立即准备执行控制台」**的单一开关。
 *
 * ## 为什么需要这个开关（证据）
 *
 * 用户 00:10~00:11 沙盒 A/B：同一语句连开两次预览——
 * - 第 1 次：`exec file updated via document+save` → 2s 内 `enter dumb mode`
 *   （dumb 任务 = `PushedFilePropertiesUpdater`，随后 `Reparsing …/mpsql-exec.sql because of
 *   language substitution`）；
 * - 第 2 次：整条准备链被跳过、**不写文件** → **没有 dumb**。
 *
 * 结论：**预览打开时"准备控制台"这一步会写 `mpsql-exec.sql`**，而该文件带着方言映射（语言被替换），
 * 平台在内容变化时要重算替换语言并推文件属性（需要索引）⇒ 整个工程进 dumb；同一链还会在 EDT 上
 * 复活该文件的 PSI（`SlowOperations … FileManagerImpl.shouldResurrect → shouldCreatePSI`，SEVERE，
 * 点名本插件）。两个症状同一个成因。
 *
 * ## 默认口径
 *
 * `false`（惰性）：预览打开**不**准备控制台、**不**预热 exec 文件；控制台在用户第一次执行/切换数据源时
 * 由执行链（`selectAndExecute`）自行「准备 + 同步 exec 文件 + 执行」——那是既有行为，无需额外接线。
 *
 * `true`（旧行为）：预览打开即准备控制台（Ultimate 立刻显示原生控制台），代价是上面那两个症状。
 *
 * Registry：`sqlvis.preview.eagerConsole`。单测/沙盒默认走旧行为（`isUnitTestMode`），避免既有用例
 * 大面积改动；要验惰性分支请显式置 [enabledForTest]。
 */
internal object PreviewConsoleEager {

    /** Registry 开关（plugin.xml `<registryKey>` 登记；缺失时按默认值 false＝惰性）。 */
    internal const val REGISTRY_KEY = "sqlvis.preview.eagerConsole"

    /** 测试缝：null＝按环境判定（单测＝eager，生产＝Registry）。 */
    internal var enabledForTest: Boolean? = null

    /** 惰性延后次数（守卫读数：预览打开真的走了「不准备控制台」分支）。 */
    internal val deferredCount = java.util.concurrent.atomic.AtomicInteger()

    internal fun recordDeferred() {
        deferredCount.incrementAndGet()
    }

    internal fun resetForTest() {
        enabledForTest = null
        deferredCount.set(0)
    }

    internal fun enabled(): Boolean =
        enabledForTest ?: runCatching {
            ApplicationManager.getApplication().isUnitTestMode ||
                Registry.`is`(REGISTRY_KEY, false)
        }.getOrDefault(true)
}
