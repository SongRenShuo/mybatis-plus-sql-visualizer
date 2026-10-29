package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-DialectInferFix（增补券）：没有 IDE 数据源时的方言证据。
 *
 * 覆盖三层证据的**判定口径**（纯函数）与「多来源不一致就不猜」的 fail-closed 语义。
 * 真实文件扫描由 `readProjectTexts` 承担，这里用测试缝注入文本，只验语义。
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
        val texts = listOf(
            "jdbc:postgresql://h:5432/a",
            "jdbc:mysql://h:3306/b",
        )
        val fake = fakeProject()
        try {
            ProjectDialectEvidence.resetForTest()
            ProjectDialectEvidence.configTextsForTest = { texts }
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
            ProjectDialectEvidence.configTextsForTest = { listOf("url: jdbc:postgresql://121.40.45.91:5432/cyzc_db1") }
            val evidence = ProjectDialectEvidence.forProject(fake)
            assertEquals("PostgreSQL", evidence?.dialectName)
            assertEquals(DialectDecisionEngine.Source.PROJECT_CONFIG, evidence?.source)
        } finally {
            ProjectDialectEvidence.resetForTest()
        }
    }

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
            ProjectDialectEvidence.configTextsForTest = { emptyList() }
            ProjectDialectEvidence.buildTextsForTest = { listOf("runtimeOnly 'org.postgresql:postgresql:42.7.3'") }
            val evidence = ProjectDialectEvidence.forProject(fake)
            assertEquals("PostgreSQL", evidence?.dialectName)
            assertEquals(DialectDecisionEngine.Source.PROJECT_DEPENDENCY, evidence?.source)
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
            ProjectDialectEvidence.configTextsForTest = {
                calls++
                listOf("jdbc:postgresql://h/db")
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
