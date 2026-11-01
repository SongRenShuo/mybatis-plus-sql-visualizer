package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-DialectStatusLiveRefresh 守卫：刷新链的真实挂点必须在场——纯策略测试证明不了
 * 「执行后确实会刷新」，本守卫钉住两处生产接线（embedConsole 单点钩子并列调用、
 * rerenderForExecutionTarget 决策同步），任一被摘即红。
 */
class DialectStatusRefreshWiringGuardTest {
    private fun read(path: String): String {
        val f = File(path)
        require(f.isFile) { "missing source file: $path" }
        return f.readText()
    }

    private val root = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui"

    @Test
    fun `embedConsole hook calls refreshDialectStatusRow`() {
        val t = read("$root/SqlPreviewPanelExecution.kt")
        assertTrue("embedConsole 钩子丢失 refreshDialectStatusRow 调用",
            t.contains("runCatching { refreshDialectStatusRow() }"))
        // 必须与绑定行刷新同拍（同一 invokeLater 块），不能散落第二个挂点
        val bind = t.indexOf("runCatching { refreshBindStateRow() }")
        val dialect = t.indexOf("runCatching { refreshDialectStatusRow() }")
        assertTrue("方言刷新未与绑定刷新同拍", bind in 0 until dialect &&
            dialect - bind < 800)
    }

    @Test
    fun `rerenderForExecutionTarget upgrades decision before renderSql`() {
        val t = read("$root/SqlPreviewPanelExecution.kt")
        val fn = t.substringAfter("internal fun SqlPreviewPanel.rerenderForExecutionTarget")
            .substringBefore("internal fun SqlPreviewPanel.executeSql")
        assertTrue("重渲染路径未升级 currentDialectDecision",
            fn.contains("currentDialectDecision ="))
        val decide = fn.indexOf("currentDialectDecision =")
        val render = fn.indexOf("    renderSql()")
        assertTrue("决策升级必须先于 renderSql", decide in 0 until render)
        assertFalse("重渲染路径不得走异步刷新（同拍同步升级即可）", fn.contains("refreshDialectStatusRow"))
    }

    @Test
    fun `refresh EDT block rerenders for bound dialect`() {
        // T-DialectGateExit P1：刷新链只重组状态行是不够的（UI-040：状态行/文本/目标三者可并存不一致）。
        // 判据按**未注释的整行**匹配——反向验证实测过：子串匹配会被 `// rerenderIfBoundDialectDiffers(chosen)`
        // 这种注释掉的探针骗成假绿。
        val t = read("$root/DialectStatusRefresh.kt")
        val wired = t.lines().any { it.trim() == "rerenderIfBoundDialectDiffers(chosen)" }
        assertTrue("绑定后未按目标方言重渲染（或只留注释）", wired)
        assertTrue("重渲染判据被摘（纯函数应在场）", t.contains("fun shouldRerenderForBoundDialect("))
    }

    @Test
    fun `last-used header refresh keeps sql rerender wired`() {
        // S2-4 券①（T-DialectInferPollution P2）：换源成功事件链必须按新源方言重渲染，
        // 不许退回「只刷头部」的 rerenderSql = false 旧形态（预览停留在旧方言渲染结果）。
        val t = read("$root/DialectStatusRefresh.kt")
        val fn = t.substringAfter("internal fun SqlPreviewPanel.refreshHeaderSourceNow")
            .substringBefore("internal fun SqlPreviewPanel.applyDialectStatusSegment")
        assertTrue("换源头部刷新链丢失 refreshDialectStatusRow 调用", fn.contains("refreshDialectStatusRow()"))
        assertFalse(
            "换源链不得传 rerenderSql = false（摘掉重渲染=退回券①之前的形态）",
            fn.contains("rerenderSql = false"),
        )
    }

    @Test
    fun `forceChoose gates silent reuse at both call sites`() {
        // T-DialectGateExit P0-3：两处静默路径都必须被 forceChoose 短路，否则「切换数据源」点了等于没点。
        // 同样只认未注释的行（防止注释掉的判据假绿）。
        val t = read("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")
        fun wired(fragment: String): Boolean =
            t.lines().any { !it.trim().startsWith("//") && it.contains(fragment) }
        assertTrue("共享控制台复用未接 forceChoose 判据", wired("allowsSharedConsoleReuse(forceChoose) &&"))
        assertTrue(
            "last-used 静默命中未接 forceChoose 判据",
            wired("allowsSilentLastUsed(executeAfterPrepare, forceChoose)"),
        )
    }

    @Test
    fun `updateMeta consumes panel decision not raw result field`() {
        val t = read("$root/SqlPreviewPanelResultView.kt")
        assertTrue("updateMeta 未写入面板决策", t.contains("currentDialectDecision = result.dialectDecision"))
        assertTrue("updateMeta 未走统一重组入口", t.contains("applyDialectStatusSegment()"))
    }

    @Test
    fun `clear resets dialect decision and segments`() {
        val t = read("$root/SqlPreviewPanelSqlView.kt")
        assertTrue("clear 未复位方言决策（会跨预览残留）",
            t.contains("currentDialectDecision = null"))
        assertTrue("clear 未复位 tooltip 分段", t.contains("metaTooltipCore = \"\""))
    }
}
