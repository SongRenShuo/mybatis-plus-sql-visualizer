package cn.song.mybatisplus.visualizer.log

import com.intellij.execution.filters.Filter
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-ConsoleFlushAlign 回归测试：MyBatis / Hibernate 解析器接入与 [JdbcTemplateLogParser.flushSettled]
 * 同款的静默冲刷（T-ConsolePendingFlush 只给了 JdbcTemplate，R10 补了 ShardingSphere）。
 *
 * 两解析器的实际待冲刷面与 JdbcTemplate 不同源（正常参数化语句在参数行到达即闭合）：
 * - MyBatis：无参语句（MyBatis 不打 `Parameters:` 行）要等同线程下一条语句行才闭合；日志截断尾
 *   「`Preparing:` 已见 / `Parameters:` 未达」永无闭合点；iBatis 挂起组（等 `Types:`）不归桶管，
 *   按组内自带 lastTouchMs 逐组判静默（无条件 flushIbatis 会把「Parameters → Types」撕开、丢类型）。
 * - Hibernate：绑定行缺位（TRACE 关）时只等同线程下一条语句行闭合；截断尾「SQL 行已见、后继行未达」。
 *
 * 用例分层（构造手法照抄 [ConsoleSettleFlushTest]）：
 * - 纯逻辑（[processConsoleLine] 直驱 + 注入时钟）：settle 产出、阈值判别力、参数不被截断、
 *   冲刷后真实参数行到达不重复闭合、iBatis 参数组 Types 缺行静默产出 / 阈值内 Types 不被冲撕；
 * - 桥桩控制台（[SqlLogConsoleBridge.attachFilter]）：轮询静默冲刷产出且不重复。
 *
 * 反向验证：摘掉两解析器 flushSettled（方法体直返 0），settle 产出类用例必红。
 *
 * 行文本照抄控制台现场（Spring Boot 默认日志格式）。
 */
class MyBatisHibernateSettleFlushTest : BasePlatformTestCase() {

    /** 可控时钟（纯逻辑用例的确定性来源；阈值 1000ms 见两解析器 DEFAULT_SETTLE_MS）。 */
    private var fakeNow = 0L

    override fun setUp() {
        super.setUp()
        fakeNow = 0L
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
    }

    override fun tearDown() {
        SqlCaptureGate.paused.set(false)
        SqlCaptureGate.epActive = false
        super.tearDown()
    }

    // ---------- 现场行文本（Spring Boot 默认格式） ----------

    private fun springBootLine(thread: String, text: String, level: String = "DEBUG", logger: String): String =
        "2026-11-11 09:00:00.000 $level 72660 --- [$thread] $logger : $text"

    private fun mybatisPreparing(thread: String, sql: String): String =
        springBootLine(thread, "==>  Preparing: $sql", logger = "c.s.mapper.UserMapper.listAll")

    private fun mybatisParams(thread: String, text: String): String =
        springBootLine(thread, "==> Parameters: $text", logger = "c.s.mapper.UserMapper.listAll")

    private fun hibernateSql(thread: String, sql: String): String =
        springBootLine(thread, "Hibernate: $sql", logger = "org.hibernate.SQL")

    private fun hibernateBinding(thread: String, text: String): String =
        springBootLine(thread, text, level = "TRACE", logger = "org.hibernate.type.descriptor.sql.BasicBinder")

    /** iBatis 2.x 三行组的行首（log4j2 pattern，logger 名被缩成 `j.s.*`，同 IbatisLogParseTest 口径）。 */
    private fun ibatisLine(text: String): String =
        springBootLine("main", text, logger = "j.s.PreparedStatement")

    /** 与 SQL 无关的噪声行（另一线程，触发逐行冲刷用，同 ConsoleSettleFlushTest 口径）。 */
    private val noiseLine =
        springBootLine("nio-8086-exec-9", "Initializing Servlet 'dispatcherServlet'", level = "INFO", logger = "o.s.web.servlet.DispatcherServlet")

    private val mybatisSql1 = "SELECT id, name FROM biz_user WHERE id = ?"

    private val mybatisSql2 = "SELECT id FROM biz_order WHERE status = ?"

    private val hibernateSql1 = "select u.id from t_user u where u.name = ?"

    private val hibernateSql2 = "select o.id from t_order o where o.status = ?"

    // ---------- 纯逻辑：processConsoleLine 直驱（注入时钟） ----------

