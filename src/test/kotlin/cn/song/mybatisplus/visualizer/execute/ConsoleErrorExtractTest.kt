package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 控制台错误提取测试（T-PerfSweep2-01）。
 *
 * 背景：extractConsoleError 被 600ms×8 拍（结果刷新）与 400ms×40/100 拍（结果 Tab 重试）
 * 两个定时器**逐拍**调用。T-PerfSweep2-01 之前 doc.getText() 每拍整串拷贝全史（M36.5.57
 * 的基线截断发生在拷贝之后），history 随长会话涨到 MB 级后是 EDT 上 O(全史) 的重复分配。
 *
 * 修复后读取改 getCharsSequence + subSequence 片段物化——本测试用记录型 CharSequence
 * 断言「只物化 baseline 之后的切片、全量 toString 零调用」，并锁定 M36.5.57 的基线语义
 * 不回归（旧残留错误不带走、无新增返 null）。提取链是纯反射，假对象用与真实方法名
 * 同名的普通类即可（getConsoleView/getHistoryViewer/getDocument/getCharsSequence），
 * 无需平台控制台。
 */
class ConsoleErrorExtractTest : BasePlatformTestCase() {

    private val LOG = Logger.getInstance(ConsoleErrorExtractTest::class.java)

    /** 记录型 CharSequence：subSequence 记区间、toString 计次——物化成本可断言。 */
    private class RecordingSeq(private val text: String) : CharSequence {
        val sliceRanges = mutableListOf<IntRange>()
        var fullToStringCalls = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char = text[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            sliceRanges += startIndex..endIndex
            return text.substring(startIndex, endIndex)
        }

        override fun toString(): String {
            fullToStringCalls++
            return text
        }
    }

    /** 提取链按反射逐层调用，假对象方法名与真实链一一对应。 */
    private class FakeDoc(private val seq: CharSequence) {
        fun getCharsSequence(): CharSequence = seq
    }

    private class FakeHistoryViewer(private val doc: Any) {
        fun getDocument(): Any = doc
    }

    private class FakeConsoleView(private val historyViewer: Any?, private val prompt: String? = null) {
        fun getHistoryViewer(): Any? = historyViewer
        fun getPrompt(): String? = prompt
    }

    private class FakeConsole(private val consoleView: Any?) {
        fun getConsoleView(): Any? = consoleView
    }

    private fun consoleOf(seq: CharSequence): Any =
        FakeConsole(FakeConsoleView(FakeHistoryViewer(FakeDoc(seq))))

    /** T-CEC：带提示符的假控制台（`mpj_demo>` 形态，回显前缀剥离用）。 */
    private fun consoleWithPrompt(seq: CharSequence, prompt: String?): Any =
        FakeConsole(FakeConsoleView(FakeHistoryViewer(FakeDoc(seq)), prompt))

    override fun setUp() {
        super.setUp()
        DataSourceSelector.consoleErrorBaseline = 0
    }

    override fun tearDown() {
        DataSourceSelector.consoleErrorBaseline = 0
        super.tearDown()
    }

    /** baseline=0（首次执行）：全文扫描，旧语义——错误行照常检出，全文物化一次。 */
    fun testBaselineZeroScansWholeText() {
        // 签名按 ERROR_SIGNATURES 连续子串匹配（"unknown column"），文本须真实含签名
        val seq = RecordingSeq("select id from t\nSQLException: Unknown column 'z' in 'field list'\ncheck the manual\n")
        val err = DataSourceSelector.extractConsoleError(consoleOf(seq), LOG)
        assertTrue("baseline=0 应检出错误行", err != null && err.contains("Unknown column"))
        assertEquals("全文路径应物化整个序列一次", 1, seq.fullToStringCalls)
        assertEquals("baseline=0 不应走切片", 0, seq.sliceRanges.size)
    }

    /** T-HiddenBugPerfScan 券 Y5：同批两条逐字相同的错误行——摘录锚定最后一次出现。 */
    fun testDuplicateErrorLinesAnchorLastOccurrence() {
        val errLine = "SQLException: Unknown column 'q' in 'field list'"
        val seq = RecordingSeq(
            "select a from t1\n" + errLine + "\ncontext of first failure\n" +
                "select b from t2\n" + errLine + "\ncontext of second failure\n",
        )
        val err = DataSourceSelector.extractConsoleError(consoleOf(seq), LOG)
        assertTrue("应检出错误", err != null && err.contains("Unknown column 'q'"))
        assertTrue(
            "摘录应锚定最后一次出现（second 上下文）：$err",
            err!!.contains("context of second failure"),
        )
        assertTrue(
            "不得锚定首现（first 上下文属前一条语句）：$err",
            !err.contains("context of first failure"),
        )
    }

