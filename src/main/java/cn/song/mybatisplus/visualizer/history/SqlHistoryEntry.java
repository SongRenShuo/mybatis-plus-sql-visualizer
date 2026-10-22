package cn.song.mybatisplus.visualizer.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SQL 历史记录条目（F-7）：一次预览的不可变快照 + 用户标注（收藏/标签）。
 *
 * <p>持久化友好：全部字段为基本类型 / String / List，XmlSerializer 可直接序列化。
 * [tags] 用 mutableList 而非 Set（XmlSerializer 对 Set 支持不稳定），[SqlHistoryManager] 维护唯一性。
 */
public final class SqlHistoryEntry {

    /** 摘要最大字符数（列表渲染用）。 */
    public static final int MAX_SUMMARY_LEN = 80;

    /** 唯一标识（空白时由 [SqlHistoryManager.add] 自动分配 UUID）。 */
    private String id = "";
    /** 完整 SQL（多行格式化）。 */
    private String sql = "";
    /** 压缩 SQL（摘要展示用）。 */
    private String compactSql = "";
    /** 预览时间戳（毫秒）。 */
    private long timestamp = 0L;
    /** 来源文件名（如 UserMapper.java）。 */
    private String sourceFile = "";
    /** 来源文件行号（0 表示未知）。 */
    private int sourceLine = 0;
    /** 目标表名（可为空）。 */
    private String tableName = "";
    /** 是否收藏（收藏条目不参与上限淘汰）。 */
    private boolean favorite = false;
    /** 用户自定义标签列表（唯一、有序）。 */
    private List<String> tags = new ArrayList<>();
    /** 执行耗时（毫秒）；null 表示无耗时信息。 */
    private Long executionTimeMs = null;
    /** R15（W3 历史工作流包）：钉住——非收藏分区裁剪跳过钉住条目（防翻找丢失）；上限由 manager 把关。 */
    private boolean pinned = false;

    /**
     * R51（O-105 余半·主体）：参数快照（键值对）。生命周期：recordHistory 早于面板参数区构建
     * （预览产物先落历史、参数后填），故初始恒空；面板参数编辑防抖落库时按缓存键回写
     * <b>最新匹配条目</b>。recall 链在 ParamHistoryCache 未命中时按同一缓存键回落本快照。
     * 持久化（V75 实测在案）：XmlSerializer 对 LinkedHashMap 往返逐值兼容。
     */
    private java.util.LinkedHashMap<String, String> paramSnapshot = new java.util.LinkedHashMap<>();

    /** R90：APP 历史域的项目归属；旧条目缺省空，不猜测归属。 */
    private String projectIdentity = "";

    /** R90：本条目的 EXPLAIN 计划快照（最新在前；预算上限见 SqlHistoryManager.enforcePlanBounds）。 */
    private List<ExplainPlanSnapshot> explainPlans = new ArrayList<>();

    /**
     * T-PresetMethodScope：预览语句的声明作用域（方法身份短哈希，见
     * {@code cn.song.mybatisplus.visualizer.psi.PreviewOriginScope}）。参数快照回落（R51）按同一口径
     * 匹配历史条目；旧条目缺省空 → 只参与旧键匹配，不参与带 scope 键的精确匹配。
     */
    private String originScopeKey = "";

    public SqlHistoryEntry() {
    }

    /** 全参构造（字段顺序对齐 Kotlin 主构造器，供 Java 调用方按位置传参）。 */
    public SqlHistoryEntry(String id, String sql, String compactSql, long timestamp, String sourceFile,
                           int sourceLine, String tableName, boolean favorite, List<String> tags) {
        this.id = id != null ? id : "";
        this.sql = sql != null ? sql : "";
        this.compactSql = compactSql != null ? compactSql : "";
        this.timestamp = timestamp;
        this.sourceFile = sourceFile != null ? sourceFile : "";
        this.sourceLine = sourceLine;
        this.tableName = tableName != null ? tableName : "";
        this.favorite = favorite;
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null ? id : "";
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql != null ? sql : "";
    }

