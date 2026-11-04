package cn.song.mybatisplus.visualizer.execute

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * 默认数据源管理（M36）：持久化用户选择的默认数据源，封装数据源枚举。
 *
 * 反射隔离：所有 `com.intellij.database.*` 引用经 [Class.forName] 加载，
 * Community 下永不触碰。持久化经 [PersistentStateComponent]（项目级）存储数据源 ID。
 *
 * LocalDataSource 方法：getUniqueId()=ID（AbstractDataSource），getName()=显示名。
 */
@State(
    name = "MpSqlVisualizerDataSource",
    storages = [Storage("mp-sql-visualizer-datasource.xml")],
)
@Service(Service.Level.PROJECT)
class DataSourceSettings : PersistentStateComponent<DataSourceSettings.State> {

    data class State(
        var defaultDataSourceId: String = "",
        var defaultDataSourceName: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        /** DataSourceStorage 类名（反射入口）。 */
        private const val DS_STORAGE_CLASS = "com.intellij.database.dataSource.DataSourceStorage"

        /** LocalDataSource 类名（反射用）。 */
        private const val LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource"

        fun getInstance(project: Project): DataSourceSettings =
            project.getService(DataSourceSettings::class.java)

        fun getDefaultId(project: Project): String? {
            val id = getInstance(project).state.defaultDataSourceId
            return if (id.isBlank()) null else id
        }

        fun getDefaultName(project: Project): String? {
            val name = getInstance(project).state.defaultDataSourceName
            return if (name.isBlank()) null else name
        }

        fun setDefault(project: Project, id: String, name: String) {
            getInstance(project).state = State(id, name)
        }

        fun clearDefault(project: Project) {
            getInstance(project).state = State()
        }

        fun hasDefault(project: Project): Boolean =
            getInstance(project).state.defaultDataSourceId.isNotBlank()

        /**
         * 获取当前项目所有已配置的数据源列表（反射调用 DataSourceStorage）。
         */
        fun getAllDataSources(project: Project): List<Any> {
            return runCatching {
                val storageClass = cachedClass(DS_STORAGE_CLASS) ?: return emptyList()
                val storage = cachedMethod(storageClass, "getStorage", Project::class.java)
                    ?.invoke(null, project) ?: return emptyList()
                val dataSources = cachedMethod(storageClass, "getDataSources")
                    ?.invoke(storage) as? List<*> ?: return emptyList()
                dataSources.filterNotNull()
            }.getOrDefault(emptyList())
        }

        /**
         * 根据 ID 查找 LocalDataSource 对象（反射调用）。
         */
        fun getDataSourceById(project: Project, id: String): Any? {
            return runCatching {
                val storageClass = cachedClass(DS_STORAGE_CLASS) ?: return null
                val storage = cachedMethod(storageClass, "getStorage", Project::class.java)
                    ?.invoke(null, project) ?: return null
                cachedMethod(storageClass, "getDataSourceById", String::class.java)?.invoke(storage, id)
            }.getOrNull()
        }

        /**
         * 获取默认数据源的 LocalDataSource 对象（反射）。
         */
        fun getDefaultDataSource(project: Project): Any? {
            val id = getDefaultId(project) ?: return null
            return getDataSourceById(project, id)
        }

        /**
         * 反射获取 LocalDataSource 的显示名称（getName()）。
         */
        fun getName(ds: Any): String {
            return runCatching {
                cachedClass(LOCAL_DS_CLASS)?.let { cls ->
                    cachedMethod(cls, "getName")?.invoke(ds) as? String
                } ?: "Unknown"
            }.getOrDefault("Unknown")
        }

        /**
         * 反射获取 LocalDataSource 的唯一 ID（getUniqueId()）。
         */
        fun getUniqueId(ds: Any): String? {
            return runCatching {
                cachedClass(LOCAL_DS_CLASS)?.let { cls ->
                    cachedMethod(cls, "getUniqueId")?.invoke(ds) as? String
                }
            }.getOrNull()
        }

        /**
         * 反射获取 LocalDataSource 的图标。
         */
        fun getIcon(ds: Any): javax.swing.Icon? {
            return runCatching {
                cachedClass(LOCAL_DS_CLASS)?.let { cls ->
                    cachedMethod(cls, "getIcon")?.invoke(ds) as? javax.swing.Icon
                }
            }.getOrNull()
        }

        // ---------- F68a（T-PerfSweep20）：反射 Class/Method 记忆化 ----------
        //
        // 比照 SqlLogConsoleBridge.methodCache 范式（:695）：预览绑定指示行取名链每次刷新
        // 现场 forName+getMethod 建表，Community/无 Database 插件下 forName 恒抛更要逐次付——
        // 收敛为首次付；负缓存用 Optional（CHM 禁 null），键含类名防跨类撞键。

        private val classCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Class<*>>>()

        private val methodCache =
            java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>>()

        /**
         * T-PerfSweep30 05a：显式上限（同族 [cn.song.mybatisplus.visualizer.log.SqlLogFileReader] 的
         * countCache 形态——超限整体清空）。
         *
         * 键域本来就小（平台 Database runner 类名 + 方法签名，几十个），但「键域小」是**观察**不是
         * **保证**：反射探测的 fqcn 来自配置与扫描结果，理论上可被插件/用户配置撑大。缓存只是加速，
         * 清空后下一次重付 forName/getMethod（毫秒级），故整体清空比 LRU 更简单且无淘汰顺序开销。
         */
        private const val REFLECT_CACHE_MAX = 256

        private fun cachedClass(fqcn: String): Class<*>? {
            if (classCache.size >= REFLECT_CACHE_MAX) classCache.clear()
            return classCache.getOrPut(fqcn) {
                java.util.Optional.ofNullable(runCatching { Class.forName(fqcn) }.getOrNull())
            }.orElse(null)
        }

        private fun cachedMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method? {
            val key = "${clazz.name}#$name(${paramTypes.joinToString(",") { it.name }})"
            if (methodCache.size >= REFLECT_CACHE_MAX) methodCache.clear()
            return methodCache.getOrPut(key) {
                java.util.Optional.ofNullable(runCatching { clazz.getMethod(name, *paramTypes) }.getOrNull())
            }.orElse(null)
        }
    }
}
