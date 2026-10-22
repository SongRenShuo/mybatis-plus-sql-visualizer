package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.sql.DialectRegistry
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProjectPluginSettings 项目级设置测试（O-5 Phase B）：
 *  - 项目设置独立于全局
 *  - 开关切换（useProjectSettings on/off）
 *  - 持久化恢复（getState → loadState 往返）
 *  - 空项目降级（无项目设置时使用全局默认值）
 */
class ProjectPluginSettingsTest : BasePlatformTestCase() {

    @Test
    fun testProjectSettingsIndependentFromGlobal() {
        // 项目设置修改不影响全局设置
        val projectSettings = project.service<ProjectPluginSettings>()
        val globalSettings = PluginSettings.getInstance()
        val globalDialectBefore = globalSettings.state.dialect

        projectSettings.state.dialect = "PostgreSQL"
        projectSettings.state.replaceParams = false

        // 全局设置不变
        assertEquals("全局 dialect 不受项目设置影响", globalDialectBefore, globalSettings.state.dialect)
    }

    @Test
    fun testUseProjectSettingsToggle() {
        val projectSettings = project.service<ProjectPluginSettings>()
        val snapshot = projectSettings.getState().copy()

        try {
            // 默认关
            assertFalse("useProjectSettings 默认关", projectSettings.state.useProjectSettings)

            // 开启
            projectSettings.state.useProjectSettings = true
            projectSettings.state.dialect = "Oracle"
            assertTrue("开启后 useProjectSettings=true", projectSettings.state.useProjectSettings)
            assertEquals("项目 dialect 可设置", "Oracle", projectSettings.state.dialect)

            // 关闭后设置保留但不生效
            projectSettings.state.useProjectSettings = false
            assertFalse("关闭后 useProjectSettings=false", projectSettings.state.useProjectSettings)
            assertEquals("关闭后项目设置保留", "Oracle", projectSettings.state.dialect)
        } finally {
            projectSettings.loadState(snapshot)
        }
    }

    @Test
    fun testStateRoundTrip() {
        val projectSettings = project.service<ProjectPluginSettings>()
        val snapshot = projectSettings.getState().copy()

        try {
            projectSettings.state.useProjectSettings = true
            projectSettings.state.dialect = "PostgreSQL"
            projectSettings.state.replaceParams = false
            projectSettings.state.tablePrefix = "sys_"
            projectSettings.state.ignoredFields = "temp"
            projectSettings.state.tableNameInference = PluginSettings.INFERENCE_CAMEL

            // 模拟持久化：getState → loadState
            val saved = projectSettings.getState()
            val fresh = ProjectPluginSettings.State()
            fresh.useProjectSettings = saved.useProjectSettings
            fresh.dialect = saved.dialect
            fresh.replaceParams = saved.replaceParams
            fresh.formatSql = saved.formatSql
            fresh.injectLogicDelete = saved.injectLogicDelete
            fresh.logicDeleteValue = saved.logicDeleteValue
            fresh.tablePrefix = saved.tablePrefix
            fresh.tableSuffix = saved.tableSuffix
            fresh.ignoredFields = saved.ignoredFields
            fresh.tableNameInference = saved.tableNameInference

            assertEquals("往返后 useProjectSettings", true, fresh.useProjectSettings)
            assertEquals("往返后 dialect", "PostgreSQL", fresh.dialect)
            assertEquals("往返后 replaceParams", false, fresh.replaceParams)
            assertEquals("往返后 tablePrefix", "sys_", fresh.tablePrefix)
            assertEquals("往返后 ignoredFields", "temp", fresh.ignoredFields)
            assertEquals("往返后 tableNameInference", PluginSettings.INFERENCE_CAMEL, fresh.tableNameInference)
        } finally {
            projectSettings.loadState(snapshot)
        }
    }

