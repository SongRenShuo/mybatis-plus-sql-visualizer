package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 券03（T-PerfSweep28-03，R28-4）：HibernateNamingConfigProbe 缓存容量上限。
 *
 * 背景：键含项目基路径 + 模块资源目录，多项目长会话缓慢累积且无项目关闭摘除。
 * 修法套 HbmXml 负缓存 / EntityResolver 同范式——超限整体清空再写（值为纯布尔判定，
 * 重算 = 一次资源目录扫描，可接受）。
 *
 * 判据：灌入超过上限的键 → 条目数不超上限且清空后可重填（反向：摘 store 守卫 → 用例红）。
 */
class HibernateNamingConfigProbeCacheBoundTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        HibernateNamingConfigProbe.clearCacheForTest()
    }

    override fun tearDown() {
        try {
            HibernateNamingConfigProbe.clearCacheForTest()
        } finally {
            super.tearDown()
        }
    }

    fun test写入超上限_条目数收敛且可重填() {
        val original = HibernateNamingConfigProbe.maxCacheEntries
        try {
            HibernateNamingConfigProbe.maxCacheEntries = 3
            repeat(10) { i ->
                HibernateNamingConfigProbe.putForTest("proj|scope-$i", i.toLong(), false)
                assertTrue(
                    "条目数不得超过上限（第 ${i + 1} 次写入后 cacheSize=${HibernateNamingConfigProbe.cacheSize()}）",
                    HibernateNamingConfigProbe.cacheSize() <= 3,
                )
            }
            // 清空后可重填：上限触发清空不影响后续写入
            HibernateNamingConfigProbe.putForTest("proj|after-sweep", 99L, true)
            assertTrue("清空后应可重填", HibernateNamingConfigProbe.cacheSize() >= 1)
        } finally {
            HibernateNamingConfigProbe.maxCacheEntries = original
        }
    }

    fun test未超上限_不提前清空() {
        val original = HibernateNamingConfigProbe.maxCacheEntries
        try {
            HibernateNamingConfigProbe.maxCacheEntries = 8
            repeat(5) { i -> HibernateNamingConfigProbe.putForTest("proj|keep-$i", i.toLong(), false) }
            assertEquals("未超上限不应清空（5 条全在）", 5, HibernateNamingConfigProbe.cacheSize())
        } finally {
            HibernateNamingConfigProbe.maxCacheEntries = original
        }
    }
}
