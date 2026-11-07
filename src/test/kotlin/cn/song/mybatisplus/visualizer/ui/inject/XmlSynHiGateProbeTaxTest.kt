package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * F79（T-PerfSweep24 性能二十四轮）：per-host 探针税两刀的判别断言。
 *
 * 背景三因素相乘（本轮报告 §4.3）：探针跑在 mapper 判定之前 × per-host 结论不缓存 ×
 * 删白名单后全部三方注入器进探测圈——任意 XML 每文本/属性节点每高亮 pass 白付全链探针。
 *
 *  - **刀一（谓词前置重排）**：inMapperFile/属性白名单/区间守卫前置到 per-host 让位门之前。
 *    重排语义等价对账（§四前提①）：守卫与探针互不写对方消费的状态，注入判定是纯 AND——
 *    守卫拒绝（不注入）与探针异常→让位（同样不注入）结果一致，交换不影响任何 case 的注入
 *    结果，唯一差异＝探针咨询次数（非 mapper/非白名单宿主从每问一次降为零）。
 *    判别＝计数探针缝下直调注入器（管线级判别见 SqlMapperInjectorWiringTest 补例）。
 *  - **刀二（stamped memo）**：探针结论按（宿主弱身份＋含文件 PSI 修改戳＋插件集代）缓存。
 *    失效对齐（§四前提②）：已提交文档修改 → modificationStamp 变化 → 重探；宿主被重解析
 *    换对象 → 弱键消亡 → 新宿主首问重探；插件启停（三方注入器集合变化）→ PluginSet 实例
 *    换代 → 全量重探；探针异常的 fail-safe 让位**不入缓存**（瞬时故障不得跨修改残留）。
 *
 * 先红断言（对修复前代码必红）：非 mapper 文本/属性零咨询、非白名单属性零咨询、同宿主
 * 未修改只问一次；其余为修复正确性守卫（修改后重探／异常不缓存／白名单宿主注入不受扰）。
 */
class XmlSynHiGateProbeTaxTest : BasePlatformTestCase() {

    private fun withProbeSeam(
        probe: (Project?, PsiElement) -> Boolean,
        block: () -> Unit,
    ) {
        val original = XmlSynHiGate.foreignInjectionProbe
        XmlSynHiGate.foreignInjectionProbe = probe
        try {
            block()
        } finally {
            XmlSynHiGate.foreignInjectionProbe = original
        }
    }

    /** 直调注入器用的记录式 registrar（只记 startInjecting/addPlace 是否发生）。 */
    private class RecordingRegistrar : MultiHostRegistrar {
        var started = false
            private set
        var places = 0
            private set

        override fun startInjecting(language: Language): MultiHostRegistrar {
            started = true
            return this
        }

        override fun addPlace(
            prefix: String?,
            suffix: String?,
            host: PsiLanguageInjectionHost,
            rangeInsideHost: TextRange,
        ): MultiHostRegistrar {
            places++
            return this
        }

        override fun doneInjecting() {}
    }

    // ------------------------------------------------------------------
    // 刀一：谓词前置——非 mapper / 非白名单宿主零探针咨询（先红）
    // ------------------------------------------------------------------

