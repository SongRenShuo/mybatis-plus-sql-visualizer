package cn.song.mybatisplus.visualizer.inspection

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 禁用 API 检查（LocalInspectionTool）集成测试（T-BannedApiCheck S3）。
 *
 * 测的是**薄壳接得对不对**：PSI 走到 `resolveMethod()` → 声明类 FQN → [BannedApiMatcher] →
 * `registerProblem`。匹配语义本身在 [BannedApiMatcherTest]（纯逻辑）里逐条钉住，这里不重复。
 *
 * 断言按「提示文案包含规则说明」过滤——测试环境可能同时跑平台默认检查（未使用变量等），
 * 直接断言 highlight 总数为 1 会把无关告警算进来。
 *
 * 属平台桶（`com.intellij` 引用），不在 `testFast` 快桶内，定向跑。
 */
class BannedApiInspectionTest : BasePlatformTestCase() {

    private fun inspectionWith(vararg rules: BannedApiRule): BannedApiInspection =
        BannedApiInspection().apply {
            val compiled = BannedApiMatcher.compile(rules.toList())
            rulesProviderForTest = { compiled }
        }

    private fun rule(
        className: String = "com.acme.LegacyDao",
        methodName: String = "save.*",
        description: String = "改用 NewDao",
        level: String = BannedApiRule.LEVEL_WARNING,
        classRegex: Boolean = false,
    ) = BannedApiRule(className = className, classRegex = classRegex, methodName = methodName, description = description, level = level)

    private fun configureCallSite() {
        myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package com.acme;

            class LegacyDao {
                void save() {}
                void saveBatch() {}
                void find() {}
            }

            class Client {
                void go(LegacyDao dao) {
                    dao.save();
                    dao.find();
                }
            }
            """.trimIndent(),
        )
    }

    /** 命中用规则说明过滤出的提示（避免平台默认检查混入）。 */
    private fun matched(description: String) =
        myFixture.doHighlighting().filter { it.description?.contains(description) == true }

    fun test命中_warning级别_方法正则整串匹配_非目标方法不报() {
        myFixture.enableInspections(inspectionWith(rule(level = BannedApiRule.LEVEL_WARNING)))
        configureCallSite()

        val hits = matched("改用 NewDao")
        assertEquals("只命中 save()，find() 不命中", 1, hits.size)
        assertEquals(HighlightSeverity.WARNING, hits.single().severity)
        assertEquals(
            "提示文案应含 类#方法 + 规则说明",
            cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle.message(
                "inspection.bannedApi.problem",
                "com.acme.LegacyDao",
                "save",
                "改用 NewDao",
            ),
            hits.single().description,
        )
    }

    fun test命中_error级别_严重度升级为ERROR() {
        myFixture.enableInspections(inspectionWith(rule(level = BannedApiRule.LEVEL_ERROR)))
        configureCallSite()

        val hits = matched("改用 NewDao")
        assertEquals(1, hits.size)
        assertEquals("规则级别 error → 编辑器标红", HighlightSeverity.ERROR, hits.single().severity)
    }

    fun test无规则_零行为() {
        myFixture.enableInspections(inspectionWith())
        configureCallSite()

        assertTrue("空规则表不得产生任何本检查提示", matched("改用 NewDao").isEmpty())
    }

    fun test类正则规则_命中包内其他类() {
        myFixture.enableInspections(
            inspectionWith(
                rule(className = """com\.acme\..*""", classRegex = true, methodName = "find", description = "禁止直查"),
            ),
        )
        configureCallSite()

        val hits = matched("禁止直查")
        assertEquals("正则类规则命中 find()", 1, hits.size)
        assertEquals(HighlightSeverity.WARNING, hits.single().severity)
    }

    fun test方法名任意的规则_该类所有方法命中() {
        myFixture.enableInspections(
            inspectionWith(rule(methodName = "", description = "整个类禁用")),
        )
        configureCallSite()

        val hits = matched("整个类禁用")
        assertEquals("save() 与 find() 两条调用都应命中", 2, hits.size)
    }
}
