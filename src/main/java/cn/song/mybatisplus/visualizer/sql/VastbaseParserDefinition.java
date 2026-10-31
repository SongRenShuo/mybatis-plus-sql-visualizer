// T-SyncK2JMaster175 afd3d09 自 master sql/SqlFamilyParserDefinitions.kt（VastbaseParserDefinition）移植
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.sql.vastbase.VastbaseSqlDialect;

/** 海量 Vastbase（语言 id {@code Vastbase}）。 */
public final class VastbaseParserDefinition extends MpvSqlFamilyParserDefinition {

    public VastbaseParserDefinition() {
        super(VastbaseSqlDialect.INSTANCE, "MPV_VASTBASE_SQL_FILE");
    }
}
