package cn.song.mybatisplus.visualizer.sql.highgo

import com.intellij.database.Dbms
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.SqlLanguageDialectBase
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.sql92.Sql92Dialect

/**
 * T-XcDialects3（券 08）：HighGo SQL 语言方言——平台注册用最小实现。
 *
 * 背景：与达梦 [cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect]、GaussDB
 * [cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect] 同一缺口——262 平台的 SQL
 * 方言注册表（database-plugin `<sql.dialect dbms=…>` 项，AZURE~VERTICA）**无 HighGo**。
 * `ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["HighGo"]` 的候选链原本只能落到
 * `PostgreSQL` 兜底，`SqlDialectMappings.getAvailableValues` 里没有 HighGo，用户也没法在
 * 设置页 SQL Dialects 里选它。本类注册后语言 id `HighGo` 进入平台可用清单，候选链首项
 * 即命中，`IdeaSqlContextBridge.pluginDialectName` 的精确名匹配也随之生效。
 *
 * 形态照抄平台最小样板 GenericDialect（同 `DmSqlDialect`/`GaussDbSqlDialect`）：
 *  - 构造传语言 id `HighGo`；
 *  - [createTokensHelper] 委托 [Sql92Dialect]（零自维护关键字/令牌）；
 *  - [getDbms] 返回 [Dbms.UNKNOWN]（平台无 HighGo 数据库方言枚举，诚实未知）；
 *  - [supportsErrorHighlighting] = false（无 HighGo 专属 parser，不开错误高亮防假报错）。
 *
 * 已知边界：无 HighGo 专属 parser/错误检查，`HighGo` 文件的高亮视觉与 Generic 相近；
 * 关键字集未按瀚高扩展——**方言渲染侧的保留字另有单一事实源**
 * （`SqlKeywords.HIGHGO_RESERVED`，供 `Dialect.quoteIfKeyword` 用），两者职责不同。
 */
class HighGoSqlDialect private constructor() : SqlLanguageDialectBase(ID) {

    override fun createTokensHelper(): TokensHelper = Sql92Dialect.INSTANCE.tokensHelper

    override fun isOperatorSupported(op: IElementType): Boolean = Sql92Dialect.INSTANCE.isOperatorSupported(op)

    override fun getSystemVariables(): Set<String> = Sql92Dialect.INSTANCE.systemVariables

    override fun getDbms(): Dbms = Dbms.UNKNOWN

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun supportsErrorHighlighting(): Boolean = false

    companion object {
        // sql.dialect 使用 DbmsExtension.InstanceBean，读取 public static INSTANCE，不调用构造器。
        @JvmField val INSTANCE: HighGoSqlDialect = HighGoSqlDialect()

        /**
         * 语言/方言 id：亦即 `ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["HighGo"]`
         * 候选链的首项，以及 `Dialect.name`（`HighGoDialect`）的逐字值——三处必须一致。
         */
        const val ID: String = "HighGo"

        /** 设置页（SQL Dialects）/ 数据源选择器里的显示名。 */
        const val DISPLAY_NAME: String = "HighGo"
    }
}
