package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * D256-M3：MPJ join 消费族（`selectJoinList` / `selectJoinMaps` / …）的**错误 SQL 守卫**。
 *
 * ## 背景
 *
 * [BatchOperationConverter] 的批量 SELECT 模板只会产出 `SELECT 全列 FROM 表 WHERE id IN (?, ?, ...)`；
 * 而 join 族的条件与列全在 wrapper 里，首参 `XxxDTO.class` 还是**响应类型**而非实体表。
 * 一旦 wrapper 链路径没命中、批量模板接手，预览会输出比无预览更糟的错误 SQL
 * （`FROM user_dto` + `WHERE id IN`）。本类钉住守卫的三条拒绝形态与两条回归：
 *
 *  1. `selectJoinMaps(未知 wrapper)` → 不产 SQL（wrapper 位不可识别）；
 *  2. `selectJoinList(UserDTO.class, 已知 wrapper)` → 不产 SQL（首参 DTO 后缀，**独立于** wrapper 判定）；
 *  3. `selectJoinList(UserEntity.class, 未知 wrapper)` → 不产 SQL（实体首参也救不了不可识别的 wrapper）；
 *  4. `getById(1L)` 仍产 SQL（非 join 族不受影响）；
 *  5. `selectList(已知 wrapper)` **不**产批量 SQL（D264 修0 翻转：原「不受影响」断言正是无 WHERE 错误 SQL 的入口）。
 *
 * 夹具内联伪造 `BaseMapper` / `IService` / `LambdaQueryWrapper`，仅提供类型与父链，
 * 不依赖真实 MP/MPJ jar（LightPlatform 无依赖索引）。`UserEntity` 以 `Entity` 后缀命中
 * [BatchOperationConverter] 的 `isLikelyEntity`，`UserDTO` 同时命中 DTO 后缀规则。
 *
 * 反向验证（实施记录）：摘掉 `isJoinFamilyRejected` 分支并临时把 join 族登记进注册表
 * （即本守卫要防的「将来登记」形态）→ 用例 1/2/3 转红；还原后复绿。
 * D264 修0 反向验证：摘掉 [BatchOperationConverter.isWrapperArgumentUnsupportedBatch] 的
 * 类型门 → 用例 5/5b 转红（重新产 `SELECT 全列 FROM 表`，无 WHERE）；还原后复绿。
 */
class BatchOperationJoinGuardTest : BasePlatformTestCase() {

    private fun convert(methodName: String, code: String) =
        myFixture.configureByText("JoinGuardDemo.java", code.trimIndent()).let {
            val call = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
                .first { it.methodExpression.referenceName == methodName }
            BatchOperationConverter.convert(call)
        }

    /** 1. `selectJoinMaps` 已在批量注册表，wrapper 不可识别时不得退化成 `WHERE id IN`。 */
    fun testJoinMapsWithUnrecognizedWrapperProducesNoSql() {
        val preview = convert("selectJoinMaps", """
            class Demo {
                void run(JoinMapper mapper, CustomWrapper<UserEntity> w) {
                    mapper.selectJoinMaps(w);
                }
            }
            interface BaseMapper<T> {}
            interface JoinMapper extends BaseMapper<UserEntity> {}
            class UserEntity {
                private Long id;
                private String name;
            }
            class CustomWrapper<T> {}
        """)
        assertNull("wrapper 类型不可识别时必须无预览（否则产出 WHERE id IN 的错误 SQL）", preview)
    }

    /** 2. 首参 `UserDTO.class` 是响应类型：即使 wrapper 是已知类名，也不得当实体表解析。 */
    fun testJoinListWithDtoClassFirstArgProducesNoSql() {
        val preview = convert("selectJoinList", """
            class Demo {
                void run(JoinMapper mapper, LambdaQueryWrapper<UserEntity> w) {
                    mapper.selectJoinList(UserDTO.class, w);
                }
            }
            interface BaseMapper<T> {}
            interface JoinMapper extends BaseMapper<UserEntity> {}
            class UserEntity {
                private Long id;
                private String name;
            }
            class UserDTO {
                private Long id;
                private String name;
            }
            class LambdaQueryWrapper<T> {}
        """)
        assertNull("首参 DTO 不是实体表，必须无预览", preview)
    }

    /** 3. 首参是实体也救不了不可识别的 wrapper：join 条件全在 wrapper 里。 */
    fun testJoinListWithEntityClassButUnrecognizedWrapperProducesNoSql() {
        val preview = convert("selectJoinList", """
            class Demo {
                void run(JoinMapper mapper, CustomWrapper<UserEntity> w) {
                    mapper.selectJoinList(UserEntity.class, w);
                }
            }
            interface BaseMapper<T> {}
            interface JoinMapper extends BaseMapper<UserEntity> {}
            class UserEntity {
                private Long id;
                private String name;
            }
            class CustomWrapper<T> {}
        """)
        assertNull("wrapper 不可识别时 join 族必须无预览", preview)
    }

