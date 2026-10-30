package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-CaptureTruth T-CT-03：零产出诊断行纯函数测试。
 *
 * 守卫目标：捕捉中且零条目时，面板必须给出「卡在哪一环」的可读结论——
 * 等待控制台 / 控制台无输出 / 有输出但没匹配到 SQL 三档提示不可互相串味（现场排障全靠这三档分流）。
 * 有条目或未捕捉时返回 null（面板还原默认空态文案）。
 *
 * T-RA-03 追加守卫：重放丢弃 / EP 短路两个计数（`SqlLogConsoleBridge` 侧原本只自增、无人读）必须在
 * 诊断行可见且数值正确，同时零值不得出现——否则「有行但被守卫/EP 吃掉」与「控制台没输出」
 * 在面板上仍是同一种沉默，正是本次审计要封的口。
 *
 * T-AgentDiagWrap T-04 追加守卫：面板空态是 `StatusText`（不是 JLabel），**不认 HTML、不把 `\n`
 * 当换行**——`\n` 被无声吞掉、超宽只裁不折，现场被裁掉的恰是那句可执行提示。折行由纯函数
 * [wrapEmptyTextLines] 在写入前算好，本组用例锁住：语义三行、视觉行不含裸换行符、
 * 每行不超宽度上限、折行不丢字、零宽/极窄宽度按下限兜底。
 */
class CaptureDiagnosticsTest {

    private fun diag(
        capturing: Boolean = true,
        waiting: Boolean = false,
        entries: Int = 0,
        consoles: Int = 1,
        lines: Long = 0L,
        enhanced: String? = null,
        dropped: Long = 0L,
        shortCircuited: Long = 0L,
        suppressed: Long = 0L,
    ) = CaptureDiagnostics(
        capturing, waiting, entries, consoles, lines, enhanced, dropped, shortCircuited,
        agentEventsReceived = 0L, suppressed = suppressed,
    )

    @Test
    fun test未捕捉或无诊断必要时返回null() {
        assertNull("未捕捉 → 无需诊断行", captureDiagnosticsText(diag(capturing = false)))
        assertNull("已有条目 → 无需诊断行", captureDiagnosticsText(diag(entries = 3)))
    }

    @Test
    fun test捕捉中零条目输出通道状态与计数() {
        val text = captureDiagnosticsText(diag(consoles = 2, lines = 150L, enhanced = "已挂载 pid=123"))!!
        assertTrue("应含「捕捉中」：$text", text.contains("捕捉中"))
        assertTrue("应含增强通道状态：$text", text.contains("已挂载 pid=123"))
        assertTrue("应含控制台挂载数：$text", text.contains("2"))
        assertTrue("应含已扫描行数：$text", text.contains("150"))
    }

    @Test
    fun test增强未开启时显示未启用() {
        val text = captureDiagnosticsText(diag(enhanced = null))!!
        assertTrue("增强未开启应显式标注未启用：$text", text.contains("未启用"))
    }

    @Test
    fun test等待控制台时提示等待项目运行() {
        val text = captureDiagnosticsText(diag(waiting = true, consoles = 0))!!
        assertTrue("应提示等待项目运行：$text", text.contains("等待项目运行"))
    }

    @Test
    fun test控制台已挂载但无输出时提示去运行应用() {
        val text = captureDiagnosticsText(diag(waiting = false, consoles = 1, lines = 0L))!!
        assertTrue("应提示控制台暂无输出：$text", text.contains("控制台暂无输出"))
    }

    @Test
    fun test控制台有输出但无SQL时指向日志级别配置() {
        val text = captureDiagnosticsText(diag(consoles = 1, lines = 4200L))!!
        assertTrue("应指出有输出但未见 SQL 日志：$text", text.contains("未见 MyBatis SQL 日志"))
        assertTrue("应给出可执行下一步（日志级别/增强捕获）：$text", text.contains("debug"))
    }

