package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.converter.QuerydslConverter
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * Querydsl 端到端预览测试（QD，M44 其他 ORM 第一批，矩阵 §3）。
 *
 * 覆盖：
 *  - QD1：JPAQueryFactory receiver 识别 + selectFrom/where/orderBy/limit/offset 链；
 *  - QD2：表达式树 walker——Q 静态字段根（QUser.user.status.eq(1)）、Q 类型变量根
 *    （qUser.name.contains("x")）→ like 位置映射；and/or → Group；
 *  - QD4：谓词变量 / BooleanBuilder 动态累积（and/or 引用链收集）；
 *  - QD5：Q 类（EntityPathBase 泛型）→ 实体 → JpaEntityResolver 列名映射（预览口径注释）；
 *  - 写路径：update(根).set(path, v).where(...).execute() / delete；
 *  - 负例：普通 receiver（非 JPAQueryFactory）不识别。
 */
class QuerydslConverterTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
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

    /** 公共 stub：JPAQueryFactory/EntityPathBase/Q 类/路径类型/BooleanBuilder + jakarta 实体。 */
    private val stubs = """
        class JPAQueryFactory {}

        class EntityPathBase<T> {}

        class NumberPath {
            public NumberPath eq(Object v) { return null; }
            public NumberPath desc() { return null; }
            public NumberPath asc() { return null; }
        }

        class StringPath {
            public StringPath eq(Object v) { return null; }
            public StringPath contains(Object v) { return null; }
            public StringPath startsWith(Object v) { return null; }
        }

        class BooleanBuilder {
            public BooleanBuilder and(Object pred) { return null; }
            public BooleanBuilder or(Object pred) { return null; }
        }

        class QUser extends EntityPathBase<User> {
            public static final QUser user = new QUser();
            public final NumberPath id = new NumberPath();
            public final StringPath name = new StringPath();
            public final NumberPath status = new NumberPath();
        }

        @jakarta.persistence.Entity
        @jakarta.persistence.Table(name = "t_user")
        class User {
            @jakarta.persistence.Id
            private Long id;

            private String name;

            private Integer status;
        }
    """.trimIndent()

    fun testQd1SelectFromWithInlineChainRendersSelect() {
        // QD1/QD2/QD5：内联链 QUser.user.status.eq(1)（Q 静态字段根）+ Q 类型变量根 qUser.name.contains
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private JPAQueryFactory queryFactory;

                public User demo() {
                    QUser qUser = QUser.user;
                    return queryFactory.selectFrom(QUser.user)
                        .where(QUser.user.status.eq(1), qUser.name.contains("x"))
                        .orderBy(QUser.user.id.desc())
                        .limit(10)
                        .offset(5)
                        .fetchOne();
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("selectFrom"))
        assertNotNull("QD1：selectFrom 链应产出预览", result)
        assertEquals("t_user", result!!.tableName)
        assertEquals(
            """
            SELECT id, name, status
            FROM t_user
            WHERE status = 1
                AND name LIKE '%x%'
            ORDER BY id DESC
            LIMIT 5, 10
            -- Querydsl：Q 类仅携带 Java 属性名，列名按实体注解映射的近似 SQL
            """.trimIndent(),
            result.sql,
        )
    }

    fun testQd4BooleanBuilderAndOrChainCollected() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private JPAQueryFactory queryFactory;

                public java.util.List<User> demo() {
                    BooleanBuilder builder = new BooleanBuilder();
                    builder.and(QUser.user.status.eq(1));
                    builder.or(QUser.user.name.startsWith("a"));
                    return queryFactory.selectFrom(QUser.user).where(builder).fetch();
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("selectFrom"))
        assertNotNull(result)
        assertEquals(
            """
            SELECT id, name, status
            FROM t_user
            WHERE (status = 1 OR name LIKE 'a%')
            -- Querydsl：Q 类仅携带 Java 属性名，列名按实体注解映射的近似 SQL
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testQd4BooleanBuilder字段变量_限useScope后引用链仍收集_券Y17() {
        // T-HiddenBugPerfScan 券 Y17：ReferencesSearch 限 variable.useScope（仓内定性「较重」，
        // 字段变量默认 scope 为全项目）——本例钉「字段变量的 and/or 引用链收集行为不变」
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private JPAQueryFactory queryFactory;
                private BooleanBuilder builder = new BooleanBuilder();

                public java.util.List<User> demo() {
                    builder.and(QUser.user.status.eq(1));
                    builder.or(QUser.user.name.startsWith("a"));
                    return queryFactory.selectFrom(QUser.user).where(builder).fetch();
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("selectFrom"))
        assertNotNull(result)
        assertEquals(
            """
            SELECT id, name, status
            FROM t_user
            WHERE (status = 1 OR name LIKE 'a%')
            -- Querydsl：Q 类仅携带 Java 属性名，列名按实体注解映射的近似 SQL
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testQdUpdateSetWhereExecuteRendersUpdate() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private JPAQueryFactory queryFactory;

                public long demo() {
                    return queryFactory.update(QUser.user)
                        .set(QUser.user.name, "新名")
                        .where(QUser.user.id.eq(1L))
                        .execute();
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("update"))
        assertNotNull("QD：update 链应产出预览", result)
        assertEquals(
            "UPDATE t_user\nSET name = '新名'\nWHERE id = 1" +
                "\n-- Querydsl：Q 类仅携带 Java 属性名，列名按实体注解映射的近似 SQL",
            result!!.sql,
        )
    }

    fun testQdDeleteWithoutWhereWarns() {
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private JPAQueryFactory queryFactory;

                public long demo() {
                    return queryFactory.delete(QUser.user).execute();
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("delete"))
        assertNotNull(result)
        assertEquals("⚠ 无 WHERE 条件，将删除全表数据！", result!!.warning)
        assertTrue(result.sql.startsWith("DELETE FROM t_user"))
    }

    fun testQdUpdateWithoutWhereWarnsAndNotes() {
        // 普查批：update 无 where → 警示 + notes 行（对齐 delete 分支既有形态）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private JPAQueryFactory queryFactory;

                public long demo() {
                    return queryFactory.update(QUser.user)
                        .set(QUser.user.name, "新名")
                        .execute();
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("update"))
        assertNotNull(result)
        assertEquals("⚠ 无 WHERE 条件，将更新全表数据！", result!!.warning)
        assertTrue("全表更新应渲染 UPDATE 语句", result.sql.startsWith("UPDATE t_user\nSET name = '新名'"))
        assertTrue(
            "无 WHERE 的 update 应在 notes 里给全表说明",
            result.sql.contains("-- 无 WHERE 条件：update(根).execute() 将更新全表（运行时 Querydsl update 无条件即全改）"),
        )
    }

    fun testQdUpdateWithWhereNoWarning() {
        // 对照：update 带 where 段 → 不出警示
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private JPAQueryFactory queryFactory;

                public long demo() {
                    return queryFactory.update(QUser.user)
                        .set(QUser.user.name, "x")
                        .where(QUser.user.id.eq(2L))
                        .execute();
                }
            }

            $stubs
            """.trimIndent(),
        )
        val result = newService().preview(call("update"))
        assertNotNull(result)
        assertNull("带 WHERE 的 update 不应出全表警示", result!!.warning)
        assertEquals(
            "UPDATE t_user\nSET name = 'x'\nWHERE id = 2" +
                "\n-- Querydsl：Q 类仅携带 Java 属性名，列名按实体注解映射的近似 SQL",
            result.sql,
        )
    }

    fun testQdNegativeCaseNotRecognized() {
        // 负例守卫：receiver 非 JPAQueryFactory（普通类同名方法）不识别
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private NotFactory notFactory;

                public Object demo() {
                    return notFactory.selectFrom("x");
                }
            }

            class NotFactory {}
            """.trimIndent(),
        )
        assertNull(QuerydslConverter.convert(call("selectFrom")))
    }
}
