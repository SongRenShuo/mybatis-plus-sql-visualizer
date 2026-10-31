package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R52 方言能力矩阵 + 执行前自检（V79 三前提全过）定向测试。
 *
 * 覆盖：①矩阵读接口真值（防伪能力——不列实现里没有的能力）；②全方言矩阵完整性；
 * ③非 EXPLAIN 缺口检测（DELETE LIMIT / UPSERT / identity 包装）与「支持方言零缺口」对照；
 * ④EXPLAIN 不进缺口清单（V79③：ExplainGate 已覆盖 EXPLAIN，不重复）。
 * 普通 JUnit——纯逻辑层，无 IDE 类型。
 *
 * k2j 移植注（生产为 Java）：方言单例为 `XxxDialect.INSTANCE` 静态；`Capability` 为 Java enum
 * （全序遍历用 `values().toList()`，Kotlin 的 `entries` 保留给 Kotlin enum）；矩阵行/缺口为
 * Java record（capability/supported/reasonKey 按组件访问器直读）。
 */
class DialectCapabilitiesTest {

    @Test
    fun `矩阵读接口真值_MySQL族与Oracle缺口如实`() {
        val mysql = DialectCapabilities.matrix(MySqlDialect.INSTANCE).associateBy { it.capability }
        assertEquals(true, mysql.getValue(DialectCapabilities.Capability.EXPLAIN).supported)
        assertEquals(true, mysql.getValue(DialectCapabilities.Capability.DELETE_LIMIT).supported)
        assertEquals(true, mysql.getValue(DialectCapabilities.Capability.UPSERT).supported)
        // MySQL 不需要 identity 包装（renderInsertWrapper 默认 null）→ 如实为不支持
        assertEquals(false, mysql.getValue(DialectCapabilities.Capability.IDENTITY_WRAP).supported)
        assertNull("支持项没有原因文案", mysql.getValue(DialectCapabilities.Capability.UPSERT).reasonKey)
        assertEquals(
            "dialect.capability.reason.identityWrap",
            mysql.getValue(DialectCapabilities.Capability.IDENTITY_WRAP).reasonKey,
        )

        val oracle = DialectCapabilities.matrix(OracleDialect.INSTANCE).associateBy { it.capability }
        assertEquals(true, oracle.getValue(DialectCapabilities.Capability.EXPLAIN).supported)
        assertEquals(false, oracle.getValue(DialectCapabilities.Capability.DELETE_LIMIT).supported)
        assertEquals(false, oracle.getValue(DialectCapabilities.Capability.UPSERT).supported)
        assertEquals(false, oracle.getValue(DialectCapabilities.Capability.IDENTITY_WRAP).supported)
    }

    @Test
    fun `EXPLAIN矩阵逐方言与唯一执行声明源一致`() {
        for (dialect in DialectRegistry.INSTANCE.dialects) {
            assertEquals(
                dialect.name,
                ExplainSupport.entryFor(dialect.name)?.supported() == true,
                DialectCapabilities.isSupported(DialectCapabilities.Capability.EXPLAIN, dialect),
            )
        }
    }

    @Test
    fun `矩阵覆盖全注册方言且每方言四项`() {
        for (dialect in DialectRegistry.INSTANCE.dialects) {
            val rows = DialectCapabilities.matrix(dialect)
            assertEquals(dialect.name, 4, rows.size)
            assertEquals(
                DialectCapabilities.Capability.values().toList(),
                rows.map { it.capability },
            )
        }
    }

    @Test
    fun `DELETE带LIMIT_MySQL零缺口_非MySQL族报缺口`() {
        val sql = "DELETE FROM t WHERE id = 1 LIMIT 10"
        assertTrue(DialectCapabilities.gapsFor(sql, MySqlDialect.INSTANCE).isEmpty())
        val oracleGaps = DialectCapabilities.gapsFor(sql, OracleDialect.INSTANCE)
        assertEquals(1, oracleGaps.size)
        assertEquals(DialectCapabilities.Capability.DELETE_LIMIT, oracleGaps[0].capability)
        assertEquals("Oracle", oracleGaps[0].dialectName)
        assertEquals("execute.confirm.capability.deleteLimit", oracleGaps[0].messageKey)
    }

    @Test
    fun `LIMIT只在DELETE语句内才算缺口_UPDATE带LIMIT不算`() {
        assertFalse(DialectCapabilities.hasDeleteLimitTail("UPDATE t SET a = 1 WHERE id = 1 LIMIT 10"))
        assertTrue(DialectCapabilities.hasDeleteLimitTail("DELETE FROM t WHERE id = 1 LIMIT 10"))
        // 字面量/列名里的 limit 不误判
        assertFalse(DialectCapabilities.hasDeleteLimitTail("DELETE FROM t WHERE note = 'limit 10'"))
    }

