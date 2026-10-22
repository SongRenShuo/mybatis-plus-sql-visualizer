package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** 用户实际选择并通过执行闸后被动学习的「模块稳定键 → 数据源 UUID」。不保存 URL。 */
@State(name = "MpSqlVisualizerModuleDataSourceBindings", storages = @Storage("mp-sql-visualizer-datasource.xml"))
@Service(Service.Level.PROJECT)
public final class ModuleDataSourceBindingStore implements PersistentStateComponent<ModuleDataSourceBindingStore.State> {

    public static class State {
        public Map<String, String> bindings = new LinkedHashMap<>();
    }

    private State state = new State();

    @Override
    @NotNull
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public void remember(@NotNull String moduleKey, @NotNull String dataSourceId) {
        if (moduleKey.isBlank() || dataSourceId.isBlank()) {
            return;
        }
        state.bindings.put(moduleKey, dataSourceId);
    }

    public void forget(@NotNull String moduleKey) {
        state.bindings.remove(moduleKey);
    }

    public void clear() {
        state.bindings.clear();
    }

    public @Nullable String dataSourceId(@NotNull String moduleKey) {
        String id = state.bindings.get(moduleKey);
        return id != null && !id.isBlank() ? id : null;
    }

    public @Nullable DialectDecisionEngine.Candidate learnedCandidate(@NotNull Project project,
                                                                      @NotNull String moduleKey) {
        String id = dataSourceId(moduleKey);
        if (id == null) {
            return null;
        }
        Object ds;
        DataSourceByIdLookup lookup = dataSourceByIdForTest;
        if (lookup != null) {
            ds = lookup.lookup(project, id);
        } else {
            ds = cn.song.mybatisplus.visualizer.execute.DataSourceSettings.getDataSourceById(project, id);
        }
        if (ds == null) {
            forget(moduleKey);
            return null;
        }
        return new DialectDecisionEngine.Candidate(id,
                cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate.fromDataSource(ds).dialectName());
    }

    public interface DataSourceByIdLookup {
        @Nullable Object lookup(Project project, String id);
    }

    /** Headless 夹具只替换 UUID→对象查找；生产恒走 DatabaseTools 存储。 */
    public static volatile DataSourceByIdLookup dataSourceByIdForTest = null;

    public static ModuleDataSourceBindingStore getInstance(@NotNull Project project) {
        return project.getService(ModuleDataSourceBindingStore.class);
    }
}
