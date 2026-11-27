package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.H2Dialect
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * L24 根治方案的回归守卫：同一实例上并发两轮方言套件**互不删表**。
 *
 * ## 钉的是什么
 *
 * L24（`docs/LESSONS.md`）记录的真实故障：方言套件用**固定表名**
 * （`dev_info` / `mpv_batch_probe` / `kw_probe_<词下标>`），`createWithRetry` 开跑先
 * `DROP TABLE`、finale 再 `DROP`——两轮并发打同一共享实例（本地 Docker 外指腿 /
 * `dialectExternalVerify` / CI 夜间方言腿 / DBX 临时探测）即互相删掉对方的表，读数红且难归因。
 *
 * 根治方案落在生产代码 [DialectVerifySupport] 的 `runSuffix()`：表名带
 * `pid_base36_启动时刻_base36_轮次` 唯一后缀（`suffixed()`），长度控制 ≤30 字符以兼容
 * Oracle 12c 前的标识符上限。本测试就是钉住这个根治不被回退。
 *
 * ## 为什么这样构造
 *
 * `CyclicBarrier(2)` 让两个 worker 同时起跑，worker 1 刻意晚 5ms——这正是「固定表名时
 * 后开跑者的 DROP/CREATE 落在先开跑者建表之后」的真实交错形态（见 L24 证据段）。
 * 跑 10 轮而非 1 轮：交错时序靠调度，单轮可能侥幸不撞。
 *
 * ## 反向验证（改动本测试前先读）
 *
 * 把 `DialectVerifySupport.runSuffix()` 临时改成返回空串（`suffixed` 会退回原名，
 * 即旧的固定表名形态），本测试**必红**——这是它唯一的存在理由；若改完仍绿，说明
 * 断言已失效（例如错误被吞、或 H2 内存库按 URL 隔离把两轮分到了不同库）。
 *
 * 当前基线：1 例绿，约 1.1s（H2 内存库 + 10 轮 × 2 worker）。
 */
class DialectTableIsolationConcurrentTest {

    private val connections = Collections.synchronizedList(mutableListOf<Connection>())

    @After
    fun closeAll() {
        connections.forEach { runCatching { it.close() } }
    }

    @Test(timeout = 180_000)
    fun `同一H2实例并发两轮互不删表`() {
        val url = "jdbc:h2:mem:l24probe_${System.nanoTime()};DB_CLOSE_DELAY=-1"
        val spec = DialectVerifySupport.EngineSpec(
            "H2-L24Probe",
            "jdbc:h2:mem:{db};DB_CLOSE_DELAY=-1",
            H2Dialect,
            DialectVerifySupport.UpsertMode.REAL,
        )
        val errors = Collections.synchronizedList(mutableListOf<String>())
        repeat(10) { round ->
            val barrier = CyclicBarrier(2)
            val threads = (0 until 2).map { worker ->
                thread(name = "l24-probe-$round-$worker") {
                    try {
                        val conn = DriverManager.getConnection(url, "sa", "")
                        connections += conn
                        barrier.await()
                        // worker 1 略晚起步：让它的开跑 DROP/CREATE 正好落在 worker 0 建表之后
                        // （固定表名时这就是「互删」的真实交错形态）
                        if (worker == 1) Thread.sleep(5)
                        DialectVerifySupport.runTcSuite(conn, spec)
                    } catch (e: Throwable) {
                        errors += "round=$round worker=$worker -> ${e::class.java.simpleName}: " +
                            (e.message?.lineSequence()?.firstOrNull() ?: "")
                    }
                }
            }
            threads.forEach { it.join() }
        }
        assertTrue(
            "并发两轮互删 ${errors.size} 例（L24 回退？查 DialectVerifySupport.runSuffix 是否仍产出唯一后缀）:\n" +
                errors.take(10).joinToString("\n"),
            errors.isEmpty(),
        )
    }
}
