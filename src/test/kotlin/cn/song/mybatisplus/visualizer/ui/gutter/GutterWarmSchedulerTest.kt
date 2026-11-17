package cn.song.mybatisplus.visualizer.ui.gutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-GutterFirstOpenDelay F2：gutter 首渲染预热**延迟调度**。
 *
 * 两块：
 * 1. 判定内核 [GutterWarmScheduler.shouldKickNow] 纯函数矩阵（dumb / 未到延迟 / 到点 /
 *    延迟=0 回旧行为）——纯函数零平台依赖，plain JUnit 直跑；
 * 2. 接线守卫：`XmlSqlGutterLineMarkerProvider` 的三条预热必须经调度器——只测 holder 测不到
 *    「有没有人改回直踢」，按源码扫（仓内同款口径见 `PreviewAssemblyWarmTest.test接线点仍在gutter的文件打开预热处`）。
 */
class GutterWarmSchedulerTest {

    // ------------------------------------------------------------------
    // ① 判定内核纯函数矩阵
    // ------------------------------------------------------------------

    @Test
    fun `dumb期一律不踢`() {
        assertTrue("dumb ⇒ 不踢（等下次渲染）", !GutterWarmScheduler.shouldKickNow(true, 10_000L, 5_000L))
        assertTrue("dumb ⇒ 不踢，哪怕已远超延迟窗", !GutterWarmScheduler.shouldKickNow(true, Long.MAX_VALUE / 2, 5_000L))
    }

    @Test
    fun `延迟为0等于旧行为立即踢`() {
        assertTrue("delay=0 ⇒ 立即踢（回退通道）", GutterWarmScheduler.shouldKickNow(false, 0L, 0L))
        assertTrue("delay=0 ⇒ 立即踢（负值同口径防御）", GutterWarmScheduler.shouldKickNow(false, 0L, -1L))
        // wiring 口径：delay<=0 在 onGutterFirstRender 里先于 dumb 预检早出（三链自带 dumb 检查，
        // 保持原语义）；此处只钉纯函数本身的矩阵，dumb+delay=0 组合不经过本判定。
        assertFalse("纯函数口径：dumb 与 delay=0 组合仍判不踢（wiring 层已提前走直踢分支）", GutterWarmScheduler.shouldKickNow(true, 0L, 0L))
    }

    @Test
    fun `smart后未到延迟窗走调度不立即踢`() {
        assertFalse("刚 smart ⇒ 走延迟派发", GutterWarmScheduler.shouldKickNow(false, 0L, 5_000L))
        assertFalse("差 1ms 到点 ⇒ 走延迟派发", GutterWarmScheduler.shouldKickNow(false, 4_999L, 5_000L))
    }

    @Test
    fun `smart后到点立即踢`() {
        assertTrue("恰好到点 ⇒ 立即踢", GutterWarmScheduler.shouldKickNow(false, 5_000L, 5_000L))
        assertTrue("超时 ⇒ 立即踢", GutterWarmScheduler.shouldKickNow(false, 60_000L, 5_000L))
    }

    // ------------------------------------------------------------------
    // ② 接线守卫（源码扫，剥离注释）
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 block 注释与行注释（与 AcpTransportScopeSourceScanTest 同款）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun readMainSource(relPath: String): String {
        val f = repoRoot().resolve(relPath)
        return f.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("找不到 $relPath（工作目录=${File(".").absolutePath}）")
    }

    private val THREE_CHAIN_CALLS = listOf(
        "prefetchMbgExample(",
        "warmCompletionContextOnce(",
        "PreviewAssemblyWarm.warmOnce(",
    )

