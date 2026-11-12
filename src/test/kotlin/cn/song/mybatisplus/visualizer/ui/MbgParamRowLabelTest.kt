package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D11b：MBG 预览参数行的**用户可读标签**。
 *
 * 真机问题（2026-11-12 16:0x）：行标签显示成「条件 2 值」——`__frch_criterion_N` 的 N 是渲染期
 * 唯一编号、**不是** criterion 下标，按下标去取条件会落空，用户看到编号根本不知道要填什么。
 * 现在按**首次出现顺序**与注入的 criterion 对齐，区间条件再区分「起始值 / 结束值」。
 */
class MbgParamRowLabelTest {

    @Test
    fun `单值条件_标签即条件文本`() {
        val rows = mbgParamRowsOf(
            listOf("__frch_criterion_0.value"),
            listOf("cust_id ="),
        )
        assertEquals(listOf("__frch_criterion_0.value" to "cust_id = 值"), rows)
    }

    @Test
    fun `区间条件_两个占位分起始与结束`() {
        val rows = mbgParamRowsOf(
            listOf("__frch_criterion_0.value", "__frch_criterion_0.secondValue"),
            listOf("create_time between"),
        )
        assertEquals(
            listOf(
                "__frch_criterion_0.value" to "create_time between 起始值",
                "__frch_criterion_0.secondValue" to "create_time between 结束值",
            ),
            rows,
        )
    }

    @Test
    fun `按出现顺序对齐而不是按编号`() {
        // 编号是 7 / 9（渲染期计数器），条件只有两条——必须按顺序取 condition[0] / condition[1]
        val rows = mbgParamRowsOf(
            listOf("__frch_criterion_7.value", "__frch_criterion_9.value"),
            listOf("cust_id =", "staff_login like"),
        )
        assertEquals(
            listOf(
                "__frch_criterion_7.value" to "cust_id = 值",
                "__frch_criterion_9.value" to "staff_login like 值",
            ),
            rows,
        )
    }

    @Test
    fun `条件文本缺失_退回可读序号而不是裸编号`() {
        val rows = mbgParamRowsOf(
            listOf("__frch_criterion_3.value"),
            listOf(null),
        )
        assertEquals(listOf("__frch_criterion_3.value" to "条件 1 值"), rows)
    }

    @Test
    fun `非 criterion 来源不进本区`() {
        val rows = mbgParamRowsOf(
            listOf("__frch_listItem_0", "__frch_criterion_0.value"),
            listOf("cust_id ="),
        )
        assertEquals(listOf("__frch_criterion_0.value" to "cust_id = 值"), rows)
    }
}
