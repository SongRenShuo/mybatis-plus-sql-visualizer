package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.ProjectDialectEvidence.ConfigFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-DialectInferFix / T-DialectInferSmart：没有 IDE 数据源时的方言证据。
 *
 * 覆盖三层证据的**判定口径**（纯函数）与「多来源不一致就不猜」的 fail-closed 语义；
 * T-DialectInferSmart 追加：激活 profile 优先、profile 覆盖基础文件、占用「其它环境」的文件不投票。
 * 真实文件扫描（含测试资源排除、模块收窄）由 `readProjectTexts` 承担，这里用测试缝注入文本。
 */
class ProjectDialectEvidenceTest {

    @Test
    fun `jdbc url in config files yields dialect`() {
        assertEquals(
            listOf("PostgreSQL"),
            ProjectDialectEvidence.dialectsInUrls("spring.datasource.url=jdbc:postgresql://h:5432/db?x=1"),
        )
        assertEquals(
            emptyList<String>(),
            ProjectDialectEvidence.dialectsInUrls("spring.datasource.url=jdbc:redis://h:6379/1"),
        )
    }

    @Test
    fun `multiple distinct config dialects do not guess`() {
        val files = listOf(
            ConfigFile("application.yml", "spring.datasource.url=jdbc:postgresql://h:5432/a"),
            ConfigFile("bootstrap.yml", "spring.datasource.url=jdbc:mysql://h:3306/b"),
        )
        val fake = fakeProject()
        try {
            ProjectDialectEvidence.resetForTest()
            ProjectDialectEvidence.configFilesForTest = { files }
            ProjectDialectEvidence.buildTextsForTest = { emptyList() }
            ProjectDialectEvidence.enabled = true
            assertNull(ProjectDialectEvidence.configDialect(fake))
            assertNull(ProjectDialectEvidence.forProject(fake))
        } finally {
            ProjectDialectEvidence.resetForTest()
        }
    }

    @Test
    fun `single config dialect becomes fallback evidence`() {
        val fake = fakeProject()
        try {
            ProjectDialectEvidence.resetForTest()
            ProjectDialectEvidence.configFilesForTest = {
                listOf(ConfigFile("application.yml", "url: jdbc:postgresql://121.40.45.91:5432/cyzc_db1"))
            }
            val evidence = ProjectDialectEvidence.forProject(fake)
            assertEquals("PostgreSQL", evidence?.dialectName)
            assertEquals(DialectDecisionEngine.Source.PROJECT_CONFIG, evidence?.source)
        } finally {
            ProjectDialectEvidence.resetForTest()
        }
    }

    // ---------- T-DialectInferSmart：激活 profile 优先 ----------

    @Test
    fun `active profile file wins over other environments`() {
        // dev=MySQL、prod=PostgreSQL，激活 dev —— 只能识别成 MySQL，不能被 prod 搅成「无共识」
        val files = listOf(
            ConfigFile("application.yml", "spring:\n  profiles:\n    active: dev\n"),
            ConfigFile("application-dev.yml", "spring.datasource.url: jdbc:mysql://h:3306/dev"),
            ConfigFile("application-prod.yml", "spring.datasource.url: jdbc:postgresql://h:5432/prod"),
        )
        assertEquals("MySQL", configDialectOf(files))
    }

    @Test
    fun `active profile overrides base file`() {
        // Spring 语义：profile 专属覆盖基础配置
        val files = listOf(
            ConfigFile("application.yml", "spring.profiles.active: prod\nspring.datasource.url: jdbc:mysql://h:3306/base"),
            ConfigFile("application-prod.yml", "spring.datasource.url: jdbc:postgresql://h:5432/prod"),
        )
        assertEquals("PostgreSQL", configDialectOf(files))
    }

    @Test
    fun `profile without url falls back to base file`() {
        val files = listOf(
            ConfigFile("application.yml", "spring.profiles.active: dev\nspring.datasource.url: jdbc:oracle:thin:@h:1521:xe"),
            ConfigFile("application-dev.yml", "logging.level.root: debug"),
        )
        assertEquals("Oracle", configDialectOf(files))
    }

    @Test
    fun `unknown active profile falls back to whole project consensus`() {
        // ${ENV} 无默认值 = 未知：退回旧行为（全部文件求共识）；这里只有一个库 → 仍可识别
        val files = listOf(
            ConfigFile("application.yml", "spring.profiles.active: \${SPRING_PROFILE}\n"),
            ConfigFile("application-dev.yml", "spring.datasource.url: jdbc:mysql://h:3306/dev"),
        )
        assertEquals("MySQL", configDialectOf(files))
    }

    @Test
    fun `profile placeholder default value is used`() {
        val files = listOf(
            ConfigFile("application.yml", "spring:\n  profiles:\n    active: \${SPRING_PROFILE:prod}\n"),
            ConfigFile("application-dev.yml", "spring.datasource.url: jdbc:mysql://h:3306/dev"),
            ConfigFile("application-prod.yml", "spring.datasource.url: jdbc:postgresql://h:5432/prod"),
        )
        assertEquals("PostgreSQL", configDialectOf(files))
    }

