package cn.song.mybatisplus.visualizer.share.format

import cn.song.mybatisplus.visualizer.log.ReplayScriptExport
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.RuntimeSlotValues
import cn.song.mybatisplus.visualizer.sql.SlotKind
import cn.song.mybatisplus.visualizer.sql.SlotValue
import cn.song.mybatisplus.visualizer.ui.components.CopyExecutableSqlAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-RuntimeSlotFill 券 B：**可执行链路一致性**。
 *
 * 用户会遇到的坑是「预览里填上了、复制出来还是注释」——所以复制 / 分享 / 导出三条链必须
 * 拿同一份文本。本类用同一个 `PreviewResult` 形态的占位表 + 同一份值，断言：
 *  - 预览产物（[ExecutableSqlFormatter]）与复制产物（[CopyExecutableSqlAction.assembleProduct]）逐字相等；
 *  - 导出回放脚本（[ReplayScriptExport]）切出的语句与这份文本一致；
 *  - 三处都不再残留 `运行时值` 注释。
 *
 * 执行链（[cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.renderForExecution]）因要
 * 触达 IDE 日志组件，其一致性断言放在 `ExecuteSqlSupportTest`（平台测试基类）。
 */
class RuntimeSlotChainConsistencyTest {

    private val loop = RuntimeSlot(
        id = "rt1",
        kind = SlotKind.LOOP,
        expression = "循环内追加",
        template = RuntimeSlot.joinBranches(listOf("id='%s' ", "or id='%s' ")),
        hint = ParamHint(),
        token = "/* 运行时值: 循环内追加 · rt1 */",
    )

    private val startTime = RuntimeSlot(
        id = "rt2",
        kind = SlotKind.VALUE,
        expression = "startTime",
        template = null,
        hint = ParamHint(quote = true),
        token = "/* 运行时值: startTime · rt2 */",
    )

    private val sql = "SELECT id, order_no FROM t_order WHERE (${loop.token}) " +
        "AND create_time >= '${startTime.token}'"

    private val values = RuntimeSlotValues.of(
        "rt1" to SlotValue.text("1,2,3"),
        "rt2" to SlotValue.text("2026-01-01 00:00:00"),
    )

    @Test
    fun `预览_复制_导出回放三处文本一致且无残留占位`() {
        val preview = ExecutableSqlFormatter.formatWithDiagnostics(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            slots = listOf(loop, startTime),
            slotValues = values,
            dialectName = MySqlDialect.name,
        )
        assertTrue("占位应全部回填: ${preview.warnings}", preview.warnings.isEmpty())

        val copy = CopyExecutableSqlAction.assembleProduct(
            rawSql = sql,
            slots = listOf(loop, startTime),
            slotValues = values,
            dialectName = MySqlDialect.name,
        )

        assertEquals("预览 / 复制必须逐字一致", preview.sql, copy.text)
        assertEquals("两处应报同一批已填占位", preview.appliedSlotIds, copy.slotsApplied)
        assertEquals(listOf("rt1", "rt2"), preview.appliedSlotIds)

        // 导出回放脚本消费的是面板交出的同一份文本（previewSqlForReadonly），这里用同一入参断言切分结果
        val statements = ReplayScriptExport.statementsOf(preview.sql)
        assertEquals("单条语句", 1, statements.size)
        assertEquals("导出语句本体应与可执行文本一致", preview.sql.trim().trimEnd(';'), statements[0].trim().trimEnd(';'))

        val script = ReplayScriptExport.render(
            ReplayScriptExport.plan(preview.sql, MySqlDialect, wrapTransaction = false)!!,
        )
        assertTrue("脚本里应出现展开后的循环: $script", script.contains("(id='1' or id='2' or id='3' )"))
        assertTrue("脚本里应出现回填的时间", script.contains("create_time >= '2026-01-01 00:00:00'"))
        assertFalse("脚本里不应残留占位注释", script.contains("运行时值"))
        assertFalse("可执行文本里不应残留占位注释", preview.sql.contains("运行时值"))
    }

    @Test
    fun `留空时三处都保持占位原样_不擅自转成NULL`() {
        val preview = ExecutableSqlFormatter.format(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            slots = listOf(loop, startTime),
            slotValues = RuntimeSlotValues.EMPTY,
            dialectName = MySqlDialect.name,
        )
        val copy = CopyExecutableSqlAction.assembleProduct(rawSql = sql, slots = listOf(loop, startTime))

        assertEquals("留空 = 与输入逐字一致（不填任何值）", sql, preview)
        // 复制链会剥注释：未填的块注释占位会被剥掉，落在引号里的占位（字符串内容）保留原样
        assertFalse("留空不允许落成 NULL", copy.text.contains("NULL"))
        assertTrue(
            "引号内的占位本身是字符串内容，应原样保留: ${copy.text}",
            copy.text.contains("'/* 运行时值: startTime · rt2 */'"),
        )
        assertEquals("留空不算回填", emptyList<String>(), copy.slotsApplied)
    }

    @Test
    fun `只填一个占位_其余原样保留`() {
        val preview = ExecutableSqlFormatter.format(
            sql = sql,
            sources = emptyList(),
            paramValues = emptyMap(),
            slots = listOf(loop, startTime),
            slotValues = RuntimeSlotValues.of("rt2" to SlotValue.text("2026-01-01")),
            dialectName = MySqlDialect.name,
        )
        assertTrue("已填的应落成字面量", preview.contains("create_time >= '2026-01-01'"))
        assertTrue("没填的应保留占位", preview.contains("运行时值: 循环内追加 · rt1"))
    }
}
