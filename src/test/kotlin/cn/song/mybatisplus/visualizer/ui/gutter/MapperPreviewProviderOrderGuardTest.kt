package cn.song.mybatisplus.visualizer.ui.gutter

import com.intellij.codeInsight.daemon.impl.LineMarkersPass
import com.intellij.lang.java.JavaLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-MapperPreviewOrder（2026-11-26 用户报障「预览图标首次在右、过一会到左」）：
 * 同一方法行的两枚图标顺序由 **provider 收集顺序**决定——平台慢趟
 * （`LineMarkersPass.queryProviders`）外层遍历 providers、每个 provider 的
 * `collectSlowLineMarkers` 结果按 add 顺序消费，最终渲染按收集顺序横向排布。
 *
 * 因此「预览恒左」等价于「预览 provider 恒排在跳转 provider 之前」。本类从两条路钉住：
 * ① 平台真实解析出的 Java provider 列表顺序（含 DumbService 过滤与 LineMarkerSettings）；
 * ② `plugin.xml` 注册段顺序（改段顺序、或把预览 provider 挪到跳转之后，这里必红）。
 */
class MapperPreviewProviderOrderGuardTest : BasePlatformTestCase() {

    fun testJavaProvider列表_预览在跳转之前() {
        val providers = LineMarkersPass.getMarkerProviders(JavaLanguage.INSTANCE, project)
        val previewIdx = providers.indexOfFirst { it is MapperMethodPreviewLineMarkerProvider }
        val navIdx = providers.indexOfFirst { it is MapperJavaToXmlLineMarkerProvider }
        assertTrue(
            "两个 provider 都应出现在 Java 提供者列表（preview=$previewIdx nav=$navIdx）",
            previewIdx >= 0 && navIdx >= 0,
        )
        assertTrue(
            "预览 provider 必须排在跳转之前（决定同行左右序：预览恒左）；实测 preview=$previewIdx nav=$navIdx",
            previewIdx < navIdx,
        )
    }

    fun testPluginXml注册顺序_预览在跳转之前() {
        val source = java.io.File("src/main/resources/META-INF/plugin.xml").readText()
        // 用 implementationClass 全名定位注册点——裸类名会先命中注释里的说明文字（本段注释也提到两个类名）。
        val preview = source.indexOf("implementationClass=\"cn.song.mybatisplus.visualizer.ui.gutter.MapperMethodPreviewLineMarkerProvider\"")
        val nav = source.indexOf("implementationClass=\"cn.song.mybatisplus.visualizer.ui.gutter.MapperJavaToXmlLineMarkerProvider\"")
        assertTrue(
            "plugin.xml 里预览 provider 必须在跳转之前（preview=$preview nav=$nav）——" +
                "顺序反转会让同行图标变成「跳转在左、预览在右」",
            preview > 0 && nav > 0 && preview < nav,
        )
    }
}