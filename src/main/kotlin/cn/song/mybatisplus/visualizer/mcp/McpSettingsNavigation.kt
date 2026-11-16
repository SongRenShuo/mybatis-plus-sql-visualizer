package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * 平台 MCP Server 设置页的稳定标识：**页面 id**（263 插件描述实证
 * `<applicationConfigurable ... id="com.intellij.mcpserver.settings" parentId="ai" />`）。
 * 页面 id 是平台对外的寻址契约，实现类名/包名/module 都是可重构的内部结构。
 */
internal const val MCP_SETTINGS_PAGE_ID = "com.intellij.mcpserver.settings"

/**
 * 设置页 Configurable 的实现类候选链，**新形态在前**。
 *
 * 为什么是候选链而不是一个 FQN（T-McpSettingsJump 20261111 真机故障）：平台 263 把 mcpserver 插件
 * 重组成 content module 形态，设置页随 UI 前端代码搬进 `intellij.mcpserver.frontend`
 * （`required-if-available="intellij.platform.frontend"`），包名从 `com.intellij.mcpserver.settings`
 * 改成 `com.intellij.mcpserver.frontend.settings`——旧 FQN 在 `263.7307.200` 整安装（lib + plugins，
 * 含 `lib/modules/`）零命中。旧的单 FQN 探测因此恒 null，设置页跳转被误报成「IDEA 版本不够」。
 *
 * **新增/调整本表时的硬约束**（`McpSettingsNavigationTest` 守着）：设置页类名只允许出现在这里，
 * 不得在别处写 `Class.forName("com.intellij.mcpserver.settings…")` 之类单点探测——那正是本次故障形态。
 * 老版本（243/251，无 mcpServer 新形态）两个 FQN 都探不到，走 [MCP_SETTINGS_PAGE_ID] 与提示文案。
 */
internal val MCP_SETTINGS_CONFIGURABLE_CLASSES = listOf(
    "com.intellij.mcpserver.frontend.settings.McpServerSettingsConfigurable",
    "com.intellij.mcpserver.settings.McpServerSettingsConfigurable",
)

/** 打开结果：只用于区分「跳成功了」与「该提示用户」，不参与任何门控判断。 */
internal enum class McpSettingsOpenResult {
    /** 经类名候选链打开（262/263 实测路径）。 */
    OPENED,

    /** 类名全落空，经平台「按页面 id 打开并选中」入口打开。 */
    OPENED_BY_ID,

    /** 两条路都没走通：插件缺失/被禁，或平台又搬了地方——调用方给一次可操作提示。 */
    UNAVAILABLE,
}

private val LOG = Logger.getInstance("cn.song.mybatisplus.visualizer.mcp.McpSettingsNavigation")

/**
 * 打开平台 MCP Server 设置页并选中该页（T-McpSettingsJump）。
 *
 * 顺序与理由：
 * 1. **类名候选链优先**——这是 262 与 263 都已经走通的路径（263 只是换了类名），
 *    不依赖页面 id 在旧版本的可用性，也不依赖平台在「id 不存在」时的行为；
 * 2. **页面 id 兜底**——照 `ui/SqlPreviewPanelExecution.kt` 打开 SQL 方言页 / 文件类型页的同款手法
 *    （T-ExecPsiGate 13.10 实证：必须在同一个 ConfigurableGroup 实例里按 id 查找才能选中目标页，
 *    自己 findById 再把裸 Configurable 交出去会落到设置根页）；
 * 3. 都失败才返回 [McpSettingsOpenResult.UNAVAILABLE]，由调用方决定文案（本函数零弹窗）。
 *
 * 不得改成 `Messages`/`JOptionPane` 调用：弹窗白名单（BannedApiSourceScanTest）只放行调用点文件。
 */
internal fun openMcpServerSettingsPage(project: Project): McpSettingsOpenResult {
    val configurable = MCP_SETTINGS_CONFIGURABLE_CLASSES.firstNotNullOfOrNull { fqn ->
        runCatching { Class.forName(fqn) as? Class<out Configurable> }.getOrNull()
    }
    if (configurable != null) {
        val opened = runCatching {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, configurable)
        }
        if (opened.isSuccess) return McpSettingsOpenResult.OPENED
    }

    val byId = runCatching {
        com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(project, MCP_SETTINGS_PAGE_ID, null)
    }.isSuccess
    if (byId) return McpSettingsOpenResult.OPENED_BY_ID

    val publicById = runCatching {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MCP_SETTINGS_PAGE_ID)
    }.isSuccess
    if (publicById) return McpSettingsOpenResult.OPENED_BY_ID

    // 诊断线索：真机上「点击前往没反应/弹提示」时靠这一行区分「类没探到」与「页面 id 也打不开」。
    LOG.warn(
        "MCP settings page unreachable: configurableClass=$configurable, " +
            "candidates=$MCP_SETTINGS_CONFIGURABLE_CLASSES, pageId=$MCP_SETTINGS_PAGE_ID",
    )
    return McpSettingsOpenResult.UNAVAILABLE
}
