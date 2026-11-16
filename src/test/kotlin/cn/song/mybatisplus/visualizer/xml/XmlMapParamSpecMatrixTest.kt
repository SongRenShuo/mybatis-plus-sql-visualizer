package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.ui.XmlPreviewService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * XML Map 参数语义**金样矩阵**（T-GoldenDefense / N-8 竞品翻车场景防御性验证）。
 *
 * 场景来源：MyBatisX（baomidou 官方）两条无人回复的用户报告——Map 参数在 XML 中 `#{map.xxx}`
 * 被标「无法解析符号」、多模块同名实体误报「Result type not match」。立项前探查实证本插件
 * 两条链路行为均正确（无缺陷），本矩阵把当前正确语义固化成回归资产，防后续迭代无感劣化。
 *
 * 期望 SQL 为手工快照——语义基线 = MyBatis 3.5.x（OGNL 属性/索引/白名单方法、TextSqlNode
 * 求值为空输出空串、foreach collection 精确命中）。MP/MyBatis 升级语义变化时按 diff 审阅后
 * 更新快照，禁止直接用实际输出重生成（那会让矩阵失去回归能力）。
 *
 * 场景清单：X1 `#{map.xxx}` 占位渲染 / X2 引号前缀命中 / X3 条件开关 / X4 OGNL Map 取值 /
 * X5 foreach collection 精确命中 / X6 `${}` 求值为空显式提示 / X7 同 FQN 双实体共存。
 * X8（@Param("map") 引号真实 PSI 解析 + 同 FQN 双实体 resolveQuotes）在
 * HeavyXmlParamTypeResolverTest——LightPlatform 跨文件 resolve 不可用（探针实证）。
 */
class XmlMapParamSpecMatrixTest : BasePlatformTestCase() {

