package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * 着色链路的**真实分派路径**守卫（T-XmlGap 收口补：把此前只能靠真机截图证明的一环变成可自动复核）。
 *
 * 背景：`plugin.xml` 曾把高亮器工厂注册成裸 `<syntaxHighlighterFactory>`（平台真名
 * `lang.syntaxHighlighterFactory`），被平台静默忽略 ⇒ 注入片段整段无色。当时的证据链是
 * 「平台源码 + 描述符扫描 + 沙盒截图」，本类把它补成**单测级**判据：
 *
 * `SyntaxHighlighterFactory.getSyntaxHighlighter(language, …)` 走的正是注入时算 token 的同一条路
 * （`getLanguageFactory().forLanguage(language)`，按语言 ID 查 EP）。因此本类断言「该调用返回
 * **我方**高亮器而非平台默认的 `PlainSyntaxHighlighter`」——EP 名写错时这条必红。
 *
 * 顺带钉住键表：`KEYWORD` token 必须映射到自有键 `MPSQL_KEYWORD_DEFAULT`（带平台 fallback），
 * 否则即便高亮器被调也没有颜色。
 */
class SqlMapperHighlighterDispatchTest : BasePlatformTestCase() {

    fun test_高亮器工厂经平台分派返回我方高亮器() {
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(SqlMapperLanguage, project, null)
        assertTrue(
            "`SyntaxHighlighterFactory.getSyntaxHighlighter(MpSqlMapper, …)` 应返回我方 SqlMapperSyntaxHighlighter；" +
                "实际=${highlighter.javaClass.name}（落到 PlainSyntaxHighlighter 即 EP 未注册——" +
                "plugin.xml 的 EP 名必须是 `lang.syntaxHighlighterFactory`）",
            highlighter is SqlMapperSyntaxHighlighter,
        )
    }

    fun test_关键字token映射到自有键() {
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(SqlMapperLanguage, project, null)
        val keys = highlighter.getTokenHighlights(SqlMapperTT.KEYWORD)
        assertTrue(
            "KEYWORD token 应映射到自有键 MPSQL_KEYWORD_DEFAULT，实际=${keys.map { it.externalName }}",
            keys.any { it.externalName == "MPSQL_KEYWORD_DEFAULT" },
        )
        assertTrue(
            "标识符 token 应映射到 MPSQL_IDENTIFIER_DEFAULT，实际=${highlighter.getTokenHighlights(SqlMapperTT.IDENTIFIER).map { it.externalName }}",
            highlighter.getTokenHighlights(SqlMapperTT.IDENTIFIER).any { it.externalName == "MPSQL_IDENTIFIER_DEFAULT" },
        )
    }
}
