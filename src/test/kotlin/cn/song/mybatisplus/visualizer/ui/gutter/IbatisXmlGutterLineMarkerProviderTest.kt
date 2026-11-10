package cn.song.mybatisplus.visualizer.ui.gutter

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlToken
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * iBatis `<sqlMap>` gutter provider 测试（T-Ibatis2Support 券 A 接线面）。
 *
 * 三条硬判据（与派活单验收项 1/2 一一对应）：
 * 1. **负例 + 不双图标**：`<sqlMap>` 下的语句标签由新 provider 认领，通用兜底通道
 *    （[XmlInlineSqlGutterLineMarkerProvider]）对**同一个元素**必须不再认领——两条通道的返回值
 *    互斥（`assertExactlyOneClaims` 直接对同一 token 调两个 provider）；
 * 2. **含 `<dynamic>` / `<isNotNull>` 子标签的语句也出图标**（当前缺陷的另一半：叶子启发式让这类
 *    语句一个图标都没有）——本 provider 不得抄叶子限制；
 * 3. **不误伤**：mapper / pages / hbm 三条既有 XML 通道的归属零变化，新 provider 对它们零产出。
 *
 * 另覆盖两道开关（`ibatisXmlEnabled` 单项 + `mapperXml` 组开关）与「`<sql>` 片段不接管」的
 * 图标无空洞保证（让位集合 ≡ 接管集合）。
 */
class IbatisXmlGutterLineMarkerProviderTest : BasePlatformTestCase() {

    private val provider = IbatisXmlGutterLineMarkerProvider()
    private val fallback = XmlInlineSqlGutterLineMarkerProvider()

    private fun xmlFile(text: String): XmlFile {
        myFixture.configureByText("SqlMapSample.xml", text)
        return myFixture.file as XmlFile
    }

    /** 首个名字为 [name] 的标签 name token（`XmlToken.text` 即标签名）。 */
    private fun tokenNamed(file: XmlFile, name: String): XmlToken? =
        PsiTreeUtil.collectElementsOfType(file, XmlToken::class.java).firstOrNull { it.text == name }

    private fun markerAt(file: XmlFile, name: String, p: LineMarkerProvider): LineMarkerInfo<*>? =
        tokenNamed(file, name)?.let { p.getLineMarkerInfo(it) }

    /** 同一元素上「恰一个通道认领」——即无图标空洞、也无双图标。 */
    private fun assertExactlyOneClaims(file: XmlFile, name: String) {
        val token = tokenNamed(file, name) ?: error("夹具里没有 <$name> 标签的 name token")
        val byNew = provider.getLineMarkerInfo(token) != null
        val byFallback = fallback.getLineMarkerInfo(token) != null
        assertEquals("<$name> 应由 iBatis provider 认领（new=$byNew fallback=$byFallback）", true, byNew)
        assertEquals(
            "<$name> 不得被两条通道同时认领（new=$byNew fallback=$byFallback）——让位/接管条件漂移即双图标",
            false,
            byFallback,
        )
    }

    override fun tearDown() {
        // 开关隔离：恢复默认（全开）
        PluginSettings.getInstance().state.ibatisXmlEnabled = true
        PluginSettings.getInstance().state.xmlInlineSqlEnabled = true
        PluginSettings.getInstance().state.showGutterIcon = true
        PluginSettings.getInstance().state.gutterGroupMapperXml = true
        super.tearDown()
    }

    // ------------------------------------------------------------------
    // 1. 负例 + 不双图标
    // ------------------------------------------------------------------

    fun testiBatis叶子select_新provider认领_兜底不再认领() {
        val file = xmlFile(
            "<sqlMap namespace=\"Org\">" +
                "<select id=\"findOrg\">SELECT ORG_ID FROM T_ORG WHERE ORG_ID = #orgId#</select>" +
                "</sqlMap>",
        )
        assertExactlyOneClaims(file, "select")
    }

    fun testiBatis六种语句标签全部锚定() {
        val file = xmlFile(
            "<sqlMap>" +
                "<select id=\"s\">SELECT 1 FROM T</select>" +
                "<insert id=\"i\">INSERT INTO T (A) VALUES (#a#)</insert>" +
                "<update id=\"u\">UPDATE T SET A = #a# WHERE ID = #id#</update>" +
                "<delete id=\"d\">DELETE FROM T WHERE ID = #id#</delete>" +
                "<statement id=\"st\">BEGIN; END;</statement>" +
                "<procedure id=\"p\">CALL P(#x#)</procedure>" +
                "</sqlMap>",
        )
        for (tag in listOf("select", "insert", "update", "delete", "statement", "procedure")) {
            assertExactlyOneClaims(file, tag)
        }
    }

    // ------------------------------------------------------------------
    // 2. 含动态子标签的语句必须出图标（叶子限制不得抄）
    // ------------------------------------------------------------------

    fun test含dynamic子标签的语句也出图标() {
        val file = xmlFile(
            "<sqlMap namespace=\"Org\">" +
                "<select id=\"findOrgDyn\">SELECT ORG_ID FROM T_ORG" +
                "<dynamic prepend=\"WHERE\">" +
                "<isNotNull property=\"orgId\" prepend=\"AND\">ORG_ID = #orgId#</isNotNull>" +
                "</dynamic>" +
                "</select>" +
                "</sqlMap>",
        )
        assertExactlyOneClaims(file, "select")
    }

