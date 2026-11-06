package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * 项目级插件设置（O-5 Phase B）。
 *
 * project-level 轻服务（@Service(Service.Level.PROJECT)，按项目隔离），经 XmlSerializer
 * 持久化到 `.idea/mp-sql-visualizer-project.xml`。
 *
 * 职责：
 *  - 存储「使用项目设置」开关 + 可覆盖项（方言/参数替换/逻辑删除/表名映射等）
 *  - 关闭时使用全局 [PluginSettings] 值；开启时覆盖全局值
 *
 * 可覆盖项与全局 [PluginSettings.State] 同名同语义，空白/默认值表示「使用全局」。
 */
@State(
    name = "MpSqlVisualizerProjectSettings",
    storages = [Storage("mp-sql-visualizer-project.xml")],
)
@Service(Service.Level.PROJECT)
class ProjectPluginSettings : PersistentStateComponent<ProjectPluginSettings.State> {

    /**
     * 项目级可持久化状态。
     *
     * 注意：字段默认值为空/默认语义（空字符串表示「使用全局」），与全局 [PluginSettings.State]
     * 的出厂默认值语义不同——[effectiveXxx] 系列方法负责空值回退。
     */
    @Suppress("DataClassShouldBeImmutable")
    data class State(
        /** 是否启用项目级设置（false 时使用全局 [PluginSettings] 值）。 */
        var useProjectSettings: Boolean = false,

        /** 项目级数据库方言（空字符串表示使用全局；仅项目级 auto 关闭时生效）。 */
        var dialect: String = "",

        /**
         * T-DialectAutoInfer：项目级方言模式（null=使用全局——与「空=不覆盖」语义同构）。
         * true=项目强制智能推断；false=项目按 [dialect]（非空时）手动指定。
         */
        var dialectAuto: Boolean? = null,

        /** 项目级参数替换开关（null 表示使用全局）。 */
        var replaceParams: Boolean? = null,

        /** 项目级格式化开关（null 表示使用全局）。 */
        var formatSql: Boolean? = null,

        /** 项目级逻辑删除注入开关（null 表示使用全局）。 */
        var injectLogicDelete: Boolean? = null,

        /** 项目级逻辑删除值（空字符串表示使用全局）。 */
        var logicDeleteValue: String = "",

        /** 项目级表前缀（空字符串表示使用全局）。 */
        var tablePrefix: String = "",

        /** 项目级表后缀（空字符串表示使用全局）。 */
        var tableSuffix: String = "",

        /** 项目级表名推断策略（空字符串表示使用全局）。 */
        var tableNameInference: String = "",

        /** 项目级忽略字段名（空字符串表示使用全局）。 */
        var ignoredFields: String = "",

        /** 项目级列名数据源校正开关（O-58；null 表示使用全局）。 */
        var columnCalibration: Boolean? = null,
    )

