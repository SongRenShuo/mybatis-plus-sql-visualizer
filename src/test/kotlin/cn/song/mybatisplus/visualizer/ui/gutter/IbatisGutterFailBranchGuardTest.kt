package cn.song.mybatisplus.visualizer.ui.gutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-IbatisGutterPerf P2 接线守卫：iBatis Java→XML provider 的 `createMarkers` 必须挂
 * 「dumb 乐观产出」与「正缓存命中仍产出」两道兜底，且 dumb 门必须在**索引查询之前**
 * （dumb 期查索引会抛 `IndexNotReadyException`，乐观产出就无从谈起）。
 *
 * 与 `MapperXmlSlowResolveFailGuardTest`（MyBatis 侧）同构、同判据形态：行为矩阵
 * （[IbatisNavigationLineMarkerProvidersTest] 的 `testF1i_*` 五例）从消费侧证明缓存/乐观产出的
 * 效果；本守卫从源码形态钉住判定**必须存在且位置正确**——判定被挪到解析之前、缓存分支删掉、
 * 或 dumb 门被挪到索引查询之后，一律红。
 *
 * 防空绿自证：修复后形态放行、修复前形态必红、判定错位必红、扫描面漂移（提取不到方法体/
 * 解析调用）按红处理不静默。
 */
class IbatisGutterFailBranchGuardTest {

    @Test
    fun `P2接线守卫_createMarkers必须挂dumb门与三态兜底`() {
        val body = extractCreateMarkersBody()
            ?: error("无法提取 IbatisJavaToXmlLineMarkerProvider.createMarkers 方法体（签名形态漂移？）——守卫扫描面失效按红处理")
        val wiring = failureBranchWiring(body)
        assertNotNull("createMarkers 内必须存在 lookupStatementAnchors 查询调用（扫描面漂移？）", wiring)
        assertTrue(
            "createMarkers 必须：① 索引查询前先过 isDumbProvider 门（dumb 期乐观产出、不查索引）" +
                "② Unknown 分支保留缓存命中（resolvedIbatisKeys.contains）" +
                "③ Absent 分支收回缓存（resolvedIbatisKeys.remove，P2.1「改 namespace 图标不消失」）。" +
                "实测 wiring=$wiring",
            wiring == true,
        )
        assertTrue(
            "createMarkers 不得吞异常（平台契约禁 catch ProcessCanceledException；解析失败走返回 null 语义）",
            !body.contains("catch"),
        )
    }

    @Test
    fun `P2守卫自证_修复后形态放行修复前形态与错位形态必红`() {
        // 修复后形态：dumb 门在查询之前 + Unknown 留缓存 + Absent 收回缓存。
        val fixed = """
            if (isDumbProvider(project)) return buildMarkers(element, first, previewEnabled)
            return when (val lookup = lookupStatementAnchors(project, statementId, ownerNamespace)) {
                is Lookup.Found -> { resolvedIbatisKeys.add(key); buildMarkers(element, first, previewEnabled) }
                Lookup.Unknown -> if (resolvedIbatisKeys.contains(key)) buildMarkers(element, first, previewEnabled) else null
                Lookup.Absent -> { resolvedIbatisKeys.remove(key); null }
            }
        """.trimIndent()
        assertEquals("修复后形态必须放行（wiring=true）", true, failureBranchWiring(fixed))

        // 修复前形态（P2 首版）：dumb 门与缓存命中也都在，唯独缓存**只进不出**——
        // Absent 也靠 contains 续命，改 namespace 后图标不消失。缺少 remove 一项即须报红。
        val legacyNoEvict = """
            if (isDumbProvider(project)) return buildMarkers(element, first, previewEnabled)
            if (lookupStatementAnchors(project, statementId, ownerNamespace).isNotEmpty()) {
                resolvedIbatisKeys.add(key)
                return buildMarkers(element, first, previewEnabled)
            }
            if (resolvedIbatisKeys.contains(key)) return buildMarkers(element, first, previewEnabled)
            return null
        """.trimIndent()
        assertEquals("缓存只进不出（无 remove）必须报红", false, failureBranchWiring(legacyNoEvict))

        // 无任何兜底判定的形态同样报红（解析失败即返回 null，缓存既不入也不收回）。
        val legacy = """
            if (lookupStatementAnchors(project, statementId, ownerNamespace).isEmpty()) return null
            return buildMarkers(element, first, previewEnabled)
        """.trimIndent()
        assertEquals("无兜底判定的形态必须报红（wiring=false）", false, failureBranchWiring(legacy))

        // dumb 门挪到索引查询之后（dumb 期先查索引 → IndexNotReadyException）同样必须报红。
        val misordered = """
            val anchors = lookupStatementAnchors(project, statementId, ownerNamespace)
            if (isDumbProvider(project)) return buildMarkers(element, first, previewEnabled)
            resolvedIbatisKeys.remove(key)
            if (resolvedIbatisKeys.contains(key)) return buildMarkers(element, first, previewEnabled)
            return null
        """.trimIndent()
        assertEquals("dumb 门晚于索引查询必须报红", false, failureBranchWiring(misordered))

        // 缓存分支挪到查询之前（先查缓存再解析）也必须报红——守住「判定在失败分支上」。
        val cacheFirst = """
            if (resolvedIbatisKeys.contains(key)) return buildMarkers(element, first, previewEnabled)
            resolvedIbatisKeys.remove(key)
            if (lookupStatementAnchors(project, statementId, ownerNamespace) is Lookup.Found) return buildMarkers(element, first, previewEnabled)
            return null
        """.trimIndent()
        assertEquals("缓存分支先于查询必须报红", false, failureBranchWiring(cacheFirst))

        // 扫描面漂移形态：无查询调用 → 返回 null（调用方按红处理）。
        assertNull(failureBranchWiring("return null"))
    }

