package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.psi.WrapperLocator;
import cn.song.mybatisplus.visualizer.ui.gutter.XmlSqlGutterLineMarkerProvider;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SqlPreviewDialog 弹窗测试（T-M23.6）：底部按钮区（执行 SQL / 复制 SQL / 关闭）+
 * 面板「关闭 SQL 面板」回调链路注入（dispose）。
 */
public class SqlPreviewDialogTest extends BasePlatformTestCase {

    private PreviewResult previewResult() {
        return new PreviewResult(
                "SELECT id\nFROM tbl",
                "SELECT id FROM tbl",
                List.of(),
                null,
                WrapperType.LAMBDA_QUERY,
                "tbl",
                List.of("id"));
    }

    // ------------------------------------------------------------------
    // T-PreviewActionRow / T-LoadFilePlacement：底部动作集合
    // ------------------------------------------------------------------

    public void testConstructionTimeActionArrayHasNoNullSlots() {
        // UI-042 回归：createActions() 在 DialogWrapper.init() 里同步调用，而动作字段的声明序决定
        // 它们那一刻是否为 null——为 null 的槽位会被平台**静默过滤**（不抛错、不写日志），于是
        // 「构造没崩」≠「构造正确」，只断言「含某个按钮名」也抓不到「少了一个但目标名字还在」。
        //
        // **这个数改过两次，都不是被谁偷偷改的**：
        //  4 → 5：T-DialogPickFile 把「从文件加载…」加进通用预览弹窗；
        //  5 → 4：T-LoadFilePlacement 判定它是**输入源**能力（弹窗是全插件 21 处入口共用的展示层），
        //         挪到独立工作台的输入工具栏。弹窗只剩 执行 / 复制 / 复制并关闭 / 取消。
        SqlPreviewDialog dialog = new SqlPreviewDialog(getProject(), previewResult());
        try {
            Action[] captured = dialog.actionsAtConstructionForTest;
            assertNotNull("构造期应已捕获动作数组", captured);
            assertEquals("底部动作应为 执行/复制/复制并关闭/取消 四个", 4, captured.length);
            StringBuilder names = new StringBuilder();
            for (Action action : captured) {
                assertNotNull("构造期动作数组不得有空元素（字段声明序晚于 init 的典型症状）：" + names, action);
                Object name = action.getValue(javax.swing.Action.NAME);
                names.append(name != null ? name.toString() : "<null>").append(" / ");
            }
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose();
            SqlPreviewPanelSqlViewKt.clear(dialog.panelForTest());
        }
    }

    public void testDialogHasNoLoadFromFileAction() {
        // T-LoadFilePlacement A-1：「从文件加载…」已从通用弹窗移到独立工作台的输入工具栏——
        // 弹窗是 21 处入口共用的展示层（gutter 图标 / 日志 / 历史 / 语句入口），在里面换输入
        // 会静默改掉「这条 SQL 从哪来」。这里按旧文案两种语言字面量兜底断言，键已从 bundle 删除。
        java.util.Set<String> legacyLabels = java.util.Set.of("从文件加载…", "Load from File…");
        SqlPreviewDialog dialog = new SqlPreviewDialog(getProject(), previewResult());
        try {
            for (Action action : dialog.actionsForTest()) {
                Object name = action.getValue(javax.swing.Action.NAME);
                assertFalse("弹窗底部不应再有「从文件加载」入口，实际=" + name,
                        name != null && legacyLabels.contains(name.toString()));
            }
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose();
            SqlPreviewPanelSqlViewKt.clear(dialog.panelForTest());
        }
    }

    public void testClosePanelCallbackInjectedToPanel() throws Exception {
        // T-M23.6：面板 onClosePanelRequested 已被弹窗注入（复制/执行 SQL 提示点「关闭 SQL 面板」
        // → 回调 dispose 关闭预览弹窗）；面板持有与对话框一致的当前结果
        SqlPreviewDialog dialog = new SqlPreviewDialog(getProject(), previewResult());
        SqlPreviewPanel panel = dialog.panelForTest();
        assertNotNull("弹窗应注入关闭面板回调", panel);
        if (panel.onClosePanelRequested != null) {
            panel.onClosePanelRequested.run();
        }
        assertNotNull("dispose 调用后对话框仍可引用（回调链路不抛异常）", dialog);
        // 释放编辑器（面板生命周期约定，同 SqlPreviewPanelTest 的 clear 收尾）
        SqlPreviewPanelSqlViewKt.clear(dialog.panelForTest());
    }

