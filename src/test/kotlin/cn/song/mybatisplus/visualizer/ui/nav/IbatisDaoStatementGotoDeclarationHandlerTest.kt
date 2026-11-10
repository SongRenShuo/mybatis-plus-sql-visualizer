package cn.song.mybatisplus.visualizer.ui.nav

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/**
 * T-Ibatis2Support 券 F-2（车道 L8）：Java DAO → iBatis XML 语句跳转用例。
 *
 * 形态说明（与 `MapperNavigationGotoDeclarationHandlerTest` 同口径）：测试夹具**不自动加载
 * plugin.xml 的 EP**，故直接驱动 [IbatisDaoStatementGotoDeclarationHandler]；EP 接线与
 * 「尾部追加」纪律由类尾 plugin.xml 文本守卫钉住。
 *
 * 反向验证口径：摘掉 `statementIdOf` 的入口方法名判定（入口方法集置空）→
 * `testF2_点号id_Java到XML语句落点` 必红（补丁见 `dev-support/reverse-probes/`）。
 *
 * 零影响判据：无 sqlMap 上下文（普通 Java 类 / 目标不存在）与 `<mapper>` 文件一律零跳转。
 */
class IbatisDaoStatementGotoDeclarationHandlerTest : BasePlatformTestCase() {

    private val handler = IbatisDaoStatementGotoDeclarationHandler()

    override fun setUp() {
        super.setUp()
        IbatisXmlStatementTargets.clearCachesForTest()
    }

