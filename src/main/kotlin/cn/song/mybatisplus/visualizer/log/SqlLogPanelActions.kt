package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.config.PluginSettings.Companion.LOG_TIMESTAMP_SOURCE_LOG
import cn.song.mybatisplus.visualizer.config.SqlLogConfigurable
import cn.song.mybatisplus.visualizer.config.SettingsChangeListener
import cn.song.mybatisplus.visualizer.log.agent.AgentManager
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
// T-UiCoord F2：下拉按钮文本截尾省略复用 meta 行的同款纯函数（避免第二份省略实现）
import cn.song.mybatisplus.visualizer.ui.clipTailToWidth
import com.intellij.util.ui.JBUI
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import cn.song.mybatisplus.visualizer.util.MpvsUiColors
import cn.song.mybatisplus.visualizer.util.TextCopyList
import cn.song.mybatisplus.visualizer.util.UiTextClipboard
import cn.song.mybatisplus.visualizer.ui.search.NativeSearchTextField
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import javax.swing.Action
import javax.swing.JComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import com.intellij.ui.JBColor
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.awt.RelativePoint
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JRadioButtonMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.JToggleButton
import javax.swing.ListModel
import javax.swing.SwingConstants
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// 扩展函数看不到接收者类的 companion 成员，显式 import 引入：
import cn.song.mybatisplus.visualizer.log.SqlLogPanel.Companion.NOTIFICATION_GROUP_ID
import cn.song.mybatisplus.visualizer.log.SqlLogPanel.Companion.SUPPRESS_NOTIFICATION_GROUP_ID

// T-M53-II（2026-10-10）：以下为 SqlLogPanel 的「右键菜单、导出与抑制反馈」内聚成员，
// 以同包扩展函数形式搬出——方法体逐字符搬运，被触及的私有成员已按需放宽为 internal。

internal fun SqlLogPanel.exportVisibleEntries() {
    val rows = lastRendered.map {
        val source = buildString {
            if (!it.sourceFile.isNullOrBlank()) {
                append(it.sourceFile)
                if (it.sourceLine > 0) append(":").append(it.sourceLine)
            }
        }
        SqlExportRow(
            time = SqlExportService.formatTime(it.timestamp),
            sql = it.restoredSql,
            source = source,
            durationMs = it.executionTimeMs,
        )
    }
    if (rows.isEmpty()) return
    val format = chooseExportFormat() ?: return
    val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
        MpSqlVisualizerBundle.message("log.export.visible"),
        "SQL (${format.ext.uppercase()})",
        format.ext,
    )
    val dialog = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
        .createSaveFileDialog(descriptor, project)
    // T-ExportSqlFormat（用户拍板 20261009）：SQL 格式默认名 sql-export-<yyyyMMdd-HHmmss>.sql
    // （时间戳防同日覆盖、不占 sql-history 名族）；CSV/TSV/TXT 保持原名随 ext。
    val defaultName = if (format == SqlExportFormat.SQL) {
        SqlExportService.defaultSqlExportFileName()
    } else {
        "sql-log.${format.ext}"
    }
    val saved = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, defaultName)
        ?: return
    val target = saved.file
    ApplicationManager.getApplication().executeOnPooledThread {
        runCatching {
            // T-MagpieGems W2：导出是离开 IDE 面——mode != OFF 一律按 MASK 打码（从严）。
            // R60 时代本链漏传 mask 形参（日志页导出原文越权），随出口缺口一并补齐。
            SqlExportService.exportToFile(
                target,
                SqlExportService.format(rows, format, PluginSettings.getInstance().displayMaskExportOn()),
            )
        }.onSuccess {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("log.history.export.done", rows.size, target.absolutePath),
                    com.intellij.notification.NotificationType.INFORMATION,
                ).notify(project)
        }.onFailure {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    MpSqlVisualizerBundle.message("log.history.export.failed", it.message ?: ""),
                    com.intellij.notification.NotificationType.ERROR,
                ).notify(project)
        }
    }
}

/** R4：导出格式四选（取消返回 null）。默认选中 SQL（T-ExportSqlFormat 用户拍板 20261009；
 *  P3：showChooseDialog 返回所选下标、取消 -1）。 */

internal fun SqlLogPanel.chooseExportFormat(): SqlExportFormat? {
    val names = arrayOf("CSV", "TSV", "TXT", "SQL")
    val idx = com.intellij.openapi.ui.Messages.showChooseDialog(
        project,
        MpSqlVisualizerBundle.message("log.history.export.choose"),
        MpSqlVisualizerBundle.message("log.export.visible"),
        com.intellij.openapi.ui.Messages.getQuestionIcon(),
        names,
        names[3],
    )
    if (idx < 0) return null
    return when (idx) {
        0 -> SqlExportFormat.CSV
        1 -> SqlExportFormat.TSV
        2 -> SqlExportFormat.TXT
        3 -> SqlExportFormat.SQL
        else -> null
    }
}


