package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 帮助入口「JCEF / 系统浏览器回退」UI 测试（F-44-6 续 · 用户要求「整个插件 UI 用例」）：
 *
 * 用户点名「帮助 JCEF / 系统浏览器回退路径最弱」。帮助动作两条路径：
 *  - 主路径：JCEF 可用 → 内嵌浏览器 loadHTML 渲染本地 `html/marketplace-custom-page.html`；
 *  - 回退路径：JCEF 不可用 / 资源缺失 / 异常 → `BrowserUtil.browse` 系统浏览器打开在线文档。
 * 本测试在 headless 下验证：
 *  - 本地帮助 HTML 资源存在且非空、含文档结构（主路径内容完整，否则会强制走最弱回退）；
 *  - 帮助动作入口展示正确（? 图标 + 文案）；
 *  - 触发回退路径（headless JCEF 通常不可用）时本插件代码不崩溃（仅可能由环境 BrowserUtil 抛异常）。
 */
class HelpUiTest : BasePlatformTestCase() {

    fun test本地帮助HTML资源存在且含文档结构() {
        // 与主路径 loadText("html", "marketplace-custom-page.html") 同路径
        val stream = javaClass.classLoader.getResourceAsStream("html/marketplace-custom-page.html")
        assertNotNull("帮助 HTML 资源应打包进插件（主路径内容）", stream)
        val content = stream!!.bufferedReader().use { it.readText() }
        assertTrue("帮助 HTML 不应为空", content.isNotBlank())
        assertTrue(
            "帮助 HTML 应含文档结构（<html> 或 <!DOCTYPE>，否则 JCEF 渲染异常）",
            content.contains("<html", ignoreCase = true) || content.contains("<!DOCTYPE", ignoreCase = true),
        )
    }

    fun test帮助动作入口展示文案与图标() {
        val panel = SqlLogPanel(project, SqlLogManager(project))
        try {
            val children = panel.toolbarGroup.getChildren(null).toList()
            val events = children.map {
                AnActionEvent.createFromAnAction(it, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT)
            }
            children.forEachIndexed { i, a -> a.update(events[i]) }
            val helpIdx = events.indexOfFirst { it.presentation.icon === MpvsIcons.Help }
            assertTrue("工具栏组应含帮助入口", helpIdx >= 0)
            assertEquals(
                "帮助入口文案应与 bundle 一致",
                MpSqlVisualizerBundle.message("log.panel.help"),
                events[helpIdx].presentation.text,
            )
        } finally {
            panel.dispose()
        }
    }

    fun test帮助动作回退路径_本插件代码不崩溃() {
        val panel = SqlLogPanel(project, SqlLogManager(project))
        try {
            val children = panel.toolbarGroup.getChildren(null).toList()
            val events = children.map {
                AnActionEvent.createFromAnAction(it, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT)
            }
            children.forEachIndexed { i, a -> a.update(events[i]) }
            val helpAction = children[events.indexOfFirst { it.presentation.icon === MpvsIcons.Help }]
            // headless 下 JBCefApp.isSupported() 通常为 false → 走回退分支；
            // 回退分支在单测模式短路（不真调 BrowserUtil——曾致定时回归任务弹用户系统浏览器，2026-09-11 修复），
            // 断言本插件代码不因此崩溃
            try {
                helpAction.actionPerformed(
                    AnActionEvent.createFromAnAction(helpAction, null, ActionPlaces.TOOLBAR, DataContext.EMPTY_CONTEXT),
                )
            } catch (t: Throwable) {
                val ourFault = t.stackTrace.any { it.className.startsWith("cn.song.mybatisplus.visualizer.log.SqlLogPanel") }
                assertFalse("帮助回退路径不应因本插件代码崩溃: ${t.message}", ourFault)
            }
        } finally {
            panel.dispose()
        }
    }
}