    @Test
    fun testEffectiveSettingsFallbackToGlobal() {
        // 项目设置关闭时，effective 方法应返回全局设置
        val projectSettings = project.service<ProjectPluginSettings>()
        val globalSettings = PluginSettings.getInstance()
        val snapshot = projectSettings.getState().copy()
        val globalSnapshot = globalSettings.getState().copy()

        try {
            // 设置全局
            globalSettings.state.dialect = "MySQL"
            globalSettings.state.tablePrefix = "g_"

            // 项目设置关闭
            projectSettings.state.useProjectSettings = false
            projectSettings.state.dialect = "PostgreSQL"
            projectSettings.state.tablePrefix = "p_"

            // effective 应返回项目值（useProjectSettings=false 时返回全局值）
            assertEquals("关闭时 effectiveDialect 返回全局", "MySQL", projectSettings.effectiveDialect())
            assertEquals("关闭时 effectiveTablePrefix 返回全局", "g_", projectSettings.effectiveTablePrefix())

            // 开启后返回项目值
            projectSettings.state.useProjectSettings = true
            assertEquals("开启时 effectiveDialect 返回项目值", "PostgreSQL", projectSettings.effectiveDialect())
            assertEquals("开启时 effectiveTablePrefix 返回项目值", "p_", projectSettings.effectiveTablePrefix())
        } finally {
            projectSettings.loadState(snapshot)
            globalSettings.loadState(globalSnapshot)
        }
    }

    @Test
    fun testDefaultStateHasCorrectDefaults() {
        val state = ProjectPluginSettings.State()
        assertFalse("useProjectSettings 默认关", state.useProjectSettings)
        assertEquals("dialect 默认空（使用全局）", "", state.dialect)
        assertEquals("tablePrefix 默认空", "", state.tablePrefix)
        assertEquals("tableNameInference 默认空", "", state.tableNameInference)
        assertNull("dialectAuto 默认 null（跟随全局）", state.dialectAuto)
    }

    // ------------------------------------------------------------------
    // T-TS-FixC：方言模式（auto/manual）口径 —— 此前 effectiveDialectAuto 零测试引用
    // ------------------------------------------------------------------

    /**
     * `effectiveDialectAuto()` 四组合矩阵（项目级 × 全局）。
     *
     * 优先级语义：项目级是**显式声明**、全局是**默认值**——故项目级「启用 + dialectAuto 非 null」
     * 时项目值压过全局（含项目 true 压过全局 false）；总开关关闭或 dialectAuto=null 时跟随全局。
     */
    @Test
    fun testEffectiveDialectAutoMatrix() {
        val ps = project.service<ProjectPluginSettings>()
        val global = PluginSettings.getInstance()
        val psSnapshot = ps.getState().copy()
        val globalSnapshot = global.getState().copy()

        try {
            // ① 项目 true + 全局 false → 项目值（核心组合：项目级「智能推断」不得被全局关闭吞掉）
            ps.state.useProjectSettings = true
            ps.state.dialectAuto = true
            global.state.dialectAuto = false
            assertTrue("项目 true + 全局 false → true", ps.effectiveDialectAuto())

            // ② 项目 false + 全局 true → 项目值
            ps.state.dialectAuto = false
            global.state.dialectAuto = true
            assertFalse("项目 false + 全局 true → false", ps.effectiveDialectAuto())

            // ③ 项目 null（未覆盖）+ 全局 false → 跟随全局
            ps.state.dialectAuto = null
            global.state.dialectAuto = false
            assertFalse("项目未覆盖 → 跟随全局 false", ps.effectiveDialectAuto())

            // ④ 总开关关闭 → 项目值（哪怕非 null）不生效，跟随全局
            ps.state.useProjectSettings = false
            ps.state.dialectAuto = true
            global.state.dialectAuto = false
            assertFalse("总开关关闭 → 项目值不生效，跟随全局 false", ps.effectiveDialectAuto())

            global.state.dialectAuto = true
            assertTrue("总开关关闭 + 全局 true → true", ps.effectiveDialectAuto())
        } finally {
            ps.loadState(psSnapshot)
            global.loadState(globalSnapshot)
        }
    }

