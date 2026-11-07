package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-PerfSweep30 04a 守卫：HTML 报告落盘不得在 EDT 直调（性能分析 §4.6 登记的唯一 EDT 直写盘面）。
 *
 * 缺陷形态：`ShareDialog` 的导出回调（按钮 → `doOKAction` → `exportHtmlReport` /
 * `exportDiagnosticHtml`）在 EDT 里直接 `shareManager.saveHtmlReport` → `File.writeText`。
 * 量级小（单文件、用户显式点击）但属 File IO 红线面。
 *
 * 收口形态：落盘收进 `saveHtmlInBackground` 的 `Task.Backgroundable.run`，打开浏览器留在
 * `onSuccess`（EDT）。本守卫锁这一形态：
 *  ① `saveHtmlReport` 全文件只出现一次；
 *  ② 该调用点必须落在 `override fun run(` 之后、`override fun onSuccess` 之前；
 *  ③ `BrowserUtil.open` 必须在 `onSuccess` 之后（打开浏览器属 UI 动作，须回 EDT）。
 *
 * 为什么锁形态而非行为：headless 下 `FileSaverDialog` 弹不出来，导出链无法端到端驱动；
 * 形态守卫是线程归属这类问题唯一可机器化的判据（同族先例：LastUsedWriteOnEdtGuardTest）。
 */
class ShareExportOffEdtGuardTest {

    private fun source(): String = File(
        "src/main/kotlin/cn/song/mybatisplus/visualizer/share/ui/ShareDialog.kt",
    ).readText()

    /** 判定内核：返回违规说明（空 = 合规）。可注入合成源码自证。 */
    internal fun offendersOf(source: String): List<String> {
        val stripped = stripComments(source)
        val problems = mutableListOf<String>()

        val saveIdx = stripped.indexOf("saveHtmlReport(")
        if (saveIdx < 0) {
            problems.add("saveHtmlReport 调用点不见了（导出链被删或改名？）")
            return problems
        }
        val secondSave = stripped.indexOf("saveHtmlReport(", saveIdx + 1)
        if (secondSave >= 0) {
            problems.add("saveHtmlReport 出现多次——落盘必须收口到 saveHtmlInBackground 单点")
        }

        val runIdx = stripped.indexOf("override fun run(")
        val successIdx = stripped.indexOf("override fun onSuccess")
        if (runIdx < 0 || successIdx < 0) {
            problems.add("未找到 Task.Backgroundable 的 run/onSuccess 覆写（后台化形态被移除？）")
            return problems
        }
        if (saveIdx < runIdx || saveIdx > successIdx) {
            problems.add("saveHtmlReport 不在 Task.Backgroundable.run 内——EDT 直写盘回归")
        }

        val openIdx = stripped.indexOf("BrowserUtil.open(")
        if (openIdx < 0) {
            problems.add("BrowserUtil.open 不见了（导出后不打开报告？）")
        } else if (openIdx < successIdx) {
            problems.add("BrowserUtil.open 不在 onSuccess 内（UI 动作必须在 EDT 完成回调）")
        }
        return problems
    }

    @Test
    fun testExportSaveRunsOffEdt() {
        val problems = offendersOf(source())
        assertEquals("ShareDialog 导出落盘形态违规：$problems", emptyList<String>(), problems)
    }

    /** 反向对照（自证）：把落盘挪回 EDT 回调（run 之前）→ 守卫必须报出问题。 */
    @Test
    fun test自证_守卫看得见EDT直写形态() {
        val synthetic = """
            private fun exportHtmlReport() {
                val html = "x"
                shareManager.saveHtmlReport(html, "/tmp/a.html")
                BrowserUtil.open("/tmp/a.html")
            }
            private fun saveHtmlInBackground(html: String, file: File) {
                ProgressManager.getInstance().run(
                    object : Task.Backgroundable(project, "t", false) {
                        override fun run(indicator: ProgressIndicator) { }
                        override fun onSuccess() { }
                    },
                )
            }
        """.trimIndent()
        val problems = offendersOf(synthetic)
        assertTrue(
            "合成缺陷源码必须被判违规（否则守卫看不见它要防的形态）：$problems",
            problems.any { it.contains("不在 Task.Backgroundable.run 内") },
        )
    }

    /** 剥离行注释与块注释——注释里的示例代码不得参与判定。 */
    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
}