    @Test
    fun test三档提示互不串味() {
        val waiting = captureDiagnosticsText(diag(waiting = true, consoles = 0))!!
        val noOutput = captureDiagnosticsText(diag(waiting = false, lines = 0L))!!
        val noSql = captureDiagnosticsText(diag(waiting = false, lines = 10L))!!
        assertTrue("等待档不得出现「控制台暂无输出」", !waiting.contains("控制台暂无输出"))
        assertTrue("无输出档不得出现「未见 MyBatis SQL 日志」", !noOutput.contains("未见 MyBatis SQL 日志"))
        assertTrue("有输出档不得出现「等待项目运行」", !noSql.contains("等待项目运行"))
    }

    // ---------- T-RA-03：丢弃/短路计数出口 ----------

    @Test
    fun test计数为零时不显示丢弃与短路() {
        val text = captureDiagnosticsText(diag(consoles = 1, lines = 30L))!!
        assertTrue("零丢弃不得出现重放守卫计数：$text", !text.contains("重放守卫"))
        assertTrue("零短路不得出现 EP 计数：$text", !text.contains("EP 通道"))
    }

    @Test
    fun test计数非零时显示且数值正确() {
        val text = captureDiagnosticsText(diag(lines = 30L, dropped = 7L, shortCircuited = 128L))!!
        assertTrue("应显示重放丢弃行数 7：$text", text.contains("重放守卫已丢弃 7 行"))
        assertTrue("应显示 EP 短路行数 128：$text", text.contains("EP 通道已短路 128 行"))
        assertTrue("两个计数不得互相串值：$text", !text.contains("重放守卫已丢弃 128"))
    }

    @Test
    fun test仅一档非零时另一档不出现() {
        val onlyDropped = captureDiagnosticsText(diag(lines = 5L, dropped = 2L))!!
        assertTrue("仅丢弃非零：应显示丢弃：$onlyDropped", onlyDropped.contains("重放守卫已丢弃 2 行"))
        assertTrue("仅丢弃非零：不得出现 EP 计数：$onlyDropped", !onlyDropped.contains("EP 通道"))
        assertTrue("仅丢弃非零：不得出现屏蔽计数：$onlyDropped", !onlyDropped.contains("屏蔽规则命中"))

        val onlyShort = captureDiagnosticsText(diag(lines = 5L, shortCircuited = 3L))!!
        assertTrue("仅短路非零：应显示短路：$onlyShort", onlyShort.contains("EP 通道已短路 3 行"))
        assertTrue("仅短路非零：不得出现丢弃计数：$onlyShort", !onlyShort.contains("重放守卫"))
    }

    /**
     * T-DevAcceptFix2 ⑩（20261006 第三轮走查）：屏蔽规则命中必须可见——用户现场「已挂载、
     * 已收事件 8 条、列表 0 条」无任何提示，事后才查明是自建**结构匹配**规则按设计拦下；
     * 计数与重放/EP 同口径（> 0 才显示，零值不添噪）。
     */
    @Test
    fun test屏蔽规则命中计数可见且零值不显示() {
        val text = captureDiagnosticsText(diag(lines = 30L, suppressed = 8L))!!
        assertTrue("屏蔽命中非零应显示：$text", text.contains("屏蔽规则命中 8 条"))
        val zero = captureDiagnosticsText(diag(lines = 30L))!!
        assertTrue("零命中不得出现屏蔽计数：$zero", !zero.contains("屏蔽规则命中"))
    }

    @Test
    fun test计数不干扰三档提示选择() {
        // 计数与提示档位正交：有行 + 有丢弃时仍走「有输出但未见 SQL」档，不得退化成「无输出」档
        val text = captureDiagnosticsText(diag(lines = 30L, dropped = 1L, shortCircuited = 1L))!!
        assertTrue("提示档位应由行数决定，不被计数改写：$text", text.contains("未见 MyBatis SQL 日志"))
        assertTrue("不得出现无输出档提示：$text", !text.contains("控制台暂无输出"))
    }

    // ---------- T-AgentDiagWrap T-04：空态文案折行（Swing 不认 \n，折行须自己算） ----------

    /** 可预测的宽度桩：每字符 10px（不依赖字体度量，保持纯函数可测）。 */
    private val stubMeasure: (String) -> Int = { it.length * 10 }

