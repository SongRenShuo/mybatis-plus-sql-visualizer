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

    // ======================== 诊断 ========================

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
    private fun writeExecFile(sql: String): VirtualFile {
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
        return vf!!
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
