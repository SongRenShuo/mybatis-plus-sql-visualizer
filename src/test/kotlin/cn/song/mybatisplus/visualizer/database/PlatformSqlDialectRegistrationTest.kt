package cn.song.mybatisplus.visualizer.database

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * T-GaussDbDialect 券 06 第 4 项：**平台 SQL 方言注册的运行期可见性**。
 *
 * ## 为什么必须放在 DB 集成通道
 *
 * `com.intellij.sql.dialect` 这个 EP 由 `com.intellij.database`（DatabaseTools）声明，而默认 `test`
 * 桶的 classpath **不含** Database 插件——`config/PluginXmlExtensionPointResolutionTest` 把该 EP
 * 列在 `externalEps` 里正是这个原因。因此默认桶只能证「XML 写得对」，证不了「平台真的认下来」。
 * 本类走 `testDatabase` 通道（`bundledPlugin("com.intellij.database")` 在该沙盒里是**已安装插件**），
 * 于是插件 `database-optional.xml` 的 `<sql.dialect>` 会被真正加载，`SqlDialectMappings` 里能查到。
 *
 * ## 判据
 *
 * 设置页 Languages & Frameworks → SQL Dialects 的候选列表来源就是
 * `SqlDialectMappings.getInstance(project).availableValues`。所以「列表里有 GaussDB」等价于
 * 「设置页能选到 GaussDB」——比截图更稳，且可重复。
 *
 * **DM 是对照组**：达梦的 `sql.dialect` 注册早于本任务就在册。若 DM 也不在列表里，说明是
 * database-optional.xml 整体没加载（环境问题），此时 GaussDB 缺失不能归因于本次改动——
 * 对照组让这两种失败可区分，避免把环境故障误读成功能缺陷。
 *
 * 已知边界：本类证的是**平台注册面**（能选、能出现在映射里）。「执行 Runner 精确匹配」那一半
 * 由 `GaussDbDialectRegistrationWiringTest` 的候选链断言 + `ExecDialectRouting.urlFallbackId`
 * 兜底链覆盖（纯逻辑）；两端合起来才是完整闭环。
 */
class PlatformSqlDialectRegistrationTest : BasePlatformTestCase() {

    private val databasePluginId = "com.intellij.database"

    fun testDatabasePluginInstalledInSandbox() {
        assertNotNull(
            "测试沙盒未安装 $databasePluginId 插件——本用例无法回答运行期可见性问题，" +
                "请确认 build.gradle.kts 的 bundledPlugin 依赖与 DB 集成通道（testDatabase）",
            PluginManagerCore.getPlugin(PluginId.getId(databasePluginId)),
        )
    }

    fun testGaussDbIsListedInPlatformSqlDialectValues() {
        assertNotNull(
            "测试沙盒未安装 $databasePluginId，无法查询平台 SQL 方言注册面",
            PluginManagerCore.getPlugin(PluginId.getId(databasePluginId)),
        )
        val available = SqlDialectMappings.getInstance(project).availableValues
        val ids = available.map { it.id }
        println("[PlatformSqlDialect] available=${ids.size} ids=$ids")

        // 对照组：DM 的注册早于本任务，用它证明「我们的 database-optional.xml 确实被加载了」
        assertTrue(
            "对照组失败：DM 不在平台可用方言里，说明插件 database-optional.xml 的 sql.dialect " +
                "注册整体没加载（环境/描述文件问题，不是本次改动）。实际：$ids",
            ids.contains("DM"),
        )
        assertTrue(
            "GaussDB 未出现在平台 SQL Dialects 可用列表（设置页因此选不到）。实际：$ids",
            ids.contains("GaussDB"),
        )
        assertEquals(
            "GaussDB 的显示名应与 GaussDbSqlDialect.DISPLAY_NAME 逐字一致",
            "GaussDB",
            available.first { it.id == "GaussDB" }.displayName,
        )
        // openGauss 我们**没有**注册平台方言（平台缺、也无必要），它的缺席是预期行为而非缺陷
        assertTrue(
            "openGauss 未注册平台方言是预期（本轮只注册 GaussDB）；若出现请复核是否有人重复注册",
            !ids.contains("openGauss"),
        )
    }
}