    /** 4. 回归：非 join 族的主键查询照常产 SQL。 */
    fun testGetByIdStillProducesSql() {
        val preview = convert("getById", """
            class Demo {
                void run(UserService service) {
                    service.getById(1L);
                }
            }
            interface IService<T> {}
            interface UserService extends IService<UserEntity> {}
            class UserEntity {
                private Long id;
                private String name;
            }
        """)
        assertNotNull("getById 不属于 join 族，守卫不得影响", preview)
        assertTrue("主键等值条件应在 SQL 中：${preview!!.sql}", preview.sql.contains("WHERE"))
    }

    /**
     * 5. D264 修0（**契约翻转**）：`selectList(已知 wrapper)` 在批量模板里**不再产 SQL**。
     *
     * 原用例断言「selectList 不受 join 族守卫影响 → 产全列查询」。D264 R10 实测证明这正是最坏面：
     * 批量模板产出 `SELECT id, status, device_name FROM device_info_entity`（**无 WHERE**），
     * 而 wrapper 携带的条件全部丢失、且无任何提示——用户会以为该查询本来就没有过滤条件。
     * 修0 的口径与 join 族一致：实参静态可见是 Wrapper 时，批量模板一律拒绝
     * （SQL 只能由 Wrapper 链路径渲染；链路径失败时由 `SqlPreviewService` 给出明确提示）。
     */
    fun testSelectListWithKnownWrapperIsRejectedByBatchTemplate() {
        val preview = convert("selectList", """
            class Demo {
                void run(UserService service, LambdaQueryWrapper<UserEntity> w) {
                    service.selectList(w);
                }
            }
            interface IService<T> {}
            interface UserService extends IService<UserEntity> {}
            class UserEntity {
                private Long id;
                private String name;
            }
            class LambdaQueryWrapper<T> {}
        """)
        assertNull("Wrapper 实参不得由批量模板产出无 WHERE 的全列 SQL（D264 修0）", preview)
    }

    /** 5b. D264 修0：内联 `new LambdaQueryWrapper<>()` 实参同被拒绝（不是只有变量形态）。 */
    fun testSelectListWithInlineNewWrapperIsRejectedByBatchTemplate() {
        val preview = convert("selectList", """
            class Demo {
                void run(UserService service) {
                    service.selectList(new LambdaQueryWrapper<UserEntity>());
                }
            }
            interface IService<T> {}
            interface UserService extends IService<UserEntity> {}
            class UserEntity {
                private Long id;
                private String name;
            }
            class LambdaQueryWrapper<T> {}
        """)
        assertNull("内联 new Wrapper 实参同样不得退化到批量模板", preview)
    }

    /**
     * 5c. D264 修0 反向守卫：实参**静态看不出是 Wrapper**（`Object` 声明）时批量模板照常工作——
     * 守卫是类型级判据，不是「任何实参都拒绝」。
     */
    fun testSelectListWithNonWrapperArgumentStillProducesSql() {
        val preview = convert("selectList", """
            class Demo {
                void run(UserService service, Object cond) {
                    service.selectList(cond);
                }
            }
            interface IService<T> {}
            interface UserService extends IService<UserEntity> {}
            class UserEntity {
                private Long id;
                private String name;
            }
        """)
        assertNotNull("非 Wrapper 实参不触发修0 守卫（否则批量能力整体失效）", preview)
        assertTrue("全列查询应含 FROM：${preview!!.sql}", preview.sql.contains("FROM"))
    }

    /**
     * 6. D256 收口：join 族在批量模板里**一律拒绝**——即使首参是实体、wrapper 已识别，
     * 也不产出 `SELECT 全列 ... WHERE id IN (...)`（SQL 只由 wrapper 链路径渲染）。
     */
    fun testJoinFamilyRejectedEvenWithEntityAndKnownWrapper() {
        val preview = convert("selectJoinList", """
            class Demo {
                void run(JoinMapper mapper, LambdaQueryWrapper<UserEntity> w) {
                    mapper.selectJoinList(UserEntity.class, w);
                }
            }
            interface BaseMapper<T> {}
            interface JoinMapper extends BaseMapper<UserEntity> {}
            class UserEntity {
                private Long id;
                private String name;
            }
            class LambdaQueryWrapper<T> {}
        """)
        assertNull("join 族 SQL 只由 wrapper 链渲染，批量模板一律拒绝", preview)
    }
}
