package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-ConsoleBind（2026-10-28）护栏：执行控制台与预览绑定数据源的一致性判定。
 *
 * 缺陷（录制现场实测）：预览头部「已绑定：mp_demo@localhost」，执行却落在 mpj_demo 的
 * 既有控制台（Services 树 mpsql-exec.sql）——快照查询 `Unknown column 'deleted'`。
 * 根因两层（262 jar 反编译实证，见 [ExecFileBinding] KDoc）：
 *  1. 平台 forced 关联短路：exec 文件路径稳定，attachConsole 写死「文件→数据源」关联，
 *     `getAttachDataSourceRunners` 命中即返回单 runner，插件选择链被短路；
 *  2. 插件复用按名不按身份：`reuseSharedConsole` 只比「记忆名 == 建缓存记录名」，
 *     控制台被翻转后照旧放行。
 *
 * 本类用**假数据源模型**（名字 + UUID 假身份）表驱动覆盖三个纯判定：
 * 复用身份判定（验收主用例）、pin 处置判定、绑定目标解析；并钉死
 * `probeExecBind` 在无 Database 插件语境的 fail-closed 降级（全 null 不抛）。
 */
class ExecFileBindingTest : BasePlatformTestCase() {

    /** 假数据源模型：名字 = 显示名（如 `mp_demo@localhost`），id = getUniqueId 空间。 */
    private data class FakeDs(val name: String, val id: String)

    private val mpDemo = FakeDs("mp_demo@localhost", "uuid-mp-demo")
    private val mpjDemo = FakeDs("mpj_demo@localhost", "uuid-mpj-demo")
    private val jpaDemo = FakeDs("jpa_demo@localhost", "uuid-jpa-demo")
    private val roster = listOf(mpDemo, mpjDemo, jpaDemo)

    private fun nameOf(ds: Any): String? = (ds as? FakeDs)?.name
    private fun idOf(ds: Any): String? = (ds as? FakeDs)?.id
    private fun byId(id: String): Any? = roster.firstOrNull { it.id == id }

    private fun memory(id: String?, name: String?): DataSourceSelector.LastUsedMemory =
        DataSourceSelector.LastUsedMemory(id, name)

    override fun setUp() {
        super.setUp()
        DataSourceSelector.forgetAllLastUsed(project)
    }

    override fun tearDown() {
        try {
            DataSourceSelector.forgetAllLastUsed(project)
        } finally {
            super.tearDown()
        }
    }

    // ======================== 复用身份判定（验收主判据） ========================

    /** ★ 验收主用例：预览绑定 mp_demo 时不得复用 mpj_demo 的控制台。 */
    fun testPreviewBoundToMpDemoDoesNotReuseMpjDemoConsole() {
        val sharedConsoleBoundToMpj = "uuid-mpj-demo"
        assertFalse(
            "预览绑定 mp_demo、共享控制台实际绑在 mpj_demo → 必须拒绝复用（缺陷本体）",
            ExecFileBinding.resolveSharedConsoleReuse(
                intendedDsId = "uuid-mp-demo",
                liveConsoleDsId = sharedConsoleBoundToMpj,
                // 旧判据的两个名字一致——正是缺陷形态：按名放行、按身份必须拦
                legacyMemoryName = "mp_demo@localhost",
                legacySharedRecordedName = "mp_demo@localhost",
            ),
        )
    }

    /** 同一数据源下重复预览仍复用同一控制台（兼容面）。 */
    fun testSameIdentityStillReusesSharedConsole() {
        assertTrue(
            "身份一致（含改名后 UUID 同源）必须放行复用",
            ExecFileBinding.resolveSharedConsoleReuse(
                intendedDsId = "uuid-mp-demo",
                liveConsoleDsId = "uuid-mp-demo",
                legacyMemoryName = null,
                legacySharedRecordedName = null,
            ),
        )
    }

    /** 身份探不到（反射失败/假控制台无 getDataSource）= 不可信 → 宁冷启不复用。 */
    fun testUnprobeableIdentityWithIntendedTargetRefusesReuse() {
        assertFalse(
            "有绑定目标但现场身份探不到 → 不复用（跑错库的代价远大于重建控制台）",
            ExecFileBinding.resolveSharedConsoleReuse(
                intendedDsId = "uuid-mp-demo",
                liveConsoleDsId = null,
                legacyMemoryName = "mp_demo@localhost",
                legacySharedRecordedName = "mp_demo@localhost",
            ),
        )
    }

