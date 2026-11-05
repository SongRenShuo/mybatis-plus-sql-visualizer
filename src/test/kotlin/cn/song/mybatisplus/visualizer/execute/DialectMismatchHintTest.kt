package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-049：空 Runner「方言不匹配」态的 URL 推断与文案渲染（纯逻辑）。
 *
 * 现场（2026-11-04 用户真机）：12 个源一长串、用户不知道改哪一个；文案还写着「执行失败」。
 * 本测试钉住三件事：
 *  1. 只按 **JDBC URL 子协议**挑「看起来是目标方言」的源，挑不出就返回空（不猜）；
 *  2. 不做方言家族合并（MariaDB ≠ MySQL）——归因准确性优先；
 *  3. 可见行/详文三种口径（unique/multiple/none）都能渲染，且多源时逐个点名有上限、其余折叠。
 */
class DialectMismatchHintTest {

    private fun src(name: String, url: String?, handle: Any? = null) =
        RegisteredJdbcSource(name = name, url = url, handle = handle)

    private fun mysql(name: String) = src(name, "jdbc:mysql://localhost:3306/$name")

    @Test
    fun test唯一MySQL源_点名该源并折叠其余() {
        val sources = listOf(
            mysql("mp_demo@localhost"),
            src("pg_demo@localhost", "jdbc:postgresql://localhost:5432/db"),
            src("mpj_dm@114.66.55.245", "jdbc:dm://114.66.55.245:5236"),
        )

        val likely = inferLikelyDialectSources("MySQL", sources)

        assertEquals(1, likely.size)
        assertEquals("mp_demo@localhost", likely.single().name)
        val hint = DialectMismatchHint("MySQL", sources.size, likely)
        assertEquals(2, hint.otherCount)
        val status = hint.statusMessage()
        assertTrue("可见行应点名唯一推断源: $status", status.contains("mp_demo@localhost"))
        assertTrue("可见行应带方言名: $status", status.contains("MySQL"))
    }

    @Test
    fun test多个MySQL源_全部点名且otherCount正确() {
        val sources = listOf(
            mysql("mysql_a"),
            mysql("mysql_b"),
            mysql("mysql_c"),
            src("pg_demo", "jdbc:postgresql://localhost/db"),
        )

        val likely = inferLikelyDialectSources("MySQL", sources)

        assertEquals(listOf("mysql_a", "mysql_b", "mysql_c"), likely.map { it.name })
        val hint = DialectMismatchHint("MySQL", sources.size, likely)
        assertEquals(1, hint.otherCount)
        val status = hint.statusMessage()
        assertTrue("多源可见行应报数量: $status", status.contains("3"))
        assertTrue("多源可见行应带方言名: $status", status.contains("MySQL"))
        assertTrue("详文应点名每个推断源: ${hint.detailMessage()}", hint.detailMessage().contains("mysql_c"))
    }

    @Test
    fun test推断不出_不猜且文案走unknown口径() {
        val sources = listOf(
            src("legacy", "jdbc:derby:memory:db"),
            src("no-url", null),
            src("weird", "not-a-jdbc-url"),
        )

        assertTrue(inferLikelyDialectSources("MySQL", sources).isEmpty())
        val hint = DialectMismatchHint("MySQL", sources.size, emptyList())
        assertNull(hint.uniqueLikely)
        val status = hint.statusMessage()
        assertTrue("unknown 口径仍要说明目标方言: $status", status.contains("MySQL"))
        assertTrue("总数应留在详文里: ${hint.detailMessage()}", hint.detailMessage().contains("3"))
    }

    @Test
    fun test方言名括号后缀参与归一_不因渲染名差异漏配() {
        // UrlSubprotocolDialects 给 "DM（达梦）"，运行时渲染名可能只是 "DM"（反之亦然）。
        val sources = listOf(src("mpj_dm@host", "jdbc:dm://host:5236"))

        assertEquals(1, inferLikelyDialectSources("DM", sources).size)
        assertEquals(1, inferLikelyDialectSources("DM（达梦）", sources).size)
        assertEquals("dm", normalizeDialectNameForMatch("DM（达梦）"))
        assertEquals("dm", normalizeDialectNameForMatch("DM"))
    }

    @Test
    fun test平台languageId别名能对上URL推断_不因TSQL漏点名() {
        // 回退路径的方言名可能来自平台 language id "TSQL"，URL 映射给的是 "SQL Server"。
        val sources = listOf(src("mssql@host", "jdbc:sqlserver://host:1433;databaseName=db"))

        assertEquals(1, inferLikelyDialectSources("TSQL", sources).size)
        assertEquals(1, inferLikelyDialectSources("SQL Server", sources).size)
        assertEquals(1, inferLikelyDialectSources("mssql", sources).size)
    }

    @Test
    fun testMariaDB不并入MySQL_归因准确性优先() {
        val sources = listOf(src("maria", "jdbc:mariadb://localhost:3306/db"))

        assertTrue(inferLikelyDialectSources("MySQL", sources).isEmpty())
        assertEquals(1, inferLikelyDialectSources("MariaDB", sources).size)
    }

    @Test
    fun test推断源过多时详文只点名前五个_其余折叠() {
        val sources = (1..7).map { mysql("mysql_$it") }

        val hint = DialectMismatchHint("MySQL", sources.size, inferLikelyDialectSources("MySQL", sources))
        val detail = hint.detailMessage()

        assertTrue("前五个应点名: $detail", detail.contains("mysql_5"))
        assertFalse("第六个应被折叠: $detail", detail.contains("mysql_6"))
        assertTrue("折叠数量应出现在详文: $detail", detail.contains("2"))
        assertEquals(0, hint.otherCount)
    }

    @Test
    fun test空方言名不推断_零源不崩() {
        val sources = listOf(mysql("mysql_a"))

        assertTrue(inferLikelyDialectSources("  ", sources).isEmpty())
        val hint = DialectMismatchHint("MySQL", 0, emptyList())
        assertTrue(hint.statusMessage().isNotBlank())
        assertTrue(hint.detailMessage().isNotBlank())
    }
}