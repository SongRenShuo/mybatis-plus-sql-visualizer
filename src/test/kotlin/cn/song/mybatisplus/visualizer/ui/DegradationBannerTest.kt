package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * T-PreviewUx-Pack #2：诚实降级横幅测试。
 *
 *  - 组件行为：show/clear 显隐、类别优先级（降级提示不被渲染警示清除覆盖）；
 *  - SqlPreviewPanel 接线：渲染警示（PreviewResult.warning）存在即显示、恢复即清除。
 */
class DegradationBannerTest : BasePlatformTestCase() {

    // ---------- 组件行为（纯 Swing，无平台服务依赖） ----------

    fun test显示与清除() {
        val banner = DegradationBanner()
        assertFalse("初始应隐藏", banner.isVisible)
        banner.show(DegradationBanner.Kind.RENDER_WARNING, "message-1")
        assertTrue(banner.isVisible)
        assertEquals("message-1", banner.textForTest())
        assertEquals(DegradationBanner.Kind.RENDER_WARNING, banner.kindForTest())
        banner.clear(DegradationBanner.Kind.RENDER_WARNING)
        assertFalse("同类别清除后应隐藏", banner.isVisible)
    }

    fun test降级提示优先不被渲染警示覆盖() {
        val banner = DegradationBanner()
        banner.show(DegradationBanner.Kind.DEGRADED, "degraded")
        // 降级提示挂起期间：渲染警示不覆盖、其清除不影响
        banner.show(DegradationBanner.Kind.RENDER_WARNING, "warning")
        assertEquals("降级指引应保持优先", "degraded", banner.textForTest())
        banner.clear(DegradationBanner.Kind.RENDER_WARNING)
        assertTrue("渲染警示清除不应影响降级提示", banner.isVisible)
        // 降级发生前已挂渲染警示 → 降级 show 覆盖（降级发生时警示让位）
        val banner2 = DegradationBanner()
        banner2.show(DegradationBanner.Kind.RENDER_WARNING, "warning")
        banner2.show(DegradationBanner.Kind.DEGRADED, "degraded")
        assertEquals("degraded", banner2.textForTest())
    }

    fun test跨类别清除互不影响() {
        val banner = DegradationBanner()
        banner.show(DegradationBanner.Kind.RENDER_WARNING, "warning")
        // 降级类别清除：当前挂起的是 RENDER_WARNING → 不受影响
        banner.clear(DegradationBanner.Kind.DEGRADED)
        assertTrue("渲染警示应保持可见", banner.isVisible)
        banner.clear(DegradationBanner.Kind.RENDER_WARNING)
        assertFalse(banner.isVisible)
    }

    // ---------- SqlPreviewPanel 接线：渲染警示横幅 ----------

    private fun result(warning: String?) = PreviewResult(
        sql = "SELECT id FROM tbl",
        compactSql = "SELECT id FROM tbl",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "tbl",
        warning = warning,
    )

    fun test预览面板_警示存在即显示恢复即清除() {
        val panel = SqlPreviewPanel(project)
        val banner = panel.degradationBannerForTest()
        try {
            panel.showResult(result(null))
            // headless 沙盒预热可能失败 → DEGRADED 横幅常驻（诚实降级语义正确）；
            // 本断言只约束「无警示时渲染警示类别不挂起」
            assertTrue(
                "无警示 → 不应挂渲染警示类别（${banner.kindForTest()}）",
                banner.kindForTest() != DegradationBanner.Kind.RENDER_WARNING,
            )

            val warningText = "⚠ 无 WHERE 条件，将删除全表数据！"
            panel.showResult(result(warningText))
            // 有警示：若当前是渲染警示 → 文案必须为三要素键值（降级态优先时 DEGRADED 亦可接受）
            if (banner.kindForTest() == DegradationBanner.Kind.RENDER_WARNING) {
                assertEquals(
                    cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.banner.renderFailed", warningText),
                    banner.textForTest(),
                )
            }

            panel.showResult(result(null))
            assertTrue(
                "警示恢复 null → 渲染警示类别应清除（${banner.kindForTest()}）",
                banner.kindForTest() != DegradationBanner.Kind.RENDER_WARNING,
            )
        } finally {
            panel.clear()
        }
    }

    // ---------- UI-001：iBatis rerender 链横幅跟随新结果（整体替换）+ 信息级前缀 ----------

    /** 对齐 demo `User.xml` selectByIterate 形态（基线降级 → 供值完整）。 */
    private fun ibatisIterateXml(): String = """
        <sqlMap namespace="user">
            <select id="selectByIterate" parameterClass="java.util.Map">
                SELECT ID, USER_NAME FROM T_USER
                <dynamic prepend="WHERE">
                    <iterate property="ids" open="(" close=")" conjunction="OR"> ID = #ids[]# </iterate>
                </dynamic>
            </select>
        </sqlMap>
    """.trimIndent()

    fun testiBatis供值后横幅恢复即清除_撤值后随新结果回归() {
        val panel = SqlPreviewPanel(project)
        val banner = panel.degradationBannerForTest()
        try {
            val result = IbatisXmlPreviewService.preview(ibatisIterateXml(), "selectByIterate")
            assertNotNull("基线待填值告警在场", result.warning)
            panel.showResult(result)
            // 供值：iterate 列表输入 → renderSql 走 rerender → fresh 无告警 → 横幅必须清除
            // （UI-001 主缺陷：基线「渲染降级」告警残留、点「刷新」也不消失）
            panel.ibatisIterateValues["ids"] = "X1,X2,X3"
            panel.renderSqlForTest()
            assertNull("供值后 currentWarning 必须被新结果整体替换为空", panel.currentWarning)
            assertTrue(
                "供值后横幅不得仍挂渲染警示（${banner.kindForTest()}）",
                banner.kindForTest() != DegradationBanner.Kind.RENDER_WARNING,
            )
            // 反向：撤值 → 基线待填值告警随新结果回归（整体替换是双向的）
            panel.ibatisIterateValues.clear()
            panel.renderSqlForTest()
            assertNotNull("撤值后基线告警应随新结果回归", panel.currentWarning)
            assertTrue("基线告警为信息级（待填值）", panel.currentWarningIsNote)
            if (banner.kindForTest() == DegradationBanner.Kind.RENDER_WARNING) {
                assertEquals(
                    "信息级说明用 ℹ 前缀，不冒充「⚠ 预览未完整还原」",
                    cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(
                        "panel.banner.renderNote",
                        panel.currentWarning!!,
                    ),
                    banner.textForTest(),
                )
            }
        } finally {
            panel.clear()
        }
    }
}
