package cn.song.mybatisplus.visualizer.convention

import cn.song.mybatisplus.visualizer.update.PlatformEpDynamism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HotSwapUnloadSafety 守卫：**我们扩展的「非动态 EP」必须登记在 [PlatformEpDynamism] 里，且只能出现在
 * 可选依赖描述文件中。**
 *
 * ## 为什么需要这条守卫（它替代了一个从未存在的守卫）
 *
 * `DynamicInstallSupport` 的 KDoc 曾写着「本插件注册的 EP 全部落在平台 `dynamic="true"` 点位上，
 * 由 `DynamicEpComplianceGuardTest` 守卫」——**这个测试类在全仓不存在**
 * （`grep -rn "class DynamicEpComplianceGuardTest" src/` 零命中），于是这条「合规」结论从来没被机器验过。
 * 2026-11-06 用户在 243 沙盒实测更新仍提示重启，真机日志点名
 * `is not unload-safe because of extension to non-dynamic EP com.intellij.sql.dialect`——
 * 平台的事实与我们的文档相反：该 EP 在 243 上**不是** dynamic（253/262/263 才是）。
 *
 * ## 守卫什么、不守卫什么
 *
 * - 守：登记表 ↔ 描述文件的一致性（登记了就必须真的声明、声明了就必须登记）、取证必填、
 *   注册只允许落在可选依赖文件里（主 `plugin.xml` 无条件加载，Community 下会炸）；
 * - 不守：**平台那一侧**的 dynamic 属性。平台事实由
 *   `dev-support/probe-platform-ep-dynamism.py` 按需取证（对发行 zip 走 HTTP Range 抽包），
 *   不在单测里跑——默认 test 桶的 classpath 不含 DatabaseTools，把平台 jar 当测试前置
 *   会让这条守卫在 Community 桶里假红（同 `SqlDialectParserDefinitionPairingGuardTest` 的取舍）。
 */
class DynamicEpComplianceGuardTest {

    private val optionalDescriptor = "META-INF/database-optional.xml"
    private val mainDescriptor = "META-INF/plugin.xml"

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "取不到 $name —— 主资源未进测试 classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * 从描述文件里解析「本插件扩展了哪些 EP」，返回全限定名。
     *
     * 两种写法都认：`<extensions defaultExtensionNs="ns"><ep .../></extensions>`
     * （EP 名 = `ns.ep`）与 `<extension point="ns.ep" .../>`。
     */
    private fun extendedEps(xml: String): Set<String> {
        // 注释里会拿 `<sql.dialect>` 当行文示例，先剥掉注释再解析，避免把说明文字当成声明
        val body = xml.replace(Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        val result = mutableSetOf<String>()
        Regex("""<extensions\b([^>]*)>(.*?)</extensions>""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(body)
            .forEach { block ->
                val attrs = block.groupValues[1]
                val ns = Regex("""defaultExtensionNs="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                // 注意 EP 名允许带点（`sql.dialect` / `lang.parserDefinition`），
                // 首版把字符类写成 [A-Za-z0-9_]+ 时整条 `<sql.dialect …>` 都匹配不上，
                // 解析结果只剩单段名的 EP——守卫因此在「找不到声明」上假红。
                Regex("""<\s*([A-Za-z0-9_.]+)[\s/>]""").findAll(block.groupValues[2]).forEach { m ->
                    val tag = m.groupValues[1]
                    // `extension` 是「带 point 属性」的通用写法，靠下面的 point= 规则收集
                    if (tag != "extension") {
                        result += if (ns.isNullOrBlank()) tag else "$ns.$tag"
                    }
                }
            }
        Regex("""<extension\b[^>]*\bpoint="([^"]+)"""").findAll(body).forEach { m ->
            result += m.groupValues[1]
        }
        return result
    }

    @Test
    fun `登记的非动态 EP 必须真的在声明文件里出现`() {
        val declared = extendedEps(resource(optionalDescriptor))
        PlatformEpDynamism.EXTENSIONS.forEach { ep ->
            assertTrue(
                "登记表里有 ${ep.qualifiedName}，但 ${ep.declaredIn} 里找不到对应扩展声明——" +
                    "僵尸登记（改了描述文件没改表，或反过来）。已解析到：$declared",
                ep.qualifiedName in declared,
            )
        }
    }

    @Test
    fun `非动态 EP 的扩展只允许落在可选依赖描述文件`() {
        val optional = extendedEps(resource(optionalDescriptor))
        val main = extendedEps(resource(mainDescriptor))
        PlatformEpDynamism.EXTENSIONS.forEach { ep ->
            assertTrue("${ep.qualifiedName} 应声明在 $optionalDescriptor", ep.qualifiedName in optional)
            assertFalse(
                "${ep.qualifiedName} 跑到了主 ${mainDescriptor} 里——主描述无条件加载，" +
                    "DatabaseTools 缺席（Community）时会 NoClassDefFoundError；必须留在可选依赖文件",
                ep.qualifiedName in main,
            )
        }
        // 可选文件能被平台的 optional config-file 机制加载，前提是主描述里有这条 depends
        val mainXml = resource(mainDescriptor)
        assertTrue(
            "主 plugin.xml 缺 <depends optional=\"true\" config-file=\"database-optional.xml\">com.intellij.database</depends>，" +
                "可选描述不会被加载，${optionalDescriptor} 里的方言/Q&amp;A 扩展全部失效",
            Regex("""<depends\s+optional="true"\s+config-file="database-optional\.xml"\s*>com\.intellij\.database</depends>""")
                .containsMatchIn(mainXml) ||
                Regex("""<depends\s+config-file="database-optional\.xml"\s+optional="true"\s*>com\.intellij\.database</depends>""")
                    .containsMatchIn(mainXml),
        )
    }

    @Test
    fun `登记表每条都要带取证与平台分界`() {
        assertEquals(
            "当前只登记了 sql.dialect 一个非动态 EP；新增必须一并补取证（见 dev-support/probe-platform-ep-dynamism.py）",
            listOf("com.intellij.sql.dialect"),
            PlatformEpDynamism.EXTENSIONS.map { it.qualifiedName },
        )
        PlatformEpDynamism.EXTENSIONS.forEach { ep ->
            assertTrue("${ep.qualifiedName} 缺取证", ep.evidence.isNotBlank())
            assertTrue("${ep.qualifiedName} 的取证要写清两档平台（一个非动态、一个 dynamic）", ep.evidence.count { it.isDigit() } >= 6)
        }
    }
}
