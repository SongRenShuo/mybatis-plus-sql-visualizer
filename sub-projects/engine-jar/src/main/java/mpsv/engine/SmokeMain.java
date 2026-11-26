// T-SyncK2JMaster181 自 master sub-projects/engine-jar/src/main/kotlin/mpsv/engine/SmokeMain.kt 移植
// （master 冻结 tip f06828c2d，27 行 Kotlin → Java）。
package mpsv.engine;

/**
 * 冒烟入口（{@code sub-projects/engine-jar/smoke.sh} 调用）：三个门面各跑一例并打印 JSON。
 *
 * <p>刻意放在引擎自有源目录（不进生产包）：它是「产物可用性」的自检，不是生产 API。
 *
 * <p><b>移植注记</b>：Kotlin {@code """ … """.trimIndent()} 原始字符串 → Java 文本块（{@code """}）。
 * 文本块与 {@code trimIndent()} 的差异在**行尾空白与首行**：文本块按最小缩进剥离后同样不含结尾换行
 * （结尾 {@code """} 前无内容即无尾换行），而 {@code trimIndent()} 会保留行内尾部空白——
 * 本用例的两行日志原文行尾无空白，两者逐字节相同。
 */
public final class SmokeMain {

    private SmokeMain() {
    }

    public static void main(String[] args) {
        String logText = """
            2026-11-25 10:00:00.123 DEBUG 1 [main] o.a.i.l.j.BaseJdbcLogger - ==>  Preparing: SELECT id, name FROM sys_user WHERE id = ? AND status = ?
            2026-11-25 10:00:00.124 DEBUG 1 [main] o.a.i.l.j.BaseJdbcLogger - ==> Parameters: 42(Long), ACTIVE(String)
            """;

        String writeSql = "UPDATE sys_user SET status = 'LOCKED'";
        String slowSql = "SELECT * FROM sys_user WHERE name LIKE '%abc' ORDER BY id";

        System.out.println("== renderLogs ==");
        System.out.println(EngineApi.renderLogs(logText, "MySQL"));
        System.out.println("== checkSql ==");
        System.out.println(EngineApi.checkSql(writeSql));
        System.out.println("== perfAdvice ==");
        System.out.println(EngineApi.perfAdvice(slowSql, "MySQL"));
    }
}
