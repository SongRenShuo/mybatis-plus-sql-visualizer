package cn.song.mybatisplus.visualizer.convention

import cn.song.mybatisplus.visualizer.config.SettingsScenarioGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 新增 Boolean 必须有场景归属（T-SettingsConverge 守卫，2026-10-31）。
 *
 * 动机：设置收敛的产物 [SettingsScenarioGroups] 是「场景 → 设置键」的单一事实源，但它靠人工
 * 维护——往 [cn.song.mybatisplus.visualizer.config.PluginSettings.State] 加一个 Boolean 字段却忘了
 * 归组，清单会静默缺一项，按场景呈现/推荐预设时该开关就永远不出现（与「幽灵键」同类的静默缺口，
 * 光看面板和单测都发现不了）。本守卫把两件事绑死：**源码里的每个 Boolean 字段都在清单里**。
 *
 * 判定口径（源码扫描，零平台依赖 → testFast 快桶）：
 *  - 断言一：扫 `PluginSettings.kt` 的 `var xxx: Boolean` 字段名，与
 *    [SettingsScenarioGroups.COVERED_FIELDS] 对账，出现未归组字段即红并点名；
 *  - 断言二：反方向——清单里有、源码里已不存在的陈旧键（字段删除/改名后清单静默失效）即红；
 *  - 断言三：四场景常量与分组表一一对应、每场景清单非空、反向索引与正向清单一致。
 *
 * 判定内核 [booleanFieldsOf] / [ungroupedBooleanFields] / [staleGroupKeys] 可注入合成源码，
 * 主用例与自证用例共用同一实现（`test_判定内核自证_…` 两例）。
 *
 * 反向验证（实施轮实测，两次读数）：临时在 `PluginSettings.State` 加一个未归组的 Boolean →
 * 断言一红并点名该字段；撤回后复绿。
 */
class SettingsScenarioGroupCoverageGuardTest {

    /**
     * 提取器（SITES）：按源码顺序取 `var xxx: Boolean` 的字段名并去重。
     *
     * 只认 `var`（State 主构造体的可持久化字段都是 `var`）；`\w` 在 Java 正则里只认 ASCII，
     * 本仓字段名全为 ASCII，够用。`\b` 收尾防 `BooleanXxx` 这类更长类型名被误认。
     */
    internal fun booleanFieldsOf(settingsSource: String): List<String> =
        Regex("""\n\s+var\s+(\w+)\s*:\s*Boolean\b""")
            .findAll(settingsSource)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /** 判定内核（VIOLATIONS）：源码里有、但未归入任何场景的 Boolean 字段（保持声明顺序）。 */
    internal fun ungroupedBooleanFields(settingsSource: String, covered: Set<String>): List<String> =
        booleanFieldsOf(settingsSource).filter { it !in covered }

    /** 判定内核（VIOLATIONS）：场景清单里已不是 Boolean 字段的陈旧键（排序后输出，便于比对）。 */
    internal fun staleGroupKeys(settingsSource: String, covered: Set<String>): List<String> {
        val fields = booleanFieldsOf(settingsSource).toSet()
        return covered.filter { it !in fields }.sorted()
    }

    // ------------------------------------------------------------------
    // 断言一：每个 Boolean 字段至少归属一个场景
    // ------------------------------------------------------------------