    /** 无身份目标时保留旧名字判定逐字不变（向后兼容面）。 */
    fun testLegacyNameSemanticsPreservedWhenNoIntendedTarget() {
        assertTrue(
            "无目标：记忆名 == 建缓存记录名 → 复用（旧行为）",
            ExecFileBinding.resolveSharedConsoleReuse(null, null, "mp_demo@localhost", "mp_demo@localhost"),
        )
        assertFalse(
            "无目标：名字不一致 → 不复用（旧行为）",
            ExecFileBinding.resolveSharedConsoleReuse(null, null, "mp_demo@localhost", "mpj_demo@localhost"),
        )
        assertFalse(
            "无目标：记忆缺失 → 不复用（旧行为：null != dsName）",
            ExecFileBinding.resolveSharedConsoleReuse(null, null, null, "mp_demo@localhost"),
        )
    }

    // ======================== pin 处置判定 ========================

    fun testNoPinMeansNoop() {
        val probe = ExecFileBinding.ExecBindProbe(attachedConsoleDsId = null, perFileMappingDsId = null)
        assertNull("无钉源身份", probe.pinnedDsId)
        assertEquals(
            "pin 不存在时不动平台状态（目标明确也一样）",
            ExecFileBinding.Action.NOOP,
            ExecFileBinding.resolveBindAction(false, "uuid-mp-demo", probe).action,
        )
        assertEquals(
            ExecFileBinding.Action.NOOP,
            ExecFileBinding.resolveBindAction(true, null, probe).action,
        )
    }

    fun testUserExplicitSwitchClearsPinToRestoreFullChoiceList() {
        val probe = ExecFileBinding.ExecBindProbe("uuid-mpj-demo", "uuid-mpj-demo")
        val action = ExecFileBinding.resolveBindAction(true, null, probe)
        assertEquals(
            "用户显式要求换源（forceChoose 族）必须清 pin——否则枚举被短路成单源，弹窗名存实亡",
            ExecFileBinding.Action.CLEAR,
            action.action,
        )
    }

    fun testPinnedAsIntendedMeansNoop() {
        val probe = ExecFileBinding.ExecBindProbe(attachedConsoleDsId = null, perFileMappingDsId = "uuid-mp-demo")
        assertEquals(
            "pin 已与绑定目标一致 → NOOP（同一数据源重复预览的复用语义不被折腾）",
            ExecFileBinding.Action.NOOP,
            ExecFileBinding.resolveBindAction(false, "uuid-mp-demo", probe).action,
        )
    }

    fun testPinnedDifferentFromIntendedRealigns() {
        val probe = ExecFileBinding.ExecBindProbe(null, "uuid-mpj-demo")
        val action = ExecFileBinding.resolveBindAction(false, "uuid-mp-demo", probe)
        assertEquals("pin（mpj_demo）≠ 预览绑定（mp_demo）→ 对齐", ExecFileBinding.Action.REALIGN, action.action)
        assertEquals("对齐目标必须是预览绑定的身份", "uuid-mp-demo", action.intendedDsId)
    }

    fun testUnknownIntendedLeavesPinAlone() {
        val probe = ExecFileBinding.ExecBindProbe("uuid-mpj-demo", null)
        assertEquals(
            "绑定目标不可知（无记忆/冲突不猜）→ 不动 pin（保留既有行为）",
            ExecFileBinding.Action.NOOP,
            ExecFileBinding.resolveBindAction(false, null, probe).action,
        )
    }

    /** 平台 provider 顺序：per-file 关联在前（DbConsoleRootDataSourceProvider）、挂载控制台在后。 */
    fun testPerFileMappingTakesPrecedenceOverAttachedConsole() {
        val probe = ExecFileBinding.ExecBindProbe("uuid-mpj-demo", "uuid-jpa-demo")
        assertEquals("mapping 命中在前", "uuid-jpa-demo", probe.pinnedDsId)
        assertEquals(
            "mapping 缺失回落挂载控制台",
            "uuid-mpj-demo",
            ExecFileBinding.ExecBindProbe("uuid-mpj-demo", null).pinnedDsId,
        )
    }

    // ======================== 绑定目标解析（假数据源模型） ========================

    fun testLastUsedMemoryResolvesToRegisteredSource() {
        val resolved = ExecFileBinding.resolveIntendedDataSourceCore(
            roster, ::nameOf, ::idOf, ::byId,
            learnedId = null,
            memory = memory("uuid-mp-demo", "mp_demo@localhost"),
            preferredRunnerName = null,
        )
        assertEquals("last-used UUID 命中在册 mp_demo", mpDemo, resolved)
    }

    fun testDeadUuidFallsBackToUniqueName() {
        val resolved = ExecFileBinding.resolveIntendedDataSourceCore(
            roster, ::nameOf, ::idOf, ::byId,
            learnedId = null,
            memory = memory("uuid-deleted", "jpa_demo@localhost"),
            preferredRunnerName = null,
        )
        assertEquals("UUID 失效（数据源已删）退名字唯一命中", jpaDemo, resolved)
    }

