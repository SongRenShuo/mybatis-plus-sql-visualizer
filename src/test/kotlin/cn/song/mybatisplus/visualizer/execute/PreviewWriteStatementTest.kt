package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PreviewDmlResult：预览执行「写语句 → 结构上没有结果页签」判据（[isWriteStatement]）。
 *
 * 真机动因（20261110）：`INSERT INTO sys_config …` 执行成功、`1 row affected in 60 ms` 只写在
 * 控制台 Output，DBN 不建结果页签；40 拍窗口到期后 `hasConsoleCompletionEvidence` 读 history
 * 文档读不到该回显，成功被归成 `execute.timeout.hint`（面板上只看到原生 Output）。
 *
 * 判据边界：DML/DDL 首关键字为真；查询类与工具语句为假（查询无页签属异常，仍走超时/错误分支）。
 * 纯逻辑用例——不依赖平台控制台。
 */
class PreviewWriteStatementTest {

    @Test
    fun `DML 与 DDL 判为无结果页签`() {
        assertTrue(DataSourceSelector.isWriteStatement("INSERT INTO sys_config (config_key) VALUES ('x')"))
        assertTrue(DataSourceSelector.isWriteStatement("UPDATE t_order SET amount = amount * 1 WHERE status = 1"))
        assertTrue(DataSourceSelector.isWriteStatement("DELETE FROM sys_config WHERE id = 4"))
        assertTrue(DataSourceSelector.isWriteStatement("MERGE INTO t_user t USING (SELECT 1) s ON (1=1) WHEN MATCHED THEN UPDATE SET a=1"))
        assertTrue(DataSourceSelector.isWriteStatement("CREATE TABLE t_probe (id INT)"))
        assertTrue(DataSourceSelector.isWriteStatement("ALTER TABLE t_probe ADD COLUMN c INT"))
        assertTrue(DataSourceSelector.isWriteStatement("DROP TABLE t_probe"))
        assertTrue(DataSourceSelector.isWriteStatement("TRUNCATE TABLE t_probe"))
    }

    @Test
    fun `小写与前导注释不影响判定`() {
        assertTrue(DataSourceSelector.isWriteStatement("insert into t_order (id) values (1)"))
        assertTrue(DataSourceSelector.isWriteStatement("-- 清理探针数据\nDELETE FROM t_order WHERE id = 99"))
        assertTrue(DataSourceSelector.isWriteStatement("/* hint */\n  UPDATE t_order SET status = 0"))
    }

    @Test
    fun `查询类与工具语句不得判为写语句`() {
        assertFalse(DataSourceSelector.isWriteStatement("SELECT id FROM t_order"))
        assertFalse(DataSourceSelector.isWriteStatement("WITH x AS (SELECT 1 AS n) SELECT n FROM x"))
        assertFalse(DataSourceSelector.isWriteStatement("SHOW TABLES"))
        assertFalse(DataSourceSelector.isWriteStatement("EXPLAIN SELECT id FROM t_order"))
        assertFalse(DataSourceSelector.isWriteStatement("SET search_path = public"))
        assertFalse(DataSourceSelector.isWriteStatement("USE panweidb"))
    }

    @Test
    fun `空值与不可识别文本判为假`() {
        assertFalse(DataSourceSelector.isWriteStatement(null))
        assertFalse(DataSourceSelector.isWriteStatement(""))
        assertFalse(DataSourceSelector.isWriteStatement("   "))
        assertFalse(DataSourceSelector.isWriteStatement("not a sql at all"))
    }

    @Test
    fun `多语句脚本按首条语句判定`() {
        // 预览执行的整批脚本若首条是 SELECT，DBN 会为它建结果页签 → 不该走写语句通道
        assertFalse(DataSourceSelector.isWriteStatement("SELECT 1;\nUPDATE t_order SET status = 0"))
        assertTrue(DataSourceSelector.isWriteStatement("UPDATE t_order SET status = 0;\nSELECT 1"))
    }
}