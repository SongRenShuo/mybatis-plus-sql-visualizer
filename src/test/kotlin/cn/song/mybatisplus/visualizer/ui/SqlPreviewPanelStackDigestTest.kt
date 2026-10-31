package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
// K2J 互操作：master 侧这些声明是同包顶层函数/常量（同包直接引用），k2j 侧落到 Java 门面类的
// public static 成员——Kotlin 调 Java 静态须类名限定，故以 static import 收敛文件内改动。
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelKt.STACK_DIGEST_MAX_CHARS
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelTextKt.META_DIGEST_MIN_PX
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelTextKt.buildStackDigestInlineLine
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelTextKt.ellipsizeMiddle
import cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelTextKt.metaLineWithinBudget
import cn.song.mybatisplus.visualizer.ui.StackFrameHover.buildStackPopupTitle
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-SourceJumpFunnel §3.3「预览 meta 行显示调用栈、悬停见完整链」的自动化断言。
 *
 * 验收项原文（`docs/验收文档/20260917-2058-T-SourceJumpFunnel-自研框架汇聚入口跳源码上溯适配-验收文档.md`
 * 判定卡）：「预览 meta 行显示调用栈、悬停见完整链（§3.3）」。
 *
 * **T-StackHoverUi 契约换代（20261007）**：完整调用链的悬停载体从 tooltip 换成调试器 Frames
 * 风格悬停弹层（[StackFrameHover]）。旧实现把「截断摘要 + 全文」都拼进 tooltip——内容重复、
 * 超长单行任意折行成文本墙；现契约：
 *  - 可见行：120 字符截断摘要不变（[buildStackDigestInlineLine] 纯函数，前 P5 契约原样保留）；
 *  - tooltip：**不含任何栈文本**（meta/警示/perf 专属）；
 *  - 栈数据：经 [SqlPreviewPanel.stackDigestForTest] 断言已喂给弹层（真实悬停动作 headless
 *    不可自动化，弹层视觉走 V116 真机走查）。
 *
 * P5 复核修正（20260920）的历史契约（摘要不得挤掉主体）原样保留，见 `test组装_*` 五例与
 * 窄窗用例——主体优先的宽度预算规则与栈文本载体无关，不受本次换代影响。
 */
class SqlPreviewPanelStackDigestTest : BasePlatformTestCase() {

    private fun waitForEdt() = UIUtil.dispatchAllInvocationEvents()

    /** `   调用栈: ` 前缀（三空格 + bundle 文案 + 空格），与原实现的字面拼接同源，避免文案改动误伤断言。 */
    private fun prefix(): String =
        "   " + MpSqlVisualizerBundle.message("panel.meta.stackDigest") + " "

    /** 纯文案前缀（可见行节点用，无缩进）。 */
    private fun label(): String = MpSqlVisualizerBundle.message("panel.meta.stackDigest")

    /** 造一条长度恰为 [length] 的调用链（栈顶在左；**不含省略号**，便于区分截断标记来源）。 */
    private fun chainOf(length: Int): String {
        val unit = "com.corp.biz.Svc#m:1 <- "
        val sb = StringBuilder()
        while (sb.length < length) sb.append(unit)
        return sb.substring(0, length)
    }