    /** 核心回归：baseline>0 只物化新增切片——全量 toString 零调用，旧残留错误不带走。 */
    fun testBaselineSliceOnlyMaterializesNewSegment() {
        val oldPart = "select id from t\nUnknown column 'x' in 'field list'\n"
        // 错误行取 lastOrNull（末尾优先），目标错误行放最后
        val newPart = "select * from u\nTable 'u' doesn't exist\nSQLException: Unknown column 'y' in 'where clause'\n"
        val seq = RecordingSeq(oldPart + newPart)
        DataSourceSelector.consoleErrorBaseline = oldPart.length

        val err = DataSourceSelector.extractConsoleError(consoleOf(seq), LOG)

        assertTrue("应检出本次执行的新错误", err != null && err.contains("Unknown column 'y'"))
        assertFalse("旧残留错误不得带入（M36.5.57 语义）", err?.contains("'x'") == true)
        assertEquals("全量 toString 零调用（每拍全史拷贝病灶消除）", 0, seq.fullToStringCalls)
        assertEquals("只切一刀", 1, seq.sliceRanges.size)
        assertEquals("切片起点=基线、终点=文档末尾", oldPart.length..seq.length, seq.sliceRanges.single())
    }

    /** 文档长度 <= 基线（DBN 未写入新内容）：返 null，不付任何物化成本。 */
    fun testNoGrowthReturnsNull() {
        val text = "select id from t\nUnknown column 'x' in 'field list'\n"
        val seq = RecordingSeq(text)
        DataSourceSelector.consoleErrorBaseline = text.length
        assertNull(DataSourceSelector.extractConsoleError(consoleOf(seq), LOG))
        assertEquals(0, seq.fullToStringCalls)
        assertEquals(0, seq.sliceRanges.size)
    }

    /** 基线越界（历史被清空重置的竞态窗口）：返 null 不抛。 */
    fun testBaselineBeyondLengthReturnsNull() {
        val seq = RecordingSeq("select 1\n")
        DataSourceSelector.consoleErrorBaseline = 100
        assertNull(DataSourceSelector.extractConsoleError(consoleOf(seq), LOG))
    }

    /** 新增段只有正常 SQL（无错误签名）：返 null——每拍早检不误报。 */
    fun testAppendedRegionWithoutErrorReturnsNull() {
        val oldPart = "select id from t\n"
        val newPart = "select 1\nfrom t where id = 1\n"
        val seq = RecordingSeq(oldPart + newPart)
        DataSourceSelector.consoleErrorBaseline = oldPart.length
        assertNull(DataSourceSelector.extractConsoleError(consoleOf(seq), LOG))
        assertEquals("无错误时也只付切片成本", 0, seq.fullToStringCalls)
    }

    /** 反射链断链（非真 ConsoleView，如测试桩）：返 null 不抛。 */
    fun testBrokenChainReturnsNull() {
        assertNull(DataSourceSelector.extractConsoleError(FakeConsole(null), LOG))
        assertNull(DataSourceSelector.extractConsoleError(null, LOG))
    }

    // ==================== T-CEC-02：服务端错误判据覆盖（用户真机 P15） ====================

    /**
     * 用户 2026-09-22 真机 Output 页报文逐字（每行带 `[时间戳] `，错误跨 4 行）。
     * 改前：14 条短语词表对 `You have an error in your SQL syntax` 一条都不含 → 漏检。
     */
    private val USER_REAL_CASE = listOf(
        "[2026-09-22 08:48:12] [42000][1064] You have an error in your SQL syntax; check the manual " +
            "that corresponds to your MySQL server version for the right syntax to use near '\"user\" t",
        "[2026-09-22 08:48:12] LEFT JOIN user_address addr",
        "[2026-09-22 08:48:12] RIGHT JOIN area a",
        "[2026-09-22 08:48:12] WHERE addr.tel LIKE '%' at line 5",
    ).joinToString("\n")

    fun testUserRealMachineMysqlSyntaxErrorDetected() {
        val err = DataSourceSelector.extractConsoleError(consoleOf(RecordingSeq(USER_REAL_CASE)), LOG)
        assertNotNull("MySQL 语法错必须命中（P15 真机漏检形态）", err)
        assertTrue("错误原文须在", err!!.contains("You have an error in your SQL syntax"))
        assertTrue("跨行报文的后继行须带上", err.contains("at line 5"))
        assertFalse("时间戳前缀须剥掉（呈现与一键复制都更干净）", err.contains("[2026-09-22"))
    }