    /**
     * `DialectRegistry.effective` 直接用例（auto/manual 两分支此前零直接测试引用）。
     *
     * 为什么放在本文件：直接调用需要真实 Application（[PluginSettings] 是 app 级服务）与项目级
     * 服务（[ProjectPluginSettings]），纯 JUnit 的 DialectTest 装不下（那里只钉「服务不可用」的
     * 兜底分支）；而这里正是「项目级 × 全局」优先级语义的归属地。
     *
     * 测试环境推断链必然未命中（无 agent 会话、无 IDE 数据源）→ auto=true 的期望 = `byName(null)`。
     */
    @Test
    fun testDialectRegistryEffectiveMatrix() {
        val ps = project.service<ProjectPluginSettings>()
        val global = PluginSettings.getInstance()
        val psSnapshot = ps.getState().copy()
        val globalSnapshot = global.getState().copy()
        val inferredFallback = DialectRegistry.INSTANCE.byName(null)

        try {
            // ① 项目 true + 全局 false → 推断（修复前返回全局手动值 PostgreSQL = 缺陷）
            ps.state.useProjectSettings = true
            ps.state.dialectAuto = true
            ps.state.dialect = "Oracle"
            global.state.dialectAuto = false
            global.state.dialect = "PostgreSQL"
            assertEquals(
                "项目 true + 全局 false → auto 分支（推断回退 MySQL）",
                inferredFallback,
                DialectRegistry.INSTANCE.effective(project),
            )

            // ② 项目 false + 全局 false → 手动分支取项目级 dialect
            ps.state.dialectAuto = false
            ps.state.dialect = "Oracle"
            assertEquals(
                "项目 false → 手动分支取项目级 dialect",
                OracleDialect.INSTANCE,
                DialectRegistry.INSTANCE.effective(project),
            )

            // ③ 项目 false + 全局 true → 项目手动值压过全局推断
            global.state.dialectAuto = true
            assertEquals(
                "项目 false + 全局 true → 项目手动值",
                OracleDialect.INSTANCE,
                DialectRegistry.INSTANCE.effective(project),
            )

            // ④ 项目未覆盖（总开关关）+ 全局 false → 全局手动值
            ps.state.useProjectSettings = false
            global.state.dialectAuto = false
            assertEquals(
                "项目不生效 + 全局 false → 全局手动值",
                PostgreSQLDialect.INSTANCE,
                DialectRegistry.INSTANCE.effective(project),
            )

            // ⑤ 项目不生效 + 全局 true → 推断
            global.state.dialectAuto = true
            assertEquals(
                "项目不生效 + 全局 true → 推断回退 MySQL",
                inferredFallback,
                DialectRegistry.INSTANCE.effective(project),
            )

            // ⑥ 无项目上下文（psi 侧无参调用点的形态）：推断链不可用 → auto 态回退 MySQL（不取全局手动值）。
            // O-93 语义修订（dc2d703）：旧实现此处返回全局手动值，等于让手动残值劫持 auto 模式
            // （实证=PaginationSimTest 泄漏 "Oracle" 致 SqlPreviewServiceBatchTest 5 例随装箱序间歇爆）；
            // 现钉住新语义防回潮（与 DialectTest 的对照钉同口径）。手动模式（auto=false）仍取全局手动值（断言④已覆盖）。
            global.state.dialectAuto = true
            global.state.dialect = "Oracle"
            assertEquals(
                "project=null 无探测上下文 → auto 回退 MySQL（手动残值不参与，O-93 语义）",
                MySqlDialect.INSTANCE,
                DialectRegistry.INSTANCE.effective(null as com.intellij.openapi.project.Project?),
            )
        } finally {
            ps.loadState(psSnapshot)
            global.loadState(globalSnapshot)
        }
    }
}
