package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.config.PluginSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * sqlv_mapper_check 工具测试（T-McpToolset2 / T-MTS2-03）：契约层（语句清单/#{} 参数数/
 * ${} 直替数）+ 渲染层 simulate（空参数语境下无 WHERE 的 UPDATE 判 DANGEROUS——
 * 「校验渲染产物」的位差落点）。XmlMapperParser/render 链细节由 xml 包测试覆盖。
 */
class MapperCheckToolTest : com.intellij.testFramework.fixtures.BasePlatformTestCase() {

    private fun check(snippet: String, simulate: Boolean = false) =
        SqlVisualizerMapperCheckTool().mapperCheck(snippet, simulate)

    override fun tearDown() {
        // 应用级设置跨用例存活——恢复默认
        PluginSettings.getInstance().state.mcpToolsEnabled = true
        super.tearDown()
    }

    private val mapper = """
        <mapper namespace="n">
          <select id="s1" resultType="map">
            SELECT * FROM t_user WHERE id = #{id} ORDER BY ${'$'}{orderCol}
          </select>
          <update id="u1">UPDATE t_user SET a = 1</update>
        </mapper>
    """.trimIndent()

    fun test契约层_语句清单参数数与直替数() {
        val output = check(mapper)
        assertTrue(output, output.contains("statements: 2"))
        assertTrue(output, output.contains("s1 [SELECT]"))
        assertTrue(output, output.contains("u1 [UPDATE]"))
        assertTrue(output, output.contains("substitutions: 1"))
    }

    fun test渲染层simulate_无WHERE的UPDATE判危险() {
        val output = check(mapper, simulate = true)
        assertTrue(output, output.contains("DANGEROUS"))
        assertTrue(output, output.contains("MISSING_WHERE"))
        assertTrue("空参数语境如实标注", output.contains("empty-parameter context"))
    }

    /**
     * T-McpWalkFindings S2：真机 20261005 实测（demo UserMapper.xml 全文）空参模拟 6/10 标
     * DANGEROUS PARSE_FAILED。归因链（探针定案）：渲染产物保留源 XML 换行+缩进 →
     * SqlCommentStripper 把空行压成真空行（`\n\n` 行内无空格）→ JSqlParser 5.3 简单模式对
     * 真空行解析失败且**静默返回 null**（不抛异常）→ SqlAstParser 非空断言 NPE → fail closed
     * 误报。修法=detect 入口空白规范化（execute 包测试钉）+ SqlAstParser null 响亮防御。
     * 本用例钉 mapper_check 全链：多分支动态 XML 模拟后按真实语法判定（不再 PARSE_FAILED）。
     */
    fun test渲染层simulate_多分支动态SQL不再误报PARSE_FAILED() {
        val multiBranch = """
            <mapper namespace="n">
              <select id="multiIf" resultType="map">
                select id, username from user
                <where>
                  deleted = 0
                  <if test="username != null">and username = #{username}</if>
                  <if test="status != null">and status = #{status}</if>
                </where>
                order by id desc
              </select>
            </mapper>
        """.trimIndent()
        val output = check(multiBranch, simulate = true)
        assertFalse(
            "多分支动态 SQL 空参模拟不得再误报 PARSE_FAILED: $output",
            output.contains("PARSE_FAILED"),
        )
        assertTrue(output, output.contains("bounded"))
    }

    /**
     * T-McpWalkFindings S2：PARSE_FAILED 必带 detail 行（`simulate risk note:`）——真语法错
     * 残余失败场景的归因线索（修复前 detail 被 kinds() 枚举名吞掉，输出零线索）。
     */
    fun test渲染层simulate_PARSE_FAILED带detail归因线索() {
        val broken = """
            <mapper namespace="n">
              <update id="broken">UPDATE t_user SET WHERE id = #{id}</update>
            </mapper>
        """.trimIndent()
        val output = check(broken, simulate = true)
        assertTrue("真语法错应判 PARSE_FAILED: $output", output.contains("PARSE_FAILED"))
        assertTrue(
            "PARSE_FAILED 必须带 detail 行（simulate risk note:）: $output",
            output.contains("simulate risk note:"),
        )
        assertFalse(
            "detail 不应是 Kotlin 断言的 must not be null（null 防御已转响亮消息）: $output",
            output.contains("must not be null"),
        )
    }

    fun test无语句mapper_预期错误() {
        val error = runCatching { check("<mapper namespace=\"n\"></mapper>") }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
        assertTrue((error?.message ?: "").contains("no <select|insert|update|delete>"))
    }

    fun test空文本_预期错误() {
        val error = runCatching { SqlVisualizerMapperCheckTool().mapperCheck("", false) }.exceptionOrNull()
        assertTrue(error is SqlvExpectedToolException)
    }
}
