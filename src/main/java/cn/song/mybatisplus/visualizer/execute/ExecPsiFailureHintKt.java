package cn.song.mybatisplus.visualizer.execute;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-ExecPsiGate 13.10：<b>临时 SQL 文件建不出 PSI 的原因判定</b>（纯函数，表驱动可测）。
 *
 * <p>背景（2026-11-11 真机 + 反编译定案）：2026.3 起平台在
 * {@code AbstractFileViewProvider.shouldCreatePsi()} 里挡掉三类文件——① 被 IDE 忽略（File Types 的
 * "Ignore files and folders" 命中）；② 被 VCS 忽略（.gitignore 命中）；③ 被 Mark as Excluded 且
 * Registry {@code ide.hide.excluded.files} 开启。这三种在用户侧表现<b>一模一样</b>（都是 findFile
 * 返回 null、vf 完全有效），而修复动作完全不同：</p>
 *
 * <table>
 *   <tr><th>原因</th><th>用户该去哪儿</th></tr>
 *   <tr><td>文件类型忽略模式</td><td>设置 → 编辑器 → 文件类型 → "忽略的文件和文件夹"</td></tr>
 *   <tr><td>VCS 忽略</td><td>项目里的 .gitignore（或 版本控制 → 忽略文件）</td></tr>
 *   <tr><td>Excluded + Registry</td><td>项目结构 → 模块 → 取消排除该目录</td></tr>
 *   <tr><td>方言不可用</td><td>设置 → 语言与框架 → SQL 方言</td></tr>
 * </table>
 *
 * <p>master 侧为顶层声明集合，Java 侧按 k2j 惯例落 {@code ExecPsiFailureHintKt} 门面类。</p>
 */
public final class ExecPsiFailureHintKt {

    private ExecPsiFailureHintKt() {
    }

    /** 失败成因（语义见类 KDoc 的表）。 */
    public enum ExecPsiFailureCause {
        /** 目录被 File Types 的忽略模式命中（设置 → 编辑器 → 文件类型）。 */
        IGNORED_BY_FILE_TYPES,
        /** 目录被 VCS 忽略（.gitignore 命中）。 */
        IGNORED_BY_VCS,
        /** 目录被 Mark as Excluded，且 Registry ide.hide.excluded.files 开启。 */
        EXCLUDED_ROOT,
        /** 文件能定位到语言，但该语言建不出 SQL PSI（方言不可用 / ParserDefinition 缺失）。 */
        DIALECT_UNAVAILABLE,
        /**
         * <b>落点目录本身建不出 PSI</b>，而文件级四项谓词一个都没命中（T-ExecPsiGate F5）。
         *
         * <p>真机读数（IU-263.7307.200）：excluded=false / underIgnored=false / regHideExcluded=false /
         * psiLang=GenericSQL / canPsiActual=true 全绿，findFile 依旧 null。差异在<b>粒度</b>——
         * 平台闸门问的是 {@code findDirectory(父目录)}，而上面四项是在<b>文件</b>上取的
         * （父目录进忽略集的形态，文件谓词看不见）。这一档只能由目录级探针判出。</p>
         */
        DIR_NOT_INDEXED,
        /** 定位不到语言（.sql 没被识别成 SQL 语言）。 */
        NO_LANGUAGE,
        /** 都不是（vf 失效等）——保持原文案，不给误导性入口。 */
        UNKNOWN,
    }

    /** 文案里编码的成因 + 剥掉标记后的展示文案（T-ExecPsiGate F8）。 */
    public record ExecHintMessage(@Nullable ExecPsiFailureCause cause, @NotNull String text) {
    }

    private static final Pattern EXEC_HINT_TOKEN = Pattern.compile("\\s*\\[hint=([A-Z_]+)]");

    /** 把 {@code [hint=XXX]} 标记从文案里剥出来（纯函数，表驱动可测）。 */
    @NotNull
    public static ExecHintMessage parseExecHintMessage(@NotNull String message) {
        Matcher matcher = EXEC_HINT_TOKEN.matcher(message);
        if (!matcher.find()) {
            return new ExecHintMessage(null, message);
        }
        ExecPsiFailureCause cause = null;
        try {
            cause = ExecPsiFailureCause.valueOf(matcher.group(1));
        } catch (RuntimeException ignored) {
            // 未知标记按无成因处理
        }
        return new ExecHintMessage(cause, matcher.reset(message).replaceAll(""));
    }

