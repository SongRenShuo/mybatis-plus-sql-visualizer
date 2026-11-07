package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.MYSQL_RESERVED
import cn.song.mybatisplus.visualizer.sql.ORACLE_RESERVED
import cn.song.mybatisplus.visualizer.sql.PG_RESERVED
import cn.song.mybatisplus.visualizer.sql.SQLSERVER_RESERVED
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
 * Phase 2（Tier 2）：真实数据库方言验证（P-立项-05；T-DockerGateOffload，2026-11-07 通道改造）。
 *
 * 执行与 H2/SQLite 测试相同的插件渲染 SQL 套件（[DialectVerifySupport.runTcSuite] + 保留字全量扫描）
 * ——这是「真实环境测试」的可自动化部分，证据级别最高。前五例每例两条通道，**外指优先、容器兜底**：
 *
 * | 用例 | 验证方言 | 外指对位（默认矩阵 key） | 容器兜底（仅无外指端点时） |
 * |------|----------|------------------------|--------------------------|
 * | 真实MySQL80 | MySQL | `mysql84`（8.4.6；容器镜像 8.0.36 的版本差见下方登记） | mysql:8.0.36 |
 * | 真实PostgreSQL16 | PostgreSQL | `pg16`（16.14；容器 16.2 微差） | postgres:16.2 |
 * | 真实MariaDB11 | MariaDB | `mariadb`（10.11.11；**容器 11.2 的大版本差**） | mariadb:11.2 |
 * | 真实SQLServer2022 | SQL Server | `sqlserver2022`（2022 CU27-GDR） | mssql/server:2022-CU12 |
 * | 真实Oracle23free | Oracle | `oracle23`（Oracle Free 23） | gvenzl/oracle-free:23.5-slim |
 *
 * 外指通道读 `.local/dialect-external-matrix.properties`（与 `./gradlew dialectExternalVerify`
 * 同一份矩阵、同一批测试服务器实例凭据，[DockerGateOffload] 2026-11-07 定例：本地不再依赖 Docker
 * Desktop；矩阵 key 可用文件内 `dockerext.<engine> = <key>` 行覆盖默认映射）。条目缺失或文件不存在
 * 时回落容器路径；容器路径仍受 Docker daemon 守卫。
 *
 * **运行开关与通道的交互**（总开关只有 `-PmpvDockerTests=true` 一把，构建侧经 mpv-testing 注入
 * `mpvs.docker.tests.enabled`）：
 *  - 开关缺省（日常门禁/CI）：六例全部秒级跳过——外指腿不连任何网络、容器腿不碰 daemon；
 *  - 开关开启 + 外指端点已配置：五例连测试服务器实例（**零 daemon 触达**），连不上=红不跳过；
 *  - 开关开启 + 无外指端点：回落容器路径（`assumeDockerAvailable` 探测 daemon，无 Docker 显式跳过）。
 *
 * 外指通道不承担实例级预算看门狗（那是 [DialectExternalMatrixVerificationTest] 的矩阵口径）；
 * 单库登录超时 20s 兜住不可达端点——显式开着开关跑就要求真读数，连不上判红而不是静默跳过。
 *
 * 版本差登记（外指实例 vs 原容器镜像）：MySQL 8.4.6（8.0.36 无对位真机）、MariaDB 10.11.11
 * （11.x 新语法面无真机——套件通过即按等价读数采信，不通过处登记「11.2 缺真机」）；
 * PostgreSQL 16.14、SQL Server 2022 CU27-GDR、Oracle Free 23 与容器同代微差。
 *
 * 第 6 例 `外部实例_方言套件与保留字扫描` 是**单实例手动入口**（`-Dmpvs.dialect.*` 逐参指定），
 * 服务于 DM/KingBase 这类无矩阵条目的自建库；矩阵实例请走 `dialectExternalVerify` 或前五例外指腿。
 *
 * **本类不承担发版判据**（2026-10-31 用户定例：真机方言验证走测试服务器外部实例、DBX 只做临时探测）。
 * 发版门禁由 `./gradlew dialectExternalVerify` 承担（[DialectExternalMatrixVerificationTest] 读实例
 * 矩阵，产出可归档的 JUnit 计数）。
 */
@Suppress("TestClassWithoutTestCases") // 真实引擎用例全部经 Assume/开关守卫（未启用时静默跳过）
class DialectDockerVerificationTest {

