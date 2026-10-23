package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import cn.song.mybatisplus.visualizer.ui.highlight.SqlHighlightColors
import cn.song.mybatisplus.visualizer.ui.highlight.SqlListSyntaxHighlighter
import cn.song.mybatisplus.visualizer.util.DisplayMasker
import cn.song.mybatisplus.visualizer.util.EditorCopyShortcuts
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** 预览内容持有者：面板组件 + 编辑器实例（测试断言与释放用）。 */
internal class SqlClickPreviewContent(val component: JComponent, val editor: Editor)

/**
 * 单击预览浮层文本（T-ListPreviewCopy）：分号收尾 + 按设置「预览时格式化还原 SQL」
 * （logFormatSql，默认开=默认格式化显示）决定是否 [SqlFormatter.format] 多行展开。
 * 与双击弹窗 `toPreviewResult` 的 `formatIfNeeded(withTerminator(...))` 同序——
 * 先补分号再格式化（生产已验证的分号处理路径）。纯函数，开关由调用方读设置传入。
 *
 * R60（T-LaneB D96）：[maskSql] 开时先脱敏（[DisplayMasker.maskDisplay]，展示层文本级）再走
 * 分号/格式化——脱敏只动浮层展示文本，条目数据层（restoredSql/params）不动。
 *
 * T-MagpieGems W2：按 [maskMode] 分级渲染（委托 [clickPreviewSql] 的 mode 主形态；
 * 布尔重载向后兼容既有调用与测试）。
 */
internal fun clickPreviewSql(sql: String, formatSql: Boolean, maskSql: Boolean = false): String =
    clickPreviewSql(
        sql, formatSql,
        if (maskSql) DisplayMasker.MaskMode.MASK else DisplayMasker.MaskMode.OFF,
    )

/**
 * 单击预览浮层/停靠预览文本（T-MagpieGems W2 主形态，按 [maskMode] 分级）：
 *  - OFF：原文；
 *  - MASK：打码文本；
 *  - WARN：原文 + **文本头部追加警示行** `⚠ 检测到疑似敏感值：身份证×a、手机号×b`
 *    （[DisplayMasker.scanSqlText] 计数为 0 则不追加；文案只含类别与次数，绝不回显原值）；
 *  - BLOCK：阻断占位文本（白名单豁免后计数 0 时按 OFF 原样）。
 * 只动浮层展示文本，条目数据层不动。
 */
internal fun clickPreviewSql(
    sql: String,
    formatSql: Boolean,
    maskMode: DisplayMasker.MaskMode,
    allowList: List<String> = emptyList(),
): String {
    val scan = DisplayMasker.scanSqlText(sql, maskMode, allowList)
    val body = if (formatSql) SqlFormatter.format(withTerminator(scan.maskedSql)) else withTerminator(scan.maskedSql)
    val warn = if (maskMode == DisplayMasker.MaskMode.WARN) DisplayMasker.warnLine(scan.counts) else null
    return if (warn != null) warn + "\n" + body else body
}

/**
 * T-MagpieGems W2：预览浮层复制文本的从严归一——复制是离开 IDE 面，mode != OFF 一律按
 * MASK 行为打码（WARN 屏显原文，但复制出去的文本不得带原值）。OFF 原样（既有语义）。
 */
internal fun previewCopySafeText(text: String, maskMode: DisplayMasker.MaskMode): String =
    if (maskMode == DisplayMasker.MaskMode.OFF) text else DisplayMasker.maskDisplay(text, true)

/**
 * 修饰键是否表达列表多选意图（T-ClickPreviewCopyRestore）：Ctrl/Shift 单击是选择的开始或扩展，
 * 修饰键在**第一击**就可判定——不必等 `selectedIndices.size > 1`（那要等第二击才满足），
 * 第一击就弹预览抢焦点正是批量复制被打断的根因。就地引导的点击守卫沿用此判定。
 */
internal fun isMultiSelectIntent(modifiersEx: Int): Boolean =
    modifiersEx and (java.awt.event.MouseEvent.CTRL_DOWN_MASK or java.awt.event.MouseEvent.SHIFT_DOWN_MASK) != 0

/** 日志与历史底部预览区共用的高亮编辑器及复制动作构建器。 */
internal object SqlClickPreview {
        private const val PREFERRED_WIDTH = 560
        private const val PREFERRED_HEIGHT = 320

        /**
         * 标记浮层预览编辑器（T-ClickPreviewCopyRestore）：copy handler 据此区分
         * 「本插件的预览编辑器」与「IDE 其余一切编辑器」——只对前者接管无选中 Ctrl+C 语义。
         */
        private val PREVIEW_EDITOR_KEY = Key.create<Boolean>("mpsv.clickPreview.editor")

        /** 浮层编辑器上挂的复制动作（头部按钮与 Ctrl+C handler 共用同一实例，含对号反馈）。 */
        private val COPY_ACTION_KEY = Key.create<() -> Unit>("mpsv.clickPreview.copyAction")

        /** 原生 copy handler 安装只做一次（编辑器实例可多次创建，Key 挂实例天然隔离）。 */
        private val COPY_HANDLER_LOCK = Any()
        private var copyHandlerInstalled = false

