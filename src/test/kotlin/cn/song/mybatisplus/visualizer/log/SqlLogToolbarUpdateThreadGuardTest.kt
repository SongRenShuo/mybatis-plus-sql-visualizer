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
 * K2J 适配：master 守卫扫 `src/main/kotlin` + `.kt`，本分支生产树是 `src/main/java` + `.java`
 * （守卫扫描根适配，Kotlin 判据已改写为 Java 等价形态：KDoc/成员列表/类体定位全按 Java 语法）。
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

    /** K2J：生产树扫描根 = src/main/java + .java（master 为 src/main/kotlin + .kt）。 */
    private fun panelSource(): String =
        File(repoRoot(), "src/main/java/cn/song/mybatisplus/visualizer/log/SqlLogPanel.java").readText()

    /** 从 anchorIdx 起找首个 `{` 并做括号配对（粗略处理字符串字面量），返回含括号的完整类体。 */
    private fun braceBody(source: String, anchorIdx: Int): String? {
        val open = source.indexOf('{', anchorIdx)
        if (open < 0) return null
        var depth = 0
        var inStr = false
        var i = open
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' && (i == 0 || source[i - 1] != '\\') -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }

    /** 命名内部类体：`class Xxx` 声明 → 类体。 */
    private fun classBody(source: String, className: String): String? {
        val idx = source.indexOf("class $className")
        return if (idx < 0) null else braceBody(source, idx)
    }

    /** 匿名类体（字段初始化器）：`xxx = new` 锚 → 实例化体。 */
    private fun anonymousBody(source: String, fieldName: String): String? {
        val idx = source.indexOf("$fieldName = new")
        return if (idx < 0) null else braceBody(source, idx)
    }

    // 匿名字段初始化器里实例化的平台基类（这些名字不是目标类，须直接取匿名体）
    private val BASE_ACTION_TYPES = setOf("AnAction", "ToggleAction")

    /**
     * 组内成员 → 类体定位。
     *
     * 反向验证实测缺陷（假绿）：字段成员**不能按首字母大写猜类名**——`captureToggleAction` 猜成
     * `CaptureToggleAction` 找不到 `class` 声明，会回落「`= new` 锚后第一个 `{`」，误取到
     * toolbarGroup 匿名组体（那个体里恰好有 BGT），把真 offender 洗白。改为从赋值点
     * `xxx = new XxxClass()` 提取真实类名；平台基类名（匿名体）直接取 `= new` 锚的实例化体。
     */
    private fun memberBody(source: String, member: String, ctorCall: Boolean): String? {
        if (ctorCall) return classBody(source, member)
        val fieldDecl = Regex(java.util.regex.Pattern.quote(member) + """\s*=\s*new\s+(\w+)""").find(source)
        val className = fieldDecl?.groupValues?.getOrNull(1)
        return when {
            className == null -> anonymousBody(source, member)
            className in BASE_ACTION_TYPES -> anonymousBody(source, member)
            else -> classBody(source, className) ?: anonymousBody(source, member)
        }
    }

    @Test
    fun `工具栏组必须声明BGT`() {
        val source = panelSource()
        // 组本体：匿名 DefaultActionGroup 子类覆写 BGT（而不是裸 new DefaultActionGroup(...)）
        val groupAnchor = source.indexOf("toolbarGroupImpl = new DefaultActionGroup() {")
        assertTrue("toolbarGroup 必须是匿名 DefaultActionGroup 子类（找不到组声明锚）", groupAnchor >= 0)
        val groupDecl = source.substring(groupAnchor, (groupAnchor + 400).coerceAtMost(source.length))
        assertTrue(
            "toolbarGroup 必须覆写 getActionUpdateThread 返回 BGT（实际：$groupDecl）",
            groupDecl.contains("ActionUpdateThread.BGT"),
        )
    }

    /**
     * 组内 11 个动作逐个核：除登记例外（agentPauseAction，master 同状态）外，
     * 每个成员类体必须声明 `getActionUpdateThread` 返回 BGT。
     * 成员列表从 `toolbarGroupImpl.addAll(...)` 实时解析——解析不到 11 个即红（守卫不能空转）。
     */
    @Test
    fun `组内动作必须声明BGT`() {
        val source = panelSource()
        val start = source.indexOf("toolbarGroupImpl.addAll(")
        val end = source.indexOf("this.toolbarGroup = toolbarGroupImpl;")
        assertTrue("找不到 toolbarGroup 成员列表锚（生产形态漂移，守卫失效）", start >= 0 && end > start)
        val groupBody = source.substring(start, end)
        // 从成员列表解析出成员名 + 形态（`new Xxx(),` = 构造调用成员；`xxx,` = 字段引用成员）。
        // 注意：必须保留完整标识符（含 Action 后缀）——早期版本用 `(?:Action)?` 吃掉后缀再拼
        // 类名会找不到声明，filter 全程跳过、探针不红（假绿）。这是 master 守卫第一版反向验证实测出来的。
        val memberMatches = Regex("""^\s{16}(new |this\.)?([A-Za-z][A-Za-z0-9]*?)(?:\(\),|,)\s*(?://.*)?$""", RegexOption.MULTILINE)
            .findAll(groupBody)
            .map { it.groupValues[2] to (it.groupValues[1] == "new ") }
            .toList()
        assertEquals(
            "应能从 toolbarGroup 解析出全部 11 个组内动作（实际 $memberMatches）——解析不到会让本守卫空转",
            11,
            memberMatches.size,
        )

        // master（ed5005d4）同状态例外：AgentPauseAction 覆写 update 但未声明 BGT——方案 B 无条件隐藏
        // （update 只写 presentation 可见性，EDT 无害），master 守卫对其同样不判。禁「顺手补 BGT」。
        val edtExceptions = setOf("agentPauseAction")

        val offenders = memberMatches.filter { (name, ctorCall) ->
            val body = memberBody(source, name, ctorCall)
                ?: return@filter true   // 找不到类体也算异常（守卫不能静默放过）
            if (name in edtExceptions) {
                false
            } else {
                !body.contains("getActionUpdateThread") || !body.contains("ActionUpdateThread.BGT")
            }
        }.map { it.first }
        assertTrue(
            "以下动作未声明 BGT（会回落 EDT 抢 CPU）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `BGT类型常量确实可用且为BGT`() {
        // 防「写了 getActionUpdateThread 但返回别的值」的假装修复
        assertEquals("ActionUpdateThread.BGT 必须仍是 BGT 枚举项", "BGT", ActionUpdateThread.BGT.name)
    }

    /**
     * W3：`sortModeLabelKey` 必须覆盖全部 `SortMode`（漏一个会让 `update` 抛异常，工具栏直接崩）
     * ——逐个模式断言 key 非空且与 `sortOptions()` 的 label 一致。
     */
    @Test
    fun `排序文案查表覆盖全部模式且与下拉文案一致`() {
        val options = cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.sortOptions().associate { it.mode() to it.label() }
        for (mode in SortMode.values()) {
            val key = cn.song.mybatisplus.visualizer.log.SqlLogPanelKt.sortModeLabelKey(mode)
            assertTrue("$mode 的文案键不应为空", key.isNotBlank())
            assertEquals(
                "$mode 的查表文案必须与下拉项一致（否则工具栏与下拉显示不同步）",
                options[mode],
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(key),
            )
        }
    }
}
