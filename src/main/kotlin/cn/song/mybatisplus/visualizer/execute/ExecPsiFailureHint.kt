package cn.song.mybatisplus.visualizer.execute

/**
 * T-ExecPsiGate 13.10：**临时 SQL 文件建不出 PSI 的原因判定**（纯函数，表驱动可测）。
 *
 * 背景（2026-11-11 真机 + 反编译定案）：2026.3 起平台在 `AbstractFileViewProvider.shouldCreatePsi()`
 * 里挡掉三类文件——① 被 IDE 忽略（File Types 的 "Ignore files and folders" 命中）；② 被 VCS 忽略
 * （`.gitignore` 命中）；③ 被 Mark as Excluded 且 Registry `ide.hide.excluded.files` 开启。
 * 这三种在用户侧表现**一模一样**（都是 `findFile` 返回 null、vf 完全有效），而修复动作完全不同：
 *
 *  | 原因 | 用户该去哪儿 |
 *  |---|---|
 *  | 文件类型忽略模式 | 设置 → 编辑器 → 文件类型 → "忽略的文件和文件夹" |
 *  | VCS 忽略 | 项目里的 `.gitignore`（或 版本控制 → 忽略文件） |
 *  | Excluded + Registry | 项目结构 → 模块 → 取消排除该目录 |
 *  | 方言不可用 | 设置 → 语言与框架 → SQL 方言 |
 *
 * 所以只报「创建临时 SQL 文件失败」等于什么都没说。本文件把「观测值 → 原因」收敛成一个纯函数，
 * UI 侧据此挂对应的蓝色可点入口（[cn.song.mybatisplus.visualizer.ui.SqlResultPanel.showErrorWithActions]）。
 */
internal enum class ExecPsiFailureCause {
    /** 目录被 File Types 的忽略模式命中（设置 → 编辑器 → 文件类型）。 */
    IGNORED_BY_FILE_TYPES,

    /** 目录被 VCS 忽略（`.gitignore` 命中）。 */
    IGNORED_BY_VCS,

    /** 目录被 Mark as Excluded，且 Registry `ide.hide.excluded.files` 开启。 */
    EXCLUDED_ROOT,

    /** 文件能定位到语言，但该语言建不出 SQL PSI（方言不可用 / ParserDefinition 缺失）。 */
    DIALECT_UNAVAILABLE,

    /**
     * **落点目录本身建不出 PSI**，而文件级四项谓词一个都没命中（T-ExecPsiGate F5，2026-11-11 真机）。
     *
     * 真机读数（IU-263.7307.200，`<项目>/.idea/sql-visualizer/exec/mpsql-exec.sql`）：
     * `excluded=false / underIgnored=false / regHideExcluded=false / psiLang=GenericSQL / canPsiActual=true`
     * 全绿，`findFile` 依旧 null。差异在**粒度**——平台闸门问的是 `findDirectory(父目录)`，而上面四项是在
     * **文件**上取的（父目录进忽略集的形态，文件谓词看不见）。这一档只能由目录级探针判出，所以它不该
     * 归 UNKNOWN：文案要说清「这个目录 IDE 不接管」，并给兜底落点。
     */
    DIR_NOT_INDEXED,

    /** 定位不到语言（`.sql` 没被识别成 SQL 语言）。 */
    NO_LANGUAGE,

    /** 都不是（vf 失效等）——保持原文案，不给误导性入口。 */
    UNKNOWN,
}

/**
 * 观测值 → 原因。
 *
 * @param excluded  `ProjectFileIndex.isExcluded(vf)`
 * @param regHideExcluded `Registry.is("ide.hide.excluded.files")`
 * @param fileTypesIgnored 是否命中 File Types 的用户忽略模式（`FileTypeManager.isFileIgnored`）
 * @param underIgnored `ProjectFileIndex.isUnderIgnored(vf)`
 * @param psiLangId 平台实际给该文件选中的语言 id（null = 没选中语言）
 * @param canPsiActual 该语言能否建 SQL PSI
 * @param dirCanHostPsi 目录级探针结论：`PsiManager.findDirectory(落点目录)` 是否为 null。
 *   `null` = 没探（文件级调用方拿不到这个信息），此时该参数不参与判定。
 */
internal fun classifyExecPsiFailure(
    excluded: Boolean,
    regHideExcluded: Boolean,
    fileTypesIgnored: Boolean,
    underIgnored: Boolean,
    psiLangId: String?,
    canPsiActual: Boolean,
    dirCanHostPsi: Boolean? = null,
): ExecPsiFailureCause {
    // 平台闸门的两条：Registry 开时看「排除」，否则看「忽略集」。先判能直接对上的那两条，
    // 因为它们有明确的修复入口；方言问题只在「既没排除也没忽略」时才成立（否则会指错地方）。
    if (excluded && regHideExcluded) return ExecPsiFailureCause.EXCLUDED_ROOT
    if (fileTypesIgnored) return ExecPsiFailureCause.IGNORED_BY_FILE_TYPES
    if (underIgnored) return ExecPsiFailureCause.IGNORED_BY_VCS
    // F5：文件级谓词全绿而目录级探针说「建不出」——真机实证的唯一形态。必须排在语言判据之前：
    // 这种机器上 psiLang / canPsiActual 都是正常的，先判语言会把它误报成方言问题。
    if (dirCanHostPsi == false) return ExecPsiFailureCause.DIR_NOT_INDEXED
    if (psiLangId == null) return ExecPsiFailureCause.NO_LANGUAGE
    if (!canPsiActual) return ExecPsiFailureCause.DIALECT_UNAVAILABLE
    return ExecPsiFailureCause.UNKNOWN
}
