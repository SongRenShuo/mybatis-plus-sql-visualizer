package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.SqlValue
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 Y8：Char 字面量求值补单引号转义回归。
 *
 * String 分支做了单引号加倍转义，Char 分支曾直接内插——.eq(Entity::getX, 反斜杠引号)
 * 求值 raw 为三个连续单引号（断串形态）。
 */
class ExpressionEvaluatorCharEscapeTest : BasePlatformTestCase() {

    fun testChar含单引号_raw转义完整() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                char quote = '\'';
            }
            """.trimIndent(),
        )
        val literal = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiLiteralExpression::class.java)
            .firstOrNull { it.value is Char } ?: error("fixture 缺 char 字面量")
        val value = ExpressionEvaluator.evaluate(literal)
            assertEquals("'", (value as SqlValue.Literal).unquoted)
            assertEquals("''''", value.raw)
    }
}
