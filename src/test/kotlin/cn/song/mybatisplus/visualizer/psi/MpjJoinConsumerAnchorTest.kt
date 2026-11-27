package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame

/**
 * D256 M2：MPJ join 消费族调用行锚点（`selectJoinList` / `selectJoinOne` / `selectJoinMap` /
 * `selectJoinCount`）。
 *
 * 契约（与 `selectJoinMaps` 族同口径）：调用行独立承载锚点——即使 wrapper 实参存在也不让位。
 * 这批名字**不进**批量注册表（D256 M3 守卫：批量模板会拿首参 DTO 当实体、产出
 * `WHERE id IN` 的错误 SQL），锚点只负责入口，SQL 由 wrapper 链路径渲染。
 *
 * receiver 门：必须属 MP Service/Mapper 家族；普通类同名方法不亮。
 */
class MpjJoinConsumerAnchorTest : BasePlatformTestCase() {

    private fun configure(code: String) {
        myFixture.configureByText("Demo.java", code.trimIndent())
    }

    private fun calls(name: String): List<PsiMethodCallExpression> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == name }

    private val fixture = """
        public class Demo {
            private DeviceInfoMapper mapper;
            private UserService service;
            private PlainDao plain;
            private Object wrapper;

            void a() { mapper.selectJoinList(UserEntity.class, wrapper); }
            void b() { service.selectJoinOne(UserEntity.class, wrapper); }
            void c() { mapper.selectJoinMap(wrapper); }
            void d() { mapper.selectJoinCount(wrapper); }
            void e() { plain.selectJoinList(UserEntity.class, wrapper); }
            void f() { mapper.selectJoinCount(); }
            void g() { service.removeById(1L); }
        }

        interface BaseMapper<T> {}
        interface IService<T> {}
        interface DeviceInfoMapper extends BaseMapper<UserEntity> {}
        interface UserService extends IService<UserEntity> {}
        class PlainDao {}
        class UserEntity { private Long id; }
    """

    fun testJoinConsumerCallsAreAnchorsOnMpReceiver() {
        configure(fixture)
        listOf("selectJoinList", "selectJoinOne", "selectJoinMap", "selectJoinCount").forEach { name ->
            val nameElement = calls(name).first().methodExpression.referenceNameElement!!
            assertSame(
                "$name 调用名应为 gutter 锚点（MP receiver）",
                nameElement,
                WrapperAnchorDetector.anchorElement(nameElement),
            )
        }
    }

    fun testNonMpReceiverNotAnchor() {
        configure(fixture)
        val plainCall = calls("selectJoinList").first {
            it.methodExpression.qualifierExpression?.text == "plain"
        }
        assertNull(
            "普通类上的 selectJoinList 不应亮图标",
            WrapperAnchorDetector.anchorElement(plainCall.methodExpression.referenceNameElement),
        )
    }

    fun testNoArgJoinConsumerNotAnchor() {
        configure(fixture)
        val noArg = calls("selectJoinCount").first { it.argumentList.expressions.isEmpty() }
        assertNull(
            "无实参的 join 消费调用没有 SQL 可预览，不亮图标",
            WrapperAnchorDetector.anchorElement(noArg.methodExpression.referenceNameElement),
        )
    }

    fun testUnrelatedBatchCallUnaffected() {
        configure(fixture)
        val nameElement = calls("removeById").first().methodExpression.referenceNameElement!!
        assertSame(
            "既有批量写方法锚点不受 M2 影响",
            nameElement,
            WrapperAnchorDetector.anchorElement(nameElement),
        )
    }
}
