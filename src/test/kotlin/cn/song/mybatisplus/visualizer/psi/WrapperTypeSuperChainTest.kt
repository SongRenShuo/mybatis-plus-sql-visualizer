package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-ServiceJoinWrapperPreview（D256 M1）：[WrapperLocator.detectWrapperTypeDeep] 父链识别定向测试。
 *
 * 夹具全部内联（Java 源码 + 假 FQN 的 MPJ/MP 基类文件），不依赖真实 MPJ / MP jar：
 *  - MPJ 家族用 `com.github.yulichang.wrapper.MPJLambdaWrapper` 假类，验证 FQN 前缀规则；
 *  - MP 家族用 `com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper` 假类，验证 MP 细分规则；
 *  - 接口同名组合用「父类 resolve 失败 + 内联 QueryJoin/Chain 接口」，验证复制改名内部库兜底。
 *
 * 调用口径统一为变量声明类型（`PsiLocalVariable.type`），与预览链路 `buildChain` 首顺位一致。
 *
 * k2j 移植注：`detectWrapperTypeDeep` / `isWrapperVariable` / `locateWithTable` 均为 k2j 的
 * `public static`（master 侧 internal 在 Java 端口按 public 承载），Kotlin 调用形态不变；
 * `error("...")` 保持（夹具写错应硬失败，不被当作「未识别」）。
 */
class WrapperTypeSuperChainTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 假 MPJ 基类（com.github.yulichang.* 前缀，父链规则 1 的唯一证据）。 */
    private fun addFakeMpjBase() {
        val path = "com/github/yulichang/wrapper/MPJLambdaWrapper.java"
        if (myFixture.findFileInTempDir(path) != null) return
        myFixture.addFileToProject(
            path,
            """
            package com.github.yulichang.wrapper;

            public abstract class MPJLambdaWrapper<T> {
            }
            """.trimIndent(),
        )
    }

    /** 假 MP lambda 基类（com.baomidou.mybatisplus.* 前缀，父链规则 3 的家族证据）。 */
    private fun addFakeMpLambdaWrapper() {
        val path = "com/baomidou/mybatisplus/core/conditions/query/LambdaQueryWrapper.java"
        if (myFixture.findFileInTempDir(path) != null) return
        myFixture.addFileToProject(
            path,
            """
            package com.baomidou.mybatisplus.core.conditions.query;

            public abstract class LambdaQueryWrapper<T> {
            }
            """.trimIndent(),
        )
    }

    /**
     * 配置 Java 夹具并按变量名取声明类型，调用 [WrapperLocator.detectWrapperTypeDeep]。
     * 变量名不存在时直接失败（夹具写错不应被当成「未识别」）。
     */
    private fun deepTypeOf(code: String, variableName: String): WrapperType? {
        myFixture.configureByText("Demo.java", code.trimIndent())
        val variable = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLocalVariable::class.java)
            .firstOrNull { it.name == variableName }
            ?: error("夹具中应存在局部变量 $variableName")
        return WrapperLocator.detectWrapperTypeDeep(variable.type)
    }

    // ------------------------------------------------------------------
    // 1. 用户真实四层自研链 + MPJ 基类 → MPJ_LAMBDA
    // ------------------------------------------------------------------

    fun testUserFourLayerCustomChainResolvesToMpjLambda() {
        addFakeMpjBase()
        val type = deepTypeOf(
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                static abstract class BaseWrapper<T> extends MPJLambdaWrapper<T> {
                }

                static abstract class AbstractJoinBaseWrapper<T, C> extends BaseWrapper<T> {
                }

                static abstract class AbstractLambdaJoinBaseWrapper<T, C> extends AbstractJoinBaseWrapper<T, C> {
                }

                static class CustomJoinWrapper<T> extends AbstractLambdaJoinBaseWrapper<T, CustomJoinWrapper<T>> {
                }

                public void run() {
                    CustomJoinWrapper<String> jw = new CustomJoinWrapper<>();
                }
            }
            """,
            "jw",
        )
        assertEquals(WrapperType.MPJ_LAMBDA, type)
    }

    // ------------------------------------------------------------------
    // 2. 自研子类 extends MP LambdaQueryWrapper → LAMBDA_QUERY
    // ------------------------------------------------------------------

    fun testCustomSubclassOfMpLambdaQueryWrapperResolvesToLambdaQuery() {
        addFakeMpLambdaWrapper()
        val type = deepTypeOf(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                static class XxxLambdaWrapper<T> extends LambdaQueryWrapper<T> {
                }

                public void run() {
                    XxxLambdaWrapper<String> w = new XxxLambdaWrapper<>();
                }
            }
            """,
            "w",
        )
        assertEquals(WrapperType.LAMBDA_QUERY, type)
    }

    // ------------------------------------------------------------------
    // 3. 父类 resolve 失败，但接口同名组合 QueryJoin + Chain → MPJ_LAMBDA
    // ------------------------------------------------------------------

    fun testInterfaceNameCombinationSurvivesUnresolvedParent() {
        val type = deepTypeOf(
            """
            public class Demo {
                interface QueryJoin<C, T> {
                }

                interface Chain<T> {
                }

                static class BrokenParentWrapper
                        extends UnknownMissingLibraryParent
                        implements QueryJoin<BrokenParentWrapper, String>, Chain<BrokenParentWrapper> {
                }

                public void run() {
                    BrokenParentWrapper w = new BrokenParentWrapper();
                }
            }
            """,
            "w",
        )
        assertEquals(WrapperType.MPJ_LAMBDA, type)
    }

    // ------------------------------------------------------------------
    // 4. 只有单个 Chain 接口（泛名）→ null，防误判
    // ------------------------------------------------------------------

    fun testSingleChainInterfaceIsNotEnough() {
        val type = deepTypeOf(
            """
            public class Demo {
                interface Chain<T> {
                }

                static class OnlyChainWrapper<T> implements Chain<OnlyChainWrapper<T>> {
                }

                public void run() {
                    OnlyChainWrapper<String> w = new OnlyChainWrapper<>();
                }
            }
            """,
            "w",
        )
        assertNull("单个 Chain 属泛名，不应判为联表 wrapper", type)
    }

    // ------------------------------------------------------------------
    // 5. 普通类（无 MP / MPJ 父链）→ null
    // ------------------------------------------------------------------

    fun testPlainWrapperNamedClassWithObjectParentIsNull() {
        val type = deepTypeOf(
            """
            public class Demo {
                static class PlainWrapper {
                }

                public void run() {
                    PlainWrapper w = new PlainWrapper<>();
                }
            }
            """,
            "w",
        )
        assertNull(type)
    }

    // ------------------------------------------------------------------
    // 6. 名字不像 Wrapper → 名字前门直接返回，不 resolve
    // ------------------------------------------------------------------

    fun testNonWrapperLikeNameReturnsNullAtFrontGate() {
        // `Foo` 不以 Wrapper/Join/Chain 结尾 ⇒ looksLikeWrapperName 为假，未进入父链 resolve。
        // 这里断言返回值即可（前门是纯文本判定，测试无法计数 resolve，故以「名字不像就不认」为契约）。
        val type = deepTypeOf(
            """
            public class Demo {
                static class Foo {
                }

                public void run() {
                    Foo f = new Foo();
                }
            }
            """,
            "f",
        )
        assertNull(type)
    }

    // ------------------------------------------------------------------
    // 7. 深度超限：MPJ 基类落在第 9 跳 → null
    // ------------------------------------------------------------------

    fun testDepthLimitEightHopsStopsBeforeMpjBase() {
        addFakeMpjBase()
        // L1(0) → L2(1) → … → L9(8) → MPJLambdaWrapper(9)：上限 8 时第 9 跳不再展开。
        // 若深度上限失效，本用例会返回 MPJ_LAMBDA 而转红——即本用例钉住「深度上限 8」。
        val type = deepTypeOf(
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                static class L9Wrapper<T> extends MPJLambdaWrapper<T> {
                }

                static class L8Wrapper<T> extends L9Wrapper<T> {
                }

                static class L7Wrapper<T> extends L8Wrapper<T> {
                }

                static class L6Wrapper<T> extends L7Wrapper<T> {
                }

                static class L5Wrapper<T> extends L6Wrapper<T> {
                }

                static class L4Wrapper<T> extends L5Wrapper<T> {
                }

                static class L3Wrapper<T> extends L4Wrapper<T> {
                }

                static class L2Wrapper<T> extends L3Wrapper<T> {
                }

                static class L1Wrapper<T> extends L2Wrapper<T> {
                }

                public void run() {
                    L1Wrapper<String> w = new L1Wrapper<>();
                }
            }
            """,
            "w",
        )
        assertNull(type)
    }

    // ------------------------------------------------------------------
    // 8. 规则 4（链方法启发）：父链完全接不到 MP/MPJ 时的兜底
    // ------------------------------------------------------------------

    /**
     * 父链断（`extends Object`）+ 初始化链含联表方法 → [WrapperLocator.isWrapperVariable] 认作
     * MPJ wrapper；`detectWrapperTypeDeep` 本身仍返回 null（规则 4 是变量级判定，不在类型级）。
     */
    fun testChainMethodHeuristicJoinMethodsResolveToMpjLambda() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                void run() {
                    CustomJoinWrapper<Object> w = new CustomJoinWrapper<>();
                    w.leftJoin(Object.class, "a", "b").eq("x", 1);
                }
            }

            class CustomJoinWrapper<T> {
                CustomJoinWrapper<T> leftJoin(Class<?> c, String a, String b) { return this; }
                CustomJoinWrapper<T> eq(String column, Object value) { return this; }
            }
            """.trimIndent(),
        )
        val variable = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLocalVariable::class.java)
            .first { it.name == "w" }
        assertNull("类型级父链识别不应命中（无 MP/MPJ 祖先）", WrapperLocator.detectWrapperTypeDeep(variable.type))
        assertTrue("规则 4：链上有 leftJoin → 认作 MPJ wrapper", WrapperLocator.isWrapperVariable(variable))
        assertNotNull("规则 4 命中后预览链路应可定位", WrapperLocator.locateWithTable(variable))
    }

    /**
     * 只有通用条件方法、且变量被 MP 消费方法引用 → LAMBDA_QUERY；无消费引用 → 不认。
     */
    fun testChainMethodHeuristicConditionChainRequiresMpConsumer() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserMapper mapper;
                void withConsumer() {
                    PlainWrapper<Object> w = new PlainWrapper<>();
                    w.eq("a", 1);
                    mapper.selectList(w);
                }
                void withoutConsumer() {
                    PlainWrapper<Object> lonely = new PlainWrapper<>();
                    lonely.eq("b", 2);
                }
            }

            class PlainWrapper<T> {
                PlainWrapper<T> eq(String column, Object value) { return this; }
            }

            interface BaseMapper<T> {}
            interface UserMapper extends BaseMapper<Object> {}
            """.trimIndent(),
        )
        val variables = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiLocalVariable::class.java)
            .associateBy { it.name }
        assertTrue(
            "被 MP mapper 消费的条件链应认作 LAMBDA_QUERY",
            WrapperLocator.isWrapperVariable(variables.getValue("w")),
        )
        assertFalse(
            "无 MP 消费引用的条件链不认（防误判）",
            WrapperLocator.isWrapperVariable(variables.getValue("lonely")),
        )
    }
}
