package cn.song.mybatisplus.visualizer.execute;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 默认数据源管理（M36）：持久化用户选择的默认数据源，封装数据源枚举。
 *
 * 反射隔离：所有 `com.intellij.database.*` 引用经 {@link Class#forName} 加载，
 * Community 下永不触碰。持久化经 {@link PersistentStateComponent}（项目级）存储数据源 ID。
 *
 * LocalDataSource 方法：getUniqueId()=ID（AbstractDataSource），getName()=显示名。
 */
@State(
        name = "MpSqlVisualizerDataSource",
        storages = @Storage("mp-sql-visualizer-datasource.xml")
)
@Service(Service.Level.PROJECT)
public final class DataSourceSettings implements PersistentStateComponent<DataSourceSettings.State> {

    /** DataSourceStorage 类名（反射入口）。 */
    private static final String DS_STORAGE_CLASS = "com.intellij.database.dataSource.DataSourceStorage";

    /** LocalDataSource 类名（反射用）。 */
    private static final String LOCAL_DS_CLASS = "com.intellij.database.dataSource.LocalDataSource";

    private State state = new State();

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    /** 持久化状态（默认数据源 ID + 名称）。 */
    public static final class State {
        /** 默认数据源 ID。 */
        public String defaultDataSourceId = "";
        /** 默认数据源显示名。 */
        public String defaultDataSourceName = "";

        public State() {
        }

        public State(@NotNull String defaultDataSourceId, @NotNull String defaultDataSourceName) {
            this.defaultDataSourceId = defaultDataSourceId;
            this.defaultDataSourceName = defaultDataSourceName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State state = (State) o;
            return Objects.equals(defaultDataSourceId, state.defaultDataSourceId)
                    && Objects.equals(defaultDataSourceName, state.defaultDataSourceName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(defaultDataSourceId, defaultDataSourceName);
        }

        @Override
        public String toString() {
            return "State(defaultDataSourceId=" + defaultDataSourceId
                    + ", defaultDataSourceName=" + defaultDataSourceName + ")";
        }
    }

    /** DataSourceStorage 类名（反射入口）。 */
    @NotNull
    public static DataSourceSettings getInstance(@NotNull Project project) {
        return project.getService(DataSourceSettings.class);
    }

    @Nullable
    public static String getDefaultId(@NotNull Project project) {
        String id = getInstance(project).state.defaultDataSourceId;
        return id.isBlank() ? null : id;
    }

    @Nullable
    public static String getDefaultName(@NotNull Project project) {
        String name = getInstance(project).state.defaultDataSourceName;
        return name.isBlank() ? null : name;
    }

    public static void setDefault(@NotNull Project project, @NotNull String id, @NotNull String name) {
        getInstance(project).state = new State(id, name);
    }

    public static void clearDefault(@NotNull Project project) {
        getInstance(project).state = new State();
    }

    public static boolean hasDefault(@NotNull Project project) {
        return !getInstance(project).state.defaultDataSourceId.isBlank();
    }

    /**
     * 获取当前项目所有已配置的数据源列表（反射调用 DataSourceStorage）。
     */
    @NotNull
    public static List<Object> getAllDataSources(@NotNull Project project) {
        try {
            Class<?> storageClass = cachedClass(DS_STORAGE_CLASS);
            if (storageClass == null) return List.of();
            Method getStorageMethod = cachedMethod(storageClass, "getStorage", Project.class);
            Object storage = getStorageMethod != null ? getStorageMethod.invoke(null, project) : null;
            if (storage == null) return List.of();
            Method getDataSourcesMethod = cachedMethod(storageClass, "getDataSources");
            Object dataSources = getDataSourcesMethod != null ? getDataSourcesMethod.invoke(storage) : null;
            if (!(dataSources instanceof List)) return List.of();
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) dataSources;
            return list.stream().filter(Objects::nonNull).collect(Collectors.toList());
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * 根据 ID 查找 LocalDataSource 对象（反射调用）。
     */
    @Nullable
    public static Object getDataSourceById(@NotNull Project project, @NotNull String id) {
        try {
            Class<?> storageClass = cachedClass(DS_STORAGE_CLASS);
            if (storageClass == null) return null;
            Method getStorageMethod = cachedMethod(storageClass, "getStorage", Project.class);
            Object storage = getStorageMethod != null ? getStorageMethod.invoke(null, project) : null;
            if (storage == null) return null;
            Method getByIdMethod = cachedMethod(storageClass, "getDataSourceById", String.class);
            return getByIdMethod != null ? getByIdMethod.invoke(storage, id) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 获取默认数据源的 LocalDataSource 对象（反射）。
     */
    @Nullable
    public static Object getDefaultDataSource(@NotNull Project project) {
        String id = getDefaultId(project);
        if (id == null) return null;
        return getDataSourceById(project, id);
    }

    /**
     * 反射获取 LocalDataSource 的显示名称（getName()）。
     */
    @NotNull
    public static String getName(@NotNull Object ds) {
        try {
            Method getNameMethod = cachedMethod(cachedClass(LOCAL_DS_CLASS), "getName");
            if (getNameMethod == null) return "Unknown";
            Object result = getNameMethod.invoke(ds);
            return result instanceof String ? (String) result : "Unknown";
        } catch (Throwable ignored) {
            return "Unknown";
        }
    }

    /**
     * 反射获取 LocalDataSource 的唯一 ID（getUniqueId()）。
     */
    @Nullable
    public static String getUniqueId(@NotNull Object ds) {
        try {
            Method getUniqueIdMethod = cachedMethod(cachedClass(LOCAL_DS_CLASS), "getUniqueId");
            if (getUniqueIdMethod == null) return null;
            Object result = getUniqueIdMethod.invoke(ds);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 反射获取 LocalDataSource 的图标。
     */
    @Nullable
    public static Icon getIcon(@NotNull Object ds) {
        try {
            Method getIconMethod = cachedMethod(cachedClass(LOCAL_DS_CLASS), "getIcon");
            if (getIconMethod == null) return null;
            Object result = getIconMethod.invoke(ds);
            return result instanceof Icon ? (Icon) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------- T-DsListAddSource（20261111）：名字解析可空化与 DbDataSource 投影 ----------
    //
    // 「unknown 数据源」死项的根因（计划 §2.2，243 database-plugin.jar javap 实证）：
    // `DbImplUtil.getAllDataSourcesForExec(PsiFile)` 返回 `JBIterable<DbDataSource>`
    // （PSI 层包装），而既有取名链用 LocalDataSource 上的 Method 去反射 invoke →
    // IllegalArgumentException → runCatching 吞掉 → 回落字面量 "Unknown"。
    // 列表装配处改用下面两个入口：解析不出就丢弃条目，不再漏出 unknown。
    //
    // 既有 getName 的 "Unknown" 回落**保持不动**：last-used 记忆链、`chosenRunnerName`
    // 比对、方言闸门（ExecutionDialectGate）都依赖它返回非空串，语义面零变化。

    /** DbDataSource 类名（PSI 层数据源包装；反射用，Community 缺席时返回 null）。master 侧常量名 {@code DB_DS_DELEGATE_METHOD}（存 delegate 取值方法名）。 */
    private static final String DB_DS_DELEGATE_METHOD = "getDelegate";

    /**
     * 把任意平台数据源对象投影成 {@code LocalDataSource}。
     *
     * <p>{@code DbDataSource.getDelegate()}（桥方法返回 {@code RawDataSource}）解出内层原始源，
     * 只有真 {@code LocalDataSource} 才认；本身已是 LocalDataSource 时原样返回。
     * 平台不可用 / delegate 缺失 / 内层不是 LocalDataSource → null。</p>
     */
    @Nullable
    public static Object resolveLocalDataSource(@NotNull Object ds) {
        Class<?> localDsClass = cachedClass(LOCAL_DS_CLASS);
        if (localDsClass != null && localDsClass.isInstance(ds)) return ds;
        try {
            Object delegate = ds.getClass().getMethod(DB_DS_DELEGATE_METHOD).invoke(ds);
            if (delegate != null && localDsClass != null && localDsClass.isInstance(delegate)) return delegate;
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 解析显示名，解析不出（对象类型不匹配 / 名字为空）返回 null。
     *
     * <p>专供<b>列表装配</b>处使用；与 {@link #getName} 的差别只在失败路径——列表宁缺毋滥，
     * 名字取不出就隐藏条目，而不是渲染一条点不了的 "Unknown"。</p>
     */
    @Nullable
    public static String nameOrNull(@NotNull Object ds) {
        Object local = resolveLocalDataSource(ds);
        if (local == null) return null;
        try {
            Class<?> cls = cachedClass(LOCAL_DS_CLASS);
            if (cls == null) return null;
            Method getNameMethod = cachedMethod(cls, "getName");
            if (getNameMethod == null) return null;
            Object result = getNameMethod.invoke(local);
            String name = result instanceof String ? (String) result : null;
            return name != null && !name.isBlank() ? name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------- F68a（T-PerfSweep20）：反射 Class/Method 记忆化 ----------
    //
    // 比照 SqlLogConsoleBridge.methodCache 范式：预览绑定指示行取名链每次刷新现场
    // forName+getMethod 建表，Community/无 Database 插件下 forName 恒抛更要逐次付——
    // 收敛为首次付；负缓存用 Optional（CHM 禁 null），键含类名防跨类撞键。

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Class<?>>> classCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Method>> methodCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Nullable
    private static Class<?> cachedClass(@NotNull String fqcn) {
        return classCache.computeIfAbsent(fqcn, name -> {
            try {
                Class<?> cls = Class.forName(name);
                return cls != null ? java.util.Optional.of(cls) : java.util.Optional.empty();
            } catch (Throwable t) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }

    @Nullable
    private static Method cachedMethod(@Nullable Class<?> clazz, @NotNull String name,
                                       Class<?>... paramTypes) {
        if (clazz == null) {
            return null;
        }
        StringBuilder key = new StringBuilder(clazz.getName()).append('#').append(name).append('(');
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) key.append(',');
            key.append(paramTypes[i].getName());
        }
        key.append(')');
        return methodCache.computeIfAbsent(key.toString(), k -> {
            try {
                return java.util.Optional.of(clazz.getMethod(name, paramTypes));
            } catch (Throwable t) {
                return java.util.Optional.empty();
            }
        }).orElse(null);
    }
}
