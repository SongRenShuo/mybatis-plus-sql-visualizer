package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * 券01（T-PerfSweep28-01）：Mapper 双向跳转 gutter 的门控与 memo 行为收口。
 *
 * 三面判据（先红后绿读数见计划 §实施记录）：
 *  ① locate 结果级 memo——同一 `namespace.id` 在文件清单同一代内第二次定位零文件遍历
 *     （statementScanCount 测试缝；修复前每个方法声明每趟付 O(mapper 文件数) 根标签比对）；
 *  ② 接口前置——非接口类（service/util）的方法声明零定位触达、零标记（P-1 夹具）；
 *  ③ 组闸——关 mapperXml 组后双 Provider 零标记且零定位触达（R28-2）；
 *  另含多模块同名（同 namespace 同 id）首命中文件稳定的 P-3 夹具。
 *
 * 驱动形态照 GutterIconDedupTest：provider 直接实例化、collectSlowLineMarkers 手工喂元素，
 * 不起 daemon。
 */
class MapperNavigationGutterGateTest : BasePlatformTestCase() {

    private val mapperXml1 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.demo.mapper.UserMapper">
    <select id="selectById" resultType="map">SELECT * FROM t_user WHERE id = #{id}</select>
</mapper>
"""

    private val mapperXml2 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.demo.mapper.UserMapper">
    <select id="selectById" resultType="map">SELECT * FROM t_user WHERE id = #{id}</select>
</mapper>
"""

    override fun setUp() {
        super.setUp()
        MapperXmlLocator.clearFileListCacheForTest()
        MapperXmlLocator.statementScanCount.set(0)
    }

    override fun tearDown() {
        try {
            MapperXmlLocator.clearFileListCacheForTest()
        } finally {
            super.tearDown()
        }
    }

    private fun addMapperXmls(count: Int) {
        repeat(count) { i ->
            myFixture.addFileToProject("src/main/resources/mapper$i/UserMapper$i.xml", mapperXml1)
        }
    }

    /** 收集方法定义名 leaf（父级 PsiMethod 且 nameIdentifier 即该 leaf）。 */
    private fun methodNameLeaves(): List<PsiIdentifier> =
        PsiTreeUtil.collectElementsOfType(myFixture.file, PsiIdentifier::class.java)
            .filter { it.parent is PsiMethod && (it.parent as PsiMethod).nameIdentifier === it }

    private fun collectMarkers(provider: LineMarkerProvider, leaves: List<PsiIdentifier>): List<LineMarkerInfo<*>> {
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(ArrayList(leaves), result)
        return result
    }

    // ---------- ① locate 结果级 memo ----------

    fun testLocateMemo_同窗口第二次零文件遍历() {
        addMapperXmls(2)
        val first = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("首次定位应命中", first)
        val afterFirst = MapperXmlLocator.statementScanCount.get()
        assertEquals("首次定位应逐文件访问 2 个 mapper XML", 2, afterFirst)

        val second = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertEquals("memo 命中应返回同一文件", first!!.virtualFile, second!!.virtualFile)
        assertEquals("第二次定位应零文件遍历（memo 命中）", afterFirst, MapperXmlLocator.statementScanCount.get())

        // 负结果也缓存（非 Mapper 方法必然落空，修前每趟重付全程）
        val miss1 = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.notExists")
        assertNull(miss1)
        val afterMiss1 = MapperXmlLocator.statementScanCount.get()
        val miss2 = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.notExists")
        assertNull(miss2)
        assertEquals("负结果的第二次定位也应零文件遍历", afterMiss1, MapperXmlLocator.statementScanCount.get())
    }

    // ---------- P-3 夹具：多模块同名首命中稳定 ----------

    fun test多模块同名_首命中文件与行号稳定() {
        myFixture.addFileToProject("src/main/resources/mapperA/UserMapperA.xml", mapperXml1)
        myFixture.addFileToProject("src/main/resources/mapperB/UserMapperB.xml", mapperXml2)
        val loc = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull(loc)
        val expectedLine = mapperXml1.lineSequence().indexOfFirst { it.contains("selectById") }
        assertEquals("行号 = 语句标签起始行", expectedLine, loc!!.line)
        // 同窗口内重复定位取同一文件（多模块同名取索引序第一个的既有契约不变）
        val again = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertEquals(loc.virtualFile, again!!.virtualFile)
        assertEquals(loc.line, again.line)
    }

    // ---------- ② 接口前置 + ③ 组闸（gutter 行为） ----------

    private val javaFixture = """
        package com.demo.mapper;

        interface UserMapper {
            void selectById();
        }
    """.trimIndent()

    /** 非接口类独立夹具（不含接口方法，保证「零触达」断言只覆盖普通类方法）。 */
    private val plainClassFixture = """
        package com.demo;

        class UserService {
            void doWork() {}
            void doMore() {}
        }
    """.trimIndent()

    private fun addMapperXmlForInterface() {
        myFixture.addFileToProject(
            "src/main/resources/mapper/UserMapper.xml",
            mapperXml1,
        )
    }

    fun test非接口类方法_零定位触达零标记() {
        addMapperXmlForInterface()
        myFixture.configureByText("UserService.java", plainClassFixture)
        val before = MapperXmlLocator.statementScanCount.get()
        val markers = collectMarkers(MapperJavaToXmlLineMarkerProvider(), methodNameLeaves())
        assertEquals("service 类方法声明不应产生标记", 0, markers.size)
        assertEquals("非接口方法不应触达 locate（接口前置）", before, MapperXmlLocator.statementScanCount.get())
    }

    fun test接口方法_恰一标记且定位命中() {
        addMapperXmlForInterface()
        myFixture.configureByText("UserMapper.java", javaFixture)
        val before = MapperXmlLocator.statementScanCount.get()
        val markers = collectMarkers(MapperJavaToXmlLineMarkerProvider(), methodNameLeaves())
        assertEquals("UserMapper.selectById 声明行应恰 1 标记", 1, markers.size)
        assertTrue("接口方法应触达 locate", MapperXmlLocator.statementScanCount.get() > before)
    }

    fun test关mapperXml组_零标记零定位触达() {
        addMapperXmlForInterface()
        myFixture.configureByText("UserMapper.java", javaFixture)
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(PluginSettings.State(showGutterIcon = true, gutterGroupMapperXml = false))
            MapperXmlLocator.statementScanCount.set(0)
            val markers = collectMarkers(MapperJavaToXmlLineMarkerProvider(), methodNameLeaves())
            assertEquals("组关时 Java 侧零标记", 0, markers.size)
            assertEquals("组关时不得触达 locate（门在重逻辑前）", 0, MapperXmlLocator.statementScanCount.get())
        } finally {
            settings.loadState(before)
        }
    }
}
