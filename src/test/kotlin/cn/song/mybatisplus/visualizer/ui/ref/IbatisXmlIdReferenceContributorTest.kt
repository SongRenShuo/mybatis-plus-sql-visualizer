package cn.song.mybatisplus.visualizer.ui.ref

import cn.song.mybatisplus.visualizer.ui.nav.IbatisXmlStatementTargets
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-Ibatis2Support 券 F-1（车道 L8）：`<sqlMap>` 文件内 id 引用用例。
 *
 * 形态说明（与 `MybatisXmlReferenceTest` 同口径）：测试夹具**不自动加载 plugin.xml 的 EP**，
 * 故直接驱动 [IbatisXmlAttributeReferenceProvider]（provider 级断言）；EP 接线由类尾守卫用例按
 * 「读 plugin.xml 文本 + 正则」的既有形态钉住。
 *
 * 反向验证口径：摘掉 `refid` 解析分支（`when` 里的 `refid` 判定）→ `testF1_refid_同文件sql片段` 必红。
 *
 * 红线：所有引用必须 `isSoft == true`（目标缺失时零报红），本类有专门负路径用例。
 */
class IbatisXmlIdReferenceContributorTest : BasePlatformTestCase() {

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

    private fun refsOf(attrName: String): List<PsiReference> {
        val file = myFixture.file as XmlFile
        val value = attributeValue(file, attrName)
        return IbatisXmlAttributeReferenceProvider.getReferencesByElement(value, ProcessingContext()).toList()
    }

    private fun attributeValue(file: XmlFile, attrName: String, attrValue: String? = null): XmlAttributeValue =
        PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue::class.java).first {
            (it.parent as? XmlAttribute)?.name == attrName && (attrValue == null || it.value == attrValue)
        }

    /** 解析目标的宿主标签（`MybatisXmlReferenceTest` 同款：属性值元素 → 父标签）。 */
    private fun targetTagOf(ref: PsiReference?): XmlTag? =
        PsiTreeUtil.getParentOfType(ref?.resolve(), XmlTag::class.java)

    private fun assertResolvesTo(attrName: String, tagName: String, id: String) {
        val ref = refsOf(attrName).single()
        assertTrue("$attrName 引用须为 soft（无目标零报红红线）", ref.isSoft)
        val tag = targetTagOf(ref)
        assertNotNull("$attrName 应解析到目标标签", tag)
        assertEquals("$attrName 目标标签名", tagName, tag?.name)
        assertEquals("$attrName 目标 id", id, tag?.getAttributeValue("id"))
    }

    // ------------------------------------------------------------------
    // 四类正例（计划册券 F 的 F-1 表）
    // ------------------------------------------------------------------

    /** `<include refid>` → 同文件 `<sql id>`。 */
    fun testF1_refid_同文件sql片段() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="m.user">
  <sql id="frag">WHERE 1=1</sql>
  <select id="s">SELECT * FROM t <include refid="frag"/></select>
</sqlMap>""",
        )
        assertResolvesTo("refid", "sql", "frag")
    }

    /** `<include refid>` 未命中字面值 → 回退 `namespace + "." + refid`（引擎 applyNamespace 语义）。 */
    fun testF1_refid_命名空间回退() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="m.user">
  <sql id="m.user.frag">WHERE 1=1</sql>
  <select id="s">SELECT * FROM t <include refid="frag"/></select>
</sqlMap>""",
        )
        assertResolvesTo("refid", "sql", "m.user.frag")
    }

    /** refid 已含 `.` 时不加 namespace 前缀（引擎 applyNamespace 的 `indexOf('.') == -1` 守卫）。 */
    fun testF1_refid_含点号不加前缀() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="m.user">
  <sql id="m.user.other.frag">WHERE 1=1</sql>
  <select id="s">SELECT * FROM t <include refid="other.frag"/></select>
</sqlMap>""",
        )
        val refs = refsOf("refid")
        assertNull(
            "refid 含点号时不得再试 `namespace + \".\" + refid`（引擎 applyNamespace 原样返回 → 只查字面值）",
            refs.single().resolve(),
        )
    }

    /** 语句上的 `resultMap=` → 同文件 `<resultMap id>`。 */
    fun testF1_resultMap_同文件() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="m.user">
  <resultMap id="rm" class="java.lang.String"/>
  <select id="s" resultMap="rm">SELECT 1</select>
</sqlMap>""",
        )
        assertResolvesTo("resultMap", "resultMap", "rm")
    }

    /** 语句上的 `parameterMap=` → 同文件 `<parameterMap id>`。 */
    fun testF1_parameterMap_同文件() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="m.user">
  <parameterMap id="pm" class="java.lang.String"/>
  <select id="s" parameterMap="pm">SELECT 1</select>
