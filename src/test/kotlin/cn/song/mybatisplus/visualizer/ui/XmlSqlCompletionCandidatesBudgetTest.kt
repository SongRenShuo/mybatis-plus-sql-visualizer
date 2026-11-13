package cn.song.mybatisplus.visualizer.ui

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-StartupReadLockFreeze §十九：补全上下文（实体/表候选）的非 GUI 性能验收。
 *
 * 真机背景（2026-11-13 15:00 沙盒，`pre-assembly` 埋点）：
 * ```
 * pre-assembly: sourceCtx=17ms completion=10327ms dumb=false
 * 同一语句第二次：completion=5924ms                    ← 无缓存，第二次照样慢
 * ```
 * 根因：`buildCandidates` 把全项目 `@TableName` 实体**逐个** `EntityResolver.resolve`，
 * 最后才 `take(50)`——2500 表工程上万分之几的候选要全解析一遍。
 *
 * 两条判据（都在本用例里可证伪）：
 *  1. 候选数必须按上限截断（不再解析全部）；
 *  2. 同一次会话内第二次解析必须走缓存（显著快于首次）。
 */
class XmlSqlCompletionCandidatesBudgetTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        XmlSqlCompletionContextResolver.clearCandidatesCacheForTest()
        // 放宽预算：本用例要证的是「上限截断」而不是「预算截断」（两者都能省时间，但机制不同）
        XmlSqlCompletionContextResolver.candidatesBudgetMs = 60_000L
    }

    override fun tearDown() {
        try {
            XmlSqlCompletionContextResolver.clearCandidatesCacheForTest()
            XmlSqlCompletionContextResolver.candidatesBudgetMs = 400L
            // 注入缝必须复原：同一 JVM 里后续测试类会拿到已 dispose 的 PSI 类（实测 6 例连锁红）
            XmlSqlCompletionContextResolver.tableNameEntitiesProviderForTest = null
            // §三十五 口径缝同样复原（null＝按环境判定：单测同步、生产缓存优先）
            XmlSqlCompletionContextResolver.candidatesBlockingBuildForTest = null
        } finally {
            super.tearDown()
        }
    }

    fun test候选按上限截断且二次解析走缓存() {
        val src = StringBuilder("package com.example;\n")
        for (i in 1..60) {
            src.append("@com.baomidou.mybatisplus.annotation.TableName(\"t_perf_$i\")\n")
            src.append("public class PerfEntity$i { private Long id; private String name; }\n")
        }
        val entitiesFile = myFixture.addFileToProject("PerfEntities.java", src.toString())
        val entityClasses = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(entitiesFile, com.intellij.psi.PsiClass::class.java)
            .filter { it.name?.startsWith("PerfEntity") == true }
        assertEquals("夹具必须真的产出 60 个实体类", 60, entityClasses.size)
        // Light 沙盒里 @TableName 注解索引取不到实体（实测返回空），用注入缝喂真实 PSI 类
        XmlSqlCompletionContextResolver.tableNameEntitiesProviderForTest = { entityClasses }
        myFixture.configureByText(
            "PerfMapper.xml",
            """
            <mapper namespace="com.example.PerfMapper">
              <select id="selectAll" resultType="map">select * from t_perf_1</select>
            </mapper>
            """.trimIndent(),
        )
        val tag = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.getAttributeValue("id") == "selectAll" }

        val t0 = System.currentTimeMillis()
        val first = XmlSqlCompletionContextResolver.resolveXml(project, tag)
        val firstMs = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val second = XmlSqlCompletionContextResolver.resolveXml(project, tag)
        val secondMs = System.currentTimeMillis() - t1

        assertTrue("候选必须按上限截断（实际 ${first.candidates.size}）", first.candidates.size <= 50)
        assertEquals("缓存命中后候选数必须一致", first.candidates.size, second.candidates.size)
        assertTrue(
            "实体解析次数必须被上限截断（实际解析 ${XmlSqlCompletionContextResolver.entitiesResolved} 个，" +
                "工程内共 60 个 @TableName 实体；真机就是这一项把它变成 6~10s）",
            XmlSqlCompletionContextResolver.entitiesResolved <= 50,
        )
        assertEquals(
            "同会话第二次解析必须命中候选缓存（真机实测第二次仍 5924ms 就是没缓存）",
            1,
            XmlSqlCompletionContextResolver.candidatesCacheHits,
        )
        assertTrue(
            "同会话第二次解析必须走缓存（实测 首次=${firstMs}ms 二次=${secondMs}ms）",
            secondMs < 150,
        )
    }

    /** §二十一：只读候选缓存不得触发解析（补全上下文构造会走这条，绝不能变贵）。 */
    fun test只读候选缓存不触发解析() {
        assertEquals("冷缓存应为空", 0, XmlSqlCompletionContextResolver.cachedCandidateTables(project).size)
        assertEquals("只读访问不得解析实体", 0, XmlSqlCompletionContextResolver.entitiesResolved)
    }

    /** §二十一：后台补齐后，只读缓存能取到候选（预览打开后补全表名靠它回填）。 */
    fun test后台补齐候选后只读缓存可用() {
        val src = StringBuilder("package com.example;\n")
        for (i in 1..8) {
            src.append("@com.baomidou.mybatisplus.annotation.TableName(\"t_warm_$i\")\n")
            src.append("public class WarmEntity$i { private Long id; }\n")
        }
        val entitiesFile = myFixture.addFileToProject("WarmEntities.java", src.toString())
        val entityClasses = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(entitiesFile, com.intellij.psi.PsiClass::class.java)
            .filter { it.name?.startsWith("WarmEntity") == true }
        XmlSqlCompletionContextResolver.tableNameEntitiesProviderForTest = { entityClasses }

        assertTrue("后台补齐应成功", XmlSqlCompletionContextResolver.warmCandidates(project))
        val cached = XmlSqlCompletionContextResolver.cachedCandidateTables(project)
        assertTrue("补齐后只读缓存应有候选（实际 ${cached.size}）", cached.isNotEmpty())
    }

    /**
     * §二十：预览打开（`includeCandidates=false`）**不得**触发候选解析。
     *
     * 真机 15:31：`ctx detail: candidates=3230ms candidatesCount=1`——2~3 秒只为 1 个候选，
     * 而候选只服务 JOIN 表名补全；预览打开没有任何理由付这笔钱。
     */
    fun test预览打开不解析联表候选() {
        val src = StringBuilder("package com.example;\n")
        for (i in 1..60) {
            src.append("@com.baomidou.mybatisplus.annotation.TableName(\"t_skip_$i\")\n")
            src.append("public class SkipEntity$i { private Long id; }\n")
        }
        val entitiesFile = myFixture.addFileToProject("SkipEntities.java", src.toString())
        val entityClasses = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(entitiesFile, com.intellij.psi.PsiClass::class.java)
            .filter { it.name?.startsWith("SkipEntity") == true }
        XmlSqlCompletionContextResolver.tableNameEntitiesProviderForTest = { entityClasses }
        myFixture.configureByText(
            "SkipMapper.xml",
            """
            <mapper namespace="com.example.SkipMapper">
              <select id="selectSkip" resultType="map">select * from t_skip_1</select>
            </mapper>
            """.trimIndent(),
        )
        val tag = PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.getAttributeValue("id") == "selectSkip" }

        val ctx = XmlSqlCompletionContextResolver.resolveXml(project, tag, includeCandidates = false)

        assertEquals("预览打开不得产出候选", 0, ctx.candidates.size)
        assertEquals("预览打开不得解析任何实体", 0, XmlSqlCompletionContextResolver.entitiesResolved)
    }

    /**
     * §三十五（2026-11-13 20:xx 真机）：**生产口径冷态不得同步解析候选**——命中缓存优先，未命中派预热。
     *
     * 真机读数：冷态首次 `candidates=374ms`（预算 400ms 打满，只解析出 1 个候选；本工程
     * `EntityResolver.resolve` 单次百毫秒级），而候选只服务 JOIN 表名提示，不该压在点击/补全路径上。
     * 反向：把口径改回同步（`candidatesBlockingBuildForTest = true`）→ 首次就拿到候选，本用例必红。
     */
    fun test冷态候选生产口径不同步解析_缓存优先() {
        val src = StringBuilder("package com.example;\n")
        for (i in 1..60) {
            src.append("@com.baomidou.mybatisplus.annotation.TableName(\"t_cold_$i\")\n")
            src.append("public class ColdEntity$i { private Long id; }\n")
        }
        val entitiesFile = myFixture.addFileToProject("ColdEntities.java", src.toString())
        val entityClasses = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(entitiesFile, com.intellij.psi.PsiClass::class.java)
            .filter { it.name?.startsWith("ColdEntity") == true }
        XmlSqlCompletionContextResolver.tableNameEntitiesProviderForTest = { entityClasses }
        XmlSqlCompletionContextResolver.candidatesBlockingBuildForTest = false
        myFixture.configureByText(
            "ColdMapper.xml",
            """
            <mapper namespace="com.example.ColdMapper">
              <select id="selectCold" resultType="map">select * from t_cold_1</select>
            </mapper>
            """.trimIndent(),
        )
        val tag = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)
            .first { it.getAttributeValue("id") == "selectCold" }

        val first = XmlSqlCompletionContextResolver.resolveXml(project, tag, includeCandidates = true)
        assertTrue(
            "冷态首次不得同步解析候选（实际拿到 ${first.candidates.size} 个）",
            first.candidates.size <= 1,
        )

        // 预热落地（单测口径：warm 同步执行）后：只读缓存可用，下一次查询命中候选
        assertTrue(
            "预热落地后只读候选缓存应有内容",
            XmlSqlCompletionContextResolver.cachedCandidateTables(project).isNotEmpty(),
        )
        val second = XmlSqlCompletionContextResolver.resolveXml(project, tag, includeCandidates = true)
        assertTrue("预热后下一次应拿到候选（实际 ${second.candidates.size} 个）", second.candidates.size >= 2)
    }
}
