package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T-CiMarketSplit（2026-11-05）：钉住的 verifier-cli 版本必须与 IPGP 实际使用的版本一致。
 *
 * 为什么需要这条：CI 的 verify / marketplace 腿不再靠跑 `:verifyPlugin` 预热来获得 verifier-cli
 * jar（那一步会连带重新编译整个项目，约 4 分钟），改为直接按
 * `dev-support/verifier-cli-version.txt` 里的版本从 Maven Central 下载后播种进 gradle 缓存。
 * 版本号本身由 IPGP 内部决定，我们无法在 CI 里动态取得，所以钉死 + 本守卫兜底：
 * 一旦本机缓存里出现**不同版本**的 verifier-cli（说明 IPGP 升级了），本用例立刻变红，
 * 提示同步改钉子——否则门禁会用旧版 verifier 悄悄跑，判定口径与 IPGP 脱钩。
 *
 * 覆盖面说明：只在本机跑过 `:verifyPlugin`（缓存里有 jar）时生效；缓存为空时 skip，
 * 因此它守的是「本机全量测试」，不是 CI。
 */
class VerifierCliVersionGuardTest {

    private val jarPattern = Regex("verifier-cli-(.+)-all\\.jar")

    @Test
    fun `钉住的 verifier-cli 版本与 IPGP 实际下载的一致`() {
        val pinnedFile = File("dev-support/verifier-cli-version.txt")
        assumeTrue("钉子文件不存在，跳过", pinnedFile.isFile)
        val pinned = pinnedFile.readText().trim()
        assumeTrue("钉子文件为空，跳过", pinned.isNotEmpty())

        val gradleHome = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".gradle").absolutePath
        val verifierDir = File(
            gradleHome,
            "caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli",
        )
        assumeTrue("本机 gradle 缓存里没有 verifier-cli（未跑过 :verifyPlugin），跳过", verifierDir.isDirectory)

        val cached = verifierDir.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile }
            .mapNotNull { jarPattern.find(it.name)?.groupValues?.get(1) }
            .toSet()

        assumeTrue("缓存里没有 verifier-cli-*-all.jar，跳过", cached.isNotEmpty())
        assertTrue(
            "dev-support/verifier-cli-version.txt 钉的是 $pinned，但本机 gradle 缓存里的版本是 $cached。" +
                "IPGP 可能升级了 verifier——请同步改钉子，否则 CI 门禁会用旧版 verifier 跑判定。",
            pinned in cached,
        )
    }
}
