package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 文本节点（{@code #PCDATA} / CDATA）。
 *
 * <p><b>空白归一化是引擎行为，不是我们的美化</b>：{@code SqlText.setText} 把 {@code \r}/{@code \n}/{@code \t}
 * 一律替换为空格（{@code \t} 只在 {@code parameterMap} 分支的静态语句里被保留——见
 * {@link SqlMapSqlRenderer} 的静态路径）。{@link #isWhitespace()} 与引擎同口径＝按 Java
 * {@code String.trim()}（≤ {@code ' '}）判空。
 *
 * <p>postParseRequired 为 true 表示本节点的 {@code #}/{@code $} <b>不在配置期解析</b>，留给 iterate
 * 每次迭代重算（引擎 {@code XMLSqlSource.parseDynamicTags} 的 postParseRequired 形参 +
 * {@code IterateTagHandler.isPostParseRequired}）。
 *
 * @param rawText           DOM 取到的原始文本（保留换行/制表符，静态路径的 parameterMap 分支要用）
 * @param postParseRequired 是否延迟到 iterate 运行期解析
 * @param parsedText        非 postParse 节点在<b>配置期</b>完成 {@code #} 解析后的文本（{@code ?} 形态）；postParse 节点为 null
 * @param mappings          配置期解析出的内联参数（postParse 节点恒为空，运行时逐次重算）
 */
public final class IbatisTextNode extends IbatisSqlNode {

    private final String rawText;
    private final boolean postParseRequired;
    @Nullable
    private final String parsedText;
    private final List<IbatisParamMapping> mappings;

    public IbatisTextNode(String rawText, boolean postParseRequired) {
        this(rawText, postParseRequired, null, List.of());
    }

    public IbatisTextNode(String rawText, boolean postParseRequired, @Nullable String parsedText) {
        this(rawText, postParseRequired, parsedText, List.of());
    }

    public IbatisTextNode(String rawText, boolean postParseRequired,
                          @Nullable String parsedText, List<IbatisParamMapping> mappings) {
        this.rawText = rawText;
        this.postParseRequired = postParseRequired;
        this.parsedText = parsedText;
        this.mappings = List.copyOf(mappings);
    }

    public String getRawText() {
        return rawText;
    }

    public boolean isPostParseRequired() {
        return postParseRequired;
    }

    @Nullable
    public String getParsedText() {
        return parsedText;
    }

    public List<IbatisParamMapping> getMappings() {
        return mappings;
    }

    /** 引擎 {@code SqlText.setText} 归一化后的文本（{@code \r} {@code \n} {@code \t} → 空格）。 */
    public String getText() {
        return rawText.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    /** 实际写入 SQL 的文本：非 postParse 用配置期解析产物，postParse 用归一化原文（运行时再解析）。 */
    public String getRenderText() {
        return parsedText != null ? parsedText : getText();
    }

    /** 引擎 {@code SqlText.isWhiteSpace}（按写入文本的 Java {@code trim()} 判空）。 */
    public boolean isWhitespace() {
        String renderText = getRenderText();
        int len = renderText.length();
        int start = 0;
        while (start < len && renderText.charAt(start) <= ' ') {
            start++;
        }
        while (start < len && renderText.charAt(len - 1) <= ' ') {
            len--;
        }
        return start >= len;
    }
}
