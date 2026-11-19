package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.ConditionView
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.psi.PsiFile
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-DegradedPreviewUx：降级横幅的原因分型 / 宽限期（P0+P1+P2），以及降级态重渲染联动（P3 / UI-022）。
 *
 * 降级态如何确定性构造：沿用 `SqlPreviewPanelPrewarmLateRecoverTest.degradePanel` 的配方——
 * 先等本面板预热出结果，再正常装配消费产物，然后 `releaseEditor()` 后二次装配：产物已被消费
 * （`prewarmedTempFile == prewarmed` 判假）→ 降级链。这条路线不依赖「本 JVM 首个面板预热超时」
 * 这类脆弱前置，且没有在途产物会来换装。
 *
 * UI-022 的两条用例是**先红后绿**的复现用例：降级态下改参数 / 勾选动态条件，编辑器文档必须随之变化。
 *
 * k2j 适配：master 侧模型构造器带默认参（Kotlin），Java 侧按最短重载位置实参展开；
 * `Condition.Simple`/`SqlValue.Literal` 为嵌套类静态构造。
 */
class SqlPreviewPanelDegradedUxTest : BasePlatformTestCase() {

    // ---------- 夹具 ----------

    private fun tableInfo() = TableInfo(
        "DeviceInfoEntity",
        "car_device_info",
        listOf(
            ColumnInfo("id", "id", true, false, "java.lang.Long", null, null, null),
            ColumnInfo("stationId", "station_id", false, false, "java.lang.Long", null, null, null),
        ),
        null, null, null, listOf(),
    )

    /** 普通文本路径（参数回填用例）。 */
    private fun paramResult() = PreviewResult(
        "SELECT id\nFROM car_device_info\nWHERE name = ?",
        "SELECT id FROM car_device_info WHERE name = ?",
        listOf("name"),
        null,
        WrapperType.LAMBDA_QUERY,
        "car_device_info",
        null,
        listOf("id", "name"),
        mapOf(),
        null,
        null,
        null,
        listOf(),
        null,
        null,
        mapOf(),
        listOf(),
        listOf(),
        null,
        listOf(),
    )

    /** 动态条件路径（勾选联动用例，与 UI-022 同一套 checkbox → enabledIds → renderSql）。 */
    private fun dynamicResult() = PreviewResult(
        "SELECT id, station_id\nFROM car_device_info",
        "SELECT id, station_id FROM car_device_info",
        emptyList(),
        null,
        WrapperType.LAMBDA_QUERY,
        "car_device_info",
        null,
        listOf("id", "station_id"),
        mapOf(),
        null,
        SqlPreviewModel(
            SelectStatement(
                tableInfo(),
                listOf(),
                null,
                listOf(),
                false,
                listOf(
                    Condition.Simple(
                        ColumnRef(null, "id", null, null, null, null),
                        "=", SqlValue.Literal("1", "1"),
                        ConditionToggle("c0", "true", true, "id = 1"),
                    ),
                    Condition.Simple(
                        ColumnRef(null, "station_id", null, null, null, null),
                        "=", SqlValue.Literal("2", "2"),
                        ConditionToggle("c1", "false", false, "station_id = 2"),
                    ),
                ),
                listOf(),
                null,
                listOf(),
                null,
                null,
                null,
                null,
                listOf(),
                null,
                null,
                listOf(),
            ),
            MySqlDialect.INSTANCE,
            true,
            listOf(
                ConditionView("c0", "id = 1", "true", true),
                ConditionView("c1", "station_id = 2", "false", false),
            ),
        ),
        null,
        listOf(),
        null,
        listOf(),
    )

