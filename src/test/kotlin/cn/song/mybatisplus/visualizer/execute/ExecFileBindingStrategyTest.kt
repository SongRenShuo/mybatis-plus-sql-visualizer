package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-ConsoleBind263（2026-11-07）：per-file 关联写入的**多版本策略链**。
 *
 * 缺陷本体：IDE 2026.3 移除了 `DataSourcePerFileMappingsImpl.setMapping(VirtualFile, LocalDataSource)`，
 * 固定调用旧重载的反射恒抛 `NoSuchMethodException` → pin 清不掉 → 平台 forced 关联把 runner
 * 枚举短路成单源，换源弹窗只剩当前源、工具栏点选静默跑到被钉住的源（用户 2026-11-07 实测）。
 *
 * 本类测**纯选择逻辑**（[ExecFileBinding.selectMappingStrategies]）与**生产接线**（源码扫描：
 * 链 + 写后回读 + 结果对象真的被用上）。真实平台上的「哪条策略生效」由 263 字节码取证与
 * §六 真机验收覆盖——测试平台的 Database 类是 243 版本，跑不出 263 形态。
 */
class ExecFileBindingStrategyTest {

    private val all = ExecFileBinding.MappingStrategy.values().toList()

    /** 全可用时顺序恒定——**不随传入集合的顺序变化**（探测结果来自集合类型也不会乱序）。 */
    @Test
    fun `策略顺序恒为 S1到S4`() {
        val shuffled = listOf(
            ExecFileBinding.MappingStrategy.STATIC_WRITE_ONLY,
            ExecFileBinding.MappingStrategy.STRING_ID,
            ExecFileBinding.MappingStrategy.WRITE_ACTION,
            ExecFileBinding.MappingStrategy.LEGACY_DS,
        )
        assertEquals(all, ExecFileBinding.selectMappingStrategies(shuffled, clearing = false))
        assertEquals(
            listOf(
                ExecFileBinding.MappingStrategy.WRITE_ACTION,
                ExecFileBinding.MappingStrategy.LEGACY_DS,
                ExecFileBinding.MappingStrategy.STRING_ID,
                ExecFileBinding.MappingStrategy.STATIC_WRITE_ONLY,
            ),
            ExecFileBinding.selectMappingStrategies(all, clearing = false),
        )
    }

    /** ★ 安全主判据：CLEAR（intendedDs=null）永远不得选 S4——263 的静态写入口内部直接取 ds.uniqueId。 */
    @Test
    fun `CLEAR 永不选只写策略`() {
        val forClear = ExecFileBinding.selectMappingStrategies(all, clearing = true)
        assertTrue("CLEAR 不得包含 S4", ExecFileBinding.MappingStrategy.STATIC_WRITE_ONLY !in forClear)
        assertEquals(
            listOf(
                ExecFileBinding.MappingStrategy.WRITE_ACTION,
                ExecFileBinding.MappingStrategy.LEGACY_DS,
                ExecFileBinding.MappingStrategy.STRING_ID,
            ),
            forClear,
        )
    }

    /** 263 的实测可用集合（S1 写动作 / S3 String / S4 只写）：REALIGN 三个都试，CLEAR 只试 S1+S3。 */
    @Test
    fun `263 可用集合下 CLEAR 剩两条候选`() {
        val on263 = listOf(
            ExecFileBinding.MappingStrategy.WRITE_ACTION,
            ExecFileBinding.MappingStrategy.STRING_ID,
            ExecFileBinding.MappingStrategy.STATIC_WRITE_ONLY,
        )
        assertEquals(on263, ExecFileBinding.selectMappingStrategies(on263, clearing = false))
        assertEquals(
            listOf(ExecFileBinding.MappingStrategy.WRITE_ACTION, ExecFileBinding.MappingStrategy.STRING_ID),
            ExecFileBinding.selectMappingStrategies(on263, clearing = true),
        )
    }

    /** 只有「只能写」的 S4 可用时，CLEAR 没有任何候选——上层必须报 UNAVAILABLE，不得硬试。 */
    @Test
    fun `仅 S4 可用时 CLEAR 无候选`() {
        val onlyS4 = listOf(ExecFileBinding.MappingStrategy.STATIC_WRITE_ONLY)
        assertTrue(ExecFileBinding.selectMappingStrategies(onlyS4, clearing = true).isEmpty())
        assertEquals(onlyS4, ExecFileBinding.selectMappingStrategies(onlyS4, clearing = false))
    }

    /** 平台一个入口都没有（社区版/未来再次移除）：两个语义都得到空链，调用方按 fail-closed 处理。 */
    @Test
    fun `无可用策略时链为空`() {
        assertTrue(ExecFileBinding.selectMappingStrategies(emptyList(), clearing = false).isEmpty())
        assertTrue(ExecFileBinding.selectMappingStrategies(emptyList(), clearing = true).isEmpty())
    }

    // ------------------------------------------------------------------
    // 生产接线守卫（源码扫描）——纯函数绿证明不了 realign 真的按链走、真的回读
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }

    private fun bindingSource(): String = File(
        repoRoot(),
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/ExecFileBinding.kt",
    ).readText()

    private fun resultsSource(): String = File(
        repoRoot(),
        "src/main/kotlin/cn/song/mybatisplus/visualizer/execute/DataSourceSelectorResults.kt",
    ).readText()

    /** realign 必须走 [ExecFileBinding.selectMappingStrategies]，不能再固定调单一重载。 */
    @Test
    fun `realign 必须按策略链走`() {
        val source = bindingSource()
        val realign = source.indexOf("fun realignExecBind(")
        assertTrue("realignExecBind 不存在", realign > 0)
        val body = source.substring(realign)
        assertTrue("realign 未使用 selectMappingStrategies（回到单版本硬编码）", body.contains("selectMappingStrategies("))
        assertTrue("realign 未做写后回读验证", body.contains("READBACK_MISMATCH"))
    }

    /** 调用方必须用结果对象（策略/回读），不能退回旧布尔——否则日志再也读不出「哪条策略生效」。 */
    @Test
    fun `调用方必须消费结果对象`() {
        val source = resultsSource()
        assertTrue("调用方未消费 realign.applied", source.contains("realign.applied"))
        assertTrue(
            "未落地的对齐必须在面板就地提示（P1-5）",
            source.contains("execute.datasource.realignNotApplied"),
        )
    }
}
