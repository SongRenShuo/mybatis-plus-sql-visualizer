package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiField
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * WrapperAnchorDetector 单测（M10-01，gutter 锚点判定）。
 *
 * 在 LightPlatform 虚拟项目中用真实 Java PSI 验证：
 *  - 链式 / 分步 / 内联三种形态的锚点识别
 *  - 同行去重（变量声明 + 构造只标变量声明）
 *  - 非 Wrapper 变量 / 构造 / 普通叶子零成本负例
 */
class WrapperAnchorDetectorTest : BasePlatformTestCase() {

    private fun configure(code: String) {
        myFixture.configureByText("Demo.java", code.trimIndent())
    }

    /** 文件内第一个局部变量。 */
    private fun firstVariable(): PsiLocalVariable =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)!!

    /** 文件内第一个 new 表达式。 */
    private fun firstNewExpression(): PsiNewExpression =
        PsiTreeUtil.findChildOfType(myFixture.file, PsiNewExpression::class.java)!!

    /**
     * 全文件锚点扫描：遍历全部 PsiIdentifier + PsiKeyword 叶子（daemon 快速路径传入的元素类型），
     * 收集所有产生锚点的元素文本。
     *
     * 整体不变量断言用（T-Mxx 教训：逐个方法名断言会漏检——selectAll 双图标曾因只断言
     * lambda 而去重通过）；集合恰好等于预期即「无多余图标」。
     */
    private fun collectedAnchors(): Set<String> {
        val anchors = mutableSetOf<String>()
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .forEach { anchors += (WrapperAnchorDetector.anchorElement(it)?.text ?: return@forEach) }
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiKeyword::class.java)
            .forEach { anchors += (WrapperAnchorDetector.anchorElement(it)?.text ?: return@forEach) }
        return anchors
    }

    fun testChainStyleAnchorsOnVariableDeclaration() {
        // 链式：变量初始化即 wrapper 构造（同行）→ 只标变量声明，new 关键字去重
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val variable = firstVariable()
        assertSame(
            "变量名 identifier 应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull("wrapper 构造应有 new 关键字", newKeyword)
        assertNull("new 关键字应被去重（与变量声明同行）", WrapperAnchorDetector.anchorElement(newKeyword!!))
    }

    fun testStepByStepAnchorsOnVariableDeclaration() {
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>();
                    w.eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val variable = firstVariable()
        assertSame(variable.nameIdentifier, WrapperAnchorDetector.anchorElement(variable.nameIdentifier))
    }

    fun testInlineNewExpressionAnchorsOnNewKeyword() {
        // 内联：list(new MPJLambdaWrapper<X>().select(...)) 无变量 → new 关键字是锚点
        configure(
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    list(new MPJLambdaWrapper<DeviceEntity>().select(DeviceEntity::getId).eq(DeviceEntity::getId, 1L));
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull(newKeyword)
        assertSame("内联构造的 new 关键字应为锚点", newKeyword, WrapperAnchorDetector.anchorElement(newKeyword!!))
    }

    fun testNonWrapperVariableIsNotAnchor() {
        configure(
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                }
            }
            """,
        )
        val variable = firstVariable()
        assertNull(WrapperAnchorDetector.anchorElement(variable.nameIdentifier))
    }

    fun testNonWrapperNewExpressionIsNotAnchor() {
        configure(
            """
            import java.util.ArrayList;

            public class Demo {
                public void query() {
                    ArrayList<String> list = new ArrayList<String>();
                }
            }
            """,
        )
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull(newKeyword)
        assertNull("非 Wrapper 构造不应是锚点", WrapperAnchorDetector.anchorElement(newKeyword!!))
    }

    fun testQualifiedEntityTypeAnchors() {
        // 回归：真实项目实体类带包名 + Wrappers.<T>lambdaQuery() 工厂写法。
        // detectWrapperType 曾用 canonicalText.substringAfterLast('.') 提取类名，
        // 泛型实参带包名时最后一个 '.' 落在泛型参数内导致判定失败（无图标）。
        // 用全限定泛型参数文本（canonicalText 含 '.'），无需真实实体类/import。
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void page() {
                    LambdaQueryWrapper<com.biz.entity.BikeDeviceProductInfoEntity> wrapper = Wrappers.<com.biz.entity.BikeDeviceProductInfoEntity>lambdaQuery()
                        .like(com.biz.entity.BikeDeviceProductInfoEntity::getName, "x");
                }
            }
            """,
        )
        val variable = firstVariable()
        assertSame(
            "带包名实体 + 工厂写法的变量名应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
    }

    fun testInlineWrappersFactoryCallAnchors() {
        // 内联 Wrappers.<T>lambdaQuery()（无变量、无 new）：工厂方法名 identifier 是锚点
        configure(
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    list(Wrappers.<DeviceEntity>lambdaQuery().eq(DeviceEntity::getId, 1L));
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        val nameId = factoryCall.methodExpression.referenceNameElement!!
        assertSame("内联工厂调用的方法名应为锚点", nameId, WrapperAnchorDetector.anchorElement(nameId))
    }

    fun testInlineWrappersFactoryCallDedupWithVariable() {
        // 变量初始化即工厂调用（`w = Wrappers.lambdaQuery()...`）：只标变量声明，工厂方法名去重
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> w = Wrappers.<DeviceEntity>lambdaQuery().eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val variable = firstVariable()
        assertSame("变量名应为锚点", variable.nameIdentifier, WrapperAnchorDetector.anchorElement(variable.nameIdentifier))
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertNull(
            "工厂方法名应与变量声明同行去重",
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testServiceChainCallAnchors() {
        // T-M15-01：service.lambdaQuery() / service.lambdaUpdate() 服务链（qualifier 任意引用，语法级匹配）
        configure(
            """
            public class Demo {
                public void query() {
                    service.lambdaQuery().eq(DeviceEntity::getId, 1L).one();
                }
            }
            """,
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertSame(
            "服务链 lambdaQuery 方法名应为锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testChainWrappersFactoryMethodNameIsAnchor() {
        // P1-05（M19.5）：ChainWrappers 静态工厂方法名应为 gutter 锚点（场景 C isWrappersFactory 扩展）
        configure(
            """
            public class Demo {
                public void query() {
                    ChainWrappers.queryChain(deviceInfoMapper).eq("a", 1).list();
                }
            }
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "queryChain" }
        assertSame(
            "ChainWrappers 工厂方法名应为锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testServiceConsumerCallIsNotAnchor() {
        // 消费调用（service.update(wrapper) / service.count(wrapper)）不是链起点 → 不是锚点（仅 lambdaQuery/lambdaUpdate 放行）
        configure(
            """
            public class Demo {
                public void query() {
                    service.update(wrapper);
                    service.count(wrapper);
                }
            }
            """,
        )
        for (name in listOf("update", "count")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            assertNull(
                "消费调用 $name 不应是锚点",
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
    }

    fun testAssignedServiceChainEntityResultAnchors() {
        // M19.5（T-M19.5-01，2026-08-13 用户实测用例 1 原样）：实体结果变量赋值形态——
        // `XxxEntity e = service.lambdaQuery()...one()` 的 lambdaQuery 方法名必须是锚点
        // （旧实现 isInsideWrapperVariableInitializer 对任意变量初始化一律去重 → 整条链无锚点，回归已实证）
        configure(
            """
            public class Demo {
                public void query() {
                    DeviceEntity onlineVersion = service.lambdaQuery()
                            .eq(DeviceEntity::getStationId, 1)
                            .orderByDesc(DeviceEntity::getDicStatus)
                            .last("LIMIT 1")
                            .one();
                }

                static class DeviceEntity { private Long stationId; private Integer dicStatus; }
            }
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertSame(
            "实体结果变量赋值不应抑制服务链锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testAssignedServiceChainBooleanResultAnchors() {
        // M19.5（用户实测用例 2 原样）：boolean 结果变量赋值形态（lambdaUpdate...update()）
        configure(
            """
            public class Demo {
                public void update() {
                    boolean updated = service.lambdaUpdate()
                            .eq(WorkflowEntity::getId, 1L)
                            .set(WorkflowEntity::getShelveStatus, 0)
                            .update();
                }

                static class WorkflowEntity { private Long id; private Integer shelveStatus; }
            }
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaUpdate" }
        assertSame(
            "boolean 结果变量赋值不应抑制服务链锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testAssignedServiceChainLongResultAnchors() {
        // M19.5（测试盲区补全）：long 结果变量赋值形态（lambdaQuery...count()）——
        // 旧实现下 `long total = service.lambdaQuery()...count()` 同样无锚点
        configure(
            """
            public class Demo {
                public void count() {
                    long total = service.lambdaQuery()
                            .eq(DeviceEntity::getDicStatus, 1)
                            .count();
                }

                static class DeviceEntity { private Integer dicStatus; }
            }
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertSame(
            "long 结果变量赋值不应抑制服务链锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testServiceChainGetObjTerminalAnchors() {
        // T-M20-06：ChainQuery getObj() 终结点链（MP 3.5.4+ 官方 API，消费方法全集核对补注册）——
        // `service.lambdaQuery()...getObj()` 的 lambdaQuery 方法名仍应为 gutter 锚点（场景 C）
        configure(
            """
            public class Demo {
                public void query() {
                    Object obj = service.lambdaQuery()
                            .eq(DeviceEntity::getId, 1L)
                            .getObj();
                }

                static class DeviceEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertSame(
            "getObj 终结点链的 lambdaQuery 方法名应为锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testBaseTypeVariableAnchors() {
        // 变量用抽象基类型声明（`Wrapper<X> w = new LambdaQueryWrapper<X>()...`）→ 变量名仍是锚点
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.Wrapper;
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    Wrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>().eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val variable = firstVariable()
        assertSame(
            "基类型 Wrapper<X> 声明的变量名应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull(newKeyword)
        assertNull("基类型声明行 new 应去重（变量声明已标）", WrapperAnchorDetector.anchorElement(newKeyword!!))
    }

    fun testMethodParameterWrapperAnchors() {
        // wrapper 是方法参数（`void del(LambdaQueryWrapper<X> wrapper)`）→ 参数名应是锚点
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void del(LambdaQueryWrapper<DeviceEntity> wrapper) {
                    wrapper.eq(DeviceEntity::getId, 1L);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val param = PsiTreeUtil.findChildOfType(myFixture.file, PsiParameter::class.java)!!
        assertSame(
            "方法参数名应为锚点",
            param.nameIdentifier,
            WrapperAnchorDetector.anchorElement(param.nameIdentifier),
        )
    }

    fun testBatchOperationCallAnchors() {
        // 批量操作调用（`service.updateBatchById(list)`）→ 方法名应是锚点
        configure(
            """
            import java.util.List;

            public class Demo {
                public void update(List<DeviceEntity> list) {
                    service.updateBatchById(list);
                }

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "updateBatchById" }
        assertSame(
            "批量操作方法名应为锚点",
            call.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
        )
    }

    fun testFieldDeclarationIsAnchor() {        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                private LambdaQueryWrapper<DeviceEntity> w = new LambdaQueryWrapper<DeviceEntity>();

                static class DeviceEntity { private Long id; }
            }
            """,
        )
        val field = PsiTreeUtil.findChildOfType(myFixture.file, PsiField::class.java)!!
        assertSame("字段声明名也应识别为锚点", field.nameIdentifier, WrapperAnchorDetector.anchorElement(field.nameIdentifier))
    }

    fun testOrdinaryLeafIsNotAnchor() {
        // 普通叶子（字符串字面量）→ null，零成本负例
        configure(
            """
            public class Demo {
                public void query() {
                    String s = "hello";
                }
            }
            """,
        )
        val literal = PsiTreeUtil.findChildOfType(myFixture.file, PsiLiteralExpression::class.java)!!
        assertNull(WrapperAnchorDetector.anchorElement(literal))
    }

    fun testJoinWrappersFactoryMethodNameIsAnchor() {
        // T-M20-02：MPJ 官方工厂 JoinWrappers.lambda(...) 内联链（无变量、无 new）——
        // 工厂方法名 identifier 应是 gutter 锚点（场景 C isWrappersFactory 文本匹配扩展）
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void query() {
                    mapper.selectJoinList(UserEntity.class, JoinWrappers.lambda(UserEntity.class)
                            .selectAll()
                            .eq(UserEntity::getId, 1L));
                }

                static class UserEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambda" }
        assertSame(
            "内联 JoinWrappers.lambda 工厂方法名应为锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testJoinWrappersFactoryAssignedVariableAnchors() {
        // T-M20-02：赋值变量形态（MPJLambdaWrapper<UserEntity> w = JoinWrappers.lambda(...)...）——
        // 变量声明是锚点（场景 B），工厂方法名与变量声明同行去重
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<UserEntity> w = JoinWrappers.lambda(UserEntity.class)
                            .selectAll()
                            .eq(UserEntity::getId, 1L);
                }

                static class UserEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val variable = firstVariable()
        assertSame(
            "赋值变量形态的变量名应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambda" }
        assertNull(
            "赋值变量形态下工厂方法名应与变量声明同行去重",
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testWrappersLambdaQueryFactoryStillAnchor() {
        // T-M20-02 回归：wrapperFactoryMethodNames 新增 lambda/delete 后，
        // Wrappers.<T>lambdaQuery() 旧行为不回归（方法名仍为锚点）
        configure(
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    list(Wrappers.<DeviceEntity>lambdaQuery().eq(DeviceEntity::getId, 1L));
                }

                static class DeviceEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertSame(
            "Wrappers.lambdaQuery 方法名应仍为锚点",
            factoryCall.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
    }

    fun testInlineMpjSelectAllOnWrapperChainNotBatchAnchor() {
        // T-M23.11（用户实测）：`selectJoinOne(VO.class, new MPJLambdaWrapper<>().selectAll(Entity.class)...)`——
        // selectAll 同时收录于批量注册表与 MPJ 链方法；链上调用不标批量锚点（链锚点由 new 承载），避免双图标
        configure(
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    taskRecordService.selectJoinOne(VO.class,
                            new MPJLambdaWrapper<RecordEntity>()
                                    .selectAll(RecordEntity.class)
                                    .eq(RecordEntity::getId, 1L));
                }

                static class RecordEntity { private Long id; }
                static class VO { }
            }
            """.trimIndent(),
        )
        val selectAllCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "selectAll" }
        assertNull(
            "wrapper 链上的 selectAll(Class) 不应标批量锚点",
            WrapperAnchorDetector.anchorElement(selectAllCall.methodExpression.referenceNameElement),
        )
        val newKeyword = firstNewExpression().firstChild as? PsiKeyword
        assertNotNull(newKeyword)
        assertSame(
            "链锚点应由 new 关键字承载",
            newKeyword,
            WrapperAnchorDetector.anchorElement(newKeyword!!),
        )
    }

    fun testBatchSelectAllCallStillAnchor() {
        // T-M23.11 回归（不能造成一个图标也识别不出来）：批量形态（链首非 wrapper 构造/工厂）仍标锚点
        configure(
            """
            import java.util.List;

            public class Demo {
                public void query() {
                    Db.selectAll(DeviceEntity.class);
                    service.updateBatchById(list);
                }

                static class DeviceEntity { private Long id; }
            }
            """.trimIndent(),
        )
        for (name in listOf("selectAll", "updateBatchById")) {
            val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .filter { it.methodExpression.referenceName == name }
            assertTrue("应存在 $name 调用", calls.isNotEmpty())
            for (call in calls) {
                assertSame(
                    "批量形态 $name 方法名应仍为锚点",
                    call.methodExpression.referenceNameElement,
                    WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
                )
            }
        }
    }

    fun testStepByStepSelectAllOnWrapperVariableNotBatchAnchor() {
        // T-M23.11 分步形态：`MPJLambdaWrapper w = new ...; w.selectAll(...)`——白名单变量判定——
        // selectAll 不标批量锚点（变量行场景 B 图标承载链），且变量行锚点必须仍在（图标不丢）
        configure(
            """
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<RecordEntity> w = new MPJLambdaWrapper<RecordEntity>();
                    w.selectAll(RecordEntity.class).eq(RecordEntity::getId, 1L);
                }

                static class RecordEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val variable = firstVariable()
        assertSame(
            "分步形态变量行应为锚点（图标不丢）",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
        val selectAllCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "selectAll" }
        assertNull(
            "wrapper 变量上的 selectAll 不应标批量锚点（分步去重）",
            WrapperAnchorDetector.anchorElement(selectAllCall.methodExpression.referenceNameElement),
        )
    }

    fun testJoinWrappersFactoryWithMethodRefArgsDeduped() {
        // T-Mxx（用户复现）：MPJ 工厂变量赋值 + select(方法引用) + leftJoin(类字面量/字符串实参) 跨行链——
        // 原 isInsideWrapperVariableInitializer 沿 parent 单链遍历，遇到非 PsiExpression 节点中断返回 false →
        // 场景 C 工厂方法名未被去重 → 变量声明 + 工厂方法名双图标。修复=改用 getParentOfType 直接定位变量祖先。
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public void query() {
                    MPJLambdaWrapper<DeviceInfoEntity> wrapper = JoinWrappers.lambda(DeviceInfoEntity.class)
                            .selectAll(DeviceInfoEntity.class)
                            .select(StationInfoEntity::getStationName)
                            .leftJoin(StationInfoEntity.class, "st",
                                    StationInfoEntity::getStationId, "t", DeviceInfoEntity::getId)
                            .eq(DeviceInfoEntity::getId, 1);
                }

                static class DeviceInfoEntity { private Long id; }
                static class StationInfoEntity { private Long stationId; private String stationName; }
            }
            """.trimIndent(),
        )
        val variable = firstVariable()
        assertSame(
            "变量名应为锚点",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
        val factoryCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambda" }
        assertNull(
            "MPJ 工厂方法名应与变量声明去重（避免双图标）",
            WrapperAnchorDetector.anchorElement(factoryCall.methodExpression.referenceNameElement),
        )
        // 第二图标根因：链上 selectAll 同时收录于批量注册表（场景 D）——校验其也不标批量锚点
        // （原 initializerHeadIsWrapper 回溯停在 qualifier 引用返回 false → selectAll 未去重 → 第二个图标）
        val selectAllCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "selectAll" }
        assertNull(
            "链上 selectAll(Class) 不应标批量锚点（与变量声明去重）",
            WrapperAnchorDetector.anchorElement(selectAllCall.methodExpression.referenceNameElement),
        )
    }

    fun testListByIdsOnMapperVariableAnchors() {
        // T-J1 补充：listByIds（MP 批量名单方法）在 mapper 变量上仍按场景 D 名单锚定（行为不变）
        configure(
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void query() {
                    deviceInfoMapper.listByIds(ids);
                }

                static class DeviceInfoMapper {}
            }
            """.trimIndent(),
        )
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "listByIds" }
        assertSame(
            "MP 批量名单方法在 mapper 变量上应仍为锚点",
            call.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
        )
    }

    // ==================================================================
    // T-WriteFamily（R4）：空 Wrapper 写族锚点 + 去重不变量（§4.5）
    //
    // 全部按 collectedAnchors() **整文件锚点集合**断言（不是逐元素）：集合恰好等于预期
    // 才等于「无多余图标」——同一语句多挂一枚时集合会多出元素而转红。
    // ==================================================================

    /** R4 夹具骨架：MP 写族 receiver（`DeviceInfoMapper extends BaseMapper<Entity>`）+ 实体。 */
    private fun configureWriteFamily(body: String, params: String = "") {
        configure(
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void op($params) {
                    $body
                }

                static class DeviceInfoEntity { private Long id; private String name; }

                interface BaseMapper<T> {}

                interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """,
        )
    }

    fun testDeleteNullAnchorsOnDeleteIdentifier() {
        // R4 不变量 `mapper.delete(null)` → 锚点=delete identifier（本券新增）
        configureWriteFamily("deviceInfoMapper.delete(null);")
        assertEquals(
            "delete(null) 只应在 delete 方法名上有一枚锚点",
            setOf("delete"),
            collectedAnchors(),
        )
    }

    fun testDeleteInlineWrapperAnchorsOnlyNewKeyword() {
        // R4 不变量 `mapper.delete(new LambdaQueryWrapper<>()...)` → 锚点=new（场景 A），delete 不得再标
        configureWriteFamily(
            "deviceInfoMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeviceInfoEntity>()" +
                ".eq(DeviceInfoEntity::getId, 1L));",
        )
        assertEquals(
            "delete(内联 Wrapper) 只应在 new 上有一枚锚点（delete 让位给链锚点）",
            setOf("new"),
            collectedAnchors(),
        )
    }

    fun testUpdateNullWrapperAnchorsOnUpdateIdentifier() {
        // R4 不变量 `mapper.update(entity, null)` → 锚点=update identifier（本券新增）
        configureWriteFamily("deviceInfoMapper.update(entity, null);", "DeviceInfoEntity entity")
        assertEquals(
            "update(entity, null) 只应在 update 方法名上有一枚锚点",
            setOf("update"),
            collectedAnchors(),
        )
    }

    fun testUpdateWrapperVariableAnchorsOnlyVariable() {
        // R4 不变量 `mapper.update(entity, wrapperVar)` → 锚点=变量声明行，update 不得再标
        configureWriteFamily(
            "deviceInfoMapper.update(entity, w);",
            "DeviceInfoEntity entity, com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeviceInfoEntity> w",
        )
        assertEquals(
            "update(entity, wrapperVar) 只应在 Wrapper 变量上有锚点（update 让位）",
            setOf("w"),
            collectedAnchors(),
        )
    }

    fun testServiceUpdateSingleArgNotAnchor() {
        // R4 不变量 `service.update(null)`（单参，SQL 本身非法 `UPDATE t SET` 无赋值）→ 零锚点
        configure(
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void op() {
                    deviceInfoService.update(null);
                }

                static class DeviceInfoEntity { private Long id; }

                interface IService<T> {}

                interface DeviceInfoService extends IService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        assertEquals(
            "单参 update(null) 不应产生任何锚点",
            emptySet<String>(),
            collectedAnchors(),
        )
    }

    fun testServiceRemoveNullAnchorsOnRemoveIdentifier() {
        // R4.5 不变量：`service.remove(null)`（IService.remove(Wrapper)，D-4 纳入）→ 锚点=remove identifier。
        // remove 不是 Wrapper 工厂名，走的是场景 D-0 → 场景 D 之前的判定路径。
        configure(
            """
            public class Demo {
                private DeviceInfoService deviceInfoService;

                public void op() {
                    deviceInfoService.remove(null);
                }

                static class DeviceInfoEntity { private Long id; }

                interface IService<T> {}

                interface DeviceInfoService extends IService<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        assertEquals(
            "service.remove(null) 只应在 remove 方法名上有一枚锚点",
            setOf("remove"),
            collectedAnchors(),
        )
    }

    fun testUpdateCastNullWrapperAnchorsOnUpdateIdentifier() {
        // R4 形态门：`update(entity, (Wrapper<X>) null)` 的强转包裹（解一层 PsiTypeCastExpression）仍识别为空 Wrapper
        configure(
            """
            public class Demo {
                private DeviceInfoMapper deviceInfoMapper;

                public void op(DeviceInfoEntity entity) {
                    deviceInfoMapper.update(entity,
                        (com.baomidou.mybatisplus.core.conditions.Wrapper<DeviceInfoEntity>) null);
                }

                static class DeviceInfoEntity { private Long id; private String name; }

                interface BaseMapper<T> {}

                interface DeviceInfoMapper extends BaseMapper<DeviceInfoEntity> {}
            }
            """.trimIndent(),
        )
        assertEquals(
            "强转 null 的 update 也应只锚 update 方法名",
            setOf("update"),
            collectedAnchors(),
        )
    }

    fun testWrapperArgumentYieldPreventsSecondIconOnSelectList() {
        // R4.5 不变量第 3 行（让位逻辑复用）：`mapper.selectList(wrapperVar)` 的语句承载在实参链上，
        // 锚点只由场景 B（变量声明行）承载——批量族让位，同一语句不得出第二枚图标。
        // 该用例同时是「去掉 hasWrapperArgument 让位」反向验证的唯一红点（delete/update 的空 Wrapper
        // 形态门已结构性排除 Wrapper 实参，让位在其上属第二道防线）。
        configureWriteFamily(
            "deviceInfoMapper.selectList(w);",
            "com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DeviceInfoEntity> w",
        )
        assertEquals(
            "selectList(wrapperVar) 只应在 Wrapper 变量上有锚点（批量族让位）",
            setOf("w"),
            collectedAnchors(),
        )
    }
}
