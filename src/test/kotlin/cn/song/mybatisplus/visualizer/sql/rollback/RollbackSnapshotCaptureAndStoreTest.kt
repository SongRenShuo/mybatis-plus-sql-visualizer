package cn.song.mybatisplus.visualizer.sql.rollback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * T-RollbackSnapshotPreExec P1 纯逻辑测试（testFast 快桶）：
 * [RollbackSnapshotCapture] 形态判定矩阵与 [RollbackSnapshotFileStore] 落盘/索引/剪枝。
 * 期望样例与计划 §3.3 降级矩阵逐行对应——每行一条「判不出 → 下一步去哪」的钉子。
 */
class RollbackSnapshotCaptureAndStoreTest {

    // ---------------------------------------------------------------
    // Capture.analyze 形态判定矩阵
    // ---------------------------------------------------------------

    @Test
    fun updateWithWhereIsCapturable() {
        val plan = RollbackSnapshotCapture.analyze("UPDATE t_order SET status = 'paid' WHERE id = 5")
        val capturable = plan as RollbackSnapshotCapture.Plan.Capturable
        assertEquals("UPDATE", capturable.statementKind)
        assertTrue("目标表应指向 t_order", capturable.targetTable.endsWith("t_order"))
        assertTrue("快照 SELECT 应复用原 WHERE", capturable.selectSql.contains("id = 5"))
    }

    @Test
    fun deleteWithWhereIsCapturable() {
        val plan = RollbackSnapshotCapture.analyze("DELETE FROM t_order WHERE id = 5")
        assertTrue(plan is RollbackSnapshotCapture.Plan.Capturable)
        assertEquals("DELETE", (plan as RollbackSnapshotCapture.Plan.Capturable).statementKind)
    }

    @Test
    fun insertAndSelectNotCapturable() {
        assertTrue(RollbackSnapshotCapture.analyze("INSERT INTO t (id) VALUES (1)") is RollbackSnapshotCapture.Plan.NotCapturable)
        assertTrue(RollbackSnapshotCapture.analyze("SELECT * FROM t WHERE id = 1") is RollbackSnapshotCapture.Plan.NotCapturable)
    }

    @Test
    fun unfilledParameterMarkerRejected() {
        val plan = RollbackSnapshotCapture.analyze("UPDATE t SET a = 1 WHERE id = ?")
        assertTrue(plan is RollbackSnapshotCapture.Plan.NotCapturable)
        assertTrue((plan as RollbackSnapshotCapture.Plan.NotCapturable).reason.contains("unfilled"))
    }

    @Test
    fun mockPlaceholderMarkerRejected() {
        // {{…}} 模拟占位不是注释、剥注释剥不掉（T-CopyPlaceholderMarker），带占位的文本不可采集
        val plan = RollbackSnapshotCapture.analyze("UPDATE t SET a = {{v}} WHERE id = 1")
        assertTrue(plan is RollbackSnapshotCapture.Plan.NotCapturable)
    }

    @Test
    fun parameterMarkerInsideStringLiteralIsNotRejected() {
        val plan = RollbackSnapshotCapture.analyze("UPDATE t SET note = 'id = ?' WHERE id = 1")
        assertTrue("字符串字面量里的 ? 不应触发拒绝", plan is RollbackSnapshotCapture.Plan.Capturable)
    }

    @Test
    fun noWhereFullTableStillCapturable() {
        // 全表写也采集（计划 §3.3：采集 + 全表标记交给行上限截断）
        val plan = RollbackSnapshotCapture.analyze("DELETE FROM t_order")
        assertTrue(plan is RollbackSnapshotCapture.Plan.Capturable)
    }

    @Test
    fun updateWithJoinFailsToParseSingleTable() {
        // MySQL 多表 UPDATE（JSqlParser 解析出的目标形态不同）——不可采集即可，原因如实
        val plan = RollbackSnapshotCapture.analyze("UPDATE t1 JOIN t2 ON t1.id = t2.tid SET t1.a = 1 WHERE t2.b = 2")
        if (plan is RollbackSnapshotCapture.Plan.Capturable) {
            // 若解析器支持多表并产出了 targetTable，至少不该把 t2 的行漏进快照——
            // 快照 SELECT 必须只指向一张表；当前实现选择不可采集，此分支仅为防御未来改动
            assertFalse(plan.selectSql.uppercase().contains("JOIN"))
        }
    }

    // ---------------------------------------------------------------
    // FileStore：落盘 / 索引 / 剪枝
    // ---------------------------------------------------------------

    private fun record(id: String, createdAtMs: Long, rows: Int = 2) =
        RollbackSnapshotFileStore.SnapshotRecord(
            id = id,
            createdAtMs = createdAtMs,
            mode = "UPDATE",
            table = "t_order",
            statementSql = "UPDATE t_order SET status = 'paid' WHERE id = $id",
            snapshotSelectSql = "SELECT * FROM t_order WHERE id = $id",
            columns = listOf("id", "status"),
            beforeRows = (1..rows).map { listOf("$it", "paid") },
        )

