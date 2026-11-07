// T-SyncK2JMaster177 95e107b 自 master sql/goldendb/GoldenDbSqlDialect.kt（新增）移植
package cn.song.mybatisplus.visualizer.sql.goldendb;

import com.intellij.database.Dbms;
import com.intellij.psi.tree.IElementType;
import com.intellij.sql.dialects.base.SqlLanguageDialectBase;
import com.intellij.sql.dialects.base.TokensHelper;
import com.intellij.sql.dialects.sql92.Sql92Dialect;

import java.util.Set;

/**
 * T-GoldenDbDialect（券 A / P-8）：GoldenDB SQL 语言方言——平台注册用最小实现。
 *
 * <p>背景：与达梦 {@code cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect}、GaussDB
 * {@code cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect}、HighGo、Vastbase 同一缺口——
 * 262 平台的 SQL 方言注册表（database-plugin {@code <sql.dialect dbms=…>} 项）<b>无 GoldenDB</b>。
 * P-8 取证（2026-11-07）：{@code .intellijPlatform/localPlatformArtifacts/IU-262.9437.185} 全量
 * 345 个 bundledModule/bundledPlugin xml {@code grep -ri golden} 零命中，正对照 mysql/greenplum
 * 同接口命中——0 是真 0。本类注册后语言 id {@code GoldenDB} 进入平台可用清单，
 * {@code ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GoldenDB"]} 候选链首项即命中，
 * 用户也可在设置页 SQL Dialects 里选它。</p>
 *
 * <p>形态照抄平台最小样板 GenericDialect（同 {@code VastbaseSqlDialect} 逐行模板）：</p>
 * <ul>
 * <li>构造传语言 id {@code GoldenDB}；</li>
 * <li>{@link #createTokensHelper()} 委托 {@link Sql92Dialect}（零自维护关键字/令牌）；</li>
 * <li>{@link #getDbms()} 返回 {@link Dbms#UNKNOWN}（平台无 GoldenDB 数据库方言枚举，诚实未知）；</li>
 * <li>{@link #supportsErrorHighlighting()} = false（无 GoldenDB 专属 parser，不开错误高亮防假报错）。</li>
 * </ul>
 *
 * <p>已知边界：无 GoldenDB 专属 parser/错误检查，{@code GoldenDB} 文件的高亮视觉与 Generic 相近；
 * 关键字集未按 GoldenDB 扩展——<b>方言渲染侧的保留字另有单一事实源</b>
 * （{@code SqlKeywords.GOLDENDB_RESERVED}，供 {@code Dialect.quoteIfKeyword} 用），两者职责不同。
 * 渲染口径按 MySQL 形态（见 {@code GoldenDbDialect} KDoc：分页/反引号/upsert/事务四项 p66/p73/p83/p320）。</p>
 */
public final class GoldenDbSqlDialect extends SqlLanguageDialectBase {

    // sql.dialect 使用 DbmsExtension.InstanceBean，读取 public static INSTANCE，不调用构造器。
    public static final GoldenDbSqlDialect INSTANCE = new GoldenDbSqlDialect();

    /**
     * 语言/方言 id：亦即 {@code ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["GoldenDB"]}
     * 候选链的首项，以及 {@code Dialect.name}（{@code GoldenDbDialect}）的逐字值——三处必须一致。
     */
    public static final String ID = "GoldenDB";

    /** 设置页（SQL Dialects）/ 数据源选择器里的显示名。 */
    public static final String DISPLAY_NAME = "GoldenDB";

    private GoldenDbSqlDialect() {
        super(ID);
    }

    @Override
    public TokensHelper createTokensHelper() {
        return Sql92Dialect.INSTANCE.getTokensHelper();
    }

    @Override
    public boolean isOperatorSupported(IElementType op) {
        return Sql92Dialect.INSTANCE.isOperatorSupported(op);
    }

    @Override
    public Set<String> getSystemVariables() {
        return Sql92Dialect.INSTANCE.getSystemVariables();
    }

    @Override
    public Dbms getDbms() {
        return Dbms.UNKNOWN;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean supportsErrorHighlighting() {
        return false;
    }
}