    public void testRowOfContentFidelityAndTruncation() throws Exception {
        // T-M23.8：列表行纯函数；T-CrudSql 原生化改造：Row.of 纯文本行（截断 80 字符加省略号；
        // HTML 转义由渲染器 html.disable 承担，摘要/详情原样保留）
        SqlSelectionDialog.Row shortRow = SqlSelectionDialog.Row.of("L3 LAMBDA_QUERY  tbl", "service.getById(id);");
        assertEquals("摘要原样保留", "L3 LAMBDA_QUERY  tbl", shortRow.getSummary());
        assertEquals("详情原样保留", "service.getById(id);", shortRow.getDetail());
        String longLine = "x".repeat(100);
        SqlSelectionDialog.Row truncated = SqlSelectionDialog.Row.of("L1 BATCH", longLine);
        assertTrue("超长行应截断加省略号", truncated.getDetail().endsWith("…"));
        assertFalse("截断后不应保留完整长行", truncated.getDetail().contains("x".repeat(81)));
        SqlSelectionDialog.Row special = SqlSelectionDialog.Row.of("L1 BATCH", "w.eq(\"a<b&c>d\");");
        assertEquals("特殊字符原样保留（渲染层禁 HTML 解析）", "w.eq(\"a<b&c>d\");", special.getDetail());
    }

    public void testDoOkActionInvokesCallbackWithSelectedCandidate() throws Exception {
        // T-CrudSql 回归防护：OK（doOKAction）必须把选中候选交给 onSelect 回调——
        // 真机曾因回调内同步开 modeless 预览被模态上下文吞掉而表现"OK 无响应"
        List<String> candidates = List.of("A", "B");
        List<String> selected = new ArrayList<>();
        SqlSelectionDialog<String> dialog = new SqlSelectionDialog<>(
                getProject(), candidates,
                List.of(new SqlSelectionDialog.Row("PA", ""), new SqlSelectionDialog.Row("PB", "")),
                selected::add);
        dialog.listForTest().setSelectedIndex(1);
        dialog.doOKAction();
        assertEquals("doOKAction 应把选中候选交给回调", List.of("B"), selected);
    }

    public void testDoubleClickOnCandidateConfirmsSelection() throws Exception {
        // T-CrudSql 用户反馈：双击候选直接确认预览（对齐 IDEA 原生弹窗交互）
        List<String> candidates = List.of("A", "B");
        List<String> selected = new ArrayList<>();
        SqlSelectionDialog<String> dialog = new SqlSelectionDialog<>(
                getProject(), candidates,
                List.of(new SqlSelectionDialog.Row("PA", ""), new SqlSelectionDialog.Row("PB", "")),
                selected::add);
        javax.swing.JList<SqlSelectionDialog.Row> list = dialog.listForTest();
        assertTrue("列表应注册鼠标监听（双击确认）", list.getMouseListeners().length > 0);
        list.dispatchEvent(
                new java.awt.event.MouseEvent(
                        list, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                        10, 10, 2, false, java.awt.event.MouseEvent.BUTTON1));
        assertEquals("双击第一项应触发回调并选中该项", List.of("A"), selected);
        assertEquals("双击后选中索引应落在双击项上", 0, list.getSelectedIndex());
    }

