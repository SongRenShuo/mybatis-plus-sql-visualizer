package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * NEW-26 五条 inspection 的**注册形态**平台测试。
 *
 * 为什么必须有它：`<localInspection>` 属性写错时平台**不报错**，检查只是静默不出现在设置树里
 * （`BannedApiRuleSerializationTest` 已为第一条踩过这个坑）。这里逐条验：
 *  - EP 存在、`instantiateTool()` 得到预期实现类；
 *  - 显示名/分组名解析成 bundle 文案（不是键原文）；
 *  - `shortName` 与 `SqlInspectionRule.shortName` 同值（`@SuppressWarnings` / XML suppress id 靠它）；
 *  - `language=""`（空串 = 全语言）——五条规则同时覆盖 mapper XML 与 Java 注解 SQL。写成 `XML`
 *    则注解 SQL 一侧永不运行；写成 `"any"` 更糟：平台把它当**语言 ID 字面量**去找
 *    `Language.findLanguageByID("any")`（结果 null），`LocalInspectionsPass` 按语言分桶时
 *    整条检查被跳过——实测「EP 内建实例能跑、`myFixture.enableInspections` 跑不出来」就是它。
 *
 * 属平台桶（`com.intellij` 引用），不在 `testFast` 快桶内，定向跑。
 */
class SqlInspectionRegistrationTest : BasePlatformTestCase() {

    private fun epOf(rule: SqlInspectionRule): LocalInspectionEP? =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.firstOrNull { it.getShortName() == rule.shortName }

    fun test五条规则全部注册且实现类正确() {
        val expected = mapOf(
            SqlInspectionRule.DOLLAR_INJECTION to DollarInjectionInspection::class.java,
            SqlInspectionRule.MISSING_PARAMETER to MissingParameterInspection::class.java,
            SqlInspectionRule.RESULT_MAP_PROPERTY to ResultMapPropertyInspection::class.java,
            SqlInspectionRule.DIALECT_QUOTE to DialectQuoteInspection::class.java,
            SqlInspectionRule.OGNL_TAUTOLOGY to OgnlTautologyInspection::class.java,
        )
        for ((rule, clazz) in expected) {
            val ep = epOf(rule) ?: error("plugin.xml 未注册 ${rule.shortName}")
            assertEquals("${rule.shortName} 实现类", clazz, ep.instantiateTool()::class.java)
        }
    }

    fun test显示名与分组名解析为bundle文案() {
        for (rule in SqlInspectionRule.entries) {
            val ep = epOf(rule) ?: error("plugin.xml 未注册 ${rule.shortName}")
            val displayName = ep.getDisplayName()
            assertTrue(
                "${rule.shortName} 显示名应是 bundle 文案而非键原文：$displayName",
                displayName in setOf(
                    MpSqlVisualizerBundle.message(rule.displayNameKey),
                    MpSqlVisualizerBundle.messageEn(rule.displayNameKey),
                ),
            )
            val groupName = ep.getGroupDisplayName()
            assertTrue(
                "${rule.shortName} 分组名应是 bundle 文案而非键原文：$groupName",
                groupName in setOf(
                    MpSqlVisualizerBundle.message("inspection.sqlv.groupName"),
                    MpSqlVisualizerBundle.messageEn("inspection.sqlv.groupName"),
                ),
            )
        }
    }

    fun test全部默认启用且级别WARNING() {
        for (rule in SqlInspectionRule.entries) {
            val ep = epOf(rule) ?: error("plugin.xml 未注册 ${rule.shortName}")
            assertEquals(
                "${rule.shortName} 的 enabledByDefault 须与枚举声明一致（P-4 语料 0 误报才允许 true）",
                rule.enabledByDefault,
                ep.enabledByDefault,
            )
            assertEquals(
                "${rule.shortName} 默认级别",
                HighlightDisplayLevel.WARNING,
                ep.getDefaultLevel(),
            )
        }
    }

    fun test语言为空串_同时覆盖XML与注解SQL() {
        for (rule in SqlInspectionRule.entries) {
            val ep = epOf(rule) ?: error("plugin.xml 未注册 ${rule.shortName}")
            assertEquals(
                "${rule.shortName} 必须 language=\"\"（空串 = 全语言）：写成 XML 会让注解 SQL 永不检查，" +
                    "写成 any 则平台按语言 ID 分桶时整条检查被跳过",
                "",
                ep.language,
            )
        }
    }

    fun testShortName与枚举一致() {
        for (rule in SqlInspectionRule.entries) {
            val tool = when (rule) {
                SqlInspectionRule.DOLLAR_INJECTION -> DollarInjectionInspection()
                SqlInspectionRule.MISSING_PARAMETER -> MissingParameterInspection()
                SqlInspectionRule.RESULT_MAP_PROPERTY -> ResultMapPropertyInspection()
                SqlInspectionRule.DIALECT_QUOTE -> DialectQuoteInspection()
                SqlInspectionRule.OGNL_TAUTOLOGY -> OgnlTautologyInspection()
            }
            assertEquals(
                "${rule.shortName}：getShortName 必须与 plugin.xml shortName 同值（suppress id 靠它）",
                rule.shortName,
                tool.shortName,
            )
        }
    }
}