</sqlMap>""",
        )
        assertResolvesTo("parameterMap", "parameterMap", "pm")
    }

    /** `<resultMap extends>` → 同文件 `<resultMap id>`。 */
    fun testF1_extends_同文件resultMap() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="m.user">
  <resultMap id="base" class="java.lang.String"/>
  <resultMap id="child" class="java.lang.String" extends="base"/>
</sqlMap>""",
        )
        assertResolvesTo("extends", "resultMap", "base")
    }

    // ------------------------------------------------------------------
    // 负路径（红线：无目标零报红）
    // ------------------------------------------------------------------

    /** 目标不存在 → `resolve()` 返回 null；引用仍存在且 **soft**（不标红），且不抛异常。 */
    fun testF1_目标缺失_soft引用零报红() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="m.user"><select id="s">SELECT 1 <include refid="missing"/></select></sqlMap>""",
        )
        val refs = refsOf("refid")
        assertEquals("引用本身仍产生（供重命名/查找用法）", 1, refs.size)
        assertNull("目标不存在 → resolve 为 null", refs.single().resolve())
        assertTrue("引用须为 soft：目标缺失时平台不标红（红线）", refs.single().isSoft)
    }

    /** 根标签不是 `sqlMap`（如 MyBatis `<mapper>`）→ 零引用（根守卫，MyBatis 走原路径）。 */
    fun testF1_mapper根_零引用() {
        myFixture.configureByText(
            "UserMapper.xml",
            """<mapper namespace="n"><select id="s">SELECT 1 <include refid="frag"/></select></mapper>""",
        )
        val value = attributeValue(myFixture.file as XmlFile, "refid")
        assertTrue(
            "「根标签名 != sqlMap」须零引用（MyBatis 文件行为逐字节不变）",
            IbatisXmlAttributeReferenceProvider.getReferencesByElement(value, ProcessingContext()).isEmpty(),
        )
    }

    /** 任意其它根标签 → 零引用。 */
    fun testF1_非sqlMap根_零引用() {
        myFixture.configureByText(
            "config.xml",
            """<root><sql id="frag">x</sql><select id="s" resultMap="frag"/></root>""",
        )
        val value = attributeValue(myFixture.file as XmlFile, "resultMap")
        assertTrue(
            "非 sqlMap 文件不产引用（根守卫）",
            IbatisXmlAttributeReferenceProvider.getReferencesByElement(value, ProcessingContext()).isEmpty(),
        )
    }

    /** 未覆盖的属性（如 `resultClass`）→ 零引用（不做无意义引用，避免噪音与误报）。 */
    fun testF1_未覆盖属性_零引用() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="n"><select id="s" resultClass="java.lang.String">SELECT 1</select></sqlMap>""",
        )
        val value = attributeValue(myFixture.file as XmlFile, "resultClass")
        assertTrue(
            "未覆盖属性不产引用",
            IbatisXmlAttributeReferenceProvider.getReferencesByElement(value, ProcessingContext()).isEmpty(),
        )
    }

    /** `extends` 只认 `<resultMap>` 宿主：`<sql>` 上的 `extends`（非 DTD 形态）不产引用。 */
    fun testF1_extends非resultMap宿主_零引用() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="n"><sql id="s" extends="base">x</sql></sqlMap>""",
        )
        val value = attributeValue(myFixture.file as XmlFile, "extends")
        assertTrue(
            "extends 只在 <resultMap> 上产引用",
            IbatisXmlAttributeReferenceProvider.getReferencesByElement(value, ProcessingContext()).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 同文件查找的两条廉价性/正确性钉（无索引访问）
    // ------------------------------------------------------------------

    /** 同文件解析不触碰文件清单缓存（零索引扫描）——热路径零索引查询的代码级证据（测试侧读数）。 */
    fun testF1_同文件解析_零索引扫描() {
        myFixture.configureByText(
            "sqlMap.xml",
            """<sqlMap namespace="m.user">
  <sql id="frag">WHERE 1=1</sql>
  <select id="s">SELECT * FROM t <include refid="frag"/></select>
</sqlMap>""",
        )
        IbatisXmlStatementTargets.indexScanCount.set(0)
        assertResolvesTo("refid", "sql", "frag")
        assertEquals(
            "同文件 id 解析不得触发文件清单扫描（热路径零索引查询）",
            0,
            IbatisXmlStatementTargets.indexScanCount.get(),
        )
    }

    // ------------------------------------------------------------------
    // EP 接线守卫（形态同 MybatisXmlReferenceTest 的 plugin.xml 正则守卫）
    // ------------------------------------------------------------------

    private fun pluginXml(): String =
        javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
            .readBytes().toString(Charsets.UTF_8)

    fun testF1_pluginXml_psiReferenceContributor接线在场() {
        assertTrue(
            "plugin.xml 须含 <psi.referenceContributor language=\"XML\" " +
                "implementation=…IbatisXmlIdReferenceContributor>",
            Regex(
                "<psi\\.referenceContributor\\s+language=\"XML\"\\s+" +
                    "implementation=\"cn\\.song\\.mybatisplus\\.visualizer\\.ui\\.ref\\.IbatisXmlIdReferenceContributor\"",
            ).containsMatchIn(pluginXml()),
        )
    }

    /** 红线守卫：本包源码不得出现校验器形态类注册面（既有 `MybatisXmlReferenceTest` 同款口径）。 */
    fun testF1_红线_本包无校验器形态() {
        val dir = java.io.File("src/main/kotlin/cn/song/mybatisplus/visualizer/ui/ref")
        assertTrue("ui/ref 源码目录存在", dir.isDirectory)
        val banned = listOf("LocalInspectionTool", "Annotator", "ProblemHighlightType", "HighlightInfo", "InspectionManager")
        val offenders = dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
            .filter { f -> banned.any { f.readText().contains(it) } }
            .map { f -> f.name }
        assertFalse("ui/ref 不得引入校验器形态（红线）：$offenders", offenders.isNotEmpty())
    }
}
