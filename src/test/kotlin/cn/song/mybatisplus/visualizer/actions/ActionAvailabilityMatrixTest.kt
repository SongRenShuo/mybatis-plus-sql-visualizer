package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * 动作可用性矩阵（T-TestGateGems W3，2026-10-24）。
 *
 * 来源：magpie `internal/gui/panel_options_test.go:14-38` 的思路——**把「界面外观/可用性」当成可断言的
 * 参数**，不起窗口。magpie 那边只能靠浏览器套件覆盖「按钮在什么上下文该灰」；IntelliJ 的
 * `AnAction.update(e)` + `Presentation` 是天然 headless 面，成本低一个数量级。
 *
 * 为什么要有这条：单点测试（`ConvertToSqlActionTest` 等）已覆盖两个转换动作，但**其余入口没有
 * 「不该出现时必须消失」的断言**——典型缺陷是右键菜单里某个动作在纯文本文件上仍然可点，
 * 点了才报错。本条用统一矩阵兜住「无关上下文一律不可见」这一侧。
 *
 * 口径：每个受测动作在「空上下文（无文件、无编辑器）」下 `update()` 后必须**不可用**
 * （`isEnabled == false`）。这里断的是「可用性」而不是「可见性」——本仓的选中类动作沿用平台惯用法
 * 「始终可见、按上下文灰置」（`SelectedTableSqlTemplateAction.update` 只设 `isEnabled`），
 * 所以「菜单里不出现」不是它们的设计；真实不变量是**点了不会有反应的那一侧必须灰**。
 * 这是最低成本的不变量，不涉及每个动作各自的正向条件（那些由各自单测覆盖）。
 */
class ActionAvailabilityMatrixTest : BasePlatformTestCase() {

    /** 空上下文：只有 project，没有 PSI_FILE / EDITOR / 选中元素。 */
    private fun emptyEvent(): AnActionEvent {
        val dc = MapDataContext()
        dc.put(CommonDataKeys.PROJECT.name, project)
        return AnActionEvent(null, dc, ActionPlaces.UNKNOWN, Presentation(), ActionManager.getInstance(), 0)
    }

    /**
     * 受测动作清单：plugin.xml 里声明的、面向「选中内容」的动作。
     * 不含始终可用的入口（设置页、反馈、帮助、日志面板开关），那些在任何上下文都该在。
     */
    private val selectionActions: List<Pair<String, () -> AnAction>> = listOf(
        "ConvertToSql" to { ConvertToSqlAction() },
        "ConvertSqlToJava" to { ConvertSqlToJavaAction() },
        "CountSelectedTable" to { CountSelectedTableAction() },
        "SampleSelectedTable" to { SampleSelectedTableAction() },
        "ExplainSelectedTable" to { ExplainSelectedTableAction() },
        "GenerateCrudSqlFromEntity" to { GenerateCrudSqlFromEntityAction() },
    )

    fun testSelectionActionsAreDisabledWithoutASelection() {
        val wronglyEnabled = mutableListOf<String>()
        for ((name, factory) in selectionActions) {
            val e = emptyEvent()
            factory().update(e)
            if (e.presentation.isEnabled) {
                wronglyEnabled += "$name (isEnabled=true)"
            }
        }
        assertTrue(
            "以下动作在空上下文（无文件/无编辑器）下仍然可用——用户点了不会有任何反应，也看不出为什么：\n" +
                wronglyEnabled.joinToString("\n"),
            wronglyEnabled.isEmpty(),
        )
    }

    /**
     * 自证：矩阵确实跑到了全部受测动作，且 `update()` 真的改动了 Presentation。
     *
     * 注意两点（都是实测踩出来的）：
     *  ① 每个动作必须用**独立事件**——共用同一个 Presentation 会让上一个动作的结论污染下一个；
     *  ② 不能只盯 `isVisible`：`SelectedTableSqlTemplateAction` 一类动作只设 `isEnabled`（动作始终可见、
     *     按上下文灰置，这是平台惯用法）。所以自证要断言「可见性或可用性至少有一个被改动了」。
     */
    fun testMatrixCoversEveryDeclaredAction() {
        assertTrue("受测动作清单为空，矩阵未生效", selectionActions.size >= 6)
        for ((name, factory) in selectionActions) {
            val e = emptyEvent()
            val visibleBefore = e.presentation.isVisible
            val enabledBefore = e.presentation.isEnabled
            factory().update(e)
            assertTrue(
                "$name 的 update() 既没改可见性也没改可用性（矩阵断言对它是空转）",
                e.presentation.isVisible != visibleBefore || e.presentation.isEnabled != enabledBefore,
            )
        }
    }
}
