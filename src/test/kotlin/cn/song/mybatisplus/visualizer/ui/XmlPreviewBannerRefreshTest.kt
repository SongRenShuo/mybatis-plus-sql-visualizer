package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.xml.XmlMapperParser
import cn.song.mybatisplus.visualizer.xml.XmlPreviewModel
import cn.song.mybatisplus.visualizer.xml.XmlPreviewProfile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-PreviewBannerLiveRefresh + T-FullTableWriteRisk + T-ForEachInert：构件随**本轮产物**走的三条接线。
 *
 * 场景照 MBG 生成的 `deleteByExample`（真机 2026-11-12 用户截图现场；demo 里该方法没有调用点，
 * 故走模拟参数骨架：`delete from CUST_ADDRESS WHERE ( {{criterion.condition}} ? )`）。
 *
 * 三条待验证的接线：
 *  1. 勾选变化后横幅**随本轮重渲染刷新**（旧行为：`renderSql` 的 XML 分支不调 `updateMeta`，
 *     横幅停在 `showResult` 那一刻——占位已经没了，「预览未完整还原」还挂着）；
 *  2. 产物变成无 WHERE 的全表写时顶上**写风险横幅**（且优先级高于渲染警示）；
 *  3. `criterion.value` 这类「分支没走到」的 foreach 值框给出「填了不影响产物」提示。
 *
 * 判据都只经过 `renderSqlForTest()`（不重开预览）——这正是旧行为失效的那条路径。
 */
class XmlPreviewBannerRefreshTest : BasePlatformTestCase() {

    private val deleteByExampleXml = """
        <mapper namespace="n">
          <sql id="Example_Where_Clause">
            <where>
              <foreach collection="oredCriteria" item="criteria" separator="or">
                <if test="criteria.valid">
                  <trim prefix="(" prefixOverrides="and" suffix=")">
                    <foreach collection="criteria.criteria" item="criterion">
                      <choose>
                        <when test="criterion.noValue">
                          and ${'$'}{criterion.condition}
                        </when>
                        <when test="criterion.singleValue">
                          and ${'$'}{criterion.condition} #{criterion.value}
                        </when>
                        <when test="criterion.betweenValue">
                          and ${'$'}{criterion.condition} #{criterion.value} and #{criterion.secondValue}
                        </when>
                        <when test="criterion.listValue">
                          and ${'$'}{criterion.condition}
                          <foreach close=")" collection="criterion.value" item="listItem" open="(" separator=",">
                            #{listItem}
                          </foreach>
                        </when>
                      </choose>
                    </foreach>
                  </trim>
                </if>
              </foreach>
            </where>
          </sql>
          <delete id="deleteByExample" parameterType="x.CustAddressExample">
            delete from CUST_ADDRESS
            <if test="_parameter != null">
              <include refid="Example_Where_Clause" />
            </if>
          </delete>
        </mapper>
    """.trimIndent()

    private fun panelWithDeleteByExample(): SqlPreviewPanel {
        val parsed = XmlMapperParser().parse(deleteByExampleXml)
        val model = XmlPreviewModel.from(
            parsed.statements.first { it.id == "deleteByExample" },
            parsed.errors,
            profile = XmlPreviewProfile.MBG_EXAMPLE,
        )
        val panel = SqlPreviewPanel(project)
        panel.showResult(XmlPreviewService.toResult(model, includeRenderErrors = false))
        return panel
    }

    private fun normalized(sql: String?): String = sql.orEmpty().replace(Regex("\\s+"), " ").trim()

    // ---------------- 1 + 2：横幅随本轮产物刷新 + 全表写风险 ----------------

