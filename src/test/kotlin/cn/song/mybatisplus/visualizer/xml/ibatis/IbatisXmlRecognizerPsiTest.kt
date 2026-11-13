package cn.song.mybatisplus.visualizer.xml.ibatis

import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 识别器薄封装（`PsiFile` → 证据）用例（T-Ibatis2Support 券 A 第 1 条）。
 *
 * 纯逻辑的判定表见 [IbatisXmlRecognizerTest]；本类只钉**取证缝**：根标签名与
 * `file.document.prolog.doctype` 的 publicId / systemId 真能从 PSI 取到，且缺省形态
 * （仅 SYSTEM 的 DOCTYPE、非 XML 文件）不抛异常。
 *
 * 测试工程 classpath 上没有 `com.ibatis.sqlmap.client.SqlMapClient`（仓内无 iBatis 依赖），
 * 故本类里 CLASSPATH 信号恒缺席——每个断言的信号集合都是「根元素 / DOCTYPE」两档。
 *
 * 用例名为 JUnit3 形态（`fun testXxx`）：基类是平台 JUnit3 测试基类，按方法名收集用例，
 * `@Test` 注解不参与判定（见 `convention/Junit3SemanticsGuardTest`）。
 */
class IbatisXmlRecognizerPsiTest : BasePlatformTestCase() {

    private fun xmlFile(name: String, text: String): PsiFile {
        myFixture.configureByText(name, text)
        return myFixture.file
    }

    /** 用户真机形态的 iBatis 语句映射文件头（PUBLIC + systemId 两段都在）。 */
    private val sqlMapSample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE sqlMap PUBLIC "-//iBATIS.com//DTD SQL Map 2.0//EN" "http://www.ibatis.com/dtd/sql-map-2.dtd">
        <sqlMap namespace="bsRwdGroupInfo">
            <select id="queryByOrgId" parameterClass="java.lang.String" resultClass="java.util.HashMap">
                SELECT * FROM bs_rwd_group_info_his WHERE ORGID = #orgId#
            </select>
        </sqlMap>
    """.trimIndent()

    fun test根元素与DOCTYPE两段证据都从PSI取到_判是() {
        val file = xmlFile("bs_RwdGroupInfo_SqlMap.xml", sqlMapSample)
        // 前置断言（防夹具退化成解析不出根元素的空转）：PSI 侧确实读到了根标签
        assertEquals("sqlMap", (file as XmlFile).rootTag?.name)

        val verdict = IbatisXmlRecognizer.recognize(file)
        assertTrue("根元素 + DOCTYPE 两条信号应判是：${verdict.signals}", verdict.isIbatis)
        assertEquals(
            setOf(IbatisXmlRecognizer.Signal.ROOT_TAG, IbatisXmlRecognizer.Signal.DOCTYPE),
            verdict.signals,
        )
    }

    fun test仅SYSTEM形态的DOCTYPE_publicId为null也不抛异常且DOCTYPE信号在场() {
        val file = xmlFile(
            "config_SqlMap.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE sqlMap SYSTEM "http://www.ibatis.com/dtd/sql-map-2.dtd">
            <sqlMap namespace="x">
                <select id="a" resultClass="java.lang.String">SELECT 1 FROM dual</select>
            </sqlMap>
            """.trimIndent(),
        )
        val verdict = IbatisXmlRecognizer.recognize(file)
        assertTrue("SYSTEM 形态应经 systemId 命中 DOCTYPE：${verdict.signals}", verdict.isIbatis)
        assertEquals(
            setOf(IbatisXmlRecognizer.Signal.ROOT_TAG, IbatisXmlRecognizer.Signal.DOCTYPE),
            verdict.signals,
        )
    }

    fun testMyBatis3的mapper文件不误判且零信号() {
        val file = xmlFile(
            "UserMapper.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.UserMapper">
                <select id="selectById" resultType="com.example.User">SELECT * FROM user WHERE id = #{id}</select>
            </mapper>
            """.trimIndent(),
        )
        val verdict = IbatisXmlRecognizer.recognize(file)
        assertFalse("MyBatis 3 文件不得被认领", verdict.isIbatis)
        assertTrue("MyBatis 3 文件不应产出任何信号：${verdict.signals}", verdict.signals.isEmpty())
    }

    fun test非XML文件不抛异常且判否() {
        val file = xmlFile("plain.txt", "not xml at all")
        val verdict = IbatisXmlRecognizer.recognize(file)
        assertFalse(verdict.isIbatis)
        assertTrue("非 XML 文件无信号：${verdict.signals}", verdict.signals.isEmpty())
    }
}