    @Test
    fun `UPSERT形态_MySQL零缺口_Oracle报缺口`() {
        val pgStyle = "INSERT INTO t (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET id = EXCLUDED.id"
        assertTrue(DialectCapabilities.gapsFor(pgStyle, PostgreSQLDialect.INSTANCE).isEmpty())
        assertEquals(
            DialectCapabilities.Capability.UPSERT,
            DialectCapabilities.gapsFor(pgStyle, OracleDialect.INSTANCE).single().capability,
        )
        val mysqlStyle = "INSERT INTO t (id) VALUES (1) ON DUPLICATE KEY UPDATE id = VALUES(id)"
        assertEquals(
            "MySQL 族原生 upsert 零缺口",
            0,
            DialectCapabilities.gapsFor(mysqlStyle, MySqlDialect.INSTANCE).size,
        )
        val mergeStyle = "MERGE INTO t (id) KEY (id) VALUES (1)"
        assertEquals(
            "整语句 MERGE 形态在 H2 支持、Oracle 同判为缺口",
            0,
            DialectCapabilities.gapsFor(mergeStyle, H2Dialect.INSTANCE).size,
        )
        assertEquals(
            DialectCapabilities.Capability.UPSERT,
            DialectCapabilities.gapsFor(mergeStyle, OracleDialect.INSTANCE).single().capability,
        )
    }

    @Test
    fun `identity包装_SQLServer零缺口_MySQL报缺口`() {
        val product = "SET IDENTITY_INSERT [user] ON;\n" +
            "INSERT INTO [user] ([id], [name]) VALUES (1, 'Tom');\n" +
            "SET IDENTITY_INSERT [user] OFF;"
        assertTrue(DialectCapabilities.gapsFor(product, SqlServerDialect.INSTANCE).isEmpty())
        assertEquals(
            DialectCapabilities.Capability.IDENTITY_WRAP,
            DialectCapabilities.gapsFor(product, MySqlDialect.INSTANCE).single().capability,
        )
    }

    @Test
    fun `EXPLAIN不进缺口清单_ExplainGate已覆盖`() {
        // 纯 SELECT 无缺口
        assertTrue(DialectCapabilities.gapsFor("SELECT * FROM t", MySqlDialect.INSTANCE).isEmpty())
        // EXPLAIN 缺口由 ExplainGate 提示，本对象不重复报
        assertTrue(DialectCapabilities.gapsFor("EXPLAIN SELECT * FROM t", OracleDialect.INSTANCE).isEmpty())
    }

    @Test
    fun `矩阵呈现文本含能力名与降级原因`() {
        val text = DialectCapabilities.summaryText(OracleDialect.INSTANCE)
        assertTrue(text, text.contains("EXPLAIN"))
        assertTrue(text, text.contains("DELETE ... LIMIT"))
        assertTrue(text, text.contains("UPSERT"))
        assertTrue(text, text.contains("IDENTITY"))
        // 支持项标 supported，不支持项出原因（非 supported）
        assertTrue(text, text.contains(cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message("dialect.capability.supported")))
    }

    /**
     * T-GaussDbDialect 券 02 第 2 项：`DELETE ... LIMIT` 判无的**用户可见说明**必须真的呈现得出来。
     *
     * 判无的理由已从「PG 语义静默错解」改成「语法接受、语义未验证」，但改 KDoc 只是内部留痕；
     * 用户侧要能看到「本方言不支持 `DELETE ... LIMIT n`」。呈现路径是**能力矩阵**
     * （预览 meta 悬停 + 诊断报告的方言能力段）——**不是** `gapsFor` 的执行前确认弹窗：
     * 判无后渲染产物里本来就没有 LIMIT 尾段，`hasDeleteLimitTail` 恒 false，缺口永远报不出来，
     * 所以矩阵这一路是唯一的说明出口。
     *
     * 断言刻意打在 `summaryText` 的实际文案上，而不是只断言 `reasonKey` 常量——后者只能证明
     * 「指向了某个键」，证不了「键有值、且真的渲染出来了」。
     */
    @Test
    fun `gauss族_DELETE_LIMIT判无在能力矩阵里给得出用户可见说明`() {
        val reason = cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
            .message("dialect.capability.reason.deleteLimit")
        assertTrue("bundle 文案不应为空：$reason", reason.isNotBlank())
        listOf(OpenGaussDialect.INSTANCE, GaussDbDialect.INSTANCE).forEach { dialect ->
            val row = DialectCapabilities.matrix(dialect)
                .single { it.capability == DialectCapabilities.Capability.DELETE_LIMIT }
            assertFalse("${dialect.name} 的 DELETE LIMIT 应如实判无", row.supported)
            assertEquals("dialect.capability.reason.deleteLimit", row.reasonKey)
            val text = DialectCapabilities.summaryText(dialect)
            assertTrue(
                "${dialect.name} 的能力矩阵应出现用户可见的 DELETE LIMIT 原因，实际：$text",
                text.contains(reason),
            )
        }
    }
}
