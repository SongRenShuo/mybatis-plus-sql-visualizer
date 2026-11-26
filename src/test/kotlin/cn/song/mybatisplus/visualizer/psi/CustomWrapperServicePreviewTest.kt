package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * D256 修复验证：自研 Service 封装 + 自定义 Wrapper 子类的预览链路。
 *
 * 结构等价复刻（类名、方法名、字段与业务语义均已脱敏，不代表真实项目命名）：
 *  - 自研 `FrameworkService<T> extends IService<T>` 把 MPJ 的 `selectJoin*` 全家桶再包一层，
 *    `FrameworkServiceImpl` 继承 MP `ServiceImpl`，方法体统一 `getBaseMapper().selectJoinList(...)`；
 *  - 业务层注入 `XxxRepository extends FrameworkService<Entity>`，调用
 *    `repository.selectJoinList(Dto.class, wrapper)`；
 *  - wrapper 是自研四层子类（`CustomJoinWrapper → AbstractLambdaJoinBaseWrapper
 *    → AbstractJoinBaseWrapper → BaseWrapper → MPJLambdaWrapper`），简单类名不在浅层白名单。
 *
 * 修复前（本类首版，提交 `fc2b90542`）固化的两条事实，现均已翻转：
 *  1. 自定义子类变量在**声明行**与**调用行**都无法定位 → 现在可定位（M1 父链/规则 4）；
 *  2. `SqlPreviewService.preview` 对同类结构返回 null → 现在产出 SQL（M1 + M2 锚点）。
 *
 * 测试内内联伪造 `MPJLambdaWrapper` / `IService`，仅提供可选链与父类型，
 * 不依赖真实 MPJ/MP jar（LightPlatform 无依赖索引）；夹具走**规则 4**（链方法 + MP 消费引用），
 * FQN 父链规则由 `WrapperTypeSuperChainTest` 用假 FQN 文件覆盖。
 *
 * k2j 移植注：`located!!.chain.calls.map { it.methodName }` 走 Java getter 的属性映射
 * （getChain()/getCalls()/getMethodName()），Kotlin 侧属性语法不变；`preview!!.sql` 同理。
 */
class CustomWrapperServicePreviewTest : BasePlatformTestCase() {

    /** 自研 wrapper 子类 + Repository 封装调用。 */
    private val customSubclassCode = """
        class Demo {
            private DeviceJoinRepository deviceJoinRepository;

            void queryDeviceList() {
                CustomJoinWrapper<DeviceInfoEntity> wrapper = new CustomJoinWrapper<>();
                wrapper.eq(DeviceInfoEntity::getId, 1L);
                wrapper.in(DeviceInfoEntity::getName, java.util.Arrays.asList("gateway-01"));
                deviceJoinRepository.selectJoinList(DeviceInfoEntity.class, wrapper);
            }
        }

        interface IService<T> {}

        interface FrameworkService<T> extends IService<T> {
            <DTO> java.util.List<DTO> selectJoinList(Class<DTO> clazz, JoinBase<T> wrapper);
        }

        interface JoinBase<T> {}

        class MPJLambdaWrapper<T> {
            <E> MPJLambdaWrapper<T> eq(java.util.function.Function<T, ?> column, Object value) { return this; }
            <E> MPJLambdaWrapper<T> in(java.util.function.Function<T, ?> column, java.util.Collection<?> values) { return this; }
        }

        /**
         * 四层自研链（结构等价，类名已脱敏）：
         * CustomJoinWrapper -> AbstractLambdaJoinBaseWrapper -> AbstractJoinBaseWrapper -> BaseWrapper -> MPJLambdaWrapper
         */
        abstract class BaseWrapper<T> extends MPJLambdaWrapper<T> {}

        abstract class AbstractJoinBaseWrapper<T, C extends AbstractJoinBaseWrapper<T, C>> extends BaseWrapper<T> {}

        abstract class AbstractLambdaJoinBaseWrapper<T, C extends AbstractLambdaJoinBaseWrapper<T, C>>
                extends AbstractJoinBaseWrapper<T, C> {}

        class CustomJoinWrapper<T> extends AbstractLambdaJoinBaseWrapper<T, CustomJoinWrapper<T>>
                implements JoinBase<T> {}

        interface DeviceJoinRepository extends FrameworkService<DeviceInfoEntity> {}

        class DeviceInfoEntity {
            private Long id;
            private String name;
        }
    """.trimIndent()

