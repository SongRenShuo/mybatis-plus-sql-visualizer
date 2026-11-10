package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 O：SqlTypeClassifier.maskQuoted 反斜杠转义对回归。
 *
 * 单引号掩码曾只处理 '' 转义——`\'`（MySQL 口径的转义引号）被当成字符串结束，
 * 掩码错位把 `INTO OUTFILE` 切进后续「新字符串」里整体掩掉：写证据从掩码文本
 * 消失 → 只读证明 fail-open → classify 返回 QUERY 免确认，写文件语句直进执行链
 * （设计声明是 fail-closed 宁错弹不放过）。修复后反斜杠转义对成对消费。
 */
class SqlTypeClassifierMaskEscapeTest {

    private val BS = "\\"

    @Test
    fun `反斜杠转义引号后的INTO_OUTFILE不再被掩掉`() {
        val sql = "SELECT * FROM t WHERE s = 'a" + BS + "'b' INTO OUTFILE " + BS + "'C:" + BS + BS + "temp" + BS + BS + "x.sql'"
        val t = SqlTypeClassifier.classify(sql)
        assertNotEquals("写证据必须可见（fail-closed），不得判 QUERY 免确认", SqlTypeClassifier.SqlType.QUERY, t)
    }

    @Test
    fun `成对消费_双反斜杠后引号仍正确闭合`() {
        // '\\'（字面反斜杠）不转义引号：字符串在 '\\' 之后闭合，OUTFILE 仍可见
        val sql = "SELECT * FROM t WHERE s = 'a" + BS + BS + "' INTO OUTFILE '/tmp/x'"
        val t = SqlTypeClassifier.classify(sql)
        assertNotEquals(SqlTypeClassifier.SqlType.QUERY, t)
    }

    @Test
    fun `标准双引号转义形态不回归`() {
        val sql = "SELECT * FROM t WHERE s = 'a''b'"
        assertEquals(SqlTypeClassifier.SqlType.QUERY, SqlTypeClassifier.classify(sql))
    }

    @Test
    fun `普通SELECT仍判QUERY_对照`() {
        assertEquals(SqlTypeClassifier.SqlType.QUERY, SqlTypeClassifier.classify("SELECT * FROM t WHERE s = 'a b'"))
    }
}
