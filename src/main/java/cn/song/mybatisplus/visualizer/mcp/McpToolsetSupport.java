// T-SyncK2JMaster187 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetSupport.kt 移植（增量同步 c07b8e3→bccbfc5）
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 工具集抽象基类（T-McpToolset2 / T-MTS2-00）：一工具一 toolset 类的平台门控基座。
 *
 * <b>为什么按工具拆类</b>：平台对工具的启用/禁用门控只有 toolset 粒度——
 * `McpToolset.Companion.getEnabledToolsets`（262 mcpserver.jar 字节码实证）按 {@code isEnabled()}
 * 过滤整个 toolset；工具级开关即一工具一 toolset 类，各以自己的 toolName 过 {@link McpToolGate}。
 * （O-68 订正：该过滤只覆盖 ReflectionToolsProvider 路径；agent 真正看到的外部会话清单走
 * {@code getMcpToolsFiltered} 过滤层，由 SqlVisualizerToolGateFilterProvider EP 承担，见 McpToolGate
 * 头注。）
 *
 * <b>类名前缀 {@code SqlVisualizer} 是硬约定（T-McpToolNaming / T-MTN-01）</b>：平台「暴露工具」页按工具描述符的
 * category 分组，category 的 {@code shortName} 直接取 <b>toolset 类的 simpleName</b>
 * （{@code ToolsetReflection_utilKt.asTools} 字节码实证），{@code McpToolset} 接口本身没有名字属性——
 * 分组名只能由类名决定。加统一前缀后本插件十组在列表里<b>组名自带插件名且字典序相邻</b>，
 * 扫视即知同属一个插件。<b>新增工具类必须照此命名</b>（{@code SqlVisualizer<Xxx>Tool}），
 * 否则该工具在「暴露工具」页会脱离本插件组群。
 *
 * <b>253 兼容（T-McpDynamicToolset 形态）</b>：本基类<b>零平台接口实现</b>（T-McpDynamicToolset：
 * jar 字节码零 com.intellij.mcpserver.* 类型引用——243/251 的 mcpServer 旧形态缺新 API，静态
 * 引用即 verifier compatibility problems，20260926 审核拒绝主因）。平台接口由运行期 ASM 生成的
 * 壳类实现（壳 extends 本基类链上的工具类 + implements McpToolset，三钩子直接继承本类实现，
 * 见 {@link McpToolsetDynamicRegistrar}）；此前「显式字面量覆写防 K2 兼容桥悬空」的三钩子约定随
 * implements 摘除自然消解（K2 桥只为接口默认方法生成）。
 *
 * <b>输出预算登记表（T-McpOutputBudget / O-20 守卫约定——新增工具或调整预算必须在此登记对齐，
 * MetadataInvalidator 缓存登记表同款防漏）</b>：
 * <ul>
 *  <li>sqlv_schema_context：maxTables 默认 8 / 硬上限 20、单元格 200 字符、tables 白名单 ≤50
 *    （常量在 McpSchemaFormatter：DEFAULT_MAX_TABLES / HARD_MAX_TABLES / MAX_CELL_CHARS /
 *    MAX_TABLE_NAMES）；</li>
 *  <li>sqlv_explain：行默认 ≤200 / 上限 1000（maxRows 可配，越界钳制不抛错；通道层另有兜底钳制）、
 *    单元格 2000 字符（常量在 McpExplainFormatter：DEFAULT_MAX_ROWS / HARD_MAX_ROWS /
 *    MAX_CELL_CHARS）；notices 节无条数预算（驱动会话通道天然有界，个案个位数）；</li>
 *  <li>sqlv_perf_advice：建议 ≤30 条（MAX_FINDINGS，超限 SEVERITY 等级序先裁低级）+ 单条文案
 *    200 字符（MAX_ADVICE_CHARS，对齐 schema 200 口径）（常量在 SqlVisualizerPerfAdviceTool）；</li>
 *  <li>sqlv_history_search（R14）：返回条 ≤20 默认 / 硬上限 100（maxResults 钳制）、单条 SQL 预览
 *    8 行 / 600 字符、扫描封顶 200_000 条（常量在 HistorySearchService：DEFAULT_MAX_RESULTS /
 *    HARD_MAX_RESULTS / MAX_PREVIEW_LINES / MAX_PREVIEW_CHARS）；范围默认当日（D1=A）防全量倾泻；</li>
 *  <li>其余六工具：输出规模由输入决定天然有界（渲染=源码规模、logs=日志条数、crud/reverse/
 *    rollback=单实体单语句），不设额外预算。</li>
 * </ul>
 *
 * 注册：运行期探测式程序化注册（{@link McpToolsetDynamicRegistrar}，mcpServer 新形态在场的 253+/262
 * 命中；243/251 旧形态静默跳过）。线程模型/零弹窗/审计约定与一期一致：协程线程调用（PSI 读包
 * ReadAction 于各服务内部）；拒绝经 {@link SqlvExpectedToolException}＋audit 边界反射转译为平台
 * McpExpectedError（见 {@link McpExpectedBridge}）；每次调用经 {@link McpAuditLog} 审计。
 *
 * <b>移植注记（T-SyncK2JMaster187，增量）</b>：平台以 kotlin-reflect 枚举 McpToolset 的 KFunction 并经
 * {@code KCallables.callSuspendBy} 调用（262 mcpserver.jar ToolsetReflection_utilKt/CallableBridge 字节码
 * 实证）。kotlin-reflect 对 Java 声明的方法一律判 isSuspend=false（本机 kotlin-reflect 2.0 探针
 * 实证），尾参 Continuation 形态会被当作普通入参混进工具 schema——故工具方法/suspend 链按
 * 普通 Java 方法移植（suspend 去除，审计块为同步 {@link ToolBlock}）。相应地：
 * <ul>
 *  <li>master 本轮把 audit 改成「intrinsics 透传＋continuation 包装」的零 SpillingKt 结构
 *    （{@code suspendCoroutineUninterceptedOrReturn} 透传 + {@code AuditGate} 包装 continuation，
 *    挂起段 primitive token + {@code AUDIT_CTX} 旁路 map 过挂起点）；Java 侧本无挂起帧，挂起
 *    包装整体退化为同步形态：token 入 {@link #AUDIT_CTX} 旁路 → block 执行 → 成败记账按 token
 *    取回并移除（{@link #auditOk}/{@link #onFailure}）——记账语义（含异常路径与预期错误转译）
 *    与 master 逐条一致，{@code FatalErrors.isFatal} 透传保持 master 原样；</li>
 *  <li>拒绝通道改为自有 {@link SqlvExpectedToolException}（RuntimeException），对平台的平台异常
 *    形态由 audit 边界经 {@link McpExpectedBridge#wrapExpected} 反射转译；{@link #throwExpected}
 *    不再需要 sneakyThrow 逃逸通道（旧 {@code McpExpectedError} 为受检异常）；</li>
 *  <li>requireProject master 原经 {@code McpExpectedBridge.currentProjectOrNull(coroutineContext)}
 *    取框架注入项目，Java 无续体访问通道——现取「唯一打开项目」回退：无/多个打开项目时按原口径抛
 *    PROJECT_NOT_READY 结构化错误（单项目窗口=平台 MCP Server 的常态部署，行为等价；
 *    多项目同开的边界差异见移植报告，待中央确认）。</li>
 * </ul>
 */
public abstract class McpToolsetSupport {

    private final String toolName;

    protected McpToolsetSupport(String toolName) {
        this.toolName = toolName;
    }

    /** 平台门控：总开关 ∧ 工具级未禁用（McpToolGate 读插件设置，测试环境兜底启用）。 */
    public boolean isEnabled() {
        return McpToolGate.isEnabled(toolName);
    }

    /** 稳定工具集（平台 McpToolset.isExperimental 默认 true = 实验态，必须显式 false 才进稳定集）。 */
    public boolean isExperimental() {
        return false;
    }

    public boolean alwaysIncluded() {
        return false;
    }

    // ------------------------------------------------------------------
    // 共用骨架（一期 SqlVisualizerToolset 逐行迁入）
    // ------------------------------------------------------------------

    /**
     * 工具执行块：master 的 `block: suspend () -> String` 直译。
     *
     * <p>Java 侧同步承载（见类头移植注记）：块体内的拒绝通道 {@link SqlvExpectedToolException}
     * 为 RuntimeException，无需 {@code throws} 声明；其余异常按 Java 规则照常传播，
     * 出口由 {@link #audit} 记账后原语义抛出。</p>
     */
    @FunctionalInterface
    protected interface ToolBlock {
        String get();
    }

    /**
     * Kotlin 默认参数等价重载：argsSummary 缺省 {@link #LEGACY_ARGS_SUMMARY}（纯静态工具零改动）。
     */
    protected McpToolTextResult audit(String tool, ToolBlock block) {
        return audit(tool, LEGACY_ARGS_SUMMARY, block);
    }

    /**
     * 审计包裹：成功/预期错误统一记 idea.log。非预期异常不在此吞（平台记 Internal error）。
     *
     * <p><b>Long token＋审计上下文旁路——零跨挂起点引用结构（T-McpDynamicToolset 定案）</b>：
     * master 的 suspend 形态把 tool/argsSummary/started 挂起前存入 {@link #AUDIT_CTX} 旁路 map，
     * audit 链只让 primitive token 跨挂起点，记账（含异常路径）按 token 取回；Java 同步形态下
     * 该结构原样保留（block 执行前后取还），记账语义与 master 的
     * {@code AuditGate.resumeWith/onFailure/onSuccess} 逐条一致。</p>
     *
     * <p>T-McpAuditTrail T-MAT-04：{@code argsSummary} 为真实入参摘要（经 {@link McpAuditLog#summarize}
     * 产出，整体受 {@link McpAuditLog#SUMMARY_LIMIT} 截断）。两参重载默认 {@link #LEGACY_ARGS_SUMMARY}：
     * 纯静态工具无执行面、参数面未立项，保持审计行逐字节不变（<b>仅 OK 路径</b>——错误路径的入参段
     * 格式十个工具统一变化）；新增执行类工具应显式传摘要，勿再落 "ok"。</p>
     *
     * <p>错误路径（EXPECTED_ERROR）的组合串走 {@link McpAuditLogKt#errorArgsSummary} 的两段分预算：
     * 错误文本优先，入参摘要让位——长 SQL 场景下错误原因不被摘要挤掉（T-MAT-04 回退修复）。</p>
     */
    protected McpToolTextResult audit(String tool, String argsSummary, ToolBlock block) {
        long token = AUDIT_SEQ.incrementAndGet();
        AUDIT_CTX.put(token, new AuditContext(tool, argsSummary, System.currentTimeMillis()));
        // 券04（T-PerfSweep27-04）：写入点顺带清扫——取消且 continuation 永不 resume 时
        // 双路径记账（AuditGate.resumeWith/onFailure）都不会触达，token 残留；同步 O(小表)
        // 清扫兜底。（master 侧另注「不得改用 try/finally 包裹挂起调用」——K2 对该形态无条件
        // 生成 SpillingKt 引用、243 平台 stdlib 缺该类；Java 同步形态无此约束，但清扫形态
        // 照 master 终态保持。）
        sweepAuditContexts();
        try {
            if (!McpToolGate.isEnabled(tool)) {
                throwExpected(
                    McpToolErrors.code(
                        McpToolErrors.TOOL_DISABLED,
                        "tool " + tool + " is disabled in SQL Visualizer settings."
                    )
                );
            }
            String outcome = block.get();
            auditOk(token);
            // T-K2JWalkMcp262（KW-262B）：262 mcpServer 新形态要求返回 Properties（class kind）——
            // 裸 String 返回在 returnTypeSchema 被拒、十工具全部 Cannot load（沙盒活体 +
            // tools/list 对账 sqlv_* 为空实证）。统一在此包 McpToolTextResult（{"text": …}），
            // 工具体内的 String 返回语义逐字节迁入 text 字段。
            return new McpToolTextResult(outcome);
        } catch (Throwable e) {
            if (FatalErrors.isFatal(e)) {
                throw (Error) e;
            }
            // 同步路径：block 体在当前帧直接失败，记账后按原语义抛出
            throw sneakyThrow(onFailure(token, e));
        }
    }

    /**
     * T-McpAuditTrail T-MAT-04：改造前的审计入参槽位字面量。保留为默认值——纯静态工具
     * （无执行面、参数面未立项）的审计行因此逐字节不变；执行类工具显式传真实摘要。
     */
    private static final String LEGACY_ARGS_SUMMARY = "ok";

    /** 审计上下文旁路（T-McpDynamicToolset 零跨挂起点引用结构，见 {@link #audit} 头注）：key=token。 */
    public static final ConcurrentHashMap<Long, AuditContext> AUDIT_CTX = new ConcurrentHashMap<>();

    /** 审计 token 发号器。 */
    private static final AtomicLong AUDIT_SEQ = new AtomicLong();

    /**
     * 券04（T-PerfSweep27-04）旁路表容量上限：常态 0~2 条（每在途工具一条），取消残留是
     * 低频异常路径，64 上限只为防「控制通道失效形态下长时间无记账」的病态累积。
     * 包私有（Kotlin internal 等价，测试可读）。
     */
    static final int AUDIT_CTX_MAX = 64;

    /**
     * 券04（T-PerfSweep27-04）过期判定窗：远超 McpDbExecutor 的 queryTimeout 量级（秒级），
     * 在途工具不会被判过期；即使误删活条目，记账端 {@code remove ?: return} 语义只丢一条审计行。
     * 包私有（Kotlin internal 等价，测试可读）。
     */
    static final long AUDIT_CTX_STALE_MS = 10 * 60_000L;

    /**
     * 券04（T-PerfSweep27-04）审计旁路表清扫：移除过期条目 + 超限按 started 移除最旧。
     * 纯同步函数（master 侧另有 SpillingKt 红线约束挂起形态，Java 同步形态天然满足）；
     * audit() 写入后调用。包私有（Kotlin internal 等价，测试直调）。
     */
    static void sweepAuditContexts() {
        sweepAuditContexts(System.currentTimeMillis());
    }

    /** Kotlin 默认参数等价重载：{@code sweepAuditContexts(now = System.currentTimeMillis())}。 */
    static void sweepAuditContexts(long now) {
        AUDIT_CTX.values().removeIf(ctx -> now - ctx.started > AUDIT_CTX_STALE_MS);
        if (AUDIT_CTX.size() > AUDIT_CTX_MAX) {
            int toRemove = AUDIT_CTX.size() - AUDIT_CTX_MAX;
            java.util.List<java.util.Map.Entry<Long, AuditContext>> entries =
                    new java.util.ArrayList<>(AUDIT_CTX.entrySet());
            entries.sort(java.util.Comparator.comparingLong(e -> e.getValue().started));
            for (int i = 0; i < toRemove; i++) {
                AUDIT_CTX.remove(entries.get(i).getKey());
            }
        }
    }

    /** 成功记账并移除上下文（master AuditGate.onSuccess／companion auditOk 的同步合并形态）。 */
    private static void auditOk(long token) {
        AuditContext ctx = AUDIT_CTX.remove(token);
        if (ctx == null) {
            return;
        }
        McpAuditLog.call(ctx.tool, ctx.argsSummary, "OK", System.currentTimeMillis() - ctx.started);
    }

    /**
     * 失败记账＋预期错误转译；其余异常原样返回（平台记 Internal error）。
     * （master AuditGate.onFailure 的同步等价体：AUDIT_CTX 取回移除，仅 SqlvExpectedToolException
     * 走 EXPECTED_ERROR 记账＋{@link McpExpectedBridge#wrapExpected} 反射转译。）
     *
     * <p>O-86（DBX #116 对照）：中断/取消（CancellationException）也落审计（status=INTERRUPTED）——
     * 此前该分支直接透传，被取消的请求在 [MCP-AUDIT] 流里无痕（审计纵深缺口）。</p>
     */
    private static Throwable onFailure(long token, Throwable e) {
        AuditContext ctx = AUDIT_CTX.remove(token);
        if (ctx == null) {
            return e;
        }
        if (e instanceof java.util.concurrent.CancellationException) {
            McpAuditLog.call(ctx.tool, ctx.argsSummary, "INTERRUPTED", System.currentTimeMillis() - ctx.started);
            return e;
        }
        if (!(e instanceof SqlvExpectedToolException)) {
            return e;
        }
        McpAuditLog.call(
            ctx.tool,
            McpAuditLogKt.errorArgsSummary(ctx.argsSummary, ((SqlvExpectedToolException) e).getMcpErrorText()),
            "EXPECTED_ERROR",
            System.currentTimeMillis() - ctx.started
        );
        // 对平台抛出的必须是平台 McpExpectedError（agent 可读错误而非 Internal error）；
        // 转译经反射（零平台类型引用），失败降级原样抛自有异常。
        return McpExpectedBridge.wrapExpected((SqlvExpectedToolException) e);
    }

    /**
     * 审计闸门（master {@code internal class AuditGate} 的 Java 承载，O-86 起 internal→public）：
     * master 形态实现 {@code Continuation<String>} 并在 {@code resumeWith} 里做挂起路径记账；
     * Java 侧无挂起帧（见类头移植注记），挂起包装退化为同步形态——本类保留为 API/测试面，
     * {@link #onSuccess}/{@link #onFailure} 委托同一静态记账逻辑（{@link #auditOk}/{@link #onFailure}）。
     */
    public static final class AuditGate {
        private final long token;
        private final kotlin.coroutines.Continuation<String> completion;

        public AuditGate(long token, kotlin.coroutines.Continuation<String> completion) {
            this.token = token;
            this.completion = completion;
        }

        /** master {@code override val context = completion.context} 的等价读取。 */
        public kotlin.coroutines.CoroutineContext getContext() {
            return completion.getContext();
        }

        /** 同步成功记账（返回值走 audit 的 outcome 透传，不经 completion）。 */
        public void onSuccess(String value) {
            auditOk(token);
        }

        /** 失败记账＋预期错误转译；其余异常原样返回（平台记 Internal error）。 */
        public Throwable onFailure(Throwable e) {
            return McpToolsetSupport.onFailure(token, e);
        }
    }

    /** 审计上下文（tool/argsSummary/started 打包，入 {@link #AUDIT_CTX}、成败记账时取回移除）。 */
    public static final class AuditContext {
        public final String tool;
        public final String argsSummary;
        public final long started;

        public AuditContext(String tool, String argsSummary, long started) {
            this.tool = tool;
            this.argsSummary = argsSummary;
            this.started = started;
        }
    }

    /**
     * 受检异常透传（k2j 移植缺口收口）：本类与同包直抛点（{@code McpSourceLocator}）复用同一通道。
     * Java 侧 catch (Throwable) 块重抛非受检归属的异常时经此擦除类型（master Kotlin 无受检概念，
     * 直接 throw）；包内可见沿用旧约定。
     */
    @SuppressWarnings("unchecked")
    protected static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * 取当前项目（master 形态经 {@link McpExpectedBridge#currentProjectOrNull} 反射解析框架注入的
     * coroutineContext；Java 无续体通道——见类头移植注记：以「唯一打开项目」回退，无/多项目按
     * 原口径抛结构化错误）。
     */
    protected Project requireProject() {
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length == 1) {
            return openProjects[0];
        }
        throwExpected(
            McpToolErrors.code(McpToolErrors.PROJECT_NOT_READY, "no project bound to this MCP call.")
        );
        return null; // 不可达：throwExpected 必抛
    }

    /** 索引未就绪明确报错不挂死（渲染/schema/实体解析依赖 PSI 与索引）。 */
    protected void requireSmart(Project project) {
        if (DumbService.getInstance(project).isDumb()) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.PROJECT_NOT_READY,
                    "project indexing is in progress; retry when the IDE finishes indexing."
                )
            );
        }
    }

    /** 拒绝通道：agent 可读结构化错误，零弹窗（audit 边界经 {@link McpExpectedBridge#wrapExpected} 转译为平台 McpExpectedError）。 */
    protected <T> T throwExpected(String message) {
        // SqlvExpectedToolException 为 RuntimeException：方法重抛无需 sneakyThrow（旧 McpExpectedError
        // 为受检异常时的擦除通道已随 T-McpDynamicToolset 摘除）。泛型表达式形态保留——调用方可写
        // `return throwExpected(...)`（master 返回类型 Nothing 的 Java 等价）。
        throw new SqlvExpectedToolException(message);
    }

    protected String firstLine(String text) {
        return text.lines().filter(line -> !line.isBlank()).findFirst().orElse(text);
    }
}
