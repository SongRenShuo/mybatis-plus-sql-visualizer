package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * JpaEntityResolver 继承字段语义测试（D254 T-EntityFieldStaticHidden / JPA 轨）：
 *
 *  - 子类重写父类字段后取**子类**定义：@Column 列名、@Version 识别都以子类为准；
 *  - 同名字段只产出一列（收集层已合并；`distinctBy(columnName)` 仅兜「不同字段撞同列名」）；
 *  - static / transient 字段不产列。
 *
 * 注解用全限定名书写、走短名兜底轨（沙盒无 jakarta.persistence 依赖，与既有 JPA 测试同款）。
 *
 * k2j 移植注：`PluginSettings.State` 同 MP 轨（Java setter + Kotlin 属性语法）；
 * `JpaEntityResolver().resolve(user)` / `table.allColumnNames` 均走 Java getter 属性映射。
 */
class JpaEntityResolverInheritanceFieldTest : BasePlatformTestCase() {

    private fun classByName(name: String, code: String): PsiClass {
        myFixture.configureByText("Entities.java", code.trimIndent())
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiClass::class.java)
            .first { it.name == name }
    }

    private fun withDefaultConfig(block: () -> Unit) {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = ""
            settings.state.tableSuffix = ""
            settings.state.tableNameInference = PluginSettings.INFERENCE_SNAKE
            settings.state.ignoredFields = ""
            JpaEntityResolver.clearCache()
            block()
        } finally {
            settings.loadState(snapshot)
            JpaEntityResolver.clearCache()
        }
    }

    fun testChildColumnOverrideWins() {
        withDefaultConfig {
            val user = classByName(
                "User",
                """
                @jakarta.persistence.Entity
                public class BaseEntity {
                    @jakarta.persistence.Column(name = "create_time")
                    private java.time.LocalDateTime createTime;
                }

                @jakarta.persistence.Entity
                public class User extends BaseEntity {
                    @jakarta.persistence.Column(name = "created_at")
                    private java.time.LocalDateTime createTime;
                    @jakarta.persistence.Id
                    private Long id;
                }
                """,
            )
            val table = JpaEntityResolver().resolve(user)
            // @Id 角色置顶（PRIMARY），其余业务列在后——同名字段只出现一份
            assertEquals(listOf("id", "created_at"), table.allColumnNames)
            assertTrue(
                "父类被重写字段的列名不应残留",
                table.columns.none { it.columnName == "create_time" },
            )
        }
    }

    fun testChildVersionOverrideDropsVersionColumn() {
        withDefaultConfig {
            val user = classByName(
                "User",
                """
                @jakarta.persistence.Entity
                public class BaseEntity {
                    @jakarta.persistence.Version
                    private Integer version;
                }

                @jakarta.persistence.Entity
                public class User extends BaseEntity {
                    private Integer version;
                    @jakarta.persistence.Id
                    private Long id;
                }
                """,
            )
            val table = JpaEntityResolver().resolve(user)
            assertNull("子类重写字段无 @Version，不应再识别为乐观锁列", table.versionColumn)
            assertEquals(1, table.columns.count { it.columnName == "version" })
        }
    }

    fun testStaticAndTransientFieldsExcluded() {
        withDefaultConfig {
            val user = classByName(
                "User",
                """
                @jakarta.persistence.Entity
                public class User {
                    @jakarta.persistence.Id
                    private Long id;
                    public static final String TABLE_NAME = "t_user";
                    private transient String runtimeCache;
                    private String name;
                }
                """,
            )
            val table = JpaEntityResolver().resolve(user)
            assertEquals(listOf("id", "name"), table.allColumnNames)
        }
    }
}