    override fun tearDown() {
        try {
            IbatisXmlStatementTargets.clearCachesForTest()
        } finally {
            super.tearDown()
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * DAO 夹具：`client` 故意声明为 `Object` —— 识别是**语法级**（方法名 + 首参字面量），
     * 不依赖 `SqlMapExecutor` 类型可解析（真实工程里该客户端多为 Spring 代理注入）。
     */
    private fun addDao(owner: String = "com.demo.UserDao"): PsiFile = myFixture.addFileToProject(
        owner.replace('.', '/') + ".java",
        """
        package ${owner.substringBeforeLast('.')};
        public class ${owner.substringAfterLast('.')} {
            private Object client;
            public Object byDotted(String p) { return client.queryForObject("user.selectById", p); }
            public Object byBare(String p) { return client.queryForObject("selectBare", p); }
            public Object byNonEntry(String p) { return client.selectOne("user.otherStatement", p); }
            public Object byLateLiteral(String p) { return client.queryForObject(p, "user.lateLiteral"); }
            public Object byUnknownTarget(String p) { return client.queryForObject("user.noSuchStatement", p); }
        }
        """.trimIndent(),
    )

    private fun addCallback(owner: String, literal: String): PsiFile = myFixture.addFileToProject(
        owner.replace('.', '/') + ".java",
        """
        package ${owner.substringBeforeLast('.')};
        public class ${owner.substringAfterLast('.')} {
            private Object client;
            public Object call() { return client.queryForObject("$literal"); }
        }
        """.trimIndent(),
    )

    private fun addSqlMap(
        body: String,
        namespace: String? = "user",
        path: String = "sqlmap/UserSqlMap.xml",
    ): PsiFile {
        val nsAttr = if (namespace.isNullOrEmpty()) "" else " namespace=\"$namespace\""
        return myFixture.addFileToProject(path, "<sqlMap$nsAttr>\n$body\n</sqlMap>")
    }

    private fun addMapperFile(
        path: String = "mapper/UserMapper.xml",
        namespace: String = "user",
    ): PsiFile = myFixture.addFileToProject(
        path,
        "<mapper namespace=\"$namespace\">\n<select id=\"selectById\">SELECT 1</select>\n</mapper>",
    )

    private fun firstLiteralValue(call: PsiMethodCallExpression): String? =
        (call.argumentList?.expressions?.firstOrNull() as? PsiLiteralExpression)?.value as? String

    /** 首个实参即 [statementId] 的调用（F-2 的正例形态）。 */
    private fun callOf(file: PsiFile, statementId: String): PsiMethodCallExpression =
        PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .first { firstLiteralValue(it) == statementId }

    /** 实参表里任一处出现 [literal] 的调用（用于「字面量不在首位」负例）。 */
    private fun callContainingLiteral(file: PsiFile, literal: String): PsiMethodCallExpression =
        PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .first { call ->
                call.argumentList?.expressions.orEmpty().any { (it as? PsiLiteralExpression)?.value == literal }
            }

    private fun methodNameOf(call: PsiMethodCallExpression): PsiElement =
        requireNotNull(call.methodExpression.referenceNameElement) { "调用名元素缺失：${call.text}" }

    /** handler 不读 editor/offset（纯元素形态判定），传 null 即可。 */
    private fun targets(element: PsiElement): List<PsiElement> =
        handler.getGotoDeclarationTargets(element, element.textOffset, null)?.toList().orEmpty()

    private fun assertAnchor(target: PsiElement, expectedId: String, expectedFile: String) {
        assertTrue("落点应是 id 属性值元素，实际 ${target.javaClass.name}", target is XmlAttributeValue)
        assertEquals("落点值", expectedId, (target as XmlAttributeValue).value)
        assertEquals("落点所属文件", expectedFile, target.containingFile?.name)
        assertEquals("落点须落在 id 属性上", "id", (target.parent as? com.intellij.psi.xml.XmlAttribute)?.name)
    }

    // ------------------------------------------------------------------
    // 正例：ns.id 与裸 id
    // ------------------------------------------------------------------

    fun testF2_点号id_Java到XML语句落点() {
        val xml = addSqlMap("<select id=\"selectById\">SELECT 1</select>")
        val java = addDao()

        val result = targets(methodNameOf(callOf(java, "user.selectById")))

        assertEquals("ns.id 应给出唯一落点", 1, result.size)
        assertAnchor(result[0], "selectById", "UserSqlMap.xml")
        assertSame("落点须在同一个 sqlMap 文件内", xml, result[0].containingFile)
    }

    fun testF2_裸id_Java到XML语句落点() {
        addSqlMap("<select id=\"selectBare\">SELECT 1</select>")
        val java = addDao()

        val result = targets(methodNameOf(callOf(java, "selectBare")))

        assertEquals("裸 id 应经全项目 sqlMap 语句表命中", 1, result.size)
        assertAnchor(result[0], "selectBare", "UserSqlMap.xml")
    }

    /** 光标落在字符串字面量本身上也必须接管（用户 Ctrl+Click 常点在 id 文本上）。 */
    fun testF2_字面量元素上_同样接管() {
        addSqlMap("<select id=\"selectById\">SELECT 1</select>")
        val java = addDao()

        val literal = callOf(java, "user.selectById").argumentList.expressions.first()
        val result = targets(literal)

        assertEquals(1, result.size)
        assertAnchor(result[0], "selectById", "UserSqlMap.xml")
    }

    /** 6 类语句标签全覆盖：`<statement>`（手写 `?`）同样参与判定（本仓券 D-7 手写 ? 形态）。 */
    fun testF2_statement标签_可跳() {
        addSqlMap("<statement id=\"selectById\">SELECT * FROM t WHERE c = ?</statement>")
        val java = addDao()

        val result = targets(methodNameOf(callOf(java, "user.selectById")))

        assertEquals(1, result.size)
        assertAnchor(result[0], "selectById", "UserSqlMap.xml")
    }

    // ------------------------------------------------------------------
    // 负例：零跳转（含「非 iBatis 文件零影响」三档）
    // ------------------------------------------------------------------

    fun testF2_非入口方法名_不接管() {
        addSqlMap("<select id=\"otherStatement\">SELECT 1</select>")
        val java = addDao()

        val element = methodNameOf(callContainingLiteral(java, "user.otherStatement"))
        assertEquals("selectOne", (element.parent as com.intellij.psi.PsiReferenceExpression).referenceName)
        assertTrue("selectOne 不在 SqlMapExecutor 入口表内，不得接管", targets(element).isEmpty())
    }

    fun testF2_首参非字面量_不接管() {
        addSqlMap("<select id=\"lateLiteral\">SELECT 1</select>")
        val java = addDao()

        val call = callContainingLiteral(java, "user.lateLiteral")
        assertEquals("首参不是字面量（形态不符）", null, firstLiteralValue(call))
        assertTrue("首参非 String 字面量时不得接管（也不得退而取第二参字面量）", targets(methodNameOf(call)).isEmpty())
    }

    fun testF2_目标不存在_返回空不抛异常() {
        addSqlMap("<select id=\"selectById\">SELECT 1</select>")
        val java = addDao()

        assertTrue(
            "语句不存在 → 返回空（不抛异常、不接管）",
            targets(methodNameOf(callOf(java, "user.noSuchStatement"))).isEmpty(),
        )
    }

    /** 普通 Java 类（项目内无任何 sqlMap）→ 零跳转。 */
    fun testF2_无sqlMap文件_零跳转() {
        val java = addDao()

        assertTrue("无 sqlMap 上下文时零跳转", targets(methodNameOf(callOf(java, "user.selectById"))).isEmpty())
        assertTrue("裸 id 同样零跳转", targets(methodNameOf(callOf(java, "selectBare"))).isEmpty())
    }

    /** MyBatis `<mapper>` 文件不参与 iBatis 解析（`<mapper>` 走原路径，零跨链干扰）。 */
    fun testF2_mapper文件不参与_零跳转() {
        addMapperFile()
        val java = addDao()

        assertTrue(
            "mapper 文件不是 sqlMap，iBatis 跳转不得认领",
            targets(methodNameOf(callOf(java, "user.selectById"))).isEmpty(),
        )
        assertTrue("裸 id 也不得命中 mapper 语句", targets(methodNameOf(callOf(java, "selectBare"))).isEmpty())
    }

    /** 空白 statementId 一律不接管。 */
    fun testF2_空白statementId_不接管() {
        addSqlMap("<select id=\"selectById\">SELECT 1</select>")
        val java = myFixture.addFileToProject(
            "com/demo/BlankDao.java",
            """
            package com.demo;
            public class BlankDao {
                private Object client;
                public Object blank(String p) { return client.queryForObject("   ", p); }
            }
            """.trimIndent(),
        )
        assertTrue("空白 statementId 不得接管", targets(methodNameOf(callOf(java, "   "))).isEmpty())
    }

    // ------------------------------------------------------------------
    // 多候选：同 namespace 优先
    // ------------------------------------------------------------------

    fun testF2_同名语句多文件_同namespace优先() {
        addSqlMap("<select id=\"dup\">SELECT 1</select>", namespace = "com.demo.UserDao", path = "sqlmap/UserSqlMap.xml")
        addSqlMap("<select id=\"dup\">SELECT 2</select>", namespace = "other.ns", path = "sqlmap/OtherSqlMap.xml")
        val java = addCallback("com.demo.UserDao", "dup")

        val result = targets(methodNameOf(callOf(java, "dup")))

        assertEquals("多候选应全部给出（平台弹选择器）", 2, result.size)
        assertEquals(
            "所属类限定名与 sqlMap namespace 相同的文件应排首位",
            "UserSqlMap.xml",
            result[0].containingFile?.name,
        )
    }

    // ------------------------------------------------------------------
    // 缓存：裸 id 重复解析不重复实扫（热路径零索引查询的测试侧读数）
    // ------------------------------------------------------------------

    fun testF2_裸id重复解析_命中memo不重复实扫() {
        addSqlMap("<select id=\"selectBare\">SELECT 1</select>")
        val java = addDao()
        IbatisXmlStatementTargets.indexScanCount.set(0)
        IbatisXmlStatementTargets.statementScanCount.set(0)

        val element = methodNameOf(callOf(java, "selectBare"))
        assertEquals(1, targets(element).size)
        assertEquals("首次解析应有一次文件清单扫描", 1, IbatisXmlStatementTargets.indexScanCount.get())
        val scans = IbatisXmlStatementTargets.statementScanCount.get()
        assertEquals("首次解析应有一次语句实扫", 1, scans)

        assertEquals(1, targets(element).size)
        assertEquals("二次解析不得重扫文件清单", 1, IbatisXmlStatementTargets.indexScanCount.get())
        assertEquals("同 id 二次解析应命中 memo", scans, IbatisXmlStatementTargets.statementScanCount.get())
    }

    // ------------------------------------------------------------------
    // EP 接线与「尾部追加」纪律
    // ------------------------------------------------------------------

    private fun pluginXml(): String =
        javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)

    fun testF2_pluginXml_gotoDeclarationHandler接线在场() {
        assertTrue(
            "plugin.xml 须注册 <gotoDeclarationHandler implementation=…IbatisDaoStatementGotoDeclarationHandler>",
            Regex(
                "<gotoDeclarationHandler\\s+implementation=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.nav\\.IbatisDaoStatementGotoDeclarationHandler\"",
            ).containsMatchIn(pluginXml()),
        )
    }

    /**
     * 「只准尾部追加」纪律（L8 派活硬约束 2）：iBatis 两条注册必须落在**文件中最后一个**
     * `<extensions defaultExtensionNs="com.intellij">` 块内，且位于既有 MyBatis 注册段之后。
     * 防后续有人把 iBatis 条目并进中段（与 L4 的 codeLineMarkerProvider 区域同一文件）。
     */
    fun testF2_pluginXml_iBatis注册块为尾部追加() {
        val xml = pluginXml()
        val marker = "<extensions defaultExtensionNs=\"com.intellij\">"
        val blockStart = xml.lastIndexOf(marker)
        assertTrue("iBatis 注册块须存在", blockStart >= 0)

        val block = xml.substring(blockStart)
        assertTrue("尾部块须含 iBatis 引用贡献器", block.contains("IbatisXmlIdReferenceContributor"))
        assertTrue("尾部块须含 iBatis 跳转 handler", block.contains("IbatisDaoStatementGotoDeclarationHandler"))
        assertFalse(
            "iBatis 块之后不得再有 <extensions 块（必须为文件尾部追加，不得并入中段）",
            xml.substring(blockStart + marker.length).contains("<extensions"),
        )
        val mybatisRef = xml.indexOf("MybatisXmlReferenceContributor")
        assertTrue("既有 MyBatis 注册段须在 iBatis 块之前（未被打乱）", mybatisRef in 0 until blockStart)
    }

    /** 快捷键不私有：iBatis 注册块内不得出现 keymap 声明（键位归平台 GotoDeclaration action）。 */
    fun testF2_pluginXml_不声明私有键位() {
        val xml = pluginXml().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val blockStart = xml.lastIndexOf("<extensions defaultExtensionNs=\"com.intellij\">")
        assertTrue("iBatis 注册块须存在", blockStart >= 0)
        val block = xml.substring(blockStart)
        assertFalse("iBatis 注册块内不得出现 keyboard-shortcut：$block", block.contains("keyboard-shortcut"))
        assertFalse("iBatis 注册块内不得出现 mouse-shortcut：$block", block.contains("mouse-shortcut"))
    }
}