    @Test
    fun `properties style active profile and include list are supported`() {
        val active = ProjectDialectEvidence.activeProfilesIn("spring.profiles.active=dev")
        assertEquals(listOf("dev"), active)
        val include = ProjectDialectEvidence.activeProfilesIn("spring.profiles.include: common, local")
        assertEquals(listOf("common", "local"), include)
        val bracket = ProjectDialectEvidence.activeProfilesIn(
            "spring:\n  profiles:\n    active: [dev, test]  # comment\n",
        )
        assertEquals(listOf("dev", "test"), bracket)
        val listForm = ProjectDialectEvidence.activeProfilesIn(
            "spring:\n  profiles:\n    active:\n      - dev\n      - local\n",
        )
        assertEquals(listOf("dev", "local"), listForm)
    }

    @Test
    fun `profile suffix parsing`() {
        assertNull(ProjectDialectEvidence.profileSuffix("application.yml"))
        assertNull(ProjectDialectEvidence.profileSuffix("bootstrap.properties"))
        assertEquals("dev", ProjectDialectEvidence.profileSuffix("application-dev.yml"))
        assertEquals("prod", ProjectDialectEvidence.profileSuffix("bootstrap-prod.properties"))
        assertNull(ProjectDialectEvidence.profileSuffix("application.yml.bak"))
    }

    @Test
    fun `maven filtered profile value is treated as unknown`() {
        assertTrue(ProjectDialectEvidence.activeProfilesIn("spring.profiles.active: @profiles.active@").isEmpty())
    }

    // ---------- 其余 ----------

    @Test
    fun `driver dependency markers map to dialects`() {
        assertEquals(
            listOf("PostgreSQL"),
            ProjectDialectEvidence.dialectsInDependencies(
                "implementation(\"org.postgresql:postgresql:42.7.3\")",
            ),
        )
        assertEquals(
            listOf("MySQL", "MariaDB"),
            ProjectDialectEvidence.dialectsInDependencies(
                "implementation(\"com.mysql:mysql-connector-j:8.4.0\")\n" +
                    "implementation(\"org.mariadb.jdbc:mariadb-java-client:3.3.3\")",
            ).distinct(),
        )
    }

    @Test
    fun `dependency evidence is used when config has no url`() {
        val fake = fakeProject()
        try {
            ProjectDialectEvidence.resetForTest()
            ProjectDialectEvidence.configFilesForTest = { emptyList() }
            ProjectDialectEvidence.buildTextsForTest = { listOf("runtimeOnly 'org.postgresql:postgresql:42.7.3'") }
            val evidence = ProjectDialectEvidence.forProject(fake)
            assertEquals("PostgreSQL", evidence?.dialectName)
            assertEquals(DialectDecisionEngine.Source.PROJECT_DEPENDENCY, evidence?.source)
        } finally {
            ProjectDialectEvidence.resetForTest()
        }
    }

    // ---------- T-XcDialects3（券 03 + 券 05-驱动标记）：瀚高 / 海量 / 金仓 PG 协议版 ----------

    @Test
    fun `xc3 driver markers highgo and vastbase coordinates`() {
        // 瀚高两个制品都归 HighGo：HgdbJdbc（jdbc:highgo:）与 hgdb-pgjdbc（原版 pgjdbc 直用，
        // jdbc:postgresql: URL 层按 PostgreSQL 不纠正，坐标层是唯一 HighGo 线索）——
        // com.highgo 与 hgdb 两个标记会同时命中同一坐标文本，故断言 distinct 后唯一
        assertEquals(
            listOf("HighGo"),
            ProjectDialectEvidence.dialectsInDependencies("implementation(\"com.highgo:HgdbJdbc:6.2.5\")").distinct(),
        )
        assertEquals(
            listOf("HighGo"),
            ProjectDialectEvidence.dialectsInDependencies("implementation(\"com.highgo:hgdb-pgjdbc:42.5.0\")").distinct(),
        )
        // 海量 cn.com.vastdata:vastbase-jdbc
        assertEquals(
            listOf("Vastbase"),
            ProjectDialectEvidence.dialectsInDependencies("implementation(\"cn.com.vastdata:vastbase-jdbc:2.16.1v\")"),
        )
    }

    @Test
    fun `xc3 kingbase pg-protocol driver inferred as kingbase not postgres`() {
        // P-15 顺序关键正例：postgresql-for-kingbase 的驱动类是 org.postgresql.Driver、子协议
        // jdbc:postgresql:，坐标是唯一纠正线索——其标记必须排在 "org.postgresql:postgresql"
        // 之前，且该坐标文本不含 "org.postgresql:postgresql" 子串、不会误触 PG 标记
        val fake = fakeProject()
        try {
            ProjectDialectEvidence.resetForTest()
            ProjectDialectEvidence.configFilesForTest = { emptyList() }
            ProjectDialectEvidence.buildTextsForTest = {
                listOf("implementation(\"cn.com.kingbase:postgresql-for-kingbase:9.0.0.jre7\")")
            }
            val evidence = ProjectDialectEvidence.forProject(fake)
            assertEquals("KingBase（人大金仓）", evidence?.dialectName)
            assertEquals(DialectDecisionEngine.Source.PROJECT_DEPENDENCY, evidence?.source)
        } finally {
            ProjectDialectEvidence.resetForTest()
        }
    }

