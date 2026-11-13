package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-IbatisFix2（2026-11-10 真机反馈 ④）：参数占位原子段——`#x#` / `#{x}` / `${x}` 在三条格式化
 * 路径（[SqlFormatter.format] / [SqlFormatter.formatClauseLine] / [SqlFormatter.formatVertical]）
 * 下必须整体保留，不得拆成 `# username #`（用户 2026.3 截图实证的缺陷形态）。
 *
 * 反向验证锚点：摘掉 [SqlFormatter.mergeParamPlaceholders] 合并（反向补丁）后
 * `井号内联参数_全展开路径` 用例必红（产出 `# username #`）。
 */
class SqlFormatterIbatisTokenTest {

    // ------------------------------------------------------------------
    // iBatis `#x#`
    // ------------------------------------------------------------------

    @Test
    fun `井号内联参数_全展开路径`() {
        val sql = "SELECT #username# AS RAW_HASH_KEPT, ? AS POSITIONAL FROM T_USER WHERE USER_NAME = ? AND EMAIL = ?"
        val formatted = SqlFormatter.format(sql)
        assertTrue("原子段必须原样保留：$formatted", formatted.contains("#username#"))
        assertFalse("不得拆成 `# username #`：$formatted", formatted.contains("# username #"))
    }

    @Test
    fun `井号三段形态_原样保留`() {
        val sql = "INSERT INTO T_USER (ID, EMAIL) VALUES (#id#, #email:VARCHAR:unknown#)"
        val formatted = SqlFormatter.format(sql)
        assertTrue("三段内容原样：$formatted", formatted.contains("#email:VARCHAR:unknown#"))
        assertFalse(formatted.contains("# email : VARCHAR : unknown #"))
    }

    @Test
    fun `井号iterate下标_原样保留`() {
        val formatted = SqlFormatter.format("SELECT A FROM T WHERE ID = #ids[0]# OR ID = #ids[1]#")
        assertTrue(formatted.contains("#ids[0]#"))
        assertTrue(formatted.contains("#ids[1]#"))
        assertFalse("下标括号不得被拆散加空格：$formatted", formatted.contains("# ids [ 0 ] #"))
    }

    @Test
    fun `井号内联参数_子句行路径`() {
        val formatted = SqlFormatter.formatClauseLine(
            "SELECT #username# AS RAW_HASH_KEPT FROM T_USER WHERE USER_NAME = ?",
        )
        assertTrue("formatClauseLine 与 format 同源分词，同样保真：$formatted", formatted.contains("#username#"))
        assertFalse(formatted.contains("# username #"))
    }

    @Test
    fun `井号内联参数_竖排路径_非INSERT走全展开`() {
        val formatted = SqlFormatter.formatVertical(
            "SELECT #username# AS RAW_HASH_KEPT, ? AS POSITIONAL FROM T_USER WHERE USER_NAME = ?",
        )
        assertTrue(formatted.contains("#username#"))
        assertFalse(formatted.contains("# username #"))
    }

    @Test
    fun `井号内联参数_竖排路径_INSERT专用路径`() {
        val formatted = SqlFormatter.formatVertical(
            "INSERT INTO T_USER (ID, EMAIL) VALUES (1, #email:VARCHAR:unknown#)",
        )
        assertTrue("INSERT 专用竖排路径同样保真：$formatted", formatted.contains("#email:VARCHAR:unknown#"))
        assertFalse(formatted.contains("# email : VARCHAR : unknown #"))
    }

    // ------------------------------------------------------------------
    // MyBatis `#{x}` / `${x}`（同类缺陷一并修）
    // ------------------------------------------------------------------

    @Test
    fun `MyBatis花括号井号_原子保留`() {
        val formatted = SqlFormatter.format("SELECT A FROM T WHERE B = #{user.name} AND C = ?")
        assertTrue(formatted.contains("#{user.name}"))
        assertFalse("不得拆成 `# { user.name }`：$formatted", formatted.contains("# { user.name }"))
    }

    @Test
    fun `MyBatis美元替换_原子保留`() {
        val formatted = SqlFormatter.format("SELECT A FROM T WHERE B = ${'$'}{tenantId} AND C = ?")
        assertTrue(formatted.contains("\${tenantId}"))
        assertFalse("不得拆成 `\$ { tenantId }`：$formatted", formatted.contains("\$ { tenantId }"))
    }

    @Test
    fun `MyBatis花括号内含属性段_原子保留`() {
        val formatted = SqlFormatter.formatClauseLine("UPDATE T SET A = #{age,javaType=int,jdbcType=INTEGER} WHERE ID = 1")
        assertTrue(formatted.contains("#{age,javaType=int,jdbcType=INTEGER}"))
    }

    // ------------------------------------------------------------------
    // 边界：未闭合 / 转义 / 混合
    // ------------------------------------------------------------------

    @Test
    fun `未闭合井号_不合并也不抛`() {
        // 未闭合残段按原 token 展示（与渲染器「未闭合」告警口径一致）；闭合部分照常合并
        val formatted = SqlFormatter.format("SELECT A FROM T WHERE B = #b AND C = #c#")
        assertTrue("闭合段照常原子：$formatted", formatted.contains("#c#"))
        assertFalse("未闭合段不得把后续词吞进原子段：$formatted", formatted.contains("#b AND"))
        assertTrue("残段两侧词都在：$formatted", formatted.contains("B = # b") && formatted.contains("AND C = #c#"))
    }

    @Test
    fun `双井号转义_原样保留`() {
        val formatted = SqlFormatter.format("SELECT '##' AS LITERAL FROM T")
        assertTrue("字符串内不动；字面量 `##` 原样：$formatted", formatted.contains("'##'"))
    }
}
