package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import javax.swing.Icon

/**
 * T-DsListAddSource（20261111）：数据源列表条目口径收口与末尾「添加数据源…」。
 *
 * 覆盖计划 §七：
 *  1. DbDataSource 投影（mergeLocalDataSources 经注入 resolve 验语义；真实反射链的成功路
 *     依赖 Database jar，默认 test 沙盒无 Database 插件 → 退化路径在此验，成功路登记 not run）；
 *  2. 合并去重（uniqueId 优先、无名条目丢弃、同名不同 id 保留）；
 *  3. B 侧 buildRunnerItems（无名 runner 不渲染 + 末尾添加项）；
 *  4. 反例：全解析失败时 A 侧仍单独出「添加数据源…」（不委托原生组）。
 *
 * 走 BasePlatformTestCase：AnAction/AnActionEvent 构造要 Application 在场（仓内既有口径）。
 */
class DsListAddSourceTest : BasePlatformTestCase() {

    /** 桩：LocalDataSource 形（有 id 有名字）。 */
    private class LocalStub(val id: String, val name: String)

    /** 桩：DbDataSource 形（getDelegate 返回内层 Local）。 */
    private class DelegateStub(target: Any) {
        val inner = target
        fun getDelegate(): Any = inner
    }

    /** 桩：既不是 Local 也没有 delegate（投影必失败）。 */
    private object Bogus

    /** DelegateStub 的投影目标（共享一个 LocalStub，便于断言「内层被解出来」）。 */
    private val delegateTarget = LocalStub("delegate-id", "delegate-ds")

    private val resolveStub: (Any) -> Any? = { ds ->
        when (ds) {
            is LocalStub -> ds
            is DelegateStub -> delegateTarget
            else -> null
        }
    }

    private val idOf: (Any) -> String? = { (it as? LocalStub)?.id }
    private val nameOf: (Any) -> String? = { (it as? LocalStub)?.name?.takeUnless { n -> n.isBlank() } }

    // ---------- 用例 1：DbDataSource 投影 ----------

    fun test投影成功路_delegate被解成Local() {
        val dbDs = DelegateStub(delegateTarget)
        val merged = mergeLocalDataSources(
            registered = emptyList(),
            native = listOf(dbDs),
            resolve = resolveStub,
            uniqueIdOf = idOf,
            nameOf = nameOf,
        )
        assertEquals("投影失败：应解出内层 LocalDataSource", listOf(delegateTarget), merged)
    }

    fun test投影退化路_非Local无delegate一律null() {
        // 默认 test 沙盒无 Database 插件：LocalDataSource 类不存在 → 任何对象都投不出。
        assertNull("非 Local 对象必须返回 null", DataSourceSettings.resolveLocalDataSource(Any()))
        assertNull("delegate 不是 Local 也必须返回 null", DataSourceSettings.resolveLocalDataSource(DelegateStub(Any())))
        assertNull("无 delegate 的对象必须返回 null", DataSourceSettings.resolveLocalDataSource(Bogus))
    }

    fun testnameOrNull_解析不出名字返回null() {
        assertNull("解析不出名字必须返回 null（unknown 死项根因）", DataSourceSettings.nameOrNull(Any()))
        assertNull(DataSourceSettings.nameOrNull(DelegateStub(Any())))
    }

    // ---------- 用例 2：合并去重 ----------

    fun test合并_registered先入_native补集按uniqueId去重() {
        val a = LocalStub("a", "dsA")
        val b = LocalStub("b", "dsB")
        val c = LocalStub("c", "dsC")
        val bTwin = LocalStub("b", "dsB") // native 集合里的重复源（同 id）
        val d = LocalStub("d", "dsD")
        val merged = mergeLocalDataSources(
            registered = listOf(a, b, c),
            native = listOf(bTwin, d),
            resolve = resolveStub,
            uniqueIdOf = idOf,
            nameOf = nameOf,
        )
        assertEquals(listOf(a, b, c, d), merged)
    }

    fun test合并_无名条目一律丢弃_不再渲染Unknown() {
        val named = LocalStub("a", "dsA")
        val nameless = LocalStub("x", "") // 名字解析失败（空名）
        val merged = mergeLocalDataSources(
            registered = listOf(named, nameless),
            native = listOf(Bogus),
            resolve = resolveStub,
            uniqueIdOf = idOf,
            nameOf = nameOf,
        )
        assertEquals("名字取不出的条目必须丢弃（这是 unknown 死项的根因）", listOf(named), merged)
    }

    fun test合并_同名不同id保留两条_不再按显示名误合并() {
        val prod = LocalStub("id-1", "mpj_demo@localhost")
        val replica = LocalStub("id-2", "mpj_demo@localhost")
        val merged = mergeLocalDataSources(
            registered = listOf(prod),
            native = listOf(replica),
            resolve = resolveStub,
            uniqueIdOf = idOf,
            nameOf = nameOf,
        )
        assertEquals("同名不同 id 是两个源，uniqueId 去重不得吞掉", listOf(prod, replica), merged)
    }

    // ---------- 用例 3：B 侧 Runner 弹窗装配 ----------

    private class NamedRunner(displayName: String) {
        private val name = displayName
        fun getDisplayName(): String = name
        fun getIcon(): Icon? = null
    }

