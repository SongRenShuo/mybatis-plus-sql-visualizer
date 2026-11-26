package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.daemon.impl.LineMarkersPass
import com.intellij.codeInsight.daemon.impl.CollectHighlightsUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.xml.XMLLanguage
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.NotNullFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Function
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon

/**
 * T-MapperNavYieldBySide 复现：让位开关 × 同类插件共存时的 Java 方法行跳转图标枚数，
 * 以及「`language="UAST"` 的 lineMarkerProvider 会投递到 Java 文件」这一平台机制。
 *
 * ## 用户可见行为
 * 报障时（真机 2026.3 / IU-263.7307.200）：Mapper 接口方法行让位勾选 = 1 枚、取消 = 2 枚（两家图标同款）。
 * T-MapperNavYieldJavaSideKeep 起 **Java 侧不再让位**，本类用例钉住现行语义：让位开/关均为 2 枚
 * （我方 1 + 同类插件 1）；让位只作用于 XML 语句行（见 `MapperNavigationGutterGateTest`）。
 *
 * ## 复现方式（非 GUI）
 * - 「同类插件」用测试内 [SyntheticUastPeerProvider] 模拟：按反编译结论把 bean 注册到
 *   `com.intellij.codeInsight.lineMarkerProvider` 的 **`language="UAST"`** 键上；
 *   我方 [MapperJavaToXmlLineMarkerProvider] 用平台已加载的 plugin.xml bean（JAVA 键）。
 * - 让位门：`MyBatisCompetitorGate.resetForTest()` + `probe = { true/false }`；
 *   用户开关走 `PluginSettings.State.mapperNavYieldToCodeHelper`。
 *
 * ## 平台能力边界（243 实测，决定取证路径）
 * `LineMarkersPass.queryLineMarkers(PsiFile, Document)` 虽为 public static，但内部断言必须处于
 * `DaemonProgressIndicator` 且 indicator 内已存 HighlightingSession（`GlobalInspectionContextBase
 * .assertUnderDaemonProgress`），light fixture 直接调用抛 IllegalStateException——首轮实测。
 * 故改用两条互补路径：
 * ① **provider 解析面**：[LineMarkersPass.getMarkerProviders] 是 daemon 收集 provider 的同一入口
 *    （内含 `LanguageExtension.buildExtensions` 对 meta language key 的并集）。断言 UAST 键的 bean
 *    出现在 Java 文件的集合里，且不出现在 XML 文件的集合里（若按 ANY 注册则 XML 侧也会出现）。
 * ② **真实调用面**：`myFixture.doHighlighting()` 跑平台 daemon 高亮管线，断言 peer 慢趟确实收到
 *    Java PSI（计数器）；再按平台 `queryProviders` 同形手工分发，钉住方法行枚数。
 *
 * ## 平台机制结论（243 源码实证）
 * `LanguageExtension.buildExtensions(String, Language)` 把 `LanguageUtil.matchingMetaLanguages(language)`
 * 的 key 并入同批查询：Java 文件收集 provider 时 key 集合是 `{JAVA, UAST, ...}`，
 * 故 `language="UAST"` 的 bean 会被 Java 文件拿到并调用。
 *
 * ## 版本口径
 * 测试基座 = 243（`intellijIdeaUltimate("2024.3.7.1")`）。真机报障版本 263 的「让位开=1、关=2」结论
 * 来自反编译 + 用户截图取证，**不在本测试覆盖范围内**；本类只证明 243 基座下机制与计数成立。
 */
class MapperNavCoexistUastReproTest : BasePlatformTestCase() {

    private val mapperXml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.demo.mapper.UserMapper">
    <select id="selectById" resultType="map">SELECT * FROM t_user WHERE id = #{id}</select>
</mapper>
"""

    private val javaFixture = """
        package com.demo.mapper;

