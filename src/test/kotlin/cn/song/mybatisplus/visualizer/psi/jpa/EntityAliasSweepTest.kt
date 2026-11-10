package cn.song.mybatisplus.visualizer.psi.jpa

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-GutterDupFreeze（S3）：实体别名反查的**有界扫描**守卫用例。
 *
 * 事故背景（20261008 用户真机冻结 70 秒）：`findClassByEntityName` 的慢路径原为无上限全项目短名扫描，
 * 误判输入（链源码里抓到的表名 `t_order`）必然扫完整个项目。本用例钉住「超限即停」这条判别行为——
 * 摘掉 [EntityAliasSweep] 里的上限判定，[testTruncatesAtLimit] 的拉取计数必红（见计划 §S3 反向验证）。
 */
class EntityAliasSweepTest : BasePlatformTestCase() {

    /** 触顶即停：10000 个短名 + 上限 100 → 至多拉取 101 个即按未命中降级，不扫完整个项目。 */
    fun testTruncatesAtLimit() {
        var pulled = 0
        val names = (1..10_000).asSequence().map {
            pulled++
            "N$it"
        }
        var truncatedAt = -1
        val result = EntityAliasSweep.findAliasOwner(
            aliasName = "t_order",
            names = names,
            lookup = { emptyArray() },
            limit = 100,
            onTruncated = { truncatedAt = it },
        )
        assertNull("触顶应按未命中降级", result)
        assertEquals("触顶回调应报告已扫数＝上限", 100, truncatedAt)
        assertTrue("不得继续拉取短名（实际拉取 $pulled）", pulled <= 101)
    }

    /** 命中即止：别名命中后不再继续遍历（原实现同样 break，本用例锁住重构后行为不变）。 */
    fun testStopsAtHit() {
        val clazz = configureEntityClass("User.java", "User", "t_order")
        var pulled = 0
        val names = (1..1_000).asSequence().map {
            pulled++
            if (it == 3) "User" else "X$it"
        }
        val result = EntityAliasSweep.findAliasOwner(
            aliasName = "t_order",
            names = names,
            lookup = { shortName -> if (shortName == "User") arrayOf(clazz) else emptyArray() },
        )
        assertNotNull("应按 @Entity(name = ...) 命中实体类", result)
        assertEquals("User", result!!.qualifiedName)
        assertTrue("命中后应即停（实际拉取 $pulled）", pulled <= 5)
    }

    /** 未命中：别名不等则不返回（含大小写不敏感比较的反向确认）。 */
    fun testNoAliasMatchReturnsNull() {
        val clazz = configureEntityClass("User.java", "User", null)
        val result = EntityAliasSweep.findAliasOwner(
            aliasName = "t_order",
            names = sequenceOf("User", "Other"),
            lookup = { if (it == "User") arrayOf(clazz) else emptyArray() },
        )
        assertNull("无匹配别名应返回 null", result)
    }

    /** 大小写不敏感：`@Entity(name = "UserInfo")` 对 `from userinfo` 应命中。 */
    fun testAliasMatchIsCaseInsensitive() {
        val clazz = configureEntityClass("UserInfo.java", "UserInfo", "UserInfo")
        val result = EntityAliasSweep.findAliasOwner(
            aliasName = "userinfo",
            names = sequenceOf("UserInfo"),
            lookup = { arrayOf(clazz) },
        )
        assertNotNull("别名比较应忽略大小写", result)
    }

    fun testCompleteCandidateScanDoesNotStopAtFirstAlias() {
        val first = (myFixture.addFileToProject("First.java", "@jakarta.persistence.Entity(name=\"Shared\") class First {}") as PsiJavaFile).classes.single()
        val second = (myFixture.addFileToProject("Second.java", "@jakarta.persistence.Entity(name=\"Shared\") class Second {}") as PsiJavaFile).classes.single()
        val scan = EntityAliasSweep.findAliasCandidates("Shared", sequenceOf("First", "Second")) {
            if (it == "First") arrayOf(first) else arrayOf(second)
        }
        assertTrue(scan.complete)
        assertEquals(listOf(first, second), scan.candidates)
    }

    fun testTruncatedCandidateScanDiscardsEvenAnEarlyMatch() {
        val first = (myFixture.addFileToProject("First.java", "@jakarta.persistence.Entity(name=\"Shared\") class First {}") as PsiJavaFile).classes.single()
        val scan = EntityAliasSweep.findAliasCandidates("Shared", sequenceOf("First", "Unknown"), limit = 1) {
            arrayOf(first)
        }
        assertFalse(scan.complete)
        assertTrue(scan.candidates.isEmpty())
    }

