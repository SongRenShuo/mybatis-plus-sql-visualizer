package cn.song.mybatisplus.visualizer.execute;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-ExecPsiGate 13.10：<b>临时 SQL 文件建不出 PSI 的原因判定</b>（纯函数，表驱动可测）。
 *
 * <p>背景（2026-11-11 真机 + 反编译定案）：2026.3 起平台在 {@code AbstractFileViewProvider.shouldCreatePsi()}
 * 里挡掉三类文件——① 被 IDE 忽略（File Types 的 "Ignore files and folders" 命中）；② 被 VCS 忽略
 * （{@code .gitignore} 命中）；③ 被 Mark as Excluded 且 Registry {@code ide.hide.excluded.files} 开启。
 * 这三种在用户侧表现<b>一模一样</b>（都是 {@code findFile} 返回 null、vf 完全有效），而修复动作完全不同：</p>
 *
 * <pre>
 *  | 原因             | 用户该去哪儿                                        |
 *  |-----------------|---------------------------------------------------|
 *  | 文件类型忽略模式  | 设置 → 编辑器 → 文件类型 → "忽略的文件和文件夹"        |
 *  | VCS 忽略         | 项目里的 {@code .gitignore}（或 版本控制 → 忽略文件）   |
 *  | Excluded + Registry | 项目结构 → 模块 → 取消排除该目录                  |
 *  | 方言不可用        | 设置 → 语言与框架 → SQL 方言                        |
 * </pre>
 *
 * <p>所以只报「创建临时 SQL 文件失败」等于什么都没说。本类把「观测值 → 原因」收敛成纯函数，
 * UI 侧据此挂对应的蓝色可点入口（{@code ui.SqlResultPanel.showErrorWithActions}）。</p>
 *
 * <p>k2j 移植说明：master 侧为 {@code execute/ExecPsiFailureHint.kt}（顶层 enum + 顶层函数）；
 * Java 端按 A3 消费形态收敛为本类嵌套 enum + 静态方法
 * （消费点：{@code ui.SqlPreviewPanelExecutionKt} / {@code ui.SqlPreviewPanelEditorLifecycleKt}）。</p>
 */
public final class ExecPsiFailureHint {

    private ExecPsiFailureHint() {
    }

    /** T-ExecPsiGate 13.10：临时 SQL 文件建不出 PSI 的原因（master 顶层 enum 的嵌套承载形态）。 */
    public enum ExecPsiFailureCause {
        /** 目录被 File Types 的忽略模式命中（设置 → 编辑器 → 文件类型）。 */
        IGNORED_BY_FILE_TYPES,

        /** 目录被 VCS 忽略（{@code .gitignore} 命中）。 */
        IGNORED_BY_VCS,

        /** 目录被 Mark as Excluded，且 Registry {@code ide.hide.excluded.files} 开启。 */
        EXCLUDED_ROOT,

        /** 文件能定位到语言，但该语言建不出 SQL PSI（方言不可用 / ParserDefinition 缺失）。 */
        DIALECT_UNAVAILABLE,

        /**
         * <b>落点目录本身建不出 PSI</b>，而文件级四项谓词一个都没命中（T-ExecPsiGate F5，2026-11-11 真机）。
         *
         * <p>真机读数（IU-263.7307.200，{@code <项目>/.idea/sql-visualizer/exec/mpsql-exec.sql}）：
         * {@code excluded=false / underIgnored=false / regHideExcluded=false / psiLang=GenericSQL / canPsiActual=true}
         * 全绿，{@code findFile} 依旧 null。差异在<b>粒度</b>——平台闸门问的是 {@code findDirectory(父目录)}，
         * 而上面四项是在<b>文件</b>上取的（父目录进忽略集的形态，文件谓词看不见）。这一档只能由目录级
         * 探针判出，所以它不该归 UNKNOWN：文案要说清「这个目录 IDE 不接管」，并给兜底落点。</p>
         */
        DIR_NOT_INDEXED,

        /** 定位不到语言（{@code .sql} 没被识别成 SQL 语言）。 */
        NO_LANGUAGE,

        /** 都不是（vf 失效等）——保持原文案，不给误导性入口。 */
        UNKNOWN,
    }

    /**
     * T-ExecPsiGate F8（2026-11-11 第五轮，用户「还是没解决」）：<b>成因随失败文案一起走</b>。
     *
     * <p>前四轮把成因存在共享字段里，靠"谁清谁写"的约定传递，结果被三条不同的机制各打掉一次：
     * 预热链入口清空（F3）、并发失败覆盖（F5）、同一次失败第二次渲染时读方清空（F7）。而且真机上
     * 还有一条更狠的：插件被动态重载时 {@code DataSourceSelector} 这个 object 可能有两份类实例，
     * 写方与读方根本不在同一个字段上。</p>
     *
     * <p>所以第五轮改成<b>把成因编码进错误文案本身</b>（{@code  [hint=IGNORED_BY_VCS]}）：文案怎么走、成因就
     * 怎么走——跨线程、跨渲染次数、跨类加载器都不会丢，也不需要任何读方负责清理。UI 在渲染前
     * 用 {@link #parseExecHintMessage} 把它剥掉，用户看不到这段标记（日志与"复制错误信息"里保留，便于归因）。</p>
     */
    public record ExecHintMessage(ExecPsiFailureCause cause, String text) {
    }

