package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-PreviewExecWriteDefer：`PreviewConsoleEager` 的口径开关。
 *
 * 背景（用户 2026-11-14 00:10 A/B）：预览打开「准备控制台」会写 `mpsql-exec.sql`，该文件带方言映射 ⇒
 * 平台重算语言替换 + 推文件属性 ⇒ 工程进 dumb（第 1 次写→dumb；第 2 次不写→无 dumb）。惰性口径把
 * 这一步挪到「首次执行」，执行链自己会准备 + 同步 exec 文件。
 */
class PreviewConsoleEagerTest : BasePlatformTestCaseShim() {

    override fun setUp() {
        super.setUp()
        PreviewConsoleEager.resetForTest()
    }

    override fun tearDown() {
        try {
            PreviewConsoleEager.resetForTest()
        } finally {
            super.tearDown()
        }
    }

    fun test单测口径默认走旧行为() {
        // 单测里默认 eager：既有用例按「打开即准备控制台」写，不能因此大面积改测试
        assertTrue("单测默认 eager（旧行为）", PreviewConsoleEager.enabled())
    }

    fun test显式置惰性时不准备控制台() {
        PreviewConsoleEager.enabledForTest = false
        assertFalse(PreviewConsoleEager.enabled())
        PreviewConsoleEager.recordDeferred()
        assertEquals("延后计数用于守卫", 1, PreviewConsoleEager.deferredCount.get())
    }

    fun test显式置旧行为() {
        PreviewConsoleEager.enabledForTest = true
        assertTrue(PreviewConsoleEager.enabled())
    }
}

/** 平台测试基类（本任务只需项目夹具，避免引 Heavy）。 */
abstract class BasePlatformTestCaseShim : com.intellij.testFramework.fixtures.BasePlatformTestCase()
