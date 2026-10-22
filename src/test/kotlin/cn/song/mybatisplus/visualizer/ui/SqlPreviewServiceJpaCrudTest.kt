package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.psi.jpa.DerivedMethodLocator
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * JPA CRUD / 写路径端到端预览测试（T-J1 Spring Data CRUD + T-J6 EntityManager + T-J21 HibernateTemplate + T-M2 @KeySequence + T-J4 IN 参数）。
 *
 * 覆盖：
 *  - Repository CRUD：save/findById/findAll/count/existsById/deleteById/deleteAll 族 → 正确表列（@Table）与语义注释
 *  - 消歧：userRepository.save（JPA 家族 → JpaEntityResolver，修错表名）vs service.save（MP 家族 → @TableName）
 *  - EntityManager：persist/merge/remove/find/getReference/refresh/createStoredProcedureQuery
 *  - HibernateTemplate：get/load/save/update/delete/executeFind（ENTITY_OP / QBC 占位）/ findByNamedQuery 回查
 *  - 负例守卫：普通类 repository / List.count() / Map.get() 不识别
 */
class SqlPreviewServiceJpaCrudTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        // T-TenantLine 开关隔离（对齐 SqlPreviewServiceBatchTest tearDown 惯例）：
        // JPA/MP INSERT 断言不含租户列
        PluginSettings.getInstance().state.injectTenantLine = false
    }

    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.dialect = PluginSettings.DEFAULT_DIALECT
        super.tearDown()
    }

    /** 按方法名取文件内第一个方法调用表达式。 */
    private fun call(name: String): PsiMethodCallExpression =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == name }

    /** 按方法名取文件内全部方法调用表达式（撞名消歧用例）。 */
    private fun calls(name: String): List<PsiMethodCallExpression> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == name }

    // ------------------------------------------------------------------
    // T-J1：Spring Data Repository CRUD
    // ------------------------------------------------------------------

    fun testRepositoryFindByIdRendersJpaTableSelect() {
        // T-J1（核心修复验证）：receiver 为 Spring Data Repository 时实体走 JpaEntityResolver——
        // @Table(name="t_user") 表名与列名正确（撞名 save 曾被 MP 路径输出 user_entity 错表）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.findById(1001L);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("findById"))
        assertNotNull("findById 应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertEquals(
            """
            SELECT id, user_name
            FROM t_user
            WHERE id = 1001
            """.trimIndent(),
            result.sql,
        )
        // 字面量实参内联（对齐 MP 批量路径 T-M23-02 语义），无参数行
        assertTrue(result.unresolvedParams.isEmpty())
    }

    fun testRepositorySaveRendersInsertWithJpaColumns() {
        // T-J1：save(entity) → INSERT 模板（@Table/@Column 列映射 + 实体变量.列名 参数行，对齐 MP 面板惯例）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void save(UserEntity entity) {
                    userRepository.save(entity);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
                private Integer dicStatus;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("save"))
        assertNotNull("save 应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertEquals(
            "INSERT INTO t_user (id, user_name, dic_status)\nVALUES (?, ?, ?)\n-- save：isNew 判定——新实体 INSERT / 已存在 UPDATE（静态统一按 INSERT 呈现）",
            result.sql,
        )
        assertEquals(
            listOf("entity.id", "entity.user_name", "entity.dic_status"),
            result.unresolvedParams,
        )
        // 数值列无引号、字符串列引号（ParamQuote 惯例）
        assertEquals(listOf(false, true, false), result.paramHints.map { it.quote })
        assertNotNull("INSERT 应携带列勾选模型", result.batchModel)
    }

    fun testRepositorySaveIdentityIdExcludedFromInsert() {
        // T-J1/J14 基础形态：@GeneratedValue(IDENTITY) 主键不进 INSERT 列清单 + 注释说明
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void save(UserEntity entity) {
                    userRepository.save(entity);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                @javax.persistence.GeneratedValue(strategy = javax.persistence.GenerationType.IDENTITY)
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("save"))
        assertNotNull(result)
        assertEquals(
            """
            INSERT INTO t_user (user_name)
            VALUES (?)
            -- 主键 id：@GeneratedValue(IDENTITY) 数据库自增，不进 INSERT 列清单
            -- save：isNew 判定——新实体 INSERT / 已存在 UPDATE（静态统一按 INSERT 呈现）
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(listOf("entity.user_name"), result.unresolvedParams)
    }

    fun testRepositorySaveSequenceIdKeepsPlaceholderWithPreInsertNote() {
        // T-J1/J14：@GeneratedValue(SEQUENCE) 主键保留占位 + 前置取值注释
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void save(UserEntity entity) {
                    userRepository.save(entity);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                @javax.persistence.GeneratedValue(strategy = javax.persistence.GenerationType.SEQUENCE)
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("save"))
        assertNotNull(result)
        assertEquals(
            """
            INSERT INTO t_user (id, user_name)
            VALUES (?, ?)
            -- 主键 id：@GeneratedValue(SEQUENCE)——INSERT 前置取值（select nextval），占位 ? 由应用回填
            -- save：isNew 判定——新实体 INSERT / 已存在 UPDATE（静态统一按 INSERT 呈现）
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testRepositoryFindAllCount() {
        // T-J1：findAll() 全列无 WHERE / count() 计数
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.findAll();
                    userRepository.count();
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        assertEquals(
            """
            SELECT id, user_name
            FROM t_user
            """.trimIndent(),
            newService().preview(call("findAll"))!!.sql,
        )
        assertEquals(
            "SELECT COUNT(*)\nFROM t_user",
            newService().preview(call("count"))!!.sql,
        )
    }

    fun testRepositoryExistsByIdSelectOneLimitSemantics() {
        // T-J1：existsById → SELECT 1 ... LIMIT 1 语义 + 语义注释
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.existsById(1001L);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("existsById"))!!
        assertEquals(
            """
            SELECT 1
            FROM t_user
            WHERE id = 1001
            LIMIT 1
            -- existsById：命中即真语义（按当前方言只取首行，运行时语句以实际日志为准）
            """.trimIndent(),
            result.sql,
        )
    }

    fun testRepositoryGetReferenceLazyAndDeprecatedNotes() {
        // T-J1：getReferenceById 延迟代理标注；getOne/getById Deprecated 迁移说明（同为 SELECT 主键等值模板）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.getReferenceById(1L);
                    userRepository.getOne(1L);
                    userRepository.getById(1L);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        val reference = newService().preview(call("getReferenceById"))!!
        assertTrue(
            "getReferenceById 应带延迟代理标注: ${reference.sql}",
            reference.sql.contains("延迟代理"),
        )
        assertTrue(
            "getOne 应带 Deprecated 迁移说明: ${reference.sql}",
            newService().preview(call("getOne"))!!.sql.contains("已废弃"),
        )
        assertTrue(
            "getById 应带 Deprecated 迁移说明",
            newService().preview(call("getById"))!!.sql.contains("已废弃"),
        )
    }

    fun testRepositoryFlushNoteOnlyCard() {
        // T-J1：flush() 无独立 SQL——脏检查刷写提示卡
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.flush();
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
            }
            """.trimIndent(),
        )
        val result = newService().preview(call("flush"))
        assertNotNull("flush 应产出提示卡", result)
        assertTrue(result!!.sql.startsWith("--"))
        assertTrue(result.sql.contains("无独立 SQL"))
    }

    fun testRepositoryDeleteFamilyTemplates() {
        // T-J1：deleteById 主键等值 / deleteAllById IN / deleteAll() 全表 + BlockAttack 警示 /
        // deleteAllByIdInBatch bulk 语义标注
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.deleteById(1001L);
                    userRepository.deleteAllById(ids);
                    userRepository.deleteAll();
                    userRepository.deleteAllByIdInBatch(ids);
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }
            """.trimIndent(),
        )
        val deleteById = newService().preview(call("deleteById"))!!
        assertEquals(
            """
            DELETE FROM t_user
            WHERE id = 1001
            """.trimIndent(),
            deleteById.sql.substringBefore("\n-- "),
        )
        assertTrue(deleteById.sql.contains("逐实体"))

        val deleteAllById = newService().preview(call("deleteAllById"))!!
        assertEquals(
            """
            DELETE FROM t_user
            WHERE id IN (?, ?, ...)
            """.trimIndent(),
            deleteAllById.sql.substringBefore("\n-- "),
        )

        val deleteAll = newService().preview(call("deleteAll"))!!
        assertEquals("DELETE FROM t_user", deleteAll.sql.substringBefore("\n-- "))
        assertNotNull("全表删除应有 BlockAttack 警示", deleteAll.warning)

        val inBatch = newService().preview(call("deleteAllByIdInBatch"))!!
        assertEquals(
            """
            DELETE FROM t_user
            WHERE id IN (?, ?, ...)
            -- deleteAllByIdInBatch：bulk DELETE（单条 IN 语句、绕过级联与回调）
            """.trimIndent(),
            inBatch.sql,
        )
    }

    // ------------------------------------------------------------------
    // T-J1 消歧（receiver 家族判定优先级的行为验证）
    // ------------------------------------------------------------------

    fun testJpaRepositorySaveDisambiguatedFromMpServiceSave() {
        // T-J1 消歧核心用例：userRepository.save → JpaEntityResolver（@Table 表名 t_jpa），
        // service.save → MP EntityResolver（@TableName 表名 t_mp）——同名方法按 receiver 家族各走各路，
        // 修复「JPA 实体被输出 user_entity 错表」的误导行为
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;
                private MpUserService mpUserService;

                public void ops(JpaEntity jpa, MpEntity mp) {
                    userRepository.save(jpa);
                    mpUserService.save(mp);
                }
            }

            interface UserRepository extends JpaRepository<JpaEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_jpa")
            class JpaEntity {
                @javax.persistence.Id
                private Long id;
                private String userName;
            }

            @com.baomidou.mybatisplus.annotation.TableName("t_mp")
            class MpEntity {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;
                private String userName;
            }

            class BaseIService<T> {}
            class MpUserService extends BaseIService<MpEntity> {}
            """.trimIndent(),
        )
        val saveCalls = calls("save")
        assertEquals("fixture 应含两处 save 调用", 2, saveCalls.size)
        val results = saveCalls.map { newService().preview(it) }
        assertTrue("两处 save 均应产出预览", results.all { it != null })
        // userRepository.save（JPA 家族）→ @Table 表名
        val jpaResult = results.first { it!!.tableName == "t_jpa" }!!
        assertEquals(
            "INSERT INTO t_jpa (id, user_name)\nVALUES (?, ?)\n-- save：isNew 判定——新实体 INSERT / 已存在 UPDATE（静态统一按 INSERT 呈现）",
            jpaResult.sql,
        )
        // mpUserService.save（MP 家族）→ @TableName 表名
        val mpResult = results.first { it!!.tableName == "t_mp" }!!
        assertEquals(
            "INSERT INTO t_mp (id, user_name)\nVALUES (?, ?)",
            mpResult.sql,
        )
    }

    fun testMpServiceSaveStillRendersMpInsert() {
        // T-J1 回归（MP 存量行为零变化）：MP service.save（IService 泛型实体）仍走 MP 批量路径
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private MpUserService mpUserService;

                public void save(MpEntity entity) {
                    mpUserService.save(entity);
                }
            }

            @com.baomidou.mybatisplus.annotation.TableName("t_mp")
            class MpEntity {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;
                private String userName;
            }

            class BaseIService<T> {}
            class MpUserService extends BaseIService<MpEntity> {}
            """.trimIndent(),
        )
        val result = newService().preview(call("save"))
        assertNotNull(result)
        assertEquals(
            "INSERT INTO t_mp (id, user_name)\nVALUES (?, ?)",
            result!!.sql,
        )
    }

    fun testPlainRepositoryReceiverIsNotRecognized() {
        // T-J1 负例守卫：普通类 repository 变量（无 Spring Data 基类）的 JPA 专属方法名不识别
        // （宁可无图标，不可出错误 SQL）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private PlainUserRepository userRepository;

                public void ops() {
                    userRepository.findById(1001L);
                    userRepository.findAll();
                }
            }

            class PlainUserRepository {}

            class UserEntity {
                private Long id;
            }
            """.trimIndent(),
        )
        assertNull(newService().preview(call("findById")))
        assertNull(newService().preview(call("findAll")))
    }

    fun testListCountAndMapGetAreNotRecognized() {
        // T-J1 负例守卫：通用名 count/get 在非 JPA receiver 上不识别
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private java.util.List<String> list;
                private java.util.Map<String, String> map;

                public void ops() {
                    list.count();
                    map.get("k");
                }
            }
            """.trimIndent(),
        )
        assertNull(newService().preview(call("count")))
        assertNull(newService().preview(call("get")))
    }

    // ------------------------------------------------------------------
    // T-J6：EntityManager 写/单查
    // ------------------------------------------------------------------

    private val entityManagerFixture = """
        public class Demo {
            private javax.persistence.EntityManager em;

            public void ops(UserEntity entity) {
                em.persist(entity);
                em.merge(entity);
                em.remove(entity);
                em.find(UserEntity.class, 1001L);
                em.getReference(UserEntity.class, 1001L);
                em.refresh(entity);
                em.createStoredProcedureQuery("proc_user_stat");
            }
        }

        @javax.persistence.Entity
        @javax.persistence.Table(name = "t_user")
        class UserEntity {
            @javax.persistence.Id
            private Long id;
            private String userName;
        }
    """.trimIndent()

    fun testEntityManagerPersistMergeRemoveFind() {
        // T-J6：persist → INSERT；merge → UPDATE（new 时 INSERT 歧义标注）；remove → DELETE；
        // find(Class, id) → SELECT 主键等值（实体从 Class 字面量解析）
        myFixture.configureByText("Demo.java", entityManagerFixture)
        val insert = newService().preview(call("persist"))!!
        assertEquals(
            "INSERT INTO t_user (id, user_name)\nVALUES (?, ?)",
            insert.sql,
        )
        val merge = newService().preview(call("merge"))!!
        assertEquals(
            """
            UPDATE t_user
            SET user_name = ?
            WHERE id = ?
            """.trimIndent(),
            merge.sql.substringBefore("\n-- "),
        )
        assertTrue("merge 应带 new→INSERT 歧义标注", merge.sql.contains("new 实体"))
        val remove = newService().preview(call("remove"))!!
        assertEquals(
            "DELETE FROM t_user\nWHERE id = ?",
            remove.sql,
        )
        assertEquals(listOf("entity.id"), remove.unresolvedParams)
        val find = newService().preview(call("find"))!!
        assertEquals(
            """
            SELECT id, user_name
            FROM t_user
            WHERE id = 1001
            """.trimIndent(),
            find.sql,
        )
    }

    fun testEntityManagerGetReferenceRefreshStoredProcedure() {
        // T-J6：getReference 延迟代理标注；refresh 主键重查；createStoredProcedureQuery → CALL 渲染
        myFixture.configureByText("Demo.java", entityManagerFixture)
        assertTrue(
            "getReference 应带延迟代理标注",
            newService().preview(call("getReference"))!!.sql.contains("延迟代理"),
        )
        val refresh = newService().preview(call("refresh"))!!
        assertEquals(
            "SELECT id, user_name\nFROM t_user\nWHERE id = ?",
            refresh.sql.substringBefore("\n-- "),
        )
        assertTrue(refresh.sql.contains("重查"))
        val proc = newService().preview(call("createStoredProcedureQuery"))!!
        assertTrue("存储过程应渲染 CALL", proc.sql.startsWith("CALL proc_user_stat(?)"))
        assertTrue(proc.sql.contains("占位"))
    }

    // ------------------------------------------------------------------
    // T-J21：HibernateTemplate 实体操作 / QBC
    // ------------------------------------------------------------------

    private val hibernateTemplateFixture = """
        public class Demo {
            private HibernateTemplate hibernateTemplate;

            public void ops(UserEntity entity) {
                hibernateTemplate.get(UserEntity.class, 1001L);
                hibernateTemplate.load(UserEntity.class, 1001L);
                hibernateTemplate.save(entity);
                hibernateTemplate.update(entity);
                hibernateTemplate.delete(entity);
                hibernateTemplate.executeFind(new Callback());
            }
        }

        class HibernateTemplate {}
        class Callback {}

        @javax.persistence.Entity
        @javax.persistence.Table(name = "t_user")
        class UserEntity {
            @javax.persistence.Id
            private Long id;
            private String userName;
        }
    """.trimIndent()

    fun testHibernateTemplateGetLoadSaveUpdateDelete() {
        // T-J21：ENTITY_OP 派发——get(Class,id)/load(Class,id) SELECT 主键（load 延迟代理标注）；
        // save → INSERT；update → 全列 UPDATE；delete → DELETE 主键等值
        myFixture.configureByText("Demo.java", hibernateTemplateFixture)
        val get = newService().preview(call("get"))!!
        assertEquals(
            """
            SELECT id, user_name
            FROM t_user
            WHERE id = 1001
            """.trimIndent(),
            get.sql,
        )
        val load = newService().preview(call("load"))!!
        assertTrue("load 应带延迟代理标注", load.sql.contains("延迟代理"))
        val save = newService().preview(call("save"))!!
        assertEquals(
            "INSERT INTO t_user (id, user_name)\nVALUES (?, ?)",
            save.sql,
        )
        val update = newService().preview(call("update"))!!
        assertEquals(
            "UPDATE t_user\nSET user_name = ?\nWHERE id = ?",
            update.sql,
        )
        val delete = newService().preview(call("delete"))!!
        assertEquals(
            "DELETE FROM t_user\nWHERE id = ?",
            delete.sql,
        )
    }

    fun testHibernateTemplateExecuteFindQbcPlaceholder() {
        // T-J21：QBC（executeFind，DetachedCriteria 实参）→ 可读占位，不出错误 SQL
        myFixture.configureByText("Demo.java", hibernateTemplateFixture)
        val result = newService().preview(call("executeFind"))
        assertNotNull("executeFind 应产出占位卡", result)
        assertTrue(result!!.sql.contains("QBC/DetachedCriteria 形态暂不支持静态解析"))
    }

    fun testCustomDaoNamedQueryDispatchesToJpql() {
        // T-J21：NAMED_QUERY——findByNamedQuery 首参是查询名，按名回查实体 @NamedQuery 的真实
        // JPQL 再走 JpqlConverter（表名来自 JpaEntityResolver 的 @Table）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void ops() {
                    getHibernateTemplate().findByNamedQuery("UserEntity.findByStatus", 1);
                }
            }

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            @javax.persistence.NamedQuery(name = "UserEntity.findByStatus", query = "SELECT u FROM UserEntity u WHERE u.status = :status")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val result = newService().previewCustomDaoHql(call("findByNamedQuery"))
        assertNotNull("findByNamedQuery 应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertTrue(
            "应回查真实 JPQL 并转换出 status 条件: ${result.sql}",
            result.sql.replace(Regex("\\s+"), " ").contains("status = ?"),
        )
        assertTrue("无 IN 参数", result.paramHints.none { it.inList })
    }

    // ------------------------------------------------------------------
    // T-J4：派生查询 IN 参数接线
    // ------------------------------------------------------------------

    fun testDerivedQueryInClauseParamsWired() {
        // T-J4：findByStatusIn——inClauseParameterOrdinals 对齐方法参数名后挂进 paramHints
        // （statuses 行 inList=true，面板回填按逗号分割多值）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                interface UserRepository extends JpaRepository<UserEntity, Long> {
                    java.util.List<UserEntity> findByStatusIn(java.util.List<Integer> statuses);
                }
            }

            interface JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
                private Integer status;
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethod::class.java)
            .first { it.name == "findByStatusIn" }
        assertTrue(DerivedMethodLocator.isRepositoryMethod(method))
        val result = newService().previewDerivedQuery(method)
        assertNotNull("派生查询应产出预览", result)
        val statusesIndex = result!!.unresolvedParams.indexOf("statuses")
        assertTrue("statuses 应在参数行", statusesIndex >= 0)
        assertTrue("statuses 应标记 IN 回填", result.paramHints[statusesIndex].inList)
    }

    // ------------------------------------------------------------------
    // T-M2：MP 侧 @KeySequence（Oracle/DM/KingBase 方言注释提示）
    // ------------------------------------------------------------------

    fun testMpSaveWithKeySequenceAddsSequenceHintOnOracle() {
        // T-M2：@KeySequence 实体 + Oracle 方言 → INSERT 尾部「主键取序列」注释提示（主键占位不变）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            // T-DialectAutoInfer：走手动模式（默认推断会无视手动方言）
            settings.state.dialectAuto = false
            settings.state.dialect = "Oracle"
            myFixture.configureByText(
                "Demo.java",
                """
                public class Demo {
                    private MpUserService mpUserService;

                    public void save(MpEntity entity) {
                        mpUserService.save(entity);
                    }
                }

                @com.baomidou.mybatisplus.annotation.KeySequence("seq_user")
                @com.baomidou.mybatisplus.annotation.TableName("t_mp")
                class MpEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String userName;
                }

                class BaseIService<T> {}
                class MpUserService extends BaseIService<MpEntity> {}
                """.trimIndent(),
            )
            val result = newService().preview(call("save"))
            assertNotNull(result)
            assertEquals(
                """
                INSERT INTO t_mp (id, user_name)
                VALUES (?, ?)
                -- @KeySequence: 主键取序列 seq_user（Oracle 方言，运行时由 MP KeyGenerator 取 nextval）
                """.trimIndent(),
                result!!.sql,
            )
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testMpSaveWithKeySequenceNoHintOnMysql() {
        // T-M2 边界：MySQL 方言无序列语义 → 不追加提示（对齐既有 save 模板逐字符一致）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private MpUserService mpUserService;

                public void save(MpEntity entity) {
                    mpUserService.save(entity);
                }
            }

            @com.baomidou.mybatisplus.annotation.KeySequence("seq_user")
            @com.baomidou.mybatisplus.annotation.TableName("t_mp")
            class MpEntity {
                @com.baomidou.mybatisplus.annotation.TableId
                private Long id;
                private String userName;
            }

            class BaseIService<T> {}
            class MpUserService extends BaseIService<MpEntity> {}
            """.trimIndent(),
        )
        val result = newService().preview(call("save"))
        assertNotNull(result)
        assertEquals(
            "INSERT INTO t_mp (id, user_name)\nVALUES (?, ?)",
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // 锚点（场景 E 与 preview 派发一致性抽查；完整消歧矩阵见 WrapperAnchorDetectorTest）
    // ------------------------------------------------------------------

    fun testJpaRepositoryCrudAnchorMatchesPreviewDispatch() {
        // T-J1：JPA 专属方法名（getOne/findAll，非 MP 批量名单）在 JPA receiver 上锚点存在——
        // 锚点判定与 preview 派发同走 receiver 家族判定（JpaCrudOperationConverter.hasJpaCrudReceiver）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserRepository userRepository;

                public void ops() {
                    userRepository.getOne(1L);
                    userRepository.findAll();
                }
            }

            interface UserRepository extends JpaRepository<UserEntity, Long> {}
            class JpaRepository<T, ID> {}

            @javax.persistence.Entity
            @javax.persistence.Table(name = "t_user")
            class UserEntity {
                @javax.persistence.Id
                private Long id;
            }
            """.trimIndent(),
        )
        for (name in listOf("getOne", "findAll")) {
            val c = call(name)
            assertEquals(
                "$name 应在 JPA receiver 上标锚点",
                c.methodExpression.referenceNameElement,
                WrapperAnchorDetector.anchorElement(c.methodExpression.referenceNameElement),
            )
        }
    }
}
