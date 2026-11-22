package cn.song.mybatisplus.visualizer.ui.standalone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补齐片段流程的纯逻辑半边（T-PreviewActionRow D-6）。
 *
 * 只覆盖 [MissingFragmentFillFlow.record]——「弹框问内容」那半是 UI（工作台/弹窗用例走注入缝覆盖）。
 * 这条规则本身值得单测：空内容/纯空白必须**不算补**，否则调用方会拿空片段重解析一次，
 * 缺失集合不变、用户却看到"点了没反应"。
 */
class MissingFragmentFillFlowTest {

    @Test
    fun `内容非空时记进overrides`() {
        val overrides = linkedMapOf<String, String>()
        assertTrue(MissingFragmentFillFlow.record("Cols", "id, name", overrides))
        assertEquals("id, name", overrides["Cols"])
    }

    @Test
    fun `内容首尾空白被裁掉`() {
        val overrides = linkedMapOf<String, String>()
        assertTrue(MissingFragmentFillFlow.record("Cols", "  id, name\n", overrides))
        assertEquals("id, name", overrides["Cols"])
    }

    @Test
    fun `取消或空白内容不算补`() {
        val overrides = linkedMapOf<String, String>()
        assertFalse("取消（null）不应记入", MissingFragmentFillFlow.record("Cols", null, overrides))
        assertFalse("纯空白不应记入", MissingFragmentFillFlow.record("Cols", "   \n ", overrides))
        assertTrue("overrides 应保持为空", overrides.isEmpty())
    }

    @Test
    fun `同一refid重复补以最后一次为准`() {
        val overrides = linkedMapOf<String, String>()
        MissingFragmentFillFlow.record("Cols", "id", overrides)
        MissingFragmentFillFlow.record("Cols", "id, name", overrides)
        assertEquals("id, name", overrides["Cols"])
    }
}