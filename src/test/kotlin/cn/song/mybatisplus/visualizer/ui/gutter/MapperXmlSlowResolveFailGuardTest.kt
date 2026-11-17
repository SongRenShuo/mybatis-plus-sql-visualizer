package cn.song.mybatisplus.visualizer.ui.gutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F1（T-GutterFirstOpenDelay）接线守卫：双向跳转两个 provider 的 `createMarker`
 * 解析失败分支都必须挂着「正缓存命中仍产出」（F1-a）与「dumb 乐观产出」（F1-b）两道判定——
 * XML 侧锚 `resolveInterfaceMethod(`，Java 侧锚 `MapperXmlLocator.locate(`（F1' 2026-11-15，
 * 真机实证 Java→XML 图标因冷窗 locate 落空 + 慢趟结果固化而长期缺席）。
 *
 * 防「守卫函数有了没人调」的空绿：行为矩阵（[MapperNavigationLineMarkerProvidersTest] 的
 * F1/F1' 用例）从消费侧证明缓存命中真会影响产出；本守卫从源码形态钉住判定必须落在
 * 解析调用**之后**（即失败分支上）——判定被挪到解析之前/分支删掉即红。
 *
 * 防空绿自证（对齐 GutterProviderSingleLookupGuardTest 惯例）：修复后形态放行、
 * 修复前形态（无失败分支判定）必红、扫描面漂移（提取不到方法体/解析调用）按红处理不静默。
 */
class MapperXmlSlowResolveFailGuardTest {

    @Test
    fun `F1接线守卫_createMarker失败分支必须挂三态兜底`() {
        val body = extractCreateMarkerBody()
            ?: error("无法提取 MapperXmlToJavaLineMarkerProvider.createMarker 方法体（签名形态漂移？）——守卫扫描面失效按红处理")
        val wiring = failureBranchWiring(body)
        assertNotNull("createMarker 内必须存在 lookupInterfaceMethod 查询调用（扫描面漂移？）", wiring)
        assertTrue(
            "createMarker 必须：① 查询前先过硬 dumb 门（isDumbProvider，避免索引重建期查索引）" +
                "② Unknown 分支保留缓存命中（resolvedStatementKeys.contains，F1-a/S2）" +
                "③ Absent 分支收回缓存（resolvedStatementKeys.remove，P2.1：删方法/改 namespace 后图标不得续命）。" +
                "实测 wiring=$wiring",
            wiring == true,
        )
        assertTrue(
            "createMarker 不得吞异常（平台契约禁 catch ProcessCanceledException；三态化把 IndexNotReady 的" +
                "catch 收敛在 MybatisXmlTargets.lookupInterfaceMethod 内）",
            !body.contains("catch"),
        )
    }

    @Test
    fun `F1守卫自证_修复后形态放行修复前形态必红`() {
        // 修复后形态（dumb 门前移 + Unknown 留缓存 + Absent 收回缓存）必须放行。
        val fixed = """
            if (isDumbProvider.apply(project)) return buildStatementMarker(element)
            return when (MybatisXmlTargets.lookupInterfaceMethod(project, namespace, id)) {
                is Found -> { resolvedStatementKeys.add(key); buildStatementMarker(element) }
                Unknown -> if (resolvedStatementKeys.contains(key)) buildStatementMarker(element) else null
                Absent -> { resolvedStatementKeys.remove(key); null }
            }
        """.trimIndent()
        assertEquals("修复后形态必须放行（wiring=true）", true, failureBranchWiring(fixed))

        // 修复前形态（F1 首版）：缓存只进不出——Absent 也靠 contains 续命，P2.1 报障本体。
        val legacyNoEvict = """
            val resolved = lookupInterfaceMethod(project, namespace, id)
            if (resolved != null) { resolvedStatementKeys.add(key); return buildStatementMarker(element) }
            if (isDumbProvider.apply(project)) return buildStatementMarker(element)
            if (resolvedStatementKeys.contains(key)) return buildStatementMarker(element)
            return null
        """.trimIndent()
        assertEquals("缓存只进不出（无 remove）必须报红", false, failureBranchWiring(legacyNoEvict))

        // 无任何缓存判定的形态同样报红。
        val legacy = """
            if (lookupInterfaceMethod(project, namespace, id) == null) return null
            return buildStatementMarker(element)
        """.trimIndent()
        assertEquals("无兜底判定的形态必须报红（wiring=false）", false, failureBranchWiring(legacy))

        // dumb 门挪到查询之后（索引重建期先查索引 → IndexNotReadyException）必须报红。
        val misordered = """
            val resolved = lookupInterfaceMethod(project, namespace, id)
            if (isDumbProvider.apply(project)) return buildStatementMarker(element)
            resolvedStatementKeys.remove(key)
            if (resolvedStatementKeys.contains(key)) return buildStatementMarker(element)
            return null
        """.trimIndent()
        assertEquals("dumb 门晚于查询必须报红", false, failureBranchWiring(misordered))

        // 缓存分支挪到查询之前同样必须报红——守住「判定在失败分支上」的语义。
        val cacheFirst = """
            if (resolvedStatementKeys.contains(key)) return buildStatementMarker(element)
            resolvedStatementKeys.remove(key)
            lookupInterfaceMethod(project, namespace, id)
            return null
        """.trimIndent()
        assertEquals("缓存分支先于查询必须报红", false, failureBranchWiring(cacheFirst))

        // 扫描面漂移形态：无查询调用 → 提取器返回 null（调用方按红处理）。
        assertNull(failureBranchWiring("return null"))
    }

