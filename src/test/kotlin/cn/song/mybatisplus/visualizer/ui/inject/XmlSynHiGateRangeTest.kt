package cn.song.mybatisplus.visualizer.ui.inject

import com.example.thirdparty.fakeprobe.FakeParamRangeInjector
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W1 门级用例：让位判定从**宿主级**下沉到**区间级**。
 *
 * 判别点（旧形态在本类必须红）：
 *  - 外来注入器只占 `#{}`/`${}` 区间 → [XmlSynHiGate.occupiedRanges] 返回该区间、
 *    [XmlSynHiGate.shouldYield] 仍判「不让位」（余下区间归我方），且互补区间不含占用段；
 *  - 外来注入器占满整宿主 / 探针失败 / 设置关闭 → 全覆盖哨兵（等价旧的保守让位）。
 */
class XmlSynHiGateRangeTest : BasePlatformTestCase() {

    private fun statementText(sql: String): XmlText {
        myFixture.configureByText("mapper.xml", "<mapper namespace=\"n\"><select id=\"a\">$sql</select></mapper>")
        return PsiTreeUtil.collectElementsOfType(myFixture.file, XmlText::class.java)
            .first { it.text.contains("SELECT") }
    }

    private fun withRangeSeam(
        probe: (com.intellij.openapi.project.Project?, com.intellij.psi.PsiElement) -> List<TextRange>,
        block: () -> Unit,
    ) {
        val originalRanges = XmlSynHiGate.foreignOccupiedRangesProbe
        val originalBool = XmlSynHiGate.foreignInjectionProbe
        // 布尔缝若被他处覆写会短路区间缝（occupiedRanges 优先采纳布尔缝）——本类只测区间语义，先清空
        XmlSynHiGate.foreignInjectionProbe = null
        XmlSynHiGate.foreignOccupiedRangesProbe = probe
        try {
            block()
        } finally {
            XmlSynHiGate.foreignOccupiedRangesProbe = originalRanges
            XmlSynHiGate.foreignInjectionProbe = originalBool
        }
    }

    // ------------------------------------------------------------------
    // 区间主体（直注假注入器清单）
    // ------------------------------------------------------------------

