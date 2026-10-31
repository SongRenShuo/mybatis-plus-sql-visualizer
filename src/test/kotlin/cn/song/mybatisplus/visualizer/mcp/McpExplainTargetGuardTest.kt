package cn.song.mybatisplus.visualizer.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-Fr45DmGuard S1（FR-0045 第 4 项 b）：`sqlv_explain` 的非关系型数据源早拒。
 *
 * 报障链路：Redis/Mongo 数据源在数据源列表里是合法条目，`McpDialectDetector.URL_PATTERNS` 不认
 * `jdbc:redis:` → `guessFromUrl` 返回 null → `resolve()` 兜底 MySQL → `EXPLAIN SELECT …` 被发给
 * Redis，用户收到 `JedisDataException`。守卫在方言派发**之前**按 URL 子协议拒绝，给 agent 结构化错误。
 *
 * 判定复用 `sql/DataSourceDialectClassification.isNonSqlUrl`（`NON_SQL_KEYS` 单一事实源，不复制键表）；
 * 本类两条腿：
 *  1. **纯逻辑**：`ExplainTargetGuard.checkDataSourceUrl` 的命中/放行形态（含大小写、前后空格、空值）；
 *  2. **接线守卫**（源码扫描）：`resolveExplainTarget` 必须在拿到 url 之后、`McpDialectDetector.resolve`
 *     之前调用守卫——`resolveExplainTarget` 依赖 Project/平台，纯逻辑用例够不着调用点，摘掉接线后
 *     只有源码断言能变红（反向验证：注释掉调用行 → 本类 `接线守卫` 失败）。
 *
 * 文案红线：错误文本只带**子协议**，绝不回显 URL（其中的账号/口令/主机不得泄漏）。
 */
class McpExplainTargetGuardTest {

    // ---------- 纯逻辑：命中 ----------

    @Test
    fun `redis URL 命中且给出结构化错误码`() {
        val error = ExplainTargetGuard.checkDataSourceUrl("jdbc:redis://121.40.45.91:6379/1")
        assertTrue("redis 子协议必须早拒，实际: $error", error != null)
        assertTrue("错误码前缀: $error", error!!.startsWith("[${McpToolErrors.NOT_A_SQL_DATASOURCE}]"))
        assertTrue("文案须点明不是关系型 SQL 库: $error", error.contains("not a relational SQL database"))
        assertTrue("文案须带实际子协议: $error", error.contains("'redis'"))
        assertTrue("文案须指引改用关系型数据源: $error", error.contains("relational data source"))
    }

    @Test
    fun `mongodb URL 命中`() {
        val error = ExplainTargetGuard.checkDataSourceUrl("jdbc:mongodb://10.0.0.1:27017/app")
        assertTrue("mongodb 子协议必须早拒", error != null)
        assertTrue(error!!.contains("'mongodb'"))
    }

    @Test
    fun `错误文案不泄漏账号口令与主机`() {
        val error = ExplainTargetGuard.checkDataSourceUrl(
            "jdbc:redis://admin:s3cr3t-pass@10.9.8.7:6379/2",
        )!!
        assertFalse("不得回显用户名: $error", error.contains("admin"))
        assertFalse("不得回显口令: $error", error.contains("s3cr3t-pass"))
        assertFalse("不得回显主机: $error", error.contains("10.9.8.7"))
    }

    @Test
    fun `大小写与前后空格形态同样命中`() {
        val error = ExplainTargetGuard.checkDataSourceUrl("  JDBC:REDIS://h:6379/0  ")
        assertTrue("大小写/空格不得绕过守卫", error != null)
        assertTrue("子协议归一为小写: $error", error!!.contains("'redis'"))
    }

    // ---------- 纯逻辑：放行（正对照，防误伤关系型库） ----------

    @Test
    fun `关系型 SQL 库不命中`() {
        listOf(
            "jdbc:mysql://h:3306/db",
            "jdbc:dm://114.66.55.245:20096",
            "jdbc:postgresql://h:5432/db",
            "jdbc:mariadb://h:3306/db",
            "jdbc:oracle:thin:@h:1521:orcl",
            "jdbc:sqlserver://h:1433;databaseName=db",
            "jdbc:kingbase8://h:54321/db",
            "jdbc:opengauss://h:8000/db",
        ).forEach { assertNull("$it 不应被非 SQL 守卫拦截", ExplainTargetGuard.checkDataSourceUrl(it)) }
    }

    @Test
    fun `未知 SQL 库与空值放行_不猜`() {
        // 插件未收录的 SQL 库（ClickHouse）不是 NON_SQL 家族——守卫不越权，留给方言派发既有语义。
        assertNull(ExplainTargetGuard.checkDataSourceUrl("jdbc:clickhouse://h:8123/db"))
        // 取不到 URL（Community / 反射失败）不猜、放行（fail-open 到既有链路）。
        assertNull(ExplainTargetGuard.checkDataSourceUrl(null))
        assertNull(ExplainTargetGuard.checkDataSourceUrl(""))
        assertNull(ExplainTargetGuard.checkDataSourceUrl("   "))
        // 非 jdbc 前缀（平台特殊连接串形态）同样不猜。
        assertNull(ExplainTargetGuard.checkDataSourceUrl("redis://h:6379/0"))
    }

    @Test
    fun `错误码常量字面量`() {
        assertTrue(McpToolErrors.NOT_A_SQL_DATASOURCE == "NOT_A_SQL_DATASOURCE")
    }

    // ---------- 接线守卫（源码扫描） ----------

    /**
     * `resolveExplainTarget` 的判定序必须是：取 url → 非 SQL 守卫 → 方言派发。
     *
     * 摘掉调用点（或把它挪到 `resolve` 之后）本用例即红——纯逻辑用例对调用点无感知，
     * 这条是「守卫真的接线了」的唯一可执行证据（与 `McpErrorPositionTextTest` 同款纪律：
     * 只钉文案不钉接线＝空绿）。
     */
    @Test
    fun `接线守卫-取 url 之后与方言派发之前调用 checkDataSourceUrl`() {
        val source = stripComments(explainToolSource().readText())
        val urlIndex = source.indexOf("getConnectionConfig")
        val guardIndex = source.indexOf("ExplainTargetGuard.checkDataSourceUrl(")
        val resolveIndex = source.indexOf("McpDialectDetector.resolve(")
        assertTrue("resolveExplainTarget 应经 getConnectionConfig 取 url（锚点缺失，源码形态已变）", urlIndex >= 0)
        assertTrue("resolveExplainTarget 应调用 ExplainTargetGuard.checkDataSourceUrl", guardIndex >= 0)
        assertTrue("方言派发锚点缺失（源码形态已变）", resolveIndex >= 0)
        assertTrue(
            "非 SQL 守卫必须排在取 url 之后（否则拿不到子协议）",
            guardIndex > urlIndex,
        )
        assertTrue(
            "非 SQL 守卫必须排在 McpDialectDetector.resolve 之前（否则 MySQL 兜底已发生）",
            guardIndex < resolveIndex,
        )
    }

    private fun explainToolSource(): File {
        val path = "src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerExplainTool.kt"
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（$path 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    /** 剥离 block 注释与行注释（`McpScopeSourceScanTest` 同款）。 */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
}