    fun test取消勾选后横幅随本轮刷新_陈旧渲染警示清除且写风险顶上() {
        val panel = panelWithDeleteByExample()
        val banner = panel.degradationBannerForTest()
        try {
            // 默认态：`_parameter != null` 勾着 ⇒ 产物含 WHERE，`${criterion.condition}` 占位在场
            val defaultSql = normalized(panel.lastRenderedSqlForTest())
            assertTrue("默认产物应带 WHERE：$defaultSql", defaultSql.contains("WHERE"))
            assertTrue("默认产物应含未还原的 ${'$'}{criterion.condition} 占位：$defaultSql", defaultSql.contains("criterion.condition"))
            assertNull("带 WHERE ⇒ 无全表写风险", panel.writeRiskWarning)
            assertTrue(
                "占位未还原 ⇒ 渲染警示在场（${panel.currentWarning}）",
                panel.currentWarning.orEmpty().contains("求值为空"),
            )

            // 只重渲染（不重开预览）：勾掉 `_parameter != null` ⇒ 产物变成无 WHERE 的全表 DELETE
            panel.enabledIds.remove("_parameter != null")
            panel.renderSqlForTest()

            assertEquals(
                "delete from cust_address",
                normalized(panel.lastRenderedSqlForTest()).lowercase(),
            )
            assertNull("产物已无占位 ⇒ 陈旧渲染警示必须随本轮清除", panel.currentWarning)
            assertEquals(FullTableWriteRisk.DELETE, panel.writeRiskWarning)
            assertEquals(DegradationBanner.Kind.WRITE_RISK, banner.kindForTest())
            assertEquals(
                MpSqlVisualizerBundle.message("panel.banner.writeRiskDelete"),
                banner.textForTest(),
            )

            // 反向：勾回来 ⇒ 风险随本轮消失、渲染警示回归（整体替换是双向的）
            panel.enabledIds.add("_parameter != null")
            panel.renderSqlForTest()
            assertNull("带 WHERE 后风险必须清除", panel.writeRiskWarning)
            assertTrue(
                "写风险不得残留在横幅上（${banner.kindForTest()}）",
                banner.kindForTest() != DegradationBanner.Kind.WRITE_RISK,
            )
            assertTrue(
                "占位回来 ⇒ 渲染警示回归（${panel.currentWarning}）",
                panel.currentWarning.orEmpty().contains("求值为空"),
            )
        } finally {
            panel.clear()
        }
    }

    fun test写风险优先级高于渲染警示() {
        // 构造「同时存在」的形态：产物既无 WHERE（全表写）又留下未还原占位
        // `delete from CUST_ADDRESS ${...}` —— `<if>` 勾着但 WHERE 段整体没进语句
        val xml = """
            <mapper namespace="n">
              <delete id="deleteByExample">
                delete from CUST_ADDRESS
                <if test="_parameter != null">${'$'}{criterion.condition}</if>
              </delete>
            </mapper>
        """.trimIndent()
        val parsed = XmlMapperParser().parse(xml)
        val model = XmlPreviewModel.from(parsed.statements.first { it.id == "deleteByExample" }, parsed.errors)
        val panel = SqlPreviewPanel(project)
        val banner = panel.degradationBannerForTest()
        try {
            panel.showResult(XmlPreviewService.toResult(model, includeRenderErrors = false))
            panel.renderSqlForTest()
            assertEquals("入场即无 WHERE 的全表写", FullTableWriteRisk.DELETE, panel.writeRiskWarning)
            assertEquals(
                "写风险应压过渲染警示（${banner.textForTest()}）",
                DegradationBanner.Kind.WRITE_RISK,
                banner.kindForTest(),
            )
        } finally {
            panel.clear()
        }
    }

    // ---------------- 3：foreach 值框「本轮未展开」提示 ----------------

    fun test未走到分支的foreach值框给出未展开提示() {
        val panel = panelWithDeleteByExample()
        try {
            val hints = panel.xmlForEachInertLabels
            assertEquals("只有可填的那一条集合行（criterion.value）挂提示", 1, hints.size)
            assertEquals("criterion.value", hints.single().first)
            assertTrue("默认走 singleValue ⇒ criterion.value 本轮未展开，提示应可见", hints.single().second.isVisible)
            assertEquals(
                MpSqlVisualizerBundle.message("panel.xml.foreach.inert"),
                hints.single().second.text,
            )

            // 切到 listValue 分支 ⇒ criterion.value 真被展开 ⇒ 提示消失（值这时才起作用）
            panel.enabledIds.remove("criterion.singleValue")
            panel.enabledIds.add("criterion.listValue")
            panel.renderSqlForTest()
            assertFalse(
                "分支走到后不得再提示「未展开」",
                panel.xmlForEachInertLabels.single().second.isVisible,
            )
        } finally {
            panel.clear()
        }
    }

    // ---------------- 判据本身（纯函数） ----------------

    fun test展开判据_按frch映射前缀() {
        assertTrue("有 __frch_listItem_0 ⇒ 展开过", forEachExpandedThisRound("listItem", listOf("__frch_listItem_0")))
        assertFalse("无该前缀 ⇒ 本轮没走到", forEachExpandedThisRound("listItem", listOf("criterion.value")))
        assertTrue("item 未知时保守判展开（不误报）", forEachExpandedThisRound(null, emptyList()))
        assertTrue("item 空白同样保守", forEachExpandedThisRound("   ", emptyList()))
        assertFalse("前缀必须整词（listItemX 不算）", forEachExpandedThisRound("listItem", listOf("__frch_listItemX_0")))
    }
}
