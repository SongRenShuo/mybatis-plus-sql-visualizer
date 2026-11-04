package cn.song.mybatisplus.visualizer.diagnostic

import cn.song.mybatisplus.visualizer.log.agent.AgentAttachStats
import cn.song.mybatisplus.visualizer.sql.DialectDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境体检自动诊断：档 1（三段式）+ 档 2（日志模式匹配）的纯函数测试（testFast 桶）。
 *
 * 守卫目标：
 *  - 非绿灯项必须按「症状 / 原因 / 动作」三行输出，缺段跳过、不打印空标签；绿灯项只有 facts；
 *  - 五项规则逐条给具体症状与原因——原因按状态分叉（方言歧义/冲突/不可用各不相同）；
 *  - 已知问题表：大小写不敏感、`allOf` 缺一项即不命中、`eachOfAny` 两个维度都得命中；
 *  - 日志项：按规则去重计数、上限 5 条、等级取最高、未命中给 OK 并写明扫描范围；
 *  - **隐私**：报告只含规则 id / 结论文案 / 命中次数，日志原文（SQL、连接串、路径）一律不出现。
 */
class EnvironmentCheckDiagnosisTest {

    private fun report(vararg items: EnvironmentCheckItem) = EnvironmentCheckReport(
        generatedAt = "2026-11-04 12:00:00",
        pluginVersion = "2026.11.3",
        ideName = "IntelliJ IDEA 2026.2.1 (IU-262.1)",
        projects = listOf("demo"),
        items = items.toList(),
    )

    // ---------- 档 1：三段式渲染 ----------

    @Test
    fun test非绿灯项按症状原因动作三行渲染() {
        val text = report(
            EnvironmentCheckItem(
                title = "示例项",
                level = CheckLevel.WARN,
                facts = listOf("事实一行"),
                symptom = "预览打开会弹选源",
                cause = "模块还没有绑定数据源",
                guidance = "执行一次 SQL 并选择数据源",
            ),
        ).render()
        assertTrue("应打印症状标签：$text", text.contains("症状：预览打开会弹选源"))
        assertTrue("应打印原因标签：$text", text.contains("原因：模块还没有绑定数据源"))
        assertTrue("应打印动作标签：$text", text.contains("动作：执行一次 SQL 并选择数据源"))
    }

    @Test
    fun test缺段跳过不打印空标签() {
        val text = report(
            EnvironmentCheckItem(
                title = "仅动作项",
                level = CheckLevel.WARN,
                facts = listOf("事实"),
                guidance = "重启 IDE",
            ),
            EnvironmentCheckItem(
                title = "仅原因项",
                level = CheckLevel.UNAVAILABLE,
                facts = listOf("事实"),
                cause = "读不到读数",
            ),
        ).render()
        assertTrue("有动作就该打印：$text", text.contains("动作：重启 IDE"))
        assertTrue("有原因就该打印：$text", text.contains("原因：读不到读数"))
        assertFalse("没有症状段时不得打印空标签：$text", text.contains("症状："))
        assertFalse("没有原因段的项也不得打印（两条都缺）：$text", text.lines().count { it.contains("原因：") } > 1)
    }

    @Test
    fun test绿灯项只有facts不打印三段() {
        val text = report(
            EnvironmentCheckItem(
                title = "绿灯项",
                level = CheckLevel.OK,
                facts = listOf("一切正常"),
                // 即使构造方误填了三段，绿灯项也不该把症状/原因/动作打出来（该规则以 level 为准）。
                symptom = "不该出现",
                cause = "不该出现",
                guidance = "不该出现",
            ),
        ).render()
        assertTrue("facts 必须保留：$text", text.contains("一切正常"))
        assertFalse("绿灯项不得打印症状：$text", text.contains("症状："))
        assertFalse("绿灯项不得打印原因：$text", text.contains("原因："))
        assertFalse("绿灯项不得打印动作：$text", text.contains("动作："))
    }