    private static final Pattern EXEC_HINT_TOKEN = Pattern.compile("\\s*\\[hint=([A-Z_]+)]");

    /** 把 {@code [hint=XXX]} 标记从文案里剥出来（纯函数，表驱动可测）。 */
    public static ExecHintMessage parseExecHintMessage(String message) {
        Matcher matcher = EXEC_HINT_TOKEN.matcher(message);
        if (!matcher.find()) return new ExecHintMessage(null, message);
        ExecPsiFailureCause cause = null;
        try {
            cause = ExecPsiFailureCause.valueOf(matcher.group(1));
        } catch (IllegalArgumentException ignored) {
            // Kotlin runCatching { valueOf }.getOrNull() 语义
        }
        return new ExecHintMessage(cause, matcher.replaceAll(""));
    }

    /** 把成因编进失败文案（与 {@link #parseExecHintMessage} 互逆；无成因时原样返回）。 */
    public static String withExecHint(String message, ExecPsiFailureCause cause) {
        return cause == null ? message : message + " [hint=" + cause.name() + "]";
    }

    /**
     * T-ExecPsiGate F8：<b>UI 侧取成因的唯一入口</b>——先认文案里编码的标记，再退回共享记录（老路径兼容）。
     *
     * <p>抽成函数是为了让「这条文案该挂哪颗蓝字」可被单测直接复现：真机前四轮的故障点全在这一步
     * 返回 null（共享字段被清/被覆盖/两份类实例），而返回 null 就等于面板只剩红字。</p>
     */
    public static ExecPsiFailureCause resolveTempFileHintCause(String errorMsg) {
        ExecPsiFailureCause fromMessage = parseExecHintMessage(errorMsg).cause();
        return fromMessage != null ? fromMessage : DataSourceSelector.consumeTempFileHintFor(errorMsg);
    }

    /**
     * 观测值 → 原因。
     *
     * @param excluded         {@code ProjectFileIndex.isExcluded(vf)}
     * @param regHideExcluded  {@code Registry.is("ide.hide.excluded.files")}
     * @param fileTypesIgnored 是否命中 File Types 的用户忽略模式（{@code FileTypeManager.isFileIgnored}）
     * @param underIgnored     {@code ProjectFileIndex.isUnderIgnored(vf)}
     * @param psiLangId        平台实际给该文件选中的语言 id（null = 没选中语言）
     * @param canPsiActual     该语言能否建 SQL PSI
     * @param dirCanHostPsi    目录级探针结论：{@code PsiManager.findDirectory(落点目录)} 是否为 null。
     *                         {@code null} = 没探（文件级调用方拿不到这个信息），此时该参数不参与判定。
     */
    public static ExecPsiFailureCause classifyExecPsiFailure(
            boolean excluded,
            boolean regHideExcluded,
            boolean fileTypesIgnored,
            boolean underIgnored,
            String psiLangId,
            boolean canPsiActual,
            Boolean dirCanHostPsi) {
        // 平台闸门的两条：Registry 开时看「排除」，否则看「忽略集」。先判能直接对上的那两条，
        // 因为它们有明确的修复入口；方言问题只在「既没排除也没忽略」时才成立（否则会指错地方）。
        if (excluded && regHideExcluded) return ExecPsiFailureCause.EXCLUDED_ROOT;
        if (fileTypesIgnored) return ExecPsiFailureCause.IGNORED_BY_FILE_TYPES;
        if (underIgnored) return ExecPsiFailureCause.IGNORED_BY_VCS;
        // F5：文件级谓词全绿而目录级探针说「建不出」——真机实证的唯一形态。必须排在语言判据之前：
        // 这种机器上 psiLang / canPsiActual 都是正常的，先判语言会把它误报成方言问题。
        if (Boolean.FALSE.equals(dirCanHostPsi)) return ExecPsiFailureCause.DIR_NOT_INDEXED;
        if (psiLangId == null) return ExecPsiFailureCause.NO_LANGUAGE;
        if (!canPsiActual) return ExecPsiFailureCause.DIALECT_UNAVAILABLE;
        return ExecPsiFailureCause.UNKNOWN;
    }

    /** {@code dirCanHostPsi} 默认参（master {@code = null}）的重载：6 参调用点委托全参形态。 */
    public static ExecPsiFailureCause classifyExecPsiFailure(
            boolean excluded,
            boolean regHideExcluded,
            boolean fileTypesIgnored,
            boolean underIgnored,
            String psiLangId,
            boolean canPsiActual) {
        return classifyExecPsiFailure(excluded, regHideExcluded, fileTypesIgnored, underIgnored,
                psiLangId, canPsiActual, null);
    }
}
