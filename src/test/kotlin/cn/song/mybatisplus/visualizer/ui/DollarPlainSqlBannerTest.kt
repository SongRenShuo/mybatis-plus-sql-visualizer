package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import cn.song.mybatisplus.visualizer.ui.standalone.StandaloneSqlSourceResolver
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.DataFlavor

/**
 * T-DollarPlainSqlBanner D252：裸 SQL 形态 `${}` 直替横幅补齐（D222 定谳残余的唯一缺口）。
 *
 *  - 裸 SQL（独立工作台/弹窗直贴 [StandaloneSqlSourceResolver] PLAIN_SQL 链）含非转义 `${}`
 *    → DOLLAR_INJECT 横幅按产物计数（[cn.song.mybatisplus.visualizer.model.PreviewResult.dollarRawCount]）
 *    显示、执行弹既有确认门、复制产物带标记注释——三处与 XML 形态行为对齐；
 *  - 计数口径 string-aware（字符串字面量/注释内的 `${}` 是字面量不计数；`\${` 转义不计数）——
 *    **与 XML 链「字符串内同样计数」刻意不同**：XML/iBatis 引擎渲染后字符串内同样直替（MyBatis
 *    渲染器对 `'a${b}'` 文本中的 `${b}` 照样替换），裸 SQL 是用户手写文本、字符串内就是字面量。
 *    两链各自忠实各自引擎，本册不跨链统一（口径出处：D252 计划册 §一「口径差异警示」）；
 *  - XML 形态行为回归由既有 [DollarInjectBannerTest] 全绿锁定（定向清单同跑）。
 *
 * headless 形态：沙盒预览降级链常驻（DEGRADED 挂起时 DOLLAR_INJECT 让位）——横幅断言先
 * showResult 走真实装配链，遇 DEGRADED 让位则 clear 后直调 [updateMeta] 重算（updateMeta 是
 * 同包 internal 扩展，DOLLAR_INJECT 分支逻辑本身在扩展函数内，装配链与横幅逻辑解耦，直调
 * 不失真）；DEGRADED 未挂时按真实链断言，不做无中生有的跳过。
 */
class DollarPlainSqlBannerTest : BasePlatformTestCase() {

    private var panel: SqlPreviewPanel? = null

    override fun tearDown() {
        // 会话级记忆复位（companion 静态跨用例存活，DollarInjectBannerTest 同款约定）
        SqlPreviewPanel.resetDollarMarkForTest()
        panel?.let {
            runCatching { it.clear() }
            runCatching { Disposer.dispose(it) }
        }
        panel = null
        super.tearDown()
    }

    // ------------------------------------------------------------------
    // 夹具：一律走生产链 [StandaloneSqlSourceResolver.resolve]（识别口径与灌入口径同源）
    // ------------------------------------------------------------------

    private fun newPanel(): SqlPreviewPanel = SqlPreviewPanel(project).also { this.panel = it }

    private fun plainSql(text: String): cn.song.mybatisplus.visualizer.model.PreviewResult {
        val resolved = StandaloneSqlSourceResolver.resolve(text)
        checkNotNull(resolved) { "前置：裸 SQL 应被识别（非 null）" }
        check(resolved.kind == StandaloneSqlSourceResolver.Kind.PLAIN_SQL) {
            "前置：应走 PLAIN_SQL 链，实际 ${resolved.kind}"
        }
        check(resolved.failure == null) { "前置：解析不应失败 ${resolved.failure}" }
        return resolved.result
    }

    /** 拦截确认框（InjectionGateTest 同款），逐次答案用尽兜底 NO。 */
    private val dialogMessages = mutableListOf<String>()
    private val dialogAnswers = mutableListOf<Int>()

    private fun interceptDialogs(vararg answers: Int) {
        dialogMessages.clear()
        dialogAnswers.clear()
        dialogAnswers.addAll(answers.toList())
        TestDialogManager.setTestDialog(
            TestDialog { message ->
                dialogMessages += message
                dialogAnswers.removeFirstOrNull() ?: Messages.NO
            },
            testRootDisposable,
        )
    }

    private fun panelState(): String = panel!!.resultPanelForTest().stateForTest()

    private fun clipboardText(): String? = runCatching {
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
    }.getOrNull()

    /**
     * 横幅断言口：showResult 走真实装配链后，DEGRADED 让位（headless 常驻）时 clear 后直调
     * updateMeta 重算横幅分支；非降级态原样返回。
     */
    private fun recomputeBannerAfterShow(result: cn.song.mybatisplus.visualizer.model.PreviewResult) {
        val p = panel ?: error("面板未创建")
        p.showResult(result)
        val banner = p.degradationBannerForTest()
        if (banner.kindForTest() == DegradationBanner.Kind.DEGRADED) {
            // headless 降级链常驻（内存 PSI 装配横幅）：DOLLAR_INJECT 让位属设计行为。
            // 收掉降级横幅后重算一次横幅分支——被测的 DOLLAR_INJECT 判定逻辑与装配链解耦。
            banner.clear(DegradationBanner.Kind.DEGRADED)
            p.updateMeta(result)
        }
    }

