package cn.song.mybatisplus.visualizer.history

import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.*
import org.junit.Test

class ExplainPlanHistoryTest {
    private val project = ExplainPlanKey.projectIdentity("/workspace/a")
    private val uuid = "eb03ba8b-a414-4453-b6e0-5bf55b34fed2"
    private val sql = "select * from t where id = 7"
    private val key = ExplainPlanKey.create(project, uuid, "MySQL", ExplainOutputFormat.TABLE, sql)
    private fun raw(n: Int = 7) = RawResult(listOf("id", "table", "rows", "Extra"), listOf(listOf("1", "t", "$n", "x<&\n\"")))
    private fun manager() = SqlHistoryManager().apply { add(SqlHistoryEntry(id = "original", sql = sql, projectIdentity = project)) }
    private fun SqlHistoryManager.capture() = beginExplainCapture(project, sql, key)!!

    @Test fun `real serializer restart preserves table and json snapshots`() {
        val m = manager()
        assertEquals(ExplainPlanSaveResult.SAVED, m.saveExplainPlan(m.capture(), raw()))
        val jsonKey = key.copy(format = "JSON", dialect = "PostgreSQL")
        val json = RawResult(listOf("QUERY PLAN"), listOf(listOf("[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Plan Rows\":9}}]")), true)
        m.saveExplainPlan(m.beginExplainCapture(project, sql, jsonKey)!!, json)
        val xml = XmlSerializer.serialize(m.getState())
        val restart = SqlHistoryManager().apply { loadState(XmlSerializer.deserialize(xml, SqlHistoryManager.State::class.java)) }
        assertEquals(raw(), restart.matchingExplainPlans(key).single().raw())
        assertEquals(json, restart.matchingExplainPlans(jsonKey).single().raw())
        assertFalse(org.jdom.output.XMLOutputter().outputString(xml).contains("/workspace/a"))
    }

    @Test fun `old xml defaults empty and does not acquire a project identity`() {
        val xml = Element("SqlHistoryEntry").addContent(Element("option").setAttribute("name", "sql").setAttribute("value", sql))
        val old = XmlSerializer.deserialize(xml, SqlHistoryEntry::class.java)
        assertTrue(old.explainPlans.isEmpty())
        assertEquals("", old.projectIdentity)
        val m = SqlHistoryManager().apply { add(old) }
        assertNull(m.beginExplainCapture(project, sql, key))
    }

    @Test fun `all target and SQL dimensions isolate persisted baselines`() {
        val m = manager()
        m.saveExplainPlan(m.capture(), raw())
        listOf(key.copy(projectIdentity = "another"), key.copy(dataSourceUuid = "eb03ba8b-a414-4453-b6e0-5bf55b34fed3"),
            key.copy(dialect = "MariaDB"), key.copy(format = "JSON"), key.copy(templateFingerprint = "other"),
            ExplainPlanKey.create(project, uuid, "MySQL", ExplainOutputFormat.TABLE, sql.replace("7", "8"))).forEach {
            assertTrue("must isolate $it", m.matchingExplainPlans(it).isEmpty())
        }
        assertEquals(key.templateFingerprint, ExplainPlanKey.create(project, uuid, "MySQL", ExplainOutputFormat.TABLE, sql.replace("7", "8")).templateFingerprint)
    }

    @Test fun `unknown or non UUID targets are session only`() {
        val m = manager()
        listOf("", "Unknown", "jdbc:mysql://secret", "name").forEach { id ->
            val unknown = key.copy(dataSourceUuid = id)
            assertEquals(ExplainPlanSaveResult.SESSION_ONLY, m.saveExplainPlan(m.beginExplainCapture(project, sql, unknown)!!, raw()))
            assertTrue(m.matchingExplainPlans(unknown).isEmpty())
        }
        assertTrue(m.findById("original")!!.explainPlans.isEmpty())
    }

    @Test fun `capture fixes original entry and clear rejects late completion even reused id`() {
        val m = manager()
        val pending = m.capture()
        m.add(SqlHistoryEntry(id = "new", sql = sql, timestamp = 1, projectIdentity = project))
        m.saveExplainPlan(pending, raw())
        assertEquals(1, m.findById("original")!!.explainPlans.size)
        assertTrue(m.findById("new")!!.explainPlans.isEmpty())
        m.clear()
        m.add(SqlHistoryEntry(id = "original", sql = sql, projectIdentity = project))
        assertEquals(ExplainPlanSaveResult.CLEARED_OR_REMOVED, m.saveExplainPlan(pending, raw()))
        assertTrue(m.matchingExplainPlans(key).isEmpty())
    }

