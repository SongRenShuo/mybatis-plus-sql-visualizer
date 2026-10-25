package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-ModuleScopedLastUsed 护栏：last-used 记忆按模块分桶（模块优先、项目级兜底）。
 *
 * 事故背景：用户在 jpa_demo 模块选了 jpa_demo@localhost，切到 mp_demo 模块预览，灰字仍显示
 * 同一个源——因为项目里并存两套记忆且粒度不一致：项目级 last-used 喂指示行，模块级
 * ModuleDataSourceBindingStore 只喂方言决策。
 *
 * 本类只测**纯键与分层读写**（真 PropertiesComponent，headless 可达），不触 JDBC/DBN。
 *
 * K2J 依赖注记（跨批接缝）：被测 API 落在 B 波 B1 批的 DataSourceSelector.java
 * （master 6aac532a 的模块隔离面增量）——moduleKey 重载族 / LastUsedKeys / forgetAllLastUsed /
 * registeredLastUsedModuleKeys。本测试随 A3 先入库，编译与运行由中央编译收口验证。
 *
 * K2J 互操作：rememberedLookupForTest 是 Java 静态字段
 * `java.util.function.Function<Project, kotlin.Pair<List, java.util.function.Function>>`——
 * Kotlin 函数类型变量不自动 SAM 转换，须显式 `java.util.function.Function { ... }` 构造
 * （系统性坑 2/23）；lambda 收进非 test 前缀的私有工厂防 JUnit3 合成方法误扫（坑 27b）。
 */
class ModuleScopedLastUsedTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PluginSettingsTestSupport.resetToFactoryDefaults()
        DataSourceSelector.forgetAllLastUsed(project)
        DataSourceSelector.rememberedLookupForTest = null
    }

    override fun tearDown() {
        try {
            DataSourceSelector.forgetAllLastUsed(project)
            DataSourceSelector.rememberedLookupForTest = null
            PluginSettingsTestSupport.resetToFactoryDefaults()
        } finally {
            super.tearDown()
        }
    }

    /** 在籍名单 + UUID→名解析注入（与 SqlPreviewPanelBindStateTest 同款互操作形态）。 */
    private fun plantRegisteredDataSources(names: List<String>, nameById: (String) -> String?) {
        DataSourceSelector.rememberedLookupForTest =
            java.util.function.Function { _: com.intellij.openapi.project.Project ->
                names to java.util.function.Function { id: String -> nameById(id) }
            }
    }

    // ---------- W1：键构造（纯函数） ----------

    fun testModuleKeyEmptyFallsBackToProjectLevelKey() {
        assertEquals(
            "无模块键必须用项目级原键（纯 .sql 入口的向后兼容基础）",
            "mpsql.visualizer.last.runner",
            DataSourceSelector.LastUsedKeys.displayNameKey(null),
        )
        assertEquals(
            "空串同样视为无模块（不得拼出 'base.' 这种残键）",
            "mpsql.visualizer.last.runner",
            DataSourceSelector.LastUsedKeys.displayNameKey("   "),
        )
        assertEquals(
            "有模块键则带后缀分桶",
            "mpsql.visualizer.last.runner.jpa_demo",
            DataSourceSelector.LastUsedKeys.displayNameKey("jpa_demo"),
        )
        assertEquals(
            "身份键同构",
            "mpsql.visualizer.last.runner.id.jpa_demo",
            DataSourceSelector.LastUsedKeys.dataSourceIdKey("jpa_demo"),
        )
    }

    // ---------- W2：分层读写 ----------

    fun testModuleScopedReadIsIsolatedFromOtherModules() {
        DataSourceSelector.rememberRunner(project, "jpa_demo@localhost", "uuid-jpa", "jpa_demo")
        DataSourceSelector.rememberRunner(project, "mp_demo@localhost", "uuid-mp", "mp_demo")

        assertEquals(
            "模块 A 必须读到自己那条",
            "jpa_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, "jpa_demo").displayName(),
        )
        assertEquals(
            "模块 B 必须读到自己的，不能被 A 覆盖",
            "mp_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, "mp_demo").displayName(),
        )
        assertEquals(
            "UUID 也要各自对应",
            "uuid-mp",
            DataSourceSelector.lastUsedRunner(project, "mp_demo").dataSourceId(),
        )
    }

    fun testWriteAlsoUpdatesProjectLevelAsFallback() {
        DataSourceSelector.rememberRunner(project, "jpa_demo@localhost", "uuid-jpa", "jpa_demo")
        assertEquals(
            "写模块级时同时写项目级——无模块上下文的入口（纯 .sql）靠它兜底",
            "jpa_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, null).displayName(),
        )
    }

    fun testUnknownModuleFallsBackToProjectLevel() {
        // 只有项目级（模拟无模块入口写入）；新模块首次预览应回落它而不是「未绑定」
        DataSourceSelector.rememberRunner(project, "mp_demo@localhost", "uuid-mp", null)

        assertEquals(
            "模块级未命中必须回落项目级，否则新模块的首次预览会退化成未绑定",
            "mp_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, "brand_new_module").displayName(),
        )
    }

    fun testModuleValueWinsOverProjectLevel() {
        DataSourceSelector.rememberRunner(project, "project_level@localhost", "uuid-proj", null)
        DataSourceSelector.rememberRunner(project, "module_level@localhost", "uuid-mod", "jpa_demo")

        assertEquals(
            "模块级必须优先于项目级",
            "module_level@localhost",
            DataSourceSelector.lastUsedRunner(project, "jpa_demo").displayName(),
        )
    }

    // ---------- W2：失效清理 ----------

    fun testForgetScopedOnlyClearsThatModule() {
        DataSourceSelector.rememberRunner(project, "jpa_demo@localhost", "uuid-jpa", "jpa_demo")
        DataSourceSelector.rememberRunner(project, "mp_demo@localhost", "uuid-mp", "mp_demo")
        // 让项目级兜底指向一个第三值，便于区分「模块级被清」与「回落到项目级」
        DataSourceSelector.rememberRunner(project, "fallback@localhost", "uuid-fb", null)

        DataSourceSelector.forgetLastRunner(project, "jpa_demo")

        val pc = PropertiesComponent.getInstance(project)
        assertNull(
            "被清模块的 name 键必须真的 unset",
            pc.getValue("mpsql.visualizer.last.runner.jpa_demo"),
        )
        assertNull(
            "被清模块的 id 键必须真的 unset",
            pc.getValue("mpsql.visualizer.last.runner.id.jpa_demo"),
        )
        assertEquals(
            "清掉模块级后读取回落项目级（这是设计内的兜底，不是泄漏）",
            "fallback@localhost",
            DataSourceSelector.lastUsedRunner(project, "jpa_demo").displayName(),
        )
        assertEquals(
            "其它模块不受牵连",
            "mp_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, "mp_demo").displayName(),
        )
        assertTrue(
            "登记清单不因单模块清理而丢（还要能清其它模块）",
            DataSourceSelector.registeredLastUsedModuleKeys(project).isNotEmpty(),
        )
    }

    fun testForgetAllClearsEveryTier() {
        DataSourceSelector.rememberRunner(project, "jpa_demo@localhost", "uuid-jpa", "jpa_demo")
        DataSourceSelector.rememberRunner(project, "mp_demo@localhost", "uuid-mp", "mp_demo")

        assertTrue(
            "写入两个模块后应登记到清单（供精确清理）",
            DataSourceSelector.registeredLastUsedModuleKeys(project).containsAll(listOf("jpa_demo", "mp_demo")),
        )

        DataSourceSelector.forgetAllLastUsed(project)

        assertNull("项目级必须清空", DataSourceSelector.lastUsedRunner(project, null).displayName())
        assertNull("模块 jpa 必须清空", DataSourceSelector.lastUsedRunner(project, "jpa_demo").displayName())
        assertNull("模块 mp 必须清空", DataSourceSelector.lastUsedRunner(project, "mp_demo").displayName())
        assertTrue(
            "模块键清单本身也要清（否则下次写会以为已登记）",
            DataSourceSelector.registeredLastUsedModuleKeys(project).isEmpty(),
        )
        // 底层键真被移除（不是仅清单清空）
        val pc = PropertiesComponent.getInstance(project)
        assertNull("模块级 name 键必须 unset", pc.getValue("mpsql.visualizer.last.runner.jpa_demo"))
        assertNull("模块级 id 键必须 unset", pc.getValue("mpsql.visualizer.last.runner.id.jpa_demo"))
    }

    // ---------- W2：与指示行同一入口 ----------

    fun testRememberedDataSourceNameHonorsModuleKey() {
        // 在籍名单与 UUID 解析用测试缝注入（无 Database 插件沙盒真链恒空）
        plantRegisteredDataSources(
            listOf("jpa_demo@localhost", "mp_demo@localhost"),
        ) { id -> when (id) {
            "uuid-jpa" -> "jpa_demo@localhost"
            "uuid-mp" -> "mp_demo@localhost"
            else -> null
        } }
        DataSourceSelector.rememberRunner(project, "jpa_demo@localhost", "uuid-jpa", "jpa_demo")
        DataSourceSelector.rememberRunner(project, "mp_demo@localhost", "uuid-mp", "mp_demo")

        assertEquals(
            "指示行取名入口必须按模块返回",
            "jpa_demo@localhost",
            DataSourceSelector.rememberedDataSourceName(project, "jpa_demo"),
        )
        assertEquals(
            "另一个模块返回自己的",
            "mp_demo@localhost",
            DataSourceSelector.rememberedDataSourceName(project, "mp_demo"),
        )
    }

    fun testNoModuleKeyKeepsLegacyBehavior() {
        plantRegisteredDataSources(
            listOf("legacy@localhost"),
        ) { id -> if (id == "uuid-legacy") "legacy@localhost" else null }
        DataSourceSelector.rememberRunner(project, "legacy@localhost", "uuid-legacy", null)

        assertEquals(
            "不传 moduleKey 的既有调用点行为必须逐字不变（P-4）",
            "legacy@localhost",
            DataSourceSelector.rememberedDataSourceName(project),
        )
        assertNotNull(
            "底层键仍是项目级原键",
            PropertiesComponent.getInstance(project).getValue("mpsql.visualizer.last.runner"),
        )
    }
}
