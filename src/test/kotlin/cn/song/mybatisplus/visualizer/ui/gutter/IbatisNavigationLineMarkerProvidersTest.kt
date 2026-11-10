package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlToken
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame

/**
 * T-Ibatis2Gap 券 B：iBatis `<sqlMap>` 语句 ⇄ Java `SqlMapExecutor` 调用点的**双向 gutter 跳转**用例。
 *
 * 三组硬判据（对应派活单验收 1/2/4）：
 * 1. **正例**：`User.xml`（根 `sqlMap`）的语句行出跳转箭头，点击落到 Java DAO 的
 *    `queryForObject("ns.id", …)` 字面量；反向从 Java 调用行落到 XML 语句 `id` 属性值元素；
 * 2. **负例**：目标不存在 → 箭头仍在（XML→Java 为点击期解析）但点击无动作、无异常；
 *    Java→XML 目标不存在 → 无图标；`<mapper>` 文件与普通 Java 文件零新图标；
 * 3. **互斥与共存**：同一 XML 语句行 MyBatis 与 iBatis 的 marker 各归各（根标签 `mapper` vs
 *    `sqlMap`）；同一 Java 调用行 MyBatis（锚接口方法声明）不认领、iBatis（锚调用行）认领；
 *    iBatis 语句行上预览图标 + 跳转箭头**共存恰 2**、通用兜底让位（不重复注册同一锚点）。
 *
 * 形态说明（与 `IbatisDaoStatementGotoDeclarationHandlerTest` 同口径）：测试夹具不自动加载
 * plugin.xml 的 EP，直接驱动 provider 实例；EP 接线由 `PluginXmlStructureGuardTest` 系钉住。
 */
class IbatisNavigationLineMarkerProvidersTest : BasePlatformTestCase() {

    private val xmlToJava = IbatisXmlToJavaLineMarkerProvider()
    private val javaToXml = IbatisJavaToXmlLineMarkerProvider()
    private val preview = IbatisXmlGutterLineMarkerProvider()
    private val fallback = XmlInlineSqlGutterLineMarkerProvider()
    private val mybatisXmlToJava = MapperXmlToJavaLineMarkerProvider()
    private val mybatisJavaToXml = MapperJavaToXmlLineMarkerProvider()

    override fun setUp() {
        super.setUp()
        IbatisXmlStatementTargets.clearCachesForTest()
        MapperXmlLocator.clearFileListCacheForTest()
    }

