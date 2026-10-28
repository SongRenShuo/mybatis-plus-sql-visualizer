package cn.song.mybatisplus.visualizer.ui.diff;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * T-Perf-24 死代码标注：本对象当前<strong>无任何调用方</strong>（diff 入口未接线）。
 *
 * <p>SQL 行级 diff 引擎（O-4 Phase B）。
 *
 * <p>对比两次预览的 SQL 文本，输出行级差异列表（新增 / 删除 / 相同）。
 * 算法：基于最长公共子序列（LCS）的动态规划，时间复杂度 O(M*N)，
 * 满足 SQL &lt; 1000 行 &lt; 100ms 性能红线。
 */
public final class SqlDiffer {

    /** Kotlin object 单例等价。 */
    public static final SqlDiffer INSTANCE = new SqlDiffer();

    private SqlDiffer() {
    }

    /**
     * F61（T-PerfSweep18）：LCS 行数上限护栏——千行级阈值。
     *
     * <p>DP 表成本 O(M*N)（时间与 int 表内存都随行数平方膨胀）：千行级内是类 Javadoc 既有
     * 性能红线覆盖区（&lt;1000 行 &lt;100ms）；超限输入（外部粘入的超大 SQL/全文对比）走
     * {@link #sideBySideDiff} 逐行并排降级——按下标对齐、同位相等记 EQUAL、否则 DELETE+INSERT
     * 成对，不做全局对齐。<b>computeDiff(old, new) → List&lt;DiffEntry&gt; 契约不变</b>
     * （R31「恰两条可用」历史对比入口与 DiffEntry 形态照旧消费）。master 为 internal
     * object 属性，Java 侧 public static final 对应模块内访问。</p>
     */
    public static final int LCS_LINE_LIMIT = 1000;

    /**
     * 差异类型。
     *
     * <p>刻意不设 CHANGE（行被修改）：差异类型的唯一消费形态是按类型分派的渲染，而 O-4 的自研
     * 高亮渲染器 DiffHighlighter 已按「行内边框无视觉效果」的产品决策整体移除，实际对比入口
     * （SqlPreviewPanel.showSqlDiff）改走 IDEA 原生 Diff，行级 diff 由平台自行计算、不消费本类型，
     * 本对象因此也无任何调用方。既无渲染分派，合并出来的 CHANGE 就无人可读，纯属不可达常量，
     * 故「修改一行」保持 DELETE + INSERT 成对表达（相邻两行的 oldLineNumber/newLineNumber 已能还原改写关系）。
     * 将来若重新接线行级渲染，应连同渲染一并补一次「相邻删增合并」后处理，而不是先加常量。
     */
    public enum DiffType { INSERT, DELETE, EQUAL }

    /** 单条差异记录。 */
    public static final class DiffEntry {
    /** 差异类型 */
    private final DiffType type;
    /** 旧 SQL 中的行号（1-based）；新增行时为 null */
    private final @Nullable Integer oldLineNumber;
    /** 新 SQL 中的行号（1-based）；删除行时为 null */
    private final @Nullable Integer newLineNumber;
    /** 旧行内容（新增行为 null） */
    private final @Nullable String oldLine;
    /** 新行内容（删除行为 null） */
    private final @Nullable String newLine;

    public DiffEntry(DiffType type, @Nullable Integer oldLineNumber, @Nullable Integer newLineNumber, @Nullable String oldLine, @Nullable String newLine) {
        this.type = type;
        this.oldLineNumber = oldLineNumber;
        this.newLineNumber = newLineNumber;
        this.oldLine = oldLine;
        this.newLine = newLine;
    }

