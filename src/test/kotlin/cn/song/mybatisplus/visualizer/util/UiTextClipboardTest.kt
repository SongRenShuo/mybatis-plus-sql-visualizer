package cn.song.mybatisplus.visualizer.util

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.ide.CopyPasteManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import cn.song.mybatisplus.visualizer.testutil.FakeClipboardManager
import cn.song.mybatisplus.visualizer.testutil.installFakeClipboard

/**
 * UiTextClipboard 文本复制收口测试（T-TestGuard2 G2-6，此前 0 测试）。
 *
 * T-UiNative P0 定案：剪贴板写入统一走本收口（进平台复制栈，Ctrl+Shift+V 可回贴）；
 * KDoc 明确不做 isUnitTestMode 短路——单元测试沙盒内 CopyPasteManager 可用且落剪贴板
 * 可读回（SqlClickPreview 等既有调用已验证），本测试把该前提本身锁成断言。
 *
 * T-ClipboardIso（20260918）：断言读回改走**内存假剪贴板**（installFakeClipboard 替换平台
 * CopyPasteManager 服务）——原实现直读系统剪贴板，用户在跑测试时复制内容、或并行分片 JVM
 * 抢占即抛 cannot open system clipboard；本类也不再含系统剪贴板字样，分片亲和规则自然不再
 * 固定本类（不再需要——进程内内存无机器级争用）。
 */
class UiTextClipboardTest : BasePlatformTestCase() {

    /** 内存剪贴板（T-ClipboardIso：测试全程不碰系统剪贴板——用户复制/并行会话互不干扰）。 */
    private lateinit var clipboard: FakeClipboardManager

    override fun setUp() {
        super.setUp()
        clipboard = installFakeClipboard(testRootDisposable)
    }

    private fun clipboardText(): String? = clipboard.text()

    /**
     * 测试态剪贴板服务必须是内存假实现（T-ClipboardIso 前提锁定）：平台服务若被还原成真实实现，
     * 本断言即红——那意味着后续用例会去碰系统剪贴板，用户复制/并行会话占用时整类假失败。
     */
    fun test剪贴板服务已替换为内存假实现() {
        assertTrue(
            "测试态 CopyPasteManager 应为 FakeClipboardManager（实际 ${CopyPasteManager.getInstance()::class.java.name}）",
            CopyPasteManager.getInstance() is FakeClipboardManager,
        )
    }

    fun testCopy_写入后可读回() {
        UiTextClipboard.copy("SELECT 1 FROM dual")
        assertEquals("copy 后剪贴板应可读回原文", "SELECT 1 FROM dual", clipboardText())
    }

    fun testCopy_连续写入_后写覆盖前写() {
        UiTextClipboard.copy("first")
        UiTextClipboard.copy("second")
        assertEquals("连续复制应以后写为准", "second", clipboardText())
    }

    fun testCopy_空串与多行文本() {
        UiTextClipboard.copy("")
        assertEquals("空串也是合法写入（清空语义）", "", clipboardText())
        val multi = "UPDATE t SET a = 1\nWHERE id = 2"
        UiTextClipboard.copy(multi)
        assertEquals("多行 SQL 原样落剪贴板", multi, clipboardText())
    }
}
