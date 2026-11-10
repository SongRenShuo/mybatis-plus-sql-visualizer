package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * T-HiddenBugPerfScan 券 A：and/or 块体 lambda 内嵌套调用的调用泄漏回归。
 *
 * 块体 `and(o -> { o.eq(A); o.or(x -> x.eq(B)); })` 的块分支收集曾不排除
 * 嵌套 lambda 体内的调用——`x.eq(B)` 既经 or 的 NestedArg 进入嵌套链，又作为
 * 独立顶层调用混入外层链，渲染出重复条件。修复后块体只收「属于本 lambda 的
 * 顶层语句链」（链尾回溯整链），嵌套 lambda 体内的调用由递归解析——与
 * [ArgResolver.collectChainFromInitializer] 的既有防线同口径。
 */
class ArgResolverNestedBlockLambdaTest : BasePlatformTestCase() {

    fun testBlockBodyNestedLambdaNotLeakedToOuterChain() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1L)
                        .and(o -> {
                            o.eq(DeviceInfoEntity::getStationId, 101L);
                            o.or(x -> x.eq(DeviceInfoEntity::getShareSchemeId, 5L));
                        });
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                    private Long shareSchemeId;
                }
            }
            """.trimIndent(),
        )
        val chain = WrapperLocator.locate(anchor())
        assertNotNull("应识别块体 and 嵌套 lambda", chain)
        assertEquals(WrapperType.LAMBDA_QUERY, chain!!.wrapperType)
        assertEquals(listOf("eq", "and"), chain.calls.map { it.methodName })
        // 泄漏发生在 and 的嵌套链内部：x.eq(shareSchemeId) 曾混入块体收集 → ["eq","or","eq"]；
        // 修复后块体只收本 lambda 的顶层语句链，or 的嵌套体由递归解析
        val nestedArg = chain.calls[1].args.firstOrNull() as? cn.song.mybatisplus.visualizer.model.WrapperArg.NestedArg
        assertNotNull("and 调用应有嵌套链参数", nestedArg)
        assertEquals(listOf("eq", "or"), nestedArg!!.chain.calls.map { it.methodName })
        val orNested = nestedArg.chain.calls[1].args.firstOrNull() as? cn.song.mybatisplus.visualizer.model.WrapperArg.NestedArg
        assertNotNull("or 调用应有嵌套链参数", orNested)
        assertEquals(listOf("eq"), orNested!!.chain.calls.map { it.methodName })
    }

    fun testBlockBodyMultiStatementChainsKeptInOrder() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .and(o -> {
                            o.eq(DeviceInfoEntity::getId, 1L).like(DeviceInfoEntity::getStationId, "a");
                            o.isNull(DeviceInfoEntity::getShareSchemeId);
                        });
                }

                static class DeviceInfoEntity {
                    private Long id;
                    private Long stationId;
                    private Long shareSchemeId;
                }
            }
            """.trimIndent(),
        )
        val chain = WrapperLocator.locate(anchor())
        assertNotNull(chain)
        // 链中调用（eq 是 like 的 qualifier）不得重复计为独立条件；顺序按源码
        assertEquals(listOf("and"), chain!!.calls.map { it.methodName })
    }

    private fun anchor(): PsiElement {
        val file = myFixture.file
        return PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
            ?: PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: error("测试文件缺少可用的锚点元素")
    }
}
