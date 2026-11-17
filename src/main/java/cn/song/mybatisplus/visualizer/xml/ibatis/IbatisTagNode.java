package cn.song.mybatisplus.visualizer.xml.ibatis;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 动态标签节点。
 *
 * <p>属性一律保留<b>原始字符串</b>（null 表示属性不存在）：引擎用 {@code Properties.getProperty} 取值，
 * {@code prepend=""} 与「无 prepend 属性」语义不同（{@code isPrependAvailable()} = 非 null 且长度 &gt; 0），
 * 故这里不做空串归一。
 */
public final class IbatisTagNode extends IbatisSqlNode {

    private final IbatisTagKind kind;
    @Nullable
    private final String prepend;
    @Nullable
    private final String property;
    @Nullable
    private final String removeFirstPrepend;
    @Nullable
    private final String open;
    @Nullable
    private final String close;
    @Nullable
    private final String conjunction;
    @Nullable
    private final String compareProperty;
    @Nullable
    private final String compareValue;
    private final boolean postParseRequired;
    private final List<IbatisSqlNode> children;

    /**
     * 父标签（引擎 {@code SqlTag.parent}）；根层节点为 null——{@code doPrepend} 的 marker 消费以此为前提。
     *
     * <p>两段式构建（先建子节点、再回填父指针）用 {@link #attachParent}，故不是构造参数。
     */
    @Nullable
    private IbatisTagNode parent;

    public IbatisTagNode(IbatisTagKind kind,
                         @Nullable String prepend,
                         @Nullable String property,
                         @Nullable String removeFirstPrepend,
                         @Nullable String open,
                         @Nullable String close,
                         @Nullable String conjunction,
                         @Nullable String compareProperty,
                         @Nullable String compareValue,
                         boolean postParseRequired,
                         List<IbatisSqlNode> children) {
        this.kind = kind;
        this.prepend = prepend;
        this.property = property;
        this.removeFirstPrepend = removeFirstPrepend;
        this.open = open;
        this.close = close;
        this.conjunction = conjunction;
        this.compareProperty = compareProperty;
        this.compareValue = compareValue;
        this.postParseRequired = postParseRequired;
        this.children = List.copyOf(children);
    }

    /** 脚本构建器的两段式挂载入口（同一包内可见，对应 Kotlin internal）。 */
    void attachParent(@Nullable IbatisTagNode node) {
        this.parent = node;
    }

    @Nullable
    public IbatisTagNode getParent() {
        return parent;
    }

    public IbatisTagKind getKind() {
        return kind;
    }

    @Nullable
    public String getPrepend() {
        return prepend;
    }

    @Nullable
    public String getProperty() {
        return property;
    }

    @Nullable
    public String getRemoveFirstPrepend() {
        return removeFirstPrepend;
    }

    @Nullable
    public String getOpen() {
        return open;
    }

    @Nullable
    public String getClose() {
        return close;
    }

    @Nullable
    public String getConjunction() {
        return conjunction;
    }

    @Nullable
    public String getCompareProperty() {
        return compareProperty;
    }

    @Nullable
    public String getCompareValue() {
        return compareValue;
    }

    public boolean isPostParseRequired() {
        return postParseRequired;
    }

    public List<IbatisSqlNode> getChildren() {
        return children;
    }

    /** 引擎 {@code isPrependAvailable()}：非 null 且非空。 */
    public boolean hasPrepend() {
        return prepend != null && !prepend.isEmpty();
    }

    public boolean hasOpen() {
        return open != null && !open.isEmpty();
    }

    public boolean hasClose() {
        return close != null && !close.isEmpty();
    }

    public boolean hasConjunction() {
        return conjunction != null && !conjunction.isEmpty();
    }
}
