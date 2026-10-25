package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

import cn.song.mybatisplus.visualizer.execute.ExplainPanelChannelKt.classifyExplainFailure

/**
 * T-ExplainPwdPrompt W2 护栏：执行计划失败分型（[classifyExplainFailure]）纯逻辑表。
 *
 * 缺陷形态（2026-10-24 用户沙盒截图）：数据源未存密码时点「执行计划」，结构化与直送两条
 * 通道都拿 `Access denied (using password: NO)` 红字收场，无任何「补存密码」指引。分型让
 * 呈现层按 AUTH（附指引、不回退直送）/ CANCELLED（轻通知、不自动重试）/ OTHER（既有回退）
 * 三路走。
 *
 * 本类**零平台引用**（testFast 快桶成员）：分型是纯字符串判定；bundle 键在场性走文件读取
 * （不触发平台 ResourceBundle）。
 *
 * K2J 互操作：master 侧 Kotlin 顶层函数落 Java 门面类 [ExplainPanelChannelKt]（坑 1 拆分惯例），
 * Kotlin 调用须显式 import Java static 后才可裸名调用。
 */
class ExplainFailureClassifierTest {

    @Test
    fun testMysqlAccessDeniedIsAuth() {
        assertEquals(
            ExplainFailureKind.AUTH,
            classifyExplainFailure("SQLException: Access denied for user 'root'@'localhost' (using password: NO)"),
        )
    }

    @Test
    fun testPostgresPasswordAuthFailedIsAuth() {
        assertEquals(
            ExplainFailureKind.AUTH,
            classifyExplainFailure("FATAL: password authentication failed for user \"root\""),
        )
    }

    @Test
    fun testOracleInvalidCredentialsIsAuth() {
        assertEquals(
            ExplainFailureKind.AUTH,
            classifyExplainFailure("java.sql.SQLException: ORA-01017: invalid username/password; logon denied"),
        )
    }

    @Test
    fun testSqlServerLoginFailedIsAuth() {
        assertEquals(
            ExplainFailureKind.AUTH,
            classifyExplainFailure("Login failed for user 'sa'. ClientConnectionId:0x1"),
        )
    }

    @Test
    fun testPlatformCancelIsCancelled() {
        assertEquals(
            ExplainFailureKind.CANCELLED,
            classifyExplainFailure("ProcessCanceledException: (no message)"),
        )
        assertEquals(
            ExplainFailureKind.CANCELLED,
            classifyExplainFailure("java.util.concurrent.CancellationException"),
        )
    }

    @Test
    fun testDriverTimeoutWithCancelledWordIsOtherNotCancelled() {
        // MySQL 驱动超时消息含 "cancelled"——超时是执行错误不是用户取消（误判会把真失败吞成轻通知）
        assertEquals(
            ExplainFailureKind.OTHER,
            classifyExplainFailure("SQLException: Statement cancelled due to timeout [1200s]"),
        )
        assertEquals(
            ExplainFailureKind.OTHER,
            classifyExplainFailure("execution timed out after 5s"),
        )
    }

    @Test
    fun testGenericFailureIsOther() {
        assertEquals(
            ExplainFailureKind.OTHER,
            classifyExplainFailure("Connection refused: connect"),
        )
    }

    // ======================== T-ExplainFailUx W1：选错源档（表不在当前源） ========================

    @Test
    fun testMysqlTableNotFoundIsWrongTarget() {
        // 用户沙盒实测原文（含 [42S02][1146] 前缀与库名限定的表）
        assertEquals(
            ExplainFailureKind.WRONG_TARGET,
            classifyExplainFailure("[42S02][1146] Table 'mp_demo.wms_inventory_jar_demo' doesn't exist"),
        )
        assertEquals(
            ExplainFailureKind.WRONG_TARGET,
            classifyExplainFailure("Table 'mp_demo.order_entity' doesn't exist"),
        )
    }

    @Test
    fun testPostgresSqlServerOracleTableNotFoundAreWrongTarget() {
        assertEquals(
            ExplainFailureKind.WRONG_TARGET,
            classifyExplainFailure("ERROR: relation \"wms_inventory_jar_demo\" does not exist"),
        )
        assertEquals(
            ExplainFailureKind.WRONG_TARGET,
            classifyExplainFailure("Invalid object name 'order_entity'."),
        )
        assertEquals(
            ExplainFailureKind.WRONG_TARGET,
            classifyExplainFailure("ORA-00942: table or view does not exist"),
        )
    }

    @Test
    fun testUnknownColumnIsNotWrongTarget() {
        // 列级问题换源解决不了——不得归入选错源档（否则会误导用户去换源）
        assertEquals(
            ExplainFailureKind.OTHER,
            classifyExplainFailure("Unknown column 'nocol' in 'field list'"),
        )
    }

    @Test
    fun testAuthTakesPrecedenceOverWrongTarget() {
        assertEquals(
            ExplainFailureKind.AUTH,
            classifyExplainFailure("Access denied for user 'root'@'localhost' (using password: NO)"),
        )
    }

    @Test
    fun testAuthHintAndCancelledBundleKeysExistInBothLanguages() {
        for (bundle in listOf(
            "src/main/resources/messages/MpSqlVisualizerBundle.properties",
            "src/main/resources/messages/MpSqlVisualizerBundle_zh.properties",
        )) {
            val text = repoRoot().resolve(bundle).readText()
            assertTrue(
                "$bundle 缺 panel.explain.authFailureHint（T-ExplainPwdPrompt 认证失败指引键）",
                text.contains("panel.explain.authFailureHint="),
            )
            assertTrue(
                "$bundle 缺 panel.explain.cancelled（T-ExplainPwdPrompt 取消轻通知键）",
                text.contains("panel.explain.cancelled="),
            )
        }
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "src/main/resources/messages/MpSqlVisualizerBundle.properties").exists()) {
            dir = dir.parentFile
        }
        return dir ?: error("repo root not found from ${System.getProperty("user.dir")}")
    }
}
