package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * T-Exec262ReadLock A'：runners 为空的三态归因与异常首行提取（纯函数）。
 *
 * 2026-11-02 用户实测现场：262 平台 `DatabaseRunners.getAttachDataSourceRunners` 内部
 * 读锁硬抛 `Read access is allowed from inside read-action only`，被 runCatching 吞成
 * 空列表 → 用户看到「没有与该 SQL 方言匹配的已配置数据源」，被误导去改 Dialects 设置。
 * 归因必须把「平台调用抛异常」与「平台正常返回零个可用源」分开。
 *
 * k2j 互操作（T-SyncK2JMaster176 B1 移植）：master 的 Kotlin 顶层函数落
 * `DataSourceSelectorResultsKt` 门面静态方法；`EmptyRunnersReason` 为其嵌套 enum，
 * 引用走嵌套限定形态。
 */
class DataSourceSelectorRunnersReasonTest {

    @Test
    fun test无在册源优先于平台异常_引导配置数据源() {
        // 一条数据源都没有时，用户最需要「先配数据源」，平台异常不应抢镜
        assertEquals(
            DataSourceSelectorResultsKt.EmptyRunnersReason.NO_DATASOURCE,
            DataSourceSelectorResultsKt.emptyRunnersReason(true, IllegalStateException("boom")),
        )
    }

    @Test
    fun test平台调用抛异常_归因为平台调用失败() {
        assertEquals(
            DataSourceSelectorResultsKt.EmptyRunnersReason.PLATFORM_CALL_FAILED,
            DataSourceSelectorResultsKt.emptyRunnersReason(false, IllegalStateException("boom")),
        )
    }

    @Test
    fun test平台正常返回空_归因为方言不匹配() {
        // 正对照：没有异常时维持既有文案语义，不误报平台故障
        assertEquals(
            DataSourceSelectorResultsKt.EmptyRunnersReason.DIALECT_MISMATCH,
            DataSourceSelectorResultsKt.emptyRunnersReason(false, null),
        )
    }

    @Test
    fun test异常首行取最内层cause并只留首行() {
        val root = RuntimeException(
            "Read access is allowed from inside read-action only\n详见 https://jb.gg/ij-platform-threading",
        )
        val wrapped = InvocationTargetException(root)
        assertEquals(
            "Read access is allowed from inside read-action only",
            DataSourceSelectorResultsKt.rootCauseFirstLine(wrapped),
        )
    }

    @Test
    fun test异常无message时回退类名() {
        assertEquals("IllegalStateException", DataSourceSelectorResultsKt.rootCauseFirstLine(IllegalStateException()))
    }
}
