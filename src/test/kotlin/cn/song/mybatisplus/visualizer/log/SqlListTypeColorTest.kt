package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color

/**
 * [SqlListTypeColor] 纯逻辑单测（T-CrudColor）。
 *
 * 覆盖：样式归一化（未知值兜底 bar）、类型→标记色（四类型 / 关闭 / OTHER / 非法色值回退）、
 * `#RRGGBB` 解析容错（缺 `#`、非法字符、空串、超长一律回退且不抛）、混色线性（0→base、1→tint、中间逐通道）。
 * （T-UN-09：选中描边退役，`ensureOutlineContrast` 提亮算法与 `usesSelectionFill` 已随删，相关用例移除。）
 *
 * 全部走**纯函数重载**（[SqlListTypeColor.parseHex] / [SqlListTypeColor.blend] /
 * [SqlListTypeColor.colorOf] 三参版 / [SqlListTypeColor.styleOf] 单参版），零平台依赖——
 * 读设置的薄壳由 SqlLogPanelRendererUiTest 等平台用例覆盖。
 */
class SqlListTypeColorTest {

    private val fallback = Color(0x123456)

    // ---------- 样式归一化 ----------

    @Test
    fun `样式_三档原样通过`() {
        assertEquals(SqlListTypeColor.STYLE_BAR, SqlListTypeColor.styleOf("bar"))
        assertEquals(SqlListTypeColor.STYLE_BACKGROUND, SqlListTypeColor.styleOf("background"))
        assertEquals(SqlListTypeColor.STYLE_OFF, SqlListTypeColor.styleOf("off"))
    }

    @Test
    fun `样式_未知值兜底为色条`() {
        assertEquals("未知样式应兜底 bar（防配置写错整列表失色）",
            SqlListTypeColor.STYLE_BAR, SqlListTypeColor.styleOf(""))
        assertEquals(SqlListTypeColor.STYLE_BAR, SqlListTypeColor.styleOf("BAR"))
        assertEquals(SqlListTypeColor.STYLE_BAR, SqlListTypeColor.styleOf("nonsense"))
    }

    // ---------- 类型 → 标记色 ----------

    @Test
    fun `标记色_四类型取到对应设置色`() {
        assertEquals(Color(0x111111), SqlListTypeColor.colorOf(StatementType.SELECT, "bar", "#111111"))
        assertEquals(Color(0x222222), SqlListTypeColor.colorOf(StatementType.INSERT, "bar", "#222222"))
        assertEquals(Color(0x333333), SqlListTypeColor.colorOf(StatementType.UPDATE, "bar", "#333333"))
        assertEquals(Color(0x444444), SqlListTypeColor.colorOf(StatementType.DELETE, "bar", "#444444"))
    }

    @Test
    fun `标记色_关闭样式一律不标记`() {
        StatementType.values().forEach {
            assertNull("off 样式下 $it 不应有标记色",
                SqlListTypeColor.colorOf(it, SqlListTypeColor.STYLE_OFF, "#4A86E8"))
        }
    }

    @Test
    fun `标记色_OTHER 恒不标记`() {
        assertNull(SqlListTypeColor.colorOf(StatementType.OTHER, "bar", "#4A86E8"))
        assertNull(SqlListTypeColor.colorOf(StatementType.OTHER, "background", "#4A86E8"))
    }

    @Test
    fun `标记色_非法色值回退默认色`() {
        assertEquals("非法色值应回退该类型默认色",
            SqlListTypeColor.defaultOf(StatementType.SELECT),
            SqlListTypeColor.colorOf(StatementType.SELECT, "bar", "zzz"))
        assertEquals(SqlListTypeColor.defaultOf(StatementType.DELETE),
            SqlListTypeColor.colorOf(StatementType.DELETE, "bar", ""))
    }

    // ---------- 色值解析容错 ----------

    @Test
    fun `解析_合法格式`() {
        assertEquals(Color(0x4A86E8), SqlListTypeColor.parseHex("#4A86E8", fallback))
        assertEquals("允许省略 #", Color(0x4A86E8), SqlListTypeColor.parseHex("4A86E8", fallback))
        assertEquals("大小写不敏感", Color(0xABCDEF), SqlListTypeColor.parseHex("#abcdef", fallback))
        assertEquals(Color(0x000000), SqlListTypeColor.parseHex("#000000", fallback))
        assertEquals(Color(0xFFFFFF), SqlListTypeColor.parseHex("#FFFFFF", fallback))
    }

