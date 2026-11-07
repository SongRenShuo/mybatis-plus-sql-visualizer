package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志条目 → 预览参数识别单元测试（T-M42）：`?` 数 == 参数数 ⇔ 匹配识别；
 * 参数行来源按序 `param1..N`、值预填日志参数值、类型 → 引号提示；其余回退现状。
 */
class LogEntryPreviewRecognizerTest {

    @Test
    fun `Sharding歧义提示来源正确且路由原文贯穿所有预览分支`() {
        val actual = "ds_0 ::: SELECT * FROM t_0 WHERE a = ? ::: [BOOK,100]\n" +
            "ds_1 ::: SELECT * FROM t_1 WHERE a = ? ::: [BOOK,100]"
        val raw = "SELECT * FROM t WHERE a = ?"
        val missing = LogSqlEntry(rawSql = raw, restoredSql = raw, params = emptyList(),
            source = SqlSource.SHARDINGSPHERE, actualSql = actual)
        val unresolved = missing.toPreviewResult(false, false)
        assertEquals(actual, unresolved.actualSql)
        assertTrue(unresolved.warning!!.contains("ShardingSphere"))
        assertFalse(unresolved.warning!!.contains("Hibernate"))
        assertFalse(unresolved.warning!!.contains("TRACE"))
        val stale = missing.copy(params = listOf(LogSqlParam("BOOK,100", null)),
            restoredSql = "SELECT * FROM t WHERE a = 'BOOK,100'")
        assertFalse(stale.toRecognizedPreview().recognized)
        assertTrue(stale.toRecognizedPreview().initialValues.isEmpty())
        val bound = stale.toPreviewResult(false, false)
        assertEquals(actual, bound.actualSql)
        assertTrue(bound.initialParamValues.isEmpty())
        assertTrue(bound.paramHints.isEmpty())
        assertTrue(bound.unresolvedParams.isEmpty())
        assertEquals(withTerminator(raw), bound.sql)
        assertEquals(raw, bound.compactSql)
        assertTrue(bound.warning!!.contains("ShardingSphere"))
        assertFalse(bound.sql.contains("ds_0"))
        val literal = missing.copy(rawSql = "SELECT * FROM t WHERE a = 1", restoredSql = "SELECT * FROM t WHERE a = 1")
        assertEquals(actual, literal.toPreviewResult(false, true).actualSql)
        assertEquals(actual, literal.toPreviewResult(false, false).actualSql)
        assertNull(missing.copy(source = SqlSource.HIBERNATE).toPreviewResult(false, false).actualSql)
    }

    private fun entry(
        rawSql: String,
        params: List<LogSqlParam>,
        restoredSql: String = "",
    ) = LogSqlEntry(rawSql = rawSql, restoredSql = restoredSql.ifEmpty { rawSql }, params = params)

    // ------------------------------------------------------------------
    // 匹配识别
    // ------------------------------------------------------------------