internal fun SqlLogPanel.showContextMenu(e: MouseEvent) {
    val index = list.locationToIndex(e.point)
    if (index < 0) return
    // T-ListPreviewCopy：命中行已在多选内 → 保留多选（复制项=批量复制多条）；否则收敛为该行。
    // 删除/发送/跳转等其余菜单项恒作用于被右键的行
    list.selectedIndices = resolveContextMenuSelection(list.selectedIndices, index)
    val entry = list.model.getElementAt(index)
    val selectedEntries = list.selectedIndices.map { list.model.getElementAt(it) }
    // T-UiNative P1a：JPopupMenu+JMenuItem → 平台 DefaultActionGroup+AnAction（ActionPopupMenu 承载）
    val group = DefaultActionGroup()
    group.add(CopySqlAction(selectedEntries))
    // T-LogSearchRowTime：复制时间戳（所见即所复制的纯时间文本，无方括号）；
    // 显示开关关闭时无片段可复制 → 菜单项置灰（置灰逻辑在 CopyTimestampAction.update）
    val timestampClip = batchCopyTimestamps(selectedEntries)
    group.add(CopyTimestampAction(selectedEntries, timestampClip))
    group.add(DeleteSqlAction(entry))
    // T-SqlSuppress（O-69）：屏蔽此 SQL（多选按不同指纹各成一条规则，已存在跳过）——
    // 置「删除」之后（计划定稿位），balloon 反馈带管理直达
    group.add(SuppressSqlAction(selectedEntries))
    // T-M37 P1：发送到 SQL → Java 转换（第三 tab）——还原 SQL 写入输入区并切换选中该 tab
    group.add(SendToSqlToJavaAction(entry))
    // T-M38：发送到日志转 SQL（第二 tab）——条目渲染回日志文本并立即转换
    group.add(SendToLogToSqlAction(entry))
    // O-1：日志 ↔ 源码关联跳转（Phase A）；T-M55-01：拆「跳转到 Java」「跳转到 XML」双目标
    group.add(JumpToSourceAction(entry))
    group.add(JumpToMapperXmlAction(entry))
    // T-M55-03：方法级聚焦（sourceFile 非空时可用；解析失败 toast 提示，无静默吞没）
    group.add(FocusCallerMethodAction(entry))
    // T-M55-04：按请求聚焦（requestTag 非空时可用；页签 K 有界防高基数爆列表）
    group.add(FocusRequestAction(entry))
    // O-90：按模板聚焦（「×N 同模板」徽章达阈值才可用；过滤出该模板全部条目）
    group.add(FocusTemplateAction(entry))
    // R4（W3 历史工作流包）：导出当前可见行集（与面板过滤/搜索一致；CSV/TSV/TXT/SQL，后台写盘）
    group.add(ExportVisibleAction())
    val popup = ActionManager.getInstance().createActionPopupMenu("mpvs.log.list", group)
    // 243 的 ActionPopupMenu 只有 setter 无 getter，Kotlin 不合成 targetComponent 属性——显式调 setter
    popup.setTargetComponent(list)
    popup.component.show(list, e.x, e.y)
}

// ---------- T-UiNative P1a：列表右键菜单动作（原 JMenuItem 语义平移，update 设文案/灰置，actionPerformed 平移原监听体） ----------

/** 复制 SQL：多选批量逐行拼接（[batchCopySql]），单选复制该行。 */

internal fun SqlLogPanel.showSuppressBalloon(addedCount: Int) {
    if (ApplicationManager.getApplication().isUnitTestMode) return
    runCatching {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(SUPPRESS_NOTIFICATION_GROUP_ID)
            .createNotification(
                MpSqlVisualizerBundle.message("log.suppress.added.title"),
                MpSqlVisualizerBundle.message("log.suppress.added.body", addedCount.toString()),
                NotificationType.INFORMATION,
            )
        notification.addAction(
            NotificationAction.createSimple(MpSqlVisualizerBundle.message("log.suppress.manage")) {
                notification.expire()
                SqlSuppressManagerDialog(project).show()
            },
        )
        notification.notify(project)
    }
}

/** T-SqlSuppress：达上限提示 balloon（D6：拒新增不淘汰，文案引导先删不用的规则）。 */

internal fun SqlLogPanel.showSuppressLimitBalloon() {
    if (ApplicationManager.getApplication().isUnitTestMode) return
    runCatching {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(SUPPRESS_NOTIFICATION_GROUP_ID)
            .createNotification(
                MpSqlVisualizerBundle.message("log.suppress.added.title"),
                MpSqlVisualizerBundle.message(
                    "log.suppress.limit",
                    SqlSuppressRuleManager.MAX_RULES.toString(),
                ),
                NotificationType.WARNING,
            )
            .notify(project)
    }
}

/** 发送到 SQL → Java 转换（第三 tab）。T-RecentExecMenu O-119：落点收口 [openEntryInTab]（弹框右键同用）。 */