    // ------------------------------------------------------------------
    // 计数口径（D252 M1：string-aware + 转义识别）
    // ------------------------------------------------------------------

    fun test计数_裸SQL多站点如实计数() {
        val result = plainSql(
            """
            SELECT ${'$'}{id} FROM t_biz WHERE a = ${'$'}{b} OR c = ${'$'}{d}
            """.trimIndent(),
        )
        assertTrue("三处直替 → 闸门信号为真", result.hasDollarRawSegments)
        assertEquals("站点计数 = 3", 3, result.dollarRawCount)
        assertTrue("产物文本保留 raw 段原文", result.sql.contains("\${"))
    }

    fun test计数_转义形态不计数() {
        // `\${` 是 MyBatis GenericTokenParser 的「原样输出」语义（紧邻反斜杠＝转义），无直替面
        val result = plainSql("SELECT * FROM t_biz WHERE a = \\${'$'}{id}")
        assertFalse("转义形态不计入 raw 段", result.hasDollarRawSegments)
        assertEquals("转义形态计数为 0", 0, result.dollarRawCount)
        assertTrue("转义形态原文保留（含反斜杠）", result.sql.contains("\\"))
    }

    /**
     * 裸 SQL string-aware 口径：字符串字面量内的 `${}` 是字面量，不计数。
     * **与 XML 链「字符串内同样计数」刻意不同**（XML 引擎渲染后字符串内照样直替；
     * 裸 SQL 手写文本字符串内就是字面量）——口径出处：D252 计划册 §一「口径差异警示」。
     */
    fun test计数_字符串字面量内不计() {
        val result = plainSql("SELECT * FROM t_biz WHERE name = 'a${'$'}{b}' AND c = ${'$'}{d}")
        assertTrue("闸门信号按字符串外站点判定（1 处 > 0）", result.hasDollarRawSegments)
        assertEquals("字符串内的 \${b} 不计，只数字符串外 1 处", 1, result.dollarRawCount)
    }

    fun test计数_纯井号问号不触发() {
        val result = plainSql("SELECT * FROM t_biz WHERE a = #{a} AND b = ?")
        assertFalse("纯 #{} / ? 无 raw 段", result.hasDollarRawSegments)
        assertEquals(0, result.dollarRawCount)
    }

    fun test计数_注释内不计() {
        val result = plainSql(
            """
            -- tail ${'$'}{x}
            SELECT /* mid ${'$'}{y} */ * FROM t_biz WHERE a = ${'$'}{b}
            """.trimIndent(),
        )
        assertEquals("行注释与块注释内的 \${} 不计，只数正文 1 处", 1, result.dollarRawCount)
    }

    // ------------------------------------------------------------------
    // 横幅接线（D252 M1：非 XML 形态读产物计数）
    // ------------------------------------------------------------------

    fun test横幅_裸SQL直替按计数显示() {
        val p = newPanel()
        val result = plainSql("SELECT * FROM t_biz WHERE id = ${'$'}{id} OR name = ${'$'}{name}")
        recomputeBannerAfterShow(result)
        val banner = p.degradationBannerForTest()
        assertEquals("两处直替 → DOLLAR_INJECT 挂起", DegradationBanner.Kind.DOLLAR_INJECT, banner.kindForTest())
        assertEquals(
            MpSqlVisualizerBundle.message("panel.banner.dollarInject", SqlPreviewPanel.DOLLAR_TOKEN, 2),
            banner.textForTest(),
        )
        assertTrue("信息级类别应带 ✕ 关闭按钮", banner.dismissVisibleForTest())
    }

    fun test横幅_裸SQL无直替不挂() {
        val p = newPanel()
        val result = plainSql("SELECT * FROM t_biz WHERE a = #{a} AND b = ?")
        recomputeBannerAfterShow(result)
        assertTrue(
            "无直替 → 不挂直替类别（${p.degradationBannerForTest().kindForTest()}）",
            p.degradationBannerForTest().kindForTest() != DegradationBanner.Kind.DOLLAR_INJECT,
        )
    }

