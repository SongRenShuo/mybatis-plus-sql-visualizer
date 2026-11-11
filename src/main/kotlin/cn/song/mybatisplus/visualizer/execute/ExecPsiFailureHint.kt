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
 */
internal fun classifyExecPsiFailure(
    excluded: Boolean,
    regHideExcluded: Boolean,
    fileTypesIgnored: Boolean,
    underIgnored: Boolean,
    psiLangId: String?,
    canPsiActual: Boolean,
): ExecPsiFailureCause {
    // 平台闸门的两条：Registry 开时看「排除」，否则看「忽略集」。先判能直接对上的那两条，
    // 因为它们有明确的修复入口；方言问题只在「既没排除也没忽略」时才成立（否则会指错地方）。
    if (excluded && regHideExcluded) return ExecPsiFailureCause.EXCLUDED_ROOT
    if (fileTypesIgnored) return ExecPsiFailureCause.IGNORED_BY_FILE_TYPES
    if (underIgnored) return ExecPsiFailureCause.IGNORED_BY_VCS
    if (psiLangId == null) return ExecPsiFailureCause.NO_LANGUAGE
    if (!canPsiActual) return ExecPsiFailureCause.DIALECT_UNAVAILABLE
    return ExecPsiFailureCause.UNKNOWN
}