    private fun makeResult(stackDigest: String?, threadName: String? = null): PreviewResult = PreviewResult(
        // K2J 互操作：master 侧具名实参 + 缺省值 → k2j Java 35 参规范构造器位置实参
        // （stackDigest 第 22 位、threadName 第 23 位；尾部与 23 参重载委托默认同值）。
        "SELECT id\nFROM user\nWHERE username = ?",
        "SELECT id FROM user WHERE username = ?",
        listOf("username"),
        null,
        WrapperType.LAMBDA_QUERY,
        "user",
        null,
        listOf("id", "username"),
        emptyMap(),
        null,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        null,
        null,
        null,
        null,
        emptyMap(),
        stackDigest,
        threadName,
        false,
        null,
        null,
        emptyList(),
        emptySet(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
    )

    // ------------------------------------------------------------------
    // 一、纯函数契约（可见行内联摘要）
    // ------------------------------------------------------------------

    fun test长调用栈_meta行摘要截断到120字符并带省略号() {
        val digest = chainOf(300)
        val inline = buildStackDigestInlineLine(digest)

        // 摘要 = 前 120 字符 + 截断标记（逐字期望值，非仅长度断言）
        assertEquals(prefix() + digest.substring(0, 120) + "…", inline)
        assertTrue("摘要应带截断标记", inline.endsWith("…"))
        // 调用栈本体长度 = 120 + 标记 1 字符，未超上限
        assertEquals(121, inline.removePrefix(prefix()).length)
        assertTrue(
            "摘要（去前缀后）不得超过 ${STACK_DIGEST_MAX_CHARS} + 标记长度",
            inline.length <= prefix().length + STACK_DIGEST_MAX_CHARS + 1,
        )
        assertFalse("摘要不得含完整链", inline.contains(digest))
    }

    fun test短调用栈_不截断不加标记() {
        val digest = chainOf(60)
        assertTrue("构造前提：短栈 < ${STACK_DIGEST_MAX_CHARS}", digest.length < STACK_DIGEST_MAX_CHARS)
        val inline = buildStackDigestInlineLine(digest)

        assertEquals(prefix() + digest, inline)
        assertFalse("短栈摘要不应出现截断标记", inline.contains("…"))
    }

    fun test边界_120字符不截断_121字符截断() {
        val at120 = chainOf(STACK_DIGEST_MAX_CHARS)
        assertEquals(STACK_DIGEST_MAX_CHARS, at120.length)
        assertEquals(prefix() + at120, buildStackDigestInlineLine(at120))

        val at121 = chainOf(STACK_DIGEST_MAX_CHARS + 1)
        assertEquals(STACK_DIGEST_MAX_CHARS + 1, at121.length)
        val truncated = buildStackDigestInlineLine(at121)
        assertEquals(prefix() + at121.substring(0, STACK_DIGEST_MAX_CHARS) + "…", truncated)
        // 边界外仅 1 字符，仍按「截到 120 + 标记」处理（严格大于阈值才截断）
        assertEquals(121, truncated.removePrefix(prefix()).length)
    }

    fun test无调用栈_内联摘要为空() {
        assertEquals("", buildStackDigestInlineLine(null))
    }

    // ------------------------------------------------------------------
    // T-StackBizFrame S3：弹层标题带线程名
    // ------------------------------------------------------------------

    fun test弹层标题_无线程名时退回纯调用栈() {
        val plain = MpSqlVisualizerBundle.message("panel.stackPopup.title")
        assertEquals(plain, buildStackPopupTitle(null))
        assertEquals("空白线程名视同未知", plain, buildStackPopupTitle("   "))
    }

    fun test弹层标题_带线程名时附加到标题() {
        val title = buildStackPopupTitle("scheduling-1")
        assertTrue("标题应含线程名: $title", title.contains("scheduling-1"))
        assertTrue(
            "标题应保留调用栈语义: $title",
            title.contains(MpSqlVisualizerBundle.message("panel.stackPopup.title")),
        )
    }

    fun test面板把线程名喂给弹层标题() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelResultViewKt.showResult(panel, makeResult(chainOf(60), threadName = "scheduling-1"))
            waitForEdt()
            assertEquals("scheduling-1", panel.stackThreadNameForTest())
            assertEquals("栈快照与线程名同源透传", chainOf(60), panel.stackDigestForTest())
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    fun test面板无线程名时弹层标题数据为空() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelResultViewKt.showResult(panel, makeResult(chainOf(60)))
            waitForEdt()
            assertNull("无线程名时不得留陈旧值", panel.stackThreadNameForTest())
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    /**
     * T-StackBizFrame S3：头部全是 agent/驱动帧时，内联摘要跳到首个项目帧——用户报障形态是
     * 「位置列有项目类，可见行的栈摘要全是驱动帧」。首个非内部帧本来就在开头时仍走原契约
     * （见上面三例），故本次改动不改变既有文案与宽度预算。
     */
    fun test长调用栈_头部全为内部帧时内联摘要跳到项目帧() {
        val digest = "cn.song.mybatisplus.sqlvis.agent.hook.WovenEvents#emit:70" +
            "<-org.postgresql.jdbc.PgPreparedStatement#execute:182" +
            "<-com.acme.service.OrderService#find:42" +
            "<-com.acme.task.OrderTask#run:45"
        val inline = buildStackDigestInlineLine(digest)
        assertEquals(
            prefix() + "com.acme.service.OrderService#find:42<-com.acme.task.OrderTask#run:45",
            inline,
        )
        assertFalse("摘要不得再以 agent 埋点帧开头", inline.contains("WovenEvents"))
        assertFalse("摘要不得以驱动帧开头", inline.contains("PgPreparedStatement"))
    }

    // ------------------------------------------------------------------
    // 二、面板接线（真实 showResult → tooltip / 弹层数据）
    // ------------------------------------------------------------------

    /**
     * T-StackHoverUi 契约：tooltip 不含任何栈文本（旧实现截断摘要+全文重复拼接、超长单行折成
     * 文本墙）；完整链喂给悬停弹层（[SqlPreviewPanel.stackDigestForTest]），真实悬停展示走
     * V116 真机走查。
     */
    fun test面板悬停tooltip_不含调用链_完整链归悬停弹层() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chainOf(300)
            SqlPreviewPanelResultViewKt.showResult(panel, makeResult(digest))
            waitForEdt()

            val tooltip = panel.metaTooltipForTest()
            assertNotNull("meta 行应有 tooltip（meta/警示/perf 载体）", tooltip)
            assertFalse("tooltip 不得含完整调用链", tooltip!!.contains(digest))
            assertFalse("tooltip 不得含调用栈文案节点", tooltip.contains(label()))
            assertFalse("tooltip 不得含截断摘要", tooltip.contains(prefix() + digest.substring(0, 120)))
            // 完整链换轨：弹层拿到原始快照（悬停展示的数据源）
            assertEquals("弹层应拿到完整调用链", digest, panel.stackDigestForTest())
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    fun test面板无调用栈_tooltip不含调用栈节点() {
        val panel = SqlPreviewPanel(project)
        try {
            SqlPreviewPanelResultViewKt.showResult(panel, makeResult(null))
            waitForEdt()

            val tooltip = panel.metaTooltipForTest() ?: ""
            assertFalse("无快照时 tooltip 不应出现调用栈文案", tooltip.contains(label()))
            assertFalse("无快照时可见 meta 行不应出现调用栈文案", panel.metaLabelForTest().contains(label()))
            assertEquals("无快照时弹层不应拿到栈数据", null, panel.stackDigestForTest())
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    /**
     * P5 已接通：可见 meta 行（`metaLabel.text`）**含**调用栈摘要——`metaLineText` 已并入
     * [buildStackDigestInlineLine] 的内联摘要。
     *
     * 本用例由原「现状锁定」用例改写而来（原文断言可见行**不含**摘要，缺口一修即转红）：
     * 断言可见行含 120 字符截断摘要、不含完整链，且完整链也不在 tooltip（T-StackHoverUi 起归弹层）。
     */
    fun test可见meta行含调用栈摘要_截断到120字符() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chainOf(300)
            SqlPreviewPanelResultViewKt.showResult(panel, makeResult(digest))
            waitForEdt()
            // 未布局分支（headless）：metaLabel.text 取 fullDisplayText() = metaLineText
            panel.applyMetaTruncationForTest()
            waitForEdt()

            val visible = panel.metaLabelForTest()
            assertTrue("可见 meta 行应含调用栈文案节点", visible.contains(label()))
            assertTrue("可见行应含 120 字符截断摘要（带截断标记）", visible.contains(digest.substring(0, 120) + "…"))
            assertFalse("可见行不应含完整链（完整链归悬停弹层）", visible.contains(digest))
            // T-StackHoverUi 反证：完整链不再退回 tooltip（旧 P6 契约已换代）
            assertFalse((panel.metaTooltipForTest() ?: "").contains(digest))
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    /**
     * P5 + F3 宽度预算：窄窗下**主体优先**——可见行保留 meta 主体（表名 / wrapper 类型），
     * 调用栈摘要按剩余宽度截尾。
     *
     * 本用例由首版 `test窄窗_可见行中段省略_tooltip恒全文` 改写（原文断言「省略后保留摘要尾段」，
     * 即把「主体被挤掉、只剩栈底帧」当预期行为锁定——复核 20260920 判定为优先级倒挂，转红即此处同步）。
     * T-StackHoverUi：原「tooltip 恒全文」断言换代为「tooltip 不含栈文本」。
     *
     * headless 无 componentResized，按 SqlPreviewTopRowLayoutTest 的既有做法手动逐层 doLayout
     * 后直调 [SqlPreviewPanel.applyMetaTruncationForTest]。
     */
    fun test窄窗_主体完整保留_摘要截尾() {
        val panel = SqlPreviewPanel(project)
        try {
            val digest = chainOf(300)
            SqlPreviewPanelResultViewKt.showResult(panel, makeResult(digest))
            waitForEdt()
            panel.setSize(640, 420)
            panel.doLayout()
            panel.topRowForTest().parent?.doLayout()
            panel.topRowForTest().doLayout()
            panel.applyMetaTruncationForTest()
            waitForEdt()

            val visible = panel.metaLabelForTest()
            assertTrue("窄窗应触发截断", visible.contains("…"))
            assertFalse("窄窗可见行不得含完整链", visible.contains(digest))
            // 复核回归点：主体（表名）不得被摘要挤掉——首版实现此处会转红（可见行只剩「…」+ 栈底帧）
            assertTrue("主体（表名）必须仍在可见行内: $visible", visible.contains("user"))
            // T-StackHoverUi：tooltip 不承载栈文本（宽度预算与栈展示解耦）
            assertFalse((panel.metaTooltipForTest() ?: "").contains(digest))
        } finally {
            SqlPreviewPanelSqlViewKt.clear(panel)
        }
    }

    // ------------------------------------------------------------------
    // P5 复核修正：meta 行宽度预算组装（纯函数，脱 FontMetrics）
    // ------------------------------------------------------------------

    /** 1 字符 = 1px 的度量替身：宽度断言可直接读成字符数。 */
    private val widthOf: (String) -> Int = { it.length }

    /**
     * K2J 互操作：Java 侧形参类型是 `ToIntFunction<String>`，而 Kotlin 函数类型**变量**不会自动
     * SAM 转换（只有 lambda 字面量 / 可调用引用会）——故显式包一层供门面纯函数调用；
     * 断言里读字符数仍用 [widthOf] 直调。
     */
    private val widthOfFn: java.util.function.ToIntFunction<String> = java.util.function.ToIntFunction { widthOf(it) }

    private fun headText() = "表: user 类型: LambdaQueryWrapper"

    private fun digestText() = "   调用栈: " + chainOf(120)

    fun test组装_放得下则整段原样返回() {
        val head = headText()
        val digest = digestText()
        assertEquals(head + digest, metaLineWithinBudget(head + digest, head, digest, widthOfFn, 200))
    }

    fun test组装_无摘要时退回F3中段省略() {
        val head = headText()
        val out = metaLineWithinBudget(head, head, "", widthOfFn, 12)
        assertEquals("无快照路径须与 F3 原行为逐字一致", ellipsizeMiddle(head, widthOfFn, 12), out)
        assertTrue("应中段省略: $out", out.contains("…"))
    }

    fun test组装_主体自身超预算_主体中段省略() {
        val head = headText()
        val digest = digestText()
        // 预算 12 < 主体宽：摘要无从谈起，退回主体自身的 F3 中段省略
        assertEquals(ellipsizeMiddle(head, widthOfFn, 12), metaLineWithinBudget(head + digest, head, digest, widthOfFn, 12))
    }

    fun test组装_主体放得下_摘要截尾且主体不被挤掉() {
        val head = headText()
        val digest = digestText()
        val budget = head.length + META_DIGEST_MIN_PX + 4
        val out = metaLineWithinBudget(head + digest, head, digest, widthOfFn, budget)
        assertTrue("主体必须完整保留（复核回归点）: $out", out.startsWith(head))
        assertTrue("摘要应被截尾（带省略号）: $out", out.endsWith("…"))
        assertFalse("摘要不得整段保留: $out", out.contains(digest))
        assertTrue("总宽不得超预算: ${widthOf(out)} > $budget", widthOf(out) <= budget)
    }

    fun test组装_剩余宽度不足_整段丢弃摘要() {
        val head = headText()
        val digest = digestText()
        val budget = head.length + META_DIGEST_MIN_PX - 1
        assertEquals(
            "剩余不足应整段丢摘要（不留孤零零的省略号）",
            head,
            metaLineWithinBudget(head + digest, head, digest, widthOfFn, budget),
        )
    }
}
