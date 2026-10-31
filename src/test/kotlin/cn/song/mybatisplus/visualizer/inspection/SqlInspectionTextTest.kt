package cn.song.mybatisplus.visualizer.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEW-26 编辑器内 SQL 检查的**纯逻辑内核**测试（[SqlInspectionText]）。
 *
 * 五条规则各配正例 + 反例。反例是这一批最重要的部分：P-4 判据是「语料误报为 0 才允许默认开」，
 * 而误报全部来自「把合法形态当问题」——每一条反例都对应一个真实 mapper 语料里会出现的写法
 * （`docs/需求文档/MPJ用法收集与兼容性测试清单.md` 的 X01~X05 节）。
 *
 * 纯 JUnit：不启平台 fixture，秒级完成。
 */
class SqlInspectionTextTest {

    // ------------------------------------------------------------------
    // 规则 1：${} 注入
    // ------------------------------------------------------------------

    @Test
    fun `规则1_正例_参数直接拼进SQL结构必报`() {
        val text = "select * from t order by \${orderBy}"
        val findings = SqlInspectionText.dollarFindings(text)
        assertEquals(1, findings.size)
        assertEquals(SqlInspectionRule.DOLLAR_INJECTION, findings[0].rule)
        assertEquals("\${orderBy}", findings[0].token)
        assertEquals(text.indexOf("\${orderBy}"), findings[0].start)
    }

    @Test
    fun `规则1_正例_表名与LIMIT拼接都报`() {
        val text = "select * from \${tableName} where a = 1 limit \${size}"
        assertEquals(2, SqlInspectionText.dollarFindings(text).size)
    }

    @Test
    fun `规则1_反例_bind静态常量列别名不报`() {
        // 语料 X02：22 处 ${} 全部是「bind 常量 → 结果集列别名」（本仓会 0 命中）
        val text = "select count(t.id) \${orderCount}, sum(t.fee) \${feeSum} from t"
        val findings = SqlInspectionText.dollarFindings(text, staticBindNames = setOf("orderCount", "feeSum"))
        assertTrue("bind 静态常量作列别名不得报：$findings", findings.isEmpty())
    }

    @Test
    fun `规则1_反例_MP_Wrapper片段不报`() {
        val text = "select * from t \${ew.customSqlSegment}"
        assertTrue(SqlInspectionText.dollarFindings(text).isEmpty())
    }

    @Test
    fun `规则1_反例_静态字段引用表达式不报`() {
        val text = "select \${@com.acme.Const@COLUMN} from t"
        assertTrue(SqlInspectionText.dollarFindings(text).isEmpty())
    }

    @Test
    fun `规则1_反例_转义的美元号不是token`() {
        val text = "select a from t where b like 'x\\\${y}'"
        assertTrue(SqlInspectionText.dollarFindings(text).isEmpty())
    }

    @Test
    fun `规则1_自证_bind值静态判定三态`() {
        assertTrue(SqlInspectionText.isStaticBindValue("@com.acme.Dic@CODE"))
        assertTrue(SqlInspectionText.isStaticBindValue("'fixed'"))
        assertTrue(SqlInspectionText.isStaticBindValue("42"))
        assertTrue("来自参数的绑定不得算静态（正是注入面）", !SqlInspectionText.isStaticBindValue("p.orderBy"))
        assertTrue("空值不得算静态", !SqlInspectionText.isStaticBindValue(null))
    }

    // ------------------------------------------------------------------
    // 规则 2：#{} 参数缺失
    // ------------------------------------------------------------------

    @Test
    fun `规则2_正例_参数名不在方法签名里必报`() {
        val text = "select * from t where id = #{userId} and name = #{name}"
        val findings = SqlInspectionText.missingParameterFindings(text, setOf("name", "param1"))
        assertEquals(1, findings.size)
        assertEquals("userId", findings[0].messageArgs[0])
    }

    @Test
    fun `规则2_反例_声明过的参数与语句内bind都不报`() {
        val text = "select * from t where a = #{a} and b = #{bound} and c = #{it}"
        val findings = SqlInspectionText.missingParameterFindings(
            text,
            knownRoots = setOf("a", "param1", "param2"),
            declaredRoots = setOf("bound", "it"),
        )
        assertTrue("已声明名字不得报：$findings", findings.isEmpty())
    }

    @Test
    fun `规则2_反例_只判根名属性路径不判`() {
        // #{p.startTime} 只看 p —— 属性链是否合法静态不可判定
        val text = "select * from t where a = #{p.startTime} and b = #{p.list[0].id}"
        assertTrue(SqlInspectionText.missingParameterFindings(text, setOf("p")).isEmpty())
    }

    @Test
    fun `规则2_反例_OGNL表达式型占位不判`() {
        val text = "select * from t where a = #{1+1}"
        assertTrue(SqlInspectionText.missingParameterFindings(text, setOf("a")).isEmpty())
    }

    @Test
    fun `规则2_自证_单参数且无Param时不可判定`() {
        // MyBatis 允许 #{任意实体属性}，此时必须放弃判定（返回 null）
        assertNull(SqlInspectionText.paramRoots(listOf("record"), hasParamAnnotation = false, paramCount = 1))
        // 单参但带 @Param → 可判定
        assertEquals(
            setOf("record", "param1", "_parameter", "_databaseId", "ew", "ewc"),
            SqlInspectionText.paramRoots(listOf("record"), hasParamAnnotation = true, paramCount = 1),
        )
        // 多参 → 补 paramN
        assertTrue(
            SqlInspectionText.paramRoots(listOf("a", "b"), false, 2)!!.containsAll(listOf("a", "b", "param1", "param2")),
        )
        // 无参 → 只认保留名（#{anything} 应报）
        assertEquals(setOf("_parameter", "_databaseId"), SqlInspectionText.paramRoots(emptyList(), false, 0))
    }

