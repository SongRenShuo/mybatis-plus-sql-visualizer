package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-PerfSweep33 券01/券03 的反向验证守卫（性能分析第十期 §4.1 P2 + §七.3）。
 *
 * ## 为什么用源码扫描而不是运行时断言
 *
 * 与 IbatisTypeAliasResolverHotPathTest 同款方法论：「解析不在 EDT 直跑」是**不存在**的事实——
 * 运行时断言只能证明「这一次没停 EDT」，证明不了入口没有被顺手改回直调。源码扫描把禁止面写成
 * 清单，机制漂移（有人把 resolve 挪回 EDT 入口体内）直接红。
 *
 * ## 钉住的不变式
 *
 * 1. **券01 单点路由**：`SqlPreviewDialog` 生产段对 `StandaloneSqlSourceResolver.resolve` 的
 *    调用点恰 1 处，且必须位于 `resolveOffEdt` 内（其生产形态＝`executeOnPooledThread` 提交体）。
 *    两个入口（reparseDraftFromEditor / loadFileIntoPreview）不得直调。
 * 2. **券03 EXPLAIN 前热 cache-only**：`SqlPreviewPanelExecution` 的
 *    `prewarmExecTempFileOnBackgroundThread` 调用必须显式带 `cacheOnly = true`——与预览主链
 *    cache-only 零写盘形态对齐（PerfSweep31 券03a 语义不被低频路径绕过）。
 *
 * 自证（防空绿）：合成样本（入口直调 resolve / 前热缺 cacheOnly）必须被抓出，干净样本必须放行——
 * 判定器退化成恒通过即红。
 */
class SqlPreviewDialogOffEdtResolveHotPathTest {

    private val dialogPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewDialog.kt"
    private val executionPath = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelExecution.kt"

    /** 剥块注释与行注释（与 IbatisTypeAliasResolverHotPathTest 同款口径：KDoc 点名 API 不误报）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")

    private fun findRepoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(8) {
            if (File(dir, dialogPath).exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("repo root with $dialogPath not found from ${System.getProperty("user.dir")}")
    }

    // ------------------------------------------------------------------
    // 券01：单点路由
    // ------------------------------------------------------------------

    /**
     * 判定核心（供生产文件与合成样本复用）：返回违规描述，null = 通过。
     * ① resolve 调用点恰 1 处；② resolveOffEdt 与 executeOnPooledThread 在场（空绿防御）；
     * ③ 唯一调用点位于 resolveOffEdt 声明之后（入口在文件前部，直调会落在它之前）。
     */
    private fun singleRoutingViolation(body: String): String? {
        val lines = body.lines()
        val callLines = lines.withIndex().filter { it.value.contains("StandaloneSqlSourceResolver.resolve(") }
        if (callLines.size != 1) return "StandaloneSqlSourceResolver.resolve 调用点应恰 1 处（现 ${callLines.size}）"
        val resolveLine = callLines.single().index
        val dispatchLine = lines.indexOfFirst { it.contains("executeOnPooledThread") }
        val entryLine = lines.indexOfFirst { it.contains("private fun resolveOffEdt") }
        if (dispatchLine < 0 || entryLine < 0) return "resolveOffEdt / executeOnPooledThread 缺席（形态漂移，请同步更新本守卫）"
        if (resolveLine <= entryLine) return "唯一 resolve 调用点不在 resolveOffEdt 体内（调用行 $resolveLine ≤ 函数行 $entryLine）"
        return null
    }

    @Test
    fun test弹窗识别链必须单点路由到池线程() {
        val body = stripComments(File(findRepoRoot(), dialogPath).readText())
        val violation = singleRoutingViolation(body)
        assertNull(
            "SqlPreviewDialog 识别链漂移（T-PerfSweep33 券01 守卫）：$violation —— " +
                "入口不得直调 resolve，统一走 resolveOffEdt（池线程计算 + invokeLater 回 EDT）",
            violation,
        )
    }