    public void testDialogRendersPrecomputedTextsWithoutPsiResolve() throws Exception {
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
                """.stripTrailing());
        com.intellij.psi.PsiFile file = myFixture.getFile();
        List<PsiMethodCallExpression> candidates =
                WrapperLocator.sqlCandidatesInRange(file, 0, file.getTextLength());
        assertTrue("fixture 应含多个候选（触发多选弹窗分支）", candidates.size() >= 2);
        List<SqlSelectionDialog.Row> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            rows.add(SqlSelectionDialog.Row.of("PRE-" + i, ""));
        }
        SqlSelectionDialog<PsiMethodCallExpression> dialog =
                new SqlSelectionDialog<>(getProject(), candidates, rows, t -> {});
        javax.swing.ListModel<SqlSelectionDialog.Row> model = dialog.listForTest().getModel();
        assertEquals("列表项数应与预计算行数一致", rows.size(), model.getSize());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("列表第 " + i + " 项应原样渲染预计算行（不重新解析 PSI）", rows.get(i), model.getElementAt(i));
        }
    }

    public void testDialogRendersXmlTagCandidates() throws Exception {
        // T-M25-01 泛化回归补测（T-M29.6）：XmlTag 语句候选同样走预计算文本渲染——
        // 弹窗构造不触碰 PSI，XML 语句候选与 Wrapper 候选行为一致（选择区/全文件候选共用）
        myFixture.configureByText(
                "OrderMapper.xml",
                """
                <mapper namespace="com.example.OrderMapper">
                  <select id="selectList">SELECT * FROM t</select>
                  <select id="selectByName">SELECT * FROM t WHERE name = #{name}</select>
                </mapper>
                """.stripTrailing());
        XmlFile file = (XmlFile) myFixture.getFile();
        List<XmlTag> candidates =
                XmlSqlGutterLineMarkerProvider.statementsInRange(file, 0, file.getTextLength());
        assertTrue("fixture 应含多条语句（触发多选弹窗分支）", candidates.size() >= 2);
        List<SqlSelectionDialog.Row> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            rows.add(SqlSelectionDialog.Row.of("PRE-" + i, ""));
        }
        SqlSelectionDialog<XmlTag> dialog =
                new SqlSelectionDialog<>(getProject(), candidates, rows, t -> {});
        javax.swing.ListModel<SqlSelectionDialog.Row> model = dialog.listForTest().getModel();
        assertEquals("列表项数应与预计算行数一致", rows.size(), model.getSize());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("列表第 " + i + " 项应原样渲染预计算行（不重新解析 PSI）", rows.get(i), model.getElementAt(i));
        }
    }

    // ------------------------------------------------------------------
    // T-PreviewActionRow D-7：弹窗内的缺失片段补齐入口
    // ------------------------------------------------------------------

    private static final String MISSING_FRAGMENT_XML =
            "<select id=\"selectByPrimaryKey\">\n"
            + "  SELECT <include refid=\"Base_Column_List\"/> FROM CUST_CHANNEL WHERE ID = #{id}\n"
            + "</select>";

    /** master 具名实参 `missingFragmentIds = listOf(…)` → k2j 派生 `withMissingFragmentIds`。 */
    private PreviewResult resultWithMissingFragment() {
        return new PreviewResult(
                "SELECT\n  /* TODO: missing <sql id=\"Base_Column_List\"> */\nFROM CUST_CHANNEL\nWHERE ID = ?",
                "SELECT * FROM CUST_CHANNEL WHERE ID = ?",
                List.of("id"),
                null,
                null,
                "CUST_CHANNEL",
                null,
                List.of(),
                java.util.Map.of(),
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                java.util.Map.of(),
                null,
                null,
                null,
                null).withMissingFragmentIds(List.of("Base_Column_List"));
    }

    public void test弹窗拿到原文且缺片段时显示补齐入口() {
        // 工作台「在弹框中打开」会随窗带上原文（sourceText）——弹窗内才有东西可重解析补齐
        SqlPreviewDialog dialog = new SqlPreviewDialog(getProject(), resultWithMissingFragment(),
                null, null, false, MISSING_FRAGMENT_XML);
        try {
            assertTrue("有原文 + 有缺失时应在结果区出现补齐入口",
                    dialog.panelForTest().missingFragmentVisibleForTest());
            assertTrue("提示文案应带 refid",
                    dialog.panelForTest().missingFragmentTextForTest().contains("Base_Column_List"));
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose();
            SqlPreviewPanelSqlViewKt.clear(dialog.panelForTest());
        }
    }

    public void test弹窗没有原文时不显示补齐入口() {
        // gutter / 日志 / 历史 / 语句预览都拿不到原文 → 不挂入口（否则是点了没反应的死链接）
        SqlPreviewDialog dialog = new SqlPreviewDialog(getProject(), resultWithMissingFragment());
        try {
            assertFalse("无原文时不应出现补齐入口",
                    dialog.panelForTest().missingFragmentVisibleForTest());
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose();
            SqlPreviewPanelSqlViewKt.clear(dialog.panelForTest());
        }
    }

    public void test补齐后弹窗内就地重解析且TODO占位消失() {
        SqlPreviewDialog dialog = new SqlPreviewDialog(getProject(), resultWithMissingFragment(),
                null, null, false, MISSING_FRAGMENT_XML);
        try {
            SqlPreviewPanel panel = dialog.panelForTest();
            // 注入内容（headless 不能弹模态补齐框），其余链路全走真实实现
            dialog.missingFragmentPrompt = refid -> "ID, CHANNEL_NAME";
            dialog.fillMissingFragmentForTest("Base_Column_List");

            // 重解析在池线程（DOM 解析不上 EDT），代次守卫回 EDT——泵队列等它落定
            long deadline = System.currentTimeMillis() + 10_000L;
            PreviewResult current = panel.current;
            while (System.currentTimeMillis() < deadline) {
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
                current = panel.current;
                if (current != null && !current.getSql().contains("TODO: missing")) break;
                Thread.sleep(10);
            }

            assertNotNull("补齐后应有新的预览载荷", current);
            assertTrue("补上的片段应展开进 SQL，实际：" + current.getSql(),
                    current.getSql().contains("ID, CHANNEL_NAME"));
            assertFalse("TODO 占位应消失，实际：" + current.getSql(),
                    current.getSql().contains("TODO: missing"));
            assertTrue("补齐后不再有缺失片段", current.getMissingFragmentIds().isEmpty());
            assertFalse("补齐后提示行应收起", panel.missingFragmentVisibleForTest());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("补齐重解析等待被中断");
        } finally {
            if (!dialog.isDisposed()) dialog.doDispose();
            SqlPreviewPanelSqlViewKt.clear(dialog.panelForTest());
        }
    }
}
