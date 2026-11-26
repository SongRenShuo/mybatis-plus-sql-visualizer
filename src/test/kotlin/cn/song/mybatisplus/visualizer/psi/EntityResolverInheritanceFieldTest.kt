package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * EntityResolver 继承字段语义测试（D254 T-EntityFieldStaticHidden / MP 轨）：
 *
 *  - 子类重写父类字段后，**注解语义随子类字段**（父类那份被同名合并排除）：
 *    重写字段不带 @TableLogic / @Version → 不再是逻辑删除 / 乐观锁列；
 *  - @TableField 列名取子类声明，父类同名字段不再产出一份；
 *  - static / transient 字段不产列（不再只特判 serialVersionUID 一个名字）。
 *
 * k2j 移植注：`PluginSettings.State` 的 `tablePrefix` 等在 Java 侧有 setter，
 * Kotlin 属性赋值语法 `settings.state.tablePrefix = ""` 不变；`table.logicDeleteColumn` /
 * `table.allColumnNames` 走 Java getter 的属性映射，亦无需改写。
 */
class EntityResolverInheritanceFieldTest : BasePlatformTestCase() {

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
            EntityResolver.clearCache()
            block()
        } finally {
            settings.loadState(snapshot)
            EntityResolver.clearCache()
        }
    }

    fun testChildOverrideWithoutTableLogicDropsLogicDelete() {
        withDefaultConfig {
            val user = classByName(
                "User",
                """
                public class BaseEntity {
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                    private Long id;
                }

                public class User extends BaseEntity {
                    private Integer deleted;
                    private String name;
                }
                """,
            )
            val table = EntityResolver().resolve(user)
            // 子类重写字段不带 @TableLogic——MP 运行时同样按子类字段判定，逻辑删除列应为空
            assertNull("子类重写字段无 @TableLogic，不应再识别为逻辑删除列", table.logicDeleteColumn)
            // 同名字段只出现一份（子类定义）
            assertEquals(listOf("id", "deleted", "name"), table.allColumnNames)
            assertEquals(1, table.columns.count { it.columnName == "deleted" })
        }
    }

    fun testChildTableFieldColumnNameWins() {
        withDefaultConfig {
            val user = classByName(
                "User",
                """
                public class BaseEntity {
                    @com.baomidou.mybatisplus.annotation.TableField("create_time")
                    private java.time.LocalDateTime createTime;
                }

                public class User extends BaseEntity {
                    @com.baomidou.mybatisplus.annotation.TableField("created_at")
                    private java.time.LocalDateTime createTime;
                    private Long id;
                }
                """,
            )
            val table = EntityResolver().resolve(user)
            assertEquals(listOf("created_at", "id"), table.allColumnNames)
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
                public class BaseEntity {
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }

                public class User extends BaseEntity {
                    private Integer version;
                    private Long id;
                }
                """,
            )
            val table = EntityResolver().resolve(user)
            assertNull("子类重写字段无 @Version，不应再识别为乐观锁列", table.versionColumn)
            assertEquals(1, table.columns.count { it.columnName == "version" })
        }
    }

    fun testStaticAndTransientFieldsExcludedFromColumns() {
        withDefaultConfig {
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
            val table = EntityResolver().resolve(user)
            assertEquals(listOf("id", "name"), table.allColumnNames)
        }
    }
}
