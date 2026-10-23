package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R60（T-LaneB D95/D96）：DisplayMasker 双条件规则与展示链脱敏纯逻辑测试。
 *
 * 规则对齐采集侧 agent Desensitizer.java:10-30（V112.1 落档）：
 * ①列名/参数名 hint 小写 contains 关键词表（24 项）→ "***"；
 * ②值形态兜底：18 位身份证 / 11 位手机号 → "***"；
 * 空/null 值原样返回；非敏感原样返回。
 */
class DisplayMaskerTest {

    // ---------- 条件①：列名关键词 hint ----------

    @Test
    fun test列名hint命中关键词_打码() {
        for (hint in listOf("password", "user_pwd", "ACCESS_TOKEN", "api_key", "手机号", "id_card", "secret")) {
            assertEquals("hint=$hint", "***", DisplayMasker.mask(hint, "any-value"))
        }
    }

    @Test
    fun test列名hint不命中_非敏感值原样() {
        assertEquals("abc", DisplayMasker.mask("user_name", "abc"))
        assertEquals("42", DisplayMasker.mask("age", "42"))
    }

    // ---------- 条件②：值形态兜底（无 hint） ----------

    @Test
    fun test身份证形态_无hint也打码() {
        assertEquals("***", DisplayMasker.mask(null, "110101199001011234"))
        assertEquals("***", DisplayMasker.mask("idno", "11010119900101123X"))
        assertEquals("***", DisplayMasker.mask("idno", "11010119900101123x"))
    }

    @Test
    fun test手机号形态_无hint也打码() {
        assertEquals("***", DisplayMasker.mask(null, "13812345678"))
        assertEquals("***", DisplayMasker.mask("contact", "13812345678"))
    }

    @Test
    fun test形态不匹配_原样() {
        assertEquals("12345", DisplayMasker.mask(null, "12345")) // 5 位非手机
        assertEquals("11010119900101123", DisplayMasker.mask(null, "11010119900101123")) // 17 位非身份证
        assertEquals("23812345678", DisplayMasker.mask(null, "23812345678")) // 11 位首尾非 1
        assertEquals("1381234567a", DisplayMasker.mask(null, "1381234567a")) // 含字母
    }

    @Test
    fun test空与null值_原样返回() {
        assertEquals("", DisplayMasker.mask("password", ""))
        assertNullValue()
        assertEquals("", DisplayMasker.mask(null, ""))
    }

    private fun assertNullValue() {
        assertEquals(null, DisplayMasker.mask("password", null))
    }

    // ---------- SQL 文本级脱敏（展示/导出/MCP 链共用入口） ----------

