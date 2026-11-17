package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 控制台数据源下拉接管组的 **update 线程契约**守卫（T-ThreadFix A，2026-11-04 真机 243 沙盒）。
 *
 * 缺陷实证（一次「预览+执行」，`idea.log` 12:40:24）：
 * ```
 * SEVERE - PreCachedDataContext - 'psi.File' is requested on EDT by
 *   ChooseDataSourceTakeoverGroup#presentation@EditorToolbar
 *   at ChooseDataSourceAction.getClientPsiFile → getContext → update
 *   at ChooseDataSourceTakeoverGroup.update(ChooseDataSourceTakeover.kt:170)
 * ```
 * 同一条链还叠 9 条 `SlowOperations`（`PsiManager.findFile` → `FileManagerImpl.findDirectory`
 * → `ProjectFileIndexImpl.isUnderIgnored` → `WorkspaceFileIndexDataImpl.ensureIsUpToDate`）——
 * 触发点是 `embedConsole` 把控制台组件挂进可见层级，原生 header 工具栏立刻刷新。
 *
 * 根因（243 字节码实证）：`AnAction.getActionUpdateThread()` 默认实现是「实现 `UpdateInBackground`
 * 且为 true → BGT；`ActionClassMetaData.isDefaultUpdate` 为 true → BGT；否则 EDT」，而
 * `isDefaultUpdate` 判的是「重写 `update()` 的类是不是 `AnAction` 本身」。本类覆写了 `update()`
 * 与 `displayTextInToolbar()`，于是默认落 **EDT**；它委托的原生 `ChooseDataSourceAction` javap
 * 实证返回 **BGT**（PSI 读走平台预缓存 DataContext）。包装组必须跟随声明 BGT。
 *
 * 为什么是形态守卫：EDT 慢操作断言只在真实应用 EDT 上生效（headless 测试线程不是真实 EDT），
 * 行为测试测不出「这段 update 跑在哪个线程」；`ActionUpdateThread` 声明是唯一可机器化判据
 * （同 `log/SqlLogToolbarUpdateThreadGuardTest` 的口径）。
 */
class TakeoverUpdateThreadGuardTest {

    private val takeoverPath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/ChooseDataSourceTakeover.kt"

    /**
     * 判定内核（VIOLATIONS）：接管组的 update 线程契约。
     *
     * 两条都要：① 显式声明 BGT；② `update` 体内不得出现 PSI/Swing/EDT-only 令牌——声明成 BGT
     * 之后这些调用会变成**跨线程误用**，比原来更糟。
     */
    internal fun takeoverUpdateThreadOffenders(source: String): List<String> {
        val src = stripComments(source)
        val out = ArrayList<String>()
        if (!src.contains("class ChooseDataSourceTakeoverGroup")) {
            out += "$takeoverPath: 未定位到 ChooseDataSourceTakeoverGroup（类改名/搬家后守卫须同步跟进）"
            return out
        }
        if (!BGT_DECL.containsMatchIn(src)) {
            out += "$takeoverPath: 未声明 `override fun getActionUpdateThread(): ActionUpdateThread = " +
                "ActionUpdateThread.BGT`（本类覆写了 update，平台默认落 EDT；原生 ChooseDataSourceAction " +
                "javap 实证是 BGT，EDT 上读 PSI_FILE 必打 PreCachedDataContext SEVERE）"
        }
        val updateBody = bodyOf(src, "update")
        if (updateBody == null) {
            out += "$takeoverPath: 未定位到 update 函数体（update 被删除/改名？）"
            return out
        }
        for ((token, why) in UPDATE_BANNED_TOKENS) {
            if (updateBody.contains(token)) {
                out += "$takeoverPath: update 函数体内出现 `$token`——$why"
            }
        }
        return out
    }

