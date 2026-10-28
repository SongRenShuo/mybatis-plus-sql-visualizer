package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.DisplayMasker
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ide.CopyPasteManager
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.psi.TokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton

/** 底部 SQL 预览区复用的高亮、可编辑文本和复制动作回归测试。 */
class SqlClickPreviewTest : BasePlatformTestCase() {

    private fun entry(sql: String) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 1L,
    )

    private fun releaseEditor(editor: Editor) {
        runCatching { EditorFactory.getInstance().releaseEditor(editor) }
    }

    private fun findButtons(root: java.awt.Container): List<JButton> =
        UIUtil.uiTraverser(root)
            .preOrderDfsTraversal()
            .filterIsInstance<JButton>()
            .toList()

    // ---------- 弹窗构建：SQL 高亮编辑器 + 可编辑 + 复制/关闭按钮 ----------

    fun test预览内容为可编辑SQL编辑器_文本等于还原SQL_带复制关闭按钮() {
        val sql = "SELECT * FROM user WHERE id = 1;"
        val content = SqlClickPreview.buildContent(
            project,
            sql,
            copyText = {},
            close = {},
        )
        try {
            assertEquals("编辑器文档应等于传入 SQL", sql, content.editor.document.text)
            assertFalse("编辑器应可编辑（非 viewer）", content.editor.isViewer)
            assertTrue("长 SQL 应软换行免横向滚动", content.editor.settings.isUseSoftWraps)

            val buttons = findButtons(content.component)
            val tooltips = buttons.map { it.toolTipText }.toSet()
            assertTrue("应含复制按钮（tooltip = i18n 文案）", tooltips.contains(MpSqlVisualizerBundle.message("log.preview.copy")))
            assertTrue("应含关闭按钮（tooltip = i18n 文案）", tooltips.contains(MpSqlVisualizerBundle.message("log.preview.close")))
        } finally {
            releaseEditor(content.editor)
        }
    }

    /** 四轮①：编辑器高亮与列表条目同源——同一 SqlLexer 的 token 覆盖全文，配色键共用同一映射。 */
    fun test编辑器高亮与列表片段同源同色() {
        val sql = "SELECT * FROM user WHERE id = 1;"
        val content = SqlClickPreview.buildContent(
            project,
            sql,
            copyText = {},
            close = {},
        )
        try {
            val highlighter = content.editor.highlighter
            assertTrue(
                "预览编辑器应挂自建词法高亮器（与列表同色链路）",
                highlighter is LexerEditorHighlighter,
            )
            val editorTokens = buildList {
                val iterator = highlighter.createIterator(0)
                while (!iterator.atEnd()) {
                    add(iterator.tokenType to iterator.start..iterator.end)
                    iterator.advance()
                }
            }
            assertTrue("编辑器应产出非平凡 token 序列", editorTokens.isNotEmpty())
            assertEquals("编辑器 token 应从文首覆盖", 0, editorTokens.first().second.first)
            assertEquals("编辑器 token 应覆盖到文末", sql.length, editorTokens.last().second.last)
            // 非空白 token 的配色键走 ATTR_BY_TYPE（与列表片段通道共用唯一映射 → 同色）
            val nonWhitespace = editorTokens.map { it.first }.filter { it != TokenType.WHITE_SPACE }
            assertTrue("应存在非空白 token", nonWhitespace.isNotEmpty())
            nonWhitespace.forEach { type ->
                assertNotNull("非空白 token 应有配色键（$type）", SqlListSyntaxHighlighter.ATTR_BY_TYPE[type])
            }
            // 列表片段通道（同一 SqlLexer）拼回文本与原文一致
            assertEquals(
                "列表片段通道文本应守恒",
                sql,
                SqlListFragmentRenderer.renderFragments(sql).joinToString("") { it.text },
            )
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test复制按钮回调携带当前文本_关闭按钮回调触发() {
        var copied: String? = null
        var closed = false
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = { closed = true },
        )
        try {
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
                .doClick()
            assertEquals("复制按钮应回调当前文本", "SELECT 1;", copied)
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.close") }
                .doClick()
            assertTrue("关闭按钮应触发关闭回调", closed)
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test复制按钮复制用户编辑后的内容() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            // 模拟用户临时改字段值（文档写入须 write action）
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.document.setText("SELECT edited_price FROM user;")
            }
            findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
                .doClick()
            assertEquals("复制应取编辑后的文本", "SELECT edited_price FROM user;", copied)
        } finally {
            releaseEditor(content.editor)
        }
    }

    /** 六轮：复制按钮点击后图标即时换成对号（复制成功反馈），无 NoneIcon 或其他非对号图标。 */
    fun test复制按钮点击后图标换对号反馈() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            val copyButton = findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
            val originalIcon = copyButton.icon
            copyButton.doClick()
            assertEquals("复制应回调当前文本", "SELECT 1;", copied)
            assertEquals(
                "复制后按钮图标应立即换成对号（成功反馈）",
                AllIcons.Actions.Checked,
                copyButton.icon,
            )
            assertNotEquals(
                "对号不应与初始复制图标相同（否则无反馈变化）",
                originalIcon,
                copyButton.icon,
            )
        } finally {
            releaseEditor(content.editor)
        }
    }

    // ---------- 复制行为与多选修饰键 ----------

    /** 模拟快捷键分派：直调当前注册的 copy handler（keymap Ctrl+C / Ctrl+Insert 绑定同一 action 入口）。 */
    private fun dispatchEditorCopy(editor: Editor) {
        // 243 action 管线拒绝裸匿名 DataContext（Utils.reportUnexpectedDataContextKind），
        // 上下文必须是 DataManager/CustomizedDataContext/SimpleDataContext 之一——用 SimpleDataContext 空上下文
        val context = SimpleDataContext.getSimpleContext(emptyMap<String, Any>(), null)
        EditorActionManager.getInstance()
            .getActionHandler(IdeActions.ACTION_EDITOR_COPY)
            .execute(editor, null, context)
    }

    fun test修饰键判定纯函数只认CtrlShift() {
        assertTrue(isMultiSelectIntent(MouseEvent.CTRL_DOWN_MASK))
        assertTrue(isMultiSelectIntent(MouseEvent.SHIFT_DOWN_MASK))
        // 真实转发携带 BUTTON1（左键正按下）：Ctrl+左键组合必须识别，纯左键/Alt 不得误报
        assertTrue(isMultiSelectIntent(MouseEvent.CTRL_DOWN_MASK or MouseEvent.BUTTON1_DOWN_MASK))
        assertFalse(isMultiSelectIntent(0))
        assertFalse(isMultiSelectIntent(MouseEvent.BUTTON1_DOWN_MASK))
        assertFalse(isMultiSelectIntent(MouseEvent.ALT_DOWN_MASK))
    }

    fun test编辑器CtrlC无选中复制全文() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            // 复制前先抓按钮引用：flashCopied 会把 tooltip 短暂换成「已复制」，复制后按 tooltip 查找必落空
            val copyButton = findButtons(content.component)
                .first { it.toolTipText == MpSqlVisualizerBundle.message("log.preview.copy") }
            dispatchEditorCopy(content.editor)
            assertEquals("浮层编辑器 Ctrl+C 无选中应复制全文（与头部按钮同源）", "SELECT 1;", copied)
            assertEquals(
                "Ctrl+C 复制后按钮图标应换对号（与按钮复制同反馈）",
                AllIcons.Actions.Checked,
                copyButton.icon,
            )
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test编辑器CtrlC复制用户编辑后的全文() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.document.setText("SELECT edited_price FROM user;")
            }
            dispatchEditorCopy(content.editor)
            assertEquals("Ctrl+C 应取编辑后文本（与按钮复制同源）", "SELECT edited_price FROM user;", copied)
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test编辑器CtrlC有选中时交原生复制不全文覆盖() {
        var copied: String? = null
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT * FROM user WHERE id = 1;",
            copyText = { copied = it },
            close = {},
        )
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.selectionModel.setSelection(0, 6) // 选中 "SELECT"
            }
            dispatchEditorCopy(content.editor)
            assertNull("有选中时不应走全文复制回调（交编辑器原生复制选中片段）", copied)
        } finally {
            releaseEditor(content.editor)
        }
    }

    /**
     * 真实复制链路的**平台调用点**（T-ClickPreviewCopyRestore 二轮）：keymap Ctrl+C 绑 `$Copy`
     * （IdeActions.ACTION_COPY），该 action 从 DataContext 取 `COPY_PROVIDER` 后调
     * `provider.performCopy(ctx)` —— 编辑器的 provider 即 `EditorEx.copyProvider`
     * （243 源码核验：`EditorImpl.MyEditable.performCopy` → `executeAction(EditorCopy)`）。
     * 本用例走这一调用点、并注入生产的真实复制实现（`UiTextClipboard.copy`），
     * 与「直调 handler、注入空回调」的用例相比更贴近用户按键路径。
     *
     * headless 边界：`DataManager.getDataContext(component)` 在无 UI 挂载时取不到 EDITOR/
     * COPY_PROVIDER（实测均为 null），`$Copy.actionPerformed` 那条外部路径无法在 headless 复现
     * ——真实按键行为由 GUI 走查覆盖（计划 §三 S4）。
     */
    fun test编辑器provider直调_无选中_剪贴板得全文() {
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { UiTextClipboard.copy(it) }, // 生产实现：弹窗路径注入的就是它
            close = {},
        )
        try {
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            val context = DataManager.getInstance().getDataContext(content.editor.component)
            val provider = (content.editor as EditorEx).copyProvider
            assertTrue("编辑器应提供复制能力", provider.isCopyEnabled(context))
            provider.performCopy(context)
            val clip: String? =
                CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
            assertEquals("无选中时经编辑器 provider 复制应得整条 SQL", "SELECT 1;", clip)
        } finally {
            releaseEditor(content.editor)
        }
    }

    /**
     * 二轮修复（真机反馈后）：**Swing 绑定层**的 Ctrl+C —— 编辑器组件（JTextComponent）自带
     * 的 copy 对无选中是 no-op，而平台 `$Copy` 在编辑器里因取不到 COPY_PROVIDER 不可用，
     * 事件最终落回这一层。断言的正是真实按键解析的落点（InputMap → ActionMap）。
     */
    fun test编辑器CtrlC绑定覆盖默认copy_无选中复制全文() {
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT 1;",
            copyText = { UiTextClipboard.copy(it) },
            close = {},
        )
        try {
            val comp = content.editor.component
            val actionKey = comp.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control C")) as String
            assertNotEquals("Ctrl+C 应覆盖 JTextComponent 默认 copy 绑定", "copy", actionKey)
            assertEquals("Ctrl+Insert 应与 Ctrl+C 同绑",
                actionKey,
                comp.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control INSERT")))
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            comp.actionMap.get(actionKey)
                .actionPerformed(java.awt.event.ActionEvent(comp, java.awt.event.ActionEvent.ACTION_PERFORMED, actionKey))
            val clip: String? = CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
            assertEquals("无选中按 Ctrl+C 应复制全文", "SELECT 1;", clip)
        } finally {
            releaseEditor(content.editor)
        }
    }

    fun test编辑器CtrlC有选中只复制选区() {
        val content = SqlClickPreview.buildContent(
            project,
            "SELECT * FROM user WHERE id = 1;",
            copyText = { UiTextClipboard.copy(it) },
            close = {},
        )
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                content.editor.selectionModel.setSelection(0, 6) // "SELECT"
            }
            val comp = content.editor.component
            val actionKey = comp.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control C")) as String
            CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
            comp.actionMap.get(actionKey)
                .actionPerformed(java.awt.event.ActionEvent(comp, java.awt.event.ActionEvent.ACTION_PERFORMED, actionKey))
            val clip: String? = CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
            assertEquals("有选中按 Ctrl+C 应只复制选区片段", "SELECT", clip)
        } finally {
            releaseEditor(content.editor)
        }
    }

    /**
     * 列表侧契约钉子：Ctrl+C 绑定**来自平台 LAF**（`List.focusInputMap`），触发后走 JBList 既有
     * copy 语义（ActionMap["copy"] → `doCopyToClipboardAction` → 选中条目逐条 `itemToText` 换行拼接）。
     * 钉住「列表 Ctrl+C 复制纯 SQL」这条用户依赖的既有行为，**不是**二轮修复新增（二轮曾在此加绑定，
     * 反向验证发现摘除后绑定仍在＝冗余，已回退）。
     */
    fun test列表CtrlC绑定在场_多选批量复制纯SQL() {
        val model = DefaultListModel<LogSqlEntry>()
        model.addElement(entry("SELECT 1;"))
        model.addElement(entry("SELECT 2;"))
        val list = SqlEntryList(model)
        list.fixedCellHeight = 20
        list.setSize(200, 100)
        list.selectedIndices = intArrayOf(0, 1)
        assertEquals("列表 Ctrl+C 应绑定到平台既有 copy 动作",
            "copy",
            list.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control C")))
        assertEquals("列表 Ctrl+Insert 应同绑",
            "copy",
            list.inputMap.get(javax.swing.KeyStroke.getKeyStroke("control INSERT")))
        CopyPasteManager.getInstance().setContents(StringSelection("__SENTINEL__"))
        list.actionMap.get("copy")
            .actionPerformed(java.awt.event.ActionEvent(list, java.awt.event.ActionEvent.ACTION_PERFORMED, "copy"))
        val clip: String? = CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
        val expectedSql = "SELECT 1;\nSELECT 2;"
        if (clip != expectedSql) {
            // T-CiRelease（20261024 诊断自曝）：CI 上 copy 动作未产出预期文本（同 JVM 的编辑器版
            // Ctrl+C 用例通过=剪贴板本身可用），失败时打出实际内容与选择态供控制台定位。
            println("[list-copy-diag] clip=<<$clip>> selection=${list.selectedValues.toList()}")
        }
        assertEquals("多选列表 Ctrl+C 应复制逐行拼接的纯 SQL", expectedSql, clip)
    }

    fun testClickPreviewSql关闭格式化为原文加分号() {
        assertEquals("SELECT 1;", clickPreviewSql("SELECT 1", formatSql = false))
        assertEquals("已有分号不重复", "SELECT 1;", clickPreviewSql("SELECT 1;", formatSql = false))
        assertEquals("空 SQL 不追加分号", "", clickPreviewSql("", formatSql = false))
    }

    fun testClickPreviewSql开启格式化为多行加缩进() {
        val formatted = clickPreviewSql("SELECT t.id,t.name FROM t WHERE t.id = 1", formatSql = true)
        assertTrue("开启设置时应多行格式化", formatted.contains('\n'))
        assertTrue("分号收尾", formatted.endsWith(";"))
        assertTrue("关键字换行（FROM 独立成行）", formatted.contains("FROM"))
    }

    // ---------- R60（T-LaneB D96）：预览浮层链脱敏（判别断言两态） ----------

    fun testClickPreviewSql脱敏开_展示文本打码() {
        val masked = clickPreviewSql(
            "SELECT * FROM t WHERE phone = '13812345678'",
            formatSql = false,
            maskSql = true,
        )
        assertTrue("脱敏开应打码", masked.contains("'***'"))
        assertFalse("原文手机号不得出现", masked.contains("13812345678"))
    }

    fun testClickPreviewSql脱敏关_原文加分号() {
        val plain = clickPreviewSql(
            "SELECT * FROM t WHERE phone = '13812345678'",
            formatSql = false,
            maskSql = false,
        )
        assertTrue("脱敏关原文保留", plain.contains("13812345678"))
        assertTrue("分号收尾语义不变", plain.endsWith(";"))
    }

    // ---------- T-MagpieGems W2：预览文本按 MaskMode 分级（WARN 警示行 / BLOCK 阻断占位） ----------

    // contact 非关键词列 → 手机号走形态兜底（警示行才含「手机号」类别）；pwd 走关键词
    private val W2_PREVIEW_SQL = "SELECT * FROM t WHERE contact = '13812345678' AND pwd = 'p@ss'"

    fun testClickPreviewSqlWARN原文加警示行() {
        val text = clickPreviewSql(W2_PREVIEW_SQL, formatSql = false, DisplayMasker.MaskMode.WARN)
        assertTrue("WARN 应保留原文", text.contains("13812345678"))
        val warnPrefix = MpSqlVisualizerBundle.message("log.preview.maskWarn", "").substringBefore("{0}")
        assertTrue("头部应追加警示行（前缀=$warnPrefix）", text.startsWith(warnPrefix))
        assertTrue("警示行应含手机号类别计数", text.contains(MpSqlVisualizerBundle.message("mask.category.mobile")))
        assertTrue("警示行应含密码类列名类别计数", text.contains(MpSqlVisualizerBundle.message("mask.category.sensitive_column")))
        assertFalse("警示行绝不回显原值", text.substringBefore("\n").contains("13812345678"))
    }

    fun testClickPreviewSqlWARN零敏感不加警示行() {
        val text = clickPreviewSql("SELECT 1", formatSql = false, DisplayMasker.MaskMode.WARN)
        assertTrue("零敏感应原文不加警示行", text.contains("SELECT 1") && !text.contains("×"))
    }

    fun testClickPreviewSqlBLOCK整段阻断占位() {
        val text = clickPreviewSql(W2_PREVIEW_SQL, formatSql = false, DisplayMasker.MaskMode.BLOCK)
        assertTrue("BLOCK 应输出阻断占位（含命中总数 2）", text.contains("2"))
        assertFalse("原文手机号不得出现", text.contains("13812345678"))
        assertFalse("原文密码不得出现", text.contains("p@ss"))
        // 白名单豁免命中后计数 0 → 按 OFF 原样
        val exempt = clickPreviewSql(
            W2_PREVIEW_SQL, formatSql = false, DisplayMasker.MaskMode.BLOCK,
            allowList = listOf("contact", "pwd"),
        )
        assertTrue("白名单全豁免后 BLOCK 按 OFF 原样", exempt.contains("13812345678"))
    }

    fun testPreviewCopySafeText从严口径() {
        // WARN 屏显原文，但复制出去必须打码（离开 IDE 面）
        val masked = previewCopySafeText(W2_PREVIEW_SQL, DisplayMasker.MaskMode.WARN)
        assertTrue(masked.contains("'***'"))
        assertFalse(masked.contains("13812345678"))
        // MASK 已打码文本再过一道幂等
        assertEquals(
            previewCopySafeText(W2_PREVIEW_SQL, DisplayMasker.MaskMode.MASK),
            previewCopySafeText(previewCopySafeText(W2_PREVIEW_SQL, DisplayMasker.MaskMode.MASK), DisplayMasker.MaskMode.MASK),
        )
        // OFF 原样（既有语义）
        assertEquals(W2_PREVIEW_SQL, previewCopySafeText(W2_PREVIEW_SQL, DisplayMasker.MaskMode.OFF))
    }

    // ---------- 三面板接线 ----------

    fun testSqlLogPanel和历史页默认不打开底部预览() {
        val manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
        val logs = SqlLogPanel(project, manager)
        val history = HistorySqlLogPanel(project)
        try {
            assertFalse(logs.previewHostForTest.previewOpenForTest)
            assertFalse(history.previewHostForTest.previewOpenForTest)
            assertFalse(logs.previewHostForTest.hintVisibleForTest)
            assertFalse(history.previewHostForTest.hintVisibleForTest)
        } finally {
            history.dispose()
            logs.dispose()
            manager.dispose()
        }
    }

    // ---------- T-AutoFold F4-b：buildContent 程序性折叠 ----------

    private fun withAutoFold(enabled: Boolean, block: () -> Unit) {
        val state = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state
        val oldEnabled = state.autoFoldEnabled
        val oldThreshold = state.autoFoldFieldThreshold
        try {
            state.autoFoldEnabled = enabled
            state.autoFoldFieldThreshold = 3
            block()
        } finally {
            state.autoFoldEnabled = oldEnabled
            state.autoFoldFieldThreshold = oldThreshold
        }
    }

    private fun ownFoldRegions(editor: Editor) =
        editor.foldingModel.allFoldRegions.filter { it.placeholderText.startsWith("…") }

    fun test开启自动折叠后弹窗内容含折叠区域() {
        withAutoFold(enabled = true) {
            val content = SqlClickPreview.buildContent(
                project,
                "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t",
                copyText = {},
                close = {},
            )
            try {
                val regions = ownFoldRegions(content.editor)
                assertEquals("开启态应有一个折叠区域", 1, regions.size)
                assertFalse("区域应收起", regions[0].isExpanded)
                assertTrue("开启态应补设折叠轮廓", content.editor.settings.isFoldingOutlineShown)
            } finally {
                releaseEditor(content.editor)
            }
        }
    }

    fun test关闭自动折叠后弹窗内容零区域且无轮廓() {
        withAutoFold(enabled = false) {
            val content = SqlClickPreview.buildContent(
                project,
                "SELECT\n    a1,\n    a2,\n    a3,\n    a4\nFROM t",
                copyText = {},
                close = {},
            )
            try {
                assertEquals("关闭态零折叠区域（紧凑外观逐字节不变）", 0, ownFoldRegions(content.editor).size)
                assertFalse("关闭态不补设折叠轮廓", content.editor.settings.isFoldingOutlineShown)
            } finally {
                releaseEditor(content.editor)
            }
        }
    }
}
