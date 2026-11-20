package cn.song.mybatisplus.visualizer.ui.standalone

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 缺失 `<sql id="…">` 片段的补齐输入框（T-IncludePlaceholder A 券）。
 *
 * 为什么自建而不 `Messages.showMultilineInput`：本平台（243 编译目标）`Messages` 没有可用的
 * 多行输入重载（编译期 Unresolved reference 实证），且这里需要「提示语 + 多行文本框 + 快捷键」
 * 的固定形态。
 *
 * 用户可粘贴 `<sql id="x">…</sql>` 整段，也可只贴内容（纯列清单），由
 * [MissingSqlFragmentSupport.withFragments] 决定是否补壳。
 */
class MissingFragmentDialog(
    private val project: Project?,
    private val refid: String,
) : DialogWrapper(project, true) {

    private val area = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
    }

    init {
        title = MpSqlVisualizerBundle.message("standalone.missingFragment.title")
        init()
        // 按钮文案必须走插件 bundle：平台默认的 OK/Cancel 随 IDE 语言包，插件语言独立（用户实测中文环境仍显示 OK/Cancel）
        setOKButtonText(MpSqlVisualizerBundle.message("standalone.missingFragment.ok"))
        setCancelButtonText(MpSqlVisualizerBundle.message("dialog.preview.cancel"))
    }

    override fun createCenterPanel(): JComponent {
        val hint = JBLabel(
            MpSqlVisualizerBundle.message("standalone.missingFragment.prompt", refid),
        ).apply { border = JBUI.Borders.emptyBottom(6) }
        // T-IncludePlaceholder A 券二轮：从工程里选一个 mapper XML，自动抽出本 refid 对应的
        // `<sql id="…">` 内部内容（找不到就把整段文本带进来，由用户自己挑）
        val pickButton = javax.swing.JButton(
            MpSqlVisualizerBundle.message("standalone.missingFragment.fromFile"),
        ).apply {
            addActionListener { pickFromFile() }
        }
        val header = JPanel(BorderLayout()).apply {
            add(hint, BorderLayout.CENTER)
            add(pickButton, BorderLayout.EAST)
            border = JBUI.Borders.emptyBottom(6)
        }
        val scroll = JBScrollPane(area).apply {
            preferredSize = Dimension(560, 180)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    /**
     * 选文件 → 抽出本 refid 的 `<sql>` 内容（找不到则整段带入）。
     *
     * 线程（T-Perf33MergeAlign）：选文件在 EDT、读文本在后台、回填回 EDT——大文件在 EDT 读会卡弹框；
     * `isDisposed` 守卫丢弃用户已关框后晚到的读取结果。
     */
    private fun pickFromFile() {
        val file = MapperFileLoader.chooseFile(project, "standalone.missingFragment.fromFile.title") ?: return
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val text = MapperFileLoader.loadText(file) ?: return@executeOnPooledThread
            app.invokeLater(
                {
                    if (isDisposed) return@invokeLater
                    area.text = MissingSqlFragmentSupport.extractFragment(text, refid) ?: text
                },
                ModalityState.nonModal(),
            )
        }
    }

    /** 用户输入的内容（确定时读取；取消路径不读）。 */
    fun content(): String = area.text

    override fun getDimensionServiceKey(): String = DIMENSION_KEY

    companion object {
        private const val DIMENSION_KEY = "cn.song.mybatisplus.visualizer.MissingFragmentDialog"
    }
}