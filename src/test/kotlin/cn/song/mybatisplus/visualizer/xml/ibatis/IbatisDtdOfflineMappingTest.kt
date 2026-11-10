package cn.song.mybatisplus.visualizer.xml.ibatis

import com.intellij.javaee.ExternalResourceManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.URL
import java.security.MessageDigest

/**
 * 券 E 硬判据：iBATIS 2 的两份 DTD 随包分发，且经 `com.intellij.standardResource` 离线映射到
 * `ExternalResourceManager`（T-Ibatis2Support，2026-11-09）。
 *
 * ## 为什么必须判到「解析结果」而不是「不报错」
 *
 * 未映射时平台对 `http://www.ibatis.com/dtd/sql-map-2.dtd` 会**返回原 url 本身**
 * （`ExternalResourceManagerExImpl.getResourceLocation` 源码：user → catalog → std 三级都查不到时
 * `return url`），随后 XML 校验器按该 url 联网取 DTD——`www.ibatis.com` 域已失效，表现为整篇
 * 「无法解析（引用的）DTD」+ 级联满屏「必须声明元素类型」。所以判据是「解析到的**字符串**指向
 * 我们随包的资源路径」，仅断言「不抛异常」「无 error 高亮」在无网环境下也会假绿。
 *
 * ## 三个用例各自钉什么（互不替代）
 *
 *  1. [IbatisDtdOfflineMappingTest.test随包DTD与jar内副本逐字节一致] —— 资产保真：sha256 与
 *     `ibatis-sqlmap-2.3.4.726.jar` 内 `com/ibatis/sqlmap/engine/builder/xml/` 的原始副本一致，
 *     且 config 版是 `sqlMap+`（jar 内）而非 `sqlMap*`（网站版 11,542 B 那版）。
 *  2. [IbatisDtdOfflineMappingTest.testpluginXml逐一登记解析器认的八个标识] —— 登记完整性：数量、
 *     url→path 指向、与识别器常量 [IbatisXmlRecognizer.IBATIS_SYSTEM_IDS] /
 *     [IbatisXmlRecognizer.IBATIS_PUBLIC_IDS] 同集合（防「识别得出 iBatis 却解析不了它的 DTD」漂移）。
 *  3. [IbatisDtdOfflineMappingTest.testExternalResourceManager解析到随包DTD] —— 平台侧生效：
 *     八个标识逐个经真实 `ExternalResourceManager` 解析，结果 ≠ 原 url、以随包路径结尾，且按该
 *     结果读出的字节与随包副本同 sha256。
 *
 * 映射标识口径 = iBatis 运行期 `SqlMapClasspathEntityResolver` 常量池（`javap -p -c` 实证 8 条，
 * 见 `src/main/resources/META-INF/plugin.xml` 末尾注释）。
 *
 * 用例名为 JUnit3 形态（`fun testXxx`）：基类是平台 JUnit3 测试基类，按方法名收集用例；
 * `@Test` 注解不参与判定（见 `convention/Junit3SemanticsGuardTest`）。
 */
class IbatisDtdOfflineMappingTest : BasePlatformTestCase() {

    private companion object {
        /** 随包资源路径（相对插件 jar 根，与 plugin.xml 的 `path` 属性同口径、不带前导斜杠）。 */
        const val SQL_MAP_DTD = "dtd/ibatis/sql-map-2.dtd"
        const val SQL_MAP_CONFIG_DTD = "dtd/ibatis/sql-map-config-2.dtd"

        /** jar 内副本 sha256（`_ibatis-research/dtd/.../sql-map-2.dtd` 与仓库内文件双向实测一致）。 */
        const val SQL_MAP_SHA256 = "bb8b53f0a6f3a6b015eeb3f3fe69f10b15023d5c33a1ff583b3fe77bc030e09e"
        const val SQL_MAP_CONFIG_SHA256 = "45f03ebb8811027a2f474a4ed0e98fffa4e5a8fdcd9596d09ae5f48b7a2a008d"

        /** 八个标识 → 随包路径：与 `SqlMapClasspathEntityResolver` 常量池逐字对应。 */
        val EXPECTED: Map<String, String> = mapOf(
            "http://ibatis.apache.org/dtd/sql-map-2.dtd" to SQL_MAP_DTD,
            "http://www.ibatis.com/dtd/sql-map-2.dtd" to SQL_MAP_DTD,
            "-//iBATIS.com//DTD SQL Map 2.0//EN" to SQL_MAP_DTD,
            "-//ibatis.apache.org//DTD SQL Map 2.0//EN" to SQL_MAP_DTD,
            "http://ibatis.apache.org/dtd/sql-map-config-2.dtd" to SQL_MAP_CONFIG_DTD,
            "http://www.ibatis.com/dtd/sql-map-config-2.dtd" to SQL_MAP_CONFIG_DTD,
            "-//iBATIS.com//DTD SQL Map Config 2.0//EN" to SQL_MAP_CONFIG_DTD,
            "-//ibatis.apache.org//DTD SQL Map Config 2.0//EN" to SQL_MAP_CONFIG_DTD,
        )
    }

