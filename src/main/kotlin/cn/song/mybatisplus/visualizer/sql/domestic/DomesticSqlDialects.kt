package cn.song.mybatisplus.visualizer.sql.domestic

import com.intellij.database.Dbms
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.SqlLanguageDialectBase
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.sql92.Sql92Dialect

/**
 * T-DomesticDialects（2026-11-09）：国产/信创第三批 10 个 SQL 语言方言——平台注册用最小实现
 * （单文件聚合：每类仅 ID/显示名不同，拆十个文件无信息增量）。
 *
 * 背景：与达梦 [cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect]、GaussDB、HighGo、
 * Vastbase、GoldenDB 同一缺口——262 平台的 SQL 方言注册表（`<sql.dialect dbms=…>` 项）无这
 * 10 家。本文件注册后各语言 id 进入平台可用清单，
 * `ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS` 各候选链首项即命中，用户也可在
 * 设置页 SQL Dialects 里选。
 *
 * 形态照抄平台最小样板 GenericDialect（`GoldenDbSqlDialect` 逐行模板）：
 *  - 构造传语言 id（[cn.song.mybatisplus.visualizer.execute.ExecDialectRouting]
 *    PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS 候选链首项；与渲染方言 `Dialect.name` 可带中文后缀
 *    不同——平台 id 不带，两处映射各自维护）；
 *  - [createTokensHelper] 委托 [Sql92Dialect]（零自维护关键字/令牌）；
 *  - [getDbms] 返回 [Dbms.UNKNOWN]（平台无这些库的 Dbms 枚举，诚实未知）；
 *  - [supportsErrorHighlighting] = false（无专属 parser，不开错误高亮防假报错）。
 *
 * 已知边界：无专属 parser/错误检查，高亮视觉与 Generic 相近；**方言渲染侧的保留字另有
 * 单一事实源**（`SqlKeywords` 各族底表，供 `Dialect.quoteIfKeyword` 用），两者职责不同。
 *
 * 注：基类必须是 public（Kotlin 禁止 public 类暴露非 public 超类型）；`sql.dialect` EP 经
 * `DbmsExtension.InstanceBean` 读 `public static INSTANCE`，各子类零参私构造 + 伴生
 * `@JvmField val INSTANCE`，与 [cn.song.mybatisplus.visualizer.sql.goldendb.GoldenDbSqlDialect] 同款。
 */
open class DomesticSqlDialectBase(
    id: String,
    private val displayName: String,
) : SqlLanguageDialectBase(id) {

    override fun createTokensHelper(): TokensHelper = Sql92Dialect.INSTANCE.tokensHelper

    override fun isOperatorSupported(op: IElementType): Boolean = Sql92Dialect.INSTANCE.isOperatorSupported(op)

    override fun getSystemVariables(): Set<String> = Sql92Dialect.INSTANCE.systemVariables

    override fun getDbms(): Dbms = Dbms.UNKNOWN

    override fun getDisplayName(): String = displayName

    override fun supportsErrorHighlighting(): Boolean = false
}

/** TiDB——语言 id `TiDB`（候选链首项，`ExecDialectRouting`）。 */
class TiDbSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: TiDbSqlDialect = TiDbSqlDialect()
        const val ID: String = "TiDB"
        const val DISPLAY_NAME: String = "TiDB"
    }
}

/** PolarDB-X——语言 id `PolarDB-X`。 */
class PolarDbXSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: PolarDbXSqlDialect = PolarDbXSqlDialect()
        const val ID: String = "PolarDB-X"
        const val DISPLAY_NAME: String = "PolarDB-X"
    }
}

/** PolarDB（Oracle 兼容版）——语言 id `PolarDB`。 */
class PolarDbSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: PolarDbSqlDialect = PolarDbSqlDialect()
        const val ID: String = "PolarDB"
        const val DISPLAY_NAME: String = "PolarDB"
    }
}

/** GBase 8a——语言 id `GBase 8a`。 */
class GBase8aSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: GBase8aSqlDialect = GBase8aSqlDialect()
        const val ID: String = "GBase 8a"
        const val DISPLAY_NAME: String = "GBase 8a"
    }
}

/** GBase 8c——语言 id `GBase 8c`。 */
class GBase8cSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: GBase8cSqlDialect = GBase8cSqlDialect()
        const val ID: String = "GBase 8c"
        const val DISPLAY_NAME: String = "GBase 8c"
    }
}

/** 神通 Oscar——语言 id `Oscar`（平台 id 不带中文后缀，渲染方言名 `Oscar（神通）`）。 */
class OscarSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: OscarSqlDialect = OscarSqlDialect()
        const val ID: String = "Oscar"
        const val DISPLAY_NAME: String = "Oscar"
    }
}

/** 优炫 UXDB——语言 id `UXDB`（平台 id 不带中文后缀，渲染方言名 `UXDB（优炫）`）。 */
class UxDbSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: UxDbSqlDialect = UxDbSqlDialect()
        const val ID: String = "UXDB"
        const val DISPLAY_NAME: String = "UXDB"
    }
}

/** AntDB——语言 id `AntDB`。 */
class AntDbSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: AntDbSqlDialect = AntDbSqlDialect()
        const val ID: String = "AntDB"
        const val DISPLAY_NAME: String = "AntDB"
    }
}

/** 虚谷 XuGu——语言 id `XuGu`（平台 id 不带中文后缀，渲染方言名 `XuGu（虚谷）`）。 */
class XuGuSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: XuGuSqlDialect = XuGuSqlDialect()
        const val ID: String = "XuGu"
        const val DISPLAY_NAME: String = "XuGu"
    }
}

/** 崖山 YashanDB——语言 id `YashanDB`（平台 id 不带中文后缀，渲染方言名 `YashanDB（崖山）`）。 */
class YashanDbSqlDialect private constructor() : DomesticSqlDialectBase(ID, DISPLAY_NAME) {
    companion object {
        @JvmField val INSTANCE: YashanDbSqlDialect = YashanDbSqlDialect()
        const val ID: String = "YashanDB"
        const val DISPLAY_NAME: String = "YashanDB"
    }
}