    @Test
    fun testSQL文本_引号字面量按列hint打码() {
        val sql = "SELECT id FROM user WHERE password = 'p@ss' AND name = 'tom'"
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("password = '***'"))
        assertTrue(masked.contains("name = 'tom'")) // 非敏感列不打码
    }

    @Test
    fun testSQL文本_值形态兜底打码() {
        val sql = "SELECT * FROM t WHERE contact = '13812345678' OR cert_no = '110101199001011234'"
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("'***'"))
        assertFalse(masked.contains("13812345678"))
        assertFalse(masked.contains("110101199001011234"))
    }

    @Test
    fun testSQL文本_IN列表逐项判() {
        // 同一敏感列（phone）的 IN 多值全部打码——对齐采集侧「该列全部参数值打码」语义
        val sql = "SELECT 1 FROM t WHERE phone IN ('13812345678', '021-8888') "
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("'***', '***'"))
        assertFalse(masked.contains("13812345678"))
        assertFalse(masked.contains("021-8888"))
        // 非敏感列（city）的 IN 值保留
        val plain = DisplayMasker.maskSqlText("SELECT 1 FROM t WHERE city IN ('sh', 'bj')")
        assertEquals("SELECT 1 FROM t WHERE city IN ('sh', 'bj')", plain)
    }

    @Test
    fun testSQL文本_裸数字形态打码_非敏感数字保留() {
        val sql = "SELECT * FROM t WHERE mobile = 13812345678 AND age = 30"
        val masked = DisplayMasker.maskSqlText(sql)
        assertFalse(masked.contains("13812345678"))
        assertTrue(masked.contains("age = 30"))
    }

    @Test
    fun testSQL文本_引号内子串手机号不打码_对齐采集侧整体值语义() {
        // 采集侧 Desensitizer 对参数值整体判形态；值内子串命中的手机号不视为敏感
        val sql = "SELECT * FROM t WHERE note = 'order 13812345678 done'"
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("'order 13812345678 done'"))
    }

    @Test
    fun testSQL文本_转义引号字面量() {
        val sql = "UPDATE t SET pwd = 'a''b' WHERE id = 1"
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("'***'"))
        assertFalse(masked.contains("'a''b'"))
    }

    @Test
    fun testSQL文本_无敏感_逐字原样() {
        val sql = "SELECT id, name FROM user WHERE age > 18 AND city = 'sh'"
        assertEquals(sql, DisplayMasker.maskSqlText(sql))
    }

    // ---------- 链入口：maskDisplay（enabled 开关） ----------

    @Test
    fun testMaskDisplay_开打码关原文() {
        val sql = "SELECT * FROM t WHERE phone = '13812345678'"
        val on = DisplayMasker.maskDisplay(sql, true)
        val off = DisplayMasker.maskDisplay(sql, false)
        assertTrue(on.contains("'***'"))
        assertFalse(on.contains("13812345678"))
        assertEquals(sql, off)
    }

    // ---------- R37：敏感字面量参数化 ----------

    @Test
    fun test参数化_敏感字面量换占位符并出脱敏参数表() {
        val sql = "SELECT id FROM user WHERE phone = '13812345678' AND name = 'tom' AND pwd = 'x'"
        val p = DisplayMasker.parameterize(sql)
        assertTrue(p.sql.contains("phone = ?"))
        assertTrue(p.sql.contains("name = 'tom'")) // 非敏感保留
        assertTrue(p.sql.contains("pwd = ?"))
        assertEquals(listOf("***", "***"), p.maskedParams)
        assertFalse(p.sql.contains("13812345678"))
    }

    @Test
    fun test参数化_零敏感_原文无参数表() {
        val sql = "SELECT id FROM user WHERE name = 'tom'"
        val p = DisplayMasker.parameterize(sql)
        assertEquals(sql, p.sql)
        assertTrue(p.maskedParams.isEmpty())
    }

    @Test
    fun test参数化_裸数字手机号也参数化() {
        val sql = "SELECT 1 FROM t WHERE mobile = 13812345678"
        val p = DisplayMasker.parameterize(sql)
        assertEquals("SELECT 1 FROM t WHERE mobile = ?", p.sql)
        assertEquals(listOf("***"), p.maskedParams)
    }

    // ---------- UI-021：INSERT VALUES 列名-值位置对位 ----------

    @Test
    fun test_INSERT_VALUES_列名对位_密码列打码且后继值不误打() {
        val sql = "INSERT INTO user (username, password, email) " +
            "VALUES ('ui_mask_probe', 'Secret123X', 'mask@probe.test')"
        assertEquals(
            "INSERT INTO user (username, password, email) " +
                "VALUES ('ui_mask_probe', '***', 'mask@probe.test')",
            DisplayMasker.maskSqlText(sql),
        )
    }

    @Test
    fun test_INSERT_VALUES_多列对位_关键词列与形态值各按其位() {
        val sql = "INSERT INTO t (a, secret_col, b) VALUES ('x', 'y', '13400000000')"
        // a/b 非敏感列 → 'x' 原样、手机号形态 '13400000000' 走形态兜底；secret_col → 'y' 打码
        assertEquals("INSERT INTO t (a, secret_col, b) VALUES ('x', '***', '***')", DisplayMasker.maskSqlText(sql))
    }

    @Test
    fun test_INSERT_VALUES_值数超出列数_逐位回退旧规则() {
        val sql = "INSERT INTO t (a, b) VALUES ('x', 'y') ON DUPLICATE KEY UPDATE password = 'Secret123X'"
        // 前两位按列清单（a/b 均非敏感）；第三位超列数回退旧 hint（UPDATE 后 password → 打码）
        assertEquals(
            "INSERT INTO t (a, b) VALUES ('x', 'y') ON DUPLICATE KEY UPDATE password = '***'",
            DisplayMasker.maskSqlText(sql),
        )
    }

    @Test
    fun test_parameterize_INSERT_VALUES_对位一致() {
        val r = DisplayMasker.parameterize("INSERT INTO u (username, pwd) VALUES ('u1', 'p1')")
        assertEquals("INSERT INTO u (username, pwd) VALUES ('u1', ?)", r.sql)
        assertEquals(listOf("***"), r.maskedParams)
    }

    // ---------- T-MagpieGems W2：mode 四档语义矩阵 ----------

    private val W2_SQL = "SELECT * FROM t WHERE phone = '13812345678' AND pwd = 'p@ss' AND id = 110101199001011234"

    @Test
    fun testMaskMode四档语义矩阵() {
        val allow = emptyList<String>()
        // OFF：原样、零计数
        val off = DisplayMasker.scanSqlText(W2_SQL, DisplayMasker.MaskMode.OFF, allow)
        assertEquals(W2_SQL, off.maskedSql)
        assertTrue(off.counts.isEmpty())
        // MASK：打码、原文不残留
        val masked = DisplayMasker.scanSqlText(W2_SQL, DisplayMasker.MaskMode.MASK, allow)
        assertTrue(masked.maskedSql.contains("'***'"))
        assertFalse(masked.maskedSql.contains("13812345678"))
        assertFalse(masked.maskedSql.contains("p@ss"))
        assertFalse(masked.maskedSql.contains("110101199001011234"))
        // WARN：原文 + 计数
        val warn = DisplayMasker.scanSqlText(W2_SQL, DisplayMasker.MaskMode.WARN, allow)
        assertEquals(W2_SQL, warn.maskedSql)
        // phone 列走关键词命中归并 sensitive_column；裸 18 位走 id_card；本例无形态手机号
        assertEquals(null, warn.counts[DisplayMasker.CAT_MOBILE])
        assertEquals(1, warn.counts[DisplayMasker.CAT_ID_CARD])
        assertEquals(2, warn.counts[DisplayMasker.CAT_SENSITIVE_COLUMN])
        // BLOCK：整段阻断占位（含命中总数 3），原值不残留
        val block = DisplayMasker.scanSqlText(W2_SQL, DisplayMasker.MaskMode.BLOCK, allow)
        assertTrue(block.maskedSql.contains("3"))
        assertFalse(block.maskedSql.contains("13812345678"))
        assertFalse(block.maskedSql.contains("p@ss"))
        // 既有 maskSqlText/maskDisplay 语义不变（= MASK）
        assertEquals(masked.maskedSql, DisplayMasker.maskSqlText(W2_SQL))
        assertEquals(masked.maskedSql, DisplayMasker.maskDisplay(W2_SQL, DisplayMasker.MaskMode.MASK, allow))
    }

    @Test
    fun testParseMaskMode非法串回落MASK() {
        assertEquals(DisplayMasker.MaskMode.MASK, DisplayMasker.parseMaskMode(null))
        assertEquals(DisplayMasker.MaskMode.MASK, DisplayMasker.parseMaskMode(""))
        assertEquals(DisplayMasker.MaskMode.MASK, DisplayMasker.parseMaskMode("bogus"))
        assertEquals(DisplayMasker.MaskMode.OFF, DisplayMasker.parseMaskMode("off"))
        assertEquals(DisplayMasker.MaskMode.WARN, DisplayMasker.parseMaskMode(" WARN "))
        assertEquals(DisplayMasker.MaskMode.BLOCK, DisplayMasker.parseMaskMode("Block"))
    }

    // ---------- T-MagpieGems W2：Allow 白名单优先于一切识别 ----------

    @Test
    fun test白名单优先_关键词hint命中也豁免() {
        val allow = DisplayMasker.parseAllowList("mobile_register\nmobile")
        // hint=mobile 本是关键词命中列，但白名单含 mobile → 原样（连形态判定都跳过）
        assertEquals("13812345678", DisplayMasker.mask("mobile", "13812345678", allow))
        // 大小写不敏感 contains：hint=mobile_register 命中 mobile
        assertEquals("13812345678", DisplayMasker.mask("MOBILE_REGISTER", "13812345678", allow))
        // 逗号分隔同样解析
        // 逗号分隔同样解析：hint 含白名单项 foo → 豁免（该 hint 不在关键词表，纯验白名单解析）
        assertEquals("keepme", DisplayMasker.mask("nickname_foo", "keepme", DisplayMasker.parseAllowList("foo,bar,baz")))
        // 白名单外的列照常打码
        assertEquals("***", DisplayMasker.mask("password", "x", allow))
        // SQL 文本级同口径：白名单列豁免、他列照打
        val scan = DisplayMasker.scanSqlText(
            "SELECT * FROM t WHERE mobile_register = '13812345678' AND phone = '13900000000'",
            DisplayMasker.MaskMode.MASK, allow,
        )
        assertTrue(scan.maskedSql.contains("mobile_register = '13812345678'"))
        assertTrue(scan.maskedSql.contains("phone = '***'"))
        // 裸数字无 hint，白名单豁免不了（对齐旧 maskSqlText 无条件替换语义）
        val bare = DisplayMasker.scanSqlText(
            "SELECT * FROM t WHERE age = 13812345678", DisplayMasker.MaskMode.MASK, allow,
        )
        assertTrue(bare.maskedSql.contains("age = ***"))
        assertEquals(1, bare.counts[DisplayMasker.CAT_MOBILE])
    }

    @Test
    fun test白名单豁免后BLOCK计数为0按OFF处理() {
        val allow = listOf("phone")
        val sql = "SELECT * FROM t WHERE phone = '13812345678'"
        val scan = DisplayMasker.scanSqlText(sql, DisplayMasker.MaskMode.BLOCK, allow)
        assertEquals(sql, scan.maskedSql)
        assertTrue(scan.counts.isEmpty())
    }

    // ---------- T-MagpieGems W2：counts 不泄密 + 同值同输出一致性 ----------

    @Test
    fun testCounts不含任何原值片段() {
        val scan = DisplayMasker.scanSqlText(W2_SQL, DisplayMasker.MaskMode.WARN, emptyList())
        // WARN 的 maskedSql=原文（屏显语义）——不泄密红线约束的是 counts 与占位/警示文案
        val countsText = scan.counts.keys.toString() + scan.counts.values.toString()
        assertFalse(countsText.contains("13812345678"))
        assertFalse(countsText.contains("p@ss"))
        assertFalse(countsText.contains("110101199001011234"))
        assertTrue(scan.counts.keys.all {
            it == DisplayMasker.CAT_SENSITIVE_COLUMN || it == DisplayMasker.CAT_ID_CARD || it == DisplayMasker.CAT_MOBILE
        })
        // MASK 产物全文（含计数拼接）不含原值
        val masked = DisplayMasker.scanSqlText(W2_SQL, DisplayMasker.MaskMode.MASK, emptyList())
        val maskedAll = masked.maskedSql + masked.counts.keys
        assertFalse(maskedAll.contains("13812345678"))
        assertFalse(maskedAll.contains("p@ss"))
        assertFalse(maskedAll.contains("110101199001011234"))
    }

    @Test
    fun test同值同输出一致性_两次独立调用与同文两处() {
        // 两次独立调用：同一 SQL 输出必须逐字相同
        val a = DisplayMasker.maskSqlText(W2_SQL)
        val b = DisplayMasker.maskSqlText(W2_SQL)
        assertEquals(a, b)
        // 同一 SQL 两处同值：输出形态相同（同值同假名——本实现为恒定 "***"，天然一致）
        val sql = "SELECT * FROM t WHERE phone = '13812345678' OR backup_phone = '13812345678'"
        val masked = DisplayMasker.maskSqlText(sql)
        val occurrences = masked.split("'***'").size - 1
        assertEquals("同值两处都应打码且形态一致", 2, occurrences)
        assertFalse(masked.contains("13812345678"))
    }

    @Test
    fun testWarnLine只含类别与次数() {
        val counts = mapOf(
            DisplayMasker.CAT_ID_CARD to 2,
            DisplayMasker.CAT_MOBILE to 1,
            DisplayMasker.CAT_SENSITIVE_COLUMN to 3,
        )
        val line = DisplayMasker.warnLine(counts)
        assertTrue(line!!.contains("2"))
        assertTrue(line.contains("×3"))
        assertFalse(line.contains("13812345678"))
        assertNull(DisplayMasker.warnLine(emptyMap()))
    }

    @Test
    fun test_非INSERT语句_旧规则不变() {
        assertEquals(
            "SELECT * FROM t WHERE password = '***'",
            DisplayMasker.maskSqlText("SELECT * FROM t WHERE password = 'x'"),
        )
    }
}
