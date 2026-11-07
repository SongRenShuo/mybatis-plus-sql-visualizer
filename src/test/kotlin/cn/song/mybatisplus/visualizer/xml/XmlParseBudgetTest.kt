package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-06③：[XmlParseBudget] 解析预算闸 + 降级模型用例。
 *
 * 正例：预算内的 mapper 照旧解析（零行为变更）；反例：超文本上限、超节点上限都走降级模型
 * （不抛异常、不建树、给错误说明与语句 id 清单）。另附 1MB 级夹具的实测耗时读数
 * （打印到测试输出，供报告引用）。
 */
class XmlParseBudgetTest {

    // ------------------------------------------------------------------
    // 正例：预算内零行为变更
    // ------------------------------------------------------------------

    @Test
    fun 预算内mapper正常解析() {
        val model = XmlMapperParser().parse(
            """<mapper namespace="demo.UserMapper">
               |  <select id="findById">SELECT * FROM t_user WHERE id = #{id}</select>
               |</mapper>""".trimMargin(),
        )
        assertFalse(model.degraded)
        assertEquals("demo.UserMapper", model.namespace)
        assertEquals(listOf("findById"), model.statements.map { it.id })
        assertTrue(model.statementIds.isEmpty())
    }

    // ------------------------------------------------------------------
    // 反例一：文本超 2MB
    // ------------------------------------------------------------------

    @Test
    fun 超文本上限走降级模型不抛异常() {
        val text = "<mapper namespace=\"big\">" + "x".repeat(XmlParseBudget.MAX_TEXT_BYTES)
        assertTrue(text.length > XmlParseBudget.MAX_TEXT_BYTES)
        val started = System.nanoTime()
        val model = XmlMapperParser().parse(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("超限文本必须降级", model.degraded)
        assertTrue(model.statements.isEmpty())
        assertTrue("错误说明应提到上限", model.errors.first().contains("MB"))
        // 降级路径只做文本扫描，不该出现「按文件大小线性变慢」的耗时
        assertTrue("超限判定应远快于解析，实测 ${elapsedMs}ms", elapsedMs < 2000)
        println("[F-06③] 2MB 超限降级耗时=${elapsedMs}ms")
    }

    // ------------------------------------------------------------------
    // 反例二：顶层语句节点超 5000
    // ------------------------------------------------------------------

    @Test
    fun 超节点上限走降级模型并给id清单() {
        val count = XmlParseBudget.MAX_TOP_LEVEL_NODES + 1
        val text = buildString {
            append("<mapper namespace=\"many\">")
            for (i in 0 until count) append("<select id=\"s$i\">SELECT $i</select>")
            append("</mapper>")
        }
        val started = System.nanoTime()
        val model = XmlMapperParser().parse(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("超限节点必须降级", model.degraded)
        assertTrue(model.statements.isEmpty())
        assertTrue("错误说明应带实测节点数", model.errors.first().contains(count.toString()))
        assertEquals(XmlParseBudget.MAX_ID_SCAN, model.statementIds.size)
        assertEquals("s0", model.statementIds.first())
        assertTrue("节点计数应远快于建树，实测 ${elapsedMs}ms", elapsedMs < 2000)
        println("[F-06③] 5001 节点超限降级耗时=${elapsedMs}ms")
    }

    // ------------------------------------------------------------------
    // 文本扫描内核
    // ------------------------------------------------------------------

    @Test
    fun 节点计数与id扫描只认语句开标签() {
        val text = """<mapper namespace="n">
            |  <sql id="frag">x</sql>
            |  <select id="a" resultType="x">SELECT 1</select>
            |  <insert id='b'>INSERT INTO t VALUES (1)</insert>
            |  <update resultMap="m" id="c">UPDATE t SET x=1</update>
            |  <delete id="d">DELETE FROM t</delete>
            |</mapper>""".trimMargin()
        assertEquals(5, XmlParseBudget.countStatementTags(text))
        assertEquals(listOf("a", "b", "c", "d"), XmlParseBudget.scanStatementIds(text))
    }

    @Test
    fun 无id语句不进id清单() {
        val text = "<mapper><select>SELECT 1</select></mapper>"
        assertTrue(XmlParseBudget.scanStatementIds(text).isEmpty())
    }

    @Test
    fun utf8字节数按真实编码计() {
        assertEquals(6, XmlParseBudget.utf8Size("中文"))
        assertEquals(3, XmlParseBudget.utf8Size("abc"))
    }

    // ------------------------------------------------------------------
    // 1MB 级夹具实测（耗时读数进报告）
    // ------------------------------------------------------------------

    @Test
    fun 一MB级mapper在预算内可解析并给出耗时() {
        val body = "SELECT id, user_name, create_time, update_time, dept_id, status, remark " +
            "FROM t_user WHERE id = #{id} AND user_name LIKE CONCAT('%', #{userName}, '%') " +
            "AND create_time BETWEEN #{start} AND #{end} AND status = #{status} " +
            "ORDER BY create_time DESC LIMIT #{offset}, #{limit}"
        val text = buildString {
            append("<mapper namespace=\"bench.BigMapper\">")
            for (i in 0 until 4000) append("<select id=\"q$i\">").append(body).append("</select>")
            append("</mapper>")
        }
        assertTrue("夹具应在 1MB 量级，实测 ${text.length} 字符", text.length > 900_000)
        assertFalse("1MB 且 4000 节点应在预算内", XmlParseBudget.check(text) != null)
        val started = System.nanoTime()
        val model = XmlMapperParser().parse(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(model.degraded)
        assertEquals(4000, model.statements.size)
        println("[F-06③] 1MB 夹具（${text.length} 字符 / 4000 语句）完整解析耗时=${elapsedMs}ms")
    }
}
