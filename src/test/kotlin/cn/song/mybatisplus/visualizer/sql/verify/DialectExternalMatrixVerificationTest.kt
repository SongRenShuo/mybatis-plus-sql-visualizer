package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.Properties

/**
 * 真机方言验证：**外部实例矩阵**（发版门禁口径，无 Docker 依赖）。
 *
 * 与 [DialectDockerVerificationTest] 的分工：
 *  - 那边用 Testcontainers 拉容器，本机无 Docker 时整类静默跳过，且默认关
 *    （`-PmpvDockerTests=true` 才触达 daemon）——只在本地想验时手工开；
 *  - 这边连**测试服务器上已部署的实例**（DBX 清单里的同一批库），把插件渲染的 SQL 套件
 *    （`DialectVerifySupport.runTcSuite`）与全量保留字扫描在真实引擎上执行，产出 JUnit 计数，
 *    可重复跑、可进发版门禁。**门禁只认这一条路**（2026-10-31 用户定例：DBX 只做临时探测）。
 *
 * 入口：`./gradlew dialectExternalVerify`（矩阵文件默认 `.local/dialect-external-matrix.properties`，
 * 可用 `-PdialectExternalMatrix=<路径>` 覆盖；矩阵格式见 `dev-support/dialect-external/matrix.example.properties`）。
 *
 * 为什么矩阵放在 `.local/`：文件里有明文凭据，`.local/` 整目录已 gitignore；仓库里只留无凭据的模板。
 *
 * 判红的四条底线（都来自既往假绿案例）：
 *  1. 方言名必须与 [DialectRegistry] **逐字**命中——`byName` 对未知名静默回退 MySQL，
 *     名字写错/中文被转码时会拿 MySQL 方言去跑别的库，全绿但验错了方言；
 *  2. 保留字词表 < 50 词即失败——词表没接上时扫描集合为空，`failures.isEmpty()` 必然成立（空扫即空绿）；
 *  3. 整轮至少产生一个 PASS——矩阵全被 optional 跳过时没有证据力，不能算通过；
 *  4. `skipWords`（T-IbatisPanwei，2026-11-06）必须配 `skipReason`，且词必须在方言词表里——
 *     它只用于「内核把该词当系统列/伪列」这类语义冲突（磐维 A 的 `rowid`），不用于掩盖渲染缺陷；
 *     缺理由或拼错词直接判红，例外因此永远可审计。
 */
class DialectExternalMatrixVerificationTest {

    private companion object {
        /** 保留字词表下限（现有 14 族最少 86 词，取 50 拦「词表没接上」）。 */
        const val MIN_RESERVED_WORDS = 50

        /** 单库登录超时（秒）；实例慢或网络差时由矩阵文件把该条标 optional。 */
        const val LOGIN_TIMEOUT_SECONDS = 20
    }