    @Test
    fun test_每个Boolean字段至少归属一个场景() {
        val source = settingsSource()
        val fields = booleanFieldsOf(source)
        // 防空跑：扫描面没解析出字段时本守卫毫无判别力（正则写坏会静默全绿）
        assertTrue("扫描面异常：PluginSettings.kt 应解析出 >= 50 个 Boolean 字段，实际 ${fields.size}", fields.size >= 50)

        val offenders = ungroupedBooleanFields(source, SettingsScenarioGroups.COVERED_FIELDS)
        assertTrue(
            "设置项缺少场景归属（SettingsScenarioGroups 未收录）：$offenders。" +
                "新增 Boolean 设置必须在 SettingsScenarioGroups.GROUPS 的至少一个场景里登记字段名" +
                "（只打标，不改默认值、不改控件）",
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 断言二：清单不得腐烂（陈旧键）
    // ------------------------------------------------------------------

    @Test
    fun test_场景清单不得包含已不存在的Boolean键() {
        val stale = staleGroupKeys(settingsSource(), SettingsScenarioGroups.COVERED_FIELDS)
        assertTrue(
            "场景清单存在陈旧键（对应字段已删除或改名，清单静默失效）：$stale。" +
                "删字段时须同步从 SettingsScenarioGroups.GROUPS 移除该键",
            stale.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 断言三：四场景表结构与反向索引自洽
    // ------------------------------------------------------------------

    @Test
    fun test_四个场景常量与分组表一一对应() {
        assertEquals(
            "场景顺序即建议的呈现顺序",
            listOf("REVIEW", "TUNING", "AI_COLLAB", "CAPTURE_DIAG"),
            SettingsScenarioGroups.ALL,
        )
        assertEquals("分组表键集应与 ALL 完全一致", SettingsScenarioGroups.ALL.toSet(), SettingsScenarioGroups.GROUPS.keys)
        SettingsScenarioGroups.ALL.forEach { scenario ->
            val keys = SettingsScenarioGroups.fieldsOf(scenario)
            assertTrue("场景 $scenario 的键清单不得为空", keys.isNotEmpty())
            assertEquals("场景 $scenario 内键不得重复", keys.size, keys.toSet().size)
        }
        // 反向索引与正向清单一致（scenariosOf 是呈现层要用的查询面）
        SettingsScenarioGroups.GROUPS.forEach { (scenario, keys) ->
            keys.forEach { key ->
                assertTrue("$key 应能反查回场景 $scenario", scenario in SettingsScenarioGroups.scenariosOf(key))
            }
        }
        assertEquals(
            "COVERED_FIELDS 应与 GROUPS 的并集相等",
            SettingsScenarioGroups.GROUPS.values.flatten().toSet(),
            SettingsScenarioGroups.COVERED_FIELDS,
        )
    }

    // ------------------------------------------------------------------
    // 自证语料（判定内核灵敏度；与主用例共用同一实现）
    // ------------------------------------------------------------------

    /**
     * 自证：判定内核**看得见**未归组的 Boolean 形态，且对已归组字段不误报、对陈旧键能点名。
     * 若这条失败说明正则/对账逻辑写坏了，而不是生产代码有问题。
     */
    @Test
    fun test_判定内核自证_未归组的Boolean必被点名且陈旧键可检出() {
        val synthetic = """
            data class State(
                var knownSwitch: Boolean = true,
                var brandNewSwitch: Boolean = false,
            )
        """.trimIndent()

        assertEquals(
            "判定内核应恰好点名未归组字段",
            listOf("brandNewSwitch"),
            ungroupedBooleanFields(synthetic, setOf("knownSwitch")),
        )
        // 反向对照：全部已归组时不得误报
        assertTrue(
            "已归组的字段不得被点名",
            ungroupedBooleanFields(synthetic, setOf("knownSwitch", "brandNewSwitch")).isEmpty(),
        )
        // 反方向内核：清单里有、源码里没有的键要被点名
        assertEquals(
            listOf("removedSwitch"),
            staleGroupKeys(synthetic, setOf("knownSwitch", "removedSwitch")),
        )
    }

    /**
     * 自证：非 Boolean 字段（`var dialect: String` / `var mode: Int`）不进扫描面——
     * 否则守卫会把普通设置项误报成「未归组的 Boolean」。
     */
    @Test
    fun test_判定内核自证_非Boolean字段不得误判() {
        val synthetic = """
            data class State(
                var dialect: String = "",
                var mode: Int = 0,
            )
        """.trimIndent()
        assertTrue("非 Boolean 字段不应进入扫描面", booleanFieldsOf(synthetic).isEmpty())
        assertTrue("无 Boolean 字段时应无违规", ungroupedBooleanFields(synthetic, emptySet()).isEmpty())
        assertTrue("无 Boolean 字段时陈旧键判定也不应报（清单为空）", staleGroupKeys(synthetic, emptySet()).isEmpty())
    }

    // ------------------------------------------------------------------
    // 资源定位
    // ------------------------------------------------------------------

    private fun settingsSource(): String =
        repoRoot().resolve("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt").readText()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }
}
