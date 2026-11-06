package cn.song.mybatisplus.visualizer.sql.verify

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.verify.InstanceBudgetSupport.InstanceResult
import cn.song.mybatisplus.visualizer.sql.verify.InstanceBudgetSupport.MatrixSummary
import cn.song.mybatisplus.visualizer.sql.verify.InstanceBudgetSupport.Outcome
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDateTime
import java.util.Properties

/**
 * 真机方言验证：**外部实例矩阵**（发版门禁口径，无 Docker 依赖）。
 *
 * 与 [DialectDockerVerificationTest] 的分工：
 *  - 那边用 Testcontainers 拉容器，本机无 Docker 时整类静默跳过，且默认关
 *    （`-PmpvDockerTests=true` 才触达 daemon）——只在本地想验时手工开；
 *  - 这边连**测试服务器上已部署的实例**（DBX 清单里的同一批库），把插件渲染的 SQL 套件
 *    （[DialectVerifySupport.runTcSuite]）与全量保留字扫描在真实引擎上执行，产出 JUnit 计数，
 *    可重复跑、可进发版门禁。**门禁只认这一条路**（2026-10-31 用户定例：DBX 只做临时探测）。
 *
 * 入口：`./gradlew dialectExternalVerify`（矩阵文件默认 `.local/dialect-external-matrix.properties`，
 * 可用 `-PdialectExternalMatrix=<路径>` 覆盖；矩阵格式见 `dev-support/dialect-external/matrix.example.properties`）。
 *
 * 为什么矩阵放在 `.local/`：文件里有明文凭据，`.local/` 整目录已 gitignore；仓库里只留无凭据的模板。
 *
 * 判红底线（前四条来自既往假绿案例，逐字保留；第五条为实例级超时新增）：
 *  1. 方言名必须与 [DialectRegistry] **逐字**命中——`byName` 对未知名静默回退 MySQL，
 *     名字写错/中文被转码时会拿 MySQL 方言去跑别的库，全绿但验错了方言；
 *  2. 保留字词表 < 50 词即失败——词表没接上时扫描集合为空，`failures.isEmpty()` 必然成立（空扫即空绿）；
 *  3. 整轮至少产生一个 PASS——矩阵全被 optional 跳过时没有证据力，不能算通过；
 *  4. `skipWords`（T-IbatisPanwei，2026-11-06）必须配 `skipReason`，且词必须在方言词表里——
 *     它只用于「内核把该词当系统列/伪列」这类语义冲突（磐维 A 的 `rowid`），不用于掩盖渲染缺陷；
 *     缺理由或拼错词直接判红，例外因此永远可审计；
 *  5. **实例超时（T-MatrixInstanceTimeout，2026-11-06）= 判红**：超时既不是通过也不是跳过。实例在
 *     [InstanceBudgetSupport.resolveBudgetSeconds] 给出的预算内没进展即落 TIMEOUT 桶，summary 与
 *     JUnit 输出都点名，整轮照常继续跑下一个实例；确需容忍某实例挂起的唯一通道是在该矩阵条目写
 *     `optional=true`（降级行写作 `SKIP(超时)`，可审计）。
 *
 * 实例级预算（本轮新增，理由见计划册 §一）：原实现只有 `DriverManager.setLoginTimeout(20)`，它只管
 * **登录**；实例一旦挂在登录之后的 socket 读里（`PGStream.receiveChar` → `SocketDispatcher.read0`），
 * 既不抛也不返回，`catch` 永不进入，整轮无限期停在该实例上，只能人工杀——而强杀又曾打断共享 Gradle
 * transform 解包窗口、连带损坏构建缓存。现在每实例在独立 daemon 线程上跑，到点判 TIMEOUT 并按序处置
 * （`statement.cancel()` → `connection.close()` → `thread.interrupt()`，每步自带上限）。
 * 预算取值：实例条目 `timeoutSeconds` → `-Dmpvs.external.instanceTimeout`（秒）→ 默认 300s。
 */
class DialectExternalMatrixVerificationTest {