    // ------------------------------------------------------------------
    // 规则 3：resultMap 属性
    // ------------------------------------------------------------------

    @Test
    fun `规则3_正例_属性不在实体类必报`() {
        assertEquals("badColumn", SqlInspectionText.unknownResultMapProperty("badColumn", setOf("id", "merchantId")))
    }

    @Test
    fun `规则3_反例_字段与getter派生名都算存在`() {
        assertNull(SqlInspectionText.unknownResultMapProperty("merchantId", setOf("id", "merchantId")))
    }

    @Test
    fun `规则3_反例_类型未解析时不判定`() {
        // members = null 表示类解析不出 → 必须沉默（0 误报的前提）
        assertNull(SqlInspectionText.unknownResultMapProperty("x", null))
        assertNull(SqlInspectionText.unknownResultMapProperty("x", emptySet()))
    }

    @Test
    fun `规则3_反例_属性链只判首段`() {
        assertNull(SqlInspectionText.unknownResultMapProperty("merchant.id", setOf("merchant")))
    }

    // ------------------------------------------------------------------
    // 规则 4：方言引号
    // ------------------------------------------------------------------

    @Test
    fun `规则4_正例_反引号写进PG方言必报`() {
        val findings = SqlInspectionText.dialectQuoteFindings("select `name` from t", setOf('"'), "PostgreSQL")
        assertEquals(1, findings.size)
        assertTrue(findings[0].messageArgs[1] == "PostgreSQL")
    }

    @Test
    fun `规则4_正例_方括号写进MySQL方言必报`() {
        val findings = SqlInspectionText.dialectQuoteFindings("select [name] from t", setOf('`'), "MySQL")
        assertEquals(1, findings.size)
    }

    @Test
    fun `规则4_反例_方言接受的引号不报`() {
        assertTrue(SqlInspectionText.dialectQuoteFindings("select `name` from t", setOf('`'), "MySQL").isEmpty())
        assertTrue(
            SqlInspectionText.dialectQuoteFindings("select [name] from t", setOf('[', '"'), "SQL Server").isEmpty(),
        )
    }

    @Test
    fun `规则4_反例_字符串字面量与注释里的引号不报`() {
        val text = "select 'a `b` c' from t -- [`x`]\n/* [y] */"
        assertTrue(
            "字面量与注释不得参与判定：${SqlInspectionText.dialectQuoteFindings(text, setOf('"'), "PostgreSQL")}",
            SqlInspectionText.dialectQuoteFindings(text, setOf('"'), "PostgreSQL").isEmpty(),
        )
    }

    @Test
    fun `规则4_反例_数组下标不是方括号标识符`() {
        // [0] / [i] 不是标识符引用形态（首字符须字母/下划线）
        assertTrue(SqlInspectionText.dialectQuoteFindings("select arr[0] from t", setOf('`'), "MySQL").isEmpty())
    }

    @Test
    fun `规则4_自证_涂白保持长度与偏移对齐`() {
        val text = "select 'abc' from t where a = 1"
        val masked = SqlInspectionText.stripLiteralsAndComments(text)
        assertEquals("涂白必须等长（偏移对齐是精确标红的前提）", text.length, masked.length)
        assertEquals(text.indexOf("from"), masked.indexOf("from"))
        assertTrue("字面量内容须被涂白", !masked.contains("abc"))
    }

    // ------------------------------------------------------------------
    // 规则 5：OGNL 常量条件
    // ------------------------------------------------------------------

    @Test
    fun `规则5_正例_恒真条件`() {
        assertEquals(true, SqlInspectionText.constantTestTruth("true"))
        assertEquals(true, SqlInspectionText.constantTestTruth("1==1"))
        assertEquals(true, SqlInspectionText.constantTestTruth("1 != 2"))
    }

    @Test
    fun `规则5_正例_恒假条件`() {
        assertEquals(false, SqlInspectionText.constantTestTruth("false"))
        assertEquals(false, SqlInspectionText.constantTestTruth("1 == 2"))
    }

    @Test
    fun `规则5_反例_引用参数的条件不判`() {
        assertNull(SqlInspectionText.constantTestTruth("p.startTime != null"))
        assertNull(SqlInspectionText.constantTestTruth("list != null and list.size() > 0"))
        assertNull(SqlInspectionText.constantTestTruth("status == 1"))
    }

    @Test
    fun `规则5_反例_静态字段引用不判`() {
        // 值来自编译期常量，但静态层不注入 staticFieldValues → 求值不可靠，必须沉默
        assertNull(SqlInspectionText.constantTestTruth("@com.acme.Const@FLAG == true"))
    }

    @Test
    fun `规则5_反例_解析失败不判`() {
        assertNull(SqlInspectionText.constantTestTruth("1 =="))
        assertNull(SqlInspectionText.constantTestTruth(""))
    }

    // ------------------------------------------------------------------
    // token 扫描基础
    // ------------------------------------------------------------------

    @Test
    fun `自证_token扫描跳过转义与无闭合`() {
        assertEquals(0, SqlInspectionText.hashTokens("a = \\#{x}").size)
        assertEquals("无闭合不是 token（MyBatis 原样保留）", 0, SqlInspectionText.hashTokens("a = #{x").size)
        val token = SqlInspectionText.hashTokens("a = #{p.id,jdbcType=BIGINT}").single()
        assertEquals("p.id", token.expression)
        assertEquals("p", token.root)
    }
}