    @Test
    fun test数据源两条非绿灯分支都给三段且原因不同() {
        val noPlugin = EnvironmentCheckRules.dataSource(false, 0, null, null)
        assertEquals(CheckLevel.UNAVAILABLE, noPlugin.level)
        assertNotNull("不可用要给症状", noPlugin.symptom)
        assertNotNull("不可用要给原因", noPlugin.cause)
        assertNotNull("不可用要给动作", noPlugin.guidance)

        val none = EnvironmentCheckRules.dataSource(true, 0, null, null)
        assertEquals(CheckLevel.WARN, none.level)
        assertNotNull(none.symptom)
        assertNotNull(none.cause)
        assertNotEquals("两条分支的原因必须区分", noPlugin.cause, none.cause)

        val ok = EnvironmentCheckRules.dataSource(true, 3, "mp_demo@localhost", null)
        assertEquals(CheckLevel.OK, ok.level)
        assertNull("绿灯不添噪", ok.symptom)
        assertNull(ok.cause)
        assertNull(ok.guidance)
    }

    @Test
    fun test方言按歧义冲突不可用给不同原因() {
        val ambiguous = EnvironmentCheckRules.dialect(
            "候选方言: MySQL (歧义)", "候选数据源: a, b", weak = true,
            status = DialectDecisionEngine.Status.AMBIGUOUS,
        )
        val conflict = EnvironmentCheckRules.dialect(
            "候选方言: Oracle (冲突)", "候选数据源: a", weak = true,
            status = DialectDecisionEngine.Status.CONFLICT,
        )
        val unavailable = EnvironmentCheckRules.dialect(
            "候选方言: MySQL (不可用)", null, weak = true,
            status = DialectDecisionEngine.Status.UNAVAILABLE,
        )
        val unresolved = EnvironmentCheckRules.dialect(null, null, weak = true)
        val causes = listOf(ambiguous, conflict, unavailable, unresolved).map { it.cause }
        assertTrue("四态都必须给原因：$causes", causes.all { !it.isNullOrBlank() })
        assertEquals("四种成因必须各不相同（处置动作不同）", 4, causes.toSet().size)
        listOf(ambiguous, conflict, unavailable, unresolved).forEach {
            assertEquals(CheckLevel.WARN, it.level)
            assertNotNull(it.symptom)
            assertNotNull(it.guidance)
        }
        assertTrue(
            "歧义原因要指向「多个候选没收敛」：${ambiguous.cause}",
            ambiguous.cause!!.contains("候选"),
        )
        assertTrue(
            "冲突原因要指向「手动值」：${conflict.cause}",
            conflict.cause!!.contains("手动"),
        )
    }

    @Test
    fun test增强捕获各分支给对应症状与原因() {
        val off = EnvironmentCheckRules.agent(false, null, null, premainSession = false)
        val statsMissing = EnvironmentCheckRules.agent(true, "已挂载", null, premainSession = false)
        val zero = EnvironmentCheckRules.agent(
            true, "已挂载", AgentAttachStats(matched = 0, weavable = 0, woven = 0),
            premainSession = false,
        )
        val premainZero = EnvironmentCheckRules.agent(
            true, "已挂载", AgentAttachStats(matched = 0, weavable = 0, woven = 0),
            premainSession = true,
        )
        val failed = EnvironmentCheckRules.agent(
            true, "已挂载", AgentAttachStats(matched = 3, weavable = 3, woven = 2, failed = 1),
            premainSession = false,
        )
        val catchAllKilled = EnvironmentCheckRules.agent(
            true, "已挂载", AgentAttachStats(matched = 3, weavable = 3, woven = 3, catchAll = 2),
            premainSession = false,
        )
        val ok = EnvironmentCheckRules.agent(
            true, "已挂载", AgentAttachStats(matched = 5, weavable = 5, woven = 5),
            premainSession = false,
        )
        val nonGreen = listOf(off, statsMissing, zero, premainZero, failed, catchAllKilled)
        nonGreen.forEach {
            assertNotNull("${it.facts} 必须给症状", it.symptom)
            assertNotNull("${it.facts} 必须给原因", it.cause)
            assertNotNull("${it.facts} 必须给动作", it.guidance)
        }
        val causes = nonGreen.map { it.cause }.toSet()
        assertEquals("六个分支的成因必须各不相同", 6, causes.size)
        assertEquals(CheckLevel.FAIL, zero.level)
        assertEquals(CheckLevel.WARN, premainZero.level)
        assertNull("绿灯不添噪", ok.symptom)
        assertNull(ok.cause)
    }

