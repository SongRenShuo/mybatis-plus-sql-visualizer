package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.share.format.ExecutableSqlFormatter;
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot;
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues;
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 执行 SQL 支持（M17）：SQL 预览面板「执行 SQL」按钮的 core 入口。
 *
 * Community 兼容（M17-R5）：`com.intellij.database`（数据源/控制台）仅 IntelliJ IDEA Ultimate /
 * DataGrip 捆绑——本类与面板零直接引用 database 类；实际实现
 * [cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher] 经反射加载：
 * launcher 字节码位于主 jar，但仅当 database 插件已加载时才被 JVM 解析（Community 下永不触碰）。
 *
 * 流程（Ultimate，M19）：创建临时 .sql 文件（含当前 SQL）→ 打开编辑器（Database 插件
 * SQL 编辑器右上角数据源选择器由用户自行选择数据源，Ctrl+Enter 执行——IDE 原生行为）。
 */
public final class ExecuteSqlSupport {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(ExecuteSqlSupport.class);

    /** T-UiNative T-UN-02：轻提示通知组（plugin.xml 已注册）。 */
    private static final String NOTIFICATION_GROUP_ID = "cn.song.mybatisplus.visualizer.preview";

    /** Database 插件标记类（插件加载时必在 classpath；探测用，版本无关）。 */
    private static final String DATABASE_MARKER_CLASS = "com.intellij.database.dataSource.LocalDataSourceManager";

    /** 隔离实现类（仅 database 插件存在时反射加载）。 */
    private static final String LAUNCHER_CLASS = "cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher";

    public static final ExecuteSqlSupport INSTANCE = new ExecuteSqlSupport();
    private ExecuteSqlSupport() {}

    /**
     * database 插件是否已加载（Ultimate/DataGrip）。
     */
    public static boolean isAvailable() {
        try {
            Class.forName(DATABASE_MARKER_CLASS);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 执行入口：不可用 → 提示升级 Ultimate；可用 → 反射调用 launcher。
     * 所有 UI 提示均 runCatching 兜底（测试/headless 环境不抛异常）。
     *
     * @param project      当前项目（面板无项目时不调用本方法）
     * @param sql          当前编辑器 SQL（含参数回填与手动编辑）
     * @param onClosePanel 成功提示「关闭 SQL 面板」按钮回调（预览弹窗关闭）；null = 成功提示维持单 OK
     */
    public static void execute(@NotNull Project project, @NotNull String sql) {
        execute(project, sql, null);
    }

    public static void execute(@NotNull Project project, @NotNull String sql, @Nullable Runnable onClosePanel) {
        if (!isAvailable()) {
            showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"));
            return;
        }
        try {
            // 第三参 Runnable 透传「关闭 SQL 面板」回调（反射方法签名与 launcher 一致）
            Runnable closeRunnable = onClosePanel;
            Method method = Class.forName(LAUNCHER_CLASS)
                    .getMethod("execute", Project.class, String.class, Runnable.class);
            method.invoke(null, project, sql, closeRunnable);
        } catch (Throwable error) {
            // T-Perf-01：反射加载失败静默降级（LOG.warn 记录详情，不弹模态框——调用方 onError 已在面板内显示错误）
            LOG.warn("[M36] launcher execute reflection FAILED: " + error.getMessage(), error);
        }
    }

    /**
     * T-RuntimeSlotFill 券 B：把运行期占位填进待执行文本（执行前的<b>唯一</b>回填口）。
     *
     * <p>执行链与复制/分享/导出链共用 {@link ExecutableSqlFormatter}，保证「预览里看到的」与
     * 「真正执行的」是同一份文本。占位为空时逐字节原样返回。</p>
     *
     * @param sql        当前编辑器 SQL（参数已回填、含运行期占位注释）
     * @param slots      运行期占位表
     * @param slotValues 占位值（空表 = 占位原样保留，执行会失败——这是既有的「不替你猜」口径）
     * @param dialectName 当前方言名（布尔字面量判定）
     */
    public static String renderForExecution(
            @NotNull String sql,
            @NotNull List<RuntimeSlot> slots,
            @NotNull RuntimeSlotValues slotValues,
            @Nullable String dialectName) {
        // Kotlin 具名实参 + 默认参（hints/namedHints 缺省空表）→ 位置实参全参形态
        return ExecutableSqlFormatter.format(sql, List.of(), Map.of(), List.of(), slots, slotValues, Map.of(), dialectName);
    }

    /** Kotlin 默认参等价重载：slots/slotValues/dialectName 缺省。 */
    public static String renderForExecution(@NotNull String sql) {
        return renderForExecution(sql, List.of(), RuntimeSlotValues.EMPTY, null);
    }

    /**
     * 带运行期占位的执行入口：先回填占位，其余与 {@link #execute} 完全一致。
     *
     * <p>单独开一个名字而不是给 {@link #execute} 加重载——既有调用点（面板、测试）签名不变，
     * 也就不会出现重载解析歧义。</p>
     */
    public static void executeWithRuntimeSlots(
            @NotNull Project project,
            @NotNull String sql,
            @NotNull List<RuntimeSlot> slots,
            @NotNull RuntimeSlotValues slotValues,
            @Nullable String dialectName,
            @Nullable Runnable onClosePanel) {
        execute(project, renderForExecution(sql, slots, slotValues, dialectName), onClosePanel);
    }

    /** Kotlin 默认参等价重载：dialectName/onClosePanel 缺省。 */
    public static void executeWithRuntimeSlots(
            @NotNull Project project,
            @NotNull String sql,
            @NotNull List<RuntimeSlot> slots,
            @NotNull RuntimeSlotValues slotValues) {
        executeWithRuntimeSlots(project, sql, slots, slotValues, null, null);
    }

    /**
     * T-UiNative T-UN-02：Community 降级提示改轻通知（原模态弹窗——纯告知无需打断）。
     * 测试模式跳过 + runCatching 兜底（headless 无通知系统依赖，原 Messages 亦被 runCatching 吞掉）。
     */
    private static void showInfo(@NotNull Project project, @NotNull String message) {
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        try {
            new Notification(
                    NOTIFICATION_GROUP_ID,
                    MpSqlVisualizerBundle.message("execute.title"),
                    message,
                    NotificationType.INFORMATION
            ).notify(project);
        } catch (Throwable ignored) {
            // headless 无通知系统依赖，与原 runCatching 等价
        }
    }
}
