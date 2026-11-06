package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-06①：[resolveColumnFieldTrace] 列 → 实体字段溯源锚点用例。
 *
 * 正例覆盖精确/归一/带表前缀/引号标识符四种可判定形态；反例覆盖计划点名的
 * 「函数列、AS 改名别名」以及跨表前缀、多级限定、歧义、空输入——这些必须判不出（null），
 * 宁可不给提示也不能张冠李戴。纯函数零 com.intellij 依赖，进 testFast 快桶。
 */
class ColumnFieldTraceTest {

    private val fields = listOf("id", "userName", "createTime", "deptId")

    // ------------------------------------------------------------------
    // 正例
    // ------------------------------------------------------------------

    @Test
    fun 下划线列名归一命中驼峰字段() {
        val trace = resolveColumnFieldTrace("user", "user_name", fields, "User")
        assertNotNull(trace)
        assertEquals("userName", trace!!.fieldName)
        assertEquals("User", trace.entityName)
    }

    @Test
    fun 大写列名同样归一命中() {
        assertEquals("createTime", resolveColumnFieldTrace("user", "CREATE_TIME", fields)?.fieldName)
    }

    @Test
    fun 精确命中不依赖归一() {
        assertEquals("id", resolveColumnFieldTrace("user", "ID", fields)?.fieldName)
    }

    @Test
    fun 驼峰列名直接命中() {
        assertEquals("deptId", resolveColumnFieldTrace("user", "deptId", fields)?.fieldName)
    }

    @Test
    fun 表前缀与表名一致时命中() {
        assertEquals("userName", resolveColumnFieldTrace("user", "user.user_name", fields)?.fieldName)
    }

    @Test
    fun 引号标识符按同名处理() {
        assertEquals("userName", resolveColumnFieldTrace("user", "`user_name`", fields)?.fieldName)
        assertEquals("userName", resolveColumnFieldTrace("user", "\"user_name\"", fields)?.fieldName)
    }

    // ------------------------------------------------------------------
    // 反例：函数列 / 别名 / 前缀不符
    // ------------------------------------------------------------------

    @Test
    fun 函数列判不出() {
        assertNull(resolveColumnFieldTrace("user", "COUNT(*)", fields, "User"))
        assertNull(resolveColumnFieldTrace("user", "count(id)", fields, "User"))
        assertNull(resolveColumnFieldTrace("user", "CONCAT(user_name, 'x')", fields, "User"))
    }

    @Test
    fun 带空白的别名表达式判不出() {
        assertNull(resolveColumnFieldTrace("user", "COUNT(*) AS cnt", fields, "User"))
    }

    @Test
    fun 别名列无对应字段时判不出() {
        assertNull(resolveColumnFieldTrace("user", "total_cnt", fields, "User"))
        assertNull(resolveColumnFieldTrace("user", "cnt", fields, "User"))
    }

    @Test
    fun 前缀不是本表时判不出() {
        assertNull(resolveColumnFieldTrace("user", "d.dept_id", fields, "User"))
    }

    @Test
    fun 无表名时带前缀列判不出() {
        assertNull(resolveColumnFieldTrace(null, "user.user_name", fields, "User"))
    }

    @Test
    fun 多级限定判不出() {
        assertNull(resolveColumnFieldTrace("user", "db.user.user_name", fields, "User"))
    }

    @Test
    fun 通配列判不出() {
        assertNull(resolveColumnFieldTrace("user", "*", fields, "User"))
        assertNull(resolveColumnFieldTrace("user", "user.*", fields, "User"))
    }

    // ------------------------------------------------------------------
    // 反例：歧义与空输入
    // ------------------------------------------------------------------

    @Test
    fun 归一后多字段同时命中判不出() {
        // userName / username 归一后同为 username，列名 user_name 对两者都只是「归一命中」→ 不任选其一
        assertNull(resolveColumnFieldTrace("user", "user_name", listOf("userName", "username"), "User"))
    }

    @Test
    fun 精确命中优先于归一歧义() {
        // 精确同名是确定命中，不被另一条的归一命中干扰
        assertEquals("user_name", resolveColumnFieldTrace("user", "user_name", listOf("userName", "user_name"))?.fieldName)
    }

    @Test
    fun 空字段清单与空列名判不出() {
        assertNull(resolveColumnFieldTrace("user", "user_name", emptyList(), "User"))
        assertNull(resolveColumnFieldTrace("user", null, fields, "User"))
        assertNull(resolveColumnFieldTrace("user", "   ", fields, "User"))
    }

    // ------------------------------------------------------------------
    // tooltip 组装（列序号 → 文案）
    // ------------------------------------------------------------------

    @Test
    fun tooltip只覆盖命中列() {
        val tooltips = columnFieldTraceTooltips(
            columnNames = listOf("user_name", "COUNT(*)", "create_time"),
            tableName = "user",
            entityFields = fields,
            entityName = "User",
        )
        assertEquals(setOf(0, 2), tooltips.keys)
        assertEquals("字段: userName（实体 User）", tooltips[0])
        assertEquals("字段: createTime（实体 User）", tooltips[2])
    }

    @Test
    fun 无实体名时文案不带实体后缀() {
        val tooltips = columnFieldTraceTooltips(listOf("user_name"), "user", fields, null)
        assertEquals("字段: userName", tooltips[0])
    }

    @Test
    fun 无实体上下文时tooltip为空() {
        assertEquals(emptyMap<Int, String>(), columnFieldTraceTooltips(listOf("user_name"), null, emptyList(), null))
    }
}
