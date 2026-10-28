package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 数据源 JDBC 直连烟雾测试（M38 / P-立项-06 方案 B）。
 *
 * 绕开 DBN 子进程驱动加载(M36.5.23d 已知限制)，直接用 mysql-connector-j(testImplementation) +
 * DriverManager 验证数据库连通性。读取 demo 项目 `.idea/dataSources.xml` 的连接信息，
 * 加载驱动后执行一条验证 SQL，确认：驱动可加载 → 连接可达 → SQL 可执行。
 *
 * 环境门控＝「显式绿跳」：平台 runner 把 AssumptionViolatedException 记为 failure 红任务
 *（DbConsoleIntegrationTest T-ExecDocGate 同款定论），故 demo 无数据源、或数据源驱动不在测试
 * classpath 时 println + return；驱动可加载的数据源连不上仍 fail（本地 MySQL 需在跑）。
 *
 * 可验证范围（T-UiFixGate9）：**确定性收窄为 localhost/127.0.0.1 源**——demo 的远程测试库
 * （如 dbx_test@dbx-test-host，用户保留用于沙盒真机验收）不是 demo 配置健全性的断言范围，
 * 门禁依赖远程可达＝网络 flake 面；按 host 过滤并打印注明，禁「连不上才跳过」的条件通过。
 *
 * 多方言扩展：demo dataSources.xml 增加本机 PG/Oracle/DM/KingBase 数据源且驱动作为
 * testImplementation 引入后，本测试自动纳入验证(对齐 P-立项-05 七家族验证通道)；
 * 远程源一律不在断言范围（见 localVerifiable KDoc）。
 */
class DataSourceJdbcSmokeTest : BasePlatformTestCase() {

    private val demoProjectDir: File
        get() = File(System.getProperty("mpDemoProjectDir", "../mybatis-plus-sql-visualizer-demo"))

    private data class DsCfg(val name: String, val url: String, val user: String, val password: String, val driverClass: String)

    /** 解析 demo dataSources.xml 全部数据源连接信息。 */
    private fun readAllDataSources(): List<DsCfg> {
        val xml = demoProjectDir.resolve(".idea/dataSources.xml")
        if (!xml.isFile) return emptyList()
        val localXml = demoProjectDir.resolve(".idea/dataSources.local.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val result = mutableListOf<DsCfg>()
        val sources = doc.getElementsByTagName("data-source")
        for (i in 0 until sources.length) {
            val node = sources.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            var url: String? = null
            var driver: String? = null
            var user = "root"
            val children = node.childNodes
            for (j in 0 until children.length) {
                when (children.item(j).nodeName) {
                    "jdbc-url" -> url = children.item(j).textContent.trim()
                    "jdbc-driver" -> driver = children.item(j).textContent.trim()
                    "user-name" -> user = children.item(j).textContent.trim()
                }
            }
            if (url == null) continue
            // 读取密码(dataSources.local.xml 或默认 root)
            var password = "root"
            if (localXml.isFile) {
                val local = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(localXml)
                val lnodes = local.getElementsByTagName("data-source")
                for (k in 0 until lnodes.length) {
                    val lname = lnodes.item(k).attributes.getNamedItem("name")?.nodeValue ?: continue
                    if (lname != name) continue
                    val lchildren = lnodes.item(k).childNodes
                    for (l in 0 until lchildren.length) {
                        if (lchildren.item(l).nodeName == "password") {
                            password = lchildren.item(l).textContent.trim().ifEmpty { "root" }
                        }
                    }
                }
            }
            result.add(DsCfg(name, url, user, password, driver ?: guessDriver(url)))
        }
        return result
    }

    private fun guessDriver(url: String): String = when {
        url.contains("mysql", ignoreCase = true) -> "com.mysql.cj.jdbc.Driver"
        url.contains("postgresql", ignoreCase = true) -> "org.postgresql.Driver"
        url.contains("oracle", ignoreCase = true) -> "oracle.jdbc.OracleDriver"
        url.contains("sqlserver", ignoreCase = true) -> "com.microsoft.sqlserver.jdbc.SQLServerDriver"
        url.contains("dameng", ignoreCase = true) -> "dm.jdbc.driver.DmDriver"
        else -> "com.mysql.cj.jdbc.Driver"
    }

    private fun isReachable(cfg: DsCfg): Boolean = runCatching {
        Class.forName(cfg.driverClass)
        DriverManager.getConnection(cfg.url, cfg.user, cfg.password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("SELECT 1")
            }
        }
    }.getOrDefault(false)

