package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-MagpieGems W4：执行错误分类学（先归因再处置）判定矩阵。
 *
 * 机制取自 magpie fallback 错误分类学：错在连接/对象/权限/语法，出路完全不同。
 * 判定顺序即优先级：CONNECTION → PERMISSION → OBJECT_MISSING → SYNTAX → OTHER。
 * 反向探针约定（S2）：摘掉 PERMISSION 先于 OBJECT_MISSING 的顺序 → 归因优先级测试红；
 * 摘掉 42 子类细分（42S02/42S22）→ 对象缺失档红；摘掉 format 接线 → 出路提示测试红。
 */
class ExecutionErrorCategoryTest {

    private fun info(
        message: String,
        sqlState: String? = null,
        errorCode: Int? = null,
    ) = ExecutionErrorInfo(message = message, sqlState = sqlState, errorCode = errorCode)

    // ---------- CONNECTION（既有 ConnectionErrorClassifier 语义不回归） ----------

    @Test
    fun test连接类_三路判定不回归() {
        assertEquals(ExecutionErrorCategory.CONNECTION, ExecutionErrorClassifier.classify(info("Communications link failure", "08S01", 0)))
        assertEquals(ExecutionErrorCategory.CONNECTION, ExecutionErrorClassifier.classify(info("Connection refused", null, null)))
        assertEquals(ExecutionErrorCategory.CONNECTION, ExecutionErrorClassifier.classify(info("gone away", null, 2006)))
        assertEquals(ExecutionErrorCategory.CONNECTION, ExecutionErrorClassifier.classify(info("IO Error", "08003", null)))
    }

    // ---------- PERMISSION ----------

    @Test
    fun test权限族_码与签名() {
        assertEquals(ExecutionErrorCategory.PERMISSION, ExecutionErrorClassifier.classify(info("Access denied for user 'u'@'h' (using password: YES)", "28000", 1045)))
        assertEquals(ExecutionErrorCategory.PERMISSION, ExecutionErrorClassifier.classify(info("SELECT command denied to user 'u'@'h' for table 't'", "42000", 1142)))
        assertEquals(ExecutionErrorCategory.PERMISSION, ExecutionErrorClassifier.classify(info("insufficient privileges", null, 1031)))
        assertEquals(ExecutionErrorCategory.PERMISSION, ExecutionErrorClassifier.classify(info("permission denied for table x", "42501", null)))
        assertEquals(ExecutionErrorCategory.PERMISSION, ExecutionErrorClassifier.classify(info("user does not have permission", null, null)))
    }

    // ---------- OBJECT_MISSING ----------

    @Test
    fun test对象缺失族_码_SQLState与签名() {
        assertEquals(ExecutionErrorCategory.OBJECT_MISSING, ExecutionErrorClassifier.classify(info("Table 'mpj_demo.user' doesn't exist", "42S02", 1146)))
        assertEquals(ExecutionErrorCategory.OBJECT_MISSING, ExecutionErrorClassifier.classify(info("Unknown column 'username' in 'field list'", "42S22", 1054)))
        assertEquals(ExecutionErrorCategory.OBJECT_MISSING, ExecutionErrorClassifier.classify(info("ORA-00942: table or view does not exist", null, 942)))
        assertEquals(ExecutionErrorCategory.OBJECT_MISSING, ExecutionErrorClassifier.classify(info("ORA-00904: \"NAEM\": invalid identifier", null, 904)))
        assertEquals(ExecutionErrorCategory.OBJECT_MISSING, ExecutionErrorClassifier.classify(info("ERROR: column \"naem\" does not exist", "42703", null)))
        assertEquals(ExecutionErrorCategory.OBJECT_MISSING, ExecutionErrorClassifier.classify(info("no such table: t1", null, null)))
        assertEquals(ExecutionErrorCategory.OBJECT_MISSING, ExecutionErrorClassifier.classify(info("Unknown database 'mpj_demo'", null, 1049)))
    }

    // ---------- SYNTAX ----------