    @Test
    fun `接管组update必须声明BGT且update体不得触PSI与Swing`() {
        val offenders = takeoverUpdateThreadOffenders(read(takeoverPath))
        assertTrue(
            "控制台数据源下拉接管组的 update 线程契约被破坏（EDT 读 PSI_FILE = 平台 " +
                "PreCachedDataContext SEVERE + SlowOperations 9 连）。违规 ${offenders.size} 处：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 自证：未声明 BGT 必红、update 体触 PSI 必红、合规形态不误报（正反两面）。 */
    @Test
    fun `自证_接管组未声明BGT与update体触PSI必被点名`() {
        val noDeclaration = """
            internal class ChooseDataSourceTakeoverGroup(private val original: Any) : ActionGroup("", true) {
                override fun update(e: Any) {
                    original.update(e)
                }
            }
        """.trimIndent()
        val missing = takeoverUpdateThreadOffenders(noDeclaration)
        assertTrue("未声明 BGT 必须被点名：$missing", missing.any { it.contains("ActionUpdateThread.BGT") })

        val psiInUpdate = """
            internal class ChooseDataSourceTakeoverGroup(private val original: Any) : ActionGroup("", true) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                override fun update(e: Any) {
                    val psi = PsiManager.getInstance(project).findFile(vf)
                    original.update(e)
                }
            }
        """.trimIndent()
        val psiOffenders = takeoverUpdateThreadOffenders(psiInUpdate)
        assertTrue(
            "BGT 声明下 update 体触 PSI 必须被点名：$psiOffenders",
            psiOffenders.any { it.contains("PsiManager") },
        )

        // T-TemplatePresentationWarn：update 体里直写模板呈现的 setEnabled/setVisible 必被点名。
        val templateWrite = """
            internal class ChooseDataSourceTakeoverGroup(private val original: Any) : ActionGroup("", true) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                override fun update(e: Any) {
                    original.update(e)
                    templatePresentation.setEnabled(e.presentation.isEnabled)
                    templatePresentation.setVisible(e.presentation.isVisible)
                }
            }
        """.trimIndent()
        val templateOffenders = takeoverUpdateThreadOffenders(templateWrite)
        assertTrue(
            "模板呈现直写 setEnabled/setVisible 必须被点名：$templateOffenders",
            templateOffenders.any { it.contains("templatePresentation.setEnabled") } &&
                templateOffenders.any { it.contains("templatePresentation.setVisible") },
        )

        val good = """
            internal class ChooseDataSourceTakeoverGroup(private val original: Any) : ActionGroup("", true) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                override fun update(e: Any) {
                    original.update(e)
                    e.presentation.setPerformGroup(true)
                    templatePresentation.setPerformGroup(true)
                    templatePresentation.copyFrom(e.presentation)
                }
            }
        """.trimIndent()
        assertEquals(
            "合规形态不应误报：${takeoverUpdateThreadOffenders(good)}",
            0,
            takeoverUpdateThreadOffenders(good).size,
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun read(path: String): String {
        val f = File(path)
        assertTrue("守卫目标文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 剥注释（块注释涂白保留换行，行注释截断）——判定只看代码，KDoc 里「提」禁词不算违规。 */
    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    /** 函数声明点：`fun [接收者.]name(` 里 `(` 的下标；普通调用点不命中。 */
    private fun declarationAt(src: String, fn: String): Int? {
        var at = src.indexOf("$fn(")
        while (at > 0) {
            var back = at
            while (back > 0 && (src[back - 1].isLetterOrDigit() || src[back - 1] == '.' || src[back - 1] == '_')) {
                back--
            }
            var s = back
            while (s > 0 && src[s - 1].isWhitespace()) s--
            if (s >= 3 && src.regionMatches(s - 3, "fun", 0, 3) &&
                (s - 4 < 0 || !(src[s - 4].isLetterOrDigit() || src[s - 4] == '_'))
            ) {
                return at
            }
            at = src.indexOf("$fn(", at + fn.length + 1)
        }
        return null
    }

    /** 函数体区间：从声明后的首个 `{` 起按大括号配平（非固定长度窗口，避免跨界读到邻居函数）。 */
    private fun bodyRange(src: String, declAt: Int): IntRange? {
        val open = src.indexOf('{', declAt)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return open..i
                }
            }
            i++
        }
        return null
    }

    private fun bodyOf(src: String, fn: String): String? =
        declarationAt(src, fn)?.let { bodyRange(src, it) }?.let { src.substring(it.first, it.last + 1) }

    private companion object {
        /** BGT 声明形态（`=` 表达式体；含显式类型标注的 `override fun …(): ActionUpdateThread`）。 */
        private val BGT_DECL = Regex(
            """override\s+fun\s+getActionUpdateThread\s*\(\s*\)\s*:\s*ActionUpdateThread\s*=\s*ActionUpdateThread\.BGT""",
        )

        /**
         * BGT update 体内禁用的令牌——命中任一条即「声明成 BGT 之后把 EDT-only 调用搬进了后台线程」。
         *
         * 注意：`original.update(e)` 读 PSI 走平台预缓存 DataContext（原生动作自己就是 BGT），
         * 所以 `PsiManager` / `findFile` 只禁**本类自己**直取，不针对委托调用。
         */
        private val UPDATE_BANNED_TOKENS = listOf(
            "PsiManager" to "BGT 上直取 PSI 应由原生 update 的预缓存 DataContext 提供",
            "findFile(" to "同上：PSI/VFS 查找不得出现在 update 体",
            "SwingUtilities" to "BGT 不得触碰 Swing",
            "javax.swing" to "BGT 不得触碰 Swing 组件",
            "invokeLater" to "update 体只做呈现计算，不派发 EDT 任务",
            "WriteAction" to "update 体不得写模型/文档",
            "WriteCommandAction" to "update 体不得写模型/文档",
            // T-TemplatePresentationWarn（2026-11-14 真机 243 日志 40 条 WARN）：模板呈现上直接
            // setEnabled/setVisible 会被 243 `Presentation.assertNotTemplatePresentation` 点名
            // （`warnInProduction` 带全栈，263 有升级为 error 的风险）。合规镜像通道是平台自己的
            // `copyFrom(Presentation)`（私有实现直写 myFlags 位、不经断言、且补发属性变化事件）。
            "templatePresentation.setEnabled" to "模板呈现的 setEnabled 是平台断言面" +
                "（243 warnInProduction），镜像必须走 `templatePresentation.copyFrom(e.presentation)`",
            "templatePresentation.setVisible" to "模板呈现的 setVisible 是平台断言面" +
                "（243 warnInProduction），镜像必须走 `templatePresentation.copyFrom(e.presentation)`",
        )
    }
}
