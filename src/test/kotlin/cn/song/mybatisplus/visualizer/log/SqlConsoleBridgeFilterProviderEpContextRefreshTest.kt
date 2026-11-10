package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-HiddenBugPerfScan 券 C：EP 通道解析配置刷新回归。
 *
 * T-LogPerf-04 之后配置刷新只剩 `bridge.refreshConsoleContexts()`（只遍历桥侧
 * consoleContexts），EP 侧 ctx 快照在控制台创建时刻固化——捕捉中改 SQL/参数前缀
 * 对 EP 主通道永不生效（T-CaptureTruth 起 EP 为主通道、桥被短路）。修复后 EP ctx
 * 经 [SqlConsoleBridgeFilterProvider.registerEpContext] 登记，设置变更经
 * [SqlConsoleBridgeFilterProvider.refreshEpContexts] 与桥同拍同步（字段面与桥侧
 * applyConfig 完全一致）。
 */
class SqlConsoleBridgeFilterProviderEpContextRefreshTest {

    private fun newCtx(sqlPrefix: String = "Preparing:"): BridgeLineContext {
        val cfg = ParserConfig(
            sqlPrefix = sqlPrefix,
            paramPrefix = "Parameters:",
            mixedPrefix = false,
            maxParseLength = 20000,
        )
        return BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = cfg.sqlPrefix,
                paramPrefix = cfg.paramPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = cfg.hibernateSqlPrefix,
                paramPrefix = cfg.hibernateParamPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
            ),
            jdbcParser = JdbcTemplateLogParser(enabled = cfg.jdbcEnabled, maxParseLength = cfg.maxParseLength),
            configProvider = { cfg },
        )
    }

    @Test
    fun `注册的EP ctx在设置变更后按新配置刷新`() {
        val ctx = newCtx(sqlPrefix = "Preparing:")
        SqlConsoleBridgeFilterProvider.registerEpContext(ctx)

        val newCfg = ParserConfig(
            sqlPrefix = "SQL:",
            paramPrefix = "BIND:",
            mixedPrefix = true,
            maxParseLength = 12345,
            hibernateSqlPrefix = "Hibernate:",
            hibernateParamPrefix = "bind:",
            hibernateEnabled = false,
            jdbcEnabled = false,
            shardingEnabled = false,
        )
        SqlConsoleBridgeFilterProvider.refreshEpContexts(newCfg)

        assertEquals("SQL:", ctx.myBatisParser.sqlPrefix)
        assertEquals("BIND:", ctx.myBatisParser.paramPrefix)
        assertTrue("mixedPrefix 应同步", ctx.myBatisParser.mixedPrefix)
        assertEquals(12345, ctx.myBatisParser.maxParseLength)
        assertEquals("Hibernate:", ctx.hibernateParser.sqlPrefix)
        assertEquals("bind:", ctx.hibernateParser.paramPrefix)
        assertEquals(12345, ctx.hibernateParser.maxParseLength)
        assertFalse("hibernateEnabled 应同步", ctx.hibernateEnabled)
        assertFalse("jdbcEnabled 应同步", ctx.jdbcParser.enabled)
        assertFalse("shardingEnabled 应同步", ctx.shardingEnabled)
    }

    @Test
    fun `同实例重复注册幂等`() {
        val ctx = newCtx()
        SqlConsoleBridgeFilterProvider.registerEpContext(ctx)
        SqlConsoleBridgeFilterProvider.registerEpContext(ctx)
        SqlConsoleBridgeFilterProvider.refreshEpContexts(
            ParserConfig("P1:", "P:", mixedPrefix = false, maxParseLength = 1),
        )
        assertEquals("P1:", ctx.myBatisParser.sqlPrefix)
    }

    @Test
    fun `刷新后新前缀对解析即时生效`() {
        val ctx = newCtx(sqlPrefix = "Preparing:")
        SqlConsoleBridgeFilterProvider.registerEpContext(ctx)
        SqlConsoleBridgeFilterProvider.refreshEpContexts(
            ParserConfig(
                sqlPrefix = "EXEC:",
                paramPrefix = "Parameters:",
                mixedPrefix = false,
                maxParseLength = 20000,
            ),
        )
        // 旧前缀行不再进入解析（Preparing 行缓冲、Parameters 行闭合——配对语义；
        // 严格模式 mixedPrefix=false 按行首精确前缀匹配，行不带日志框架前缀）
        val timePrefix: String? = null
        assertTrue(
            "旧前缀行应不再产出",
            processConsoleLine("Preparing: SELECT 1", ctx, timePrefix, {}).isEmpty(),
        )
        assertTrue(
            "旧前缀参数行应不再闭合条目",
            processConsoleLine("Parameters: 1(Long)", ctx, timePrefix, {}).isEmpty(),
        )
        assertEquals(
            "新前缀行应正常缓冲并闭合条目",
            1,
            processConsoleLine("EXEC: SELECT 2", ctx, timePrefix, {}).size +
                processConsoleLine("Parameters: 2(Long)", ctx, timePrefix, {}).size,
        )
    }
}