    fun test含isNotNull子标签但无dynamic容器也出图标() {
        val file = xmlFile(
            "<sqlMap namespace=\"Org\">" +
                "<update id=\"updDyn\">UPDATE T_ORG SET NAME = #name# " +
                "<isNotNull property=\"orgId\">WHERE ORG_ID = #orgId#</isNotNull>" +
                "</update>" +
                "</sqlMap>",
        )
        assertExactlyOneClaims(file, "update")
    }

    // ------------------------------------------------------------------
    // 3. 不误伤：mapper / pages / hbm 三条既有通道零变化
    // ------------------------------------------------------------------

    fun testmapper文件走原通道_新provider零产出() {
        val file = xmlFile("<mapper namespace=\"m\"><select id=\"x\">SELECT * FROM t</select></mapper>")
        assertNotNull(
            "mapper 语句仍由 XmlSql provider 认领",
            markerAt(file, "select", XmlSqlGutterLineMarkerProvider()),
        )
        assertNull("iBatis provider 不得认领 <mapper> 下的语句", markerAt(file, "select", provider))
    }

    fun testpages文件走原通道_新provider零产出() {
        val file = xmlFile(
            "<pages><maintainPage id=\"p1\"><datasource><![CDATA[SELECT 1]]></datasource></maintainPage></pages>",
        )
        assertNotNull(
            "pages 页面标签仍由 Pages provider 认领",
            markerAt(file, "maintainPage", PagesXmlGutterLineMarkerProvider()),
        )
        assertNull("iBatis provider 不得认领 pages 文件", markerAt(file, "maintainPage", provider))
    }

    fun testhbm文件走原通道_新provider零产出() {
        val file = xmlFile(
            "<hibernate-mapping><query name=\"q\"><![CDATA[FROM A a]]></query></hibernate-mapping>",
        )
        assertNotNull(
            "hbm 命名查询仍由 Hbm provider 认领",
            markerAt(file, "query", HbmNamedQueryGutterLineMarkerProvider()),
        )
        assertNull("iBatis provider 不得认领 hbm 文件", markerAt(file, "query", provider))
    }

    fun test非sqlMap根下的同名标签_新provider零产出且兜底照旧认领() {
        val file = xmlFile("<root><select>SELECT 1 FROM T</select></root>")
        assertNull("父标签不是 <sqlMap> → iBatis provider 不得认领", markerAt(file, "select", provider))
        assertNotNull(
            "非 <sqlMap> 下的同形标签仍归通用兜底通道（既有行为零变化）",
            markerAt(file, "select", fallback),
        )
    }

    /**
     * `<sql id>` 片段（iBatis 的可复用 SQL 片段）**不在接管集合里**，因而也不在让位集合里——
     * 它照旧由通用兜底通道认领。这条钉住「让位集合 ≡ 接管集合」：让位多让一格就会让片段零图标
     * （比现状更差），这正是计划册 §券 A 反复强调的「只让位不接管」风险。
     */
    fun testsql片段不被让位_仍走兜底通道() {
        val file = xmlFile(
            "<sqlMap namespace=\"Org\">" +
                "<sql id=\"baseCols\">SELECT ORG_ID, ORG_NAME FROM T_ORG</sql>" +
                "<select id=\"findOrg\"><include refid=\"baseCols\"/></select>" +
                "</sqlMap>",
        )
        assertNull("<sql> 片段不归 iBatis provider", markerAt(file, "sql", provider))
        assertNotNull("<sql> 片段仍由通用兜底通道认领（图标无空洞）", markerAt(file, "sql", fallback))
    }

    // ------------------------------------------------------------------
    // 4. 开关
    // ------------------------------------------------------------------

    fun test单项开关关闭后不出图标() {
        PluginSettings.getInstance().state.ibatisXmlEnabled = false
        val file = xmlFile("<sqlMap><select id=\"s\">SELECT 1 FROM T</select></sqlMap>")
        assertNull("ibatisXmlEnabled=false → iBatis provider 零产出", markerAt(file, "select", provider))
    }

    fun testmapperXml组开关关闭后不出图标() {
        PluginSettings.getInstance().state.gutterGroupMapperXml = false
        val file = xmlFile("<sqlMap><select id=\"s\">SELECT 1 FROM T</select></sqlMap>")
        assertNull("mapperXml 组关闭 → iBatis provider 零产出（拍板 D-8 归组生效）", markerAt(file, "select", provider))
    }

    fun test全局总闸关闭后不出图标() {
        PluginSettings.getInstance().state.showGutterIcon = false
        val file = xmlFile("<sqlMap><select id=\"s\">SELECT 1 FROM T</select></sqlMap>")
        assertNull("总闸关闭 → iBatis provider 零产出", markerAt(file, "select", provider))
    }

    fun test开关全开时正常出图标() {
        val file = xmlFile("<sqlMap><select id=\"s\">SELECT 1 FROM T</select></sqlMap>")
        assertNotNull("默认档应出图标", markerAt(file, "select", provider))
    }

    // ------------------------------------------------------------------
    // 5. 分组登记（纯逻辑面）
    // ------------------------------------------------------------------

    fun test分组登记为mapperXml() {
        assertEquals(
            "拍板 D-8：iBatis provider 归 mapperXml 组",
            "mapperXml",
            GutterGroups.groupOf("IbatisXmlGutterLineMarkerProvider"),
        )
    }
}
