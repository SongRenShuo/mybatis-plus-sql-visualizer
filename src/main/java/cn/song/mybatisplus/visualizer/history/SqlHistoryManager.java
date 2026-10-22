package cn.song.mybatisplus.visualizer.history;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 历史记录管理器（F-7）：增删改查 + 上限淘汰 + 收藏 + 标签 + 搜索，跨会话持久化。
 *
 * <p>application-level 轻服务（@Service(Service.Level.APP)，跨项目共享），经 XmlSerializer
 * 持久化到 `mp-sql-visualizer.xml`（与 [PluginSettings] 同文件）。
 */
@Service(Service.Level.APP)
@State(
        name = "MpSqlVisualizerHistory",
        storages = {@Storage(value = "mp-sql-visualizer.xml", roamingType = RoamingType.DISABLED)}
)
public final class SqlHistoryManager implements PersistentStateComponent<SqlHistoryManager.State> {

    /** 可持久化状态（XmlSerializer 序列化）。 */
    public static final class State {
        /** 普通条目（按时间倒序，index 0 = 最新）。 */
        private List<SqlHistoryEntry> entries = new ArrayList<>();
        /** 收藏条目（独立存放，不参与上限淘汰）。 */
        private List<SqlHistoryEntry> favoriteEntries = new ArrayList<>();

        public State() {
        }

        public State(List<SqlHistoryEntry> entries, List<SqlHistoryEntry> favoriteEntries) {
            this.entries = entries != null ? entries : new ArrayList<>();
            this.favoriteEntries = favoriteEntries != null ? favoriteEntries : new ArrayList<>();
        }

        public List<SqlHistoryEntry> getEntries() {
            return entries;
        }

        public void setEntries(List<SqlHistoryEntry> entries) {
            this.entries = entries != null ? entries : new ArrayList<>();
        }

        public List<SqlHistoryEntry> getFavoriteEntries() {
            return favoriteEntries;
        }

        public void setFavoriteEntries(List<SqlHistoryEntry> favoriteEntries) {
            this.favoriteEntries = favoriteEntries != null ? favoriteEntries : new ArrayList<>();
        }
    }

    /** 默认历史记录上限（非收藏条目）。 */
    public static final int DEFAULT_MAX_SIZE = 200;

    /** T-Perf-19：收藏条目上限（宽松封顶防无限增长）。 */
    public static final int MAX_FAVORITES = 500;

    private State state = new State();

    /** 历史记录上限（非收藏条目）。默认 200，跟随配置。 */
    private int maxSize = DEFAULT_MAX_SIZE;

    /** 获取历史记录上限（非收藏条目）。 */
    public int getMaxSize() {
        return maxSize;
    }

    /** 设置历史记录上限（至少为 1）。 */
    public void setMaxSize(int value) {
        maxSize = Math.max(value, 1);
    }

    /**
     * 添加一条历史记录（均摊 O(1)）。
     *
     * @return 添加后的条目（含分配的 id）
     */
    public SqlHistoryEntry add(@NotNull SqlHistoryEntry entry) {
        entry.ensureId();
        if (entry.isFavorite()) {
            insertSorted(state.getFavoriteEntries(), entry);
            evictFavoritesIfNeeded();
        } else {
            insertSorted(state.getEntries(), entry);
            evictIfNeeded();
        }
        return entry;
    }

    /**
     * T-Perf-19：收藏条目上限（MAX_FAVORITES）——此前 favoriteEntries 永不淘汰，
     * 长期使用无限增长（持久化文件与内存双膨胀）。超出时淘汰最旧收藏（队尾）。
     */
    private void evictFavoritesIfNeeded() {
        while (state.getFavoriteEntries().size() > MAX_FAVORITES) {
            state.getFavoriteEntries().remove(state.getFavoriteEntries().size() - 1);
        }
    }

