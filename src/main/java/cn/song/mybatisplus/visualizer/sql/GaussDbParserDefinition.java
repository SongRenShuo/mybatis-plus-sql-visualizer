// T-SyncK2JMaster175 afd3d09 自 master sql/SqlFamilyParserDefinitions.kt（GaussDbParserDefinition）移植
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.sql.gaussdb.GaussDbSqlDialect;

/** GaussDB（语言 id {@code GaussDB}）。 */
public final class GaussDbParserDefinition extends MpvSqlFamilyParserDefinition {

    public GaussDbParserDefinition() {
        super(GaussDbSqlDialect.INSTANCE, "MPV_GAUSSDB_SQL_FILE");
    }
}
