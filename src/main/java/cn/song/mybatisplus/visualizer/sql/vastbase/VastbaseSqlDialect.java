// T-SyncK2JMaster175 afd3d09 自 master sql/vastbase/VastbaseSqlDialect.kt（新增）移植
package cn.song.mybatisplus.visualizer.sql.vastbase;

import com.intellij.database.Dbms;
import com.intellij.psi.tree.IElementType;
import com.intellij.sql.dialects.base.SqlLanguageDialectBase;
import com.intellij.sql.dialects.base.TokensHelper;
import com.intellij.sql.dialects.sql92.Sql92Dialect;

import java.util.Set;

/**
 * T-XcDialects3（券 08）：Vastbase SQL 语言方言——平台注册用最小实现。
 *
 * <p>背景：与达梦 {@code cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect}、GaussDB
 * {@code cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect} 同一缺口——262 平台的 SQL
 * 方言注册表（database-plugin {@code <sql.dialect dbms=…>} 项，AZURE~VERTICA）<b>无 Vastbase</b>。
 * {@code ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["Vastbase"]} 的候选链原本只能落到
 * {@code PostgreSQL} 兜底，{@code SqlDialectMappings.getAvailableValues} 里没有 Vastbase，用户也没法在
 * 设置页 SQL Dialects 里选它。本类注册后语言 id {@code Vastbase} 进入平台可用清单，候选链首项
 * 即命中，{@code IdeaSqlContextBridge.pluginDialectName} 的精确名匹配也随之生效。</p>
 *
 * <p>形态照抄平台最小样板 GenericDialect（同 {@code DmSqlDialect}/{@code GaussDbSqlDialect}）：</p>
 * <ul>
 * <li>构造传语言 id {@code Vastbase}；</li>
 * <li>{@link #createTokensHelper()} 委托 {@link Sql92Dialect}（零自维护关键字/令牌）；</li>
 * <li>{@link #getDbms()} 返回 {@link Dbms#UNKNOWN}（平台无 Vastbase 数据库方言枚举，诚实未知）；</li>
 * <li>{@link #supportsErrorHighlighting()} = false（无 Vastbase 专属 parser，不开错误高亮防假报错）。</li>
 * </ul>
 *
 * <p>已知边界：无 Vastbase 专属 parser/错误检查，{@code Vastbase} 文件的高亮视觉与 Generic 相近；
 * 关键字集未按海量扩展——<b>方言渲染侧的保留字另有单一事实源</b>
 * （{@code SqlKeywords.VASTBASE_RESERVED}，供 {@code Dialect.quoteIfKeyword} 用），两者职责不同。
 * 渲染口径按 PG 兼容形态（A 模式默认实例未分叉，见 {@code VastbaseDialect} KDoc）。</p>
 */
public final class VastbaseSqlDialect extends SqlLanguageDialectBase {

    // sql.dialect 使用 DbmsExtension.InstanceBean，读取 public static INSTANCE，不调用构造器。
    public static final VastbaseSqlDialect INSTANCE = new VastbaseSqlDialect();

    /**
     * 语言/方言 id：亦即 {@code ExecDialectRouting.PLUGIN_DIALECT_TO_SQL_LANGUAGE_IDS["Vastbase"]}
     * 候选链的首项，以及 {@code Dialect.name}（{@code VastbaseDialect}）的逐字值——三处必须一致。
     */
    public static final String ID = "Vastbase";

    /** 设置页（SQL Dialects）/ 数据源选择器里的显示名。 */
    public static final String DISPLAY_NAME = "Vastbase";

    private VastbaseSqlDialect() {
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