    // ------------------------------------------------------------------
    // 扫描面与提取器
    // ------------------------------------------------------------------

    /**
     * T-IbatisXmlArrowCallerGate（2026-11-16 用户报障）：XML→Java 方向的箭头门槛也必须挂三态——
     * 「确定无调用点」要收回缓存并摘箭头（改 `id`/删调用后不得留「点开必空」的假图标）。
     */
    @Test
    fun `箭头门槛守卫_XML侧createMarker必须挂三态`() {
        val body = extractBody(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisXmlToJavaLineMarkerProvider.kt",
            "private fun createMarker",
        ) ?: error("无法提取 IbatisXmlToJavaLineMarkerProvider.createMarker 方法体（签名形态漂移？）——守卫扫描面失效按红处理")
        val wiring = failureBranchWiring(
            body,
            anchor = "IbatisJavaCallSites.lookupCallSite(",
            keepToken = "resolvedIbatisCallKeys.contains(",
            evictToken = "resolvedIbatisCallKeys.remove(",
        )
        assertNotNull("createMarker 内必须存在 IbatisJavaCallSites.lookupCallSite 调用（扫描面漂移？）", wiring)
        assertTrue(
            "箭头门槛必须：① 查询前先过硬 dumb 门 ② Unknown 保留缓存命中 ③ Absent 收回缓存并摘箭头。" +
                "实测 wiring=$wiring",
            wiring == true,
        )
    }

    /** 扫描面漂移与老形态自证：无查询调用 → null；缺 remove → false；三态齐 → true。 */
    @Test
    fun `箭头门槛守卫自证_缺收回或漂移必红`() {
        assertNull(
            "无查询调用（扫描面漂移）按红处理",
            failureBranchWiring(
                "return buildMarker(element)",
                anchor = "IbatisJavaCallSites.lookupCallSite(",
                keepToken = "resolvedIbatisCallKeys.contains(",
                evictToken = "resolvedIbatisCallKeys.remove(",
            ),
        )
        val legacy = """
            if (!requiresCallerProvider()) return buildMarker(element)
            if (isDumbProvider(project)) return buildMarker(element)
            val exists = IbatisJavaCallSites.lookupCallSite(project, namespace, statementId) != null
            if (resolvedIbatisCallKeys.contains(key)) return buildMarker(element)
            return if (exists) buildMarker(element) else null
        """.trimIndent()
        assertEquals(
            "只进不出（缺 remove）必须报红",
            false,
            failureBranchWiring(
                legacy,
                anchor = "IbatisJavaCallSites.lookupCallSite(",
                keepToken = "resolvedIbatisCallKeys.contains(",
                evictToken = "resolvedIbatisCallKeys.remove(",
            ),
        )
    }

    private fun repoFile(relative: String): File {
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(root, relative)
            if (candidate.isFile) return candidate
            root = root.parentFile ?: return@repeat
        }
        error("未定位到仓库根（$relative 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }

    /** 取任意源文件里指定签名（含关键词）的配平方法体；提取不到返回 null（调用方按红处理）。 */
    private fun extractBody(relativePath: String, marker: String): String? {
        val stripped = stripComments(repoFile(relativePath).readText())
        val start = stripped.indexOf(marker)
        if (start < 0) return null
        val open = stripped.indexOf('{', start + marker.length)
        if (open < 0) return null
        var depth = 0
        for (i in open until stripped.length) {
            when (stripped[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(open, i + 1)
                }
            }
        }
        return null
    }

    private fun sourceFile(): File {
        val relative = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter/IbatisJavaToXmlLineMarkerProvider.kt"
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(root, relative)
            if (candidate.isFile) return candidate
            root = root.parentFile ?: return@repeat
        }
        error("未定位到仓库根（$relative 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }

    /** 取 `private fun createMarkers` 的配平方法体（该文件中唯一一次出现，形态漂移即返回 null）。 */
    private fun extractCreateMarkersBody(): String? {
        val stripped = stripComments(sourceFile().readText())
        val marker = "private fun createMarkers"
        val start = stripped.indexOf(marker)
        if (start < 0) return null
        val open = stripped.indexOf('{', start + marker.length)
        if (open < 0) return null
        var depth = 0
        for (i in open until stripped.length) {
            when (stripped[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /**
     * 接线判定：查询调用（[anchor]）**之前**必须有 dumb 门（`isDumbProvider(`），**之后**必须同时有
     * 「Unknown 保留缓存」（[keepToken]）与「Absent 收回缓存」（[evictToken]）。无查询调用返回
     * null（按红处理）。默认锚＝Java→XML 侧；XML→Java 侧（箭头门槛）传 `lookupCallSite(` 与
     * `resolvedIbatisCallKeys.*`。
     */
    private fun failureBranchWiring(
        body: String,
        anchor: String = "lookupStatementAnchors(",
        keepToken: String = "resolvedIbatisKeys.contains(",
        evictToken: String = "resolvedIbatisKeys.remove(",
    ): Boolean? {
        val resolveIdx = body.indexOf(anchor)
        if (resolveIdx < 0) return null
        val before = body.substring(0, resolveIdx)
        val after = body.substring(resolveIdx)
        return before.contains("isDumbProvider(") &&
            after.contains(keepToken) &&
            after.contains(evictToken)
    }

    /** 剥行注释与块注释（KDoc 里的花括号不干扰配平）。 */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') i++
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}