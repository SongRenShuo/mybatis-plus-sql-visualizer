package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertTrue

/**
 * sqlv_crud_sql 工具测试（T-McpToolset2 / T-MTS2-02）：className 定位实体 → 四件套生成；
 * 非 MP 实体诚实拒绝；无入参拒绝。实体解析细节由 GenerateCrudSqlFromEntityActionTest 覆盖。
 */
class CrudSqlToolTest : com.intellij.testFramework.fixtures.BasePlatformTestCase() {

    private val tool = SqlVisualizerCrudSqlTool()

    private fun crud(vararg args: Pair<String, String>): String {
        val map = args.toMap()
        val spec = tool.resolveSpec(project, map["filePath"] ?: "", map["className"] ?: "")
        return tool.crudOutput(spec)
    }

    private fun addEntity() {
        myFixture.configureByText(
            "UserEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("t_user")
            public class UserEntity {
                private Long id;
                private String userName;

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getUserName() { return userName; }
                public void setUserName(String userName) { this.userName = userName; }
            }
            """.trimIndent(),
        )
    }

    fun testClassName定位实体_生成四件套() {
        addEntity()
        val output = crud("className" to "UserEntity")
        assertTrue(output, output.contains("table: t_user"))
        assertTrue(output, output.contains("SELECT_ALL"))
        assertTrue(output, output.contains("INSERT"))
        assertTrue(output, output.contains("UPDATE"))
        assertTrue(output, output.contains("DELETE"))
    }

    fun test空列实体_诚实拒绝() {
        // 无任何可映射字段的实体 → CrudSqlGenerator 返回空清单 → crudOutput 拒绝
        myFixture.configureByText(
            "EmptyEntity.java",
            """
            import com.baomidou.mybatisplus.annotation.TableName;

            @TableName("t_empty")
            public class EmptyEntity {
            }
            """.trimIndent(),
        )
        val error = runCatching { crud("className" to "EmptyEntity") }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
        assertTrue((error?.message ?: "").contains("no mappable columns"))
    }

    fun test类不存在_预期错误() {
        val error = runCatching { crud("className" to "com.example.NoSuchEntity") }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
        assertTrue((error?.message ?: "").contains("class not found"))
    }

    fun test无入参_预期错误() {
        val error = runCatching { tool.resolveSpec(project, "", "") }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
        assertTrue((error?.message ?: "").contains("provide filePath or className"))
    }
}