    /** jdbc-url 里的 host 部分（`jdbc:xxx://host:port/db?params` → host；非 // 形态返回空串）。 */
    private fun hostOf(jdbcUrl: String): String =
        if (!jdbcUrl.contains("://")) "" else jdbcUrl.substringAfter("://").substringBefore(':').substringBefore('/').substringBefore('?')

    /**
     * T-UiFixGate9：可验证范围**确定性收窄**为 localhost/127.0.0.1（含 ::1）源。
     *
     * 理由：demo 工程 dataSources.xml 新增远程测试库（dbx_test@dbx-test-host，PG16；条目系用户
     * 明令保留用于沙盒真机验收，不得删除）。远程源不是 demo 配置健全性的断言范围，且门禁 JVM
     * 依赖远程可达＝网络 flake 面（20261018 全量门禁 testAllDemoDataSourcesReachable /
     * testExecuteValidationSqlOnEachSource 各 1 红实证——PG 驱动恰在测试 classpath，「驱动可加载」
     * 判据把远程源纳入了直连断言）。收窄按 host 确定性过滤并打印注明，**禁止**做成「连不上才跳过」
     * 的条件通过（那是有网才算数的假绿形态）。
     */
    private fun localVerifiable(sources: List<DsCfg>): List<DsCfg> {
        val remote = mutableListOf<String>()
        val local = sources.filter { cfg ->
            val host = hostOf(cfg.url)
            val isLocal = host == "localhost" || host == "127.0.0.1" || host == "::1"
            if (!isLocal) remote += cfg.name
            isLocal
        }
        if (remote.isNotEmpty()) {
            println("[DS-SMOKE] SKIP(确定性收窄): 远程数据源不在 demo 配置健全性断言范围" +
                "（沙盒真机验收专用，门禁不依赖远程可达）: $remote")
        }
        return local
    }

    @Test
    fun testAllDemoDataSourcesReachable() {
        val sources = readAllDataSources()
        // 环境门控＝「显式绿跳」：平台 runner 把 AssumptionViolatedException 记为 failure 红任务
        //（DbConsoleIntegrationTest T-ExecDocGate 同款定论），故环境不满足时 println + return。
        if (sources.isEmpty()) {
            println("[DS-SMOKE] SKIP: demo 项目 dataSources.xml 未找到任何数据源(路径: ${demoProjectDir.absolutePath})")
            return
        }

        // T-UiFixGate9：先按 host 确定性收窄到本机源（远程源打印注明后排除，见 localVerifiable KDoc），
        // 再沿用既有「驱动不在测试 classpath 的源逐源跳过」门控。
        val verifiable = localVerifiable(sources).filter { runCatching { Class.forName(it.driverClass) }.isSuccess }
        if (verifiable.isEmpty()) {
            println("[DS-SMOKE] SKIP: demo 数据源驱动均不在测试 classpath")
            return
        }

        // 逐一验证连通性（仅对驱动可加载的数据源断言）
        val failures = mutableListOf<String>()
        for (cfg in verifiable) {
            val ok = isReachable(cfg)
            if (!ok) failures += "${cfg.name}(${cfg.url})"
        }
        assertTrue("以下数据源 JDBC 直连失败: $failures", failures.isEmpty())
    }

    @Test
    fun testExecuteValidationSqlOnEachSource() {
        val sources = readAllDataSources()
        if (sources.isEmpty()) {
            println("[DS-SMOKE] SKIP: demo 项目 dataSources.xml 未找到任何数据源")
            return
        }

        // 与 testAllDemoDataSourcesReachable 同口径：先按 host 确定性收窄到本机源（T-UiFixGate9，
        // 见 localVerifiable KDoc），再沿用既有「驱动不在测试 classpath 的数据源逐源跳过」门控
        // （demo 可含 DM / PG 等未作为 testImplementation 引入的数据源，本测试只依赖 mysql-connector-j）；
        // 可验证集为空时整测绿跳而非 fail。
        val verifiable = localVerifiable(sources).filter { runCatching { Class.forName(it.driverClass) }.isSuccess }
        if (verifiable.isEmpty()) {
            println("[DS-SMOKE] SKIP: demo 数据源驱动均不在测试 classpath，无可验证数据源")
            return
        }

        val failures = mutableListOf<String>()
        for (cfg in verifiable) {
            val ok = runCatching {
                Class.forName(cfg.driverClass)
                DriverManager.getConnection(cfg.url, cfg.user, cfg.password).use { conn ->
                    conn.createStatement().use { st ->
                        val rs = st.executeQuery("SELECT 1 AS val")
                        rs.next() && rs.getInt("val") == 1
                    }
                }
            }.getOrDefault(false)
            if (!ok) failures += "${cfg.name}"
        }
        assertTrue("以下数据源执行验证 SQL 失败: $failures", failures.isEmpty())
    }
}
