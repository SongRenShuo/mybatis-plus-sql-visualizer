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
    fun `P2接线守卫_createMarkers必须挂dumb门与正缓存兜底`() {
        val body = extractCreateMarkersBody()
            ?: error("无法提取 IbatisJavaToXmlLineMarkerProvider.createMarkers 方法体（签名形态漂移？）——守卫扫描面失效按红处理")
        val wiring = failureBranchWiring(body)
        assertNotNull("createMarkers 内必须存在 resolveStatementAnchors 解析调用（扫描面漂移？）", wiring)
        assertTrue(
            "createMarkers 必须：① 索引查询前先过 isDumbProvider 门（dumb 期乐观产出、不查索引）" +
                "② 解析失败分支上挂 resolvedIbatisKeys.contains 命中仍产出。实测 wiring=$wiring",
            wiring == true,
        )
        assertTrue(
            "createMarkers 不得吞异常（平台契约禁 catch ProcessCanceledException；解析失败走返回 null 语义）",
            !body.contains("catch"),
        )
    }

    @Test
    fun `P2守卫自证_修复后形态放行修复前形态与错位形态必红`() {
        // 修复后形态：dumb 门在索引查询之前 + 缓存命中分支在解析之后。
        val fixed = """
            if (isDumbProvider(project)) return buildMarkers(element, first, previewEnabled)
            if (resolveStatementAnchors(project, statementId, ownerNamespace).isNotEmpty()) {
                resolvedIbatisKeys.add(key)
                return buildMarkers(element, first, previewEnabled)
            }
            if (resolvedIbatisKeys.contains(key)) return buildMarkers(element, first, previewEnabled)
            return null
        """.trimIndent()
        assertEquals("修复后形态必须放行（wiring=true）", true, failureBranchWiring(fixed))

        // 修复前形态：解析失败即 return null，无任何兜底判定。
        val legacy = """
            if (resolveStatementAnchors(project, statementId, ownerNamespace).isEmpty()) return null
            return buildMarkers(element, first, previewEnabled)
        """.trimIndent()
        assertEquals("修复前形态必须报红（wiring=false）", false, failureBranchWiring(legacy))

        // dumb 门挪到索引查询之后（dumb 期先查索引 → IndexNotReadyException）同样必须报红。
        val misordered = """
            val anchors = resolveStatementAnchors(project, statementId, ownerNamespace)
            if (isDumbProvider(project)) return buildMarkers(element, first, previewEnabled)
            if (anchors.isNotEmpty()) return buildMarkers(element, first, previewEnabled)
            return buildMarkers(element, first, previewEnabled)
        """.trimIndent()
        assertEquals("dumb 门晚于索引查询必须报红", false, failureBranchWiring(misordered))

        // 缓存分支挪到解析之前（先查缓存再解析）也必须报红——守住「判定在失败分支上」。
        val cacheFirst = """
            if (resolvedIbatisKeys.contains(key)) return buildMarkers(element, first, previewEnabled)
            if (resolveStatementAnchors(project, statementId, ownerNamespace).isNotEmpty()) return buildMarkers(element, first, previewEnabled)
            return null
        """.trimIndent()
        assertEquals("缓存分支先于解析必须报红", false, failureBranchWiring(cacheFirst))

        // 扫描面漂移形态：无解析调用 → 返回 null（调用方按红处理）。
        assertNull(failureBranchWiring("return null"))
    }

    // ------------------------------------------------------------------
    // 扫描面与提取器
    // ------------------------------------------------------------------

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
     * 接线判定：解析调用（`resolveStatementAnchors(`）**之前**必须有 dumb 门（`isDumbProvider(`），
     * **之后**必须有正缓存命中分支（`resolvedIbatisKeys.contains(`）。无解析调用返回 null（按红处理）。
     */
    private fun failureBranchWiring(body: String): Boolean? {
        val resolveIdx = body.indexOf("resolveStatementAnchors(")
        if (resolveIdx < 0) return null
        val before = body.substring(0, resolveIdx)
        val after = body.substring(resolveIdx)
        return before.contains("isDumbProvider(") && after.contains("resolvedIbatisKeys.contains(")
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