    private fun compact(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()

    private fun model(mapper: String, paramQuotes: Map<String, Boolean> = emptyMap()): XmlPreviewModel {
        val parsed = XmlMapperParser().parse(mapper.trimIndent())
        return XmlPreviewModel.from(parsed.statements.single(), parsed.errors, paramQuotes = paramQuotes)
    }

    /** 金样断言：SQL 快照按空白压缩后比对，name 进失败信息便于矩阵定位；返回渲染产物供补充断言。 */
    private fun spec(name: String, mapper: String, expectedSql: String): RenderedXmlSql {
        val rendered = model(mapper).renderSql()
        assertEquals(name, compact(expectedSql), compact(rendered.sql))
        return rendered
    }

    // ========== X1：#{map.xxx} 纯占位渲染（竞品「无法解析符号」无同款报错面） ==========

    fun `test X1 map参数占位渲染`() {
        val rendered = spec(
            "X1",
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t_user WHERE name = #{map.userName}</select>
            </mapper>
            """,
            "SELECT * FROM t_user WHERE name = ?",
        )
        assertEquals("X1: mappings 恰含 property=map.userName", listOf("map.userName"), rendered.mappings.map { it.property })
        assertTrue("X1: 无错误", rendered.errors.isEmpty())
    }

    // ========== X2：@Param("map") Map → 键=参数名，任意 map.* 前缀命中引号 ==========

    fun `test X2 map参数引号前缀命中`() {
        // paramQuotes["map"] = true 即 XmlParamTypeResolver 对 @Param("map") Map 的解析产物
        // （Map → 引号；真实 PSI 链路见 HeavyXmlParamTypeResolverTest X8-1）
        val m = model(
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t_user WHERE name = #{map.userName}</select>
            </mapper>
            """,
            paramQuotes = mapOf("map" to true),
        )
        assertEquals("X2: 精确键命中", true, m.quoteFor("map"))
        assertEquals("X2: 任意 map.* 属性前缀命中", true, m.quoteFor("map.userName"))
        assertNull("X2: 未命中返回 null（面板兜底引号）", m.quoteFor("other.field"))
    }

    // ========== X3：map.status 条件开关——默认勾选，勾选渲染 / 取消消失 ==========

    fun `test X3 map参数条件开关`() {
        val m = model(
            """
            <mapper namespace="n">
              <select id="s1">
                SELECT * FROM t_user
                <where>
                  <if test="map.status != null">AND status = #{map.status}</if>
                </where>
              </select>
            </mapper>
            """,
        )
        val condition = m.conditionViews.single()
        assertEquals("X3: 条件 id = test 表达式原文", "map.status != null", condition.id)
        assertTrue("X3: 无 == null → 默认勾选（defaultTruth）", condition.defaultEnabled)
        assertEquals("X3: 默认勾选渲染 WHERE", "SELECT * FROM t_user WHERE status = ?", compact(m.renderSql().sql))
        val off = m.defaultState().copy(enabledIds = emptySet())
        assertEquals("X3: 取消后条件段消失", "SELECT * FROM t_user", compact(m.renderSql(off).sql))
    }

    // ========== X4：OGNL Map 取值三形态（属性/索引/白名单方法，对齐 MyBatis OGNL 语义） ==========

    fun `test X4 OGNL Map取值属性索引白名单方法`() {
        val bindings = mapOf<String, Any?>("map" to mapOf("status" to "1", "k" to "v"))
        fun eval(expr: String): Any? = when (val r = OgnlEvaluator.evaluate(expr, bindings)) {
            is EvalResult.Ok -> r.value
            is EvalResult.Err -> error("X4: 求值不应报错：${r.message}")
        }
        assertEquals("X4: 属性访问 map.status", "1", eval("map.status"))
        assertEquals("X4: 索引访问 map['status']", "1", eval("map['status']"))
        assertEquals("X4: 白名单方法 containsKey", true, eval("map.containsKey('k')"))
        // MyBatis OGNL：键不存在返回 null 而非报错（计划 §5 风险约定，按官方语义锁定）
        assertNull("X4: 属性缺失 → null 非报错", eval("map.missing"))
        assertNull("X4: 索引缺失 → null 非报错", eval("map['missing']"))
    }

    // ========== X5：foreach collection="map.items" 表达式全文作 mock 键精确命中 ==========

    fun `test X5 foreach map集合默认展开两元素`() {
        val m = model(
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t_user WHERE id IN
                <foreach collection="map.items" item="it" open="(" separator="," close=")">#{it}</foreach>
              </select>
            </mapper>
            """,
        )
        assertEquals("X5: collection 表达式全文进 foreach 视图", listOf("map.items"), m.forEachViews.map { it.collectionExpr })
        assertEquals("X5: 默认展开 2 元素", 2, m.defaultState().forEachCounts["map.items"])
        val rendered = spec(
            "X5",
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM t_user WHERE id IN
                <foreach collection="map.items" item="it" open="(" separator="," close=")">#{it}</foreach>
              </select>
            </mapper>
            """,
            "SELECT * FROM t_user WHERE id IN ( ? , ? )",
        )
        assertTrue(
            "X5: foreach 内参数重写为 __frch_ 前缀：${rendered.mappings.map { it.property }}",
            rendered.mappings.map { it.property } == listOf("__frch_it_0", "__frch_it_1"),
        )
    }

    // ========== X6：${map.column} 求值为空 → 显式错误提示，不出 null 字面量 ==========

    fun `test X6 dollar表达式求值为空显式提示`() {
        val rendered = spec(
            "X6",
            """
            <mapper namespace="n">
              <select id="s1">SELECT * FROM ${'$'}{map.column} WHERE id = 1</select>
            </mapper>
            """,
            // T-MbgExamplePreview R1：预览链开启占位模式，缺段以 {{表达式}} 显式可见（不再是空串）
            "SELECT * FROM {{map.column}} WHERE id = 1",
        )
        assertTrue(
            "X6: 应有求值为空显式提示：${rendered.errors}",
            rendered.errors.any { it.contains("map.column") && it.contains("求值为空") },
        )
        assertFalse("X6: SQL 不出现 null 字面量", rendered.sql.contains("null"))
    }

    // ========== X7：同 FQN 双实体共存——预览输出与单实体完全一致，无告警误报 ==========

    fun `test X7 同FQN双实体共存预览输出不变`() {
        myFixture.configureByText(
            "User.java",
            """
            package com.example;

            public class User {
                private Long id;
            }
            """.trimIndent(),
        )
        val mapper = """
            <mapper namespace="com.example.UserMapper">
              <select id="getById" resultType="com.example.User">SELECT id FROM t_user WHERE id = #{map.id}</select>
            </mapper>
        """.trimIndent()
        val baseline = XmlPreviewService.preview(mapper, "getById", project)
        assertNotNull("X7: 应产出预览结果", baseline)
        assertEquals("X7: 单实体基线", "SELECT id FROM t_user WHERE id = ?", baseline.compactSql)
        assertNull("X7: 单实体基线无告警", baseline.warning)
        // 多模块同名实体竞品翻车形态：不同文件放同 FQN 同名类（findClass 命中顺序不确定，
        // 断言只锁「输出一致、不报错」，不锁命中哪一个类——计划 §5 风险约定）
        myFixture.configureByText(
            "UserDup.java",
            """
            package com.example;

            public class User {
                private String name;
            }
            """.trimIndent(),
        )
        val after = XmlPreviewService.preview(mapper, "getById", project)
        assertNotNull("X7: 双实体后应产出预览结果", after)
        assertEquals("X7: 双实体后预览 SQL 与单实体完全一致", baseline.compactSql, after.compactSql)
        assertNull("X7: 双实体后无告警误报", after.warning)
    }
}
