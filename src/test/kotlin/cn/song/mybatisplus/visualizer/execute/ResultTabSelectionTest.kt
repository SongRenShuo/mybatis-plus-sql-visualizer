package cn.song.mybatisplus.visualizer.execute

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.lang.reflect.Proxy

/**
 * 结果 Tab 选择判定测试（M36.5.22b）。
 *
 * 背景：沙盒实测（17:25 会话）暴露「执行后显示上一次结果」——DBN 产生新结果时**复用同一个
 * Content 实例**并改名（Result 1 → Result 2），此前按实例身份过滤的新旧判定失效，
 * `retry#1 contents=[Output, Result 2], select=null`。
 *
 * 本测试用动态代理精确复现该行为（同一实例、displayName 变更），锁定按 displayName
 * 判定的修复不再回归。判定逻辑已抽为纯函数 [DataSourceSelector.pickResultTab]，
 * 不依赖 Database 插件运行时（单测沙盒无 DBN，计划文档 M36.5.20 已记录该边界）。
 */
class ResultTabSelectionTest : BasePlatformTestCase() {

    /**
     * 可改名的 Content 代理——模拟 DBN 复用实例改名的真实行为。
     * equals/hashCode 保持实例身份语义（与真实 Content 一致），确保测试
     * 锁定的是"名称判定"而非实例判定。
     */
    private class FakeContent(initialName: String) {
        var displayName: String = initialName

        val proxy: Content = Proxy.newProxyInstance(
            FakeContent::class.java.classLoader,
            arrayOf(Content::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getDisplayName" -> displayName
                "setDisplayName" -> {
                    displayName = args?.get(0) as String
                    null
                }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeContent($displayName)"
                else -> null
            }
        } as Content
    }

    /** 反射 getContents() 的假 RunnerLayoutUi（resultContents 经反射调用）。 */
    private class FakeUi(private val tabs: Array<Content>) {
        fun getContents(): Array<Content> = tabs
    }

    // ==================== 场景一：实例复用改名（M36.5.22b 回归核心） ====================

    /**
     * DBN 复用同一个 Content 实例并改名：快照时是 Result 1，提交后变为 Result 2。
     * 必须第 1 拍即选中（此前实例身份过滤会返回 null → 滞后一轮）。
     */
    fun testRenamedContentInstancePickedImmediately() {
        val reused = FakeContent("Result 1")
        val preExisting = setOf("Result 1") // 提交时刻的名称快照
        reused.displayName = "Result 2" // DBN 复用实例改名（沙盒 17:25 实证行为）

        val picked = DataSourceSelector.pickResultTab(arrayOf(reused.proxy), preExisting, attempts = 1)

        assertEquals("改名后的实例应第 1 拍即选中（滞后一轮回归点）", "Result 2", picked?.displayName)
    }

    /** 同上场景，但通过 resultContents 快照 + rename 验证完整判定链（名称快照来源）。 */
    fun testSnapshotThenRenameFullChain() {
        val reused = FakeContent("Result 1")
        val ui = FakeUi(arrayOf(reused.proxy))
        val preExistingNames = DataSourceSelector.resultContents(ui).map { it.displayName }.toSet()
        assertEquals(setOf("Result 1"), preExistingNames)

        reused.displayName = "Result 2" // 执行后 DBN 改名
        val picked = DataSourceSelector.pickResultTab(
            DataSourceSelector.resultContents(ui).toTypedArray(),
            preExistingNames,
            attempts = 1,
        )
        assertEquals("Result 2", picked?.displayName)
    }

    // ==================== 场景二：新 Tab 新名称 ====================

