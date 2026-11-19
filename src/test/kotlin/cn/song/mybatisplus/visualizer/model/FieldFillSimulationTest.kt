package cn.song.mybatisplus.visualizer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FieldFillSimulation 纯逻辑测试（master `model/FieldFillSimulationTest.kt` 移植，k2j 生产为
 * Java 构造器位置实参形态；本轮 T-PreQuotedLiteral 三例含预引号透传与安全闸门语义）。
 */
class FieldFillSimulationTest {
    @Test
    fun overlayHonorsUserSimulationRegardlessOfFillKind() {
        // 用户真机反馈（UPDATE 预览里 [fill=INSERT] 的 create_time）：面板为该字段提供了
        // 模拟行并置灰普通输入，旧实现按 fill 策略过滤 → 模拟值被静默忽略、SQL 回落灰框值。
        val insertOnly = BatchColumn("created", "e.created", true, true, false, "INSERT")
        val model = BatchModel(
            BatchKind.UPDATE, "t", listOf(insertOnly),
            listOf(BatchColumn("id", "e.id", false, true, false, "INSERT_UPDATE")),
        )
        val overlay = FieldFillSimulation.overlay(
            model, setOf("created"), mapOf("e.created" to "2026-10-19 12:00:00"), true,
        )
        assertEquals(mapOf(0 to "'2026-10-19 12:00:00'"), overlay.literals())
        assertTrue(overlay.invalidSources().isEmpty())
    }

    @Test
    fun overlayStillExcludesUpsert() {
        val write = BatchColumn("stamp", "e.stamp", false, true, false, "INSERT_UPDATE")
        val model = BatchModel(BatchKind.UPSERT, "t", listOf(write), listOf())
        val overlay = FieldFillSimulation.overlay(
            model, setOf("stamp"), mapOf("e.stamp" to "42"), true,
        )
        assertTrue("UPSERT 运行期分支不可知，仍不模拟", overlay.literals().isEmpty())
    }

    @Test
    fun numericAndInjectionNegatives() {
        for (value in listOf("now()", "1; DROP TABLE t", "1 OR 1=1", "1,2", "NaN", "Infinity", "0x10", "--1", "1/*x*/", "'1'")) {
            assertNull(value, FieldFillSimulation.literal(value, false))
        }
        for (value in listOf("0", "-12.5", "+2", ".5", "1e3")) assertEquals(value, FieldFillSimulation.literal(value, false))
        assertEquals("'O''Reilly'", FieldFillSimulation.literal("O'Reilly", true))
        assertEquals("'now()'", FieldFillSimulation.literal("now()", true))
        // T-PreQuotedLiteral：用户已手工加引号的值原样（不翻倍 `'''abc'''`）
        assertEquals("'abc'", FieldFillSimulation.literal("'abc'", true))
        assertEquals("''", FieldFillSimulation.literal("''", true))
        // 预引号透传在安全闸门之后：分号/反斜杠/控制符拒绝语义不变
        assertNull(FieldFillSimulation.literal("'; DROP TABLE t; --'", true))
        assertNull(FieldFillSimulation.literal("'x\\y'", true))
        assertNull(FieldFillSimulation.literal("x'; DROP TABLE t", true))
        assertNull(FieldFillSimulation.literal("x\\' OR 1=1", true))
        assertNull(FieldFillSimulation.literal("a\nb", true))
    }

    @Test
    fun overlayRespectsSelectionsDisableAndValidation() {
        val write = BatchColumn("stamp", "e.stamp", false, true, false, "INSERT_UPDATE")
        val fixed = BatchColumn("id", "e.id", false, true, false, "INSERT_UPDATE")
        val model = BatchModel(BatchKind.UPDATE, "t", listOf(write), listOf(fixed))
        val values = mapOf("e.stamp" to "42", "e.id" to "99")
        fun overlay(active: Set<String> = setOf("stamp"), enabled: Boolean = true) =
            FieldFillSimulation.overlay(model, active, values, enabled)
        assertEquals(mapOf(0 to "42"), overlay().literals())
        assertTrue(overlay(emptySet()).literals().isEmpty())
        assertTrue(overlay(enabled = false).literals().isEmpty())
        assertEquals("42", values["e.stamp"])
        val invalid = FieldFillSimulation.overlay(model, setOf("stamp"), mapOf("e.stamp" to "1;select 1"), true)
        assertEquals(mapOf(0 to "?"), invalid.literals())
        assertEquals(setOf("e.stamp"), invalid.invalidSources())
    }
}
