package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.canCreateSqlPsiFor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-ExecPsiGate：exec 链「可建 PSI 门」与「清映射自愈」的回归用例。
 *
 * ## 缺陷形态（2026-11-10 用户报障）
 *
 * Mac / UOS 用户点「执行 SQL」必报
 * `创建临时 SQL 文件失败：findPsi: PsiManager.findFile null (mpsql-exec.sql)`，文件本身存在且可读写。
 * 平台 `FileManagerImpl.findFile` 只有三条 null 分支（目录 / vf 无效 / PSI 创建被拒），前两条与
 * 用户证据互斥 ⇒ 落在第三条：**PSI 语言由插件自己写的方言映射决定，目标方言在这台机器上建不出
 * SQL PSI 时 findFile 只会静默返回 null**。preview 链一开始就有这道门（`canCreateSqlPsiFor`
 * 判定后跳过映射），exec 链没有——所以同一台机器预览正常、点执行必死。
 *
 * ## 为什么必须走 DB 集成通道
 *
 * 本类要真实的 `SqlDialectMappings`（写入/清除文件级方言映射）与真实 SQL 方言语言对象（判断
 * 「能不能建 PSI」）。默认 `test` 桶的 classpath 被 `mpv-testing.gradle.kts` 剥掉了 database /
 * intellij.sql 相关 jar，`instanceOf(project)` 恒 null，断言会全部退化成 no-op 的「空绿」——
 * 这正是本类不能放进快桶的原因（与 `PlatformSqlDialectRegistrationTest` 同一判定）。
 *
 * ## 用例与判据
 *
 * - `testSqlDialectLanguagesCanBuildPsi`：平台内置方言 + 插件自注册方言都能建 SQL PSI（正向基线）；
 * - `testNonSqlLanguageCannotBuildPsi`：Java 语言与「没有任何 parser definition」的自定义语言不能；
 * - `testGateSkipsMappingForNonPsiLanguage`：**主修判据**——把目标语言换成建不出 PSI 的语言，
 *   `applyExecDialectMapping` 必须返回 false、**不写映射**，且文件仍能建出 PSI；
 * - `testHealRebuildsPsiAfterBadMapping`：**自愈判据**——先手工写入坏映射把 `findFile` 打成 null
 *   （缺陷形态复现），再走 `healExecPsi`，必须拿回 PSI 且坏映射被清掉。
 *
 * 反向验证（T-ExecPsiGate §6.2）：摘掉 `applyExecDialectMapping` 里的闸门分支后，
 * `testGateSkipsMappingForNonPsiLanguage` 必红（映射落到 NoParserForTest，findFile 变 null）。
 */
class ExecPsiGateTest : BasePlatformTestCase() {

