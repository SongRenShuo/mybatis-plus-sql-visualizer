package cn.song.mybatisplus.visualizer.execute;

import cn.song.mybatisplus.visualizer.mcp.McpDbExecutor;
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Consumer;

/**
 * R33：面板 EXPLAIN 结构化渲染通道（自建执行链路线，批次 9 核实报告 §三 V41 定案）。
 *
 * <p>现状（V41 锚点）：{@code SqlPreviewPanel.explainSql} 把包装后的 EXPLAIN 直送原生 Database 控制台，
 * 插件对结果区仅做组件换入（{@code embedConsole}），<b>无行列级程序化读取面</b>——截获控制台输出不可行
 * （网格由 DBN 托管，硬读=侵入平台内部模型，版本脆弱）。本通道复用 MCP 链既有的自持执行链
 * （{@link McpDbExecutor} 反射建连执行，243/262 签名核验在案），把行列文本交给面板侧结构化渲染。</p>
 *
 * <p><b>双通道并存（保守增量红线）</b>：本通道不是 EXPLAIN 的唯一路径——判定/执行/渲染任一环节
 * 不成立都 fail-closed 回退既有直送控制台通道（{@link Decision.DirectSilent} = 设计内直送不提示；
 * {@link Decision.DirectFallback} = 结构化预期成立但环境不给，落回直送通道并给一条轻提示，不弹窗）。</p>
 *
 * <p>线程纪律：{@link #decide} 在 EDT 调用（只读布尔判定）；{@link #executeStructured} 的 JDBC 全程在
 * pooled 线程（JDBC 禁 EDT，循 McpDbExecutor 既有线程形态），结果经 EDT 回调交付。</p>
 *
 * <p>移植注记：master 侧为 {@code object ExplainPanelChannel}，本类落为全 static 工具类（k2j 既有
 * object 承载惯例），并保留 {@code INSTANCE} 以对齐 object 语义。</p>
 */
public final class ExplainPanelChannel {

    public static final ExplainPanelChannel INSTANCE = new ExplainPanelChannel();

    private ExplainPanelChannel() {
    }

    /** 结构化渲染认得的输出格式：仅 MySQL 家族表格行与 PG 家族单列 JSON 文本。 */
    private static final Set<ExplainOutputFormat> STRUCTURED_FORMATS =
            Set.of(ExplainOutputFormat.TABLE, ExplainOutputFormat.JSON);

    /**
     * T-UI030：该输出格式是否有结构化渲染面（TEXT/XML/未知一律无——不硬解析红线）。
     * 供「开关关闭时是否值得提示启用」复用同一份格式白名单，避免第二处硬编码。
     */
    public static boolean supportsStructuredFormat(@Nullable ExplainOutputFormat format) {
        return format != null && STRUCTURED_FORMATS.contains(format);
    }

    /** 通道判定结果（三类可区分——提示文案不同，合并会让用户拿到错的解释，ExplainGate 同款口径）。 */
    public sealed interface Decision permits Decision.Structured, Decision.DirectSilent, Decision.DirectFallback {

        /** 走结构化通道（自建执行链 + 面板渲染）。 */
        final class Structured implements Decision {
            public static final Structured INSTANCE = new Structured();

            private Structured() {
            }
        }

        /** 设计内直送（开关关 / 方言格式非结构化档 TEXT/XML/未知）——既有主路径，不给提示。 */
        final class DirectSilent implements Decision {
            public static final DirectSilent INSTANCE = new DirectSilent();

            private DirectSilent() {
            }
        }

        /** 环境性回退：结构化预期成立但环境不可用——落回直送通道并附轻提示（{@link #reason()} 可区分）。 */
        record DirectFallback(FallbackReason reason) implements Decision {
        }
    }

    /** 回退理由（轻提示按理由给可操作的措辞）。 */
    public enum FallbackReason {
        /** 预览会话没有已绑数据源（用户还没在控制台里选过数据源）。 */
        NO_DATA_SOURCE,

        /** Community / 无 Database 插件（自建链与控制台链同样不可用——此时直送链自身会提示引导）。 */
        NO_DATABASE_PLUGIN,

        /**
         * R33 真机走查修复：SQL 仍含未回填的 {@code ?} 占位符——预览文本是回填链的终点产物
         * （参数击键有防抖重渲染），残留 {@code ?} 即真未填（默认占位档 / 无类型信息）。此时下发
         * 结构化探测 = 把字面 {@code ?} 送进 JDBC → 语法错（真机 {@code SQLException: You have an error
         * in your SQL syntax}）。响亮失败优于语法错：不发探测，明示「存在未回填参数」后
         * 落回直送通道（控制台如实呈现语法错）。
         */
        UNFILLED_PARAMS,
    }

    /** Kotlin 默认参数等价重载：hasUnfilledParams 缺省 false。 */
    public static Decision decide(boolean enabled,
                                  @Nullable ExplainOutputFormat format,
                                  boolean databaseAvailable,
                                  boolean hasBoundDataSource) {
        return decide(enabled, format, databaseAvailable, hasBoundDataSource, false);
    }

