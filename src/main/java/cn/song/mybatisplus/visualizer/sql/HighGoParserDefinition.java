// T-SyncK2JMaster175 afd3d09 自 master sql/SqlFamilyParserDefinitions.kt（HighGoParserDefinition）移植
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.sql.highgo.HighGoSqlDialect;

/** 瀚高 HighGo（语言 id {@code HighGo}）。 */
public final class HighGoParserDefinition extends MpvSqlFamilyParserDefinition {

    public HighGoParserDefinition() {
        super(HighGoSqlDialect.INSTANCE, "MPV_HIGHGO_SQL_FILE");
    }
}
