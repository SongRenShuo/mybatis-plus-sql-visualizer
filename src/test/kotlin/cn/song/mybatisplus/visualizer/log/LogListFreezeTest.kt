package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * T-LogListFreeze 单元测试：列表刷新性能优化（EDT 冻结修复）。
 *
 * - T-01/03：批量布局抑制 + 分批 EDT 写入常量存在性
 * - T-04（2026-09-08）：HTML 缓存随片段通道退役，原 T-02 缓存语义用例删除；
 *   增量刷新断言见 [SqlLogStreamingDiffTest]
 */
class LogListFreezeTest : BasePlatformTestCase() {

    fun test首屏批次数大于0且小于总批次数() {
        // REFRESH_FIRST_BATCH_SIZE 和 REFRESH_CHUNK_SIZE 应为合理正数
        assertTrue("首屏批次应 > 0", REFRESH_FIRST_BATCH_SIZE > 0)
        assertTrue("异步批次应 > 0", REFRESH_CHUNK_SIZE > 0)
        assertTrue("首屏批次应 <= 异步批次 × 3（保证首屏足够快）", REFRESH_FIRST_BATCH_SIZE <= REFRESH_CHUNK_SIZE * 3)
    }

    fun test200条数据分两批以上写入() {
        val total = 200
        val firstBatchSize = REFRESH_FIRST_BATCH_SIZE
        val chunkSize = REFRESH_CHUNK_SIZE
        val remaining = total - firstBatchSize
        val chunks = if (remaining <= 0) 0 else (remaining + chunkSize - 1) / chunkSize
        val totalBatches = 1 + chunks

        assertTrue("200 条应分多批写入（>2 批），避免首屏冻结", totalBatches > 2)
        assertTrue("首屏批次应 < 总数（有异步追加）", firstBatchSize < total)
    }
}
