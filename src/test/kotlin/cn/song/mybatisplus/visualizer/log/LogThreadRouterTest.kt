package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogThreadRouter] 纯逻辑测试（T-LogParseConc）：线程 token 提取（三种日志形态 + 干扰段排除）、
 * 按 token 隔离、无线程行归属「等待态桶」优先、桶上限淘汰空闲桶。
 */
class LogThreadRouterTest {

    /** 测试用桶：仅需「是否处于中间态 / 是否空闲」两个标志。 */
    private class Bucket {
        var waiting = false
        var idle = true
    }

    private fun router(max: Int = 64) = LogThreadRouter(
        factory = { Bucket() },
        isCollecting = { it.waiting },
        isIdle = { it.idle },
        maxBuckets = max,
    )

    // ---------- token 提取 ----------

    @Test
    fun `token提取_三种日志形态`() {
        assertEquals(
            "http-nio-8088-exec-4",
            LogThreadRouter.threadTokenOf("2026-09-15 13:47:17.843 [TID: AE51C] [http-nio-8088-exec-4] DEBUG c.Foo - Preparing: x"),
        )
        assertEquals(
            "main",
            LogThreadRouter.threadTokenOf("2026-08-21 10:00:00.124 TRACE [main] o.h.h.i.QueryTranslatorImpl : select"),
        )
        assertEquals(
            "cache-preheat-1",
            LogThreadRouter.threadTokenOf("2026-08-24 15:46:42.444 [TID: null] [cache-preheat-1] TRACE c.Foo - x"),
        )
    }

    @Test
    fun `token提取_干扰段排除与无法提取`() {
        assertNull("无级别关键词锚点 → 无法可靠提取", LogThreadRouter.threadTokenOf("[main] 只有方括号没有级别词"))
        assertEquals(
            "exec-9",
            LogThreadRouter.threadTokenOf("2026-09-16 14:35:00.413 [TID: FED5CD4B] [exec-9] DEBUG x - y"),
        )
        assertNull("纯数字方括号段不当作线程名", LogThreadRouter.threadTokenOf("2026-09-16 10:00:00.000 DEBUG x [12345] y"))
    }

    // ---------- 分桶 ----------

    @Test
    fun `同token同桶_异token异桶`() {
        val r = router()
        val a1 = r.bucketFor("2026-09-16 10:00:00.000 DEBUG [exec-1] x - a")
        val a2 = r.bucketFor("2026-09-16 10:00:00.001 DEBUG [exec-1] x - b")
        val b1 = r.bucketFor("2026-09-16 10:00:00.002 DEBUG [exec-2] x - c")
        assertSame(a1, a2)
        assertNotSame(a1, b1)
        assertEquals(2, r.bucketCount())
    }

    @Test
    fun `无线程行归属等待态桶_否则回退桶`() {
        val r = router()
        val fallback1 = r.bucketFor("纯文本无 token 行")
        val fallback2 = r.bucketFor("又一条纯文本无 token 行")
        assertSame("两条无线程行都落回退桶（同一实例）", fallback1, fallback2)

        val waiting = r.bucketFor("2026-09-16 10:00:00.000 DEBUG [exec-7] x - a")
        waiting.waiting = true
        assertSame("无线程行优先归属处于等待态的桶（多行续行/等待下一行场景）", waiting, r.bucketFor("FROM user"))
    }

    @Test
    fun `桶上限_全忙时不丢状态_有空闲时淘汰空闲桶`() {
        val r = router(max = 2)
        val keep = r.bucketFor("2026-09-16 10:00:00.000 DEBUG [keep] x - a").apply { waiting = true; idle = false }
        r.bucketFor("2026-09-16 10:00:00.001 DEBUG [busy] x - b").idle = false

        val third = r.bucketFor("2026-09-16 10:00:00.002 DEBUG [third] x - c").apply { idle = false }
        assertSame("等待态桶不被淘汰", keep, r.bucketFor("2026-09-16 10:00:00.003 DEBUG [keep] x - d"))
        assertSame("非空闲桶不被淘汰", third, r.bucketFor("2026-09-16 10:00:00.004 DEBUG [third] x - e"))
        assertEquals("全部非空闲时宁超上限也不丢状态（淘汰只打空闲桶）", 3, r.bucketCount())

        val busy = r.bucketFor("2026-09-16 10:00:00.005 DEBUG [busy] x - f")
        busy.idle = true
        r.bucketFor("2026-09-16 10:00:00.006 DEBUG [fourth] x - g")
        assertEquals("有空闲桶时先淘汰再新建，桶数不增长", 3, r.bucketCount())
        assertNotSame("空闲桶确实被淘汰（同 token 再取是新桶）", busy, r.bucketFor("2026-09-16 10:00:00.007 DEBUG [busy] x - h"))
        assertSame("等待态桶始终在册", keep, r.bucketFor("2026-09-16 10:00:00.008 DEBUG [keep] x - i"))
    }