    @Test
    fun test诊断文案按语义分成三行() {
        val text = captureDiagnosticsText(diag(consoles = 5, lines = 88L, enhanced = "已挂载 pid=1", dropped = 8L))!!
        val lines = wrapEmptyTextLines(text, 4000, stubMeasure)
        assertEquals("语义行应为三行（捕捉中+增强 / 控制台+计数 / 可执行提示）：$lines", 3, lines.size)
        assertTrue("首行应含捕捉中与增强通道：${lines[0]}", lines[0].contains("捕捉中") && lines[0].contains("已挂载 pid=1"))
        assertTrue("次行应含控制台通道与丢弃计数：${lines[1]}", lines[1].contains("控制台") && lines[1].contains("重放守卫已丢弃 8 行"))
        assertTrue("末行应是那句可执行提示：${lines[2]}", lines[2].contains("未见 MyBatis SQL 日志"))
    }

    @Test
    fun test视觉行不含裸换行符() {
        val text = captureDiagnosticsText(diag(consoles = 1, lines = 4200L, dropped = 3L))!!
        val lines = wrapEmptyTextLines(text, 200, stubMeasure)
        assertTrue("视觉行不得含裸 \\n（StatusText 不认换行，会被无声拼接成一行）：$lines", lines.none { it.contains('\n') })
        assertTrue("视觉行不得含裸 \\r：$lines", lines.none { it.contains('\r') })
    }

    @Test
    fun test超宽行折行且每行不超宽度上限() {
        val text = captureDiagnosticsText(diag(consoles = 1, lines = 4200L))!!
        val lines = wrapEmptyTextLines(text, 200, stubMeasure)
        assertTrue("超宽应折成多行（现场正是这一档被右边界裁掉提示）：$lines", lines.size > 3)
        assertTrue("每视觉行不得超过宽度上限：$lines", lines.all { stubMeasure(it) <= 200 })
    }

    @Test
    fun test折行只搬位置不改内容() {
        val text = captureDiagnosticsText(diag(consoles = 1, lines = 4200L, dropped = 2L))!!
        val lines = wrapEmptyTextLines(text, 200, stubMeasure)
        assertEquals(
            "折行不得丢字（空白断行处允许丢空格）",
            text.filterNot { it == '\n' || it == ' ' },
            lines.joinToString("").filterNot { it == ' ' },
        )
    }

    @Test
    fun test零宽与极窄宽度按下限排版且不产空行() {
        val text = captureDiagnosticsText(diag(consoles = 1, lines = 5L))!!
        val zero = wrapEmptyTextLines(text, 0, stubMeasure)
        assertTrue("宽度 0 应按下限排版（语义行仍是 3 行）：$zero", zero.size >= 3)
        assertTrue("宽度 0 不得产出空行：$zero", zero.none { it.isBlank() })
        val tiny = wrapEmptyTextLines(text, 1, stubMeasure)
        assertEquals(
            "极窄宽度须 clamp 到下限（否则每行一个字符，排版碎成竖排）",
            wrapEmptyTextLines(text, EMPTY_TEXT_MIN_WIDTH_PX, stubMeasure),
            tiny,
        )
        assertEquals(
            "极窄宽度折行同样不得丢字",
            text.filterNot { it == '\n' || it == ' ' },
            tiny.joinToString("").filterNot { it == ' ' },
        )
    }

    @Test
    fun test英文按空白断行不切碎单词() {
        val source = "alpha beta gamma delta"
        val lines = wrapEmptyTextLines(source, 110, stubMeasure)
        assertTrue("应在空白处断行且不留首尾空格：$lines", lines.all { it == it.trim() })
        assertTrue("不得把单词切成两半：$lines", lines.flatMap { it.split(" ") }.all { it in source.split(" ") })
    }

    // ---------- T-CaptureCatchAll 券02/03：活性回落 + attach 覆盖自检 ----------

