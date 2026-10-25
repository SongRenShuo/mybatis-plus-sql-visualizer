// 自 master src/test/kotlin/.../ui/annotate/PreviewSchemaSessionLifecycleTest.kt 移植
// （k2j 直取：PreviewSchemaHighlighter 为 Java 承载，静态缝同名可用）
package cn.song.mybatisplus.visualizer.ui.annotate

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-PreviewSchemaSessionLeak 护栏：预览错列标注会话（[PreviewSchemaHighlighter.Session]）的**生命周期**。
 *
 * 缺陷形态（20261023 用户真机日志）：`Session` 实现了 `Disposable`，但从未注册进 Disposer 树；其内部
 * `Alarm(POOLED_THREAD, this)` 以它为 parentDisposable，平台遂把这条孤儿分支挂到 `ROOT_DISPOSABLE` 下，
 * 关 IDE 时报 24 条 `Memory leak detected`（插件被点名）。修法＝「注册进父树（project）+ 自身可判定
 * disposed 标志幂等」。
 *
 * 本类断言的是修法的两条载荷：
 *  1. **属主 dispose 会连带销毁会话**（兜底真的生效——关 IDE 时不再残留）；
 *  2. 生产入口以 `editor.project` 为属主（没退化成"不注册"）；
 *  3. `uninstall` 幂等（树感知销毁 + 标志守卫）；
 *  4. 无属主（`owner == null`）分支显式覆盖：不注册但仍能被 `uninstall` 正常销毁。
 */
class PreviewSchemaSessionLifecycleTest : BasePlatformTestCase() {

    private fun configureEditor(): Editor {
        myFixture.configureByText("lifecycle.sql", "select 1")
        return myFixture.editor
    }

    override fun tearDown() {
        try {
            runCatching { PreviewSchemaHighlighter.uninstall(myFixture.editor) }
        } finally {
            super.tearDown()
        }
    }

    /** 属主 dispose ⇒ 会话随之销毁（兜底回收；这正是关 IDE 时原先漏掉的一步）。 */
    fun testOwnerDisposalReclaimsSession() {
        val editor = configureEditor()
        val owner = Disposer.newDisposable()
        Disposer.register(testRootDisposable, owner)

        PreviewSchemaHighlighter.install(editor, owner)
        assertTrue("刚安装时应存在活会话", PreviewSchemaHighlighter.hasLiveSessionForTest(editor))

        Disposer.dispose(owner)
        assertFalse(
            "属主 dispose 后不应再有活会话——否则关 IDE 时该会话仍挂在 ROOT 下报泄漏",
            PreviewSchemaHighlighter.hasLiveSessionForTest(editor),
        )
    }

    /** 生产入口以 editor.project 为属主（防止退化成"不入树"）。 */
    fun testProductionInstallUsesProjectAsOwner() {
        val editor = configureEditor()
        PreviewSchemaHighlighter.install(editor)

        assertSame(
            "生产 install 必须以 editor.project 为属主",
            editor.project,
            PreviewSchemaHighlighter.sessionOwnerForTest(editor),
        )
    }

    /** uninstall 幂等：树感知销毁一次后再调不抛、状态保持已销毁。 */
    fun testUninstallIsIdempotent() {
        val editor = configureEditor()
        PreviewSchemaHighlighter.install(editor)

        assertTrue("uninstall 前应有活会话", PreviewSchemaHighlighter.hasLiveSessionForTest(editor))
        PreviewSchemaHighlighter.uninstall(editor)
        assertFalse("首次 uninstall 后不应再有活会话", PreviewSchemaHighlighter.hasLiveSessionForTest(editor))
        PreviewSchemaHighlighter.uninstall(editor)   // 第二次不应抛
        PreviewSchemaHighlighter.uninstall(null)
    }

    /** 属主 dispose 后又走 uninstall：两条路径先后到达也必须幂等（同一会话不得二次 dispose）。 */
    fun testOwnerDisposalThenUninstallStaysIdempotent() {
        val editor = configureEditor()
        val owner = Disposer.newDisposable()
        Disposer.register(testRootDisposable, owner)

        PreviewSchemaHighlighter.install(editor, owner)
        Disposer.dispose(owner)
        PreviewSchemaHighlighter.uninstall(editor)   // 兜底已销毁，这里应直接跳过

        assertFalse(PreviewSchemaHighlighter.hasLiveSessionForTest(editor))
    }

    /** 无属主分支（owner == null）：不注册进树，但 uninstall 仍能正常销毁。 */
    fun testNullOwnerBranchStillDisposableByUninstall() {
        val editor = configureEditor()
        PreviewSchemaHighlighter.install(editor, null)

        assertNull("无属主时不应记录属主", PreviewSchemaHighlighter.sessionOwnerForTest(editor))
        assertTrue(PreviewSchemaHighlighter.hasLiveSessionForTest(editor))

        PreviewSchemaHighlighter.uninstall(editor)
        assertFalse("无属主分支也要能被 uninstall 销毁", PreviewSchemaHighlighter.hasLiveSessionForTest(editor))
    }

    /**
     * T-PreviewChainNoise W3：uninstall（树感知销毁）全程不得产生平台 ERROR。
     *
     * Session 的两个监听经两参重载注册（摘除所有权=注册时的 parentDisposable），且 ObjectTree
     * 按后序（子先父后）销毁——若 `Session.dispose` 里再手动 remove 即二次摘除，平台打
     * 「Can't remove document listener」与 EditorImpl 的「Assertion failed」（2026-10-23 用户
     * 真机日志；`ebfaf73f` 改树感知销毁后暴露，此前直接调 `session.dispose()` 不走树，
     * 手动摘除是唯一路径所以无此噪音）。反向：把两行手动摘除加回 dispose → 本用例必红。
     */
    fun testUninstallLogsNoPlatformErrors() {
        val editor = configureEditor()
        PreviewSchemaHighlighter.install(editor)
        val rec = RecordingProcessor()
        LoggedErrorProcessor.executeWith(rec, com.intellij.util.ThrowableRunnable {
            PreviewSchemaHighlighter.uninstall(editor)
        })
        val hits = rec.seen.filter {
            it.contains("Can't remove document listener") || it.contains("Assertion failed")
        }
        assertTrue("dispose 全程不应产生平台 ERROR（二次摘监听即命中）：$hits", hits.isEmpty())
    }
}

/**
 * 记录平台 ERROR 级日志的处理器（SqlPreviewPanelPrewarmWriteSafeTest 同款口径）。
 * 一律返回 `Action.NONE`（不 dump、不抛）——判据由显式断言给出，可读且不掩盖被测链的
 * runCatching 行为。两个参数都收（logger 名 + 消息体），避免漏掉真正含特征串的那侧。
 */
private class RecordingProcessor : LoggedErrorProcessor() {
    val seen = mutableListOf<String>()

    override fun processError(
        message: String,
        details: String,
        trace: Array<String>,
        throwable: Throwable?,
    ): MutableSet<LoggedErrorProcessor.Action> {
        seen += message
        seen += details
        return LoggedErrorProcessor.Action.NONE
    }
}
