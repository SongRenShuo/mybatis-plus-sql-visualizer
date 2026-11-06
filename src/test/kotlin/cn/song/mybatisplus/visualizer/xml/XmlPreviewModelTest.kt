package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览模型测试（T-M24-04）：变量收集（排除保留键）、启发式默认真值（`== null` 关闭）、
 * 渲染状态切换（条件开关 / foreach 次数与 null / bind 覆盖）、默认渲染单源。
 */
class XmlPreviewModelTest {

    private fun model(mapper: String, statementId: String = "s1"): XmlPreviewModel {
        val parsed = XmlMapperParser().parse(mapper)
        return XmlPreviewModel.from(parsed.statements.first { it.id == statementId }, parsed.errors)
    }

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------------
    // 变量收集
    // ------------------------------------------------------------------

    @Test
    fun `变量收集_来自bind与dollar表达式_排除保留键`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="p" value="'%' + _parameter.title + '%'"/>
                <bind name="q" value="list.size() + 1"/>
                SELECT * FROM t WHERE a = ${'$'}{ew.customSqlSegment} AND b LIKE #{p}
              </select>
            </mapper>
        """.trimIndent())
        // _parameter 是保留键（不收集）；title 归模拟参数对象属性；list/ew 是普通根变量
        assertEquals(setOf("title"), m.paramProps)
        assertEquals(setOf("list", "ew"), m.variables)
        assertEquals(listOf("p", "q"), m.bindViews.map { it.name })
    }

    @Test
    fun `变量收集_if条件不参与`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t<if test="status != null and type == 1"> WHERE x = 1</if></select>
            </mapper>
        """.trimIndent())
        // test 表达式由开关驱动，不收集变量
        assertEquals(emptySet<String>(), m.variables)
        assertEquals(listOf("status != null and type == 1"), m.conditionViews.map { it.id })
    }

    // ------------------------------------------------------------------
    // 启发式默认值
    // ------------------------------------------------------------------

    @Test
    fun `启发式默认_非判空展开_判null关闭`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <where>
                  <if test="a != null">AND a = 1</if>
                  <if test="b == null">AND b IS NULL</if>
                  <if test="c != null and c != ''">AND c = #{c}</if>
                  <if test="d">AND d = 1</if>
                  <if test="flag == false">AND e = 2</if>
                </where>
              </select>
            </mapper>
        """.trimIndent())
        val defaults = m.conditionViews.map { it.id to it.defaultEnabled }.toMap()
        assertEquals(true, defaults["a != null"])
        assertEquals(false, defaults["b == null"])
        assertEquals(true, defaults["c != null and c != ''"])
        assertEquals(true, defaults["d"])
        assertEquals(true, defaults["flag == false"])
    }

    // ------------------------------------------------------------------
    // 渲染状态
    // ------------------------------------------------------------------

    @Test
    fun `foreach默认展开两次_次数可调`() {
        // T-GoldenDefense：collection="map.items" 形态锁定为金样矩阵 X5（XmlMapParamSpecMatrixTest）
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              </select>
            </mapper>
        """.trimIndent())
        assertEquals(XmlPreviewModel.DEFAULT_FOREACH_SIZE, m.defaultState().forEachCounts["ids"])
        assertEquals("SELECT * FROM t WHERE id IN ( ? , ? )", compact(m.renderSql().sql))
        val state = m.defaultState().copy(forEachCounts = mapOf("ids" to 0))
        assertEquals("SELECT * FROM t WHERE id IN", compact(m.renderSql(state).sql))
    }

    @Test
    fun `foreach集合为null时错误提示`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              </select>
            </mapper>
        """.trimIndent())
        val state = m.defaultState().copy(forEachNulls = setOf("ids"))
        val result = m.renderSql(state)
        assertEquals("SELECT * FROM t WHERE id IN", compact(result.sql))
        assertTrue(result.errors.any { it.contains("evaluated to a null value") })
    }

    @Test
    fun `bind覆盖优先于表达式求值`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="pattern" value="'%' + name + '%'"/>
                SELECT * FROM t WHERE name LIKE #{pattern}
              </select>
            </mapper>
        """.trimIndent())
        // 默认：name 占位 "1" → '%1%'
        assertEquals("SELECT * FROM t WHERE name LIKE ?", compact(m.renderSql().sql))
        val state = m.defaultState().copy(bindOverrides = mapOf("pattern" to "'%x%'"))
        assertEquals("SELECT * FROM t WHERE name LIKE ?", compact(m.renderSql(state).sql))
        assertEquals("pattern", m.renderSql(state).mappings.single().property)
    }

    @Test
    fun `条件开关切换分支`() {
        // T-GoldenDefense：Map 参数条件（map.status != null）开关语义锁定为金样矩阵 X3（XmlMapParamSpecMatrixTest）
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <if test="a != null">WHERE a = 1</if>
                <if test="b != null"> AND b = 2</if>
              </select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = 2", compact(m.renderSql().sql))
        val state = m.defaultState().copy(enabledIds = setOf("a != null"))
        assertEquals("SELECT * FROM t WHERE a = 1", compact(m.renderSql(state).sql))
        val none = m.defaultState().copy(enabledIds = emptySet())
        assertEquals("SELECT * FROM t", compact(m.renderSql(none).sql))
    }

    @Test
    fun `表达式解析失败的test默认展开_开关可关闭`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <if test="not valid @@ expression">WHERE a = 1</if>
              </select>
            </mapper>
        """.trimIndent())
        // 解析失败 → 默认 true（预览容错）
        assertEquals("SELECT * FROM t WHERE a = 1", compact(m.renderSql().sql))
        val state = m.defaultState().copy(enabledIds = emptySet())
        assertEquals("SELECT * FROM t", compact(m.renderSql(state).sql))
    }

    @Test
    fun `语句元信息透传`() {
        val m = model("""
            <mapper namespace="com.example.BizMapper">
              <update id="updateById" parameterType="map">UPDATE t SET a = 1 WHERE id = 2</update>
            </mapper>
        """.trimIndent(), statementId = "updateById")
        assertEquals("updateById", m.statementId)
        assertEquals(XmlStatementKind.UPDATE, m.kind)
        assertEquals("map", m.parameterType)
    }

    // ------------------------------------------------------------------
    // T-M29.5：参数回填引号映射（quoteFor 精确/前缀/未命中）
    // ------------------------------------------------------------------

    @Test
    fun `quoteFor_精确与前缀匹配`() {
        val m = XmlPreviewModel.from(
            XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE a = #{p.settlementStartDate} AND b = #{p.dicCreditStatus}</select>
                </mapper>
            """.trimIndent()).statements.single(),
            emptyList(),
            paramQuotes = mapOf(
                "p.settlementStartDate" to true,
                "p.dicCreditStatus" to false,
                "keyword" to true,
            ),
        )
        // 精确命中
        assertEquals(true, m.quoteFor("p.settlementStartDate"))
        assertEquals(false, m.quoteFor("p.dicCreditStatus"))
        assertEquals(true, m.quoteFor("keyword"))
        // 未命中 → null（面板兜底引号）
        assertEquals(null, m.quoteFor("unknown"))
    }

    @Test
    fun `quoteFor_Map参数前缀兜底`() {
        // Map 入参：键 = 参数名 p，任意 p.* 属性前缀命中（T-M29.4 兜底语义）
        // T-GoldenDefense：@Param("map") 命名形态已锁定为金样矩阵 X2（XmlMapParamSpecMatrixTest）
        val m = XmlPreviewModel.from(
            XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t WHERE a = #{p.settlementStartDate}</select>
                </mapper>
            """.trimIndent()).statements.single(),
            emptyList(),
            paramQuotes = mapOf("p" to true),
        )
        assertEquals(true, m.quoteFor("p.settlementStartDate"))
        // 精确键优先于前缀（DTO 与 Map 键并存时不误判）
        val mixed = XmlPreviewModel.from(
            XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1">SELECT * FROM t</select>
                </mapper>
            """.trimIndent()).statements.single(),
            emptyList(),
            paramQuotes = mapOf("p" to true, "p.dicCreditStatus" to false),
        )
        assertEquals(false, mixed.quoteFor("p.dicCreditStatus"))
        assertEquals(true, mixed.quoteFor("p.settlementStartDate"))
    }

    // ------------------------------------------------------------------
    // T-ExprAstPrecheck T-EP-02：if test 求值失败不再静默
    // ------------------------------------------------------------------

    @Test
    fun `if求值失败记错误_默认展开行为不变`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <if test="not valid @@ expression">WHERE a = 1</if>
              </select>
            </mapper>
        """.trimIndent())
        // 开关缺席（无 conditionOverrides）→ 走表达式求值兜底：解析失败仍默认展开 true，但必须记 errors
        val rendered = XmlSqlRenderer().render(parsed.statements.single().node, bindings = emptyMap())
        assertEquals("SELECT * FROM t WHERE a = 1", compact(rendered.sql))
        assertTrue(
            "求值失败的 test 应留下可见错误：${rendered.errors}",
            rendered.errors.any { it.contains("条件表达式无法求值，已按 true 展开——可在预览面板用开关覆盖") },
        )
        // 表达式原文随错误一并带出（用户据此定位是哪条条件）
        assertTrue(
            "错误应含 test 原文：${rendered.errors}",
            rendered.errors.any { it.contains("not valid @@ expression") },
        )
    }

    @Test
    fun `if求值成功的test不记错误`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <if test="a != null">WHERE a = 1</if>
              </select>
            </mapper>
        """.trimIndent())
        val rendered = XmlSqlRenderer().render(parsed.statements.single().node, bindings = mapOf("a" to "1"))
        assertEquals("SELECT * FROM t WHERE a = 1", compact(rendered.sql))
        assertTrue("求值成功不应记错误：${rendered.errors}", rendered.errors.isEmpty())
    }

    @Test
    fun `超长整数bind面值_模型构建与渲染降级不抛`() {
        // T-SAF-19（Ognl.kt@parse-exception-confinement）：修复前 XmlPreviewModel.from →
        // collectExpressionPaths → OgnlEvaluator.parse 对 20+ 位面值抛未捕获 NumberFormatException，
        // 模型构建直接炸穿（本文件与 renderSql 链路均无 try/catch）；修复后 parse 收敛为
        // ParseResult.Err → rootPaths 返回空集，渲染走 bind 求值 Err 降级并记 errors 行
        val m = model("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="x" value="99999999999999999999"/>
                SELECT * FROM t WHERE id = #{x}
              </select>
            </mapper>
        """.trimIndent())
        val rendered = m.renderSql()
        assertTrue(
            "bind 求值失败应留下可见错误：${rendered.errors}",
            rendered.errors.any { it.contains("无法解析 bind 表达式") },
        )
    }

    // ------------------------------------------------------------------
    // T-ExprAstPrecheck T-EP-03：条件行支持面预检标注
    // ------------------------------------------------------------------

    @Test
    fun `条件行支持面标注_三档与展开行为无关`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t
                <if test="a != null and a.size() > 0">AND a = 1</if>
                <if test="a ? b : c">AND b = 2</if>
                <if test="map.length() > 0">AND c = 3</if>
              </select>
            </mapper>
        """.trimIndent())
        val byId = m.conditionViews.associateBy { it.id }

        val supported = byId.getValue("a != null and a.size() > 0")
        assertEquals(ExprSupport.SUPPORTED, supported.support)
        assertEquals(null, supported.supportReason)
        assertEquals(null, supported.supportDetail)

        val syntax = byId.getValue("a ? b : c")
        assertEquals(ExprSupport.UNSUPPORTED_SYNTAX, syntax.support)
        assertEquals(ExprReason.SYNTAX, syntax.supportReason)
        assertTrue("解析失败应带解析器消息：${syntax.supportDetail}", !syntax.supportDetail.isNullOrBlank())

        val fallback = byId.getValue("map.length() > 0")
        assertEquals(ExprSupport.EVAL_FALLBACK, fallback.support)
        assertEquals(ExprReason.NARROW_CARRIER, fallback.supportReason)
        assertEquals("length", fallback.supportDetail)

        // 标注只是提示：默认渲染三个条件全部按启发式展开（行为零变化）
        assertEquals("SELECT * FROM t AND a = 1 AND b = 2 AND c = 3", compact(m.renderSql().sql))
    }
}
