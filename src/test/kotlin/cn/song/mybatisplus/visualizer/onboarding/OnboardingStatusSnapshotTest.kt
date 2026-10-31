package cn.song.mybatisplus.visualizer.onboarding

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2「首用即生效」纯逻辑矩阵（testFast 快桶，零平台依赖）：
 *
 *  1. 状态栏快照 [buildSnapshot] 的三条分支（无捕获 / 有捕获 / 捕获中）与 Community 追加说明；
 *  2. widget 可用性 [isWidgetAvailable]（P-8：Community 同样可用，只随项目生命周期收放）；
 *  3. mapper 工程探测的文件名判定 [MapperProjectProbe.isMapperXmlFileName]（正例 + 反例）；
 *  4. 首用引导的防打扰三重约束中「版本粒度 / headless」两条 [MapperProjectPrimer.shouldProbe]。
 *
 * 断言文案一律经 `MpSqlVisualizerBundle` 取（不硬编码中英字面量——语言随设置变化，硬编码会脆）。
 */
class OnboardingStatusSnapshotTest {

    // ------------------------------------------------------------------
    // 状态栏快照
    // ------------------------------------------------------------------

    @Test
    fun `无捕获时显示就绪文案`() {
        val s = buildSnapshot(executeAvailable = true, capturing = false, entryCount = 0)
        assertEquals(MpSqlVisualizerBundle.message("statusbar.sqlviz.ready"), s.text)
        assertEquals(MpSqlVisualizerBundle.message("statusbar.sqlviz.tooltip"), s.tooltip)
    }

    @Test
    fun `有捕获时显示条数文案`() {
        val s = buildSnapshot(executeAvailable = true, capturing = false, entryCount = 12)
        assertEquals(MpSqlVisualizerBundle.message("statusbar.sqlviz.count", 12), s.text)
        assertNotEquals("条数文案不得与就绪文案相同", MpSqlVisualizerBundle.message("statusbar.sqlviz.ready"), s.text)
    }

    @Test
    fun `1条与0条文案必须不同_边界`() {
        val one = buildSnapshot(executeAvailable = true, capturing = false, entryCount = 1)
        val zero = buildSnapshot(executeAvailable = true, capturing = false, entryCount = 0)
        assertNotEquals("entryCount=1 必须走「有捕获」分支", zero.text, one.text)
    }

    @Test
    fun `捕获中在tooltip追加说明`() {
        val idle = buildSnapshot(executeAvailable = true, capturing = false, entryCount = 3)
        val live = buildSnapshot(executeAvailable = true, capturing = true, entryCount = 3)
        assertEquals("捕获中不改变主文本", idle.text, live.text)
        assertTrue(
            "捕获中 tooltip 必须包含追加说明",
            live.tooltip.contains(MpSqlVisualizerBundle.message("statusbar.sqlviz.tooltip.capturing")),
        )
        assertFalse(live.tooltip == idle.tooltip)
    }

    @Test
    fun `执行不可用时tooltip追加Community说明`() {
        val ultimate = buildSnapshot(executeAvailable = true, capturing = false, entryCount = 0)
        val community = buildSnapshot(executeAvailable = false, capturing = false, entryCount = 0)
        assertEquals("Community 不改变主文本（预览/复制照常可用）", ultimate.text, community.text)
        assertTrue(
            "执行不可用时 tooltip 必须说明原因",
            community.tooltip.contains(MpSqlVisualizerBundle.message("statusbar.sqlviz.tooltip.community")),
        )
        assertFalse(ultimate.tooltip == community.tooltip)
    }

    @Test
    fun `捕获中与Community两条追加可叠加`() {
        val s = buildSnapshot(executeAvailable = false, capturing = true, entryCount = 2)
        assertTrue(s.tooltip.contains(MpSqlVisualizerBundle.message("statusbar.sqlviz.tooltip.capturing")))
        assertTrue(s.tooltip.contains(MpSqlVisualizerBundle.message("statusbar.sqlviz.tooltip.community")))
    }

    // ------------------------------------------------------------------
    // widget 可用性（P-8）
    // ------------------------------------------------------------------

    @Test
    fun `widget随项目生命周期收放_Community不做版本降级`() {
        assertTrue("项目存活时 Community 也必须可用（EP 属平台核心，不依赖 Database 插件）", isWidgetAvailable(false))
        assertFalse("项目关闭窗口内不创建", isWidgetAvailable(true))
    }

    // ------------------------------------------------------------------
    // mapper 工程探测（文件名判定）
    // ------------------------------------------------------------------

    @Test
    fun `mapperXml文件名正例`() {
        assertTrue(MapperProjectProbe.isMapperXmlFileName("UserMapper.xml"))
        assertTrue(MapperProjectProbe.isMapperXmlFileName("order/OrderMapper.XML"))
        assertTrue("小写 mapper 也接受（大小写不敏感）", MapperProjectProbe.isMapperXmlFileName("user-mapper.xml"))
    }

    @Test
    fun `mapperXml文件名反例`() {
        assertFalse("普通 XML 不算", MapperProjectProbe.isMapperXmlFileName("applicationContext.xml"))
        assertFalse("hbm.xml 不是 MyBatis mapper", MapperProjectProbe.isMapperXmlFileName("User.hbm.xml"))
        assertFalse("非 xml 扩展名不算", MapperProjectProbe.isMapperXmlFileName("UserMapper.java"))
        assertFalse("裸 mapper.xml 之外的空名不算", MapperProjectProbe.isMapperXmlFileName(""))
        assertFalse("比扩展名还短不算", MapperProjectProbe.isMapperXmlFileName(".xml"))
    }

    // ------------------------------------------------------------------
    // 首用引导的防打扰约束（版本粒度 / headless）
    // ------------------------------------------------------------------

    @Test
    fun `headless下不探测`() {
        assertFalse(
            "单测/headless 一律不探测也不写记忆",
            MapperProjectPrimer.shouldProbe(unitTestMode = true, currentVersion = "2026.10.31", lastPrimedVersion = ""),
        )
    }

    @Test
    fun `同版本已提示过不再探测`() {
        assertFalse(
            "同版本永不重弹（一次性 / 版本粒度）",
            MapperProjectPrimer.shouldProbe(
                unitTestMode = false,
                currentVersion = "2026.10.31",
                lastPrimedVersion = "2026.10.31",
            ),
        )
    }

    @Test
    fun `版本变化或首次安装才探测`() {
        assertTrue(
            "首次安装（空记忆）→ 允许提示一次",
            MapperProjectPrimer.shouldProbe(unitTestMode = false, currentVersion = "2026.10.31", lastPrimedVersion = ""),
        )
        assertTrue(
            "版本升级 → 允许再提示一次",
            MapperProjectPrimer.shouldProbe(
                unitTestMode = false,
                currentVersion = "2026.11.01",
                lastPrimedVersion = "2026.10.31",
            ),
        )
    }

    @Test
    fun `插件版本未知时不探测`() {
        assertFalse(
            "非打包环境（读不到插件版本）不做版本粒度判定，宁可不弹",
            MapperProjectPrimer.shouldProbe(unitTestMode = false, currentVersion = null, lastPrimedVersion = ""),
        )
        assertFalse(MapperProjectPrimer.shouldProbe(unitTestMode = false, currentVersion = "  ", lastPrimedVersion = ""))
    }
}
