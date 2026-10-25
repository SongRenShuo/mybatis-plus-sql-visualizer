package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-DialectInferPollution W11 护栏：排队执行意图的**自有有界结算**
 * （[PrepareSingleFlightGate.expirePendingExecute]）。
 *
 * 缺陷形态（2026-10-24 用户真机「点执行 SQL 卡住」）：执行意图排队（QUEUE_ONLY）后只能等
 * owner 的 60s 逃生被下一链接管才结算——排队者自身无触发点，面板永远「执行中…」。修法＝
 * 排队意图自己的 15s 结算 Timer：到期只 BLOCKED 排队意图解除加载态，**不动门与 owner 链**。
 *
 * [PrepareSingleFlightGate] 纯 JDK 原子类可直测（类 KDoc 既有口径），零平台引用。
 */
class PrepareGatePendingTimeoutTest {

    private fun gate(): PrepareSingleFlightGate = PrepareSingleFlightGate()

    private fun ownerIntent() = QueuedExecuteIntent(null, null, null, null, false)

    @Test
    fun testPendingIntentExpiresOnItsOwnWithoutTouchingGate() {
        val g = gate()
        val owner = ownerIntent()
        assertEquals(PrepareGateDecision.PROCEED, g.tryEnter(true, owner))
        val queued = ownerIntent()
        assertEquals(PrepareGateDecision.QUEUE_ONLY, g.tryEnter(true, queued))

        assertTrue("排队意图 15s 超时应自结算（BLOCKED 解除加载态）", g.expirePendingExecute(queued, "timeout"))
        assertTrue("排队意图结算后门与 owner 链不受影响（owner 照常推进）", g.isOwner(owner))
        assertTrue("排队位应清空", !g.pendingExecute.get())
        assertTrue("被结算的排队意图不得重复消费", !g.expirePendingExecute(queued, "timeout"))
    }

    @Test
    fun testOwnerChainCanStillConsumeAfterQueuedExpiry() {
        val g = gate()
        val owner = ownerIntent()
        g.tryEnter(true, owner)
        val queued = ownerIntent()
        g.tryEnter(true, queued)
        g.expirePendingExecute(queued, "timeout")

        // owner 链照常推进：仍可消费排队位（已空）并 finishPrepare 复位
        assertNull("排队位已被超时结算清空，owner 消费得到 null", g.consumePendingExecute(owner))
        assertTrue("owner 链终结复位不受排队超时影响", g.finishPrepare(owner))
        assertFalse("门已复位", g.prepareInFlight.get())
    }

    @Test
    fun testUnknownIntentIsIdempotentNoOp() {
        val g = gate()
        val owner = ownerIntent()
        g.tryEnter(true, owner)
        val queued = ownerIntent()
        g.tryEnter(true, queued)

        val stranger = ownerIntent()
        assertFalse("陌生意图不得误清他人排队位", g.expirePendingExecute(stranger, "timeout"))
        assertTrue("排队位应仍在", g.pendingExecute.get())
    }
}
