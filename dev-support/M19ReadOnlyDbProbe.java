import cn.song.mybatisplus.visualizer.execute.DmlImpactEstimator;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.MySqlDialect;
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect;

/** Emits production-generated SQL for a synthetic, read-only database acceptance run. */
public class M19ReadOnlyDbProbe {
    public static void main(String[] args) {
        String cte = "WITH m19_rows AS (SELECT 1 AS id, 10 AS amount UNION ALL SELECT 2, 20 UNION ALL SELECT 3, 30) ";
        StringBuilder where = new StringBuilder("amount >= 10");
        for (int i = 0; i < 24; i++) where.append(" AND id >= 1");
        where.append(" AND id = 3");
        for (Dialect dialect : new Dialect[] { MySqlDialect.INSTANCE, PostgreSQLDialect.INSTANCE }) {
            for (String verb : new String[] { "UPDATE m19_rows SET amount = 0", "DELETE FROM m19_rows" }) {
                DmlImpactEstimator.Plan plan = DmlImpactEstimator.INSTANCE.plan(verb + " WHERE " + where, dialect);
                if (!(plan instanceof DmlImpactEstimator.Plan.Applicable)) throw new AssertionError(plan);
                DmlImpactEstimator.Probe probe = ((DmlImpactEstimator.Plan.Applicable) plan).getProbe();
                if (probe.getWhereText().length() != 200 || !probe.getProbeSql().endsWith("id = 3")) {
                    throw new AssertionError("Display truncation must not truncate executable predicate");
                }
                System.out.println(dialect.getName() + ":" + verb.split(" ")[0]);
                System.out.println(cte + probe.getProbeSql() + ";");
            }
            String query = "SELECT id FROM (SELECT 1 AS id UNION ALL SELECT 2 UNION ALL SELECT 3) m19_rows WHERE id = 3";
            System.out.println(dialect.getName() + ":EXPLAIN");
            System.out.println(dialect.renderExplainStatement(query) + ";");
        }
    }
}
