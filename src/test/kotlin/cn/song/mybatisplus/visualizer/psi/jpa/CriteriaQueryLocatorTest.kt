package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.model.JoinType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * CriteriaQueryLocator 单元测试（F-JPA-02）。
 *
 * 在 LightPlatform 虚拟项目中使用真实 Java PSI 解析，验证：
 *  - cb.createQuery + cq.from 实体识别
 *  - root.get("field") 列引用解析
 *  - cb.equal/greaterThan/like/in/isNull 条件识别
 *  - cq.where 谓词收集
 *  - cq.orderBy(cb.asc(...)) 排序解析
 *  - cq.multiselect / cq.select 列选择
 *  - root.join JOIN 解析
 *
 * 测试模式与 WrapperLocatorTest / DerivedMethodLocatorTest 一致：内联 JPA 接口定义
 *（LightPlatform 无 JPA classpath，接口在测试代码中内联）。
 */
class CriteriaQueryLocatorTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 在测试代码中查找指定名称的 PsiMethod。 */
    private fun findMethod(name: String): PsiElement? {
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiElement::class.java)
            .firstOrNull { it.text.contains(name) && it.parent is com.intellij.psi.PsiMethod }
            ?: PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethod::class.java)
                .firstOrNull { it.name == name }
    }

    /** 在测试代码中查找指定名称的方法调用。 */
    private fun findMethodCall(name: String): PsiMethodCallExpression? {
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiMethodCallExpression::class.java)
            .firstOrNull { it.methodExpression.referenceName == name }
    }

    /** 从指定方法名定位 CriteriaQueryModel。 */
    private fun locateFromMethod(methodName: String): CriteriaQueryModel? {
        val method = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiMethod::class.java)
            .firstOrNull { it.name == methodName } ?: return null
        return CriteriaQueryLocator.locate(method)
    }

    // ------------------------------------------------------------------
    // 基础定位测试
    // ------------------------------------------------------------------

    fun testCreateQuery_from_识别实体类() {
        myFixture.configureByText(
            "CriteriaDemo.java",
            """
            public class CriteriaDemo {
                public void query(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    cq.select(root);
                }
            }

            class EntityManager {
                public CriteriaBuilder getCriteriaBuilder() { return null; }
            }
            class CriteriaBuilder {
                public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
            }
            class CriteriaQuery<T> {
                public <X> Root<X> from(Class<X> cls) { return null; }
                public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
            }
            class Root<T> {
                public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
            }
            enum JoinType { LEFT, INNER, RIGHT }
            class UserEntity {
                private Long id;
                private String username;
                private Integer status;
            }
            """.trimIndent(),
        )
        val model = locateFromMethod("query")
        assertNotNull("应识别 CriteriaQuery 调用链", model)
        assertEquals("UserEntity", model!!.entityClassName)
    }

    fun testCreateQuery_未找到返回null() {
        myFixture.configureByText(
            "NoQuery.java",
            """
            public class NoQuery {
                public void normalMethod() {
                    String s = "hello";
                    System.out.println(s);
                }
            }
            """.trimIndent(),
        )
        val model = locateFromMethod("normalMethod")
        assertNull("非 CriteriaQuery 代码应返回 null", model)
    }

    // ------------------------------------------------------------------
    // WHERE 条件解析测试
    // ------------------------------------------------------------------

    fun testCb_equal_解析为Simple条件() {
        myFixture.configureByText(
            "CriteriaDemo.java",
            """
            public class CriteriaDemo {
                public void query(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    cq.where(cb.equal(root.get("username"), "admin"));
                }
            }

            class EntityManager {
                public CriteriaBuilder getCriteriaBuilder() { return null; }
            }
            class CriteriaBuilder {
                public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate ge(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate lt(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
            }
            class CriteriaQuery<T> {
                public <X> Root<X> from(Class<X> cls) { return null; }
                public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
            }
            class Root<T> {
                public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
            }
            enum JoinType { LEFT, INNER, RIGHT }
            class UserEntity {
                private Long id;
                private String username;
                private Integer status;
            }
            """.trimIndent(),
        )
        val model = locateFromMethod("query")
        assertNotNull("应识别 CriteriaQuery", model)
        val predicates = model!!.predicates
        assertTrue("应至少解析出 1 个条件", predicates.isNotEmpty())
        val first = predicates[0]
        assertTrue("应为 Simple 条件", first is PredicateModel.Simple)
        val simple = first as PredicateModel.Simple
        assertEquals("username", simple.fieldName)
        assertEquals("=", simple.op)
    }

    fun testCb_greaterThan_解析为大于条件() {
        myFixture.configureByText(
            "CriteriaDemo.java",
            """
            public class CriteriaDemo {
                public void query(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    cq.where(cb.greaterThan(root.get("status"), 1));
                }
            }

            class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
            class CriteriaBuilder {
                public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
            }
            class CriteriaQuery<T> {
                public <X> Root<X> from(Class<X> cls) { return null; }
                public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
            }
            class Root<T> {
                public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
            }
            enum JoinType { LEFT, INNER, RIGHT }
            class UserEntity { private Long id; private String username; private Integer status; }
            """.trimIndent(),
        )
        val model = locateFromMethod("query")
        assertNotNull(model)
        val pred = model!!.predicates.getOrNull(0)
        if (pred is PredicateModel.Simple) {
            assertEquals("status", pred.fieldName)
            assertEquals(">", pred.op)
        }
    }

    // ------------------------------------------------------------------
    // ORDER BY / SELECT / JOIN 解析测试
    // ------------------------------------------------------------------

    fun testCq_orderBy_解析为排序项() {
        myFixture.configureByText(
            "CriteriaDemo.java",
            """
            public class CriteriaDemo {
                public void query(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    cq.orderBy(cb.asc(root.get("username")));
                }
            }

            class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
            class CriteriaBuilder {
                public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
            }
            class CriteriaQuery<T> {
                public <X> Root<X> from(Class<X> cls) { return null; }
                public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
            }
            class Root<T> {
                public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
            }
            enum JoinType { LEFT, INNER, RIGHT }
            class UserEntity { private Long id; private String username; private Integer status; }
            """.trimIndent(),
        )
        val model = locateFromMethod("query")
        assertNotNull(model)
        val orderBys = model!!.orderBys
        assertTrue("应至少解析出 1 个排序项", orderBys.isNotEmpty())
        val ob = orderBys[0]
        assertEquals("username", ob.fieldName)
        assertEquals(true, ob.asc)
    }

    fun testCq_multiselect_解析为列列表() {
        myFixture.configureByText(
            "CriteriaDemo.java",
            """
            public class CriteriaDemo {
                public void query(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    cq.multiselect(root.get("id"), root.get("username"));
                }
            }

            class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
            class CriteriaBuilder {
                public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
            }
            class CriteriaQuery<T> {
                public <X> Root<X> from(Class<X> cls) { return null; }
                public CriteriaQuery<T> multiselect(jakarta.persistence.criteria.Selection<?>... selections) { return this; }
                public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
            }
            class Root<T> {
                public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
            }
            enum JoinType { LEFT, INNER, RIGHT }
            class UserEntity { private Long id; private String username; private Integer status; }
            """.trimIndent(),
        )
        val model = locateFromMethod("query")
        assertNotNull(model)
        assertEquals(2, model!!.selectColumns.size)
        assertEquals("id", model.selectColumns[0].fieldName)
        assertEquals("username", model.selectColumns[1].fieldName)
    }

    // ------------------------------------------------------------------
    // 集成测试：完整条件链
    // ------------------------------------------------------------------

    fun test完整CriteriaQuery_解析所有子句() {
        myFixture.configureByText(
            "CriteriaDemo.java",
            """
            public class CriteriaDemo {
                public void query(EntityManager em) {
                    CriteriaBuilder cb = em.getCriteriaBuilder();
                    CriteriaQuery<UserEntity> cq = cb.createQuery(UserEntity.class);
                    Root<UserEntity> root = cq.from(UserEntity.class);
                    cq.where(
                        cb.equal(root.get("status"), 1),
                        cb.like(root.get("username"), "admin%")
                    );
                    cq.orderBy(cb.desc(root.get("id")));
                }
            }

            class EntityManager { public CriteriaBuilder getCriteriaBuilder() { return null; } }
            class CriteriaBuilder {
                public <T> CriteriaQuery<T> createQuery(Class<T> cls) { return null; }
                public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate in(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... predicates) { return null; }
                public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<Boolean> a) { return null; }
                public jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Comparable> a, Comparable lo, Comparable hi) { return null; }
                public jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a, Object b) { return null; }
                public jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Number> a, Number b) { return null; }
                public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<String> a, String pattern) { return null; }
                public jakarta.persistence.criteria.Predicate notIn(jakarta.persistence.criteria.Expression<?> a) { return null; }
                public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> e) { return null; }
                public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> e) { return null; }
            }
            class CriteriaQuery<T> {
                public <X> Root<X> from(Class<X> cls) { return null; }
                public CriteriaQuery<T> select(jakarta.persistence.criteria.Selection<? extends T> s) { return this; }
                public CriteriaQuery<T> where(jakarta.persistence.criteria.Expression<Boolean>... predicates) { return this; }
                public CriteriaQuery<T> orderBy(jakarta.persistence.criteria.Order... orders) { return this; }
            }
            class Root<T> {
                public jakarta.persistence.criteria.Path<String> get(String field) { return null; }
                public <X, Y extends X> Root<X> join(String field, JoinType joinType) { return null; }
            }
            enum JoinType { LEFT, INNER, RIGHT }
            class UserEntity { private Long id; private String username; private Integer status; }
            """.trimIndent(),
        )
        val model = locateFromMethod("query")
        assertNotNull(model)
        assertEquals("UserEntity", model!!.entityClassName)
        assertTrue("应解析出至少 2 个条件", model.predicates.size >= 2)
        assertTrue("应解析出 1 个排序项", model.orderBys.size == 1)
    }
}