    private val databasePluginId = "com.intellij.database"
    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DataSourceSelector::class.java)

    // ======================== 环境与正向基线 ========================

    fun testDatabasePluginInstalledInSandbox() {
        assertNotNull(
            "测试沙盒未安装 $databasePluginId 插件——本类无法回答「能不能建 PSI」，请确认该用例走在 " +
                "DB 集成通道（testDb*）而非默认 test 桶",
            PluginManagerCore.getPlugin(PluginId.getId(databasePluginId)),
        )
    }

    /**
     * 平台内置方言与插件自注册方言都必须判 true。
     *
     * 自注册方言（DM / GaussDB / HighGo / Vastbase / GoldenDB）依赖 `database-optional.xml` 里
     * 成对的 `<lang.parserDefinition>`；哪一对掉了，这条用例先红——比等到真机 `findFile` 返回
     * null 再回头查要早得多。
     */
    fun testSqlDialectLanguagesCanBuildPsi() {
        val mappings = SqlDialectMappings.getInstance(project)
        val available = mappings.availableValues.mapNotNull { it as? Language }
        val ids = available.map { it.id }
        println("[ExecPsiGate] available dialect languages=$ids")

        val mustBuild = listOf("MySQL", "PostgreSQL", "GenericSQL", "DM", "GaussDB", "HighGo", "Vastbase", "GoldenDB")
        for (id in mustBuild) {
            val language = available.firstOrNull { it.id.equals(id, ignoreCase = true) }
            assertNotNull("$id 不在平台可用方言里（可用于对照的清单：$ids）", language)
            assertTrue(
                "方言 '$id' 必须能建出 SQL PSI（否则 exec/preview 的 findFile 会返回 null）",
                canCreateSqlPsiFor(language),
            )
        }
    }

    /**
     * **实验 R4**：把文件逐个映射到**平台里每一个可用方言**，看谁让 `findFile` 拿不到 PSI。
     *
     * 动机（2026-11-11 用户五元组）：`vfValid=true / dir=false / fileType=SQL / mappedLang=null`
     * ——两条已知分支都被排掉，只剩「PSI 语言没有可用 ParserDefinition」。而当我们自己没写映射时，
     * 决定 `.sql` 的 PSI 语言的是**平台默认 SQL 方言**（`SqlLanguageSubstitutor` 的
     * `getConfiguredDefaultSqlDialect`）。所以这里把每个方言都试一遍，看哪些会让 PSI 建不出来。
     */
    fun testProbeEveryDialectForPsiCreation() {
        val mappings = SqlDialectMappings.getInstance(project)
        val available = mappings.availableValues.mapNotNull { it as? Language }
        val (ioFile, vf) = writeExecFileWithIo("select dialect-probe")
        val bad = mutableListOf<String>()
        val table = mutableListOf<String>()
        for (language in available) {
            runCatching { DialectPreMapping.setLanguageOnEdt(project, vf, language) }
            val outcome = awaitPsiOutcome(vf)
            table += "${language.id}=$outcome"
            if (outcome != "PSI") bad += "${language.id}:$outcome"
        }
        println("[R4] total=${available.size} bad=${bad.size} -> $bad")
        println("[R4] table=$table")
        // 这里只做记录：把「哪些方言建不出 PSI」的事实钉在日志里，供真机比对
        assertTrue("至少要有 MySQL 这种能建 PSI 的方言（否则平台环境异常）", table.any { it.startsWith("MySQL=") && it.endsWith("=PSI") })
    }

    /** 取一次「当前映射下的 PSI 结果」，最多等 10 拍让重解析落地；返回 PSI / NULL / THROW:xxx。 */
    private fun awaitPsiOutcome(vf: VirtualFile): String {
        var last = "NULL"
        repeat(10) {
            UIUtil.dispatchAllInvocationEvents()
            val r = runCatching { findExecPsi(project, vf) }
            last = when {
                r.isFailure -> "THROW:${r.exceptionOrNull()!!.javaClass.simpleName}"
                r.getOrNull() == null -> "NULL"
                else -> "PSI"
            }
            if (last == "PSI" || last.startsWith("THROW")) return last
            Thread.sleep(20L)
        }
        return last
    }

    fun testNonSqlLanguageCannotBuildPsi() {
        val java = Language.findLanguageByID("JAVA")
        assertNotNull("JAVA 语言应可见（本用例的前提）", java)
        assertFalse("非 SQL 语言不得被判为『能建 SQL PSI』", canCreateSqlPsiFor(java))
        assertFalse("没有任何 parser definition 的语言必须判 false", canCreateSqlPsiFor(NoParserForTest))
        assertFalse("null 语言必须判 false（不得空指针）", canCreateSqlPsiFor(null))
    }

    // ======================== 主修判据：闸门 ========================

    /**
     * 目标语言建不出 SQL PSI 时，**不得写映射**（写了就等价于把 exec 文件钉死在 findFile 恒 null）。
     *
     * 反向验证：摘掉 `applyExecDialectMapping` 的闸门分支，本用例第二段（mapping 为 null）与
     * 第三段（findFile 非 null）双双变红。
     */
    fun testGateSkipsMappingForNonPsiLanguage() {
        val vf = writeExecFile("select 1")
        // 判据一律用「不是那个坏语言」而不是「为 null」：`configuredLanguageId` 走平台的
        // getConfiguredMapping（含继承链），项目/全局有默认方言时它不会返回 null——拿 null 当
        // 判据会变成环境相关的假红。
        assertFalse(
            "前置：新文件不应已经映射到 NoParserForTest",
            DialectPreMapping.configuredLanguageId(project, vf)?.equals(NoParserForTest.id, true) == true,
        )

        val applied = DataSourceSelector.applyExecDialectMapping(
            project, vf, LOG,
            targetLanguage = NoParserForTest,
            targetId = NoParserForTest.id,
            renderedDialectName = NoParserForTest.id,
            targetDs = null,
        )
        assertEquals(
            "目标语言建不出 PSI 时，闸门必须拒绝映射（APPLIED = 没拦住）",
            DataSourceSelector.ExecMappingOutcome.SKIPPED_NO_PSI,
            applied,
        )
        assertFalse(
            "闸门拒绝后不得残留该方言映射（残留 = PSI 永远拿不到）",
            DialectPreMapping.configuredLanguageId(project, vf)?.equals(NoParserForTest.id, true) == true,
        )
        assertNotNull(
            "闸门拒绝后应回落到平台默认语言，PSI 照常建出来",
            awaitPsiFor(vf) { it != null },
        )
    }

    /** 正向对照：能建 PSI 的语言仍然照常写映射（闸门不得误伤正常路径）。 */
    fun testGateAppliesMappingForPsiCapableLanguage() {
        val vf = writeExecFile("select 2")
        val mysql = SqlDialectMappings.getInstance(project).availableValues
            .mapNotNull { it as? Language }
            .firstOrNull { it.id.equals("MySQL", ignoreCase = true) }
        assertNotNull("MySQL 方言语言应可用", mysql)

        val applied = DataSourceSelector.applyExecDialectMapping(
            project, vf, LOG,
            targetLanguage = mysql,
            targetId = "MySQL",
            renderedDialectName = "MySQL",
            targetDs = null,
        )
        assertEquals(
            "能建 PSI 的语言必须照常映射（闸门误伤会让所有正常执行退化）",
            DataSourceSelector.ExecMappingOutcome.APPLIED,
            applied,
        )
        assertEquals(
            "映射应落地为 MySQL",
            "MySQL",
            DialectPreMapping.configuredLanguageId(project, vf),
        )
        assertNotNull("MySQL 映射下 PSI 应可建", awaitPsiFor(vf) { it != null })
    }

    // ======================== 自愈判据 ========================

    /**
     * 缺陷形态复现 → 自愈：坏映射把 findFile 打成 null，`healExecPsi` 清映射后必须拿回 PSI。
     *
     * 第二段断言（`findFile` 为 null）是**先证伪前提**：如果平台对自定义语言干脆不管（映射
     * 写不进去 / 仍按默认方言建 PSI），说明本单的 null 另有来源，测试必须先红再改生产逻辑。
     */
    fun testHealRebuildsPsiAfterBadMapping() {
        val vf = writeExecFile("select 3")
        assertTrue(
            "前置：必须能把文件映射到『建不出 SQL PSI』的语言上（否则缺陷形态复现不出来）",
            DialectPreMapping.setLanguageOnEdt(project, vf, NoParserForTest),
        )
        // 实测形态（2026-11-10，本单沙盒）：非 `SqlLanguageDialect` 的映射会在
        // `SqlLanguageSubstitutor.getSqlLanguageFromMapping` 里直接抛 CCE——这证明**映射就是 PSI
        // 语言的来源**（platform：`LanguageUtil.getLanguageForPsi → substituteLanguage`）；
        // 方言类映射但缺 parser definition 时则表现为 `findFile` 返回 null（T-PreviewPsiDialect
        // 真机上两种都出现过）。对调用方的观测一致：拿不到可用 PSI。
        assertNull(
            "前置：坏映射下必须拿不到 PSI（异常被吞或返回 null 都算）",
            runCatching { findExecPsi(project, vf) }.getOrNull(),
        )

        val healed = DataSourceSelector.healExecPsi(project, vf, LOG)
        assertNotNull("清映射后必须能拿回 PSI（自愈失败等于用户仍然点不动执行）", healed)
        assertFalse(
            "自愈必须把坏映射清掉（否则下次 findFile 又是 null）",
            DialectPreMapping.configuredLanguageId(project, vf)?.equals(NoParserForTest.id, true) == true,
        )
    }

    // ======================== 并发外部写守卫 ========================

    /**
     * 插件之外还有写方（同步盘 / 其他进程 / 另一个窗口）时，「绕过 VFS 的外部写 + 并发 refresh」
     * 会不会把 exec 文件的 vf 打成失效，从而在 `findFile` 上表现为 null（用户 Mac/UOS 报的那一形态）。
     *
     * **实测读数（2026-11-10，Windows / 本地盘）**：外部写 344 次 + 每次并发 `refreshAndFindFileByIoFile`，
     * 主线程 369 次 `findFile` → `null=0 ex=0`，vf 全程有效。平台对「内容被外部改掉」是自愈的
     * （内容更新、vf 有效、PSI 重建），所以这条分支在本地盘上**打不出来**。
     *
     * 判据不是「必须为 null」（那样会随机器时序时红时绿），而是**诊断必须能归型**：
     * 只要观测到 null，`execPsiDiagnostics` 就必须报 `vfValid=false`。若出现
     * `null 且 vfValid=true`，说明还有第三条 null 来源（既不是 vf 失效，也不是映射坏），
     * 本用例会先红——这正是 Unix watcher / 网络盘 / 同步盘环境上最想知道的那件事。
     */
    fun testConcurrentExternalWriteNeverYieldsUndiagnosedNull() {
        val base = project.basePath ?: error("project.basePath null")
        val dir = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec")
        assertTrue("exec 目录应可创建", dir.exists() || dir.mkdirs())
        // 生产同款固定文件名：这条探针要的是真实路径的 VFS 行为，不做用例隔离改名
        val f = File(dir, "mpsql-exec.sql")
        f.writeText("select 1")
        val vf = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
        }
        assertNotNull("前置：exec 文件应 VFS 可见", vf)

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val writer = Thread {
            var i = 0
            while (!stop.get()) {
                runCatching { f.writeText("select ${i++}") }
                writes.incrementAndGet()
                runCatching { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f) }
                Thread.sleep(1L)
            }
        }
        writer.isDaemon = true
        writer.start()

        var ok = 0
        var nulls = 0
        var exceptions = 0
        var nullWithValidVf = 0
        val deadline = System.currentTimeMillis() + 1200L
        try {
            while (System.currentTimeMillis() < deadline) {
                val attempt = runCatching { findExecPsi(project, vf) }
                if (attempt.isFailure) {
                    exceptions++
                } else if (attempt.getOrNull() == null) {
                    nulls++
                    val diag = execPsiDiagnostics(project, vf)
                    if (!diag.contains("vfValid=false")) {
                        nullWithValidVf++
                        println("[RACE] null 但 vf 仍有效（第三条来源？）: $diag")
                    }
                } else {
                    ok++
                }
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(2L)
            }
        } finally {
            stop.set(true)
            writer.join(2000L)
        }
        println("[RACE] round=windows ok=$ok null=$nulls ex=$exceptions nullWithValidVf=$nullWithValidVf externalWrites=${writes.get()}")

        assertEquals(
            "并发外部写期间若出现『null 但 vf 有效』，说明 findFile 还有第三个 null 来源（不是 vf 失效，\n" +
                "也不是映射坏），必须先查清才能收口。计数：ok=$ok null=$nulls ex=$exceptions",
            0,
            nullWithValidVf,
        )
    }

    // ======================== 坏映射下重复取 PSI 守卫 ========================

    /**
     * 坏映射（映射到非 `SqlLanguageDialect` 的语言）下，`findFile` **每次都是 CCE，不会退化成 null**。
     *
     * **实测读数（2026-11-10，Windows / 243 平台测试沙盒）**：连取 5 次全部
     * `THROW:ClassCastException`（`[CCE] sequence=[THROW:ClassCastException ×5]`）。
     *
     * 这条否掉了一个本来很有说服力的假说：「预热链先撞 CCE（只 warn，用户看不到）→ 冷路径再取
     * 一次时平台把该文件的 provider 记成失败态 → 返回 null」。既然平台每次都重新抛，那用户文案
     * 里的纯 null **不能**归到映射侧，只能是 vf 失效（`vfValid=false`）或第三条来源。
     * 若将来某个平台版本开始在这条序列里返回 null，本用例先红——那时用户的文案就多了一种解释。
     */
    fun testRepeatedFindFileAfterMappingCceKeepsThrowing() {
        val vf = writeExecFile("select 7")
        assertTrue(
            "前置：必须能把文件映射到建不出 PSI 的语言",
            DialectPreMapping.setLanguageOnEdt(project, vf, NoParserForTest),
        )
        val seq = (1..5).map { i ->
            val r = runCatching { findExecPsi(project, vf) }
            val desc = when {
                r.isFailure -> "THROW:${r.exceptionOrNull()!!.javaClass.simpleName}"
                r.getOrNull() == null -> "NULL"
                else -> "PSI:${r.getOrNull()!!.language.id}"
            }
            println("[CCE] attempt#$i -> $desc")
            desc
        }
        println("[CCE] sequence=$seq")
        assertTrue(
            "坏映射下 findFile 必须恒抛（出现任何非 THROW 都说明平台行为变了，用户那条纯 null 就多一种来源）: $seq",
            seq.all { it.startsWith("THROW") },
        )
    }

    /**
     * **支 3 判据（R4 真机驱动）**：毒源是**继承来的**方言时，清掉文件自己的映射没用。
     *
     * 真机证据（2026-11-11，UOS + Mac 两台、同一个项目 `fortress4_basetime`）：
     * `vfValid=true / dir=false / fileType=SQL / mappedLang=null` 却仍拿不到 PSI ⇒ 决定 PSI
     * 语言的是继承链（项目根/目录映射、全局默认 SQL 方言、`SqlDialectPusher` 按数据源推的方言）。
     * 这种状态下「清映射」等于把继承值请回来，必须**显式钉**一个能建 SQL PSI 的方言覆盖它。
     *
     * 本用例用「目录映射」模拟继承毒源（平台 `getMappingForHierarchy` 自下而上，目录映射被其下
     * 文件继承），断言：文件自身无映射时拿不到 PSI → `healExecPsi` 钉住可用方言后拿回 PSI，
     * 且文件上确实留下了那个显式映射。
     */
    fun testHealPinsGoodDialectWhenInheritedIsPoison() {
        val (ioFile, vf) = writeExecFileWithIo("select 8")
        val dirVf = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile.parentFile)
        }
        assertNotNull("exec 目录应 VFS 可见", dirVf)
        try {
            assertTrue(
                "前置：目录映射必须写成功（模拟继承毒源）",
                DialectPreMapping.setLanguageOnEdt(project, dirVf!!, NoParserForTest),
            )
            assertNull(
                "前置：继承坏方言下 PSI 必须拿不到（这正是用户五元组的形态）",
                runCatching { findExecPsi(project, vf) }.getOrNull(),
            )

            val healed = DataSourceSelector.healExecPsi(project, vf, LOG, ioFile)
            assertNotNull("继承方言是毒源时必须靠「钉住可用方言」救回来（清映射救不了）", healed)
            val pinned = DialectPreMapping.configuredLanguageId(project, vf)
            println("[ExecPsiGate] pinned dialect after inherited-poison heal = $pinned")
            assertTrue(
                "钉住后文件上必须有显式映射，且不是那个坏语言（否则继承值又生效了）: $pinned",
                pinned != null && !pinned.equals(NoParserForTest.id, ignoreCase = true),
            )
        } finally {
            // 目录映射会污染同目录下的其它用例，必须清掉
            runCatching { DialectPreMapping.clearMapping(project, dirVf!!) }
        }
    }

    /**
     * **实验 R5**：映射写入触发的**异步重解析**与紧接着的 `findFile` 之间的竞态。
     *
     * 真机线索（2026-11-11）：用户的 SQL Dialects 里 `Global/Project = <None>`，而
     * `mpsql-exec.sql` 显式映射为 **MySQL**——也就是说映射本身完全正常，`findFile` 却仍拿不到 PSI。
     * 那剩下的解释只有「**取 PSI 的时刻**不对」：`setMapping` 会经 `LanguageSubstitutors.requestReparsing`
     * 投一拍**异步写动作**换语言，而 exec 链在 `ensureMapped` 返回后**立刻** findFile
     * （preview 链有 `awaitPreviewPsiLanguage` 专门等这一拍，exec 链没有）。
     *
     * 做法：在池线程上反复跑生产函数 `createTempPsiFile`，每轮**交替目标方言**（MySQL ↔ PostgreSQL）
     * 强制每轮都真的写一次映射 + 触发重解析，统计失败次数与失败阶段。
     */
    fun testCreateTempPsiFileUnderReparseRaceProbe() {
        var ok = 0
        val failures = mutableListOf<String>()
        repeat(3) { i ->
            val dialect = if (i % 2 == 0) "MySQL" else "PostgreSQL"
            val pair = com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread<Pair<VirtualFile, PsiFile>?> {
                    DataSourceSelector.createTempPsiFile(project, "select $i", dialect)
                }.get(30, java.util.concurrent.TimeUnit.SECONDS)
            if (pair == null) {
                failures += "#$i($dialect): ${DataSourceSelector.lastTempFileFailure}"
            } else {
                ok++
            }
        }
        println("[R5] ok=$ok fail=${failures.size} -> $failures")
        assertTrue("至少要成功一次（否则是环境问题，不是竞态）", ok > 0)
    }

    // ======================== 诊断 ========================

    /**
     * **vf 失效分支**：用户那条纯 null 的两个来源之一（另一来源见 §testRepeatedFindFile… 的否证）。
     *
     * 生产形态：exec 文件在盘上、内容也刚写成功，但手里的 vf 条目已失效（watcher 与 refresh
     * 抢跑 / 外部替换 / 网络盘、同步盘）。平台 `FileManagerImpl.findFile` 对失效 vf 只返回 null，
     * 清方言映射救不了它——必须**重新 refresh 定位**取新 vf。
     *
     * 判据三段：①失效 vf 上 `findFile` 必须拿不到 PSI，且五元组报 `vfValid=false`（诊断区分力）；
     * ②文件仍在盘上时，`healExecPsi` 必须重定位成功并返回 PSI；③返回的 PSI 必须挂在**新** vf 上
     * （缓存与 attach 要用同一个，否则又是一次失效引用）。
     */
    fun testHealRefindsVfWhenInvalid() {
        val (ioFile, vf) = writeExecFileWithIo("select 5")
        assertNotNull("前置：正常状态 PSI 应可建", findExecPsi(project, vf))

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            vf.delete(this)
        }
        assertFalse("前置：删除后 vf 必须失效（否则本用例测的不是这条分支）", vf.isValid)

        // 平台 `FileManagerImpl.findFile` 在 vf 失效时 `LOG.error(InvalidVirtualFileAccessException)`
        // 后返回 null；单测模式下 LOG.error 可能抛断言，故「null 或抛」都算拿不到 PSI。
        assertNull(
            "vf 失效时必须拿不到 PSI（null 或平台断言均算）",
            runCatching { findExecPsi(project, vf) }.getOrNull(),
        )
        val diag = execPsiDiagnostics(project, vf)
        println("[ExecPsiGate] invalid-vf diagnostics=$diag")
        assertTrue("诊断必须报出 vfValid=false（区分两条 null 分支的唯一判据）: $diag", diag.contains("vfValid=false"))

        // 生产里文件一直在（写盘成功），只是 vf 条目失效——把内容写回盘上，vf 对象仍然失效
        ioFile.writeText("select 5")

        val healed = DataSourceSelector.healExecPsi(project, vf, LOG, ioFile)
        assertNotNull("文件在盘上、vf 失效时必须靠重定位拿回 PSI（这正是用户那一形态的自愈面）", healed)
        val healedVf = healed!!.virtualFile
        assertNotNull("重定位后的 PSI 必须挂在具体 vf 上", healedVf)
        assertNotSame("必须换到新 vf（沿用失效 vf 等于没修）", vf, healedVf)
        assertTrue("重定位出来的 vf 必须有效", healedVf!!.isValid)
    }

    /** 失败文案的五元组：三项静态事实 + 映射语言 + 该语言能否建 PSI。 */
    fun testDiagnosticsCarryFiveFields() {
        val vf = writeExecFile("select 4")
        val diag = execPsiDiagnostics(project, vf)
        println("[ExecPsiGate] diagnostics=$diag")
        for (field in listOf("name=mpsql-exec", "vfValid=true", "dir=false", "fileType=", "mappedLang=", "canPsi=", "path=")) {
            assertTrue("诊断串缺少字段 '$field'（用户回传时就靠这几项定位）: $diag", diag.contains(field))
        }
    }

    // ======================== 辅助 ========================

    /** 项目内 exec 文件（生产同款路径），落在 LocalFileSystem 上以便模拟真实 VFS 行为。 */
    private fun writeExecFile(sql: String): VirtualFile = writeExecFileWithIo(sql).second

    /** 同上，同时返回磁盘 File（vf 失效重定位用例需要它来「把内容写回盘上」）。 */
    private fun writeExecFileWithIo(sql: String): Pair<File, VirtualFile> {
        val base = project.basePath ?: error("project.basePath null")
        val dir = File(base, ".idea/${cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.DATA_DIR_NAME}/exec")
        assertTrue("exec 目录应可创建", dir.exists() || dir.mkdirs())
        // 文件名带用例后缀让各用例互不干扰：方言映射按**文件路径**持久化在 workspace.xml，
        // 同名文件会把上一个用例写下的映射状态带进来。
        val suffix = sql.hashCode().toUInt().toString(16)
        val f = File(dir, "mpsql-exec-$suffix.sql")
        f.writeText(sql)
        val vf = WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
        }
        assertNotNull("exec 文件应 VFS 可见", vf)
        return f to vf!!
    }

    /**
     * 有界等待一个 PSI 条件成立。
     *
     * 方言映射写下去之后，平台会经 `LanguageSubstitutors.requestReparsing` 再投一拍写动作，PSI
     * 语言是**异步**换过来的（production 侧 `awaitPreviewPsiLanguage` 处理同一件事）。测试在 EDT
     * 上跑，直接 sleep 会让那一拍永远不落地，所以每轮先 `dispatchAllInvocationEvents()` 把排队事件
     * 推进一轮，再取 PSI。
     */
    private fun awaitPsiFor(vf: VirtualFile, budgetMs: Long = 3000L, predicate: (PsiFile?) -> Boolean): PsiFile? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (true) {
            UIUtil.dispatchAllInvocationEvents()
            val psi = PsiManager.getInstance(project).findFile(vf)
            if (predicate(psi)) return psi
            if (System.currentTimeMillis() >= deadline) return psi
            Thread.sleep(20L)
        }
    }

    private companion object {
        /**
         * 一个**没有任何 parser definition** 的语言。
         *
         * 平台 `SingleRootFileViewProvider.createFile` 拿不到 ParserDefinition 就返回 null，于是
         * `PsiManager.findFile` 返回 null——这正是用户真机形态的最小复现体（真机上那个语言是
         * 「本机建不出 PSI 的自注册方言」）。
         */
        val NoParserForTest: Language = object : Language("NoParserForTest") {}
    }
}
