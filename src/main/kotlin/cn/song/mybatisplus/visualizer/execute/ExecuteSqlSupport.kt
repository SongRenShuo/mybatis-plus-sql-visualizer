package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * 执行 SQL 支持（M17）：SQL 预览面板「执行 SQL」按钮的 core 入口。
 *
 * Community 兼容（M17-R5）：`com.intellij.database`（数据源/控制台）**仅 IntelliJ IDEA Ultimate /
 * DataGrip 捆绑**——本类与面板**零直接引用** database 类；实际实现
 * [cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher] 经**反射**加载：
 * launcher 字节码位于主 jar，但仅当 database 插件已加载时才被 JVM 解析（Community 下永不触碰，
 * 无 NoClassDefFoundError）。
 *
 * 流程（Ultimate，M19）：创建临时 .sql 文件（含当前 SQL）→ 打开编辑器（Database 插件
 * SQL 编辑器右上角**数据源选择器**由用户自行选择数据源，Ctrl+Enter 执行——IDE 原生行为）。
 */
object ExecuteSqlSupport {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ExecuteSqlSupport::class.java)

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private const val NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview"

    /** Database 插件标记类（插件加载时必在 classpath；探测用，版本无关——绕开 PluginId API 迁移）。 */
    private const val DATABASE_MARKER_CLASS = "com.intellij.database.dataSource.LocalDataSourceManager"

    /** 隔离实现类（仅 database 插件存在时反射加载）。 */
    private const val LAUNCHER_CLASS = "cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher"

    /**
     * database 插件是否已加载（Ultimate/DataGrip）：`Class.forName` 探测标记类——
     * 不依赖 `PluginManagerCore.isPluginInstalled`/`PluginId` 的版本形态（该 API 在 2025.3~2026.2
     * 间有包迁移），Community/测试沙盒（jar 已排除）下返回 false。
     */
    @JvmStatic
    fun isAvailable(): Boolean = runCatching {
        Class.forName(DATABASE_MARKER_CLASS)
        true
    }.getOrDefault(false)

    /**
     * UX Community 执行入口预判：不可用时的说明文案（tooltip）；可用返回 null。
     *
     * 立项动因：Community 版「执行 SQL」按钮此前**看起来可点**，点了才弹一条「需要 Ultimate」的
     * 轻提示——用户白点一次才知道不可用。执行入口在构造期即按 [isAvailable] 置灰，tooltip 直接
     * 说明原因与替代路径（预览/复制不受影响），把「点了才报错」提前成「一眼就知道」。
     *
     * 只读、幂等、零副作用（`Class.forName` 探测结果在进程内稳定）。
     */
    @JvmStatic
    fun unavailableHint(): String? =
        if (isAvailable()) null else MpSqlVisualizerBundle.message("execute.unavailable.tooltip")

    /**
     * 执行入口：不可用 → 提示升级 Ultimate；可用 → 反射调用 launcher。
     * 所有 UI 提示均 runCatching 兜底（测试/headless 环境不抛异常）。
     *
     * @param project      当前项目（面板无项目时不调用本方法）
     * @param sql          当前编辑器 SQL（含参数回填与手动编辑）
     * @param onClosePanel T-M23.6：成功提示「关闭 SQL 面板」按钮回调（预览弹窗关闭）；
     *                     null → 成功提示维持单 OK（非预览弹窗入口零影响）
     */
    @JvmStatic
    fun execute(project: Project, sql: String, onClosePanel: (() -> Unit)? = null) {
        if (!isAvailable()) {
            showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"))
            return
        }
        runCatching {
            // T-M23.6：第三参 Runnable 透传「关闭 SQL 面板」回调（反射方法签名与 launcher 一致）
            val closeRunnable = onClosePanel?.let { Runnable { it() } }
            val method = Class.forName(LAUNCHER_CLASS)
                .getMethod("execute", Project::class.java, String::class.java, Runnable::class.java)
            method.invoke(null, project, sql, closeRunnable)
        }.onFailure { error ->
            // T-Perf-01：反射加载失败静默降级（LOG.warn 记录详情，不弹模态框——调用方 onError 已在面板内显示错误）
            LOG.warn("[M36] launcher execute reflection FAILED: ${error.message}", error)
        }
    }

    /**
     * T-UiNative T-UN-02：Community 降级提示改轻通知（原模态弹窗——纯告知无需打断）。
     * 测试模式跳过 + runCatching 兜底（headless 无通知系统依赖，原 Messages 亦被 runCatching 吞掉）。
     */
    private fun showInfo(project: Project, message: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            Notification(
                NOTIFICATION_GROUP_ID,
                MpSqlVisualizerBundle.message("execute.title"),
                message,
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }
}
