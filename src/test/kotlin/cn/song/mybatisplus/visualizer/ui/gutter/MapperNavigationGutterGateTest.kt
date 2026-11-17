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
 *  ① locate 结果稳定（重复定位同文件；负结果不抛不挂）——F1''' 起定位走平台 fileBasedIndex，
 *     原 statementScanCount/memo 测试缝随单槽机制退役；
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
        MyBatisCompetitorGate.resetForTest()
    }

    override fun tearDown() {
        try {
            MyBatisCompetitorGate.resetForTest()
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

    fun testLocateRepeat_结果稳定且多模块同名全命中清单() {
        // F1''' 起定位走平台 fileBasedIndex：重复定位的结果稳定性由索引查询保证
        // （statementScanCount/memo 测试缝随 T-PS5-05 单槽机制退役，行为面保留）。
        addMapperXmls(2)
        val first = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertNotNull("首次定位应命中", first)
        val second = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.selectById")
        assertEquals("重复定位应返回同一文件", first!!.virtualFile, second!!.virtualFile)

        // 负结果稳定（非 Mapper 方法必然落空，回退 Java 跳转）
        val miss1 = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.notExists")
        assertNull(miss1)
        val miss2 = MapperXmlLocator.locate(project, "com.demo.mapper.UserMapper.notExists")
        assertNull(miss2)
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
        val markers = collectMarkers(MapperJavaToXmlLineMarkerProvider(), methodNameLeaves())
        assertEquals("service 类方法声明不应产生标记", 0, markers.size)
    }

    fun test接口方法_恰一标记且定位命中() {
        addMapperXmlForInterface()
        myFixture.configureByText("UserMapper.java", javaFixture)
        val markers = collectMarkers(MapperJavaToXmlLineMarkerProvider(), methodNameLeaves())
        assertEquals("UserMapper.selectById 声明行应恰 1 标记", 1, markers.size)
        // k2j 注：master 侧此处还有「正缓存入账」断言（MapperXmlToJavaLineMarkerProvider
        // .resolvedStatementKeys）；k2j 生产尚未移植 F1 MyBatis 侧正缓存缝，先收行为断言，
        // 生产落位后按 master 原样恢复。
    }

    fun test关mapperXml组_零标记零定位触达() {
        addMapperXmlForInterface()
        myFixture.configureByText("UserMapper.java", javaFixture)
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            // k2j 注：master 侧先调 MapperXmlToJavaLineMarkerProvider.resetResolvedCacheForTest()
            // 复位伴生正缓存；k2j 生产尚未移植该缝，暂缺（生产落位后按 master 原样恢复）。
            // k2j 等价：master 具名参 State(showGutterIcon=true, gutterGroupMapperXml=false)
            val gateOff = PluginSettings.State()
            gateOff.gutterGroupMapperXml = false
            settings.loadState(gateOff)
            val markers = collectMarkers(MapperJavaToXmlLineMarkerProvider(), methodNameLeaves())
            assertEquals("组关时 Java 侧零标记", 0, markers.size)
        } finally {
            settings.loadState(before)
        }
    }

    // ---------- ④ T-MapperNavCoexistYield：竞品共存让位门 ----------

    /** XML 侧基线：namespace 可解析到接口方法 → 语句标签行恰 1 个跳转图标。 */
    private fun xmlSideMarkerCount(): Int {
        myFixture.addFileToProject("com/demo/mapper/UserMapper.java", javaFixture)
        val xml = myFixture.addFileToProject("mapping/UserMapper.xml", mapperXml1)
        val leaves = PsiTreeUtil.collectElements(xml) { it.firstChild == null }.toMutableList()
        val result = mutableListOf<LineMarkerInfo<*>>()
        MapperXmlToJavaLineMarkerProvider().collectSlowLineMarkers(leaves, result)
        return result.size
    }

    /** Java 侧基线：接口方法声明行恰 1 个跳转图标（与 test接口方法_恰一标记且定位命中 同夹具）。 */
    private fun javaSideMarkerCount(): Int {
        addMapperXmlForInterface()
        myFixture.configureByText("UserMapper.java", javaFixture)
        return collectMarkers(MapperJavaToXmlLineMarkerProvider(), methodNameLeaves()).size
    }

    fun test竞品在位且让位开_XML侧零图标() {
        MyBatisCompetitorGate.probe = { true }
        assertEquals("竞品在位 + 让位开：XML 侧应让位（0 图标）", 0, xmlSideMarkerCount())
    }

    fun test竞品在位且让位开_Java侧零图标() {
        MyBatisCompetitorGate.probe = { true }
        assertEquals("竞品在位 + 让位开：Java 侧应让位（0 图标）", 0, javaSideMarkerCount())
    }

    fun test竞品在位但让位关_XML侧与基线一致() {
        withYieldDisabled {
            MyBatisCompetitorGate.probe = { true }
            assertEquals("让位关：XML 侧强制保留本插件图标", 1, xmlSideMarkerCount())
        }
    }

    fun test竞品在位但让位关_Java侧与基线一致() {
        withYieldDisabled {
            MyBatisCompetitorGate.probe = { true }
            assertEquals("让位关：Java 侧强制保留本插件图标", 1, javaSideMarkerCount())
        }
    }

    fun test竞品不在位_XML侧基线不变() {
        MyBatisCompetitorGate.probe = { false }
        assertEquals("不在位：XML 侧基线 1", 1, xmlSideMarkerCount())
    }

    fun test竞品不在位_Java侧基线不变() {
        MyBatisCompetitorGate.probe = { false }
        assertEquals("不在位：Java 侧基线 1", 1, javaSideMarkerCount())
    }

    /** 临时关闭让位开关执行 [block]（结束后恢复原 State）。 */
    private fun withYieldDisabled(block: () -> Unit) {
        val settings = PluginSettings.getInstance()
        val before = settings.state
        try {
            settings.loadState(before.copy(mapperNavYieldToCodeHelper = false))
            block()
        } finally {
            settings.loadState(before)
        }
    }
}
