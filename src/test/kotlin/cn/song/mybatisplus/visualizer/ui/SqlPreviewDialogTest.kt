package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * SqlPreviewDialog 弹窗测试（T-M23.6）：底部按钮区（执行 SQL / 复制 SQL / 关闭）+
 * 面板「关闭 SQL 面板」回调链路注入（dispose）。
 */
class SqlPreviewDialogTest : BasePlatformTestCase() {

    private fun previewResult() = PreviewResult(
        sql = "SELECT id\nFROM tbl",
        compactSql = "SELECT id FROM tbl",
        unresolvedParams = emptyList(),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "tbl",
        columns = listOf("id"),
    )

    fun testCopyAndCloseActionCopiesSqlAndDisposesDialog() {
        // T-DmlImpactProminence：底部新增蓝色「复制 SQL 并关闭」——复制原文并关闭弹窗。
        val clipboard = cn.song.mybatisplus.visualizer.testutil.installFakeClipboard(testRootDisposable)
        val dialog = SqlPreviewDialog(project, previewResult())
        val label = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.copySqlAndClose")
        val action = dialog.actionsForTest().first { it.getValue(javax.swing.Action.NAME) == label }
        try {
            action.actionPerformed(java.awt.event.ActionEvent(dialog, 0, ""))
            val copied = clipboard.getContents()
                ?.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            assertTrue("应复制 SQL 原文：$copied", copied?.contains("SELECT") == true)
            assertTrue("应复制 SQL 原文：$copied", copied?.contains("FROM tbl") == true)
            assertTrue("复制后应关闭弹窗", dialog.isDisposed())
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose()
            dialog.panelForTest()?.clear()
        }
    }

    fun testCopyAndCloseActionDoesNotOfferClosePanelAction() {
        // UI-044：弹窗随即关闭，复制成功通知里不该再挂「关闭 SQL 面板」（用户真机截图即此形态：
        // 面板已关，通知还挂着这个链接）。对照组：单纯「复制 SQL」仍应挂上。
        val clipboard = cn.song.mybatisplus.visualizer.testutil.installFakeClipboard(testRootDisposable)
        val dialog = SqlPreviewDialog(project, previewResult())
        val panel = dialog.panelForTest()!!
        val actions = mutableListOf<String>()
        panel.copyNotificationActionsForTest = actions
        try {
            val label = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.copySqlAndClose")
            val action = dialog.actionsForTest().first { it.getValue(javax.swing.Action.NAME) == label }
            action.actionPerformed(java.awt.event.ActionEvent(dialog, 0, ""))
            val closeLabel = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("dialog.closeSqlPanel")
            assertFalse("复制并关闭时通知不应带「关闭 SQL 面板」，实际=$actions", actions.contains(closeLabel))
            assertTrue("复制仍应成功", dialog.isDisposed())
        } finally {
            panel.copyNotificationActionsForTest = null
            if (!dialog.isDisposed()) dialog.doDispose()
            panel.clear()
        }
    }

    fun testConstructionTimeActionArrayHasNoNullSlots() {
        // UI-042 补强：平台 `DialogWrapper.createSouthPanel()` 字节码里是
        // `createActions()` → `ContainerUtil.filter(..., Conditions.notNull())`——null 元素在进按钮区
        // 之前就被静默丢掉，不抛错、不写日志。所以「构造没崩」≠「构造正确」，而只断言
        // 「数组里含某个按钮名」也抓不到「少了一个但目标名字还在」。
        // 元素个数 + 无 null 槽是唯一能看见那次静默过滤的读数。
        val dialog = SqlPreviewDialog(project, previewResult())
        try {
            val captured = dialog.actionsAtConstructionForTest
            assertNotNull("构造期应已捕获动作数组", captured)
            assertEquals("底部动作应为 执行/复制/复制并关闭/取消 四个", 4, captured!!.size)
            assertTrue(
                "构造期动作数组不得有空元素（字段声明序晚于 init 的典型症状）：" +
                    captured.joinToString { it.getValue(javax.swing.Action.NAME)?.toString() ?: "<null>" },
                captured.none { it == null },
            )
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose()
            dialog.panelForTest()?.clear()
        }
    }

    fun testNonPrimaryButtonsKeepPlatformDefaultStyle() {
        // UI-043 补强：只有「复制 SQL 并关闭」该是主行动样式。防「修过头」——把
        // applyPrimaryButtonStyle 误用到全部动作，会让每个按钮都变成蓝底。
        val dialog = SqlPreviewDialog(project, previewResult())
        try {
            val primaryLabel = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.copySqlAndClose")
            dialog.actionsForTest().forEach { action ->
                val label = action.getValue(javax.swing.Action.NAME)?.toString()
                val button = dialog.createJButtonForActionForTest(action)
                val styled = button.getClientProperty(
                    com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY,
                ) == java.lang.Boolean.TRUE
                if (label == primaryLabel) {
                    assertTrue("主行动「$label」应打 styleDefault", styled)
                } else {
                    assertFalse("非主行动「$label」不应被染成主按钮样式", styled)
                }
            }
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose()
            dialog.panelForTest()?.clear()
        }
    }

