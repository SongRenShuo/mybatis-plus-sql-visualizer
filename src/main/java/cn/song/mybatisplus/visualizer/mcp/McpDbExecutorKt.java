// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpDbExecutor.kt 顶层函数段移植
package cn.song.mybatisplus.visualizer.mcp;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * warnings 取尽内核（T-NoticeChannel 纯逻辑层）：{@link WarningSurface} 每项 = (getAllWarnings 调用,
 * clearWarnings 调用) 二元组，反射接线在 {@link McpDbExecutor#warningSurfaces}——本核不碰反射
 * 与平台类型，裸 JUnit 可直测。顶层函数（非 object 成员）：测试加载本文件门面类时不必
 * 触发 {@link McpDbExecutor} 的 Logger 初始化。
 *
 * 全程吞异常降级：个别驱动对 warnings 抛 UnsupportedOperationException 时降级为不产出
 * 该面消息，绝不连坐查询结果；clearWarnings 失败同样不丢已取到的消息（下一条语句执行前的
 * pre-drain 兜底，DBX postgres.rs 同款两件套）。
 */
public final class McpDbExecutorKt {

    private McpDbExecutorKt() {
    }

/**
 * 通道层行预算兜底（T-McpRowBudget / T-MRB-04，O-36）：工具层已按
 * {@link McpExplainFormatter#clampMaxRows(Integer)} 钳制一次，本通道再钳一次——
 * {@link McpDbExecutor#executeQuery} 是「未来任何执行类工具共用」的通道（{@code McpSelectGuard}
 * KDoc 同款宣示），新增执行工具若漏钳制也不会突破 {@link McpExplainFormatter#HARD_MAX_ROWS}
 * （对齐 DBX「静态断言恒小于驱动上限」的护栏语义）。定案=<b>钳制不抛错</b>（越界静默收进合法区间，
 * 与 clampMaxTables 既有语义一致）。
 *
 * <p>顶层函数（非 object 成员）：测试加载本文件门面类时不必触发 {@link McpDbExecutor} 的 Logger 初始化。</p>
 */
public static int channelRowBudget(int requested) {
    return McpExplainFormatter.clampMaxRows(requested);
}

/**
 * T-PreviewChainNoise W2：Database 反射链的<b>阻塞上下文兜底</b>（顶层函数，非 object 成员
 * ——与 {@link #drainWarnings} 同款理由：测试加载本文件门面不必触发 {@link McpDbExecutor} 的 Logger 初始化）。
 *
 * <p>平台事实（243 sources {@code progress/coroutines.kt:138-143}）：{@code DatabaseConnectionManager$Builder
 * .createBlocking} 内部走 {@code runBlockingCancellable}，当前线程既无全局 ProgressIndicator 又无
 * Job（{@code executeOnPooledThread} 起的裸池线程即此形态）时打
 * {@code IllegalStateException: There is no ProgressIndicator or Job in this thread}。同一坑
 * {@code PreviewSideQuery} 已修（T-PreviewSideQuery），2026-10-23 用户真机日志证明
 * {@code ExplainPanelChannel.executeStructured} 是漏网第二处——故兜底下沉到唯一入口 {@link McpDbExecutor}，
 * 未来任何新调用点不再依赖各自记得包一层。</p>
 *
 * <p><b>已有指示器时不得替换</b>：{@code runProcess(EmptyProgressIndicator)} 会顶掉线程上真实指示器，
 * 外层取消（MCP 工具跑在协程里带 Job）随之失效——所以只在缺上下文时补空指示器
 * （查询本身不读取消状态：只读短查询，超时由驱动级 {@code setQueryTimeout} 护栏负责）。</p>
 */
public static <T> T withDbBlockingContext(java.util.function.Supplier<T> block) {
    com.intellij.openapi.progress.ProgressManager pm = com.intellij.openapi.progress.ProgressManager.getInstance();
    if (pm.getProgressIndicator() != null) {
        return block.get();
    }
    // 显式 Computable 局部变量：runProcess 有 Runnable/Computable 双重载，裸 lambda/method-ref 会有歧义
    com.intellij.openapi.util.Computable<T> computable = block::get;
    return pm.runProcess(
            computable,
            new com.intellij.openapi.progress.EmptyProgressIndicator());
}

/**
 * warnings 采集面二元组：getAllWarnings 产出原始消息清单（可空），clearWarnings 清空驱动缓冲。
 * Kotlin 侧为 {@code Pair<() -> List<*>?, () -> Unit>}，Java 侧以 record + 函数式接口同构表达。
 */
    public record WarningSurface(
        /** 取 warnings 原始清单（驱动反射接线；失败/为空可返回 null）。 */
        Supplier<List<?>> getAllWarnings,
        /** 清空 warnings（驱动反射接线）。 */
        Runnable clearWarnings
    ) {
    }

    /** warnings 取尽：语句级/连接级各面依次采集（每面采集后立刻 clear），过滤空白行后拼接。 */
    public static List<String> drainWarnings(WarningSurface... surfaces) {
        List<String> out = new ArrayList<>();
        for (WarningSurface surface : surfaces) {
            List<String> messages = new ArrayList<>();
            try {
                List<?> raw = surface.getAllWarnings().get();
                if (raw != null) {
                    for (Object w : raw) {
                        if (w == null) {
                            continue;
                        }
                        // 单条映射失败降级为丢弃该条（Kotlin runCatching.getOrNull 语义）
                        try {
                            // master 侧 mapNotNull：SQLWarning.message 可空（Throwable.message 为 String?），
                            // 映射结果为 null 即丢弃该条。原先无条件 add 会把 null 混进清单，
                            // 下方 isBlank 判定随即 NPE（测试的 SQLWarning(null as String?) 即此路径）。
                            String message = w instanceof SQLWarning sqlWarning ? sqlWarning.getMessage() : w.toString();
                            if (message != null) {
                                messages.add(message);
                            }
                        } catch (Throwable ignored) {
                            // 映射失败不产出该条
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 该面取 warnings 失败 → 降级为不产出该面消息（不连坐其他面）
            }
            try {
                surface.clearWarnings().run();
            } catch (Throwable ignored) {
                // clear 失败不丢已取到的消息
            }
            for (String message : messages) {
                if (!message.isBlank()) {
                    out.add(message);
                }
            }
        }
        return out;
    }
}
