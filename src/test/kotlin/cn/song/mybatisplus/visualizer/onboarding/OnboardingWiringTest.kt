package cn.song.mybatisplus.visualizer.onboarding

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「首用即生效」接线守卫（源码级，testFast 快桶）：
 *
 *  1. **预热只接线不重造**：项目打开预热必须调 `SqlDialectMappingPrewarm.prewarm(`（fire-and-forget，
 *     投池化线程），**不得**调 `prewarmAndAwait(` / `prewarmNow(`——后两者在候选调用链上是
 *     「EDT 有界等待」或「要求调用方已在 BGT」的同步形态，放进项目打开路径等于把启动拖慢，
 *     或在断言/未知线程上做慢操作（红线：预热不得在 EDT 做慢操作）。
 *  2. **三件套都在 plugin.xml 注册**：预热活动 / 首用引导活动 / 状态栏 widget 工厂；widget id 与
 *     工厂 `getId()` 一致（平台按 id 存取可见性，不一致 = 用户切换可见性静默失效）。
 *  3. **首用引导不写重复读取**：`mapperPrimerVersion` 是版本粒度记忆，状态字段与设置页无耦合
 *     （内部状态位，设置页不呈现）——这里只钉源码里确实读写该字段（防「注册了活动但判据字段没接线」）。
 *
 * 这是**结构**守卫：行为面（状态栏真的出现、引导真的只弹一次）由
 * `OnboardingStatusSnapshotTest` / `MapperProjectPrimerClaimTest` 的纯逻辑与状态翻转覆盖，
 * 真机首帧仍需 runIde（见开发计划判据）。
 */
class OnboardingWiringTest {

    private fun repoFile(rel: String): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return File(dir, rel)
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun sourceOf(rel: String): String = repoFile(rel).readText()

    /**
     * 剥注释后再判调用形态：KDoc 里会写「不得调 prewarmNow / prewarmAndAwait」的说明文字，
     * 不剥注释会把说明本身当成调用（本仓 `BannedApiSourceScanTest` 同口径）。
     */
    private fun codeOf(rel: String): String =
        sourceOf(rel)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun pluginXml(): String = sourceOf("src/main/resources/META-INF/plugin.xml")

    @Test
    fun `项目打开预热只调fire-and-forget不调同步等待形态`() {
        val src = codeOf("src/main/kotlin/cn/song/mybatisplus/visualizer/onboarding/OnboardingPrewarm.kt")
        assertTrue(
            "预热活动应调用 SqlDialectMappingPrewarm.prewarm(（fire-and-forget，投池化线程）",
            src.contains("SqlDialectMappingPrewarm.prewarm("),
        )
        assertTrue(
            "预热活动不得调用 prewarmAndAwait(——那是 EDT 装配链的有界等待，放进项目打开路径会拖慢启动",
            !src.contains("prewarmAndAwait("),
        )
        assertTrue(
            "预热活动不得直接调用 prewarmNow(——同步形式要求调用方已在 BGT，项目打开路径不保证",
            !src.contains("prewarmNow("),
        )
    }

    @Test
    fun `三件套均在pluginXml注册`() {
        val xml = pluginXml()
        assertTrue(
            "缺项目打开预热注册",
            xml.contains(
                """<postStartupActivity implementation="cn.song.mybatisplus.visualizer.onboarding.OnboardingPrewarm"/>""",
            ),
        )
        assertTrue(
            "缺首用引导注册",
            xml.contains(
                """<postStartupActivity implementation="cn.song.mybatisplus.visualizer.onboarding.MapperProjectPrimerActivity"/>""",
            ),
        )
        assertTrue(
            "缺状态栏 widget 工厂注册",
            xml.contains(
                "cn.song.mybatisplus.visualizer.onboarding.SqlPreviewStatusBarWidgetFactory",
            ),
        )
    }

    @Test
    fun `状态栏widget注册id与工厂getId一致`() {
        // 断言同一条注册里 id 是工厂返回的那个常量（平台按 id 存取可见性，写错即静默失效）
        val registration = Regex(
            """<statusBarWidgetFactory\s+id="([^"]+)"\s*\n?\s*implementation="cn\.song\.mybatisplus\.visualizer\.onboarding\.SqlPreviewStatusBarWidgetFactory"\s*/>""",
        ).find(pluginXml())
        assertTrue("未找到 statusBarWidgetFactory 注册（或属性顺序变了，守卫需同步）", registration != null)
        assertTrue(
            "widget id 必须与 SqlPreviewStatusBarWidget.WIDGET_ID 一致，实际=${registration!!.groupValues[1]}",
            registration.groupValues[1] == SqlPreviewStatusBarWidget.WIDGET_ID,
        )
    }

    @Test
    fun `首用引导的持久化字段确实被读写`() {
        val src = codeOf("src/main/kotlin/cn/song/mybatisplus/visualizer/onboarding/MapperProjectPrimerActivity.kt")
        assertTrue("引导读取已提示版本（shouldProbe 判据）", src.contains("state.mapperPrimerVersion"))
        assertTrue("领取时写入已提示版本（领取即消耗）", src.contains("mapperPrimerVersion = version"))
    }
}
