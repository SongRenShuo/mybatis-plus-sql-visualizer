package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染管线端到端测试（T-M24-01）：mapper 文本 → 解析 → 预览模型 → 渲染。
 *
 * 覆盖：静态语句、where/if 组合、include 展开 + property、CDATA、set、foreach、
 * choose、bind、转义 `\#{`、渲染状态切换（关闭条件 / foreach 次数 / bind 覆盖）。
 */
class XmlSqlRendererTest {

    /** 解析 mapper 文本并构建预览模型（首个语句）。 */
    private fun model(mapper: String, statementId: String = "s1"): XmlPreviewModel {
        val parsed = XmlMapperParser().parse(mapper)
        val statement = parsed.statements.first { it.id == statementId }
        return XmlPreviewModel.from(statement, parsed.errors)
    }

    /** 空白归一化单行断言（段间/缩进空白不参与比较）。 */
    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    private val mapperTemplate = """
        <mapper namespace="com.example.BizMapper">
          <sql id="baseWhere">
            <if test="status != null">AND status = #{status}</if>
          </sql>
          <select id="s1" resultType="map">
            SELECT * FROM t_biz
            <where>
              <if test="type != null">AND type = #{type}</if>
              <include refid="baseWhere">
                <property name="status" value="1"/>
              </include>
            </where>
            AND create_time <![CDATA[ > ]]> #{createTime}
            <if test="ids != null">
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </if>
            <choose>
              <when test="orderType == 1">ORDER BY create_time DESC</when>
              <otherwise>ORDER BY id</otherwise>
            </choose>
            <bind name="likeName" value="'%' + name + '%'"/>
            <if test="name != null">AND name LIKE #{likeName}</if>
          </select>
        </mapper>
    """.trimIndent()

    // ------------------------------------------------------------------
    // 默认状态（全部条件按启发式展开）
    // ------------------------------------------------------------------

    @Test
    fun `默认渲染_全标签组合`() {
        val m = model(mapperTemplate)
        val result = m.renderSql()

        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND status = ? AND create_time > ? " +
                "AND id IN ( ? , ? ) ORDER BY create_time DESC AND name LIKE ?",
            compact(result.sql),
        )
        // 参数顺序：#{} 出现顺序（含 foreach 重写名）
        assertEquals(
            listOf("type", "status", "createTime", "__frch_id_0", "__frch_id_1", "likeName"),
            result.mappings.map { it.property },
        )
        // 视图收集
        assertEquals(5, m.conditionViews.size)
        assertEquals(listOf("ids"), m.forEachViews.map { it.collectionExpr })
        assertEquals(listOf("likeName"), m.bindViews.map { it.name })
        assertEquals(setOf("name"), m.variables)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `默认勾选态启发式`() {
        val m = model(mapperTemplate)
        val ids = m.conditionViews.map { it.id to it.defaultEnabled }.toMap()
        assertEquals(true, ids["type != null"])
        assertEquals(true, ids["status != null"])
        assertEquals(true, ids["ids != null"])
        assertEquals(true, ids["orderType == 1"]) // 非判空形态默认展开
        assertEquals(true, ids["name != null"])
        // == null 形态默认关闭
        val m2 = model(mapperTemplate.replace("name != null", "name == null"))
        assertEquals(false, m2.conditionViews.last { it.id == "name == null" }.defaultEnabled)
    }

    // ------------------------------------------------------------------
    // 渲染状态切换
    // ------------------------------------------------------------------

    @Test
    fun `关闭条件后where整体消失`() {
        val m = model(mapperTemplate)
        val state = m.defaultState().copy(
            enabledIds = setOf("ids != null"), // 只剩 foreach 条件
        )
        val result = m.renderSql(state)
        // where 内容为空 → WHERE 不输出（对齐 MyBatis trim 行为）
        assertEquals(
            "SELECT * FROM t_biz AND create_time > ? AND id IN ( ? , ? ) ORDER BY id",
            compact(result.sql),
        )
    }