    /** DBN 给服务端错误加 `[SQLSTATE][errorCode]` 前缀（真机日志实证形态）——结构性判据跨方言。 */
    fun testStructuralJudgementCoversDialects() {
        val cases = listOf(
            "[23000][1062] Duplicate entry '1' for key 'PRIMARY'",
            "[42000][1064] You have an error in your SQL syntax",
            "[42S22][1054] Unknown column 'x' in 'field list'",
            "ORA-00933: SQL command not properly ended",
            "ORA-00942: table or view does not exist",
        )
        for (line in cases) {
            val err = DataSourceSelector.extractConsoleError(consoleOf(RecordingSeq("select 1\n$line\n")), LOG)
            assertNotNull("应命中：$line", err)
            assertTrue("原文须保留：$line", err!!.contains(line.take(10)))
        }
    }

    /** 反例：正常 SQL 回显带方括号/比较符，结构性判据不得误命中。 */
    fun testBracketBearingSqlEchoNotReported() {
        val seq = RecordingSeq("select id from t where a in [1,2]\nselect 1 where b>1\n")
        assertNull(DataSourceSelector.extractConsoleError(consoleOf(seq), LOG))
    }

    /** 反例：插件自己写进 Output 的回显行（writeConsoleOutput 的「执行失败: 」）不得被当成新错误。 */
    fun testOwnFailureEchoNotReported() {
        val seq = RecordingSeq("执行失败: [42000][1064] You have an error in your SQL syntax\n")
        assertNull(DataSourceSelector.extractConsoleError(consoleOf(seq), LOG))
    }

    /**
     * R33 真机走查修复（反向）：EXPLAIN 附随的 SHOW WARNINGS note 行（`[HY000][1003]`，
     * 含重写查询）不是执行失败——修复前该行被 SQLSTATE 前缀判据命中，直送通道执行成功出了
     * 结果、底部红条却报「执行失败: HY000[1003] …」（真机实证）。
     */
    fun testExplainShowWarningsNote1003NotReported() {
        val text = listOf(
            "mpj_demo> EXPLAIN SELECT id FROM user WHERE username = 'x'",
            "[2026-10-01 10:00:00] [HY000][1003] Note: /* select#1 */ select `id` from `user` where `username` = 'x'",
        ).joinToString("\n")
        assertNull(
            "note 1003 行是 informational，不得当错误报红条",
            DataSourceSelector.extractConsoleError(consoleOf(RecordingSeq(text)), LOG),
        )
    }

    /** 正例（关键）：note 1003 之后紧跟真实语法错——真实失败仍须检出，排除不得扩大化。 */
    fun testNote1003ThenRealErrorStillDetected() {
        val text = listOf(
            "[2026-10-01 10:00:00] [HY000][1003] Note: /* select#1 */ select `id` from `user`",
            "[2026-10-01 10:00:01] [42000][1064] You have an error in your SQL syntax; check the manual",
        ).joinToString("\n")
        val err = DataSourceSelector.extractConsoleError(consoleOf(RecordingSeq(text)), LOG)
        assertNotNull("真实语法错必须仍命中", err)
        assertTrue("错误原文须在", err!!.contains("You have an error in your SQL syntax"))
        assertFalse("note 行不得混入错误摘录", err.contains("1003"))
    }

    /** 边界：note 排除只认 HY000+1003 组合——其余 SQLSTATE 组合的 1003 号照旧按判据走。 */
    fun testNonHy000Code1003StillTreatedAsError() {
        val seq = RecordingSeq("select 1\n[42S22][1003] Unknown column 'x' in 'field list'\n")
        assertNotNull(
            "非 HY000 的 1003 不在 note 排除面（排除不得吞掉结构性判据）",
            DataSourceSelector.extractConsoleError(consoleOf(seq), LOG),
        )
    }

    // ==================== T-CEC-03：兜底呈现（判据认不出时的残余） ====================

    /** 正常执行的新增文本形态：SQL 回显（首行带提示符）+ 完成回显。 */
    private val ECHO_AND_STATUS = listOf(
        "mpj_demo> SELECT addr.tel",
        "              LEFT JOIN user_address addr",
        "[2026-09-22 08:48:29] completed in 7 ms",
    ).joinToString("\n")

    private val ECHOED_SQL = "SELECT addr.tel\n              LEFT JOIN user_address addr"

    fun testResidueDropsSqlEchoAndStatusLines() {
        val text = "$ECHO_AND_STATUS\n[2026-09-22 08:48:29] 未知形态的失败说明（自定义报文）\n"
        val residue = DataSourceSelector.extractConsoleResidue(
            consoleWithPrompt(text, "mpj_demo>"), ECHOED_SQL, LOG,
        )
        assertNotNull("有残余就该呈现（而不是笼统超时提示）", residue)
        assertTrue("残余=控制台新增文本", residue!!.contains("未知形态的失败说明"))
        assertFalse("SQL 回显不得混入", residue.contains("LEFT JOIN user_address"))
        assertFalse("状态行不得混入", residue.contains("completed in"))
    }

