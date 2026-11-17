package cn.song.mybatisplus.visualizer.xml.ibatis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iBatis 2.x 识别器纯逻辑用例（T-Ibatis2Support 券 A 第 1 条）。
 *
 * 零 PSI：只打 [IbatisXmlRecognizer.recognize] 的四参重载，四条输入全显式构造——
 * 判定表逐行钉死「三选二」的每一档，含三条实测口径的反例（Apache publicId 不算证据、
 * 单条信号不认领、MyBatis 形态不误判）。
 */
class IbatisXmlRecognizerTest {

    private val sqlMapSystemId = "http://ibatis.apache.org/dtd/sql-map-2.dtd"
    private val sqlMapLegacySystemId = "http://www.ibatis.com/dtd/sql-map-2.dtd"
    private val sqlMapConfigSystemId = "http://ibatis.apache.org/dtd/sql-map-config-2.dtd"
    private val sqlMapConfigLegacySystemId = "http://www.ibatis.com/dtd/sql-map-config-2.dtd"
    private val ibatisComPublicId = "-//iBATIS.com//DTD SQL Map 2.0//EN"
    private val ibatisApachePublicId = "-//ibatis.apache.org//DTD SQL Map 2.0//EN"

    /** 不是 iBatis 的 publicId（实测：仅出现在 iBatis jar 的 manifest Implementation-Vendor 里）。 */
    private val apacheFoundationPublicId = "-//Apache Software Foundation//DTD SQL Map 2.0//EN"

    private fun verdict(
        root: String? = null,
        publicId: String? = null,
        systemId: String? = null,
        classpath: Boolean = false,
    ) = IbatisXmlRecognizer.recognize(root, publicId, systemId, classpath)

    // ---------- 1. 三条信号两两组合（都判是） ----------

    @Test
    fun `根元素加DOCTYPE_判是`() {
        val v = verdict(root = "sqlMap", systemId = sqlMapSystemId)
        assertTrue("根元素 + DOCTYPE 两条信号应判是", v.isIbatis)
        assertEquals(
            setOf(IbatisXmlRecognizer.Signal.ROOT_TAG, IbatisXmlRecognizer.Signal.DOCTYPE),
            v.signals,
        )
    }

    @Test
    fun `根元素加classpath_判是`() {
        val v = verdict(root = "sqlMap", classpath = true)
        assertTrue("根元素 + classpath 两条信号应判是", v.isIbatis)
        assertEquals(
            setOf(IbatisXmlRecognizer.Signal.ROOT_TAG, IbatisXmlRecognizer.Signal.CLASSPATH),
            v.signals,
        )
    }

    @Test
    fun `DOCTYPE加classpath_判是`() {
        val v = verdict(publicId = ibatisComPublicId, classpath = true)
        assertTrue("DOCTYPE + classpath 两条信号应判是（无根元素也认——片段文件/未解析形态）", v.isIbatis)
        assertEquals(
            setOf(IbatisXmlRecognizer.Signal.DOCTYPE, IbatisXmlRecognizer.Signal.CLASSPATH),
            v.signals,
        )
    }

    @Test
    fun `三条信号齐备_判是且信号集为三条`() {
        val v = verdict(root = "sqlMapConfig", publicId = ibatisApachePublicId, classpath = true)
        assertTrue(v.isIbatis)
        assertEquals(3, v.signalCount)
    }

    // ---------- 2. 单条信号不认领（三选二的必要性） ----------

    @Test
    fun `仅根元素_判否`() {
        val v = verdict(root = "sqlMap")
        assertFalse("只有根元素一条信号必须判否（裸 sqlMap 不是运行时可用形态，且仓里可能有死文件）", v.isIbatis)
        assertEquals(setOf(IbatisXmlRecognizer.Signal.ROOT_TAG), v.signals)
    }

    @Test
    fun `仅DOCTYPE_判否`() {
        val v = verdict(publicId = ibatisComPublicId, systemId = sqlMapSystemId)
        assertFalse("只信 DOCTYPE 会误认领未注册的片段文件", v.isIbatis)
        assertEquals(setOf(IbatisXmlRecognizer.Signal.DOCTYPE), v.signals)
    }

    @Test
    fun `仅classpath_判否`() {
        val v = verdict(classpath = true)
        assertFalse("classpath 有 iBatis 只说明工程在用 iBatis，单条不足以认领这个文件", v.isIbatis)
        assertEquals(setOf(IbatisXmlRecognizer.Signal.CLASSPATH), v.signals)
    }

    // ---------- 3. 实测口径：Apache publicId 容忍但不作证据 ----------