    @Test
    fun test语法族_码_SQLState与签名() {
        assertEquals(ExecutionErrorCategory.SYNTAX, ExecutionErrorClassifier.classify(info("You have an error in your SQL syntax; check the manual", "42000", 1064)))
        assertEquals(ExecutionErrorCategory.SYNTAX, ExecutionErrorClassifier.classify(info("ORA-00933: SQL command not properly ended", null, 933)))
        assertEquals(ExecutionErrorCategory.SYNTAX, ExecutionErrorClassifier.classify(info("ORA-00936: missing expression", null, 936)))
        assertEquals(ExecutionErrorCategory.SYNTAX, ExecutionErrorClassifier.classify(info("syntax error at or near \"SELEC\"", "42601", null)))
        assertEquals(ExecutionErrorCategory.SYNTAX, ExecutionErrorClassifier.classify(info("ORA-01756: quoted string not properly terminated", null, null)))
    }

    // ---------- OTHER（不硬给出路） ----------

    @Test
    fun test其它错误_保持OTHER() {
        assertEquals(ExecutionErrorCategory.OTHER, ExecutionErrorClassifier.classify(info("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213)))
        assertEquals(ExecutionErrorCategory.OTHER, ExecutionErrorClassifier.classify(info("Duplicate entry '1' for key 'PRIMARY'", "23000", 1062)))
        assertEquals(ExecutionErrorCategory.OTHER, ExecutionErrorClassifier.classify(info("Query execution was interrupted", "70100", 1317)))
        assertEquals(ExecutionErrorCategory.OTHER, ExecutionErrorClassifier.classify(info("offline", errorCode = 0)))
    }

    // ---------- 归因优先级（magpie 语义：先归因再处置的次序本身就是契约） ----------

    @Test
    fun test归因优先级_权限压过对象缺失与语法() {
        // 同串既有权限语义又有对象缺失语义 → 权限档吃掉（先修权限才有意义）
        assertEquals(
            ExecutionErrorCategory.PERMISSION,
            ExecutionErrorClassifier.classify(info("SELECT command denied to user 'u'@'h' for table 't' that does not exist", "42000", 1142)),
        )
        // SQLState 42S02（对象缺失）不得被 42 前缀语法兜底提前吃掉
        assertEquals(
            ExecutionErrorCategory.OBJECT_MISSING,
            ExecutionErrorClassifier.classify(info("Table 'db.t' doesn't exist", "42S02", null)),
        )
        // 连接码命中时即便消息含 access denied 也归连接（码表在排除表之上，与既有 classifier 一致）
        assertEquals(
            ExecutionErrorCategory.CONNECTION,
            ExecutionErrorClassifier.classify(info("access denied while connection reset", null, 2006)),
        )
    }

    // ---------- 出路提示接线（format 单点） ----------

    @Test
    fun test出路提示_有出路的档追加静态文案且不回显参数值() {
        val sensitiveValue = "13800138000"
        val info = info("Table 'mpj_demo.user' doesn't exist (row: $sensitiveValue)", "42S02", 1146)
        val text = ExecutionErrorFormatter.format(info)
        val expectedHint = MpSqlVisualizerBundle.message("error.category.objectMissing")
        assertTrue("出路提示追加在末行", text.endsWith(expectedHint))
        assertTrue("提示不含原值", !expectedHint.contains(sensitiveValue))
        // CONNECTION 与 OTHER 无出路提示
        assertEquals("plain", ExecutionErrorFormatter.format(info("offline", errorCode = 0)))
        assertEquals(
            "Communications link failure",
            ExecutionErrorFormatter.format(info("Communications link failure", null, 2013)),
        )
    }

    @Test
    fun test每个有hintKey的档位键存在且非空文案() {
        for (category in ExecutionErrorCategory.entries) {
            if (category.hintKey == null) continue
            val text = MpSqlVisualizerBundle.message(category.hintKey!!)
            assertFalse("档位 ${category.name} 的文案键缺失（返回了 key 本身）", text == category.hintKey)
            assertTrue(text.isNotBlank())
        }
    }
}
