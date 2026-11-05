package cn.song.mybatisplus.visualizer.ui.inject

import cn.song.mybatisplus.visualizer.ui.highlight.XmlSqlCompletionContributor
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * 补全贡献器的**真实分派路径**守卫（T-XmlGap 收口第三轮补）。
 *
 * 与 [SqlMapperHighlighterDispatchTest] 同款思路：`plugin.xml` 里
 * `<completion.contributor language="MpSqlMapper" …>` 是否**真的把贡献器挂到本语言上**，
 * 用平台自己的查询 API 断言，而不是只读描述文件文本。
 *
 * 触发场景：收口第三轮沙盒走查发现——注入片段内（状态栏面包屑确为 `<Injected MpSqlMapper file>`）
 * 键入 `wh` **没有**弹出任何补全候选，而属性值位（`refid`/`test`）弹的是平台自己的候选。
 * 本类把「我方贡献器是否挂到 MpSqlMapper 上」变成可自动复核的判据。
 */
class SqlMapperCompletionDispatchTest : BasePlatformTestCase() {

    fun test_补全贡献器按语言分派可达() {
        val contributors = CompletionContributor.forLanguage(SqlMapperLanguage)
        val ours = contributors.filter { it is XmlSqlCompletionContributor }
        assertTrue(
            "`CompletionContributor.forLanguage(MpSqlMapper)` 应含我方 XmlSqlCompletionContributor；" +
                "实际=${contributors.map { it.javaClass.name }}（空/不含即 plugin.xml 的 " +
                "`<completion.contributor language=\"MpSqlMapper\">` 未生效）",
            ours.isNotEmpty(),
        )
    }

    fun test_语言ID可被平台按名查到() {
        // 平台多处按语言 ID 解析（LanguageExtensionPoint / findLanguageByID）；
        // 本语言由代码创建、无 <language> EP，故这条是「按 ID 能否解析」的直接读数。
        val byId = com.intellij.lang.Language.findLanguageByID("MpSqlMapper")
        assertTrue(
            "`Language.findLanguageByID(\"MpSqlMapper\")` 应解析到本语言实例；实际=$byId" +
                "（null ⇒ 任何按语言 ID 解析的 EP 都会挂不上）",
            byId === SqlMapperLanguage,
        )
    }
}
