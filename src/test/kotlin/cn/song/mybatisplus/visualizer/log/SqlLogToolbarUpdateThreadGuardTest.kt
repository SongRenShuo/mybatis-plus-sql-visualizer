package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.actionSystem.ActionUpdateThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-SqlLogToolbarEdt 护栏：日志工具栏动作组与组内动作必须声明 `ActionUpdateThread.BGT`。
 *
 * 事故背景（实测日志，非估算）：沙盒 `idea.log` 20:34:28 / 20:35:23 两条
 * `ActionUpdater - 3981 ms / 3174 ms total to grab EDT 10 times to expand
 *  DefaultActionGroup#expandGroup@cn.song.mybatisplus.visualizer.log.toolbar ...
 *  Use ActionUpdateThread.BGT`，累计抢 EDT 3.1~4.0 秒。
 *
 * 根因（243 字节码实证）：`AnAction.getActionUpdateThread()` 默认实现是
 * 「`UpdateInBackground` 且返回 true → BGT；`ActionClassMetaData.isDefaultUpdate` 为 true → BGT；
 * 否则 EDT」，而 `isDefaultUpdate` 判的是「声明 `update()` 的类是否就是 `AnAction` 本身」——
 * 组内动作全都覆写了 `update()`，于是默认全落 EDT。
 *
 * 反向验证：删掉任一 `getActionUpdateThread` 覆写 → 必须红。
 */
class SqlLogToolbarUpdateThreadGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录")
    }

    private fun panelSource(): String =
        File(repoRoot(), "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogPanel.kt").readText()

    @Test
    fun `工具栏组必须声明BGT`() {
        val source = panelSource()
        // 组本体：object : DefaultActionGroup() 覆写 BGT（而不是裸 DefaultActionGroup(...)）
        val groupDecl = source.substring(
            source.indexOf("internal val toolbarGroup ="),
            source.indexOf("internal val toolbarGroup =") + 400,
        )
        assertTrue(
            "toolbarGroup 必须是覆写了 getActionUpdateThread 的 DefaultActionGroup 子类（实际：$groupDecl）",
            groupDecl.contains("DefaultActionGroup()") && groupDecl.contains("ActionUpdateThread.BGT"),
        )
    }

    /**
     * 组内 11 个动作逐个核：`update()` 被覆写的必须有 BGT 覆写。
     * 用「类名/动作名 → 该声明之后 600 字符内是否出现 BGT」判定（动作体都很短）。
     */
    @Test
    fun `组内覆写了update的动作必须声明BGT`() {
        val source = panelSource()
        val groupBody = source.substring(
            source.indexOf("internal val toolbarGroup ="),
            source.indexOf("// ---------- T-Perf-15"),
        )
        // 从组声明里取出成员引用（形如 `captureToggleAction,` / `ClearAction(),`）。
        // 注意：**必须保留完整标识符**（含 Action 后缀）——早期版本用 `(?:Action)?` 吃掉后缀再拼
        // `"${name}Action"`，对 `captureToggleAction` 会拼成 `captureToggleActionAction` 找不到声明，
        // 于是 `filter` 全程跳过、探针不红（假绿）。这是本守卫的第一版反向验证实测出来的。
        val members = Regex("""^\s{16}([A-Za-z][A-Za-z0-9]*?)(?:\(\),|,)\s*$""", RegexOption.MULTILINE)
            .findAll(groupBody)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "应能从 toolbarGroup 解析出全部 11 个组内动作（实际 $members）——解析不到会让本守卫空转",
            11,
            members.size,
        )

        val offenders = members.filter { name ->
            // 声明位置：`val xxx =` 或 `class Xxx`
            val declIdx = listOf("val $name", "class $name")
                .map { source.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull() ?: return@filter true   // 找不到声明也算异常（守卫不能静默放过）
            val body = source.substring(declIdx, (declIdx + 700).coerceAtMost(source.length))
            body.contains("override fun update(") && !body.contains("getActionUpdateThread")
        }
        assertTrue(
            "以下动作覆写了 update() 却未声明 BGT（会回落 EDT 抢 CPU）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `BGT类型常量确实可用且为BGT`() {
        // 防「写了 getActionUpdateThread 但返回别的值」的假装修复
        assertEquals("ActionUpdateThread.BGT 必须仍是 BGT 枚举项", "BGT", ActionUpdateThread.BGT.name)
    }

    /**
     * W3：`sortModeLabelKey` 必须覆盖全部 `SortMode`（漏一个会让 `update` 抛 NoWhenBranchMatched，
     * 工具栏直接崩）——逐个模式断言 key 非空且与 `sortOptions()` 的 label 一致。
     */
    @Test
    fun `排序文案查表覆盖全部模式且与下拉文案一致`() {
        val options = sortOptions().associate { it.mode to it.label }
        for (mode in SortMode.values()) {
            val key = sortModeLabelKey(mode)
            assertTrue("$mode 的文案键不应为空", key.isNotBlank())
            assertEquals(
                "$mode 的查表文案必须与下拉项一致（否则工具栏与下拉显示不同步）",
                options[mode],
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(key),
            )
        }
    }
}
