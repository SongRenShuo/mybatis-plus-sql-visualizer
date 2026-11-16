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

        // ---------- T-DsListAddSource（20261111）：名字解析可空化与 DbDataSource 投影 ----------
        //
        // 「unknown 数据源」死项的根因（计划 §2.2，243 database-plugin.jar javap 实证）：
        // `DbImplUtil.getAllDataSourcesForExec(PsiFile)` 返回 `JBIterable<DbDataSource>`
        // （PSI 层包装），而既有取名链用 LocalDataSource 上的 Method 去反射 invoke →
        // IllegalArgumentException → runCatching 吞掉 → 回落字面量 "Unknown"。
        // 列表装配处改用下面两个入口：解析不出就丢弃条目，不再漏出 unknown。
        //
        // 既有 [getName] 的 "Unknown" 回落**保持不动**：last-used 记忆链、`chosenRunnerName`
        // 比对、方言闸门（ExecutionDialectGate）都依赖它返回非空串，语义面零变化。

        /** DbDataSource 类名（PSI 层数据源包装；反射用，Community 缺席时返回 null）。 */
        private const val DB_DS_DELEGATE_METHOD = "getDelegate"

        /**
         * 把任意平台数据源对象投影成 `LocalDataSource`。
         *
         * `DbDataSource.getDelegate()`（桥方法返回 `RawDataSource`）解出内层原始源，
         * 只有真 `LocalDataSource` 才认；本身已是 LocalDataSource 时原样返回。
         * 平台不可用 / delegate 缺失 / 内层不是 LocalDataSource → null。
         */
        fun resolveLocalDataSource(ds: Any): Any? {
            if (cachedClass(LOCAL_DS_CLASS)?.isInstance(ds) == true) return ds
            return runCatching {
                val delegate = ds.javaClass.getMethod(DB_DS_DELEGATE_METHOD).invoke(ds)
                if (delegate != null && cachedClass(LOCAL_DS_CLASS)?.isInstance(delegate) == true) delegate else null
            }.getOrNull()
        }

        /**
         * 解析显示名，解析不出（对象类型不匹配 / 名字为空）返回 null。
         *
         * 专供**列表装配**处使用；与 [getName] 的差别只在失败路径——列表宁缺毋滥，
         * 名字取不出就隐藏条目，而不是渲染一条点不了的 "Unknown"。
         */
        fun nameOrNull(ds: Any): String? {
            val local = resolveLocalDataSource(ds) ?: return null
            return runCatching {
                cachedClass(LOCAL_DS_CLASS)?.let { cls ->
                    cachedMethod(cls, "getName")?.invoke(local) as? String
                }
            }.getOrNull()?.takeUnless { it.isBlank() }
        }

        // ---------- F68a（T-PerfSweep20）：反射 Class/Method 记忆化 ----------
        //
        // 比照 SqlLogConsoleBridge.methodCache 范式（:695）：预览绑定指示行取名链每次刷新
        // 现场 forName+getMethod 建表，Community/无 Database 插件下 forName 恒抛更要逐次付——
        // 收敛为首次付；负缓存用 Optional（CHM 禁 null），键含类名防跨类撞键。

        private val classCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Class<*>>>()

        private val methodCache =
            java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>>()

        private fun cachedClass(fqcn: String): Class<*>? =
            classCache.getOrPut(fqcn) {
                java.util.Optional.ofNullable(runCatching { Class.forName(fqcn) }.getOrNull())
            }.orElse(null)

        private fun cachedMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): java.lang.reflect.Method? {
            val key = "${clazz.name}#$name(${paramTypes.joinToString(",") { it.name }})"
            return methodCache.getOrPut(key) {
                java.util.Optional.ofNullable(runCatching { clazz.getMethod(name, *paramTypes) }.getOrNull())
            }.orElse(null)
        }
    }
}
