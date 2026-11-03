package cn.song.mybatisplus.visualizer.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * T-DialectGatePassDeadChain（2026-11-02）：准备链超时文案分型守卫。
 *
 * 排队执行意图超时的真实处境是「上一条准备链还没结束」，旧实现复用 `execute.prepare.timeout`
 * （「数据库控制台准备超时，未提交 SQL。请重新选择数据源后重试。」）——既误导（用户没超时），
 * 又把人指向当时根本不存在的换源入口。本用例钉住：
 *  - 排队超时是**独立键**，不复用准备超时；
 *  - 文案带等待秒数占位符 `{0}`，且不再出现「重新选择数据源」；
 *  - 早退结算键（`execute.prepare.superseded`）中英双语齐备。
 *
 * 纯逻辑测试（零平台依赖）：直接按 UTF-8 读 classpath 上的两份 bundle。
 */
class PrepareQueuedTimeoutMessageTest {

    private fun bundle(suffix: String): Properties {
        val stream = javaClass.classLoader
            .getResourceAsStream("messages/MpSqlVisualizerBundle$suffix.properties")
            ?: error("bundle 资源缺失: messages/MpSqlVisualizerBundle$suffix.properties")
        return Properties().also { props -> stream.use { props.load(it.reader(Charsets.UTF_8)) } }
    }

    @Test
    fun `queued timeout has its own message with elapsed seconds`() {
        val zh = bundle("_zh")
        val en = bundle("")
        val queued = zh.getProperty("execute.prepare.queuedTimeout")
        val prepare = zh.getProperty("execute.prepare.timeout")

        assertTrue("排队超时必须有独立键", !queued.isNullOrBlank())
        assertTrue("排队超时不得复用准备超时文案", queued != prepare)
        assertTrue("文案须带等待秒数占位符", queued.contains("{0}"))
        assertFalse("不得再让用户「重新选择数据源」", queued.contains("重新选择数据源"))

        val queuedEn = en.getProperty("execute.prepare.queuedTimeout")
        assertTrue("英文同键须存在且带占位符", !queuedEn.isNullOrBlank() && queuedEn.contains("{0}"))
    }

    @Test
    fun `superseded message exists in both bundles`() {
        assertTrue(
            "中文早退结算文案缺失",
            !bundle("_zh").getProperty("execute.prepare.superseded").isNullOrBlank(),
        )
        assertTrue(
            "英文早退结算文案缺失",
            !bundle("").getProperty("execute.prepare.superseded").isNullOrBlank(),
        )
    }
}
