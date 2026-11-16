package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.ref.MybatisXmlTargets
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MapperNavigationLineMarkerProvidersTest : BasePlatformTestCase() {

    private val javaProvider = MapperJavaToXmlLineMarkerProvider()
    private val xmlProvider = MapperXmlToJavaLineMarkerProvider()

    override fun setUp() {
        super.setUp()
        MapperXmlToJavaLineMarkerProvider.resetResolvedCacheForTest()
    }

    override fun tearDown() {
        try {
            MapperXmlToJavaLineMarkerProvider.resetResolvedCacheForTest()
        } finally {
            super.tearDown()
        }
    }

    private fun addMapper(): PsiFile = myFixture.addFileToProject(
        "com/demo/UserMapper.java",
        """
        package com.demo;
        public interface UserMapper {
            int count();
            int insertOne();
            int updateOne();
            int deleteOne();
            int missing();
        }
        """.trimIndent(),
    )

    private fun addXml(body: String, namespace: String = "com.demo.UserMapper"): PsiFile =
        myFixture.addFileToProject("mapper/UserMapper.xml", "<mapper namespace=\"$namespace\">\n$body\n</mapper>")

    private fun markers(provider: LineMarkerProvider, file: PsiFile): List<LineMarkerInfo<*>> {
        val elements = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toMutableList()
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(elements, result)
        return result
    }

    private fun <T : PsiElement> navigate(marker: LineMarkerInfo<T>) {
        marker.navigationHandler!!.navigate(null, marker.element!!)
    }

    fun testJavaMethodNavigatesToMatchingXmlStatement() {
        val java = addMapper()
        val xml = addXml("  <select id=\"count\">SELECT 1</select>")
        val marker = markers(javaProvider, java).single()
        assertSame(MpvsIcons.MapperMethod, marker.icon)
        assertEquals("count", marker.element?.text)

        navigate(marker)
        val manager = FileEditorManager.getInstance(project)
        assertEquals(xml.virtualFile, manager.selectedFiles.single())
        val editor = manager.selectedTextEditor!!
        assertEquals(1, editor.document.getLineNumber(editor.caretModel.offset))
    }

    fun testXmlStatementNavigatesToMatchingJavaMethod() {
        val java = addMapper()
        val xml = addXml("  <select id=\"count\">SELECT 1</select>")
        val marker = markers(xmlProvider, xml).single()
        assertSame(MpvsIcons.MapperStatement, marker.icon)

        navigate(marker)
        val manager = FileEditorManager.getInstance(project)
        assertEquals(java.virtualFile, manager.selectedFiles.single())
        val method = PsiTreeUtil.findChildrenOfType(java, PsiMethod::class.java).first { it.name == "count" }
        assertEquals(method.textOffset, manager.selectedTextEditor!!.caretModel.offset)
    }

    fun testXmlClosingTagsDoNotProduceDuplicateMarkers() {
        addMapper()
        val xml = addXml("<select id=\"count\">\nSELECT 1\n</select>")
        val marker = markers(xmlProvider, xml).single()
        assertEquals(xml.text.indexOf("select"), marker.element!!.textRange.startOffset)
    }

    fun testAllFourStatementKindsProduceBothMarkers() {
        val java = addMapper()
        val xml = addXml(
            """
            <select id="count">SELECT 1</select>
            <insert id="insertOne">INSERT INTO t VALUES (1)</insert>
            <update id="updateOne">UPDATE t SET a = 1</update>
            <delete id="deleteOne">DELETE FROM t</delete>
            """.trimIndent(),
        )
        assertEquals(listOf("count", "insertOne", "updateOne", "deleteOne"),
            markers(javaProvider, java).map { it.element?.text })
        assertEquals(listOf("select", "insert", "update", "delete"),
            markers(xmlProvider, xml).map { it.element?.text })
    }

    fun testMismatchedNamespaceProducesNoMarkers() {
        val java = addMapper()
        val xml = addXml("<select id=\"count\">SELECT 1</select>", "com.demo.OtherMapper")
        assertEmpty(markers(javaProvider, java))
        assertEmpty(markers(xmlProvider, xml))
    }

    fun testMismatchedIdProducesNoMarkers() {
        val java = addMapper()
        val xml = addXml("<select id=\"unknown\">SELECT 1</select>")
        assertEmpty(markers(javaProvider, java))
        assertEmpty(markers(xmlProvider, xml))
    }

    fun testMissingXmlProducesNoJavaMarker() {
        assertEmpty(markers(javaProvider, addMapper()))
    }

    fun testMissingJavaProducesNoXmlMarker() {
        assertEmpty(markers(xmlProvider, addXml("<select id=\"count\">SELECT 1</select>")))
    }

    fun testFragmentsAndNestedStatementsProduceNoXmlMarkers() {
        addMapper()
        val xml = addXml(
            """
            <sql id="count">SELECT 1</sql>
            <resultMap id="count" type="map"/>
            <select id="unknown"><select id="count">SELECT 1</select></select>
            """.trimIndent(),
        )
        assertEmpty(markers(xmlProvider, xml))
    }

    fun testMapperNestedInOtherRootProducesNoXmlMarker() {
        addMapper()
        val xml = myFixture.addFileToProject(
            "other.xml",
            """<page namespace="com.demo.UserMapper"><mapper><select id="count">SELECT 1</select></mapper></page>""",
        )
        assertEmpty(markers(xmlProvider, xml))
    }

    fun testFastPathDoesNotProduceMarkers() {
        // F1''' 起定位走平台 fileBasedIndex（getContainingFiles），fast path 不再持有任何
        // 本插件自建扫描计数缝——本用例保留「fast path 零产出」的行为面（原 indexScanCount
        // 断言随 T-PS5-05 单槽机制退役删除）。
        val java = addMapper()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        for ((provider, file) in listOf(javaProvider to java, xmlProvider to xml)) {
            for (element in PsiTreeUtil.collectElements(file) { it.firstChild == null }) {
                assertNull(provider.getLineMarkerInfo(element))
            }
        }
    }

    // ------------------------------------------------------------------
    // F1（T-GutterFirstOpenDelay）：解析失败不摘图标——正缓存判定矩阵
    // dumb 判定走 isDumbProvider 测试缝（承 MbgExampleCallResolver 定例），矩阵与夹具真实
    // dumb 态解耦（夹具方法体在读操作内，waitForSmartMode 不可用）。
    // ------------------------------------------------------------------

    fun testF1ResolveSuccessProducesMarkerAndSeedsPositiveCache() {
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val xmlProvider = MapperXmlToJavaLineMarkerProvider()
        addMapper()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        val marker = markers(xmlProvider, xml).single()
        assertSame(MpvsIcons.MapperStatement, marker.icon)
        assertTrue(
            "解析成功必须记入会话级正缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#count"),
        )
    }

    fun testF1ResolveUnknownWithCacheHitStillProducesMarker() {
        // 冷窗形态（S2）：查询**没算完**（Unknown），不是「确定没有」——已确认过的图标必须留着。
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add("com.demo.UserMapper#count")
        MybatisXmlTargets.methodLookupOverrideForTest = { _, _, _ -> MybatisXmlTargets.MethodLookup.Unknown }
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val xmlProvider = MapperXmlToJavaLineMarkerProvider()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        val marker = markers(xmlProvider, xml).single()
        assertSame(MpvsIcons.MapperStatement, marker.icon)
        assertTrue(
            "Unknown 不得动缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#count"),
        )
    }

    /**
     * P2.1（T-MapperGutterEvict，2026-11-16）：确定不存在时图标必须消失并收回缓存——
     * 早期 F1 缓存只进不出，删掉接口方法 / 改 `<mapper namespace>` 后图标靠旧记忆续命。
     */
    fun testF1ResolveAbsentEvictsPositiveCacheAndProducesNoMarker() {
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add("com.demo.UserMapper#count")
        MybatisXmlTargets.methodLookupOverrideForTest = { _, _, _ -> MybatisXmlTargets.MethodLookup.Absent }
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val xmlProvider = MapperXmlToJavaLineMarkerProvider()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        assertEmpty(markers(xmlProvider, xml))
        assertFalse(
            "确定不存在必须收回正缓存，否则下一趟慢 pass 还会把图标画回来",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#count"),
        )
    }

    fun testF1ResolveFailureWithoutCacheProducesNoMarkerAndLeavesCacheClean() {
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val xmlProvider = MapperXmlToJavaLineMarkerProvider()
        addMapper()
        val xml = addXml("<select id=\"ghost\">SELECT 1</select>")
        assertEmpty(markers(xmlProvider, xml))
        assertFalse(
            "「真没有」的负例不得污染正缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#ghost"),
        )
    }

    fun testF1DumbModeOptimisticallyProducesMarkerWithoutCaching() {
        // F1-b：dumb 窗内解析失败也乐观产出（不摘图标），且失败路径不得入正缓存。
        // dumb 判定走 isDumbProvider 测试缝（承 MbgExampleCallResolver 定例），不依赖平台真实 dumb 态。
        // 缝是伴生共享（Java/XML 两侧同读），用完即还原，防测试顺序泄漏。
        val original = MapperXmlToJavaLineMarkerProvider.isDumbProvider
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { true }
        try {
            val xmlProvider = MapperXmlToJavaLineMarkerProvider()
            addMapper()
            val xml = addXml("<select id=\"ghost\">SELECT 1</select>")
            val marker = markers(xmlProvider, xml)
            assertEquals("dumb 窗内解析失败须乐观产出（F1-b）", 1, marker.size)
            assertSame(MpvsIcons.MapperStatement, marker.single().icon)
            assertFalse("乐观产出不得污染正缓存",
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#ghost"))
        } finally {
            MapperXmlToJavaLineMarkerProvider.isDumbProvider = original
        }
    }

    // ------------------------------------------------------------------
    // F1'（T-GutterFirstOpenDelay Java 侧）：MapperXmlLocator.locate 瞬时落空不缺席——
    // 真机 2026-11-15 实证：冷窗/索引临界期慢趟首跑 locate 落空 → 慢趟结果固化 →
    // Java→XML 图标长期缺席（CustChannelMapper/CustAddressMapper 全方法行，daemon 重跑才恢复）。
    // 判定与 XML 侧同构：成功入共享正缓存 / 失败+缓存命中 / 失败+dumb 乐观 / 其余不产出。
    // ------------------------------------------------------------------

    fun testF1JavaLocateSuccessProducesMarkerAndSeedsPositiveCache() {
        val java = addMapper()
        addXml("<select id=\"count\">SELECT 1</select>")
        val marker = markers(javaProvider, java).single()
        assertSame(MpvsIcons.MapperMethod, marker.icon)
        assertTrue(
            "locate 成功必须记入共享正缓存（Java 侧失败分支靠它双向受益）",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#count"),
        )
    }

    fun testF1JavaLocateUnknownWithCacheHitStillProducesMarker() {
        // 冷窗形态：locate **没算完**（Unknown）——缓存命中必须照常产出，缓存不动。
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add("com.demo.UserMapper#count")
        MapperXmlLocator.lookupOverrideForTest = { _, _ -> MapperXmlLocator.Lookup.Unknown }
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        val marker = markers(javaProvider, addMapper())
        assertEquals("Unknown + 缓存命中必须照常产出（图标不缺席）", 1, marker.size)
        assertEquals("count", marker.single().element?.text)
        assertTrue(
            "Unknown 不得动缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#count"),
        )
    }

    /** P2.1（T-MapperGutterEvict，2026-11-16）：Java 侧确定不存在时摘图标并收回缓存。 */
    fun testF1JavaLocateAbsentEvictsPositiveCacheAndProducesNoMarker() {
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add("com.demo.UserMapper#count")
        MapperXmlLocator.lookupOverrideForTest = { _, _ -> MapperXmlLocator.Lookup.Absent }
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        assertEmpty(markers(javaProvider, addMapper()))
        assertFalse(
            "确定不存在必须收回正缓存（删掉 XML 语句 / 改 namespace 后图标不得续命）",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#count"),
        )
    }

    fun testF1JavaDumbOptimisticallyProducesMarkerWithoutPollutingCache() {
        // F1-b Java 侧：dumb 窗内 locate 落空也乐观产出；失败路径不得污染正缓存。
        val original = MapperXmlToJavaLineMarkerProvider.isDumbProvider
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { true }
        try {
            val marker = markers(javaProvider, addMapper())
            assertEquals("dumb 窗内 locate 落空须乐观产出（接口 5 个方法）", 5, marker.size)
            assertFalse("乐观产出不得污染正缓存",
                MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#missing"))
        } finally {
            MapperXmlToJavaLineMarkerProvider.isDumbProvider = original
        }
    }

    fun testF1JavaCacheMissAndSmartProducesNoMarkerNegative() {
        // 负例不回归：真没有对应 XML（缓存空 + smart）依旧不产出。显式置 smart 缝防兄弟用例泄漏。
        val original = MapperXmlToJavaLineMarkerProvider.isDumbProvider
        MapperXmlToJavaLineMarkerProvider.isDumbProvider = { false }
        try {
            assertEmpty(markers(javaProvider, addMapper()))
        } finally {
            MapperXmlToJavaLineMarkerProvider.isDumbProvider = original
        }
    }
}
