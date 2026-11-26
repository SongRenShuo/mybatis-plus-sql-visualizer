package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * EntityFieldCollector 测试（D254 T-EntityFieldStaticHidden）：
 *
 *  - 同名合并方向：子类优先（最具体声明），父类同名字段被排除；
 *  - static / transient 排除（列语义），且**去重在前、过滤在后**——
 *    子类 static 与父类实例同名时该名字整体不产出（MP `ReflectionKit.getFieldList` 语义）；
 *  - 输出顺序：无重名时「父类在前、子类在后」与改动前逐项相同（回归锚）；
 *  - 属性语义（[EntityFieldCollector.collectProperties]）：保留 static 常量，排除
 *    `serialVersionUID` / `class` / `$` 前缀（MyBatis `Reflector.isValidPropertyName` 同口径）。
 *
 * k2j 移植注：`EntityFieldCollector` 在 k2j 是无状态 Java 静态工具（原 `object`），
 * `collectColumns` / `collectProperties` 调用形态与 master 逐字一致；`containingClass?.name` /
 * `field.name` / `status.type.canonicalText` 全走 Kotlin 属性语法映射到 Java getter，无需改写。
 */
class EntityFieldCollectorTest : BasePlatformTestCase() {

    private fun classByName(name: String, code: String): PsiClass {
        myFixture.configureByText("Entities.java", code.trimIndent())
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .first { it.name == name }
    }

    private fun names(fields: List<PsiField>): List<String> = fields.map { it.name }

    // ------------------------------------------------------------------
    // 同名合并（子类优先）
    // ------------------------------------------------------------------

    fun testChildFieldHidesParentField() {
        val user = classByName(
            "User",
            """
            public class BaseEntity {
                private String name;
                private Long id;
            }

            public class User extends BaseEntity {
                private String name;
                private Integer age;
            }
            """,
        )
        val fields = EntityFieldCollector.collectColumns(user)
        assertEquals(listOf("id", "name", "age"), names(fields))
        assertEquals(
            "同名字段应取子类声明",
            "User",
            fields.first { it.name == "name" }.containingClass?.name,
        )
        assertEquals(
            "唯一字段应保持父类声明",
            "BaseEntity",
            fields.first { it.name == "id" }.containingClass?.name,
        )
    }

    fun testMiddleLayerRedefinitionWins() {
        val c = classByName(
            "C",
            """
            public class A { private String code; }
            public class B extends A { private String code; }
            public class C extends B { private String label; }
            """,
        )
        val fields = EntityFieldCollector.collectColumns(c)
        assertEquals(listOf("code", "label"), names(fields))
        assertEquals("三层继承中取最具体声明（B）", "B", fields.first { it.name == "code" }.containingClass?.name)
    }

    fun testParentsFirstOrderForNonOverriddenFields() {
        // 无重名场景：顺序必须与改动前一致（父类字段在前、子类在后）——T-M16.2-02 / T-M23.3 回归锚
        val user = classByName(
            "User",
            """
            public class BaseEntity {
                private Long id;
                private java.time.LocalDateTime createTime;
            }

            public class User extends BaseEntity {
                private String deviceName;
            }
            """,
        )
        assertEquals(
            listOf("id", "createTime", "deviceName"),
            names(EntityFieldCollector.collectColumns(user)),
        )
    }

    // ------------------------------------------------------------------
    // static / transient（列语义）
    // ------------------------------------------------------------------

    fun testStaticAndTransientFieldsExcluded() {
        val user = classByName(
            "User",
            """
            public class User {
                private Long id;
                public static final String TABLE_NAME = "t_user";
                private static final String LOG_TAG = "user";
                private transient String runtimeCache;
                private String name;
            }
            """,
        )
        assertEquals(listOf("id", "name"), names(EntityFieldCollector.collectColumns(user)))
    }

    /**
     * 顺序语义最关键的一条：子类 static 字段与父类实例字段同名 → 该名字整体不产出列。
     *
     * MP `ReflectionKit.getFieldList` 先 `excludeOverrideSuperField`（子类占位、父类同名排除）
     * 再过滤 static ⇒ 最终没有该列。若把过滤提前到去重之前，就会多出父类那一列而与真机不符。
     */
    fun testChildStaticFieldHidesParentInstanceField() {
        val user = classByName(
            "User",
            """
            public class BaseEntity {
                private String token;
                private Long id;
            }

            public class User extends BaseEntity {
                private static String token;
                private String name;
            }
            """,
        )
        val fields = EntityFieldCollector.collectColumns(user)
        assertEquals(listOf("id", "name"), names(fields))
        assertFalse("父类同名字段不得因过滤顺序而复活", fields.any { it.name == "token" })
    }

    // ------------------------------------------------------------------
    // 属性语义（MyBatis 参数访问）
    // ------------------------------------------------------------------

    fun testPropertiesKeepStaticButDropUnreadableNames() {
        val dto = classByName(
            "QueryDto",
            """
            public class QueryDto {
                private Long id;
                public static final String TABLE = "query_dto";
                private static final long serialVersionUID = 1L;
                private int ${'$'}synthetic;
                private String name;
            }
            """,
        )
        val properties = EntityFieldCollector.collectProperties(dto)
        val propertyNames = names(properties)
        assertTrue("static 常量真机可读，应保留", "TABLE" in propertyNames)
        assertFalse("serialVersionUID 真机取不到", "serialVersionUID" in propertyNames)
        assertFalse("\$ 前缀（编译器合成）真机取不到", propertyNames.any { it.startsWith("$") })
        assertTrue("普通字段应保留", "id" in propertyNames && "name" in propertyNames)
    }

    fun testPropertiesSameNamePrefersChild() {
        val dto = classByName(
            "QueryDto",
            """
            public class BaseQuery { private Integer status; }
            public class QueryDto extends BaseQuery { private String status; }
            """,
        )
        val properties = EntityFieldCollector.collectProperties(dto)
        val status = properties.single { it.name == "status" }
        assertEquals("同名属性应取子类声明", "QueryDto", status.containingClass?.name)
        assertEquals("后者类型应与子类一致", "String", status.type.canonicalText)
    }
}
