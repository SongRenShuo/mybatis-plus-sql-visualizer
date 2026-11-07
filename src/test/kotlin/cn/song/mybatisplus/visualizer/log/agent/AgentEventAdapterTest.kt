package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.log.CaptureProvenance
import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogSqlParam
import cn.song.mybatisplus.visualizer.log.SqlSource
import cn.song.mybatisplus.visualizer.log.normalizeSqlWhitespace
import cn.song.mybatisplus.visualizer.log.sqlFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AgentEventAdapter] 纯逻辑测试（M56 T-M56-02）：事件→条目映射、参数还原、
 * dedupeKey 与桥通道一致性（接管/回落切换期两通道去重口径统一）。
 */
class AgentEventAdapterTest {

    private fun event(
        sql: String,
        params: List<Triple<Int, String?, String?>> = emptyList(),
        updateCount: Long? = null,
        errorText: String? = null,
        sourceFile: String? = null,
        sourceLine: Int = 0,
        methodKey: String? = null,
        requestTag: String? = null,
        durationNanos: Long = 5_000_000L,
    ) = AgentEvent(
        sql = sql,
        timestamp = 1724567890000L,
        durationNanos = durationNanos,
        updateCount = updateCount,
        errorText = errorText,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
        methodKey = methodKey,
        requestTag = requestTag,
        params = params,
        threadName = "main",
    )

    @Test
    fun 事件解析_全字段() {
        val body = """{"sql":"UPDATE t SET a = ?","ts":1724567890000,"dn":1234567,"u":3,"sf":"a/A.java","sl":9,"mk":"A#do","rt":"req-1","th":"main","p":[[1,"x",null],[2,"7","Long"]]}"""
        val e = AgentEvent.parse(body)!!
        assertEquals("UPDATE t SET a = ?", e.sql)
        assertEquals(1724567890000L, e.timestamp)
        assertEquals(1234567L, e.durationNanos)
        assertEquals(3L, e.updateCount)
        assertEquals("a/A.java", e.sourceFile)
        assertEquals(9, e.sourceLine)
        assertEquals("A#do", e.methodKey)
        assertEquals("req-1", e.requestTag)
        assertEquals(2, e.params.size)
        assertEquals(Triple(1, "x", null as String?), e.params[0])
        assertEquals(Triple(2, "7", "Long"), e.params[1])
    }

    @Test
    fun 事件解析_结构不符与空SQL丢弃() {
        assertNull(AgentEvent.parse("not json"))
        assertNull(AgentEvent.parse("""{"ts":1}"""))
        assertNull(AgentEvent.parse("""{"sql":"  "}"""))
    }

    // ---------- T-SqlListCatchup 券 F2b：事件聚合计数（协议双向兼容） ----------

    @Test
    fun 事件解析_聚合计数_c键大于1时解析() {
        // agent 队列满聚合形态：c 键仅 >1 时写出（Jsons.writeEvent 契约）
        val e = AgentEvent.parse("""{"sql":"INSERT INTO t VALUES (?)","ts":1,"dn":5,"c":7}""")!!
        assertEquals("聚合代表条带出累计执行次数", 7, e.aggregatedCount)
    }

