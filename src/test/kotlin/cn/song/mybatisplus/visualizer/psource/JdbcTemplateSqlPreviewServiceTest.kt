package cn.song.mybatisplus.visualizer.psource

import cn.song.mybatisplus.visualizer.model.PreviewResult
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [JdbcTemplateSqlPreviewService] PSI 集成测试（BasePlatformTestCase）。
 *
 * 覆盖：PreviewResult 组装（命名参数收集、引号提示、namedParamHints、末尾分号）。
 */
class JdbcTemplateSqlPreviewServiceTest : BasePlatformTestCase() {

    private fun findFirstCall(methodName: String): PsiMethodCallExpression? {
        val file = myFixture.file as? PsiJavaFile ?: return null
        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .firstOrNull { it.methodExpression.referenceName == methodName }
    }

    private fun previewOfCall(call: PsiMethodCallExpression): PreviewResult? {
        val locatorCall = JdbcTemplateSqlLocator.locateFromCall(call) ?: return null
        return JdbcTemplateSqlPreviewService.preview(locatorCall, call)
    }

    // ------------------------------------------------------------------
    // PreviewResult 组装
    // ------------------------------------------------------------------

    fun testPreview_singleNamedParam_producesNamedParamHints() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM users WHERE id = :id");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        // 命名参数识别
        assertTrue(result!!.namedParamHints.isNotEmpty())
        assertTrue(result.namedParamHints.containsKey("id"))
        // unresolvedParams 包含命名参数名
        assertTrue(result.unresolvedParams.contains("id"))
        // wrapperType = null（非 Wrapper 预览）
        assertNull(result.wrapperType)
    }

    fun testPreview_multipleNamedParams() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t WHERE a = :CUR_USER AND b = :flowKey");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        assertEquals(2, result!!.namedParamHints.size)
        assertTrue(result.namedParamHints.containsKey("CUR_USER"))
        assertTrue(result.namedParamHints.containsKey("flowKey"))
    }

    fun testPreview_duplicateParam_deduplicated() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t WHERE a = :CUR_USER OR b = :CUR_USER");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        // 同名参数去重 → 1 条 namedParamHint
        assertEquals(1, result!!.namedParamHints.size)
        assertEquals(1, result.unresolvedParams.size)
    }

    fun testPreview_questionMarkPlaceholders() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t WHERE a = ? AND b = ?", new Object[]{1, 2});
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        // 无命名参数 → namedParamHints 空
        assertTrue(result!!.namedParamHints.isEmpty())
        // `?` 占位符按序（param1, param2）
        assertTrue(result.unresolvedParams.contains("param1"))
        assertTrue(result.unresolvedParams.contains("param2"))
    }

    fun testPreview_sqlTerminatesWithSemicolon() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public void q(JdbcTemplate jt) {
                    jt.queryForList("SELECT * FROM t");
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull(result)
        assertTrue(result!!.sql.trimEnd().endsWith(";"))
    }

    fun testPreview_nonJdbcCall_returnsNull() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void q() {
                    session.createQuery("from User");
                }
            }
        """.trimIndent())
        val call = findFirstCall("createQuery")!!
        val result = previewOfCall(call)
        assertNull(result)
    }

    // ------------------------------------------------------------------
    // 端到端：用户真实 dynamicSqlWithStringBuilder 场景
    // ------------------------------------------------------------------

    fun testPreview_dynamicSqlWithStringBuilder_runtimeValuesMarkedIncomplete() {
        // 用户原始代码：StringBuilder + 链式 append + 条件运行时值
        // 关键验证：运行时参数值（username/status/roleId/deptId）被标记为占位而非丢失
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            import org.springframework.util.StringUtils;
            public class Demo {
                public List<Map<String, Object>> dynamicSqlWithStringBuilder(
                        JdbcTemplate jdbcTemplate, String username, Integer status,
                        Integer roleId, Integer deptId) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT u.id, u.username, u.age, u.email, u.status, ");
                    sql.append("       r.name AS role_name, d.name AS dept_name ");
                    sql.append("FROM user u ");
                    sql.append("LEFT JOIN role r ON u.role_id = r.id ");
                    sql.append("LEFT JOIN department d ON u.dept_id = d.id ");
                    sql.append("WHERE u.deleted = 0 ");
                    if (StringUtils.hasText(username)) {
                        sql.append("AND u.username LIKE '%").append(username).append("%' ");
                    }
                    if (status != null) {
                        sql.append("AND u.status = ").append(status).append(" ");
                    }
                    sql.append("ORDER BY u.id DESC");
                    return jdbcTemplate.queryForList(sql.toString());
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        // 通过 toString() 引用定位到 StringBuilder 变量
        val locatorCall = JdbcTemplateSqlLocator.locateFromCall(call)
        assertNotNull("应定位到 JdbcTemplate 调用", locatorCall)
        val result = JdbcTemplateSqlPreviewService.preview(locatorCall!!, call)
        assertNotNull(result)
        // 静态片段应完整保留（格式化后可能有换行，用 contains 检查关键 token）
        val compact = result!!.sql.replace("\\s+".toRegex(), " ").trim()
        assertTrue("应包含 SELECT 列 u.id", compact.contains("u.id,"))
        assertTrue("应包含 SELECT 列 u.username", compact.contains("u.username,"))
        assertTrue("应包含 role_name 别名", compact.contains("role_name"))
        assertTrue("应包含 dept_name 别名", compact.contains("dept_name"))
        assertTrue("应包含 FROM user u", compact.contains("FROM user u"))
        assertTrue("应包含 LEFT JOIN role", compact.contains("LEFT JOIN role r"))
        assertTrue("应包含 LEFT JOIN department", compact.contains("LEFT JOIN department d"))
        assertTrue("应包含 WHERE deleted = 0", compact.contains("WHERE u.deleted = 0"))
        assertTrue("应包含 ORDER BY", compact.contains("ORDER BY u.id DESC"))
        // 运行时值不应丢失（被标记为占位）
        assertTrue("应标记 username 为运行时值", result.sql.contains("username"))
        assertTrue("应标记 status 为运行时值", result.sql.contains("status"))
        // 含运行时值 → warning 提示
        assertNotNull("应有降级 warning", result.warning)
        assertTrue("warning 应提及运行时值", result.warning!!.contains("运行时"))
        // R3：无 else 的条件追加 → 保留片段 + 前置说明注释（可能不出现）
        assertTrue("条件追加应带说明注释: ${result.sql}", result.sql.contains("条件追加"))
    }

    // ------------------------------------------------------------------
    // R1/R2/R3 端到端：用户 getAreaPowerGeneration 样本（循环 + 三元 + 尾部拼接）
    // ------------------------------------------------------------------

    fun testPreview_loopTernaryConcat_keepsWellFormedSql() {
        // 用户原始代码：for 循环里按三元拼 id 条件，再拼进 WHERE (...)，尾部拼 startTime
        // 关键验证：占位是单条自洽注释 → WHERE 的右括号在场、引号平衡、无源码裸露
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public java.util.List<java.util.Map<String, Object>> getAreaPowerGeneration(
                        JdbcTemplate jdbcTemplate, java.util.List<String> ids, String startTime) {
                    StringBuilder idStr = new StringBuilder();
                    for (int k = 0; k < ids.size(); k++) {
                        idStr.append(k == 0 ? "id='" + ids.get(k) + "' " : "or id='" + ids.get(k) + "' ");
                    }
                    String sql = "SELECT * FROM hplt.autogen.calpoint " +
                        " WHERE (" + idStr + ") and time >= '" + startTime + "' tz('Asia/Shanghai')";
                    return jdbcTemplate.queryForList(sql);
                }
            }
        """.trimIndent())
        val call = findFirstCall("queryForList")!!
        val result = previewOfCall(call)
        assertNotNull("应定位到 JdbcTemplate 调用", result)
        val compact = result!!.sql.replace("\\s+".toRegex(), " ").trim()
        assertTrue("WHERE ( 应在场: $compact", compact.contains("WHERE ("))
        assertTrue("WHERE (...) 的右括号应在场: $compact", compact.contains(")"))
        assertEquals("单引号应平衡: $compact", 0, compact.count { it == '\'' } % 2)
        assertTrue("占位应形如 /* 运行时值: ... */: $compact", compact.contains("/* 运行时值: "))
        assertTrue("静态尾段应保留: $compact", compact.contains("tz") && compact.contains("Asia/Shanghai"))
        assertFalse("三元源码不应以活 SQL 形态出现: $compact", compact.contains("k == 0 ?"))
        assertNotNull("应有降级 warning", result.warning)
        assertTrue("warning 应提及运行时值", result.warning!!.contains("运行时"))
    }

    // ------------------------------------------------------------------
    // 团队自研 DAO 工具层（DbHelper 等）的 delete / insert 场景
    // ------------------------------------------------------------------

    fun testPreview_dbHelperDeleteNamedParam() {
        // 用户真实场景：dbHelper.delete(sql, paramMap)——方法名 delete 不在 Spring JdbcTemplate
        // 方法集（update/query 族），但第 1 实参为字符串 SQL，应被识别为 SQL 预览候选。
        myFixture.configureByText("Demo.java", """
            import java.util.HashMap;
            import java.util.Map;
            public class Demo {
                public int deleteByDbHelper(DbHelper dbHelper, Integer id) {
                    String sql = "UPDATE user SET deleted = 1, update_time = NOW() WHERE id = :id";
                    Map<String, Object> paramMap = new HashMap<>();
                    paramMap.put("id", id);
                    return dbHelper.delete(sql, paramMap);
                }
                static class DbHelper {
                    public int delete(String sql, Map<String, Object> paramMap) { return 0; }
                }
            }
        """.trimIndent())
        val call = findFirstCall("delete")!!
        val result = previewOfCall(call)
        assertNotNull("dbHelper.delete 应被识别为 SQL 预览", result)
        // 命名参数 :id 被识别
        assertTrue("应识别命名参数 id", result!!.namedParamHints.containsKey("id"))
        // SQL 含 DELETE/UPDATE 语义
        assertTrue("SQL 应含 UPDATE", result.sql.contains("UPDATE"))
        assertTrue("SQL 应含 deleted", result.sql.contains("deleted"))
    }

    fun testPreview_dbHelperInsertNamedParam() {
        // DbHelper.insert(sql, paramMap) 同样应被识别（方法名 insert）
        myFixture.configureByText("Demo.java", """
            import java.util.HashMap;
            import java.util.Map;
            public class Demo {
                public int insertByDbHelper(DbHelper dbHelper, String name) {
                    String sql = "INSERT INTO user(username, status) VALUES (:name, 1)";
                    Map<String, Object> paramMap = new HashMap<>();
                    paramMap.put("name", name);
                    return dbHelper.insert(sql, paramMap);
                }
                static class DbHelper {
                    public int insert(String sql, Map<String, Object> paramMap) { return 0; }
                }
            }
        """.trimIndent())
        val call = findFirstCall("insert")!!
        val result = previewOfCall(call)
        assertNotNull("dbHelper.insert 应被识别为 SQL 预览", result)
        assertTrue("应识别命名参数 name", result!!.namedParamHints.containsKey("name"))
        assertTrue("SQL 应含 INSERT", result.sql.contains("INSERT"))
    }
}
