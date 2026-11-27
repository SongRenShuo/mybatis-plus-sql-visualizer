package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R62（T-LaneB，V114 三态）：更新检查失败可诊断化——check 层三态映射与 UpdateApi 取数分层。
 *
 * 三态＝网络不通（NETWORK）/ 通道 404（CHANNEL_MISSING）/ 已最新（UpToDate）；
 * V114③ 已 javap 实证 243 基线 HttpRequests$HttpStatusException.getStatusCode() 可得，
 * 故无需降级。fetch seam 注入 FetchOutcome（不触网）。
 */
class UpdateCheckDiagnosisTest {

    private val newerManifest =
        """{"version":"2026.8.15","downloadUrl":"https://example.com/plugin.zip"}"""
    private val sameManifest =
        """{"version":"2026.8.14","downloadUrl":"https://example.com/plugin.zip"}"""

    // ---------- 三态各一例（V121.3） ----------

    @Test
    fun 网络不通_网络态() {
        val result = PluginUpdateRunner.check("2026.8.14") {
            UpdateApi.FetchOutcome.NetworkError("connect timed out")
        }
        assertTrue(result is PluginUpdateRunner.CheckResult.Failure)
        assertEquals(
            PluginUpdateRunner.FailureKind.NETWORK,
            (result as PluginUpdateRunner.CheckResult.Failure).kind,
        )
    }

    @Test
    fun 通道404_通道缺失态() {
        val result = PluginUpdateRunner.check("2026.8.14") {
            UpdateApi.FetchOutcome.HttpError(404)
        }
        assertTrue(result is PluginUpdateRunner.CheckResult.Failure)
        assertEquals(
            PluginUpdateRunner.FailureKind.CHANNEL_MISSING,
            (result as PluginUpdateRunner.CheckResult.Failure).kind,
        )
    }

    @Test
    fun 清单同版_已最新态() {
        val result = PluginUpdateRunner.check("2026.8.14") {
            UpdateApi.FetchOutcome.Success(sameManifest)
        }
        assertTrue(result is PluginUpdateRunner.CheckResult.UpToDate)
    }

    // ---------- 辅助态（诚实降级面） ----------

    @Test
    fun 清单损坏_解析态() {
        val result = PluginUpdateRunner.check("2026.8.14") {
            UpdateApi.FetchOutcome.Success("not json")
        }
        assertEquals(
            PluginUpdateRunner.FailureKind.MANIFEST_MALFORMED,
            (result as PluginUpdateRunner.CheckResult.Failure).kind,
        )
    }

    @Test
    fun 当前版本不可读_版本态() {
        val result = PluginUpdateRunner.check(null) { throw AssertionError("fetch must not be called") }
        assertEquals(
            PluginUpdateRunner.FailureKind.VERSION_UNREADABLE,
            (result as PluginUpdateRunner.CheckResult.Failure).kind,
        )
    }

    @Test
    fun 非404HTTP错误_网络态带状态描述() {
        val result = PluginUpdateRunner.check("2026.8.14") {
            UpdateApi.FetchOutcome.HttpError(503)
        }
        val failure = result as PluginUpdateRunner.CheckResult.Failure
        assertEquals(PluginUpdateRunner.FailureKind.NETWORK, failure.kind)
        assertTrue("非 404 HTTP 错误按网络态降级且消息带状态码", failure.message.contains("503"))
    }

    @Test
    fun 更新可用态不受影响() {
        val result = PluginUpdateRunner.check("2026.8.14") {
            UpdateApi.FetchOutcome.Success(newerManifest)
        }
        assertTrue(result is PluginUpdateRunner.CheckResult.Available)
    }

    // ---------- UpdateSource 兜底常量（V114②） ----------

    @Test
    fun Marketplace兜底链接常量存在_与发行页同值() {
        assertEquals(
            "https://plugins.jetbrains.com/plugin/33633-mybatis-sql-visualizer",
            UpdateSource.MARKETPLACE_URL,
        )
        assertEquals(
            "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases",
            UpdateSource.GITEE_RELEASES_URL,
        )
    }
}
