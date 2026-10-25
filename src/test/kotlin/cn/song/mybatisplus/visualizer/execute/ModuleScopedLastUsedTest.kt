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
            DataSourceSelector.lastUsedRunner(project, "jpa_demo").displayName,
        )
        assertEquals(
            "模块 B 必须读到自己的，不能被 A 覆盖",
            "mp_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, "mp_demo").displayName,
        )
        assertEquals(
            "UUID 也要各自对应",
            "uuid-mp",
            DataSourceSelector.lastUsedRunner(project, "mp_demo").dataSourceId,
        )
    }

    fun testWriteAlsoUpdatesProjectLevelAsFallback() {
        DataSourceSelector.rememberRunner(project, "jpa_demo@localhost", "uuid-jpa", "jpa_demo")
        assertEquals(
            "写模块级时同时写项目级——无模块上下文的入口（纯 .sql）靠它兜底",
            "jpa_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, null).displayName,
        )
    }

    fun testUnknownModuleFallsBackToProjectLevel() {
        // 只有项目级（模拟无模块入口写入）；新模块首次预览应回落它而不是「未绑定」
        DataSourceSelector.rememberRunner(project, "mp_demo@localhost", "uuid-mp", null)

        assertEquals(
            "模块级未命中必须回落项目级，否则新模块的首次预览会退化成未绑定",
            "mp_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, "brand_new_module").displayName,
        )
    }

    fun testModuleValueWinsOverProjectLevel() {
        DataSourceSelector.rememberRunner(project, "project_level@localhost", "uuid-proj", null)
        DataSourceSelector.rememberRunner(project, "module_level@localhost", "uuid-mod", "jpa_demo")

        assertEquals(
            "模块级必须优先于项目级",
            "module_level@localhost",
            DataSourceSelector.lastUsedRunner(project, "jpa_demo").displayName,
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
            DataSourceSelector.lastUsedRunner(project, "jpa_demo").displayName,
        )
        assertEquals(
            "其它模块不受牵连",
            "mp_demo@localhost",
            DataSourceSelector.lastUsedRunner(project, "mp_demo").displayName,
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

        assertNull("项目级必须清空", DataSourceSelector.lastUsedRunner(project, null).displayName)
        assertNull("模块 jpa 必须清空", DataSourceSelector.lastUsedRunner(project, "jpa_demo").displayName)
        assertNull("模块 mp 必须清空", DataSourceSelector.lastUsedRunner(project, "mp_demo").displayName)
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
        DataSourceSelector.rememberedLookupForTest = {
            listOf("jpa_demo@localhost", "mp_demo@localhost") to { id: String ->
                when (id) {
                    "uuid-jpa" -> "jpa_demo@localhost"
                    "uuid-mp" -> "mp_demo@localhost"
                    else -> null
                }
            }
        }
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
        DataSourceSelector.rememberedLookupForTest = {
            listOf("legacy@localhost") to { id: String -> if (id == "uuid-legacy") "legacy@localhost" else null }
        }
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