    override fun tearDown() {
        try {
            IbatisXmlStatementTargets.clearCachesForTest()
            MapperXmlLocator.clearFileListCacheForTest()
            PluginSettings.getInstance().state.showGutterIcon = true
            PluginSettings.getInstance().state.gutterGroupMapperXml = true
            // 竞品探针是 object 级全局缝（MapperNavCoexistGateTest 同口径）：用完必须复位
            MyBatisCompetitorGate.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun addSqlMap(namespace: String = "user"): PsiFile = myFixture.addFileToProject(
        "sqlmap/User.xml",
        """
        <sqlMap namespace="$namespace">
          <select id="selectById">SELECT ID, USERNAME FROM T_USER WHERE ID = #id#</select>
          <select id="selectBare">SELECT ID FROM T_USER</select>
          <select id="selectNoCaller">SELECT 1 FROM T_USER</select>
          <sql id="fragment">SELECT ID FROM T_USER</sql>
        </sqlMap>
        """.trimIndent(),
    )

    private fun addDao(): PsiFile = myFixture.addFileToProject(
        "com/demo/UserDao.java",
        """
        package com.demo;
        public class UserDao {
            private Object client;
            public Object byDotted(String p) { return client.queryForObject("user.selectById", p); }
            public Object byBare(String p) { return client.queryForObject("selectBare", p); }
            public Object byUnknownId(String p) { return client.queryForObject("user.missing", p); }
            public Object byNonEntry(String p) { return client.selectOne("user.selectById", p); }
            public Object bySecondArg(String p) { return client.queryForObject(p, "user.selectById"); }
        }
        """.trimIndent(),
    )

    private fun markers(provider: LineMarkerProvider, file: PsiFile): List<LineMarkerInfo<*>> {
        val elements = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toMutableList()
        val result = mutableListOf<LineMarkerInfo<*>>()
        provider.collectSlowLineMarkers(elements, result)
        return result
    }

    /** xml 文件里标签名为 [name] 且父级是 XmlTag 的首个名字 token（语句标签的起始名在前）。 */
    private fun statementNameToken(file: PsiFile, name: String): XmlToken? =
        PsiTreeUtil.collectElementsOfType(file, XmlToken::class.java).firstOrNull {
            it.text == name && it.parent is XmlTag
        }

    /** 首参文本 == `"$literal"` 的 [methodName] 调用的方法名 identifier。 */
    private fun firstArgCallName(file: PsiFile, methodName: String, literal: String): PsiElement? =
        PsiTreeUtil.collectElementsOfType(file, PsiMethodCallExpression::class.java)
            .firstOrNull {
                it.methodExpression.referenceName == methodName &&
                    it.argumentList.expressions.firstOrNull()?.text == "\"$literal\""
            }
            ?.methodExpression?.referenceNameElement

    /** 第二参文本 == `"$literal"` 的 [methodName] 调用的方法名 identifier。 */
    private fun secondArgCallName(file: PsiFile, methodName: String, literal: String): PsiElement? =
        PsiTreeUtil.collectElementsOfType(file, PsiMethodCallExpression::class.java)
            .firstOrNull {
                it.methodExpression.referenceName == methodName &&
                    it.argumentList.expressions.getOrNull(1)?.text == "\"$literal\""
            }
            ?.methodExpression?.referenceNameElement

    private fun <T : PsiElement> navigate(marker: LineMarkerInfo<T>) {
        marker.navigationHandler!!.navigate(null, marker.element!!)
    }

    /** XML 侧 marker 的锚是语句标签起始名 token——用宿主标签的 `id` 识别语句。 */
    private fun markerStatementIds(provider: LineMarkerProvider, file: PsiFile): List<String?> =
        markers(provider, file).map { (it.element?.parent as? XmlTag)?.getAttributeValue("id") }

    private fun statementIdOf(marker: LineMarkerInfo<*>): String? =
        (marker.element?.parent as? XmlTag)?.getAttributeValue("id")

    // ------------------------------------------------------------------
    // 1. 正例：双向跳转
    // ------------------------------------------------------------------

    fun testXml语句行出箭头_点击落到Java调用字面量() {
        val dao = addDao()
        val sqlMap = addSqlMap()
        val marker = markers(xmlToJava, sqlMap).single { statementIdOf(it) == "selectById" }
        assertSame(MpvsIcons.MapperStatement, marker.icon)

        navigate(marker)
        val manager = FileEditorManager.getInstance(project)
        assertEquals(dao.virtualFile, manager.selectedFiles.single())
        val editor = manager.selectedTextEditor!!
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "user.selectById" }
        assertEquals(literal.textOffset, editor.caretModel.offset)
    }

    fun testJava调用行出箭头_点击落到Xml语句id值() {
        val dao = addDao()
        val sqlMap = addSqlMap()
        val callName = firstArgCallName(dao, "queryForObject", "user.selectById")!!
        val marker = markers(javaToXml, dao).single { it.element === callName }
        assertSame(MpvsIcons.MapperMethod, marker.icon)

        navigate(marker)
        val manager = FileEditorManager.getInstance(project)
        assertEquals(sqlMap.virtualFile, manager.selectedFiles.single())
        val editor = manager.selectedTextEditor!!
        val idValue = PsiTreeUtil.findChildrenOfType(sqlMap, XmlAttributeValue::class.java)
            .first { it.value == "selectById" }
        assertEquals(idValue.textOffset, editor.caretModel.offset)
    }

    fun test点号id与裸id两种形态都出Java侧箭头() {
        val dao = addDao()
        addSqlMap()
        val starts = markers(javaToXml, dao).map { it.element!!.textRange.startOffset }.toSet()
        assertEquals(
            "ns.id 与裸 id 两处调用都应出箭头（byUnknownId/byNonEntry/bySecondArg 三处负例不应出）",
            setOf(
                firstArgCallName(dao, "queryForObject", "user.selectById")!!.textRange.startOffset,
                firstArgCallName(dao, "queryForObject", "selectBare")!!.textRange.startOffset,
            ),
            starts,
        )
    }

    fun test六种语句标签全部出XML侧箭头() {
        val sqlMap = myFixture.addFileToProject(
            "sqlmap/All.xml",
            """
            <sqlMap namespace="all">
              <select id="s">SELECT 1 FROM T</select>
              <insert id="i">INSERT INTO T (A) VALUES (#a#)</insert>
              <update id="u">UPDATE T SET A = #a#</update>
              <delete id="d">DELETE FROM T</delete>
              <statement id="st">BEGIN; END;</statement>
              <procedure id="p">CALL P(#x#)</procedure>
            </sqlMap>
            """.trimIndent(),
        )
        val names = markers(xmlToJava, sqlMap).map { it.element?.text }
        assertEquals(listOf("select", "insert", "update", "delete", "statement", "procedure"), names)
    }

    fun test一语句一锚_闭合名与片段不重复出箭头() {
        addDao()
        val sqlMap = addSqlMap()
        // 3 条语句（selectById/selectBare/selectNoCaller）各恰 1 个箭头；闭合名 token 与 <sql> 片段不认领
        val ids = markerStatementIds(xmlToJava, sqlMap).filterNotNull()
        assertEquals(3, ids.size)
        assertEquals(
            setOf("selectById", "selectBare", "selectNoCaller"),
            ids.toSet(),
        )
    }

    fun test无namespace的sqlMap按语句结构锚定() {
        addDao()
        val sqlMap = myFixture.addFileToProject(
            "sqlmap/Bare.xml",
            "<sqlMap><select id=\"selectBare\">SELECT ID FROM T_USER</select></sqlMap>",
        )
        val names = markers(xmlToJava, sqlMap).map { it.element?.text }
        assertEquals(listOf("select"), names)
    }

    // ------------------------------------------------------------------
    // 2. 负例
    // ------------------------------------------------------------------

    fun testXML侧目标不存在_箭头仍在但点击无动作无异常() {
        addDao()
        val sqlMap = addSqlMap()
        val marker = markers(xmlToJava, sqlMap).single { statementIdOf(it) == "selectNoCaller" }
        // 点击期才解析（券 B 口径）：无 Java 调用点 → 无动作、无异常
        navigate(marker)
    }

    fun testJava侧目标不存在_无图标() {
        val dao = addDao()
        addSqlMap()
        val starts = markers(javaToXml, dao).map { it.element!!.textRange.startOffset }.toSet()
        assertEquals(false, starts.contains(firstArgCallName(dao, "queryForObject", "user.missing")!!.textRange.startOffset))
    }

    fun testJava侧非入口方法与第二参字面量_无图标() {
        val dao = addDao()
        addSqlMap()
        val starts = markers(javaToXml, dao).map { it.element!!.textRange.startOffset }.toSet()
        assertEquals(false, starts.contains(firstArgCallName(dao, "selectOne", "user.selectById")!!.textRange.startOffset))
        assertEquals(false, starts.contains(secondArgCallName(dao, "queryForObject", "user.selectById")!!.textRange.startOffset))
    }

    fun test普通Java文件零新图标() {
        val plain = myFixture.addFileToProject(
            "com/demo/Service.java",
            """
            package com.demo;
            public class Service {
                public Object get(String key) { return null; }
                public int count() { return 0; }
            }
            """.trimIndent(),
        )
        assertEmpty(markers(javaToXml, plain))
    }

    fun testMapper文件零iBatis图标() {
        val mapper = myFixture.addFileToProject(
            "mapper/UserMapper.xml",
            "<mapper namespace=\"com.demo.UserMapper\"><select id=\"selectById\">SELECT 1</select></mapper>",
        )
        assertEmpty(markers(xmlToJava, mapper))
    }

    // ------------------------------------------------------------------
    // 3. 互斥与共存
    // ------------------------------------------------------------------

    /**
     * T-Ibatis2Gap 收尾反证锚：竞品 MyBatisCodeHelperPro **没有 iBatis 能力**（发行包解包扫描
     * iBatis 命中 0），sqlMap / SqlMapExecutor 调用行不存在图标冲突——竞品在位时 iBatis 两个箭头
     * **必须照常产出**（不读 `mapperNavYieldToCodeHelper`）；MyBatis 那对让位行为不变（对照半边
     * 证明让位门本身在起作用、夹具没坏）。把「不让位」改回「让位」必恰本条变红（补丁见
     * `dev-support/reverse-probes/ibatis-navicon-restore-ibatis-yield-gate.patch`）。
     */
    fun test竞品在位_iBatis箭头仍产出_MyBatis对照仍让位() {
        // 竞品在位造法照 MapperNavCoexistGateTest：注入 probe（纯测试缝，零反射、零插件依赖）
        MyBatisCompetitorGate.resetForTest()
        MyBatisCompetitorGate.probe = { true }
        val dao = addDao()
        val sqlMap = addSqlMap()
        val iface = myFixture.addFileToProject(
            "com/demo/UserMapper.java",
            "package com.demo;\npublic interface UserMapper { int selectById(); }",
        )
        val mapper = myFixture.addFileToProject(
            "mapper/UserMapper.xml",
            "<mapper namespace=\"com.demo.UserMapper\"><select id=\"selectById\">SELECT 1</select></mapper>",
        )
        // iBatis 对：竞品在位照常产出（让位只会藏掉竞品根本不画的箭头）
        assertEquals(
            "竞品在位时 iBatis XML→Java 箭头必须仍产出（CodeHelperPro 无 iBatis 能力，无图标冲突）",
            true,
            markerStatementIds(xmlToJava, sqlMap).contains("selectById"),
        )
        assertEquals(
            "竞品在位时 iBatis Java→XML 箭头必须仍产出",
            true,
            markers(javaToXml, dao).map { it.element!!.textRange.startOffset }
                .contains(firstArgCallName(dao, "queryForObject", "user.selectById")!!.textRange.startOffset),
        )
        // 对照半边：MyBatis 那对在竞品在位时仍让位（行为零变化，不是夹具坏了）
        assertEmpty(markers(mybatisXmlToJava, mapper))
        assertEmpty(markers(mybatisJavaToXml, iface))

        // 对照：竞品不在位时 MyBatis 那对恢复产出——证明让位门本身在起作用
        MyBatisCompetitorGate.resetForTest()
        MyBatisCompetitorGate.probe = { false }
        assertEquals(
            "竞品不在位时 MyBatis XML→Java 应恢复产出（让位门对照基线）",
            1,
            markers(mybatisXmlToJava, mapper).size,
        )
        assertEquals(
            "竞品不在位时 MyBatis Java→XML 应恢复产出（让位门对照基线）",
            1,
            markers(mybatisJavaToXml, iface).size,
        )
    }

    fun test同一xml语句行MyBatis与iBatis各归各() {
        addDao()
        val mapper = myFixture.addFileToProject(
            "mapper/UserMapper.xml",
            "<mapper namespace=\"com.demo.UserMapper\"><select id=\"selectById\">SELECT 1</select></mapper>",
        )
        val sqlMap = addSqlMap()
        // sqlMap 文件：iBatis 侧认领，MyBatis 侧（isMapperFile 门控）零产出
        assertEquals(true, markerStatementIds(xmlToJava, sqlMap).contains("selectById"))
        assertEmpty(markers(mybatisXmlToJava, sqlMap))
        // mapper 文件：无接口方法 → MyBatis 侧零产出；iBatis 侧（根标签非 sqlMap）零产出
        assertEmpty(markers(mybatisXmlToJava, mapper))
        assertEmpty(markers(xmlToJava, mapper))
    }

    fun test同一Java调用行MyBatis与iBatis各归各() {
        val dao = addDao()
        addSqlMap()
        val callName = firstArgCallName(dao, "queryForObject", "user.selectById")!!
        // MyBatis Java→XML 锚接口方法声明（containingClass.isInterface 门控）——对调用行零产出
        assertNull(mybatisJavaToXml.getLineMarkerInfo(callName))
        assertEmpty(markers(mybatisJavaToXml, dao))
        // iBatis 侧经慢趟认领同一锚点，恰 1 个
        val onCall = markers(javaToXml, dao).filter { it.element === callName }
        assertEquals(1, onCall.size)
    }

    fun testiBatis语句行_预览与跳转共存恰2_兜底让位() {
        addDao()
        val sqlMap = addSqlMap()
        val token = statementNameToken(sqlMap, "select")!!
        val claims = listOf(
            "preview" to (preview.getLineMarkerInfo(token) != null),
            "nav" to (markers(xmlToJava, sqlMap).any { it.element === token }),
            "fallback" to (fallback.getLineMarkerInfo(token) != null),
        )
        val count = claims.count { it.second }
        assertEquals("语句行上图标通道数应为恰 2（预览 + 跳转），兜底让位：$claims", 2, count)
        assertEquals(true, claims.first { it.first == "preview" }.second)
        assertEquals(true, claims.first { it.first == "nav" }.second)
        assertEquals(false, claims.first { it.first == "fallback" }.second)
    }

    fun testsql片段行_跳转不认领_预览不认领_兜底照旧() {
        addDao()
        val sqlMap = addSqlMap()
        val token = statementNameToken(sqlMap, "sql")!!
        // 券 A 既有口径（testsql片段不被让位_仍走兜底通道）：预览不认领 <sql>，兜底照旧认领（图标无空洞）
        assertNull(preview.getLineMarkerInfo(token))
        assertNotNull(fallback.getLineMarkerInfo(token))
        // 本券跳转箭头对 <sql> 片段同样零产出（<sql> 不在 6 类语句标签表）
        assertEmpty(markers(xmlToJava, sqlMap).filter { it.element === token })
    }

    // ------------------------------------------------------------------
    // 4. 开关与分组
    // ------------------------------------------------------------------

    fun test总闸与组开关关闭_双向箭头消失() {
        val dao = addDao()
        val sqlMap = addSqlMap()
        PluginSettings.getInstance().state.showGutterIcon = false
        assertEmpty(markers(xmlToJava, sqlMap))
        assertEmpty(markers(javaToXml, dao))
        PluginSettings.getInstance().state.showGutterIcon = true
        PluginSettings.getInstance().state.gutterGroupMapperXml = false
        assertEmpty(markers(xmlToJava, sqlMap))
        assertEmpty(markers(javaToXml, dao))
        PluginSettings.getInstance().state.gutterGroupMapperXml = true
        assertEquals(true, markers(javaToXml, dao).isNotEmpty())
    }

    fun test分组登记_两个新provider归mapperXml组() {
        assertEquals("mapperXml", GutterGroups.groupOf("IbatisXmlToJavaLineMarkerProvider"))
        assertEquals("mapperXml", GutterGroups.groupOf("IbatisJavaToXmlLineMarkerProvider"))
    }
}
