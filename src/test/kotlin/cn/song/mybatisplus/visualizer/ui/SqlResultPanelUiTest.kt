package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
// T-M53-II：`deliverError` 已搬为 execute 包内的同包扩展函数（原 DataSourceSelector 成员），
// 跨包调用点需显式导入（下面仍按全限定名书写接收者）。
import cn.song.mybatisplus.visualizer.execute.deliverError
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

/**
 * SQL 执行结果面板 UI 测试（F-44-6 续 · 补用例）：
 * 此前 0 测试，正是用户点名的「SqlPreviewPanel 错误态」薄弱点。覆盖：
 *   - 状态机 EMPTY/LOADING/ERROR/SUCCESS 正确切换（stateForTest 缝）；
 *   - showError：HTML 转义（防 < > & 破坏布局）、tooltip 存完整错误、errorCopyButton 显隐；
 *   - showIdle 保留 ERROR（防"一闪而过"）但清 LOADING/SUCCESS；
 *   - 自动可见性：有内容显示、空态隐藏（不占高度）。
 */
class SqlResultPanelUiTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    private fun allComponents(c: Component): List<Component> {
        val list = mutableListOf(c)
        if (c is Container) for (child in c.components) list += allComponents(child)
        return list
    }

    /** 错误复制按钮：JButton 且 text == bundle panel.copyError。 */
    private fun copyButton(panel: SqlResultPanel): JButton? =
        allComponents(panel).filterIsInstance<JButton>().firstOrNull { it.text == MpSqlVisualizerBundle.message("panel.copyError") }

    /** 信息标签：唯一非空 JBLabel（CENTER infoLabel）。 */
    private fun infoLabel(panel: SqlResultPanel): JBLabel? =
        allComponents(panel).filterIsInstance<JBLabel>().firstOrNull { it.text.isNotEmpty() }

    fun testShowError_状态与复制按钮与可见性() {
        val panel = SqlResultPanel()
        try {
            panel.showError("boom: connection refused")
            waitForEdt()
            assertEquals("showError 应进入 ERROR 态", "ERROR", panel.stateForTest())
            assertEquals("currentError 应保留原始错误文本", "boom: connection refused", panel.currentErrorForTest())
            assertTrue("ERROR 态面板应可见", panel.isVisible)
            val btn = copyButton(panel)
            assertNotNull("错误复制按钮应存在", btn)
            assertTrue("错误复制按钮应可见", btn!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    fun testShowError_HTML转义防布局破坏() {
        val panel = SqlResultPanel()
        try {
            // 含 < > & 与注入式序列——必须被转义，否则会破坏 infoLabel HTML 渲染
            val raw = "a < b & c > d </span><script>alert(1)</script>"
            panel.showError(raw)
            waitForEdt()
            val text = infoLabel(panel)?.text ?: ""
            assertTrue("错误标签文本应包含 HTML 包裹", text.startsWith("<html>"))
            assertTrue("'<' 应被转义为 &lt;", text.contains("&lt;"))
            assertTrue("'>' 应被转义为 &gt;", text.contains("&gt;"))
            assertTrue("'&' 应被转义为 &amp;", text.contains("&amp;"))
            assertFalse("原始 '<' 不应直接出现（防注入/布局破坏）", text.contains("a < b"))
            // tooltip 保留完整原始错误（hover 可读）
            assertEquals("tooltip 应保留完整原始错误", raw, infoLabel(panel)?.toolTipText)
        } finally {
            panel.clear()
        }
    }

    fun testShowIdle_保留错误态防一闪而过() {
        val panel = SqlResultPanel()
        try {
            panel.showError("boom")
            waitForEdt()
            panel.showIdle()
            waitForEdt()
            assertEquals("showIdle 在 ERROR 态下应保留 ERROR", "ERROR", panel.stateForTest())
            assertEquals("showIdle 应保留错误文本（可复制/可读）", "boom", panel.currentErrorForTest())
            assertTrue("showIdle 在 ERROR 态下面板仍可见", panel.isVisible)
            assertTrue("showIdle 在 ERROR 态下复制按钮仍可见", copyButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    fun testShowIdle_清除非错误态() {
        val panel = SqlResultPanel()
        try {
            panel.showLoading("ds1")
            waitForEdt()
            assertEquals("showLoading 应为 LOADING 态", "LOADING", panel.stateForTest())
            panel.showIdle()
            waitForEdt()
            assertEquals("showIdle 在 LOADING 态应回落 EMPTY", "EMPTY", panel.stateForTest())
            assertFalse("showIdle 后面板应隐藏（不占高度）", panel.isVisible)
            assertNull("showIdle 后 currentError 应为 null", panel.currentErrorForTest())
        } finally {
            panel.clear()
        }
    }

    fun test成功与加载态_隐藏复制按钮且可见() {
        val panel = SqlResultPanel()
        try {
            panel.showQueryResult(10, 25, "mysql")
            waitForEdt()
            assertEquals("showQueryResult 应为 SUCCESS 态", "SUCCESS", panel.stateForTest())
            assertTrue("结果态面板应可见", panel.isVisible)
            assertFalse("成功态不应显示错误复制按钮", copyButton(panel)!!.isVisible)

            panel.showUpdateResult(3, 12, "mysql")
            waitForEdt()
            assertEquals("showUpdateResult 应为 SUCCESS 态", "SUCCESS", panel.stateForTest())

            panel.showLoading()
            waitForEdt()
            assertEquals("showLoading 应为 LOADING 态", "LOADING", panel.stateForTest())
            assertFalse("加载态不应显示错误复制按钮", copyButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    fun testShowEmpty_隐藏面板且清空错误() {
        val panel = SqlResultPanel()
        try {
            panel.showError("boom")
            waitForEdt()
            panel.showEmpty()
            waitForEdt()
            assertFalse("空态面板应隐藏", panel.isVisible)
            assertEquals("空态应为 EMPTY", "EMPTY", panel.stateForTest())
            assertNull("空态 currentError 应清空", panel.currentErrorForTest())
            assertFalse("空态复制按钮应隐藏", copyButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    // ------------------------------------------------------------------
    // T-ErrorLocateUsability T-ELU-01（O-49）：定位按钮可用性判定
    // ------------------------------------------------------------------

    /** 定位按钮：JButton 且 text == bundle execute.error.locate。 */
    private fun locateButton(panel: SqlResultPanel): JButton? =
        allComponents(panel).filterIsInstance<JButton>()
            .firstOrNull { it.text == MpSqlVisualizerBundle.message("execute.error.locate") }

    private fun info(positionChar: Int? = null, lineInSql: Int? = null) =
        cn.song.mybatisplus.visualizer.execute.ExecutionErrorInfo(
            message = "boom",
            positionChar = positionChar,
            lineInSql = lineInSql,
        )

    /**
     * 本任务的核心判据（面板侧契约）：`locate == null` → 按钮**不出现**。
     * 宿主（`SqlPreviewPanel`）负责在锚点不可解析时传 null——改前它恒传非 null lambda，
     * 于是按钮可见但点了什么都不发生。
     */
    fun testShowErrorRich_不可定位时不显示定位按钮() {
        val panel = SqlResultPanel()
        try {
            panel.showErrorRich(info(), null)
            waitForEdt()
            assertEquals("仍应进入 ERROR 态", "ERROR", panel.stateForTest())
            val btn = locateButton(panel)
            assertNotNull("定位按钮组件应存在（仅隐藏）", btn)
            assertFalse("locate=null 时定位按钮不得可见", btn!!.isVisible)
            assertTrue("错误复制按钮不受影响", copyButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    fun testShowErrorRich_可定位时显示按钮且tooltip如实标注两态() {
        val panel = SqlResultPanel()
        try {
            panel.showErrorRich(info(positionChar = 24), { })
            waitForEdt()
            val btn = locateButton(panel)!!
            assertTrue("locate 非 null 时定位按钮应可见", btn.isVisible)
            val tip = btn.toolTipText
            // 用 bundle 自身文案比对（与既有用例同款，不依赖 locale）——钉住「用的是带位置的 key」
            assertEquals(
                "tooltip 应为带驱动位置的文案",
                MpSqlVisualizerBundle.message("execute.error.locate.tooltip.position", 24),
                tip,
            )
            assertTrue("tooltip 应含驱动报的位置", tip.contains("24"))
            // 测试环境 bundle 兜底中文（MpSqlVisualizerBundle KDoc：settings 未注册 → 中文）
            assertTrue("tooltip 应如实标注位置指向被执行的 SQL", tip.contains("被执行的 SQL"))
            assertTrue("tooltip 不得宣称能定位到 mapper 原文行", tip.contains("不一一对应"))
        } finally {
            panel.clear()
        }
    }

    /** 无位置信号时用「语句起始行」口径的 tooltip（同样如实标注两态）。 */
    fun testShowErrorRich_无位置信号用起始行口径() {
        val panel = SqlResultPanel()
        try {
            panel.showErrorRich(info(), { })
            waitForEdt()
            val tip = locateButton(panel)!!.toolTipText
            assertTrue("tooltip 应标注两态", tip.contains("被执行的 SQL"))
        } finally {
            panel.clear()
        }
    }

    /**
     * T-Round10FU（独立复核 ㉕）：**只有行号、没有字符位置**的驱动报错（MySQL 语法错误 `at line N`）
     * 用带行号的文案。改前 tooltip 只判 `positionChar`，这类报错落到「语句起始行」兜底文案——
     * 驱动明明报了行号却被丢弃（`ExecutionErrorInfo.lineInSql` 因此在 src/main 无任何消费方）。
     */
    fun testShowErrorRich_只带行号时tooltip用行号文案() {
        val panel = SqlResultPanel()
        try {
            panel.showErrorRich(info(lineInSql = 3), { })
            waitForEdt()
            val btn = locateButton(panel)!!
            assertTrue("locate 非 null 时定位按钮应可见", btn.isVisible)
            val tip = btn.toolTipText
            assertEquals(
                "tooltip 应为带驱动行号的文案",
                MpSqlVisualizerBundle.message("execute.error.locate.tooltip.line", 3),
                tip,
            )
            assertTrue("tooltip 应含驱动报的行号", tip.contains("3"))
            assertTrue("tooltip 应如实标注位置指向被执行的 SQL", tip.contains("被执行的 SQL"))
            assertTrue("tooltip 不得宣称能定位到 mapper 原文行", tip.contains("不一一对应"))
        } finally {
            panel.clear()
        }
    }

    fun testRichErrorPreservesMetadataInTooltipAndCopy() {
        val panel = SqlResultPanel()
        try {
            val driver = java.sql.SQLException("Syntax <bad> & detail\nPosition: 12", "42601", 932)
            val info = cn.song.mybatisplus.visualizer.execute.ErrorPositionExtractor.buildInfo(driver, driver.message!!, 80)
            val expected = cn.song.mybatisplus.visualizer.execute.ExecutionErrorFormatter.format(info)
            panel.showErrorRich(info, { })
            waitForEdt()
            val label = allComponents(panel).filterIsInstance<javax.swing.JLabel>()
                .first { it.toolTipText == expected }
            assertTrue(label.text.contains("sqlState: 42601"))
            assertTrue(locateButton(panel)!!.isVisible)
            cn.song.mybatisplus.visualizer.util.UiTextClipboard.copy("sentinel")
            copyButton(panel)!!.doClick()
            assertEquals(expected, cn.song.mybatisplus.visualizer.util.UiTextClipboard.readText())
        } finally {
            panel.clear()
        }
    }

    /** 富错误后再走普通 showError（无定位）→ 定位按钮必须收起（不残留可点却无动作的按钮）。 */
    fun testShowErrorRich_转普通错误后按钮收起() {
        val panel = SqlResultPanel()
        try {
            panel.showErrorRich(info(), { })
            waitForEdt()
            assertTrue(locateButton(panel)!!.isVisible)
            panel.showError("boom again")
            waitForEdt()
            assertFalse("普通错误路径应隐藏定位按钮", locateButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    /**
     * T-ELU-01 收口契约（2026-09-23 真机走查补）：`DataSourceSelector.deliverError` 必须**互斥**投递
     * ——富通道成功时**不得**再投旧 `onError`。
     *
     * 背景：本仓曾把 O-49 的收口实现在合并里丢掉（`914724c` 采信 master 侧），端态退回
     * 「定位按钮永不出现」——真机实证：一条锚点可解析的失败语句只出红字 +「复制错误信息」，无定位按钮。
     * 上一条用例 `testShowErrorRich_转普通错误后按钮收起` 正是**为什么**必须互斥：`showError` 会抹掉按钮。
     *
     * **反向验证**：把 `deliverError` 改回「先投富通道、再无条仵投旧通道」→ 本用例必红
     * （`plainCalls` 变 1 且按钮不可见）。
     */
    fun testDeliverError_富通道成功时不再投旧通道_定位按钮得以保留() {
        val panel = SqlResultPanel()
        try {
            val richCalls = java.util.concurrent.atomic.AtomicInteger(0)
            val plainCalls = java.util.concurrent.atomic.AtomicInteger(0)
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.deliverError(
                onErrorRich = { info ->
                    richCalls.incrementAndGet()
                    panel.showErrorRich(info, { })
                },
                onError = { msg ->
                    plainCalls.incrementAndGet()
                    panel.showError(msg)
                },
                cause = null,
                message = "boom: Invalid object name 'no_such_table_xyz'.",
                sqlMaxLength = 128,
            )
            waitForEdt()
            assertEquals("富通道应被投递一次", 1, richCalls.get())
            assertEquals("旧通道不得再被投递（互斥）", 0, plainCalls.get())
            assertTrue("定位按钮应可见（未被旧通道抹除）", locateButton(panel)!!.isVisible)
        } finally {
            panel.clear()
        }
    }

    /** 无富通道消费方时回退旧通道；富通道抛异常时同样回退——不让「结构化」这一增强吞掉错误呈现。 */
    fun testDeliverError_无富通道或富通道抛异常时回退旧通道() {
        val panel1 = SqlResultPanel()
        try {
            val plain = java.util.concurrent.atomic.AtomicInteger(0)
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.deliverError(
                onErrorRich = null,
                onError = { m ->
                    plain.incrementAndGet()
                    panel1.showError(m)
                },
                cause = null,
                message = "boom",
                sqlMaxLength = null,
            )
            waitForEdt()
            assertEquals("无富通道时必须回退旧通道", 1, plain.get())
            assertEquals("仍应进入 ERROR 态", "ERROR", panel1.stateForTest())
        } finally {
            panel1.clear()
        }
        val panel2 = SqlResultPanel()
        try {
            val plain = java.util.concurrent.atomic.AtomicInteger(0)
            cn.song.mybatisplus.visualizer.execute.DataSourceSelector.deliverError(
                onErrorRich = { throw IllegalStateException("rich boom") },
                onError = { m ->
                    plain.incrementAndGet()
                    panel2.showError(m)
                },
                cause = null,
                message = "boom2",
                sqlMaxLength = null,
            )
            waitForEdt()
            assertEquals("富通道抛异常时必须回退旧通道", 1, plain.get())
            assertEquals("仍应进入 ERROR 态", "ERROR", panel2.stateForTest())
        } finally {
            panel2.clear()
        }
    }

    /**
     * T-DialectGateUx：方言闸门阻断态的两个就地出口——可见、可点、离开错误态即清。
     *
     * 反向判据：`doClick` 不看可见性，故「清空后再点不得再触发」必须由 handler 置 null 保证；
     * 这里一并钉死，防止旧动作挂在成功/加载态上被误触。
     */
    fun testShowErrorWithActions_两个修复入口可点且随状态清除() {
        val panel = SqlResultPanel()
        try {
            var fixClicks = 0
            var settingsClicks = 0
            panel.showErrorWithActions(
                "dialect mismatch",
                fixLabel = "切换数据源",
                fixTooltip = "tip-fix",
                fix = { fixClicks++ },
                settingsLabel = "方言设置",
                settingsTooltip = "tip-settings",
                settings = { settingsClicks++ },
            )
            waitForEdt()
            val fix = panel.fixActionForTest()
            val settings = panel.settingsActionForTest()
            assertTrue("修复入口应可见", fix.isVisible)
            assertTrue("设置入口应可见", settings.isVisible)
            assertEquals("切换数据源", fix.text)
            assertEquals("方言设置", settings.text)
            assertEquals("tip-fix", fix.toolTipText)
            assertEquals("tip-settings", settings.toolTipText)

            fix.doClick()
            settings.doClick()
            assertEquals("点击修复入口应触发一次", 1, fixClicks)
            assertEquals("点击设置入口应触发一次", 1, settingsClicks)

            panel.showStatus("ok")
            waitForEdt()
            assertFalse("非错误态不得残留修复入口", fix.isVisible)
            assertFalse("非错误态不得残留设置入口", settings.isVisible)
            fix.doClick()
            assertEquals("清空后旧动作不得再触发", 1, fixClicks)

            // 普通 showError 不带动作：两个入口保持隐藏
            panel.showError("plain error")
            waitForEdt()
            assertFalse("普通错误不应显示修复入口", fix.isVisible)
            assertFalse("普通错误不应显示设置入口", settings.isVisible)
        } finally {
            panel.clear()
        }
    }

    // ======================== T-SAF-10：错误 tooltip 安全化 ========================

    /**
     * T-SAF-10（ui.swing-html-label.unescaped-lower-trust-text）回归：showError 曾把原始
     * 错误字符串（远程 DB SQLException 文本，低信任）直接挂 infoLabel.toolTipText（:277）——
     * Swing 工具提示仅在文本以 <html> 起结时按 HTML 渲染，以 <html> 起结的恶意错误消息可
     * 注入内容并触发 <img src> 资源加载。修复语义：HTML 起结文本转义为字面量显示，
     * 纯文本原样保留（M36.5.53「tooltip 完整错误」语义不变）。
     */
    fun testErrorTooltipHtmlPrefixedTextIsNeutralized_TSAF10() {
        val panel = SqlResultPanel()
        try {
            val payload = "<html><img src=http://127.0.0.1:1/probe.png></html>"
            panel.showError(payload)
            waitForEdt()
            val tooltip = panel.infoTooltipForTest().orEmpty()
            assertFalse("tooltip 不得保留可渲染的 <html> 起结：$tooltip", tooltip.startsWith("<html"))
            // 纯文本错误：tooltip 原样保留（显示语义不变）
            panel.showError("plain failure: value < 10")
            waitForEdt()
            assertEquals("plain failure: value < 10", panel.infoTooltipForTest())
        } finally {
            panel.clear()
        }
    }
}
