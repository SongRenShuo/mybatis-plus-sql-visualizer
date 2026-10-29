package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [JdbcUrlDialects] 纯逻辑测试（T-AgentDialectLoop）：URL 子协议 → 目标库显示名映射。
 * 覆盖各方言对应子协议 + 国产/兼容形态 + 畸形输入容错。
 *
 * R32 弱形态专区：[JdbcUrlDialects.badgeName]/[JdbcUrlDialects.targetOf]——url 解析真值
 * （库名@host:port）+ 方言名合成；**凭据串绝不入标注**（user/password 参数与 Oracle thin
 * 登录前缀解析期剔除，测试夹具一律假凭据并断言不泄漏）。
 */
class JdbcUrlDialectsTest {

    @Test
    fun `九方言子协议映射`() {
        assertEquals("MySQL", JdbcUrlDialects.displayName("jdbc:mysql://localhost:3306/db"))
        assertEquals("MariaDB", JdbcUrlDialects.displayName("jdbc:mariadb://localhost:3306/db"))
        assertEquals("PostgreSQL", JdbcUrlDialects.displayName("jdbc:postgresql://localhost:5432/db"))
        assertEquals("Oracle", JdbcUrlDialects.displayName("jdbc:oracle:thin:@localhost:1521:orcl"))
        assertEquals("SQL Server", JdbcUrlDialects.displayName("jdbc:sqlserver://localhost:1433;databaseName=db"))
        assertEquals("H2", JdbcUrlDialects.displayName("jdbc:h2:mem:test"))
        assertEquals("SQLite", JdbcUrlDialects.displayName("jdbc:sqlite:test.db"))
        assertEquals("DM（达梦）", JdbcUrlDialects.displayName("jdbc:dm://localhost:5236"))
        assertEquals("KingBase（人大金仓）", JdbcUrlDialects.displayName("jdbc:kingbase8://localhost:54321/db"))
    }

    @Test
    fun `国产与兼容形态`() {
        assertEquals("KingBase（人大金仓）", JdbcUrlDialects.displayName("jdbc:kingbase://localhost/db"))
        assertEquals("openGauss", JdbcUrlDialects.displayName("jdbc:opengauss://localhost:5432/db"))
        assertEquals("GBASE", JdbcUrlDialects.displayName("jdbc:gbasedbt-sqli://localhost:9088/db"))
        assertEquals("HSQL", JdbcUrlDialects.displayName("jdbc:hsqldb:mem:test"))
        // T-DialectXcFamily（O-63）：OceanBase 新增角标；T-GaussDbDialect（券 04）起华为 GaussDB
        // 前缀独立成角标（原与 opengauss 同归 openGauss，D2=A 已撤销）
        assertEquals("OceanBase", JdbcUrlDialects.displayName("jdbc:oceanbase://localhost:2881/db"))
        assertEquals("GaussDB", JdbcUrlDialects.displayName("jdbc:gaussdb://localhost:8000/db"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals("MySQL", JdbcUrlDialects.displayName("JDBC:MySQL://localhost/db"))
        assertEquals("DM（达梦）", JdbcUrlDialects.displayName("JDBC:DM://localhost"))
    }

    @Test
    fun `畸形与非jdbc输入返回null`() {
        assertNull("null URL", JdbcUrlDialects.displayName(null))
        assertNull("空白 URL", JdbcUrlDialects.displayName("  "))
        assertNull("非 jdbc 前缀", JdbcUrlDialects.displayName("mysql://localhost"))
        assertNull("未知子协议", JdbcUrlDialects.displayName("jdbc:fakemysql://localhost"))
        assertNull("仅前缀无子协议", JdbcUrlDialects.displayName("jdbc:"))
    }

    // ---------- R32 弱形态：url 解析真值充实角标（badgeName/targetOf） ----------

    @Test
    fun `R32_MySQL路径形态_库名host端口与方言合成`() {
        assertEquals(
            "MySQL · jpa_demo@localhost:3306",
            JdbcUrlDialects.badgeName("jdbc:mysql://localhost:3306/jpa_demo?useUnicode=true&characterEncoding=utf8"),
        )
    }

    @Test
    fun `R32_凭据参数绝不入标注`() {
        val badge = JdbcUrlDialects.badgeName(
            "jdbc:mysql://dbhost.example:3306/shop?user=app&password=s3cret",
        )
        assertEquals("MySQL · shop@dbhost.example:3306", badge)
        assertFalse("password 值不得泄漏", badge!!.contains("s3cret"))
        assertFalse("user 参数值不得泄漏", badge.contains("app"))
        assertFalse("password 参数名不得泄漏", badge.contains("password"))
    }

    @Test
    fun `R32_authority内联凭据剔除`() {
        val badge = JdbcUrlDialects.badgeName("jdbc:mysql://root:pw123@dbhost.example:3306/shop")
        assertEquals("MySQL · shop@dbhost.example:3306", badge)
        assertFalse("内联 user 不得泄漏", badge!!.contains("root"))
        assertFalse("内联 password 不得泄漏", badge.contains("pw123"))
    }

    @Test
    fun `R32_Oracle_thin_SID形态`() {
        val badge = JdbcUrlDialects.badgeName("jdbc:oracle:thin:scott/tiger@dbhost.example:1521:ORCL")
        assertEquals("Oracle · ORCL@dbhost.example:1521", badge)
        assertFalse("thin 登录前缀 user 不得泄漏", badge!!.contains("scott"))
        assertFalse("thin 登录前缀 password 不得泄漏", badge.contains("tiger"))
    }

    @Test
    fun `R32_Oracle_thin_service_name形态`() {
        assertEquals(
            "Oracle · orclsvc@dbhost.example:1521",
            JdbcUrlDialects.badgeName("jdbc:oracle:thin:@//dbhost.example:1521/orclsvc"),
        )
    }

    @Test
    fun `R32_Oracle_TNS长描述不猜_保留纯方言名`() {
        assertEquals(
            "Oracle",
            JdbcUrlDialects.badgeName(
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=dbhost.example)(PORT=1521))" +
                    "(CONNECT_DATA=(SERVICE_NAME=orclsvc)))",
            ),
        )
    }