    public String getCompactSql() {
        return compactSql;
    }

    public void setCompactSql(String compactSql) {
        this.compactSql = compactSql != null ? compactSql : "";
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile != null ? sourceFile : "";
    }

    public int getSourceLine() {
        return sourceLine;
    }

    public void setSourceLine(int sourceLine) {
        this.sourceLine = sourceLine;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName != null ? tableName : "";
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(Long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    /** R15：钉住态（钉住条目在非收藏分区裁剪时跳过；上限由 SqlHistoryManager.MAX_PINNED 把关）。 */
    public boolean isPinned() {
        return pinned;
    }

    public java.util.LinkedHashMap<String, String> getParamSnapshot() {
        return paramSnapshot;
    }

    public void setParamSnapshot(java.util.LinkedHashMap<String, String> paramSnapshot) {
        this.paramSnapshot = paramSnapshot != null ? paramSnapshot : new java.util.LinkedHashMap<>();
    }

    public String getProjectIdentity() {
        return projectIdentity;
    }

    public void setProjectIdentity(String projectIdentity) {
        this.projectIdentity = projectIdentity;
    }

    public List<ExplainPlanSnapshot> getExplainPlans() {
        return explainPlans;
    }

    public void setExplainPlans(List<ExplainPlanSnapshot> explainPlans) {
        this.explainPlans = explainPlans != null ? explainPlans : new ArrayList<>();
    }

    public String getOriginScopeKey() {
        return originScopeKey;
    }

    public void setOriginScopeKey(String originScopeKey) {
        this.originScopeKey = originScopeKey != null ? originScopeKey : "";
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    /** 分配唯一 id（仅当当前为空白时）。 */
    public void ensureId() {
        if (getId().isBlank()) {
            setId(UUID.randomUUID().toString());
        }
    }

    /** 摘要文本：取 compactSql 首行，截断至 [MAX_SUMMARY_LEN] 字符。 */
    public String summary() {
        String trimmed = getCompactSql().trim();
        String firstLine = trimmed.isEmpty() ? "" : trimmed.split("\n", -1)[0].trim();
        if (firstLine.length() <= MAX_SUMMARY_LEN) {
            return firstLine;
        }
        return firstLine.substring(0, MAX_SUMMARY_LEN) + "...";
    }

    /** 来源描述文案（如 "UserMapper.java:10"）。 */
    public String sourceLabel() {
        String sf = getSourceFile();
        if (sf.isBlank()) {
            return "";
        }
        return getSourceLine() > 0 ? sf + ":" + getSourceLine() : sf;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SqlHistoryEntry)) {
            return false;
        }
        SqlHistoryEntry that = (SqlHistoryEntry) o;
        return timestamp == that.timestamp && sourceLine == that.sourceLine && favorite == that.favorite
                && pinned == that.pinned
                && Objects.equals(id, that.id) && Objects.equals(sql, that.sql)
                && Objects.equals(compactSql, that.compactSql) && Objects.equals(sourceFile, that.sourceFile)
                && Objects.equals(tableName, that.tableName) && Objects.equals(tags, that.tags)
                && Objects.equals(executionTimeMs, that.executionTimeMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sql, compactSql, timestamp, sourceFile, sourceLine, tableName, favorite, tags, executionTimeMs, pinned);
    }

    @Override
    public String toString() {
        return "SqlHistoryEntry(id=" + id + ", sql=" + sql + ", compactSql=" + compactSql
                + ", timestamp=" + timestamp + ", sourceFile=" + sourceFile + ", sourceLine=" + sourceLine
                + ", tableName=" + tableName + ", favorite=" + favorite + ", tags=" + tags + ", executionTimeMs=" + executionTimeMs
                + ", pinned=" + pinned + ")";
    }
}
