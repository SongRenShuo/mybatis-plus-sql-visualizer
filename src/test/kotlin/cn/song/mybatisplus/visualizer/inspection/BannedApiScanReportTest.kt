package cn.song.mybatisplus.visualizer.inspection

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存量扫描报告组装单元测试（T-BannedApiCheck S4，纯逻辑）。
 *
 * 覆盖：总数/扫描数行、按规则聚合、明细行、明细上限截断标注、取消标注。
 */
class BannedApiScanReportTest {

    private fun hit(
        classFqn: String = "com.acme.LegacyDao",
        methodName: String = "save",
        level: String = BannedApiRule.LEVEL_WARNING,
        description: String = "改用 NewDao",
        filePath: String = "src/main/java/com/acme/Client.java",
        line: Int = 12,
    ) = BannedApiScanHit(classFqn, methodName, level, description, filePath, line)

    @Test
    fun `报告包含项目_扫描数_总数与明细行`() {
        val report = BannedApiScanReport.build(
            projectName = "demo",
            scannedFiles = 3,
            totalHits = 2,
            hits = listOf(hit(line = 12), hit(line = 30)),
            canceled = false,
        )
        assertTrue("标题", report.contains(MpSqlVisualizerBundle.message("scan.bannedApi.report.title")))
        assertTrue("项目名", report.contains(MpSqlVisualizerBundle.message("scan.bannedApi.report.project", "demo")))
        assertTrue("扫描文件数", report.contains(MpSqlVisualizerBundle.message("scan.bannedApi.report.scanned", "3")))
        assertTrue("命中总数", report.contains(MpSqlVisualizerBundle.message("scan.bannedApi.report.total", "2")))
        assertTrue(
            "明细行含路径:行号",
            report.contains(
                MpSqlVisualizerBundle.message(
                    "scan.bannedApi.report.hitEntry",
                    "src/main/java/com/acme/Client.java",
                    "12",
                    "com.acme.LegacyDao#save",
                    "改用 NewDao",
                ),
            ),
        )
        assertTrue("未截断时不出现截断标注", !report.contains(MpSqlVisualizerBundle.message("scan.bannedApi.report.truncated", "500")))
    }

    @Test
    fun `按规则聚合_同目标计数_不同级别分行`() {
        val hits = listOf(
            hit(line = 1),
            hit(line = 2),
            hit(line = 3, level = BannedApiRule.LEVEL_ERROR),
            hit(classFqn = "com.acme.OldDao", methodName = "find"),
        )
        val groups = BannedApiScanReport.groupedCounts(hits)
        assertEquals("同目标同级别聚成一组（error 单独一组）", 3, groups.size)
        assertEquals("warning 组计数", 2, groups.first { it.level == BannedApiRule.LEVEL_WARNING }.count)
        assertEquals("error 组计数", 1, groups.first { it.level == BannedApiRule.LEVEL_ERROR }.count)
        assertEquals("不同目标独立成组", "com.acme.OldDao#find", groups.last().target)
    }

    @Test
    fun `明细超上限_报告截断且标注`() {
        val total = BannedApiScanReport.MAX_DETAIL_ENTRIES + 7
        val hits = (1..total).map { hit(line = it) }
        val report = BannedApiScanReport.build(
            projectName = "big",
            scannedFiles = 900,
            totalHits = total,
            hits = hits,
            canceled = false,
        )
        assertTrue("总命中数按真实计数", report.contains(MpSqlVisualizerBundle.message("scan.bannedApi.report.total", total.toString())))
        assertTrue(
            "含截断标注",
            report.contains(MpSqlVisualizerBundle.message("scan.bannedApi.report.truncated", BannedApiScanReport.MAX_DETAIL_ENTRIES.toString())),
        )
        // 明细段只保留上限条：最后一条（行号 = total）不应出现
        assertTrue(
            "超出上限的明细行不得进入报告",
            !report.contains(
                MpSqlVisualizerBundle.message(
                    "scan.bannedApi.report.hitEntry",
                    "src/main/java/com/acme/Client.java",
                    total.toString(),
                    "com.acme.LegacyDao#save",
                    "改用 NewDao",
                ),
            ),
        )
    }

    @Test
    fun `取消扫描_报告首部标注结果不完整`() {
        val report = BannedApiScanReport.build(
            projectName = "demo",
            scannedFiles = 1,
            totalHits = 0,
            hits = emptyList(),
            canceled = true,
        )
        assertTrue(
            "取消标注",
            report.contains(MpSqlVisualizerBundle.message("scan.bannedApi.report.canceled")),
        )
    }
}
