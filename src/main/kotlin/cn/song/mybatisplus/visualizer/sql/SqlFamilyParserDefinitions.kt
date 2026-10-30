package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect
import cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect
import cn.song.mybatisplus.visualizer.sql.highgo.HighGoSqlDialect
import cn.song.mybatisplus.visualizer.sql.vastbase.VastbaseSqlDialect
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.sql92.Sql92ParserDefinition
import com.intellij.sql.psi.stubs.SqlFileElementType

/**
 * T-PreviewPsiDialect：四个自注册方言的 `lang.parserDefinition`。
 *
 * ## 为什么「只注册 sql.dialect」不够
 *
 * 平台 `SqlParserDefinitionBase.createFile` 里有一句
 * `new SqlCodeFragmentImpl(viewProvider, getFileNodeType().getLanguage(), project)`
 * 并把取到的语言强转 [SqlLanguageDialect]（字节码 `checkcast`，2024.3 制品 javap 实证；
 * 262 真机栈同样落在 `SqlParserDefinitionBase.createFile:90`）。
 *
 * 我们的 DM / GaussDB / HighGo / Vastbase 原先只注册了 `<sql.dialect>`，**没有为该语言注册
 * ParserDefinition**。平台 `LanguageParserDefinitions.forLanguage` 查不到就沿语言链上溯，落到基语言
 * `SQL` 的那份（`database-plugin.jar` 的 `plugin.xml:1253` 是
 * `<lang.parserDefinition language="SQL" implementationClass="SqlParserDefinitionBase$Sql"/>`），
 * 而它的 file node type 绑定的是基类 `SqlLanguage` —— 强转 `SqlLanguageDialect` 必然
 * `ClassCastException`，PSI 创建失败，`PsiManager.findFile` 返回 null，预览预热降级成内存 PSI
 * （表/列补全不可用，并在 idea.log 里留下点名本插件的 SEVERE）。
 *
 * 平台内置方言都是**成对**注册的：`SQL`→`SqlParserDefinitionBase$Sql`、
 * `GenericSQL`→`GenericParserDefinition`、`SQL92`→`Sql92ParserDefinition`。本文件补齐我们这四对的
 * 后一半。
 *
 * ## 形态
 *
 * 照平台 `GenericParserDefinition`（`final`，不可继承，逐字节对齐其最小覆写集）：
 *  - 静态 `SqlFileElementType(常量名, 本方言语言)` —— **这是修复的关键点**：file node type 必须绑定
 *    方言语言，`createFile` 里的强转才有正确类型；
 *  - lexer / parser / elementFactory 一律委托 `Sql92ParserDefinition`（我们的方言
 *    `createTokensHelper()` 本来就委托 `Sql92Dialect`，语义一致）；
 *  - 不新开错误高亮（方言仍 `supportsErrorHighlighting=false`）。
 *
 * 已知边界：文件按 SQL92 语法解析，GaussDB / 瀚高 / 海量 的 PG 系特有语法在 PSI 层可能与真库有出入，
 * 影响面限于补全与高亮，不影响插件自己的渲染（渲染走 `sql/Dialect.kt` 的字面口径）。
 * 将来若要方言专属解析，把委托对象换掉即可，注册面不用再动。
 */
open class MpvSqlFamilyParserDefinition(
    private val dialect: SqlLanguageDialect,
    fileTypeName: String,
) : SqlParserDefinitionBase() {

    private val fileElementType: IFileElementType = SqlFileElementType(fileTypeName, dialect)

    /** 平台只读一次并缓存（`SqlParserDefinitionBase.getElementFactory` 内有 volatile 缓存）。 */
    private val sql92: Sql92ParserDefinition by lazy { Sql92ParserDefinition() }

    override fun getFileNodeType(): IFileElementType = fileElementType

    override fun createElementFactory(): SqlElementFactoryBase = sql92.elementFactory

    override fun createLexer(project: Project): Lexer = sql92.createLexer(project)

    override fun createParser(project: Project): PsiParser = sql92.createParser(project)
}

/** 达梦（`database-optional.xml` 的 `sql.dialect` 语言 id `DM`）。 */
class DmParserDefinition : MpvSqlFamilyParserDefinition(DmSqlDialect.INSTANCE, "MPV_DM_SQL_FILE")

/** GaussDB（语言 id `GaussDB`）。 */
class GaussDbParserDefinition : MpvSqlFamilyParserDefinition(GaussDbSqlDialect.INSTANCE, "MPV_GAUSSDB_SQL_FILE")

/** 瀚高 HighGo（语言 id `HighGo`）。 */
class HighGoParserDefinition : MpvSqlFamilyParserDefinition(HighGoSqlDialect.INSTANCE, "MPV_HIGHGO_SQL_FILE")

/** 海量 Vastbase（语言 id `Vastbase`）。 */
class VastbaseParserDefinition : MpvSqlFamilyParserDefinition(VastbaseSqlDialect.INSTANCE, "MPV_VASTBASE_SQL_FILE")
