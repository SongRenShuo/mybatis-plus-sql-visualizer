package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-McpSettingsJump（20261111）：平台 MCP Server 设置页的寻址不得退回「单一 FQN 探测」。
 *
 * 故障形态（真机 2026.3 / `263.7307.200`）：平台把设置页搬进新 content module
 * `intellij.mcpserver.frontend` 并换包名，写死的旧 FQN `Class.forName` 恒 null，设置页跳转被
 * 误报成「需要 IDEA 2025.2+」。本守卫钉死两件事：
 *  1. 候选链必须同时覆盖「263 新形态」与「262/更早形态」，且新形态在前；
 *  2. 设置页类名只允许出现在 [McpSettingsNavigation.kt] 的候选链里——别处再写一次
 *     `Class.forName("com.intellij.mcpserver…McpServerSettingsConfigurable")` 就是复发形态。
 *
 * 运行时行为（真机能不能选中目标页）本类不覆盖：无 platform 服务时构造不出 Project，
 * 判据在计划 A2（2026.3 真机三步验收）。
 */
class McpSettingsNavigationTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private val newFormFqn = "com.intellij.mcpserver.frontend.settings.McpServerSettingsConfigurable"
    private val legacyFormFqn = "com.intellij.mcpserver.settings.McpServerSettingsConfigurable"

    @Test
    fun `候选链覆盖新旧两形态且新形态在前`() {
        val candidates = MCP_SETTINGS_CONFIGURABLE_CLASSES
        assertEquals(
            "候选链首项必须是 263 新形态（intellij.mcpserver.frontend 模块）——排在旧形态之后时，" +
                "高版本会先撞空一次旧 FQN 才回落，探测日志与真机排障都会指向错的类",
            newFormFqn,
            candidates.first(),
        )
        assertTrue(
            "候选链必须保留 262/更早形态 $legacyFormFqn（老版本兼容面），当前=$candidates",
            candidates.contains(legacyFormFqn),
        )
        assertTrue(
            "候选链出现未知条目，新增/更名请同时在本用例与 McpSettingsNavigation KDoc 登记证据：$candidates",
            candidates.all { it == newFormFqn || it == legacyFormFqn },
        )
    }

    @Test
    fun `页面 id 与平台插件描述一致`() {
        assertEquals(
            "页面 id 是平台对外寻址契约（263 插件描述 id=\"com.intellij.mcpserver.settings\"）；" +
                "改它必须先改 263 安装内 mcpserver-plugin.jar 的 META-INF/plugin.xml 证据注释",
            "com.intellij.mcpserver.settings",
            MCP_SETTINGS_PAGE_ID,
        )
    }

    @Test
    fun `设置页类名只允许出现在候选链文件`() {
        val navigationFile = "src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpSettingsNavigation.kt"
        val offenders = repoRoot().resolve("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to it.readText() }
            .filter { (path, text) ->
                path != navigationFile && (text.contains(newFormFqn) || text.contains(legacyFormFqn))
            }
            .map { (path, _) -> path }
            .toList()
        assertTrue(
            "设置页类名出现在 $navigationFile 之外：$offenders。平台搬动设置页（263 已发生一次）时这些" +
                "写死点会静默失配并把用户引向「版本不够」——一律改调 openMcpServerSettingsPage(...)",
            offenders.isEmpty(),
        )
    }
}