    fun testCopyAndCloseActionIsPresentAtConstructionTime() {
        // 构造期字段序回归（UI-042）：`init()` 会同步调用 createActions()，若 copyAndCloseAction
        // 声明在 init 之后则此刻为 null——动作数组出现空位，按钮不渲染且不报错（用户侧「按钮不见了」）。
        // 断言必须落在构造期真实产出的那批动作上：事后重调 createActions() 字段已赋值，只能证明
        // 数组写得对、证不了按钮区（早期版本就是这样假绿的）。
        val dialog = SqlPreviewDialog(project, previewResult())
        try {
            val label = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.copySqlAndClose")
            val captured = dialog.actionsAtConstructionForTest
            assertNotNull("构造期应已捕获动作数组", captured)
            val names = captured!!.map { it.getValue(javax.swing.Action.NAME) }
            assertTrue("构造期动作应含「$label」，实际=$names", names.contains(label))
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose()
            dialog.panelForTest()?.clear()
        }
    }

    fun testPrimaryButtonUsesPlatformStyleInsteadOfPaintedBackground() {
        // UI-043：主行动按钮必须走平台原生绘制（client property `JButton.styleDefault`）。
        // 手工涂底色的形态（isOpaque=true + background）会让圆角按钮外面多出一圈方形蓝边——
        // DarculaButtonPainter 的边框是内缩 4px 圆角，底色按整矩形铺满就溢出到圆角之外。
        val dialog = SqlPreviewDialog(project, previewResult())
        try {
            val label = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.copySqlAndClose")
            val action = dialog.actionsForTest().first { it.getValue(javax.swing.Action.NAME) == label }
            val button = dialog.createJButtonForActionForTest(action)
            assertTrue(
                "主行动按钮应打平台 styleDefault 标记（否则 LAF 不会画原生蓝色圆角）",
                button.getClientProperty(
                    com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY,
                ) == java.lang.Boolean.TRUE,
            )
            assertTrue(
                "主行动按钮不得靠手工铺底色（会在圆角外溢出成方角蓝边）",
                !button.isOpaque,
            )
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose()
            dialog.panelForTest()?.clear()
        }
    }

    /**
     * UX Community 执行入口预判置灰（新功能小券批 券3）：测试沙盒无 Database 插件，
     * 「执行 SQL」按钮必须在**构造期**就灰置并带 tooltip，而不是点了才报错。
     *
     * 反向验证：摘掉 createActions 里 execute 动作 init 块的 `isEnabled = false` 后本用例必红。
     */
    fun testCommunity下执行入口预判置灰并带tooltip() {
        val dialog = SqlPreviewDialog(project, previewResult())
        try {
            assertFalse(
                "本用例前提=测试沙盒无 Database 插件（Community 形态）",
                cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable(),
            )
            val label = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("panel.executeSql")
            val action = dialog.actionsForTest().first { it.getValue(javax.swing.Action.NAME) == label }
            assertFalse("Community 下执行动作应预判置灰", action.isEnabled)
            assertEquals(
                "置灰必须带说明 tooltip",
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("execute.unavailable.tooltip"),
                action.getValue(javax.swing.Action.SHORT_DESCRIPTION),
            )
            val button = dialog.createJButtonForActionForTest(action)
            assertFalse("按钮必须同步灰置", button.isEnabled)
            assertEquals(
                "按钮 tooltip 应与动作说明一致",
                cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("execute.unavailable.tooltip"),
                button.toolTipText,
            )
            // 程序化触发也必须被守卫拦下（灰置只挡 UI，不挡代码路径）
            action.actionPerformed(java.awt.event.ActionEvent(dialog, 0, ""))
            assertFalse("置灰动作被程序化触发不得关闭弹窗", dialog.isDisposed())
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose()
            dialog.panelForTest()?.clear()
        }
    }

    fun testClosePanelCallbackInjectedToPanel() {        // T-M23.6：面板 onClosePanelRequested 已被弹窗注入（复制/执行 SQL 提示点「关闭 SQL 面板」
        // → 回调 dispose 关闭预览弹窗）；面板持有与对话框一致的当前结果
        val dialog = SqlPreviewDialog(project, previewResult())
        assertNotNull("弹窗应注入关闭面板回调", dialog.panelForTest())
        dialog.panelForTest()!!.onClosePanelRequested?.invoke()
        assertNotNull("dispose 调用后对话框仍可引用（回调链路不抛异常）", dialog)
        // 释放编辑器（面板生命周期约定，同 SqlPreviewPanelTest 的 clear 收尾）
        dialog.panelForTest()!!.clear()
    }

