// T-SyncK2JMaster175 afd3d09 自 master sql/SqlFamilyParserDefinitions.kt（DmParserDefinition）移植
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.sql.dm.DmSqlDialect;

/** 达梦（{@code database-optional.xml} 的 {@code sql.dialect} 语言 id {@code DM}）。 */
public final class DmParserDefinition extends MpvSqlFamilyParserDefinition {

    public DmParserDefinition() {
        super(DmSqlDialect.INSTANCE, "MPV_DM_SQL_FILE");
    }
}
