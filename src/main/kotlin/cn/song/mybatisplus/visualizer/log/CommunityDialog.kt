package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.util.HtmlEscape
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.SafeBrowser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.imageio.ImageIO
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class CommunityDialog(
    project: Project,
    private val loadImage: (String) -> BufferedImage = CommunityQrImages::fetch,
) : DialogWrapper(project, true) {

    private val authorQr = qrPlaceholder()
    private val groupQr = qrPlaceholder()
    private val authorCard = card("log.community.author", "log.community.author.desc", authorQr)
    private val groupCard = card("log.community.group", "log.community.group.desc", groupQr)
    private val openSiteButton = JButton(MpSqlVisualizerBundle.message("log.community.openSite")).apply {
        addActionListener { SafeBrowser.browse(ABOUT_URL) }
    }
    private var loadingStarted = false

    internal val qrLabelsForTest: List<JLabel> get() = listOf(authorQr, groupQr)
    internal val cardsForTest: List<JPanel> get() = listOf(authorCard, groupCard)
    internal val openSiteButtonForTest: JButton get() = openSiteButton

    init {
        title = MpSqlVisualizerBundle.message("log.community.title")
        init()
        setOKButtonText(MpSqlVisualizerBundle.message("log.community.close"))
        setResizable(false)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val compact = !GraphicsEnvironment.isHeadless() &&
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds.width < JBUI.scale(540)
        val cards = JPanel(GridLayout(if (compact) 2 else 1, if (compact) 1 else 2, JBUI.scale(12), JBUI.scale(12)))
        cards.add(authorCard)
        cards.add(groupCard)

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(cards, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
                border = JBUI.Borders.emptyTop(8)
                add(openSiteButton)
            }, BorderLayout.SOUTH)
        }
    }

    internal fun startLoading() {
        if (loadingStarted || isDisposed) return
        loadingStarted = true
        listOf(WECHAT_QR_URL to authorQr, GROUP_QR_URL to groupQr).forEach { (url, label) ->
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { CommunityQrImages.scaled(loadImage(url)) }
                ApplicationManager.getApplication().invokeLater({ applyImageResult(label, result) }, ModalityState.any())
            }
        }
    }

    internal fun applyImageResult(label: JLabel, result: Result<BufferedImage>) {
        if (isDisposed) return
        result.onSuccess { image ->
            label.icon = ImageIcon(image)
            label.text = null
        }.onFailure {
            label.icon = null
            label.text = MpSqlVisualizerBundle.message("log.community.loadFailed")
        }
    }

    private fun qrPlaceholder(): JLabel = JLabel(MpSqlVisualizerBundle.message("log.community.loading"), SwingConstants.CENTER).apply {
        preferredSize = JBUI.size(QR_SIZE, QR_SIZE)
        minimumSize = preferredSize
        accessibleContext.accessibleName = MpSqlVisualizerBundle.message("log.community.loading")
    }

    private fun card(titleKey: String, descriptionKey: String, image: JLabel): JPanel {
        val title = MpSqlVisualizerBundle.message(titleKey)
        val description = MpSqlVisualizerBundle.message(descriptionKey)
        val titleLabel = JLabel(title, SwingConstants.CENTER).apply {
            alignmentX = 0.5f
            font = font.deriveFont(java.awt.Font.BOLD)
            // T-SAF-10：纯 bundle 文案、无 markup 需求，禁用 HTML 解析（对齐 DegradationBanner 同款约定）
            putClientProperty("html.disable", true)
        }
        val descriptionLabel = JLabel(
            // T-SAF-10：转义收敛到共享 HtmlEscape（原本地变体缺 `'` 转义）
            "<html><div style='width:${JBUI.scale(188)}px;text-align:center'>${HtmlEscape.escape(description)}</div></html>",
            SwingConstants.CENTER,
        ).apply {
            alignmentX = 0.5f
            preferredSize = JBUI.size(196, 76)
        }
        image.accessibleContext.accessibleName = title
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(12),
            )
            add(image.apply { alignmentX = 0.5f })
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(titleLabel)
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(descriptionLabel)
        }
    }

    companion object {
        private const val QR_SIZE = 180
        internal const val ABOUT_URL = "https://sql-visualizer.jvtools.cn/about/"
        internal const val WECHAT_QR_URL = "https://sql-visualizer.jvtools.cn/wechat-qr.png"
        internal const val GROUP_QR_URL = "https://sql-visualizer.jvtools.cn/group-qr.png"
    }
}

internal object CommunityQrImages {
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_DIMENSION = 1024

    fun fetch(url: String): BufferedImage = HttpRequests.request(url)
        .connectTimeout(5_000)
        .readTimeout(5_000)
        .gzip(false)
        .throwStatusCodeException(true)
        .connect { request -> request.inputStream.use { decode(readBounded(it)) } }

    internal fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) throw IOException("QR image exceeds size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun decode(bytes: ByteArray): BufferedImage {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw IOException("Invalid QR image")
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) throw IOException("Unsupported QR image")
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
                    throw IOException("QR image dimensions exceed limit")
                }
                return reader.read(0) ?: throw IOException("Invalid QR image")
            } finally {
                reader.dispose()
            }
        }
    }

    internal fun scaled(image: BufferedImage): BufferedImage {
        val result = BufferedImage(180, 180, BufferedImage.TYPE_INT_ARGB)
        val graphics: Graphics2D = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            graphics.drawImage(image, 0, 0, result.width, result.height, null)
        } finally {
            graphics.dispose()
        }
        return result
    }
}
