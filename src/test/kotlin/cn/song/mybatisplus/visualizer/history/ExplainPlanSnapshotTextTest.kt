package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-TraceDocExport S3：ExplainPlanSnapshot 的 TEXT 档原文快照 + 旧 payload 零迁移兼容。
 *
 * 纯逻辑用例（不引平台类）：payload JSON 读回、persistable 档位、落盘往返、结构回退。
 */
class ExplainPlanSnapshotTextTest {

    private val project = ExplainPlanKey.projectIdentity("/workspace/dm")
    private val uuid = "eb03ba8b-a414-4453-b6e0-5bf55b34fed2"
    private val sql = "select * from t where id = 7"
    private val textKey = ExplainPlanKey.create(project, uuid, "DM（达梦）", ExplainOutputFormat.TEXT, sql)

    private fun manager() = SqlHistoryManager().apply {
        add(SqlHistoryEntry(id = "original", sql = sql, projectIdentity = project))
    }

    private fun save(key: ExplainPlanKey, raw: RawResult): ExplainPlanSnapshot {
        val m = manager()
        assertEquals(ExplainPlanSaveResult.SAVED, m.saveExplainPlan(m.beginExplainCapture(project, sql, key)!!, raw))
        return m.matchingExplainPlans(key).single()
    }

    @Test
    fun `text format is persistable while xml stays session only`() {
        assertTrue(textKey.persistable)
        assertFalse(textKey.copy(format = ExplainOutputFormat.XML.name).persistable)
        assertFalse(textKey.copy(format = "").persistable)
    }

    @Test
    fun `legacy headers rows payload reads back unchanged`() {
        val legacy = ExplainPlanSnapshot(
            format = "TABLE",
            payload = """{"headers":["id","rows"],"rows":[["1","10"]],"truncated":true}""",
        )
        assertEquals(
            RawResult(listOf("id", "rows"), listOf(listOf("1", "10")), true),
            legacy.raw(),
        )
    }

    @Test
    fun `legacy payload without truncated flag still reads back`() {
        val legacy = ExplainPlanSnapshot(format = "TABLE", payload = """{"headers":["id"],"rows":[["1"]]}""")
        assertEquals(RawResult(listOf("id"), listOf(listOf("1")), false), legacy.raw())
    }

    @Test
    fun `text payload reads back as single column rows`() {
        val snapshot = ExplainPlanSnapshot(
            format = "TEXT",
            payload = """{"text":"NSET2\n  TABLE SCAN T","headers":["PLAN"],"truncated":false}""",
        )
        assertEquals(
            RawResult(listOf("PLAN"), listOf(listOf("NSET2"), listOf("  TABLE SCAN T")), false),
            snapshot.raw(),
        )
    }

    @Test
    fun `text payload without headers uses stable plan header`() {
        val snapshot = ExplainPlanSnapshot(format = "TEXT", payload = """{"text":"line1\nline2"}""")
        assertEquals(
            RawResult(listOf(ExplainPlanSnapshot.TEXT_HEADER), listOf(listOf("line1"), listOf("line2"))),
            snapshot.raw(),
        )
    }

    @Test
    fun `empty text payload reads back as empty rows`() {
        val snapshot = ExplainPlanSnapshot(format = "TEXT", payload = """{"text":"","headers":["PLAN"]}""")
        assertEquals(RawResult(listOf("PLAN"), emptyList()), snapshot.raw())
    }

    @Test
    fun `saving a text plan stores raw text payload and round trips`() {
        val raw = RawResult(listOf("PLAN"), listOf(listOf("NSET2"), listOf("  TABLE SCAN T")), true)
        val snapshot = save(textKey, raw)
        assertTrue("payload should carry raw text form: ${snapshot.payload}", snapshot.payload.contains("\"text\""))
        assertEquals(raw, snapshot.raw())
    }

    @Test
    fun `multi column text plan keeps structured payload for fidelity`() {
        val raw = RawResult(listOf("a", "b"), listOf(listOf("1", "2")))
        val snapshot = save(textKey, raw)
        assertFalse(snapshot.payload.contains("\"text\""))
        assertEquals(raw, snapshot.raw())
    }

    @Test
    fun `single column cell with embedded newline keeps structured payload`() {
        val raw = RawResult(listOf("PLAN"), listOf(listOf("a\nb")))
        val snapshot = save(textKey, raw)
        assertFalse(snapshot.payload.contains("\"text\""))
        assertEquals(raw, snapshot.raw())
    }

    @Test
    fun `text snapshots take part in retention bounds`() {
        val m = manager()
        repeat(7) { n ->
            val raw = RawResult(listOf("PLAN"), listOf(listOf("plan $n")))
            assertEquals(ExplainPlanSaveResult.SAVED, m.saveExplainPlan(m.beginExplainCapture(project, sql, textKey)!!, raw))
        }
        assertEquals(SqlHistoryManager.MAX_PLANS_PER_ENTRY, m.matchingExplainPlans(textKey).size)
    }
}
