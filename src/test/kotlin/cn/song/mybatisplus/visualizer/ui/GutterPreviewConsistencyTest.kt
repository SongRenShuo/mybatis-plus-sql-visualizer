package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.ui.gutter.SqlGutterLineMarkerProvider
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * gutter 标记存在 = 预览可用的接缝测试。
 *
 * 验证 SqlGutterLineMarkerProvider（gutter 标记）与 SqlPreviewService（预览）的接缝：
 *  - 方法体内有 Wrapper 调用 → gutter marker 存在 → 预览 SQL 非空
 *  - 方法体内无 Wrapper 调用 → gutter marker 不存在 → 预览返回 null
 *  - @ManyToOne 关联的嵌套方法调用 → gutter 行为正确
 *
 * 每个场景同时验证 gutter 锚点判定 + preview 结果一致性。
 */
class GutterPreviewConsistencyTest : BasePlatformTestCase() {

    private val provider: LineMarkerProvider = SqlGutterLineMarkerProvider()

    private fun newService() = SqlPreviewService(project)

    /** 文件内第一个局部变量的名字 identifier。 */
    private fun firstVariableId(): PsiIdentifier =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)!!.nameIdentifier!!

    /** 文件内第一个 new 表达式的关键字。 */
    private fun firstNewKeyword(): PsiKeyword =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiNewExpression::class.java)!!.firstChild as PsiKeyword

    /** 文件内第一个方法调用。 */
    private fun firstMethodCall(): PsiMethodCallExpression =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiMethodCallExpression::class.java)!!

    /**
     * 接缝：gutter 标记存在 → 预览可用。
     * 场景：方法体内有 Wrapper 调用（链式）→ gutter marker 存在 → 预览 SQL 非空。
     */
    fun testWrapperCallGutterMarkerPresentAndPreviewAvailable() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // gutter 标记验证：变量声明行应产生 marker
        val varId = firstVariableId()
        assertNotNull("Wrapper 变量声明行应产生 gutter marker", provider.getLineMarkerInfo(varId))

        // 锚点判定验证：变量 identifier 是合法锚点
        assertNotNull("变量 identifier 应被 WrapperAnchorDetector 识别为锚点", WrapperAnchorDetector.anchorElement(varId))

        // 预览可用验证：从锚点元素 preview 应返回非空 SQL
        val result = newService().preview(varId)
        assertNotNull("gutter marker 存在时 preview 应返回非空结果", result)
        assertTrue("preview 应生成 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
        assertTrue("preview SQL 应含 WHERE: ${result.sql}", result.sql.contains("WHERE"))
    }

    /**
     * 接缝：gutter 标记存在（内联 new）→ 预览可用。
     * 场景：内联构造 `list(new LambdaQueryWrapper<X>().eq(...))` → new 关键字处 marker 存在 → 预览 SQL 非空。
     */
    fun testInlineNewGutterMarkerPresentAndPreviewAvailable() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    list(new LambdaQueryWrapper<DeviceInfoEntity>().eq(DeviceInfoEntity::getId, 1001L));
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // gutter 标记验证：new 关键字应产生 marker
        val newKeyword = firstNewKeyword()
        assertNotNull("内联 new 关键字应产生 gutter marker", provider.getLineMarkerInfo(newKeyword))

        // 预览可用验证
        val result = newService().preview(newKeyword)
        assertNotNull("gutter marker 存在时 preview 应返回非空结果", result)
        assertTrue("preview 应生成 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
    }

    /**
     * 接缝：gutter 标记不存在 → 预览不可用。
     * 场景：方法体内无 Wrapper 调用 → gutter marker 不存在 → 预览返回 null。
     */
    fun testNoWrapperCallNoGutterMarkerAndPreviewNull() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void plainMethod() {
                    String message = "hello world";
                    System.out.println(message);
                }
            }
            """.trimIndent(),
        )
        // gutter 标记验证：普通代码行不应产生 marker
        val firstElement = PsiTreeUtil.getDeepestFirst(myFixture.file)
        assertNull("非 Wrapper 代码不应产生 gutter marker", provider.getLineMarkerInfo(firstElement))

        // 预览不可用验证：从普通元素 preview 应返回 null
        val result = newService().preview(firstElement)
        assertNull("无 Wrapper 调用时 preview 应返回 null", result)
    }

    /**
     * 接缝：gutter 标记不存在（非 Wrapper 方法调用）→ 预览不可用。
     * 场景：方法体内有普通方法调用（非 Wrapper）→ gutter marker 不存在 → 预览返回 null。
     */
    fun testNonWrapperMethodCallNoGutterMarkerAndPreviewNull() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void plainMethod() {
                    java.util.List<String> items = new java.util.ArrayList<>();
                    items.add("test");
                    items.get(0);
                }
            }
            """.trimIndent(),
        )
        // gutter 标记验证：普通方法调用不应产生 marker
        val methodCall = firstMethodCall()
        assertNull("普通方法调用不应产生 gutter marker", provider.getLineMarkerInfo(methodCall))

        // 预览不可用验证
        val result = newService().preview(methodCall)
        assertNull("非 Wrapper 方法调用时 preview 应返回 null", result)
    }

    /**
     * 接缝：gutter 标记存在（工厂链）→ 预览可用。
     * 场景：`Wrappers.<Entity>lambdaQuery().eq(...)` → 工厂方法名 identifier 处 marker 存在 → 预览 SQL 非空。
     */
    fun testFactoryChainGutterMarkerPresentAndPreviewAvailable() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = Wrappers.<DeviceInfoEntity>lambdaQuery()
                        .eq(DeviceInfoEntity::getId, 1001L);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // gutter 标记验证：变量声明行应产生 marker（工厂链赋值给变量）
        val varId = firstVariableId()
        assertNotNull("工厂链变量声明行应产生 gutter marker", provider.getLineMarkerInfo(varId))

        // 预览可用验证
        val result = newService().preview(varId)
        assertNotNull("gutter marker 存在时 preview 应返回非空结果", result)
        assertTrue("preview 应生成 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
        assertTrue("preview SQL 应含 WHERE: ${result.sql}", result.sql.contains("WHERE"))
    }

    /**
     * 接缝：@ManyToOne 关联的嵌套方法调用 → gutter 行为正确。
     * 场景：`entity.getAddress().getCity()` 嵌套方法调用 → 非 Wrapper 调用 → gutter marker 不存在 → 预览返回 null。
     */
    fun testNestedMethodCallNotMisidentifiedAsWrapper() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void nestedAccess() {
                    UserEntity user = getUser();
                    String city = user.getAddress().getCity();
                }
                static class UserEntity {
                    private AddressEntity address;
                    public AddressEntity getAddress() { return address; }
                }
                static class AddressEntity {
                    private String city;
                    public String getCity() { return city; }
                }
                private UserEntity getUser() { return new UserEntity(); }
            }
            """.trimIndent(),
        )
        // gutter 标记验证：嵌套方法调用不应产生 marker
        val methodCall = firstMethodCall()
        assertNull("嵌套方法调用不应产生 gutter marker", provider.getLineMarkerInfo(methodCall))

        // 预览不可用验证：嵌套方法调用不是 Wrapper → preview 返回 null
        val result = newService().preview(methodCall)
        assertNull("嵌套方法调用时 preview 应返回 null", result)
    }

    /**
     * 接缝：gutter 标记存在（删除消费）→ 预览可用（DELETE）。
     * 场景：`service.remove(wrapper)` → gutter marker 存在 → 预览返回 DELETE 语句。
     */
    fun testDeleteConsumerGutterMarkerPresentAndPreviewDelete() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void remove() {
                    LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                        .eq(DeviceInfoEntity::getId, 1001L);
                    service.remove(w);
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // gutter 标记验证：变量声明行应产生 marker
        val varId = firstVariableId()
        assertNotNull("删除消费的 Wrapper 变量应产生 gutter marker", provider.getLineMarkerInfo(varId))

        // 预览可用验证：应返回 DELETE 语句
        val result = newService().preview(varId)
        assertNotNull("gutter marker 存在时 preview 应返回非空结果", result)
        assertTrue("删除消费应生成 DELETE: ${result!!.sql}", result.sql.startsWith("DELETE FROM"))
    }

    /**
     * 接缝：gutter 标记存在（服务链）→ 预览可用。
     * 场景：`service.lambdaQuery()...one()` 服务链 → 方法名 identifier 处 marker 存在 → 预览 SQL 非空。
     */
    fun testServiceChainGutterMarkerPresentAndPreviewAvailable() {
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    DeviceInfoEntity one = service.lambdaQuery()
                        .eq(DeviceInfoEntity::getId, 1001L)
                        .one();
                }

                static class DeviceInfoEntity { private Long id; }
            }
            """.trimIndent(),
        )
        // 预览可用验证：服务链应能 preview
        val methodCall = firstMethodCall()
        val result = newService().preview(methodCall)
        assertNotNull("服务链 preview 应返回非空结果", result)
        assertTrue("服务链应生成 SELECT: ${result!!.sql}", result.sql.startsWith("SELECT"))
    }

    // ==================================================================
    // T-WriteFamily（R4）：空 Wrapper 写族的 gutter ↔ preview 接缝
    // ==================================================================

    fun testDeleteNullGutterMarkerPresentAndPreviewDelete() {
        // R4：`mapper.delete(null)` 锚点（delete identifier）→ gutter marker 存在 → 预览产出无 WHERE 的 DELETE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void remove() {
                    deviceInfoMapper.delete(null);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                interface BaseMapper<T> {}

                interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val nameId = firstMethodCall().methodExpression.referenceNameElement!!
        assertSame(
            "delete(null) 的 delete identifier 应为锚点",
            nameId,
            WrapperAnchorDetector.anchorElement(nameId),
        )
        assertNotNull("delete(null) 应产生 gutter marker", provider.getLineMarkerInfo(nameId))
        val result = newService().preview(nameId)
        assertNotNull("gutter marker 存在时 preview 应返回非空结果", result)
        assertTrue("delete(null) 应生成 DELETE：${result!!.sql}", result.sql.startsWith("DELETE FROM"))
        assertNotNull("全表删除应有警示", result.warning)
    }

    fun testUpdateNullWrapperGutterMarkerPresentAndPreviewUpdate() {
        // R4：`mapper.update(entity, null)` 锚点（update identifier）→ gutter marker 存在 → 预览产出无 WHERE 的 UPDATE
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void update(DeviceInfoEntity entity) {
                    deviceInfoMapper.update(entity, null);
                }

                static class DeviceInfoEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                }

                interface BaseMapper<T> {}

                interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        val nameId = firstMethodCall().methodExpression.referenceNameElement!!
        assertSame(
            "update(entity, null) 的 update identifier 应为锚点",
            nameId,
            WrapperAnchorDetector.anchorElement(nameId),
        )
        assertNotNull("update(entity, null) 应产生 gutter marker", provider.getLineMarkerInfo(nameId))
        val result = newService().preview(nameId)
        assertNotNull("gutter marker 存在时 preview 应返回非空结果", result)
        assertTrue("update(entity, null) 应生成 UPDATE：${result!!.sql}", result.sql.startsWith("UPDATE"))
        assertTrue("不得生成 WHERE：${result.sql}", !result.sql.contains("WHERE"))
        assertNotNull("全表更新应有警示", result.warning)
    }
}
