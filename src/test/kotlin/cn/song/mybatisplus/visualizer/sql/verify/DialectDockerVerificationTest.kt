package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.SqlKeywords.MYSQL_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.ORACLE_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.PG_RESERVED
import cn.song.mybatisplus.visualizer.sql.SqlKeywords.SQLSERVER_RESERVED
import cn.song.mybatisplus.visualizer.sql.verify.DialectVerifySupport.UpsertMode
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * Phase 2（Tier 2）：Docker 真实数据库方言验证（P-立项-05）。
 *
 * 使用 Testcontainers 拉起**真实数据库引擎**，执行与 H2/SQLite 测试相同的插件渲染 SQL 套件
 * （[DialectVerifySupport.runTcSuite] + 保留字全量扫描）——这是「真实环境测试」的可自动化部分，
 * 证据级别最高：
 *
 * | 容器 | 验证方言 | UPSERT |
 * |------|----------|--------|
 * | mysql:8.0 | MySQL | ON DUPLICATE KEY UPDATE + VALUES() |
 * | postgres:16 | PostgreSQL | ON CONFLICT (id) DO UPDATE + EXCLUDED（H2 无法模拟，此处补齐） |
 * | mariadb:11 | MariaDB | 同 MySQL 形态 |
 * | mssql/server:2022 | SQL Server | 无原生 → 降级纯 INSERT |
 * | gvenzl/oracle-free:23 | Oracle | 无原生 → 降级纯 INSERT |
 *
 * 运行条件与策略：
 *  - 本机/CI **无 Docker 时自动跳过**（[assumeTrue] 探测 Docker daemon），日常 `./gradlew test`
 *    零影响；有 Docker 时随全量测试自动执行；
 *  - Oracle/SQL Server 镜像体积大（数 GB）首次拉取慢——本地手动跑时可先
 *    `docker pull` 预热，或用 test/resources/docker-compose-dialects.yml 自建环境后
 *    以 -Dmpvs.dialect.jdbcUrl 等系统属性指向外部库执行（见 [externalEngines]）。
 *
 * DM（达梦）/ KingBase（人大金仓）无公开自动化镜像——真实环境验证需用户侧连接实例执行
 * （文档级验证见 [DialectDocGoldenTest]），本测试不支持。
 *
 * **本类不承担发版判据**（2026-10-31 用户定例：真机方言验证走测试服务器外部实例、DBX 只做临时探测）。
 * 发版门禁改由 `./gradlew dialectExternalVerify` 承担（[DialectExternalMatrixVerificationTest] 读实例
 * 矩阵，产出可归档的 JUnit 计数）；Docker 用例只在本地想验时用 `-PmpvDockerTests=true` 手工开启。
 */
@Suppress("TestClassWithoutTestCases") // 容器用例全部经 Assume 守卫（无 Docker 时静默跳过）
class DialectDockerVerificationTest {

    // ------------------------------------------------------------------
    // Docker 可用性守卫（无 Docker → 测试跳过而非失败）
    // ------------------------------------------------------------------

    private fun assumeDockerAvailable() {
        // Docker 测试默认关闭（20260923 用户定例，构建侧经 mpv-testing 注入该系统属性）：
        // 只有 -PmpvDockerTests=true 才允许触达 daemon。根因=daemon 半活态时下方 ping 探测
        // 会阻塞且无超时（try/catch 只兜异常、兜不住阻塞，20260923 发行门禁实录三片挂起），
        // daemon 活着时真容器验证的 pull/run 耗时也不可控——开关前置后两者都不会发生。
        assumeTrue(
            "Docker 测试未启用（-PmpvDockerTests=true 开启），跳过真实数据库方言验证（H2/SQLite 嵌入式验证已覆盖语法形态）",
            System.getProperty("mpvs.docker.tests.enabled") == "true",
        )
        val available = try {
            DockerClientFactory.instance().client().pingCmd().exec() != null
            true
        } catch (_: Throwable) {
            false
        }
        assumeTrue("Docker 不可用，跳过真实数据库方言验证（H2/SQLite 嵌入式验证已覆盖语法形态）", available)
    }

    // ------------------------------------------------------------------
    // 真实引擎套件
    // ------------------------------------------------------------------

