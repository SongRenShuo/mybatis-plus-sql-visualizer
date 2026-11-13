package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * T-PreviewAssemblyWarm：文件打开时的**装配预热**（只算不建 UI）。
 *
 * 真机读数（2026-11-13 21:44 沙盒）：本会话第一次预览 ≈695ms（`request→dialog` 351 + `construct` 256 +
 * `show` 88），之后每次 55~75ms；351ms 里除装配（热态 47~62ms）外主要是首次类加载/JIT——本预热就是把它
 * 提前消化掉。`construct` 那 256ms 属面板/编辑器（本类不碰，也不该碰）。
 *
 * 本类钉三件事：开关能关死、同语句只跑一次、**预热不产生 UI 与临时文件**（计划 §五 A3 副作用门）。
 */
class PreviewAssemblyWarmTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PreviewAssemblyWarm.resetForTest()
    }

    override fun tearDown() {
        try {
            PreviewAssemblyWarm.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    private fun mapperTag(withId: Boolean = true): XmlTag {
        val id = if (withId) """ id="selectByExample"""" else ""
        myFixture.configureByText(
            "WarmMapper.xml",
            """
            <mapper namespace="com.example.WarmMapper">
              <select$id resultType="map">select * from t_warm where id = #{id}</select>
            </mapper>
            """.trimIndent(),
        )
        return PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.name == "select" }
    }

    fun test开关关闭时一次也不预热() {
        PreviewAssemblyWarm.enabledProvider = { false }
        PreviewAssemblyWarm.syncForTest = true
        PreviewAssemblyWarm.warmOnce(mapperTag())
        assertEquals("Registry 关掉必须整段早退（回到点击时才装配）", 0, PreviewAssemblyWarm.runCount.get())
    }

    fun test同一语句只预热一次() {
        PreviewAssemblyWarm.syncForTest = true
        val tag = mapperTag()
        PreviewAssemblyWarm.warmOnce(tag)
        PreviewAssemblyWarm.warmOnce(tag)
        assertEquals("同文件同语句只允许预热一次", 1, PreviewAssemblyWarm.runCount.get())
    }

    fun test缺少语句id时不预热() {
        PreviewAssemblyWarm.syncForTest = true
        PreviewAssemblyWarm.warmOnce(mapperTag(withId = false))
        assertEquals("没有 id 无法定位装配对象，不得预热", 0, PreviewAssemblyWarm.runCount.get())
    }

    fun test预热不创建预览弹窗也不留临时文件() {
        PreviewAssemblyWarm.syncForTest = true
        val basePath = project.basePath
        val before = basePath?.let { dir -> File(dir).listFiles()?.map { it.name }?.toSet() } ?: emptySet()

        PreviewAssemblyWarm.warmOnce(mapperTag())
        assertEquals("预热应真的跑了一次", 1, PreviewAssemblyWarm.runCount.get())

        assertNull("预热只算不建 UI：不得创建预览弹窗", SqlPreviewService.activePreviewDialogForTest(project))
        val after = basePath?.let { dir -> File(dir).listFiles()?.map { it.name }?.toSet() } ?: emptySet()
        assertEquals("预热不得在工程根留下任何文件（exec/预览临时文件）", before, after)
        assertTrue(
            "工程根不应出现预览/执行临时文件（实际=${after.filter { it.contains("mpsql") }}）",
            after.none { it.contains("mpsql") },
        )
    }

    /**
     * 接线守卫：预热必须挂在**文件打开时的 gutter 渲染**上（与 ctx 预热同一个信号）。
     *
     * 只测 holder 测不到「有没有人调它」——摘掉 gutter 那行调用，装载态会静默退化成「点击时才装配」，
     * 而所有 holder 用例照样绿。故此条按源码扫（仓内同款口径见 `XmlSqlCompletionContextResolverIndexKeySourceScanTest`）。
     */
    fun test接线点仍在gutter的文件打开预热处() {
        val candidates = listOf(
            File("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/XmlSqlGutterLineMarkerProvider.kt"),
            File("../src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/XmlSqlGutterLineMarkerProvider.kt"),
        )
        val source = candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("找不到 XmlSqlGutterLineMarkerProvider.kt（工作目录=${File(".").absolutePath}）")
        val codeOnly = source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
        assertTrue(
            "gutter 文件打开预热处必须调用 PreviewAssemblyWarm.warmOnce(tag)",
            codeOnly.contains("PreviewAssemblyWarm.warmOnce("),
        )
        assertTrue(
            "preview 装配入口必须仍是纯装配（XmlPreviewService.preview），别接成建面板那条",
            codeOnly.contains("XmlPreviewService.preview("),
        )
    }
}
