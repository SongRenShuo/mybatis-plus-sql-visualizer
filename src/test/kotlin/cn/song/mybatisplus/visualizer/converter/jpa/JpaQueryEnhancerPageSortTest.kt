package cn.song.mybatisplus.visualizer.converter.jpa

import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 X：evaluatePageSort 双字面量判定回归。
 *
 * KDoc 声明「双字面量（page/size 均可求值）才内联；否则占位 + 参数行」，实现却只判
 * size——`PageRequest.of(pageNum, 20)`（page 运行时变量、size 字面量，高频形态）：
 * offset=null（渲染语义=无 OFFSET）、参数行缺 pageOffset、note 谎报 offset=0，
 * 预览变首页语义而真实运行时 offset = pageNum×20。修复后 page 非字面量时
 * OFFSET 走 pageOffset 占位并登记参数行。
 */
class JpaQueryEnhancerPageSortTest : BasePlatformTestCase() {

    /** fixture：Repo.find(PageRequest) + caller 传实参 → evaluatePageSort 产物。 */
    private fun eval(argText: String): JpaQueryEnhancer.PageSortEval? {
        myFixture.configureByText(
            "Repo.java",
            """
            public class Repo {
                public Object find(org.springframework.data.domain.Pageable p) { return null; }
                public void caller() {
                    find($argText);
                }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .firstOrNull { it.name == "find" } ?: error("fixture 缺 find 方法")
        val call = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethodCallExpression::class.java)
            ?: error("fixture 缺调用")
        return JpaQueryEnhancer.evaluatePageSort(method, call.argumentList.expressions.toList())
    }

    @Test
    fun test双字面量PageRequest仍内联OFFSET与LIMIT() {
        val eval = eval("PageRequest.of(2, 20)") ?: error("应产出 PageSortEval")
        val limit = eval.limit ?: error("双字面量应内联 LIMIT")
        assertEquals("40", (limit.offset as? cn.song.mybatisplus.visualizer.model.SqlValue.Literal)?.unquoted)  // offset = page*size 静态求值
        assertEquals("20", (limit.count as? cn.song.mybatisplus.visualizer.model.SqlValue.Literal)?.unquoted)
        assertEquals("双字面量不产生额外参数行", emptyList<String>(), eval.extraParamNames)
    }

    @Test
    fun test运行时page字面量size_OFFSET占位不得丢() {
        val eval = eval("PageRequest.of(pageNum, 20)") ?: error("应产出 PageSortEval")
        val limit = eval.limit ?: error("size 字面量应内联 LIMIT")
        assertNotNull("offset 不得为 null（曾渲染为无 OFFSET 首页语义）", limit.offset)
        val placeholder = limit.offset as? cn.song.mybatisplus.visualizer.model.SqlValue.Placeholder
        assertEquals("pageOffset", placeholder?.source)
        assertEquals("20", (limit.count as? cn.song.mybatisplus.visualizer.model.SqlValue.Literal)?.unquoted)
        assertTrue("参数行应登记 pageOffset：${eval.extraParamNames}", eval.extraParamNames.contains("pageOffset"))
        assertTrue("note 不得谎报 offset=0：${eval.notes}", eval.notes.none { it.contains("offset=0") })
    }
}
