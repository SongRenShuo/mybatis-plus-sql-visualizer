package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.WrapperArg
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 L：toWrapperCall 运算符优先级回归。
 *
 * `methodName == SELECT_FUNC || methodName == APPLY_FUNC && index == 1` 因 && 优先于
 * || 实为「selectFunc 任意位 ∪ apply 第 2 位」——selectFunc 第三参（别名位）被
 * resolveFuncLambda 恒吃成 FuncArg，解析端（QueryWrapperParser.parseSelectFunc）的
 * 别名位只认 AliasArg/ValueArg → PSI 链路上三参 selectFunc 的别名恒丢失。
 * 修复：第 2 位（聚合函数）走 resolveFuncLambda；第 3 位按姊妹分支 SELECT_SUB && index==2
 * 同款 asAlias（方法引用 → AliasArg）。
 */
class ArgResolverSelectFuncArgPositionsTest : BasePlatformTestCase() {

    fun testThreeArgSelectFuncSecondIsFuncArgThirdIsAliasArg() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    mapper.selectJoinList(UserEntity.class, JoinWrappers.lambda(UserEntity.class)
                            .selectFunc(DefaultFuncEnum.SUM, UserEntity::getAge, UserVo::getAgeCount)
                            .eq(UserEntity::getId, 1L));
                }

                static class UserEntity {
                    private Long id;
                    private Integer age;
                }
            }
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull("应定位到 JoinWrappers.lambda 工厂链", located)
        val call = located!!.chain.calls.first { it.methodName == "selectFunc" }
        assertTrue("第二参应为 FuncArg（聚合列位）", call.args.getOrNull(1) is WrapperArg.FuncArg)
        val alias = call.args.getOrNull(2)
        assertTrue("第三参应为 AliasArg（别名位）", alias is WrapperArg.AliasArg)
        assertEquals("ageCount", (alias as WrapperArg.AliasArg).propertyName)
    }

    fun testApplySecondArgStaysFuncLambdaPosition() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    mapper.selectJoinList(UserEntity.class, JoinWrappers.lambda(UserEntity.class)
                            .selectFunc("CONCAT(%s)", f -> f.accept(UserEntity::getName))
                            .eq(UserEntity::getId, 1L));
                }

                static class UserEntity {
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        )
        val located = WrapperLocator.locateWithTable(anchor())
        assertNotNull(located)
        val call = located!!.chain.calls.first { it.methodName == "selectFunc" }
        assertTrue(
            "模板串模板形态第二参应为 FuncArg（func lambda）",
            call.args.getOrNull(1) is WrapperArg.FuncArg,
        )
    }

    private fun anchor(): PsiElement {
        val file = myFixture.file
        return PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
            ?: PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: error("测试文件缺少可用的锚点元素")
    }
}
