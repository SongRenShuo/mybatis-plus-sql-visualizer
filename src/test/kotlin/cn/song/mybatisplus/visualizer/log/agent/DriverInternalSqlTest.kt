package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-DriverInternalSqlFilter：驱动内部语句识别（纯函数表驱动，headless）。
 *
 * 正例即沙盒实证（2026-11-21 Jdk16Demo 现场，mysql-connector 5.1.49）：条目 #1 带驱动版本注释的
 * `@@session` 大查询、#2/#3 无注释形态。负例钉住「刻意不收」的边界——SQL Server 的 `@@IDENTITY`
 * 是真业务 SQL、`SET NAMES` 无实证不收（防误伤，见计划 20261121-1412 §一）。
 *
 * 反向验证：git 补丁摘掉 [AgentSessionSupport] 入队点的过滤分支 → 端到端用例必红（本类不动，
 * 钉的是判据本身；端到端接线在 AgentManagerIntakeGateTest）。
 */
class DriverInternalSqlTest {

    @Test
    fun 实证三形态全命中() {
        // 条目 #1：带驱动版本注释的大查询（现场原文形态）
        assertTrue(
            DriverInternalSql.isInternal(
                "/* mysql-connector-java-5.1.49 (Revision: ad6f36e100e104cd9266b81c8cab956570116) */" +
                    "SELECT @@session.auto_increment_increment AS auto_increment_increment, " +
                    "@@character_set_client AS character_set_client",
            ),
        )
        // 条目 #2/#3：无注释形态
        assertTrue(DriverInternalSql.isInternal("SELECT @@session.autocommit;"))
        assertTrue(DriverInternalSql.isInternal("SELECT @@session.transaction.read_only;"))
    }

    @Test
    fun 大小写与前导空白变体命中() {
        assertTrue(DriverInternalSql.isInternal("  select @@session.autocommit"))
        assertTrue(DriverInternalSql.isInternal("SELECT @@SESSION.auto_increment_increment"))
        assertTrue(DriverInternalSql.isInternal("  /* MYSQL-CONNECTOR-JAVA-5.1.49 (Revision: x) */SELECT 1"))
        assertTrue(DriverInternalSql.isInternal("SET @@session.sql_mode=1"))
    }

    @Test
    fun sqlServer业务语句不得误伤() {
        // @@ 家族只收 @@session. 带点形态；@@IDENTITY/@@ROWCOUNT/@@VERSION 是真业务 SQL 常客
        assertFalse(DriverInternalSql.isInternal("SELECT @@IDENTITY AS id"))
        assertFalse(DriverInternalSql.isInternal("select @@rowcount"))
        assertFalse(DriverInternalSql.isInternal("SELECT @@VERSION"))
        assertFalse(DriverInternalSql.isInternal("insert into t(a) output inserted.id values(1)"))
    }

    @Test
    fun 无实证形态与业务语句不命中() {
        // SET NAMES 等无实证形态刻意不收（扩展须先有实证）
        assertFalse(DriverInternalSql.isInternal("SET NAMES utf8mb4"))
        assertFalse(DriverInternalSql.isInternal("SET character_set_results = NULL"))
        assertFalse(DriverInternalSql.isInternal("SELECT * FROM user WHERE name = '@@session.'"))
        assertFalse(DriverInternalSql.isInternal("SELECT 1"))
    }

    @Test
    fun 空与null不命中() {
        assertFalse(DriverInternalSql.isInternal(null))
        assertFalse(DriverInternalSql.isInternal(""))
        assertFalse(DriverInternalSql.isInternal("   "))
    }
}