    fun testLearnedBindingBeatsLastUsed() {
        // 「学习优先于 last-used」的可观测形态：学习源命中、last-used 记忆失效（UUID 已死、
        // 名字缺失，无可回退）→ 学习源直接命中。注意与下方冲突用例的边界——学习与 last-used
        // **并存且不同**按设计返回 null（冲突不猜，与 EXPLAIN 回落同规），那里才是冲突语义。
        val resolved = ExecFileBinding.resolveIntendedDataSourceCore(
            roster, ::nameOf, ::idOf, ::byId,
            learnedId = "uuid-mpj-demo",
            memory = memory("uuid-deleted", null),
            preferredRunnerName = null,
        )
        assertEquals("last-used 失效（UUID 死、名字缺）时模块学习绑定命中", mpjDemo, resolved)
    }

    fun testConflictBetweenLearnedAndLastUsedIsNotGuessed() {
        val resolved = ExecFileBinding.resolveIntendedDataSourceCore(
            roster, ::nameOf, ::idOf, ::byId,
            learnedId = "uuid-mpj-demo",
            memory = memory("uuid-jpa-demo", "jpa_demo@localhost"),
            preferredRunnerName = null,
        )
        assertNull("学习与 last-used 冲突必须返回 null（不猜，走既有选择器自愈）", resolved)
    }

    fun testPreferredRunnerNameWinsAndMustBeUnique() {
        assertEquals(
            "显式指定压过冲突的学习/记忆",
            mpDemo,
            ExecFileBinding.resolveIntendedDataSourceCore(
                roster, ::nameOf, ::idOf, ::byId,
                learnedId = "uuid-mpj-demo",
                memory = memory("uuid-jpa-demo", "jpa_demo@localhost"),
                preferredRunnerName = "mp_demo@localhost",
            ),
        )
        assertNull(
            "显式指定不在册 → 不猜，走后续判定",
            ExecFileBinding.resolveIntendedDataSourceCore(
                roster, ::nameOf, ::idOf, ::byId,
                learnedId = null,
                memory = memory(null, null),
                preferredRunnerName = "ghost@nowhere",
            ),
        )
    }

    fun testAmbiguousNameIsNotGuessed() {
        val duplicate = listOf(mpDemo, FakeDs("mp_demo@localhost", "uuid-mp-demo-2"), mpjDemo)
        assertNull(
            "同名多条按 singleOrNull 判歧义 → last-used 名字回退不命中",
            ExecFileBinding.resolveIntendedDataSourceCore(
                duplicate, ::nameOf, ::idOf, { null },
                learnedId = null,
                memory = memory(null, "mp_demo@localhost"),
                preferredRunnerName = null,
            ),
        )
    }

    fun testLearnedIdUnresolvableIsNullNotCrash() {
        assertNull(
            "学习 id 指向已删数据源 → 目标不可知（null），绝不抛",
            ExecFileBinding.resolveIntendedDataSourceCore(
                roster, ::nameOf, ::idOf, ::byId,
                learnedId = "uuid-deleted",
                memory = memory(null, null),
                preferredRunnerName = null,
            ),
        )
    }

    // ======================== 探测降级（Community/无插件 fail-closed） ========================

    /** 无 Database 插件 / 无挂载：探测必须全 null 且不抛——对齐器整体退化为 NOOP。 */
    fun testProbeDegradesGracefullyWithoutConsoleMount() {
        val vf = WriteActionTemp.vf()
        val probe = ExecFileBinding.probeExecBind(project, vf)
        assertNull("无挂载控制台 → console 身份 null", probe.attachedConsoleDsId)
        assertNull("无 per-file 关联 → mapping 身份 null", probe.perFileMappingDsId)
        assertEquals(
            "全 null 探针在明确目标下也必须 NOOP（不折腾平台状态）",
            ExecFileBinding.Action.NOOP,
            ExecFileBinding.resolveBindAction(false, "uuid-mp-demo", probe).action,
        )
    }
}

/** 测试辅助：内存 vf（不落盘——首次实跑发现写 project.basePath 与临时目录生命周期竞态：
 * 目录未建/已被上一用例清理时 FileNotFoundException 闪失；探测降级用例只要求「无挂载」语境，
 * LightVirtualFile 完全够用且无文件系统耦合）。 */
private object WriteActionTemp {
    fun vf(): com.intellij.openapi.vfs.VirtualFile =
        com.intellij.testFramework.LightVirtualFile("mpsql-exec.sql", "SELECT 1")
}