    /**
     * 通道判定（纯函数，无 IDE 类型——EDT 直调安全）。
     *
     * <p>判定序：① 开关关 → 直送；② 格式非结构化档（TEXT/XML/未知，含 R23 Oracle 二段式与
     * SQL Server XML——<b>不硬解析</b>红线）→ 直送；③ database 插件不在场 → 回退（提示）；
     * ④ 无已绑数据源 → 回退（提示）；⑤ SQL 仍含未回填 {@code ?}（R33 真机走查修复）→ 回退（提示）；
     * ⑥ 全部成立 → 结构化。</p>
     *
     * <p>{@code hasUnfilledParams} 默认 false 只为既有调用点零改编译——面板接线必须显式传
     * 占位探测结论（WiringGuard 钉住），漏传会让语法错场景重新滑进结构化探测。</p>
     */
    public static Decision decide(boolean enabled,
                                  @Nullable ExplainOutputFormat format,
                                  boolean databaseAvailable,
                                  boolean hasBoundDataSource,
                                  boolean hasUnfilledParams) {
        if (!enabled) return Decision.DirectSilent.INSTANCE;
        if (format == null || !STRUCTURED_FORMATS.contains(format)) return Decision.DirectSilent.INSTANCE;
        if (!databaseAvailable) return new Decision.DirectFallback(FallbackReason.NO_DATABASE_PLUGIN);
        if (!hasBoundDataSource) return new Decision.DirectFallback(FallbackReason.NO_DATA_SOURCE);
        if (hasUnfilledParams) return new Decision.DirectFallback(FallbackReason.UNFILLED_PARAMS);
        return Decision.Structured.INSTANCE;
    }

    /**
     * 结构化执行结果（纯数据，EDT 回调交付；{@code dataSourceName}/{@code elapsedMs} 来自
     * {@link McpDbExecutor.ExecutedOutcome} 审计钩子的同步回读——成功失败两条路径都会先回调一次）。
     */
    public record StructuredOutcome(
        /** EXPLAIN 行列文本（McpExplainFormatter 适配层产物）。 */
        McpExplainFormatter.RawResult raw,
        /** 本次执行耗时（毫秒；钩子回读，未回调为 -1）。 */
        long elapsedMs,
        /** 数据源显示名（钩子回读；不可达/headless 为 null）。 */
        @Nullable String dataSourceName
    ) {
    }

    /** 测试缝：headless 不连真库；生产为 null 走池线程真链。 */
    public interface StructuredRunner {
        void run(Project project, Object dataSource, String explainSql, int timeoutSeconds,
                 Consumer<StructuredOutcome> onPlan, Consumer<String> onFailed);
    }

    public static volatile StructuredRunner structuredRunnerForTest = null;

    /**
     * 结构化执行（JDBC 全程 pooled 线程，禁 EDT）。
     *
     * <p>复用 {@link McpDbExecutor#executeQuery} 既有反射链（channelNote 不传——结构化档方言全是
     * 单语句通道，null 走 executeQuery 主通道与既有调用点逐字节同形）；行预算取
     * {@link McpExplainFormatter#DEFAULT_MAX_ROWS}（EXPLAIN 计划行通常 &lt;100，MCP 链同款默认）；
     * 超时护栏沿用设置项（与 MCP 链同源）。</p>
     *
     * @param onPlan   成功回调（EDT 交付）
     * @param onFailed 失败回调（EDT 交付；参数 = 驱动异常「类名: 首行消息」）——调用方据此
     *                 回退直送通道（fail-closed，绝不吞掉失败假装渲染成功）
     */
    public static void executeStructured(Project project,
                                         Object dataSource,
                                         String explainSql,
                                         int timeoutSeconds,
                                         Consumer<StructuredOutcome> onPlan,
                                         Consumer<String> onFailed) {
        StructuredRunner testRunner = structuredRunnerForTest;
        if (testRunner != null) {
            testRunner.run(project, dataSource, explainSql, timeoutSeconds, onPlan, onFailed);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final long[] elapsedMs = {-1L};
            final String[] dataSourceName = {null};
            try {
                McpExplainFormatter.RawResult raw = McpDbExecutor.executeQuery(
                    project, dataSource, explainSql, McpExplainFormatter.DEFAULT_MAX_ROWS,
                    timeoutSeconds,
                    // T-ExplainPwdPrompt：面板 UI 上下文——数据源未存密码时交平台原生密码框
                    //（此前硬编码 false 连坐自 MCP 通道，沙盒实测只出 Access denied 红字）
                    true,
                    outcome -> {
                        // 审计钩子同步回读（成功/失败两路径都回调一次；取数失败不影响主链）
                        elapsedMs[0] = outcome.elapsedMs();
                        dataSourceName[0] = outcome.dataSourceName();
                    });
                StructuredOutcome structured = new StructuredOutcome(raw, elapsedMs[0], dataSourceName[0]);
                invokeLaterEdt(() -> onPlan.accept(structured));
            } catch (Throwable e) {
                // T-FatalPassthrough 同款解包口径（反射链目标异常被包进 InvocationTargetException）
                Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException)
                    ? ((java.lang.reflect.InvocationTargetException) e).getTargetException()
                    : e;
                if (FatalErrors.isFatal(cause)) throw (Error) cause;
                String message = cause.getMessage();
                String firstLine = message == null
                    ? null
                    : message.lines().filter(line -> !line.isBlank()).findFirst().orElse(null);
                String text = cause.getClass().getSimpleName() + ": "
                    + (firstLine != null ? firstLine : "(no message)");
                invokeLaterEdt(() -> onFailed.accept(text));
            }
        });
    }

    private static void invokeLaterEdt(Runnable block) {
        // write-safe：回调只做 Swing 组装/换入（无文档写），ModalityState.any() 同 embedConsole 口径
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any());
    }
}