    private companion object {
        /** 外指端点矩阵文件的系统属性名（构建侧注入，`-PdialectExternalMatrix=<路径>` 可覆盖）。 */
        const val EXTERNAL_MATRIX_PROPERTY = "mpvs.dockerext.matrix"

        /** 矩阵文件缺省路径（相对测试 JVM 工作目录=项目目录；与 dialectExternalVerify 默认一致）。 */
        const val DEFAULT_EXTERNAL_MATRIX_PATH = ".local/dialect-external-matrix.properties"

        /**
         * 五引擎 → 矩阵 key 的默认映射（T-DockerGateOffload，2026-11-07）。
         * 取矩阵文件里**现成条目**（全部已进发版门禁矩阵并被真机跑绿），不新采凭据；
         * 同文件写 `dockerext.<engine> = <key>` 可逐引擎改指（key 指到矩阵里不存在的条目
         * ＝该腿回落容器路径）。
         */
        val DEFAULT_ENGINE_MATRIX_KEYS = mapOf(
            "mysql" to "mysql84",
            "postgresql" to "pg16",
            "mariadb" to "mariadb",
            "sqlserver" to "sqlserver2022",
            "oracle" to "oracle23",
        )

        /** 外指腿单库登录超时（秒）——不可达端点在这条线内判红，不做静默跳过。 */
        const val EXTERNAL_LOGIN_TIMEOUT_SECONDS = 20
    }

    /** 外指端点（解析自矩阵文件条目）。 */
    private data class ExternalEndpoint(
        val engineKey: String,
        val matrixKey: String,
        val dialectName: String,
        val url: String,
        val user: String?,
        val password: String?,
    )

    /** 每条真实引擎用例的通道：外指端点已解析 → external；否则回落容器。 */
    private sealed interface LegChannel {
        data class External(val endpoint: ExternalEndpoint) : LegChannel
        object ContainerFallback : LegChannel
    }

