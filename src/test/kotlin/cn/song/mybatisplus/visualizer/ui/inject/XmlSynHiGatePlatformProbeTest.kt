package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.fakeprobe.FakePlatformInjector
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlSynHi D68/T-DevAcceptFix ④：宿主级「其他注入器会不会给这个宿主上色」探测——门与探测主体分级用例。
 *
 * 背景定案（字节码实证，出处见 [XmlSynHiGate] KDoc）：两条历史让位线共用「在位=覆盖」这一被真机
 * 证伪的前提——`SqlLanguageInjector` 在 243/用户真机 262 均不存在（现行平台 SQL 注入器喂入类型
 * 全为 SQL PSI，mapper XML 宿主从不着色）；用户真机 MCH-Pro 启用中而 mapper 语句文本仍无色。
 * 让位门槛因此下沉为「**有注入器会**对该宿主注入」的 per-host 探测，探测域=**除本插件自身族外
 * 全部 EP 在册注入器**（平台与三方一视同仁：真上色才让位）。
 *
 * 分级：
 *  - 门级（seam）：[XmlSynHiGate.foreignInjectionProbe] 缝三形态——会注入→让位（该位归对方，不双写）、
 *    不会注入→不让位（关键修复：对方未覆盖的位由本插件补色）、整体抛异常→让位（fail-safe 回保守）；
 *    另钉开关关短路（探针零咨询）。
 *  - 主体级：[XmlSynHiGate.foreignInjectorsWouldInject] 直注假注入器清单（自身族排除 + 喂入契约预筛 +
 *    startInjecting 捕获 + 三方族同权咨询 + 单注入器试探异常跳过续问）。
 *  - 默认探针冒烟：真实测试平台 EP 上不炸、可判定。
 */
class XmlSynHiGatePlatformProbeTest : BasePlatformTestCase() {

    private fun firstMapperText(): XmlText {
        myFixture.configureByText(
            "mapper.xml",
            "<mapper namespace=\"n\"><select id=\"a\">SELECT 1 FROM t</select></mapper>",
        )
        val file = myFixture.file
        return PsiTreeUtil.collectElementsOfType(file, XmlText::class.java).first()
    }

