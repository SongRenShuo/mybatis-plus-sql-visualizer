package cn.song.mybatisplus.visualizer.config

/**
 * 设置项场景分组（T-SettingsConverge 阶段 1）。
 *
 * 背景：[PluginSettings.State] 的 Boolean 字段已到 54 个，用户在设置页面对一长串平铺开关
 * 无从下手。本类只做**打标**——按「用户想干什么」把设置键归到四个场景，不改任何默认值、
 * 不改任何控件、不改任何读写路径。将来要按场景呈现（分组、推荐预设）时，场景 → 键清单
 * 就是单一事实源。
 *
 * 分组依据（每项按**首要受益场景**归类；跨场景的允许出现在多组，例如增强捕获既属
 * 「捕获诊断」也属「AI 协作」）：
 *
 *  - [REVIEW] 审阅：日常读 SQL、看还原结果、定位语句。凡影响「还原出来的 SQL 长什么样 /
 *    在编辑器里怎么被找到」的展示与导航项（格式化、参数内联、竖排标注、自动折叠、gutter
 *    图标与来源分组之外的总闸、JOIN 图、空白符、列表元信息、语法高亮）归此。
 *  - [TUNING] 调优：研判 SQL 语义与执行计划。凡改变**生成/发送语句内容**的语义项（逻辑删除、
 *    租户、分页模拟、数据权限提示）与执行计划分析项（结构化渲染、计划留存）归此。
 *  - [AI_COLLAB] AI 协作：把插件能力交给外部 AI 客户端（MCP 工具面）以及**数据出站**的脱敏
 *    口径。凡 MCP 开关与输出打码归此。
 *  - [CAPTURE_DIAG] 捕获诊断：日志/agent 侧的捕获来源、自动行为与恢复链。凡「从哪儿抓、
 *    抓到后怎么落列表、抓不到怎么兜底」的开关归此，含 6 个 gutter 来源分组与 3 个日志框架门。
 *
 * 特殊成员（非用户设置，仍须归组以保持「全部字段有场景归属」的对账闭合）：
 *  - 内部状态位 [PluginSettings.State.sqlListPreviewHintSeen] /
 *    [PluginSettings.State.enhancedCaptureConfirmed] /
 *    [PluginSettings.State.onboardingAnchorTipShown] /
 *    [PluginSettings.State.explainStructuredOfferedShown]——归入其语义所属场景（提示来自哪个
 *    功能就归哪组），设置页不呈现。
 *  - 迁移源 [PluginSettings.State.displayMaskEnabled]——归入审阅（它决定旧配置的展示层脱敏
 *    观感），设置页不呈现。
 *
 * 维护约定：新增 [PluginSettings.State] 的 Boolean 字段必须同时加入至少一个场景，
 * `SettingsScenarioGroupCoverageGuardTest` 会扫源码逐字段对账，漏归组即红。
 */
object SettingsScenarioGroups {

    /** 审阅：读 SQL / 看还原结果 / 在编辑器里定位语句。 */
    const val REVIEW = "REVIEW"

    /** 调优：研判 SQL 语义与执行计划。 */
    const val TUNING = "TUNING"

    /** AI 协作：MCP 工具面与数据出站脱敏。 */
    const val AI_COLLAB = "AI_COLLAB"

    /** 捕获诊断：日志/agent 捕获来源、自动行为与恢复链。 */
    const val CAPTURE_DIAG = "CAPTURE_DIAG"

    /** 全部场景（顺序即建议的呈现顺序）。 */
    val ALL: List<String> = listOf(REVIEW, TUNING, AI_COLLAB, CAPTURE_DIAG)

    /**
     * 场景 → 该场景下**建议开启**的设置键清单。
     *
     * 键是 [PluginSettings.State] 的**字段名**字符串（不是 bundle key），如 `"formatSql"`。
     * 清单只表达「做这件事时哪些开关值得打开」，与字段默认值无关——默认值有红线的部分
     * （例如 `injectTenantLine` 默认关）不因归组而改变。
     */
    val GROUPS: Map<String, List<String>> = mapOf(
        REVIEW to listOf(
            // 展示形态
            "dialectAuto", "replaceParams", "formatSql", "verticalPairAnnotation", "autoFoldEnabled",
            "editorShowWhitespaces", "xmlSynHiEnabled", "joinDiagramNative",
            // 定位与导航
            "showGutterIcon", "mapperNavYieldToCodeHelper",
            // 还原准确度的辅助开关
            "columnCalibrationEnabled", "libraryEntitySweepEnabled", "reverseManualEntitySelection",
            "jdbcSqlPreviewEnabled",
            // SQL 列表/日志条目的可读性元信息
            "logFormatSql", "logTimestampEnabled", "showDurationInLogList", "dataSourceBadgeDetail",
            // 看之前的保护：展示层脱敏（含旧布尔迁移源）
            "displayMaskEnabled",
            // 引导与日常维护
            "onboardingAnchorTipEnabled", "onboardingAnchorTipShown", "sqlListPreviewHintSeen",
            "autoCheckUpdate",
        ),
        TUNING to listOf(
            // 生成语句语义
            "injectLogicDelete", "injectTenantLine", "injectTenantOnInsert",
            "dataPermissionHint", "paginationSimEnabled", "paginationCountEnabled",
            // 执行计划分析
            "explainStructuredRender", "explainPlanRetentionEnabled", "explainStructuredOfferedShown",
        ),
        AI_COLLAB to listOf(
            "mcpToolsEnabled", "mcpMaskEnabled",
            "enhancedCaptureEnabled", "enhancedCaptureDesensitize",
        ),
        CAPTURE_DIAG to listOf(
            // 来源识别（gutter 六组 + 通用 XML 内嵌检测）
            "xmlInlineSqlEnabled",
            "gutterGroupMapperXml", "gutterGroupAnnotation", "gutterGroupJpaHql",
            "gutterGroupJdbc", "gutterGroupOther", "gutterGroupInlineFallback",
            // 捕获行为
            "logMixedPrefix", "logAutoCaptureOnStart", "logAutoCaptureSuppressed",
            "logStopOnToolWindowClose", "logAutoOpenOnCapture", "logAutoSaveEnabled",
            // 日志框架门
            "logHibernateEnabled", "logJdbcEnabled", "logShardingSphereEnabled",
            // 恢复与兜底
            "literalParamRecovery",
            // agent 采集
            "enhancedCaptureEnabled", "enhancedCaptureConfirmed",
        ),
    )

    /** 键 → 所属场景集合（可多组）。 */
    val SCENARIOS_OF: Map<String, Set<String>> = buildReverseIndex()

    /** 已归组（至少一个场景）的全部设置键。 */
    val COVERED_FIELDS: Set<String> = SCENARIOS_OF.keys.toSet()

    /** 某字段所属场景（未归组返回空集）。 */
    fun scenariosOf(fieldName: String): Set<String> = SCENARIOS_OF[fieldName].orEmpty()

    /** 某场景下的建议开启键（未知场景返回空清单）。 */
    fun fieldsOf(scenario: String): List<String> = GROUPS[scenario].orEmpty()

    /** 由 [GROUPS] 反推「键 → 场景集合」索引（声明顺序即 `ALL` 顺序）。 */
    private fun buildReverseIndex(): Map<String, Set<String>> {
        val index = LinkedHashMap<String, MutableSet<String>>()
        ALL.forEach { scenario ->
            GROUPS[scenario].orEmpty().forEach { key ->
                index.getOrPut(key) { linkedSetOf() }.add(scenario)
            }
        }
        return index
    }
}
