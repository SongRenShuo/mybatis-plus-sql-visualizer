package cn.song.mybatisplus.visualizer.sql.goldendb

import com.intellij.database.Dbms
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.SqlLanguageDialectBase
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.sql92.Sql92Dialect

/**
 * T-GoldenDbDialect（券 A / P-8）：GoldenDB SQL 语言方言——平台注册用最小实现。
 *
 * 背景：与达梦 [cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect]、GaussDB
 * [cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect]、HighGo、Vastbase 同一缺口——
 * 262 平台的 SQL 方言注册表（database-plugin `<sql.dialect dbms=…>` 项）**无 GoldenDB**。
 * P-8 取证（2026-11-07）：`.intellijPlatform/localPlatformArtifacts/IU-262.9437.185` 全量
 * 345 个 bundledModule/bundledPlugin xml `grep -ri golden` 零命中，正对照 mysql/greenplum
 * 同接口命中——0 是真 0。本类注册后语言 id `GoldenDB` 进入平台可用清单，
 * `ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GoldenDB"]` 候选链首项即命中，
 * 用户也可在设置页 SQL Dialects 里选它。
 *
 * 形态照抄平台最小样板 GenericDialect（同 `VastbaseSqlDialect` 逐行模板）：
 *  - 构造传语言 id `GoldenDB`；
 *  - [createTokensHelper] 委托 [Sql92Dialect]（零自维护关键字/令牌）；
 *  - [getDbms] 返回 [Dbms.UNKNOWN]（平台无 GoldenDB 数据库方言枚举，诚实未知）；
 *  - [supportsErrorHighlighting] = false（无 GoldenDB 专属 parser，不开错误高亮防假报错）。
 *
 * 已知边界：无 GoldenDB 专属 parser/错误检查，`GoldenDB` 文件的高亮视觉与 Generic 相近；
 * 关键字集未按 GoldenDB 扩展——**方言渲染侧的保留字另有单一事实源**
 * （`SqlKeywords.GOLDENDB_RESERVED`，供 `Dialect.quoteIfKeyword` 用），两者职责不同。
 * 渲染口径按 MySQL 形态（见 `GoldenDbDialect` KDoc：分页/反引号/upsert/事务四项 p66/p73/p83/p320）。
 */
class GoldenDbSqlDialect private constructor() : SqlLanguageDialectBase(ID) {

    override fun createTokensHelper(): TokensHelper = Sql92Dialect.INSTANCE.tokensHelper

    override fun isOperatorSupported(op: IElementType): Boolean = Sql92Dialect.INSTANCE.isOperatorSupported(op)

    override fun getSystemVariables(): Set<String> = Sql92Dialect.INSTANCE.systemVariables

    override fun getDbms(): Dbms = Dbms.UNKNOWN

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun supportsErrorHighlighting(): Boolean = false

    companion object {
        // sql.dialect 使用 DbmsExtension.InstanceBean，读取 public static INSTANCE，不调用构造器。
        @JvmField val INSTANCE: GoldenDbSqlDialect = GoldenDbSqlDialect()

        /**
         * 语言/方言 id：亦即 `ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GoldenDB"]`
         * 候选链的首项，以及 `Dialect.name`（`GoldenDbDialect`）的逐字值——三处必须一致。
         */
        const val ID: String = "GoldenDB"

        /** 设置页（SQL Dialects）/ 数据源选择器里的显示名。 */
        const val DISPLAY_NAME: String = "GoldenDB"
    }
}
