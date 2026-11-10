package cn.song.mybatisplus.visualizer.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * T-HiddenBugPerfScan 券 G：SqlHistoryManager 锁口径并发回归。
 *
 * 写路径（add/remove/clear/loadState/saveExplainPlan）此前已有 @Synchronized，
 * 但 entries/findById/toggleFavorite/togglePin/addTag/removeTag/search/
 * updateNewestParamSnapshot/newestParamSnapshot 等 13 个读/改入口裸奔——
 * add 在池化线程（previewAsync 落库）、参数快照回写在 EDT（防抖 Timer），
 * mergeByTimestamp/firstOrNull 裸遍历同一 MutableList 曾可 CME/撕裂。
 * 修复后全入口同锁；本测试用高强度交错压力钉住（摘掉 @Synchronized 探针大概率红）。
 */
class SqlHistoryManagerConcurrencyTest {

    private fun entry(sql: String, ts: Long) = SqlHistoryEntry(
        sql = sql,
        compactSql = sql,
        timestamp = ts,
        sourceFile = "UserMapper.java",
        sourceLine = 10,
        tableName = "user",
    )

    @Test
    fun `池线程add与EDT读改并发_无CME无撕裂`() {
        val manager = SqlHistoryManager()
        manager.maxSize = 100_000
        repeat(3000) { manager.add(entry("SELECT * FROM user WHERE id = $it", it.toLong())) }

        val error = AtomicReference<Throwable?>()
        val writerReady = CountDownLatch(1)
        val readerReady = CountDownLatch(1)
        val writer = Thread {
            try {
                writerReady.countDown()
                readerReady.await()
                // 固定 10 条反复 toggle（收藏区不触 MAX_FAVORITES 淘汰，总量不变量成立）
                val toggled = (0 until 10).map { manager.entries[it].id }
                repeat(2000) { i ->
                    manager.add(entry("INSERT INTO log VALUES ($i)", 10_000L + i))
                    manager.toggleFavorite(toggled[i % toggled.size])
                }
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            }
        }
        writer.start()
        writerReady.await()
        readerReady.countDown()
        // 读者并发轮转全部裸奔过的入口
        var reads = 0
        while (writer.isAlive && error.get() == null) {
            manager.entries
            manager.findById("no-such-id")
            manager.search("SELECT")
            manager.allTags()
            manager.filterFavorites()
            manager.updateNewestParamSnapshot({ it.tableName == "user" }, mapOf("p" to "1"))
            manager.newestParamSnapshot { it.tableName == "user" }
            manager.filterByTag("t")
            reads++
        }
        writer.join(10_000)
        assertEquals("写线程应无异常跑完", null, error.get())
        assertEquals("总量=3000+2000", 5000, manager.entries.size)
        assertTrue("读者应轮转过若干轮（压力有效性）", reads > 10)
    }
}
