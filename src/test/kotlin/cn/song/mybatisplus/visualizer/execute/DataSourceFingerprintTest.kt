package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-HdAbsorb D218（数据源指纹兜底）：
 * ①纯函数面——[DataSourceFingerprint.compute]（url 必在、user/dbms 空归一、稳定哈希、
 * 空白不敏感）与 [DataSourceFingerprint.matchIn]（唯一命中→下标；歧义/零命中/空记忆→null）；
 * ②**源码扫描接线守卫**（PreviewPreparePickTest 范式）：静默复用链必须接指纹兜底、
 * rememberRunner 必须落指纹、forget 两条链必须连指纹一起清——摘掉接线必红。
 */
class DataSourceFingerprintTest {

    // ------------------------------------------------------------------
    // compute：纯函数
    // ------------------------------------------------------------------

    @Test
    fun `无URL不构成指纹`() {
        assertNull(DataSourceFingerprint.compute(null, "root", "MySQL"))
        assertNull(DataSourceFingerprint.compute("  ", "root", "MySQL"))
    }

    @Test
    fun `同连接事实同指纹`() {
        val a = DataSourceFingerprint.compute("jdbc:mysql://localhost:3306/demo", "root", "MySQL")
        val b = DataSourceFingerprint.compute("jdbc:mysql://localhost:3306/demo", "root", "MySQL")
        assertEquals(a, b)
    }

    @Test
    fun `任一维度不同指纹不同`() {
        val base = DataSourceFingerprint.compute("jdbc:mysql://localhost:3306/demo", "root", "MySQL")
        assertNotEquals(base, DataSourceFingerprint.compute("jdbc:mysql://localhost:3307/demo", "root", "MySQL"))
        assertNotEquals(base, DataSourceFingerprint.compute("jdbc:mysql://localhost:3306/demo", "app", "MySQL"))
        assertNotEquals(base, DataSourceFingerprint.compute("jdbc:mysql://localhost:3306/demo", "root", "MariaDB"))
    }

    @Test
    fun `空白维度归一_改名不失效`() {
        // 显示名不在指纹里——改数据源名字指纹不变（本项存在的意义）
        val a = DataSourceFingerprint.compute("jdbc:mysql://l/db", "root", "MySQL")
        val b = DataSourceFingerprint.compute("jdbc:mysql://l/db", " root ", " MySQL ")
        assertEquals(a, b)
    }

    // ------------------------------------------------------------------
    // matchIn：纯函数
    // ------------------------------------------------------------------

    @Test
    fun `唯一命中返回下标`() {
        assertEquals(1, DataSourceFingerprint.matchIn(listOf("fp-a", "fp-b", "fp-c"), "fp-b"))
    }

    @Test
    fun `多条同指纹歧义弃`() {
        assertNull(DataSourceFingerprint.matchIn(listOf("fp-a", "fp-b", "fp-b"), "fp-b"))
    }

    @Test
    fun `零命中与空记忆`() {
        assertNull(DataSourceFingerprint.matchIn(listOf("fp-a", "fp-b"), "fp-x"))
        assertNull(DataSourceFingerprint.matchIn(listOf<String?>(null, null), "fp-a"))
        assertNull(DataSourceFingerprint.matchIn(listOf("fp-a"), ""))
        assertNull(DataSourceFingerprint.matchIn(listOf("fp-a"), null))
    }

    // ------------------------------------------------------------------
    // 源码扫描接线守卫（PreviewPreparePickTest 范式）：摘掉接线必红
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(workingDir())
        while (dir != null && !File(dir, "settings.gradle.kts").exists() && !File(dir, "settings.gradle").exists()) {
            dir = dir.parentFile
        }
        return dir ?: error("repo root not found from ${workingDir()}")
    }

    private fun workingDir(): String = System.getProperty("user.dir") ?: "."

    private fun sourceText(rel: String): String = File(repoRoot(), rel).readText()

    @Test
    fun `接线守卫_静默复用链接了指纹兜底`() {
        val src = sourceText("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt")
        assertTrue(
            "静默复用链应调用 DataSourceFingerprint.resolveRunnerName（摘掉兜底接线本守卫红）",
            src.contains("DataSourceFingerprint.resolveRunnerName"),
        )
    }

    @Test
    fun `接线守卫_rememberRunner落指纹`() {
        val src = sourceText("src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelector.kt")
        assertTrue(
            "rememberRunner 应在写 last-used 双键时顺带落指纹（fingerprintOfDataSource）",
            src.contains("DataSourceFingerprint.fingerprintOfDataSource"),
        )
        assertTrue(
            "forgetLastRunner/forgetAllLastUsed 应连指纹键一起清（FINGERPRINT_KEY unset）",
            src.contains("pc.unsetValue(LastUsedKeys.key(DataSourceFingerprint.FINGERPRINT_KEY"),
        )
    }
}