    fun testResidueNullWhenOnlyEchoAndStatus() {
        assertNull(
            "只有回显+完成回显 → 无残余，落回既有提示（不误报错误）",
            DataSourceSelector.extractConsoleResidue(
                consoleWithPrompt(ECHO_AND_STATUS, "mpj_demo>"), ECHOED_SQL, LOG,
            ),
        )
    }

    /** 提示符读不到（getPrompt 缺失/为空）时，仍靠「剩余部分是本次 SQL 子串」剥掉回显。 */
    fun testResidueDropsPromptEchoWithoutPromptReadable() {
        val text = "mpj_demo> SELECT addr.tel\n未知形态失败\n"
        val residue = DataSourceSelector.extractConsoleResidue(consoleOf(RecordingSeq(text)), "SELECT addr.tel", LOG)
        assertNotNull(residue)
        assertFalse("回显行须被剥掉", residue!!.contains("SELECT addr.tel"))
        assertTrue(residue.contains("未知形态失败"))
    }

    /**
     * 反例（关键）：DML / 工具语句成功时不建结果 Tab、同样走满 40 拍窗口——影响行数与完成回显
     * 必须被剔干净，否则会把一次**成功**的执行报成「未知错误形态」。
     */
    fun testResidueNullForSuccessfulDmlSummary() {
        val text = listOf(
            "mpj_demo> UPDATE user SET username = 'x' WHERE id = 1",
            "[2026-09-22 08:48:29] 1 row affected",
            "[2026-09-22 08:48:29] completed in 12 ms",
        ).joinToString("\n")
        assertNull(
            "成功 DML 的摘要行不得被当成残余",
            DataSourceSelector.extractConsoleResidue(
                consoleWithPrompt(text, "mpj_demo>"),
                "UPDATE user SET username = 'x' WHERE id = 1",
                LOG,
            ),
        )
    }

    // ======================== T8（T-TestGapSpeed）：工具语句成功完成证据 ========================
    // 缺陷：`use`/DML 这类无结果 Tab 的语句成功后，40 拍窗口到期落 execute.timeout.hint
    // （把成功说成超时/密码驱动问题）——pitfall-console-error-signature-coverage 顺带观察在案。

    /**
     * 成功 DML（与 [testResidueNullForSuccessfulDmlSummary] 同一真机形态样本）必须检出完成
     * 证据——这正是 40 拍到期时与「真挂死」区分的唯一正向信号。
     */
    fun testCompletionEvidenceDetectedForSuccessfulDml() {
        val text = listOf(
            "mpj_demo> UPDATE user SET username = 'x' WHERE id = 1",
            "[2026-09-22 08:48:29] 1 row affected",
            "[2026-09-22 08:48:29] completed in 12 ms",
        ).joinToString("\n")
        assertTrue(
            "DML 完成回显（affected/completed in）应被识别为完成证据",
            DataSourceSelector.hasConsoleCompletionEvidence(consoleWithPrompt(text, "mpj_demo>")),
        )
    }

    /** `use` 类工具语句若 DBN 只回显语句本身（无摘要行）——不判完成（宁少勿错，退化为旧提示）。 */
    fun testCompletionEvidenceAbsentForEchoOnly() {
        val text = "mpj_demo> use mpj_demo\n"
        assertFalse(
            "纯回显（无完成回显）不得判完成",
            DataSourceSelector.hasConsoleCompletionEvidence(consoleWithPrompt(text, "mpj_demo>")),
        )
    }

    /** 进度语义词（executing/fetching/connected）不算完成——长查询挂死时不误报「已完成」。 */
    fun testCompletionEvidenceAbsentForInProgressStatusLines() {
        val text = listOf(
            "mpj_demo> SELECT SLEEP(3600)",
            "[2026-09-22 08:48:29] connected",
            "[2026-09-22 08:48:29] executing...",
        ).joinToString("\n")
        assertFalse(
            "进度语义词不是完成证据",
            DataSourceSelector.hasConsoleCompletionEvidence(consoleWithPrompt(text, "mpj_demo>")),
        )
    }

    /** 控制台不可读 / 无新增文本：不判完成（调用方落回既有提示）。 */
    fun testCompletionEvidenceAbsentWhenConsoleUnreadable() {
        assertFalse(DataSourceSelector.hasConsoleCompletionEvidence(FakeConsole(null)))
        assertFalse(DataSourceSelector.hasConsoleCompletionEvidence(null))
    }
}
