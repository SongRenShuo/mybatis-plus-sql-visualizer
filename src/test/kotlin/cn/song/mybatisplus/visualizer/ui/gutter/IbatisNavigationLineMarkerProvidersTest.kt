package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.log.MapperXmlLocator
import cn.song.mybatisplus.visualizer.ui.icons.MpvsIcons
import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VfsUtil
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

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
        IbatisXmlStatementTargets.resetPerfLogForTest()
        IbatisJavaToXmlLineMarkerProvider.resetResolvedCacheForTest()
        IbatisXmlToJavaLineMarkerProvider.resetCallKeysCacheForTest()
    }

    override fun tearDown() {
        try {
            IbatisXmlStatementTargets.resetPerfLogForTest()
            IbatisJavaToXmlLineMarkerProvider.resetResolvedCacheForTest()
            IbatisXmlToJavaLineMarkerProvider.resetCallKeysCacheForTest()
            PluginSettings.getInstance().state.showGutterIcon = true
            PluginSettings.getInstance().state.gutterGroupMapperXml = true
            PluginSettings.getInstance().state.ibatisXmlEnabled = true
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
        // T-MapperClassJump：接口类名新增一枚跳 XML 图标——本类语句/方法基线统一排除它。
        return result.filterNot(::isMapperClassMarker)
    }

    private fun isMapperClassMarker(info: LineMarkerInfo<*>): Boolean {
        val id = info.element as? com.intellij.psi.PsiIdentifier ?: return false
        val owner = id.parent as? com.intellij.psi.PsiClass ?: return false
        return owner.nameIdentifier === id
    }

    /** T-MapperNavNamespaceJump 起 XML 侧多一枚「根标签 → 接口类」图标；语句基线断言需排除它。 */
    private fun isRootMapperMarker(info: LineMarkerInfo<*>): Boolean {
        val token = info.element as? com.intellij.psi.xml.XmlToken ?: return false
        val tag = token.parent as? com.intellij.psi.xml.XmlTag ?: return false
        return tag.name == "mapper" && tag.parentTag == null
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
        // T-IbatisCallPreview：调用行现在有两枚 marker（箭头锚 identifier / 预览锚 literal），
        // 本断言只看箭头那枚——按锚类型过滤，别把预览 marker 的偏移也算进来。
        val starts = markers(javaToXml, dao)
            .filter { it.element is PsiIdentifier }
            .map { it.element!!.textRange.startOffset }
            .toSet()
        assertEquals(
            "ns.id 与裸 id 两处调用都应出箭头（byUnknownId/byNonEntry/bySecondArg 三处负例不应出）",
            setOf(
                firstArgCallName(dao, "queryForObject", "user.selectById")!!.textRange.startOffset,
                firstArgCallName(dao, "queryForObject", "selectBare")!!.textRange.startOffset,
            ),
            starts,
        )
    }

    /**
     * T-IbatisCallPreview：调用行两枚图标共存——跳转箭头（锚方法名 identifier）+
     * 预览图标（锚首参 literal，与 XML 语句行预览同一枚 sqlGutter 图标），且同一行。
     */
    fun testJava调用行_预览与跳转共存恰2() {
        val dao = addDao()
        addSqlMap()
        val callName = firstArgCallName(dao, "queryForObject", "user.selectById")!!
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "user.selectById" }
        val onLine = markers(javaToXml, dao).filter { it.element === callName || it.element?.parent === literal }
        assertEquals("调用行应恰 2 枚图标（跳转 + 预览）：${onLine.map { it.icon }}", 2, onLine.size)
        assertSame(MpvsIcons.MapperMethod, onLine.single { it.element === callName }.icon)
        assertSame(
            IbatisXmlGutterLineMarkerProvider.SQL_PREVIEW_ICON,
            onLine.single { it.element?.parent === literal }.icon,
        )
        val document = dao.viewProvider.document!!
        assertEquals(
            "两枚图标必须落在同一行",
            document.getLineNumber(callName.textRange.startOffset),
            document.getLineNumber(literal.textRange.startOffset),
        )
    }

    /** T-IbatisCallPreview 拍板 D-4：`ibatisXmlEnabled` 只灭预览，跳转箭头保留。 */
    fun test预览图标受ibatisXmlEnabled约束_箭头不受() {
        val dao = addDao()
        addSqlMap()
        val callName = firstArgCallName(dao, "queryForObject", "user.selectById")!!
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == "user.selectById" }
        PluginSettings.getInstance().state.ibatisXmlEnabled = false
        val off = markers(javaToXml, dao)
        assertEquals("开关关闭：箭头保留", 1, off.count { it.element === callName })
        assertEquals("开关关闭：预览图标消失", 0, off.count { it.element?.parent === literal })
        PluginSettings.getInstance().state.ibatisXmlEnabled = true
        assertEquals(
            "开关打开：两枚恢复",
            2,
            markers(javaToXml, dao).count { it.element === callName || it.element?.parent === literal },
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
        // T-IbatisXmlArrowCallerGate：箭头按「Java 侧存在调用点」出，故六条语句各配一个调用点
        myFixture.addFileToProject(
            "com/demo/AllDao.java",
            """
            package com.demo;
            public class AllDao {
                private Object client;
                public void a() {
                    client.queryForObject("all.s", null);
                    client.insert("all.i", null);
                    client.update("all.u", null);
                    client.delete("all.d", null);
                    client.queryForList("all.st", null);
                    client.queryForObject("all.p", null);
                }
            }
            """.trimIndent(),
        )
        val names = markers(xmlToJava, sqlMap).map { it.element?.text }
        assertEquals(listOf("select", "insert", "update", "delete", "statement", "procedure"), names)
    }

    fun test一语句一锚_闭合名与片段不重复出箭头() {
        addDao()
        // selectNoCaller 在 addDao() 里没有调用点，本用例只为「一语句一锚」形态取证，补一个调用点
        myFixture.addFileToProject(
            "com/demo/ExtraDao.java",
            """
            package com.demo;
            public class ExtraDao {
                private Object client;
                public Object a() { return client.queryForObject("user.selectNoCaller", null); }
            }
            """.trimIndent(),
        )
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

    /**
     * T-IbatisXmlArrowCallerGate（2026-11-16 用户报障本体）：XML 语句 `id` 改名/调用点删除后，
     * 语句行**不得**再挂着「点了没反应」的跳转箭头。`addSqlMap()` 里 `selectNoCaller` 无调用点。
     */
    fun testXML侧无调用点_不出现跳转箭头() {
        addDao()
        val sqlMap = addSqlMap()
        assertNull(
            "无 Java 调用点的语句不得出箭头（点开必空的假图标）",
            markers(xmlToJava, sqlMap).firstOrNull { statementIdOf(it) == "selectNoCaller" },
        )
        assertEquals(
            "有调用点的两条语句箭头照旧",
            setOf("selectById", "selectBare"),
            markerStatementIds(xmlToJava, sqlMap).filterNotNull().toSet(),
        )
    }

    /**
     * T-IbatisXmlArrowNamespace（2026-11-16 用户报障「xml 跳 Java 跳错地方」）：词索引按词取候选，
     * 多命名空间工程里同名 id 遍地——旧实现还认「任意前缀的尾段 `.id`」，于是 `User.xml` 的
     * `selectById` 被 `OdLogistics60233.selectById` 抢先命中，点箭头跳到别的 DAO。
     * 命名空间是语句身份的一部分：跨命名空间同名 id 是**另一条语句**，不得互相认领。
     */
    fun test同id不同namespace不得误命中() {
        val userXml = myFixture.addFileToProject(
            "sqlmap/User.xml",
            """<sqlMap namespace="user"><select id="selectById">SELECT 1 FROM T_USER</select></sqlMap>""",
        )
        myFixture.addFileToProject(
            "sqlmap/Order.xml",
            """<sqlMap namespace="order"><select id="selectById">SELECT 2 FROM T_ORDER</select></sqlMap>""",
        )
        myFixture.addFileToProject(
            "com/demo/OrderDao.java",
            """
            package com.demo;
            public class OrderDao {
                private Object client;
                public Object a() { return client.queryForObject("order.selectById", null); }
            }
            """.trimIndent(),
        )
        assertNull(
            "user 命名空间不得命中 order.selectById（跨命名空间同 id 是另一条语句）",
            IbatisJavaCallSites.findFirstCallLiteral(project, "user", "selectById"),
        )
        assertNotNull(
            "order 命名空间仍应命中自己的调用点",
            IbatisJavaCallSites.findFirstCallLiteral(project, "order", "selectById"),
        )
        assertNull(
            "User.xml 不得因为别的命名空间有同名调用点而出箭头",
            markers(xmlToJava, userXml).firstOrNull { statementIdOf(it) == "selectById" },
        )
    }

    /** 反向：`useStatementNamespaces=false`（默认）时调用点用**裸 id**，必须照旧命中。 */
    fun test裸id形态仍命中() {
        val sqlMap = myFixture.addFileToProject(
            "sqlmap/Bare.xml",
            """<sqlMap namespace="user"><select id="selectById">SELECT 1 FROM T_USER</select></sqlMap>""",
        )
        myFixture.addFileToProject(
            "com/demo/BareDao.java",
            """
            package com.demo;
            public class BareDao {
                private Object client;
                public Object a() { return client.queryForObject("selectById", null); }
            }
            """.trimIndent(),
        )
        assertNotNull(
            "裸 id 形态必须命中（heads 关闭命名空间前缀的工程）",
            IbatisJavaCallSites.findFirstCallLiteral(project, "user", "selectById"),
        )
        assertNotNull(
            "裸 id 命中必须让语句行出箭头",
            markers(xmlToJava, sqlMap).firstOrNull { statementIdOf(it) == "selectById" },
        )
    }

    /** 回退通道：`sqlvis.gutter.ibatisXmlArrowRequiresCaller=false` 回到「无条件出箭头」（点击无目标即无动作）。 */
    fun test回退开关关闭_恢复无条件出箭头且点击无动作() {
        val original = IbatisXmlToJavaLineMarkerProvider.requiresCallerProvider
        IbatisXmlToJavaLineMarkerProvider.requiresCallerProvider = { false }
        try {
            addDao()
            val sqlMap = addSqlMap()
            val marker = markers(xmlToJava, sqlMap).single { statementIdOf(it) == "selectNoCaller" }
            navigate(marker) // 无 Java 调用点 → 无动作、无异常
        } finally {
            IbatisXmlToJavaLineMarkerProvider.requiresCallerProvider = original
        }
    }

    // ------------------------------------------------------------------
    // 2b. 箭头门槛三态矩阵（T-IbatisXmlArrowCallerGate）
    // ------------------------------------------------------------------

    fun test箭头门槛_命中调用点_出箭头并记入缓存() {
        addDao()
        val sqlMap = addSqlMap()
        assertEquals(
            "有调用点必须出箭头（selectNoCaller 无调用点 → 不出）",
            setOf("selectById", "selectBare"),
            markerStatementIds(xmlToJava, sqlMap).filterNotNull().toSet(),
        )
        assertTrue(
            "命中必须记入会话级缓存",
            IbatisXmlToJavaLineMarkerProvider.resolvedIbatisCallKeys.contains(callKey("user", "selectById")),
        )
    }

    fun test箭头门槛_确定无调用点_摘箭头并收回缓存() {
        IbatisXmlToJavaLineMarkerProvider.resolvedIbatisCallKeys.add(callKey("user", "selectById"))
        IbatisJavaCallSites.lookupOverrideForTest = { _, _, _ -> IbatisJavaCallSites.CallLookup.Absent }
        addDao()
        val sqlMap = addSqlMap()
        assertNull(
            "确定无调用点时不得再靠缓存续命",
            markers(xmlToJava, sqlMap).firstOrNull { statementIdOf(it) == "selectById" },
        )
        assertFalse(
            "确定无调用点必须收回缓存，否则下一趟慢 pass 还会把箭头画回来",
            IbatisXmlToJavaLineMarkerProvider.resolvedIbatisCallKeys.contains(callKey("user", "selectById")),
        )
    }

    fun test箭头门槛_判定未知_缓存命中仍出箭头() {
        IbatisXmlToJavaLineMarkerProvider.resolvedIbatisCallKeys.add(callKey("user", "selectById"))
        IbatisJavaCallSites.lookupOverrideForTest = { _, _, _ -> IbatisJavaCallSites.CallLookup.Unknown }
        addDao()
        val sqlMap = addSqlMap()
        assertNotNull(
            "Unknown（判不了）时缓存命中必须照常出箭头",
            markers(xmlToJava, sqlMap).firstOrNull { statementIdOf(it) == "selectById" },
        )
        assertTrue(
            "Unknown 不得动缓存",
            IbatisXmlToJavaLineMarkerProvider.resolvedIbatisCallKeys.contains(callKey("user", "selectById")),
        )
    }

    fun test箭头门槛_dumb窗乐观产出不入缓存() {
        val original = IbatisXmlToJavaLineMarkerProvider.isDumbProvider
        IbatisXmlToJavaLineMarkerProvider.isDumbProvider = { true }
        try {
            addDao()
            val sqlMap = addSqlMap()
            assertNotNull(
                "dumb 窗（词索引不可查）应乐观出箭头",
                markers(xmlToJava, sqlMap).firstOrNull { statementIdOf(it) == "selectNoCaller" },
            )
            assertFalse(
                "乐观产出不得污染缓存",
                IbatisXmlToJavaLineMarkerProvider.resolvedIbatisCallKeys.contains("user|selectNoCaller"),
            )
        } finally {
            IbatisXmlToJavaLineMarkerProvider.isDumbProvider = original
        }
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
     * **必须照常产出**（不读 `mapperNavYieldToCodeHelper`）；MyBatis 的对照半边：XML 与 Java 两侧
     * 都让位（2026-11-25 两侧让位口径）。把 iBatis「不让位」改回「让位」必恰本条变红（补丁见
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
        // 对照半边：MyBatis 的 XML 与 Java 两侧在竞品在位时都让位（2026-11-25 两侧让位口径）
        assertEmpty(markers(mybatisXmlToJava, mapper))
        assertEmpty(markers(mybatisJavaToXml, iface))

        // 对照：竞品不在位时 MyBatis 那对恢复产出——证明让位门本身在起作用
        MyBatisCompetitorGate.resetForTest()
        MyBatisCompetitorGate.probe = { false }
        assertEquals(
            "竞品不在位时 MyBatis XML→Java 应恢复产出（让位门对照基线；排除根标签图标）",
            1,
            markers(mybatisXmlToJava, mapper).count { !isRootMapperMarker(it) },
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

    // ------------------------------------------------------------------
    // T-IbatisGutterPerf P2：解析失败不摘图标——正缓存 / dumb 乐观产出判定矩阵
    // （与 MyBatis 侧 F1/F1' 矩阵同构；dumb 走 isDumbProvider 测试缝，不依赖夹具真实 dumb 态）
    // ------------------------------------------------------------------

    /** 某个调用点的两枚 marker（锚 = 方法名 identifier + 首参 literal 叶子）。 */
    private fun markersForCall(dao: PsiFile, literalValue: String): List<LineMarkerInfo<*>> {
        val callName = firstArgCallName(dao, "queryForObject", literalValue)!!
        val literal = PsiTreeUtil.findChildrenOfType(dao, PsiLiteralExpression::class.java)
            .first { it.value == literalValue }
        return markers(javaToXml, dao).filter { it.element === callName || it.element?.parent === literal }
    }

    /**
     * 正缓存键测试构造（T-PerfSweep31 券04 同步）：键带 `project.locationHash` 前缀后，
     * 断言必须与生产 [IbatisJavaToXmlLineMarkerProvider.cacheKey] 同源，不得再裸拼语句 id。
     */
    private fun cacheKey(statementId: String, ownerNamespace: String? = null): String =
        IbatisJavaToXmlLineMarkerProvider.cacheKey(project, statementId, ownerNamespace)

    /**
     * 调用点缓存键测试构造（T-PerfSweep32 收口轮同步）：[IbatisXmlToJavaLineMarkerProvider]
     * 的键同样带 `project.locationHash` 前缀，断言与生产 [IbatisXmlToJavaLineMarkerProvider.callKey]
     * 同源——箭头门槛三态矩阵的入账/收回/命中断言不得再裸拼 `namespace|id`。
     */
    private fun callKey(namespace: String, statementId: String): String =
        IbatisXmlToJavaLineMarkerProvider.callKey(project, namespace, statementId)

    fun testF1i_解析成功_入正缓存并产出两枚() {
        val dao = addDao()
        addSqlMap()
        assertEquals("解析成功应出两枚（跳转 + 预览）", 2, markersForCall(dao, "user.selectById").size)
        assertTrue(
            "解析成功必须记入会话级正缓存",
            IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys.contains(cacheKey("user.selectById")),
        )
    }

    fun testF1i_索引重建中未知_缓存命中仍产出() {
        // 冷窗形态（S2）：查询**没算完**（Unknown），不是「确定没有」——已确认过的图标必须留着。
        IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys.add(cacheKey("user.selectById"))
        IbatisXmlStatementTargets.lookupOverrideForTest = { _, _, _ -> IbatisXmlStatementTargets.Lookup.Unknown }
        val dao = addDao()
        assertEquals(
            "Unknown（暂时查不了）时缓存命中必须照常产出——慢趟按趟换装不得摘掉已确认的图标",
            2,
            markersForCall(dao, "user.selectById").size,
        )
        assertTrue(
            "Unknown 不得动缓存",
            IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys.contains(cacheKey("user.selectById")),
        )
    }

    /**
     * P2.1（用户 2026-11-16 报障）：把 `User.xml` 的 `namespace="User"` 改掉后图标必须消失。
     * 早期版本正缓存无失效，图标靠缓存续命——本用例钉住「确定不存在 ⇒ 摘图标 + 收回缓存」。
     */
    fun testF1i_确定不存在_摘图标并收回缓存() {
        IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys.add(cacheKey("user.selectById"))
        IbatisXmlStatementTargets.lookupOverrideForTest = { _, _, _ -> IbatisXmlStatementTargets.Lookup.Absent }
        val dao = addDao()
        assertEquals(
            "确定不存在（namespace 改了 / 语句删了）时不得再靠缓存续命",
            0,
            markersForCall(dao, "user.selectById").size,
        )
        assertFalse(
            "确定不存在必须收回正缓存，否则下一趟慢 pass 还会把它画回来",
            IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys.contains(cacheKey("user.selectById")),
        )
    }

    fun testF1i_解析失败且无缓存_不产出且不污染缓存() {
        val dao = addDao()
        addSqlMap()
        assertEquals("真不存在的语句不得出图标", 0, markersForCall(dao, "user.missing").size)
        assertFalse(
            "「真没有」的负例不得污染正缓存",
            IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys.contains(cacheKey("user.missing")),
        )
    }

    /** 三态内核：真实索引查询下，namespace 改掉后必须是 Absent（而不是 Unknown）——否则缓存永不收回。 */
    fun testF1i_改namespace后查询真实返回absent() {
        val sqlMap = addSqlMap()
        assertEquals(
            "改 namespace 之前必须是 Found",
            true,
            IbatisXmlStatementTargets.lookupStatementAnchors(project, "user.selectById") is
                IbatisXmlStatementTargets.Lookup.Found,
        )
        val vf = sqlMap.virtualFile
        WriteAction.run<RuntimeException> {
            VfsUtil.saveText(vf, VfsUtil.loadText(vf).replace("namespace=\"user\"", "namespace=\"userRenamed\""))
        }
        assertEquals(
            "改 namespace 之后必须落到 Absent（查询成功执行且无目标），否则正缓存不会收回；" +
                "实测=${IbatisXmlStatementTargets.lookupStatementAnchors(project, "user.selectById")::class.simpleName}",
            IbatisXmlStatementTargets.Lookup.Absent,
            IbatisXmlStatementTargets.lookupStatementAnchors(project, "user.selectById"),
        )
    }

    fun testF1i_dumb窗乐观产出且不入缓存() {
        val original = IbatisJavaToXmlLineMarkerProvider.isDumbProvider
        IbatisJavaToXmlLineMarkerProvider.isDumbProvider = { true }
        try {
            val dao = addDao()
            assertEquals(
                "dumb 窗（索引不可查）应乐观产出两枚，含真不存在的语句",
                2,
                markersForCall(dao, "user.missing").size,
            )
            assertFalse(
                "乐观产出不得污染正缓存（否则 key 会被错当成「确认存在」）",
                IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys.contains(cacheKey("user.missing")),
            )
        } finally {
            IbatisJavaToXmlLineMarkerProvider.isDumbProvider = original
        }
    }

    fun testF1i_裸id缓存键带所属类限定名() {
        val dao = addDao()
        addSqlMap()
        assertEquals(2, markersForCall(dao, "selectBare").size)
        assertTrue(
            "裸 id 的缓存键必须带所属类限定名（同一裸 id 在不同 DAO 上可能偏好不同的 sqlMap 文件）",
            IbatisJavaToXmlLineMarkerProvider.resolvedIbatisKeys.contains(cacheKey("selectBare", "com.demo.UserDao")),
        )
    }
}
