package cn.song.mybatisplus.visualizer.psource

import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [SqlStringConcatenator] PSI 集成测试（BasePlatformTestCase，真实 Java PSI 解析）。
 *
 * 覆盖：字面量 `+` 拼接、collectStringLiterals 收集、StringBuilder 聚合（含链式 append）。
 */
class SqlStringConcatenatorTest : BasePlatformTestCase() {

    /** 从当前文件内查找第一个匹配类型的表达式。 */
    private fun <T : PsiExpression> findFirstExpr(type: Class<T>): T? {
        val file = myFixture.file as? PsiJavaFile ?: return null
        return PsiTreeUtil.findChildrenOfType(file, type).firstOrNull()
    }

    /** 聚合指定 StringBuilder / StringBuffer 变量（取其首次出现的引用）。 */
    private fun concatVariable(name: String): SqlStringConcatenator.ConcatenationResult? {
        val file = myFixture.file as? PsiJavaFile ?: error("not java file")
        val ref = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java)
            .firstOrNull { it.referenceName == name }
            ?: error("未找到变量引用 $name")
        return SqlStringConcatenator.concat(ref)
    }

    /** 注释起始符出现次数（占位必须恰好一条自洽注释）。 */
    private fun commentOpenCount(sql: String): Int = sql.split("/*").size - 1

    /** 注释结束符出现次数（有源码裸露时会出现悬空结束符 → 多于 1）。 */
    private fun commentCloseCount(sql: String): Int = sql.split("*/").size - 1

    // ------------------------------------------------------------------
    // 路径 A — 字面量 `+` 拼接
    // ------------------------------------------------------------------

    fun testConcat_literalLiteral() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    String sql = "SELECT " + "FROM t";
                }
            }
        """.trimIndent())
        val first = findFirstExpr(PsiLiteralExpression::class.java)
        assertNotNull(first)
        // 直接字面量求值
        val result = SqlStringConcatenator.concat(first!!)
        assertNotNull(result)
        assertEquals("SELECT ", result!!.sql)
        assertTrue(result.complete)
    }

    fun testCollectStringLiterals_nestedConcatenation() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    String sql = "SELECT " + "a, " + "b FROM t";
                }
            }
        """.trimIndent())
        val file = myFixture.file as PsiJavaFile
        val codeBlock = PsiTreeUtil.findChildrenOfType(file, PsiCodeBlock::class.java).first()
        val literals = SqlStringConcatenator.collectStringLiterals(codeBlock)
        assertEquals(3, literals.size)
        assertEquals("SELECT ", literals[0])
        assertEquals("a, ", literals[1])
        assertEquals("b FROM t", literals[2])
    }

    // ------------------------------------------------------------------
    // 路径 B — StringBuilder 聚合（含链式 append）
    // ------------------------------------------------------------------

    fun testConcat_stringBuilderWithAppends() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT ");
                    sql.append("FROM t");
                    jdbcTemplate.queryForList(sql.toString(), null);
                }
            }
        """.trimIndent())
        // 找 StringBuilder 变量引用，通过公共 concat() 聚合
        val file = myFixture.file as? PsiJavaFile ?: error("not java file")
        val ref = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java)
            .first { it.referenceName == "sql" }
        val result = SqlStringConcatenator.concat(ref)
        assertNotNull(result)
        assertEquals("SELECT FROM t", result!!.sql)
        assertTrue(result.complete)
    }

    fun testConcat_chainedAppendWithRuntimeValues_marksIncomplete() {
        // 用户真实场景：链式 append 中混有运行时参数（方法参数）
        // sql.append("AND u.username LIKE '%").append(username).append("%' ");
        // 期望：字符串字面量保留，运行时值标记为 /*运行时值*/，complete = false
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query(String username) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT * FROM user WHERE 1=1 ");
                    sql.append("AND u.username LIKE '%").append(username).append("%' ");
                    sql.append("ORDER BY u.id DESC");
                }
            }
        """.trimIndent())
        // 找 StringBuilder 变量引用，通过公共 concat() 聚合
        val file = myFixture.file as? PsiJavaFile ?: error("not java file")
        val ref = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java)
            .first { it.referenceName == "sql" }
        val result = SqlStringConcatenator.concat(ref)
        assertNotNull(result)
        // 应包含静态字符串片段
        assertTrue("应包含 SELECT 片段", result!!.sql.contains("SELECT * FROM user WHERE 1=1 "))
        assertTrue("应包含 LIKE 片段", result.sql.contains("AND u.username LIKE '%"))
        assertTrue("应包含 ORDER BY 片段", result.sql.contains("ORDER BY u.id DESC"))
        // 运行时值应被标记为占位
        assertTrue("应标记运行时值 username", result.sql.contains("username"))
        // 含运行时值 → complete = false
        assertFalse("含运行时值应标记 incomplete", result.complete)
    }

    // ------------------------------------------------------------------
    // collectStringLiterals 工具
    // ------------------------------------------------------------------

    fun testCollectStringLiterals_singleLiteral() {
        myFixture.configureByText("Demo.java", """
            public class Demo {
                String s = "hello";
            }
        """.trimIndent())
        val literal = findFirstExpr(PsiLiteralExpression::class.java)!!
        val result = SqlStringConcatenator.collectStringLiterals(literal)
        assertEquals(1, result.size)
        assertEquals("hello", result[0])
    }

    fun testCollectStringLiterals_intLiteralReturnsEmpty() {
        // int 字面量（非 String）→ collectStringLiterals 只收集字符串字面量，返回空
        myFixture.configureByText("Demo.java", """
            public class Demo {
                int x = 42;
            }
        """.trimIndent())
        val literal = findFirstExpr(PsiLiteralExpression::class.java)
        assertNotNull(literal)
        // 42 是 int 字面量（非 String）→ collectStringLiterals 返回空
        val result = SqlStringConcatenator.collectStringLiterals(literal!!)
        assertTrue("非字符串字面量应返回空", result.isEmpty())
    }

    fun testConcat_nullInputReturnsNull() {
        assertNull(SqlStringConcatenator.concat(null))
    }

    // ------------------------------------------------------------------
    // R1 — 运行时值占位必须是单条自洽注释
    // ------------------------------------------------------------------

    fun testPlaceholder_wellFormedCommentSanitizesQuotesAndTerminator() {
        // 占位文本里同时含注释结束符与单/双引号：不清理就会「注释提前闭合 + 源码裸露 + 悬空结束符」，
        // 且占位落在字符串字面量内部时原文引号会截断字符串
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    StringBuilder sb = new StringBuilder("SELECT * FROM t WHERE note = '");
                    sb.append(raw("a*/b") + "'");
                }
            }
        """.trimIndent())
        val result = concatVariable("sb")
        assertNotNull(result)
        assertFalse("含运行时值应标记 incomplete", result!!.complete)
        assertEquals("应只有一条注释起始: ${result.sql}", 1, commentOpenCount(result.sql))
        assertEquals("应只有一个注释结束符（不允许多余的悬空结束符）: ${result.sql}", 1, commentCloseCount(result.sql))
        assertEquals("引号应平衡（原文引号已替换为同形安全字符）: ${result.sql}", 0, result.sql.count { it == '\'' } % 2)
        assertTrue("占位应形如 /* 运行时值: ... */", Regex("/\\* 运行时值: .+ \\*/").containsMatchIn(result.sql))
        assertFalse("原文的 */ 不应原样出现: ${result.sql}", result.sql.contains("a*/b"))
    }

    // ------------------------------------------------------------------
    // R2 — 条件表达式（三元）
    // ------------------------------------------------------------------

    fun testConcat_conditionalStraightLine_singlePlaceholderComment() {
        // 用户样本：k == 0 ? "id='" + ids.get(k) + "' " : "or id='" + ids.get(k) + "' "
        // 两支文本不同 → 单条占位注释，绝不把两支都拼进去
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query(java.util.List<String> ids, int k) {
                    StringBuilder sb = new StringBuilder("SELECT * FROM t WHERE ");
                    sb.append(k == 0 ? "id='" + ids.get(k) + "' " : "or id='" + ids.get(k) + "' ");
                }
            }
        """.trimIndent())
        val result = concatVariable("sb")
        assertNotNull(result)
        assertFalse("三元两支不同 → 占位（非静态）", result!!.complete)
        assertEquals("应只有一条注释起始: ${result.sql}", 1, commentOpenCount(result.sql))
        assertEquals("应只有一个注释结束符: ${result.sql}", 1, commentCloseCount(result.sql))
        assertTrue("占位应形如 /* 运行时值: ... */", Regex("/\\* 运行时值: .+ \\*/").containsMatchIn(result.sql))
        assertTrue("静态前缀应保留: ${result.sql}", result.sql.startsWith("SELECT * FROM t WHERE "))
    }

    fun testConcat_conditionalConstantCondition_picksBranch() {
        // 条件是 true / false 字面量 → 直接聚合对应分支（不占位）
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    StringBuilder sb = new StringBuilder();
                    sb.append(true ? "AND a = 1 " : "AND b = 2 ");
                }
            }
        """.trimIndent())
        val result = concatVariable("sb")
        assertNotNull(result)
        assertEquals("AND a = 1 ", result!!.sql)
        assertTrue("常量条件取分支后完全静态", result.complete)
    }

    fun testConcat_conditionalIdenticalBranches_folds() {
        // 两分支聚合文本相同 → 折叠取该文本（运行期走哪支都一样，文本确定）
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query(boolean flag) {
                    StringBuilder sb = new StringBuilder("SELECT 1 ");
                    sb.append(flag ? "AND a = 1 " : "AND a = 1 ");
                }
            }
        """.trimIndent())
        val result = concatVariable("sb")
        assertNotNull(result)
        assertEquals("SELECT 1 AND a = 1 ", result!!.sql)
        assertTrue("两分支相同 → 文本确定，判静态", result.complete)
        assertFalse("不应出现占位", result.sql.contains("运行时值"))
    }

    // ------------------------------------------------------------------
    // R3 — 控制流感知
    // ------------------------------------------------------------------

    fun testConcat_appendInsideLoop_degradedToPlaceholder() {
        // 循环内 append：运行期重复 N 次，线性拼接必错 → 贡献整体降级为一条占位注释
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query(java.util.List<String> ids) {
                    StringBuilder sb = new StringBuilder("SELECT * FROM t WHERE 1=1 ");
                    for (int k = 0; k < ids.size(); k++) {
                        sb.append("or id='").append(ids.get(k)).append("' ");
                    }
                }
            }
        """.trimIndent())
        val result = concatVariable("sb")
        assertNotNull(result)
        assertFalse("循环内追加应标记 incomplete", result!!.complete)
        assertTrue("应标明循环内重复追加: ${result.sql}", result.sql.contains("循环内追加"))
        assertFalse("循环内片段不应线性拼接进 SQL: ${result.sql}", result.sql.contains("or id='"))
        assertEquals("同一循环只降级一次: ${result.sql}", 1, commentOpenCount(result.sql))
    }

    fun testConcat_appendInsideIfElsePaired_bothDegraded() {
        // if/else 两支都对同一 builder 追加：互斥片段不能同时出现在 SQL 里 → 两支都降级
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query(boolean flag) {
                    StringBuilder sb = new StringBuilder("SELECT * FROM t WHERE 1=1 ");
                    if (flag) {
                        sb.append("AND a = 1 ");
                    } else {
                        sb.append("AND a = 2 ");
                    }
                }
            }
        """.trimIndent())
        val result = concatVariable("sb")
        assertNotNull(result)
        assertFalse("互斥分支追加应标记 incomplete", result!!.complete)
        assertTrue("应标明互斥分支: ${result.sql}", result.sql.contains("互斥分支"))
        assertFalse("互斥片段不应进 SQL: ${result.sql}", result.sql.contains("AND a = 1"))
        assertFalse("互斥片段不应进 SQL: ${result.sql}", result.sql.contains("AND a = 2"))
    }

    fun testConcat_appendInsideIfWithoutElse_keptWithNote() {
        // 无 else 的条件追加：保留片段，但前面插一条说明注释（可能不出现）
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query(boolean flag) {
                    StringBuilder sb = new StringBuilder("SELECT * FROM t WHERE 1=1 ");
                    if (flag) {
                        sb.append("AND a = 1 ");
                    }
                }
            }
        """.trimIndent())
        val result = concatVariable("sb")
        assertNotNull(result)
        assertFalse("条件追加应标记 incomplete", result!!.complete)
        assertTrue("应保留片段: ${result.sql}", result.sql.contains("AND a = 1"))
        assertTrue("应插入说明注释: ${result.sql}", result.sql.contains("条件追加"))
    }

    fun testConcat_straightLineAppends_unchanged() {
        // 直线代码回归：既有输出不得被 R3 改变
        myFixture.configureByText("Demo.java", """
            public class Demo {
                public void query() {
                    StringBuilder sb = new StringBuilder();
                    sb.append("SELECT ").append("a").append(" FROM t");
                }
            }
        """.trimIndent())
        val result = concatVariable("sb")
        assertNotNull(result)
        assertEquals("SELECT a FROM t", result!!.sql)
        assertTrue("直线路径完全静态", result.complete)
    }
}