    private fun awaitPrewarmProduct(panel: SqlPreviewPanel, allowRearm: Boolean = true): Pair<VirtualFile, PsiFile>? {
        var await = panel.prewarmAwaitForTest()
        assertNotNull("面板构造期应已发起预热", await)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !await!!.isDone()) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("预热应在预算内出结果（成功或失败）", await!!.isDone())
        val product = await.joinOrNull()
        if (product != null || !allowRearm) return product
        panel.retryPrewarmForTest()
        return awaitPrewarmProduct(panel, allowRearm = false)
    }

    /** 把面板推进稳定降级态（见类 KDoc），并确认横幅已按 PRODUCT_CONSUMED 立即挂出。
     *
     * T-FirstOpenDegradedLabel 二轮：生产路径在「产物已消费」时会**自动补一轮预热**（语义回到
     * 「在途」→ 宽限期静默）。本类要的是**稳定**降级态来验降级期行为，故夹具显式置位
     * `prewarmAutoRetried`，让这一轮走「已消费」的硬失败档——这是测试意图，不是绕过实现。
     */
    private fun degradePanel(panel: SqlPreviewPanel, result: PreviewResult) {
        awaitPrewarmProduct(panel)
        panel.prewarmAutoRetried = true
        SqlPreviewPanelResultViewKt.showResult(panel, result)
        UIUtil.dispatchAllInvocationEvents()
        panel.releaseEditorForTest()
        SqlPreviewPanelResultViewKt.showResult(panel, result)
        UIUtil.dispatchAllInvocationEvents()
        assertNull("前置：降级文档不应挂在项目内临时文件上", panel.editorForTest()?.document?.let {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it)
        })
        assertNotNull("前置：应登记降级文档身份", panel.degradedDocumentForTest())
        assertEquals(
            "前置：降级横幅应已挂起",
            DegradationBanner.Kind.DEGRADED,
            panel.degradationBannerForTest().kindForTest(),
        )
    }

    // ---------- P1：宽限期 ----------

    fun test超时降级_宽限期内不显示_到点显示() {
        val panel = SqlPreviewPanel(project)
        try {
            degradePanel(panel, paramResult())
            val banner = panel.degradationBannerForTest()

            panel.showDegradedBannerForTest(PreviewDegradationReason.TIMEOUT)
            assertFalse("超时是瞬时态：宽限期内不得显示横幅", banner.isVisible)
            assertTrue("宽限定时器应在途", panel.degradedGracePendingForTest())

            panel.fireDegradedGraceForTest()
            assertTrue("宽限期到点必须披露", banner.isVisible)
            assertEquals(
                MpSqlVisualizerBundle.message("panel.banner.degradedTimeout"),
                banner.textForTest(),
            )
            assertTrue("超时型横幅仍应给出重试动作", banner.retryVisibleForTest())
            assertFalse("到点后定时器应已停止", panel.degradedGracePendingForTest())
        } finally {
            panel.clear()
        }
    }

    fun test硬失败_立即显示原因与重试() {
        val panel = SqlPreviewPanel(project)
        try {
            degradePanel(panel, paramResult())
            val banner = panel.degradationBannerForTest()

            panel.showDegradedBannerForTest(PreviewDegradationReason.FILE_UNAVAILABLE)
            assertTrue("硬失败必须立即披露", banner.isVisible)
            assertFalse("硬失败不走宽限期", panel.degradedGracePendingForTest())
            assertEquals(
                MpSqlVisualizerBundle.message(
                    "panel.banner.degradedFailed",
                    MpSqlVisualizerBundle.message("panel.banner.degradedCause.file"),
                ),
                banner.textForTest(),
            )
            assertTrue("失败型横幅应给出重试动作", banner.retryVisibleForTest())
        } finally {
            panel.clear()
        }
    }

    fun test宽限定时器_面板清空即取消() {
        val panel = SqlPreviewPanel(project)
        try {
            degradePanel(panel, paramResult())
            panel.showDegradedBannerForTest(PreviewDegradationReason.TIMEOUT)
            assertTrue(panel.degradedGracePendingForTest())

            panel.clear()
            assertFalse("clear() 后宽限定时器必须停止（防迟到弹横幅）", panel.degradedGracePendingForTest())
        } finally {
            panel.clear()
        }
    }

    // ---------- P3：降级态重渲染（UI-022 复现） ----------

    fun test降级态改参数_SQL随之重渲染() {
        val panel = SqlPreviewPanel(project)
        try {
            degradePanel(panel, paramResult())
            val doc = panel.editorForTest()?.document
            assertNotNull("降级态应有编辑器文档", doc)
            assertFalse("前置：未填参数时 SQL 不含该值", doc!!.text.contains("bob"))

            panel.paramValues["name"] = "bob"
            panel.renderSqlForTest()

            val text = panel.editorForTest()?.document?.text ?: ""
            assertTrue("降级态改参数后 SQL 应随之重渲染（UI-022），实际: $text", text.contains("bob"))
        } finally {
            panel.clear()
        }
    }

    fun test降级态勾选动态条件_SQL追加条件() {
        val panel = SqlPreviewPanel(project)
        try {
            degradePanel(panel, dynamicResult())
            val initial = panel.editorForTest()?.document?.text ?: ""
            assertTrue("前置：默认勾选条件应出现在 SQL，实际: $initial", initial.contains("id = 1"))
            assertFalse("前置：未勾选条件不应出现，实际: $initial", initial.contains("station_id = 2"))

            val checkbox = panel.conditionCheckboxForTest(1)
            assertNotNull("降级态应仍构建动态条件行", checkbox)
            checkbox!!.doClick()
            UIUtil.dispatchAllInvocationEvents()

            val text = panel.editorForTest()?.document?.text ?: ""
            assertTrue("降级态勾选条件后 SQL 应追加条件（UI-022），实际: $text", text.contains("station_id = 2"))
        } finally {
            panel.clear()
        }
    }
}
