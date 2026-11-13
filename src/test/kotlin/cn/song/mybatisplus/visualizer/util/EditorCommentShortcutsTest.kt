package cn.song.mybatisplus.visualizer.util

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-PreviewLineCommentDash O-134/D136：预览行注释统一 `--` 接管件的行为契约。
 *
 * 驱动方式＝**动作注册表解析落点**（`ActionManager.getAction(ACTION_COMMENT_LINE)` 后以自建
 * DataContext 驱动 actionPerformed/update，与 keymap 的取用链路同源），不依赖真键盘分发。
 * 首跑教训（本类存在的原因）：原计划层位 `EditorActionManager.setActionHandler` 对该 id **必抛
 * ClassCastException**（平台注册的是 MultiCaretCodeInsightAction 子类，非 EditorAction），
 * 本类即把接管层位钉在可用的动作注册层上。
 */
class EditorCommentShortcutsTest : BasePlatformTestCase() {

    private lateinit var originalAction: AnAction

    override fun setUp() {
        super.setUp()
        originalAction = ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)!!
    }

    override fun tearDown() {
        try {
            // 兜底复原注册表（各用例 finally 已复原，防断言失败路径残留接管件）
            ActionManager.getInstance().replaceAction(IdeActions.ACTION_COMMENT_LINE, originalAction)
        } finally {
            super.tearDown()
        }
    }

    private fun editor(text: String): Editor =
        EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument(text), project)

    /** 显式携带 EDITOR 的测试上下文（裸编辑器组件不提供 EDITOR——探针实证 editorInCtx=false）。 */
    private fun contextOf(ed: Editor): DataContext = object : DataContext {
        override fun getData(dataId: String): Any? =
            if (CommonDataKeys.EDITOR.`is`(dataId)) ed else null
    }

    private fun eventFor(action: AnAction, ed: Editor): AnActionEvent =
        AnActionEvent.createFromAnAction(action, null, "test", contextOf(ed))

    /** 与 keymap 同源：从动作注册表解析当前动作后执行。 */
    private fun triggerLineComment(ed: Editor) {
        val action = ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)!!
        action.actionPerformed(eventFor(action, ed))
    }

    /** 原动作替身：委托断言的被调探针（不经注册表，直接构造接管件注入）。 */
    private class RecordingAction : AnAction() {
        var performed = 0
        var updates = 0
        override fun actionPerformed(e: AnActionEvent) {
            performed++
        }

        override fun update(e: AnActionEvent) {
            updates++
            e.presentation.isEnabled = true
        }
    }

    private fun selectAll(ed: Editor) {
        WriteCommandAction.runWriteCommandAction(project) {
            ed.selectionModel.setSelection(0, ed.document.textLength)
        }
    }

    // ------------------------------------------------------------------
    // 纯逻辑：toggleLines 行级切换
    // ------------------------------------------------------------------

    fun test纯逻辑_补前缀保缩进() {
        // 前缀插在首个非空白字符前：前导缩进原样保留（剥除侧对称只吞前缀后一个空格）
        assertEquals(
            listOf("-- SELECT 1;", "    -- FROM t"),
            EditorCommentShortcuts.toggleLines(listOf("SELECT 1;", "    FROM t")),
        )
        assertEquals(
            listOf("SELECT 1;", "    FROM t"),
            EditorCommentShortcuts.toggleLines(listOf("-- SELECT 1;", "    -- FROM t")),
        )
    }

    fun test纯逻辑_已注释行幂等不叠加() {
        assertEquals(
            listOf("-- SELECT 1;", "-- FROM t"),
            EditorCommentShortcuts.toggleLines(listOf("-- SELECT 1;", "FROM t")),
        )
    }

    fun test纯逻辑_全已注释则整段剥除_双横线与井号双认() {
        assertEquals(
            listOf("SELECT 1;", "FROM t", "WHERE 1 = 1"),
            EditorCommentShortcuts.toggleLines(listOf("-- SELECT 1;", "# FROM t", "-- WHERE 1 = 1")),
        )
    }

    fun test纯逻辑_空白行双态原样保留() {
        assertEquals(
            listOf("-- a", "", "-- b"),
            EditorCommentShortcuts.toggleLines(listOf("a", "", "b")),
        )
        assertEquals(
            listOf("a", "", "b"),
            EditorCommentShortcuts.toggleLines(listOf("-- a", "", "-- b")),
        )
    }

    fun test纯逻辑_剥除只吞前缀后一个空格() {
        assertEquals("  x", strip("--   x"))
        assertEquals("x", strip("--x"))
        assertEquals("x", strip("# x"))
    }

    private fun strip(line: String): String = EditorCommentShortcuts.toggleLines(listOf(line)).single()

    // ------------------------------------------------------------------
    // 动作本体：打标接管 / 未打标双向委托（替身注入，不经注册表）
    // ------------------------------------------------------------------

    fun test未打标编辑器_动作双向委托原动作零行为差异() {
        val fake = RecordingAction()
        val action = EditorCommentShortcuts.DashLineCommentAction(fake)
        val ed = editor("SELECT 2;")
        try {
            action.actionPerformed(eventFor(action, ed))
            action.update(eventFor(action, ed))
            assertEquals("未打标 → actionPerformed 必须委托原动作", 1, fake.performed)
            assertEquals("未打标 → update 必须委托原动作", 1, fake.updates)
            assertEquals("本接管件不得改动未打标编辑器文档", "SELECT 2;", ed.document.text)
        } finally {
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test打标编辑器_动作走本地切换且不触原动作() {
        val fake = RecordingAction()
        val action = EditorCommentShortcuts.DashLineCommentAction(fake)
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            action.actionPerformed(eventFor(action, ed))
            action.update(eventFor(action, ed))
            assertEquals("-- SELECT 1;", ed.document.text)
            assertEquals("打标 → 不得委托原动作", 0, fake.performed)
            assertEquals("打标 → update 不得委托原动作", 0, fake.updates)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    // ------------------------------------------------------------------
    // 注册表链路：打标编辑器接管 / 卸载复原 / 计数与幂等
    // ------------------------------------------------------------------

    fun test打标编辑器_无选区注释光标行插双横线() {
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            assertTrue(
                "install 应把该 id 换入自有动作",
                ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)
                    is EditorCommentShortcuts.DashLineCommentAction,
            )
            triggerLineComment(ed)
            assertEquals("-- SELECT 1;", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test再按一次对称剥除() {
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            triggerLineComment(ed)
            triggerLineComment(ed)
            assertEquals("SELECT 1;", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test遗留井号残留行可剥除() {
        // 修复前 MySQL commenter 产出的 `#` 行（残留）+ 修复后的 `--` 行——整段剥除时一并剥
        val ed = editor("# old comment\n-- SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            selectAll(ed)
            triggerLineComment(ed)
            assertEquals("old comment\nSELECT 1;", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test多行选区按行粒度整段切换_再按对称还原() {
        val ed = editor("a\nb\nc")
        try {
            EditorCommentShortcuts.install(ed)
            // 选区跨第 1~2 行（偏移 0..3 落在前两行的行界内）→ 行粒度只波及前两行
            WriteCommandAction.runWriteCommandAction(project) {
                ed.selectionModel.setSelection(0, 3)
            }
            triggerLineComment(ed)
            assertEquals("-- a\n-- b\nc", ed.document.text)
            // 前一次触发已按新范围回设选区：两行全已注释 → 再按对称剥除
            triggerLineComment(ed)
            assertEquals("a\nb\nc", ed.document.text)
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test混合选区_已注释行幂等未注释行补前缀() {
        val ed = editor("-- a\nb")
        try {
            EditorCommentShortcuts.install(ed)
            selectAll(ed)
            triggerLineComment(ed)
            assertEquals("-- a\n-- b", ed.document.text)
            assertFalse("不得产出 MySQL `#` 前缀", ed.document.text.contains('#'))
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test卸载后复原原动作() {
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            assertTrue(
                "前置：应已在位自有动作",
                ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)
                    is EditorCommentShortcuts.DashLineCommentAction,
            )
            EditorCommentShortcuts.uninstall(ed)
            assertSame(
                "卸载后应复原平台原动作",
                originalAction,
                ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE),
            )
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    fun test引用计数_最后一个打标编辑器卸载才复原() {
        val e1 = editor("a")
        val e2 = editor("b")
        try {
            EditorCommentShortcuts.install(e1)
            EditorCommentShortcuts.install(e2)
            EditorCommentShortcuts.uninstall(e1)
            assertTrue(
                "还剩 e2 在场 → 应仍在位自有动作",
                ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)
                    is EditorCommentShortcuts.DashLineCommentAction,
            )
            EditorCommentShortcuts.uninstall(e2)
            assertSame(
                "最后一个卸载 → 复原平台原动作",
                originalAction,
                ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE),
            )
        } finally {
            EditorCommentShortcuts.uninstall(e1)
            EditorCommentShortcuts.uninstall(e2)
            EditorFactory.getInstance().releaseEditor(e1)
            EditorFactory.getInstance().releaseEditor(e2)
        }
    }

    fun test重复打标幂等_计数不虚增() {
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            EditorCommentShortcuts.install(ed) // 幂等：不重复计数
            EditorCommentShortcuts.uninstall(ed)
            assertSame(
                "重复打标不应虚增计数——卸载一次即复原",
                originalAction,
                ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE),
            )
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    /**
     * T-CommentShortcutKeymap：换入/换回不得改动键盘绑定本身。
     *
     * 换入后平台会给新实例回填 `ProxyShortcutSet(actionId)`（按 keymap 惰性解析），所以键位数必须
     * 与 keymap 对该 id 的映射一致——这同时是「先置 CustomShortcutSet.EMPTY」不丢键位的回归网。
     */
    fun test换入换回后快捷键绑定仍与keymap一致() {
        val keymapKeys = KeymapManager.getInstance().activeKeymap
            .getShortcuts(IdeActions.ACTION_COMMENT_LINE).size
        val ed = editor("SELECT 1;")
        try {
            EditorCommentShortcuts.install(ed)
            val installed = ActionManager.getInstance().getAction(IdeActions.ACTION_COMMENT_LINE)!!
            assertEquals(
                "换入后键位数应仍来自 keymap（ProxyShortcutSet 惰性解析）",
                keymapKeys,
                installed.shortcutSet.shortcuts.size,
            )
            EditorCommentShortcuts.uninstall(ed)
            assertEquals(
                "换回后原动作键位数应仍来自 keymap",
                keymapKeys,
                originalAction.shortcutSet.shortcuts.size,
            )
        } finally {
            EditorCommentShortcuts.uninstall(ed)
            EditorFactory.getInstance().releaseEditor(ed)
        }
    }

    /**
     * T-CommentShortcutKeymap 源码守卫：换动作**必须**经 `replaceActionSilently`（先置 EMPTY）。
     *
     * 行为级断言够不着——平台告警是 `Logger.warn(PluginException)`，仓内无日志捕获通道；
     * 而「忘了先置 EMPTY」在功能上完全无感（键位照旧生效），只有真机日志里刷
     * `ShortcutSet of global AnActions should not be changed outside of KeymapManager`。
     * 故按 PerfSweep27StaticGuardTest 的 `File("src/main/kotlin/$rel")` 先例做结构钉。
     */
    fun test源码守卫_换全局动作必须经replaceActionSilently() {
        val src = File("src/main/kotlin/cn/song/mybatisplus/visualizer/util/EditorCommentShortcuts.kt")
            .readText()
            .replace(Regex("""(?s)/\*.*?\*/"""), "")        // 剥块注释（本文件的 KDoc 里提到这些符号）
            .replace(Regex("""(?m)^\s*//.*$"""), "")         // 剥行注释
        assertEquals(
            "replaceAction 只允许出现在 replaceActionSilently 内（先置 CustomShortcutSet.EMPTY 再换）",
            1,
            Regex("""\breplaceAction\(""").findAll(src).count(),
        )
        val helper = src.substringAfter("fun ActionManager.replaceActionSilently", "")
        val emptySet = helper.indexOf("setShortcutSet(CustomShortcutSet.EMPTY)")
        val replace = helper.indexOf("replaceAction(")
        assertTrue("replaceActionSilently 必须存在（先置 EMPTY 的转调点）", helper.isNotEmpty())
        assertTrue(
            "必须先 setShortcutSet(CustomShortcutSet.EMPTY)（$emptySet）再 replaceAction（$replace）",
            emptySet in 0 until replace,
        )
    }
}
