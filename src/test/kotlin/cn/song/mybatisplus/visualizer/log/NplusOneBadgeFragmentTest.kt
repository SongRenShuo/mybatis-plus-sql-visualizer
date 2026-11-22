package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-HdAbsorb D216：N+1 嫌疑徽章渲染面（平台 fixture，bundle 加载齐）。
 *
 * 覆盖：阈值门（≥5 才显示）、与「同模板计数」徽章并存分层、Registry 回退通道关闭即不显示、
 * 双语键在册（BundleParityGuardTest 另有双份守卫）。
 *
 * 反向验证锚点：[test未达阈值_不显示嫌疑徽章]——摘掉 formatNplusOneSuspectFragment 的阈值
 * 判定（或 SqlEntryPresentation 的接线）时，接线扫描用例与本类达阈值用例转红。
 */
class NplusOneBadgeFragmentTest : BasePlatformTestCase() {

    private fun entry() = LogSqlEntry(
        rawSql = "SELECT * FROM t_user WHERE id = ?",
        restoredSql = "SELECT * FROM t_user WHERE id = ?",
        params = emptyList(),
        source = SqlSource.MYBATIS,
    )

    private fun countsFor(entry: LogSqlEntry, n: Int): Map<String, Int> =
        structureKeyCounts((1..n).map { entry.copy(actualSql = "run_$it ::: SELECT ? ::: [$it]") })

    fun test达阈值_显示嫌疑徽章且与计数徽章并存() {
        val entry = entry()
        val counts = countsFor(entry, NplusOneSuspect.DEFAULT_MIN_COUNT)
        val suspect = formatNplusOneSuspectFragment(entry, counts, false)
        assertNotNull(suspect)
        assertTrue("文案应含「疑似 N+1」（双语键 log.panel.nplusOneBadge）：${suspect!!.first}",
            suspect.first.contains("N+1"))
        val template = formatTemplateCountFragment(entry, counts, false)
        assertNotNull("计数徽章仍应并存（分层语义）", template)
    }

    fun test未达阈值_不显示嫌疑徽章() {
        val entry = entry()
        val counts = countsFor(entry, NplusOneSuspect.DEFAULT_MIN_COUNT - 1)
        assertNull(formatNplusOneSuspectFragment(entry, counts, false))
        // 无计数 map（空面板快照）同样不显示
        assertNull(formatNplusOneSuspectFragment(entry, emptyMap(), false))
    }

    fun test回退通道_开关关_徽章消失() {
        val entry = entry()
        val counts = countsFor(entry, NplusOneSuspect.DEFAULT_MIN_COUNT + 3)
        val old = NplusOneSuspect.enabledProvider
        NplusOneSuspect.enabledProvider = { false }
        try {
            assertNull(formatNplusOneSuspectFragment(entry, counts, false))
        } finally {
            NplusOneSuspect.enabledProvider = old
        }
    }

    fun test接线守卫_三个渲染出口都接了嫌疑徽章() {
        // 源码扫描（PreviewPreparePickTest 范式）：headerBadgeFragments / details / metadata
        // 三出口若有人摘掉接线，本守卫红
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists() &&
            !java.io.File(dir, "settings.gradle").exists()
        ) {
            dir = dir.parentFile
        }
        val src = java.io.File(dir, "src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlEntryPresentation.kt").readText()
        val occurrences = src.split("formatNplusOneSuspectFragment(").size - 1
        assertTrue("SqlEntryPresentation 至少接线 3 处嫌疑徽章（现值 $occurrences）", occurrences >= 3)
    }
}