    @Test
    fun `xc3 kingbase8 marker covers mysql and sqlserver variants`() {
        // 子串匹配：kingbase8 覆盖 kingbase8-mysql / kingbase8-sqlserver 变体坐标（核对性正例）
        assertEquals(
            listOf("KingBase（人大金仓）"),
            ProjectDialectEvidence.dialectsInDependencies("implementation(\"cn.com.kingbase:kingbase8-mysql:9.0.1\")"),
        )
        assertEquals(
            listOf("KingBase（人大金仓）"),
            ProjectDialectEvidence.dialectsInDependencies("implementation(\"cn.com.kingbase:kingbase8-sqlserver:9.0.1\")"),
        )
    }

    @Test
    fun `xc3 kingbase pg-protocol plus vanilla pg in one file has no consensus`() {
        // 同一构建文件同时出现金仓 PG 协议版与原版 PG 坐标：标记全量收集后两方言各有命中 →
        // distinct 2 项无共识 → null（fail-closed 不猜，与既有「多驱动不猜」同口径）
        val fake = fakeProject()
        try {
            ProjectDialectEvidence.resetForTest()
            ProjectDialectEvidence.configFilesForTest = { emptyList() }
            ProjectDialectEvidence.buildTextsForTest = {
                listOf(
                    "implementation(\"org.postgresql:postgresql:42.7.3\")\n" +
                        "implementation(\"cn.com.kingbase:postgresql-for-kingbase:9.0.0.jre7\")",
                )
            }
            assertNull(ProjectDialectEvidence.forProject(fake))
        } finally {
            ProjectDialectEvidence.resetForTest()
        }
    }

    @Test
    fun `syntax hint only fires on a single dialect`() {
        assertEquals("MySQL", ProjectDialectEvidence.syntaxHint("select `id` from t limit 10, 20"))
        assertEquals("PostgreSQL", ProjectDialectEvidence.syntaxHint("select id::bigint from t"))
        assertEquals("Oracle", ProjectDialectEvidence.syntaxHint("select nvl(a, 0), rownum from t"))
        assertEquals("SQL Server", ProjectDialectEvidence.syntaxHint("select top 10 [id] from [user]"))
        // 两个方言特征同时出现：不猜
        assertNull(ProjectDialectEvidence.syntaxHint("select `id`::bigint from t"))
        assertNull(ProjectDialectEvidence.syntaxHint("select id from t"))
        assertNull(ProjectDialectEvidence.syntaxHint(null))
    }

    @Test
    fun `cache reuses evidence within ttl and refreshes after`() {
        val fake = fakeProject()
        var now = 1_000L
        try {
            ProjectDialectEvidence.resetForTest()
            ProjectDialectEvidence.clockMs = { now }
            ProjectDialectEvidence.ttlMs = 100L
            var calls = 0
            ProjectDialectEvidence.configFilesForTest = {
                calls++
                listOf(ConfigFile("application.yml", "jdbc:postgresql://h/db"))
            }
            assertEquals("PostgreSQL", ProjectDialectEvidence.forProject(fake)?.dialectName)
            // TTL 窗口内：缓存在，且不需要再扫
            assertEquals("PostgreSQL", ProjectDialectEvidence.cachedOrNull(fake)?.dialectName)
            assertEquals("PostgreSQL", ProjectDialectEvidence.forProject(fake)?.dialectName)
            assertEquals(1, calls)
            now += 1_000L
            assertEquals("PostgreSQL", ProjectDialectEvidence.forProject(fake)?.dialectName)
            assertEquals(2, calls)
            // 过期后 EDT 只读缓存不触发扫描
            now += 1_000L
            assertNull(ProjectDialectEvidence.cachedOrNull(fake))
            assertEquals(2, calls)
        } finally {
            ProjectDialectEvidence.resetForTest()
        }
    }

    private fun configDialectOf(files: List<ConfigFile>): String? {
        val fake = fakeProject()
        return try {
            ProjectDialectEvidence.resetForTest()
            ProjectDialectEvidence.configFilesForTest = { files }
            ProjectDialectEvidence.configDialect(fake)
        } finally {
            ProjectDialectEvidence.resetForTest()
        }
    }

    /** 轻量 Project 替身：本类只把它当弱引用键，不调用任何方法。 */
    private fun fakeProject(): com.intellij.openapi.project.Project =
        java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.openapi.project.Project::class.java.classLoader,
            arrayOf(com.intellij.openapi.project.Project::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as com.intellij.openapi.project.Project
}