    @Test
    fun `真实MySQL80_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        MySQLContainer<Nothing>("mysql:8.0.36").apply {
            withUrlParam("useSSL", "false")
            withUrlParam("allowPublicKeyRetrieval", "true")
        }.use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "MySQL 8.0 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.MySqlDialect.INSTANCE,
                upsertMode = UpsertMode.REAL,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, MYSQL_RESERVED)
                assertSweepClean("MySQL 8.0", MYSQL_RESERVED.size, failures)
            }
        }
    }

    @Test
    fun `真实PostgreSQL16_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        PostgreSQLContainer<Nothing>("postgres:16.2").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "PostgreSQL 16 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect.INSTANCE,
                upsertMode = UpsertMode.REAL,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, PG_RESERVED)
                assertSweepClean("PostgreSQL 16", PG_RESERVED.size, failures)
            }
        }
    }

    @Test
    fun `真实MariaDB11_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        MariaDBContainer<Nothing>("mariadb:11.2").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "MariaDB 11 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.MariaDbDialect.INSTANCE,
                upsertMode = UpsertMode.REAL,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, spec.dialect.reservedWords)
                assertSweepClean("MariaDB 11", spec.dialect.reservedWords.size, failures)
            }
        }
    }

    @Test
    fun `真实SQLServer2022_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        MSSQLServerContainer<Nothing>("mcr.microsoft.com/mssql/server:2022-CU12-ubuntu-22.04").use { container ->
            container.acceptLicense()
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "SQL Server 2022 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.SqlServerDialect.INSTANCE,
                upsertMode = UpsertMode.DEGRADE_ONLY,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, SQLSERVER_RESERVED)
                assertSweepClean("SQL Server 2022", SQLSERVER_RESERVED.size, failures)
            }
        }
    }

    @Test
    fun `真实Oracle23free_方言套件与保留字扫描`() {
        assumeDockerAvailable()
        OracleContainer("gvenzl/oracle-free:23.5-slim").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "Oracle 23ai Free (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.OracleDialect.INSTANCE,
                upsertMode = UpsertMode.DEGRADE_ONLY,
                // Oracle Free 23 实测支持多行 VALUES（12c 起；原注释「Oracle 不支持多行 VALUES」已过期）
                multiRowValues = true,
            )
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                DialectVerifySupport.runTcSuite(conn, spec)
                val failures = DialectVerifySupport.sweepReservedWords(conn, spec, ORACLE_RESERVED)
                assertSweepClean("Oracle 23ai", ORACLE_RESERVED.size, failures)
            }
        }
    }

    // ------------------------------------------------------------------
    // 外部实例接入（用户侧真实环境：DM / KingBase 及任意自建库）
    // ------------------------------------------------------------------

    /**
     * 外部实例验证入口（无容器依赖，连不上即跳过）：
     *
     * ```
     * ./gradlew test --tests '*DialectDockerVerificationTest*' --no-configuration-cache \
     *   -Dmpvs.dialect.jdbcUrl="jdbc:dm://host:5236" \
     *   -Dmpvs.dialect.user=SYSDBA -Dmpvs.dialect.password=... \
     *   -Dmpvs.dialect.name="DM（达梦）"
     * ```
     *
     * 方言取 [cn.song.mybatisplus.visualizer.sql.DialectRegistry.byName]（名称与 Settings 下拉一致）；
     * 执行与容器/嵌入式完全相同的 TC 套件 + 保留字扫描——DM/KingBase 真实环境验证的标准流程。
     *
     * **必须带 `--no-configuration-cache`**（T-SuiteIdempotent，2026-10-30）：`-Dmpvs.*` 的透传写在
     * `buildSrc/.../mpv-testing.gradle.kts` 的 `tasks.test { System.getProperties()… }` 里，是**配置期**
     * 读取——配置缓存命中时会原样复用上一次写入的 `systemProperty`，于是换库重跑仍在连**上一族**的
     * URL，读数看起来「绿」但验的根本不是这次的目标库（假绿）。缓存未命中时又可能静默换值，读数不可
     * 复现。外部实例验证一律关缓存。
     *
     * 无原生 upsert 的方言（Oracle / SQL Server / 达梦）由 [DialectVerifySupport.upsertModeOf] 自动
     * 降级为只验纯 INSERT 路径；指向 12c 前的 Oracle 另加 `-Dmpvs.dialect.multiRowValues=false`。
     */
    @Test
    fun `外部实例_方言套件与保留字扫描`() {
        val url = System.getProperty("mpvs.dialect.jdbcUrl")
        assumeTrue("未提供 -Dmpvs.dialect.jdbcUrl，跳过外部实例验证", !url.isNullOrBlank())
        val user = System.getProperty("mpvs.dialect.user")
        val password = System.getProperty("mpvs.dialect.password")
        val dialectName = System.getProperty("mpvs.dialect.name") ?: "MySQL"
        val dialect = cn.song.mybatisplus.visualizer.sql.DialectRegistry.INSTANCE.byName(dialectName)
        // 防静默回退（T-SuiteIdempotent）：byName 对未知名称回退 MySQL，名称写错/中文参数被转码时
        // 会拿 MySQL 方言去跑别的库 → 全绿但验错了方言。这里逐字核对，不命中直接失败。
        assertEquals(
            "方言名未逐字命中 DialectRegistry（会静默回退 MySQL，读数无效）：请求 [$dialectName] 实际 [${dialect.name}]",
            dialectName,
            dialect.name,
        )
        val spec = DialectVerifySupport.EngineSpec(
            label = "$dialectName (external)",
            jdbcUrlTemplate = url,
            dialect = dialect,
            // 由方言能力推导（不再硬编码 REAL）：无原生 upsert 的方言（Oracle/SQL Server/达梦）
            // 只验降级纯 INSERT 路径，否则必然踩主键冲突并带崩整段保留字扫描
            upsertMode = DialectVerifySupport.upsertModeOf(dialect),
            // 默认按支持多行 VALUES 处理；指向 12c 前的 Oracle 用 -Dmpvs.dialect.multiRowValues=false
            multiRowValues = System.getProperty("mpvs.dialect.multiRowValues")?.toBoolean() ?: true,
        )
        val conn: Connection = if (user != null) {
            DriverManager.getConnection(url, user, password)
        } else {
            DriverManager.getConnection(url)
        }
        conn.use {
            DialectVerifySupport.runTcSuite(it, spec)
            val failures = DialectVerifySupport.sweepReservedWords(it, spec, dialect.reservedWords)
            assertSweepClean(dialectName, dialect.reservedWords.size, failures)
        }
    }

    private fun assertSweepClean(label: String, total: Int, failures: List<String>) {
        // 空扫即空绿（T-SuiteIdempotent）：词表为空时 failures 必然为空，绿读数毫无证据力。
        // 现有 14 族最少 86 词（PG），下限取 50 足以拦住「词表没接上」的形态。
        org.junit.Assert.assertTrue(
            "$label 保留字清单仅 $total 个词——空扫即空绿，不能当证据",
            total >= 50,
        )
        org.junit.Assert.assertTrue(
            "$label 方言保留字扫描失败 ${failures.size}/$total 个词:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
