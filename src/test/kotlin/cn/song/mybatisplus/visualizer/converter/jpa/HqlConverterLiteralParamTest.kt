package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 B：HQL/JPQL 命名/位置参数替换的字面量掩码回归。
 *
 * 参数替换曾对裸文本全文跑 `(?<!:):(\w+)` / `\?\d+` 正则——字符串字面量内的
 * `:word`（'HH:mm'、'订单:status'）与 `?N`（'SKU?12'）会被换成占位符：
 * 字面量被改写、且该占位符落在引号内不被回填链认领，但参数名表却多记一条，
 * 后续所有 `?` 的按位回填从该处起整体错位。修复后与同文件后续步骤一样走
 * [JpaSqlLiteralMasking]「掩码→替换→还原」两段式。
 */
class HqlConverterLiteralParamTest {

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user_entity",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("pattern", "pattern"),
        ),
    )

    private val orderTable = TableInfo(
        entityClassName = "OrderEntity",
        tableName = "order_entity",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("code", "code"),
            ColumnInfo("remark", "remark"),
        ),
    )

    @Test
    fun testHqlLiteralColonWordNotReplaced() {
        val hql = "SELECT u FROM UserEntity u WHERE u.pattern = 'HH:mm' AND u.username = :name"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("字面量应原样保留", result.sql.contains("'HH:mm'"))
        assertEquals(
            "字面量内 :m 不得计入占位符（仅 :name 一个 ?）",
            1,
            result.sql.count { it == '?' },
        )
    }

    @Test
    fun testHqlLiteralColonBeforeParamsKeepsBackfillAlignment() {
        // 字面量在前、两个真参数在后：曾多记一个名导致按位回填整体前移一位
        val hql =
            "SELECT o FROM OrderEntity o WHERE o.remark = '状态:ready' AND o.id = :id AND o.code = :code"
        val result = HqlConverter.convert(hql, orderTable)
        assertTrue("字面量应原样保留", result.sql.contains("'状态:ready'"))
        assertEquals(
            "字面量内 :ready 不得计入占位符（仅 :id 与 :code 两个 ?）",
            2,
            result.sql.count { it == '?' },
        )
    }

    @Test
    fun testJpqlLiteralPositionalDigitNotReplaced() {
        // 位置参数 ?1 同样先掩码：字面量 'SKU?12' 不得被 `\?\d+` 消费
        // （曾产出 'SKU?'——字面量被改写且 ?12 被计为位置参数）
        val jpql = "SELECT o FROM OrderEntity o WHERE o.code = 'SKU?12' AND o.id = ?1"
        val result = JpqlConverter.convert(jpql, orderTable)
        assertTrue("字面量应原样保留（含 ?12）", result.sql.contains("'SKU?12'"))
        // 总 ? 数 = 字面量内 1 个 + 真占位符 1 个
        assertEquals(2, result.sql.count { it == '?' })
    }

    @Test
    fun testHqlInClauseDetectionStillWorksOnMaskedText() {
        // 掩码不得破坏 IN 子句参数检测（真参数在字面量之外）
        val hql = "SELECT u FROM UserEntity u WHERE u.pattern = 'a:1' AND u.id IN :ids"
        val result = HqlConverter.convert(hql, userTable)
        assertTrue("IN 子句参数仍应被标记", result.inClauseParams.contains("ids"))
        assertTrue(result.sql.contains("'a:1'"))
    }
}