    /**
     * 解析该引擎的外指端点：矩阵文件缺失 / 映射 key 未指条目 / 条目缺 url 或方言名 → null（回落容器）。
     * 只读文件不连库——连不上属于运行期判红（见 [runExternalLeg]），不在解析层吞掉。
     */
    private fun resolveExternalEndpoint(engineKey: String): ExternalEndpoint? {
        val matrixPath = System.getProperty(EXTERNAL_MATRIX_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_EXTERNAL_MATRIX_PATH
        val file = java.io.File(matrixPath)
        if (!file.isFile) return null
        val props = java.util.Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        val matrixKey = props.getProperty("dockerext.$engineKey")?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ENGINE_MATRIX_KEYS[engineKey]
            ?: return null
        val dialectName = props.getProperty("$matrixKey.dialect")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = props.getProperty("$matrixKey.url")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val user = props.getProperty("$matrixKey.user")?.trim()?.takeIf { it.isNotEmpty() }
        val password = props.getProperty("$matrixKey.password")?.trim()?.takeIf { it.isNotEmpty() }
        return ExternalEndpoint(engineKey, matrixKey, dialectName, url, user, password)
    }

    private fun channelFor(engineKey: String): LegChannel {
        val endpoint = resolveExternalEndpoint(engineKey)
        return if (endpoint != null) LegChannel.External(endpoint) else LegChannel.ContainerFallback
    }

    /** 真实引擎用例总开关（外指腿与容器腿共用；构建侧经 mpv-testing 注入，`-PmpvDockerTests=true` 开启）。 */
    private fun assumeRealEngineTestsEnabled() {
        // Docker/真机测试默认关闭（20260923 用户定例；20261107 扩为「真机验证总开关」）：
        // 开关缺省时外指腿不连网络、容器腿不碰 daemon，日常门禁与 CI 全部秒级跳过。
        assumeTrue(
            "真实引擎方言验证未启用（-PmpvDockerTests=true 开启），跳过（H2/SQLite 嵌入式验证已覆盖语法形态）",
            System.getProperty("mpvs.docker.tests.enabled") == "true",
        )
    }

    // ------------------------------------------------------------------
    // 容器路径守卫（仅无外指端点时走到；无 Docker → 测试跳过而非失败）
    // ------------------------------------------------------------------

    private fun assumeDockerAvailable() {
        // daemon 半活态时 ping 探测会阻塞且无超时（try/catch 只兜异常、兜不住阻塞，
        // 20260923 发行门禁实录三片挂起）；开关前置后只有显式开启才探 daemon。
        val available = try {
            DockerClientFactory.instance().client().pingCmd().exec() != null
            true
        } catch (_: Throwable) {
            false
        }
        assumeTrue("Docker 不可用，跳过真实数据库方言验证（H2/SQLite 嵌入式验证已覆盖语法形态）", available)
    }

    // ------------------------------------------------------------------
    // 外指腿（测试服务器实例；连不上判红不跳过）
    // ------------------------------------------------------------------

    private fun runExternalLeg(endpoint: ExternalEndpoint) {
        val dialect = cn.song.mybatisplus.visualizer.sql.DialectRegistry.byName(endpoint.dialectName)
        // 防静默回退（T-SuiteIdempotent）：byName 对未知名称回退 MySQL，矩阵条目方言名写错时
        // 会拿 MySQL 方言去跑别的库 → 全绿但验错了方言。这里逐字核对，不命中直接失败。
        assertEquals(
            "外指条目 ${endpoint.matrixKey}（引擎 ${endpoint.engineKey}）的方言名未逐字命中 " +
                "DialectRegistry（会静默回退 MySQL，读数无效）：请求 [${endpoint.dialectName}] 实际 [${dialect.name}]",
            endpoint.dialectName,
            dialect.name,
        )
        val spec = DialectVerifySupport.EngineSpec(
            label = "${dialect.name} (external ${endpoint.matrixKey})",
            jdbcUrlTemplate = endpoint.url,
            dialect = dialect,
            // 由方言能力推导（T-SuiteIdempotent）：无原生 upsert 的方言（Oracle/SQL Server）只验降级路径
            upsertMode = DialectVerifySupport.upsertModeOf(dialect),
            multiRowValues = true,
        )
        DriverManager.setLoginTimeout(EXTERNAL_LOGIN_TIMEOUT_SECONDS)
        println(
            "[DockerGateOffload] engine=${endpoint.engineKey} channel=external key=${endpoint.matrixKey} url=${endpoint.url}",
        )
        val conn: Connection = if (endpoint.user == null) {
            DriverManager.getConnection(endpoint.url)
        } else {
            DriverManager.getConnection(endpoint.url, endpoint.user, endpoint.password)
        }
        conn.use {
            DialectVerifySupport.runTcSuite(it, spec)
            val failures = DialectVerifySupport.sweepReservedWords(it, spec, dialect.reservedWords)
            assertSweepClean("${dialect.name} (external ${endpoint.matrixKey})", dialect.reservedWords.size, failures)
        }
    }

    // ------------------------------------------------------------------
    // 真实引擎套件（外指优先，容器兜底）
    // ------------------------------------------------------------------

    @Test
    fun `真实MySQL80_方言套件与保留字扫描`() {
        assumeRealEngineTestsEnabled()
        when (val channel = channelFor("mysql")) {
            is LegChannel.External -> runExternalLeg(channel.endpoint)
            LegChannel.ContainerFallback -> runMysqlContainerLeg()
        }
    }

    /** 容器兜底：MySQL 8.0.36（仅矩阵文件无 mysql 外指条目时走到）。 */
    private fun runMysqlContainerLeg() {
        assumeDockerAvailable()
        MySQLContainer<Nothing>("mysql:8.0.36").apply {
            withUrlParam("useSSL", "false")
            withUrlParam("allowPublicKeyRetrieval", "true")
        }.use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "MySQL 8.0 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.MySqlDialect,
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
        assumeRealEngineTestsEnabled()
        when (val channel = channelFor("postgresql")) {
            is LegChannel.External -> runExternalLeg(channel.endpoint)
            LegChannel.ContainerFallback -> runPostgresqlContainerLeg()
        }
    }

    /** 容器兜底：PostgreSQL 16.2。 */
    private fun runPostgresqlContainerLeg() {
        assumeDockerAvailable()
        PostgreSQLContainer<Nothing>("postgres:16.2").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "PostgreSQL 16 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect,
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
        assumeRealEngineTestsEnabled()
        when (val channel = channelFor("mariadb")) {
            is LegChannel.External -> runExternalLeg(channel.endpoint)
            LegChannel.ContainerFallback -> runMariadbContainerLeg()
        }
    }

    /** 容器兜底：MariaDB 11.2（外指对位为 10.11 等价读数，版本差见类注释登记）。 */
    private fun runMariadbContainerLeg() {
        assumeDockerAvailable()
        MariaDBContainer<Nothing>("mariadb:11.2").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "MariaDB 11 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.MariaDbDialect,
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
        assumeRealEngineTestsEnabled()
        when (val channel = channelFor("sqlserver")) {
            is LegChannel.External -> runExternalLeg(channel.endpoint)
            LegChannel.ContainerFallback -> runSqlserverContainerLeg()
        }
    }

    /** 容器兜底：SQL Server 2022 CU12。 */
    private fun runSqlserverContainerLeg() {
        assumeDockerAvailable()
        MSSQLServerContainer<Nothing>("mcr.microsoft.com/mssql/server:2022-CU12-ubuntu-22.04").use { container ->
            container.acceptLicense()
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "SQL Server 2022 (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.SqlServerDialect,
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
        assumeRealEngineTestsEnabled()
        when (val channel = channelFor("oracle")) {
            is LegChannel.External -> runExternalLeg(channel.endpoint)
            LegChannel.ContainerFallback -> runOracleContainerLeg()
        }
    }

    /** 容器兜底：Oracle Free 23.5 slim。 */
    private fun runOracleContainerLeg() {
        assumeDockerAvailable()
        OracleContainer("gvenzl/oracle-free:23.5-slim").use { container ->
            container.start()
            val spec = DialectVerifySupport.EngineSpec(
                label = "Oracle 23ai Free (Docker)",
                jdbcUrlTemplate = container.jdbcUrl,
                dialect = cn.song.mybatisplus.visualizer.sql.OracleDialect,
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
        val dialect = cn.song.mybatisplus.visualizer.sql.DialectRegistry.byName(dialectName)
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