    /** 非 mapper XML 的文本宿主：本地守卫拒绝 → 探针零咨询（修复前 probe 先跑 → 每问必红）。 */
    fun testF79_刀一_非mapper文本宿主_探针零咨询() {
        myFixture.configureByText(
            "configuration.xml",
            "<configuration><setting>SELECT * FROM t</setting></configuration>",
        )
        val host = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java).first()
        val consulted = intArrayOf(0)
        withProbeSeam({ _, _ -> consulted[0]++; false }) {
            SqlMapperInjector().getLanguagesToInject(RecordingRegistrar(), host)
        }
        assertEquals("非 mapper 文本宿主：守卫前置 → 探针零咨询", 0, consulted[0])
    }

    /** 非 mapper XML 的属性值宿主：inMapperFile 拒绝 → 探针零咨询。 */
    fun testF79_刀一_非mapper属性宿主_探针零咨询() {
        myFixture.configureByText(
            "configuration.xml",
            "<configuration><setting test=\"a != 1\">v</setting></configuration>",
        )
        val host = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlAttributeValue::class.java).first()
        val consulted = intArrayOf(0)
        withProbeSeam({ _, _ -> consulted[0]++; false }) {
            SqlMapperInjector().getLanguagesToInject(RecordingRegistrar(), host)
        }
        assertEquals("非 mapper 属性宿主：守卫前置 → 探针零咨询", 0, consulted[0])
    }

    /** mapper 文件内非白名单属性（id）：白名单拒绝 → 探针零咨询。 */
    fun testF79_刀一_非白名单属性_探针零咨询() {
        myFixture.configureByText(
            "mapper.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT 1 FROM t</select></mapper>",
        )
        val host = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlAttributeValue::class.java)
            .first { it.textRange.endOffset < myFixture.file.textLength } // id 属性（首个属性值）
        val consulted = intArrayOf(0)
        withProbeSeam({ _, _ -> consulted[0]++; false }) {
            SqlMapperInjector().getLanguagesToInject(RecordingRegistrar(), host)
        }
        assertEquals("非白名单属性（id）：白名单前置 → 探针零咨询", 0, consulted[0])
    }

    /** 白名单属性宿主（mapper 内 test）：探针恰咨询一次，让位未命中时注入照常（重排不扰正常路径）。 */
    fun testF79_刀一_mapper白名单宿主_探针恰一次且注入不扰() {
        myFixture.configureByText(
            "mapper.xml",
            "<mapper namespace=\"n\"><select id=\"a\"><if test=\"a != 1\">SELECT 1</if></select></mapper>",
        )
        val host = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlAttributeValue::class.java)
            .first { it.text.contains("!=") }
        val consulted = intArrayOf(0)
        val registrar = RecordingRegistrar()
        withProbeSeam({ _, _ -> consulted[0]++; false }) {
            SqlMapperInjector().getLanguagesToInject(registrar, host)
        }
        assertEquals("mapper 白名单宿主：守卫全过 → 探针恰一次", 1, consulted[0])
        assertTrue("探针判不让位 → 注入照常（startInjecting）", registrar.started)
        assertEquals("注入位恰一个", 1, registrar.places)
    }

    // ------------------------------------------------------------------
    // 刀二：stamped memo（先红：同宿主只问一次）
    // ------------------------------------------------------------------

    /** 同宿主未修改：首问探针、后续 memo 命中——探针只问一次（修复前每问必探 → 红）。 */
    fun testF79_刀二_同宿主未修改_探针只问一次() {
        myFixture.configureByText(
            "mapper.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT 1 FROM t</select></mapper>",
        )
        val host = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java).first()
        val consulted = intArrayOf(0)
        withProbeSeam({ _, _ -> consulted[0]++; false }) {
            repeat(3) { XmlSynHiGate.shouldYield(project, host) }
        }
        assertEquals("未修改宿主：首问探针、后续 memo 命中（修复前 3 问 3 探 → 红）", 1, consulted[0])
    }

    /**
     * 文档提交（PSI 修改戳变化）后结论失效重探：编辑**别处**（宿主对象在未触区域经增量
     * 重解析存活）→ 同对象下一问必须重探且新结论生效——若 memo 不随修改戳失效则残留旧
     * false → 红。
     */
    fun testF79_刀二_文档提交后_结论失效重探() {
        myFixture.configureByText(
            "mapper.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT 1 FROM t</select><select id=\"b\">SELECT 2</select></mapper>",
        )
        val host = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java).first()
        val consulted = intArrayOf(0)
        withProbeSeam({ _, _ -> consulted[0]++; false }) {
            assertFalse("首问：探针 false → 不让位", XmlSynHiGate.shouldYield(project, host))
        }
        // 编辑第二个 select（宿主所在首个 select 未触）并提交——PSI 修改戳变化
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("SELECT 2", "SELECT 2 FROM u WHERE c = 9"))
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val survived = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java)
            .firstOrNull { it === host }
        assertNotNull("未触区域宿主应经增量重解析存活（同对象——修改戳失效判定的形态前提）", survived)
        withProbeSeam({ _, _ -> consulted[0]++; true }) {
            assertTrue(
                "修改后：memo 失效 → 重探 → 新结论让位（残留旧 false → 红）",
                XmlSynHiGate.shouldYield(project, survived!!),
            )
        }
        assertEquals("两阶段各探一次", 2, consulted[0])
    }

    /** 探针异常的 fail-safe 让位不入缓存：探针恢复后下一问须重探（缓存 stale true → 红）。 */
    fun testF79_刀二_探针异常_让位结论不缓存() {
        myFixture.configureByText(
            "mapper.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT 1 FROM t</select></mapper>",
        )
        val host = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java).first()
        withProbeSeam({ _, _ -> throw IllegalStateException("probe boom") }) {
            assertTrue("探针异常 → fail-safe 让位", XmlSynHiGate.shouldYield(project, host))
        }
        val consulted = intArrayOf(0)
        withProbeSeam({ _, _ -> consulted[0]++; false }) {
            assertFalse(
                "异常结论不入缓存：探针恢复后下一问须重探",
                XmlSynHiGate.shouldYield(project, host),
            )
        }
        assertEquals("恢复后的第二问须真实探针", 1, consulted[0])
    }

    // ------------------------------------------------------------------
    // V38 计数缝（T-PerfSweep25 通道 1 ③）：开关翻转后每问分计（真机量级实测的读数载体）
    // ------------------------------------------------------------------

    /** 开关开启：首问实探计入 probeRuns、次问 memo 命中计入 memoHits；关闭时零计数。 */
    fun testV38_计数缝_实探与命中分计() {
        myFixture.configureByText(
            "ProbeTaxMapper.xml",
            "<mapper namespace=\"m\"><select id=\"a\">SELECT 1 FROM t</select><select id=\"b\">SELECT 2 FROM t</select></mapper>",
        )
        // 两阶段各用独立宿主：关闭态那次问询会填充该宿主 memo，不能与开启态共用
        // （否则开启态两问全命中，实探计数恒 0——本用例首版即栽于此）。
        val hosts = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java).toList()
        val hostOff = hosts.first { it.text.contains("SELECT 1") }
        val hostOn = hosts.first { it.text.contains("SELECT 2") }
        val originalFlag = XmlSynHiGate.probeTraceEnabled
        try {
            // 关闭态：问询不计数（零成本路径的行为锚点）
            XmlSynHiGate.probeTraceEnabled = false
            XmlSynHiGate.resetProbeTraceForTest()
            withProbeSeam({ _, _ -> false }) { XmlSynHiGate.occupiedRanges(project, hostOff) }
            assertEquals("开关关闭时零计数", 0L, XmlSynHiGate.probeTraceSnapshot()[0])

            // 开启态：首问实探、次问 memo 命中
            XmlSynHiGate.probeTraceEnabled = true
            XmlSynHiGate.resetProbeTraceForTest()
            withProbeSeam({ _, _ -> false }) {
                XmlSynHiGate.occupiedRanges(project, hostOn)
                XmlSynHiGate.occupiedRanges(project, hostOn)
            }
            val snap = XmlSynHiGate.probeTraceSnapshot()
            assertEquals("两问都计入 calls", 2L, snap[0])
            assertEquals("首问为实探", 1L, snap[2])
            assertEquals("次问为 memo 命中", 1L, snap[1])
        } finally {
            XmlSynHiGate.probeTraceEnabled = originalFlag
            XmlSynHiGate.resetProbeTraceForTest()
        }
    }
}
