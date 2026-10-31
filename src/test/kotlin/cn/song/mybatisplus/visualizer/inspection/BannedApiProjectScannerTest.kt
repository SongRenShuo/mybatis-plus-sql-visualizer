package cn.song.mybatisplus.visualizer.inspection

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 存量扫描器集成测试（T-BannedApiCheck S4）：真实 PSI + FileTypeIndex 链路上验证统计口径。
 *
 * 与 [BannedApiScanReportTest]（报告排版，纯逻辑）互补：这里证「扫得到、数得对、路径/行号取得到」，
 * 那里证「排版与截断」。属平台桶，不在 `testFast` 快桶内，定向跑。
 *
 * 夹具说明：用 `configureByText(JavaFileType)` 建**一个**含全部类的 Java 文件（与
 * [BannedApiInspectionTest] 同款）——方法调用解析在同一文件内必然成立，且文件落在项目临时目录里
 * 能被 FileTypeIndex 扫到。曾试 `src/...` 普通文件 + 跨文件解析，调用 `resolveMethod()` 落空
 * （普通文件不在源根、未进类索引），0 命中是夹具问题不是扫描器问题。
 */
class BannedApiProjectScannerTest : BasePlatformTestCase() {

    private fun compiledRules(vararg rules: BannedApiRule): CompiledRules =
        BannedApiMatcher.compile(rules.toList())

    fun test扫描项目Java文件_按规则统计命中与位置() {
        myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package com.acme;

            class LegacyDao {
                void save() {}
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
        val rules = compiledRules(
            BannedApiRule(className = "com.acme.LegacyDao", methodName = "save", description = "改用 NewDao"),
        )

        val result = BannedApiProjectScanner.scan(project, rules, EmptyProgressIndicator())

        assertEquals("夹具文件应被扫到", 1, result.scannedFiles)
        assertEquals("只命中 save() 一次", 1, result.totalHits)
        val hit = result.hits.single()
        assertEquals("命中类 FQN", "com.acme.LegacyDao", hit.classFqn)
        assertEquals("命中方法名", "save", hit.methodName)
        assertEquals("说明来自规则", "改用 NewDao", hit.description)
        assertTrue("展示路径应指向夹具文件：${hit.filePath}", hit.filePath.endsWith(".java"))
        assertEquals("dao.save() 在夹具第 10 行", 10, hit.line)
        assertFalse("未取消", result.canceled)
    }

    fun test无规则命中_结果为空但不崩() {
        myFixture.configureByText(
            JavaFileType.INSTANCE,
            """
            package com.acme;

            class Plain {
                void run() { System.currentTimeMillis(); }
            }
            """.trimIndent(),
        )
        val rules = compiledRules(
            BannedApiRule(className = "com.acme.LegacyDao", methodName = "save", description = "改用 NewDao"),
        )

        val result = BannedApiProjectScanner.scan(project, rules, EmptyProgressIndicator())

        assertEquals("无命中", 0, result.totalHits)
        assertTrue("明细为空", result.hits.isEmpty())
        assertFalse("非取消", result.canceled)
    }
}
