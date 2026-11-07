package cn.song.mybatisplus.visualizer.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换源入口测试（T-Intranet-01）：系统属性 `mpvs.update.manifest.url` 的解析口径。
 *
 * 覆盖「属性未设 / 空白 → 默认源」与「属性设值 → 覆盖源」两条纯函数分支。
 * 日志有无不作断言目标（Logger 输出在单测中不可靠，不为测试引入 seam）；
 * 生效值的端到端确认见开发计划 §五 真机走查清单。
 */
class UpdateSourceTest {

    private val original = System.getProperty(UpdateSource.MANIFEST_URL_OVERRIDE)

    private val originalPreview = System.getProperty(UpdateSource.PREVIEW_MANIFEST_URL_OVERRIDE)

    @After
    fun tearDown() {
        if (original == null) {
            System.clearProperty(UpdateSource.MANIFEST_URL_OVERRIDE)
        } else {
            System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, original)
        }
        if (originalPreview == null) {
            System.clearProperty(UpdateSource.PREVIEW_MANIFEST_URL_OVERRIDE)
        } else {
            System.setProperty(UpdateSource.PREVIEW_MANIFEST_URL_OVERRIDE, originalPreview)
        }
    }

    @Test
    fun manifestUrlFallsBackToDefaultWhenPropertyAbsentOrBlank() {
        System.clearProperty(UpdateSource.MANIFEST_URL_OVERRIDE)
        assertEquals(UpdateSource.DEFAULT_MANIFEST_URL, UpdateSource.manifestUrl())

        // 空白（空格/制表）视为未设置——与属性解析口径一致
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, "   ")
        assertEquals(UpdateSource.DEFAULT_MANIFEST_URL, UpdateSource.manifestUrl())
    }

    @Test
    fun manifestUrlPrefersSystemPropertyWhenSet() {
        val internal = "http://nexus.intranet.local/plugins/update.json"
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, internal)
        assertEquals(internal, UpdateSource.manifestUrl())
    }

    // ---------- T-DevUpdateChannel：预览清单 URL 分流 ----------

    @Test
    fun previewManifestUrlDefaultsToPreviewPath() {
        System.clearProperty(UpdateSource.MANIFEST_URL_OVERRIDE)
        System.clearProperty(UpdateSource.PREVIEW_MANIFEST_URL_OVERRIDE)
        val url = UpdateSource.previewManifestUrl()
        assertEquals(UpdateSource.DEFAULT_PREVIEW_MANIFEST_URL, url)
        assertTrue("预览 URL 必须指向 preview.update.json：$url", url.endsWith(UpdateSource.PREVIEW_MANIFEST_PATH))
        // 与正式清单不同（分流的意义：读到的是另一个文件）
        assertTrue(url != UpdateSource.manifestUrl())
    }

    @Test
    fun previewManifestUrlDerivesFromOverriddenManifestUrl() {
        // 本地 server 验证只需多一条路由：换源到 localhost 后预览地址同源改名
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, "http://127.0.0.1:8000/update/update.json")
        System.clearProperty(UpdateSource.PREVIEW_MANIFEST_URL_OVERRIDE)
        assertEquals(
            "http://127.0.0.1:8000/update/preview.update.json",
            UpdateSource.previewManifestUrl(),
        )
    }

    @Test
    fun previewManifestUrlPrefersItsOwnOverride() {
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, "http://127.0.0.1:8000/update/update.json")
        System.setProperty(UpdateSource.PREVIEW_MANIFEST_URL_OVERRIDE, "http://127.0.0.1:8000/custom/preview-v2.json")
        assertEquals("http://127.0.0.1:8000/custom/preview-v2.json", UpdateSource.previewManifestUrl())

        // 空白视为未设置
        System.setProperty(UpdateSource.PREVIEW_MANIFEST_URL_OVERRIDE, "   ")
        assertEquals("http://127.0.0.1:8000/update/preview.update.json", UpdateSource.previewManifestUrl())
    }

    @Test
    fun previewManifestUrlFallsBackToDefaultWhenOverrideHasNoManifestPath() {
        // 换源值不含 update/update.json（自定义文件名）→ 同源改名无从谈起，回落默认预览地址，
        // 绝不静默等于正式 URL（否则开发版通道会悄悄读正式清单）
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, "http://nexus.intranet.local/plugins/plugin-meta.json")
        System.clearProperty(UpdateSource.PREVIEW_MANIFEST_URL_OVERRIDE)
        val url = UpdateSource.previewManifestUrl()
        assertEquals(UpdateSource.DEFAULT_PREVIEW_MANIFEST_URL, url)
        assertTrue(url != UpdateSource.manifestUrl())
    }
}