    /** 对照组：与上面同构，只把 wrapper 换成白名单内的官方类名。 */
    private val officialWrapperCode = customSubclassCode
        .replace("CustomJoinWrapper<DeviceInfoEntity> wrapper = new CustomJoinWrapper<>();",
            "MPJLambdaWrapper<DeviceInfoEntity> wrapper = new MPJLambdaWrapper<>();")

    private fun anchorOnSelectJoinList(): PsiMethodCallExpression =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "selectJoinList" }

    private fun anchorOnWrapperDeclaration(): PsiMethodCallExpression =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "eq" }

    /**
     * 修复验证 1：自定义子类在**调用行**可定位。
     */
    fun testCustomWrapperSubclassAtCallSiteLocated() {
        myFixture.configureByText("Demo.java", customSubclassCode)
        val located = WrapperLocator.locateWithTable(anchorOnSelectJoinList())
        assertNotNull("自研 wrapper 子类应可识别——调用行定位非空", located)
        assertEquals(
            "链内条件调用应完整收集",
            listOf("eq", "in"),
            located!!.chain.calls.map { it.methodName },
        )
    }

    /** 修复验证 1b：自定义子类在**链内**（eq 调用处）同样可定位。 */
    fun testCustomWrapperSubclassInsideChainLocated() {
        myFixture.configureByText("Demo.java", customSubclassCode)
        assertNotNull(
            "自研 wrapper 子类应可识别——链内定位非空",
            WrapperLocator.locateWithTable(anchorOnWrapperDeclaration()),
        )
    }

    /**
     * 对照组：同样的 Service 封装 + 调用位置，wrapper 换成白名单类名即可定位。
     */
    fun testOfficialWrapperAtCallSiteLocated() {
        myFixture.configureByText("Demo.java", officialWrapperCode)
        val located = WrapperLocator.locateWithTable(anchorOnSelectJoinList())
        assertNotNull("官方 MPJLambdaWrapper + Service 封装调用行应可定位", located)
        assertEquals(
            listOf("eq", "in"),
            located!!.chain.calls.map { it.methodName },
        )
    }

    /**
     * 修复验证 3：gutter 锚点——自定义子类变量声明与 `selectJoinList` 调用行都应亮；
     * 官方类名对照组照常亮。
     */
    fun testGutterAnchorFollowsWrapperTypeWhitelist() {
        myFixture.configureByText("Demo.java", customSubclassCode)
        val customVar = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiLocalVariable::class.java)
            .first { it.name == "wrapper" }
        assertNotNull(
            "D256 M1：自定义 wrapper 子类变量声明应亮 gutter 锚点",
            WrapperAnchorDetector.anchorElement(customVar.nameIdentifier),
        )
        assertNotNull(
            "D256 M2：selectJoinList 调用行已独立承载锚点（MP Service receiver）",
            WrapperAnchorDetector.anchorElement(anchorOnSelectJoinList().methodExpression.referenceNameElement),
        )

        myFixture.configureByText("Demo.java", officialWrapperCode)
        val officialVar = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiLocalVariable::class.java)
            .first { it.name == "wrapper" }
        assertNotNull(
            "官方 wrapper 变量声明应亮 gutter 锚点（对照组）",
            WrapperAnchorDetector.anchorElement(officialVar.nameIdentifier),
        )
    }

    /**
     * 修复验证 4（用户感知层）：预览服务对自定义子类产出 SQL。
     */
    fun testPreviewServiceProducesSqlForCustomSubclass() {
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        myFixture.configureByText("Demo.java", customSubclassCode)
        val preview = cn.song.mybatisplus.visualizer.ui.SqlPreviewService(project)
            .preview(anchorOnSelectJoinList())
        assertNotNull("自定义 wrapper 子类在预览服务层应产出结果", preview)
        assertNotNull("预览结果应带 SQL 文本", preview!!.sql.takeIf { it.isNotBlank() })
    }

    /**
     * 对照组（用户感知层）：同结构换官方类名，预览服务直接产出 SQL。
     */
    fun testPreviewServiceProducesSqlForOfficialWrapper() {
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        myFixture.configureByText("Demo.java", officialWrapperCode)
        val preview = cn.song.mybatisplus.visualizer.ui.SqlPreviewService(project)
            .preview(anchorOnSelectJoinList())
        assertNotNull("官方 wrapper 同结构下预览应产出 SQL（对照组）", preview)
    }
}