    // ------------------------------------------------------------------
    // 取证小工具
    // ------------------------------------------------------------------

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "随包资源缺失：$path（src/main/resources 下未入库，或未进测试 classpath）"
        }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 剥掉 XML 注释后的主描述文件文本（注释里会拿 `<standardResource>` 当行文示例）。 */
    private fun pluginXml(): String {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")) {
            "取不到 META-INF/plugin.xml —— 主资源未进测试 classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        return text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    /** 主描述文件里 `<standardResource url=.. path=../>` 的 url→path 表。 */
    private fun declaredStandardResources(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""<standardResource\b([^>]*?)/>""").findAll(pluginXml()).forEach { m ->
            val attrs = m.groupValues[1]
            val url = Regex("""\burl\s*=\s*"([^"]+)"""").find(attrs)?.groupValues?.get(1)
            val path = Regex("""\bpath\s*=\s*"([^"]+)"""").find(attrs)?.groupValues?.get(1)
            assertNotNull("<standardResource> 缺 url 或 path 属性：${m.value}", url)
            assertNotNull("<standardResource> 缺 url 或 path 属性：${m.value}", path)
            out[url!!] = path!!
        }
        return out
    }

    // ------------------------------------------------------------------
    // 1. 资产保真
    // ------------------------------------------------------------------

    fun test随包DTD与jar内副本逐字节一致() {
        assertEquals(
            "sql-map-2.dtd 与 ibatis-sqlmap-2.3.4.726.jar 内副本不一致（被网站版覆盖过？）",
            SQL_MAP_SHA256,
            sha256(resourceBytes(SQL_MAP_DTD)),
        )
        assertEquals(
            "sql-map-config-2.dtd 与 jar 内副本不一致（网站版是 11,542 B / sqlMap*，不可替换）",
            SQL_MAP_CONFIG_SHA256,
            sha256(resourceBytes(SQL_MAP_CONFIG_DTD)),
        )

        // jar 内 config 版根声明是 sqlMap+（至少一个 <sqlMap>），网站版是 sqlMap*——这是两版唯一的
        // 语义分叉点，也是「以 jar 内为准」的机器可判据。
        val config = resourceBytes(SQL_MAP_CONFIG_DTD).toString(Charsets.UTF_8)
        assertTrue("config 版根声明应为 sqlMap+（jar 内副本）", config.contains("typeAlias*, typeHandler*, transactionManager?, sqlMap+)+"))
        assertFalse("config 版出现 sqlMap*——这是网站版特征，jar 内副本不是这样", config.contains("sqlMap*)+"))
    }

    // ------------------------------------------------------------------
    // 2. 登记完整性
    // ------------------------------------------------------------------

    fun testpluginXml逐一登记解析器认的八个标识() {
        assertEquals(
            "plugin.xml 的 <standardResource> 登记表与预期不符（缺项 / 多项 / path 指错）",
            EXPECTED,
            declaredStandardResources(),
        )
        assertEquals(
            "登记表与识别器的 DOCTYPE 常量不同集合——会出现「识别成 iBatis 但 DTD 解析不了」",
            IbatisXmlRecognizer.IBATIS_SYSTEM_IDS + IbatisXmlRecognizer.IBATIS_PUBLIC_IDS,
            EXPECTED.keys,
        )
    }

    // ------------------------------------------------------------------
    // 3. 平台侧生效（本券硬判据）
    // ------------------------------------------------------------------

    fun testExternalResourceManager解析到随包DTD() {
        val erm = ExternalResourceManager.getInstance()
        EXPECTED.forEach { (id, path) ->
            // 走 (url, version) 重载：单参重载在 243 已 @Deprecated，二者内部等价
            // （version=null 时都会替换成空串再查 user/catalog/std 三级表）。
            val resolved = erm.getResourceLocation(id, null as String?)
            assertNotNull("$id 未映射：ExternalResourceManager 返回 null", resolved)
            assertTrue(
                "$id 未被离线映射——解析结果仍是原 url 本身（平台三级查找 user/catalog/std 全未命中时的" +
                    "兜底返回值，随后会联网取 DTD，www.ibatis.com 已失效）",
                resolved != id,
            )
            assertTrue(
                "$id 解析到 $resolved，不是随包 DTD $path（standardResource 未生效？）",
                resolved!!.endsWith(path),
            )
            // 解析结果必须真能读出 DTD；且字节与随包副本一致（防解析到同名的别家副本）
            val bytes = URL(resolved).openStream().use { it.readBytes() }
            assertEquals(
                "$id 解析出的内容与随包 $path 不一致",
                sha256(resourceBytes(path)),
                sha256(bytes),
            )
        }
    }
}