    @Test fun `remove and load invalidate pending writes`() {
        val m = manager()
        val pending = m.capture()
        m.remove("original")
        assertEquals(ExplainPlanSaveResult.CLEARED_OR_REMOVED, m.saveExplainPlan(pending, raw()))
        m.loadState(SqlHistoryManager.State(mutableListOf(SqlHistoryEntry(id = "original", sql = sql, projectIdentity = project))))
        assertEquals(ExplainPlanSaveResult.CLEARED_OR_REMOVED, m.saveExplainPlan(pending, raw()))
    }

    @Test fun `five retained snapshots oversized rejected without truncating or deleting SQL`() {
        val m = manager()
        repeat(7) { m.saveExplainPlan(m.capture(), raw(it)) }
        assertEquals(5, m.findById("original")!!.explainPlans.size)
        val before = m.matchingExplainPlans(key)
        assertEquals(ExplainPlanSaveResult.TOO_LARGE, m.saveExplainPlan(m.capture(), RawResult(listOf("plan"), listOf(listOf("中<&".repeat(40000))))))
        assertEquals(before, m.matchingExplainPlans(key))
        assertEquals(sql, m.findById("original")!!.sql)
    }

    @Test fun `overall eviction preserves favorite SQL and loaded state bounds`() {
        val entries = (0..49).map { n ->
            SqlHistoryEntry(id = "$n", sql = sql, projectIdentity = project, favorite = true, explainPlans = mutableListOf(
                ExplainPlanSnapshot.create(key, RawResult(listOf("plan"), listOf(listOf("x".repeat(200000))))).apply { capturedAt = n.toLong() },
            ))
        }.toMutableList()
        val m = SqlHistoryManager().apply { loadState(SqlHistoryManager.State(favoriteEntries = entries)) }
        val plans = m.matchingExplainPlans(key)
        assertTrue(plans.size < 50)
        assertTrue(plans.sumOf { it.serializedBytes().toLong() } <= SqlHistoryManager.MAX_TOTAL_PLAN_BYTES)
        assertEquals(50, m.filterFavorites().size)
        assertTrue(m.findById("0")!!.explainPlans.isEmpty())
        assertTrue(m.findById("49")!!.explainPlans.isNotEmpty())
    }

    @Test fun `explicit original id wins over newer identical SQL and survives rerender`() {
        val m = manager()
        m.add(SqlHistoryEntry(id = "new", sql = sql, timestamp = 1, projectIdentity = project))
        val capture = m.beginExplainCapture(project, "select exists ($sql)", key, "original")!!
        m.saveExplainPlan(capture, raw())
        assertEquals(1, m.findById("original")!!.explainPlans.size)
        assertTrue(m.findById("new")!!.explainPlans.isEmpty())
        assertNull(m.beginExplainCapture(project, sql, key, ""))
        assertNull(m.beginExplainCapture("other", sql, key.copy(projectIdentity = "other"), "original"))
    }

    @Test fun `read snapshots are defensive and notices are not persisted`() {
        val m = manager()
        m.saveExplainPlan(m.capture(), raw().copy(notices = listOf("jdbc:mysql://private password=secret")))
        val first = m.matchingExplainPlans(key).single()
        assertFalse(first.payload.contains("password"))
        first.payload = "broken"
        assertEquals(raw(), m.matchingExplainPlans(key).single().raw())
    }

    /** T-TraceDocExport S3：TEXT 档原文快照经真实 XmlSerializer 往返；同文件旧结构化 payload 读回不破（P-5）。 */
    @Test fun `text snapshot survives real serializer restart and keeps raw text form`() {
        val m = manager()
        assertEquals(ExplainPlanSaveResult.SAVED, m.saveExplainPlan(m.capture(), raw()))
        val textKey = key.copy(format = "TEXT", dialect = "DM（达梦）")
        val text = RawResult(listOf("PLAN"), listOf(listOf("NSET2"), listOf("  TABLE SCAN T")), true)
        assertEquals(ExplainPlanSaveResult.SAVED, m.saveExplainPlan(m.beginExplainCapture(project, sql, textKey)!!, text))
        assertTrue(m.matchingExplainPlans(textKey).single().payload.contains("\"text\""))
        val xml = XmlSerializer.serialize(m.getState())
        val restart = SqlHistoryManager().apply {
            loadState(XmlSerializer.deserialize(xml, SqlHistoryManager.State::class.java))
        }
        assertEquals(text, restart.matchingExplainPlans(textKey).single().raw())
        assertEquals(raw(), restart.matchingExplainPlans(key).single().raw())
    }
}
