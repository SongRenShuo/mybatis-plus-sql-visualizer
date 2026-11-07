package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import cn.song.mybatisplus.visualizer.update.BarrierRegistry

/**
 * F48（T-PerfQuick5）：SqlMapperParserDefinition 编程式注册的卸载摘除。
 *
 * 三面：
 *  1. 注册在位 + [BarrierRegistry] 有账（登记簿口径）；
 *  2. 摘除路径运行时证据（V39：removeExplicitExtension 对显式注册真实生效——旧形态无任何
 *     摘除动作，注册表把实例钉到进程终了）；
 *  3. 裸注册（修复前形态）与 Disposer 挂靠（修复后形态）对照：parent dispose 只摘挂靠者，
 *     裸注册者原样钉死——修复必要性的同环境正反证。
 */
class SqlMapperParserDefinitionRegistrationTest : BasePlatformTestCase() {

    /** 注册表清场：ensureRegistered 的进程内注册不跨测试残留（JUnit 方法序不定，对照测试的 assertNull 依赖无残留前提）。 */
    override fun tearDown() {
        SqlMapperParserDefinition.unregisterForTest()
        super.tearDown()
    }

    fun testF48_注册在位且登记簿有账() {
        SqlMapperParserDefinition.ensureRegistered()
        assertNotNull("注入前注册在位（F37 语义保持）", LanguageParserDefinitions.INSTANCE.forLanguage(SqlMapperLanguage))
        val ids = BarrierRegistry.snapshot().map { it.id }
        assertTrue("登记簿须含 parser definition 条目", "sql-mapper-parser-definition" in ids)
        assertTrue("登记簿须含 SqlMapperLanguage 结构性条目", "sql-mapper-language" in ids)
        assertEquals(
            "parser definition 条目摘除路径 = 容器级联",
            BarrierRegistry.RemovalPath.DISPOSER_CASCADE_ON_UNLOAD,
            BarrierRegistry.snapshot().first { it.id == "sql-mapper-parser-definition" }.removal,
        )
    }

    fun testF48_摘除路径运行时证据() {
        SqlMapperParserDefinition.ensureRegistered()
        assertNotNull(LanguageParserDefinitions.INSTANCE.forLanguage(SqlMapperLanguage))
        SqlMapperParserDefinition.unregisterForTest()
        assertNull("显式摘除后注册表不再持有（V39 运行时证据）", LanguageParserDefinitions.INSTANCE.forLanguage(SqlMapperLanguage))
        // 幂等：重复摘除不抛
        SqlMapperParserDefinition.unregisterForTest()
    }

    /**
     * F67（T-PerfSweep18）：ensureRegistered 前置 registered 短路（@Volatile 读零锁零注册表查询）。
     * 纯性能微件——短路与原 forLanguage 在位判定行为等价（都不重建实例），黑盒无独占判别面，
     * 此处钉「行为等价」两面：已注册态重复调用零副作用（实例同一）、摘除后短路失效须重注册。
     */
    fun testF67_前置短路_已注册态重复调用零副作用且摘除后失效() {
        SqlMapperParserDefinition.ensureRegistered()
        val first = LanguageParserDefinitions.INSTANCE.forLanguage(SqlMapperLanguage)
        assertNotNull(first)
        // 短路路径连打三次：注册表实例不换
        repeat(3) { SqlMapperParserDefinition.ensureRegistered() }
        assertTrue(
            "短路下不重建实例（注册表实例同一）",
            first === LanguageParserDefinitions.INSTANCE.forLanguage(SqlMapperLanguage),
        )
        // 摘除复位短路：再次 ensure 须重新注册
        SqlMapperParserDefinition.unregisterForTest()
        SqlMapperParserDefinition.ensureRegistered()
        assertNotNull(
            "摘除后 ensureRegistered 须重新注册（短路只对已注册态生效）",
            LanguageParserDefinitions.INSTANCE.forLanguage(SqlMapperLanguage),
        )
    }

    fun testF48_裸注册与挂靠注册对照() {
        val registry = LanguageParserDefinitions.INSTANCE
        // 清场：本测试的 assertNull 断言依赖注册表无残留（ensureRegistered 可能被同类/前序用例触发过）
        SqlMapperParserDefinition.unregisterForTest()

        // 反向对照（修复前形态）：裸注册，无摘除挂钩——dispose 无关 parent 后仍在（钉住形态）
        val pinned = SqlMapperParserDefinition()
        registry.addExplicitExtension(SqlMapperLanguage, pinned)
        val unrelated = Disposer.newDisposable()
        Disposer.dispose(unrelated)
        try {
            assertNotNull("裸注册不被任何 parent dispose 摘除（修复前钉住形态）", registry.forLanguage(SqlMapperLanguage))
        } finally {
            registry.removeExplicitExtension(SqlMapperLanguage, pinned)
        }
        assertNull(registry.forLanguage(SqlMapperLanguage))

        // 修复形态：三参挂靠——parent dispose 即摘（卸载时由插件应用级宿主承担 parent 角色）
        val parent = Disposer.newDisposable()
        val hooked = SqlMapperParserDefinition()
        registry.addExplicitExtension(SqlMapperLanguage, hooked, parent)
        assertNotNull("挂靠注册在位", registry.forLanguage(SqlMapperLanguage))
        Disposer.dispose(parent)
        assertNull("parent dispose → 级联摘除（容器卸载同路径）", registry.forLanguage(SqlMapperLanguage))
    }
}