    @Test
    fun `首渲染预热必须经调度器`() {
        val source = stripComments(
            readMainSource("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/XmlSqlGutterLineMarkerProvider.kt"),
        )
        val getLineMarkerInfoBody = source
            .substringAfter("override fun getLineMarkerInfo")
            .substringBefore("private fun prefetchMbgExample")
        assertTrue(
            "getLineMarkerInfo 必须经 GutterWarmScheduler.onGutterFirstRender 调度（F2：三链合并为一个调度点）",
            getLineMarkerInfoBody.contains("GutterWarmScheduler.onGutterFirstRender("),
        )
        val offenders = THREE_CHAIN_CALLS.filter { getLineMarkerInfoBody.contains(it) }
        assertTrue(
            "getLineMarkerInfo 不得直踢预热链（$offenders）——改回直踢=绕过调度器，回退通道是 sqlvis.gutter.warmDelayMs=0",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `调度器到点踢出必须走统一踢出点且三链齐全`() {
        val scheduler = stripComments(
            readMainSource("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/GutterWarmScheduler.kt"),
        )
        assertTrue(
            "调度器必须经 XmlSqlGutterLineMarkerProvider.kickFileWarmers 踢出（三链顺序与去重语义集中在 provider）",
            scheduler.contains("XmlSqlGutterLineMarkerProvider.kickFileWarmers("),
        )
        assertTrue(
            "判定必须走 shouldKickNow 纯函数（单测矩阵钉死的那条）",
            scheduler.contains("shouldKickNow("),
        )
        val provider = stripComments(
            readMainSource("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/XmlSqlGutterLineMarkerProvider.kt"),
        )
        val kickBody = provider
            .substringAfter("internal fun kickFileWarmers")
            .substringBefore("private fun warmCompletionContextOnce")
        val missing = THREE_CHAIN_CALLS.filter { !kickBody.contains(it) }
        assertTrue("统一踢出点必须保留全部三条预热链（缺=$missing）", missing.isEmpty())
    }

    @Test
    fun `延迟量必须走Registry且回退通道在场`() {
        val pluginXml = readMainSource("src/main/resources/META-INF/plugin.xml")
        val keyDecl = """<registryKey key="sqlvis.gutter.warmDelayMs""""
        assertTrue(
            "plugin.xml 须登记 sqlvis.gutter.warmDelayMs（BundleParityGuard 对 registryKey 族整元素豁免，别引入 bundle 键）",
            pluginXml.contains(keyDecl),
        )
        val registryDecl = pluginXml
            .substringAfter(keyDecl)
            .substringBefore("/>")
        assertTrue(
            "默认值必须是 5000（实际片段：$registryDecl）",
            registryDecl.contains("""defaultValue="5000""""),
        )
        val scheduler = stripComments(
            readMainSource("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/GutterWarmScheduler.kt"),
        )
        assertTrue(
            "delay<=0 必须走立即踢（旧行为回退通道）",
            scheduler.contains("delayMs <= 0L") && scheduler.contains("XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag)"),
        )
        assertTrue(
            "Registry 键名必须与 plugin.xml 登记一致",
            scheduler.contains("\"sqlvis.gutter.warmDelayMs\""),
        )
    }

    // ------------------------------------------------------------------
    // ③ T-GutterWarmReadAction（2026-11-16 真机 SEVERE 实证）：延迟派发体必须包读动作
    // ------------------------------------------------------------------

    /**
     * 延迟派发体接线判定：`scheduleWarmIfDue` 体内必须先出现 `runReadAction(`，且
     * `tag.isValid`（复检）与 `kickFileWarmers(`（踢出）都落在它**之后**——
     * 池线程上无读动作，PSI 读会抛 `Read access is allowed from inside read-action only`。
     * 提取不到方法体返回 null（调用方按红处理）。
     */
    private fun readActionWiring(schedulerSource: String): Boolean? {
        val body = schedulerSource.substringAfter("private fun scheduleWarmIfDue", "")
        if (body.isEmpty()) return null
        val readActionIdx = body.indexOf("runReadAction(")
        if (readActionIdx < 0) return false
        return body.indexOf("tag.isValid") > readActionIdx &&
            body.indexOf("kickFileWarmers(") > readActionIdx
    }

    @Test
    fun `延迟派发体必须包读动作`() {
        val wiring = readActionWiring(
            stripComments(readMainSource("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/GutterWarmScheduler.kt")),
        )
        assertNotNull("无法提取 scheduleWarmIfDue 方法体（签名漂移？）——守卫扫描面失效按红处理", wiring)
        assertTrue(
            "scheduleWarmIfDue 的延迟派发体必须在读动作内完成「复检 + 踢出」：池线程不持读动作，" +
                "`tag.isValid` 与三链同步段的 PSI 读会抛 `Read access is allowed from inside read-action only`" +
                "（沙盒 2026-11-16 12:35:42 一次连出 3 条 SEVERE）。实测 wiring=$wiring",
            wiring == true,
        )
    }

    @Test
    fun `守卫自证_延迟派发体漏读动作必须报红`() {
        val legacy = """
            private fun scheduleWarmIfDue(project: Project, tag: XmlTag, fileUrl: String, remainingMs: Long) {
                val fire = Runnable {
                    val due = tag.isValid
                    if (due) XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag)
                }
            }
        """.trimIndent()
        assertEquals("修复前形态（池线程直读 PSI）必须报红", false, readActionWiring(legacy))

        val fixed = """
            private fun scheduleWarmIfDue(project: Project, tag: XmlTag, fileUrl: String, remainingMs: Long) {
                val fire = Runnable {
                    app.runReadAction(Computable {
                        if (tag.isValid) XmlSqlGutterLineMarkerProvider.kickFileWarmers(tag)
                    })
                }
            }
        """.trimIndent()
        assertEquals("修复后形态必须放行", true, readActionWiring(fixed))

        assertNull("扫描面漂移（无该方法）按红处理", readActionWiring("private fun other() {}"))
    }
}