        interface UserMapper {
            void selectById();
        }
    """.trimIndent()

    /** 模拟同类插件（MPJ）的箭头 provider：注册在 UAST 键上，给接口方法名 leaf 出一枚 marker。 */
    private class SyntheticUastPeerProvider : RelatedItemLineMarkerProvider() {
        /** 慢趟收到的元素总数（含非 PsiIdentifier 元素）——用来证「平台确实把 Java PSI 喂进来了」。 */
        var collectSlowInvocations = 0

        /** 命中「Mapper 接口方法名 leaf」的次数——用来区分「被调用」与「只被收集」。 */
        var interfaceMethodLeafVisits = 0

        override fun getName(): String = "SyntheticUastPeerProvider"

        override fun collectNavigationMarkers(
            element: PsiElement,
            result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
        ) {
            collectSlowInvocations++
            if (element !is PsiIdentifier) return
            val method = element.parent as? PsiMethod ?: return
            if (method.nameIdentifier !== element) return
            if (method.containingClass?.isInterface != true) return
            interfaceMethodLeafVisits++
            result.add(
                RelatedItemLineMarkerInfo(
                    element,
                    element.textRange,
                    PEER_ICON,
                    Function { _ -> "synthetic peer marker" },
                    null,
                    GutterIconRenderer.Alignment.CENTER,
                    NotNullFactory { emptyList<GotoRelatedItem>() },
                ),
            )
        }
    }

    private lateinit var peer: SyntheticUastPeerProvider
    private lateinit var originalState: PluginSettings.State

    override fun setUp() {
        super.setUp()
        MyBatisCompetitorGate.resetForTest()
        MapperXmlToJavaLineMarkerProvider.resetResolvedCacheForTest()
        originalState = PluginSettings.getInstance().state
        // 夹具：同 namespace 的 mapper XML + 接口。XML 在 addFileToProject 后才进索引。
        myFixture.addFileToProject("src/main/resources/mapper/UserMapper.xml", mapperXml)
        // 同类插件（UAST 键）只能由本用例登记。
        peer = SyntheticUastPeerProvider()
        registerLineMarkerEp("UAST", peer)
        ensureSingleOwnProviderOnEp()
        LineMarkerProviders.getInstance().clearCache(XMLLanguage.INSTANCE)
    }

    /**
     * 确保 Java 侧恰有 1 个 [MapperJavaToXmlLineMarkerProvider] 实例。
     *
     * 实测（243 / IPGP 测试任务）：本插件 plugin.xml **确实被加载**——直接再程序化注册会得到两个实例，
     * 方法行出现两枚本插件图标（本轮首跑读数：让位关 own=2）。故先查平台已解析集合：
     * 有则用平台 bean（顺带证明测试读的是真实 plugin.xml 声明），无则按声明同形补注册；
     * 最后钉住「恰 1」——多一个就会让枚数断言失去意义。
     */
    private fun ensureSingleOwnProviderOnEp() {
        if (ownProviderInstances().isEmpty()) {
            registerLineMarkerEp("JAVA", MapperJavaToXmlLineMarkerProvider())
        }
        assertEquals(
            "Java 侧 MapperJavaToXmlLineMarkerProvider 应恰 1 个实例（重复会让方法行多画图标）",
            1,
            ownProviderInstances().size,
        )
    }

    private fun ownProviderInstances(): List<MapperJavaToXmlLineMarkerProvider> {
        LineMarkerProviders.getInstance().clearCache(JavaLanguage.INSTANCE)
        return LineMarkersPass.getMarkerProviders(JavaLanguage.INSTANCE, project)
            .filterIsInstance<MapperJavaToXmlLineMarkerProvider>()
    }

    override fun tearDown() {
        try {
            PluginSettings.getInstance().loadState(originalState)
            MyBatisCompetitorGate.resetForTest()
            MapperXmlToJavaLineMarkerProvider.resetResolvedCacheForTest()
        } finally {
            super.tearDown()
        }
    }

    /** 程序化注册 `codeInsight.lineMarkerProvider` bean（与 plugin.xml 声明同形态：language + 实例）。 */
    private fun registerLineMarkerEp(language: String, instance: LineMarkerProvider) {
        val coreDescriptor = PluginManagerCore.getPlugin(PluginId.getId("com.intellij"))
        val bean = LanguageExtensionPoint(language, instance)
        // BaseKeyedLazyInstance.getInstance 对 pluginDescriptor 参数有 NotNull 断言（仓内既有实证），
        // 挂核心插件描述符；实例构造路径不会再实例化，仅需非空。
        if (coreDescriptor != null) bean.setPluginDescriptor(coreDescriptor)
        LineMarkerProviders.EP_NAME.point.registerExtension(bean, testRootDisposable)
    }

    /**
     * 按平台 `LineMarkersPass.queryProviders` 同形收集：provider 列表取平台真实解析结果
     * [LineMarkersPass.getMarkerProviders]（该函数即 daemon 收集 provider 的入口，内含
     * LanguageExtension 对 meta language key 的并集），对文件全部 PSI 元素各调一次快速判定、
     * 再整批调一次慢趟。**只分发本用例登记的两个 provider**，避免无关平台 provider 的 marker 串号。
     *
     * 备注：243 的 `LineMarkersPass.queryLineMarkers(PsiFile, Document)` 断言必须处于
     * `DaemonProgressIndicator` 且该 indicator 内已存 HighlightingSession
     * （`GlobalInspectionContextBase.assertUnderDaemonProgress` +「No HighlightingSession stored」），
     * light fixture 内不可直接调用——首轮实测抛 IllegalStateException，故改走本函数。
     */
    private fun collectMethodLineMarkers(file: PsiFile, language: Language): List<LineMarkerInfo<*>> {
        val providers = LineMarkersPass.getMarkerProviders(language, project)
            .filter {
                it is MapperJavaToXmlLineMarkerProvider ||
                    it is MapperMethodPreviewLineMarkerProvider ||
                    it === peer
            }
        val elements = CollectHighlightsUtil.getElementsInRange(file, 0, file.textLength)
        val result = mutableListOf<LineMarkerInfo<*>>()
        for (provider in providers) {
            for (element in elements) provider.getLineMarkerInfo(element)?.let(result::add)
            provider.collectSlowLineMarkers(ArrayList(elements), result)
        }
        return result.filter { info ->
            val el = info.element
            // T-MapperPreviewOrder：预览图标的锚点是「方法名同行前一叶」（offset 小一档），
            // 不再落在方法名 leaf 上——按 tooltip 单独保留，避免该行预览被过滤掉。
            (el is PsiIdentifier && (el.parent as? PsiMethod)?.nameIdentifier === el) ||
                info.lineMarkerTooltip == MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.tooltip")
        }
    }

    private fun ownIconCount(markers: List<LineMarkerInfo<*>>): Int =
        markers.count { it.getIcon() === MpvsIcons.MapperMethod }

    /** T-MapperMethodSqlPreview：方法行 SQL 预览图标（锚点与跳转箭头同 element，只能按 tooltip 区分）。 */
    private fun previewIconCount(markers: List<LineMarkerInfo<*>>): Int =
        markers.count { it.lineMarkerTooltip == MpSqlVisualizerBundle.message("gutter.mapperMethodPreview.tooltip") }

    private fun peerIconCount(markers: List<LineMarkerInfo<*>>): Int =
        markers.count { it.getIcon() === PEER_ICON }

    /** 准备 Java 夹具文件（不落盘，configureByText 建在测试项目里）。 */
    private fun configureJavaFixture() {
        myFixture.configureByText("UserMapper.java", javaFixture)
    }

    // ---------- ① UAST 投递机制 ----------

    fun testUastProvider被Java文件收集_而XML文件不收集() {
        val javaProviders = LineMarkersPass.getMarkerProviders(JavaLanguage.INSTANCE, project)
        assertTrue(
            "language=\"UAST\" 的 provider 必须出现在 Java 文件的 provider 集合里（meta language key 并入）",
            javaProviders.any { it === peer },
        )
        val xmlProviders = LineMarkersPass.getMarkerProviders(XMLLanguage.INSTANCE, project)
        assertTrue(
            "对照组：XML 不匹配 UAST meta language，peer 不应出现——证明投递经 UAST 匹配，不是按 ANY 注册",
            xmlProviders.none { it === peer },
        )
    }

    fun testUastProvider收到Java元素并产出marker() {
        configureJavaFixture()
        // 门面固定为「竞品不在位」：本用例只考察投递机制，计数不掺让位门
        armPeer(false)
        val beforeLeaf = peer.interfaceMethodLeafVisits
        val markers = collectMethodLineMarkers(myFixture.file, JavaLanguage.INSTANCE)
        assertTrue("UAST provider 的慢趟应收到 Java 文件元素", peer.collectSlowInvocations > 0)
        assertTrue(
            "UAST provider 应在本接口方法名 leaf 上被实际调用（收到 Java PSI 元素）",
            peer.interfaceMethodLeafVisits > beforeLeaf,
        )
        assertEquals("模拟同类插件在该方法行应恰出 1 枚 marker", 1, peerIconCount(markers))
        assertEquals("T-MapperMethodSqlPreview：我方方法行应有 1 枚预览图标", 1, previewIconCount(markers))
        assertEquals("该行应共 3 枚 marker（我方预览 1 + 我方箭头 1 + 模拟同类插件 1）", 3, markers.size)
    }

    /** 真实 daemon 高亮管线（非手工分发）是否也会把 Java 元素喂给 UAST provider。 */
    fun test平台daemon高亮管线把Java元素喂给UastProvider() {
        configureJavaFixture()
        myFixture.doHighlighting()
        assertTrue(
            "平台 daemon 的 LineMarkersPass 应在 Java 文件上调用 UAST provider（Light 测试也跑真实高亮管线）",
            peer.interfaceMethodLeafVisits > 0,
        )
    }

    // ---------- ② 让位开/关的枚数复现 ----------

fun test竞品在位且让位关方法行共3枚图标() {
        withYieldSwitch(false) {
            armPeer(true)
            configureJavaFixture()
            val markers = collectMethodLineMarkers(myFixture.file, JavaLanguage.INSTANCE)
            assertEquals("让位关：我方应保留跳转箭头", 1, ownIconCount(markers))
            assertEquals("让位关：模拟同类插件也应出图标", 1, peerIconCount(markers))
            assertEquals("让位关：我方预览图标同在", 1, previewIconCount(markers))
            assertEquals("让位关：方法行应共 3 枚（预览 + 我方箭头 + 对方箭头）", 3, markers.size)
        }
    }

    fun test竞品在位且让位开方法行只剩预览与对方箭头() {
        withYieldSwitch(true) {
            armPeer(true)
            configureJavaFixture()
            val markers = collectMethodLineMarkers(myFixture.file, JavaLanguage.INSTANCE)
            // T-MapperMethodSqlPreview（2026-11-25）：让位只让**跳转**，方法行预览图标保留——
            // 该行不再是空的（此前只装 MPJ 时让位后 Java 行 0 枚）。
            assertEquals("让位开：跳转箭头应让位（0 枚）", 0, ownIconCount(markers))
            assertEquals("让位开：同类插件仍出箭头", 1, peerIconCount(markers))
            assertEquals("让位开：我方预览图标保留 1 枚", 1, previewIconCount(markers))
            assertEquals("让位开：方法行共 2 枚（我方预览 + 对方箭头）", 2, markers.size)
        }
    }

    /** 对照：让位开但竞品探测不在位——我方必须保留（证明门判插件在位，不判 marker 是否真的出现）。 */
    fun test竞品不在位且让位开我方仍保留图标() {
        withYieldSwitch(true) {
            armPeer(false)
            configureJavaFixture()
            val markers = collectMethodLineMarkers(myFixture.file, JavaLanguage.INSTANCE)
            assertEquals("竞品不在位：我方不得让位", 1, ownIconCount(markers))
            assertEquals("我方预览图标同在", 1, previewIconCount(markers))
            assertEquals("对照侧仍模拟出 1 枚同类插件 marker（证明门不读 marker）", 1, peerIconCount(markers))
            assertEquals("共 3 枚：门判的是插件启停而非运行期 marker 存在性", 3, markers.size)
        }
    }

    private fun armPeer(active: Boolean) {
        MyBatisCompetitorGate.resetForTest()
        MyBatisCompetitorGate.probe = { active }
    }

    /** 临时设置让位开关执行 [block]（结束后恢复 setUp 记下的原 State）。 */
    private fun withYieldSwitch(yield: Boolean, block: () -> Unit) {
        val settings = PluginSettings.getInstance()
        try {
            settings.loadState(originalState.copy(mapperNavYieldToCodeHelper = yield))
            block()
        } finally {
            settings.loadState(originalState)
        }
    }

    private companion object {
        /** 唯一身份的自定义图标：避免与平台其他 provider 共用图标导致计数串号。 */
        val PEER_ICON: Icon = object : Icon {
            override fun getIconHeight(): Int = 12
            override fun getIconWidth(): Int = 12
            override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {}
        }
    }
}