    @Test
    fun `占位符数与参数数一致时识别成功`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE username = ? AND id = ?",
            params = listOf(LogSqlParam("admin", "String"), LogSqlParam("10", "Long")),
        ).toRecognizedPreview()
        assertTrue("应识别成功", recognized.recognized)
        assertEquals("参数来源按 `?` 出现顺序", listOf("param1", "param2"), recognized.unresolvedParams)
        assertEquals("值按序预填", mapOf("param1" to "admin", "param2" to "10"), recognized.initialValues)
        assertEquals("sql 应为 rawSql", "SELECT * FROM t WHERE username = ? AND id = ?", recognized.sql)
        assertEquals("compactSql 应为 rawSql", "SELECT * FROM t WHERE username = ? AND id = ?", recognized.compactSql)
    }

    @Test
    fun `类型映射为引号提示`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?",
            params = listOf(
                LogSqlParam("abc", "String"),
                LogSqlParam("10", "Long"),
                LogSqlParam("true", "Boolean"),
            ),
        ).toRecognizedPreview()
        assertEquals(
            "String 引号 / 数值·布尔原样",
            listOf(true, false, false),
            recognized.paramHints.map { it.quote },
        )
    }

    @Test
    fun `无类型标注提示为 null 走面板启发式`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            params = listOf(LogSqlParam("abc", null)),
        ).toRecognizedPreview()
        assertEquals(listOf(null as Boolean?), recognized.paramHints.map { it.quote })
    }

    @Test
    fun `时间等未知类型标注保守引号`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            params = listOf(
                LogSqlParam("2026-08-16 12:00:00.0", "Timestamp"),
                LogSqlParam("ACTIVE", "StatusEnum"),
            ),
        ).toRecognizedPreview()
        assertEquals(listOf(true, true), recognized.paramHints.map { it.quote })
    }

    @Test
    fun `日期族类型标注带temporal_字符串日期形值不带`() {
        // T-DateLiteralBackfill：类型是日期族才置 temporal；字符串列的日期形值不能被误包装
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            params = listOf(
                LogSqlParam("2026-11-07T13:20:14.417", "LocalDateTime"),
                LogSqlParam("2026-11-07", "String"),
            ),
        ).toRecognizedPreview()
        assertEquals(listOf(true, false), recognized.paramHints.map { it.temporal })
    }

    // ------------------------------------------------------------------
    // 回退（未识别）
    // ------------------------------------------------------------------

    @Test
    fun `参数为空时回退`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            params = emptyList(),
        ).toRecognizedPreview()
        assertFalse("参数为空应回退", recognized.recognized)
        assertTrue(recognized.unresolvedParams.isEmpty())
        assertTrue(recognized.initialValues.isEmpty())
        assertTrue(recognized.paramHints.isEmpty())
    }

    @Test
    fun `参数多于占位符时回退`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long")),
        ).toRecognizedPreview()
        assertFalse("参数多于占位符应回退", recognized.recognized)
    }

    @Test
    fun `参数少于占位符时回退`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            params = listOf(LogSqlParam("1", "Long")),
        ).toRecognizedPreview()
        assertFalse("参数少于占位符应回退", recognized.recognized)
    }

    @Test
    fun `无占位符无参数纯静态 SQL 回退`() {
        val recognized = entry(
            rawSql = "SELECT 1 FROM t",
            params = emptyList(),
        ).toRecognizedPreview()
        assertFalse("纯静态 SQL 应回退", recognized.recognized)
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    fun `null 参数值预填空串`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            params = listOf(LogSqlParam(null, null)),
        ).toRecognizedPreview()
        assertTrue(recognized.recognized)
        assertEquals(mapOf("param1" to ""), recognized.initialValues)
        assertEquals(listOf(null as Boolean?), recognized.paramHints.map { it.quote })
    }

    @Test
    fun `字符串字面量内问号不计数不回填`() {
        // T-HibDualCapture：`'what?'` 的 `?` 不是占位符（旧口径会计入，与回填错位一格）——
        // 计数与还原共用 placeholderPositions 口径，2 个 `?` 配 2 个参数 ⇔ 识别成功
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE note = 'what?' AND a = ? AND b = ?",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long")),
        ).toRecognizedPreview()
        assertTrue("字面量内 ? 不计入占位符", recognized.recognized)
        assertEquals(listOf("param1", "param2"), recognized.unresolvedParams)
        assertEquals(
            mapOf("param1" to "1", "param2" to "2"),
            recognized.initialValues,
        )
        // 还原同口径：字面量保持原样，仅真实占位符被替换
        val restored = LogSqlRestorer().restore(
            "SELECT * FROM t WHERE note = 'what?' AND a = ? AND b = ?",
            listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long")),
        )
        assertEquals("'what?' 原样保留", "SELECT * FROM t WHERE note = 'what?' AND a = 1 AND b = 2", restored)
    }

    @Test
    fun `注释与转义引号内的问号不计数`() {
        // `--` 行注释 / 块注释 / `''` 转义内的 ? 均非占位符；PG `?|` 与 WHERE 条件算两个
        assertEquals(2, countPlaceholders("-- 问?号\nSELECT a ?| b /* 占?位 */ FROM t WHERE note = '它''?' AND c = ?"))
        assertEquals(0, countPlaceholders("SELECT 'a?b'"))
        assertEquals(2, countPlaceholders("SELECT ? WHERE x = ?"))
    }

    @Test
    fun `IN 列表多占位符按序识别`() {
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE id IN (?, ?, ?)",
            params = listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long"), LogSqlParam("3", "Long")),
        ).toRecognizedPreview()
        assertTrue(recognized.recognized)
        assertEquals(listOf("param1", "param2", "param3"), recognized.unresolvedParams)
        assertEquals(
            mapOf("param1" to "1", "param2" to "2", "param3" to "3"),
            recognized.initialValues,
        )
        assertTrue(recognized.paramHints.all { it.quote == false })
    }

    // ------------------------------------------------------------------
    // toPreviewResult 统一识别通道（T-HistParam F3）
    // ------------------------------------------------------------------

    @Test
    fun `toPreviewResult 三级分流语义`() {
        // 1. 占位符匹配识别：参数行 + 预填 + 无警告
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE username = ?",
            params = listOf(LogSqlParam("admin", "String")),
        ).toPreviewResult(formatSql = false, literalRecovery = true)
        assertEquals(listOf("param1"), recognized.unresolvedParams)
        assertEquals(mapOf("param1" to "admin"), recognized.initialParamValues)
        assertNull("识别成功无警告", recognized.warning)

        // 2. 零占位符零字面量：回退现状、无警告（纯静态 SQL）
        val static = entry(rawSql = "SELECT 1 FROM t", params = emptyList())
            .toPreviewResult(formatSql = false, literalRecovery = true)
        assertTrue(static.unresolvedParams.isEmpty())
        assertNull("零 ? 回退无警告", static.warning)

        // 3. 有占位符但缺绑定行：现状警告分流保留（不进字面量恢复）
        val noBinding = entry(rawSql = "SELECT * FROM t WHERE a = ?", params = emptyList())
            .toPreviewResult(formatSql = false, literalRecovery = true)
        assertTrue(noBinding.unresolvedParams.isEmpty())
        assertNotNull("缺绑定行应有引导警告", noBinding.warning)
    }

    // ------------------------------------------------------------------
    // T-DdlLogPreview S1（20261006 第三轮走查）：DDL 字面量恢复门禁
    // ------------------------------------------------------------------

    /**
     * DDL 的字符串是**列注释/默认值**，不是可编辑参数——门禁后不得产生参数行，且预览 SQL 必须
     * 保留原文（占位符化会让 DDL 语法不合法：用户实测 `CREATE TABLE … COMMENT '主键'` 曾被恢复出
     * param1..param10，执行必报错）。反向验证：摘掉 `LogEntryPreviewRecognizer` 的语句类型门禁即红。
     */
    @Test
    fun `DDL 不产字面量参数且预览 SQL 保留原文`() {
        val ddl = entry(
            rawSql = "CREATE TABLE `biz_project_plan` ( `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键', " +
                "`plan_id` VARCHAR(64) NOT NULL COMMENT '计划编号' ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            params = emptyList(),
        ).toPreviewResult(formatSql = false, literalRecovery = true)
        assertTrue("DDL 不得产生参数行（列注释不是参数）：${ddl.unresolvedParams}", ddl.unresolvedParams.isEmpty())
        assertTrue("预览 SQL 必须保留注释原文（可执行性）", ddl.sql.contains("COMMENT '主键'"))
        assertFalse("预览 SQL 不得被占位符化（COMMENT ? 语法不合法）", ddl.sql.contains("COMMENT ?"))

        val drop = entry(rawSql = "DROP TABLE IF EXISTS `biz_project_member`", params = emptyList())
            .toPreviewResult(formatSql = false, literalRecovery = true)
        assertTrue("DROP 同样不产参数行", drop.unresolvedParams.isEmpty())
    }

    /** 对照：DML/QUERY 的字面量恢复**行为不变**（门禁只拦 DDL/UNKNOWN）。 */
    @Test
    fun `DML 字面量恢复行为不变`() {
        val select = entry(rawSql = "SELECT * FROM t WHERE name = 'abc'", params = emptyList())
            .toPreviewResult(formatSql = false, literalRecovery = true)
        assertEquals("查询的字面量仍恢复为参数", listOf("param1"), select.unresolvedParams)
        assertEquals(mapOf("param1" to "abc"), select.initialParamValues)
    }

    // ------------------------------------------------------------------
    // T-ListParamCollect：命名参数条目的集合值预填
    // ------------------------------------------------------------------

    @Test
    fun `命名参数条目_集合值预填为元素清单`() {
        // 与解析器同构的数据：SQL 中 `:name` 出现顺序 ⇔ params 顺序（本例 2 个引用 2 个参数）
        val named = entry(
            rawSql = "SELECT * FROM t WHERE ((\"deleted\" = :param1) AND (\"id\" IN (:param3)))",
            params = listOf(
                LogSqlParam("0", null),
                LogSqlParam("[6b31d693xxxxx7f4979b]", null),
            ),
        ).toRecognizedPreview()
        assertFalse("命名参数条目无 ? 占位符 → 不走参数行通道", named.recognized)
        // IN 位置的集合值剥括号为元素清单，非 IN 位置原样
        assertEquals(
            mapOf("param1" to "0", "param3" to "6b31d693xxxxx7f4979b"),
            named.initialValues,
        )
        assertTrue("命名参数条目仍无参数行来源", named.unresolvedParams.isEmpty())
    }

    @Test
    fun `命名参数条目_参数数与引用数不齐时不预填`() {
        // 防御：refs 与 params 数量不符时整体弃用（宁可空表，不把值错位配到别的参数名上）
        val mismatched = entry(
            rawSql = "SELECT * FROM t WHERE a = :p1",
            params = listOf(LogSqlParam("1", null), LogSqlParam("2", null)),
        ).toRecognizedPreview()
        assertTrue(mismatched.initialValues.isEmpty())
    }

    @Test
    fun `占位符条目_不受集合值预填影响`() {
        // `?` 条目（含 `:name` 混排的极端形态）仍按占位符通道预填，行为不变
        val recognized = entry(
            rawSql = "SELECT * FROM t WHERE a = ? AND note = ':x'",
            params = listOf(LogSqlParam("1", "Long")),
        ).toRecognizedPreview()
        assertTrue(recognized.recognized)
        assertEquals(mapOf("param1" to "1"), recognized.initialValues)
    }

    // ------------------------------------------------------------------
    // T-StackHoverUi：调用栈快照三分支同权透传
    // （识别/字面量恢复两分支此前丢快照——带参数条目 meta 行摘要与悬停弹层不可达）
    // ------------------------------------------------------------------

    @Test
    fun `识别分支_透传调用栈快照`() {
        val result = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            restoredSql = "SELECT * FROM t WHERE a = ?",
            params = listOf(LogSqlParam("1", "Long")),
            stackDigest = "com.a.B#m:1<-com.c.D#n:2",
        ).toPreviewResult(formatSql = false, literalRecovery = false)
        assertEquals("com.a.B#m:1<-com.c.D#n:2", result.stackDigest)
    }

    @Test
    fun `字面量恢复分支_透传调用栈快照`() {
        val sql = "SELECT * FROM tableA WHERE (\"id\" = '3251') ;"
        val result = LogSqlEntry(
            rawSql = sql,
            restoredSql = sql,
            params = emptyList(),
            stackDigest = "com.a.B#m:3",
        ).toPreviewResult(formatSql = false, literalRecovery = true)
        assertEquals("com.a.B#m:3", result.stackDigest)
    }

    @Test
    fun `兜底分支_保留调用栈快照`() {
        // 占位符 2 != 参数数 1 → 识别失败，且占位符非零不走字面量恢复 → 兜底分支（既有行为钉）
        val result = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            restoredSql = "SELECT * FROM t WHERE a = ? AND b = ?",
            params = listOf(LogSqlParam("1", "Long")),
            stackDigest = "com.a.B#m:4",
        ).toPreviewResult(formatSql = false, literalRecovery = false)
        assertEquals("com.a.B#m:4", result.stackDigest)
    }

    @Test
    fun `无快照条目_三分支不凭空造快照`() {
        val recognized = LogSqlEntry(
            rawSql = "SELECT * FROM t WHERE a = ?",
            restoredSql = "SELECT * FROM t WHERE a = ?",
            params = listOf(LogSqlParam("1", "Long")),
        ).toPreviewResult(formatSql = false, literalRecovery = false)
        assertNull("无快照仍为 null（控制台/桥通道条目不冒称）", recognized.stackDigest)
    }
}
