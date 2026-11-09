package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PreviewPsiDialect 根因守卫：**每个 `<sql.dialect>` 必须有一条同名 `lang.parserDefinition`**。
 *
 * ## 为什么需要这条守卫
 *
 * 2026-10-30 真机（IDEA 2026.2.3 + v2026.10.31-beta.1）：方言为 GaussDB 时预览恒降级，10/10 次
 * 预热失败，横幅「临时文件解析失败；表/列补全暂不可用」。根因是四个自注册方言
 * （DM / GaussDB / HighGo / Vastbase）只注册了 `<sql.dialect>`，没有为该语言注册 ParserDefinition；
 * 平台 `LanguageParserDefinitions.forLanguage` 查不到就沿语言链上落到基语言 `SQL` 的那份
 * （`database-plugin.jar` 的 `plugin.xml:1253`），其 file node type 绑的是基类 `SqlLanguage`，
 * 于是 `SqlParserDefinitionBase.createFile` 里的 `(SqlLanguageDialect) getFileNodeType().getLanguage()`
 * 抛 `ClassCastException`，PSI 创建失败，`PsiManager.findFile` 返回 null。
 *
 * 平台内置方言 SQL / GenericSQL / SQL92 **全是成对注册的**（同文件 :1253 / :1913 / :1925）。
 * 这条守卫把「成对」固化下来：将来加第五个方言只写 `<sql.dialect>` 时会先红。
 *
 * ## 为什么是静态扫描而不是运行期断言
 *
 * `com.intellij.sql.dialect` EP 由 DatabaseTools 声明，默认 `test` 桶的 classpath 不含该插件
 * （见 `PlatformSqlDialectRegistrationTest` 的类注释）。运行期「PSI 真的建得出来」由那个
 * DB 集成通道的用例负责；本类只负责「描述文件里成对」，两者互补。
 */
class SqlDialectParserDefinitionPairingGuardTest {

    /** 方言实现类 → 它注册的平台语言 id。加新方言时必须同时改这里（有意为之：强制过一遍守卫）。 */
    private val expectedPairs = mapOf(
        "cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect" to "DM",
        "cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect" to "GaussDB",
        "cn.song.mybatisplus.visualizer.sql.highgo.HighGoSqlDialect" to "HighGo",
        "cn.song.mybatisplus.visualizer.sql.vastbase.VastbaseSqlDialect" to "Vastbase",
        // T-GoldenDbDialect（券 A / P-8）：第五对——262 平台无 GoldenDB 方言（IU-262.9437.185
        // 全量 xml grep golden 零命中，正对照 mysql/greenplum 命中），自注册成对落位。
        "cn.song.mybatisplus.visualizer.sql.goldendb.GoldenDbSqlDialect" to "GoldenDB",
        // T-DomesticDialects（2026-11-09）：国产/信创第三批 10 对（实现类单文件聚合在
        // sql/domestic/DomesticSqlDialects.kt，GoldenDbSqlDialect 样板）。注意平台语言 id 不带
        // 中文后缀（渲染方言名 `Oscar（神通）` ↔ 平台 id `Oscar`）。
        "cn.song.mybatisplus.visualizer.sql.domestic.TiDbSqlDialect" to "TiDB",
        "cn.song.mybatisplus.visualizer.sql.domestic.PolarDbXSqlDialect" to "PolarDB-X",
        "cn.song.mybatisplus.visualizer.sql.domestic.PolarDbSqlDialect" to "PolarDB",
        "cn.song.mybatisplus.visualizer.sql.domestic.GBase8aSqlDialect" to "GBase 8a",
        "cn.song.mybatisplus.visualizer.sql.domestic.GBase8cSqlDialect" to "GBase 8c",
        "cn.song.mybatisplus.visualizer.sql.domestic.OscarSqlDialect" to "Oscar",
        "cn.song.mybatisplus.visualizer.sql.domestic.UxDbSqlDialect" to "UXDB",
        "cn.song.mybatisplus.visualizer.sql.domestic.AntDbSqlDialect" to "AntDB",
        "cn.song.mybatisplus.visualizer.sql.domestic.XuGuSqlDialect" to "XuGu",
        "cn.song.mybatisplus.visualizer.sql.domestic.YashanDbSqlDialect" to "YashanDB",
    )

    private fun descriptor(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("META-INF/database-optional.xml")) {
            "取不到 META-INF/database-optional.xml —— 主资源未进测试 classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun dialectClasses(xml: String): List<String> =
        Regex("""<sql\.dialect\b[^>]*implementationClass="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toList()

    private fun parserDefinitionLanguages(xml: String): List<String> =
        Regex("""<lang\.parserDefinition\b[^>]*language="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toList()

    @Test
    fun `每个自注册方言都必须有配套的 lang parserDefinition`() {
        val xml = descriptor()
        val dialects = dialectClasses(xml)
        val languages = parserDefinitionLanguages(xml)

        assertEquals(
            "database-optional.xml 的 <sql.dialect> 数量与守卫登记的方言表不一致：" +
                " dialects=$dialects，登记=$expectedPairs。" +
                " 新增方言时请同步更新本测试的 expectedPairs 并补齐 lang.parserDefinition",
            expectedPairs.size,
            dialects.size,
        )
        for ((implClass, languageId) in expectedPairs) {
            assertTrue(
                "方言 $implClass 没有注册 <sql.dialect> —— 平台 SQL 方言列表里见不到它",
                dialects.contains(implClass),
            )
            assertTrue(
                "语言 '$languageId' 缺少 <lang.parserDefinition>。" +
                    "缺了它，平台会回落到基语言 SQL 的 parser definition，" +
                    "SqlParserDefinitionBase.createFile 强转 SqlLanguageDialect 必抛 CCE，" +
                    "预览 PSI 建不出来（T-PreviewPsiDialect 真机缺陷）。实际：$languages",
                languages.contains(languageId),
            )
        }
        assertEquals(
            "lang.parserDefinition 数量应等于方言数量（每个方言一条，多出来的那条没有对应方言）" +
                "：languages=$languages",
            expectedPairs.size,
            languages.size,
        )
    }

    @Test
    fun `parserDefinition 实现类必须落在本插件包内且与语言一一对应`() {
        val xml = descriptor()
        val impls = Regex("""<lang\.parserDefinition\b[^>]*implementationClass="([^"]+)"""")
            .findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals("每个语言一条 parserDefinition，实现类不应重复使用：$impls", impls.size, impls.toSet().size)
        for (impl in impls) {
            assertTrue(
                "parserDefinition 实现类 $impl 不在本插件包内，核对是否写错 FQCN",
                impl.startsWith("cn.song.mybatisplus.visualizer.sql."),
            )
        }
    }
}
