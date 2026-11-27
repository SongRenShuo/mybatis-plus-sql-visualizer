package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-PerfSweep34 券02 结构守卫：让位开关变化触发的 gutter 重算**不得**退回全项目全量重分析。
 *
 * 背景（第十一期性能调研 §4.2）：`refreshGutterLineMarkers` 原实现对每个打开项目调**无参**
 * `DaemonCodeAnalyzer.restart()`——那是全项目全量重分析（inspection / annotator / 高亮全部重跑），
 * 而触发它的只是一个 gutter 显示开关。修法是按文件 `restart(psi)`，影响面从「全项目所有文件」
 * 收到「打开着的文件」。
 *
 * ## 为什么按方法体切片而不是全文扫描
 *
 * `restart()` 与 `restart(psi)` 的区别在实参上；全文扫描会把平台/别处的合法无参调用一起算进来。
 * 切片到 `refreshGutterLineMarkers` 方法体后判据精确——这个方法体的唯一职责就是本次重算，
 * 里面出现无参 `restart()` 就是退回旧形态。
 *
 * ## 防空绿（对齐 pitfall-fake-green-test-assertions）
 *
 * 单靠「方法体里没有无参 restart」会空绿：方法体提取失败（签名漂移）时切片为空串，
 * 断言照样通过。故补三条——①切片非空；②切片内必须有 `restart(psi)` 形态；
 * ③合成样本自证（把旧形态喂给判据必须被识别）。
 */
class GutterYieldRefreshScopeGuardTest {

    private val sourcePath =
        "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt"

    private fun readSource(): String =
        File(sourcePath).let { f ->
            assertTrue("源文件必须存在：$sourcePath（工作目录=${File(".").absolutePath}）", f.isFile)
            f.readText(Charsets.UTF_8)
        }

    /** 从全文切出 `refreshGutterLineMarkers` 的方法体（到下一个同缩进的 `fun ` 或文件尾）。 */
    private fun refreshBody(source: String): String {
        val start = source.indexOf("private fun refreshGutterLineMarkers(")
        if (start < 0) return ""
        val rest = source.substring(start)
        // 方法体结束：下一个顶层 `fun `（settings 面板里紧跟 apply()）
        val endRel = rest.indexOf("\n    fun ").let { if (it < 0) rest.length else it }
        return rest.substring(0, endRel)
    }

    /** 无参 `restart()` 命中数（排除 `restart(` 后带实参的形态）。 */
    private fun bareRestartCount(body: String): Int =
        Regex("\\.restart\\(\\s*\\)").findAll(body).count()

    @Test
    fun `让位重算不得退回全项目无参restart`() {
        val body = refreshBody(readSource())
        assertTrue(
            "必须能切出 refreshGutterLineMarkers 方法体（切不出=签名漂移，本守卫会空绿）",
            body.isNotBlank(),
        )
        assertTrue(
            "方法体必须按文件重分析（restart 带实参）；实际方法体=\n$body",
            Regex("\\.restart\\(\\s*[A-Za-z_]").containsMatchIn(body),
        )
        assertFalse(
            "让位重算不得调无参 restart()——那是全项目全量重分析（成本与「改一个图标开关」严重错配）",
            bareRestartCount(body) > 0,
        )
    }

    @Test
    fun `判据自证_旧形态必须被识别`() {
        val legacy = """
            private fun refreshGutterLineMarkers() {
                ProjectManager.getInstance().openProjects.forEach { proj ->
                    DaemonCodeAnalyzer.getInstance(proj).restart()
                }
            }
        """.trimIndent()
        assertTrue(
            "旧形态（无参 restart）必须被判据识别，否则守卫对真实违规失明",
            bareRestartCount(legacy) > 0,
        )
        val fixed = """
            private fun refreshGutterLineMarkers() {
                FileEditorManager.getInstance(proj).openFiles.forEach { vf ->
                    analyzer.restart(psi)
                }
            }
        """.trimIndent()
        assertFalse(
            "修复形态（restart(psi)）必须放行，否则守卫会把正确实现判红",
            bareRestartCount(fixed) > 0,
        )
    }
}
