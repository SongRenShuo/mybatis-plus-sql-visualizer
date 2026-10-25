package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-ExplainConsoleFirst W7 护栏：`Unknown column` 跨源升格判据（[CrossSourceColumnEvidence]）。
 *
 * 事故背景：用户实测 `[42S22][1054] Unknown column 'first_name' in 'field list'`，上下文行三处
 * 读数全是 `mp_demo`——**源没选错**，错的是「SQL 与库」这一对（`first_name` 属 jpa_demo 的同名
 * user 表）。既有分类把 `Unknown column` 一律排除在 WRONG_TARGET 外，这里按证据补上。
 *
 * 护栏重心在**不误判**：拼错列名（任何源都没有）必须维持不升格，否则会把用户引导去换源。
 */
class CrossSourceColumnEvidenceTest {

    private val err = "[42S22][1054] Unknown column 'first_name' in 'field list'"
    private val sql = "SELECT id, first_name FROM user"

    /** 当前源（mp_demo）的 user 表：无 first_name。 */
    private val current: (String) -> Set<String>? = { t ->
        if (t.equals("user", true)) setOf("id", "username", "status") else null
    }

    /** 另一源（jpa_demo）的 user 表：有 first_name。 */
    private val holder: (String) -> Set<String>? = { t ->
        if (t.equals("user", true)) setOf("id", "first_name", "last_name") else null
    }

    @Test
    fun `列不在当前源但另一源同名表有_升格`() {
        val ev = CrossSourceColumnEvidence.assess(err, sql, current, listOf(holder))
        assertNotNull("当前源无此列、另一源有 → 必须升格", ev)
        assertEquals("first_name", ev!!.column)
        assertEquals("user", ev.table)
    }

    @Test
    fun `列名拼错任何源都没有_不升格`() {
        val typo = "[42S22][1054] Unknown column 'frist_name' in 'field list'"
        assertNull(
            "拼错列名换源解决不了，不得引导用户去换源",
            CrossSourceColumnEvidence.assess(typo, sql, current, listOf(holder)),
        )
    }

    @Test
    fun `当前源其实有这一列_不升格`() {
        // 报错另有原因（大小写敏感/权限/别名遮蔽），不归因换源
        val currentHas = { t: String -> if (t.equals("user", true)) setOf("id", "first_name") else null }
        assertNull(
            "当前源有该列时不得归因为选错源",
            CrossSourceColumnEvidence.assess(err, sql, currentHas, listOf(holder)),
        )
    }

    @Test
    fun `当前源缓存未热_无从证明不升格`() {
        val cold = { _: String -> null as Set<String>? }
        assertNull(
            "缓存未热 = 无从证明当前源没有这一列，诚实不升格",
            CrossSourceColumnEvidence.assess(err, sql, cold, listOf(holder)),
        )
    }

    @Test
    fun `没有其它源持有_不升格`() {
        assertNull(
            "另一源也没这列（或没有其它源）→ 换源解决不了，不升格",
            CrossSourceColumnEvidence.assess(err, sql, current, emptyList()),
        )
        assertNull(
            "其它源存在但不带该列 → 不升格",
            CrossSourceColumnEvidence.assess(err, sql, current, listOf(current)),
        )
    }

    @Test
    fun `从SQL取不到表名_不升格`() {
        assertNull(
            "SQL 解析不出表名时不做任何判定（不猜）",
            CrossSourceColumnEvidence.assess(err, "not a sql", current, listOf(holder)),
        )
    }

    @Test
    fun `非UnknownColumn文本_不升格`() {
        assertNull(
            "其它错误形态不走本判据",
            CrossSourceColumnEvidence.assess(
                "[42S02][1146] Table 'mp_demo.x' doesn't exist", sql, current, listOf(holder),
            ),
        )
    }

    @Test
    fun `别名前缀要剥掉取末段`() {
        assertEquals(
            "u.first_name → first_name",
            "first_name",
            CrossSourceColumnEvidence.unknownColumnOf("Unknown column 'u.first_name' in 'where clause'"),
        )
        assertEquals(
            "无前缀原样返回",
            "area_id",
            CrossSourceColumnEvidence.unknownColumnOf("[42S22][1054] Unknown column 'area_id' in 'on clause'"),
        )
        assertNull(
            "非该形态返回 null",
            CrossSourceColumnEvidence.unknownColumnOf("[42S02][1146] Table 'x' doesn't exist"),
        )
    }

    /**
     * 端到端形态：错误文本里带插件拼的 `[当前库=…]` 上下文行时，列名解析仍要正确
     * （`extractConsoleError` 会把上下文行拼在末尾再交给本判据）。
     */
    @Test
    fun `带上下文行的错误文本仍能解析列名`() {
        val withCtx = err + "\n[当前库=mp_demo，数据源=mp_demo@localhost，数据源默认库=mp_demo]"
        val ev = CrossSourceColumnEvidence.assess(withCtx, sql, current, listOf(holder))
        assertNotNull("上下文行不得干扰列名解析", ev)
        assertEquals("first_name", ev!!.column)
    }
}
