package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * T-EmbeddedConsoleExecRestore 券 C1：面板内 Ctrl+Enter 执行快捷键的绑定守卫。
 *
 * 背景：T-SAF-04（D1=A）移除了 `CommonDataKeys.EDITOR` / `SessionClientHolder.CLIENT_KEY`，
 * 平台原生 ▶ / Ctrl+Enter 因此恒禁用。本任务不恢复原生入口（那会重开「低信任文本绕过插件
 * 确认链」的缺口），改为把 Ctrl+Enter 接到面板既有的 [SqlPreviewPanel.executeSql] 入口——
 * 同一份控制台文本、同一套门禁，只多一个触发方式。
 *
 * 本类钉住三件事：
 * 1. 键集是 `Ctrl+Enter` + `Meta+Enter`（mac 习惯键）；
 * 2. 面板 init 真的把该键集注册到了执行动作上（不是定义了却没挂）；
 * 3. 动作触发时确实进到本动作体（计数缝），且不抛异常。
 *
 * 桶位：`BasePlatformTestCase` 属平台 `test` 桶，**不在 `testFast`**——定向跑用
 * `--tests "cn.song.mybatisplus.visualizer.ui.SqlPreviewPanelExecuteShortcutTest"`。
 */
class SqlPreviewPanelExecuteShortcutTest : BasePlatformTestCase() {

    private fun keyStrokesOf(panel: SqlPreviewPanel): List<KeyStroke> =
        panel.executeShortcutAction.shortcutSet.shortcuts
            .filterIsInstance<KeyboardShortcut>()
            .map { it.firstKeyStroke }

    fun test快捷键集合为CtrlEnter与MetaEnter() {
        val expected = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK),
        )
        assertEquals(
            "执行快捷键必须恰好是 Ctrl+Enter 与 Meta+Enter（顺序固定，避免误加别的键）",
            expected,
            executeShortcuts().filterIsInstance<KeyboardShortcut>().map { it.firstKeyStroke },
        )
    }

    fun test面板init把执行快捷键注册到动作上() {
        val panel = SqlPreviewPanel(project)
        assertEquals(
            "面板构造后执行动作必须已挂上 Ctrl+Enter / Meta+Enter（注册点在 init，漏挂即红）",
            listOf(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK),
            ),
            keyStrokesOf(panel),
        )
        panel.clear()
    }

    fun test动作触发进本动作体且不抛异常() {
        val panel = SqlPreviewPanel(project)
        // 空 SQL：executeSql 内部早退（不连库），只验「按键落到了本动作」这一层
        panel.showResult(result(sql = "   ", compact = "   "))
        assertEquals(0, panel.executeShortcutInvocations)
        panel.executeShortcutAction.actionPerformed(
            AnActionEvent.createFromAnAction(
                panel.executeShortcutAction,
                null,
                com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
                com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT,
            ),
        )
        assertEquals(
            "触发一次必须记一次（证明按键走的是插件执行动作，不是平台原生链）",
            1,
            panel.executeShortcutInvocations,
        )
        panel.clear()
    }

    private fun result(sql: String, compact: String): PreviewResult =
        PreviewResult(
            sql = sql,
            compactSql = compact,
            unresolvedParams = emptyList(),
            paramTable = null,
            wrapperType = cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY,
            tableName = "tbl",
            columns = listOf("id"),
            candidateTables = emptyList(),
        )
}