    @Test
    fun `foreach次数可调_零次为空集`() {
        val m = model(mapperTemplate)
        val state = m.defaultState().copy(forEachCounts = mapOf("ids" to 3))
        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND status = ? AND create_time > ? " +
                "AND id IN ( ? , ? , ? ) ORDER BY create_time DESC AND name LIKE ?",
            compact(m.renderSql(state).sql),
        )
        val zero = m.defaultState().copy(forEachCounts = mapOf("ids" to 0))
        assertTrue(!compact(m.renderSql(zero).sql).contains("IN ("))
    }

    @Test
    fun `bind覆盖值优先于表达式求值`() {
        val m = model(mapperTemplate)
        val state = m.defaultState().copy(bindOverrides = mapOf("likeName" to "'%用户输入%'"))
        val result = m.renderSql(state)
        assertEquals(
            "SELECT * FROM t_biz WHERE type = ? AND status = ? AND create_time > ? " +
                "AND id IN ( ? , ? ) ORDER BY create_time DESC AND name LIKE ?",
            compact(result.sql),
        )
        assertEquals("likeName", result.mappings.last().property)
    }

    @Test
    fun `choose关闭首个when时走otherwise`() {
        val m = model(mapperTemplate)
        val state = m.defaultState().copy(enabledIds = m.defaultState().enabledIds - "orderType == 1")
        assertTrue(compact(m.renderSql(state).sql).endsWith("ORDER BY id AND name LIKE ?"))
    }

    // ------------------------------------------------------------------
    // 静态语句与边界
    // ------------------------------------------------------------------

    @Test
    fun `纯静态语句_无参数`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT 1</select>
            </mapper>
        """.trimIndent())
        val result = m.renderSql()
        assertEquals("SELECT 1", result.sql)
        assertTrue(result.mappings.isEmpty())
        assertTrue(m.conditionViews.isEmpty())
        assertTrue(m.forEachViews.isEmpty())
    }

    @Test
    fun `纯参数语句_含jdbcType属性`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE a = #{a} AND b = #{b, jdbcType=VARCHAR}</select>
            </mapper>
        """.trimIndent())
        val result = m.renderSql()
        assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", result.sql)
        assertEquals("a", result.mappings[0].property)
        assertEquals("b", result.mappings[1].property)
        assertEquals("VARCHAR", result.mappings[1].attributes["jdbcType"])
    }

    @Test
    fun `转义dollar在静态文本中原样保留`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT \${'$'}{col} FROM t</select>
            </mapper>
        """.trimIndent())
        // `\${` 转义 → 文本判定为静态 → StaticTextSqlNode 原样输出（对齐 MyBatis：转义仅在动态文本生效）
        assertEquals("SELECT \\${'$'}{col} FROM t", m.renderSql().sql)
    }

    @Test
    fun `dollar表达式未解析时输出占位并记录错误`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE ${'$'}{ew.customSqlSegment}</select>
            </mapper>
        """.trimIndent())
        val result = m.renderSql()
        // T-MbgExamplePreview R1：预览链开启占位模式——缺段写成 {{表达式}}，不再是无声空串
        assertEquals("SELECT * FROM t WHERE {{ew.customSqlSegment}}", result.sql)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `renderer直接调用时占位默认关_空值仍输出空串`() {
        // T-MbgExamplePreview R1 的默认值闸：真实 SQL 产物路径（renderer 默认参数）不受占位模式影响
        val context = DynamicContext(mapOf(DynamicContext.PARAMETER_OBJECT_KEY to null))
        TextSqlNode("SELECT * FROM t WHERE \${ew.customSqlSegment}").apply(context)
        assertEquals("SELECT * FROM t WHERE", context.getSql())
    }

    @Test
    fun `set与if组合_update语句`() {
        val m = model("""
            <mapper namespace="n">
              <update id="s1">
                UPDATE t_biz
                <set>
                  <if test="name != null">name = #{name},</if>
                  <if test="remark != null">remark = #{remark},</if>
                </set>
                WHERE id = #{id}
              </update>
            </mapper>
        """.trimIndent())
        assertEquals(
            "UPDATE t_biz SET name = ?, remark = ? WHERE id = ?",
            compact(m.renderSql().sql),
        )
        assertEquals(
            "UPDATE t_biz SET name = ? WHERE id = ?",
            compact(m.renderSql(m.defaultState().copy(enabledIds = setOf("name != null"))).sql),
        )
    }

    @Test
    fun `未知标签报错`() {
        assertThrows(XmlScriptException::class.java) {
            XmlMapperParser().parse("""
                <mapper namespace="n">
                  <select id="s1"><else>oops</else></select>
                </mapper>
            """.trimIndent())
        }
    }

    @Test
    fun `根元素非mapper报错`() {
        assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse("""<configuration></configuration>""")
        }
    }

    @Test
    fun `非良构XML报错`() {
        assertThrows(XmlParseException::class.java) {
            XmlMapperParser().parse("""<mapper><select id="a"></mapper>""")
        }
    }

    @Test
    fun `include缺失记录错误且不中断`() {
        val m = model("""
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t <include refid="missing"/></select>
            </mapper>
        """.trimIndent())
        assertTrue(m.errors.any { it.contains("missing") })
        assertEquals("SELECT * FROM t", compact(m.renderSql().sql))
    }

    @Test
    fun `bind自动求值结果提取`() {
        // T-M25.5（用户实测：bind 变量未自动求值）——渲染结果携带 bind 求值结果
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="idleStatus" value="'1'"/>
                SELECT * FROM t WHERE status = #{idleStatus}
              </select>
            </mapper>
        """.trimIndent())
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = m.renderSql()
        assertEquals("1", rendered.bindValues["idleStatus"])
        assertEquals("SELECT * FROM t WHERE status = ?", compact(rendered.sql))
    }

    @Test
    fun `bind覆盖值优先于自动求值`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="status" value="'1'"/>
                SELECT * FROM t WHERE status = #{status}
              </select>
            </mapper>
        """.trimIndent())
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val state = m.defaultState().copy(bindOverrides = mapOf("status" to "9"))
        assertEquals("9", m.renderSql(state).bindValues["status"])
    }

    @Test
    fun `官方DTD声明可解析`() {
        // T-M25.1（用户实测）：MyBatis 官方 DTD 声明（<!DOCTYPE mapper PUBLIC ...>）
        // 此前被 disallow-doctype-decl 拒绝——允许声明 + EntityResolver 拦截（不联网）
        val m = model("""
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE id = #{id}</select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT * FROM t WHERE id = ?", compact(m.renderSql().sql))
        assertEquals("id", m.renderSql().mappings.single().property)
    }

    @Test
    fun `内部实体定义可解析`() {
        val m = model("""
            <!DOCTYPE mapper [<!ENTITY status "ACTIVE">]>
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t WHERE status = '&status;'</select>
            </mapper>
        """.trimIndent())
        assertEquals("SELECT * FROM t WHERE status = 'ACTIVE'", compact(m.renderSql().sql))
    }

    @Test
    fun `外部实体被清空_XXE防护`() {
        // 外部实体引用解析为空（不注入外部文件内容）——实测 /etc/hosts 内容不出现
        val m = model("""
            <!DOCTYPE mapper [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
            <mapper namespace="n">
              <select id="s1">SELECT '&xxe;' FROM t</select>
            </mapper>
        """.trimIndent())
        val sql = compact(m.renderSql().sql)
        assertEquals("SELECT '' FROM t", sql)
    }

    @Test
    fun `bind静态常量引用_注入值渲染`() {
        // T-M25.2（用户实测）：OGNL 静态字段引用 @类全名@字段名（MyBatis mapper 常见）
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="portStatus" value="@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_DEVICE_PORT_STATUS_IDLE"/>
                SELECT * FROM t WHERE port_status = #{portStatus}
              </select>
            </mapper>
        """.trimIndent())
        val statement = parsed.statements.single()
        val m = XmlPreviewModel.from(
            statement,
            parsed.errors,
            staticFieldValues = mapOf(
                "com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_DEVICE_PORT_STATUS_IDLE" to "1",
            ),
        )
        val rendered = m.renderSql()
        assertEquals("SELECT * FROM t WHERE port_status = ?", compact(rendered.sql))
        assertEquals("portStatus", rendered.mappings.single().property)
        assertTrue(rendered.errors.isEmpty())
    }

    @Test
    fun `bind双竖线包裹_兼容解析并提示`() {
        // 用户实测场景：value="||@class@FIELD||"（非标准写法，真实 OGNL 运行时会失败）
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="idleStatus" value="||@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_DEVICE_PORT_STATUS_IDLE||"/>
                SELECT * FROM t WHERE status = #{idleStatus}
              </select>
            </mapper>
        """.trimIndent())
        val m = XmlPreviewModel.from(
            parsed.statements.single(),
            parsed.errors,
            staticFieldValues = mapOf(
                "com.cyzc.platform.common.constant.DicConstants@DIC_VALUE_BIKE_DEVICE_PORT_STATUS_IDLE" to "1",
            ),
        )
        val rendered = m.renderSql()
        assertEquals("SELECT * FROM t WHERE status = ?", compact(rendered.sql))
        assertTrue("应提示 || 包裹非标准写法", rendered.errors.any { it.contains("|| 包裹") })
    }

    @Test
    fun `bind静态常量引用_未注入时提示可填写覆盖值`() {
        val parsed = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="s1">
                <bind name="portStatus" value="@com.cyzc.platform.common.constant.DicConstants@DIC_VALUE"/>
                SELECT * FROM t WHERE port_status = #{portStatus}
              </select>
            </mapper>
        """.trimIndent())
        val m = XmlPreviewModel.from(parsed.statements.single(), parsed.errors)
        val rendered = m.renderSql()
        assertTrue(rendered.errors.any { it.contains("静态常量") && it.contains("填写覆盖值") })
    }
}
