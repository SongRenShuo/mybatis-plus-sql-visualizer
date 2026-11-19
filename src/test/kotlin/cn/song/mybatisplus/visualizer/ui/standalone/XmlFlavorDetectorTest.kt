package cn.song.mybatisplus.visualizer.ui.standalone

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 片段级风味判定测试（T-StandaloneIbatisRecognize D-1）。
 *
 * 纯逻辑、零平台依赖（自动进 testFast 快桶）。夹具覆盖计划 §四 P-3 的四类形态：
 * ①纯 iBatis 片段 ②纯 MyBatis 片段 ③**MyBatis 片段里含 `#x#` 字面量**（反向风险点——
 * 不得因一处字面量把整段判成 iBatis）④畸形/无信号片段（同分判 MyBatis）。
 *
 * k2j 移植注：生产侧 XmlFlavorDetector 已是 Java（静态方法 detect + 枚举 Flavor），
 * Kotlin 测试直调即兼容，无需互操作改写。
 */
class XmlFlavorDetectorTest {

    @Test
    fun `用户报障的 iBatis 2 片段判 iBatis`() {
        val text = """
            <select id="selectByCondition" parameterClass="User" resultMap="userResult">
              SELECT <include refid="userColumns"/>
              FROM T_USER
              <dynamic prepend="WHERE">
                <isNotNull prepend="AND " property="id">ID = #id#</isNotNull>
                <isEqual prepend="AND " property="status" compareValue="1">STATUS = #status#</isEqual>
              </dynamic>
              ORDER BY ID
            </select>
        """.trimIndent()
        assertEquals(XmlFlavorDetector.Flavor.IBATIS, XmlFlavorDetector.detect(text))
    }

    @Test
    fun `完整 sqlMap 文件判 iBatis`() {
        val text = """
            <!DOCTYPE sqlMap PUBLIC "-//iBATIS.com//DTD SQL Map 2.0//EN" "http://ibatis.apache.org/dtd/sql-map-2.dtd">
            <sqlMap namespace="User">
              <select id="q">SELECT * FROM T_USER WHERE ID = #id#</select>
            </sqlMap>
        """.trimIndent()
        assertEquals(XmlFlavorDetector.Flavor.IBATIS, XmlFlavorDetector.detect(text))
    }

    @Test
    fun `完整 mapper 文件判 MyBatis`() {
        val text = """
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="n">
              <select id="q">SELECT * FROM t WHERE a = #{a}</select>
            </mapper>
        """.trimIndent()
        assertEquals(XmlFlavorDetector.Flavor.MYBATIS, XmlFlavorDetector.detect(text))
    }

    @Test
    fun `MyBatis 片段里的字符串字面量含井号形态不误判`() {
        // P-3 ③ 反向风险点：字面量内的 `#x#` / `$x$` 不得给 iBatis 信号
        val text = """
            <select id="q">
              SELECT * FROM t WHERE note = '#a#' AND tag = '${'$'}b${'$'}' AND a = #{a}
            </select>
        """.trimIndent()
        assertEquals(XmlFlavorDetector.Flavor.MYBATIS, XmlFlavorDetector.detect(text))
    }

    @Test
    fun `注释里的 iBatis 标签不计信号`() {
        val text = """
            <select id="q">
              <!-- <dynamic><isNotNull property="x">x = #x#</isNotNull></dynamic> -->
              SELECT * FROM t WHERE a = #{a}
            </select>
        """.trimIndent()
        assertEquals(XmlFlavorDetector.Flavor.MYBATIS, XmlFlavorDetector.detect(text))
    }

    @Test
    fun `无任何风味信号的裸语句块同分判 MyBatis`() {
        val text = """<select id="q">SELECT * FROM t</select>"""
        assertEquals(XmlFlavorDetector.Flavor.MYBATIS, XmlFlavorDetector.detect(text))
    }

    @Test
    fun `iBatis 专有标签单独出现也判 iBatis`() {
        val text = """<select id="q">SELECT * FROM t <iterate property="ids" open="(" close=")" separator=",">#ids[]#</iterate></select>"""
        assertEquals(XmlFlavorDetector.Flavor.IBATIS, XmlFlavorDetector.detect(text))
    }

    @Test
    fun `MyBatis 动态标签单独出现判 MyBatis`() {
        val text = """<select id="q">SELECT * FROM t <if test="a != null">AND a = #{a}</if></select>"""
        assertEquals(XmlFlavorDetector.Flavor.MYBATIS, XmlFlavorDetector.detect(text))
    }

    @Test
    fun `空文本判 MyBatis（兜底，不抛异常）`() {
        assertEquals(XmlFlavorDetector.Flavor.MYBATIS, XmlFlavorDetector.detect("   "))
    }
}