    private fun newContext(): BridgeLineContext {
        val cfg = ParserConfig("Preparing:", "Parameters:", true, 20000)
        return BridgeLineContext(
            myBatisParser = MyBatisLogParser(
                sqlPrefix = cfg.sqlPrefix,
                paramPrefix = cfg.paramPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
                clock = { fakeNow },
            ),
            hibernateParser = HibernateLogParser(
                sqlPrefix = cfg.hibernateSqlPrefix,
                paramPrefix = cfg.hibernateParamPrefix,
                mixedPrefix = cfg.mixedPrefix,
                maxParseLength = cfg.maxParseLength,
                clock = { fakeNow },
            ),
            jdbcParser = JdbcTemplateLogParser(
                enabled = cfg.jdbcEnabled,
                maxParseLength = cfg.maxParseLength,
                clock = { fakeNow },
            ),
            configProvider = { cfg },
            hibernateEnabled = cfg.hibernateEnabled,
        )
    }

    private fun feed(ctx: BridgeLineContext, vararg lines: String): List<LogSqlEntry> =
        lines.flatMap { processConsoleLine(it, ctx, null, {}) }

    /** MyBatis 主场景：两条无参语句分属 exec-1/exec-2，MyBatis 不打 Parameters 行、线程此后不再有行。 */
    fun testMyBatis逐行冲刷_两线程各一条无参语句_静默后都产出() {
        val ctx = newContext()
        assertTrue("exec-1 语句行本身不产出（等参数行/等冲刷）", feed(ctx, mybatisPreparing("nio-8086-exec-1", mybatisSql1)).isEmpty())
        assertTrue("exec-2 语句行本身不产出", feed(ctx, mybatisPreparing("nio-8086-exec-2", mybatisSql2)).isEmpty())
        assertEquals("未满静默阈值不得提前产出（此时参数行仍有到达可能）", 0, ctx.myBatisParser.takeEntries().size)

        fakeNow = MyBatisLogParser.DEFAULT_SETTLE_MS
        val out = feed(ctx, noiseLine)
        assertEquals("两条各自独占线程的无参语句都被静默冲刷交出", 2, out.size)
        assertEquals("条目内容与顺序（分桶插入序）", listOf(mybatisSql1, mybatisSql2), out.map { it.rawSql })
        assertTrue("来源标记为 MYBATIS", out.all { it.source == SqlSource.MYBATIS })
    }

    /** 参数行在阈值内到达 → 走既有即时闭合（分支 4），条目带参数；随后冲刷不重复产出。 */
    fun testMyBatis参数行在阈值内到达_正常闭合_冲刷不重复() {
        val ctx = newContext()
        assertTrue(feed(ctx, mybatisPreparing("nio-8086-exec-1", mybatisSql1)).isEmpty())
        fakeNow = MyBatisLogParser.DEFAULT_SETTLE_MS / 2
        val out = feed(ctx, mybatisParams("nio-8086-exec-1", "7(Long)"))
        assertEquals("Parameters 行到达即闭合（原语义不变，不依赖冲刷）", 1, out.size)
        assertEquals("7", out[0].params[0].value)
        assertTrue("还原 SQL 含参数值", out[0].restoredSql.contains("7"))

        fakeNow = MyBatisLogParser.DEFAULT_SETTLE_MS * 2
        assertTrue("桶已空，冲刷不得重复产出", feed(ctx, noiseLine).isEmpty())
        assertEquals("直调冲刷亦无重复", 0, ctx.myBatisParser.flushSettled())
    }

    /** 冲刷交出未还原条目后，迟到的真实参数行不再重复闭合（按无上下文丢弃留痕）。 */
    fun testMyBatis冲刷后真实参数行到达_不再重复闭合() {
        val ctx = newContext()
        assertTrue(feed(ctx, mybatisPreparing("nio-8086-exec-1", mybatisSql1)).isEmpty())
        fakeNow = MyBatisLogParser.DEFAULT_SETTLE_MS + 500
        val out = feed(ctx, noiseLine)
        assertEquals("静默冲刷交出 1 条", 1, out.size)
        assertEquals("参数未达 → 条目为未还原形态（不伪造参数）", 0, out[0].params.size)
        assertTrue("rawSql 保留占位符", out[0].rawSql.contains("?"))

        assertTrue("迟到的参数行不得再闭合出第二条", feed(ctx, mybatisParams("nio-8086-exec-1", "7(Long)")).isEmpty())
        assertEquals("累计仍 1 条（无重复）", 1, out.size + ctx.myBatisParser.takeEntries().size)
    }

