package cn.song.mybatisplus.visualizer.ui.gutter

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-MapperNavCoexistYield：共存让位门的纯逻辑面。
 *
 * 覆盖三块：
 *  ① [MyBatisCompetitorGate.shouldYield] 真值表四档——「用户开让位 × 竞品在位」的组合里
 *    只有「开 ＋ 在位」让位，其余三档都保留本插件图标（默认宁可多画，不误删功能）；
 *  ② [MyBatisCompetitorGate.isPeerPluginActive] 探针三形态——在位 / 不在位 / 探测抛异常；
 *     异常必须降级为「不在位」而不是把异常抛进 daemon 的慢趟；
 *  ③ 探测结论级缓存——插件启停需重启 IDE，同一会话内重复查询不得反复走反射（探针计数钉住）。
 *
 * 另附一条 P-1 回归锚：本机 MPJ `META-INF/plugin.xml` 实测 id 为
 * `com.ccnode.codegenerator.MyBatisCodeHelperPro`，前缀候选必须能命中它。
 *
 * 纯 JUnit（零平台 fixture）：被测对象是纯判定 + 可注入探针，不需要 IDE 实例。
 * 反向验证读数见计划 §实施记录（把 shouldYield 的竞品项短路成 false → 真值表恰 1 条红）。
 */
class MapperNavCoexistGateTest {

    @After
    fun tearDown() {
        MyBatisCompetitorGate.resetForTest()
    }

    // ---------- ① shouldYield 真值表 ----------

    @Test
    fun testShouldYield_真值表四档() {
        assertTrue("让位开 + 竞品在位 → 让位", MyBatisCompetitorGate.shouldYield(true, true))
        assertFalse("让位开 + 竞品不在位 → 保留本插件图标", MyBatisCompetitorGate.shouldYield(true, false))
        assertFalse("让位关 + 竞品在位 → 用户显式要求保留", MyBatisCompetitorGate.shouldYield(false, true))
        assertFalse("让位关 + 竞品不在位 → 保留", MyBatisCompetitorGate.shouldYield(false, false))
    }

    // ---------- ② 探针三形态 ----------

    @Test
    fun test探针_竞品在位() {
        MyBatisCompetitorGate.resetForTest()
        MyBatisCompetitorGate.probe = { true }
        assertTrue("探针报在位时应判为在位", MyBatisCompetitorGate.isPeerPluginActive())
        assertTrue(
            "在位 + 默认让位开关（true）应触发让位",
            MyBatisCompetitorGate.shouldYield(true, MyBatisCompetitorGate.isPeerPluginActive()),
        )
    }

    @Test
    fun test探针_竞品不在位() {
        MyBatisCompetitorGate.resetForTest()
        MyBatisCompetitorGate.probe = { false }
        assertFalse("探针报不在位时应判为不在位", MyBatisCompetitorGate.isPeerPluginActive())
    }

    @Test
    fun test探针_探测抛异常降级为不在位() {
        MyBatisCompetitorGate.resetForTest()
        MyBatisCompetitorGate.probe = { error("synthetic probe failure") }
        assertFalse(
            "探测抛异常必须降级为「不在位」（异常不得漏进 daemon 慢趟）",
            MyBatisCompetitorGate.isPeerPluginActive(),
        )
    }

    // ---------- ③ 探测结论缓存 ----------

    @Test
    fun test探测结论缓存_同一会话只探一次() {
        MyBatisCompetitorGate.resetForTest()
        var calls = 0
        MyBatisCompetitorGate.probe = {
            calls++
            true
        }
        assertTrue(MyBatisCompetitorGate.isPeerPluginActive())
        assertTrue(MyBatisCompetitorGate.isPeerPluginActive())
        assertTrue(MyBatisCompetitorGate.isPeerPluginActive())
        assertEquals("插件启停需重启 IDE，结论应只探一次", 1, calls)

        MyBatisCompetitorGate.resetForTest()
        MyBatisCompetitorGate.probe = {
            calls++
            true
        }
        assertTrue(MyBatisCompetitorGate.isPeerPluginActive())
        assertEquals("resetForTest 后应重新探测", 2, calls)
    }

    // ---------- P-1 回归锚 ----------

    @Test
    fun testP1_前缀候选覆盖本机实测pluginId() {
        // 本机 MPJ 3.6.8+2321 的 plugin.xml：<id>com.ccnode.codegenerator.MyBatisCodeHelperPro</id>
        val localMeasured = "com.ccnode.codegenerator.MyBatisCodeHelperPro"
        assertTrue(
            "前缀候选必须覆盖本机实测 id（$localMeasured）",
            MyBatisCompetitorGate.PEER_PLUGIN_ID_PREFIXES.any { localMeasured.startsWith(it) },
        )
    }
}