    /**
     * 按时间戳倒序插入（index 0 = 最新）。
     */
    private void insertSorted(List<SqlHistoryEntry> list, SqlHistoryEntry entry) {
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getTimestamp() <= entry.getTimestamp()) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            list.add(entry);
        } else {
            list.add(idx, entry);
        }
    }

    /**
     * 超出上限时淘汰最旧的非收藏条目（队尾）。R15：钉住条目跳过（防翻找丢失）——
     * 从队尾向前找首个非钉住条目移除；全为钉住则不淘汰（钉住总量由 MAX_PINNED 把关）。
     */
    private void evictIfNeeded() {
        while (state.getEntries().size() > maxSize) {
            int idx = -1;
            for (int i = state.getEntries().size() - 1; i >= 0; i--) {
                if (!state.getEntries().get(i).isPinned()) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) return; // 全为钉住：不再淘汰（钉住上限由 togglePin 把关）
            state.getEntries().remove(idx);
        }
    }

    /** R15：钉住上限（防钉住分区无限膨胀挤占淘汰机制）。 */
    private static final int MAX_PINNED = 50;

    /** R15：切换钉住态；钉住数达 {@link #MAX_PINNED} 上限时拒绝并返回 false。 */
    public boolean togglePin(String id) {
        SqlHistoryEntry entry = findById(id);
        if (entry == null) return false;
        if (!entry.isPinned()) {
            int pinnedCount = 0;
            for (SqlHistoryEntry e : state.getEntries()) {
                if (e.isPinned()) pinnedCount++;
            }
            for (SqlHistoryEntry e : state.getFavoriteEntries()) {
                if (e.isPinned()) pinnedCount++;
            }
            if (pinnedCount >= MAX_PINNED) return false;
        }
        entry.setPinned(!entry.isPinned());
        return true;
    }

    /** 全部条目（含收藏与非收藏，按时间倒序合并）。 */
    public List<SqlHistoryEntry> getEntries() {
        return mergeByTimestamp(state.getFavoriteEntries(), state.getEntries());
    }

    /**
     * R51：参数快照回写——面板参数编辑防抖落库时同步进最新匹配历史条目（会话内闭环）。
     * 空快照不写；无匹配条目返回 false。
     */
    public synchronized boolean updateNewestParamSnapshot(
            java.util.function.Predicate<SqlHistoryEntry> match, java.util.Map<String, String> snapshot) {
        if (snapshot.isEmpty()) {
            return false;
        }
        for (SqlHistoryEntry entry : state.getEntries()) {
            if (match.test(entry)) {
                entry.getParamSnapshot().clear();
                entry.getParamSnapshot().putAll(snapshot);
                return true;
            }
        }
        return false;
    }

    /**
     * R51：按匹配谓词取最新一条条目的参数快照（recall 链在 ParamHistoryCache 未命中时的
     * 回落源；快照为空的条目不参与——无快照的历史回落无意义）。
     */
    public synchronized java.util.Map<String, String> newestParamSnapshot(
            java.util.function.Predicate<SqlHistoryEntry> match) {
        for (SqlHistoryEntry entry : state.getEntries()) {
            if (match.test(entry) && !entry.getParamSnapshot().isEmpty()) {
                return entry.getParamSnapshot();
            }
        }
        return null;
    }

    // ---------- R90：EXPLAIN 计划持久化 ----------

    private long planGeneration = 0L;

    public synchronized long explainPlanGeneration() {
        return planGeneration;
    }

    /** R90 计划预算：单条目最大计划数。 */
    private static final int MAX_PLANS_PER_ENTRY = 10;
    /** R90 计划预算：全域计划序列化字节总量上限。 */
    private static final int MAX_TOTAL_PLAN_BYTES = 512 * 1024;
    /** R90：单计划序列化字节上限。 */
    private static final int MAX_PLAN_BYTES = 64 * 1024;

    /** 提交前固定原条目；异步完成时禁止再按「最新 SQL」回查归属。 */
    public synchronized ExplainPlanCapture beginExplainCapture(
            String projectIdentity, String previewSql, ExplainPlanKey key, String entryId) {
        if (!key.projectIdentity().equals(projectIdentity)) return null;
        SqlHistoryEntry entry;
        if (entryId != null) {
            entry = findById(entryId);
            if (entry == null || !entry.getProjectIdentity().equals(projectIdentity)) return null;
        } else {
            entry = null;
            for (SqlHistoryEntry e : state.getEntries()) {
                if (e.getProjectIdentity().equals(projectIdentity) && e.getSql().equals(previewSql)) {
                    entry = e;
                    break;
                }
            }
            if (entry == null) return null;
        }
        return new ExplainPlanCapture(entry.getId(), planGeneration, key);
    }

    /** R91 可只读消费此 API：严格同项目/UUID/方言/格式/模板/exact，返回快照列表（最新在前）。 */
    public synchronized java.util.List<ExplainPlanSnapshot> matchingExplainPlans(ExplainPlanKey key) {
        if (!key.isPersistable()) return java.util.List.of();
        java.util.List<ExplainPlanSnapshot> out = new java.util.ArrayList<>();
        java.util.List<SqlHistoryEntry> all = new java.util.ArrayList<>(state.getEntries());
        all.addAll(state.getFavoriteEntries());
        for (SqlHistoryEntry entry : all) {
            for (ExplainPlanSnapshot snapshot : entry.getExplainPlans()) {
                if (snapshot.key().equals(key)) {
                    out.add(snapshot);
                }
            }
        }
        out.sort((a, b) -> Long.compare(b.getCapturedAt(), a.getCapturedAt()));
        return out;
    }

    public synchronized ExplainPlanSaveResult saveExplainPlan(ExplainPlanCapture capture,
                                                              cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter.RawResult raw) {
        if (capture.generation() != planGeneration) return ExplainPlanSaveResult.CLEARED_OR_REMOVED;
        SqlHistoryEntry entry = findById(capture.entryId());
        if (entry == null) return ExplainPlanSaveResult.CLEARED_OR_REMOVED;
        if (!entry.getProjectIdentity().equals(capture.key().projectIdentity())) {
            return ExplainPlanSaveResult.CLEARED_OR_REMOVED;
        }
        if (!capture.key().isPersistable()) return ExplainPlanSaveResult.SESSION_ONLY;
        ExplainPlanSnapshot snapshot = ExplainPlanSnapshot.create(capture.key(), raw);
        long newestTime = 0L;
        java.util.List<SqlHistoryEntry> all = new java.util.ArrayList<>(state.getEntries());
        all.addAll(state.getFavoriteEntries());
        for (SqlHistoryEntry e : all) {
            for (ExplainPlanSnapshot p : e.getExplainPlans()) {
                newestTime = Math.max(newestTime, p.getCapturedAt());
            }
        }
        // 同一毫秒完成的多个回调仍按保存顺序淘汰，不因稳定排序把最新一份丢掉。
        if (newestTime >= snapshot.getCapturedAt() && newestTime < Long.MAX_VALUE) {
            snapshot.setCapturedAt(newestTime + 1);
        }
        if (snapshot.serializedBytes() > MAX_PLAN_BYTES) return ExplainPlanSaveResult.TOO_LARGE;
        entry.getExplainPlans().add(0, snapshot);
        enforcePlanBounds(all);
        return ExplainPlanSaveResult.SAVED;
    }

    /** 计划预算只淘汰计划，绝不删除 SQL（特别是收藏）。加载旧/手改 XML 同样受限。 */
    private void enforcePlanBounds(java.util.List<SqlHistoryEntry> all) {
        for (SqlHistoryEntry entry : all) {
            entry.getExplainPlans().removeIf(p ->
                    !p.key().isPersistable() || !p.getProjectIdentity().equals(entry.getProjectIdentity())
                            || p.raw() == null || p.serializedBytes() > MAX_PLAN_BYTES);
            entry.getExplainPlans().sort((a, b) -> Long.compare(b.getCapturedAt(), a.getCapturedAt()));
            while (entry.getExplainPlans().size() > MAX_PLANS_PER_ENTRY) {
                entry.getExplainPlans().remove(entry.getExplainPlans().size() - 1);
            }
        }
        record PlanRef(SqlHistoryEntry entry, ExplainPlanSnapshot snapshot, int size) {}
        java.util.List<PlanRef> oldest = new java.util.ArrayList<>();
        long totalBytes = 0;
        for (SqlHistoryEntry entry : all) {
            for (ExplainPlanSnapshot p : entry.getExplainPlans()) {
                int sz = p.serializedBytes();
                oldest.add(new PlanRef(entry, p, sz));
                totalBytes += sz;
            }
        }
        oldest.sort((a, b) -> Long.compare(a.snapshot().getCapturedAt(), b.snapshot().getCapturedAt()));
        for (PlanRef ref : oldest) {
            if (totalBytes <= MAX_TOTAL_PLAN_BYTES) break;
            ref.entry().getExplainPlans().remove(ref.snapshot());
            totalBytes -= ref.size();
        }
    }

    /** 按 id 查找条目（含收藏分区）。 */
    public SqlHistoryEntry findById(String id) {
        for (SqlHistoryEntry e : state.getEntries()) {
            if (e.getId().equals(id)) {
                return e;
            }
        }
        for (SqlHistoryEntry e : state.getFavoriteEntries()) {
            if (e.getId().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** 收藏条目（时间倒序）。 */
    public List<SqlHistoryEntry> filterFavorites() {
        return new ArrayList<>(state.getFavoriteEntries());
    }

    /**
     * 切换收藏状态。
     */
    public void toggleFavorite(String id) {
        int inNormal = indexOfById(state.getEntries(), id);
        if (inNormal >= 0) {
            SqlHistoryEntry e = state.getEntries().remove(inNormal);
            e.setFavorite(true);
            insertSorted(state.getFavoriteEntries(), e);
            evictFavoritesIfNeeded();
            return;
        }
        int inFav = indexOfById(state.getFavoriteEntries(), id);
        if (inFav >= 0) {
            SqlHistoryEntry e = state.getFavoriteEntries().remove(inFav);
            e.setFavorite(false);
            insertSorted(state.getEntries(), e);
            evictIfNeeded();
        }
    }

    /** 为条目添加标签（去重）。 */
    public void addTag(String id, String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        SqlHistoryEntry e = findById(id);
        if (e != null && e.getTags().stream().noneMatch(t -> t.equals(tag))) {
            e.getTags().add(tag);
        }
    }

    /** 移除条目的指定标签。 */
    public void removeTag(String id, String tag) {
        SqlHistoryEntry e = findById(id);
        if (e != null) {
            e.getTags().remove(tag);
        }
    }

    /** 全部标签集合（所有条目的标签并集）。 */
    public Set<String> allTags() {
        Set<String> result = new LinkedHashSet<>();
        for (SqlHistoryEntry e : state.getEntries()) {
            result.addAll(e.getTags());
        }
        for (SqlHistoryEntry e : state.getFavoriteEntries()) {
            result.addAll(e.getTags());
        }
        return result;
    }

    /** 按标签筛选条目（精确匹配，时间倒序）。 */
    public List<SqlHistoryEntry> filterByTag(String tag) {
        List<SqlHistoryEntry> result = new ArrayList<>();
        for (SqlHistoryEntry e : getEntries()) {
            if (e.getTags().contains(tag)) {
                result.add(e);
            }
        }
        return result;
    }

    /**
     * 子串搜索（SQL 文本，忽略大小写）。空查询返回全量。
     */
    public List<SqlHistoryEntry> search(String query) {
        if (query == null || query.isBlank()) {
            return getEntries();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<SqlHistoryEntry> result = new ArrayList<>();
        for (SqlHistoryEntry e : getEntries()) {
            if (e.getSql().toLowerCase(Locale.ROOT).contains(q)
                    || e.getCompactSql().toLowerCase(Locale.ROOT).contains(q)) {
                result.add(e);
            }
        }
        return result;
    }

    /** 按 id 删除条目。 */
    public void remove(String id) {
        state.getEntries().removeIf(e -> e.getId().equals(id));
        state.getFavoriteEntries().removeIf(e -> e.getId().equals(id));
    }

    /** 清空全部历史（含收藏）。 */
    public void clear() {
        state.getEntries().clear();
        state.getFavoriteEntries().clear();
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = new State(
                state.getEntries() != null ? state.getEntries() : new ArrayList<>(),
                state.getFavoriteEntries() != null ? state.getFavoriteEntries() : new ArrayList<>()
        );
    }

    /** 两个分区按时间戳倒序合并。 */
    private List<SqlHistoryEntry> mergeByTimestamp(List<SqlHistoryEntry> a, List<SqlHistoryEntry> b) {
        List<SqlHistoryEntry> result = new ArrayList<>(a.size() + b.size());
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).getTimestamp() >= b.get(j).getTimestamp()) {
                result.add(a.get(i++));
            } else {
                result.add(b.get(j++));
            }
        }
        while (i < a.size()) {
            result.add(a.get(i++));
        }
        while (j < b.size()) {
            result.add(b.get(j++));
        }
        return result;
    }

    private static int indexOfById(List<SqlHistoryEntry> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 全局实例获取。 */
    public static SqlHistoryManager getInstance() {
        return ApplicationManager.getApplication().getService(SqlHistoryManager.class);
    }
}