    /** iBatis 挂起参数组（等 `Types:`）不归桶管：Types 缺行时按组内 lastTouchMs 静默后按无类型产出。 */
    fun testMyBatis_iBatis参数组Types缺行_静默后按无类型产出() {
        val ctx = newContext()
        feed(ctx, ibatisLine("{pstm-100021} Executing Statement: INSERT INTO biz_log VALUES (?, ?)"))
        feed(ctx, ibatisLine("{pstm-100021} Parameters: [7, abc]"))
        assertEquals("Parameters 行不闭合（等 Types 并入，既有语义不变）", 0, ctx.myBatisParser.takeEntries().size)

        fakeNow = MyBatisLogParser.DEFAULT_SETTLE_MS + 500
        val out = feed(ctx, noiseLine)
        assertEquals("挂起组静默后产出 1 条", 1, out.size)
        assertTrue("来源标记为 IBATIS", out.all { it.source == SqlSource.IBATIS })
        assertEquals("参数保留（Types 缺行按无类型启发式渲染）", 2, out[0].params.size)

        assertTrue("迟到的 Types 行不得再闭合出第二条", feed(ctx, ibatisLine("{pstm-100021} Types: [java.lang.Long, java.lang.String]")).isEmpty())
        assertEquals("直调冲刷亦无重复", 0, ctx.myBatisParser.flushSettled())
    }

    /** 冲刷防误伤面：`Types:` 在阈值内到达 → 正常并入类型闭合；逐行冲刷永不撕开 1ms 量级的参数组。 */
    fun testMyBatis_iBatis参数组Types在阈值内到达_不被冲刷截断() {
        val ctx = newContext()
        feed(ctx, ibatisLine("{pstm-100022} Executing Statement: SELECT * FROM t WHERE id = ?"))
        feed(ctx, ibatisLine("{pstm-100022} Parameters: [00340025002200200006]"))
        fakeNow = MyBatisLogParser.DEFAULT_SETTLE_MS / 2
        val out = feed(ctx, ibatisLine("{pstm-100022} Types: [java.lang.Long]"))
        assertEquals("Types 行到达即按位并入闭合", 1, out.size)
        assertEquals("类型已并入（纯数字串不裸出）", "java.lang.Long", out[0].params[0].type)

        fakeNow = MyBatisLogParser.DEFAULT_SETTLE_MS * 2
        assertTrue("挂起组已清空，冲刷不得重复产出", feed(ctx, noiseLine).isEmpty())
    }

    /** Hibernate 主场景：两条 logger 形态语句（无绑定行，TRACE 关），线程此后不再有行。 */
    fun testHibernate逐行冲刷_两线程各一条语句_静默后都产出() {
        val ctx = newContext()
        assertTrue("exec-1 语句行本身不产出（等绑定行/等冲刷）", feed(ctx, hibernateSql("nio-8086-exec-1", hibernateSql1)).isEmpty())
        assertTrue("exec-2 语句行本身不产出", feed(ctx, hibernateSql("nio-8086-exec-2", hibernateSql2)).isEmpty())
        assertEquals("未满静默阈值不得提前产出", 0, ctx.hibernateParser.takeEntries().size)

        fakeNow = HibernateLogParser.DEFAULT_SETTLE_MS
        val out = feed(ctx, noiseLine)
        assertEquals("两条各自独占线程的语句都被静默冲刷交出", 2, out.size)
        assertEquals("条目内容与顺序（分桶插入序）", listOf(hibernateSql1, hibernateSql2), out.map { it.rawSql })
        assertTrue("来源标记为 HIBERNATE", out.all { it.source == SqlSource.HIBERNATE })
    }

    /** 绑定行在阈值内到达 → 追加参数（保持 COLLECTING）；条目随后由冲刷闭合，参数不丢。 */
    fun testHibernate参数行在阈值内到达_不被冲刷截断() {
        val ctx = newContext()
        assertTrue(feed(ctx, hibernateSql("nio-8086-exec-1", hibernateSql1)).isEmpty())
        fakeNow = HibernateLogParser.DEFAULT_SETTLE_MS / 2
        assertTrue("绑定行只追加参数，不闭合", feed(ctx, hibernateBinding("nio-8086-exec-1", "binding parameter [1] as [INTEGER] - [7]")).isEmpty())

        fakeNow = HibernateLogParser.DEFAULT_SETTLE_MS + 500
        val out = feed(ctx, noiseLine)
        assertEquals("冲刷闭合 1 条", 1, out.size)
        assertEquals("绑定参数已随条目还原（未被冲刷截断）", 1, out[0].params.size)
        assertEquals("7", out[0].params[0].value)
        assertTrue("还原 SQL 含参数值", out[0].restoredSql.contains("7"))
    }