    // ------------------------------------------------------------------
    // 券03：EXPLAIN 前热 cache-only
    // ------------------------------------------------------------------

    /** 判定核心：EXPLAIN 前热调用段的括号配平实参里必须含 `cacheOnly = true`。 */
    private fun explainCacheOnlyViolation(body: String): String? {
        val start = body.indexOf("prewarmExecTempFileOnBackgroundThread(")
        if (start < 0) return "EXPLAIN 前热调用点缺席（形态漂移，请同步更新本守卫）"
        val segment = balancedCallSegment(body, start)
        if (!segment.contains("cacheOnly = true")) {
            return "EXPLAIN 前热调用缺 cacheOnly = true（会绕过 PerfSweep31 券03a 的同内容零写盘判等）"
        }
        return null
    }

    /** 从调用起点取括号配平的调用段（防嵌套括号截断实参列表）。 */
    private fun balancedCallSegment(body: String, start: Int): String {
        val sb = StringBuilder()
        var depth = 0
        var i = body.indexOf('(', start)
        while (i >= 0 && i < body.length) {
            val c = body[i]
            sb.append(c)
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        return sb.toString()
    }

    @Test
    fun testEXPLAIN前热必须带cacheOnly零写盘() {
        val body = stripComments(File(findRepoRoot(), executionPath).readText())
        val violation = explainCacheOnlyViolation(body)
        assertNull(
            "EXPLAIN 前热漂移（T-PerfSweep33 券03 守卫）：$violation",
            violation,
        )
    }

    // ------------------------------------------------------------------
    // 自证（防空绿）：合成样本必须被抓出、干净样本必须放行
    // ------------------------------------------------------------------

    @Test
    fun test自证_入口直调resolve的合成样本必须被抓出() {
        val violating = """
            class FakeDialog {
                fun reparseDraftFromEditor() {
                    val resolved = StandaloneSqlSourceResolver.resolve(text)
                    apply(resolved)
                }

                private fun resolveOffEdt(textSource: () -> String?, applyOnEdt: (Any) -> Unit) {
                    app.executeOnPooledThread { }
                }
            }
        """.trimIndent()
        assertTrue(
            "合成样本（入口体内直调 resolve）必须被抓出——判定器恒通过即守卫失效：${singleRoutingViolation(stripComments(violating))}",
            singleRoutingViolation(stripComments(violating)) != null,
        )
        val clean = """
            class FakeDialog {
                private fun resolveOffEdt(textSource: () -> String?, applyOnEdt: (Any) -> Unit) {
                    app.executeOnPooledThread {
                        val resolved = StandaloneSqlSourceResolver.resolve(textSource())
                        app.invokeLater({ apply(resolved) }, modality)
                    }
                }
            }
        """.trimIndent()
        assertNull(
            "干净样本（唯一调用点在 resolveOffEdt 池线程体内）必须放行",
            singleRoutingViolation(stripComments(clean)),
        )
    }

    @Test
    fun test自证_缺cacheOnly的合成样本必须被抓出() {
        val violating = """
            class Fake {
                fun prewarm() {
                    sel.prewarmExecTempFileOnBackgroundThread(
                        project,
                        sql,
                        dialect,
                        logger,
                    )
                }
            }
        """.trimIndent()
        assertTrue(
            "合成样本（前热缺 cacheOnly）必须被抓出",
            explainCacheOnlyViolation(stripComments(violating)) != null,
        )
        val clean = """
            class Fake {
                fun prewarm() {
                    sel.prewarmExecTempFileOnBackgroundThread(
                        project,
                        sql,
                        dialect,
                        logger,
                        cacheOnly = true,
                    )
                }
            }
        """.trimIndent()
        assertNull(
            "干净样本（带 cacheOnly = true）必须放行",
            explainCacheOnlyViolation(stripComments(clean)),
        )
    }
}