    private companion object {
        /** 保留字词表下限（现有 14 族最少 86 词，取 50 拦「词表没接上」）。 */
        const val MIN_RESERVED_WORDS = 50

        /** 单库登录超时（秒）；实例慢或网络差时由矩阵文件把该条标 optional。 */
        const val LOGIN_TIMEOUT_SECONDS = 20

        /** 无进展实例的结局行抬头（报告里按桶分区用）。 */
        const val BUCKET_TIMEOUT_NOTE = "超时 = 该实例未验证（既不计通过也不静默跳过；确需容忍请写 optional=true）"
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
        val summary = MatrixSummary()
        val globalBudget = InstanceBudgetSupport.resolveBudgetSeconds(null, System.getProperty(InstanceBudgetSupport.TIMEOUT_PROPERTY))
        val budgetNotes = mutableListOf<String>()

        for (key in keys) {
            val dialectName = prop(props, key, "dialect")
            val url = prop(props, key, "url")
            val optional = prop(props, key, "optional")?.toBooleanStrictOrNull() ?: false
            val budget = InstanceBudgetSupport.resolveBudgetSeconds(prop(props, key, "timeoutSeconds"), System.getProperty(InstanceBudgetSupport.TIMEOUT_PROPERTY))
            budgetNotes += "$key  预算 ${budget.seconds}s（${budget.source}）"

            if (dialectName == null || url == null) {
                summary.record(InstanceResult(key, Outcome.FAIL, "缺少 dialect / url", 0, budget.seconds), optional)
                continue
            }
            val user = prop(props, key, "user")
            val password = prop(props, key, "password")
            val multiRowValues = prop(props, key, "multiRowValues")?.toBooleanStrictOrNull() ?: true
            // T-IbatisPanwei：按实例声明保留字扫描的**例外词**（只用于「内核把该词当系统列/伪列」这类
            // 语义冲突，不用于掩盖渲染缺陷）。例外必须带理由，否则等于静默放宽。
            val skipWords = prop(props, key, "skipWords")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
            val skipReason = prop(props, key, "skipReason")?.trim().orEmpty()
            if (skipWords.isNotEmpty() && skipReason.isEmpty()) {
                summary.record(
                    InstanceResult(key, Outcome.FAIL, "skipWords 非空但未写 skipReason——例外必须可审计（写在矩阵文件里）", 0, budget.seconds),
                    optional,
                )
                continue
            }

            val dialect = DialectRegistry.byName(dialectName)
            if (dialect.name != dialectName) {
                summary.record(
                    InstanceResult(
                        key,
                        Outcome.FAIL,
                        "方言名未逐字命中 DialectRegistry（会静默回退 MySQL，读数无效）" +
                            "——请求 [$dialectName] 实际 [${dialect.name}]",
                        0,
                        budget.seconds,
                    ),
                    optional,
                )
                continue
            }

            val spec = DialectVerifySupport.EngineSpec(
                label = "$key ($dialectName external)",
                jdbcUrlTemplate = url,
                dialect = dialect,
                upsertMode = DialectVerifySupport.upsertModeOf(dialect),
                multiRowValues = multiRowValues,
            )
            // 句柄先发布再跑套件：看门狗线程靠 volatile 字段（而不是 worker 线程栈）拿到连接与正在执行的语句。
            val handle = InstanceJdbcHandle()
            val result = InstanceBudgetSupport.runWithBudget(
                key = key,
                budgetSeconds = budget.seconds,
                cleanupSteps = listOf(
                    "statement.cancel" to { handle.cancelCurrentStatement() },
                    "connection.close" to { handle.closeConnection() },
                ),
            ) {
                val raw = if (user.isNullOrBlank()) {
                    DriverManager.getConnection(url)
                } else {
                    DriverManager.getConnection(url, user, password)
                }
                val conn = handle.publish(raw)
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
                "${dialect.name}  words=${dialect.reservedWords.size}" +
                    (if (skipWords.isEmpty()) "" else "  skip=${skipWords.size}(${skipWords.joinToString(",")})")
            }
            summary.record(result, optional)
        }

        val report = buildString {
            appendLine("# 外部实例方言验证 · ${LocalDateTime.now()}")
            appendLine()
            appendLine("矩阵：${file.absolutePath}")
            appendLine()
            appendLine("实例级预算（超时看门狗）：默认 ${globalBudget.seconds}s（${globalBudget.source}）")
            budgetNotes.forEach { appendLine("- $it") }
            appendLine()
            appendLine(summary.countsLine())
            appendLine()
            appendLine("超时桶说明：$BUCKET_TIMEOUT_NOTE")
            appendLine()
            summary.allLines().forEach { appendLine("- $it") }
        }
        val summaryFile = System.getProperty("mpvs.external.summary")
            ?.let { File(it) }
            ?: File("build/reports/dialectExternalVerify/summary.md")
        summaryFile.parentFile?.mkdirs()
        summaryFile.writeText(report, Charsets.UTF_8)
        println(report)

        assertTrue("外部实例验证失败 ${summary.failed.size} 项：\n" + summary.failed.joinToString("\n"), summary.failed.isEmpty())
        assertTrue(
            "外部实例超时未验证 ${summary.timedOut.size} 项（超时不是通过，也不是跳过；" +
                "确需容忍请在该实例条目写 optional=true）：\n" + summary.timedOut.joinToString("\n"),
            summary.timedOut.isEmpty(),
        )
        assertTrue("矩阵未产生任何 PASS（全部被跳过，读数无证据力）", summary.hasPass)
    }