    @Test
    fun test捕获覆盖各分支给对应症状与原因() {
        val off = EnvironmentCheckRules.capture(false, 0, 0, 0, 0, 0, 0)
        val noSql = EnvironmentCheckRules.capture(true, 0, 1, 120, 0, 0, 0)
        val noSource = EnvironmentCheckRules.capture(true, 0, 0, 0, 0, 0, 0)
        val ok = EnvironmentCheckRules.capture(true, 7, 2, 1500, 8, 0, 0)
        listOf(off, noSql, noSource).forEach {
            assertNotNull(it.symptom)
            assertNotNull(it.cause)
            assertNotNull(it.guidance)
        }
        assertEquals("三态成因必须各不相同", 3, setOf(off.cause, noSql.cause, noSource.cause).size)
        assertNull(ok.symptom)
        assertNull(ok.cause)
    }

    @Test
    fun testCommunity不可用时给三段且绿灯无噪音() {
        val unavailable = EnvironmentCheckRules.community(false)
        assertNotNull(unavailable.symptom)
        assertNotNull(unavailable.cause)
        assertNotNull(unavailable.guidance)
        val available = EnvironmentCheckRules.community(true)
        assertNull(available.symptom)
        assertNull(available.cause)
    }

    @Test
    fun test降级项也给症状与原因而不是只说没采到() {
        val degraded = EnvironmentCheckRules.degraded(
            titleKey = EnvironmentCheckRules.TITLE_KEY_DATA_SOURCE,
            outcome = CheckOutcome.ITEM_TIMEOUT,
            elapsedMs = 3001,
            budgetMs = 3000,
        )
        assertNotNull(degraded.symptom)
        assertNotNull(degraded.cause)
        assertTrue("原因要写明「缺失不等于正常」：${degraded.cause}", degraded.cause!!.contains("缺失"))
    }

    // ---------- 档 2：已知问题表 ----------

    /** 每条规则一条真机形态样本（消息行 + 栈续行），供命中/大小写/缺特征三例共用。 */
    private val samples: Map<String, String> = mapOf(
        // 三条「症状类别」样本一律取**真机 2026-11-04 12:40 那次「预览+执行」的原始形态**，
        // 且刻意的落点不是 ui-001/ui-002（执行链 / action update / SqlExecutor）——这正是首版
        // 规则漏检的场景：18 条真机 SlowOperations 无一落在 ScanBannedApiUsages 上。
        "edt-slow-operations" to
            "2026-11-04 12:40:20,355 [ 101950] SEVERE - #c.i.u.SlowOperations - " +
            "Slow operations are prohibited on EDT. See SlowOperations.assertSlowOperationsAreAllowed javadoc.\n" +
            "java.lang.Throwable: Slow operations are prohibited on EDT.\n" +
            "\tat com.intellij.util.SlowOperations.assertSlowOperationsAreAllowed(SlowOperations.java:114)\n" +
            "\tat cn.song.mybatisplus.visualizer.execute.DataSourceSelectorConsoleKt." +
            "prepareInConsole(DataSourceSelectorConsole.kt:1)",
        "edt-psi-request" to
            "2026-11-04 12:40:31,001 [2358357] SEVERE - #c.i.o.a.i.PreCachedDataContext - " +
            "'psi.File' is requested on EDT by ChooseDataSourceTakeoverGroup#presentation@EditorToolbar " +
            "(cn.song.mybatisplus.visualizer.execute.ChooseDataSourceTakeoverGroup) " +
            "\"Console.Jdbc.ChooseDataSource\". See ActionUpdateThread javadoc.\n" +
            "\tat cn.song.mybatisplus.visualizer.execute.ChooseDataSourceTakeoverGroup." +
            "update(ChooseDataSourceTakeover.kt:170)",
        "write-unsafe" to
            "2026-11-04 12:40:35,000 [2359000] SEVERE - #c.i.o.a.TransactionGuardImpl - " +
            "Write-unsafe context! Model changes are allowed from write-safe contexts only.\n" +
            "\tat cn.song.mybatisplus.visualizer.execute.SqlExecutor.execute(SqlExecutor.kt:59)",
        "ui-001" to "2026-11-04 12:00:00,123 [  42] SEVERE - Slow operations are prohibited on EDT\n" +
            "\tat cn.song.mybatisplus.visualizer.action.ScanBannedApiUsagesAction.present(ScanBannedApiUsagesAction.kt:66)",
        "ui-002" to "2026-11-04 12:00:01,456 [  42] ERROR - Read access is allowed from inside read-action only\n" +
            "\tat cn.song.mybatisplus.visualizer.execute.DataSourceSelector.consumeCachedTempPsiFile(DataSourceSelector.kt:1283)",
        "driver-missing" to "2026-11-04 12:00:02,000 [  42] ERROR - java.lang.ClassNotFoundException: " +
            "com.mysql.cj.jdbc.Driver",
        "psi-invalid" to "2026-11-04 12:00:03,000 [  42] ERROR - " +
            "com.intellij.psi.PsiInvalidElementAccessException: Element is invalid",
        "exec-temp-fail" to "2026-11-04 12:00:04,000 [  42] ERROR - java.io.IOException: " +
            "Failed to create temporary SQL file: tmp.sql",
        "table-unresolved" to "2026-11-04 12:00:05,000 [  42] WARN - Unable to resolve table 'orders'",
        "platform-component" to "2026-11-04 12:00:06,000 [  10] ERROR - " +
            "Cannot init component state (componentName=DocCommentManagerImpl)",
    )

