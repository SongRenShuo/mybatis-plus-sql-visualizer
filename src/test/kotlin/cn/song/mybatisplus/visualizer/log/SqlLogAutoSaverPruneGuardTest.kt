package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T-PS4-10：历史裁剪安全门（本插件名族过滤）+ 裁剪节流 单元测试（纯 JUnit，无平台依赖）。
 *
 * 核心回归守卫（第七轮审计数据安全发现）：自定义保存目录混入用户自有 .sql 时，裁剪只删
 * 本插件渲染名族文件，用户文件零删除；节流窗口内重复写批不再重复全目录扫描。
 */
class SqlLogAutoSaverPruneGuardTest {

    private fun tempDir(): String {
        val dir = Files.createTempDirectory("ps410-test-").toFile()
        dir.deleteOnExit()
        return dir.absolutePath
    }

    private fun makeFile(dir: String, name: String, stamp: Long) {
        val f = File(dir, name)
        f.parentFile.mkdirs()
        f.writeText("-- source: x --\nSELECT 1\n\n")
        f.setLastModified(stamp)
    }

    private fun namesOf(dir: String): List<String> =
        File(dir).listFiles()!!.map { it.name }.sorted()

    // ------------------------------------------------------------------
    // 安全门：名族过滤（宁漏删不误删）
    // ------------------------------------------------------------------