    private var state = State()

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ProjectPluginSettings::class.java)

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // ------------------------------------------------------------------
    // effective 取值（useProjectSettings=true 时项目级优先，否则回退全局）
    // ------------------------------------------------------------------

    /** 当前项目是否启用了项目级设置。 */
    fun isEnabled(): Boolean = state.useProjectSettings

    /**
     * T-DialectAutoInfer：有效方言模式（项目级 dialectAuto 非 null 且已启用项目设置时优先，
     * 否则回退全局 dialectAuto）。
     */
    fun effectiveDialectAuto(): Boolean =
        if (state.useProjectSettings && state.dialectAuto != null) state.dialectAuto!!
        else PluginSettings.getInstance().state.dialectAuto

    /** 项目级方言（未启用或空则回退全局）。 */
    fun effectiveDialect(): String =
        if (state.useProjectSettings && state.dialect.isNotBlank()) state.dialect
        else PluginSettings.getInstance().state.dialect

    /** 项目级参数替换开关（未启用或 null 则回退全局）。 */
    fun effectiveReplaceParams(): Boolean =
        if (state.useProjectSettings) state.replaceParams ?: PluginSettings.getInstance().state.replaceParams
        else PluginSettings.getInstance().state.replaceParams

    /** 项目级格式化开关（未启用或 null 则回退全局）。 */
    fun effectiveFormatSql(): Boolean =
        if (state.useProjectSettings) state.formatSql ?: PluginSettings.getInstance().state.formatSql
        else PluginSettings.getInstance().state.formatSql

    /** 项目级逻辑删除注入开关（未启用或 null 则回退全局）。 */
    fun effectiveInjectLogicDelete(): Boolean =
        if (state.useProjectSettings) state.injectLogicDelete ?: PluginSettings.getInstance().state.injectLogicDelete
        else PluginSettings.getInstance().state.injectLogicDelete

    /**
     * 项目级逻辑删除值（未启用或空则回退全局）。
     * T-SAF-08：`.idea/mp-sql-visualizer-project.xml` 是 VCS 共享的低信任通道——项目值未通过
     * [SettingsValueValidator] 值级校验（SQL 位形单字面量）时 warn 回退全局值（覆盖被拒绝）。
     */
    fun effectiveLogicDeleteValue(): String =
        if (state.useProjectSettings && state.logicDeleteValue.isNotBlank()) {
            validatedOrGlobal(
                "logicDeleteValue", state.logicDeleteValue,
                SettingsValueValidator::checkLogicDeleteValue,
            ) { PluginSettings.getInstance().state.logicDeleteValue }
        } else PluginSettings.getInstance().state.logicDeleteValue

    /** 项目级表前缀（未启用或空则回退全局）。T-SAF-08：标识符形状校验，同上。 */
    fun effectiveTablePrefix(): String =
        if (state.useProjectSettings && state.tablePrefix.isNotBlank()) {
            validatedOrGlobal(
                "tablePrefix", state.tablePrefix,
                SettingsValueValidator::checkTablePrefix,
            ) { PluginSettings.getInstance().state.tablePrefix }
        } else PluginSettings.getInstance().state.tablePrefix

    /** 项目级表后缀（未启用或空则回退全局）。T-SAF-08：标识符形状校验，同上。 */
    fun effectiveTableSuffix(): String =
        if (state.useProjectSettings && state.tableSuffix.isNotBlank()) {
            validatedOrGlobal(
                "tableSuffix", state.tableSuffix,
                SettingsValueValidator::checkTableSuffix,
            ) { PluginSettings.getInstance().state.tableSuffix }
        } else PluginSettings.getInstance().state.tableSuffix

    /**
     * T-SAF-08 共享闸：项目级 SQL 位形值通过校验则原样生效；未通过时 warn（拒绝报告）
     * 并回退全局值（覆盖被拒绝，与导入路径的「拒绝+报告」同口径——本路径无 UI 反馈面，落 warn 日志）。
     */
    private fun validatedOrGlobal(
        field: String,
        projectValue: String,
        check: (String) -> SettingsValueValidator.Violation?,
        globalFallback: () -> String,
    ): String {
        val violation = check(projectValue)
        if (violation == null) return projectValue
        LOG.warn("项目级设置 $field 未通过 SQL 位形安全校验（${violation.reason}），回退全局值")
        return globalFallback()
    }

    /** 项目级表名推断策略（未启用或空则回退全局）。 */
    fun effectiveTableNameInference(): String =
        if (state.useProjectSettings && state.tableNameInference.isNotBlank()) state.tableNameInference
        else PluginSettings.getInstance().state.tableNameInference

    /** 项目级忽略字段名（未启用或空则回退全局）。 */
    fun effectiveIgnoredFields(): String =
        if (state.useProjectSettings && state.ignoredFields.isNotBlank()) state.ignoredFields
        else PluginSettings.getInstance().state.ignoredFields

    /** 项目级列名数据源校正开关（O-58；未启用或 null 则回退全局）。 */
    fun effectiveColumnCalibration(): Boolean =
        if (state.useProjectSettings) {
            state.columnCalibration ?: PluginSettings.getInstance().state.columnCalibrationEnabled
        } else {
            PluginSettings.getInstance().state.columnCalibrationEnabled
        }

    companion object {
        /**
         * 获取指定项目的 ProjectPluginSettings 实例。
         *
         * @param project 目标项目
         * @return 项目级设置实例
         */
        @JvmStatic
        fun getInstance(project: Project): ProjectPluginSettings =
            project.getService(ProjectPluginSettings::class.java)
    }
}