    @Test
    fun saveLoadRoundTrip() {
        val dir = Files.createTempDirectory("snaptest")
        val id = RollbackSnapshotFileStore.newId(1_700_000_000_000)
        RollbackSnapshotFileStore.save(dir, record(id, 1_700_000_000_000), 1_700_000_000_000)
        val loaded = RollbackSnapshotFileStore.load(dir, id)
        assertTrue(loaded != null)
        assertEquals(id, loaded!!.id)
        assertEquals(listOf("id", "status"), loaded.columns)
        assertEquals(2, loaded.beforeRows.size)
        assertEquals(RollbackSnapshotFileStore.Status.CAPTURED, loaded.status)
    }

    @Test
    fun indexListsNewestFirst() {
        val dir = Files.createTempDirectory("snaptest")
        val oldId = RollbackSnapshotFileStore.newId(1_000)
        val newId = RollbackSnapshotFileStore.newId(2_000)
        RollbackSnapshotFileStore.save(dir, record(oldId, 1_000), 2_000)
        RollbackSnapshotFileStore.save(dir, record(newId, 2_000), 2_000)
        val entries = RollbackSnapshotFileStore.listEntries(dir)
        assertEquals(2, entries.size)
        assertEquals(newId, entries.first().id)
    }

    @Test
    fun updateStatusPersists() {
        val dir = Files.createTempDirectory("snaptest")
        // createdAt 必须用真实当前时间：save() 内联剪枝按「now - 7 天」裁剪，假旧时间戳会在
        // 落盘瞬间被剪掉（生产行为正确，测试要顺着这个语义构造）
        val now = System.currentTimeMillis()
        val id = RollbackSnapshotFileStore.newId(now)
        RollbackSnapshotFileStore.save(dir, record(id, now), now)
        assertTrue(RollbackSnapshotFileStore.updateStatus(dir, id) { it.copy(status = RollbackSnapshotFileStore.Status.ROLLED_BACK) })
        assertEquals(RollbackSnapshotFileStore.Status.ROLLED_BACK, RollbackSnapshotFileStore.load(dir, id)!!.status)
    }

    @Test
    fun pruneKeepsRecentDropsOld() {
        val dir = Files.createTempDirectory("snaptest")
        val now = 10L * 24 * 60 * 60 * 1000 // 10 天
        val oldId = RollbackSnapshotFileStore.newId(0)
        val newId = RollbackSnapshotFileStore.newId(now)
        // save() 内联剪枝：以「落盘时刻 nowMs」为基准。先以 era=0 落盘旧条目（cutoff 为负，
        // 不会被自己的 save 剪掉）；再落盘新条目（nowMs=now），旧条目此刻应被顺带剪掉。
        RollbackSnapshotFileStore.save(dir, record(oldId, 0), 0)
        RollbackSnapshotFileStore.save(dir, record(newId, now), now)
        assertTrue("超期条目应在新条目落盘的顺带剪枝中被删除", RollbackSnapshotFileStore.load(dir, oldId) == null)
        assertTrue("保留期内条目应存活", RollbackSnapshotFileStore.load(dir, newId) != null)
        // 显式 prune 也能独立工作：再造一个超期条目（era 落盘不被剪），手动 prune 到 now 应删 1
        val oldId2 = RollbackSnapshotFileStore.newId(0)
        RollbackSnapshotFileStore.save(dir, record(oldId2, 0), 0)
        val removed = RollbackSnapshotFileStore.prune(dir, now)
        assertEquals(1, removed)
        assertTrue(RollbackSnapshotFileStore.load(dir, oldId2) == null)
    }

    @Test
    fun gitignoreWrittenOnce() {
        val dir: Path = Files.createTempDirectory("snaptest")
        RollbackSnapshotFileStore.ensureGitignore(dir)
        RollbackSnapshotFileStore.ensureGitignore(dir)
        val ignore = dir.resolve(".gitignore")
        assertTrue(Files.exists(ignore))
        val text = Files.readString(ignore)
        assertTrue(text.contains("*"))
    }

    // ---------------------------------------------------------------
    // 反向验证缝：把 Capture.analyze 的关键字判定临时短路（改回 UPDATE/DELETE 双白名单）
    // ----------------------------------------------------------------
    // 反向做法（收口轮执行，禁 git checkout）：
    //   1. 临时把 firstKeyword 判定改为「任何关键字都可采集」→ insertAndSelectNotCapturable 必红；
    //   2. 临时把 hasUnfilledParameterMarker 恒 false → unfilledParameterMarkerRejected 必红；
    //   3. 恢复后本类全绿。
}