    /**
     * 互斥取值守卫：xmlDollarSubstitutions 是面板渲染态（仅 xmlModel != null 轮复位），纯 SQL
     * 轮不得读它——先看含 `${}` 的 XML（面板渲染计数 2），再灌无 `${}` 的裸 SQL，横幅必须收掉
     * 而不是被残留计数误挂。
     */
    fun test横幅_纯SQL轮不读XML渲染态残留() {
        val p = newPanel()
        val mapper = """
            <mapper namespace="com.example.BizMapper">
              <select id="s1" resultType="map">
                SELECT * FROM t_biz ORDER BY ${'$'}{'create_time'} , ${'$'}{'id'}
              </select>
            </mapper>
        """.trimIndent()
        val xmlResolved = StandaloneSqlSourceResolver.resolve(mapper)
        checkNotNull(xmlResolved)
        val xmlResult = xmlResolved.result
        check(xmlResult.xmlModel != null) { "前置：XML 形态应带 xmlModel" }
        recomputeBannerAfterShow(xmlResult)
        // XML 形态横幅挂起与否不作硬断言（headless 降级让位既有守卫处理，DollarInjectBannerTest
        // 已锁计数文案）；本用例防的是下一轮——若此处横幅挂着，恰好构成「残留误挂」的最强前情。
        // 灌裸 SQL（无 ${}）——xmlDollarSubstitutions 不复位（残留旧值），产物计数为 0
        val plainResult = plainSql("SELECT * FROM t_biz WHERE a = #{a}")
        recomputeBannerAfterShow(plainResult)
        assertTrue(
            "纯 SQL 轮横幅应收掉，不被残留 XML 计数误挂（${p.degradationBannerForTest().kindForTest()}）",
            p.degradationBannerForTest().kindForTest() != DegradationBanner.Kind.DOLLAR_INJECT,
        )
    }

    fun test横幅_关闭后裸SQL轮不再弹() {
        val p = newPanel()
        val result = plainSql("SELECT * FROM t_biz WHERE id = ${'$'}{id}")
        recomputeBannerAfterShow(result)
        val banner = p.degradationBannerForTest()
        if (banner.kindForTest() == DegradationBanner.Kind.DOLLAR_INJECT) {
            banner.clickDismissForTest()
            // 会话记忆后重算横幅：不再挂
            p.updateMeta(result)
            assertTrue(
                "✕ 后会话记忆生效 → 不再挂直替类别（${banner.kindForTest()}）",
                banner.kindForTest() != DegradationBanner.Kind.DOLLAR_INJECT,
            )
        }
        // headless 降级让位时 ✕ 不可点，会话记忆门控由组件级用例（DollarInjectBannerTest）覆盖
    }

    // ------------------------------------------------------------------
    // 执行闸门（P3 实证：闸门对裸 SQL 实际生效）
    // ------------------------------------------------------------------

    fun test执行_裸SQL含直替弹确认且NO中断() {
        interceptDialogs(Messages.NO)
        val p = newPanel()
        p.showResult(plainSql("SELECT * FROM t_biz WHERE id = ${'$'}{id}"))
        assertEquals("前置：未执行时结果面板为空态", "EMPTY", panelState())

        p.executeSql()

        assertEquals("含直替的裸 SQL 执行前应弹一次确认", 1, dialogMessages.size)
        assertEquals(
            MpSqlVisualizerBundle.message("execute.confirm.dollarRaw.message", SqlPreviewPanel.DOLLAR_TOKEN),
            dialogMessages[0],
        )
        assertTrue("文案应点明 \${} 记号：${dialogMessages[0]}", dialogMessages[0].contains("\${}"))
        assertEquals("选 NO 应中止（结果面板停留空态）", "EMPTY", panelState())
    }

    fun test执行_裸SQL无直替不弹门() {
        interceptDialogs(Messages.NO)
        val p = newPanel()
        p.showResult(plainSql("SELECT * FROM t_biz WHERE a = #{a} AND b = ?"))

        p.executeSql()

        assertEquals("无直替的裸 SQL 照旧免确认（闸门零新增）", 0, dialogMessages.size)
    }

    fun test执行_裸SQL含直替确认后放行() {
        interceptDialogs(Messages.YES)
        val p = newPanel()
        p.showResult(plainSql("SELECT * FROM t_biz WHERE id = ${'$'}{id}"))

        p.executeSql()

        assertEquals("确认弹一次", 1, dialogMessages.size)
        assertTrue(
            "确认后应进入执行链（实际状态 ${panelState()}）",
            panelState() != "EMPTY",
        )
    }

    // ------------------------------------------------------------------
    // 复制链（读口同源：panel.hasDollarRawSegments() 读裸 SQL 产物）
    // ------------------------------------------------------------------

    fun test复制_裸SQL含直替产物带标记注释() {
        val p = newPanel()
        p.showResult(plainSql("SELECT * FROM t_biz WHERE id = ${'$'}{id}"))
        assertTrue("前置：闸门读口对裸 SQL 为真", p.hasDollarRawSegments())

        val copied = CopyExecutableSqlAction(project).execute(p)

        assertTrue("复制应成功", copied)
        val text = clipboardText()
        assertTrue("产物头应带 raw 标记注释：$text", text!!.startsWith(CopyExecutableSqlAction.DOLLAR_RAW_MARKER + "\n"))
        assertTrue("标记之后是原产物", text.removePrefix(CopyExecutableSqlAction.DOLLAR_RAW_MARKER + "\n").contains("t_biz"))
    }

    fun test复制_裸SQL无直替产物无标记() {
        val p = newPanel()
        p.showResult(plainSql("SELECT * FROM t_biz WHERE a = #{a}"))

        CopyExecutableSqlAction(project).execute(p)

        val text = clipboardText()
        assertFalse("无直替产物逐字节不变：$text", text!!.contains(CopyExecutableSqlAction.DOLLAR_RAW_MARKER))
    }
}
