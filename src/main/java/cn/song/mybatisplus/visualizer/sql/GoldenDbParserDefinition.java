// T-SyncK2JMaster177 95e107b 自 master sql/SqlFamilyParserDefinitions.kt（GoldenDbParserDefinition，新增）移植
package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.sql.goldendb.GoldenDbSqlDialect;

/** GoldenDB（语言 id {@code GoldenDB}，T-GoldenDbDialect 券 A——与上面四个自注册方言同款成对注册）。 */
public final class GoldenDbParserDefinition extends MpvSqlFamilyParserDefinition {

    public GoldenDbParserDefinition() {
        super(GoldenDbSqlDialect.INSTANCE, "MPV_GOLDENDB_SQL_FILE");
    }
}
