package cn.song.mybatisplus.visualizer.sql.gaussdb

import com.intellij.database.Dbms
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.SqlLanguageDialectBase
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.sql92.Sql92Dialect

/**
 * T-GaussDbDialect（券 06）：GaussDB SQL 语言方言——平台注册用最小实现。
 *
 * 背景：与达梦 [cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect] 同一缺口——262 平台的 SQL
 * 方言注册表（database-plugin `<sql.dialect dbms=…>` 35 项，AZURE~VERTICA）**无 GaussDB 也无
 * openGauss**。`ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GaussDB"]` 的候选链
 * 原本只能落到 `PostgreSQL` 兜底，`SqlDialectMappings.getAvailableValues` 里没有 GaussDB，
 * 用户也没法在设置页 SQL Dialects 里选它。本类注册后语言 id `GaussDB` 进入平台可用清单，
 * 候选链首项即命中，`IdeaSqlContextBridge.pluginDialectName` 的精确名匹配也随之生效。
 *
 * 形态照抄平台最小样板 GenericDialect（同 `DmSqlDialect`，javap 反编译逐字节对齐最小覆写集）：
 *  - 构造传语言 id `GaussDB`；
 *  - [createTokensHelper] 委托 [Sql92Dialect]（零自维护关键字/令牌）；
 *  - [getDbms] 返回 [Dbms.UNKNOWN]（平台无 GaussDB 数据库方言枚举，诚实未知）；
 *  - [supportsErrorHighlighting] = false（无 GaussDB 专属 parser，不开错误高亮防假报错）。
 *
 * 已知边界：无 GaussDB 专属 parser/错误检查，`GaussDB` 文件的高亮视觉与 Generic 相近；
 * 关键字集未按 GaussDB 扩展——**方言渲染侧的保留字另有单一事实源**
 * （`SqlKeywords.GAUSSDB_RESERVED`，供 `Dialect.quoteIfKeyword` 用），两者职责不同，
 * 不要把平台侧的关键字集当成渲染侧的依据。
 */
class GaussDbSqlDialect private constructor() : SqlLanguageDialectBase(ID) {

    override fun createTokensHelper(): TokensHelper = Sql92Dialect.INSTANCE.tokensHelper

    override fun isOperatorSupported(op: IElementType): Boolean = Sql92Dialect.INSTANCE.isOperatorSupported(op)

    override fun getSystemVariables(): Set<String> = Sql92Dialect.INSTANCE.systemVariables

    override fun getDbms(): Dbms = Dbms.UNKNOWN

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun supportsErrorHighlighting(): Boolean = false

    companion object {
        // sql.dialect 使用 DbmsExtension.InstanceBean，读取 public static INSTANCE，不调用构造器。
        @JvmField val INSTANCE: GaussDbSqlDialect = GaussDbSqlDialect()

        /**
         * 语言/方言 id：亦即 `ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GaussDB"]`
         * 候选链的首项，以及 `Dialect.name`（`GaussDbDialect`）的逐字值——三处必须一致。
         */
        const val ID: String = "GaussDB"

        /** 设置页（SQL Dialects）/ 数据源选择器里的显示名。 */
        const val DISPLAY_NAME: String = "GaussDB"
    }
}