    private fun withProbeSeam(
        probe: (com.intellij.openapi.project.Project?, PsiElement) -> Boolean,
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

    // ------------------------------------------------------------------
    // 门级（seam 三形态 + 短路序）
    // ------------------------------------------------------------------

    /** 会让位语义：另一注入器判定会注入 → 让位（该宿主归对方，不双写）。 */
    @Test
    fun testF68_探针判定会注入_让位() {
        val host = firstMapperText()
        withProbeSeam({ _, _ -> true }) {
            assertTrue("其他注入器会给该宿主上色 → 让位（不双写）", XmlSynHiGate.shouldYield(project, host))
        }
    }

    /** 关键修复用例：对方不会注入 → 不让位（未覆盖位由本插件补色）。 */
    @Test
    fun testF68_探针判定不会注入_不让位() {
        val host = firstMapperText()
        withProbeSeam({ _, _ -> false }) {
            assertFalse(
                "无人会给该宿主上色 → 不让位（本插件补色；旧「在位即让位」形态即杀这里）",
                XmlSynHiGate.shouldYield(project, host),
            )
        }
    }

    /** fail-safe：整体探针抛异常 → 让位（探测不了时回到保守让位）。 */
    @Test
    fun testF68_探针抛异常_保守让位() {
        val host = firstMapperText()
        withProbeSeam({ _, _ -> throw IllegalStateException("probe boom") }) {
            assertTrue("探针异常 → 让位（fail-safe）", XmlSynHiGate.shouldYield(project, host))
        }
    }

    /** 探针收到的是分发后的同一个宿主实例（per-host 语义的形态证据）。 */
    @Test
    fun testF68_探针收到分发后同款宿主() {
        val host = firstMapperText()
        var received: PsiElement? = null
        withProbeSeam({ _, h -> received = h; false }) {
            XmlSynHiGate.shouldYield(project, host)
        }
        assertSame("per-host 探针须收到喂入的宿主实例", host, received)
    }

    /** 短路序：设置开关关 → 探针零咨询（喂入税面上探针只在共通线全过才走）。 */
    @Test
    fun testF68_开关关_探针零咨询() {
        val host = firstMapperText()
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val old = settings.state.xmlSynHiEnabled
        var consulted = 0
        try {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = false))
            withProbeSeam({ _, _ -> consulted++; true }) {
                assertTrue(XmlSynHiGate.shouldYield(project, host))
            }
            assertEquals("开关关短路 → 探针零咨询", 0, consulted)
        } finally {
            settings.loadState(settings.state.copy(xmlSynHiEnabled = old))
        }
    }

    // ------------------------------------------------------------------
    // 主体级（foreignInjectorsWouldInject 直注清单）
    // ------------------------------------------------------------------

    /** 喂入命中 + startInjecting 被调 → 让位（平台族）。 */
    @Test
    fun testF68_注入器_喂入命中且start_让位() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(listOf(XmlText::class.java), start = true)
        assertTrue(
            "注入器对该宿主 startInjecting → 让位",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host),
        )
        assertEquals("喂入命中须真实咨询", 1, fake.consulted)
    }

    /** 喂入命中但未 startInjecting（未覆盖该位）→ 不让位。 */
    @Test
    fun testF68_注入器_喂入命中但未start_不让位() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(listOf(XmlText::class.java), start = false)
        assertFalse(
            "咨询后未 startInjecting → 未覆盖该宿主 → 不让位",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host),
        )
        assertEquals(1, fake.consulted)
    }

    /** 喂入契约预筛：elementsToInjectIn 不含宿主类型 → 不咨询、不让位。 */
    @Test
    fun testF68_注入器_喂入不命中_不咨询不让位() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(listOf(com.intellij.psi.PsiFile::class.java))
        assertFalse(XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host))
        assertEquals("喂入契约预筛不命中 → 零咨询（243 probeElementsUpInner 同口径）", 0, fake.consulted)
    }

    /** 自身族排除：本插件包前缀的注入器即使会 start 也不让位（自身让位由其余各线负责）。 */
    @Test
    fun testF68_自身族注入器_排除不咨询() {
        val host = firstMapperText()
        val fake = FakeSelfInjector(listOf(XmlText::class.java))
        assertFalse(XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host))
        assertEquals("自身族 EP 在册项被包前缀排除", 0, fake.consulted)
    }

    // ---- T-DevAcceptFix ④：三方族并入 per-host 探测（用户在位≠覆盖，真上色才让位） ----

    /** 第三方注入器（占满宿主）：喂入命中且 start → 让位（该位确实归它）。 */
    @Test
    fun testDevAcceptFix04_三方注入器_喂入命中且start_让位() {
        val host = firstMapperText()
        val fake = com.example.thirdparty.fakeprobe.FakeThirdPartyInjector(listOf(XmlText::class.java))
        assertTrue(
            "三方注入器确实给该宿主上色 → 让位（在位≠覆盖，真 start 才让位）",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host),
        )
        assertEquals("三方族须真实咨询（不再由安装态批发让位）", 1, fake.consulted)
    }

    /** 三方族未 start（在位但不覆盖该宿主，即用户真机无色现场）→ 不让位，本插件补色。 */
    @Test
    fun testDevAcceptFix04_三方注入器_未start_不让位() {
        val host = firstMapperText()
        val fake = com.example.thirdparty.fakeprobe.FakeThirdPartyInjector(listOf(XmlText::class.java), start = false)
        assertFalse(
            "三方在位但未覆盖该宿主 → 不让位（用户真机无色现场的关键修复）",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host),
        )
        assertEquals(1, fake.consulted)
    }

    /** 未知三方包注入器同权：喂入命中且 start → 让位（探测域=除自身族外全部在册项）。 */
    @Test
    fun testDevAcceptFix04_未知三方注入器_start_让位() {
        val host = firstMapperText()
        val fake = org.example.fakeprobe.FakeUnknownInjector(listOf(XmlText::class.java))
        assertTrue(
            "任意在册注入器覆盖该宿主即让位（探测域不再按包名白名单收窄）",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host),
        )
        assertEquals(1, fake.consulted)
    }

    /**
     * 单注入器试探异常 → **跳过该注入器继续问下一个**（T-DevAcceptFix ④ 语义修订）：
     * 覆盖不可判的坏注入器不得把本插件一起拖成零着色；紧随其后的会注入者仍应命中。
     */
    @Test
    fun testDevAcceptFix04_单注入器抛异常_跳过续问下一个() {
        val host = firstMapperText()
        val throwing = FakePlatformInjector(listOf(XmlText::class.java), throwOnInject = true)
        val starting = FakePlatformInjector(listOf(XmlText::class.java), start = true)
        assertTrue(
            "前一个注入器炸掉应跳过而非整体让位——后一个真上色者仍命中",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(throwing, starting), host),
        )
        assertEquals("异常注入器之后的注入器须被咨询到", 1, starting.consulted)
    }

    /** 单个坏注入器（无人上色）→ 跳过后续问尽仍无命中 → 不让位（本插件补色）。 */
    @Test
    fun testDevAcceptFix04_仅坏注入器_不让位() {
        val host = firstMapperText()
        val throwing = FakePlatformInjector(listOf(XmlText::class.java), throwOnInject = true)
        assertFalse(
            "唯一注入器覆盖不可判 → 不让位（把位留给本插件，避免「坏注入器⇒零着色」）",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(throwing), host),
        )
    }

    /** 喂入类型表读取异常同口径：跳过该注入器（不因它整体让位）。 */
    @Test
    fun testDevAcceptFix04_喂入类型表抛异常_跳过() {
        val host = firstMapperText()
        val fake = FakePlatformInjector(emptyList(), throwOnFeed = true)
        assertFalse(
            "elementsToInjectIn 异常 → 跳过该注入器（不让位）",
            XmlSynHiGate.foreignInjectorsWouldInject(listOf(fake), host),
        )
    }

    // ------------------------------------------------------------------
    // 默认探针冒烟（真实测试平台 EP）
    // ------------------------------------------------------------------

    /**
     * 真实 EP 冒烟：裸测试平台（无 XML 宿主命中项）上默认探针可判定且不炸，判 false
     * （注入链在此环境必须活）。T-XmlGap W1 起生产入口是 [XmlSynHiGate.occupiedRanges]
     * （区间形态），此处按其判定面断言——布尔缝未覆写时二者语义一致。
     */
    @Test
    fun testF68_默认探针_真实EP环境_不炸且裸环境判不让位() {
        val host = firstMapperText()
        val result = XmlSynHiGate.shouldYield(project, host)
        assertFalse("裸测试平台 EP 无 XML 宿主命中 → 不让位（注入链活）", result)
        assertTrue("区间形态同口径：无占用区间", XmlSynHiGate.occupiedRanges(project, host).isEmpty())
    }

    /** 空清单形态：无在册注入器 → 不让位（Community 无 DatabaseTools 的常态）。 */
    @Test
    fun testF68_空注入器清单_不让位() {
        val host = firstMapperText()
        assertFalse(XmlSynHiGate.foreignInjectorsWouldInject(emptyList(), host))
    }
}

/** 自身族假注入器（包名 = 本插件族前缀 → 排除组；与被测门同包，模拟本插件 EP 在册项）。 */
internal class FakeSelfInjector(
    private val fedTypes: List<Class<out PsiElement>>,
) : MultiHostInjector {

    var consulted: Int = 0
        private set

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        consulted++
        val host = context as? PsiLanguageInjectionHost ?: return
        registrar.startInjecting(com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE)
            .addPlace(null, null, host, TextRange(0, host.textLength))
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = fedTypes
}