    fun testRowOfContentFidelityAndTruncation() {
        // T-M23.8：列表行纯函数；T-CrudSql 原生化改造：Row.of 纯文本行（截断 80 字符加省略号；
        // HTML 转义由渲染器 html.disable 承担，摘要/详情原样保留）
        val short = SqlSelectionDialog.Row.of("L3 LAMBDA_QUERY  tbl", "service.getById(id);")
        assertEquals("摘要原样保留", "L3 LAMBDA_QUERY  tbl", short.summary)
        assertEquals("详情原样保留", "service.getById(id);", short.detail)
        val longLine = "x".repeat(100)
        val truncated = SqlSelectionDialog.Row.of("L1 BATCH", longLine)
        assertTrue("超长行应截断加省略号", truncated.detail.endsWith("…"))
        assertFalse("截断后不应保留完整长行", truncated.detail.contains("x".repeat(81)))
        val special = SqlSelectionDialog.Row.of("L1 BATCH", "w.eq(\"a<b&c>d\");")
        assertEquals("特殊字符原样保留（渲染层禁 HTML 解析）", "w.eq(\"a<b&c>d\");", special.detail)
    }

    fun testDoOkActionInvokesCallbackWithSelectedCandidate() {
        // T-CrudSql 回归防护：OK（doOKAction）必须把选中候选交给 onSelect 回调——
        // 真机曾因回调内同步开 modeless 预览被模态上下文吞掉而表现"OK 无响应"
        val candidates = listOf("A", "B")
        val selected = mutableListOf<String>()
        val dialog = SqlSelectionDialog(project, candidates, listOf(SqlSelectionDialog.Row("PA", ""), SqlSelectionDialog.Row("PB", ""))) { selected += it }
        dialog.listForTest().selectedIndex = 1
        dialog.doOKAction()
        assertEquals("doOKAction 应把选中候选交给回调", listOf("B"), selected)
    }

    fun testDoubleClickOnCandidateConfirmsSelection() {
        // T-CrudSql 用户反馈：双击候选直接确认预览（对齐 IDEA 原生弹窗交互）
        val candidates = listOf("A", "B")
        val selected = mutableListOf<String>()
        val dialog = SqlSelectionDialog(project, candidates, listOf(SqlSelectionDialog.Row("PA", ""), SqlSelectionDialog.Row("PB", ""))) { selected += it }
        val list = dialog.listForTest()
        assertTrue("列表应注册鼠标监听（双击确认）", list.mouseListeners.isNotEmpty())
        list.dispatchEvent(
            java.awt.event.MouseEvent(
                list, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                10, 10, 2, false, java.awt.event.MouseEvent.BUTTON1,
            ),
        )
        assertEquals("双击第一项应触发回调并选中该项", listOf("A"), selected)
        assertEquals("双击后选中索引应落在双击项上", 0, list.selectedIndex)
    }

    fun testDialogRendersPrecomputedTextsWithoutPsiResolve() {
        // T-M23.8 增量修复（EDT 慢操作回归防护）：弹窗构造只渲染调用方后台预计算的显示文本——
        // 若构造内仍从 PSI 重新计算摘要（旧实现，EDT 上会被 2025.3+ SlowOperations 守卫禁止），
        // 传入的预计算文本会被覆盖，逐项断言即失败
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void m(OrderService service, Order order) {
                    service.getById(order.getId());
                    service.getById(1001L);
                }

                static class Order {
                    private Long id;
                }

                static class OrderService {
                    public void getById(Object id) {}
                }
            }
            """.trimIndent(),
        )
        val file = myFixture.file
        val candidates = WrapperLocator.sqlCandidatesInRange(file, 0, file.textLength)
        assertTrue("fixture 应含多个候选（触发多选弹窗分支）", candidates.size >= 2)
        val rows = candidates.mapIndexed { i, _ -> SqlSelectionDialog.Row.of("PRE-$i", "") }
        val dialog = SqlSelectionDialog(project, candidates, rows) {}
        val model = dialog.listForTest().model
        assertEquals("列表项数应与预计算行数一致", rows.size, model.size)
        for (i in rows.indices) {
            assertEquals("列表第 $i 项应原样渲染预计算行（不重新解析 PSI）", rows[i], model.getElementAt(i))
        }
    }

    fun testDialogRendersXmlTagCandidates() {
        // T-M25-01 泛化回归补测（T-M29.6）：XmlTag 语句候选同样走预计算文本渲染——
        // 弹窗构造不触碰 PSI，XML 语句候选与 Wrapper 候选行为一致（选择区/全文件候选共用）
        myFixture.configureByText(
            "OrderMapper.xml",
            """
            <mapper namespace="com.example.OrderMapper">
              <select id="selectList">SELECT * FROM t</select>
              <select id="selectByName">SELECT * FROM t WHERE name = #{name}</select>
            </mapper>
            """.trimIndent(),
        )
        val file = myFixture.file as XmlFile
        val candidates = XmlSqlGutterLineMarkerProvider.statementsInRange(file, 0, file.textLength)
        assertTrue("fixture 应含多条语句（触发多选弹窗分支）", candidates.size >= 2)
        val rows = candidates.mapIndexed { i, _ -> SqlSelectionDialog.Row.of("PRE-$i", "") }
        val dialog = SqlSelectionDialog(project, candidates, rows) {}
        val model = dialog.listForTest().model
        assertEquals("列表项数应与预计算行数一致", rows.size, model.size)
        for (i in rows.indices) {
            assertEquals("列表第 $i 项应原样渲染预计算行（不重新解析 PSI）", rows[i], model.getElementAt(i))
        }
    }
}
