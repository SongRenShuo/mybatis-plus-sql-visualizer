package cn.song.mybatisplus.visualizer.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-08 SQL 历史项目维度测试：双项目并发不串流、过滤/清空按项目隔离、旧数据「未归属」读回兼容、
 * 参数快照的项目收窄（见 [SqlHistoryManager.updateNewestParamSnapshot] 的 F-08 口径）。
 *
 * 直接实例化 manager（不依赖平台容器），与 SqlHistoryManagerTest 同形态。
 */
class SqlHistoryProjectDimensionTest {

    private val projA = "identity-a" // 模拟项目 A 的 project key（ExplainPlanKey.projectIdentity 产物）
    private val projB = "identity-b" // 模拟项目 B 的 project key

    /** 带项目身份构造条目（timestamp 递增保证插入序稳定）。 */
    private fun entry(
        sql: String,
        identity: String = projA,
        projectName: String = "",
        favorite: Boolean = false,
        timestamp: Long = System.currentTimeMillis(),
    ) = SqlHistoryEntry(
        sql = sql,
        compactSql = sql,
        timestamp = timestamp,
        tableName = "t",
        favorite = favorite,
        projectIdentity = identity,
        projectName = projectName,
    )

    // ---------- 双项目并发不串流 ----------

    @Test
    fun `双项目并发各自过滤不串流`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "A1", identity = projA, timestamp = 1000L))
        manager.add(entry(sql = "B1", identity = projB, timestamp = 2000L))
        manager.add(entry(sql = "A2", identity = projA, timestamp = 3000L))

        val ofA = manager.filterByProject(projA)
        val ofB = manager.filterByProject(projB)
        assertEquals(listOf("A2", "A1"), ofA.map { it.sql })
        assertEquals(listOf("B1"), ofB.map { it.sql })
        // A 的条目绝不进 B 的档，反之亦然
        assertTrue(ofA.none { it.projectIdentity == projB })
        assertTrue(ofB.none { it.projectIdentity == projA })
    }

    @Test
    fun `收藏条目同样按项目隔离`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "FA", identity = projA, favorite = true, timestamp = 1000L))
        manager.add(entry(sql = "FB", identity = projB, favorite = true, timestamp = 2000L))
        assertEquals(listOf("FA"), manager.filterByProject(projA).map { it.sql })
        assertEquals(listOf("FB"), manager.filterByProject(projB).map { it.sql })
    }

    // ---------- 未归属档（旧数据兼容） ----------

    @Test
    fun `旧数据无项目身份归未归属档且不混入已归属项目`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "OLD1", identity = "", timestamp = 1000L))
        manager.add(entry(sql = "A1", identity = projA, timestamp = 2000L))

        val unassigned = manager.filterByProject("")
        assertEquals(listOf("OLD1"), unassigned.map { it.sql })
        assertTrue(unassigned.all { it.projectIdentity.isBlank() })
        // 未归属条目不出现在任何项目的过滤结果里（不猜测归属）
        assertTrue(manager.filterByProject(projA).none { it.sql == "OLD1" })
    }

    @Test
    fun `loadState 旧格式条目读回不炸且入未归属档`() {
        val manager = SqlHistoryManager()
        // 模拟旧版持久化状态：条目只带旧字段（projectName/explainPlans 等新字段缺省）
        val legacy = SqlHistoryEntry(sql = "LEGACY", compactSql = "LEGACY", timestamp = 1L).apply { ensureId() }
        manager.loadState(SqlHistoryManager.State(entries = mutableListOf(legacy)))
        assertEquals(1, manager.entries.size)
        assertEquals("", manager.entries.first().projectIdentity)
        assertEquals("LEGACY", manager.filterByProject("").single().sql)
        assertEquals(1, manager.projectNames().size) // 空 identity 一个档
    }

    // ---------- 清空按项目隔离 ----------

    @Test
    fun `clearProject 只清该项目两个分区且不动其他项目`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "A1", identity = projA, timestamp = 1000L))
        manager.add(entry(sql = "FA", identity = projA, favorite = true, timestamp = 2000L))
        manager.add(entry(sql = "B1", identity = projB, timestamp = 3000L))
        manager.add(entry(sql = "OLD", identity = "", timestamp = 4000L))

        val removed = manager.clearProject(projA)
        assertEquals(2, removed)
        assertEquals(0, manager.filterByProject(projA).size)
        assertEquals(listOf("B1"), manager.filterByProject(projB).map { it.sql })
        assertEquals(listOf("OLD"), manager.filterByProject("").map { it.sql })
        assertEquals(2, manager.entries.size)
    }

    @Test
    fun `clearProject 空白键只清未归属档`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "OLD1", identity = "", timestamp = 1000L))
        manager.add(entry(sql = "OLD2", identity = "", timestamp = 2000L))
        manager.add(entry(sql = "A1", identity = projA, timestamp = 3000L))

        val removed = manager.clearProject("")
        assertEquals(2, removed)
        assertEquals(0, manager.filterByProject("").size)
        assertEquals(listOf("A1"), manager.filterByProject(projA).map { it.sql })
    }

    // ---------- 项目清单 ----------

    @Test
    fun `projectNames 同项目多条目取最新名字`() {
        val manager = SqlHistoryManager()
        manager.add(entry(sql = "A1", identity = projA, projectName = "旧名", timestamp = 1000L))
        manager.add(entry(sql = "B1", identity = projB, projectName = "proj-b", timestamp = 2000L))
        manager.add(entry(sql = "A2", identity = projA, projectName = "新名", timestamp = 3000L))

        val names = manager.projectNames()
        assertEquals(2, names.size)
        assertEquals("新名", names[projA]) // entries 时间倒序，先写者胜 = 最新一条
        assertEquals("proj-b", names[projB])
    }

    // ---------- 参数快照的项目收窄 ----------

    @Test
    fun `参数快照按项目收窄_双项目同表名不串快照`() {
        val manager = SqlHistoryManager()
        val matchByTable: (SqlHistoryEntry) -> Boolean = { it.tableName == "t" }
        manager.add(entry(sql = "SELECT * FROM t WHERE id = 1", identity = projA, timestamp = 1000L))
        manager.add(entry(sql = "SELECT * FROM t WHERE id = 2", identity = projB, timestamp = 2000L))

        // 项目 A 写入快照
        assertTrue(manager.updateNewestParamSnapshotForProject(projA, matchByTable, mapOf("id" to "1")))
        // 项目 B 读取：不得拿到 A 的快照（B 的条目无快照 → null）
        assertNull(manager.newestParamSnapshotForProject(projB, matchByTable))
        // 项目 A 读取：拿回自己的
        assertEquals(mapOf("id" to "1"), manager.newestParamSnapshotForProject(projA, matchByTable))
    }

    @Test
    fun `参数快照_旧条目空白身份对任意项目保持可回落`() {
        val manager = SqlHistoryManager()
        val matchByTable: (SqlHistoryEntry) -> Boolean = { it.tableName == "t" }
        manager.add(entry(sql = "SELECT * FROM t", identity = "", timestamp = 1000L))
        assertTrue(manager.updateNewestParamSnapshotForProject(projA, matchByTable, mapOf("id" to "9")))
        // 空白身份条目（旧数据）不因新维度被排除——既有回落行为不变
        assertEquals(mapOf("id" to "9"), manager.newestParamSnapshotForProject(projA, matchByTable))
        assertEquals(mapOf("id" to "9"), manager.newestParamSnapshotForProject(projB, matchByTable))
    }

    @Test
    fun `参数快照_不过滤参数保持旧调用语义`() {
        val manager = SqlHistoryManager()
        val matchByTable: (SqlHistoryEntry) -> Boolean = { it.tableName == "t" }
        manager.add(entry(sql = "SELECT * FROM t WHERE id = 1", identity = projA, timestamp = 1000L))
        manager.add(entry(sql = "SELECT * FROM t WHERE id = 2", identity = projB, timestamp = 2000L))
        assertTrue(manager.updateNewestParamSnapshot(matchByTable, mapOf("id" to "2")))
        // 不过滤 = 既有行为：最新一条（B）被更新，A 的条目不残留该快照
        assertEquals(mapOf("id" to "2"), manager.newestParamSnapshot(matchByTable))
        assertFalse(manager.entries.any { it.projectIdentity == projA && it.paramSnapshot.isNotEmpty() })
    }
}
