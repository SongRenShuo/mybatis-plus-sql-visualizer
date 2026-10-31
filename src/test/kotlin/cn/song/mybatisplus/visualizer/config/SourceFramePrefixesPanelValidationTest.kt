package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * T-PrefixValidate 面板装配测试：校验结果行与「一键修复」按钮在真实组件树中的行为——
 * 输入非法/冲突文本 → 结果行与按钮露出；点击修复 → 两栏回写规范化文本、问题清零后整行隐藏。
 * （判定逻辑本身在 [SourceFramePrefixesValidatorTest] 纯函数覆盖，本类只锁 UI 装配与回写链路。）
 */
class SourceFramePrefixesPanelValidationTest : BasePlatformTestCase() {

    /** 承载「源码位置捕获」分组的真实子页（与用户设置页同一路径）。 */
    private fun newConfigurable(): SectionConfigurable = SqlLogConfigurable()

    private fun <T : JComponent> findAll(container: java.awt.Container, type: Class<T>, out: MutableList<T>) {
        for (c in container.components) {
            when {
                type.isInstance(c) -> out.add(type.cast(c))
                c is java.awt.Container -> findAll(c, type, out)
            }
        }
    }

    private fun findFixButton(root: java.awt.Container): JButton? {
        for (c in root.components) {
            when {
                c is JButton && c.text == MpSqlVisualizerBundle.message("settings.sourceFramePrefixes.fix") -> return c
                c is java.awt.Container -> findFixButton(c)?.let { return it }
            }
        }
        return null
    }

    private fun isInTree(root: java.awt.Container, target: JComponent): Boolean {
        if (root === target) return true
        for (c in root.components) {
            if (c is java.awt.Container && isInTree(c, target)) return true
        }
        return false
    }

    fun test校验行在组件树中且默认隐藏() {
        val configurable = newConfigurable()
        configurable.createComponent()
        val root = configurable.panelForTest().getRoot()
        val button = findFixButton(root)
        assertNotNull("一键修复按钮应在组件树中（隐藏态）", button)
        assertTrue("无问题时按钮应隐藏", !checkNotNull(button).isVisible)
    }

    fun test非法与冲突输入_结果行露出_一键修复回写并隐藏() {
        val configurable = newConfigurable()
        configurable.createComponent()
        val panel = configurable.panelForTest()
        val root = panel.getRoot()

        // T-MagpieGems W2 起 SQL 日志页有多个 JBTextArea（脱敏白名单 3 行域先于本组装配），
        // 按「树里前两个 JTextArea」定位会取错控件（往白名单域写文本 → 校验不触发 → 按钮不露），
        // 直接引用面板字段，与装配顺序解耦。
        val skipField = panel.frameSkipPrefixesField
        val bizField = panel.bizJarPrefixesField
        assertTrue("两栏前缀输入框应都在组件树中", isInTree(root, skipField) && isInTree(root, bizField))

        // 非法行（bad line）+ 跨栏冲突（com.corp. 两栏都登）
        skipField.text = "com.corp.\nbad line"
        bizField.text = "com.corp.\n com.biz. "

        val button = checkNotNull(findFixButton(root))
        assertTrue("有问题时一键修复按钮应露出", button.isVisible)

        // 结果行文案可见（带告警图标的 JLabel，多问题时显示首条＝非法行提示，全文进 tooltip）
        val labels = mutableListOf<JLabel>()
        findAll(root, JLabel::class.java, labels)
        val hint = labels.firstOrNull { it.icon != null && it.isVisible && it.text?.contains("bad line") == true }
        assertNotNull("校验结果行应带出问题文案（首条＝非法行提示）", hint)
        assertTrue(
            "悬停 tooltip 应含全部问题（含跨栏冲突前缀）: ${hint!!.toolTipText}",
            hint.toolTipText?.contains("com.corp.") == true,
        )

        // 一键修复：非法行剔除、同栏去重、跨栏冲突从 BIZ 侧剔除（skip 优先）
        button.doClick()
        assertEquals("SKIP 栏应回写规范化文本", "com.corp.", skipField.text)
        assertEquals("BIZ 栏应剔除与 SKIP 冲突的条目", "com.biz.", bizField.text)
        assertTrue("修复后无问题，按钮应隐藏", !button.isVisible)
        assertTrue("修复后结果行应隐藏", isInTree(root, button))
    }
}
