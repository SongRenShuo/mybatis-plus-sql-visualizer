package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志转 SQL 面板「渲染 / 布局 / 交互」UI 测试（F-44-6 续 · 用户要求「整个插件 UI 用例」）：
 *
 * 此前仅覆盖转换逻辑（LogToSqlPanelTest），编辑器 L&F 与格式切换的真实渲染未测——用户点名
 * 「编辑器 L&F / 格式切换未测」。本测试模拟真实面板打开：
 *  - 面板构建即出输入/结果编辑器（不崩），空态提示接线既有文案键（T-UN-14 迁编辑器原生 placeholder）；
 *  - 真实转换：MyBatis 日志（Preparing/Parameters）经解析 → 参数被替换进 SQL、默认格式化输出含换行；
 *  - 格式切换：关闭格式化 → 同一条 SQL 输出为原样单行（无换行），证明格式开关真实生效；
 *  - 布局：非空标签有正首选尺寸（编辑器在 headless 下 preferredSize 不可靠，跳过，仅断言标签层）。
 */
public class LogToSqlPanelUiTest extends BasePlatformTestCase {

    private static void waitForEdt() {
        UIUtil.dispatchAllInvocationEvents();
    }

    private static List<Component> allComponents(Component c) {
        List<Component> list = new ArrayList<>();
        list.add(c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                list.addAll(allComponents(child));
            }
        }
        return list;
    }

    @Test
    public void test面板构建_编辑器与空态提示可见() throws Exception {
        LogToSqlPanel panel = new LogToSqlPanel(getProject());
        try {
            waitForEdt();
            // T-UiNative T-UN-14：空态提示已迁编辑器原生 placeholder（空文档时由编辑器原生绘制），
            // 自摆 hintLabel 删除——断言两区 placeholder 均接线既有文案键
            assertEquals(
                "输入区 placeholder 应接线文案键",
                MpSqlVisualizerBundle.message("log.panel.hint.input"),
                panel.inputPlaceholderForTest());
            assertEquals(
                "结果区 placeholder 应接线文案键",
                MpSqlVisualizerBundle.message("log.panel.hint.result"),
                panel.resultPlaceholderForTest());
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test转换_格式化输出含换行与参数替换() throws Exception {
        LogToSqlPanel panel = new LogToSqlPanel(getProject());
        try {
            String input = "Preparing: SELECT * FROM user WHERE id = ?\nParameters: 1(Integer)";
            panel.setInputForTest(input);
            panel.convertForTest();
            String result = panel.resultTextForTest();

            assertTrue("结果应含 SELECT", result.contains("SELECT"));
            assertTrue("参数应被替换进 SQL（? → 1）", result.contains("1"));
            assertTrue("默认格式化输出应含换行（多行缩进）", result.contains("\n"));
            assertFalse("参数应已被全部替换，结果不应残留占位符 ?", result.contains("?"));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test格式切换_原样输出无换行() throws Exception {
        LogToSqlPanel panel = new LogToSqlPanel(getProject());
        try {
            String input = "Preparing: SELECT * FROM user WHERE id = ?\nParameters: 1(Integer)";
            panel.setInputForTest(input);
            panel.convertForTest(); // 默认 formatToggled = true
            String formatted = panel.resultTextForTest();
            assertTrue("格式化输出应含换行", formatted.contains("\n"));

            panel.toggleFormatForTest(); // formatToggled = false
            panel.convertForTest(); // 重新转换以应用新开关
            String raw = panel.resultTextForTest();
            assertTrue("原样输出仍应含 SQL 且参数已替换", raw.contains("SELECT") && raw.contains("1"));
            assertFalse("原样（不格式化）输出不应含换行", raw.contains("\n"));
        } finally {
            panel.dispose();
        }
    }

    @Test
    public void test布局_非空标签有正首选尺寸() throws Exception {
        LogToSqlPanel panel = new LogToSqlPanel(getProject());
        try {
            waitForEdt();
            // T-UiNative T-UN-14：空态灰字提示迁编辑器 placeholder 后，面板常态只有底部状态行
            // 是文字标签——先真实转换一次让状态行有文案，再断言无非空标签渲染塌缩
            panel.setInputForTest("Preparing: SELECT * FROM user WHERE id = ?\nParameters: 1(Integer)");
            panel.convertForTest();
            waitForEdt();
            List<JBLabel> nonEmptyLabels = new ArrayList<>();
            for (Component comp : allComponents(panel)) {
                if (comp instanceof JBLabel) {
                    JBLabel label = (JBLabel) comp;
                    if (!label.getText().isBlank()) {
                        nonEmptyLabels.add(label);
                    }
                }
            }
            assertTrue("面板应含非空标签（状态行）", !nonEmptyLabels.isEmpty());
            List<String> zeroTexts = new ArrayList<>();
            for (JBLabel label : nonEmptyLabels) {
                if (label.getPreferredSize().width <= 0 || label.getPreferredSize().height <= 0) {
                    zeroTexts.add(label.getText());
                }
            }
            assertEquals("非空标签应有正首选尺寸（无渲染塌缩）: " + zeroTexts, 0, zeroTexts.size());
        } finally {
            panel.dispose();
        }
    }

    /**
     * T-SqlDialectPrewarm：结果区必须挂**插件自研**词法器。
     *
     * 判别力放在 token 类型上：平台 SQL 词法器给出的关键字 token 不是 {@code MP_SQL_KEYWORD}，把高亮器改回
     * 平台工厂（{@code SyntaxHighlighterFactory.getSyntaxHighlighter(SQL, …)}）本断言即红——「类型是
     * LexerEditorHighlighter」这种弱断言两边都成立，不能当覆盖。
     */
    @Test
    public void test结果区挂自研SQL词法器() throws Exception {
        LogToSqlPanel panel = new LogToSqlPanel(getProject());
        try {
            panel.setResultForTest("SELECT id FROM user_info");
            waitForEdt();
            com.intellij.openapi.editor.highlighter.EditorHighlighter highlighter =
                ((com.intellij.openapi.editor.ex.EditorEx) panel.resultEditorForTest()).getHighlighter();
            assertTrue(
                "结果区 highlighter 应为 LexerEditorHighlighter",
                highlighter instanceof com.intellij.openapi.editor.ex.util.LexerEditorHighlighter);
            com.intellij.openapi.editor.highlighter.HighlighterIterator iterator =
                ((com.intellij.openapi.editor.ex.util.LexerEditorHighlighter) highlighter).createIterator(0);
            assertFalse("token 迭代不应为空", iterator.atEnd());
            assertEquals(
                "首个 token 应为自研词法器的关键字类型（改回平台 SQL 高亮器会变成平台 token）",
                "MP_SQL_KEYWORD",
                String.valueOf(iterator.getTokenType()));
        } finally {
            panel.dispose();
        }
    }
}
