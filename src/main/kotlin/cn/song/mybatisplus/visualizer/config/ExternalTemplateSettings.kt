package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.io.File

/**
 * T-TemplateHook（FR-0045 第 3 项 MVP）：外部 SQL 模板渲染器设置。
 *
 * application-level 轻服务（`@Service(Service.Level.APP)`，跨项目共享——用户的自研模板渲染脚本
 * 通常按项目配，但插件侧只保留**一份**命令配置，工作目录留空即用当前项目根，见 [workingDirectoryOrNull]
 * 的调用点），经 XmlSerializer 持久化到独立文件 `mp-sql-visualizer-external-template.xml`：不与
 * [PluginSettings] 的 `mp-sql-visualizer.xml` 互踩写盘。应用级必须 `RoamingType.DISABLED`
 * （Settings Sync 回滚坑，[PluginSettings] KDoc；模板照抄 `log/SqlSuppressRuleManager.kt:33-46`）。
 *
 * **默认关闭**（[State.enabled] = false）：关闭时整条 MCP 渲染链零行为变更——`McpSourceLocator`
 * 只在 `enabled` 为 true 时才进入外部渲染分支，未开启用户的错误文案/渲染结果逐字节不变。
 *
 * headless 测试约束：本类除 [getInstance] 外无平台依赖，可直接 `ExternalTemplateSettings()`
 * 构造纯 JUnit。
 */
@State(
    name = "MpSqlVisualizerExternalTemplate",
    storages = [Storage("mp-sql-visualizer-external-template.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class ExternalTemplateSettings : PersistentStateComponent<ExternalTemplateSettings.State> {

    /**
     * 可持久化状态（XmlSerializer 序列化；默认值即出厂配置）。
     *
     * @property enabled 总开关；默认 false（关闭时全链零行为变更）
     * @property statementTags 逗号分隔的语句 tag 名（默认 `sql`）；命中这些 tag 时走外部渲染
     * @property commandTemplate 命令模板，含 `{template}` / `{sqlId}` / `{paramsJson}` 占位符；
     *   空串 = 未配置（与关闭同效）
     * @property timeoutSeconds 超时秒数（读时钳 [MIN_TIMEOUT_SECONDS]..[MAX_TIMEOUT_SECONDS]）
     * @property workingDirectory 工作目录；空串 = 项目根
     */
    data class State(
        var enabled: Boolean = false,
        var statementTags: String = "sql",
        var commandTemplate: String = "",
        var timeoutSeconds: Int = 10,
        var workingDirectory: String = "",
    )

    private var state = State()

    /** 总开关。 */
    val enabled: Boolean get() = state.enabled

    /** 语句 tag 原始配置（逗号分隔）。 */
    val statementTags: String get() = state.statementTags

    /** 命令模板原文。 */
    val commandTemplate: String get() = state.commandTemplate

    /** 超时秒数原文（可能越界，业务读取走 [effectiveTimeoutSeconds]）。 */
    val timeoutSeconds: Int get() = state.timeoutSeconds

    /** 工作目录原文（空串 = 未配置）。 */
    val workingDirectory: String get() = state.workingDirectory

    /** 已生效超时（钳 [MIN_TIMEOUT_SECONDS]..[MAX_TIMEOUT_SECONDS]，默认值本身不越界）。 */
    fun effectiveTimeoutSeconds(): Int = state.timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)

    /**
     * 配置是否**可用**：总开关打开且命令模板非空白。只判「要不要尝试外部渲染」，
     * 不判 tag 命中（那是 [isStatementTag] 的职责）。
     */
    fun isActive(): Boolean = state.enabled && state.commandTemplate.isNotBlank()

    /** 语句 tag 名单（逗号/中文逗号分隔，去空白、大小写不敏感以容忍用户手写 `SQL`）。 */
    fun statementTagSet(): Set<String> =
        state.statementTags.split(',', '，')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** 指定 XML tag 名是否属于用户配置的语句 tag 集。 */
    fun isStatementTag(tagName: String): Boolean = tagName.trim().lowercase() in statementTagSet()

    /**
     * 外部命令的工作目录：配置为空/空白时返回 null（调用方回落项目根，见
     * `McpSourceLocator` 的请求构造）；配置了但目录不存在时也返回 null（宁可回落也不让
     * `ProcessBuilder` 起进程即抛）。
     */
    fun workingDirectoryOrNull(): File? {
        val raw = state.workingDirectory.trim()
        if (raw.isEmpty()) return null
        val dir = File(raw)
        return if (dir.isDirectory) dir else null
    }

    /**
     * 设置页 apply 的写入口（设置页不直接摸 [state]，避免把 XmlSerializer 的可变状态暴露给 UI）。
     * 超时在写入时也钳一次，保证落盘的配置自洽。
     */
    fun update(
        enabled: Boolean,
        statementTags: String,
        commandTemplate: String,
        timeoutSeconds: Int,
        workingDirectory: String,
    ) {
        state = State(
            enabled = enabled,
            statementTags = statementTags.trim(),
            commandTemplate = commandTemplate,
            timeoutSeconds = timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
            workingDirectory = workingDirectory.trim(),
        )
    }

    override fun getState(): State = state

    override fun loadState(state: State) {
        // 防御损坏文件：XmlSerializer 缺字段走 Kotlin 默认值，但显式空串 tags 会让规则面变成
        // 「永不禁用」——回落出厂 tag。超时越界（手改 xml）同样钳一次。
        this.state = State(
            enabled = state.enabled,
            statementTags = state.statementTags.ifBlank { DEFAULT_STATEMENT_TAGS },
            commandTemplate = state.commandTemplate,
            timeoutSeconds = state.timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
            workingDirectory = state.workingDirectory,
        )
    }

    companion object {
        /** 超时下限（秒）——与 [cn.song.mybatisplus.visualizer.mcp.ExternalTemplateRenderer] 同源。 */
        const val MIN_TIMEOUT_SECONDS = cn.song.mybatisplus.visualizer.mcp.ExternalTemplateRenderer.MIN_TIMEOUT_SECONDS

        /** 超时上限（秒）——同源。 */
        const val MAX_TIMEOUT_SECONDS = cn.song.mybatisplus.visualizer.mcp.ExternalTemplateRenderer.MAX_TIMEOUT_SECONDS

        /** 出厂语句 tag 名单（自研模板最常见的语句节点名）。 */
        const val DEFAULT_STATEMENT_TAGS = "sql"

        /** 全局实例获取。 */
        @JvmStatic
        fun getInstance(): ExternalTemplateSettings =
            ApplicationManager.getApplication().getService(ExternalTemplateSettings::class.java)

        /** headless 安全获取（应用未就绪/测试环境无服务时返回 null，调用方按关闭处理）。 */
        @JvmStatic
        fun getInstanceOrNull(): ExternalTemplateSettings? =
            runCatching { getInstance() }.getOrNull()
    }
}