    /** 冲刷交出零参条目后，迟到的真实绑定行不再重复闭合（无上下文丢弃，不产出第二条）。 */
    fun testHibernate冲刷后真实参数行到达_不再重复闭合() {
        val ctx = newContext()
        assertTrue(feed(ctx, hibernateSql("nio-8086-exec-1", hibernateSql1)).isEmpty())
        fakeNow = HibernateLogParser.DEFAULT_SETTLE_MS + 500
        val out = feed(ctx, noiseLine)
        assertEquals("静默冲刷交出 1 条（零参条目随 takeEntries 交出）", 1, out.size)
        assertEquals("绑定行未达 → 条目零参（不伪造参数）", 0, out[0].params.size)

        assertTrue("迟到的绑定行不得再闭合出第二条", feed(ctx, hibernateBinding("nio-8086-exec-1", "binding parameter [1] as [INTEGER] - [7]")).isEmpty())
        assertEquals("累计仍 1 条（无重复）", 1, out.size + ctx.hibernateParser.takeEntries().size)
    }

    // ---------- 桥桩控制台：轮询静默冲刷（照抄 ConsoleSettleFlushTest 模板） ----------

    /** 反射面桩控制台：记录挂载的 filter；getEditor 返回 null（本组用例聚焦产出时机，重放守卫另测）。 */
    private class FakeConsole {
        val attached = mutableListOf<Any>()
        fun addMessageFilter(filter: Filter) {
            attached += filter
        }

        @Suppress("unused")
        fun getEditor(): Any? = null
    }

    private fun newBridgeFilter(buffer: SqlLogEntryBuffer): Pair<SqlLogConsoleBridge, Pair<Filter, FakeConsole>> {
        val bridge = SqlLogConsoleBridge(buffer)
        val console = FakeConsole()
        bridge.attachFilter(console)
        val filter = bridge.attachedConsoles[console]!!.first as Filter
        return bridge to (filter to console)
    }

    /** MyBatis：应用安静下来（无参语句后一行都不再打）→ 轮询静默冲刷是唯一出路。 */
    fun testMyBatis桥轮询冲刷_应用安静后仍产出() {
        val buffer = SqlLogEntryBuffer().apply { dedupeEnabled = false }
        val (bridge, pair) = newBridgeFilter(buffer)
        val filter = pair.first

        filter.applyFilter(mybatisPreparing("nio-8086-exec-1", mybatisSql1), 200)
        filter.applyFilter(mybatisPreparing("nio-8086-exec-2", mybatisSql2), 400)
        assertEquals("轮询前零产出（行已到桥，条目停在各自线程桶里）", 0, buffer.snapshot().size)

        // 走真实轮询入口（scanAll）而不是直调冲刷方法——验的是**接线**：定时器那一拍是否真会冲刷。
        // 静默阈值默认 1s（DEFAULT_SETTLE_MS），故先等待过阈值再走一拍。
        Thread.sleep(MyBatisLogParser.DEFAULT_SETTLE_MS + 150)
        bridge.scanAll()
        assertEquals("静默冲刷交出 2 条", 2, buffer.snapshot().size)

        bridge.scanAll()
        assertEquals("再次轮询不重复产出（桶已清空）", 2, buffer.snapshot().size)
        assertEquals("显式时点直调亦无重复", 0, bridge.flushSettledEntries(System.currentTimeMillis() + 5_000))
    }

    /** Hibernate：应用安静下来（语句后无绑定行、无线程后继行）→ 轮询静默冲刷是唯一出路。 */
    fun testHibernate桥轮询冲刷_应用安静后仍产出() {
        val buffer = SqlLogEntryBuffer().apply { dedupeEnabled = false }
        val (bridge, pair) = newBridgeFilter(buffer)
        val filter = pair.first

        filter.applyFilter(hibernateSql("nio-8086-exec-1", hibernateSql1), 200)
        filter.applyFilter(hibernateSql("nio-8086-exec-2", hibernateSql2), 400)
        assertEquals("轮询前零产出（行已到桥，条目停在各自线程桶里）", 0, buffer.snapshot().size)

        Thread.sleep(HibernateLogParser.DEFAULT_SETTLE_MS + 150)
        bridge.scanAll()
        assertEquals("静默冲刷交出 2 条", 2, buffer.snapshot().size)

        bridge.scanAll()
        assertEquals("再次轮询不重复产出（桶已清空）", 2, buffer.snapshot().size)
        assertEquals("显式时点直调亦无重复", 0, bridge.flushSettledEntries(System.currentTimeMillis() + 5_000))
    }
}