    @Test
    fun `解析_非法输入一律回退且不抛`() {
        listOf("", "  ", "#", "##4A86E8", "#4A86E", "#4A86E88", "#GGGGGG", "0x4A86E8", "#4A86E8;").forEach {
            assertEquals("非法色值 '$it' 应回退", fallback, SqlListTypeColor.parseHex(it, fallback))
        }
    }

    // ---------- 混色 ----------

    @Test
    fun `混色_端点恒等`() {
        val base = Color(0x1E1F22)
        val tint = Color(0x4A86E8)
        assertEquals(base, SqlListTypeColor.blend(base, tint, 0.0))
        assertEquals(tint, SqlListTypeColor.blend(base, tint, 1.0))
    }

    @Test
    fun `混色_中间值逐通道线性`() {
        assertEquals(Color(128, 128, 128), SqlListTypeColor.blend(Color(0, 0, 0), Color(255, 255, 255), 0.5))
        // half-up：51*0.5=25.5→26、76.5→77（非银行家舍入）
        assertEquals(Color(26, 51, 77), SqlListTypeColor.blend(Color(0, 0, 0), Color(51, 102, 153), 0.5))
    }

    @Test
    fun `混色_超界比例被夹紧`() {
        val base = Color(10, 20, 30)
        val tint = Color(200, 210, 220)
        assertEquals(base, SqlListTypeColor.blend(base, tint, -1.0))
        assertEquals(tint, SqlListTypeColor.blend(base, tint, 2.0))
    }

    @Test
    fun `混色_暗色底实测值与设计稿一致`() {
        // 设计稿色值表（计划 §1.2.1）复现：blend(#1E1F22, 类型色, 0.14)
        val dark = Color(0x1E1F22)
        assertEquals(Color(0x242D3E), SqlListTypeColor.blend(dark, Color(0x4A86E8), SqlListTypeColor.MIX_NORMAL))
        assertEquals(Color(0x243328), SqlListTypeColor.blend(dark, Color(0x4CAF50), SqlListTypeColor.MIX_NORMAL))
        assertEquals(Color(0x372124), SqlListTypeColor.blend(dark, Color(0xD32F2F), SqlListTypeColor.MIX_NORMAL))
    }

    @Test
    fun `混色_亮色底实测值与设计稿一致`() {
        val light = Color(0xFFFFFF)
        assertEquals(Color(0xE6EEFC), SqlListTypeColor.blend(light, Color(0x4A86E8), SqlListTypeColor.MIX_NORMAL))
        assertEquals(Color(0xE6F4E7), SqlListTypeColor.blend(light, Color(0x4CAF50), SqlListTypeColor.MIX_NORMAL))
        assertEquals(Color(0xF9E2E2), SqlListTypeColor.blend(light, Color(0xD32F2F), SqlListTypeColor.MIX_NORMAL))
    }

    // ---------- T-DdlTypeColor：DDL 着色与虚线形态 ----------

    /** CREATE 复用 INSERT 绿、DROP 复用 DELETE 红（不新增设置项）。 */
    fun testDdl着色复用Dml色() {
        val p = SqlListTypeColor.STYLE_BAR
        val create = SqlListTypeColor.colorOf(StatementType.CREATE, p, "#4CAF50")
        val insert = SqlListTypeColor.colorOf(StatementType.INSERT, p, "#4CAF50")
        val drop = SqlListTypeColor.colorOf(StatementType.DROP, p, "#D32F2F")
        val delete = SqlListTypeColor.colorOf(StatementType.DELETE, p, "#D32F2F")
        assertNotNull("CREATE 应着色", create)
        assertNotNull("DROP 应着色", drop)
        assertEquals("CREATE 与 INSERT 同色", insert, create)
        assertEquals("DROP 与 DELETE 同色", delete, drop)
    }

    /** OTHER 仍然不着色（未识别语句不误导用户）。 */
    fun testOther仍不着色() {
        assertNull(SqlListTypeColor.colorOf(StatementType.OTHER, SqlListTypeColor.STYLE_BAR, "#4A86E8"))
    }

    /** isDdl：只有 CREATE/DROP 两档为 true。 */
    fun testIsDdl判定() {
        assertTrue(SqlListTypeColor.isDdl(StatementType.CREATE))
        assertTrue(SqlListTypeColor.isDdl(StatementType.DROP))
        assertFalse(SqlListTypeColor.isDdl(StatementType.SELECT))
        assertFalse(SqlListTypeColor.isDdl(StatementType.INSERT))
        assertFalse(SqlListTypeColor.isDdl(StatementType.UPDATE))
        assertFalse(SqlListTypeColor.isDdl(StatementType.DELETE))
        assertFalse(SqlListTypeColor.isDdl(StatementType.OTHER))
    }
}
