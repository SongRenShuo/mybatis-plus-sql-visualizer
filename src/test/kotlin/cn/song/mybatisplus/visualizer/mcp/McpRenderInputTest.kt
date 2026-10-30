package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-McpToolset 方言推断与 render_sql 入参校验单测（纯逻辑层）。
 */
class McpDialectDetectorTest {

    // ------------------------------------------------------------------
    // URL 推断
    // ------------------------------------------------------------------

    @Test
    fun `URL推断_主流与国产库`() {
        assertEquals("MySQL", McpDialectDetector.guessFromUrl("jdbc:mysql://localhost:3306/db"))
        assertEquals("MariaDB", McpDialectDetector.guessFromUrl("jdbc:mariadb://localhost/db"))
        assertEquals("PostgreSQL", McpDialectDetector.guessFromUrl("jdbc:postgresql://localhost:5432/db"))
        assertEquals("Oracle", McpDialectDetector.guessFromUrl("jdbc:oracle:thin:@host:1521:orcl"))
        assertEquals("SQL Server", McpDialectDetector.guessFromUrl("jdbc:sqlserver://host:1433;databaseName=db"))
        assertEquals("H2", McpDialectDetector.guessFromUrl("jdbc:h2:mem:test"))
        assertEquals("SQLite", McpDialectDetector.guessFromUrl("jdbc:sqlite:app.db"))
        assertEquals("DM（达梦）", McpDialectDetector.guessFromUrl("jdbc:dm://localhost:5236"))
        assertEquals("KingBase（人大金仓）", McpDialectDetector.guessFromUrl("jdbc:kingbase8://localhost:54321/db"))
        // T-DialectXcFamily（O-63）：信创双备；T-GaussDbDialect（券 04）起 gaussdb 归独立方言
        assertEquals("OceanBase", McpDialectDetector.guessFromUrl("jdbc:oceanbase://localhost:2881/db"))
        assertEquals("openGauss", McpDialectDetector.guessFromUrl("jdbc:opengauss://localhost:5432/db"))
        assertEquals("GaussDB", McpDialectDetector.guessFromUrl("jdbc:gaussdb://localhost:8000/db"))
        // T-XcDialects3（券 03）：瀚高 / 海量；dws:iam 是海量驱动 parseURL 认可的第二前缀
        assertEquals("HighGo", McpDialectDetector.guessFromUrl("jdbc:highgo://localhost:5866/db"))
        assertEquals("Vastbase", McpDialectDetector.guessFromUrl("jdbc:vastbase://localhost:5432/db"))
        assertEquals("Vastbase", McpDialectDetector.guessFromUrl("jdbc:dws:iam://localhost:5432/db"))
    }

    @Test
    fun `URL推断_未知与空值回落null`() {
        assertNull(McpDialectDetector.guessFromUrl(null))
        assertNull(McpDialectDetector.guessFromUrl(""))
        assertNull(McpDialectDetector.guessFromUrl("jdbc:unknowndb://x"))
    }

    // ------------------------------------------------------------------
    // 显式指定归一化
    // ------------------------------------------------------------------

    @Test
    fun `显式方言_大小写与中英别名归一`() {
        assertEquals("MySQL", McpDialectDetector.normalize("mysql"))
        assertEquals("MySQL", McpDialectDetector.normalize(" MySQL "))
        assertEquals("PostgreSQL", McpDialectDetector.normalize("postgres"))
        assertEquals("PostgreSQL", McpDialectDetector.normalize("pg"))
        assertEquals("SQL Server", McpDialectDetector.normalize("mssql"))
        assertEquals("DM（达梦）", McpDialectDetector.normalize("达梦"))
        assertEquals("KingBase（人大金仓）", McpDialectDetector.normalize("金仓"))
        // T-DialectXcFamily（O-63）：信创双备显式名归一；T-GaussDbDialect（券 04）起 gaussdb 独立
        assertEquals("OceanBase", McpDialectDetector.normalize("oceanbase"))
        assertEquals("openGauss", McpDialectDetector.normalize("opengauss"))
        assertEquals("GaussDB", McpDialectDetector.normalize("gaussdb"))
        assertEquals("GaussDB", McpDialectDetector.normalize("高斯"))
        // T-XcDialects3（券 03）：瀚高两种中文写法（官方「瀚高」/ 用户常写「翰高」）都收；
        // 海量收厂商中文名「海量」
        assertEquals("HighGo", McpDialectDetector.normalize("highgo"))
        assertEquals("HighGo", McpDialectDetector.normalize("HighGo"))
        assertEquals("HighGo", McpDialectDetector.normalize("瀚高"))
        assertEquals("HighGo", McpDialectDetector.normalize("翰高"))
        assertEquals("Vastbase", McpDialectDetector.normalize("vastbase"))
        assertEquals("Vastbase", McpDialectDetector.normalize("海量"))
    }

    @Test
    fun `显式方言_不可识别`() {
        assertNull(McpDialectDetector.normalize("oracle-no-such-variant-xyz"))
        assertNull(McpDialectDetector.normalize("madeupdb"))
        assertTrue(McpDialectDetector.isRecognized(null))
        assertTrue(McpDialectDetector.isRecognized("mysql"))
        assertFalse(McpDialectDetector.isRecognized("madeupdb"))
    }