    @Test
    fun `F1接线守卫_Java侧createMarker失败分支必须挂三态兜底`() {
        val body = extractCreateMarkerBody(1)
            ?: error("无法提取第二个 createMarker（MapperJavaToXmlLineMarkerProvider，签名/顺序漂移？）——守卫扫描面失效按红处理")
        val wiring = javaFailureBranchWiring(body)
        assertNotNull("Java 侧 createMarker 内必须存在 MapperXmlLocator.locateOrUnknown 查询调用（扫描面漂移？）", wiring)
        assertTrue(
            "Java 侧必须：① 查询前先过硬 dumb 门 ② Unknown 保留缓存命中 ③ Absent 收回缓存" +
                "（与 XML 侧同构）。实测 wiring=$wiring",
            wiring == true,
        )
        assertTrue(
            "Java 侧 createMarker 同样不得吞异常（平台契约禁 catch ProcessCanceledException）",
            !body.contains("catch"),
        )
    }

    @Test
    fun `F1守卫自证_Java侧修复后形态放行修复前形态必红`() {
        // Java 侧修复后形态（dumb 门前移 + 三态兜底）必须放行。
        val fixed = """
            if (MapperXmlToJavaLineMarkerProvider.isDumbProvider.apply(project)) return buildMethodMarker(element)
            return when (MapperXmlLocator.locateOrUnknown(project, statementId)) {
                is Found -> { MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(key); buildMethodMarker(element) }
                Unknown -> if (MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(key)) buildMethodMarker(element) else null
                Absent -> { MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.remove(key); null }
            }
        """.trimIndent()
        assertEquals("Java 侧修复后形态必须放行（wiring=true）", true, javaFailureBranchWiring(fixed))

        // Java 侧修复前形态（缓存只进不出，无 remove）必须被报出。
        val legacyNoEvict = """
            val resolved = MapperXmlLocator.locateOrUnknown(project, statementId)
            if (resolved != null) { MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add(key); return buildMethodMarker(element) }
            if (MapperXmlToJavaLineMarkerProvider.isDumbProvider.apply(project)) return buildMethodMarker(element)
            if (MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains(key)) return buildMethodMarker(element)
            return null
        """.trimIndent()
        assertEquals("Java 侧缓存只进不出必须报红（wiring=false）", false, javaFailureBranchWiring(legacyNoEvict))

        // 扫描面漂移形态：无查询调用 → 返回 null（调用方按红处理）。
        assertNull(javaFailureBranchWiring("return null"))
    }

    // ------------------------------------------------------------------
    // 扫描面与提取器
    // ------------------------------------------------------------------

    // k2j 生产 100% Java：master 单文件 MapperNavigationLineMarkerProviders.kt 在本仓拆为两个 .java
    // （XML 侧 MapperXmlToJavaLineMarkerProvider / Java 侧 MapperJavaToXmlLineMarkerProvider）。
    // occurrence 0=XML 侧、1=Java 侧，改为按文件分别读取；守卫语义（失败分支三态兜底）不变。
    private fun sourceFile(occurrence: Int): File {
        val name = if (occurrence == 0) "MapperXmlToJavaLineMarkerProvider.java" else "MapperJavaToXmlLineMarkerProvider.java"
        val dir = "src/main/java/cn/song/mybatisplus/visualizer/ui/gutter"
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(root, "src/main/java").isDirectory) return File(File(root, dir), name)
            root = root.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/java 不存在于 user.dir 及上层）: " + System.getProperty("user.dir"))
    }

    /** 取对应 provider 文件内 `private static LineMarkerInfo<PsiElement> createMarker` 的配平方法体。 */
    private fun extractCreateMarkerBody(occurrence: Int = 0): String? {
        val stripped = stripComments(sourceFile(occurrence).readText())
        val marker = "private static LineMarkerInfo<PsiElement> createMarker"
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
     * 失败分支接线判定（T-MapperGutterEvict P2.1 起为三态形态）：查询调用（[anchor]）**之前**必须有
     * dumb 门（`isDumbProvider(`，避免索引重建期查索引），**之后**必须同时有
     * 「Unknown 保留缓存」（`resolvedStatementKeys.contains(`）与「Absent 收回缓存」
     * （`resolvedStatementKeys.remove(`）。无查询调用返回 null（调用方按红处理）。
     * XML 侧锚=`lookupInterfaceMethod(`，Java 侧锚=`MapperXmlLocator.locateOrUnknown(`。
     */
    private fun failureBranchWiring(body: String, anchor: String = "lookupInterfaceMethod("): Boolean? {
        val resolveIdx = body.indexOf(anchor)
        if (resolveIdx < 0) return null
        val before = body.substring(0, resolveIdx)
        val after = body.substring(resolveIdx)
        // k2j: Kotlin isDumbProvider(project) → Java isDumbProvider.apply(project)。
        return before.contains("isDumbProvider.apply(") &&
            after.contains("resolvedStatementKeys.contains(") &&
            after.contains("resolvedStatementKeys.remove(")
    }

    /** Java 侧（`MapperJavaToXmlLineMarkerProvider.createMarker`）的失败分支接线判定。 */
    private fun javaFailureBranchWiring(body: String): Boolean? =
        failureBranchWiring(body, anchor = "MapperXmlLocator.locateOrUnknown(")

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
