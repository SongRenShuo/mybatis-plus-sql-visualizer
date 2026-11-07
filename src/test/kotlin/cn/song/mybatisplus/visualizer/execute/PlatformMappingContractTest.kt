package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-ConsoleBind263 §4.3：平台契约探针（`availableMappingStrategy()`）。
 *
 * 为什么要有这一层：2026.3 把 `setMapping(VirtualFile, LocalDataSource)` 收走后，插件侧**没有任何
 * 红灯**——旧链一路 `runCatching` 退化成「只清 detach、没清关联」，症状是用户在真机上看到
 * 「弹窗只有 1 项」。契约探针把「这版平台还有哪条写入口」变成一行可读日志 + 一条断言。
 *
 * **能力边界（如实登记，勿当全绿）**：本仓库测试平台是编译基准 2024.3（数据库类为 243 版本），
 * 断言的是「探针在**当前测试平台**上能看到至少一条入口」。263 上是否还有 S1，由 263 平台 jar
 * 字节码取证（本单 §八 V1/V3）与 §六 真机验收覆盖，单测跑不到 263 形态。
 */
class PlatformMappingContractTest : BasePlatformTestCase() {

    fun testProbeSeesAtLeastOneWriteEntryWhenDatabaseApiIsOnClasspath() {
        val implClass = runCatching {
            Class.forName("com.intellij.database.dataSource.DataSourcePerFileMappingsImpl")
        }.getOrNull()
        if (implClass == null) {
            // 环境受限分支：不冒充绿——README/报告里按「未验证」记，不记「通过」。
            println(
                "[T-ConsoleBind263] DataSourcePerFileMappingsImpl 不在测试类路径 → " +
                    "契约探针未验证（不是通过）",
            )
            return
        }
        val strategies = ExecFileBinding.availableMappingStrategies()
        println(
            "[T-ConsoleBind263] contract probe on ${implClass.protectionDomain?.codeSource?.location} " +
                "→ $strategies, preferred=${ExecFileBinding.availableMappingStrategy()}",
        )
        assertTrue(
            "Database API 在类路径上，探测却拿不到任何写入口——策略链名存实亡（IDE 大版本又改了面）",
            strategies.isNotEmpty(),
        )
        assertNotNull(
            "契约探针必须给出首选策略名（日志分型依赖它）",
            ExecFileBinding.availableMappingStrategy(),
        )
    }
}
