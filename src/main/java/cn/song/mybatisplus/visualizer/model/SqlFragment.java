package cn.song.mybatisplus.visualizer.model;

import java.util.List;
import java.util.Objects;

/**
 * SQL 片段文本 + 其中占位符 {@code ?} 的来源（T-UpdateSetSqlConcat）。
 *
 * <p>片段位置（setSql/setApply/apply/exists/last/having/inSql/compareSql/MPJ 字符串 join 等）的文本
 * 是<b>原样拼进语句的 SQL</b>：其中的 {@code ?} 必须按出现顺序登记进参数表，否则面板按位置回填会整体错位
 * ——{@code SET amount = amount * ?} 的 {@code ?} 会被当成后面 WHERE 条件的那一个，用户输入落到错误的占位符上。
 *
 * @param text   片段文本（静态段 + 占位符 {@code ?}）
 * @param params 与 {@code text} 中占位符 {@code ?} 一一对应的来源名（字符串字面量/注释内的 {@code ?} 不计）
 */
public record SqlFragment(String text, List<String> params) {

    /** 无来源信息的纯文本片段（字面量等静态文本）。 */
    public static SqlFragment ofText(String text) {
        return new SqlFragment(text, List.of());
    }

    public SqlFragment {
        params = params == null ? List.of() : List.copyOf(params);
    }

    /** 单参形态（无来源信息，等价 {@link #ofText}）。 */
    public SqlFragment(String text) {
        this(text, List.of());
    }

    /** Kotlin {@code val text} 的 JVM 读取器形态（Kotlin 调用方按属性名读）。 */
    public String getText() {
        return text;
    }

    /** Kotlin {@code val params} 的 JVM 读取器形态。 */
    public List<String> getParams() {
        return params;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SqlFragment that)) {
            return false;
        }
        return Objects.equals(text, that.text) && Objects.equals(params, that.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, params);
    }

    @Override
    public String toString() {
        return "SqlFragment(text=" + text + ", params=" + params + ")";
    }
}
