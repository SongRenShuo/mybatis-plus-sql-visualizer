package cn.song.mybatisplus.visualizer.script

import cn.song.mybatisplus.visualizer.script.api.IEnv
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * T-RollbackSnapshotPreExec P7：脚本的项目级持久化（独立 XML，不混入 ProjectPluginSettings
 * 的「使用项目设置覆盖全局」门控语义）。
 *
 * 载荷（D-2 全部级 + D-3 显式变量表）：
 * - 数据源级前/后置脚本：`dsName → 脚本源码`（JSON map）；
 * - 脚本变量表：`name → value`（D-3 显式——设置/管理对话框可编辑，与 IEnv 双向）。
 * 全局级脚本在 [cn.song.mybatisplus.visualizer.config.PluginSettings]（APP 级）；
 * 语句级在 [SqlScriptStatementScope]（预览面板生命周期，不持久化——改档已入计划）。
 */
@State(name = "MpSqlVisualizerScriptState", storages = [Storage("mp-sql-visualizer-script.xml")])
@Service(Service.Level.PROJECT)
class SqlScriptProjectState : com.intellij.openapi.components.PersistentStateComponent<SqlScriptProjectState.State> {

    data class State(
        var varsJson: String = "{}",
        var preByDataSourceJson: String = "{}",
        var postByDataSourceJson: String = "{}",
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    private val gson = Gson()

    // ---------------- 变量表（D-3 显式） ----------------

    fun vars(): Map<String, String> = parseMap(state.varsJson)

    fun setVars(newVars: Map<String, String>) {
        state.varsJson = gson.toJson(newVars)
    }

    // ---------------- 数据源级脚本 ----------------

    fun preScriptFor(dataSourceName: String): String = parseMap(state.preByDataSourceJson)[dataSourceName] ?: ""

    fun postScriptFor(dataSourceName: String): String = parseMap(state.postByDataSourceJson)[dataSourceName] ?: ""

    fun setPreScriptFor(dataSourceName: String, source: String) {
        val map = parseMap(state.preByDataSourceJson).toMutableMap()
        if (source.isBlank()) map.remove(dataSourceName) else map[dataSourceName] = source
        state.preByDataSourceJson = gson.toJson(map)
    }

    fun setPostScriptFor(dataSourceName: String, source: String) {
        val map = parseMap(state.postByDataSourceJson).toMutableMap()
        if (source.isBlank()) map.remove(dataSourceName) else map[dataSourceName] = source
        state.postByDataSourceJson = gson.toJson(map)
    }

    fun dataSourceNamesWithScripts(): Set<String> =
        parseMap(state.preByDataSourceJson).keys + parseMap(state.postByDataSourceJson).keys

    private fun parseMap(json: String): Map<String, String> = runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        obj.entrySet().associate { it.key to (it.value?.asString ?: "") }
    }.getOrDefault(emptyMap())
}

/**
 * P7：脚本变量表的 IEnv 桥（D-3 显式语义）——脚本 setVar/getVar 直接落到项目级持久化，
 * 变量表 UI 与脚本看到同一份数据。写操作经 listener 全量回存（轻量 JSON，脚本线程直写）。
 */
@Service(Service.Level.PROJECT)
class SqlScriptVarsBridge(private val project: Project) {

    private val state = project.getService(SqlScriptProjectState::class.java)
    val env: IEnv = IEnv.Impl()

    init {
        // 启动时装入持久化变量
        state.vars().forEach { (k, v) -> env.setVar(k, v) }
        env.addListener { _, _ ->
            // 脚本线程里写——回存走 runCatching（轻量 JSON，不再另起线程）
            runCatching { state.setVars(env.vars()) }
        }
    }
}

/**
 * P7：语句级（预览面板级）脚本作用域——预览面板生命周期内存态，不持久化（改档已入计划：
 * 语句级挂面板状态、生命周期与参数预设按方法隔离同族，v1 会话内生效）。
 */
object SqlScriptStatementScope {

    private val pre = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())
    private val post = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())

    fun preScriptFor(panelKey: Any): String = synchronized(pre) { pre[panelKey] ?: "" }

    fun postScriptFor(panelKey: Any): String = synchronized(post) { post[panelKey] ?: "" }

    fun setPreScript(panelKey: Any, source: String) {
        synchronized(pre) { if (source.isBlank()) pre.remove(panelKey) else pre[panelKey] = source }
    }

    fun setPostScript(panelKey: Any, source: String) {
        synchronized(post) { if (source.isBlank()) post.remove(panelKey) else post[panelKey] = source }
    }
}
