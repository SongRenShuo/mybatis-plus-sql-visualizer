package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-RuntimeSlotFill 券 B：占位值模型与静态校验（纯单测，零 PSI / 零 IDE 依赖）。
 *
 * 面板（券 C）直接用它建行与做「应用」前校验，因此这里钉住三件事：
 *  - `isUnset` 的边界（留空 = 保留占位，绝不能等同 NULL）；
 *  - `splitList` 的拆分口径（逗号 / 换行 / 中文逗号，两端空白与空项丢弃）；
 *  - `validate` 只报问题、不擅自改值，且类别相关的空值有提示。
 */
class RuntimeSlotValuesTest {

    private fun slot(id: String, kind: SlotKind, expression: String = "x") = RuntimeSlot(
        id = id,
        kind = kind,
        expression = expression,
        template = null,
        hint = ParamHint(),
        token = "/* 运行时值: $expression · $id */",
    )

    // ---- isUnset / 留空语义 ------------------------------------------

    @Test
    fun `未动作判定_默认值才算未动作`() {
        assertTrue("全默认 = 未动作", SlotValue.UNSET.isUnset)
        assertTrue("显式空文本 + AUTO 也是未动作", SlotValue.text("").isUnset)
        assertTrue("纯空白也是未动作", SlotValue.text("   ").isUnset)

        assertFalse("选了类型就算动作", SlotValue.text("", ValueType.NULL).isUnset)
        assertFalse("填了值就算动作", SlotValue.text("1").isUnset)
        assertFalse("选了分支就算动作", SlotValue.branch(0).isUnset)
        assertFalse("取消包含就算动作", SlotValue.excluded().isUnset)
    }

    @Test
    fun `effective只保留用户动作过的条目`() {
        val values = RuntimeSlotValues.of(
            "rt1" to SlotValue.UNSET,
            "rt2" to SlotValue.text("x"),
            "rt3" to SlotValue.excluded(),
        )
        assertEquals(setOf("rt2", "rt3"), values.effective().keys)
        assertTrue("rt1 未动作", !values.isSet("rt1"))
        assertTrue("rt3 已动作（取消包含）", values.isSet("rt3"))
        assertEquals(SlotValue.text("x"), values["rt2"])
    }

    @Test
    fun `空值表为空且不影响任何占位`() {
        assertTrue(RuntimeSlotValues.EMPTY.isEmpty)
        assertEquals(emptyMap<String, SlotValue>(), RuntimeSlotValues.EMPTY.effective())
        assertEquals(emptyList<SlotWarning>(), RuntimeSlotValues.EMPTY.validate(listOf(slot("rt1", SlotKind.VALUE))))
    }

    // ---- 多值拆分 ----------------------------------------------------

    @Test
    fun `多值拆分_逗号换行中文逗号都可且丢空项`() {
        assertEquals(listOf("1", "2", "3"), RuntimeSlotValues.splitList("1, 2\n3"))
        assertEquals(listOf("a", "b"), RuntimeSlotValues.splitList(" a ，b ,, \n"))
        assertEquals(emptyList<String>(), RuntimeSlotValues.splitList("  ,\n "))
    }

    @Test
    fun `多值构造用一项一行编码_拆分口径与手输一致`() {
        val value = SlotValue.list("a", "b")
        assertEquals(ValueType.LIST, value.type)
        assertEquals("a\nb", value.text)
        assertEquals(listOf("a", "b"), RuntimeSlotValues.splitList(value.text))
        // 逗号与换行都是分隔符（与用户手输 `1,2` 同口径）——项内含逗号会被拆开，属已声明的限制
        assertEquals(listOf("1", "2", "3"), RuntimeSlotValues.splitList("1,\n2,3"))
    }

    // ---- 校验 --------------------------------------------------------

    @Test
    fun `校验_未知占位id有提示`() {
        val warnings = RuntimeSlotValues.of("rt9" to SlotValue.text("1")).validate(listOf(slot("rt1", SlotKind.VALUE)))
        assertEquals(1, warnings.size)
        assertEquals("rt9", warnings[0].slotId)
        assertTrue(warnings[0].message.contains("未知"))
    }

    @Test
    fun `校验_循环空值与超限有提示`() {
        val slots = listOf(slot("rt1", SlotKind.LOOP))
        val empty = RuntimeSlotValues.of("rt1" to SlotValue.text(" , ")).validate(slots)
        assertTrue("空循环值应提示: ${empty.map { it.message }}", empty.any { it.message.contains("没有值") })

        val many = (1..RuntimeSlotValues.MAX_LOOP_ITEMS + 1).joinToString(",")
        val overflow = RuntimeSlotValues.of("rt1" to SlotValue.text(many)).validate(slots)
        assertTrue("超限应提示: ${overflow.map { it.message }}", overflow.any { it.message.contains("过多") })
    }

    @Test
    fun `校验_三元未选分支与非法分支有提示`() {
        val slots = listOf(slot("rt1", SlotKind.TERNARY))
        assertEquals(
            "未选分支",
            true,
            RuntimeSlotValues.of("rt1" to SlotValue.text("1")).validate(slots).any { it.message.contains("没选分支") },
        )
        assertTrue(
            "非法分支",
            RuntimeSlotValues.of("rt1" to SlotValue.branch(2)).validate(slots).any { it.message.contains("0（真）") },
        )
    }

    @Test
    fun `校验_值超长与数字类型非法有提示`() {
        val slots = listOf(slot("rt1", SlotKind.VALUE))
        val long = "x".repeat(RuntimeSlotValues.MAX_VALUE_LENGTH + 1)
        val warnings = RuntimeSlotValues.of(
            "rt1" to SlotValue.number(long),
        ).validate(slots)
        assertTrue("超长应提示: ${warnings.map { it.message }}", warnings.any { it.message.contains("过长") })
        assertTrue("非数字应提示: ${warnings.map { it.message }}", warnings.any { it.message.contains("不是数字") })
    }

    @Test
    fun `校验_取消包含却填了值会提示值不生效`() {
        val warnings = RuntimeSlotValues.of(
            "rt1" to SlotValue(include = false, text = "abc"),
        ).validate(listOf(slot("rt1", SlotKind.OPTIONAL)))
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message.contains("不会生效") || warnings[0].message.contains("被忽略"))
    }
}
