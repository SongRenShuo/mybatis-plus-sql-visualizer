package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 跨 ORM 的表名注解源（[TableNameAnnotations]）。
 *
 * 背景：反查索引改成「先问平台注解索引」之后，**覆盖面必须显式列清**——只认 MP 的 `@TableName`
 * 会把 JPA 工程（`@Table(name=…)`）整个漏掉，而这种漏在测试夹具里看不见（stub 索引对 fixture
 * 新建文件不给结果，会走全项目推断那半边，用例照样绿）。本类直接钉住注解源的判定与取值。
 */
class TableNameAnnotationsTest : BasePlatformTestCase() {

    private fun classOf(source: String): PsiClass {
        myFixture.configureByText("Probe.java", source.trimIndent())
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiClass::class.java) ?: error("没有类")
    }

    private fun annotationOf(source: String): PsiAnnotation {
        myFixture.configureByText("Probe.java", source.trimIndent())
        return PsiTreeUtil.findChildOfType(myFixture.file, PsiAnnotation::class.java) ?: error("没有注解")
    }

    fun testMpTableNameValue() {
        val clazz = classOf(
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("cust_address")
            public class CustAddress { private Long id; }
            """,
        )
        assertEquals("cust_address", TableNameAnnotations.tableNameOfClass(clazz))
    }

    fun testMpTableNameExplicitValueAttribute() {
        val clazz = classOf(
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName(value = "cust_channel")
            public class CustChannel { }
            """,
        )
        assertEquals("cust_channel", TableNameAnnotations.tableNameOfClass(clazz))
    }

    fun testJpaTableName() {
        // 夹具里没有 javax.persistence 依赖 ⇒ 注解 FQN 解析不出，按**写入的简单名**认（正是缺依赖场景）
        val clazz = classOf(
            """
            import javax.persistence.Table;

            @Table(name = "t_legacy_booking")
            public class LegacyBooking { }
            """,
        )
        assertEquals("t_legacy_booking", TableNameAnnotations.tableNameOfClass(clazz))
    }

    fun testJpaJakartaTableName() {
        val clazz = classOf(
            """
            import jakarta.persistence.Table;

            @Table(name = "t_jakarta_booking")
            public class JakartaBooking { }
            """,
        )
        assertEquals("t_jakarta_booking", TableNameAnnotations.tableNameOfClass(clazz))
    }

    fun testJpaTableWithoutNameIsIgnored() {
        // `@Table` 只写了索引等属性、没有 name ⇒ 表名走 JPA 默认约定（类名），这里必须返回 null
        val clazz = classOf(
            """
            import javax.persistence.Table;

            @Table(indexes = {})
            public class NoNameEntity { }
            """,
        )
        assertNull(TableNameAnnotations.tableNameOfClass(clazz))
    }

    fun testNonTableAnnotationsAreIgnored() {
        val annotation = annotationOf(
            """
            @Deprecated
            public class Plain { }
            """,
        )
        assertNull("别家注解不得当成表名源", TableNameAnnotations.sourceOf(annotation))
    }

    fun testAnnotationSourcesCoverMpAndJpa() {
        assertEquals(
            "注解索引要查的简单名集合：MP + JPA 两代",
            setOf("TableName", "Table"),
            TableNameAnnotations.simpleNames,
        )
    }
}