    @Test
    fun test规则表十条且样本齐全() {
        assertEquals("第一批规则固定 10 条（7 条原始 + 3 条线程症状类别）", 10, KnownIssueCatalog.rules.size)
        assertEquals("id 不得重复", 10, KnownIssueCatalog.rules.map { it.id }.toSet().size)
        assertEquals(
            "每条规则都必须有真机形态样本（否则本组用例会假绿）",
            KnownIssueCatalog.rules.map { it.id }.toSet(),
            samples.keys,
        )
    }

    @Test
    fun test每条规则命中且大小写不敏感() {
        KnownIssueCatalog.rules.forEach { rule ->
            val sample = samples.getValue(rule.id)
            assertEquals("${rule.id} 应命中", listOf(rule.id), KnownIssueCatalog.scan(sample).map { it.id })
            assertEquals(
                "${rule.id} 大小写变体也应命中",
                listOf(rule.id),
                KnownIssueCatalog.scan(sample.uppercase()).map { it.id },
            )
        }
    }

    @Test
    fun test每条规则缺一项特征即不命中() {
        KnownIssueCatalog.rules.forEach { rule ->
            val sample = samples.getValue(rule.id)
            val sabotaged = if (rule.allOf.isNotEmpty()) {
                sample.replace(rule.allOf.first(), "", ignoreCase = true)
            } else {
                rule.eachOfAny.first().fold(sample) { acc, feature -> acc.replace(feature, "", ignoreCase = true) }
            }
            assertTrue(
                "${rule.id} 摘掉 $sabotaged 后必须不命中（特征被删还命中 = 规则过宽）",
                KnownIssueCatalog.scan(sabotaged).isEmpty(),
            )
        }
    }

    @Test
    fun test已知特化命中时通用症状类别不重复报() {
        // 通用规则是特化的超集：ui-001 的样本必然也含「slow operations on EDT + 插件栈帧」。
        // 若不去重，用户会看到「插件在 EDT 慢操作」和「已知缺陷 UI-001」两条同源结论而不知先修哪个。
        val ui001 = samples.getValue("ui-001")
        assertEquals(
            "已知特化命中时通用规则必须让位",
            listOf("ui-001"),
            KnownIssueCatalog.scan(ui001).map { it.id },
        )
        // 未知落点（真机那次执行链/action update 的形态）仍由通用规则兜底。
        val unknownSite = samples.getValue("edt-slow-operations")
        assertEquals(
            "未知落点必须由通用规则兜住",
            listOf("edt-slow-operations"),
            KnownIssueCatalog.scan(unknownSite).map { it.id },
        )
    }

    @Test
    fun testui002必须消息与插件栈帧同时出现() {
        val messageOnly = "2026-11-04 [1] ERROR - Read access is allowed from inside read-action only"
        val frameOnly = "\tat cn.song.mybatisplus.visualizer.execute.DataSourceSelector.consumeCachedTempPsiFile(X.kt:1)"
        assertTrue("只有消息行不得命中 ui-002", KnownIssueCatalog.scan(messageOnly).isEmpty())
        assertTrue("只有栈帧不得命中 ui-002", KnownIssueCatalog.scan(frameOnly).isEmpty())
        assertEquals(
            "消息行 + 栈帧（跨行）才命中",
            listOf("ui-002"),
            KnownIssueCatalog.scan("$messageOnly\n$frameOnly").map { it.id },
        )
    }

