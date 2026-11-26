package mpsv.engine

/**
 * 冒烟入口（`sub-projects/engine-jar/smoke.sh` 调用）：三个门面各跑一例并打印 JSON。
 *
 * 刻意放在引擎自有源目录（不进生产包）：它是「产物可用性」的自检，不是生产 API。
 */
object SmokeMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val logText = """
            2026-11-25 10:00:00.123 DEBUG 1 [main] o.a.i.l.j.BaseJdbcLogger - ==>  Preparing: SELECT id, name FROM sys_user WHERE id = ? AND status = ?
            2026-11-25 10:00:00.124 DEBUG 1 [main] o.a.i.l.j.BaseJdbcLogger - ==> Parameters: 42(Long), ACTIVE(String)
        """.trimIndent()

        val writeSql = "UPDATE sys_user SET status = 'LOCKED'"
        val slowSql = "SELECT * FROM sys_user WHERE name LIKE '%abc' ORDER BY id"

        println("== renderLogs ==")
        println(EngineApi.renderLogs(logText, "MySQL"))
        println("== checkSql ==")
        println(EngineApi.checkSql(writeSql))
        println("== perfAdvice ==")
        println(EngineApi.perfAdvice(slowSql, "MySQL"))
    }
}