    @Test
    fun `R32_SQLServer分号参数形态_库名只认databaseName键`() {
        val badge = JdbcUrlDialects.badgeName(
            "jdbc:sqlserver://dbhost.example:1433;databaseName=dbx;user=sa;password=hunter2",
        )
        assertEquals("SQL Server · dbx@dbhost.example:1433", badge)
        assertFalse("user 参数值不得泄漏", badge!!.contains("sa"))
        assertFalse("password 值不得泄漏", badge.contains("hunter2"))
        assertFalse("参数串不得整体入标注", badge.contains("user="))
    }

    @Test
    fun `R32_无库段_仅host端口`() {
        assertEquals("DM（达梦） · localhost:5236", JdbcUrlDialects.badgeName("jdbc:dm://localhost:5236"))
    }

    @Test
    fun `R32_未知方言但有连接事实_输出真值`() {
        assertEquals("db9@h1.example", JdbcUrlDialects.badgeName("jdbc:fakesql://h1.example/db9"))
    }

    @Test
    fun `R32_嵌入形态无连接事实_保持纯方言名`() {
        assertEquals("H2", JdbcUrlDialects.badgeName("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"))
        assertEquals("SQLite", JdbcUrlDialects.badgeName("jdbc:sqlite:test.db"))
    }

    @Test
    fun `R32_畸形输入返回null`() {
        assertNull("null URL", JdbcUrlDialects.badgeName(null))
        assertNull("空白 URL", JdbcUrlDialects.badgeName("  "))
        assertNull("非 jdbc 前缀", JdbcUrlDialects.badgeName("mysql://localhost/db"))
        assertNull("仅前缀无子协议", JdbcUrlDialects.badgeName("jdbc:"))
    }

    @Test
    fun `R32_targetOf_解析字段判别`() {
        val t = JdbcUrlDialects.targetOf("jdbc:postgresql://dbhost.example:5432/orders?password=x")!!
        assertEquals("orders", t.dbName)
        assertEquals("dbhost.example", t.host)
        assertEquals("5432", t.port)
        // 嵌入形态（H2 mem/file、SQLite 文件）无 host 可标 → null
        assertNull(JdbcUrlDialects.targetOf("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"))
        assertNull(JdbcUrlDialects.targetOf("jdbc:sqlite:test.db"))
        assertNull(JdbcUrlDialects.targetOf(null))
    }
}
