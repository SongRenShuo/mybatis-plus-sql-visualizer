package com.example.thirdparty.fakeprobe

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

/**
 * T-XmlSynHi D68 / T-DevAcceptFix ④ 探针测试专用假注入器：模拟一个**占满整宿主**的第三方注入器
 * ——20261006 验收后**并入 per-host 探测**（在位≠覆盖：真 startInjecting 才让位）。只供
 * [cn.song.mybatisplus.visualizer.ui.inject.XmlSynHiGatePlatformProbeTest] 经
 * `XmlSynHiGate.foreignInjectorsWouldInject` 缝直注清单，不注册任何 EP。
 *
 * 包名故意用 `com.example.thirdparty.`（中性占位），不取任何真实插件的包名——夹具只需表达
 * 「非本插件族的注入器」这一身份，生产判定只看 `SELF_PACKAGE_PREFIX`。
 */
class FakeThirdPartyInjector(
    private val fedTypes: List<Class<out PsiElement>>,
    private val start: Boolean = true,
) : MultiHostInjector {

    var consulted: Int = 0
        private set

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        consulted++
        if (!start) return
        val host = context as? PsiLanguageInjectionHost ?: return
        registrar.startInjecting(PlainTextLanguage.INSTANCE)
            .addPlace(null, null, host, TextRange(0, host.textLength))
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = fedTypes
}
