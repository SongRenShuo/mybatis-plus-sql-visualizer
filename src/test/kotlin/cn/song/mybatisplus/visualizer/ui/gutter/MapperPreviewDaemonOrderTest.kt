package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-MapperPreviewOrder 真实渲染顺序复现（非 GUI）：直接读 daemon 的 line markers 列表
 * （[DaemonCodeAnalyzerImpl.getLineMarkers]，即 `EditorGutterComponentImpl` 构建 gutter 缓存时
 * 消费的这一份），断言同一方法行我方两枚的顺序为「预览在前、跳转在后」，且在**多次高亮**
 * （模拟首次渲染 → daemon 重算）之间保持稳定。
 *
 * 背景：gutter 缓存按 highlighter 的 startOffset 归行并排序（`processRangeHighlighters`），
 * 同 offset 的两枚其先后受 daemon 增量复用 highlighter 影响会翻转（用户实测「首次在右、
 * 过一会到左」）。预览图标因此改锚「方法名同行前一叶」（offset 小一档），本类钉住：
 * ① 真实 daemon 列表里预览在前；② 第二轮高亮后仍是预览在前（不随 pass 翻转）。
 */
class MapperPreviewDaemonOrderTest : BasePlatformTestCase() {

    private val previewTip: String
        get() = MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.tooltip")
    private val navTip: String
        get() = MpSqlVisualizerBundle.message("gutter.mapperMethod.tooltip")

    private fun configureFixture() {
        myFixture.configureByText(
            "UserMapper.java",
            """
            package com.demo;
            public interface UserMapper {
                int count();
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "mapper/UserMapper.xml",
            "<mapper namespace=\"com.demo.UserMapper\"><select id=\"count\">SELECT 1</select></mapper>",
        )
    }

    /** 该行我方两枚的 tooltip 顺序（按 element 所在行过滤，忽略平台自带的 marker）。 */
    private fun oursInMethodRow(): List<String> {
        val document = myFixture.editor.document
        val methodName = PsiTreeUtil.collectElementsOfType(myFixture.file, com.intellij.psi.PsiIdentifier::class.java)
            .first { it.text == "count" && (it.parent as? PsiMethod)?.nameIdentifier === it }
        val line = document.getLineNumber(methodName.textRange.startOffset)
        return DaemonCodeAnalyzerImpl.getLineMarkers(document, project)
            .filter { info ->
                val el = info.element ?: return@filter false
                el.isValid && document.getLineNumber(el.textRange.startOffset) == line &&
                    (info.lineMarkerTooltip == previewTip || info.lineMarkerTooltip == navTip)
            }
            .map { it.lineMarkerTooltip ?: "" }
    }

    fun test真实daemon列表_方法行两枚顺序_预览在前且跨轮稳定() {
        configureFixture()
        myFixture.doHighlighting()
        val first = oursInMethodRow()
        assertEquals("方法行应恰我方两枚（预览 + 跳转）；实际=$first", 2, first.size)
        assertEquals("真实 daemon 列表里预览必须在左（实际=$first）", previewTip, first[0])
        assertEquals("跳转箭头在右（实际=$first）", navTip, first[1])

        // 第二轮高亮（模拟 daemon 重算 / 图标重绘）：顺序不得翻转
        myFixture.doHighlighting()
        val second = oursInMethodRow()
        assertEquals("第二轮高亮后仍应是预览在前（实际=$second）", previewTip, second.firstOrNull())
        assertEquals("两轮顺序必须一致（first=$first second=$second）", first, second)
    }
}