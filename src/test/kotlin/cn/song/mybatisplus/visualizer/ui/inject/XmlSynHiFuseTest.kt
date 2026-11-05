package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-XmlGap W8：注入守卫的**熔断阈值**与根守卫用例（计划 §六 W8 验收列了「熔断阈值测试」，
 * 而本轮盘点发现该阈值自 T-XmlSynHi D6 落地起**一直没有测试覆盖**——补上）。
 *
 * 覆盖两条熔断与一条根守卫：
 *  - 语句文本 100_000 字符上限（[XmlSynHiGate.MAX_TEXT_LENGTH]，防万行级 mapper 的区间扫描/注入卡顿）；
 *  - 属性值 4096 字符上限（[XmlSynHiGate.allowAttributeValue]）；
 *  - 根守卫：非 mapper 文件语句文本不放行（与接线用例的「零注入位」互补——此处钉**判定函数**本身）。
 *
 * 边界取**恰好等于阈值**与**阈值 + 1**两侧（`>` 语义：等于放行、超一即熔断）。
 */
class XmlSynHiFuseTest : BasePlatformTestCase() {

    /** 构造语句标签，其文本体长 `bodyLength`（标签总长 = 体长 + 开闭标签开销）。 */
    private fun selectTagWithBody(bodyLength: Int): XmlTag {
        val body = "a".repeat(bodyLength)
        myFixture.configureByText("mapper.xml", "<mapper namespace=\"n\"><select id=\"s\">$body</select></mapper>")
        return PsiTreeUtil.collectElementsOfType(myFixture.file, XmlTag::class.java).first { it.name == "select" }
    }

    fun testW8_语句文本熔断_恰好阈值放行_超一熔断() {
        val overhead = selectTagWithBody(10).textLength - 10
        val atLimit = selectTagWithBody(XmlSynHiGate.MAX_TEXT_LENGTH - overhead)
        assertEquals("边界构造：标签总长须恰为阈值", XmlSynHiGate.MAX_TEXT_LENGTH, atLimit.textLength)
        assertTrue("恰好等于阈值 → 放行（判定为 `>`）", XmlSynHiGate.allowStatementText(atLimit))

        val over = selectTagWithBody(XmlSynHiGate.MAX_TEXT_LENGTH - overhead + 1)
        assertFalse("超阈值一个字符 → 熔断", XmlSynHiGate.allowStatementText(over))
    }

    fun testW8_属性值熔断_4096边界() {
        val whitelisted = "test"
        assertTrue("白名单属性值恰 4096 → 放行", XmlSynHiGate.allowAttributeValue(whitelisted, "a".repeat(4096)))
        assertFalse("白名单属性值 4097 → 熔断", XmlSynHiGate.allowAttributeValue(whitelisted, "a".repeat(4097)))
        assertFalse("非白名单属性（id）不放行", XmlSynHiGate.allowAttributeValue("id", "a"))
        assertTrue(
            "空值在**判定函数层**放行（该函数只管长度与白名单；空区间由注入器另行跳过——见 SqlMapperInjector 的 isEmpty 分支）",
            XmlSynHiGate.allowAttributeValue(whitelisted, ""),
        )
    }

    fun testW8_根守卫_非mapper文件不放行() {
        myFixture.configureByText("other.xml", "<configuration><settings><setting name=\"a\" value=\"b\"/></settings></configuration>")
        val root = (myFixture.file as XmlFile).rootTag!!
        assertFalse("非 mapper 根 → 语句文本守卫不放行", XmlSynHiGate.allowStatementText(root))
        // 属性值侧：`allowAttributeValue` 只管长度 + 白名单，**不含文件上下文**——根守卫由注入器经
        // `inMapperFile` 承担（SqlMapperInjector 的 XmlAttributeValue 分支），故此处直接钉该判定。
        assertFalse("非 mapper 根 → inMapperFile 判否（属性值根守卫的落点）", XmlSynHiGate.inMapperFile(root))
    }

    /** 反向验证位：把上限调成 0 语义（即「任何非空文本都超限」）时，放行侧必须变红——本用例给出判据形态。 */
    fun testW8_熔断判据是内容长度而非恒真() {
        val tag = selectTagWithBody(64)
        assertTrue("短文本必须放行（若守卫被写成恒 false，此处红）", XmlSynHiGate.allowStatementText(tag))
        assertTrue("短属性值必须放行（若守卫被写成恒 false，此处红）", XmlSynHiGate.allowAttributeValue("test", "x"))
    }
}