    @Test
    fun `外部实例矩阵_方言套件与保留字扫描`() {
        val matrixPath = System.getProperty("mpvs.external.matrix")
        assumeTrue(
            "未提供 -Dmpvs.external.matrix，跳过外部实例矩阵验证（用 ./gradlew dialectExternalVerify 跑）",
            !matrixPath.isNullOrBlank(),
        )
        val file = File(matrixPath!!)
        assertTrue(
            "外部实例矩阵文件不存在：$matrixPath（模板见 dev-support/dialect-external/matrix.example.properties）",
            file.isFile,
        )
        val props = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        val keys = (props.getProperty("instances") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        assertTrue("矩阵未声明任何实例（instances=a,b,...）", keys.isNotEmpty())

        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS)
        val passed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (key in keys) {
            val dialectName = prop(props, key, "dialect")
            val url = prop(props, key, "url")
            if (dialectName == null || url == null) {
                failed += "$key：缺少 dialect / url"
                continue
            }
            val user = prop(props, key, "user")
            val password = prop(props, key, "password")
            val optional = prop(props, key, "optional")?.toBooleanStrictOrNull() ?: false
            val multiRowValues = prop(props, key, "multiRowValues")?.toBooleanStrictOrNull() ?: true
            // T-IbatisPanwei：按实例声明保留字扫描的**例外词**（只用于「内核把该词当系统列/伪列」这类
            // 语义冲突，不用于掩盖渲染缺陷）。例外必须带理由，否则等于静默放宽。
            val skipWords = prop(props, key, "skipWords")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
            val skipReason = prop(props, key, "skipReason")?.trim().orEmpty()
            if (skipWords.isNotEmpty() && skipReason.isEmpty()) {
                failed += "$key：skipWords 非空但未写 skipReason——例外必须可审计（写在矩阵文件里）"
                continue
            }

            val dialect = DialectRegistry.byName(dialectName)
            if (dialect.name != dialectName) {
                failed += "$key：方言名未逐字命中 DialectRegistry（会静默回退 MySQL，读数无效）" +
                    "——请求 [$dialectName] 实际 [${dialect.name}]"
                continue
            }

            val spec = DialectVerifySupport.EngineSpec(
                label = "$key ($dialectName external)",
                jdbcUrlTemplate = url,
                dialect = dialect,
                upsertMode = DialectVerifySupport.upsertModeOf(dialect),
                multiRowValues = multiRowValues,
            )
            val startedAt = System.currentTimeMillis()
            try {
                val conn = if (user.isNullOrBlank()) {
                    DriverManager.getConnection(url)
                } else {
                    DriverManager.getConnection(url, user, password)
                }
                conn.use { c ->
                    DialectVerifySupport.runTcSuite(c, spec)
                    val words = dialect.reservedWords
                    val unknownSkips = skipWords - words
                    assertTrue(
                        "$key（$dialectName）skipWords 里的 $unknownSkips 不在该方言保留字表中" +
                            "（拼写错误或方言名不对）",
                        unknownSkips.isEmpty(),
                    )
                    val swept = words - skipWords
                    assertTrue(
                        "$key（$dialectName）保留字清单仅 ${swept.size} 个词（扣除 skipWords）" +
                            "——空扫即空绿，不能当证据",
                        swept.size >= MIN_RESERVED_WORDS,
                    )
                    val sweepFailures = DialectVerifySupport.sweepReservedWords(c, spec, swept)
                    assertTrue(
                        "$key（$dialectName）保留字扫描失败 ${sweepFailures.size}/${swept.size} 个词:\n" +
                            sweepFailures.joinToString("\n"),
                        sweepFailures.isEmpty(),
                    )
                }
                passed += "PASS  $key  ${dialect.name}  words=${dialect.reservedWords.size}" +
                    (if (skipWords.isEmpty()) "" else "  skip=${skipWords.size}(${skipWords.joinToString(",")})") +
                    "  ${System.currentTimeMillis() - startedAt}ms"
            } catch (t: Throwable) {
                val message = (t.message ?: t::class.java.simpleName)
                    .lineSequence().take(3).joinToString(" / ")
                    .take(600)
                if (optional) {
                    skipped += "SKIP  $key  ${dialect.name}  $message"
                } else {
                    failed += "$key（${dialect.name}）FAIL: $message"
                }
            }
        }

        val report = buildString {
            appendLine("# 外部实例方言验证 · ${LocalDateTime.now()}")
            appendLine()
            appendLine("矩阵：${file.absolutePath}")
            appendLine()
            appendLine("通过 ${passed.size} / 跳过 ${skipped.size} / 失败 ${failed.size}")
            appendLine()
            (passed + skipped + failed).forEach { appendLine("- $it") }
        }
        val summary = System.getProperty("mpvs.external.summary")
            ?.let { File(it) }
            ?: File("build/reports/dialectExternalVerify/summary.md")
        summary.parentFile?.mkdirs()
        summary.writeText(report, Charsets.UTF_8)
        println(report)

        assertTrue("外部实例验证失败 ${failed.size} 项：\n" + failed.joinToString("\n"), failed.isEmpty())
        assertTrue("矩阵未产生任何 PASS（全部被跳过，读数无证据力）", passed.isNotEmpty())
    }

    private fun prop(props: Properties, key: String, name: String): String? =
        props.getProperty("$key.$name")?.trim()?.takeIf { it.isNotEmpty() }
}