        /**
         * 接管 ACTION_EDITOR_COPY 的**无选中**分支（T-ClickPreviewCopyRestore）：
         * 浮层编辑器无选区时 Ctrl+C 复制全文（编辑后文本，与头部按钮同源）——
         * 平台编辑器 copy 按选区工作、无选中是 no-op，用户「点开浮层直接 Ctrl+C」因此什么都没发生；
         * 有选区不接管（放行原生：只复制选中片段，保留「临时改字段值后复制片段」能力）；
         * 非浮层编辑器一律委托原生 handler，IDE 全局语义零变化。keymap 里 Ctrl+C 与 Ctrl+Insert
         * 都绑 $Copy，替换 handler 一处两键同生效。全局替换以 companion 惰性单次 + 委托兜底保证安全
         * （测试进程内同样生效，直调 handler 即可断言）。
         */
        internal fun installCopyHandler() {
            synchronized(COPY_HANDLER_LOCK) {
                if (copyHandlerInstalled) return
                val manager = EditorActionManager.getInstance()
                val origin = manager.getActionHandler(IdeActions.ACTION_EDITOR_COPY)
                manager.setActionHandler(
                    IdeActions.ACTION_EDITOR_COPY,
                    object : EditorActionHandler() {
                        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
                            if (editor.getUserData(PREVIEW_EDITOR_KEY) == true &&
                                !editor.selectionModel.hasSelection()
                            ) {
                                editor.getUserData(COPY_ACTION_KEY)?.invoke()
                                return
                            }
                            origin.execute(editor, caret, dataContext)
                        }

                        override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
                            editor.getUserData(PREVIEW_EDITOR_KEY) == true ||
                                // 三参 isEnabled 是 final 分派器（243 源码核验：caret 非 null 即转发 origin 的
                                // isEnabledForCaret）——委托它保持 origin 完整的可用性判定语义
                                origin.isEnabled(editor, caret, dataContext)
                    },
                )
                copyHandlerInstalled = true
            }
        }

        /**
         * 构建预览内容：头部「复制 | 关闭」图标按钮 + SQL 高亮可编辑编辑器。
         * 高亮 = [SqlListSyntaxHighlighter]（SqlLexer 分词 + [SqlHighlightColors] 配色键，
         * 与日志列表条目共用同一映射——四轮用户反馈：预览高亮须与列表同色，直接复用；
         * 不走平台 SQL FileType 方言高亮，也不依赖 Database 插件，缺席环境同样有高亮），
         * 软换行长 SQL 免横向滚动，隐藏行号/折叠/缩进导轨/页底留白的紧凑外观。
         * 按钮回调由调用方注入（测试直录断言）；**调用方负责释放 editor**（弹窗路径经 onClosed）。
         */
        internal fun buildContent(
            project: Project,
            sql: String,
            copyText: (String) -> Unit,
            close: () -> Unit,
        ): SqlClickPreviewContent {
            val document = EditorFactory.getInstance().createDocument(sql)
            val editor = EditorFactory.getInstance().createEditor(document, project)
            (editor as? EditorEx)?.apply {
                // createEditor 静态类型为 Editor，setHighlighter 在 EditorEx（243 javap 核验）；
                // EditorImpl 实现之，非 EditorEx 实现时跳过（降级无高亮纯文本）
                setHighlighter(
                    LexerEditorHighlighter(
                        SqlListSyntaxHighlighter(),
                        EditorColorsManager.getInstance().globalScheme,
                    ),
                )
                settings.apply {
                    isLineNumbersShown = false // 紧凑弹窗不留 gutter
                    isFoldingOutlineShown = false
                    isIndentGuidesShown = false
                    isAdditionalPageAtBottom = false
                    setCaretRowShown(false)
                    setUseSoftWraps(true) // 长 SQL 软换行免横向滚动
                }
                // T-PreviewWhitespaceToggle O-44a：空白符可视化（默认关=不设置该属性、继承 IDE 全局）
                cn.song.mybatisplus.visualizer.ui.EditorWhitespaceSettings.applyTo(this)
            }
            editor.component.preferredSize = Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT)

            // T-AutoFold F4-b：弹窗编辑器程序性折叠（关闭态 applyTo 内部即清除短路，零外观变更；
            // 开启态由 applyTo 补设 isFoldingOutlineShown=true——上面紧凑外观的 false 被覆盖）
            cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding.applyTo(editor, sql)

            val copyButton = JButton(AllIcons.Actions.Copy).apply {
                toolTipText = MpSqlVisualizerBundle.message("log.preview.copy")
                isFocusable = false
            }
            val closeButton = JButton(AllIcons.Actions.Close).apply {
                toolTipText = MpSqlVisualizerBundle.message("log.preview.close")
                isFocusable = false
                addActionListener { close() }
            }
            // T-ClickPreviewCopyRestore：头部按钮与编辑器 Ctrl+C handler 共用同一复制动作
            //（取编辑后当前文本 + 图标换对号反馈），两条入口产物与反馈一致
            val copyAll = {
                copyText(editor.document.text) // 复制当前内容（含用户编辑后文本）
                cn.song.mybatisplus.visualizer.ui.flashCopied(copyButton) // 复制成功反馈：复制图标换对号，短暂后恢复
            }
            copyButton.addActionListener { copyAll() }
            editor.putUserData(PREVIEW_EDITOR_KEY, true)
            editor.putUserData(COPY_ACTION_KEY, copyAll)
            installCopyHandler()

            // T-SqlCopyShortcuts O-122（原二轮内联实现收敛到共享件）：Swing 层 Ctrl+C / Ctrl+Insert
            // 接管——无选中复制全文、有选中只复制选区（保留改字段值后复制片段），与头部按钮同反馈；
            // 为什么必须挂在这一层、为什么不是 keymap 侧，见 EditorCopyShortcuts 的 KDoc。
            EditorCopyShortcuts.install(
                editor,
                copyText,
                onCopied = { cn.song.mybatisplus.visualizer.ui.flashCopied(copyButton) },
            )
            val header = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
                add(copyButton)
                add(closeButton)
            }
            val panel = JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(editor.component, BorderLayout.CENTER)
            }
            return SqlClickPreviewContent(panel, editor)
        }
}