    @Test
    fun `clear清空分桶`() {
        val r = router()
        r.bucketFor("2026-09-16 10:00:00.000 DEBUG [exec-1] x - a")
        r.clear()
        assertEquals(0, r.bucketCount())
    }

    // ---------- T-SAF-22（log.threadrouter.unbounded-nonidle-bucket-retention）：挂起桶老化 ----------

    /**
     * 非空闲但静默超阈值的桶必须可被淘汰（淘汰资格不再只有 isIdle）。
     *
     * 复现证据（NEEDS-VALIDATION #22）：修复前 evictIdle 只打 isIdle 桶，而 isIdle 要求
     * pendingSql == null——每条未闭合语句（有 Preparing 无 Parameters）的桶永久在册，
     * 64 上限形同虚设，10000 个唯一线程 token 把桶数推到 10000（本断言转红，且每桶
     * 至多滞留 20k 字符 pendingSql + 原始行）；修复后非空闲桶静默超阈值即老化淘汰，桶数有界。
     */
    @Test
    fun `非空闲桶静默超阈值后被淘汰`() {
        var now = 1_000_000L
        val r = LogThreadRouter(
            factory = { Bucket() },
            isCollecting = { it.waiting },
            isIdle = { it.idle },
            maxBuckets = 64,
            clock = { now },
        )
        repeat(10_000) { i ->
            now += 61_000L // 每桶此后静默 61s（> 60s 老化阈值）
            r.bucketFor("2026-09-16 10:00:00.000 DEBUG [t-$i] x - a").apply { idle = false }
        }
        assertTrue(
            "桶数须有界（修复前增长到 10000，64 上限被 isIdle 判据废掉）",
            r.bucketCount() <= 64,
        )
    }

    // ---------- 日志行判定 ----------

    @Test
    fun `日志行判定_参数裸文本不算`() {
        assertTrue(looksLikeLogLine("2026-09-16 10:00:00.100 DEBUG [exec-1] x - y", "Preparing:"))
        assertTrue(looksLikeLogLine("==> Parameters: 7(Long)", "Parameters:"))
        assertTrue(looksLikeLogLine("<==      Total: 1", "Preparing:"))
        assertTrue("含语句关键字即日志行", looksLikeLogLine("2026-09-16 10:00:00.100 DEBUG x - Preparing: SELECT 1", "Preparing:"))
        assertEquals(false, looksLikeLogLine("42(Long), abc(String)", "Preparing:", "Parameters:"))
        assertEquals(false, looksLikeLogLine("", "Preparing:"))
    }

    // ---------- F58（T-PerfSweep18 压轴）：token 每行提一次下传 ----------

    /**
     * bucketForToken（预提 token 路由）与 bucketFor（行内自提）逐行等价：
     * 同一 router 上同序喂同一批行，每行先走 bucketFor 记住结果桶、再「重放」预提 token
     * 走 bucketForToken——同 token/同 null 必须命中同一桶实例。
     * 这是「每行提一次下传四解析器」重构的等价性锚——喂行方一次提取经 bucketForToken
     * 下传后，分桶行为与各解析器自提时逐字一致。
     */
    @Test
    fun `F58_bucketForToken与bucketFor逐行等价`() {
        val lines = listOf(
            "2026-09-15 13:47:17.843 [TID: AE51C] [http-nio-8088-exec-4] DEBUG c.Foo - Preparing: x",
            "2026-08-21 10:00:00.124 TRACE [main] o.h.h.i.QueryTranslatorImpl : select",
            "business log without any marker",
            "2026-09-16 10:00:00.000 DEBUG x [12345] y",
        )
        val r = router()
        for (line in lines) {
            val viaLine = r.bucketFor(line)
            val token = LogThreadRouter.threadTokenOf(line)
            assertSame(
                "预提 token 路由须与行内自提同桶：$line",
                viaLine,
                r.bucketForToken(token),
            )
        }
        // token=null 的 fail-open 回退：稳定落共享桶
        assertSame(r.bucketForToken(null), r.bucketForToken(null))
    }
}