    @Test
    fun `isRecognized_空白视为未指定放行`() {
        // T-McpRenderDialectFix：平台 JSON 绑定把省略的可选参数落成 Kotlin 默认值 ""——空白
        // 必须视为「未指定」放行（真机实测省略 dialect 曾因 "" 被判不可识别必报
        // INVALID_ARGUMENT）；resolve 链对空白走 URL 推断 + 默认 MySQL 兜底。
        assertTrue(McpDialectDetector.isRecognized(""))
        assertTrue(McpDialectDetector.isRecognized("   "))
        assertEquals("MySQL", McpDialectDetector.resolve("", "jdbc:mysql://h/db"))
        assertEquals("MySQL", McpDialectDetector.resolve("", null))
    }

    // ------------------------------------------------------------------
    // 解析优先级：显式可识别 > URL > 默认 MySQL（不可识别名由 isRecognized 前置校验报错）
    // ------------------------------------------------------------------

    @Test
    fun `resolve_显式优先于URL_默认MySQL`() {
        assertEquals("MySQL", McpDialectDetector.resolve(null, null))
        assertEquals("PostgreSQL", McpDialectDetector.resolve(null, "jdbc:postgresql://h/db"))
        assertEquals("Oracle", McpDialectDetector.resolve("oracle", "jdbc:postgresql://h/db"))
        assertEquals("MySQL", McpDialectDetector.resolve("madeupdb", null))
    }
}

/** sqlv_render_sql 入参形态校验（纯逻辑层）。 */
class McpRenderInputTest {

    @Test
    fun `路径形态_合法与行号`() {
        val (parsed, error) = McpRenderInput.parse("src/main/java/A.java", 12, null, null)
        assertNull(error)
        assertNotNull(parsed)
        assertEquals("src/main/java/A.java", parsed?.filePath)
        assertEquals(12, parsed?.line)
    }

    @Test
    fun `路径形态_行号缺省与非法`() {
        val (noLine, e1) = McpRenderInput.parse("a.xml", null, null, null)
        assertNull(e1)
        assertNull("line=null 表示取首个语句", noLine?.line)

        val (_, e2) = McpRenderInput.parse("a.xml", -1, null, null)
        assertTrue(e2!!.startsWith("[${McpToolErrors.INVALID_ARGUMENT}]"))
        val (_, e3) = McpRenderInput.parse("a.xml", McpRenderInput.MAX_LINE + 1, null, null)
        assertNotNull(e3)
    }

    @Test
    fun `片段形态_仅xml_mapper`() {
        val (parsed, error) = McpRenderInput.parse(null, null, "<select id=\"q\">SELECT 1</select>", "xml_mapper")
        assertNull(error)
        assertEquals("xml_mapper", parsed?.sourceType)
        assertEquals("<select id=\"q\">SELECT 1</select>", parsed?.xmlSnippet)

        val (_, err) = McpRenderInput.parse(null, null, "<select/>", "java_snippet")
        assertNotNull("java 片段不支持应报 INVALID_ARGUMENT", err)
        assertTrue(err!!.contains("java_snippet"))
        assertTrue(err.contains(McpToolErrors.INVALID_ARGUMENT))
    }

    @Test
    fun `两者皆缺_报INVALID_ARGUMENT`() {
        val (parsed, error) = McpRenderInput.parse("", 0, "  ", "")
        assertNull(parsed)
        assertNotNull(error)
        assertTrue(error!!.contains(McpToolErrors.INVALID_ARGUMENT))
        assertTrue(error.contains("filePath") && error.contains("xmlSnippet"))
    }

    @Test
    fun `路径形态优先于片段`() {
        val (parsed, error) = McpRenderInput.parse("a.xml", 3, "<select/>", "xml_mapper")
        assertNull(error)
        assertEquals("a.xml", parsed?.filePath)
        assertNull(parsed?.xmlSnippet)
    }

    @Test
    fun `路径归一化_拒绝绝对路径与上跳`() {
        assertNull(McpRenderInput.normalizeFilePath("../etc/passwd"))
        assertNull(McpRenderInput.normalizeFilePath("a/../../b"))
        assertNull(McpRenderInput.normalizeFilePath("C:/Windows/system32"))
        assertEquals("src/a.xml", McpRenderInput.normalizeFilePath("src\\a.xml"))
        assertEquals("src/a.xml", McpRenderInput.normalizeFilePath("/src/a.xml"))
    }
}

/** 错误码结构化形态（DBX `[CODE] message` 同款）。 */
class McpToolErrorsTest {

    @Test
    fun `结构化错误码形态`() {
        assertEquals("[MCP_READ_ONLY] only select", McpToolErrors.code(McpToolErrors.READ_ONLY, "only select"))
        assertTrue(McpToolErrors.code(McpToolErrors.SQL_BLOCKED, "x").startsWith("[SQL_BLOCKED] "))
    }
}