    /** 核心回归守卫：自定义目录混入用户自有 .sql——裁剪只删插件名族最旧组，用户文件零删除。 */
    @Test
    fun `安全门_用户自有sql零删除_只删插件名族最旧组`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null, maxFiles = 2)
        // 插件名族 3 组（超上限 2，09-10 最旧）+ 用户自有 4 个（时间戳比插件文件更老也不许删）
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        makeFile(dir, "sql-history-2026-09-15.sql", 2000)
        makeFile(dir, "sql-history-2026-09-16.sql", 3000)
        makeFile(dir, "init.sql", 100)
        makeFile(dir, "readme.sql", 200)
        makeFile(dir, "migration-v1.sql", 300)
        makeFile(dir, "backup-old.sql", 400)
        saver.pruneRetainedFiles()
        val remain = namesOf(dir)
        assertEquals("只删名族最旧组 09-10，其余 6 个（含 4 个用户文件）全保留",
            listOf("backup-old.sql", "init.sql", "migration-v1.sql", "readme.sql",
                "sql-history-2026-09-15.sql", "sql-history-2026-09-16.sql"),
            remain)
        saver.close()
    }

    /** 自定义模板按其渲染形状认领（字面段锚定 + 占位段通配）；形似但字面前缀对不上的文件属族外，零删除。 */
    @Test
    fun `安全门_自定义模板按渲染名族匹配_族外零删除`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "applog-\$yyyy-MM-dd\$.sql", null, maxFiles = 1)
        makeFile(dir, "applog-2026-09-10.sql", 1000)
        makeFile(dir, "applog-2026-09-15.sql", 2000)
        // 族外：其他插件形状（applog- 前缀对不上）与用户文件
        makeFile(dir, "sql-history-2026-09-01.sql", 300)
        makeFile(dir, "mydata.sql", 400)
        saver.pruneRetainedFiles()
        assertEquals("只删 applog 族最旧组，族外全保留",
            listOf("applog-2026-09-15.sql", "mydata.sql", "sql-history-2026-09-01.sql"),
            namesOf(dir))
        saver.close()
    }

    /** 固定无占位符模板：族 = 该固定名及其滚动块（基名同为一组），族外文件不计数零删除。 */
    @Test
    fun `安全门_固定模板认领固定名与滚动块_族外零删除`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "daily.sql", null, maxFiles = 2)
        makeFile(dir, "daily.sql", 1000)
        makeFile(dir, "daily.2.sql", 1100) // daily 的滚动块，与 daily.sql 同组
        makeFile(dir, "other.sql", 2000)
        makeFile(dir, "keep.sql", 300)
        saver.pruneRetainedFiles()
        // 族内仅 1 组（滚动块归并）≤ 上限 2 → 全保留；族外（other/keep）永不参与计数与删除
        assertEquals(4, namesOf(dir).size)
        saver.close()
    }

    /** 全通配模板（剥扩展名后无字面段可锚定）：宁漏删不误删，放弃裁剪零删除。 */
    @Test
    fun `安全门_全通配模板放弃裁剪零删除`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "\$yyyy\$.sql", null, maxFiles = 1)
        makeFile(dir, "2026.sql", 1000)
        makeFile(dir, "2025.sql", 2000)
        makeFile(dir, "user-data.sql", 300)
        saver.pruneRetainedFiles()
        assertEquals("无字面段锚定不认领任何文件", 3, namesOf(dir).size)
        saver.close()
    }

    /** 滚动块归并口径在名族过滤后仍生效：同基名滚动块计 1 组、整组删。 */
    @Test
    fun `安全门_滚动块归并计一组且整组删除`() {
        val saver = SqlLogAutoSaver()
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null, maxFiles = 2)
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        makeFile(dir, "sql-history-2026-09-10.2.sql", 1300)
        makeFile(dir, "sql-history-2026-09-15.sql", 2000)
        makeFile(dir, "sql-history-2026-09-16.sql", 3000)
        makeFile(dir, "init.sql", 100)
        saver.pruneRetainedFiles()
        assertEquals("09-10 两块同组整删；init.sql 零删除",
            listOf("init.sql", "sql-history-2026-09-15.sql", "sql-history-2026-09-16.sql"),
            namesOf(dir))
        saver.close()
    }

    // ------------------------------------------------------------------
    // 节流：60s 窗口内重复裁剪跳过（不重复全目录扫描），窗口过后恢复
    // ------------------------------------------------------------------

    @Test
    fun `节流_窗口内跳过裁剪_窗口过后恢复`() {
        var now = 1_000_000L
        val saver = SqlLogAutoSaver(clock = { now }, pruneMinIntervalMs = 60_000L)
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null, maxFiles = 1)
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        makeFile(dir, "sql-history-2026-09-15.sql", 2000)
        saver.pruneRetainedFiles() // 首扫：2 组 > 1，删最旧
        assertFalse("首扫应删最旧组", File(dir, "sql-history-2026-09-10.sql").exists())
        // 窗口内再造超限：裁剪必须被节流跳过（超限文件不删——证明目录未被再次扫描）
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        now += 30_000
        saver.pruneRetainedFiles()
        assertTrue("节流窗口内应跳过裁剪", File(dir, "sql-history-2026-09-10.sql").exists())
        // 窗口过后恢复裁剪
        now += 31_000
        saver.pruneRetainedFiles()
        assertFalse("窗口过后应恢复裁剪", File(dir, "sql-history-2026-09-10.sql").exists())
        saver.close()
    }

    /** 节流常量默认 60s（避免常量被无声改动）。 */
    @Test
    fun `节流_默认间隔为60秒`() {
        assertEquals(60_000L, SqlLogAutoSaver.DEFAULT_PRUNE_MIN_INTERVAL_MS)
        assertEquals(60_000L, SqlLogAutoSaver().pruneMinIntervalMs)
    }

    // ------------------------------------------------------------------
    // 写线程链路：写批后裁剪仍由 drainAndWrite 驱动，且受节流约束
    // ------------------------------------------------------------------

    @Test
    fun `写批后裁剪_经submit链路生效且受节流约束`() {
        var now = 1_000_000L
        val saver = SqlLogAutoSaver(clock = { now })
        val dir = tempDir()
        saver.configure(dir, "sql-history-\$yyyy-MM-dd\$.sql", null, maxFiles = 1)
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        makeFile(dir, "sql-history-2026-09-15.sql", 2000)
        saver.submit(entryOf("SELECT 1"))
        awaitBlockContaining(File(dir), "SELECT 1")
        // writeBatch 先落盘、随后同任务（同监视器串行）裁剪：首裁删最旧名族组
        // T-CiMarketSplit（2026-11-05）：原为 Thread.sleep(200) + assertFalse，在 CI 上假红过
        // （run#14 test(4) 在 176 行失败）——裁剪在 saver 写线程里，固定睡眠在 2 核 + 十三 job
        // 并行时不够。改成有界轮询：真没删照样红，只是不再把机器慢判成缺陷。
        awaitFileGone(File(dir, "sql-history-2026-09-10.sql"))
        // 窗口内第二批写批：裁剪被节流跳过
        makeFile(dir, "sql-history-2026-09-10.sql", 1000)
        saver.submit(entryOf("SELECT 2"))
        awaitBlockContaining(File(dir), "SELECT 2")
        Thread.sleep(200)
        assertTrue("节流窗口内写批不应再触发裁剪", File(dir, "sql-history-2026-09-10.sql").exists())
        saver.close()
    }

    /** 轮询等待目录内任一 .sql 出现含指定内容的条目块（异步写线程完成信号）。 */
    private fun awaitBlockContaining(dir: File, marker: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hit = dir.listFiles { f -> f.isFile && f.extension.equals("sql", true) }
                ?.any { it.readText().contains(marker) } == true
            if (hit) return
            Thread.sleep(20)
        }
        throw AssertionError("等待超时：目录 ${dir.absolutePath} 内未出现含 $marker 的条目")
    }

    /** 轮询等待文件被删（裁剪是异步写线程的后续动作，不能用固定睡眠当同步）。
     *  合并轮全量实测定案（2026-11-13）：5s 预算在 5 分片并行 + 本机满载下会假红（T-CiMarketSplit
     *  同族，定向复跑恒绿）——预算放宽到 30s，不弱化断言（真没删照样红）。 */
    private fun awaitFileGone(file: File, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!file.exists()) return
            Thread.sleep(20)
        }
        assertFalse("等待超时：${file.name} 应在写批后随裁剪被删除（${timeoutMs}ms 内仍在）", file.exists())
    }

    private fun entryOf(sql: String) = LogSqlEntry(
        rawSql = sql,
        restoredSql = sql,
        params = emptyList(),
        timestamp = 1724567890000L,
    )
}
