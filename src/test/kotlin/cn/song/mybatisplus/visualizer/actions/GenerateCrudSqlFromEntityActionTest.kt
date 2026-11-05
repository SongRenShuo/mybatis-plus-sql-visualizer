package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 实体类右键生成 CRUD SQL action 测试（T-TestGuard-07 零覆盖补测：T-CrudSql-02 的
 * 实体收集/MP 实体判定/规格适配此前零覆盖）。
 *
 * update() 的行为核心 = collectEntityClasses + isMpEntity（BGT 线程 PSI 读取），
 * 端到端弹窗链路（CrudSqlPreviewFlow）已有独立覆盖，此处锁定判定契约：
 * 普通 POJO 不误启用、类级/字段级 MP 注解均判中、表名解析经 EntityResolver。
 */
class GenerateCrudSqlFromEntityActionTest : BasePlatformTestCase() {

    private val action = GenerateCrudSqlFromEntityAction()

    private fun classesOf(code: String): List<PsiClass> {
        val file = myFixture.configureByText("Demo.java", code)
        // PsiClassOwner.classes 只含顶层类——fixture 的实体是静态内部类，须一并收集
        return (file as PsiClassOwner).classes.flatMap { it.allInnerClasses.toList() }
    }
    fun `testupdate判定在BGT线程`() {
        // 262 平台 EDT 读 PSI 硬抛——update() 必须声明 BGT
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }
    fun `test普通POJO不判为MP实体`() {
        val pojo = classesOf(
            """
            public class Demo {
                static class PlainPojo {
                    private Long id;
                    private String name;
                }
            }
            """.trimIndent(),
        ).single()
        assertFalse("无 MP 注解的 POJO 不应误启用右键项", action.isMpEntity(pojo))
    }
    fun `test类级TableName与字段级注解均判中`() {
        val classes = classesOf(
            """
            public class Demo {
                @com.baomidou.mybatisplus.annotation.TableName("t_order")
                static class OrderEntity {
                    private Long id;
                }

                static class FieldEntity {
                    @com.baomidou.mybatisplus.annotation.TableField("order_id")
                    private Long orderId;
                }
            }
            """.trimIndent(),
        )
        assertEquals(2, classes.size)
        assertTrue("类级 @TableName 应判中", action.isMpEntity(classes[0]))
        assertTrue("字段级 @TableField 应判中", action.isMpEntity(classes[1]))
    }
    fun `testentitySpec经EntityResolver解析出表名与列`() {
        val entity = classesOf(
            """
            public class Demo {
                @com.baomidou.mybatisplus.annotation.TableName("t_order")
                static class OrderEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String remark;
                }
            }
            """.trimIndent(),
        ).single()
        val spec = action.entitySpec(entity)
        assertNotNull("MP 实体应产出表规格", spec)
        assertEquals("t_order", spec!!.tableName)
        assertTrue("列应含 id 且识别为主键", spec.columns.any { it.columnName == "id" && it.isPrimaryKey })
        assertTrue("列应含 remark", spec.columns.any { it.columnName == "remark" })
    }

    fun `test实体右键规格读取实体所在项目的手动方言`() {
        val global = PluginSettings.getInstance()
        val oldGlobal = global.getState().copy()
        val settings = ProjectPluginSettings.getInstance(project)
        val oldProject = settings.state.copy()
        try {
            global.state.dialectAuto = true
            settings.state.useProjectSettings = true
            settings.state.dialectAuto = false
            settings.state.dialect = "Oracle"
            val entity = classesOf(
                """
                public class Demo {
                    @com.baomidou.mybatisplus.annotation.TableName("t_order")
                    static class OrderEntity {
                        @com.baomidou.mybatisplus.annotation.TableId private Long id;
                    }
                }
                """.trimIndent(),
            ).single()
            assertEquals(OracleDialect, action.entitySpec(entity)!!.dialect)
        } finally {
            settings.loadState(oldProject)
            global.loadState(oldGlobal)
        }
    }
}