    @Test
    fun `ApacheSoftwareFoundation的publicId不作证据_单条判否`() {
        // 「容忍」= 识别器没有针对它的否决逻辑，只看它不构成 DOCTYPE 信号
        val v = verdict(root = "sqlMap", publicId = apacheFoundationPublicId)
        assertFalse("该 publicId 不是 iBatis 的（jar 内只出现在 manifest）", v.isIbatis)
        assertEquals(setOf(IbatisXmlRecognizer.Signal.ROOT_TAG), v.signals)

        val onlyApache = verdict(publicId = apacheFoundationPublicId)
        assertFalse(onlyApache.isIbatis)
        assertTrue("Apache publicId 不构成任何信号", onlyApache.signals.isEmpty())
    }

    @Test
    fun `Apache的publicId不影响另两条信号生效_判是`() {
        // 容忍的第二层含义：它不会把已经够阈值的文件拉成「否」
        val v = verdict(root = "sqlMap", publicId = apacheFoundationPublicId, classpath = true)
        assertTrue(v.isIbatis)
        assertEquals(
            setOf(IbatisXmlRecognizer.Signal.ROOT_TAG, IbatisXmlRecognizer.Signal.CLASSPATH),
            v.signals,
        )
    }

    // ---------- 4. 防误判 MyBatis 3 ----------

    @Test
    fun `mapper根与configuration根都判否`() {
        // MyBatis 3 的 mapper（带 namespace）与 configuration：既无 iBatis 根名，也无 iBatis DOCTYPE
        listOf("mapper", "configuration", "MyBatisMapper", "sql").forEach { root ->
            val v = verdict(root = root)
            assertFalse("MyBatis 形态不得误判：root=$root", v.isIbatis)
            assertTrue("MyBatis 根元素不应产出任何信号：root=$root", v.signals.isEmpty())
        }
    }

    @Test
    fun `MyBatis的DTD与iBatis不互认`() {
        // MyBatis 3 DTD（mybatis-3-mapper.dtd / mybatis-3-config.dtd）不在 iBatis 表内
        val mapper = verdict(
            root = "mapper",
            publicId = "-//mybatis.org//DTD Mapper 3.0//EN",
            systemId = "http://mybatis.org/dtd/mybatis-3-mapper.dtd",
        )
        assertFalse(mapper.isIbatis)
        assertTrue(mapper.signals.isEmpty())
    }

    // ---------- 5. DOCTYPE 合法值全表（含配置版） ----------

    @Test
    fun `四条systemId与四条publicId都算DOCTYPE信号`() {
        listOf(
            sqlMapSystemId,
            sqlMapLegacySystemId,
            sqlMapConfigSystemId,
            sqlMapConfigLegacySystemId,
        ).forEach { systemId ->
            assertTrue(
                "systemId 应命中 DOCTYPE 信号：$systemId",
                IbatisXmlRecognizer.Signal.DOCTYPE in verdict(systemId = systemId).signals,
            )
        }
        listOf(
            ibatisComPublicId,
            ibatisApachePublicId,
            "-//iBATIS.com//DTD SQL Map Config 2.0//EN",
            "-//ibatis.apache.org//DTD SQL Map Config 2.0//EN",
        ).forEach { publicId ->
            assertTrue(
                "publicId 应命中 DOCTYPE 信号：$publicId",
                IbatisXmlRecognizer.Signal.DOCTYPE in verdict(publicId = publicId).signals,
            )
        }
    }

    @Test
    fun `根元素名带空白与大小写仍命中`() {
        // 解析器给的根名可能带空白（片段/占用时）；大小写容忍不放大误判面（表内只有 iBatis 专有词）
        assertTrue(IbatisXmlRecognizer.Signal.ROOT_TAG in verdict(root = " sqlMap ").signals)
        assertTrue(IbatisXmlRecognizer.Signal.ROOT_TAG in verdict(root = "SQLMAP").signals)
        assertTrue(IbatisXmlRecognizer.Signal.ROOT_TAG in verdict(root = "SqlMapConfig").signals)
        assertFalse(IbatisXmlRecognizer.Signal.ROOT_TAG in verdict(root = "sqlMapConfigX").signals)
    }

    @Test
    fun `DOCTYPE取值带首尾空白仍命中`() {
        assertTrue(
            IbatisXmlRecognizer.Signal.DOCTYPE in verdict(systemId = "  $sqlMapSystemId  ").signals,
        )
    }

    @Test
    fun `全空输入判否且零信号`() {
        val v = verdict()
        assertFalse(v.isIbatis)
        assertTrue(v.signals.isEmpty())
        assertEquals(0, v.signalCount)
    }
}
