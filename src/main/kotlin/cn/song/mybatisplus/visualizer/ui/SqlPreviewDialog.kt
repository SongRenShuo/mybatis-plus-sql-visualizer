package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.Action
import javax.swing.JComponent

/**
 * SQL 预览弹窗（M3-02，F-07 弹窗展示方式）。
 *
 * 由 [cn.song.mybatisplus.visualizer.actions.ConvertToSqlAction] 触发，
 * 复用 [SqlPreviewPanel] 展示 SQL 与参数对照，底部提供"复制 / 关闭"。
 *
 * M14：默认尺寸按屏幕比例自适应（宽 62% / 高 65%，下限 900×600、上限 85%），
 * 并记忆用户拉伸后的尺寸（[rememberLastWindowSize]）。
 */
class SqlPreviewDialog(
    private val project: Project?,
    private val result: PreviewResult,
    private val sourceLocation: String? = null,
    sourceContext: cn.song.mybatisplus.visualizer.context.PreviewSourceContext? = null,
) : DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS) {

    private val panel = SqlPreviewPanel(project)

    /**
     * T-DmlImpactProminence：底部蓝色主行动——复制 SQL 并关闭（复制成功提示随之关闭面板）。
     *
     * 必须声明在 [init] 之前：Kotlin 字段按声明序初始化，而 `init()` 会在构造期同步调用
     * `createActions()`，声明在后则该字段此刻仍为 null，动作数组里出现空位、按钮不渲染
     * （UI-042：用户侧表现为「复制 SQL 并关闭」按钮整个不见）。
     */
    private val copyAndCloseAction = object : DialogWrapperAction(
        MpSqlVisualizerBundle.message("panel.copySqlAndClose"),
    ) {
        override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
            // UI-044：弹窗随即关闭，「关闭 SQL 面板」动作已无目标，通知里不再挂它
            panel.copySql(offerClosePanelAction = false)
            doDispose()
        }
    }

    /**
     * 构造期首次 [createActions] 的产出（UI-042 回归缝）。
     *
     * 必须声明在 [init] 之前：`init()` 同步触发 `createActions()`，若本字段声明在后，
     * 其初始化表达式会在捕获之后执行、把捕获结果抹掉，回归缝自身失效。
     */
    internal var actionsAtConstructionForTest: Array<Action>? = null

    init {
        title = MpSqlVisualizerBundle.message("dialog.preview.title")
        panel.setSourceLocation(sourceLocation)
        panel.setSourceContext(sourceContext)
        panel.showResult(result)
        // 注册面板到弹窗 Disposer 树：弹窗 dispose 时级联释放面板及其 messageBus 连接
        Disposer.register(disposable, panel)
        // T-M23.6：复制/执行 SQL 成功提示的「关闭 SQL 面板」按钮 → 关闭预览弹窗
        panel.onClosePanelRequested = { dispose() }
        // 最小尺寸兜底（防止拉伸时缩没；DialogWrapper 无 setMinimumSize，对中心面板设置）
        panel.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
        init()
        // 关闭按钮文案走插件 bundle（平台 cancelAction 随 IDE 语言包，插件语言设置独立于 IDE，避免中文环境显示英文 Cancel）
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"))
        // 弹窗默认尺寸（按屏幕比例自适应；环境异常兜底）
        val size = initialSize()
        setSize(size.width, size.height)
    }

    override fun createCenterPanel(): JComponent = panel

    /**
     * T-RefreshDbSteal F2：程序化关闭（供 [SqlPreviewService] 弹窗单例化关旧开新）。
     * DialogWrapper.dispose 在平台侧 protected，同类内提升为 public 包装——语义与用户
     * 点关闭一致（级联 Disposer 释放面板，rehost 监听随之移除）。
     */
    fun doDispose() {
        dispose()
    }

    /** T-M23.6：internal 测试访问器（面板「关闭 SQL 面板」回调链路断言，仿 SqlPreviewPanel.editorForTest）。 */
    internal fun panelForTest(): SqlPreviewPanel? = panel

    override fun createActions(): Array<Action> = arrayOf(
        // 执行 SQL（M17：选择数据源 → 临时文件 → 可执行；提示由面板/支持层统一处理）
        // UX Community 预判置灰：构造期按 ExecuteSqlSupport.isAvailable() 判定，不可用即灰置 +
        // tooltip 说明（SHORT_DESCRIPTION 由 JButton(Action) 直接渲染成按钮 tooltip），
        // 不再「点了才报错」。doAction 仍保留二次守卫（防动作被程序化触发）。
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("panel.executeSql")) {
            init {
                cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.unavailableHint()?.let { hint ->
                    isEnabled = false
                    putValue(Action.SHORT_DESCRIPTION, hint)
                }
            }

            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                if (!cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.isAvailable()) return
                panel.executeSql()
            }
        },
        // 复制 SQL（F-08，提示由面板统一处理）
        object : DialogWrapperAction(MpSqlVisualizerBundle.message("panel.copySql")) {
            override fun doAction(actionEvent: java.awt.event.ActionEvent?) {
                panel.copySql()
            }
        },
        copyAndCloseAction,
        // 关闭
        cancelAction,
    ).also { actionsAtConstructionForTest = actionsAtConstructionForTest ?: it }

    /** 复制并关闭按钮按主行动样式渲染（蓝色实底）；其余按钮保持平台默认。 */
    override fun createJButtonForAction(action: Action): javax.swing.JButton {
        val button = super.createJButtonForAction(action)
        if (action === copyAndCloseAction) applyPrimaryButtonStyle(button)
        return button
    }

    /** UI-043 测试访问器（createJButtonForAction 在 DialogWrapper 内为 protected）。 */
    internal fun createJButtonForActionForTest(action: Action): javax.swing.JButton = createJButtonForAction(action)

    /** T-DmlImpactProminence：测试访问器（createActions 在 DialogWrapper 内为 protected）。 */
    internal fun actionsForTest(): Array<Action> = createActions()

    /** 尺寸记忆 key（非 null → DialogWrapper 经 DimensionService 记忆用户拉伸尺寸，下次打开保持）。 */
    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    companion object {
        /** 尺寸记忆 key。 */
        const val DIMENSION_KEY = "cn.song.mybatisplus.visualizer.SqlPreviewDialog"

        /** 最小弹窗尺寸。 */
        const val MIN_WIDTH = 640
        const val MIN_HEIGHT = 420

        /** 屏幕占比（宽/高）。 */
        const val SCREEN_RATIO_WIDTH = 0.62
        const val SCREEN_RATIO_HEIGHT = 0.65

        /** 屏幕占比上限（防止小屏超屏）。 */
        const val SCREEN_RATIO_CAP = 0.85

        /** 最小默认尺寸（小屏/异常环境兜底）。 */
        const val DEFAULT_WIDTH = 900
        const val DEFAULT_HEIGHT = 600

        /**
         * 计算弹窗默认尺寸（M14）：按屏幕比例（宽 62% / 高 65%），
         * 夹在「下限 900×600」与「屏幕 85% 上限」之间；小屏时**上限优先（不超屏）**；
         * headless/无屏幕信息时兜底 900×600。
         * 独立静态方法便于单测（mock 屏幕）。
         */
        internal fun initialSize(screenWidth: Int = screenWidthOf(), screenHeight: Int = screenHeightOf()): Dimension {
            if (screenWidth <= 0 || screenHeight <= 0) return Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT)
            val w = fitWithin((screenWidth * SCREEN_RATIO_WIDTH).toInt(), screenWidth, DEFAULT_WIDTH)
            val h = fitWithin((screenHeight * SCREEN_RATIO_HEIGHT).toInt(), screenHeight, DEFAULT_HEIGHT)
            return Dimension(w, h)
        }

        /**
         * 尺寸夹取：正常屏 → 比例值夹在 [minDefault, 屏幕 85%]；
         * 小屏（上限 < 下限）→ 取上限（不超屏优先于保下限）。
         */
        private fun fitWithin(ratioValue: Int, screenSize: Int, minDefault: Int): Int {
            val cap = (screenSize * SCREEN_RATIO_CAP).toInt()
            return if (minDefault <= cap) ratioValue.coerceIn(minDefault, cap) else cap
        }

        private fun screenWidthOf(): Int = runCatching { Toolkit.getDefaultToolkit().screenSize.width }.getOrDefault(0)

        private fun screenHeightOf(): Int = runCatching { Toolkit.getDefaultToolkit().screenSize.height }.getOrDefault(0)
    }
}
