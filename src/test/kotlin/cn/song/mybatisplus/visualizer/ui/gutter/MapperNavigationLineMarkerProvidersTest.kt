package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
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
    }

    override fun tearDown() {
        try {
            MapperXmlLocator.clearFileListCacheForTest()
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
}
