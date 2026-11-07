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
 * WrapperAnchorDetector JPA CRUD 锚点、MPJ 工厂链整文件单锚点、批量操作锚点用例（自 WrapperAnchorDetectorTest 拆出）。
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class WrapperAnchorDetectorJpaAndWholeFileTest: BasePlatformTestCase() {

    fun testJpaCrudReceiverCallAnchors() {
        // T-J1/T-J6/T-J21（场景 E）：JPA 专属方法名在 JPA 家族 receiver 上标锚点——
        // Repository（findById/findAll/count/getOne）、EntityManager（persist）、HibernateTemplate（get）
        configure(
            """
            public class Demo {
                private UserRepository userRepository;
                private javax.persistence.EntityManager em;
                private HibernateTemplate hibernateTemplate;

                public void ops() {
                    userRepository.findById(1L);
                    userRepository.getOne(1L);
                    userRepository.findAll();
                    userRepository.count();
                    em.persist(entity);
                    hibernateTemplate.get(UserEntity.class, 1L);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}
            class HibernateTemplate {}
            class UserEntity { private Long id; }
            """.trimIndent(),
        )
        for (name in listOf("findById", "getOne", "findAll", "count", "persist", "get")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            assertSame(
                "JPA 家族 receiver 上的 $name 应为锚点",
                call.methodExpression.referenceNameElement,
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
    }

    fun testJpaCrudMethodNamesOnPlainReceiverNotAnchors() {
        // T-J1/T-J6 负例守卫（宁可无图标不可误亮）：同名方法在非 JPA receiver 上不标锚点——
        // 普通类 repository（无 Spring Data 基类）/ List.count / Map.get
        configure(
            """
            public class Demo {
                private PlainUserRepository userRepository;
                private java.util.List<String> list;
                private java.util.Map<String, String> map;

                public void ops() {
                    userRepository.findById(1L);
                    userRepository.findAll();
                    list.count();
                    map.get("k");
                }
            }

            class PlainUserRepository {}
            """.trimIndent(),
        )
        for (name in listOf("findById", "findAll", "count", "get")) {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == name }
            assertNull(
                "非 JPA receiver 上的 $name 不应标锚点",
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
    }

    fun testMpAndJpaSaveCollisionBothAnchored() {
        // T-J1 消歧（锚点层面）：save 同时收录于 MP 批量名单（场景 D 名单先行）与 JPA CRUD 名单
        // （场景 E）——MP service 与 JPA repository 上的 save 都有图标（同元素单 marker），
        // 语义分派发生在 preview 层（receiver 家族 → 各自转换器，见 SqlPreviewServiceJpaCrudTest）
        configure(
            """
            public class Demo {
                private MpUserService mpUserService;
                private UserRepository userRepository;

                public void ops() {
                    mpUserService.save(mp);
                    userRepository.save(jpa);
                }
            }

            interface UserRepository extends JpaRepository<JpaEntity, Long> {}
            class JpaRepository<T, ID> {}
            class JpaEntity { private Long id; }
            class MpUserService {}
            """.trimIndent(),
        )
        for (name in listOf("save")) {
            for (call in PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .filter { it.methodExpression.referenceName == name }) {
                assertSame(
                    "撞名方法 save 在两种 receiver 上都应有锚点",
                    call.methodExpression.referenceNameElement,
                    WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
                )
            }
        }
    }

    fun testWrapperChainTerminalCountNotJpaAnchor() {
        // T-J1 负例守卫（撞链终结点）：`service.lambdaQuery()...count()` 的 count 是 wrapper 链终结点，
        // 不标 JPA CRUD 锚点（链锚点由 lambdaQuery 承载，避免双图标）
        configure(
            """
            public class Demo {
                public void query() {
                    long total = service.lambdaQuery().eq(DeviceEntity::getId, 1L).count();
                }
            }
            """.trimIndent(),
        )
        val countCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "count" }
        assertNull(
            "wrapper 链终结点 count 不应标 JPA 锚点",
            WrapperAnchorDetector.anchorElement(countCall.methodExpression.referenceNameElement),
        )
    }

    fun testLambdaParameterWrapperIsNotAnchor() {
        // T-M23.12（用户实测）：`wrapper.and(o -> ...)` 嵌套条件 lambda 参数（显式声明 wrapper 类型，
        // LightPlatform 可解析类型文本）不应标锚点——真实 IDE 推断类型为 LambdaQueryWrapper 会被
        // 场景 B 命中产生多余图标；方法参数锚点（testMethodParameterWrapperAnchors）不受影响
        configure(
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<DeviceEntity> wrapper = new LambdaQueryWrapper<DeviceEntity>();
                    wrapper.and((LambdaQueryWrapper<DeviceEntity> o) -> o.eq(DeviceEntity::getId, 1L));
                }

                static class DeviceEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val variable = firstVariable()
        assertSame(
            "wrapper 变量行锚点应保留（图标不丢）",
            variable.nameIdentifier,
            WrapperAnchorDetector.anchorElement(variable.nameIdentifier),
        )
        val lambdaParam = PsiTreeUtil.findChildOfType(myFixture.file, PsiParameter::class.java)!!
        assertNull(
            "lambda 参数不应是锚点（嵌套条件非独立 wrapper 变量）",
            WrapperAnchorDetector.anchorElement(lambdaParam.nameIdentifier),
        )
    }

    /**
     * 整体不变量：用户真实 MPJ 工厂链形态——wrapper 链本身恰好 1 个锚点（wrapper 变量声明），
     * **MPJ Maps 终止调用按 T-MapResultVo S4「批量直调登记」独立承载**（与经典 MP
     * `mapper.selectList(wrapper)` / `selectMaps(wrapper)` 同族：声明行与调用行各一锚点，
     * 长年行为；`ee090f7` 把 selectJoinMaps/selectJoinMapsPage 注册进批量注册表后
     * 三个 Maps 形态用例由此转红，20261007 复核判定为契约更新而非回归）。
     *
     * T-Mxx（用户实测回归）：`wrapper = JoinWrappers.lambda(...).selectAll(...).leftJoin(...)` 曾出现双图标——
     * 链上 selectAll 撞批量注册表（场景 D），initializerHeadIsWrapper 回溯停在 qualifier 引用返回 false
     * 未去重。逐个方法名断言（lambda 去重）无法暴露此问题；本用例扫描全文件叶子断言锚点集合，
     * **链内方法（selectAll/lambda/leftJoin 等）任何多余锚点都会失败**（不变量所在）；
     * 终止调用（Maps 族）的独立锚点已按上述口径计入期望集合。
     */
    fun testMpjFactoryChainWholeFileSingleAnchorRightAlias() {
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public Map<Object, Object> query() {
                    MPJLambdaWrapper<DeviceInfoEntity> wrapper = JoinWrappers.lambda(DeviceInfoEntity.class)
                            .selectAll(DeviceInfoEntity.class)
                            .select(StationInfoEntity::getStationName)
                            .leftJoin(StationInfoEntity.class, "st",
                                    StationInfoEntity::getStationId, "t", DeviceInfoEntity::getId)
                            .eq(DeviceInfoEntity::getId, 1);
                    return deviceInfoMapper.selectJoinMaps(wrapper);
                }

                static class DeviceInfoEntity { private Long id; }
                static class StationInfoEntity { private Long stationId; private String stationName; }
            }
            """.trimIndent(),
        )
        assertEquals(
            "MPJ 工厂链（链内单锚点）＋ Maps 终止调用独立锚点",
            setOf("wrapper", "selectJoinMaps"),
            collectedAnchors(),
        )
    }

    fun testMpjFactoryChainWholeFileSingleAnchorJoinExt() {
        // joinExtAndSubTable：lambda 参数子表 + ext 追加 ON 条件的细分形态
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public Object query() {
                    MPJLambdaWrapper<DeviceInfoEntity> wrapper = JoinWrappers.lambda(DeviceInfoEntity.class)
                            .selectAll(DeviceInfoEntity.class)
                            .leftJoin(StationInfoEntity.class,
                                    StationInfoEntity::getId, DeviceInfoEntity::getStationId,
                                    ext -> ext.eq(StationInfoEntity::getStationName, "中心站"))
                            .leftJoin(StationInfoEntity.class, t -> t
                                            .setAlias("tt")
                                            .selectAll()
                                            .ge(StationInfoEntity::getMerchantId, 0),
                                    StationInfoEntity::getStationId, DeviceInfoEntity::getStationId)
                            .eq(DeviceInfoEntity::getId, 1);
                    return deviceInfoMapper.selectJoinMaps(wrapper);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }
                static class StationInfoEntity { private Long stationId; private Long id; private String stationName; private Integer merchantId; }
            }
            """.trimIndent(),
        )
        assertEquals(
            "MPJ 链内单锚点＋ Maps 终止调用独立锚点",
            setOf("wrapper", "selectJoinMaps"),
            collectedAnchors(),
        )
    }

    fun testMpjFactoryChainWholeFileSingleAnchorFromPage() {
        // joinFromAndPage：from(子查询) + setTableName + around + pageByMain 细分形态
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public Object query() {
                    MPJLambdaWrapper<DeviceInfoEntity> wrapper = JoinWrappers.lambda(DeviceInfoEntity.class)
                            .selectAll()
                            .from(from -> from
                                    .selectAll()
                                    .ge(DeviceInfoEntity::getId, 0)
                                    .last("LIMIT 100"))
                            .leftJoin(StationInfoEntity.class, StationInfoEntity::getStationId, DeviceInfoEntity::getStationId)
                            .setTableName(name -> name + "_archive")
                            .pageByMain()
                            .around("/* mpj hint */ ", " /* end */");
                    return deviceInfoMapper.selectJoinMapsPage(wrapper);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }
                static class StationInfoEntity { private Long stationId; }
            }
            """.trimIndent(),
        )
        assertEquals(
            "MPJ 链内单锚点＋ Maps 分页终止调用独立锚点",
            setOf("wrapper", "selectJoinMapsPage"),
            collectedAnchors(),
        )
    }

    fun testMpjFactoryChainWholeFileSingleAnchorOneAndCount() {
        // joinOneAndCount：selectJoinMap + selectJoinCount + 实体返回细分形态
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public Object query(Integer id) {
                    MPJLambdaWrapper<DeviceInfoEntity> wrapper = JoinWrappers.lambda(DeviceInfoEntity.class)
                            .selectAll(DeviceInfoEntity.class)
                            .select(StationInfoEntity::getStationName, StationInfoEntity::getMerchantId)
                            .leftJoin(StationInfoEntity.class, StationInfoEntity::getStationId, DeviceInfoEntity::getStationId)
                            .eq(DeviceInfoEntity::getId, id);
                    Object one = deviceInfoMapper.selectJoinMap(wrapper);
                    return deviceInfoMapper.selectJoinCount(wrapper);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; }
                static class StationInfoEntity { private Long stationId; private String stationName; private Integer merchantId; }
            }
            """.trimIndent(),
        )
        assertEquals("joinOneAndCount 形态应恰好 1 个锚点", setOf("wrapper"), collectedAnchors())
    }

    fun testMpjFactoryChainWholeFileSingleAnchorListEntity() {
        // joinListEntity：实体返回 + orderByAsc 细分形态
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;
            import com.github.yulichang.wrapper.MPJLambdaWrapper;

            public class Demo {
                public Object query() {
                    MPJLambdaWrapper<DeviceInfoEntity> wrapper = JoinWrappers.lambda(DeviceInfoEntity.class)
                            .selectAll(DeviceInfoEntity.class)
                            .leftJoin(StationInfoEntity.class, StationInfoEntity::getStationId, DeviceInfoEntity::getStationId)
                            .eq(DeviceInfoEntity::getShareSchemeId, 1)
                            .orderByAsc(DeviceInfoEntity::getId);
                    return deviceInfoMapper.selectJoinList(DeviceInfoEntity.class, wrapper);
                }

                static class DeviceInfoEntity { private Long id; private Long stationId; private Long shareSchemeId; }
                static class StationInfoEntity { private Long stationId; }
            }
            """.trimIndent(),
        )
        assertEquals("joinListEntity 形态应恰好 1 个锚点", setOf("wrapper"), collectedAnchors())
    }

    // ------------------------------------------------------------------
    // T-DaoSql-03：字符串 SQL 形态让位（场景 D 与字符串 SQL 路径互斥）
    // ------------------------------------------------------------------

    fun testBatchOperation_stringSqlArg_yieldsToStringSqlProvider() {
        // `beanCruder.selectList(X.class, sql, ...)`：selectList 同时收录于批量注册表与
        // 字符串 SQL 方法候选，实参含字符串形态（sql 为 String 变量）→ 场景 D 让位（不标批量锚点）
        configure(
            """
            public class Demo {
                public void query(long userId) {
                    String sql = "SELECT wp.PROC_NAME FROM WKFL_TASK WT WHERE WT.OWNER = :CUR_USER ";
                    beanCruder.selectList(ScheduleVO.class, sql, "CUR_USER", userId);
                }

                static class ScheduleVO { private String taskName; }
            }
            """.trimIndent(),
        )
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "selectList" }
        assertNull(
            "字符串 SQL 形态的批量方法名调用应让位给字符串 SQL provider",
            WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
        )
    }

    fun testBatchOperation_collectionArg_regression_stillAnchors() {
        // 回归：集合/实体实参（无字符串形态）的批量调用照常标注
        configure(
            """
            import java.util.List;

            public class Demo {
                public void save(List<DeviceEntity> list) {
                    beanCruder.updateBatchById(list);
                }

                static class DeviceEntity { private Long id; }
            }
            """.trimIndent(),
        )
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "updateBatchById" }
        assertSame(
            "集合实参的批量调用不受让位守卫影响",
            call.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
        )
    }

    fun testNewExpressionQualifierChainCallIsNotAnchor() {
        // M38.1（负例回归，Flex-Helper「图标误显示」差评源）：`new Helper().lambdaQuery().eq(...)` ——
        // 任意普通类实例的撞名调用不是 MP 服务链（服务链 qualifier 是变量/字段/this 引用），
        // lambdaQuery 方法名不得因场景 C 服务链分支而误标锚点
        configure(
            """
            public class Demo {
                public void query() {
                    new Helper().lambdaQuery().eq("a", 1);
                }

                static class Helper {
                    public Helper lambdaQuery() { return this; }
                    public Helper eq(String column, Object val) { return this; }
                }
            }
            """.trimIndent(),
        )
        val chainCall = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "lambdaQuery" }
        assertNull(
            "new 表达式 qualifier 的撞名工厂调用不应标服务链锚点",
            WrapperAnchorDetector.anchorElement(chainCall.methodExpression.referenceNameElement),
        )
        assertEquals("普通类实例撞名链不应产生任何锚点", emptySet<String>(), collectedAnchors())
    }

    // ------------------------------------------------------------------
    // T-JpaDeleteAnchor：与 Wrapper 工厂/链方法撞名的 JPA 写方法锚点
    // ------------------------------------------------------------------

    fun testJpaRepositoryDeleteAnchored() {
        // T-JpaDeleteAnchor：`delete` 同时是 Wrapper 工厂名，场景 C 旧实现直接 return null（在场景 E 之前），
        // 于是 repository.delete(entity) 只有预览、没有 gutter 入口。此处钉住新增的 JPA 分支。
        configure(
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.delete(entity);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}
            class UserEntity { private Long id; }
            """,
        )
        val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "delete" }
        assertSame(
            "JPA Repository 上的 delete 应为锚点",
            call.methodExpression.referenceNameElement,
            WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
        )
        assertEquals(
            "整文件只有 delete 一枚锚点",
            setOf("delete"),
            collectedAnchors(),
        )
    }

    fun testHibernateTemplateDeleteAndUpdateAnchored() {
        // T-JpaDeleteAnchor：`ht.delete(entity)` / `ht.update(entity)`——update 同时是
        // Wrapper 工厂名与服务链方法名，两者都要经 JPA 分支才可达。
        configure(
            """
            public class Demo {
                private HibernateTemplate hibernateTemplate;

                public void ops() {
                    hibernateTemplate.delete(entity);
                    hibernateTemplate.update(entity);
                }
            }

            class HibernateTemplate {}
            class UserEntity { private Long id; }
            """,
        )
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        for (name in listOf("delete", "update")) {
            val call = calls.first { it.methodExpression.referenceName == name }
            assertSame(
                "HibernateTemplate 上的 $name 应为锚点",
                call.methodExpression.referenceNameElement,
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
        assertEquals(
            "整文件恰好 delete/update 两枚锚点",
            setOf("delete", "update"),
            collectedAnchors(),
        )
    }

    fun testJoinWrappersDeleteUpdateStayFactoryOnly() {
        // T-JpaDeleteAnchor 去重不变量（硬性）：delete/update 是 JoinWrappers 工厂方法名，
        // 工厂分支必须优先——JPA 分支不得抢跑，也不得在同一语句上再补一枚。
        configure(
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void ops() {
                    JoinWrappers.delete(Entity.class);
                    JoinWrappers.update(Entity.class);
                }

                static class Entity { private Long id; }
            }
            """,
        )
        assertEquals(
            "JoinWrappers.delete/update 只锚工厂方法名",
            setOf("delete", "update"),
            collectedAnchors(),
        )
        // 元素级：恰好 2 个锚点元素，且都落在被调方法名上（qualifier `JoinWrappers` 不算）
        val anchorElements = collectedAnchorElements()
        assertEquals("整文件恰好 2 个锚点元素", 2, anchorElements.size)
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        val methodNameElements = calls.map { it.methodExpression.referenceNameElement }.toSet()
        assertTrue(
            "锚点元素必须是被调方法名（工厂名），不出现第二枚",
            anchorElements.all { it in methodNameElements },
        )
    }

    fun testServiceUpdateVariantsNotAnchored() {
        // T-JpaDeleteAnchor 负例守卫：`update` 撞名面最大——
        // `service.update(wrapper)`（消费调用）与 `service.lambdaUpdate()...update()`（链终结点）
        // 都不是 JPA 家族，必须保持不锚 update。
        configure(
            """
            public class Demo {
                private Object wrapper;

                public void ops() {
                    service.update(wrapper);
                    service.lambdaUpdate().eq("id", 1).update();
                }
            }
            """,
        )
        val updateCalls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == "update" }
        assertEquals("两种 update 形态都在夹具内", 2, updateCalls.size)
        for (call in updateCalls) {
            assertNull(
                "非 JPA receiver 的 update 不应标锚点",
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
        assertEquals(
            "整文件只剩链起点 lambdaUpdate 一枚锚点",
            setOf("lambdaUpdate"),
            collectedAnchors(),
        )
    }

    fun testJpaExistingAnchorsKept() {
        // T-JpaDeleteAnchor 回归：本券不改既有形态——em.remove(entity)（场景 E）与
        // repository.deleteById(id)（场景 E）的锚点必须保持。
        configure(
            """
            public class Demo {
                private UserRepository userRepository;
                private javax.persistence.EntityManager em;

                public void ops() {
                    em.remove(entity);
                    userRepository.deleteById(1L);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}
            class UserEntity { private Long id; }
            """,
        )
        val calls = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
        for (name in listOf("remove", "deleteById")) {
            val call = calls.first { it.methodExpression.referenceName == name }
            assertSame(
                "既有 JPA 锚点 $name 不应丢失",
                call.methodExpression.referenceNameElement,
                WrapperAnchorDetector.anchorElement(call.methodExpression.referenceNameElement),
            )
        }
        assertEquals(
            "整文件恰好 remove/deleteById 两枚锚点",
            setOf("remove", "deleteById"),
            collectedAnchors(),
        )
    }

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

    /**
     * 元素级锚点扫描（T-JpaDeleteAnchor）：同 [collectedAnchors]，但返回元素本身。
     * 集合断言按文本去重，抓不到「同一语句两枚同文本图标」之外的形态差异；
     * 元素级断言用来钉住「锚点个数恰好」与「锚点落在被调方法名而非 qualifier」。
     */
    private fun collectedAnchorElements(): List<com.intellij.psi.PsiElement> {
        val anchors = mutableListOf<com.intellij.psi.PsiElement>()
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .forEach { WrapperAnchorDetector.anchorElement(it)?.let { a -> anchors += a } }
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiKeyword::class.java)
            .forEach { WrapperAnchorDetector.anchorElement(it)?.let { a -> anchors += a } }
        return anchors
    }
}
