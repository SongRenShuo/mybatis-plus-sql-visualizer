package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.StaticTextSqlNode
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlStatement
import cn.song.mybatisplus.visualizer.xml.XmlStatementKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-SourceJumpFunnel P6 回归守护：参数导入「空剪贴板」行内提示态**不得丢掉 meta 行 tooltip**。
 *
 * 缺陷形态：`showImportResult` 的空剪贴板分支旧写法 `metaLabel.toolTipText = hint` 把
 * [SqlPreviewPanel.updateMeta] 组装的 meta tooltip **整个覆写**掉了——提示态一挂起，悬停就只剩
 * 提示本身、基线丢失；且提示文案本就在可见行上全文可见，覆写并未换来任何信息。
 *
 * 修复：提示态 tooltip 由「覆写」改「拼接」（提示首行 + `updateMeta` 缓存的基线 metaTooltipBase），
 * 基线取自缓存字段而非当前 tooltip，故重复触发幂等（不叠加自身）。
 *
 * **T-StackHoverUi 契约换代（20261007）**：完整调用链的悬停载体从 tooltip 换成调试器 Frames
 * 风格悬停弹层（[StackFrameHover]），tooltip 基线只含 meta/警示/perf——本文件原「tooltip 保留
 * 完整调用链」断言同步翻转为「tooltip 不含栈文本」；拼接/幂等/toast/P10 机制断言原样保留。
 *
 * 可测性：该提示是**纯行内状态更新**（不弹任何窗），故已从 `showImportResult` 的「测试模式跳过弹窗」
 * 闸门之前分离出来（闸门只为避开 Messages/TestDialog 拦截器），本用例据此可在 headless 下驱动。
 */
class SqlPreviewPanelMetaHintTooltipTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun hint(): String = MpSqlVisualizerBundle.message("panel.param.import.result.empty")

    private fun stackLabel(): String = MpSqlVisualizerBundle.message("panel.meta.stackDigest")

    private fun makeResult(stackDigest: String) = PreviewResult(
        sql = "SELECT id\nFROM user\nWHERE username = ?",
        compactSql = "SELECT id FROM user WHERE username = ?",
        unresolvedParams = listOf("username"),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        columns = listOf("id", "username"),
        stackDigest = stackDigest,
    )

    private fun chain(): String {
        val unit = "com.corp.biz.Svc#m:1 <- "
        val sb = StringBuilder()
        while (sb.length < 200) sb.append(unit)
        return sb.substring(0, 200)
    }

    fun test方言冲突的回退在可见行且候选证据可查看() {
        val panel = SqlPreviewPanel(project)
        try {
            val decision = cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Decision(
                cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Status.CONFLICT,
                null,
                cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Confidence.FALLBACK,
                cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine.Source.IDEA_CONFIGURED_SCOPE,
                dataSourceIds = listOf("uuid-pg", "uuid-my"),
            )
            panel.showResult(makeResult("").copy(dialectDecision = decision))
            waitForEdt()
            val visible = panel.metaLabelForTest()
            assertTrue("冲突预览不能只显示 MySQL 而不说兼容回退：$visible",
                visible.contains("MySQL") && (visible.contains("未验证") || visible.contains("unverified")))
            val tooltip = panel.metaTooltipForTest().orEmpty()
            assertTrue("候选 UUID 集合须在按需查看的证据面保留：$tooltip",
                tooltip.contains("uuid-pg") && tooltip.contains("uuid-my"))
            assertFalse("元信息不能保存 JDBC URL", tooltip.contains("jdbc:"))
        } finally {
            panel.clear()
        }
    }

    fun test实际方言能力悬停段有消费方而非空表达式() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(makeResult("").copy(
                renderedDialect = cn.song.mybatisplus.visualizer.sql.OracleDialect,
            ))
            waitForEdt()
            val tooltip = panel.metaTooltipForTest().orEmpty()
            assertTrue("方言能力段须实际进入 tooltip：$tooltip", tooltip.contains("Oracle"))
        } finally {
            panel.clear()
        }
    }

    fun testXML解析警示与后续求值警示同时可见() {
        val panel = SqlPreviewPanel(project)
        try {
            val xml = XmlPreviewModel.from(
                XmlStatement("s", XmlStatementKind.SELECT, StaticTextSqlNode("SELECT 1"), null),
                emptyList(),
            )
            val result = PreviewResult(
                sql = "SELECT 1", compactSql = "SELECT 1", unresolvedParams = emptyList(),
                paramTable = null, wrapperType = null, tableName = null,
                xmlModel = xml, warning = "初始解析警示",
            )
            panel.xmlErrors = listOf("后续求值警示", "后续求值警示")
            panel.updateMeta(result)
            assertEquals("初始解析警示；后续求值警示", panel.currentWarning)
            assertTrue((panel.metaTooltipForTest() ?: "").contains("初始解析警示；后续求值警示"))
        } finally {
            panel.clear()
        }
    }

    fun test空剪贴板提示_可见行显提示且tooltip保留基线() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chain()
            panel.showResult(makeResult(digest))
            waitForEdt()
            val baseTooltip = panel.metaTooltipForTest() ?: ""
            // T-StackHoverUi：基线只含 meta/警示/perf，不含栈文本
            assertFalse("前提（换代后）：预览态 tooltip 不含完整调用链", baseTooltip.contains(digest))

            // 空参数导入 = 空剪贴板提示路径（importParams(emptyMap())）
            panel.importParamsForTest(emptyMap())
            waitForEdt()

            assertEquals("可见 meta 行应切到行内提示", "⚠ ${hint()}", panel.metaLabelForTest())
            val tooltip = panel.metaTooltipForTest() ?: ""
            assertTrue("提示态 tooltip 首行应为提示本身", tooltip.startsWith(hint()))
            assertFalse("提示态 tooltip 不得出现调用栈文本（完整链归悬停弹层）", tooltip.contains(digest))
            assertFalse("提示态 tooltip 不得出现调用栈节点文案", tooltip.contains(stackLabel()))
            // 拼接而非覆写：基线完整保留在其后（幂等拼接的实质断言）
            assertTrue("提示态 tooltip 应保留 updateMeta 的完整基线", tooltip.endsWith(baseTooltip))
        } finally {
            panel.clear()
        }
    }

    fun test空剪贴板提示_重复触发不叠加自身() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(makeResult(chain()))
            waitForEdt()
            panel.importParamsForTest(emptyMap())
            waitForEdt()
            val once = panel.metaTooltipForTest() ?: ""

            panel.importParamsForTest(emptyMap())
            waitForEdt()
            assertEquals("重复触发应幂等（基线取自缓存字段，不把上次拼接结果当基线）", once, panel.metaTooltipForTest())
        } finally {
            panel.clear()
        }
    }

    fun test空剪贴板提示后_再次预览恢复纯metaTooltip() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(makeResult(chain()))
            waitForEdt()
            panel.importParamsForTest(emptyMap())
            waitForEdt()
            assertTrue("前提：提示态 tooltip 含提示文案", (panel.metaTooltipForTest() ?: "").contains(hint()))

            panel.showResult(makeResult(chain()))
            waitForEdt()
            val tooltip = panel.metaTooltipForTest() ?: ""
            assertFalse("toast 语义：下次 updateMeta 覆盖提示态（可见行同步回纯 meta）", panel.metaLabelForTest().contains(hint()))
            assertFalse("toast 语义：下次 updateMeta 覆盖提示态（tooltip 同步回纯 meta）", tooltip.contains(hint()))
            // T-StackHoverUi：恢复后 tooltip 仍不含栈文本（完整链在悬停弹层）
            assertFalse("恢复后 tooltip 不得含完整调用链", tooltip.contains(chain()))
        } finally {
            panel.clear()
        }
    }

    /**
     * P10 回归守护（2026-09-20 真机走查发现）：提示态**必须扛得住宽度预算重算**。
     *
     * 缺陷形态：提示此前只写 `metaLabel.text`，不进 `metaLineHead`/`metaLineText`。真机上
     * `errorCopyButton.isVisible = true` 会改变工具栏理想宽 → topRow 重排 → 紧接着的
     * `applyMetaTruncation()`（预算变了故不短路）用旧 `metaLineText` 把提示当场覆盖——走查实测
     * 「提示态根本看不到」（红字与复制按钮都没出现），即本项此前**只在单测里成立**。
     *
     * 本用例按同一路径驱动：提示挂起后主动触发一次 `applyMetaTruncation()`（headless 等价于
     * 真机的重排收口），断言可见行仍是提示、且不含被覆盖回来的 meta 主体。
     */
    fun test提示态_宽度预算重算后不被meta覆盖() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(makeResult(chain()))
            waitForEdt()
            panel.importParamsForTest(emptyMap())
            waitForEdt()
            assertEquals("前提：提示已挂到可见行", "⚠ ${hint()}", panel.metaLabelForTest())

            // 真机路径的等价动作：布局变化后按新预算重拼可见行
            panel.applyMetaTruncationForTest()
            waitForEdt()

            val visible = panel.metaLabelForTest()
            assertTrue("重算后提示必须仍在可见行内（P10 回归点）: $visible", visible.contains(hint()))
            assertFalse("不得被 meta 主体覆盖回来: $visible", visible.contains("LAMBDA_QUERY"))
            val tooltip = panel.metaTooltipForTest() ?: ""
            assertTrue("提示态 tooltip 必须仍在（P10：重算不覆写提示拼接）", tooltip.startsWith(hint()))
        } finally {
            panel.clear()
        }
    }

    // ======================== T-SAF-10：meta 行（可见行 + tooltip）低信任数据转义 ========================

    /**
     * T-SAF-10（ui.swing-html-label.unescaped-lower-trust-text）回归：updateMeta 曾把
     * result.tableName（实体注解字面量，低信任）、xmlModel.statementId、警示文本等未转义
     * 地组合进 metaCoreText（:622/:629/:650），经 fullDisplayText/applyMetaTruncation 的
     * <html> 包装（:719/:728）成为活 markup，并经 metaTooltipBase 到达 tooltip。
     *
     * **修复前失败断言**：`可见行不得含未转义标记`——修复前载荷原样进入可见行与 tooltip。
     */
    fun testMetaLineEscapesLowTrustTableNameAndWarning_TSAF10() {
        val panel = SqlPreviewPanel(project)
        try {
            val payload = "<img src=http://127.0.0.1:1/probe.png>"
            panel.showResult(makeResult("").copy(tableName = payload, warning = "warn<$payload>"))
            waitForEdt()
            val visible = panel.metaLabelForTest()
            assertFalse("可见行不得含未转义标记：$visible", visible.contains("<img"))
            val tooltip = panel.metaTooltipForTest().orEmpty()
            assertFalse("tooltip 不得含未转义标记：$tooltip", tooltip.contains("<img"))
        } finally {
            panel.clear()
        }
    }
}