    /** 只占 `#{}` 的注入器 → 区间被记录、布尔形态仍判「覆盖」（布尔面语义不变）。 */
    @Test
    fun testW1_区间主体_只占参数区间_记录区间且布尔判覆盖() {
        val host = statementText("SELECT * FROM t WHERE a = #{id}")
        val fake = FakeParamRangeInjector(listOf(XmlText::class.java))
        val ranges = XmlSynHiGate.foreignInjectorsOccupiedRanges(listOf(fake), host)
        assertEquals("只占一个 #{} → 一个区间", 1, ranges.size)
        val expected = host.text.indexOf("#{")
        assertEquals(TextRange(expected, expected + 5), ranges.single())
        assertTrue(
            "布尔形态仍按「有覆盖」判（D68 语义不回归）",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host),
        )
        assertEquals("两种形态各咨询一次（同一假注入器的自查计数）", 2, fake.consulted)
    }

    /** 未命中喂入契约 → 不咨询、无区间。 */
    @Test
    fun testW1_区间主体_喂入不命中_零咨询零区间() {
        val host = statementText("SELECT * FROM t WHERE a = #{id}")
        val fake = FakeParamRangeInjector(listOf(com.intellij.psi.PsiFile::class.java))
        assertTrue(XmlSynHiGate.foreignInjectorsOccupiedRanges(listOf(fake), host).isEmpty())
        assertEquals(0, fake.consulted)
    }

    /** 自身族排除：本插件包前缀的注入器不咨询。 */
    @Test
    fun testW1_区间主体_自身族排除() {
        val host = statementText("SELECT * FROM t WHERE a = #{id}")
        val fake = FakeSelfInjector(listOf(XmlText::class.java))
        assertTrue(XmlSynHiGate.foreignInjectorsOccupiedRanges(listOf(fake), host).isEmpty())
        assertEquals(0, fake.consulted)
    }

    // ------------------------------------------------------------------
    // 门级（缝三形态）：区间形态下的让位语义
    // ------------------------------------------------------------------

    /** 部分占用 → **不让位**（这是 W1 的核心修复；宿主级形态在此必红）。 */
    @Test
    fun testW1_部分占用_不让位() {
        val host = statementText("SELECT * FROM t WHERE a = #{id} AND b = 2")
        val open = host.text.indexOf("#{")
        withRangeSeam({ _, _ -> listOf(TextRange(open, open + 5)) }) {
            assertFalse(
                "只占一个 #{} → 余下区间归我方 → 不让位（旧宿主级形态在此判让位＝整条语句无色）",
                XmlSynHiGate.shouldYield(project, host),
            )
            assertEquals("占用区间须原样暴露给互补计算", 1, XmlSynHiGate.occupiedRanges(project, host).size)
        }
    }

    /** 全覆盖哨兵 → 让位（保守线保留）。 */
    @Test
    fun testW1_全覆盖_让位() {
        val host = statementText("SELECT 1")
        withRangeSeam({ _, h -> InjectRangePlan.yieldAll(h.textLength) }) {
            assertTrue("整宿主被占 → 让位（零注入）", XmlSynHiGate.shouldYield(project, host))
        }
    }

    /** 占用为空 → 不让位。 */
    @Test
    fun testW1_无占用_不让位() {
        val host = statementText("SELECT 1")
        withRangeSeam({ _, _ -> emptyList() }) {
            assertFalse(XmlSynHiGate.shouldYield(project, host))
            assertTrue(XmlSynHiGate.occupiedRanges(project, host).isEmpty())
        }
    }

    /** 区间缝抛异常 → 全覆盖哨兵（fail-safe 保守让位，D68 口径不回归）。 */
    @Test
    fun testW1_区间缝抛异常_保守让位() {
        val host = statementText("SELECT 1")
        withRangeSeam({ _, _ -> throw IllegalStateException("range probe boom") }) {
            assertTrue("探测不了 → 回到保守让位", XmlSynHiGate.shouldYield(project, host))
        }
    }

    /** 设置开关关闭 → 零咨询 + 让位（短路序不变）。 */
    @Test
    fun testW1_开关关_零咨询且让位() {
        val host = statementText("SELECT 1")
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val old = settings.state.xmlSynHiEnabled
        var consulted = 0
        try {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = false))
            withRangeSeam({ _, _ -> consulted++; emptyList() }) {
                assertTrue(XmlSynHiGate.shouldYield(project, host))
                assertTrue(XmlSynHiGate.occupiedRanges(project, host).isNotEmpty())
            }
            assertEquals("开关关 → 区间缝零咨询", 0, consulted)
        } finally {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = old))
        }
    }

    /** 布尔缝仍被尊重（既有 D68 用例的兼容面）：true → 全覆盖。 */
    @Test
    fun testW1_布尔缝覆写_true_全覆盖() {
        val host = statementText("SELECT 1")
        val original = XmlSynHiGate.foreignInjectionProbe
        XmlSynHiGate.foreignInjectionProbe = { _, _ -> true }
        try {
            assertTrue(XmlSynHiGate.shouldYield(project, host))
            assertEquals("布尔全覆盖 → 一个占满宿主的区间", 1, XmlSynHiGate.occupiedRanges(project, host).size)
        } finally {
            XmlSynHiGate.foreignInjectionProbe = original
        }
    }

    /** 布尔缝 false → 空区间（既有接线用例靠这条固定环境，语义不变）。 */
    @Test
    fun testW1_布尔缝覆写_false_空区间() {
        val host = statementText("SELECT 1")
        val original = XmlSynHiGate.foreignInjectionProbe
        XmlSynHiGate.foreignInjectionProbe = { _, _ -> false }
        try {
            assertFalse(XmlSynHiGate.shouldYield(project, host))
            assertTrue(XmlSynHiGate.occupiedRanges(project, host).isEmpty())
        } finally {
            XmlSynHiGate.foreignInjectionProbe = original
        }
    }
}
