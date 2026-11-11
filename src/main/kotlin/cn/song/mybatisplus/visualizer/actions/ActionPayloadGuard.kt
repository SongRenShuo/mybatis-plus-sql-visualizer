package cn.song.mybatisplus.visualizer.actions

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException

/**
 * T-HiddenBugPerfScan 券 F：Action 后台 ReadAction 载荷守卫——取消协议（PCE/Cancellation）
 * 原样上抛：后台 ReadAction 被写操作取消/进度取消时，取消不得被吞成「未找到」空结果
 * 误导用户重试或放弃；其余 Throwable 收敛为 [default]（各站点原有兜底语义不变）。
 *
 * 本仓 9 处 `catch (t: Throwable)` 站点（ConvertToSqlAction ×7 + GenerateCrudSql 两动作）
 * 共用本守卫（S7 同型兄弟一次修）；契约与 [ConvertSqlToJavaAction.runBatchLookupGuarded]
 * （券 E）同款。
 */
internal inline fun <T> actionPayloadGuarded(default: T, block: () -> T): T = try {
    block()
} catch (t: Throwable) {
    if (t is ProcessCanceledException || t is CancellationException) throw t
    // T-FatalPassthrough T-FP-03 收口：JVM 级致命错误原样上抛（testFast 守卫判据）
    if (cn.song.mybatisplus.visualizer.util.FatalErrors.isFatal(t)) throw t
    default
}