    @Test
    fun 事件解析_聚合计数_旧版agent无c键按1计() {
        val e = AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"dn":1}""")!!
        assertEquals("旧版事件无 c 键 → 按 1 计（协议向后兼容）", 1, e.aggregatedCount)
    }

    @Test
    fun 事件解析_聚合计数_畸形值回落1() {
        // c<=1 / 非数值：按「无聚合」处理，不抛错
        assertEquals(1, AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"c":1}""")!!.aggregatedCount)
        assertEquals(1, AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"c":-3}""")!!.aggregatedCount)
        assertEquals(1, AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"c":"x"}""")!!.aggregatedCount)
    }

    @Test
    fun 事件解析_候选帧_T_AgentSourceJump() {
        val body = """{"sql":"SELECT 1","ts":1,"dn":1,"sf":"com/github/pagehelper/PageInterceptor.java","sl":132,"cands":[["com/example/service/UserServiceImpl.java",36],["com/example/controller/UserController.java",48]]}"""
        val e = AgentEvent.parse(body)!!
        assertEquals(2, e.sourceCandidates.size)
        assertEquals("com/example/service/UserServiceImpl.java" to 36, e.sourceCandidates[0])
        assertEquals("com/example/controller/UserController.java" to 48, e.sourceCandidates[1])
        // 两列旧 agent 协议：无类名列 → candidateClasses 空（可能少于候选数）
        assertEquals(0, e.candidateClasses.size)
    }

    @Test
    fun 事件解析_无候选键_空列表容错() {
        // 旧版 agent 无 cands 键：必须空列表（跳转回退主位置），不得抛错
        val e = AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"dn":1,"sf":"a/A.java","sl":3}""")!!
        assertEquals(0, e.sourceCandidates.size)
        assertEquals(0, e.candidateClasses.size)
    }

    @Test
    fun 事件解析_候选畸形项跳过_不污染() {
        // 四列/含 null 类名形态混入：跳过语义不变（T-PS4-14 解析合一后仍逐项容错）
        val body = """{"sql":"SELECT 1","ts":1,"dn":1,"cands":[["ok/A.java",5,"com.ok.A","m1"],["",9,"com.x","m"],["noLine.java"],123,["null/B.java",7,null,"m2"]]}"""
        val e = AgentEvent.parse(body)!!
        assertEquals("空文件名/非数组项/非数组元素应跳过", 3, e.sourceCandidates.size)
        assertEquals("ok/A.java" to 5, e.sourceCandidates[0])
        assertEquals("缺行号项保留且行号归 0", "noLine.java" to 0, e.sourceCandidates[1])
        assertEquals("null/B.java" to 7, e.sourceCandidates[2])
        assertEquals("类名缺失项不入名单、有效项按序入名单", listOf("com.ok.A"), e.candidateClasses)
    }

    @Test
    fun `T_PS4_14_解析合一_四列旧协议兼容与字段对齐`() {
        // 四列协议（file/line/className/method）：sourceCandidates/candidateClasses 从同一次解析派生
        // （原实现同一 JSON 数组解析三遍）；第四列 method 协议遗留零消费方，残留不串位不抛错
        val body = """{"sql":"SELECT 1","ts":1,"dn":1,"cands":[["a/S.java",10,"com.a.S","doQuery"],["b/C.java",20,"com.b.C","onClick"]]}"""
        val e = AgentEvent.parse(body)!!
        assertEquals(listOf("a/S.java" to 10, "b/C.java" to 20), e.sourceCandidates)
        assertEquals("类名与候选栈序对齐", listOf("com.a.S", "com.b.C"), e.candidateClasses)
    }

    @Test
    fun 映射_来源AGENT_耗时纳秒转毫秒_四新字段() {
        val entry = AgentEventAdapter.toEntry(
            event(
                "UPDATE t SET a = ?",
                params = listOf(Triple(1, "x", "String")),
                updateCount = 1L,
                errorText = "SQLException: boom",
                sourceFile = "a/A.java",
                sourceLine = 9,
                methodKey = "A#do",
                requestTag = "req-1",
            ),
        )
        assertEquals(SqlSource.AGENT, entry.source)
        assertEquals("通道归因恒为 AGENT（O-147 W0 打标）", CaptureProvenance.AGENT, entry.captureProvenance)
        assertEquals(5L, entry.executionTimeMs)
        assertEquals(1L, entry.updateCount)
        assertEquals("SQLException: boom", entry.errorText)
        assertEquals("a/A.java", entry.sourceFile)
        assertEquals(9, entry.sourceLine)
        assertEquals("A#do", entry.mapperStatement) // T-M56-06 聚焦键经 mapperStatement 字段承载
        assertEquals("req-1", entry.requestTag)
    }

    @Test
    fun 参数还原_字符串加引号_数值直出_缺失不替换() {
        // 字符串值加引号；数值类型直出
        val restored = AgentEventAdapter.restore(
            "UPDATE t SET a = ? WHERE id = ?",
            listOf(LogSqlParam("x'y", "String"), LogSqlParam("7", "Long")),
        )
        assertEquals("UPDATE t SET a = 'x''y' WHERE id = 7", restored)
        // 占位符数 != 参数数 → 原样返回（防错位）
        val mismatch = AgentEventAdapter.restore("SELECT ?", listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long")))
        assertEquals("SELECT ?", mismatch)
        // null 参数 → NULL
        val nullParam = AgentEventAdapter.restore("INSERT INTO t VALUES (?)", listOf(LogSqlParam(null, "NULL")))
        assertEquals("INSERT INTO t VALUES (NULL)", nullParam)
        // 引号字面量内的 ? 不参与替换
        val literal = AgentEventAdapter.restore(
            "SELECT * FROM t WHERE name = 'a?b' AND id = ?",
            listOf(LogSqlParam("9", "Long")),
        )
        assertEquals("SELECT * FROM t WHERE name = 'a?b' AND id = 9", literal)
    }

    @Test
    fun 数值判定常量_行为等价防回潮() {
        // T-PS4-04：isNumericType/isNumericLike 两条正则提 object 级常量（renderParam 逐参数调用，
        // agent 会话读线程热路径）。常量为 private，本用例按行为等价钉住语义防回潮：
        // 回退「每次 matches(Regex(...)) 现场编译」行为不变测试仍绿（性能项），但模式被改坏必红。
        fun render(value: String?, type: String?) =
            AgentEventAdapter.restore("SELECT ?", listOf(LogSqlParam(value, type)))
        // 数值形态 + 数值类型 → 直出（整数/负数/小数全形态）
        assertEquals("SELECT 7", render("7", "Long"))
        assertEquals("SELECT -3", render("-3", "Integer"))
        assertEquals("SELECT 1.5", render("1.5", "Double"))
        assertEquals("SELECT 0.25", render("0.25", "BigDecimal"))
        // 数值形态 + String 类型 → 加引号（类型判定拦住）
        assertEquals("SELECT '7'", render("7", "String"))
        assertEquals("SELECT '1.5'", render("1.5", null))
        // 非数值形态 + 数值类型 → 加引号（形态判定拦住）
        assertEquals("SELECT 'abc'", render("abc", "Long"))
        assertEquals("SELECT '1,5'", render("1,5", "Double"))
        assertEquals("SELECT '+1'", render("+1", "Long"))
        assertEquals("SELECT '1e3'", render("1e3", "Double"))
        assertEquals("SELECT ' 7'", render(" 7", "Long"))
    }

    @Test
    fun 日期族参数还原为ANSI字面量() {
        // T-DateLiteralBackfill：agent 还原链与 LogSqlRestorer 同口径（ISO T 归一 + ANSI 包装）
        val restored = AgentEventAdapter.restore(
            "UPDATE t SET update_time = ? WHERE id = ?",
            listOf(LogSqlParam("2026-11-07T13:20:14.417", "LocalDateTime"), LogSqlParam("1", "Long")),
        )
        assertEquals(
            "UPDATE t SET update_time = TIMESTAMP '2026-11-07 13:20:14.417' WHERE id = 1",
            restored,
        )
    }

    @Test
    fun SQLServer方言_日期族回落引号() {
        // T-SQL 不支持 ANSI 类型字面量；SQL Server 的 ISO 8601 引号串本来就能隐式转换
        val restored = AgentEventAdapter.restore(
            "UPDATE t SET update_time = ?",
            listOf(LogSqlParam("2026-11-07T13:20:14.417", "LocalDateTime")),
            dialectName = "SQL Server",
        )
        assertEquals("UPDATE t SET update_time = '2026-11-07T13:20:14.417'", restored)
    }

    @Test
    fun 字符串日期形值_仍加引号() {
        // 无日期族类型标注 → 不能启发式包装（字符串列语义）
        val restored = AgentEventAdapter.restore(
            "SELECT * FROM t WHERE code = ?",
            listOf(LogSqlParam("2026-11-07", "String")),
        )
        assertEquals("SELECT * FROM t WHERE code = '2026-11-07'", restored)
    }

    @Test
    fun dedupeKey与桥通道一致_同一SQL同指纹() {
        // 口径：dedupeKey 只管面板去重（两侧**手写同一 restoredSql** 时同指纹）。
        // 注意本用例不是「跨通道集成」守卫——它不经过控制台解析链，故过去的失配（布尔参数渲染、
        // 注释内 `?`、参数个数守卫）它一概发现不了；日志时间配对的真实守卫见
        // `AgentLogTimeParityTest`（T-AgentLogTime 立，键已与 dedupeKey 解耦）。
        // agent 通道条目
        val agentEntry = AgentEventAdapter.toEntry(event("SELECT * FROM user WHERE id = 42"))
        // 桥通道同 SQL 条目（restoredSql 相同）
        val bridgeEntry = LogSqlEntry(
            rawSql = "SELECT * FROM user WHERE id = ?",
            restoredSql = "SELECT * FROM user WHERE id = 42",
            params = emptyList(),
            source = SqlSource.MYBATIS,
        )
        assertEquals(bridgeEntry.dedupeKey, agentEntry.dedupeKey)
        assertEquals(sqlFingerprint(normalizeSqlWhitespace("SELECT * FROM user WHERE id = 42")), agentEntry.dedupeKey)
    }

    @Test
    fun `事件解析_url键与缺失容错`() {
        val withUrl = AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"dn":1,"url":"jdbc:dm://localhost:5236"}""")!!
        assertEquals("jdbc:dm://localhost:5236", withUrl.url)
        val withoutUrl = AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"dn":1}""")!!
        assertNull("旧版 agent 无 url 键 → null 容错", withoutUrl.url)
    }

    @Test
    fun `映射_url充实目标库标注_缺失为null`() {
        // R32 弱形态：databaseName 从「纯方言名」升级为「方言名 · 库名@host:port」url 解析真值
        val dm = AgentEventAdapter.toEntry(event("SELECT 1").copy(url = "jdbc:dm://localhost:5236"))
        assertEquals("DM（达梦） · localhost:5236", dm.databaseName)
        val mysql = AgentEventAdapter.toEntry(event("SELECT 1").copy(url = "jdbc:mysql://localhost/db"))
        assertEquals("MySQL · db@localhost", mysql.databaseName)
        val unknown = AgentEventAdapter.toEntry(event("SELECT 1").copy(url = "jdbc:weirddb://x/db9"))
        assertEquals("未知方言但有连接事实 → 输出真值", "db9@x", unknown.databaseName)
        val legacy = AgentEventAdapter.toEntry(event("SELECT 1"))
        assertNull("旧版 agent 无 url → null", legacy.databaseName)
    }

    @Test
    fun `R32_条目标注不含凭据`() {
        // 安全红线：url 带 user/password 参数（假凭据夹具）→ 条目字段绝不出现
        val e = AgentEventAdapter.toEntry(
            event("SELECT 1").copy(url = "jdbc:mysql://dbhost.example:3306/shop?user=app&password=s3cret"),
        )
        assertEquals("MySQL · shop@dbhost.example:3306", e.databaseName)
        assertFalse("password 值不得入条目", e.databaseName!!.contains("s3cret"))
        assertFalse("user 值不得入条目", e.databaseName!!.contains("app"))
    }
}
