package cn.song.mybatisplus.visualizer.ui.gutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F1（T-GutterFirstOpenDelay）接线守卫：`MapperXmlToJavaLineMarkerProvider.createMarker`
 * 的解析失败分支必须挂着「正缓存命中仍产出」（F1-a）与「dumb 乐观产出」（F1-b）两道判定。
 *
 * 防「守卫函数有了没人调」的空绿：行为矩阵（[MapperNavigationLineMarkerProvidersTest] 的
 * F1 用例）从消费侧证明缓存命中真会影响产出；本守卫从源码形态钉住判定必须落在
 * `resolveInterfaceMethod` **之后**（即失败分支上）——判定被挪到解析之前/分支删掉即红。
 *
 * 防空绿自证（对齐 GutterProviderSingleLookupGuardTest 惯例）：修复后形态放行、
 * 修复前形态（无失败分支判定）必红、扫描面漂移（提取不到方法体/解析调用）按红处理不静默。
 */
class MapperXmlSlowResolveFailGuardTest {

    @Test
    fun `F1接线守卫_createMarker失败分支必须挂正缓存与dumb乐观判定`() {
        val body = extractCreateMarkerBody()
            ?: error("无法提取 MapperXmlToJavaLineMarkerProvider.createMarker 方法体（签名形态漂移？）——守卫扫描面失效按红处理")
        val wiring = failureBranchWiring(body)
        assertNotNull("createMarker 内必须存在 resolveInterfaceMethod 解析调用（扫描面漂移？）", wiring)
        assertTrue(
            "createMarker 的解析失败分支必须挂 F1-a（resolvedStatementKeys.contains 命中仍产出）" +
                "与 F1-b（isDumbProvider 乐观产出）两道判定，且都落在 resolveInterfaceMethod 之后。实测 wiring=$wiring",
            wiring == true,
        )
        assertTrue(
            "createMarker 不得吞异常（平台契约禁 catch ProcessCanceledException；解析失败走返回 null 语义）",
            !body.contains("catch"),
        )
    }

    @Test
    fun `F1守卫自证_修复后形态放行修复前形态必红`() {
        // 修复后形态（判定在解析之后的失败分支上）必须放行。
        val fixed = """
            val resolved = resolveInterfaceMethod(project, namespace, id)
            if (resolved != null) {
                resolvedStatementKeys.add(key)
                return buildStatementMarker(element)
            }
            if (resolvedStatementKeys.contains(key)) return buildStatementMarker(element)
            if (isDumbProvider(project)) return buildStatementMarker(element)
            return null
        """.trimIndent()
        assertEquals("修复后形态必须放行（wiring=true）", true, failureBranchWiring(fixed))

        // 修复前形态（解析失败即 return null，无任何失败分支判定）必须被报出——
        // 守卫提取器/判定退化成恒 true 即空绿。
        val legacy = """
            val resolved = resolveInterfaceMethod(project, namespace, id)
            if (resolved == null) return null
            return buildStatementMarker(element)
        """.trimIndent()
        assertEquals("修复前形态必须报红（wiring=false）", false, failureBranchWiring(legacy))

        // 判定挪到解析之前（先查缓存再解析）也必须报红——守住「判定在失败分支上」的语义。
        val misordered = """
            if (resolvedStatementKeys.contains(key)) return buildStatementMarker(element)
            if (isDumbProvider(project)) return buildStatementMarker(element)
            val resolved = resolveInterfaceMethod(project, namespace, id)
            if (resolved != null) resolvedStatementKeys.add(key)
            return null
        """.trimIndent()
        assertEquals("判定先于解析（不在失败分支上）必须报红", false, failureBranchWiring(misordered))

        // 扫描面漂移形态：无解析调用 → 提取器返回 null（调用方按红处理）。
        assertNull(failureBranchWiring("return null"))
    }

    // ------------------------------------------------------------------
    // 扫描面与提取器
    // ------------------------------------------------------------------

    private fun sourceFile(): File {
        val dir = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/gutter"
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(root, "src/main/kotlin").isDirectory) return File(File(root, dir), "MapperNavigationLineMarkerProviders.kt")
            root = root.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }

    /** 取文件内第一个 `private fun createMarker`（MapperXmlToJavaLineMarkerProvider 在前）的配平方法体。 */
    private fun extractCreateMarkerBody(): String? {
        val stripped = stripComments(sourceFile().readText())
        val marker = "private fun createMarker"
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
     * 失败分支接线判定：方法体内 `resolveInterfaceMethod(` 解析调用之后的文本里，
     * F1-a（`resolvedStatementKeys.contains(`）与 F1-b（`isDumbProvider(`）判定必须都存在——
     * 即两道判定都在解析**之后**（失败分支上）。无解析调用返回 null（调用方按红处理）。
     */
    private fun failureBranchWiring(body: String): Boolean? {
        val resolveIdx = body.indexOf("resolveInterfaceMethod(")
        if (resolveIdx < 0) return null
        val afterResolve = body.substring(resolveIdx)
        val f1a = afterResolve.contains("resolvedStatementKeys.contains(")
        val f1b = afterResolve.contains("isDumbProvider(")
        return f1a && f1b
    }

    /** 剥行注释与块注释（该文件方法体内只有 // 注释；KDoc 含花括号也不干扰配平）。 */
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