    fun testNewTabNameWinsImmediately() {
        val old = FakeContent("Result 1").proxy
        val newTab = FakeContent("Result 2").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(old, newTab), setOf("Result 1"), attempts = 1)
        assertEquals("Result 2", picked?.displayName)
    }

    fun testMultipleNewTabsPicksFirst() {
        val a = FakeContent("Result 2").proxy
        val b = FakeContent("Result 3").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(a, b), setOf("Result 1"), attempts = 1)
        assertEquals("Result 2", picked?.displayName)
    }

    // ==================== 场景三：旧 Tab 复用阈值（M36.5.22 防抢跑；M36.5.44 降至 4 拍 1.6s） ====================

    /** 只有旧名称 Tab 时，阈值前不得选中——否则把上一轮结果顶进面板（滞后一轮真因）。 */
    fun testStaleTabNotPickedBeforeThreshold() {
        val stale = FakeContent("Result 1").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(stale), setOf("Result 1"), attempts = DataSourceSelector.REUSE_ACCEPT_TICKS - 1)
        assertNull("阈值前不应选中旧 Tab（防旧结果抢跑）", picked)
    }

    /** 阈值后接受复用（DBN 可能不建新 Tab；打点 result tab reused，避免永远等待）。 */
    fun testStaleTabAcceptedAtThreshold() {
        val stale = FakeContent("Result 1").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(stale), setOf("Result 1"), attempts = DataSourceSelector.REUSE_ACCEPT_TICKS)
        assertEquals("Result 1", picked?.displayName)
    }

    // ==================== 场景四：Output 过滤 ====================

    fun testOutputTabExcluded() {
        val output = FakeContent("Output").proxy
        val result = FakeContent("Result 1").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(output, result), emptySet(), attempts = 1)
        assertEquals("Result 1", picked?.displayName)
    }

    fun testChineseOutputTabExcluded() {
        val output = FakeContent("输出").proxy
        val result = FakeContent("Result 1").proxy
        val picked = DataSourceSelector.pickResultTab(arrayOf(output, result), emptySet(), attempts = 1)
        assertEquals("Result 1", picked?.displayName)
    }

    fun testOnlyOutputYieldsNull() {
        val output = FakeContent("Output").proxy
        assertNull(DataSourceSelector.pickResultTab(arrayOf(output), emptySet(), attempts = 40))
    }

    fun testEmptyContentsYieldsNull() {
        assertNull(DataSourceSelector.pickResultTab(emptyArray(), emptySet(), attempts = 40))
    }

    // ==================== 场景五：空名占位（T-ExplainFirstNative 首点病灶） ====================

    /**
     * DBN 首次执行先 add 空名 Content 再异步改名/建网格（262 真机 21:06:45 实证
     * `contentAdded: `）。空名 ∉ 空快照集合恒真，此前第 1 拍即被当新结果选中并强制换入
     * （网格尚未构建），事后无人补换入 → 结果滞留原生控制台。
     */
    fun testBlankNamePlaceholderNotPickedAsNew() {
        val blank = FakeContent("").proxy
        assertNull("空名占位不得当新结果选中", DataSourceSelector.pickResultTab(arrayOf(blank), emptySet(), attempts = 1))
        assertNull("阈值后空名占位也不得复用（不是结果）", DataSourceSelector.pickResultTab(arrayOf(blank), emptySet(), attempts = DataSourceSelector.REUSE_ACCEPT_TICKS))
    }

    /** 空名占位与旧名 Tab 并存：不选空名，走旧 Tab 阈值语义。 */
    fun testBlankNameWithStaleTabWaitsForThreshold() {
        val blank = FakeContent("").proxy
        val stale = FakeContent("Result 1").proxy
        assertNull(DataSourceSelector.pickResultTab(arrayOf(blank, stale), setOf("Result 1"), attempts = 1))
        assertEquals("阈值后选中的是具名旧 Tab 而非占位", "Result 1", DataSourceSelector.pickResultTab(arrayOf(blank, stale), setOf("Result 1"), attempts = DataSourceSelector.REUSE_ACCEPT_TICKS)?.displayName)
    }

    /** 占位改名后（"" → "Result 1"）第 1 拍即选中——真机首点病灶的完整链路复现。 */
    fun testPlaceholderRenamedPickedImmediately() {
        val placeholder = FakeContent("")
        val preExisting = DataSourceSelector.resultContents(FakeUi(arrayOf(placeholder.proxy))).map { it.displayName }.toSet()
        placeholder.displayName = "Result 1" // DBN 异步改名+建网格
        val picked = DataSourceSelector.pickResultTab(arrayOf(placeholder.proxy), preExisting, attempts = 1)
        assertEquals("改名后应立即选中并换入", "Result 1", picked?.displayName)
    }

    // ==================== resultContents 反射读取 ====================

    fun testResultContentsFiltersOutputTabs() {
        val output = FakeContent("Output").proxy
        val outputCn = FakeContent("输出").proxy
        val result = FakeContent("Result 1").proxy
        val list = DataSourceSelector.resultContents(FakeUi(arrayOf(output, outputCn, result)))
        assertEquals(listOf("Result 1"), list.map { it.displayName })
    }

    fun testResultContentsNullUiYieldsEmpty() {
        assertTrue(DataSourceSelector.resultContents(null).isEmpty())
    }

    fun testResultContentsNonReflectiveUiYieldsEmpty() {
        // 无 getContents 方法的对象：反射失败 → 空列表（不抛异常）
        assertTrue(DataSourceSelector.resultContents(Any()).isEmpty())
    }
}