    private fun diagWithCoverage(
        fallback: Boolean = false,
        stats: cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats? = null,
    ) = CaptureDiagnostics(
        capturing = true,
        waitingForConsole = false,
        entryCount = 0,
        consolesAttached = 1,
        linesSeen = 30L,
        enhancedStatus = "已挂载 pid=1",
        replayDropped = 0L,
        epShortCircuited = 0L,
        agentEventsReceived = 0L,
        suppressed = 0L,
        activityFallbackActive = fallback,
        attachStats = stats,
    )

    @Test
    fun test活性回落解封态必须可见且只出现一次() {
        val text = captureDiagnosticsText(diagWithCoverage(fallback = true))!!
        assertTrue("解封态必须如实披露「已回退控制台解析」：$text", text.contains("增强捕获疑似空转，已回退控制台解析"))
        assertEquals("解封文案不得重复", 1, Regex("增强捕获疑似空转").findAll(text).count())
        val normal = captureDiagnosticsText(diagWithCoverage(fallback = false))!!
        assertTrue("未解封时不得出现回落文案：$normal", !normal.contains("增强捕获疑似空转"))
    }

    @Test
    fun testattach零命中必须可见_替代静默() {
        val stats = cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats(
            matched = 0L, weavable = 0L, woven = 0L, matchedByInterface = 0L,
        )
        val text = captureDiagnosticsText(diagWithCoverage(stats = stats))!!
        assertTrue("零命中必须报「本次 attach 命中 0 个驱动」：$text", text.contains("本次 attach 命中 0 个驱动"))
    }

    @Test
    fun testattach命中但未织入时指向织入链() {
        val stats = cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats(
            matched = 4L, weavable = 4L, woven = 0L,
        )
        val text = captureDiagnosticsText(diagWithCoverage(stats = stats))!!
        assertTrue("命中但零织入应单独叙事：$text", text.contains("命中 4 个驱动但未织入"))
        assertTrue("不得说成「命中 0 个」：$text", !text.contains("命中 0 个驱动"))
    }

    @Test
    fun test接口兜底与失败计数可见且互不串味() {
        val stats = cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats(
            matched = 6L, weavable = 6L, woven = 5L, matchedByInterface = 2L, failed = 1L,
        )
        val text = captureDiagnosticsText(diagWithCoverage(stats = stats))!!
        assertTrue("接口兜底命中应可见：$text", text.contains("接口兜底匹配 2 个驱动"))
        assertTrue("retransform 失败应可见：$text", text.contains("attach 失败 1 个类"))
        assertTrue("正常命中不得报零命中：$text", !text.contains("命中 0 个驱动"))
    }

    @Test
    fun test版本闸与熔断事实可见() {
        val stats = cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats(
            weavable = 5L, woven = 5L, skippedByVersion = 3L, catchAll = 2,
        )
        val text = captureDiagnosticsText(diagWithCoverage(stats = stats))!!
        assertTrue("版本闸跳过类数应可见：$text", text.contains("3 个类因类文件版本过高被跳过"))
        assertTrue("接口兜底熔断应可见：$text", text.contains("接口兜底已熔断"))
    }

    @Test
    fun test旧版agent未上报时零噪音() {
        val text = captureDiagnosticsText(diagWithCoverage(stats = null))!!
        assertTrue("未上报不得编造覆盖结论：$text", !text.contains("attach"))
        assertTrue("未上报不得出现接口兜底：$text", !text.contains("接口兜底"))
    }

    @Test
    fun test新增覆盖事实不改变三行语义形态() {
        val stats = cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats(
            matched = 0L, weavable = 0L, woven = 0L, matchedByInterface = 0L, failed = 2L,
        )
        val text = captureDiagnosticsText(diagWithCoverage(fallback = true, stats = stats))!!
        val lines = wrapEmptyTextLines(text, 4000, stubMeasure)
        assertEquals("新增事实必须并入首行（第四行会被面板右边界裁掉）：$lines", 3, lines.size)
        assertTrue("首行应同时含增强状态、回落与覆盖事实：${lines[0]}", lines[0].contains("已挂载 pid=1") &&
            lines[0].contains("增强捕获疑似空转") && lines[0].contains("本次 attach 命中 0 个驱动"))
    }
}
