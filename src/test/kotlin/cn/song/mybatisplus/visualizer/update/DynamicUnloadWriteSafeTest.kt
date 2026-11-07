package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Method
import java.nio.file.Paths

/**
 * 卸载/装载派发模态的写安全守卫（T-DynUpdateWriteSafe）。
 *
 * 背景（2026-09-22 真机日志）：`Write-unsafe context!` 的模态串是
 * `[设置对话框, Unloading of SQL Visualizer]`——后者是平台动态卸载压入的 ad-hoc 模态实体，其写安全
 * 资格取「派发时刻」的 `myWritingAllowed` 快照（`LaterInvocator.enterModal:204-207` →
 * `TransactionGuardImpl.enteredModality:199-201`），而原实现用 `ModalityState.any()` 从后台任务派发，
 * posted runnable 一律 `performActivity(false, …)` → 快照恒 false → 卸载窗口内平台自身的
 * commit/落盘/dispose 全部中招（源码链见 [DynamicInstallSupport.dispatchModalityForUnload] KDoc）。
 *
 * 本类钉三件事：①决策函数不得把 `any()` 传下去（改回 `any()` 即红——本项的核心回归锁）；②平台谓词在
 * 本环境具备判别力（**正对照**，防「谓词恒真」的空绿）；③调用点实际派发的模态非 `any()` 且写安全。
 *
 * 证据边界（如实标注）：真实「模态弹窗存续期 + 平台卸载」场景无法 headless 复现（设置对话框与动态
 * 卸载都要真机，且开发态被 `PlatformPluginInstaller.devGuardOrFailure()` 主动拒绝），故本类只断言
 * 「决策 + 派发点」这一层；真机侧的最终证据是派发日志行
 * `dynamic update: dispatch modality=… writeSafe=…`。
 */
class DynamicUnloadWriteSafeTest : BasePlatformTestCase() {

    private var savedDispatcher: ((ModalityState, Runnable) -> Unit)? = null

    override fun setUp() {
        super.setUp()
        savedDispatcher = DynamicInstallSupport.edtDispatcher
    }

    override fun tearDown() {
        savedDispatcher?.let { DynamicInstallSupport.edtDispatcher = it }
        super.tearDown()
    }

    /** 正对照：平台谓词必须能区分 `nonModal`（恒 write-safe）与 `any()`（恒 write-unsafe）。 */
    fun testWriteSafePredicateDiscriminatesAnyFromNonModal() {
        assertTrue(
            "nonModal 必须判写安全（平台契约）",
            DynamicInstallSupport.isWriteSafeDispatch(ModalityState.nonModal()),
        )
        assertFalse(
            "any() 必须判写不安全（平台契约）——本断言是本测试环境判别力的正对照",
            DynamicInstallSupport.isWriteSafeDispatch(ModalityState.any()),
        )
    }

    /** 核心回归锁：`any()` 不得原样作为派发模态传下去。 */
    fun testChooseDispatchModalityRejectsAnyModality() {
        val chosen = DynamicInstallSupport.chooseDispatchModality(ModalityState.any())
        assertFalse("any() 恒 write-unsafe，不得作为卸载派发模态", chosen === ModalityState.any())
        assertTrue("非写安全模态应退化为 nonModal", chosen === ModalityState.nonModal())
    }

    fun testChooseDispatchModalityKeepsWriteSafeModality() {
        assertTrue(
            "写安全模态应原样保留（设置对话框场景靠它立即执行，不被模态推迟）",
            DynamicInstallSupport.chooseDispatchModality(ModalityState.nonModal()) === ModalityState.nonModal(),
        )
    }

    fun testChooseDispatchModalityFallsBackWhenCaptureMissing() {
        assertTrue(
            "EDT 跳板取不到模态时应退 nonModal（宁延迟一拍不违规）",
            DynamicInstallSupport.chooseDispatchModality(null) === ModalityState.nonModal(),
        )
    }

    /** 生产解析出的模态必须写安全（headless 无模态栈 → nonModal）。 */
    fun testResolvedDispatchModalityIsWriteSafe() {
        val resolved = DynamicInstallSupport.dispatchModalityForUnload()
        assertTrue(
            "dispatchModalityForUnload() 解析出的模态必须写安全，实际=$resolved",
            DynamicInstallSupport.isWriteSafeDispatch(resolved),
        )
    }

    /** 调用点钉住：`unloadAndInstallOnEdt` 实际派发用的模态非 `any()` 且写安全。 */
    fun testUnloadDispatchNeverUsesAnyModality() {
        var dispatched: ModalityState? = null
        DynamicInstallSupport.edtDispatcher = { modality, body ->
            dispatched = modality
            body.run()
        }
        val api = DynamicInstallSupport.PlatformApi(
            dynamicPlugins = Any(),
            // T-DynUpdate263：预检入口换成能力族；本用例只验派发模态，不触预检
            unloadGate = DynamicInstallSupport.UnloadGate(
                DynamicInstallSupport.UnloadGateFamily.LEGACY,
                dummyMethod(),
            ),
            unloadPluginMethod = dummyMethod(),
            unloadPluginOptions = null,
            installAndLoadMethod = dummyMethod(),
            loadFromArtifactMethod = dummyMethod(),
            findPluginMethod = dummyMethod(),
        )
        val result = api.unloadAndInstallOnEdt(Any(), Paths.get("unused.zip"), null)
        assertEquals("反射入口为哑方法，两步都应判失败", false to false, result)
        val modality = dispatched
        assertNotNull("派发器必须被调用", modality)
        assertFalse("派发模态不得为 any()", modality === ModalityState.any())
        assertTrue(
            "派发模态必须写安全，实际=$modality",
            DynamicInstallSupport.isWriteSafeDispatch(modality!!),
        )
    }

    /** 哑方法：反射调用必抛（参数个数不匹配）→ 走既有 runCatching 失败分支，不触真实平台卸载。 */
    private fun dummyMethod(): Method = Any::class.java.getMethod("toString")
}