    private fun prop(props: Properties, key: String, name: String): String? =
        props.getProperty("$key.$name")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 当前实例的 JDBC 句柄（供**看门狗线程**在超时后按序处置）。
     *
     * 为什么需要代理：[DialectVerifySupport] 里的语句都是 `createStatement()`/`prepareStatement()` 即取即用、
     * `use {}` 里就地释放，句柄不外传——真挂起时那条语句只存在于 worker 线程的栈上，看门狗拿不到它，
     * `statement.cancel()` 这一步就无从下手。这里用动态代理截获连接工厂方法的返回值，把「正在执行的那条
     * 语句」记进 volatile 字段，让处置顺序（cancel → close → interrupt）中的第一步真正可达。
     *
     * 代理构建失败（驱动实现类带非公开接口等）时静默回退成原对象：宁可丢掉 cancel 这一步，也不能因为
     * 装了个看门狗就把真机读数弄坏。
     */
    private class InstanceJdbcHandle {
        @Volatile
        private var connection: Connection? = null

        @Volatile
        private var currentStatement: Statement? = null

        /** 发布连接（另一线程可读）并返回给套件使用的代理连接。 */
        fun publish(raw: Connection): Connection {
            connection = raw
            return try {
                val interfaces = publicInterfacesOf(raw.javaClass)
                Proxy.newProxyInstance(InstanceJdbcHandle::class.java.classLoader, interfaces) { _, method, args ->
                    val result = invoke(raw, method, args)
                    if (result is Statement) track(result) else result
                } as Connection
            } catch (_: Throwable) {
                raw
            }
        }

        /** 处置第 1 步：取消正在执行的语句（无在执行的语句时是无操作）。 */
        fun cancelCurrentStatement() {
            currentStatement?.cancel()
        }

        /** 处置第 2 步：关闭连接（pg 族关 socket 让阻塞读抛错返回；也是本层的主力手段）。 */
        fun closeConnection() {
            connection?.close()
        }

        private fun track(statement: Statement): Statement {
            currentStatement = statement
            return try {
                val interfaces = publicInterfacesOf(statement.javaClass)
                Proxy.newProxyInstance(InstanceJdbcHandle::class.java.classLoader, interfaces) { _, method, args ->
                    if (method.name == "close" && args.isNullOrEmpty() && currentStatement === statement) {
                        currentStatement = null
                    }
                    invoke(statement, method, args)
                } as Statement
            } catch (_: Throwable) {
                statement
            }
        }

        private fun invoke(target: Any, method: Method, args: Array<Any?>?): Any? =
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

        /** JDBC 面向调用方的接口面（含父类声明的接口）；非公开接口会让 Proxy 直接拒绝，先滤掉。 */
        private fun publicInterfacesOf(type: Class<*>): Array<Class<*>> {
            val found = LinkedHashSet<Class<*>>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.interfaces.filterTo(found) { Modifier.isPublic(it.modifiers) }
                current = current.superclass
            }
            found += Connection::class.java
            found += Statement::class.java
            return found.toTypedArray()
        }
    }
}
