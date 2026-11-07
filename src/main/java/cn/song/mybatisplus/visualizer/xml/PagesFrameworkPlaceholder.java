package cn.song.mybatisplus.visualizer.xml;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 框架占位符 {@code #{...}} 预处理（T-PagesHql）。
 *
 * <p>{@code <datasource>} 的 HQL 里会出现 {@code #{SESSION_BASE_ORGAN}} 这类<b>框架层</b>占位符——框架在把
 * HQL 交给 Hibernate 之前做值替换，Hibernate 本身不认识 {@code #{}}。预览必须在转换前把它改写成
 * HQL 命名参数 {@code :name}，才能复用 HqlConverter 的命名参数链（{@code :name} → {@code ?} + 名称序）。</p>
 *
 * <p>规则：</p>
 * <ul>
 * <li>单引号字面量内不替换（{@code '#{x}'} 是业务文本）；</li>
 * <li>{@code #{}} 空表达式原样保留（不产生参数）；</li>
 * <li>合法标识符直接沿用原名；含点/空格等非法字符时用 {@code __p_N} 占位，展示名保留原文。</li>
 * </ul>
 *
 * <p>master 顶层 {@code object} + 顶层 {@code data class PagesPlaceholderRewrite}；k2j 承载为
 * 静态门面 + 嵌套 record（A2 调用点已按 {@code PagesFrameworkPlaceholder.PagesPlaceholderRewrite}
 * 与 {@code PagesFrameworkPlaceholder.rewrite(...)} 写死）。</p>
 */
public final class PagesFrameworkPlaceholder {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private PagesFrameworkPlaceholder() {
    }

    /**
     * {@code #{}} 框架占位符改写结果。
     *
     * @param text         改写后文本（{@code #{x}} → {@code :x}；非法标识符 → {@code :__p_N}）
     * @param displayNames 参数名 → 面板展示名（合法标识符时为同一字符串；{@code a.b.c} 这类保留原名）
     */
    public record PagesPlaceholderRewrite(@NotNull String text, @NotNull Map<String, String> displayNames) {
    }

    @NotNull
    public static PagesPlaceholderRewrite rewrite(@NotNull String sql) {
        if (!sql.contains("#{")) return new PagesPlaceholderRewrite(sql, Map.of());
        StringBuilder out = new StringBuilder(sql.length());
        LinkedHashMap<String, String> displayNames = new LinkedHashMap<>();
        int i = 0;
        boolean inQuote = false;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inQuote) {
                out.append(c);
                if (c == '\'') {
                    // SQL 单引号转义：'' 视为字面量内的一个引号
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i += 2;
                        continue;
                    }
                    inQuote = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inQuote = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '#' && i + 1 < sql.length() && sql.charAt(i + 1) == '{') {
                int close = sql.indexOf('}', i + 2);
                if (close > i + 2) {
                    String raw = sql.substring(i + 2, close).trim();
                    if (!raw.isEmpty()) {
                        String paramName = VALID_NAME.matcher(raw).matches() ? raw : nextSyntheticName(displayNames);
                        displayNames.put(paramName, raw);
                        out.append(':').append(paramName);
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return new PagesPlaceholderRewrite(out.toString(), displayNames);
    }

    @NotNull
    private static String nextSyntheticName(@NotNull Map<String, String> used) {
        int seq = used.size() + 1;
        while (used.containsKey("__p_" + seq)) seq++;
        return "__p_" + seq;
    }
}