    private class NamelessRunner {
        // getDisplayName / getName 都没有：连名字都不暴露的对象才是要收掉的死项
    }

    /** 只暴露 getName 的实现形态（数据源对象与既有测试夹具都是这种；平台 20261112 三轮红的成因）。 */
    private class NameOnlyRunner(private val name: String) {
        fun getName(): String = name
    }

    /** getName 为空的形态：仍算无名。 */
    private class BlankNameRunner {
        fun getName(): String = "  "
    }

    private val addText: String get() = MpSqlVisualizerBundle.message("execute.datasource.add")

    fun testB侧_无名runner不渲染() {
        val items = DataSourceSelector.buildRunnerItems(
            runners = listOf<Any>(NamedRunner("mpj_demo@localhost"), NamelessRunner()),
            withAddEntry = false,
        )
        assertEquals("只有取名成功的 runner 进列表", listOf("mpj_demo@localhost"), items.map { it.name })
    }

    fun testB侧_只暴露getName的runner必须保留_不得判无名丢弃() {
        // 回归（2026-11-12 CI 三轮红）：只认 getDisplayName 会把这类合法条目整条滤掉 →
        // 弹层空 → selectedIndex=0 落空 → 既不 onChosen 也不 canceled → 调用方永无回调。
        val items = DataSourceSelector.buildRunnerItems(
            runners = listOf<Any>(NameOnlyRunner("mp_demo")),
            withAddEntry = false,
        )
        assertEquals("getName 形态必须保留并按该名字渲染", listOf("mp_demo"), items.map { it.name })
    }

    fun testB侧_两段取名都为空才判无名() {
        assertNull("getName 为空白也算无名", DataSourceSelector.optionalRunnerName(BlankNameRunner()))
        assertNull("两个方法都没有才返回 null", DataSourceSelector.optionalRunnerName(NamelessRunner()))
        assertEquals("getDisplayName 优先于 getName", "display", DataSourceSelector.optionalRunnerName(DisplayAndNameRunner()))
    }

    /** 两个方法都在：getDisplayName 优先。 */
    private class DisplayAndNameRunner {
        fun getDisplayName(): String = "display"
        fun getName(): String = "name"
    }

    fun testB侧_末尾添加项在平台可用时追加且不携带runner() {
        val items = DataSourceSelector.buildRunnerItems(
            runners = listOf<Any>(NamedRunner("dsA")),
            withAddEntry = true,
        )
        assertEquals(2, items.size)
        val add = items.last()
        assertTrue("末尾必须是添加项", add.addNew)
        assertNull("添加项不携带 runner", add.runner)
        assertEquals("添加项文案走 bundle", addText, add.name)
        assertFalse("数据源项不是添加项", items.first().addNew)
    }

    fun testB侧_平台不可用时不追加添加项() {
        val items = DataSourceSelector.buildRunnerItems(
            runners = listOf<Any>(NamedRunner("dsA")),
            withAddEntry = false,
        )
        assertEquals("只保留数据源条目", 1, items.size)
        assertFalse(items.single().addNew)
    }

    fun testRunner名字可空版与回落语义() {
        assertNull("缺 getDisplayName 返回 null", DataSourceSelector.optionalRunnerName(NamelessRunner()))
        assertEquals("正常名字直通", "dsA", DataSourceSelector.optionalRunnerName(NamedRunner("dsA")))
        assertEquals(
            "getRunnerName 的 Unknown 回落保持不动（选源语义依赖非空串）",
            "Unknown",
            DataSourceSelector.getRunnerName(NamelessRunner()),
        )
    }

    // ---------- 用例 4：A 侧工具栏 getChildren ----------

    private class StubGroup : com.intellij.openapi.actionSystem.DefaultActionGroup() {
        var childrenCalls = 0
        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            childrenCalls++
            return arrayOf(object : AnAction("native") {
                override fun actionPerformed(e: AnActionEvent) {}
            })
        }
    }

    private fun execFileEvent(): AnActionEvent {
        val vf = myFixture.addFileToProject("sql-visualizer/exec/mpsql-exec.sql", "select 1").virtualFile
        return AnActionEvent.createFromDataContext("test", Presentation(), DataContext { key ->
            when (key) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> vf
                else -> null
            }
        })
    }

    fun testA侧_exec文件平台可用_空列表也单独给添加数据源出口() {
        val event = execFileEvent()
        val original = NewDataSourceEntry.availabilityForTest
        NewDataSourceEntry.availabilityForTest = { true }
        try {
            val children = ChooseDataSourceTakeoverGroup(StubGroup()).getChildren(event)
            // 测试环境无 Database jar：sources 恒空。本用例钉的是「空列表也单独给出口」（计划 §七 4）。
            assertEquals(1, children.size)
            assertEquals("末尾（唯一）条目必须是添加数据源", addText, children.single().templateText)
        } finally {
            NewDataSourceEntry.availabilityForTest = original
        }
    }

    fun testA侧_exec文件平台不可用_保持委托原生组() {
        val event = execFileEvent()
        val stub = StubGroup()
        val children = ChooseDataSourceTakeoverGroup(stub).getChildren(event)
        assertEquals("无源且平台新建入口不可用 → 原样委托原生组（D-3）", 1, children.size)
        assertEquals("native", children.single().templateText)
        assertEquals("委托只发生一次", 1, stub.childrenCalls)
    }
}
