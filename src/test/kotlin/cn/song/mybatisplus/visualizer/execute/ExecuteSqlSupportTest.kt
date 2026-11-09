package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.share.format.ExecutableSqlFormatter
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues
import cn.song.mybatisplus.visualizer.sql.SlotKind
import cn.song.mybatisplus.visualizer.sql.SlotValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 执行 SQL 支持测试（M17）：
 *  - 测试沙盒（build.gradle 已排除 database 插件 jar）无 Database 插件 → `isAvailable()` = false
 *    （Community 降级路径，稳定可测）；
 *  - `execute()` 降级路径不抛异常（提示经 runCatching 兜底，headless 环境安全）；
 *  - launcher（引用 com.intellij.database 的隔离类）在测试环境**不应被加载**（反射隔离生效）。
 */
class ExecuteSqlSupportTest : BasePlatformTestCase() {

    fun testIsAvailableFalseWithoutDatabasePlugin() {
        // build.gradle Test classpath 已排除 database 插件 jar → 沙盒等价 Community
        assertFalse("测试沙盒无 Database 插件，应判定不可用（Community 降级）", ExecuteSqlSupport.isAvailable())
    }

    fun testExecuteWithoutDatabasePluginDoesNotThrow() {
        // Community 降级：不可用 → 提示升级（headless 下被 runCatching 吞掉）→ 不抛异常、
        // 不触碰反射 launcher（无需 database 类）
        ExecuteSqlSupport.execute(project, "SELECT 1 FROM t")
        assertTrue(true)
    }

    fun testExecuteWithBlankSqlNoop() {
        // 面板层已过滤空白 SQL；支持层对任意文本都不抛异常
        ExecuteSqlSupport.execute(project, "")
        assertTrue(true)
    }

    // ------------------------------------------------------------------
    // T-RuntimeSlotFill 券 B：执行链的占位回填
    // ------------------------------------------------------------------

    fun testRenderForExecutionFillsRuntimeSlotsAndMatchesExecutableEntry() {
        val slot = RuntimeSlot(
            id = "rt2",
            kind = SlotKind.VALUE,
            expression = "startTime",
            template = null,
            hint = ParamHint(quote = true),
            token = "/* 运行时值: startTime · rt2 */",
        )
        val sql = "SELECT id FROM t_order WHERE create_time >= '${slot.token}'"
        val values = RuntimeSlotValues.of("rt2" to SlotValue.text("2026-01-01"))

        val rendered = ExecuteSqlSupport.renderForExecution(sql, listOf(slot), values, "MySQL")
        assertEquals("SELECT id FROM t_order WHERE create_time >= '2026-01-01'", rendered)
        assertFalse("执行文本不应残留占位注释", rendered.contains("运行时值"))
        // 执行链与复制/分享/导出共用同一入口：文本必须逐字一致
        assertEquals(
            "执行链文本必须与可执行 SQL 唯一入口一致",
            ExecutableSqlFormatter.format(
                sql = sql,
                sources = emptyList(),
                paramValues = emptyMap(),
                slots = listOf(slot),
                slotValues = values,
                dialectName = "MySQL",
            ),
            rendered,
        )
    }

    fun testRenderForExecutionWithoutSlotsIsIdentity() {
        // 无占位时逐字原样（既有执行链零变化）
        val sql = "SELECT id\n  FROM t_order WHERE create_time >= '2026-01-01'"
        assertEquals(sql, ExecuteSqlSupport.renderForExecution(sql))
    }
}