    /**
     * 触顶结果**落缓存**：同一名字在同一版本内不重复付整段预算的扫描代价。
     *
     * 背景（20261016 实测）：真实工程的短名空间（含 JDK）恒大于 [EntityAliasSweep.SCAN_LIMIT]，
     * 触顶是常态而非例外；触顶后不缓存 ⇒ 同一个名字**每次预览都重扫一整段预算**（单次约 1s，
     * 且该扫描跑在预览的阻塞读动作里＝持读锁 1s）。摘掉「触顶也写缓存」这一档 → 第二次调用会再扫
     * 一次，本用例的 `scanCountForTest()==1` 必红。
     */
    fun testTruncatedScanIsCachedAndNotRepeated() {
        myFixture.addFileToProject("Foo.java", "@jakarta.persistence.Entity(name=\"Shared\") class Foo {}")
        val context = myFixture.configureByText("Host.java", "class Host {}")
        EntityAliasSweep.resetScanCountForTest()
        EntityAliasSweep.scanLimitForTest = 0
        try {
            assertNull("触顶应按未解析返回", EntityAliasSweep.resolve(project, "Shared", context).psiClass)
            assertEquals("应恰好扫描一次", 1, EntityAliasSweep.scanCountForTest())
            assertNull("第二次仍按未解析返回", EntityAliasSweep.resolve(project, "Shared", context).psiClass)
            assertEquals("触顶结果已落缓存，第二次不得重扫", 1, EntityAliasSweep.scanCountForTest())
        } finally {
            EntityAliasSweep.scanLimitForTest = null
        }
    }

    fun testCandidateScanUsesCaseSensitiveJpaEntityNames() {
        val first = (myFixture.addFileToProject("First.java", "@jakarta.persistence.Entity(name=\"Shared\") class First {}") as PsiJavaFile).classes.single()
        val scan = EntityAliasSweep.findAliasCandidates("shared", sequenceOf("First")) { arrayOf(first) }
        assertTrue(scan.complete)
        assertTrue(scan.candidates.isEmpty())
    }

    fun testCandidateScanPropagatesCancellation() {
        try {
            EntityAliasSweep.findAliasCandidates("Shared", sequenceOf("First")) {
                throw com.intellij.openapi.progress.ProcessCanceledException()
            }
            fail("Cancellation must not be converted into an unresolved entity")
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            // Expected: the surrounding read action owns cancellation.
        }
    }

    // ------------------------------------------------------------------
    // 券 0（T-Ibatis2SlowFix）：同步热路径入口 resolveCachedOnly —— 零扫描
    // ------------------------------------------------------------------

    /**
     * 零扫描入口在冷缓存下一律按未解析返回，且**不触发扫描**（改动前同步预览正是在这里付整段预算）。
     *
     * 两类输入一起钉：① 物理表名（XML 内嵌 SQL 的常态根标识符，扫描必然打空）；② 只以
     * `@Entity(name=...)` 存在的别名（**代价所在**：零扫描入口打不到它，能力留在
     * [EntityAliasSweep.resolve]，缓存被预热后本入口亦会命中）。
     */
    fun testCachedOnlyResolveNeverScans() {
        myFixture.addFileToProject("Foo.java", "@jakarta.persistence.Entity(name=\"Shared\") class Foo {}")
        val context = myFixture.configureByText("Host.java", "class Host {}")
        EntityAliasSweep.resetScanCountForTest()

        assertNull(
            "物理表名：零扫描入口按未解析返回",
            EntityAliasSweep.resolveCachedOnly(project, "t_order", context).psiClass,
        )
        assertNull(
            "只以 @Entity(name) 存在的别名：零扫描入口同样按未解析返回（能力留在 resolve）",
            EntityAliasSweep.resolveCachedOnly(project, "Shared", context).psiClass,
        )
        assertEquals("零扫描入口恒不触发扫描", 0, EntityAliasSweep.scanCountForTest())
    }

    /**
     * 零扫描入口保留**直查**能力：类短名/全限定名命中的实体照旧解析（HQL 根实体＝类名的常规写法），
     * 且同样零扫描——即「移除的是别名全扫，不是实体解析」。
     */
    fun testCachedOnlyResolveKeepsDirectHitWithoutScan() {
        val clazz = configureEntityClass("User.java", "User", null)
        EntityAliasSweep.resetScanCountForTest()

        assertEquals(
            "直查命中的实体仍解析",
            clazz,
            EntityAliasSweep.resolveCachedOnly(project, "User", clazz).psiClass,
        )
        assertEquals("直查命中不扫描", 0, EntityAliasSweep.scanCountForTest())
    }

    /**
     * 在测试夹具里造一个类；[alias] 非空时标注 `@Entity(name = alias)`。
     * 注解写全限定名：`JpaMetadataReader.findFlex` 有短名文本兜底，无需把 JPA 库挂上 classpath
     * （同 `JpaEntityResolverTest.testEntityNameAlias` 的口径）。
     */
    private fun configureEntityClass(fileName: String, className: String, alias: String?): PsiClass {
        val annotation = alias?.let { "@jakarta.persistence.Entity(name = \"$it\")" } ?: ""
        myFixture.configureByText(
            fileName,
            """
            $annotation
            class $className {}
            """.trimIndent(),
        )
        return (myFixture.file as PsiJavaFile).classes.first()
    }
}
