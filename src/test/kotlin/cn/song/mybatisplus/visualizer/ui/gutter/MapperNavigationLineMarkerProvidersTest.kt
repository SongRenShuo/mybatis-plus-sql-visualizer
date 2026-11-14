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
        MapperXmlLocator.clearFileListCacheForTest()
        MapperXmlToJavaLineMarkerProvider.resetResolvedCacheForTest()
    }

    override fun tearDown() {
        try {
            MapperXmlLocator.clearFileListCacheForTest()
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

    fun testFastPathDoesNotScanMapperFiles() {
        val java = addMapper()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        val before = MapperXmlLocator.indexScanCount.get()
        for ((provider, file) in listOf(javaProvider to java, xmlProvider to xml)) {
            for (element in PsiTreeUtil.collectElements(file) { it.firstChild == null }) {
                assertNull(provider.getLineMarkerInfo(element))
            }
        }
        assertEquals(before, MapperXmlLocator.indexScanCount.get())
    }

    // ------------------------------------------------------------------
    // F1（T-GutterFirstOpenDelay）：解析失败不摘图标——正缓存判定矩阵
    // dumb 判定走 isDumbProvider 测试缝（承 MbgExampleCallResolver 定例），矩阵与夹具真实
    // dumb 态解耦（夹具方法体在读操作内，waitForSmartMode 不可用）。
    // ------------------------------------------------------------------

    fun testF1ResolveSuccessProducesMarkerAndSeedsPositiveCache() {
        val xmlProvider = MapperXmlToJavaLineMarkerProvider().apply { isDumbProvider = { false } }
        addMapper()
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        val marker = markers(xmlProvider, xml).single()
        assertSame(MpvsIcons.MapperStatement, marker.icon)
        assertTrue(
            "解析成功必须记入会话级正缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#count"),
        )
    }

    fun testF1ResolveFailureWithCacheHitStillProducesMarker() {
        // 冷窗形态模拟：namespace 侧接口不可解析（无对应 Java 文件），但本会话早先解析成功过该 key。
        MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.add("com.demo.UserMapper#count")
        val xmlProvider = MapperXmlToJavaLineMarkerProvider().apply { isDumbProvider = { false } }
        val xml = addXml("<select id=\"count\">SELECT 1</select>")
        assertTrue("resolveInterfaceMethod 对该 key 必须确实解析失败（否则本用例测不到失败分支）",
            MybatisXmlTargets.resolveInterfaceMethod(project, "com.demo.UserMapper", "count") == null)
        val marker = markers(xmlProvider, xml).single()
        assertSame(MpvsIcons.MapperStatement, marker.icon)
    }

    fun testF1ResolveFailureWithoutCacheProducesNoMarkerAndLeavesCacheClean() {
        val xmlProvider = MapperXmlToJavaLineMarkerProvider().apply { isDumbProvider = { false } }
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
        val xmlProvider = MapperXmlToJavaLineMarkerProvider().apply { isDumbProvider = { true } }
        addMapper()
        val xml = addXml("<select id=\"ghost\">SELECT 1</select>")
        val marker = markers(xmlProvider, xml)
        assertEquals("dumb 窗内解析失败须乐观产出（F1-b）", 1, marker.size)
        assertSame(MpvsIcons.MapperStatement, marker.single().icon)
        assertFalse("乐观产出不得污染正缓存",
            MapperXmlToJavaLineMarkerProvider.resolvedStatementKeys.contains("com.demo.UserMapper#ghost"))
    }
}
