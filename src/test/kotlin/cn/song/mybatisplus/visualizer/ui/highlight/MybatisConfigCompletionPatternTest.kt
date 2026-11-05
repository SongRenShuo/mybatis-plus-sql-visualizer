package cn.song.mybatisplus.visualizer.ui.highlight

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlToken
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-PerfSweep26 F101：配置文件补全贡献器的位置模式约束直接用例。
 *
 * 原模式为无约束 `PlatformPatterns.psiElement()`（全 IDE 任意语言补全都进 provider 体，
 * 靠首行 XmlFile cast 早退兜底）；修复后加 `withLanguage(XMLLanguage.INSTANCE)` 约束。
 * 本类直接钉模式的两向判定：
 *  - mybatis-config.xml 内补全位（XmlToken，标准 XML 语言）仍被接受——约束不误伤，行为不变；
 *  - 非 XML 语言元素（纯文本文件 token）被拒绝——约束真正收窄（修复前此形态被接受 → 红）。
 *
 * 端到端派发链（EP 注册 + completeBasic 全链路）由同包 XmlSqlCompletionDispatchTest 形态承载，
 * 本类按计划口径只做模式级最小直接验证。
 */
class MybatisConfigCompletionPatternTest : BasePlatformTestCase() {

    fun testF101_配置文件内XML补全位_模式仍接受() {
        myFixture.configureByText(
            "mybatis-config.xml",
            """<configuration>
  <settings>
    <setting name="cacheEnabled" value="true"/>
  </settings>
</configuration>""",
        )
        val tokens = PsiTreeUtil.collectElementsOfType(myFixture.file, XmlToken::class.java)
        assertTrue("夹具自检：配置文件须含 XML token", tokens.isNotEmpty())
        for (token in tokens) {
            assertTrue(
                "配置文件内补全位（${token.text}）须被接受——约束后行为不得变",
                MybatisConfigCompletionContributor.completionPositionPattern.accepts(token),
            )
        }
    }

    fun testF101_非XML语言元素_模式拒绝() {
        val text = PsiFileFactory.getInstance(project)
            .createFileFromText("plain.txt", PlainTextFileType.INSTANCE, "plain text token")
        val token = text.findElementAt(0) ?: error("纯文本夹具须产出 token")
        assertFalse(
            "非 XML 语言补全位须被模式拒绝（约束前接受 → 全 IDE 每次补全都进 provider 体）",
            MybatisConfigCompletionContributor.completionPositionPattern.accepts(token),
        )
    }
}