    public DiffType getType() { return type; }
    public DiffType type() { return type; }
    public @Nullable Integer getOldLineNumber() { return oldLineNumber; }
    public @Nullable Integer oldLineNumber() { return oldLineNumber; }
    public @Nullable Integer getNewLineNumber() { return newLineNumber; }
    public @Nullable Integer newLineNumber() { return newLineNumber; }
    public @Nullable String getOldLine() { return oldLine; }
    public @Nullable String oldLine() { return oldLine; }
    public @Nullable String getNewLine() { return newLine; }
    public @Nullable String newLine() { return newLine; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiffEntry)) return false;
        DiffEntry that = (DiffEntry) o;
        return java.util.Objects.equals(this.type, that.type) && java.util.Objects.equals(this.oldLineNumber, that.oldLineNumber) && java.util.Objects.equals(this.newLineNumber, that.newLineNumber) && java.util.Objects.equals(this.oldLine, that.oldLine) && java.util.Objects.equals(this.newLine, that.newLine);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(type, oldLineNumber, newLineNumber, oldLine, newLine);
    }

    @Override
    public String toString() {
        return "DiffEntry[" + "type=" + type + ", " + "oldLineNumber=" + oldLineNumber + ", " + "newLineNumber=" + newLineNumber + ", " + "oldLine=" + oldLine + ", " + "newLine=" + newLine + "]";
    }
}

    /**
     * 计算两段 SQL 文本的行级差异。
     *
     * @param oldSql 旧 SQL（null 视为空字符串）
     * @param newSql 新 SQL
     * @return 差异条目列表（按行序）
     */
    @NotNull
    public static List<DiffEntry> computeDiff(@Nullable String oldSql, @NotNull String newSql) {
        List<String> oldLines = (oldSql == null || oldSql.isEmpty()) ? List.of() : Arrays.asList(oldSql.split("\n"));
        List<String> newLines = newSql.isEmpty() ? List.of() : Arrays.asList(newSql.split("\n"));
        // F61：超限降级逐行并排（契约不变，仅内部算法切换）
        if (oldLines.size() > LCS_LINE_LIMIT || newLines.size() > LCS_LINE_LIMIT) {
            return sideBySideDiff(oldLines, newLines);
        }
        // LCS 动态规划表
        int[][] lcs = computeLCS(oldLines, newLines);
        // 回溯构造差异序列（仅返回非 EQUAL 条目，减少下游处理负担）
        return backtrack(lcs, oldLines, newLines).stream()
                .filter(e -> e.type() != DiffType.EQUAL)
                .collect(Collectors.toList());
    }

    /**
     * F61：降级路径——逐行并排（同下标相等跳过，否则 DELETE+INSERT 成对；单侧越界只出存在侧）。
     * 与主路径同口径：EQUAL 不产出（下游只消费差异条目）。
     */
    @NotNull
    private static List<DiffEntry> sideBySideDiff(@NotNull List<String> old, @NotNull List<String> newL) {
        List<DiffEntry> result = new ArrayList<>();
        for (int idx = 0; idx < Math.max(old.size(), newL.size()); idx++) {
            String o = idx < old.size() ? old.get(idx) : null;
            String n = idx < newL.size() ? newL.get(idx) : null;
            if (o != null && n != null && o.equals(n)) continue;
            if (o != null) result.add(new DiffEntry(DiffType.DELETE, idx + 1, null, o, null));
            if (n != null) result.add(new DiffEntry(DiffType.INSERT, null, idx + 1, null, n));
        }
        return result;
    }

    /** 计算 LCS 长度表（动态规划）。 */
    private static int[][] computeLCS(@NotNull List<String> old, @NotNull List<String> newL) {
        int m = old.size();
        int n = newL.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                dp[i][j] = old.get(i - 1).equals(newL.get(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp;
    }

    /** 回溯 LCS 表，构造差异条目列表。 */
    @NotNull
    private static List<DiffEntry> backtrack(int[][] lcs, @NotNull List<String> old, @NotNull List<String> newL) {
        List<DiffEntry> result = new ArrayList<>();
        int i = old.size();
        int j = newL.size();
        // 从右下角回溯到左上角
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && old.get(i - 1).equals(newL.get(j - 1))) {
                // 相等 → EQUAL
                result.add(new DiffEntry(DiffType.EQUAL, i, j, old.get(i - 1), newL.get(j - 1)));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                // 来自左侧 → INSERT（新行新增）
                result.add(new DiffEntry(DiffType.INSERT, null, j, null, newL.get(j - 1)));
                j--;
            } else {
                // 来自上方 → DELETE（旧行删除）
                result.add(new DiffEntry(DiffType.DELETE, i, null, old.get(i - 1), null));
                i--;
            }
        }
        // 回溯是逆序的，翻转
        Collections.reverse(result);
        return result;
    }
}