    @Test
    fun test驱动缺失必须异常类名与驱动包两个维度() {
        assertTrue(
            "只有异常类名（非驱动包）不得命中",
            KnownIssueCatalog.scan("java.lang.ClassNotFoundException: com.example.AppService").isEmpty(),
        )
        assertTrue(
            "只有驱动包名（驱动加载日志，非异常）不得命中",
            KnownIssueCatalog.scan("Loading class `com.mysql.cj.jdbc.Driver'. This is deprecated.").isEmpty(),
        )
        assertEquals(
            "异常 + 驱动包才命中",
            listOf("driver-missing"),
            KnownIssueCatalog.scan("NoClassDefFoundError: com/mysql/cj/jdbc/Driver").map { it.id },
        )
    }

    @Test
    fun test同一规则多次命中只报一条且次数累加() {
        val text = (samples.getValue("table-unresolved") + "\n").repeat(3)
        val hits = KnownIssueCatalog.scan(text)
        assertEquals("同一规则只报一条", 1, hits.size)
        assertEquals("次数必须累加", 3, hits.single().count)
    }

    // ---------- 档 2：日志体检项 ----------

    @Test
    fun test日志命中项等级取最高且上限五条() {
        val text = samples.values.joinToString("\n")
        val item = EnvironmentCheckRules.logDiagnosis(text, text.length.toLong())
        assertEquals("命中 10 条，最多列 5 条", 5, item.facts.size)
        assertEquals("等级取最高命中项（driver-missing 为失败）", CheckLevel.FAIL, item.level)
        assertTrue("最严重的一条要排在最前：${item.facts}", item.facts.first().contains("driver-missing"))
        assertNotNull(item.symptom)
        assertNotNull(item.cause)
        assertNotNull(item.guidance)
    }

    @Test
    fun test日志未命中给OK并写明扫描范围() {
        val text = "2026-11-04 12:00:00,000 [  1] INFO - nothing interesting\n"
        val item = EnvironmentCheckRules.logDiagnosis(text, text.length.toLong())
        assertEquals(CheckLevel.OK, item.level)
        assertTrue("必须写明未发现已知模式：${item.facts}", item.facts.first().contains("未发现已知异常模式"))
        assertTrue("必须写明扫描了多大窗口：${item.facts}", item.facts.any { it.contains("KB") })
        assertNull("绿灯不给动作", item.guidance)
        assertNull(item.symptom)
    }

    @Test
    fun test日志不可读降级为不可用并区分缺失与读取失败() {
        val missing = EnvironmentCheckRules.logUnavailable("envCheck.log.missing")
        assertEquals(CheckLevel.UNAVAILABLE, missing.level)
        assertTrue("缺失要说清未找到 idea.log：${missing.facts}", missing.facts.single().contains("未找到 idea.log"))
        assertNotNull(missing.guidance)

        val failed = EnvironmentCheckRules.logUnavailable("envCheck.log.readFailed", "IOException")
        assertEquals(CheckLevel.UNAVAILABLE, failed.level)
        assertTrue("读取失败要带异常类名：${failed.facts}", failed.facts.single().contains("IOException"))
    }

    @Test
    fun test报告不携带日志原文() {
        val raw = buildString {
            appendLine("2026-11-04 12:00:01,000 [  42] ERROR - Read access is allowed from inside read-action only")
            appendLine("\tat cn.song.mybatisplus.visualizer.execute.DataSourceSelector.consumeCachedTempPsiFile(X.kt:1283)")
            appendLine("SELECT * FROM payroll_secret_zzz WHERE token = 'abc-SECRET-zzz'")
            appendLine("jdbc:mysql://10.9.8.7:3306/secret_db?user=root")
            append("/home/alice/private/project/src/main/resources/mapper/Hidden.xml")
        }
        val item = EnvironmentCheckRules.logDiagnosis(raw, raw.length.toLong())
        val text = report(item).render()
        assertTrue("前提：确实命中了 ui-002（否则隐私断言假绿）：$text", text.contains("ui-002"))
        listOf(
            "payroll_secret_zzz",
            "abc-SECRET-zzz",
            "jdbc:mysql",
            "10.9.8.7",
            "/home/alice",
            "consumeCachedTempPsiFile",
            "Read access is allowed",
            "Hidden.xml",
        ).forEach { fragment ->
            assertFalse("报告不得含日志原文片段「$fragment」：$text", text.contains(fragment))
        }
    }

    private fun assertNotEquals(message: String, unexpected: Any?, actual: Any?) =
        assertFalse("$message（都是 $unexpected）", unexpected == actual)
}
