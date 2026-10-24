package com.example.thirdparty.fakeprobe

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * T-XmlGap W1 探针测试专用假注入器：**只占参数占位区间**（模拟一条按区间注入的第三方链——
 * `#{}` 注入器按区间 `startInjecting`+`addPlace`；`${}` 注入器只报 `${` 之后到 `}` 之前）。
 *
 * 与 [FakeThirdPartyInjector]（占满整宿主）的区别正是 W1 的判别点：占满 → 我方零注入（等价旧让位）；
 * 只占参数区间 → 我方为**余下区间**着色（旧形态在这里整宿主放弃）。
 *
 * 只供 W1 用例经 `XmlSynHiGate.foreignInjectorsOccupiedRanges` 缝直注清单，或经区间缝喂入，
 * 不注册任何 EP。
 */
class FakeParamRangeInjector(
    private val fedTypes: List<Class<out PsiElement>>,
    private val params: List<Regex> = listOf(Regex("""#\{[^}]*}"""), Regex("""\$\{[^}]*}""")),
    /** true = 只报参数内部区间（不含 `${`/`#{` 与 `}`），模拟只报内层的注入形态。 */
    private val innerOnly: Boolean = false,
) : MultiHostInjector {

    /** 被咨询计数（喂入契约预筛不命中则不增）。 */
    var consulted: Int = 0
        private set

    /** 最近一次咨询实际占用的区间（宿主坐标系）。 */
    val occupied: List<TextRange> get() = lastOccupied
    private var lastOccupied: List<TextRange> = emptyList()

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        consulted++
        val host = context as? PsiLanguageInjectionHost ?: return
        val text = host.text
        val ranges = params.flatMap { p ->
            p.findAll(text).map { m ->
                if (innerOnly) TextRange(m.range.first + 2, m.range.last) else TextRange(m.range.first, m.range.last + 1)
            }.toList()
        }.filter { !it.isEmpty }
        if (ranges.isEmpty()) return
        lastOccupied = ranges
        val session = registrar.startInjecting(PlainTextLanguage.INSTANCE)
        for (range in ranges) session.addPlace(if (innerOnly) "%{" else "", "", host, range)
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = fedTypes
}
