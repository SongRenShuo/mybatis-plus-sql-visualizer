package cn.song.mybatisplus.visualizer.psource

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.RuntimeSlot
import cn.song.mybatisplus.visualizer.sql.SlotKind
import cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * T-RuntimeSlotFill 券 A：运行期占位模型（[RuntimeSlot]）与拼接器的结构化产出。
 *
 * 夹具直接抄 demo 仓 `RuntimeConcatReproController` 的三个方法体（`listByLoopTernary` /
 * `listByTernaryBranch` / `listByOptionalIf`），因为这三条就是用户报障的原形。
 *
 * 覆盖：
 *  - P-1：格式化器不改写块注释**内容**（身份内嵌方案的前提）
 *  - 循环 / 三元 / 可选 if 三形态的 slot 类别、id 顺序、模板
 *  - 留空回归：不填值时 SQL 与基线逐字一致（只多 ` · rtN`）
 */
class RuntimeSlotTest : BasePlatformTestCase() {

    // ------------------------------------------------------------------
    // 夹具工具
    // ------------------------------------------------------------------

    private fun findCall(methodName: String): PsiMethodCallExpression? {
        val file = myFixture.file as? PsiJavaFile ?: return null
        return PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)
            .firstOrNull { it.methodExpression.referenceName == methodName }
    }

    private fun previewOf(call: PsiMethodCallExpression): PreviewResult? {
        val locatorCall = JdbcTemplateSqlLocator.locateFromCall(call) ?: return null
        return JdbcTemplateSqlPreviewService.preview(locatorCall, call)
    }

    /** 收集占位 id 标记（` · rtN`）后的 SQL：用于「只多了标记」的逐字回归。 */
    private fun stripSlotIds(sql: String): String = sql.replace(Regex(" · rt\\d+"), "")

    private fun compact(result: PreviewResult): String = result.sql.replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------------
    // P-1：格式化器不改写块注释内容
    // ------------------------------------------------------------------

    fun testP1_formatterKeepsBlockCommentTextVerbatim() {
        val comment = "/* 运行时值: 循环内追加 id=’…’ / or id=’…’（运行时重复） · rt1 */"
        val quoted = "'/* 运行时值: startTime · rt2 */'"
        val src = "SELECT id FROM t_order WHERE ( $comment ) and create_time >= $quoted"
        val formatted = SqlFormatter.format(src)
        // 结论：内容逐字保留（只被挪到独立行），因此注释文本可以作为身份载体
        assertTrue("块注释内容应逐字保留: $formatted", formatted.contains(comment))
        assertTrue("落在字符串内的占位应逐字保留: $formatted", formatted.contains(quoted))
        assertEquals("注释起始符应各 1 个", 2, formatted.split("/*").size - 1)
        assertEquals("注释结束符应各 1 个（不许有悬空 */）", 2, formatted.split("*/").size - 1)

        // 非字面量源码形态（带 ? 与空格）同样原样保留
        val raw = "/* 运行时值: k == 0 ? x : y · rt3 */"
        val formatted2 = SqlFormatter.format("SELECT a FROM t WHERE b = $raw AND c = 1")
        assertTrue("源码形态注释内容应逐字保留: $formatted2", formatted2.contains(raw))
    }

    // ------------------------------------------------------------------
    // 夹具 1：循环 + 三元（listByLoopTernary）
    // ------------------------------------------------------------------

    fun testLoopTernary_producesLoopAndValueSlots() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public java.util.List<java.util.Map<String, Object>> listByLoopTernary(
                        JdbcTemplate jdbcTemplate, java.util.List<Long> ids, String startTime) {
                    StringBuilder idStr = new StringBuilder();
                    for (int k = 0; k < ids.size(); k++) {
                        idStr.append(k == 0 ? "id='" + ids.get(k) + "' " : "or id='" + ids.get(k) + "' ");
                    }
                    String sql = "SELECT id, order_no, user_id, amount, create_time FROM t_order " +
                            " WHERE (" + idStr + ") and create_time >= '" + startTime + "'";
                    return jdbcTemplate.queryForList(sql);
                }
            }
        """.trimIndent())
        val call = findCall("queryForList")!!
        val result = previewOf(call)
        assertNotNull(result)

        val slots = result!!.runtimeSlots
        assertEquals("恰好两个可填占位（循环 + startTime）: ${slots.map { it.id + ":" + it.kind }}", 2, slots.size)

        // id 唯一且按产物出现顺序（循环在前）
        assertEquals(listOf("rt1", "rt2"), slots.map { it.id })
        assertEquals(SlotKind.LOOP, slots[0].kind)
        assertEquals(SlotKind.VALUE, slots[1].kind)

        // 循环模板：首项 / 其余 两支，来自源码字面量（不猜连接符）
        val loop = slots[0]
        assertEquals("id='%s' ", loop.firstTemplate)
        assertEquals("or id='%s' ", loop.restTemplate)

        // 表达式摘要仍是既有文案（回归）；源码不外泄
        assertEquals("id=’…’ / or id=’…’", loop.expression)
        assertFalse("三元源码不应进占位: ${loop.token}", loop.token.contains("k == 0"))
        assertFalse("方法调用源码不应进占位: ${loop.token}", loop.token.contains("ids.get"))
        assertFalse("模板里不应出现源码: ${loop.template}", loop.template!!.contains("ids.get"))

        // startTime 落在单引号内 → quote
        val startTime = slots[1]
        assertEquals(ParamHint(quote = true), startTime.hint)
        assertEquals(null, startTime.template)

        // 身份写在注释里：token 是产物中的精确文本
        assertTrue("token 应含 id: ${loop.token}", loop.token.endsWith(" · rt1 */"))
        assertTrue("SQL 里应能找到 token", result.sql.contains(loop.token))
        assertTrue("SQL 里应能找到 token", result.sql.contains(startTime.token))
    }

    // ------------------------------------------------------------------
    // 夹具 2：字符串层面三元（listByTernaryBranch）
    // ------------------------------------------------------------------

    fun testTernaryBranch_producesTernarySlotWithTwoBranchTemplates() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public java.util.List<java.util.Map<String, Object>> listByTernaryBranch(
                        JdbcTemplate jdbcTemplate, String keyword, Integer status) {
                    String sql = "SELECT id, order_no, status FROM t_order WHERE 1 = 1 " +
                            (status == null ? "" : " AND status = " + status) +
                            " AND order_no LIKE '%" + keyword + "%' " +
                            " ORDER BY id DESC";
                    return jdbcTemplate.queryForList(sql);
                }
            }
        """.trimIndent())
        val call = findCall("queryForList")!!
        val result = previewOf(call)
        assertNotNull(result)

        val slots = result!!.runtimeSlots
        assertEquals("两个占位（三元 + keyword）", 2, slots.size)
        assertEquals(listOf("rt1", "rt2"), slots.map { it.id })
        assertEquals(SlotKind.TERNARY, slots[0].kind)
        assertEquals(SlotKind.VALUE, slots[1].kind)

        // P-4：两支文本各自拿到（then 为空、else 是 AND status = <值>）
        val ternary = slots[0]
        assertEquals("", ternary.firstTemplate)
        assertEquals(" AND status = %s", ternary.restTemplate)
        assertEquals(" AND status = %s", ternary.templateBranches[1])

        // keyword 落在单引号内
        assertEquals(ParamHint(quote = true), slots[1].hint)
    }

    // ------------------------------------------------------------------
    // 夹具 3：可选 if 片段（listByOptionalIf）
    // ------------------------------------------------------------------

    fun testOptionalIf_producesTwoOptionalSlotsWithFragmentTemplates() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public java.util.List<java.util.Map<String, Object>> listByOptionalIf(
                        JdbcTemplate jdbcTemplate, String keyword, Integer minAmount) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT id, order_no, amount FROM t_order WHERE deleted = 0 ");
                    if (keyword != null && !keyword.isEmpty()) {
                        sql.append("AND order_no LIKE '%").append(keyword).append("%' ");
                    }
                    if (minAmount != null) {
                        sql.append("AND amount >= ").append(minAmount).append(" ");
                    }
                    sql.append("ORDER BY id DESC");
                    return jdbcTemplate.queryForList(sql.toString());
                }
            }
        """.trimIndent())
        val call = findCall("queryForList")!!
        val result = previewOf(call)
        assertNotNull(result)

        val slots = result!!.runtimeSlots
        assertEquals("恰好两个 OPTIONAL 占位: ${slots.map { it.id + ":" + it.kind }}", 2, slots.size)
        assertTrue("两处都归 OPTIONAL", slots.all { it.kind == SlotKind.OPTIONAL })

        // 展开形态 = 该 if 分支整段模板（字面量原样，非字面量位置 %s）
        assertEquals("AND order_no LIKE '%%s%' ", slots[0].template)
        assertEquals("AND amount >= %s ", slots[1].template)
        assertEquals(ParamHint(quote = true), slots[0].hint)

        // 说明性注释（条件追加，可能不出现）不是可填值对象 → 不带 id、不进 slot
        assertEquals("说明性注释不应带 id", 0, Regex("可能不出现 · rt").findAll(result.sql).count())
        assertTrue("说明性注释仍在场", result.sql.contains("/* 运行时值: 条件追加，可能不出现 */"))
    }

    // ------------------------------------------------------------------
    // 回填语义：IN (...) 内 → inList
    // ------------------------------------------------------------------

    fun testSlotHint_insideInList() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public java.util.List<java.util.Map<String, Object>> q(
                        JdbcTemplate jdbcTemplate, java.util.List<Long> ids) {
                    String sql = "SELECT id FROM t_order WHERE id IN (" + ids + ")";
                    return jdbcTemplate.queryForList(sql);
                }
            }
        """.trimIndent())
        val call = findCall("queryForList")!!
        val result = previewOf(call)
        assertNotNull(result)
        val slot = result!!.runtimeSlots.single()
        assertTrue("落在 IN (...) 内应标 inList: ${slot.hint}", slot.hint.inList)
        assertEquals("IN 列表内不加引号判断", null, slot.hint.quote)
    }

    // ------------------------------------------------------------------
    // 留空回归：不填值时 SQL 与基线逐字一致（只多 ` · rtN`）
    // ------------------------------------------------------------------

    fun testEmptyValues_regressionLoop() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public java.util.List<java.util.Map<String, Object>> listByLoopTernary(
                        JdbcTemplate jdbcTemplate, java.util.List<Long> ids, String startTime) {
                    StringBuilder idStr = new StringBuilder();
                    for (int k = 0; k < ids.size(); k++) {
                        idStr.append(k == 0 ? "id='" + ids.get(k) + "' " : "or id='" + ids.get(k) + "' ");
                    }
                    String sql = "SELECT id, order_no, user_id, amount, create_time FROM t_order " +
                            " WHERE (" + idStr + ") and create_time >= '" + startTime + "'";
                    return jdbcTemplate.queryForList(sql);
                }
            }
        """.trimIndent())
        val result = previewOf(findCall("queryForList")!!)!!
        // 基线 = 券 A 改动前实测产物（probe 读数，见计划册 §九）
        val baseline = "SELECT id, order_no, user_id, amount, create_time FROM t_order  WHERE (" +
            "/* 运行时值: 循环内追加 id=’…’ / or id=’…’（运行时重复） */) and create_time >= " +
            "'/* 运行时值: startTime */';"
        assertEquals("留空时除 ` · rtN` 外应逐字一致", baseline, stripSlotIds(result.compactSql))
        assertEquals("标记个数应等于 slot 个数", 2, Regex(" · rt\\d+").findAll(result.compactSql).count())
    }

    fun testEmptyValues_regressionTernary() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public java.util.List<java.util.Map<String, Object>> listByTernaryBranch(
                        JdbcTemplate jdbcTemplate, String keyword, Integer status) {
                    String sql = "SELECT id, order_no, status FROM t_order WHERE 1 = 1 " +
                            (status == null ? "" : " AND status = " + status) +
                            " AND order_no LIKE '%" + keyword + "%' " +
                            " ORDER BY id DESC";
                    return jdbcTemplate.queryForList(sql);
                }
            }
        """.trimIndent())
        val result = previewOf(findCall("queryForList")!!)!!
        val baseline = "SELECT id, order_no, status FROM t_order WHERE 1 = 1 " +
            "/* 运行时值: (status == null ? ”” : ” AND status = ” + status) */ AND order_no LIKE " +
            "'%/* 运行时值: keyword */%'  ORDER BY id DESC;"
        assertEquals("留空时除 ` · rtN` 外应逐字一致", baseline, stripSlotIds(result.compactSql))
    }

    fun testEmptyValues_regressionOptionalIf() {
        myFixture.configureByText("Demo.java", """
            import org.springframework.jdbc.core.JdbcTemplate;
            public class Demo {
                public java.util.List<java.util.Map<String, Object>> listByOptionalIf(
                        JdbcTemplate jdbcTemplate, String keyword, Integer minAmount) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT id, order_no, amount FROM t_order WHERE deleted = 0 ");
                    if (keyword != null && !keyword.isEmpty()) {
                        sql.append("AND order_no LIKE '%").append(keyword).append("%' ");
                    }
                    if (minAmount != null) {
                        sql.append("AND amount >= ").append(minAmount).append(" ");
                    }
                    sql.append("ORDER BY id DESC");
                    return jdbcTemplate.queryForList(sql.toString());
                }
            }
        """.trimIndent())
        val result = previewOf(findCall("queryForList")!!)!!
        val baseline = "SELECT id, order_no, amount FROM t_order WHERE deleted = 0 " +
            "/* 运行时值: 条件追加，可能不出现 */AND order_no LIKE '%/* 运行时值: keyword */%' " +
            "/* 运行时值: 条件追加，可能不出现 */AND amount >= /* 运行时值: minAmount */ ORDER BY id DESC;"
        assertEquals("留空时除 ` · rtN` 外应逐字一致", baseline, stripSlotIds(result.compactSql))
        // 留空 = 保留占位（D-5），不是 NULL
        assertFalse("留空不应落成 NULL", result.compactSql.contains("= NULL"))
    }

    // ------------------------------------------------------------------
    // 模型：两支编码与 null 语义（纯单元）
    // ------------------------------------------------------------------

    fun testJoinBranches_rejectsLossyShapes() {
        assertEquals("single", RuntimeSlot.joinBranches(listOf("single")))
        assertEquals("a\u0000b", RuntimeSlot.joinBranches(listOf("a", "b")))
        assertEquals(null, RuntimeSlot.joinBranches(emptyList()))
        assertEquals("超过两支无法编码", null, RuntimeSlot.joinBranches(listOf("a", "b", "c")))
        assertEquals(
            "分支里出现分隔符就不编码（不许有损）",
            null,
            RuntimeSlot.joinBranches(listOf("a${RuntimeSlot.BRANCH_SEPARATOR}b", "c")),
        )
    }

    fun testTemplateDecode_singleShapeReadsSameOnBothSides() {
        val slot = RuntimeSlot(
            id = "rt7",
            kind = SlotKind.LOOP,
            expression = "x",
            template = RuntimeSlot.joinBranches(listOf("v=%s ")),
            hint = ParamHint(),
            token = "/* 运行时值: x · rt7 */",
        )
        assertEquals("v=%s ", slot.firstTemplate)
        assertEquals("v=%s ", slot.restTemplate)
        assertEquals(1, slot.templateBranches.size)
    }
}