    /** 把成因编进失败文案（与 {@link #parseExecHintMessage} 互逆；无成因时原样返回）。 */
    @NotNull
    public static String withExecHint(@NotNull String message, @Nullable ExecPsiFailureCause cause) {
        return cause == null ? message : message + " [hint=" + cause.name() + "]";
    }

    /**
     * T-ExecPsiGate F8：<b>UI 侧取成因的唯一入口</b>——先认文案里编码的标记，再退回共享记录
     * （老路径兼容）。抽成函数是为了让「这条文案该挂哪颗蓝字」可被单测直接复现。
     */
    @Nullable
    public static ExecPsiFailureCause resolveTempFileHintCause(@NotNull String errorMsg) {
        ExecPsiFailureCause fromMessage = parseExecHintMessage(errorMsg).cause();
        return fromMessage != null ? fromMessage : DataSourceSelector.consumeTempFileHintFor(errorMsg);
    }

    /**
     * 观测值 → 原因。
     *
     * @param excluded         ProjectFileIndex.isExcluded(vf)
     * @param regHideExcluded  Registry.is("ide.hide.excluded.files")
     * @param fileTypesIgnored 是否命中 File Types 的用户忽略模式（FileTypeManager.isFileIgnored）
     * @param underIgnored     ProjectFileIndex.isUnderIgnored(vf)
     * @param psiLangId        平台实际给该文件选中的语言 id（null = 没选中语言）
     * @param canPsiActual     该语言能否建 SQL PSI
     * @param dirCanHostPsi    目录级探针结论：PsiManager.findDirectory(落点目录) 是否为 null。
     *                         null = 没探（文件级调用方拿不到这个信息），此时该参数不参与判定
     */
    @NotNull
    public static ExecPsiFailureCause classifyExecPsiFailure(
            boolean excluded,
            boolean regHideExcluded,
            boolean fileTypesIgnored,
            boolean underIgnored,
            @Nullable String psiLangId,
            boolean canPsiActual,
            @Nullable Boolean dirCanHostPsi) {
        // 平台闸门的两条：Registry 开时看「排除」，否则看「忽略集」。先判能直接对上的那两条，
        // 因为它们有明确的修复入口；方言问题只在「既没排除也没忽略」时才成立（否则会指错地方）。
        if (excluded && regHideExcluded) {
            return ExecPsiFailureCause.EXCLUDED_ROOT;
        }
        if (fileTypesIgnored) {
            return ExecPsiFailureCause.IGNORED_BY_FILE_TYPES;
        }
        if (underIgnored) {
            return ExecPsiFailureCause.IGNORED_BY_VCS;
        }
        // F5：文件级谓词全绿而目录级探针说「建不出」——真机实证的唯一形态。必须排在语言判据之前：
        // 这种机器上 psiLang / canPsiActual 都是正常的，先判语言会把它误报成方言问题。
        if (dirCanHostPsi != null && !dirCanHostPsi) {
            return ExecPsiFailureCause.DIR_NOT_INDEXED;
        }
        if (psiLangId == null) {
            return ExecPsiFailureCause.NO_LANGUAGE;
        }
        if (!canPsiActual) {
            return ExecPsiFailureCause.DIALECT_UNAVAILABLE;
        }
        return ExecPsiFailureCause.UNKNOWN;
    }

    /** 无目录级探针信息的简化形态（Kotlin 默认参 dirCanHostPsi=null 等价）。 */
    @NotNull
    public static ExecPsiFailureCause classifyExecPsiFailure(
            boolean excluded,
            boolean regHideExcluded,
            boolean fileTypesIgnored,
            boolean underIgnored,
            @Nullable String psiLangId,
            boolean canPsiActual) {
        return classifyExecPsiFailure(excluded, regHideExcluded, fileTypesIgnored, underIgnored,
                psiLangId, canPsiActual, null);
    }
}
